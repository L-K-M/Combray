"""Runs pages through Servo and the reference engine, judges them and records the results.

Pages are checked one at a time and one engine at a time: the machine the nightly run
uses has 8 GB, so servoshell and Chromium never run side by side. For each page:

1. Servo loads it in a fresh servoshell (``servo.run_page``). A page that ended up
   at a URL the policy excludes (a redirect, a script navigation) is discarded at
   once: verdict ``excluded``, no reference load, and it leaves the selection.
2. The reference engine loads it, or a recent cached reference result is reused.
3. ``compare.verdict`` judges Servo's result against the reference. A failure
   against a cached reference is judged again against a fresh one, so a page that
   changed since the cache entry is not blamed on Servo.
4. A failing verdict (or a harness error) is retried in a fresh servoshell. A failure
   that does not repeat makes the page ``flaky``.

Each attempt is one row in the store; the last attempt's row carries the page's final
verdict, because the store treats the highest attempt as the page's outcome. Artifacts
go to ``runs/<run_id>/pages/<page_key>/`` (attempt 2 and later in ``attempt-N/``
below it). Page text is kept only as gzip files there, never in the store, so the
database and the reports stay compact.

The engines are small protocols so the orchestration is tested with fakes; the real
ones are ``ServoshellEngine`` and ``ChromiumSource``.
"""

from __future__ import annotations

import contextlib
import functools
import gzip
import hashlib
import hmac
import json
import sys
import time
import traceback
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import asdict, dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any, Protocol, TextIO
from urllib.parse import urlsplit

from sitecheck import compare, config, reference, servo, urlpolicy
from sitecheck.images import ImagesUnavailable
from sitecheck.model import Engine, Finding, PageResult, PageStatus, Verdict, VerdictKind
from sitecheck.store import IssueSighting, IssueUpdate, Store

# File names inside a page directory, next to servo.py's servo.log and servo.png and
# reference.py's reference.png.
SERVO_RECORD_NAME = "servo.json"
REFERENCE_RECORD_NAME = "reference.json"
_SERVO_TEXT_NAME = "servo.text.gz"
_REFERENCE_TEXT_NAME = "reference.text.gz"
_WEB_SCHEMES = ("http", "https")
_ATTEMPT_DIR_PREFIX = "attempt-"
# The traceback of an unexpected exception in the harness, next to the engine's files.
HARNESS_ERROR_NAME = "harness-error.txt"
_TRACEBACK_NAMES = {
    Engine.SERVO: HARNESS_ERROR_NAME,
    Engine.CHROMIUM: f"reference-{HARNESS_ERROR_NAME}",
}

# Error lists in the store are capped; the page's JSON files keep them all.
_STORED_ERRORS_MAX = 50

# This many pages in a row ending as harness errors means the setup is broken (the
# binary does not start, the disk is full), not the pages; the run stops rather
# than spend its time budget failing the same way.
MAX_CONSECUTIVE_HARNESS_ERRORS = 5

# Verdicts that end a page's attempts: Servo's result was judged fine, there is no
# baseline to judge it against, or the page left the policy (another Servo load
# would not change that, or must not happen).
_SETTLED = frozenset(
    {VerdictKind.OK, VerdictKind.REFERENCE_FAILED, VerdictKind.NOT_COMPARED, VerdictKind.EXCLUDED}
)
# Verdicts that say nothing about Servo itself.
_NOT_EVIDENCE = frozenset({VerdictKind.HARNESS_ERROR})
_FLAKY_SIGNATURE = "flaky"
# Failures judged against a cached reference that a fresh reference could clear:
# everything that depends on the page's content. Crashes and hangs do not.
_REFERENCE_INDEPENDENT = frozenset({VerdictKind.CRASH, VerdictKind.HANG})

LOCAL_FILE_LABEL = "(local file)"

# URL-policy outcomes of a host on this machine or the local network, such as a
# local server for reduced test cases: its pages change while someone works on
# them, so the reference is never cached for it.
_LOCAL_REASONS = frozenset(
    {
        urlpolicy.Reason.INVALID,
        urlpolicy.Reason.SCHEME,
        urlpolicy.Reason.USERINFO,
        urlpolicy.Reason.PORT,
        urlpolicy.Reason.PRIVATE_IP,
        urlpolicy.Reason.PRIVATE_HOST,
    }
)

# Hex digits of the keyed hash that tags a site in symptom signatures.
_SCOPE_TAG_LENGTH = 8

# Not problems of one page: the run cannot continue without them.
_MISSING_DEPENDENCIES = (reference.ReferenceUnavailable, ImagesUnavailable)

# Why the page may not be at a URL it navigated to, or None when it may.
NavigationRefusal = Callable[[str], str | None]


class RunAborted(Exception):
    """The run stopped early because every recent page failed in the harness."""


class ServoEngine(Protocol):
    def run_page(self, url: str, out_dir: Path) -> PageResult: ...


@dataclass(frozen=True)
class ReferenceOutcome:
    result: PageResult
    cached: bool  # Reused from the reference cache rather than loaded now.


class ReferenceSource(Protocol):
    @property
    def name(self) -> str:
        """The engine and its version, for the run record."""
        ...

    def load(
        self,
        url: str,
        out_dir: Path,
        *,
        cache: reference.CachePolicy | None = None,
        refusal: NavigationRefusal | None = None,
    ) -> ReferenceOutcome:
        """Load the reference; `cache` overrides the source's cache policy for this load,
        and `refusal` blocks navigations the policy excludes where the engine can."""
        ...


class NavigationPolicy(Protocol):
    def refusal(self, url: str, target: RunTarget) -> str | None:
        """Why the page planned as `target` may not be at `url`, or None when it may."""
        ...


class SiteScope:
    """Tags a page's site for symptom signatures (see compare.verdict's `scope`).

    A keyed hash of the site, so a tag quoted somewhere cannot be matched against a
    list of popular sites; the key lives in the private data directory.
    """

    def __init__(self, key: bytes) -> None:
        self._key = key

    def __call__(self, url: str) -> str:
        parts = urlsplit(url)
        site = (
            LOCAL_FILE_LABEL if parts.scheme == "file" else urlpolicy.site_of(parts.hostname or "")
        )
        digest = hmac.new(self._key, site.encode("utf-8"), hashlib.sha256).hexdigest()
        return digest[:_SCOPE_TAG_LENGTH]


# images.compare_screenshots, or a fake with the same contract.
ImageComparer = Callable[[Path | None, Path | None], Mapping[str, Any]]


@dataclass(frozen=True)
class Engines:
    servo: ServoEngine
    reference: ReferenceSource | None = None  # None: Servo's result is not compared.
    compare_images: ImageComparer | None = None  # None: the screenshot rules are skipped.


@dataclass(frozen=True)
class AttemptPolicy:
    """How many times Servo loads a page.

    ``minimum`` loads always happen; after that, a page whose verdict is a failure
    or a harness error is loaded again until ``maximum`` loads.
    """

    minimum: int = 1
    maximum: int = 2

    def __post_init__(self) -> None:
        if self.minimum < 1 or self.maximum < self.minimum:
            raise ValueError("attempts need 1 <= minimum <= maximum")

    @classmethod
    def retrying(cls, retries: int) -> AttemptPolicy:
        """One load, plus up to ``retries`` more while the verdict is a failure (`run`)."""
        if retries < 0:
            raise ValueError("retries must not be negative")
        return cls(minimum=1, maximum=1 + retries)

    @classmethod
    def repeating(cls, loads: int) -> AttemptPolicy:
        """Exactly ``loads`` loads, whatever the verdicts (`check --repeat`)."""
        return cls(minimum=loads, maximum=loads)


@dataclass(frozen=True)
class RunTarget:
    url: str  # The store's key: the normalized URL for pages from the policy.
    bucket: str | None = None  # The selection bucket; None for URLs named directly.


@dataclass(frozen=True)
class Attempt:
    number: int  # From 1.
    directory: Path  # Where this attempt's Servo artifacts are.
    servo: PageResult
    images: Mapping[str, Any] | None
    verdict: Verdict  # This attempt's own verdict.


@dataclass(frozen=True)
class PageOutcome:
    url: str
    directory: Path  # The page directory; the reference artifacts are here.
    attempts: tuple[Attempt, ...]
    reference: ReferenceOutcome | None
    verdict: Verdict  # The page's final verdict over all attempts.
    # Why the page was discarded for leaving the URL policy, or None.
    excluded: str | None = None


@dataclass(frozen=True)
class RunSummary:
    run_id: str
    run_dir: Path
    planned: int
    skipped: int  # Not started because the time budget ran out.
    pages: tuple[dict[str, Any], ...] = field(default=())  # `outcome_record` per page.
    new_issues: tuple[str, ...] = ()  # Signatures seen for the first time.
    reopened_issues: tuple[str, ...] = ()  # Marked fixed earlier and seen again.

    @property
    def checked(self) -> int:
        return len(self.pages)

    @property
    def verdicts(self) -> Counter[str]:
        return Counter(page["verdict"] for page in self.pages)


class ServoshellEngine:
    """The real Servo: one servoshell per load, with Combray's engine settings."""

    def __init__(
        self, binary: Path, user_agent: str, timing: servo.ServoTiming = servo.DEFAULT_TIMING
    ) -> None:
        self._binary = binary
        self._user_agent = user_agent
        self._timing = timing

    def run_page(self, url: str, out_dir: Path) -> PageResult:
        return servo.run_page(
            self._binary,
            url,
            out_dir,
            user_agent=self._user_agent,
            prefs=config.ENGINE_PREFS,
            viewport=(config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT),
            device_pixel_ratio=config.DEVICE_PIXEL_RATIO,
            timing=self._timing,
        )


class ChromiumSource:
    """Chromium through Playwright, with the per-URL reference cache in front of it.

    Local files and pages of local servers are never cached: they are test pages
    that change while someone works on them.
    """

    def __init__(
        self,
        chromium: reference.ChromiumReference,
        data: Path,
        *,
        user_agent: str,
        policy: reference.CachePolicy = reference.CachePolicy.USE,
        timing: reference.ReferenceTiming = reference.DEFAULT_TIMING,
    ) -> None:
        self._chromium = chromium
        self._data = data
        self._user_agent = user_agent
        self._policy = policy
        self._timing = timing

    @property
    def name(self) -> str:
        return self._chromium.browser_version

    def load(
        self,
        url: str,
        out_dir: Path,
        *,
        cache: reference.CachePolicy | None = None,
        refusal: NavigationRefusal | None = None,
    ) -> ReferenceOutcome:
        probe = servo.load_probe_source()
        cacheable = _is_cacheable(url)
        browser = self.name
        if cacheable:
            cached = reference.load_cached_reference(
                self._data,
                url,
                out_dir,
                user_agent=self._user_agent,
                probe_source=probe,
                browser_version=browser,
                timing=self._timing,
                policy=cache or self._policy,
            )
            if cached is not None:
                return ReferenceOutcome(cached.result, cached=True)

        result = self._chromium.run_page(
            url,
            out_dir,
            user_agent=self._user_agent,
            probe_source=probe,
            timing=self._timing,
            navigation_refusal=refusal,
        )
        if cacheable:
            reference.store_cached_reference(
                self._data,
                result,
                user_agent=self._user_agent,
                probe_source=probe,
                browser_version=browser,
                timing=self._timing,
            )
        return ReferenceOutcome(result, cached=False)


def check_page(
    url: str,
    page_dir: Path,
    engines: Engines,
    policy: AttemptPolicy | None = None,
    *,
    progress: TextIO | None = None,
    scope: str | None = None,
    refusal: NavigationRefusal | None = None,
) -> PageOutcome:
    """Load one page in Servo (as often as `policy` says) and the reference, and judge it.

    Writes the page's artifacts into `page_dir`. Touches no store. An unexpected
    exception from an engine becomes that attempt's harness error (its traceback in
    the attempt's directory, a one-line note without the URL on `progress`), so one
    failing attempt does not discard the others. `scope` tags symptom signatures
    (see compare.verdict). `refusal` judges where the page ended up: a page at a URL
    it refuses is discarded without loading it again in either engine.
    """
    attempt_policy = policy or AttemptPolicy()
    out = sys.stderr if progress is None else progress
    config.ensure_private_dir(page_dir)

    reference_outcome: ReferenceOutcome | None = None
    attempts: list[Attempt] = []
    excluded: str | None = None
    for number in range(1, attempt_policy.maximum + 1):
        attempt_dir = page_dir if number == 1 else page_dir / f"{_ATTEMPT_DIR_PREFIX}{number}"
        config.ensure_private_dir(attempt_dir)
        servo_result = _guarded(
            functools.partial(engines.servo.run_page, url, attempt_dir),
            Engine.SERVO,
            url,
            attempt_dir,
            out,
        )
        _write_engine_files(attempt_dir, servo_result, _SERVO_FILES)
        excluded = _left_policy(servo_result, url, refusal)
        if excluded is not None:
            verdict = compare.policy_excluded(
                f"servo ended up at a URL the policy excludes: {excluded}"
            )
            attempts.append(Attempt(number, attempt_dir, servo_result, None, verdict))
            break

        # The reference is loaded once, after Servo's first load, and reused by the
        # retries: they ask whether Servo repeats itself, not whether the site does.
        if number == 1 and engines.reference is not None:
            reference_outcome, excluded = _load_reference(
                engines.reference, url, page_dir, out, refusal
            )
            if excluded is not None:
                verdict = compare.policy_excluded(
                    f"the reference ended up at a URL the policy excludes: {excluded}"
                )
                attempts.append(Attempt(number, attempt_dir, servo_result, None, verdict))
                break

        reference_result = reference_outcome.result if reference_outcome else None
        images = _image_evidence(engines.compare_images, servo_result, reference_result)
        verdict = compare.verdict(servo_result, reference_result, images, scope=scope)
        if (
            reference_outcome is not None
            and reference_outcome.cached
            and verdict.is_failure
            and verdict.kind not in _REFERENCE_INDEPENDENT
            and engines.reference is not None
        ):
            # The cached reference may be days old; judge against today's page.
            reference_outcome, excluded = _load_reference(
                engines.reference, url, page_dir, out, refusal, reference.CachePolicy.REFRESH
            )
            if excluded is not None:
                verdict = compare.policy_excluded(
                    f"the reference ended up at a URL the policy excludes: {excluded}"
                )
                attempts.append(Attempt(number, attempt_dir, servo_result, None, verdict))
                break
            reference_result = reference_outcome.result
            images = _image_evidence(engines.compare_images, servo_result, reference_result)
            verdict = compare.verdict(servo_result, reference_result, images, scope=scope)
        attempts.append(Attempt(number, attempt_dir, servo_result, images, verdict))

        if number >= attempt_policy.minimum and verdict.kind in _SETTLED:
            break

    return PageOutcome(
        url=url,
        directory=page_dir,
        attempts=tuple(attempts),
        reference=reference_outcome,
        verdict=final_verdict([attempt.verdict for attempt in attempts]),
        excluded=excluded,
    )


def final_verdict(verdicts: Sequence[Verdict]) -> Verdict:
    """A page's verdict over all its attempts.

    - A failure in one attempt and ok in another: `flaky`. Its signature is
      ``flaky:<first failure's signature>``, so an intermittent panic stays traceable.
    - Otherwise, when attempts failed, the most severe failure, carrying the
      findings of every failing attempt (each signature once), so a crash on the
      first load is not replaced by a milder failure on the retry, and a later
      attempt that only lacks a baseline (reference-failed) hides nothing.
    - Otherwise the last verdict that is evidence about Servo, or a harness error
      when no attempt produced any.
    """
    if not verdicts:
        raise ValueError("a page needs at least one attempt")

    evidence = [verdict for verdict in verdicts if verdict.kind not in _NOT_EVIDENCE]
    if not evidence:
        return verdicts[-1]

    failures = [verdict for verdict in evidence if verdict.is_failure]
    if not failures:
        return evidence[-1]
    if not any(verdict.kind is VerdictKind.OK for verdict in evidence):
        return _merged_failures(failures)

    first = failures[0]
    signature = (
        f"{_FLAKY_SIGNATURE}:{first.signatures[0]}" if first.signatures else _FLAKY_SIGNATURE
    )
    detail = "attempts: " + ", ".join(verdict.kind.value for verdict in verdicts)
    severity = compare.SEVERITY[VerdictKind.FLAKY]
    return Verdict(
        VerdictKind.FLAKY, severity, (Finding(VerdictKind.FLAKY, severity, detail, signature),)
    )


def _merged_failures(failures: Sequence[Verdict]) -> Verdict:
    if len(failures) == 1:
        return failures[0]
    ordered = sorted(
        failures, key=lambda verdict: (-verdict.severity, compare.KIND_ORDER[verdict.kind])
    )
    worst = ordered[0]
    findings: list[Finding] = []
    seen: set[str | None] = set()
    for finding in (finding for verdict in ordered for finding in verdict.findings):
        if finding.signature in seen:
            continue
        seen.add(finding.signature)
        findings.append(finding)
    return Verdict(worst.kind, worst.severity, tuple(findings))


def run(
    store: Store,
    data: Path,
    run_id: str,
    targets: Sequence[RunTarget],
    engines: Engines,
    *,
    policy: AttemptPolicy | None = None,
    time_budget: timedelta | None = None,
    progress: TextIO | None = None,
    monotonic: Callable[[], float] = time.monotonic,
    scope: SiteScope | None = None,
    navigation: NavigationPolicy | None = None,
) -> RunSummary:
    """Check `targets` in order for the run `run_id` (created by the caller) and record them.

    Stops starting pages once `time_budget` has passed and reports how many were
    skipped. Marks the run finished when it completes; a run interrupted by an
    exception stays unfinished. Raises RunAborted after
    MAX_CONSECUTIVE_HARNESS_ERRORS harness errors in a row. `scope` tags symptom
    signatures with the page's site; `navigation` decides where a page may end up,
    and a page that leaves it is kept out of later selections.
    """
    out = sys.stderr if progress is None else progress
    attempt_policy = policy or AttemptPolicy.retrying(1)
    config.ensure_private_dir(data / config.RUNS_DIR_NAME)
    run_path = config.ensure_private_dir(config.run_dir(data, run_id))
    # Created here so mkdir(parents=True) below never makes it with the umask's mode.
    config.ensure_private_dir(run_path / config.PAGES_DIR_NAME)

    started = monotonic()
    pages: list[dict[str, Any]] = []
    new_issues: list[str] = []
    reopened_issues: list[str] = []
    skipped = 0
    consecutive_harness_errors = 0
    for index, target in enumerate(targets, start=1):
        if time_budget is not None and monotonic() - started >= time_budget.total_seconds():
            skipped = len(targets) - index + 1
            minutes = time_budget.total_seconds() / 60
            print(
                f"time budget of {minutes:g} min used up; {skipped} planned pages not started",
                file=out,
            )
            break

        page_dir = config.page_dir(run_path, target.url)
        refusal = None
        if navigation is not None:
            refusal = functools.partial(navigation.refusal, target=target)
        outcome = check_page_or_harness_error(
            target.url,
            page_dir,
            engines,
            attempt_policy,
            progress=out,
            scope=None if scope is None else scope(target.url),
            refusal=refusal,
        )
        update = _record(store, run_id, run_path, target, outcome)
        if update is not None:
            new_issues += [sig for sig in update.new if sig not in new_issues]
            reopened_issues += [sig for sig in update.reopened if sig not in reopened_issues]
        record = outcome_record(outcome, run_path, bucket=target.bucket)
        pages.append(record)
        verdict_name = outcome.verdict.kind.value
        print(f"[{index}/{len(targets)}] {verdict_name} {display_host(target.url)}", file=out)

        if outcome.verdict.kind is VerdictKind.HARNESS_ERROR:
            consecutive_harness_errors += 1
        else:
            consecutive_harness_errors = 0
        if consecutive_harness_errors >= MAX_CONSECUTIVE_HARNESS_ERRORS:
            raise RunAborted(
                f"{consecutive_harness_errors} pages in a row failed in the harness; "
                f"see the servo.log files under {run_path}"
            )

    store.finish_run(run_id)
    return RunSummary(
        run_id=run_id,
        run_dir=run_path,
        planned=len(targets),
        skipped=skipped,
        pages=tuple(pages),
        new_issues=tuple(new_issues),
        reopened_issues=tuple(reopened_issues),
    )


def outcome_record(
    outcome: PageOutcome, base: Path, *, bucket: str | None = None
) -> dict[str, Any]:
    """A JSON-ready summary of a page's outcome, without page text.

    Paths are absolute. `base` is the run (or check) directory the attempt
    directories are listed relative to.
    """
    reference_outcome = outcome.reference
    return {
        "url": outcome.url,
        "bucket": bucket,
        "verdict": outcome.verdict.kind.value,
        "severity": outcome.verdict.severity,
        "signatures": outcome.verdict.signatures,
        "findings": [_finding_record(finding) for finding in outcome.verdict.findings],
        "attempts": [
            {
                "attempt": attempt.number,
                "verdict": attempt.verdict.kind.value,
                "servo_status": attempt.servo.status.value,
                "servo_ms": attempt.servo.duration_ms,
                "detail": attempt.servo.detail,
                "artifacts": attempt.directory.relative_to(base).as_posix(),
            }
            for attempt in outcome.attempts
        ],
        "reference": None
        if reference_outcome is None
        else {
            "status": reference_outcome.result.status.value,
            "ms": reference_outcome.result.duration_ms,
            "cached": reference_outcome.cached,
            "detail": reference_outcome.result.detail,
        },
        # Whether Servo's last attempt produced a screenshot: without one the
        # screenshot rules (blank, visual-diff) could not run.
        "servo_screenshot": outcome.attempts[-1].servo.screenshot is not None,
        "excluded": outcome.excluded,
        "page_dir": str(outcome.directory),
    }


def display_host(url: str) -> str:
    """What progress lines and summaries show for a page: its host, never the full URL."""
    parts = urlsplit(url)
    if parts.scheme == "file":
        return LOCAL_FILE_LABEL
    return parts.hostname or "(no host)"


def check_page_or_harness_error(
    url: str,
    page_dir: Path,
    engines: Engines,
    policy: AttemptPolicy,
    *,
    progress: TextIO,
    scope: str | None = None,
    refusal: NavigationRefusal | None = None,
) -> PageOutcome:
    """check_page, with an unexpected harness exception turned into a harness error.

    One odd page must not end an unattended run or a `check` of several URLs; a
    systematic failure still ends a run, through the consecutive harness error
    limit. Missing dependencies are not per-page problems and propagate.
    """
    try:
        return check_page(
            url, page_dir, engines, policy, progress=progress, scope=scope, refusal=refusal
        )
    except _MISSING_DEPENDENCIES:
        raise
    except Exception as error:
        with contextlib.suppress(OSError):
            config.ensure_private_dir(page_dir)
        result = _harness_failure(Engine.SERVO, url, page_dir, error, progress)
        verdict = compare.verdict(result, None, None)
        attempt = Attempt(1, page_dir, result, None, verdict)
        return PageOutcome(url, page_dir, (attempt,), None, verdict)


def _guarded(
    load: Callable[[], PageResult], engine: Engine, url: str, directory: Path, out: TextIO
) -> PageResult:
    """`load()`, with an unexpected exception turned into a harness error result."""
    try:
        return load()
    except _MISSING_DEPENDENCIES:
        raise
    except Exception as error:
        return _harness_failure(engine, url, directory, error, out)


def _load_reference(
    source: ReferenceSource,
    url: str,
    page_dir: Path,
    out: TextIO,
    refusal: NavigationRefusal | None,
    cache: reference.CachePolicy | None = None,
) -> tuple[ReferenceOutcome, str | None]:
    """Load the reference into `page_dir`, and say why it left the policy: a
    navigation it blocked, or a redirect it could only follow. None when it stayed."""
    blocked = _RefusalRecorder(refusal)
    outcome = _guarded_reference(source, url, page_dir, out, blocked if refusal else None, cache)
    _write_engine_files(page_dir, outcome.result, _REFERENCE_FILES)
    return outcome, blocked.refused or _left_policy(outcome.result, url, refusal)


def _guarded_reference(
    source: ReferenceSource,
    url: str,
    page_dir: Path,
    out: TextIO,
    refusal: NavigationRefusal | None,
    cache: reference.CachePolicy | None = None,
) -> ReferenceOutcome:
    try:
        return source.load(url, page_dir, cache=cache, refusal=refusal)
    except _MISSING_DEPENDENCIES:
        raise
    except Exception as error:
        result = _harness_failure(Engine.CHROMIUM, url, page_dir, error, out)
        return ReferenceOutcome(result, cached=False)


class _RefusalRecorder:
    """A navigation refusal that remembers the first URL it refused."""

    def __init__(self, refusal: NavigationRefusal | None) -> None:
        self._refusal = refusal
        self.refused: str | None = None

    def __call__(self, url: str) -> str | None:
        reason = None if self._refusal is None else self._refusal(url)
        if reason is not None and self.refused is None:
            self.refused = reason
        return reason


def _left_policy(result: PageResult, url: str, refusal: NavigationRefusal | None) -> str | None:
    """Why the page is now at a URL the policy refuses (location.href after redirects
    and script navigations), or None when it stayed within it."""
    if refusal is None or result.metrics is None:
        return None
    final = result.metrics.get("url")
    if not isinstance(final, str) or urlsplit(final).scheme not in _WEB_SCHEMES:
        return None
    try:
        if urlpolicy.normalize(final) == urlpolicy.normalize(url):
            return None
    except ValueError:
        pass  # The requested URL is a local file; judge where it went.
    return refusal(final)


def _harness_failure(
    engine: Engine, url: str, directory: Path, error: Exception, out: TextIO
) -> PageResult:
    """Record an unexpected exception as an engine result with status ERROR.

    The traceback goes to a private file in `directory`, since exception messages
    can contain the URL; the progress stream gets the host and the exception type.
    Writing it is best effort: on a full disk the run goes on (and stops after
    MAX_CONSECUTIVE_HARNESS_ERRORS) instead of ending in a traceback.
    """
    traceback_path = directory / _TRACEBACK_NAMES[engine]
    try:
        config.write_private_file(traceback_path, traceback.format_exc().encode("utf-8"))
    except OSError as write_error:
        where = f"no traceback file ({write_error.strerror or type(write_error).__name__})"
    else:
        where = f"traceback in {traceback_path}"
    print(
        f"harness error ({engine.value}) on {display_host(url)}: {type(error).__name__}; {where}",
        file=out,
    )
    return PageResult(
        engine=engine,
        url=url,
        status=PageStatus.ERROR,
        duration_ms=0,
        detail=f"harness exception: {type(error).__name__}: {error}",
    )


def _record(
    store: Store, run_id: str, run_path: Path, target: RunTarget, outcome: PageOutcome
) -> IssueUpdate | None:
    """Store the page's attempts, its issues and what loading it revealed."""
    last = outcome.attempts[-1]
    for attempt in outcome.attempts:
        # The highest attempt is the page's outcome in the store, so it carries the
        # final verdict; its own verdict stays in the metrics.
        verdict = outcome.verdict if attempt is last else attempt.verdict
        store.record_result(
            run_id,
            target.url,
            attempt.number,
            verdict.kind,
            verdict.severity,
            verdict.signatures,
            _stored_metrics(outcome, attempt, verdict, target.bucket),
            attempt.directory.relative_to(run_path).as_posix(),
            attempt.servo.duration_ms,
        )

    if outcome.excluded is not None:
        store.exclude_after_load(target.url, f"navigated to an excluded URL ({outcome.excluded})")
    store.add_derived_hosts(_seen_hosts(outcome))

    sightings = [
        IssueSighting(finding.signature, finding.kind, target.url)
        for finding in outcome.verdict.findings
        if finding.signature
    ]
    if not sightings:
        return None
    return store.upsert_issues(run_id, sightings)


def _seen_hosts(outcome: PageOutcome) -> set[str]:
    """Hosts the page led to while loading: where it ended up and where its failing
    scripts came from. They are history-derived even when they are not pages."""
    results = [attempt.servo for attempt in outcome.attempts]
    if outcome.reference is not None:
        results.append(outcome.reference.result)
    urls: list[object] = []
    for result in results:
        metrics = result.metrics or {}
        urls.append(metrics.get("url"))
        urls += [error.source for error in result.errors]
        captured = metrics.get("errors")
        if isinstance(captured, list):
            urls += [entry.get("source") for entry in captured if isinstance(entry, Mapping)]
    hosts = set()
    for url in urls:
        if not isinstance(url, str):
            continue
        parts = urlsplit(url)
        if parts.scheme in _WEB_SCHEMES and parts.hostname:
            hosts.add(parts.hostname.lower())
    return hosts


def _stored_metrics(
    outcome: PageOutcome, attempt: Attempt, verdict: Verdict, bucket: str | None
) -> dict[str, Any]:
    reference_outcome = outcome.reference
    reference_record = None
    if reference_outcome is not None:
        reference_record = _engine_record(reference_outcome.result)
        reference_record["cached"] = reference_outcome.cached
    return {
        "bucket": bucket,
        "attempt_verdict": attempt.verdict.kind.value,
        "flaky": verdict.kind is VerdictKind.FLAKY,
        "findings": [_finding_record(finding) for finding in verdict.findings],
        "servo": _engine_record(attempt.servo),
        "reference": reference_record,
        "images": None if attempt.images is None else dict(attempt.images),
    }


def _engine_record(result: PageResult) -> dict[str, Any]:
    """What the store keeps of one engine's result: everything but the page text."""
    return {
        "status": result.status.value,
        "duration_ms": result.duration_ms,
        "navigation_timed_out": result.navigation_timed_out,
        "detail": result.detail,
        "crash": None if result.crash is None else asdict(result.crash),
        "error_count": len(result.errors),
        "errors": [asdict(error) for error in result.errors[:_STORED_ERRORS_MAX]],
        "metrics": _without_text(result.metrics),
    }


def _finding_record(finding: Finding) -> dict[str, Any]:
    return {
        "kind": finding.kind.value,
        "severity": finding.severity,
        "detail": finding.detail,
        "signature": finding.signature,
    }


def _without_text(metrics: Mapping[str, Any] | None) -> dict[str, Any] | None:
    if metrics is None:
        return None
    return {key: value for key, value in metrics.items() if key != "text"}


def _image_evidence(
    comparer: ImageComparer | None, servo_result: PageResult, reference_result: PageResult | None
) -> Mapping[str, Any] | None:
    if comparer is None or reference_result is None:
        return None
    return comparer(servo_result.screenshot, reference_result.screenshot)


@dataclass(frozen=True)
class _EngineFiles:
    """The names of one engine's record and text files in a page directory."""

    record: str
    text: str


_SERVO_FILES = _EngineFiles(SERVO_RECORD_NAME, _SERVO_TEXT_NAME)
_REFERENCE_FILES = _EngineFiles(REFERENCE_RECORD_NAME, _REFERENCE_TEXT_NAME)


def _write_engine_files(directory: Path, result: PageResult, names: _EngineFiles) -> None:
    """The result as JSON without the text, and the text alone as gzip, for debugging."""
    record = result.to_json()
    record["metrics"] = _without_text(result.metrics)
    config.write_private_file(
        directory / names.record, json.dumps(record, indent=2).encode("utf-8")
    )

    text = (result.metrics or {}).get("text")
    text_path = directory / names.text
    if not isinstance(text, str):
        text_path.unlink(missing_ok=True)
        return
    config.write_private_file(text_path, gzip.compress(text.encode("utf-8"), mtime=0))


def _is_cacheable(url: str) -> bool:
    """Whether the reference of `url` may be cached: a web page on a public host."""
    return urlpolicy.classify(url).reason not in _LOCAL_REASONS
