"""Tests for the SQLite store, on a temporary database."""

from __future__ import annotations

import contextlib
import os
import sqlite3
import stat
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sitecheck import store as store_module
from sitecheck.model import VerdictKind
from sitecheck.store import (
    SCHEMA_VERSION,
    DocumentVisit,
    IssueSighting,
    IssueStatus,
    PageClassification,
    PageRow,
    PageSource,
    Store,
    StoreError,
    UpsertCounts,
    open_store,
)

START = datetime(2026, 10, 24, 3, 0, 0, tzinfo=UTC)
A = "https://a.example.org/"
B = "https://b.example.org/"
C = "https://c.example.org/"


class FakeClock:
    def __init__(self, start: datetime = START) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += timedelta(seconds=seconds)


def page(url: str, **overrides: object) -> PageRow:
    host = url.split("/")[2] if "//" in url else ""
    values: dict[str, object] = {
        "url": url,
        "host": host,
        "site": ".".join(host.split(".")[-2:]),
        "template": url,
        "source": PageSource.HISTER,
        "eligible": True,
        "reason": "ok",
        "hister_updated": 1_760_000_000,
        "add_count": 3,
    }
    values.update(overrides)
    return PageRow(**values)  # type: ignore[arg-type]


class StoreTestCase(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        self.path = self.dir / "state.sqlite3"
        self.clock = FakeClock()
        self.store = self.open()

    def open(self) -> Store:
        store = open_store(self.path, clock=self.clock)
        self.addCleanup(store.close)
        return store

    def run_with_results(self, label: str | None, outcomes: dict[str, str]) -> str:
        """Create a finished run in which each URL got the given verdict."""
        self.clock.advance(60)
        run_id = self.store.create_run(label=label)
        for url, verdict in outcomes.items():
            severity = 0 if verdict == "ok" else 3
            self.store.record_result(run_id, url, 1, verdict, severity, [], {}, None, 100)
        self.store.finish_run(run_id)
        return run_id


class SchemaTests(StoreTestCase):
    def test_reopening_keeps_schema_and_data(self) -> None:
        self.store.set_meta("hister_last_sync", "1760000000")
        self.store.close()

        reopened = self.open()

        self.assertEqual(reopened.get_meta("hister_last_sync"), "1760000000")
        with contextlib.closing(sqlite3.connect(self.path)) as raw:
            self.assertEqual(raw.execute("PRAGMA user_version").fetchone()[0], SCHEMA_VERSION)
            self.assertEqual(raw.execute("PRAGMA journal_mode").fetchone()[0], "wal")
            tables = {row[0] for row in raw.execute("SELECT name FROM sqlite_master")}
        self.assertTrue({"pages", "meta", "runs", "results", "issues", "issue_pages"} <= tables)

    def test_newer_schema_is_refused(self) -> None:
        self.store.close()
        with contextlib.closing(sqlite3.connect(self.path)) as raw:
            raw.execute(f"PRAGMA user_version = {SCHEMA_VERSION + 1}")

        with self.assertRaisesRegex(StoreError, "newer"):
            open_store(self.path)

    def test_files_are_owner_only(self) -> None:
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

        self.store.set_meta("k", "v")  # Leaves a WAL file while the store is open.
        wal = Path(f"{self.path}-wal")
        self.assertTrue(wal.exists())
        self.assertEqual(stat.S_IMODE(wal.stat().st_mode), 0o600)

    def test_existing_file_is_tightened(self) -> None:
        self.store.close()
        os.chmod(self.path, 0o644)

        self.open()

        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_missing_directory_is_an_error(self) -> None:
        with self.assertRaises(StoreError):
            open_store(self.dir / "missing" / "state.sqlite3")

    def test_a_full_database_reports_its_own_error_and_stays_usable(self) -> None:
        # SQLite rolls the whole transaction back itself on some errors, such as a full
        # disk; the store must report that error, not its own failed ROLLBACK.
        connection = self.store._connection
        (pages,) = connection.execute("PRAGMA page_count").fetchone()
        connection.execute(f"PRAGMA max_page_count = {pages}")
        rows = [page(f"https://p{i}.example.org/{'x' * 500}") for i in range(200)]

        with self.assertRaisesRegex(sqlite3.OperationalError, "full"):
            self.store.upsert_pages(rows)

        connection.execute(f"PRAGMA max_page_count = {2**30}")
        self.assertEqual(self.store.upsert_pages([page(A)]), UpsertCounts(new=1, updated=0))
        self.assertEqual([row["url"] for row in self.store.selection_rows()], [A])

    def test_foreign_keys_are_enforced(self) -> None:
        with self.assertRaisesRegex(StoreError, "No run"):
            self.store.record_result("no-such-run", A, 1, "ok", 0, [], {}, None, 1)


class _CommitFails:
    """A connection whose COMMIT fails while leaving the transaction open, as
    SQLITE_BUSY would; it records the statements it was given."""

    def __init__(self) -> None:
        self.statements: list[str] = []
        self.in_transaction = False

    def execute(self, statement: str) -> None:
        self.statements.append(statement)
        if statement.startswith("BEGIN"):
            self.in_transaction = True
        elif statement == "COMMIT":
            raise sqlite3.OperationalError("database is locked")
        elif statement == "ROLLBACK":
            self.in_transaction = False


class WriteTransactionTests(unittest.TestCase):
    def test_a_failed_commit_rolls_back_and_keeps_its_error(self) -> None:
        connection = _CommitFails()
        with self.assertRaisesRegex(sqlite3.OperationalError, "database is locked"):
            with store_module._write_transaction(connection):  # type: ignore[arg-type]
                pass
        self.assertEqual(connection.statements, ["BEGIN IMMEDIATE", "COMMIT", "ROLLBACK"])
        self.assertFalse(connection.in_transaction)


class PageTests(StoreTestCase):
    def test_upsert_counts_new_and_updated(self) -> None:
        first = self.store.upsert_pages([page(A), page(B)])
        second = self.store.upsert_pages([page(A), page(C), page(C)])

        self.assertEqual(first, UpsertCounts(new=2, updated=0))
        self.assertEqual(second, UpsertCounts(new=1, updated=2))
        self.assertEqual(len(self.store.selection_rows()), 3)

    def test_reclassification_and_sticky_history_fields(self) -> None:
        self.store.upsert_pages([page("https://a.example.org/", add_count=7)])
        self.store.upsert_pages(
            [page("https://a.example.org/", source="seed", hister_updated=None, add_count=None)]
        )

        (selected,) = self.store.selection_rows()
        self.assertEqual(selected["source"], "hister")
        self.assertEqual(selected["add_count"], 7)
        self.assertEqual(selected["hister_updated"], 1_760_000_000)

        self.store.upsert_pages([page(A, eligible=False, reason="sensitive")])
        self.assertEqual(self.store.selection_rows(), [])

    def test_invalid_row_writes_nothing(self) -> None:
        rows = [page(A), page(B, source="bogus")]

        with self.assertRaises(ValueError):
            self.store.upsert_pages(rows)
        with self.assertRaises(ValueError):
            self.store.upsert_pages([{"url": A, "title": "no"}])  # type: ignore[list-item]
        with self.assertRaises(ValueError):
            self.store.upsert_pages([page(A, add_count=2**63)])  # Beyond SQLite INTEGER.
        self.assertEqual(self.store.selection_rows(), [])

    def test_hosts_sites_and_urls_include_every_page(self) -> None:
        self.store.upsert_pages(
            [
                page("https://a.example.org/"),
                page("https://login.example.net/x", eligible=False, reason="sensitive"),
                page("mailto:someone", host="", site="", eligible=False, reason="scheme"),
            ]
        )
        run_id = self.store.create_run()
        adhoc = "https://adhoc.example.com/"
        self.store.record_result(run_id, adhoc, 1, "ok", 0, [], None, None, 1)

        names = self.store.hosts_and_sites()
        hosts, sites = names

        self.assertEqual(hosts, {"a.example.org", "login.example.net"})
        self.assertEqual(sites, {"example.org", "example.net"})
        self.assertIn(adhoc, self.store.known_urls())
        self.assertIn("https://login.example.net/x", self.store.known_urls())

    def test_meta(self) -> None:
        self.assertIsNone(self.store.get_meta("missing"))
        self.store.set_meta("hister_last_sync", "1")
        self.store.set_meta("hister_last_sync", "2")
        self.assertEqual(self.store.get_meta("hister_last_sync"), "2")
        with self.assertRaises(ValueError):
            self.store.set_meta("k", 3)  # type: ignore[arg-type]


class VisitTests(StoreTestCase):
    """Visit counts are kept per history document, so a partial sync cannot shrink them."""

    def test_counts_add_up_per_document_across_syncs(self) -> None:
        self.store.upsert_pages([page(A, add_count=None, hister_updated=None)])

        self.store.record_visits(
            [
                DocumentVisit("doc-1", A, 5, 1_760_000_100),
                DocumentVisit("doc-2", A, 3, 1_760_000_200),
            ]
        )
        (row,) = self.store.selection_rows()
        self.assertEqual((row["add_count"], row["hister_updated"]), (8, 1_760_000_200))

        # An incremental sync rereads only the recent document.
        self.store.record_visits([DocumentVisit("doc-2", A, 4, 1_760_000_300)])
        (row,) = self.store.selection_rows()
        self.assertEqual((row["add_count"], row["hister_updated"]), (9, 1_760_000_300))

    def test_a_document_that_moves_to_another_page_leaves_the_old_one(self) -> None:
        self.store.upsert_pages([page(A), page(B)])
        self.store.record_visits([DocumentVisit("doc-1", A, 5, 1), DocumentVisit("doc-2", A, 2, 1)])

        self.store.record_visits([DocumentVisit("doc-1", B, 5, 1)])

        counts = {row["url"]: row["add_count"] for row in self.store.selection_rows()}
        self.assertEqual(counts, {A: 2, B: 5})


class ClassificationTests(StoreTestCase):
    def test_reclassified_pages_leave_and_rejoin_the_selection(self) -> None:
        self.store.upsert_pages([page(A), page(B)])

        self.store.reclassify_pages([PageClassification(A, False, "denied", None)])
        self.assertEqual([row["url"] for row in self.store.selection_rows()], [B])
        self.assertEqual(
            {(c.url, c.eligible, c.reason) for c in self.store.page_classifications()},
            {(A, False, "denied"), (B, True, "ok")},
        )

        self.store.reclassify_pages([PageClassification(A, True, "ok", "https://a.example.org/{}")])
        rows = {row["url"]: row for row in self.store.selection_rows()}
        self.assertEqual(rows[A]["template"], "https://a.example.org/{}")

    def test_a_load_exclusion_outlives_later_imports(self) -> None:
        self.store.upsert_pages([page(A), page(B)])

        self.store.exclude_after_load(A, "navigated to a sensitive URL")
        self.store.upsert_pages([page(A)])  # A later sync sees the page again.

        self.assertEqual([row["url"] for row in self.store.selection_rows()], [B])

    def test_page_counts_by_source(self) -> None:
        self.store.upsert_pages([page(A), page(B, source="seed"), page(C, source="seed")])
        self.assertEqual(self.store.page_count(PageSource.HISTER), 1)
        self.assertEqual(self.store.page_count(PageSource.SEED), 2)
        self.assertEqual(self.store.page_count(PageSource.MANUAL), 0)


class DerivedHostTests(StoreTestCase):
    def test_derived_hosts_feed_the_leak_check_but_not_the_selection(self) -> None:
        self.store.add_derived_hosts(["static.acme-cdn.net", "www.acme-group.com"])
        self.store.add_derived_hosts(["static.acme-cdn.net"])

        self.assertEqual(
            self.store.derived_hosts(), frozenset({"static.acme-cdn.net", "www.acme-group.com"})
        )
        self.assertEqual(self.store.selection_rows(), [])


class MigrationTests(StoreTestCase):
    def test_a_version_1_store_is_upgraded_and_syncs_in_full_next(self) -> None:
        self.store.close()
        self.path.unlink()
        with contextlib.closing(sqlite3.connect(self.path)) as raw:
            raw.executescript(store_module._SCHEMA_V1)
            raw.execute("PRAGMA user_version = 1")
            raw.execute("INSERT INTO meta (key, value) VALUES ('hister_last_sync', '1760000000')")
            raw.execute(
                "INSERT INTO pages (url, host, site, template, source, eligible, reason, "
                "add_count, first_seen, last_seen) VALUES (?, 'a.example.org', 'example.org', "
                "NULL, 'hister', 1, 'ok', 4, 'x', 'x')",
                (A,),
            )
            raw.commit()

        upgraded = self.open()

        self.assertEqual([row["url"] for row in upgraded.selection_rows()], [A])
        # Visit counts are now kept per document; a full sync rebuilds them.
        self.assertIsNone(upgraded.get_meta("hister_last_sync"))
        upgraded.add_derived_hosts(["cdn.example-cdn.net"])
        upgraded.record_visits([DocumentVisit("doc-1", A, 2, None)])


class RetentionTests(StoreTestCase):
    def test_old_runs_are_prunable_except_the_latest_per_label_and_open_issue_origins(self) -> None:
        a = "https://a.example.org/"
        old_nightly = self.run_with_results("nightly", {a: "ok"})
        issue_origin = self.run_with_results("nightly", {a: "crash"})
        self.store.upsert_issues(issue_origin, [IssueSighting("crash:x.rs:1", "crash", a)])
        old_adhoc = self.run_with_results(None, {a: "ok"})
        latest_nightly = self.run_with_results("nightly", {a: "ok"})
        latest_adhoc = self.run_with_results(None, {a: "ok"})

        prunable = self.store.runs_to_prune(self.clock.now + timedelta(days=1))

        self.assertEqual(set(prunable), {old_nightly, old_adhoc})
        self.assertNotIn(latest_nightly, prunable)
        self.assertNotIn(latest_adhoc, prunable)
        self.assertEqual(self.store.runs_to_prune(START), [])


class RunTests(StoreTestCase):
    def test_run_lifecycle(self) -> None:
        run_id = self.store.create_run(
            "nightly", "abc123", Path("/servo/target/medium/servoshell"), "chromium", "first"
        )

        self.assertRegex(run_id, r"^20261024T030000Z-[0-9a-f]{4}$")
        run = self.store.get_run(run_id)
        assert run is not None
        self.assertEqual(run["started"], "2026-10-24T03:00:00Z")
        self.assertIsNone(run["finished"])
        self.assertEqual(run["servo_binary"], "/servo/target/medium/servoshell")
        self.assertEqual(run["label"], "nightly")

        self.clock.advance(90)
        self.store.finish_run(run_id)

        run = self.store.get_run(run_id)
        assert run is not None
        self.assertEqual(run["finished"], "2026-10-24T03:01:30Z")
        with self.assertRaisesRegex(StoreError, "already finished"):
            self.store.finish_run(run_id)
        with self.assertRaisesRegex(StoreError, "No run"):
            self.store.finish_run("20261024T030000Z-0000")
        self.assertIsNone(self.store.get_run("20261024T030000Z-0000"))

    def test_latest_and_previous_runs(self) -> None:
        self.assertIsNone(self.store.latest_run_id())

        nightly_1 = self.store.create_run("nightly")
        adhoc = self.store.create_run("adhoc")  # Same second: ordered by insertion.
        unlabeled = self.store.create_run()
        self.clock.advance(1)
        nightly_2 = self.store.create_run("nightly")

        self.assertEqual(self.store.latest_run_id(), nightly_2)
        self.assertEqual(self.store.latest_run_id("adhoc"), adhoc)
        self.assertIsNone(self.store.latest_run_id("weekly"))
        self.assertEqual(self.store.previous_run_id(nightly_2), nightly_1)
        self.assertIsNone(self.store.previous_run_id(nightly_1))
        self.assertIsNone(self.store.previous_run_id(adhoc))
        self.assertIsNone(self.store.previous_run_id(unlabeled))
        self.clock.advance(1)
        self.assertEqual(self.store.previous_run_id(self.store.create_run()), unlabeled)
        with self.assertRaises(StoreError):
            self.store.previous_run_id("missing")


class ResultTests(StoreTestCase):
    def test_results_round_trip(self) -> None:
        run_id = self.store.create_run("nightly")
        metrics = {"textLength": 1200, "bodyRect": {"x": 0, "width": 412.5}, "flaky": True}

        self.store.record_result(
            run_id,
            "https://a.example.org/",
            1,
            VerdictKind.CRASH,
            5,
            ("crash:components/layout/flow.rs:12", "js:x is not defined"),
            metrics,
            Path("pages/0123456789abcdef"),
            4321,
        )
        self.store.record_result(run_id, A, 2, "flaky", 1, [], None, None, 10)
        self.store.record_result(run_id, B, 1, "ok", 0, [], {}, None, 10)

        results = self.store.results_for_run(run_id)
        self.assertEqual(
            [(r["url"], r["attempt"]) for r in results],
            [(A, 1), (A, 2), (B, 1)],
        )
        first = results[0]
        self.assertEqual(first["verdict"], "crash")
        self.assertEqual(first["severity"], 5)
        self.assertEqual(
            first["signatures"], ["crash:components/layout/flow.rs:12", "js:x is not defined"]
        )
        self.assertEqual(first["metrics"], metrics)
        self.assertEqual(first["artifacts"], "pages/0123456789abcdef")
        self.assertEqual(first["duration_ms"], 4321)
        self.assertEqual(first["recorded"], "2026-10-24T03:00:00Z")
        self.assertIsNone(results[1]["metrics"])

        outcomes = self.store.outcomes_for_run(run_id)
        self.assertEqual(outcomes[A]["attempt"], 2)
        self.assertEqual(outcomes[A]["verdict"], "flaky")

    def test_signatures_from_an_iterator_are_stored(self) -> None:
        # Validation must not use up a one-shot iterable before it is stored.
        run_id = self.store.create_run()
        signatures = (signature for signature in ("crash:x", "js:y"))

        self.store.record_result(run_id, A, 1, "crash", 5, signatures, None, None, 1)

        (result,) = self.store.results_for_run(run_id)
        self.assertEqual(result["signatures"], ["crash:x", "js:y"])

    def test_invalid_results_are_refused(self) -> None:
        run_id = self.store.create_run()
        url = "https://a.example.org/"
        self.store.record_result(run_id, url, 1, "ok", 0, [], {}, None, 1)

        with self.assertRaisesRegex(StoreError, "already has attempt 1"):
            self.store.record_result(run_id, url, 1, "ok", 0, [], {}, None, 1)
        invalid_arguments = [
            dict(verdict="broken"),
            dict(severity=6),
            dict(attempt=0),
            dict(duration_ms=-1),
            dict(signatures="crash"),
            dict(metrics={"ratio": float("nan")}),
        ]
        for overrides in invalid_arguments:
            arguments: dict[str, object] = dict(
                run_id=run_id,
                url=url,
                attempt=2,
                verdict="ok",
                severity=0,
                signatures=[],
                metrics={},
                artifacts=None,
                duration_ms=1,
            )
            arguments.update(overrides)
            with self.subTest(overrides), self.assertRaises(ValueError):
                self.store.record_result(**arguments)  # type: ignore[arg-type]

    def test_unjudged_results_do_not_count_as_checked(self) -> None:
        a = "https://a.example.org/"
        self.store.upsert_pages([page(a)])
        crashed = self.run_with_results("nightly", {a: "crash"})
        unjudged = self.run_with_results("nightly", {a: "reference-failed"})
        self.run_with_results("adhoc", {a: "not-compared"})

        (row,) = self.store.selection_rows()

        # A run without a baseline neither replaces the crash nor counts as a check.
        self.assertEqual((row["last_verdict"], row["last_run_id"]), ("crash", crashed))
        self.assertEqual(row["last_checked"], "2026-10-24T03:01:00Z")
        self.assertEqual(row["last_attempted"], "2026-10-24T03:03:00Z")
        self.assertNotEqual(row["last_run_id"], unjudged)

    def test_only_unjudged_results_leave_the_page_unchecked(self) -> None:
        a = "https://a.example.org/"
        self.store.upsert_pages([page(a)])
        self.run_with_results("nightly", {a: "harness-error"})

        (row,) = self.store.selection_rows()

        self.assertIsNone(row["last_checked"])
        self.assertIsNone(row["last_verdict"])
        self.assertEqual(row["last_attempted"], "2026-10-24T03:01:00Z")

    def test_selection_rows_join_latest_result(self) -> None:
        a, b, hidden = "https://a.example.org/", "https://b.example.org/", "https://c.example.org/"
        self.store.upsert_pages(
            [page(a, add_count=9), page(b), page(hidden, eligible=False, reason="sensitive")]
        )
        self.run_with_results("nightly", {a: "ok", hidden: "ok"})
        latest = self.run_with_results("nightly", {a: "content-missing"})

        rows = {row["url"]: row for row in self.store.selection_rows()}

        self.assertEqual(set(rows), {a, b})
        self.assertEqual(rows[a]["last_verdict"], "content-missing")
        self.assertEqual(rows[a]["last_severity"], 3)
        self.assertEqual(rows[a]["last_run_id"], latest)
        self.assertEqual(rows[a]["last_checked"], "2026-10-24T03:02:00Z")
        self.assertEqual(rows[a]["add_count"], 9)
        self.assertEqual(rows[a]["site"], "example.org")
        self.assertIsNone(rows[b]["last_checked"])
        self.assertIsNone(rows[b]["last_verdict"])
        self.assertEqual(rows[b]["last_signatures"], [])
        self.assertIsInstance(rows[a], dict)


class IssueTests(StoreTestCase):
    def test_issue_lifecycle(self) -> None:
        a, b = "https://a.example.org/", "https://b.example.org/"
        signature = "crash:components/layout/flow.rs:12"
        first = self.store.create_run("nightly")

        update = self.store.upsert_issues(
            first,
            [
                IssueSighting(signature, VerdictKind.CRASH, a),
                IssueSighting(signature, "crash", b),
                IssueSighting("js:x is not defined", "js-error", a),
            ],
        )

        self.assertEqual(update.new, ("crash:components/layout/flow.rs:12", "js:x is not defined"))
        self.assertEqual(update.seen, 2)
        issues = {issue["signature"]: issue for issue in self.store.issues()}
        self.assertEqual(len(issues), 2)
        crash = issues[signature]
        self.assertEqual(crash["signature"], signature)
        self.assertEqual(crash["kind"], "crash")
        self.assertEqual(crash["status"], "open")
        self.assertEqual(crash["url_count"], 2)
        self.assertEqual((crash["first_seen_run"], crash["last_seen_run"]), (first, first))

        self.clock.advance(60)
        self.store.mark_issue(signature, IssueStatus.FIXED, "fixed in abc123")
        self.store.mark_issue("js:x is not defined", "wontfix", "site bug")
        self.assertEqual([i["signature"] for i in self.store.issues(status="fixed")], [signature])

        self.clock.advance(60)
        second = self.store.create_run("nightly")
        update = self.store.upsert_issues(
            second,
            [
                IssueSighting(signature, "crash", a),
                IssueSighting("js:x is not defined", "js-error", a),
            ],
        )

        self.assertEqual(update.new, ())
        self.assertEqual(update.reopened, (signature,))
        issues = {issue["signature"]: issue for issue in self.store.issues()}
        self.assertEqual(issues[signature]["status"], "open")
        self.assertEqual(issues[signature]["last_seen_run"], second)
        self.assertEqual(issues[signature]["first_seen_run"], first)
        self.assertEqual(issues[signature]["notes"], "2026-10-24T03:01:00Z fixed: fixed in abc123")
        self.assertEqual(issues["js:x is not defined"]["status"], "wontfix")
        (record,) = [issue for issue in self.store.issues() if issue["signature"] == signature]
        self.assertEqual(record["url_count"], 2)

        self.store.mark_issue(signature, "upstream", "fixed upstream")
        notes = self.store.issues(status=IssueStatus.UPSTREAM)[0]["notes"]
        self.assertEqual(notes.splitlines()[-1], "2026-10-24T03:02:00Z upstream: fixed upstream")
        self.assertEqual(len(notes.splitlines()), 2)

    def test_older_run_does_not_reopen_or_move_last_seen(self) -> None:
        signature = "hang"
        old = self.store.create_run("nightly")
        self.clock.advance(60)
        new = self.store.create_run("nightly")
        self.store.upsert_issues(new, [IssueSighting(signature, "hang", A)])
        self.clock.advance(60)
        self.store.mark_issue(signature, "fixed")

        update = self.store.upsert_issues(old, [IssueSighting(signature, "hang", B)])

        self.assertEqual(update.reopened, ())
        (issue,) = self.store.issues()
        self.assertEqual(issue["status"], "fixed")
        self.assertEqual(issue["first_seen_run"], old)
        self.assertEqual(issue["last_seen_run"], new)
        self.assertIsNone(issue["notes"])

    def test_note_without_status_change_keeps_reopen_point(self) -> None:
        signature = "hang"
        self.store.upsert_issues(self.store.create_run(), [IssueSighting(signature, "hang", A)])
        self.clock.advance(60)
        self.store.mark_issue(signature, "fixed", "fix merged")
        self.clock.advance(60)
        run_between = self.store.create_run()
        self.clock.advance(60)
        self.store.mark_issue(signature, "fixed", "binary rebuilt")

        (issue,) = self.store.issues()
        self.assertEqual(issue["status_changed"], "2026-10-24T03:01:00Z")
        update = self.store.upsert_issues(run_between, [IssueSighting(signature, "hang", A)])
        self.assertEqual(update.reopened, (signature,))

    def test_run_in_the_same_second_as_the_fix_reopens(self) -> None:
        # Timestamps have one-second resolution; a tie must not hide a recurrence.
        signature = "hang"
        self.store.upsert_issues(self.store.create_run(), [IssueSighting(signature, "hang", A)])
        self.clock.advance(60)
        self.store.mark_issue(signature, "fixed")
        same_second = self.store.create_run()

        update = self.store.upsert_issues(same_second, [IssueSighting(signature, "hang", A)])

        self.assertEqual(update.reopened, (signature,))

    def test_invalid_issue_requests(self) -> None:
        run_id = self.store.create_run()
        with self.assertRaisesRegex(StoreError, "No issue"):
            self.store.mark_issue("missing", "fixed")
        with self.assertRaises(ValueError):
            self.store.mark_issue("missing", "closed")
        with self.assertRaises(ValueError):
            self.store.upsert_issues(run_id, [IssueSighting("x", "not-a-kind", A)])
        with self.assertRaises(StoreError):
            self.store.upsert_issues("missing", [IssueSighting("x", "crash", A)])
        self.assertEqual(self.store.issues(), [])


if __name__ == "__main__":
    unittest.main()
