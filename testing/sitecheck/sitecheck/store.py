"""The site check's local state: pages, runs, per-page results and issues, in SQLite.

All SQL in the harness lives in this module, and every value reaches SQLite as a
bound parameter. The database holds URLs derived from the owner's browsing history,
so the file is created owner-only (0600); SQLite gives its WAL and shared-memory
files the same mode.

Conventions:
- Timestamps are ISO-8601 UTC strings (``2026-10-24T03:00:00Z``); Hister times are
  integer unix seconds.
- Runs are ordered by start time, then by insertion order for runs started in the
  same second.
- A page may be tried several times in one run (``attempt`` 1, 2, ...). The highest
  attempt is the page's outcome for that run.
- Query methods return plain dicts (typed as ``TypedDict``), JSON columns decoded.
- Visit counts are kept per history document (``page_visits``) and summed per page,
  so a sync that rereads only some of a page's documents cannot shrink its count.
"""

from __future__ import annotations

import json
import os
import secrets
import sqlite3
import stat
from collections.abc import Callable, Iterable, Iterator, Mapping, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Any, NamedTuple, TypedDict

from sitecheck.model import VerdictKind

_FILE_MODE = 0o600
_SEVERITY_MIN = 0
_SEVERITY_MAX = 5
_SQLITE_INTEGER_MAX = 2**63 - 1
_RUN_ID_ATTEMPTS = 5  # Same-second runs collide on the 16-bit suffix only rarely.

# _MIGRATIONS[n] upgrades a database at user_version n to n + 1. Never edit a
# released entry; append a new one instead.
_SCHEMA_V1 = """
CREATE TABLE pages (
    url TEXT PRIMARY KEY,
    host TEXT NOT NULL,
    site TEXT NOT NULL,
    template TEXT,
    source TEXT NOT NULL CHECK (source IN ('hister', 'seed', 'manual')),
    eligible INTEGER NOT NULL CHECK (eligible IN (0, 1)),
    reason TEXT NOT NULL,
    hister_updated INTEGER,
    add_count INTEGER CHECK (add_count IS NULL OR add_count >= 0),
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL
);

CREATE TABLE meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE runs (
    id TEXT PRIMARY KEY,
    started TEXT NOT NULL,
    finished TEXT,
    servo_rev TEXT,
    servo_binary TEXT,
    label TEXT,
    reference_engine TEXT,
    notes TEXT
);

CREATE TABLE results (
    run_id TEXT NOT NULL REFERENCES runs(id),
    url TEXT NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt >= 1),
    verdict TEXT NOT NULL,
    severity INTEGER NOT NULL CHECK (severity BETWEEN 0 AND 5),
    signatures TEXT NOT NULL,   -- JSON list of strings.
    metrics TEXT,               -- JSON object; NULL when the engine produced none.
    artifacts TEXT,             -- Page directory, relative to the run directory.
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    recorded TEXT NOT NULL,
    PRIMARY KEY (run_id, url, attempt)
);
CREATE INDEX results_by_url ON results(url);

CREATE TABLE issues (
    signature TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    first_seen_run TEXT NOT NULL REFERENCES runs(id),
    last_seen_run TEXT NOT NULL REFERENCES runs(id),
    status TEXT NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'fixed', 'wontfix', 'upstream')),
    status_changed TEXT NOT NULL,
    notes TEXT
);

CREATE TABLE issue_pages (
    signature TEXT NOT NULL REFERENCES issues(signature),
    url TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES runs(id),
    PRIMARY KEY (signature, url, run_id)
);
"""
# Version 2: visit counts per history document, hosts the harness saw while loading
# pages (redirect targets, script sources) for the leak check, and pages excluded
# after a load showed where they lead. The sync marker is cleared so the next sync
# reads the whole history and fills page_visits.
_SCHEMA_V2 = """
CREATE TABLE page_visits (
    document TEXT PRIMARY KEY,  -- Hash of the history document's own URL.
    page_url TEXT NOT NULL REFERENCES pages(url),
    add_count INTEGER CHECK (add_count IS NULL OR add_count >= 0),
    hister_updated INTEGER
);
CREATE INDEX page_visits_by_page ON page_visits(page_url);

CREATE TABLE derived_hosts (
    host TEXT PRIMARY KEY,
    first_seen TEXT NOT NULL
);

ALTER TABLE pages ADD COLUMN load_exclusion TEXT;

-- hister.LAST_SYNC_META_KEY, written out: a released migration never changes.
DELETE FROM meta WHERE key = 'hister_last_sync';
"""
_MIGRATIONS: tuple[str, ...] = (_SCHEMA_V1, _SCHEMA_V2)
SCHEMA_VERSION = len(_MIGRATIONS)

_RESULT_COLUMN_NAMES = (
    "run_id",
    "url",
    "attempt",
    "verdict",
    "severity",
    "signatures",
    "metrics",
    "artifacts",
    "duration_ms",
    "recorded",
)
_RESULT_COLUMNS = ", ".join(_RESULT_COLUMN_NAMES)
_QUALIFIED_RESULT_COLUMNS = ", ".join(f"results.{name}" for name in _RESULT_COLUMN_NAMES)

# Ranks each URL's judged results newest first: latest run, then its highest attempt.
# Outcomes that say nothing about Servo (no baseline, no comparison, a harness
# failure) neither count as a check nor replace an earlier verdict.
_UNJUDGED_VERDICTS = tuple(sorted(kind.value for kind in VerdictKind if not kind.triaged))
_RANKED_JUDGED_RESULTS = f"""
    SELECT {_QUALIFIED_RESULT_COLUMNS},
        ROW_NUMBER() OVER (
            PARTITION BY results.url
            ORDER BY runs.started DESC, runs.rowid DESC, results.attempt DESC
        ) AS position
    FROM results JOIN runs ON runs.id = results.run_id
    WHERE results.verdict NOT IN ({", ".join("?" for _ in _UNJUDGED_VERDICTS)})
"""


class StoreError(Exception):
    """The store cannot be opened or a request conflicts with its contents."""


class PageSource(str, Enum):
    HISTER = "hister"
    SEED = "seed"  # Public seed list, not history.
    MANUAL = "manual"  # Named on the command line or in a URL file.


class IssueStatus(str, Enum):
    OPEN = "open"
    FIXED = "fixed"
    WONTFIX = "wontfix"
    UPSTREAM = "upstream"  # Fixed upstream; waiting for the next upstream merge.


@dataclass(frozen=True)
class PageRow:
    """A page as classified by the URL policy, ready to upsert.

    ``url`` is the key: the normalized URL for eligible pages.
    """

    url: str
    host: str
    site: str
    template: str | None
    source: PageSource | str
    eligible: bool
    reason: str
    hister_updated: int | None = None  # Unix seconds.
    add_count: int | None = None


@dataclass(frozen=True)
class DocumentVisit:
    """One history document's visits, attributed to the page it normalizes to."""

    document: str  # A hash of the document's own URL, never the URL itself.
    page_url: str  # The page's key in `pages`.
    add_count: int | None
    hister_updated: int | None  # Unix seconds.


@dataclass(frozen=True)
class PageClassification:
    """A page's current URL-policy outcome."""

    url: str
    eligible: bool
    reason: str
    template: str | None


@dataclass(frozen=True)
class IssueSighting:
    """One page showing one finding signature in a run."""

    signature: str
    kind: VerdictKind | str  # The kind of the finding that produced the signature.
    url: str


@dataclass(frozen=True)
class UpsertCounts:
    new: int
    updated: int


@dataclass(frozen=True)
class IssueUpdate:
    new: tuple[str, ...]  # Signatures seen for the first time.
    reopened: tuple[str, ...]  # Marked fixed earlier, seen again in a later run.
    seen: int  # Distinct signatures in the sightings.


class HostsAndSites(NamedTuple):
    hosts: frozenset[str]
    sites: frozenset[str]


class RunRecord(TypedDict):
    id: str
    started: str
    finished: str | None
    servo_rev: str | None
    servo_binary: str | None
    label: str | None
    reference_engine: str | None
    notes: str | None


class ResultRecord(TypedDict):
    run_id: str
    url: str
    attempt: int
    verdict: str
    severity: int
    signatures: list[str]
    metrics: dict[str, Any] | None
    artifacts: str | None
    duration_ms: int
    recorded: str


class SelectionRow(TypedDict):
    """An eligible page with its latest judged outcome.

    The last_* fields describe the latest result that judged Servo; they are None
    (and last_signatures empty) if no load ever did. ``last_attempted`` is when the
    page was last loaded at all, judged or not.
    """

    url: str
    host: str
    site: str
    template: str | None
    source: str
    add_count: int | None
    hister_updated: int | None
    last_checked: str | None
    last_run_id: str | None
    last_verdict: str | None
    last_severity: int | None
    last_signatures: list[str]
    last_attempted: str | None


class IssueRecord(TypedDict):
    signature: str
    kind: str
    status: str
    status_changed: str
    first_seen_run: str
    last_seen_run: str
    notes: str | None
    url_count: int  # Distinct pages that ever showed the signature.


def open_store(
    path: str | os.PathLike[str], *, clock: Callable[[], datetime] | None = None
) -> Store:
    """Open or create the store at ``path`` and bring its schema up to date.

    The parent directory must exist; it is the private data directory, whose
    creation and permissions belong to the configuration. ``clock`` returns the
    current time as an aware datetime; tests pass a fixed one.
    """
    db_path = Path(path)
    if not db_path.parent.is_dir():
        raise StoreError(f"The store's directory {db_path.parent} does not exist")
    _ensure_private_file(db_path)

    # autocommit=True leaves transaction control to explicit BEGIN/COMMIT here,
    # rather than sqlite3's implicit transactions.
    connection = sqlite3.connect(db_path, autocommit=True)
    try:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        journal_mode = connection.execute("PRAGMA journal_mode = WAL").fetchone()[0]
        if str(journal_mode).lower() != "wal":
            raise StoreError(f"SQLite refused WAL mode for {db_path} (got {journal_mode})")
        _migrate(connection)
    except BaseException:
        connection.close()
        raise
    return Store(connection, clock or _utc_now)


class Store:
    """An open store. Use ``open_store`` to create one; close it when done."""

    def __init__(self, connection: sqlite3.Connection, clock: Callable[[], datetime]) -> None:
        self._connection = connection
        self._clock = clock

    def close(self) -> None:
        self._connection.close()

    def __enter__(self) -> Store:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    # Pages

    def upsert_pages(self, rows: Iterable[PageRow]) -> UpsertCounts:
        """Insert or refresh pages; returns how many were new and how many already existed.

        An invalid row raises ValueError and nothing is written. A re-classified page
        takes the new classification, but a load exclusion (`exclude_after_load`)
        stays; Hister timestamps and visit counts keep their stored values when the
        new row has none (a seed import, for example).
        """
        now = self._now()
        new = 0
        updated = 0
        with self._transaction():
            for row in rows:
                values = _page_values(row)
                exists = self._connection.execute(
                    "SELECT 1 FROM pages WHERE url = ?", (values["url"],)
                ).fetchone()
                self._connection.execute(
                    """
                    INSERT INTO pages (url, host, site, template, source, eligible, reason,
                        hister_updated, add_count, first_seen, last_seen)
                    VALUES (:url, :host, :site, :template, :source, :eligible, :reason,
                        :hister_updated, :add_count, :now, :now)
                    ON CONFLICT (url) DO UPDATE SET
                        host = excluded.host,
                        site = excluded.site,
                        template = excluded.template,
                        -- A URL known to be in the history stays marked as such, so
                        -- nothing later mistakes it for a public seed.
                        source = CASE WHEN pages.source = 'hister'
                            THEN 'hister' ELSE excluded.source END,
                        eligible = excluded.eligible,
                        reason = excluded.reason,
                        hister_updated = COALESCE(excluded.hister_updated, pages.hister_updated),
                        add_count = COALESCE(excluded.add_count, pages.add_count),
                        last_seen = excluded.last_seen
                    """,
                    {**values, "now": now},
                )
                if exists:
                    updated += 1
                else:
                    new += 1
        return UpsertCounts(new=new, updated=updated)

    def selection_rows(self) -> list[SelectionRow]:
        """Eligible pages with their latest judged outcome across all runs, ordered by URL."""
        rows = self._connection.execute(
            f"""
            WITH ranked AS ({_RANKED_JUDGED_RESULTS}),
            attempted AS (SELECT url, MAX(recorded) AS last_attempted FROM results GROUP BY url)
            SELECT pages.url, pages.host, pages.site, pages.template, pages.source,
                pages.add_count, pages.hister_updated,
                ranked.recorded AS last_checked, ranked.run_id AS last_run_id,
                ranked.verdict AS last_verdict, ranked.severity AS last_severity,
                ranked.signatures AS last_signatures, attempted.last_attempted
            FROM pages
            LEFT JOIN ranked ON ranked.url = pages.url AND ranked.position = 1
            LEFT JOIN attempted ON attempted.url = pages.url
            WHERE pages.eligible = 1 AND pages.load_exclusion IS NULL
            ORDER BY pages.url
            """,
            _UNJUDGED_VERDICTS,
        ).fetchall()
        selection = []
        for row in rows:
            values = dict(row)
            signatures = values["last_signatures"]
            values["last_signatures"] = [] if signatures is None else json.loads(signatures)
            selection.append(SelectionRow(**values))
        return selection

    def page_classifications(self) -> list[PageClassification]:
        """Every page's stored URL-policy outcome, for re-checking it against the current policy."""
        rows = self._connection.execute(
            "SELECT url, eligible, reason, template FROM pages ORDER BY url"
        ).fetchall()
        return [
            PageClassification(row["url"], bool(row["eligible"]), row["reason"], row["template"])
            for row in rows
        ]

    def reclassify_pages(self, changes: Iterable[PageClassification]) -> None:
        """Store new URL-policy outcomes, for instance after the deny list grew."""
        with self._transaction():
            for change in changes:
                if not isinstance(change.reason, str) or not isinstance(change.eligible, bool):
                    raise ValueError("a classification needs a bool eligible and a str reason")
                self._connection.execute(
                    "UPDATE pages SET eligible = ?, reason = ?, template = ? WHERE url = ?",
                    (int(change.eligible), change.reason, change.template, change.url),
                )

    def exclude_after_load(self, url: str, reason: str) -> bool:
        """Keep a page out of every later selection: loading it showed it leaves the policy.

        Unlike the URL-policy outcome, later imports of the same URL do not undo it.
        Returns whether the store has the page (a local file named on the command
        line is not a page).
        """
        cursor = self._connection.execute(
            "UPDATE pages SET load_exclusion = ? WHERE url = ?", (reason, url)
        )
        return cursor.rowcount > 0

    def page_count(self, source: PageSource) -> int:
        row = self._connection.execute(
            "SELECT COUNT(*) FROM pages WHERE source = ?", (PageSource(source).value,)
        ).fetchone()
        return int(row[0])

    def record_visits(self, visits: Iterable[DocumentVisit]) -> None:
        """Store each history document's visits and update the totals of the pages concerned.

        A page's ``add_count`` becomes the sum over its documents and ``hister_updated``
        their latest time, whichever documents this sync happened to reread.
        """
        affected: set[str] = set()
        with self._transaction():
            for visit in visits:
                if not visit.document or not visit.page_url:
                    raise ValueError("a visit needs a document and a page")
                for name in ("add_count", "hister_updated"):
                    if getattr(visit, name) is not None:
                        _require_int(getattr(visit, name), name, minimum=0)
                previous = self._connection.execute(
                    "SELECT page_url FROM page_visits WHERE document = ?", (visit.document,)
                ).fetchone()
                if previous is not None:
                    affected.add(previous[0])
                self._connection.execute(
                    """
                    INSERT INTO page_visits (document, page_url, add_count, hister_updated)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (document) DO UPDATE SET
                        page_url = excluded.page_url,
                        add_count = excluded.add_count,
                        hister_updated = excluded.hister_updated
                    """,
                    (visit.document, visit.page_url, visit.add_count, visit.hister_updated),
                )
                affected.add(visit.page_url)
            for url in sorted(affected):
                self._connection.execute(
                    """
                    UPDATE pages SET
                        add_count = (SELECT SUM(add_count) FROM page_visits WHERE page_url = :url),
                        hister_updated = (
                            SELECT MAX(hister_updated) FROM page_visits WHERE page_url = :url)
                    WHERE url = :url
                    """,
                    {"url": url},
                )

    def add_derived_hosts(self, hosts: Iterable[str]) -> None:
        """Remember hosts seen while loading pages (redirect targets, script sources).

        They are history-derived without being pages: the leak check looks for them,
        the selection never loads them.
        """
        now = self._now()
        with self._transaction():
            self._connection.executemany(
                "INSERT OR IGNORE INTO derived_hosts (host, first_seen) VALUES (?, ?)",
                [(host, now) for host in sorted(set(hosts)) if host],
            )

    def derived_hosts(self) -> frozenset[str]:
        rows = self._connection.execute("SELECT host FROM derived_hosts")
        return frozenset(row[0] for row in rows)

    def hosts_and_sites(self) -> HostsAndSites:
        """Every host and site in the store, eligible or not, for the leak check."""
        hosts = self._connection.execute("SELECT DISTINCT host FROM pages WHERE host != ''")
        sites = self._connection.execute("SELECT DISTINCT site FROM pages WHERE site != ''")
        return HostsAndSites(
            hosts=frozenset(row[0] for row in hosts), sites=frozenset(row[0] for row in sites)
        )

    def known_urls(self) -> frozenset[str]:
        """Every URL in the store: pages and checked URLs, which may not be pages."""
        rows = self._connection.execute("SELECT url FROM pages UNION SELECT url FROM results")
        return frozenset(row[0] for row in rows)

    # Meta

    def get_meta(self, key: str) -> str | None:
        row = self._connection.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return None if row is None else row[0]

    def set_meta(self, key: str, value: str) -> None:
        if not isinstance(key, str) or not key:
            raise ValueError("meta key must be a non-empty string")
        if not isinstance(value, str):
            raise ValueError("meta value must be a string")
        self._connection.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?) "
            "ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    # Runs

    def create_run(
        self,
        label: str | None = None,
        servo_rev: str | None = None,
        servo_binary: str | os.PathLike[str] | None = None,
        reference_engine: str | None = None,
        notes: str | None = None,
    ) -> str:
        """Record a new run and return its id, ``yyyymmddThhmmssZ-xxxx`` (UTC start, 4 hex)."""
        started = self._clock()
        binary = None if servo_binary is None else os.fspath(servo_binary)
        for _ in range(_RUN_ID_ATTEMPTS):
            run_id = f"{_utc(started):%Y%m%dT%H%M%SZ}-{secrets.token_hex(2)}"
            try:
                self._connection.execute(
                    "INSERT INTO runs (id, started, servo_rev, servo_binary, label, "
                    "reference_engine, notes) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (run_id, _iso(started), servo_rev, binary, label, reference_engine, notes),
                )
            except sqlite3.IntegrityError:
                continue  # The id is taken; draw another suffix.
            return run_id
        raise StoreError("Could not allocate a unique run id")

    def finish_run(self, run_id: str) -> None:
        cursor = self._connection.execute(
            "UPDATE runs SET finished = ? WHERE id = ? AND finished IS NULL",
            (self._now(), run_id),
        )
        if cursor.rowcount == 1:
            return
        self._require_run(run_id)
        raise StoreError(f"Run {run_id} is already finished")

    def get_run(self, run_id: str) -> RunRecord | None:
        row = self._connection.execute(
            "SELECT id, started, finished, servo_rev, servo_binary, label, reference_engine, "
            "notes FROM runs WHERE id = ?",
            (run_id,),
        ).fetchone()
        return None if row is None else RunRecord(**dict(row))

    def runs_to_prune(self, started_before: datetime) -> list[str]:
        """Runs whose artifacts may go: started before the cutoff, and neither the latest
        run of their label (the base of the next comparison) nor where an open issue
        was first seen (its evidence)."""
        rows = self._connection.execute(
            """
            SELECT id FROM runs
            WHERE started < ?
                AND id NOT IN (SELECT first_seen_run FROM issues WHERE status = ?)
                AND id NOT IN (
                    SELECT id FROM (
                        SELECT id, ROW_NUMBER() OVER (
                            PARTITION BY label ORDER BY started DESC, rowid DESC
                        ) AS position FROM runs
                    ) WHERE position = 1
                )
            ORDER BY started, rowid
            """,
            (_iso(started_before), IssueStatus.OPEN.value),
        ).fetchall()
        return [row[0] for row in rows]

    def latest_run_id(self, label: str | None = None) -> str | None:
        """The most recently started run, finished or not; only runs with ``label`` if given."""
        row = self._connection.execute(
            "SELECT id FROM runs WHERE ? IS NULL OR label = ? "
            "ORDER BY started DESC, rowid DESC LIMIT 1",
            (label, label),
        ).fetchone()
        return None if row is None else row[0]

    def previous_run_id(self, run_id: str) -> str | None:
        """The run with the same label that started before ``run_id``, for comparisons.

        Only same-label runs are comparable: an ad hoc run checks different pages
        than the nightly one. A missing label matches other unlabeled runs.
        """
        self._require_run(run_id)
        row = self._connection.execute(
            """
            SELECT earlier.id FROM runs AS earlier, runs AS current
            WHERE current.id = ?
                AND earlier.label IS current.label
                AND (earlier.started < current.started
                    OR (earlier.started = current.started AND earlier.rowid < current.rowid))
            ORDER BY earlier.started DESC, earlier.rowid DESC
            LIMIT 1
            """,
            (run_id,),
        ).fetchone()
        return None if row is None else row[0]

    # Results

    def record_result(
        self,
        run_id: str,
        url: str,
        attempt: int,
        verdict: VerdictKind | str,
        severity: int,
        signatures: Sequence[str],
        metrics: Mapping[str, Any] | None,
        artifacts: str | os.PathLike[str] | None,
        duration_ms: int,
    ) -> None:
        """Store one attempt at one page. Attempts are numbered from 1 within a run."""
        verdict_value = VerdictKind(verdict).value
        _require_int(attempt, "attempt", minimum=1)
        _require_int(severity, "severity", minimum=_SEVERITY_MIN, maximum=_SEVERITY_MAX)
        _require_int(duration_ms, "duration_ms", minimum=0)
        if not isinstance(url, str) or not url:
            raise ValueError("url must be a non-empty string")
        if isinstance(signatures, str) or not all(isinstance(s, str) for s in signatures):
            raise ValueError("signatures must be a sequence of strings")
        if metrics is not None and not isinstance(metrics, Mapping):
            raise ValueError("metrics must be a mapping or None")

        try:
            self._connection.execute(
                f"INSERT INTO results ({_RESULT_COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    run_id,
                    url,
                    attempt,
                    verdict_value,
                    severity,
                    _json(list(signatures)),
                    None if metrics is None else _json(dict(metrics)),
                    None if artifacts is None else os.fspath(artifacts),
                    duration_ms,
                    self._now(),
                ),
            )
        except sqlite3.IntegrityError as exc:
            self._require_run(run_id)
            raise StoreError(f"Run {run_id} already has attempt {attempt} for this page") from exc

    def results_for_run(self, run_id: str) -> list[ResultRecord]:
        """Every attempt in a run, ordered by URL and attempt."""
        self._require_run(run_id)
        rows = self._connection.execute(
            f"SELECT {_RESULT_COLUMNS} FROM results WHERE run_id = ? ORDER BY url, attempt",
            (run_id,),
        ).fetchall()
        return [_result_record(row) for row in rows]

    def outcomes_for_run(self, run_id: str) -> dict[str, ResultRecord]:
        """Each page's outcome in a run (its highest attempt), keyed by URL."""
        self._require_run(run_id)
        rows = self._connection.execute(
            f"""
            SELECT {_RESULT_COLUMNS} FROM (
                SELECT {_RESULT_COLUMNS},
                    ROW_NUMBER() OVER (PARTITION BY url ORDER BY attempt DESC) AS position
                FROM results WHERE run_id = ?
            ) WHERE position = 1 ORDER BY url
            """,
            (run_id,),
        ).fetchall()
        return {row["url"]: _result_record(row) for row in rows}

    # Issues

    def upsert_issues(self, run_id: str, sightings: Iterable[IssueSighting]) -> IssueUpdate:
        """Fold a run's finding signatures into the issue list.

        A new signature becomes an open issue. A signature marked fixed no later than
        this run started, and seen in it, is reopened: the fix did not hold. ``wontfix``
        and ``upstream`` issues keep their status. First/last-seen runs follow run
        order, so recording an older run does not move ``last_seen_run`` back.
        """
        by_signature: dict[str, tuple[str, set[str]]] = {}
        for sighting in sightings:
            if not sighting.signature or not sighting.url:
                raise ValueError("an issue sighting needs a signature and a URL")
            kind = VerdictKind(sighting.kind).value
            _, urls = by_signature.setdefault(sighting.signature, (kind, set()))
            urls.add(sighting.url)

        new: list[str] = []
        reopened: list[str] = []
        with self._transaction():
            run = self._require_run(run_id)
            run_key = (run["started"], run["run_order"])
            for signature, (kind, urls) in sorted(by_signature.items()):
                existing = self._connection.execute(
                    "SELECT first_seen_run, last_seen_run, status, status_changed "
                    "FROM issues WHERE signature = ?",
                    (signature,),
                ).fetchone()
                if existing is None:
                    self._connection.execute(
                        "INSERT INTO issues (signature, kind, first_seen_run, last_seen_run, "
                        "status, status_changed) VALUES (?, ?, ?, ?, ?, ?)",
                        (signature, kind, run_id, run_id, IssueStatus.OPEN.value, self._now()),
                    )
                    new.append(signature)
                else:
                    first_seen = existing["first_seen_run"]
                    last_seen = existing["last_seen_run"]
                    if run_key < self._run_key(first_seen):
                        first_seen = run_id
                    if run_key > self._run_key(last_seen):
                        last_seen = run_id
                    status = existing["status"]
                    # Timestamps have one-second resolution. A run that started in
                    # the same second as the fix counts as after it: a needless
                    # reopen is cheaper than a hidden recurrence.
                    fixed_before_run = (
                        status == IssueStatus.FIXED.value
                        and run["started"] >= existing["status_changed"]
                    )
                    if fixed_before_run:
                        status = IssueStatus.OPEN.value
                        reopened.append(signature)
                    self._connection.execute(
                        "UPDATE issues SET first_seen_run = ?, last_seen_run = ?, status = ?, "
                        "status_changed = CASE WHEN status = ? THEN status_changed ELSE ? END "
                        "WHERE signature = ?",
                        (first_seen, last_seen, status, status, self._now(), signature),
                    )
                self._connection.executemany(
                    "INSERT OR IGNORE INTO issue_pages (signature, url, run_id) VALUES (?, ?, ?)",
                    [(signature, url, run_id) for url in sorted(urls)],
                )
        return IssueUpdate(new=tuple(new), reopened=tuple(reopened), seen=len(by_signature))

    def mark_issue(
        self, signature: str, status: IssueStatus | str, note: str | None = None
    ) -> None:
        """Set an issue's status; a note is appended to its notes with a timestamp.

        ``status_changed`` moves only when the status does, so adding a note to a
        fixed issue does not shift the point after which a sighting reopens it.
        """
        status_value = IssueStatus(status).value
        now = self._now()
        entry = None if note is None else f"{now} {status_value}: {note}"
        cursor = self._connection.execute(
            """
            UPDATE issues SET
                status = :status,
                status_changed = CASE WHEN status = :status THEN status_changed ELSE :now END,
                notes = CASE WHEN :entry IS NULL THEN notes
                    ELSE COALESCE(notes || char(10), '') || :entry END
            WHERE signature = :signature
            """,
            {"status": status_value, "now": now, "entry": entry, "signature": signature},
        )
        if cursor.rowcount == 0:
            raise StoreError("No issue has that signature")

    def issues(self, status: IssueStatus | str | None = None) -> list[IssueRecord]:
        """Issues ordered by signature, optionally only those with ``status``."""
        status_value = None if status is None else IssueStatus(status).value
        rows = self._connection.execute(
            """
            SELECT issues.signature, issues.kind, issues.status, issues.status_changed,
                issues.first_seen_run, issues.last_seen_run, issues.notes,
                (SELECT COUNT(DISTINCT url) FROM issue_pages
                    WHERE issue_pages.signature = issues.signature) AS url_count
            FROM issues
            WHERE ? IS NULL OR issues.status = ?
            ORDER BY issues.signature
            """,
            (status_value, status_value),
        ).fetchall()
        return [IssueRecord(**dict(row)) for row in rows]

    # Internals

    def _now(self) -> str:
        return _iso(self._clock())

    @contextmanager
    def _transaction(self) -> Iterator[None]:
        # IMMEDIATE takes the write lock up front, so a concurrent reader-turned-
        # writer cannot make this transaction fail halfway with SQLITE_BUSY.
        self._connection.execute("BEGIN IMMEDIATE")
        try:
            yield
        except BaseException:
            self._connection.execute("ROLLBACK")
            raise
        self._connection.execute("COMMIT")

    def _require_run(self, run_id: str) -> sqlite3.Row:
        row = self._connection.execute(
            "SELECT id, started, rowid AS run_order FROM runs WHERE id = ?", (run_id,)
        ).fetchone()
        if row is None:
            raise StoreError(f"No run has id {run_id!r}")
        return row

    def _run_key(self, run_id: str) -> tuple[str, int]:
        """Sort key matching run order: start time, then insertion order."""
        row = self._require_run(run_id)
        return row["started"], row["run_order"]


def _ensure_private_file(path: Path) -> None:
    """Create the database file owner-only, or tighten an existing one."""
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, _FILE_MODE)
    except FileExistsError:
        if stat.S_IMODE(path.stat().st_mode) & ~_FILE_MODE:
            os.chmod(path, _FILE_MODE)
        return
    try:
        # The umask can only remove bits; set the mode exactly in case it removed ours.
        os.fchmod(descriptor, _FILE_MODE)
    finally:
        os.close(descriptor)


def _migrate(connection: sqlite3.Connection) -> None:
    version = _user_version(connection)
    if version > SCHEMA_VERSION:
        raise StoreError(
            f"The store uses schema version {version}, newer than this sitecheck's "
            f"{SCHEMA_VERSION}; update sitecheck"
        )
    while version < SCHEMA_VERSION:
        connection.execute("BEGIN IMMEDIATE")
        try:
            # Another process may have migrated while this one waited for the lock.
            version = _user_version(connection)
            if version < SCHEMA_VERSION:
                connection.executescript(_MIGRATIONS[version])
                version += 1
                # PRAGMA takes no bound parameters; version is an int from this module.
                connection.execute(f"PRAGMA user_version = {version:d}")
        except BaseException:
            connection.execute("ROLLBACK")
            raise
        connection.execute("COMMIT")


def _user_version(connection: sqlite3.Connection) -> int:
    return int(connection.execute("PRAGMA user_version").fetchone()[0])


def _page_values(page: PageRow) -> dict[str, Any]:
    if not isinstance(page, PageRow):
        raise ValueError("a page row must be a PageRow")
    if not isinstance(page.url, str) or not page.url:
        raise ValueError("a page row needs a non-empty url")
    for name in ("host", "site", "reason"):
        if not isinstance(getattr(page, name), str):
            raise ValueError(f"page row field {name} must be a string")
    if page.template is not None and not isinstance(page.template, str):
        raise ValueError("page row field template must be a string or None")
    if not isinstance(page.eligible, bool):
        raise ValueError("page row field eligible must be a bool")
    for name in ("hister_updated", "add_count"):
        if getattr(page, name) is not None:
            _require_int(getattr(page, name), name, minimum=0)
    return {
        "url": page.url,
        "host": page.host,
        "site": page.site,
        "template": page.template,
        "source": PageSource(page.source).value,
        "eligible": int(page.eligible),
        "reason": page.reason,
        "hister_updated": page.hister_updated,
        "add_count": page.add_count,
    }


def _result_record(row: sqlite3.Row) -> ResultRecord:
    return ResultRecord(
        run_id=row["run_id"],
        url=row["url"],
        attempt=row["attempt"],
        verdict=row["verdict"],
        severity=row["severity"],
        signatures=json.loads(row["signatures"]),
        metrics=None if row["metrics"] is None else json.loads(row["metrics"]),
        artifacts=row["artifacts"],
        duration_ms=row["duration_ms"],
        recorded=row["recorded"],
    )


def _json(value: Any) -> str:
    # NaN and infinity are not JSON; refuse them rather than store unreadable text.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _require_int(
    value: Any, name: str, *, minimum: int | None = None, maximum: int = _SQLITE_INTEGER_MAX
) -> None:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    if minimum is not None and value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    if value > maximum:
        raise ValueError(f"{name} must be at most {maximum}")


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _utc(moment: datetime) -> datetime:
    if moment.tzinfo is None:
        raise ValueError("the store's clock must return timezone-aware datetimes")
    return moment.astimezone(UTC)


def _iso(moment: datetime) -> str:
    return f"{_utc(moment):%Y-%m-%dT%H:%M:%SZ}"
