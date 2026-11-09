"""The report service's durable state: SQLite rows plus one PNG file per screenshot.

Layout under the data directory (directories 0700, files 0600):

    reports.sqlite3        WAL mode, PRAGMA user_version = 1
    screenshots/<id>.png   the screenshot of every row with a screenshot_sha256
    tmp/                   upload staging, emptied by every sweep
    writer.lock            flock target of the writer lock

Invariants:

- A screenshot file is complete before its row exists. It is written under tmp/
  with O_EXCL, fsynced and renamed into screenshots/, and only then is the row
  inserted; a failed insert unlinks it. A crash in between leaves an orphan file,
  which the next sweep deletes, never a row without its file.
- Purging and deleting change the row first and unlink the file after the commit,
  for the same reason.
- One writer at a time. add, set_status, delete, sweep and backup hold a
  threading.Lock and an flock on writer.lock, so the CLI's sweep and backup can
  run in another process beside the server.
- Every operation opens its own SQLite connection and closes it. Readers see a
  WAL snapshot and never wait for the writer. A connection per thread would
  outlive the server's per-connection threads until garbage collection, and the
  service handles a few requests an hour, so opening one costs nothing that
  matters.

Timestamps are RFC 3339 UTC with milliseconds (``2026-11-04T09:12:45.123Z``);
the fixed width makes text comparison in SQL chronological.
"""

from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
import secrets
import shutil
import sqlite3
import stat
import threading
from collections.abc import Callable, Collection, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path
from typing import Any, BinaryIO

from site_reports.validation import (
    RESOLVED_STATUSES,
    SETTABLE_STATUSES,
    ParsedReport,
    Status,
    StatusChange,
    is_report_id,
)

DATABASE_NAME = "reports.sqlite3"
SCREENSHOTS_DIR = "screenshots"
STAGING_DIR = "tmp"

DEFAULT_MAX_STORAGE_BYTES = 1024 * 1024 * 1024
DEFAULT_RESOLVED_RETENTION = timedelta(days=30)
DEFAULT_TOMBSTONE_RETENTION = timedelta(days=365)

_LOCK_NAME = "writer.lock"
_SCREENSHOT_SUFFIX = ".png"
_SCHEMA_VERSION = 1
_DIR_MODE = 0o700
_FILE_MODE = 0o600
_BUSY_TIMEOUT_SECONDS = 30.0
_COPY_CHUNK_BYTES = 64 * 1024

# Status changes an agent may make. Setting the current status again is a no-op,
# handled before this table is consulted.
_TRANSITIONS: Mapping[Status, frozenset[Status]] = {
    Status.OPEN: frozenset({Status.CLAIMED, Status.DONE, Status.REJECTED}),
    Status.CLAIMED: frozenset({Status.OPEN, Status.DONE, Status.REJECTED}),
    Status.DONE: frozenset({Status.OPEN}),
    Status.REJECTED: frozenset({Status.OPEN}),
    Status.DELETED: frozenset(),
}

# The extra CHECKs beyond the design's schema hold the invariants above: a
# screenshot has both a size and a digest, purged rows have no contents, and a
# deleted row is purged.
_SCHEMA = (
    """
    CREATE TABLE reports (
      seq               INTEGER PRIMARY KEY AUTOINCREMENT,
      id                TEXT NOT NULL UNIQUE,
      content_sha256    TEXT NOT NULL,
      status            TEXT NOT NULL
                        CHECK (status IN ('open', 'claimed', 'done', 'rejected', 'deleted')),
      status_note       TEXT,
      received_at       TEXT NOT NULL,
      updated_at        TEXT NOT NULL,
      resolved_at       TEXT,
      report_json       TEXT,
      screenshot_bytes  INTEGER,
      screenshot_sha256 TEXT,
      purged_at         TEXT,
      CHECK ((screenshot_bytes IS NULL) = (screenshot_sha256 IS NULL)),
      CHECK ((report_json IS NULL) = (purged_at IS NOT NULL)),
      CHECK (purged_at IS NULL OR screenshot_sha256 IS NULL),
      CHECK (status <> 'deleted' OR purged_at IS NOT NULL)
    )
    """,
    "CREATE INDEX reports_status_seq ON reports(status, seq)",
    """
    CREATE TABLE history (
      report_seq INTEGER NOT NULL REFERENCES reports(seq) ON DELETE CASCADE,
      at         TEXT NOT NULL,
      status     TEXT NOT NULL
                 CHECK (status IN ('open', 'claimed', 'done', 'rejected', 'deleted')),
      actor      TEXT NOT NULL CHECK (actor IN ('reporter', 'agent', 'retention')),
      note       TEXT
    )
    """,
    "CREATE INDEX history_report_seq ON history(report_seq)",
)

_REPORT_COLUMNS = (
    "seq, id, content_sha256, status, status_note, received_at, updated_at, resolved_at,"
    " report_json, screenshot_bytes, screenshot_sha256, purged_at"
)
_PURGEABLE = "status IN ('done', 'rejected') AND purged_at IS NULL AND resolved_at < :cutoff"


def utc_now() -> datetime:
    """The store's production clock."""
    return datetime.now(UTC)


class Actor(StrEnum):
    """Who changed a report, as recorded in its history."""

    REPORTER = "reporter"
    AGENT = "agent"
    RETENTION = "retention"


@dataclass(frozen=True, slots=True)
class Limits:
    """Storage budget and retention; Config builds this from the environment."""

    max_storage_bytes: int = DEFAULT_MAX_STORAGE_BYTES
    resolved_retention: timedelta = DEFAULT_RESOLVED_RETENTION
    tombstone_retention: timedelta = DEFAULT_TOMBSTONE_RETENTION

    def __post_init__(self) -> None:
        if self.max_storage_bytes < 1:
            raise ValueError("max_storage_bytes must be positive")
        if self.resolved_retention <= timedelta(0) or self.tombstone_retention <= timedelta(0):
            raise ValueError("retention periods must be positive")


@dataclass(frozen=True, slots=True)
class Row:
    """One report as stored. ``report`` is the validated metadata, None once purged.

    ``report`` is shared parsed JSON; callers must not modify it.
    """

    seq: int
    id: str
    status: Status
    status_note: str | None = field(repr=False)
    received_at: str
    updated_at: str
    resolved_at: str | None
    purged_at: str | None
    report: Mapping[str, Any] | None = field(repr=False)
    screenshot_bytes: int | None
    screenshot_sha256: str | None

    @property
    def purged(self) -> bool:
        return self.purged_at is not None

    @property
    def has_screenshot(self) -> bool:
        return self.screenshot_sha256 is not None


@dataclass(frozen=True, slots=True)
class HistoryEntry:
    status: Status
    at: str
    actor: Actor
    note: str | None = field(repr=False)


@dataclass(frozen=True, slots=True)
class SweepResult:
    purged: int  # Resolved reports whose contents were removed.
    expired: int  # Tombstones deleted with their history.
    orphans: int  # Screenshot files without a row that holds them.
    staging: int  # Leftover upload files.


@dataclass(frozen=True, slots=True)
class BackupResult:
    reports: int
    screenshots: int
    missing: int  # Rows whose screenshot could not be copied; cleared in the copy.


class StoreError(Exception):
    """A request the store refuses; ``code`` is the wire error code."""

    code = "store_error"


class NotFound(StoreError):
    code = "not_found"


class Gone(StoreError):
    """The report was deleted; only its tombstone remains."""

    code = "deleted"


class IdConflict(StoreError):
    """The id exists with different content."""

    code = "id_conflict"


class StatusConflict(StoreError):
    """The caller's ``expect`` differs from the current status."""

    code = "status_conflict"

    def __init__(self, current: Status) -> None:
        super().__init__(f"current status is {current}")
        self.current = current


class PurgedConflict(StoreError):
    """A purged report cannot be reopened: its contents are gone."""

    code = "purged"


class InvalidTransition(StoreError):
    code = "invalid_transition"


class StorageFull(StoreError):
    """Stored screenshots plus this one would exceed the storage budget."""

    code = "storage_full"


class IncompatibleDatabase(Exception):
    """The database's schema version is not one this code knows."""


class CorruptStore(Exception):
    """Stored data contradicts its record; a server fault, not a client error."""


class Store:
    """Reports and their screenshots in one data directory. Safe to share between threads."""

    def __init__(
        self,
        data_dir: Path,
        clock: Callable[[], datetime],
        limits: Limits,
    ) -> None:
        self._clock = clock
        self._limits = limits
        data_dir.mkdir(mode=_DIR_MODE, parents=True, exist_ok=True)
        self._root = data_dir.resolve()
        self._screenshots = self._root / SCREENSHOTS_DIR
        self._staging = self._root / STAGING_DIR
        self._database = self._root / DATABASE_NAME
        self._screenshots.mkdir(mode=_DIR_MODE, exist_ok=True)
        self._staging.mkdir(mode=_DIR_MODE, exist_ok=True)
        _create_private_file(self._database)

        self._thread_lock = threading.Lock()
        self._lock_fd: int | None = os.open(
            self._root / _LOCK_NAME, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, _FILE_MODE
        )
        try:
            self._initialise()
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        if self._lock_fd is not None:
            os.close(self._lock_fd)
            self._lock_fd = None

    def __enter__(self) -> Store:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    def add(self, report: ParsedReport) -> tuple[Row, bool]:
        """Store a new report, or return the existing one: (row, created).

        A repeated id with the same content returns the current row whatever its
        status, so a phone retry never reopens or recreates a report. Raises
        IdConflict for different content and StorageFull when the screenshot does
        not fit the budget.
        """
        now = self._now()
        with self._writer(), self._connect() as conn:
            existing = _select(conn, report.id)
            if existing is not None:
                if existing["content_sha256"] != report.content_sha256:
                    raise IdConflict()
                return _row(existing), False

            if report.screenshot is not None:
                stored = _stored_bytes(conn)
                if stored + len(report.screenshot) > self._limits.max_storage_bytes:
                    raise StorageFull()
                self._write_screenshot(report.id, report.screenshot)
            try:
                with _transaction(conn):
                    seq = conn.execute(
                        "INSERT INTO reports (id, content_sha256, status, received_at, updated_at,"
                        " report_json, screenshot_bytes, screenshot_sha256)"
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            report.id,
                            report.content_sha256,
                            Status.OPEN.value,
                            now,
                            now,
                            report.metadata_json,
                            None if report.screenshot is None else len(report.screenshot),
                            report.screenshot_sha256,
                        ),
                    ).lastrowid
                    _record(conn, seq, now, Status.OPEN, Actor.REPORTER, None)
            except BaseException:
                if report.screenshot is not None:
                    self._remove_screenshot(report.id)
                raise
            return _row(_select(conn, report.id)), True

    def get(self, report_id: str) -> Row | None:
        with self._connect() as conn:
            record = _select(conn, report_id)
        return None if record is None else _row(record)

    def history(self, report_id: str) -> list[HistoryEntry]:
        """The report's status history, oldest first; empty for an unknown id."""
        with self._connect() as conn:
            records = conn.execute(
                "SELECT h.status, h.at, h.actor, h.note FROM history h"
                " JOIN reports r ON r.seq = h.report_seq WHERE r.id = ? ORDER BY h.rowid",
                (report_id,),
            ).fetchall()
        return [
            HistoryEntry(Status(r["status"]), r["at"], Actor(r["actor"]), r["note"])
            for r in records
        ]

    def list_reports(
        self, statuses: Collection[Status], limit: int, after: int | None = None
    ) -> tuple[list[Row], int | None]:
        """Reports with one of ``statuses`` and ``seq > after``, oldest first.

        Returns the page and the cursor for the next one, None on the last page.
        """
        if limit < 1 or not statuses:
            raise ValueError("limit must be positive and statuses non-empty")
        values = sorted(status.value for status in statuses)
        marks = ", ".join("?" * len(values))
        with self._connect() as conn:
            records = conn.execute(
                f"SELECT {_REPORT_COLUMNS} FROM reports"
                f" WHERE status IN ({marks}) AND seq > ? ORDER BY seq LIMIT ?",
                (*values, after or 0, limit + 1),
            ).fetchall()
        rows = [_row(record) for record in records[:limit]]
        cursor = rows[-1].seq if len(records) > limit else None
        return rows, cursor

    def open_screenshot(self, report_id: str) -> tuple[BinaryIO, int] | None:
        """The screenshot file and its size, or None when the report has none (any more).

        The caller closes the file. Its path must resolve inside screenshots/ (so
        that directory cannot have been replaced by a link elsewhere), the file is
        opened without following a symbolic link, and it must be a regular file of
        the recorded size.
        """
        row = self.get(report_id)
        if row is None or not row.has_screenshot:
            return None
        path = self._screenshot_path(report_id)
        if Path(os.path.realpath(path)).parent != self._screenshots:
            raise CorruptStore("screenshot path resolves outside screenshots/")
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        except FileNotFoundError:
            return None  # Purged or deleted since the row was read.
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_size != row.screenshot_bytes:
                raise CorruptStore("screenshot file does not match its record")
            return os.fdopen(fd, "rb"), info.st_size
        except BaseException:
            os.close(fd)
            raise

    def set_status(self, report_id: str, change: StatusChange, actor: Actor) -> Row:
        """Apply an agent's status change, with optional compare-and-set on ``expect``.

        Raises NotFound, Gone, StatusConflict, InvalidTransition or PurgedConflict.
        Setting the current status again changes nothing unless a note comes with
        it, which is recorded.
        """
        if change.status not in SETTABLE_STATUSES:
            raise ValueError(f"{change.status} is not settable")
        now = self._now()
        with self._writer(), self._connect() as conn, _transaction(conn):
            record = _select(conn, report_id)
            if record is None:
                raise NotFound()
            current = Status(record["status"])
            if current is Status.DELETED:
                raise Gone()
            if change.expect is not None and change.expect != current:
                raise StatusConflict(current)

            if change.status == current:
                if change.note is None:
                    return _row(record)
                conn.execute(
                    "UPDATE reports SET status_note = ?, updated_at = ? WHERE seq = ?",
                    (change.note, now, record["seq"]),
                )
            else:
                if change.status not in _TRANSITIONS[current]:
                    raise InvalidTransition(f"{current} to {change.status}")
                if record["purged_at"] is not None:
                    raise PurgedConflict()
                resolved_at = now if change.status in RESOLVED_STATUSES else None
                conn.execute(
                    "UPDATE reports SET status = ?, status_note = ?, updated_at = ?,"
                    " resolved_at = ? WHERE seq = ?",
                    (change.status.value, change.note, now, resolved_at, record["seq"]),
                )
            _record(conn, record["seq"], now, change.status, actor, change.note)
            return _row(_select(conn, report_id))

    def delete(self, report_id: str, actor: Actor) -> bool:
        """Purge a report's contents now and keep a tombstone; False for an unknown id.

        The tombstone keeps the id and content digest, so a late phone retry is
        answered as a repeat instead of storing the report again. Notes are cleared
        too: they may describe the page.
        """
        now = self._now()
        with self._writer(), self._connect() as conn:
            with _transaction(conn):
                record = _select(conn, report_id)
                if record is None:
                    return False
                if record["status"] != Status.DELETED:
                    conn.execute(
                        "UPDATE reports SET status = 'deleted', status_note = NULL,"
                        " report_json = NULL, screenshot_bytes = NULL, screenshot_sha256 = NULL,"
                        " purged_at = COALESCE(purged_at, ?), updated_at = ? WHERE seq = ?",
                        (now, now, record["seq"]),
                    )
                    conn.execute(
                        "UPDATE history SET note = NULL WHERE report_seq = ?", (record["seq"],)
                    )
                    _record(conn, record["seq"], now, Status.DELETED, actor, None)
            # Also on a repeat: a tombstone holds no screenshot, so a file left by a
            # failed unlink is an orphan, and the retry should finish removing it.
            self._remove_screenshot(report_id)
        return True

    def stored_bytes(self) -> int:
        """Bytes of screenshots held, the figure the storage budget counts."""
        with self._connect() as conn:
            return _stored_bytes(conn)

    def sweep(self) -> SweepResult:
        """Apply retention and remove stray files (design section 1.7).

        Resolved reports past the resolved retention lose their contents and
        screenshot but keep their status and history; tombstones past the tombstone
        retention are deleted with their history. Open and claimed reports are never
        purged. Files under tmp/ and screenshots without a row that holds them are
        deleted.
        """
        moment = self._clock_now()
        now = _timestamp(moment)
        resolved_cutoff = _timestamp(moment - self._limits.resolved_retention)
        tombstone_cutoff = _timestamp(moment - self._limits.tombstone_retention)
        with self._writer(), self._connect() as conn:
            with _transaction(conn):
                purged_ids = [
                    r["id"]
                    for r in conn.execute(
                        f"SELECT id FROM reports WHERE {_PURGEABLE}", {"cutoff": resolved_cutoff}
                    )
                ]
                conn.execute(
                    "INSERT INTO history (report_seq, at, status, actor, note)"
                    f" SELECT seq, :now, status, :actor, NULL FROM reports WHERE {_PURGEABLE}",
                    {"now": now, "actor": Actor.RETENTION.value, "cutoff": resolved_cutoff},
                )
                conn.execute(
                    "UPDATE reports SET report_json = NULL, screenshot_bytes = NULL,"
                    " screenshot_sha256 = NULL, purged_at = :now, updated_at = :now"
                    f" WHERE {_PURGEABLE}",
                    {"now": now, "cutoff": resolved_cutoff},
                )
                expired = conn.execute(
                    "DELETE FROM reports WHERE purged_at IS NOT NULL AND purged_at < ?",
                    (tombstone_cutoff,),
                ).rowcount
            held = {
                r["id"]
                for r in conn.execute("SELECT id FROM reports WHERE screenshot_sha256 IS NOT NULL")
            }
            for report_id in purged_ids:
                self._remove_screenshot(report_id)
            orphans = _remove_entries(self._screenshots, keep={_screenshot_name(i) for i in held})
            staging = _remove_entries(self._staging, keep=frozenset())
        return SweepResult(len(purged_ids), expired, orphans, staging)

    def backup(self, target: Path) -> BackupResult:
        """Copy the database (sqlite3 backup API) and screenshots into a new directory.

        ``target`` must not exist; it gets the data directory's layout, so a restore
        is a copy of its contents. Holding the writer lock keeps rows and files
        consistent. A screenshot that is missing or no longer matches its digest is
        left out and its row's screenshot columns are cleared in the copy. A failed
        backup removes the directory: a partial copy holds the same private data
        and, after a full disk, keeps the volume full.
        """
        target.mkdir(mode=_DIR_MODE)
        try:
            return self._write_backup(target)
        except BaseException:
            shutil.rmtree(target, ignore_errors=True)
            raise

    def _write_backup(self, target: Path) -> BackupResult:
        screenshots = target / SCREENSHOTS_DIR
        screenshots.mkdir(mode=_DIR_MODE)
        database = target / DATABASE_NAME
        _create_private_file(database)
        with self._writer(), self._connect() as source:
            copy = sqlite3.connect(database, isolation_level=None)
            try:
                source.backup(copy)
                copy.execute("PRAGMA journal_mode = DELETE")  # One self-contained file.
                held = copy.execute(
                    "SELECT id, screenshot_sha256 FROM reports WHERE screenshot_sha256 IS NOT NULL"
                ).fetchall()
                missing = [
                    report_id
                    for report_id, digest in held
                    if not self._copy_screenshot(report_id, digest, screenshots)
                ]
                copy.executemany(
                    "UPDATE reports SET screenshot_bytes = NULL, screenshot_sha256 = NULL"
                    " WHERE id = ?",
                    [(report_id,) for report_id in missing],
                )
                reports = copy.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
            finally:
                copy.close()
        _fsync_directory(screenshots)
        _fsync_directory(target)
        return BackupResult(reports, len(held) - len(missing), len(missing))

    def is_healthy(self) -> bool:
        """Whether the database answers and the staging directory is writable."""
        try:
            with self._connect() as conn:
                conn.execute("SELECT 1").fetchone()
        except sqlite3.Error:
            return False
        return os.access(self._staging, os.W_OK)

    def _initialise(self) -> None:
        with self._writer(), self._connect() as conn:
            mode = conn.execute("PRAGMA journal_mode = WAL").fetchone()[0]
            if mode != "wal":
                raise IncompatibleDatabase(f"journal mode {mode}, not wal")
            with _transaction(conn):
                version = conn.execute("PRAGMA user_version").fetchone()[0]
                if version == _SCHEMA_VERSION:
                    return
                if version != 0:
                    raise IncompatibleDatabase(f"schema version {version}")
                for statement in _SCHEMA:
                    conn.execute(statement)
                conn.execute(f"PRAGMA user_version = {_SCHEMA_VERSION}")
            self._fsync_root()

    def _fsync_root(self) -> None:
        """Make the new database file's directory entry durable, not just its commits.

        `PRAGMA synchronous = FULL` fsyncs the file, but a power loss could still leave the
        directory without the entry, so the store would look empty after acknowledged commits.
        Best effort: where the platform cannot fsync a directory, the file itself is still
        durable.
        """
        try:
            _fsync_directory(self._root)
        except OSError:
            pass

    @contextmanager
    def _writer(self) -> Iterator[None]:
        with self._thread_lock:
            if self._lock_fd is None:
                raise RuntimeError("store is closed")
            fcntl.flock(self._lock_fd, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(self._lock_fd, fcntl.LOCK_UN)

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self._database, isolation_level=None, timeout=_BUSY_TIMEOUT_SECONDS)
        try:
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA foreign_keys = ON")
            # A 201 lets the phone forget the report, so a commit must survive power loss.
            conn.execute("PRAGMA synchronous = FULL")
            yield conn
        finally:
            conn.close()

    def _clock_now(self) -> datetime:
        moment = self._clock()
        if moment.tzinfo is None:
            raise ValueError("the clock must return an aware datetime")
        return moment

    def _now(self) -> str:
        return _timestamp(self._clock_now())

    def _screenshot_path(self, report_id: str) -> Path:
        # The id pattern admits no separator or dot, so the path stays in screenshots/.
        if not is_report_id(report_id):
            raise ValueError("not a report id")
        return self._screenshots / _screenshot_name(report_id)

    def _write_screenshot(self, report_id: str, data: bytes) -> None:
        final = self._screenshot_path(report_id)
        staging = self._staging / f"{report_id}.{secrets.token_hex(8)}"
        fd = os.open(
            staging, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, _FILE_MODE
        )
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(staging, final)
        except BaseException:
            _unlink_quietly(staging)
            raise
        _fsync_directory(self._screenshots)

    def _remove_screenshot(self, report_id: str) -> None:
        _unlink_quietly(self._screenshot_path(report_id))

    def _copy_screenshot(self, report_id: str, digest: str, directory: Path) -> bool:
        try:
            source = os.open(
                self._screenshot_path(report_id), os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC
            )
        except FileNotFoundError:
            return False
        target = directory / _screenshot_name(report_id)
        hasher = hashlib.sha256()
        with os.fdopen(source, "rb") as reader:
            fd = os.open(
                target,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                _FILE_MODE,
            )
            with os.fdopen(fd, "wb") as writer:
                while block := reader.read(_COPY_CHUNK_BYTES):
                    hasher.update(block)
                    writer.write(block)
                writer.flush()
                os.fsync(writer.fileno())
        if hasher.hexdigest() != digest:
            target.unlink()
            return False
        return True


def _select(conn: sqlite3.Connection, report_id: str) -> sqlite3.Row | None:
    return conn.execute(
        f"SELECT {_REPORT_COLUMNS} FROM reports WHERE id = ?", (report_id,)
    ).fetchone()


def _row(record: sqlite3.Row) -> Row:
    report_json = record["report_json"]
    return Row(
        seq=record["seq"],
        id=record["id"],
        status=Status(record["status"]),
        status_note=record["status_note"],
        received_at=record["received_at"],
        updated_at=record["updated_at"],
        resolved_at=record["resolved_at"],
        purged_at=record["purged_at"],
        report=None if report_json is None else json.loads(report_json),
        screenshot_bytes=record["screenshot_bytes"],
        screenshot_sha256=record["screenshot_sha256"],
    )


def _record(
    conn: sqlite3.Connection, seq: int, at: str, status: Status, actor: Actor, note: str | None
) -> None:
    conn.execute(
        "INSERT INTO history (report_seq, at, status, actor, note) VALUES (?, ?, ?, ?, ?)",
        (seq, at, status.value, actor.value, note),
    )


def _stored_bytes(conn: sqlite3.Connection) -> int:
    return conn.execute(
        "SELECT COALESCE(SUM(screenshot_bytes), 0) FROM reports WHERE screenshot_sha256 IS NOT NULL"
    ).fetchone()[0]


@contextmanager
def _transaction(conn: sqlite3.Connection) -> Iterator[None]:
    # IMMEDIATE takes the write lock at the start, so a transaction never fails
    # half way on a lock upgrade.
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        # SQLite rolls back by itself after some errors (SQLITE_FULL, for one); a
        # second ROLLBACK would then raise and hide the original error.
        if conn.in_transaction:
            conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")


def _timestamp(moment: datetime) -> str:
    utc = moment.astimezone(UTC).replace(tzinfo=None)
    return utc.isoformat(timespec="milliseconds") + "Z"


def _screenshot_name(report_id: str) -> str:
    return report_id + _SCREENSHOT_SUFFIX


def _remove_entries(directory: Path, keep: Collection[str]) -> int:
    """Unlink every non-directory entry of ``directory`` not named in ``keep``."""
    removed = 0
    with os.scandir(directory) as entries:
        for entry in entries:
            if entry.name in keep or entry.is_dir(follow_symlinks=False):
                continue
            _unlink_quietly(Path(entry.path))
            removed += 1
    return removed


def _create_private_file(path: Path) -> None:
    with contextlib.suppress(FileExistsError):
        os.close(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, _FILE_MODE))


def _unlink_quietly(path: Path) -> None:
    with contextlib.suppress(FileNotFoundError):
        path.unlink()


def _fsync_directory(directory: Path) -> None:
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
