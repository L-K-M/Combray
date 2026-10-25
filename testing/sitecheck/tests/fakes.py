"""Scripted stand-ins for both engines and the image comparison, for the runner,
report and CLI tests, and a git that does not finish in time for the leak check
tests. Nothing here starts a process or opens a socket."""

from __future__ import annotations

import contextlib
import subprocess
from collections.abc import Callable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from unittest import mock

from sitecheck.model import CrashInfo, Engine, PageError, PageResult, PageStatus
from sitecheck.reference import CachePolicy
from sitecheck.runner import ReferenceOutcome

# Enough distinct words (three letters or more, no digits) for the content rule.
WORDS = (
    "alpha bravo charlie delta echo foxtrot golf hotel india juliett kilo lima mike "
    "november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu"
)
FAKE_PNG = b"\x89PNG\r\n\x1a\nfake"


def good_metrics() -> dict[str, Any]:
    return {
        "readyState": "complete",
        "text": WORDS,
        "textLength": len(WORDS),
        "scrollWidth": 412,
        "innerWidth": 412,
        "imagesLoaded": 4,
        "imagesBroken": 0,
        "errors": [],
    }


@dataclass(frozen=True)
class Load:
    """One scripted servoshell load."""

    status: PageStatus = PageStatus.OK
    errors: tuple[PageError, ...] = ()
    crash: CrashInfo | None = None
    metrics: Mapping[str, Any] | None = None  # None: good_metrics() when status is OK.
    detail: str | None = None


OK_LOAD = Load()
CRASH_LOAD = Load(
    PageStatus.CRASH, crash=CrashInfo("boom", "components/layout/flow.rs:42:7", exit_code=101)
)
HANG_LOAD = Load(PageStatus.HANG, detail="wall clock exceeded")
ERROR_LOAD = Load(PageStatus.ERROR, detail="servoshell did not start")
JS_ERROR_LOAD = Load(errors=(PageError("uncaught", "fakeFn is not defined"),))


@dataclass
class FakeServo:
    """Plays `script[url]` load by load; the last entry repeats. Others get `default`."""

    script: dict[str, list[Load | Exception]] = field(default_factory=dict)
    default: Load = OK_LOAD
    calls: list[tuple[str, Path]] = field(default_factory=list)

    def run_page(self, url: str, out_dir: Path) -> PageResult:
        self.calls.append((url, out_dir))
        queue = self.script.get(url)
        step: Load | Exception = self.default
        if queue:
            step = queue.pop(0) if len(queue) > 1 else queue[0]
        if isinstance(step, Exception):
            raise step

        out_dir.mkdir(parents=True, exist_ok=True)
        log = out_dir / "servo.log"
        log.write_text("fake servo log\n", encoding="utf-8")
        ok = step.status is PageStatus.OK
        screenshot = None
        if ok:
            screenshot = out_dir / "servo.png"
            screenshot.write_bytes(FAKE_PNG)
        metrics = None
        if ok:
            metrics = dict(step.metrics) if step.metrics is not None else good_metrics()
        return PageResult(
            engine=Engine.SERVO,
            url=url,
            status=step.status,
            duration_ms=1500,
            metrics=metrics,
            screenshot=screenshot,
            log=log,
            errors=list(step.errors),
            crash=step.crash,
            detail=step.detail,
        )


@dataclass
class FakeReference:
    """A reference engine whose every page loads fine, unless `failing` names it.

    A URL in `stale` comes from the cache with those metrics until a load asks for a
    fresh result (CachePolicy.REFRESH). A URL in `navigates_to` tries to navigate to
    the URL given there, which the navigation refusal may block. A URL in
    `redirects_when_fresh` ends up at the URL given there on a fresh (uncached) load,
    like a server redirect the refusal could not block.
    """

    failing: frozenset[str] = frozenset()
    name: str = "fake-chromium 1.0"
    stale: dict[str, Mapping[str, Any]] = field(default_factory=dict)
    navigates_to: dict[str, str] = field(default_factory=dict)
    redirects_when_fresh: dict[str, str] = field(default_factory=dict)
    calls: list[str] = field(default_factory=list)
    policies: list[CachePolicy | None] = field(default_factory=list)
    refusals: list[Callable[[str], str | None] | None] = field(default_factory=list)

    def load(
        self,
        url: str,
        out_dir: Path,
        *,
        cache: CachePolicy | None = None,
        refusal: Callable[[str], str | None] | None = None,
    ) -> ReferenceOutcome:
        self.calls.append(url)
        self.policies.append(cache)
        self.refusals.append(refusal)
        if url in self.navigates_to and refusal is not None:
            refusal(self.navigates_to[url])
        out_dir.mkdir(parents=True, exist_ok=True)
        if url in self.failing:
            result = PageResult(
                Engine.CHROMIUM, url, PageStatus.NAVIGATION_ERROR, 300, detail="net::ERR_FAILED"
            )
            return ReferenceOutcome(result, cached=False)
        screenshot = out_dir / "reference.png"
        screenshot.write_bytes(FAKE_PNG)
        cached = url in self.stale and cache is not CachePolicy.REFRESH
        metrics = dict(self.stale[url]) if cached else good_metrics()
        if not cached and url in self.redirects_when_fresh:
            metrics["url"] = self.redirects_when_fresh[url]
        result = PageResult(
            Engine.CHROMIUM, url, PageStatus.OK, 1100, metrics=metrics, screenshot=screenshot
        )
        return ReferenceOutcome(result, cached=cached)


def fake_images(servo_png: Path | None, reference_png: Path | None) -> dict[str, Any]:
    """images.compare_screenshots for two similar, non-blank screenshots."""
    stats = {"width": 412, "height": 915, "mean": 200.0, "stddev": 40.0}
    return {
        "servo": stats if servo_png else None,
        "reference": stats if reference_png else None,
        "similarity": {"pixel": 0.95, "ahash": 0.9, "score": 0.9}
        if servo_png and reference_png
        else None,
        "errors": [],
    }


@contextlib.contextmanager
def reference_context(source: FakeReference) -> Iterator[FakeReference]:
    yield source


class StepClock:
    """An aware clock that moves forward a minute per call, so runs are ordered."""

    def __init__(self, start: datetime | None = None) -> None:
        self._now = start or datetime(2026, 10, 24, 1, 0, tzinfo=UTC)

    def __call__(self) -> datetime:
        self._now += timedelta(minutes=1)
        return self._now


def urls_of(pages: Sequence[Mapping[str, Any]]) -> list[str]:
    return [page["url"] for page in pages]


@contextlib.contextmanager
def git_timing_out(*markers: str) -> Iterator[None]:
    """Processes with any of `markers` among their arguments do not exit within the
    timeout they are waited for; every other process runs as usual."""
    real_popen = subprocess.Popen

    class SlowPopen(real_popen):  # type: ignore[misc, valid-type]
        def wait(self, timeout: float | None = None) -> int:
            if timeout is not None and any(marker in self.args for marker in markers):
                raise subprocess.TimeoutExpired(self.args, timeout)
            return super().wait(timeout)

    with mock.patch.object(subprocess, "Popen", SlowPopen):
        yield
