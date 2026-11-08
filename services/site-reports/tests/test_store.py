"""Tests for site_reports.store: write order, idempotency, transitions, retention, files."""

from __future__ import annotations

import errno
import fcntl
import os
import sqlite3
import stat
import threading
import unittest
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock

from site_reports import store as store_module
from site_reports.store import (
    Actor,
    CorruptStore,
    Gone,
    IdConflict,
    IncompatibleDatabase,
    InvalidTransition,
    Limits,
    NotFound,
    PurgedConflict,
    StatusConflict,
    StorageFull,
    Store,
)
from site_reports.validation import ParsedReport, Status, StatusChange
from tests import support

PNG = support.png(3, 2)
LATER = timedelta(milliseconds=1)
SETTABLE = (Status.OPEN, Status.CLAIMED, Status.DONE, Status.REJECTED)
ALLOWED = {
    (Status.OPEN, Status.CLAIMED),
    (Status.OPEN, Status.DONE),
    (Status.OPEN, Status.REJECTED),
    (Status.CLAIMED, Status.OPEN),
    (Status.CLAIMED, Status.DONE),
    (Status.CLAIMED, Status.REJECTED),
    (Status.DONE, Status.OPEN),
    (Status.REJECTED, Status.OPEN),
}


def change(status: Status, expect: Status | None = None, note: str | None = None) -> StatusChange:
    return StatusChange(status=status, expect=expect, note=note)


class StoreTestCase(unittest.TestCase):
    limits = Limits()

    def setUp(self) -> None:
        self.root = support.temporary_directory(self)
        self.data = self.root / "data"
        self.clock = support.FakeClock()
        self.store = support.open_store(self, self.data, self.clock, self.limits)

    def add(self, **overrides: object) -> ParsedReport:
        report = support.report(**overrides)
        _row, created = self.store.add(report)
        self.assertTrue(created)
        return report

    def add_without_screenshot(self) -> ParsedReport:
        report = support.parse(support.without_screenshot(support.request()))
        self.store.add(report)
        return report

    def files(self, directory: str) -> set[str]:
        return {entry.name for entry in (self.data / directory).iterdir()}

    def screenshot_path(self, report: ParsedReport) -> Path:
        return self.data / "screenshots" / f"{report.id}.png"

    def query(self, sql: str, *parameters: object) -> list[tuple]:
        connection = sqlite3.connect(self.data / "reports.sqlite3")
        try:
            return connection.execute(sql, parameters).fetchall()
        finally:
            connection.close()

    def execute(self, sql: str) -> None:
        connection = sqlite3.connect(self.data / "reports.sqlite3", isolation_level=None)
        try:
            connection.execute(sql)
        finally:
            connection.close()

    def history(self, report: ParsedReport) -> list[tuple[Status, Actor, str | None]]:
        return [(entry.status, entry.actor, entry.note) for entry in self.store.history(report.id)]


class SchemaTest(StoreTestCase):
    def test_a_new_store_creates_a_private_wal_database_at_version_1(self) -> None:
        self.assertEqual(self.query("PRAGMA user_version"), [(1,)])
        self.assertEqual(self.query("PRAGMA journal_mode"), [("wal",)])
        for directory in (self.data, self.data / "screenshots", self.data / "tmp"):
            with self.subTest(directory=directory.name):
                self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
        for name in ("reports.sqlite3", "writer.lock"):
            with self.subTest(name=name):
                self.assertEqual(stat.S_IMODE((self.data / name).stat().st_mode), 0o600)

    def test_new_database_directory_entry_is_synced(self) -> None:
        data = self.root / "durable"
        with mock.patch.object(store_module, "_fsync_directory", wraps=store_module._fsync_directory) as sync:
            support.open_store(self, data, self.clock)
        sync.assert_called_once_with(data)

    def test_unsupported_directory_sync_keeps_the_store_usable(self) -> None:
        data = self.root / "unsupported"
        with mock.patch.object(store_module, "_fsync_directory", side_effect=OSError(errno.EINVAL, "synthetic")) as sync:
            store = support.open_store(self, data, self.clock)
        sync.assert_called_once_with(data)
        row, created = store.add(support.parse(support.without_screenshot(support.request())))
        self.assertTrue(created)
        self.assertEqual(store.get(row.id).id, row.id)

    def test_reopening_keeps_the_reports(self) -> None:
        report = self.add()
        self.store.close()

        reopened = support.open_store(self, self.data, self.clock)

        self.assertEqual(reopened.get(report.id).report, report.metadata)

    def test_a_newer_schema_is_refused(self) -> None:
        self.execute("PRAGMA user_version = 2")

        with self.assertRaises(IncompatibleDatabase):
            Store(self.data, self.clock, Limits())

    def test_the_clock_must_be_aware(self) -> None:
        naive = support.open_store(self, self.root / "naive", lambda: datetime(2026, 11, 4))

        with self.assertRaises(ValueError):
            naive.add(support.report())

    def test_limits_must_be_positive(self) -> None:
        for limits in (
            {"max_storage_bytes": 0},
            {"resolved_retention": timedelta(0)},
            {"tombstone_retention": timedelta(days=-1)},
        ):
            with self.subTest(limits=limits), self.assertRaises(ValueError):
                Limits(**limits)


class AddTest(StoreTestCase):
    def test_add_stores_the_row_the_file_and_the_history(self) -> None:
        report = support.report()

        row, created = self.store.add(report)

        self.assertTrue(created)
        self.assertEqual(row.id, report.id)
        self.assertIs(row.status, Status.OPEN)
        self.assertEqual(row.received_at, "2026-11-04T09:12:45.000Z")
        self.assertEqual(row.updated_at, row.received_at)
        self.assertIsNone(row.resolved_at)
        self.assertFalse(row.purged)
        self.assertEqual(row.report, report.metadata)
        self.assertEqual(
            (row.screenshot_bytes, row.screenshot_sha256), (len(PNG), report.screenshot_sha256)
        )
        self.assertEqual(self.screenshot_path(report).read_bytes(), PNG)
        self.assertEqual(stat.S_IMODE(self.screenshot_path(report).stat().st_mode), 0o600)
        self.assertEqual(self.history(report), [(Status.OPEN, Actor.REPORTER, None)])
        self.assertEqual(self.files("tmp"), set())
        self.assertEqual(self.store.get(report.id), row)

    def test_a_report_without_a_screenshot_has_no_file(self) -> None:
        report = self.add_without_screenshot()

        row = self.store.get(report.id)

        self.assertFalse(row.has_screenshot)
        self.assertIsNone(row.screenshot_bytes)
        self.assertEqual(self.files("screenshots"), set())
        self.assertIsNone(self.store.open_screenshot(report.id))

    def test_a_repeat_with_the_same_content_returns_the_current_row(self) -> None:
        report = self.add()
        claimed = self.store.set_status(report.id, change(Status.CLAIMED), Actor.AGENT)
        self.clock.advance(timedelta(hours=1))

        row, created = self.store.add(report)

        self.assertFalse(created)
        self.assertEqual(row, claimed)
        self.assertEqual(self.query("SELECT COUNT(*) FROM reports"), [(1,)])
        self.assertEqual(len(self.store.history(report.id)), 2)

    def test_a_repeat_with_different_content_conflicts(self) -> None:
        report = self.add()
        other = support.parse(support.request(id=report.id, comment="Different."))

        with self.assertRaises(IdConflict):
            self.store.add(other)

        self.assertEqual(self.store.get(report.id).report, report.metadata)
        self.assertEqual(self.files("screenshots"), {f"{report.id}.png"})

    def test_the_file_is_in_place_before_the_row_exists(self) -> None:
        report = support.report()
        replace = os.replace
        rows_at_rename = []

        def spy(source: str | Path, target: str | Path) -> None:
            replace(source, target)
            rows_at_rename.append(self.query("SELECT COUNT(*) FROM reports")[0][0])
            self.assertEqual(Path(target).read_bytes(), PNG)

        with mock.patch.object(store_module.os, "replace", side_effect=spy):
            self.store.add(report)

        self.assertEqual(rows_at_rename, [0])
        self.assertEqual(self.query("SELECT COUNT(*) FROM reports"), [(1,)])

    def test_a_failed_insert_removes_the_file(self) -> None:
        for table in ("reports", "history"):
            with self.subTest(table=table):
                self.execute(
                    f"CREATE TRIGGER refuse_{table} BEFORE INSERT ON {table}"
                    " BEGIN SELECT RAISE(ABORT, 'injected'); END"
                )
                report = support.report()
                try:
                    with self.assertRaises(sqlite3.IntegrityError):
                        self.store.add(report)
                finally:
                    self.execute(f"DROP TRIGGER refuse_{table}")

                self.assertIsNone(self.store.get(report.id))
                self.assertEqual(self.query("SELECT COUNT(*) FROM reports"), [(0,)])
                self.assertEqual(self.files("screenshots"), set())
                self.assertEqual(self.files("tmp"), set())

        _row, created = self.store.add(support.report())
        self.assertTrue(created)

    def test_staging_never_follows_or_reuses_an_existing_name(self) -> None:
        report = support.report()
        outside = self.root / "outside"
        outside.write_bytes(b"untouched")
        (self.data / "tmp" / f"{report.id}.{'0' * 16}").symlink_to(outside)

        with (
            mock.patch.object(store_module.secrets, "token_hex", return_value="0" * 16),
            self.assertRaises(FileExistsError),
        ):
            self.store.add(report)

        self.assertEqual(outside.read_bytes(), b"untouched")
        self.assertIsNone(self.store.get(report.id))
        self.assertEqual(self.files("screenshots"), set())

    def test_an_orphan_from_a_crash_is_replaced_by_the_next_add(self) -> None:
        report = support.report()
        self.screenshot_path(report).write_bytes(b"left over")

        self.store.add(report)

        self.assertEqual(self.screenshot_path(report).read_bytes(), PNG)

    def test_concurrent_adds_store_every_report_once(self) -> None:
        reports = [support.report() for _ in range(12)]
        repeated = support.report()
        results: list[bool] = []
        errors: list[BaseException] = []

        def work(batch: list[ParsedReport]) -> None:
            try:
                for report in [*batch, repeated]:
                    _row, created = self.store.add(report)
                    if report is repeated:
                        results.append(created)
            except BaseException as error:
                errors.append(error)

        threads = [threading.Thread(target=work, args=(reports[i::4],)) for i in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(errors, [])
        self.assertEqual(sorted(results), [False, False, False, True])
        self.assertEqual(self.query("SELECT COUNT(*) FROM reports"), [(13,)])
        self.assertEqual(len(self.files("screenshots")), 13)

    def test_the_writer_lock_excludes_other_processes(self) -> None:
        fd = os.open(self.data / "writer.lock", os.O_RDWR)
        self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX)
        done = threading.Event()
        thread = threading.Thread(target=lambda: (self.store.add(support.report()), done.set()))

        thread.start()
        blocked = not done.wait(0.3)
        fcntl.flock(fd, fcntl.LOCK_UN)
        thread.join(5)

        self.assertTrue(blocked)
        self.assertTrue(done.is_set())


class StorageBudgetTest(StoreTestCase):
    limits = Limits(max_storage_bytes=2 * len(PNG))

    def test_screenshots_over_the_budget_are_refused(self) -> None:
        first = self.add()
        self.add()
        self.assertEqual(self.store.stored_bytes(), 2 * len(PNG))
        third = support.report()

        with self.assertRaises(StorageFull):
            self.store.add(third)

        self.assertIsNone(self.store.get(third.id))
        self.assertEqual(len(self.files("screenshots")), 2)
        self.assertEqual(self.files("tmp"), set())
        # Repeats and reports without a screenshot still go through.
        self.assertFalse(self.store.add(first)[1])
        self.add_without_screenshot()

        self.store.delete(first.id, Actor.AGENT)

        self.assertEqual(self.store.stored_bytes(), len(PNG))
        self.assertTrue(self.store.add(third)[1])

    def test_purged_screenshots_free_the_budget(self) -> None:
        first = self.add()
        self.add()
        self.store.set_status(first.id, change(Status.DONE), Actor.AGENT)
        self.clock.advance(timedelta(days=31))

        self.store.sweep()

        self.assertEqual(self.store.stored_bytes(), len(PNG))
        self.add()


class TransitionTest(StoreTestCase):
    def report_in(self, status: Status) -> ParsedReport:
        report = self.add()
        if status is not Status.OPEN:
            self.store.set_status(report.id, change(status), Actor.AGENT)
        return report

    def test_the_transition_table(self) -> None:
        for source in SETTABLE:
            for target in SETTABLE:
                with self.subTest(source=source, target=target):
                    report = self.report_in(source)
                    if source == target or (source, target) in ALLOWED:
                        row = self.store.set_status(report.id, change(target), Actor.AGENT)
                        self.assertIs(row.status, target)
                    else:
                        with self.assertRaises(InvalidTransition):
                            self.store.set_status(report.id, change(target), Actor.AGENT)
                        self.assertIs(self.store.get(report.id).status, source)

    def test_resolution_time_and_note_follow_the_status(self) -> None:
        report = self.add()
        self.clock.advance(timedelta(minutes=5))

        claimed = self.store.set_status(
            report.id, change(Status.CLAIMED, note="looking"), Actor.AGENT
        )
        self.assertEqual((claimed.status_note, claimed.resolved_at), ("looking", None))
        self.assertEqual(claimed.updated_at, "2026-11-04T09:17:45.000Z")

        done = self.store.set_status(report.id, change(Status.DONE), Actor.AGENT)
        self.assertEqual((done.status_note, done.resolved_at), (None, done.updated_at))

        self.clock.advance(timedelta(minutes=1))
        reopened = self.store.set_status(report.id, change(Status.OPEN, note="again"), Actor.AGENT)
        self.assertEqual((reopened.status_note, reopened.resolved_at), ("again", None))
        self.assertEqual(
            self.history(report),
            [
                (Status.OPEN, Actor.REPORTER, None),
                (Status.CLAIMED, Actor.AGENT, "looking"),
                (Status.DONE, Actor.AGENT, None),
                (Status.OPEN, Actor.AGENT, "again"),
            ],
        )

    def test_compare_and_set_reports_the_current_status(self) -> None:
        report = self.add()
        self.store.set_status(report.id, change(Status.CLAIMED, expect=Status.OPEN), Actor.AGENT)

        with self.assertRaises(StatusConflict) as caught:
            self.store.set_status(
                report.id, change(Status.CLAIMED, expect=Status.OPEN), Actor.AGENT
            )

        self.assertIs(caught.exception.current, Status.CLAIMED)
        self.assertEqual(caught.exception.code, "status_conflict")
        self.assertEqual(len(self.store.history(report.id)), 2)
        released = self.store.set_status(
            report.id, change(Status.OPEN, expect=Status.CLAIMED), Actor.AGENT
        )
        self.assertIs(released.status, Status.OPEN)

    def test_setting_the_current_status_again(self) -> None:
        report = self.report_in(Status.CLAIMED)
        before = self.store.get(report.id)
        self.clock.advance(timedelta(minutes=1))

        unchanged = self.store.set_status(report.id, change(Status.CLAIMED), Actor.AGENT)
        self.assertEqual(unchanged, before)
        self.assertEqual(len(self.store.history(report.id)), 2)

        noted = self.store.set_status(
            report.id, change(Status.CLAIMED, note="still on it"), Actor.AGENT
        )
        self.assertEqual(noted.status_note, "still on it")
        self.assertNotEqual(noted.updated_at, before.updated_at)
        self.assertEqual(self.history(report)[-1], (Status.CLAIMED, Actor.AGENT, "still on it"))

    def test_unknown_and_deleted_reports(self) -> None:
        with self.assertRaises(NotFound):
            self.store.set_status(str(uuid.uuid4()), change(Status.DONE), Actor.AGENT)

        report = self.add()
        self.store.delete(report.id, Actor.AGENT)

        with self.assertRaises(Gone):
            self.store.set_status(report.id, change(Status.OPEN, expect=Status.DONE), Actor.AGENT)

    def test_deleted_is_not_a_settable_status(self) -> None:
        report = self.add()

        with self.assertRaises(ValueError):
            self.store.set_status(report.id, change(Status.DELETED), Actor.AGENT)


class RetentionTest(StoreTestCase):
    def resolve(self, report: ParsedReport, status: Status = Status.DONE) -> None:
        self.store.set_status(report.id, change(Status.CLAIMED, note="claim note"), Actor.AGENT)
        self.store.set_status(report.id, change(status, note="resolution note"), Actor.AGENT)

    def test_resolved_reports_are_purged_after_30_days(self) -> None:
        done, rejected = self.add(), self.add()
        self.resolve(done)
        self.resolve(rejected, Status.REJECTED)

        self.clock.advance(timedelta(days=30))
        self.assertEqual(self.store.sweep().purged, 0)
        self.clock.advance(LATER)
        result = self.store.sweep()

        self.assertEqual((result.purged, result.expired, result.orphans), (2, 0, 0))
        for report, status in ((done, Status.DONE), (rejected, Status.REJECTED)):
            with self.subTest(status=status):
                row = self.store.get(report.id)
                self.assertIs(row.status, status)
                self.assertTrue(row.purged)
                self.assertIsNone(row.report)
                self.assertFalse(row.has_screenshot)
                self.assertEqual(row.status_note, "resolution note")
                self.assertEqual(row.purged_at, "2026-12-04T09:12:45.001Z")
                self.assertEqual(
                    self.history(report),
                    [
                        (Status.OPEN, Actor.REPORTER, None),
                        (Status.CLAIMED, Actor.AGENT, "claim note"),
                        (status, Actor.AGENT, "resolution note"),
                        (status, Actor.RETENTION, None),
                    ],
                )
        self.assertEqual(self.files("screenshots"), set())
        self.assertEqual(self.store.sweep().purged, 0)

    def test_open_and_claimed_reports_are_never_purged(self) -> None:
        waiting, claimed = self.add(), self.add()
        self.store.set_status(claimed.id, change(Status.CLAIMED), Actor.AGENT)
        self.clock.advance(timedelta(days=800))

        result = self.store.sweep()

        self.assertEqual((result.purged, result.expired), (0, 0))
        self.assertEqual(self.store.get(waiting.id).report, waiting.metadata)
        self.assertEqual(self.store.get(claimed.id).report, claimed.metadata)
        self.assertEqual(len(self.files("screenshots")), 2)

    def test_a_reopened_report_restarts_its_retention(self) -> None:
        report = self.add()
        self.resolve(report)
        self.clock.advance(timedelta(days=20))
        self.store.set_status(report.id, change(Status.OPEN), Actor.AGENT)
        self.store.set_status(report.id, change(Status.DONE), Actor.AGENT)
        self.clock.advance(timedelta(days=20))

        self.assertEqual(self.store.sweep().purged, 0)

    def test_a_purged_report_cannot_be_reopened(self) -> None:
        report = self.add()
        self.resolve(report)
        self.clock.advance(timedelta(days=31))
        self.store.sweep()

        with self.assertRaises(PurgedConflict):
            self.store.set_status(report.id, change(Status.OPEN), Actor.AGENT)
        with self.assertRaises(InvalidTransition):
            self.store.set_status(report.id, change(Status.REJECTED), Actor.AGENT)
        noted = self.store.set_status(report.id, change(Status.DONE, note="later"), Actor.AGENT)
        self.assertEqual(noted.status_note, "later")

    def test_a_phone_retry_of_a_purged_report_is_a_repeat(self) -> None:
        report = self.add()
        self.resolve(report)
        self.clock.advance(timedelta(days=31))
        self.store.sweep()

        row, created = self.store.add(report)

        self.assertFalse(created)
        self.assertIs(row.status, Status.DONE)
        self.assertTrue(row.purged)
        self.assertEqual(self.files("screenshots"), set())

    def test_tombstones_expire_after_365_days(self) -> None:
        resolved, deleted = self.add(), self.add()
        self.resolve(resolved)
        self.clock.advance(timedelta(days=30) + LATER)
        self.store.sweep()  # Purges the resolved report now.
        self.store.delete(deleted.id, Actor.AGENT)

        self.clock.advance(timedelta(days=365))
        self.assertEqual(self.store.sweep().expired, 0)
        self.clock.advance(LATER)
        result = self.store.sweep()

        self.assertEqual(result.expired, 2)
        for report in (resolved, deleted):
            self.assertIsNone(self.store.get(report.id))
            self.assertEqual(self.store.history(report.id), [])
        self.assertEqual(self.query("SELECT COUNT(*) FROM history"), [(0,)])
        # With the tombstone gone, the same id is a new report.
        self.assertTrue(self.store.add(deleted)[1])


class DeleteTest(StoreTestCase):
    def test_delete_purges_now_and_keeps_a_tombstone(self) -> None:
        report = self.add()
        self.store.set_status(report.id, change(Status.CLAIMED, note="private detail"), Actor.AGENT)

        self.assertTrue(self.store.delete(report.id, Actor.AGENT))

        row = self.store.get(report.id)
        self.assertIs(row.status, Status.DELETED)
        self.assertTrue(row.purged)
        self.assertIsNone(row.report)
        self.assertIsNone(row.status_note)
        self.assertFalse(row.has_screenshot)
        self.assertEqual(self.files("screenshots"), set())
        self.assertEqual(
            self.history(report),
            [
                (Status.OPEN, Actor.REPORTER, None),
                (Status.CLAIMED, Actor.AGENT, None),
                (Status.DELETED, Actor.AGENT, None),
            ],
        )
        self.assertEqual(self.query("SELECT report_json, status_note FROM reports"), [(None, None)])
        self.assertIsNone(self.store.open_screenshot(report.id))

    def test_a_late_phone_retry_after_delete_is_a_repeat(self) -> None:
        report = self.add()
        self.store.delete(report.id, Actor.AGENT)

        row, created = self.store.add(report)

        self.assertFalse(created)
        self.assertIs(row.status, Status.DELETED)
        self.assertEqual(self.files("screenshots"), set())
        with self.assertRaises(IdConflict):
            self.store.add(support.parse(support.request(id=report.id, comment="Changed.")))

    def test_delete_is_idempotent_and_false_for_unknown_ids(self) -> None:
        report = self.add()

        self.assertTrue(self.store.delete(report.id, Actor.AGENT))
        self.assertTrue(self.store.delete(report.id, Actor.AGENT))
        self.assertFalse(self.store.delete(str(uuid.uuid4()), Actor.AGENT))
        self.assertEqual(len(self.store.history(report.id)), 2)

    def test_a_repeated_delete_removes_a_screenshot_left_by_a_failed_unlink(self) -> None:
        report = self.add()
        self.assertTrue(self.store.delete(report.id, Actor.AGENT))
        self.screenshot_path(report).write_bytes(PNG)  # As if the unlink had failed.

        self.assertTrue(self.store.delete(report.id, Actor.AGENT))

        self.assertEqual(self.files("screenshots"), set())
        self.assertEqual(len(self.store.history(report.id)), 2)


class SweepFilesTest(StoreTestCase):
    def test_orphans_and_staging_files_are_removed(self) -> None:
        kept = self.add()
        orphan = self.data / "screenshots" / f"{uuid.uuid4()}.png"
        orphan.write_bytes(PNG)
        (self.data / "screenshots" / "notes.txt").write_text("x")
        (self.data / "screenshots" / "directory").mkdir()
        (self.data / "tmp" / f"{uuid.uuid4()}.0123456789abcdef").write_bytes(b"partial")
        (self.data / "tmp" / "stray").write_bytes(b"x")

        result = self.store.sweep()

        self.assertEqual((result.orphans, result.staging), (2, 2))
        self.assertEqual(self.files("screenshots"), {f"{kept.id}.png", "directory"})
        self.assertEqual(self.files("tmp"), set())
        self.assertEqual(self.screenshot_path(kept).read_bytes(), PNG)

    def test_a_file_whose_row_has_no_screenshot_is_an_orphan(self) -> None:
        report = self.add_without_screenshot()
        self.screenshot_path(report).write_bytes(PNG)

        self.assertEqual(self.store.sweep().orphans, 1)
        self.assertEqual(self.files("screenshots"), set())


class ScreenshotTest(StoreTestCase):
    def test_open_screenshot_returns_the_file_and_its_size(self) -> None:
        report = self.add()

        handle, size = self.store.open_screenshot(report.id)
        with handle:
            self.assertEqual(handle.read(), PNG)
        self.assertEqual(size, len(PNG))
        self.assertIsNone(self.store.open_screenshot(str(uuid.uuid4())))

    def test_a_file_removed_meanwhile_reads_as_none(self) -> None:
        report = self.add()
        self.screenshot_path(report).unlink()

        self.assertIsNone(self.store.open_screenshot(report.id))

    def test_a_symbolic_link_out_of_screenshots_is_corrupt(self) -> None:
        report = self.add()
        outside = self.root / "outside.png"
        outside.write_bytes(PNG)
        self.screenshot_path(report).unlink()
        self.screenshot_path(report).symlink_to(outside)

        with self.assertRaises(CorruptStore):
            self.store.open_screenshot(report.id)

    def test_a_symbolic_link_within_screenshots_is_not_followed(self) -> None:
        report = self.add()
        other = self.screenshot_path(report).with_name("other.png")
        self.screenshot_path(report).rename(other)
        self.screenshot_path(report).symlink_to(other.name)

        with self.assertRaises(OSError):
            self.store.open_screenshot(report.id)

    def test_a_screenshot_directory_behind_a_link_is_corrupt(self) -> None:
        report = self.add()
        screenshots = self.data / "screenshots"
        screenshots.rename(self.data / "elsewhere")
        screenshots.symlink_to(self.data / "elsewhere")

        with self.assertRaises(CorruptStore):
            self.store.open_screenshot(report.id)

    def test_a_file_of_the_wrong_size_is_corrupt(self) -> None:
        report = self.add()
        self.screenshot_path(report).write_bytes(PNG + b"\x00")

        with self.assertRaises(CorruptStore):
            self.store.open_screenshot(report.id)


class ListTest(StoreTestCase):
    def test_pages_are_oldest_first_with_a_cursor(self) -> None:
        reports = [self.add() for _ in range(5)]

        first, cursor = self.store.list_reports({Status.OPEN}, 2)
        second, cursor_2 = self.store.list_reports({Status.OPEN}, 2, cursor)
        third, cursor_3 = self.store.list_reports({Status.OPEN}, 2, cursor_2)

        ids = [row.id for row in first + second + third]
        self.assertEqual(ids, [report.id for report in reports])
        self.assertEqual(cursor, first[-1].seq)
        self.assertIsNone(cursor_3)
        self.assertEqual(self.store.list_reports({Status.OPEN}, 5), (first + second + third, None))

    def test_statuses_filter_the_list(self) -> None:
        open_, claimed, done, deleted = (self.add() for _ in range(4))
        self.store.set_status(claimed.id, change(Status.CLAIMED), Actor.AGENT)
        self.store.set_status(done.id, change(Status.DONE), Actor.AGENT)
        self.store.delete(deleted.id, Actor.AGENT)

        def ids(statuses: set[Status]) -> list[str]:
            return [row.id for row in self.store.list_reports(statuses, 100)[0]]

        self.assertEqual(ids({Status.OPEN}), [open_.id])
        self.assertEqual(ids({Status.CLAIMED}), [claimed.id])
        self.assertEqual(ids({Status.DONE, Status.REJECTED}), [done.id])
        self.assertEqual(
            ids({Status.OPEN, Status.CLAIMED, Status.DONE, Status.REJECTED}),
            [open_.id, claimed.id, done.id],
        )

    def test_invalid_arguments(self) -> None:
        with self.assertRaises(ValueError):
            self.store.list_reports({Status.OPEN}, 0)
        with self.assertRaises(ValueError):
            self.store.list_reports(set(), 10)


class BackupTest(StoreTestCase):
    def test_backup_copies_rows_and_screenshots_into_a_new_directory(self) -> None:
        reports = [self.add(), self.add(), self.add_without_screenshot()]
        target = self.root / "backup"

        result = self.store.backup(target)

        self.assertEqual((result.reports, result.screenshots, result.missing), (3, 2, 0))
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((target / "reports.sqlite3").stat().st_mode), 0o600)
        self.assertFalse((target / "reports.sqlite3-wal").exists())
        copy = support.open_store(self, target, self.clock)
        for report in reports:
            self.assertEqual(copy.get(report.id), self.store.get(report.id))
        handle, _size = copy.open_screenshot(reports[0].id)
        with handle:
            self.assertEqual(handle.read(), PNG)

    def test_an_existing_target_is_refused(self) -> None:
        target = self.root / "backup"
        target.mkdir()

        with self.assertRaises(FileExistsError):
            self.store.backup(target)

    def test_a_failed_backup_leaves_no_partial_copy(self) -> None:
        self.add()
        self.add()
        target = self.root / "backup"
        copy_screenshot = store_module.Store._copy_screenshot
        calls = 0

        def fill_disk_on_the_second_copy(store, *args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError(errno.ENOSPC, "No space left on device")
            return copy_screenshot(store, *args)

        with (
            mock.patch.object(store_module.Store, "_copy_screenshot", fill_disk_on_the_second_copy),
            self.assertRaises(OSError),
        ):
            self.store.backup(target)

        self.assertFalse(target.exists())
        self.assertEqual(self.store.backup(target).screenshots, 2)

    def test_a_missing_screenshot_is_cleared_in_the_copy(self) -> None:
        report = self.add()
        self.screenshot_path(report).unlink()

        result = self.store.backup(self.root / "backup")

        self.assertEqual((result.screenshots, result.missing), (0, 1))
        copy = support.open_store(self, self.root / "backup", self.clock)
        self.assertFalse(copy.get(report.id).has_screenshot)
        self.assertEqual(copy.get(report.id).report, report.metadata)


class HealthTest(StoreTestCase):
    def test_a_working_store_is_healthy(self) -> None:
        self.assertTrue(self.store.is_healthy())

    @unittest.skipIf(os.geteuid() == 0, "root can write to any directory")
    def test_a_read_only_staging_directory_is_unhealthy(self) -> None:
        staging = self.data / "tmp"
        staging.chmod(0o500)
        self.addCleanup(staging.chmod, 0o700)

        self.assertFalse(self.store.is_healthy())


if __name__ == "__main__":
    unittest.main()
