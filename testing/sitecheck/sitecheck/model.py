"""Value types shared by the site check's runners, comparison, store and reports.

They stay plain dataclasses so that every pure module imports with the standard
library alone, and so a result can round-trip through JSON for the store.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

# Characters kept of one script error message, in both engines and in
# errors_userscript.js (MAX_MESSAGE_LENGTH there; a test keeps them equal), so a
# long message clipped in one engine is clipped the same way in the other.
MAX_ERROR_MESSAGE_CHARS = 1000


class Engine(str, Enum):
    SERVO = "servo"
    CHROMIUM = "chromium"


class PageStatus(str, Enum):
    """How far one engine got with one page."""

    OK = "ok"  # Probed and captured (the load event may still have timed out).
    CRASH = "crash"  # The engine process died or panicked.
    HANG = "hang"  # Stopped responding (a script or command timed out) or hit its wall clock.
    NAVIGATION_ERROR = "navigation-error"  # The engine reported the page could not load.
    ERROR = "error"  # The harness failed; not evidence about the engine.


class VerdictKind(str, Enum):
    """Servo's outcome on a page relative to the reference engine, worst first."""

    CRASH = "crash"
    HANG = "hang"
    BLANK = "blank"
    CONTENT_MISSING = "content-missing"
    JS_ERROR = "js-error"
    LAYOUT_OVERFLOW = "layout-overflow"
    IMAGES = "images"
    SLOW_LOAD = "slow-load"
    VISUAL_DIFF = "visual-diff"
    FLAKY = "flaky"
    OK = "ok"
    REFERENCE_FAILED = "reference-failed"  # No usable baseline; excluded from triage.
    NOT_COMPARED = "not-compared"  # Run without a reference: only crash and hang judged.
    EXCLUDED = "excluded"  # The page navigated to a URL the policy excludes; discarded.
    HARNESS_ERROR = "harness-error"  # The harness failed; excluded from triage.

    @property
    def triaged(self) -> bool:
        """Whether this outcome is evidence about Servo, as opposed to the baseline,
        the harness or the URL policy. The one place that decides it."""
        return self not in _UNJUDGED_KINDS

    @property
    def is_failure(self) -> bool:
        """Whether this outcome is evidence of a Servo problem (flaky included)."""
        return self.triaged and self is not VerdictKind.OK


# Outcomes that say nothing about Servo: no baseline, no comparison, a page the
# policy excludes, or a harness failure. Never in the triage queue or rechecks.
_UNJUDGED_KINDS = frozenset(
    {
        VerdictKind.REFERENCE_FAILED,
        VerdictKind.NOT_COMPARED,
        VerdictKind.EXCLUDED,
        VerdictKind.HARNESS_ERROR,
    }
)


@dataclass(frozen=True)
class PageError:
    """A script error or console error an engine reported for a page."""

    kind: str  # "uncaught", "rejection" or "console"
    message: str
    source: str | None = None  # Script URL; kept local, never placed in signatures.
    line: int | None = None
    column: int | None = None


@dataclass(frozen=True)
class CrashInfo:
    """The first panic or crash report an engine produced."""

    message: str
    location: str | None = None  # "components/x/y.rs:12:5" when a Rust panic named it.
    exit_code: int | None = None
    signal: int | None = None


@dataclass
class PageResult:
    """One engine's attempt at one page."""

    engine: Engine
    url: str
    status: PageStatus
    duration_ms: int
    metrics: dict[str, Any] | None = None  # probe.js output, see probe.js.
    screenshot: Path | None = None
    log: Path | None = None
    errors: list[PageError] = field(default_factory=list)
    crash: CrashInfo | None = None
    navigation_timed_out: bool = False
    detail: str | None = None  # Harness-side explanation for non-OK statuses.
    # For HANG: what stopped and where, e.g. "script-timeout:scroll" or
    # "wall-clock:navigate". Part of the hang signature; never names the page.
    hang_cause: str | None = None

    def to_json(self) -> dict[str, Any]:
        data = asdict(self)
        data["engine"] = self.engine.value
        data["status"] = self.status.value
        data["screenshot"] = str(self.screenshot) if self.screenshot else None
        data["log"] = str(self.log) if self.log else None
        return data


@dataclass(frozen=True)
class Finding:
    """One observed difference, with the severity the comparison gave it."""

    kind: VerdictKind
    severity: int  # 0 (fine) to 5 (crash).
    detail: str  # Counts and ratios only; never page text.
    signature: str | None = None  # Groups the same root cause across pages.


@dataclass(frozen=True)
class Verdict:
    kind: VerdictKind
    severity: int
    findings: tuple[Finding, ...] = ()

    @property
    def signatures(self) -> list[str]:
        return [f.signature for f in self.findings if f.signature]

    @property
    def triaged(self) -> bool:
        """Whether the verdict is evidence about Servo (see `VerdictKind.triaged`)."""
        return self.kind.triaged

    @property
    def is_failure(self) -> bool:
        """Whether the verdict is evidence of a Servo problem (flaky included)."""
        return self.kind.is_failure
