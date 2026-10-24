"""The `sitecheck` command line. See README.md for the workflow and every command.

Exit codes: 0 success; 1 findings (check, leakcheck), a failed check (doctor) or a run
that could not do its job; 2 a usage or configuration error; 3 `check` judged no
failure but could not judge every page; 128 + N when stopped by signal N. Commands
that load pages or write the store hold the data directory's lock, so two of them
never compete for the machine's memory or the store.

No servoshell outlives the command: an atexit handler and the SIGINT, SIGTERM and
SIGHUP handlers kill every live servoshell process group, servo.py's supervisor kills
the group when this process dies without running them, and leaving the Chromium
context manager stops Playwright's driver and its browser. The Hister settings are
removed from this process's environment once read, so no child inherits the token.
"""

from __future__ import annotations

import argparse
import atexit
import contextlib
import dataclasses
import functools
import io
import itertools
import json
import logging
import os
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import time
from collections import Counter
from collections.abc import Callable, Iterator, Mapping, Sequence
from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path
from typing import Any, NoReturn, TextIO
from urllib.parse import urlsplit
from urllib.request import url2pathname

from sitecheck import (
    config,
    hister,
    images,
    leakcheck,
    reference,
    report,
    runner,
    selection,
    servo,
    store,
    urlpolicy,
)
from sitecheck.model import VerdictKind

EXIT_OK = 0
EXIT_FAILURE = 1
EXIT_USAGE = 2
EXIT_NOT_JUDGED = 3  # `check`: no failure, but some pages could not be judged.
_SIGNAL_EXIT_BASE = 128
_STOP_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)

# About an hour of pages at two engines and ~6 s each per engine, with retries.
DEFAULT_BUDGET = 60
LATEST = "latest"

# Ad hoc checks keep their artifacts here for a week, so a screenshot can still be
# looked at after the command, and then they are removed like any history-derived file.
_CHECKS_DIR_NAME = "checks"
_CHECK_RETENTION = timedelta(days=7)
_CHECK_LABEL = "check"

# URLs named on the command line skip the history-oriented exclusions (private hosts,
# odd ports and file-like paths are how reduced test cases are served), but never
# these: a deny-listed site, credentials, the Hister server or an action URL.
_REFUSED_NAMED_REASONS = frozenset(
    {
        urlpolicy.Reason.INVALID,
        urlpolicy.Reason.SCHEME,
        urlpolicy.Reason.USERINFO,
        urlpolicy.Reason.HISTER_HOST,
        urlpolicy.Reason.DENIED,
        urlpolicy.Reason.SENSITIVE,
    }
)

_DISK_FAIL_BYTES = 1 << 30  # A run writes about 1 MB of screenshots and logs per page.
_DISK_WARN_BYTES = 5 << 30
_SUBPROCESS_TIMEOUT_S = 30
_EXCLUDED_KEY_PREFIX = "excluded:"
_GIB = 1 << 30

# Run directories (screenshots, logs, page text: all history-derived) are removed
# after this long, except the latest run of each label and runs where an open issue
# was first seen. Their store rows stay.
_RUN_RETENTION = timedelta(days=30)

# A run that could judge at most this share of its pages (the rest had no baseline
# or failed in the harness: no network, a broken Chromium) did not do its job.
_UNJUDGED_FAILURE_SHARE = 0.5
_UNJUDGED_RUN_VERDICTS = frozenset({VerdictKind.REFERENCE_FAILED, VerdictKind.HARNESS_ERROR})

# Planned pages are resolved before they are loaded: a history host that resolves to
# a private address (a router, a NAS, a tailnet machine) is excluded for good. When
# more than this share of a plan does, the resolver is lying for everything (a
# proxy's fake-IP mode, a captive portal) and the run stops instead.
_PRIVATE_RESOLUTION_REASON = "resolves to a private address"
_MAX_PRIVATE_RESOLUTION_SHARE = 0.5
_PLAN_ROUNDS = 5


class _UsageError(Exception):
    """The command line is invalid; argparse's message, shown with the usage line."""


class _Stopped(BaseException):
    """A stop signal arrived. BaseException, so no `except Exception` swallows it."""

    def __init__(self, signal_number: int) -> None:
        super().__init__(signal_number)
        self.signal_number = signal_number


@dataclass(frozen=True)
class ServoIdentity:
    """What the run record and the user agent need to know about a servoshell build."""

    version: str
    user_agent: str
    revision: str | None  # The checkout's commit; None when git could not tell.


def identify_servo(binary: Path) -> ServoIdentity:
    """Read the version and revision of the checkout `binary` was built in."""
    checkout = config.servo_checkout(binary)
    version = config.servo_version(checkout)
    return ServoIdentity(version, config.user_agent(version), _git_revision(checkout))


def resolve_addresses(host: str) -> list[str]:
    """The addresses `host` resolves to; raises OSError when it does not resolve."""
    infos = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    return sorted({str(info[4][0]) for info in infos})


@contextlib.contextmanager
def open_chromium(
    data: Path, user_agent: str, policy: reference.CachePolicy
) -> Iterator[runner.ReferenceSource]:
    """Chromium for one command, with the reference cache pruned of expired entries."""
    with reference.ChromiumReference() as chromium:
        reference.prune_reference_cache(data)
        yield runner.ChromiumSource(chromium, data, user_agent=user_agent, policy=policy)


@dataclass
class Runtime:
    """What the commands take from the outside world. Tests replace parts of it."""

    environ: Mapping[str, str] = field(default_factory=lambda: os.environ)
    stdout: TextIO = field(default_factory=lambda: sys.stdout)
    stderr: TextIO = field(default_factory=lambda: sys.stderr)
    now: Callable[[], datetime] = field(default=lambda: datetime.now(UTC))
    servo_identity: Callable[[Path], ServoIdentity] = identify_servo
    servo_engine: Callable[[Path, str], runner.ServoEngine] = runner.ServoshellEngine
    open_reference: Callable[
        [Path, str, reference.CachePolicy], AbstractContextManager[runner.ReferenceSource]
    ] = open_chromium
    compare_images: runner.ImageComparer | None = images.compare_screenshots
    images_available: Callable[[], bool] = images.available
    browser_version: Callable[[], str] = reference.ensure_browser
    resolve: Callable[[str], list[str]] = resolve_addresses
    webdriver_exposure: Callable[[Path, str], str | None] = servo.check_webdriver_exposure
    handle_signals: bool = True  # Off in tests, which must not replace global handlers.


@dataclass(frozen=True)
class _UrlPolicy:
    """The URL policy as configured now: the Hister host and the deny list.

    Pages are classified when they enter the store, so a deny entry added later, a new
    Hister host or a stricter policy reaches stored pages only through `reclassify`,
    which every page-loading command runs first.
    """

    hister_host: str | None
    deny: tuple[str, ...]

    @classmethod
    def load(cls, rt: Runtime) -> _UrlPolicy:
        deny = config.load_deny_list(config.deny_list_path(config.config_dir(rt.environ)))
        return cls(_hister_host(rt), deny)

    def classify(self, url: str) -> urlpolicy.Decision:
        return urlpolicy.classify(url, self.hister_host, self.deny)

    def refusal(self, url: str, target: runner.RunTarget) -> str | None:
        """Why the page planned as `target` may not end up at `url` (runner.NavigationPolicy).

        A page from the history must stay eligible; a URL someone named only must
        not reach the refusals that apply to named URLs.
        """
        decision = self.classify(url)
        if target.bucket is None:
            return decision.reason.value if decision.reason in _REFUSED_NAMED_REASONS else None
        return None if decision.eligible else decision.reason.value

    def reclassify(self, db: store.Store) -> int:
        """Bring every stored page's classification up to date; return how many changed."""
        changes = []
        for page in db.page_classifications():
            if page.url.startswith(_EXCLUDED_KEY_PREFIX):
                continue  # Stored by hash: excluded when it was read, and stays so.
            decision = self.classify(page.url)
            current = store.PageClassification(
                page.url, decision.eligible, decision.reason.value, decision.template or None
            )
            if not decision.eligible:
                current = dataclasses.replace(current, template=page.template)
            if current != page:
                changes.append(current)
        db.reclassify_pages(changes)
        return len(changes)


def main(argv: Sequence[str] | None = None, runtime: Runtime | None = None) -> int:
    rt = runtime or Runtime()
    if rt.environ is os.environ:
        # The commands read their settings from a copy; child processes (servoshell,
        # Playwright's driver and Chromium, git) inherit the process environment,
        # which no longer holds the Hister settings.
        rt.environ = dict(os.environ)
        config.scrub_secrets(os.environ)
    parser = _parser()
    try:
        args = parser.parse_args(argv)
    except _UsageError as error:
        print(parser.format_usage().rstrip(), file=rt.stderr)
        print(f"sitecheck: error: {error}", file=rt.stderr)
        return EXIT_USAGE
    except SystemExit as exit_request:  # --help
        return int(exit_request.code or 0)

    handler = logging.StreamHandler(rt.stderr)
    handler.setFormatter(logging.Formatter("%(levelname)s: %(message)s"))
    package_logger = logging.getLogger("sitecheck")
    package_logger.addHandler(handler)
    try:
        with _engine_cleanup(rt.handle_signals):
            return args.handler(args, rt)
    except _Stopped as stop:
        print(f"sitecheck: stopped by signal {stop.signal_number}", file=rt.stderr)
        return _SIGNAL_EXIT_BASE + stop.signal_number
    except KeyboardInterrupt:
        servo.terminate_all()
        print("sitecheck: interrupted", file=rt.stderr)
        return _SIGNAL_EXIT_BASE + signal.SIGINT
    except _UsageError as error:
        print(f"sitecheck: error: {error}", file=rt.stderr)
        return EXIT_USAGE
    except (
        config.ConfigError,
        hister.HisterConfigError,
        store.StoreError,
        leakcheck.LeakcheckError,
        reference.ReferenceUnavailable,
        images.ImagesUnavailable,
    ) as error:
        print(f"sitecheck: {error}", file=rt.stderr)
        return EXIT_USAGE
    except (hister.HisterError, runner.RunAborted) as error:
        print(f"sitecheck: {error}", file=rt.stderr)
        return EXIT_FAILURE
    except (OSError, sqlite3.Error) as error:
        # A full disk, a vanished directory, a damaged store: one line, no traceback.
        # Messages name files in the data directory (page keys), never page URLs.
        print(f"sitecheck: {type(error).__name__}: {error}", file=rt.stderr)
        return EXIT_FAILURE
    finally:
        package_logger.removeHandler(handler)


# --- Commands -----------------------------------------------------------------------


def _cmd_sync(args: argparse.Namespace, rt: Runtime) -> int:
    settings = config.load_hister_settings(environ=rt.environ, warnings=rt.stderr)
    config_path = config.config_dir(rt.environ)
    if settings is None:
        raise config.ConfigError(
            f"Hister is not configured: set {config.HISTER_URL_KEY} and "
            f"{config.HISTER_TOKEN_KEY} in {config_path / config.HISTER_ENV_NAME}"
        )
    deny = config.load_deny_list(config.deny_list_path(config_path))
    client = hister.HisterClient(settings.url, settings.token)
    policy = _UrlPolicy(urlsplit(client.base_url).hostname, deny)

    data = _data_dir(rt)
    with config.run_lock(data), _open_store(data, rt) as db:
        sync_started = int(rt.now().timestamp())
        last_sync = db.get_meta(hister.LAST_SYNC_META_KEY)
        date_from = None
        if not args.full and last_sync is not None:
            date_from = hister.incremental_date_from(_meta_int(last_sync))

        stats = hister.HisterStats()
        rows: dict[str, store.PageRow] = {}
        visits: list[store.DocumentVisit] = []
        reasons: Counter[str] = Counter()
        failure: hister.HisterError | None = None
        try:
            documents = client.iter_documents(
                date_from=date_from, max_pages=args.max_pages, stats=stats
            )
            for document in documents:
                decision = policy.classify(document.url)
                reasons[decision.reason.value] += 1
                row = _history_row(document, decision)
                if row is None:
                    continue
                rows[row.url] = row
                # Counted per document, so a sync that rereads only some of a page's
                # documents keeps the others' visits.
                visits.append(
                    store.DocumentVisit(
                        config.page_key(document.url), row.url, document.add_count, document.updated
                    )
                )
        except hister.HisterError as error:
            failure = error  # Keep what was read; the next sync reads it again.

        counts = db.upsert_pages(rows.values())
        db.record_visits(visits)
        policy.reclassify(db)
        # A sync that stopped at the page cap still read the newest history; the
        # next one continues incrementally rather than re-reading the same pages.
        # One stopped by an error or a looping cursor is repeated in full next time.
        if failure is None and stats.stop_reason in (hister.StopReason.END, hister.StopReason.CAP):
            db.set_meta(hister.LAST_SYNC_META_KEY, str(sync_started))

    kind = "full" if date_from is None else "incremental"
    excluded = sum(count for reason, count in reasons.items() if reason != "ok")
    print(
        f"{kind} sync: {stats.documents} documents in {stats.pages} pages "
        f"({stats.skipped} skipped); {reasons.get('ok', 0)} eligible, {excluded} excluded; "
        f"store: {counts.new} new, {counts.updated} updated",
        file=rt.stdout,
    )
    if excluded:
        breakdown = ", ".join(
            f"{reason} {count}" for reason, count in sorted(reasons.items()) if reason != "ok"
        )
        print(f"excluded by reason: {breakdown}", file=rt.stdout)
    if stats.cap_hit:
        print(
            f"warning: stopped at {args.max_pages} pages of history; older history was not "
            "read (sync --full --max-pages N reads more)",
            file=rt.stderr,
        )
    if failure is not None:
        raise failure
    if stats.stop_reason is hister.StopReason.CURSOR_REPEAT:
        print("sitecheck: Hister repeated a paging cursor; the sync is incomplete", file=rt.stderr)
        return EXIT_FAILURE
    return EXIT_OK


def _cmd_seed(args: argparse.Namespace, rt: Runtime) -> int:
    config_path = config.config_dir(rt.environ)
    seed_file = config.BUNDLED_SEEDS_PATH if args.file is None else Path(args.file)
    if not seed_file.is_file():
        raise config.ConfigError(f"no seed file at {seed_file}")
    urls = config.load_seed_urls(seed_file) + config.load_seed_urls(
        config.private_seeds_path(config_path)
    )
    policy = _UrlPolicy.load(rt)

    rows = []
    for url in urls:
        decision = policy.classify(url)
        if not decision.eligible or decision.normalized is None:
            print(f"skipping seed {url}: {decision.reason}", file=rt.stderr)
            continue
        rows.append(
            store.PageRow(
                url=decision.normalized,
                host=decision.host,
                site=decision.site,
                template=decision.template or None,
                source=store.PageSource.SEED,
                eligible=True,
                reason=decision.reason.value,
            )
        )

    data = _data_dir(rt)
    with config.run_lock(data), _open_store(data, rt) as db:
        counts = db.upsert_pages(rows)
        policy.reclassify(db)  # Seeds stored before a deny entry was added.
    print(
        f"seeds: {len(rows)} eligible of {len(urls)}; store: {counts.new} new, "
        f"{counts.updated} updated",
        file=rt.stdout,
    )
    return EXIT_OK


def _cmd_select(args: argparse.Namespace, rt: Runtime) -> int:
    data = _data_dir(rt)
    policy = _UrlPolicy.load(rt)
    with _open_store(data, rt) as db:
        # A preview: the current policy filters pages here; `run` also stores it.
        pages = _selection_pages(db, policy)
    now = rt.now()
    seed = args.seed if args.seed is not None else f"{now:%Y-%m-%d}"
    plan = selection.select(pages, args.budget, now, rng_seed=seed)

    if args.json:
        json.dump(
            [{"url": item.url, "site": item.site, "bucket": item.bucket.value} for item in plan],
            rt.stdout,
            indent=2,
        )
        print(file=rt.stdout)
        return EXIT_OK
    for item in plan:
        print(f"{item.bucket.value:<9} {item.site:<30} {item.url}", file=rt.stdout)
    print(f"{len(plan)} of {len(pages)} eligible pages planned", file=rt.stderr)
    return EXIT_OK


def _cmd_run(args: argparse.Namespace, rt: Runtime) -> int:
    sources = sum((args.budget is not None, args.urls is not None, bool(args.url)))
    if sources > 1:
        raise _UsageError("give at most one of --budget, --urls and URLs")
    binary = _servo_binary(args.servo, rt)
    identity = rt.servo_identity(binary)
    named = _named_urls(args)
    use_reference = not args.no_reference
    if use_reference and not rt.images_available():
        raise images.ImagesUnavailable(
            "Pillow is not installed; run through the uv project (uv sync), or pass --no-reference"
        )

    data = _data_dir(rt)
    with config.run_lock(data), _open_store(data, rt) as db:
        _prune_old_runs(db, data, rt)
        _require_free_space(data)
        url_policy = _UrlPolicy.load(rt)
        url_policy.reclassify(db)
        compare_to = _comparison_before_run(db, args.compare)
        targets: list[runner.RunTarget] = []
        if named is not None:
            targets, rows = _named_targets(named, url_policy)
            db.upsert_pages(rows)
        elif not db.selection_rows():
            raise config.ConfigError(
                "the store has no eligible pages: run `sitecheck seed` or `sitecheck sync` first"
            )

        policy = (
            reference.CachePolicy.REFRESH if args.fresh_reference else reference.CachePolicy.USE
        )
        with _reference_or_none(rt, use_reference, data, identity.user_agent, policy) as ref:
            run_id = db.create_run(
                label=args.label,
                servo_rev=identity.revision,
                servo_binary=str(binary),
                reference_engine=ref.name if ref is not None else None,
            )
            if named is None:
                budget = DEFAULT_BUDGET if args.budget is None else args.budget
                targets = _planned_targets(db, rt, run_id, budget, url_policy)
            print(f"run {run_id}: {len(targets)} pages", file=rt.stderr)
            previous = compare_to or db.previous_run_id(run_id)
            try:
                summary = runner.run(
                    db,
                    data,
                    run_id,
                    targets,
                    _engines(rt, binary, identity, ref),
                    policy=runner.AttemptPolicy.retrying(args.retries),
                    time_budget=None if args.minutes is None else timedelta(minutes=args.minutes),
                    progress=rt.stderr,
                    scope=runner.SiteScope(config.signature_key(data)),
                    navigation=url_policy,
                )
            except runner.RunAborted:
                # The pages checked so far are recorded; their report says the run
                # did not finish.
                with contextlib.suppress(OSError, store.StoreError):
                    report.write_report(db, data, run_id, compare_to=previous)
                raise
        files = report.write_report(db, data, run_id, compare_to=previous)

    _print_summary(summary, files, rt)
    unjudged = sum(
        count
        for verdict, count in summary.verdicts.items()
        if VerdictKind(verdict) in _UNJUDGED_RUN_VERDICTS
    )
    if summary.checked and unjudged > summary.checked * _UNJUDGED_FAILURE_SHARE:
        print(
            f"sitecheck: {unjudged} of {summary.checked} pages could not be judged (no baseline "
            "from the reference, or a harness failure); see the report and servo.log files",
            file=rt.stderr,
        )
        return EXIT_FAILURE
    return EXIT_OK


def _cmd_check(args: argparse.Namespace, rt: Runtime) -> int:
    binary = _servo_binary(args.servo, rt)
    identity = rt.servo_identity(binary)
    use_reference = not args.no_reference
    if use_reference and not rt.images_available():
        raise images.ImagesUnavailable(
            "Pillow is not installed; run through the uv project (uv sync), or pass --no-reference"
        )
    url_policy = _UrlPolicy.load(rt)
    targets, rows = _named_targets(args.url, url_policy)
    attempt_policy = runner.AttemptPolicy.repeating(args.repeat)
    # A check confirms a bug or a fix now: it never trusts a reference cached days
    # ago, and a reduced test case changes between checks.
    policy = reference.CachePolicy.REFRESH

    data = _data_dir(rt)
    with (
        config.run_lock(data),
        _reference_or_none(rt, use_reference, data, identity.user_agent, policy) as ref,
    ):
        _require_free_space(data)
        engines = _engines(rt, binary, identity, ref)
        if args.record:
            records = _check_recorded(
                rt, data, targets, rows, engines, attempt_policy, args, binary, identity, url_policy
            )
        else:
            records = _check_unrecorded(rt, data, targets, engines, attempt_policy, url_policy)

    if args.json:
        json.dump(records, rt.stdout, indent=2)
        print(file=rt.stdout)
    else:
        for record in records:
            _print_check_record(record, rt.stdout)
    verdicts = [VerdictKind(record["verdict"]) for record in records]
    if any(verdict.is_failure for verdict in verdicts):
        return EXIT_FAILURE
    unjudged = [verdict for verdict in verdicts if not verdict.triaged]
    if unjudged:
        kinds = ", ".join(sorted({verdict.value for verdict in unjudged}))
        print(
            f"sitecheck: {len(unjudged)} of {len(verdicts)} pages were not judged ({kinds}); "
            "no failure was found, but nothing was confirmed either",
            file=rt.stderr,
        )
        return EXIT_NOT_JUDGED
    return EXIT_OK


def _cmd_report(args: argparse.Namespace, rt: Runtime) -> int:
    data = _data_dir(rt)
    with _open_store(data, rt) as db:
        run_id = _resolve_run(db, args.run, args.label)
        compare_to = _resolve_run(db, args.compare) if args.compare else db.previous_run_id(run_id)
        files = report.write_report(db, data, run_id, compare_to=compare_to)
    print(files.markdown, file=rt.stdout)
    print(files.html, file=rt.stdout)
    return EXIT_OK


def _cmd_issues(args: argparse.Namespace, rt: Runtime) -> int:
    data = _data_dir(rt)
    with _open_store(data, rt) as db:
        run_id = _resolve_run(db, args.run, args.label)
        run = db.get_run(run_id)
        assert run is not None
        outcomes = db.outcomes_for_run(run_id)
        queue = report.triage_queue(db, data, run_id, include_closed=args.all)

    finished = run["finished"] is not None
    if args.json:
        payload = {
            "run_id": run_id,
            "label": run["label"],
            # An unfinished run was interrupted or aborted: its issues are incomplete.
            "finished": finished,
            "pages": len(outcomes),
            "verdicts": dict(Counter(outcome["verdict"] for outcome in outcomes.values())),
            "issues": queue,
        }
        json.dump(payload, rt.stdout, indent=2)
        print(file=rt.stdout)
        return EXIT_OK
    if not finished:
        print(f"sitecheck: run {run_id} did not finish; its issues are incomplete", file=rt.stderr)
    if not queue:
        print(f"run {run_id}: no issues", file=rt.stdout)
    for issue in queue:
        print(
            f"P{issue['priority']:<3} {issue['kind']:<16} sev {issue['severity']} "
            f"{issue['site_count']} sites {issue['page_count']} pages  {issue['signature']}"
            + ("" if issue["status"] == store.IssueStatus.OPEN.value else f"  [{issue['status']}]"),
            file=rt.stdout,
        )
    return EXIT_OK


def _cmd_urls(args: argparse.Namespace, rt: Runtime) -> int:
    """Print a run's pages whose final verdict is one of `--verdict`, one per line.

    After a fix, the nightly agent sweeps pages that were fine with `run --urls` to
    catch regressions. The list is history: keep it in the data directory, never in a
    repository. With `--limit`, every site gets a page before any site gets a second.
    """
    data = _data_dir(rt)
    with _open_store(data, rt) as db:
        run_id = _resolve_run(db, args.run, args.label)
        outcomes = db.outcomes_for_run(run_id)

    wanted = set(args.verdict or [VerdictKind.OK.value])
    by_site: dict[str, list[str]] = {}
    for url, outcome in outcomes.items():
        if outcome["verdict"] in wanted:
            by_site.setdefault(urlpolicy.site_of(urlsplit(url).hostname or ""), []).append(url)
    rounds = itertools.zip_longest(*by_site.values())
    urls = [url for round_ in rounds for url in round_ if url is not None]
    for url in urls[: args.limit] if args.limit is not None else urls:
        print(url, file=rt.stdout)
    return EXIT_OK


def _cmd_mark(args: argparse.Namespace, rt: Runtime) -> int:
    data = _data_dir(rt)
    # Under the lock, so a run in progress cannot interleave its issue updates.
    with config.run_lock(data), _open_store(data, rt) as db:
        db.mark_issue(args.signature, args.status, args.note)
    print(f"{args.signature}: {args.status}", file=rt.stdout)
    return EXIT_OK


def _cmd_leakcheck(args: argparse.Namespace, rt: Runtime) -> int:
    if args.git_range is None and not args.text:
        raise _UsageError("nothing to scan: pass --git-range and/or --text")
    db_path = config.state_db_path(config.data_dir(rt.environ))
    if not db_path.is_file():
        # Reporting "clean" here would hide that nothing was compared.
        raise config.ConfigError(
            f"no page inventory at {db_path}; run `sitecheck sync` or `sitecheck seed` first"
        )
    with store.open_store(db_path, clock=rt.now) as db:
        names = db.hosts_and_sites()
        urls = db.known_urls()
        derived = db.derived_hosts()
        hister_pages = db.page_count(store.PageSource.HISTER)
    seed_names, seed_urls = _bundled_seeds()
    terms = leakcheck.inventory(
        names.hosts | derived,
        names.sites,
        urls - seed_urls,
        allowed=leakcheck.ALLOWED_HOSTS | seed_names,
    )
    if not args.allow_empty_inventory:
        # Reporting "clean" against nothing would hide that nothing was compared: the
        # data directory was reset, swapped, or never synced.
        if terms.empty:
            raise config.ConfigError(
                f"the page inventory at {db_path} has no terms to look for; run "
                f"`sitecheck sync` first or check {config.DATA_DIR_ENV} "
                "(--allow-empty-inventory for a setup without history)"
            )
        hister_configured = config.load_hister_settings(environ=rt.environ, warnings=rt.stderr)
        if hister_configured is not None and hister_pages == 0:
            raise config.ConfigError(
                f"Hister is configured but {db_path} holds no pages from it: run "
                "`sitecheck sync` first (--allow-empty-inventory to scan anyway)"
            )

    repo = Path(args.repo)
    leaks: list[leakcheck.Leak] = []
    if args.git_range is not None:
        leaks += leakcheck.scan_git_range(
            repo, args.git_range, terms, exclude_refs=args.exclude_ref or ()
        )
    if args.text:
        # No exception for terms the repository already contains: a PR body that
        # names a site ties it to the fix.
        leaks += leakcheck.scan_files([Path(path) for path in args.text], terms)

    for leak in leaks:
        print(leak, file=rt.stdout)
    terms_count = len(terms.names) + sum(len(urls) for urls in terms.urls_by_host.values())
    print(f"leakcheck: {len(leaks)} findings against {terms_count} terms", file=rt.stderr)
    return EXIT_FAILURE if leaks else EXIT_OK


class _Health(StrEnum):
    OK = "ok"
    WARN = "warn"
    FAIL = "FAIL"


def _cmd_doctor(args: argparse.Namespace, rt: Runtime) -> int:
    results: list[tuple[_Health, str, str]] = []

    def report_check(health: _Health, name: str, detail: str) -> None:
        results.append((health, name, detail))
        print(f"{health.value:<5} {name}: {detail}", file=rt.stdout)

    data = config.data_dir(rt.environ)
    _doctor_data_dir(data, report_check)
    _doctor_store(data, rt, report_check)
    config_path = config.config_dir(rt.environ)
    _doctor_hister(rt, offline=args.offline, report_check=report_check)
    _doctor_lists(config_path, report_check)
    _doctor_servo(args.servo, rt, report_check)
    _doctor_reference(rt, report_check)
    _doctor_disk(data, report_check)

    failures = sum(1 for health, _, _ in results if health is _Health.FAIL)
    return EXIT_FAILURE if failures else EXIT_OK


# --- Command helpers ----------------------------------------------------------------


def _data_dir(rt: Runtime) -> Path:
    return config.ensure_private_dir(config.data_dir(rt.environ))


def _open_store(data: Path, rt: Runtime) -> store.Store:
    return store.open_store(config.state_db_path(data), clock=rt.now)


def _require_free_space(data: Path) -> None:
    free = shutil.disk_usage(data).free
    if free < _DISK_FAIL_BYTES:
        raise config.ConfigError(
            f"only {free / _GIB:.1f} GiB free at {data}; pages need at least "
            f"{_DISK_FAIL_BYTES / _GIB:.0f} GiB for screenshots and logs"
        )


def _prune_old_runs(db: store.Store, data: Path, rt: Runtime) -> None:
    removed = 0
    for run_id in db.runs_to_prune(rt.now() - _RUN_RETENTION):
        directory = config.run_dir(data, run_id)
        if directory.is_dir() and not directory.is_symlink():
            shutil.rmtree(directory)
            removed += 1
    if removed:
        print(
            f"removed the artifacts of {removed} runs older than {_RUN_RETENTION.days} days",
            file=rt.stderr,
        )


def _decided_signatures(db: store.Store) -> frozenset[str]:
    """Issues marked wontfix or upstream: their pages need no re-check."""
    decided = {store.IssueStatus.WONTFIX.value, store.IssueStatus.UPSTREAM.value}
    return frozenset(issue["signature"] for issue in db.issues() if issue["status"] in decided)


def _selection_pages(db: store.Store, policy: _UrlPolicy) -> list[selection.Page]:
    """The eligible pages under the current policy, with their current templates."""
    decided = _decided_signatures(db)
    pages = []
    for row in db.selection_rows():
        decision = policy.classify(row["url"])
        if not decision.eligible:
            continue
        current = {**row, "template": decision.template or None}
        pages.append(selection.Page.from_row(current, decided))
    return pages


def _hister_host(rt: Runtime) -> str | None:
    """The configured Hister server's host, which the URL policy excludes; no network."""
    settings = config.load_hister_settings(environ=rt.environ, warnings=rt.stderr)
    if settings is None:
        return None
    try:
        return urlsplit(settings.url).hostname
    except ValueError:
        return None


def _history_row(document: hister.HisterDoc, decision: urlpolicy.Decision) -> store.PageRow | None:
    """The store row for a history document, or None when it has no usable host.

    An excluded page is kept under a hash instead of its URL: its host still feeds the
    leak check, but the URL may carry a token or a search query that need not be kept.
    The visit count and time come from the document's visits (store.record_visits).
    """
    if decision.eligible and decision.normalized is not None:
        return store.PageRow(
            url=decision.normalized,
            host=decision.host,
            site=decision.site,
            template=decision.template or None,
            source=store.PageSource.HISTER,
            eligible=True,
            reason=decision.reason.value,
        )
    if not decision.host:
        return None
    return store.PageRow(
        url=f"{_EXCLUDED_KEY_PREFIX}{config.page_key(document.url)}",
        host=decision.host,
        site=decision.site,
        template=None,
        source=store.PageSource.HISTER,
        eligible=False,
        reason=decision.reason.value,
    )


def _meta_int(value: str) -> int | None:
    try:
        return int(value)
    except ValueError:
        return None  # An unreadable marker means a full sync, which is always safe.


def _servo_binary(option: str | None, rt: Runtime) -> Path:
    binary = Path(option).expanduser() if option else config.default_servo_binary(rt.environ)
    if binary is None:
        raise config.ConfigError(
            f"no servoshell found: pass --servo PATH or set {config.SERVO_BINARY_ENV}"
        )
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise config.ConfigError(f"{binary} is not an executable file")
    return binary


def _named_urls(args: argparse.Namespace) -> list[str] | None:
    """URLs from the command line or --urls FILE; None when the run selects its pages."""
    if args.urls is not None:
        path = Path(args.urls)
        if not path.is_file():
            raise config.ConfigError(f"no URL file at {path}")
        urls = list(config.load_seed_urls(path))
        if not urls:
            raise config.ConfigError(f"{path} lists no URLs")
        return urls
    if args.url:
        return list(args.url)
    return None


def _named_targets(
    urls: Sequence[str], policy: _UrlPolicy
) -> tuple[list[runner.RunTarget], list[store.PageRow]]:
    """Targets for URLs someone named, and store rows for their hosts (leak check).

    Local paths become file: URLs. Web URLs are normalized and checked against the
    refusals in _REFUSED_NAMED_REASONS; other exclusions do not apply to them.
    """
    targets: dict[str, runner.RunTarget] = {}
    rows: list[store.PageRow] = []
    for raw in urls:
        url = raw.strip()
        scheme = urlsplit(url).scheme.lower()
        if not scheme and Path(url).is_file():
            url, scheme = Path(url).resolve().as_uri(), "file"
        if scheme == "file":
            path = Path(url2pathname(urlsplit(url).path))
            if not path.is_file():
                raise config.ConfigError(f"no such file: {path}")
            targets.setdefault(url, runner.RunTarget(url))
            continue

        decision = policy.classify(url)
        if decision.reason in _REFUSED_NAMED_REASONS:
            raise config.ConfigError(f"refusing {url}: URL policy reason {decision.reason}")
        key = urlpolicy.normalize(url)
        rows.append(
            store.PageRow(
                url=key,
                host=decision.host,
                site=decision.site,
                template=decision.template or None,
                source=store.PageSource.MANUAL,
                eligible=decision.eligible,
                reason=decision.reason.value,
            )
        )
        targets.setdefault(key, runner.RunTarget(key))
    return list(targets.values()), rows


def _planned_targets(
    db: store.Store, rt: Runtime, run_id: str, budget: int, policy: _UrlPolicy
) -> list[runner.RunTarget]:
    """The run's plan, without pages whose host resolves to a private address.

    Such pages leave the selection for good and the plan is made again, a few
    rounds at most; whatever still resolves privately after that is dropped.
    """
    now = rt.now()
    private_hosts: dict[str, bool] = {}
    plan: list[selection.PlannedPage] = []
    for _ in range(_PLAN_ROUNDS):
        # The run id seeds the plan, so the plan of any run can be reproduced.
        plan = selection.select(_selection_pages(db, policy), budget, now, rng_seed=run_id)
        private = [item for item in plan if _resolves_privately(item.url, rt, private_hosts)]
        if not private:
            break
        if len(private) > len(plan) * _MAX_PRIVATE_RESOLUTION_SHARE:
            raise config.ConfigError(
                f"{len(private)} of {len(plan)} planned pages resolve to private addresses; "
                "a proxy or VPN in fake-IP mode, or a captive portal, answers for every "
                "host. Nothing was excluded; fix name resolution and run again"
            )
        for item in private:
            db.exclude_after_load(item.url, _PRIVATE_RESOLUTION_REASON)
    plan = [item for item in plan if not _resolves_privately(item.url, rt, private_hosts)]
    buckets = Counter(item.bucket.value for item in plan)
    print(
        "plan: " + ", ".join(f"{count} {bucket}" for bucket, count in sorted(buckets.items())),
        file=rt.stderr,
    )
    return [runner.RunTarget(item.url, item.bucket.value) for item in plan]


def _resolves_privately(url: str, rt: Runtime, known: dict[str, bool]) -> bool:
    """Whether the URL's host resolves to any address that is not public.

    A name that does not resolve is not private: both engines would fail to load it.
    """
    host = urlsplit(url).hostname or ""
    if host not in known:
        try:
            addresses = rt.resolve(host)
        except OSError:
            addresses = []
        known[host] = any(not urlpolicy.is_public_address(address) for address in addresses)
    return known[host]


def _comparison_before_run(db: store.Store, option: str | None) -> str | None:
    """`--compare`, resolved before the new run exists (so `latest` is not the run itself)."""
    if option is None:
        return None
    if option == LATEST:
        return db.latest_run_id()
    return _resolve_run(db, option)


def _resolve_run(db: store.Store, option: str | None, label: str | None = None) -> str:
    """A run id, or for `latest` the most recent run (of `label`, when given)."""
    if option is None or option == LATEST:
        run_id = db.latest_run_id(label)
        if run_id is None:
            labelled = "" if label is None else f" labelled {label!r}"
            raise store.StoreError(f"no runs{labelled} yet: start one with `sitecheck run`")
        return run_id
    if db.get_run(option) is None:
        raise store.StoreError(f"No run has id {option!r}")
    return option


@contextlib.contextmanager
def _reference_or_none(
    rt: Runtime, enabled: bool, data: Path, user_agent: str, policy: reference.CachePolicy
) -> Iterator[runner.ReferenceSource | None]:
    if not enabled:
        yield None
        return
    with rt.open_reference(data, user_agent, policy) as source:
        yield source


def _engines(
    rt: Runtime, binary: Path, identity: ServoIdentity, ref: runner.ReferenceSource | None
) -> runner.Engines:
    return runner.Engines(
        servo=rt.servo_engine(binary, identity.user_agent),
        reference=ref,
        compare_images=rt.compare_images if ref is not None else None,
    )


def _check_recorded(
    rt: Runtime,
    data: Path,
    targets: Sequence[runner.RunTarget],
    rows: Sequence[store.PageRow],
    engines: runner.Engines,
    policy: runner.AttemptPolicy,
    args: argparse.Namespace,
    binary: Path,
    identity: ServoIdentity,
    url_policy: _UrlPolicy,
) -> list[dict[str, Any]]:
    with _open_store(data, rt) as db:
        db.upsert_pages(rows)
        run_id = db.create_run(
            label=args.label or _CHECK_LABEL,
            servo_rev=identity.revision,
            servo_binary=str(binary),
            reference_engine=engines.reference.name if engines.reference else None,
        )
        summary = runner.run(
            db,
            data,
            run_id,
            targets,
            engines,
            policy=policy,
            progress=rt.stderr,
            scope=runner.SiteScope(config.signature_key(data)),
            navigation=url_policy,
        )
        report.write_report(db, data, run_id, compare_to=db.previous_run_id(run_id))
    print(f"recorded as run {run_id}", file=rt.stderr)
    return list(summary.pages)


def _check_unrecorded(
    rt: Runtime,
    data: Path,
    targets: Sequence[runner.RunTarget],
    engines: runner.Engines,
    policy: runner.AttemptPolicy,
    url_policy: _UrlPolicy,
) -> list[dict[str, Any]]:
    checks = config.ensure_private_dir(data / _CHECKS_DIR_NAME)
    _prune_checks(checks)
    check_dir = config.ensure_private_dir(
        checks / f"{rt.now():%Y%m%dT%H%M%SZ}-{secrets.token_hex(2)}"
    )
    config.ensure_private_dir(check_dir / config.PAGES_DIR_NAME)
    scope = runner.SiteScope(config.signature_key(data))
    records = []
    for index, target in enumerate(targets, start=1):
        outcome = runner.check_page_or_harness_error(
            target.url,
            config.page_dir(check_dir, target.url),
            engines,
            policy,
            progress=rt.stderr,
            scope=scope(target.url),
            refusal=functools.partial(url_policy.refusal, target=target),
        )
        records.append(runner.outcome_record(outcome, check_dir))
        print(
            f"[{index}/{len(targets)}] {outcome.verdict.kind.value} "
            f"{runner.display_host(target.url)}",
            file=rt.stderr,
        )
    return records


def _prune_checks(checks: Path) -> None:
    cutoff = time.time() - _CHECK_RETENTION.total_seconds()
    for entry in checks.iterdir():
        if entry.is_dir() and not entry.is_symlink() and entry.stat().st_mtime < cutoff:
            shutil.rmtree(entry)


def _print_check_record(record: Mapping[str, Any], out: TextIO) -> None:
    print(f"{record['verdict']} (severity {record['severity']}) {record['url']}", file=out)
    servo_loaded = record["attempts"][-1]["servo_status"] == "ok"
    if servo_loaded and not record["servo_screenshot"]:
        print("  (no Servo screenshot: the screenshot rules could not run)", file=out)
    for finding in record["findings"]:
        signature = f"  [{finding['signature']}]" if finding["signature"] else ""
        print(f"  - {finding['kind']}: {finding['detail']}{signature}", file=out)
    for attempt in record["attempts"]:
        detail = f" ({attempt['detail']})" if attempt["detail"] else ""
        print(
            f"  servo attempt {attempt['attempt']}: {attempt['servo_status']} in "
            f"{attempt['servo_ms'] / 1000:.1f} s, {attempt['verdict']}{detail}",
            file=out,
        )
    ref = record["reference"]
    if ref is not None:
        cached = ", cached" if ref["cached"] else ""
        print(f"  reference: {ref['status']} in {ref['ms'] / 1000:.1f} s{cached}", file=out)
    print(f"  artifacts: {record['page_dir']}", file=out)


def _print_summary(summary: runner.RunSummary, files: report.ReportFiles, rt: Runtime) -> None:
    order = {kind.value: index for index, kind in enumerate(VerdictKind)}
    counts = ", ".join(
        f"{verdict} {count}"
        for verdict, count in sorted(summary.verdicts.items(), key=lambda item: order[item[0]])
    )
    print(
        f"run {summary.run_id}: {summary.checked} of {summary.planned} pages checked"
        + (f", {summary.skipped} skipped (time budget)" if summary.skipped else ""),
        file=rt.stdout,
    )
    if counts:
        print(f"verdicts: {counts}", file=rt.stdout)
    if summary.new_issues:
        print(f"new issues: {len(summary.new_issues)}", file=rt.stdout)
    for signature in summary.reopened_issues:
        print(f"reopened (marked fixed, seen again): {signature}", file=rt.stdout)
    print(f"report: {files.markdown}", file=rt.stdout)
    print(f"        {files.html}", file=rt.stdout)


def _bundled_seeds() -> tuple[frozenset[str], frozenset[str]]:
    """Hosts and sites, and normalized URLs, of the public seed list the repository holds."""
    names: set[str] = set()
    urls: set[str] = set()
    for url in config.load_seed_urls(config.BUNDLED_SEEDS_PATH):
        decision = urlpolicy.classify(url)
        names.update(name for name in (decision.host, decision.site) if name)
        if decision.normalized:
            urls.add(decision.normalized)
    return frozenset(names), frozenset(urls)


def _git_revision(checkout: Path) -> str | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            timeout=_SUBPROCESS_TIMEOUT_S,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    revision = result.stdout.strip()
    return revision if result.returncode == 0 and revision else None


# --- Doctor -------------------------------------------------------------------------

_ReportCheck = Callable[[_Health, str, str], None]


def _doctor_data_dir(data: Path, report_check: _ReportCheck) -> None:
    if not data.exists():
        report_check(_Health.WARN, "data directory", f"{data} does not exist yet (created on use)")
        return
    info = data.stat()
    mode = info.st_mode & 0o777
    if info.st_uid != os.getuid():
        report_check(_Health.FAIL, "data directory", f"{data} belongs to another user")
    elif mode != config.PRIVATE_DIR_MODE:
        report_check(
            _Health.WARN, "data directory", f"{data} has mode {mode:04o}; it is set to 0700 on use"
        )
    else:
        report_check(_Health.OK, "data directory", f"{data} (0700)")


def _doctor_store(data: Path, rt: Runtime, report_check: _ReportCheck) -> None:
    db_path = config.state_db_path(data)
    if not db_path.is_file():
        report_check(_Health.WARN, "store", "empty: run `sitecheck seed` or `sitecheck sync`")
        return
    with store.open_store(db_path, clock=rt.now) as db:
        eligible = len(db.selection_rows())
        names = db.hosts_and_sites()
        latest = db.latest_run_id()
    report_check(
        _Health.OK,
        "store",
        f"{eligible} eligible pages, {len(names.sites)} sites known, latest run {latest or 'none'}",
    )
    try:
        with config.run_lock(data):
            pass
    except config.RunLockedError as error:
        report_check(_Health.WARN, "lock", str(error))


def _doctor_hister(rt: Runtime, *, offline: bool, report_check: _ReportCheck) -> None:
    warnings = io.StringIO()
    try:
        settings = config.load_hister_settings(environ=rt.environ, warnings=warnings)
    except config.ConfigError as error:
        report_check(_Health.FAIL, "hister", str(error))
        return
    for warning in warnings.getvalue().splitlines():
        report_check(_Health.WARN, "hister", warning)
    if settings is None:
        report_check(_Health.WARN, "hister", "not configured; runs use the seed list only")
        return

    # Only the host is shown: the URL's path or query could hold a secret.
    try:
        client = hister.HisterClient(settings.url, settings.token)
    except hister.HisterConfigError as error:
        report_check(_Health.FAIL, "hister", f"configured but unusable: {error}")
        return
    host = urlsplit(client.base_url).hostname
    if offline:
        report_check(_Health.OK, "hister", f"configured for {host} (not contacted: --offline)")
        return
    try:
        next(iter(client.iter_documents(max_pages=1)), None)
    except hister.HisterError as error:
        report_check(_Health.FAIL, "hister", f"configured for {host}, but: {error}")
        return
    report_check(_Health.OK, "hister", f"configured for {host}, reachable, token accepted")


def _doctor_lists(config_path: Path, report_check: _ReportCheck) -> None:
    try:
        deny = config.load_deny_list(config.deny_list_path(config_path))
    except config.ConfigError as error:
        report_check(_Health.FAIL, "deny list", str(error))
    else:
        report_check(_Health.OK, "deny list", f"{len(deny)} entries")
    bundled = config.load_seed_urls(config.BUNDLED_SEEDS_PATH)
    private = config.load_seed_urls(config.private_seeds_path(config_path))
    report_check(_Health.OK, "seeds", f"{len(bundled)} bundled, {len(private)} private")


def _doctor_servo(option: str | None, rt: Runtime, report_check: _ReportCheck) -> None:
    try:
        binary = _servo_binary(option, rt)
        identity = rt.servo_identity(binary)
    except config.ConfigError as error:
        report_check(_Health.FAIL, "servo", str(error))
        return
    try:
        result = subprocess.run(
            [str(binary), "--version"],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=_SUBPROCESS_TIMEOUT_S,
            # Nothing of the harness's environment (such as a Hister token) is needed.
            env=config.child_environment(rt.environ),
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        report_check(_Health.FAIL, "servo", f"{binary} --version failed: {error}")
        return
    if result.returncode != 0:
        report_check(_Health.FAIL, "servo", f"{binary} --version exited {result.returncode}")
        return
    version_line = next(iter(result.stdout.strip().splitlines()), "no version output")
    revision = (identity.revision or "unknown")[:12]
    report_check(_Health.OK, "servo", f"{binary}: {version_line}, checkout at {revision}")
    report_check(_Health.OK, "user agent", identity.user_agent)
    _doctor_webdriver(binary, identity, rt, report_check)


def _doctor_webdriver(
    binary: Path, identity: ServoIdentity, rt: Runtime, report_check: _ReportCheck
) -> None:
    try:
        exposed = rt.webdriver_exposure(binary, identity.user_agent)
    except (OSError, RuntimeError) as error:
        report_check(_Health.WARN, "webdriver", f"could not start servoshell to check: {error}")
        return
    if exposed is None:
        report_check(_Health.OK, "webdriver", "answers on loopback only")
        return
    report_check(
        _Health.FAIL,
        "webdriver",
        f"servoshell's WebDriver server answers at {exposed} too: anyone on the network who "
        "connects first can drive the engine and read local files. Patch the downstream "
        "servoshell to bind 127.0.0.1, or block incoming connections to this machine",
    )


def _doctor_reference(rt: Runtime, report_check: _ReportCheck) -> None:
    if rt.images_available():
        report_check(_Health.OK, "pillow", "installed")
    else:
        report_check(_Health.FAIL, "pillow", "not installed: run through the uv project (uv sync)")
    try:
        version = rt.browser_version()
    except reference.ReferenceUnavailable as error:
        report_check(_Health.FAIL, "chromium", str(error))
        return
    report_check(_Health.OK, "chromium", version)


def _doctor_disk(data: Path, report_check: _ReportCheck) -> None:
    existing = next((path for path in (data, *data.parents) if path.exists()), Path("/"))
    free = shutil.disk_usage(existing).free
    detail = f"{free / (1 << 30):.1f} GiB free at {existing}"
    if free < _DISK_FAIL_BYTES:
        report_check(_Health.FAIL, "disk", detail)
    elif free < _DISK_WARN_BYTES:
        report_check(_Health.WARN, "disk", detail)
    else:
        report_check(_Health.OK, "disk", detail)


# --- Process cleanup ----------------------------------------------------------------


@contextlib.contextmanager
def _engine_cleanup(enabled: bool) -> Iterator[None]:
    """Kill every servoshell when the command ends, however it ends."""
    if not enabled:
        yield
        return

    atexit.register(servo.terminate_all)

    def stop(signal_number: int, _frame: object) -> NoReturn:
        # terminate_all is safe in a signal handler; the exception then unwinds the
        # Chromium context manager, which stops the browser.
        servo.terminate_all()
        raise _Stopped(signal_number)

    previous = {number: signal.signal(number, stop) for number in _STOP_SIGNALS}
    try:
        yield
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


# --- Argument parsing ---------------------------------------------------------------


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        raise _UsageError(message)


def _positive_int(text: str) -> int:
    value = _int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return value


def _non_negative_int(text: str) -> int:
    value = _int(text)
    if value < 0:
        raise argparse.ArgumentTypeError("must not be negative")
    return value


def _int(text: str) -> int:
    try:
        return int(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a whole number: {text!r}") from None


def _positive_minutes(text: str) -> float:
    try:
        value = float(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a number: {text!r}") from None
    if not value > 0:
        raise argparse.ArgumentTypeError("must be more than 0")
    return value


def _parser() -> _Parser:
    parser = _Parser(
        prog="sitecheck",
        description="Load websites in Servo and headless Chromium and report what breaks.",
    )
    commands = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")

    sync = commands.add_parser("sync", help="import history from Hister (prints counts only)")
    sync.add_argument("--full", action="store_true", help="ignore the last sync time")
    sync.add_argument(
        "--max-pages",
        type=_positive_int,
        default=hister.DEFAULT_MAX_PAGES,
        help=f"history pages of {hister.PAGE_SIZE} to read at most (default %(default)s)",
    )
    sync.set_defaults(handler=_cmd_sync)

    seed = commands.add_parser("seed", help="add the public seed list (and private seeds)")
    seed.add_argument("--file", help="seed file instead of the bundled seeds.txt")
    seed.set_defaults(handler=_cmd_seed)

    select = commands.add_parser("select", help="print the pages a run would check")
    select.add_argument("--budget", type=_non_negative_int, default=DEFAULT_BUDGET)
    select.add_argument("--seed", help="plan seed (a run uses its run id; default: today)")
    select.add_argument("--json", action="store_true")
    select.set_defaults(handler=_cmd_select)

    run = commands.add_parser("run", help="check pages and record the results")
    _add_engine_options(run)
    run.add_argument(
        "--budget", type=_non_negative_int, help=f"pages to select (default {DEFAULT_BUDGET})"
    )
    run.add_argument("--urls", metavar="FILE", help="check the URLs in FILE, one per line")
    run.add_argument("url", nargs="*", help="check these URLs or local files")
    run.add_argument("--label", help="run label; reports compare runs with the same label")
    run.add_argument("--minutes", type=_positive_minutes, help="stop starting pages after M min")
    run.add_argument("--compare", metavar="RUN", help="run id or 'latest' to compare with")
    run.add_argument(
        "--retries", type=_non_negative_int, default=1, help="servo reloads of a failing page"
    )
    run.set_defaults(handler=_cmd_run)

    check = commands.add_parser("check", help="check URLs now; no store writes unless --record")
    check.add_argument("--servo", metavar="PATH", help="servoshell binary")
    check.add_argument(
        "--no-reference", action="store_true", help="skip Chromium: judges only crash and hang"
    )
    check.add_argument(
        "--fresh-reference", action="store_true", help="accepted; check always loads Chromium"
    )
    check.add_argument("url", nargs="+", help="URLs or local files")
    check.add_argument("--repeat", type=_positive_int, default=1, help="servo loads per URL")
    check.add_argument("--json", action="store_true")
    check.add_argument("--record", action="store_true", help="record the check as a run")
    check.add_argument("--label", help=f"run label with --record (default {_CHECK_LABEL})")
    check.set_defaults(handler=_cmd_check)

    report_parser = commands.add_parser("report", help="write a run's report")
    report_parser.add_argument("--run", default=LATEST, help="run id or 'latest'")
    report_parser.add_argument("--label", help="with 'latest': the latest run with this label")
    report_parser.add_argument("--compare", metavar="RUN", help="run id to compare with")
    report_parser.set_defaults(handler=_cmd_report)

    issues = commands.add_parser("issues", help="list a run's issues by priority")
    issues.add_argument("--run", default=LATEST, help="run id or 'latest'")
    issues.add_argument("--label", help="with 'latest': the latest run with this label")
    issues.add_argument("--json", action="store_true")
    issues.add_argument("--all", action="store_true", help="include wontfix and upstream issues")
    issues.set_defaults(handler=_cmd_issues)

    urls = commands.add_parser("urls", help="print a run's pages by final verdict (private)")
    urls.add_argument("--run", default=LATEST, help="run id or 'latest'")
    urls.add_argument("--label", help="with 'latest': the latest run with this label")
    urls.add_argument(
        "--verdict",
        action="append",
        choices=[kind.value for kind in VerdictKind],
        help="final verdict to list; repeatable (default: ok)",
    )
    urls.add_argument("--limit", type=_non_negative_int, help="at most N pages, spread over sites")
    urls.set_defaults(handler=_cmd_urls)

    mark = commands.add_parser("mark", help="set an issue's status")
    mark.add_argument("signature")
    mark.add_argument(
        "--status", required=True, choices=[status.value for status in store.IssueStatus]
    )
    mark.add_argument("--note")
    mark.set_defaults(handler=_cmd_mark)

    leak = commands.add_parser("leakcheck", help="find history hosts in a diff or text files")
    leak.add_argument("--git-range", metavar="BASE..HEAD")
    leak.add_argument("--repo", default=".", help="repository for --git-range (default .)")
    leak.add_argument(
        "--exclude-ref",
        action="append",
        metavar="REF",
        help="skip commits reachable from REF, such as a merged upstream release",
    )
    leak.add_argument("--text", action="append", metavar="FILE", help="text file to scan")
    leak.add_argument(
        "--allow-empty-inventory",
        action="store_true",
        help="scan even when the store holds nothing from the history",
    )
    leak.set_defaults(handler=_cmd_leakcheck)

    doctor = commands.add_parser("doctor", help="check the setup")
    doctor.add_argument("--servo", metavar="PATH", help="servoshell to check")
    doctor.add_argument("--offline", action="store_true", help="do not contact Hister")
    doctor.set_defaults(handler=_cmd_doctor)
    return parser


def _add_engine_options(command: argparse.ArgumentParser) -> None:
    command.add_argument("--servo", metavar="PATH", help="servoshell binary")
    command.add_argument("--no-reference", action="store_true", help="skip Chromium")
    command.add_argument(
        "--fresh-reference", action="store_true", help="ignore cached Chromium results"
    )
