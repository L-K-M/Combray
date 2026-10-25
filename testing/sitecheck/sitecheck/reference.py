"""Reference runner: loads a page in headless Chromium through Playwright.

Chromium is the baseline Servo's result is compared with, so it loads each page the
way servo.py does: the same user agent, viewport, pixel ratio, timings
(config.VisitTiming), settle-and-scroll sequence and probe, with a new browser context
(no cookies, no storage, service workers blocked) per page. The context emulates a
mobile viewport but no touch screen, since desktop servoshell reports a fine pointer
with hover. One browser process serves the whole run; it gets the same allowlisted
environment as servoshell. Main-frame navigations to URLs the run's policy refuses
(a script sending the page to a logout link) are blocked; a server redirect is
followed before the request can be seen, so the runner also checks where the page
ended up.

Playwright is imported inside functions so that the pure modules, and the unit tests
on a Python without third-party packages, import this package unharmed.

The async API runs on a private event loop behind a synchronous interface. The sync
API cannot abandon a call that never returns (page.evaluate() has no timeout, and a
page that keeps its main thread busy never answers), so one page could stall an
unattended run indefinitely; with asyncio every page gets a hard wall clock.

Reference results can be cached for a few days (`load_cached_reference`,
`store_cached_reference`), which roughly halves a run's time.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import functools
import hashlib
import json
import os
import re
import shutil
import tempfile
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from enum import Enum
from pathlib import Path
from typing import TYPE_CHECKING, Any

from sitecheck import config
from sitecheck.model import (
    MAX_ERROR_MESSAGE_CHARS,
    CrashInfo,
    Engine,
    PageError,
    PageResult,
    PageStatus,
)
from sitecheck.servo import (
    ERRORS_USERSCRIPT_PATH,
    SCROLL_TO_BOTTOM_SCRIPT,
    SCROLL_TO_TOP_SCRIPT,
)

if TYPE_CHECKING:
    from playwright.async_api import (
        Browser,
        BrowserContext,
        ConsoleMessage,
        Page,
        Playwright,
        Route,
    )
    from playwright.async_api import Error as PlaywrightError

# servo.py loads the same file with --userscripts.
ERRORS_USERSCRIPT = ERRORS_USERSCRIPT_PATH

_PROJECT_DIR = Path(__file__).resolve().parent.parent
_SYNC_COMMAND = f"uv sync --project {_PROJECT_DIR}"
# Only the headless shell is needed: launch(headless=True) uses it, and it is a
# fraction of the full browser's download.
INSTALL_COMMAND = f"uv run --project {_PROJECT_DIR} playwright install chromium --only-shell"
# Playwright's launch error when the browser build for its version is not installed.
_MISSING_EXECUTABLE_MARKER = "Executable doesn't exist"

SCREENSHOT_NAME = "reference.png"

# Pages that log in a loop would otherwise grow the result without bound.
_MAX_ERRORS = 200
# Closing a context or the browser is bounded so a wedged renderer cannot stall
# the run; Playwright's driver kills Chromium when it stops.
_CLOSE_TIMEOUT_S = 10.0
# Raised by evaluate() when a script navigation replaced the document under it.
_CONTEXT_DESTROYED_MARKER = "Execution context was destroyed"
# How a blocked navigation fails (Chromium's net::ERR_BLOCKED_BY_CLIENT).
_BLOCKED_ERROR_CODE = "blockedbyclient"
_ALL_REQUESTS = "**/*"
# Chromium's network error code in a goto() failure, e.g. net::ERR_NAME_NOT_RESOLVED.
_NET_ERROR = re.compile(r"net::ERR_[A-Z0-9_]+")
# A V8 stack frame: "    at fn (https://host/a.js:12:34)" or "    at https://host/a.js:12:34".
_STACK_FRAME = re.compile(r"^\s*at (?:.*? \()?(?P<source>\S+?):(?P<line>\d+):(?P<column>\d+)\)?$")

_CACHE_FORMAT = 1
_CACHE_RECORD_NAME = "reference.json"
DEFAULT_CACHE_MAX_AGE = timedelta(days=3)


class ReferenceUnavailable(RuntimeError):
    """Playwright or its Chromium is missing or cannot start; the message says how to fix it."""


# Chromium's time limits are the shared visit timing, so the two captures match.
ReferenceTiming = config.VisitTiming
DEFAULT_TIMING = config.DEFAULT_VISIT_TIMING


class CachePolicy(Enum):
    """Whether a run may reuse cached reference results."""

    USE = "use"  # Reuse entries younger than the maximum age.
    REFRESH = "refresh"  # --fresh-reference: ignore entries; new results replace them.


@dataclass(frozen=True)
class CachedReference:
    result: PageResult  # Its screenshot has been copied into the page directory.
    captured_at: datetime


def ensure_browser() -> str:
    """Check that Playwright and its Chromium are installed; return Chromium's version.

    Raises ReferenceUnavailable with the exact command to run otherwise. Nothing is
    downloaded here: an unattended run must not fetch a browser on its own.
    """
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(_installed_browser_version())
    finally:
        loop.close()


class ChromiumReference:
    """One headless Chromium for a run; `run_page` visits one page in a fresh context.

    Use as a context manager. Not thread-safe; call it from one thread.
    """

    def __init__(
        self,
        *,
        viewport: tuple[int, int] = (config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT),
        errors_userscript: Path = ERRORS_USERSCRIPT,
    ) -> None:
        self._viewport = viewport
        self._errors_userscript = errors_userscript
        self._loop: asyncio.AbstractEventLoop | None = None
        self._playwright: Playwright | None = None
        self._browser: Browser | None = None

    def __enter__(self) -> ChromiumReference:
        if not self._errors_userscript.is_file():
            raise ReferenceUnavailable(f"error capture script not found: {self._errors_userscript}")

        async_playwright = _import_playwright()
        self._loop = asyncio.new_event_loop()
        try:
            self._playwright = self._loop.run_until_complete(async_playwright().start())
            self._browser = self._loop.run_until_complete(_launch(self._playwright))
        except BaseException:
            self._close()
            raise
        return self

    def __exit__(self, *exc_info: object) -> None:
        self._close()

    @property
    def browser_version(self) -> str:
        """Chromium's version, for the run record."""
        browser = self._connected_browser()
        return f"chromium {browser.version}"

    def run_page(
        self,
        url: str,
        out_dir: Path,
        *,
        user_agent: str,
        probe_source: str,
        timing: ReferenceTiming = DEFAULT_TIMING,
        navigation_refusal: Callable[[str], str | None] | None = None,
    ) -> PageResult:
        """Load `url`, probe it and save its screenshot as `out_dir`/reference.png.

        `out_dir` is the page's artifact directory. `probe_source` is probe.js: a
        W3C WebDriver script body that ends in `return`. `navigation_refusal` names
        the URLs the main frame must not navigate to. Failures become the result's
        status (never an exception), except ReferenceUnavailable when Chromium
        cannot be restarted.
        """
        from playwright.async_api import Error as PlaywrightError

        loop = self._require_loop()
        browser = self._connected_browser()
        out_dir.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        capture = _Capture()

        def result(outcome: _Outcome) -> PageResult:
            return PageResult(
                engine=Engine.CHROMIUM,
                url=url,
                status=outcome.status,
                duration_ms=round((time.monotonic() - started) * 1000),
                metrics=outcome.metrics,
                screenshot=outcome.screenshot,
                errors=list(capture.errors),
                crash=outcome.crash,
                navigation_timed_out=capture.navigation_timed_out,
                detail=outcome.detail,
            )

        try:
            context = loop.run_until_complete(
                asyncio.wait_for(
                    self._new_context(browser, user_agent, navigation_refusal),
                    timing.script_timeout,
                )
            )
        except (TimeoutError, PlaywrightError) as error:
            self._discard_browser()
            detail = f"could not open a context: {_describe(error)}"
            return result(_Outcome(PageStatus.ERROR, detail=detail))

        try:
            outcome = loop.run_until_complete(
                asyncio.wait_for(
                    _visit(
                        context,
                        url=url,
                        out_dir=out_dir,
                        probe_source=probe_source,
                        timing=timing,
                        capture=capture,
                    ),
                    timing.wall_clock,
                )
            )
        except TimeoutError:
            # Only the outer wait_for raises TimeoutError here; _visit handles its own.
            outcome = _Outcome(
                PageStatus.HANG, detail=f"exceeded the {timing.wall_clock:g} s wall clock"
            )
        except PlaywrightError as error:
            # Steps outside _visit's own handling, such as opening the tab, fail
            # when the browser itself went away; the next page relaunches it.
            self._discard_browser()
            outcome = _Outcome(PageStatus.ERROR, detail=f"browser failed: {_describe(error)}")
        finally:
            self._close_context(context)
        return result(outcome)

    async def _new_context(
        self,
        browser: Browser,
        user_agent: str,
        navigation_refusal: Callable[[str], str | None] | None = None,
    ) -> BrowserContext:
        width, height = self._viewport
        context = await browser.new_context(
            viewport={"width": width, "height": height},
            device_scale_factor=config.DEVICE_PIXEL_RATIO,
            # is_mobile applies the viewport meta tag, as Servo does with Combray's
            # preferences. No touch: desktop servoshell has a fine pointer and hover,
            # and pages choose their layout from those media features.
            is_mobile=True,
            has_touch=False,
            user_agent=user_agent,
            java_script_enabled=True,
            service_workers="block",
            accept_downloads=False,
        )
        # The same early error capture Servo gets through --userscripts, so the
        # probe's `errors` field means the same in both engines.
        await context.add_init_script(path=self._errors_userscript)
        if navigation_refusal is not None:
            await context.route(_ALL_REQUESTS, _navigation_guard(navigation_refusal))
        return context

    def _close_context(self, context: BrowserContext) -> None:
        loop = self._require_loop()
        try:
            loop.run_until_complete(asyncio.wait_for(context.close(), _CLOSE_TIMEOUT_S))
        except Exception:
            # A context that does not close means a wedged browser: start a new
            # one for the next page rather than reuse it.
            self._discard_browser()

    def _connected_browser(self) -> Browser:
        """The run's browser, relaunched if it crashed or was discarded."""
        loop = self._require_loop()
        if self._browser is not None and self._browser.is_connected():
            return self._browser

        assert self._playwright is not None
        self._discard_browser()
        self._browser = loop.run_until_complete(_launch(self._playwright))
        return self._browser

    def _discard_browser(self) -> None:
        browser, self._browser = self._browser, None
        if browser is None or self._loop is None:
            return
        # Best effort: the browser is already unusable, and Playwright's driver
        # kills it at the latest when the run stops the driver.
        with contextlib.suppress(Exception):
            self._loop.run_until_complete(asyncio.wait_for(browser.close(), _CLOSE_TIMEOUT_S))

    def _require_loop(self) -> asyncio.AbstractEventLoop:
        if self._loop is None:
            raise RuntimeError("ChromiumReference must be used as a context manager")
        return self._loop

    def _close(self) -> None:
        loop = self._loop
        if loop is None:
            return

        self._discard_browser()
        playwright, self._playwright = self._playwright, None
        if playwright is not None:
            # Best effort during shutdown; stopping the driver also ends Chromium.
            with contextlib.suppress(Exception):
                loop.run_until_complete(asyncio.wait_for(playwright.stop(), _CLOSE_TIMEOUT_S))
        with contextlib.suppress(Exception):
            loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()
        self._loop = None


def load_cached_reference(
    data_dir: Path,
    url: str,
    out_dir: Path,
    *,
    user_agent: str,
    probe_source: str,
    browser_version: str = "",
    timing: ReferenceTiming = DEFAULT_TIMING,
    viewport: tuple[int, int] = (config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT),
    policy: CachePolicy = CachePolicy.USE,
    max_age: timedelta = DEFAULT_CACHE_MAX_AGE,
    now: datetime | None = None,
) -> CachedReference | None:
    """A cached reference result for `url`, or None when there is no usable entry.

    An entry is used only when it is younger than `max_age` and was captured with
    the same user agent, browser version, viewport, timings, probe and error
    capture script. Its screenshot is copied to `out_dir`/reference.png so the
    run's artifacts are self-contained. An entry that cannot be read counts as a
    miss and is replaced by the next store. Entries hold the page text: they are
    as private as the run directories.
    """
    if policy is CachePolicy.REFRESH:
        return None

    entry = config.reference_cache_dir(data_dir, url)
    record = _read_cache_record(entry / _CACHE_RECORD_NAME)
    if record is None or record.get("url") != url:
        return None
    fingerprint = _cache_fingerprint(user_agent, viewport, probe_source, browser_version, timing)
    if record.get("fingerprint") != fingerprint:
        return None

    captured_at = _parse_timestamp(record.get("captured_at"))
    current = now or datetime.now(UTC)
    if captured_at is None or captured_at > current or current - captured_at >= max_age:
        return None

    cached_screenshot = entry / SCREENSHOT_NAME
    result = _result_from_json(record.get("result"))
    if result is None or result.url != url or not cached_screenshot.is_file():
        return None

    out_dir.mkdir(parents=True, exist_ok=True)
    screenshot = out_dir / SCREENSHOT_NAME
    shutil.copyfile(cached_screenshot, screenshot)
    result.screenshot = screenshot
    return CachedReference(result, captured_at)


def store_cached_reference(
    data_dir: Path,
    result: PageResult,
    *,
    user_agent: str,
    probe_source: str,
    browser_version: str = "",
    timing: ReferenceTiming = DEFAULT_TIMING,
    viewport: tuple[int, int] = (config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT),
    now: datetime | None = None,
) -> bool:
    """Cache a reference result; return whether it was stored.

    Only complete results (status ok with a screenshot) are stored, so a page that
    failed in Chromium is retried on the next run instead of failing for days.
    """
    assert result.engine is Engine.CHROMIUM, "only reference results are cached"
    if result.status is not PageStatus.OK or result.screenshot is None:
        return False

    config.ensure_private_dir(data_dir / config.REFERENCE_CACHE_DIR_NAME)
    entry = config.ensure_private_dir(config.reference_cache_dir(data_dir, result.url))
    record = {
        "format": _CACHE_FORMAT,
        "url": result.url,
        "captured_at": (now or datetime.now(UTC)).isoformat(timespec="seconds"),
        "fingerprint": _cache_fingerprint(
            user_agent, viewport, probe_source, browser_version, timing
        ),
        "result": result.to_json(),
    }
    # Any earlier record goes first and the new one last: a record names a complete
    # entry. In between the entry is a miss, which prune removes if it stays one.
    screenshot = result.screenshot.read_bytes()
    with contextlib.suppress(FileNotFoundError):
        (entry / _CACHE_RECORD_NAME).unlink()
    _replace_file(entry / SCREENSHOT_NAME, lambda handle: handle.write(screenshot))
    _replace_file(
        entry / _CACHE_RECORD_NAME,
        lambda handle: handle.write(json.dumps(record, ensure_ascii=False).encode("utf-8")),
    )
    return True


def prune_reference_cache(
    data_dir: Path, *, max_age: timedelta = DEFAULT_CACHE_MAX_AGE, now: datetime | None = None
) -> int:
    """Delete cache entries older than `max_age` (or unreadable); return how many.

    Cached pages are history-derived data, so they are not kept beyond their use.
    """
    root = data_dir / config.REFERENCE_CACHE_DIR_NAME
    if not root.is_dir():
        return 0

    current = now or datetime.now(UTC)
    removed = 0
    for entry in root.iterdir():
        if entry.is_symlink() or not entry.is_dir():
            continue
        record = _read_cache_record(entry / _CACHE_RECORD_NAME)
        captured_at = _parse_timestamp(record.get("captured_at")) if record else None
        if captured_at is not None and timedelta(0) <= current - captured_at < max_age:
            continue
        shutil.rmtree(entry)
        removed += 1
    return removed


@dataclass
class _Capture:
    """What a page reported while it loaded; it outlives a wall-clock cancellation."""

    errors: list[PageError] = field(default_factory=list)
    crashed: bool = False
    navigation_timed_out: bool = False

    def attach(self, page: Page) -> None:
        page.on("pageerror", self._on_page_error)
        page.on("console", self._on_console)
        page.on("crash", self._on_crash)

    def _on_page_error(self, error: PlaywrightError) -> None:
        source, line, column = _first_stack_frame(error.stack)
        self._add(PageError("uncaught", error.message, source, line, column))

    def _on_console(self, message: ConsoleMessage) -> None:
        if message.type != "error":
            return
        location = message.location
        self._add(
            PageError(
                "console",
                message.text,
                location.get("url") or None,
                _one_based(location.get("lineNumber")),
                _one_based(location.get("columnNumber")),
            )
        )

    def _on_crash(self, _page: Page) -> None:
        self.crashed = True

    def _add(self, error: PageError) -> None:
        if len(self.errors) >= _MAX_ERRORS:
            return
        if len(error.message) > MAX_ERROR_MESSAGE_CHARS:
            error = PageError(
                error.kind,
                error.message[:MAX_ERROR_MESSAGE_CHARS],
                error.source,
                error.line,
                error.column,
            )
        self.errors.append(error)


@dataclass(frozen=True)
class _Outcome:
    status: PageStatus
    metrics: dict[str, Any] | None = None
    screenshot: Path | None = None
    crash: CrashInfo | None = None
    detail: str | None = None


_RENDERER_CRASH = _Outcome(PageStatus.CRASH, crash=CrashInfo("Chromium renderer crashed"))


async def _visit(
    context: BrowserContext,
    *,
    url: str,
    out_dir: Path,
    probe_source: str,
    timing: ReferenceTiming,
    capture: _Capture,
) -> _Outcome:
    from playwright.async_api import Error as PlaywrightError
    from playwright.async_api import TimeoutError as PlaywrightTimeoutError

    page = await context.new_page()
    capture.attach(page)

    try:
        await page.goto(url, wait_until="load", timeout=timing.page_load_timeout * 1000)
    except PlaywrightTimeoutError:
        # Pages often work without ever firing load (a hanging tracker), so the
        # visit continues, as it does in Servo.
        capture.navigation_timed_out = True
    except PlaywrightError as error:
        if capture.crashed:
            return _RENDERER_CRASH
        return _Outcome(PageStatus.NAVIGATION_ERROR, detail=_navigation_error(error))

    screenshot = out_dir / SCREENSHOT_NAME
    try:
        await asyncio.sleep(timing.settle)
        await _evaluate(page, _as_function(SCROLL_TO_BOTTOM_SCRIPT), timing)
        await asyncio.sleep(timing.scroll_pause)
        await _evaluate(page, _as_function(SCROLL_TO_TOP_SCRIPT), timing)
        await asyncio.sleep(timing.scroll_pause)
        metrics = await _evaluate(page, _as_function(probe_source), timing)
        await page.screenshot(path=screenshot, timeout=timing.script_timeout * 1000)
    except (TimeoutError, PlaywrightTimeoutError):
        # _evaluate's own deadline or the screenshot's: the page keeps the
        # renderer's main thread busy.
        if capture.crashed:
            return _RENDERER_CRASH
        detail = f"a script or the screenshot did not finish within {timing.script_timeout:g} s"
        return _Outcome(PageStatus.HANG, detail=detail)
    except PlaywrightError as error:
        if capture.crashed:
            return _RENDERER_CRASH
        return _Outcome(PageStatus.ERROR, detail=f"page step failed: {_describe(error)}")

    if not isinstance(metrics, dict):
        return _Outcome(
            PageStatus.ERROR, detail=f"probe returned {type(metrics).__name__}, not an object"
        )
    return _Outcome(PageStatus.OK, metrics=metrics, screenshot=screenshot)


def _navigation_guard(refusal: Callable[[str], str | None]) -> Callable[[Route], Any]:
    """A route handler that aborts main-frame navigations the policy refuses."""

    async def handle(route: Route) -> None:
        request = route.request
        try:
            main_frame = request.frame.parent_frame is None
        except Exception:  # A request of a service worker or a closed frame.
            main_frame = False
        if _blocks_navigation(request.url, request.is_navigation_request(), main_frame, refusal):
            await route.abort(_BLOCKED_ERROR_CODE)
            return
        await route.fallback()

    return handle


def _blocks_navigation(
    url: str,
    is_navigation: bool,
    main_frame: bool,
    refusal: Callable[[str], str | None] | None,
) -> bool:
    """Whether a request is a main-frame navigation to a URL the policy refuses.

    Frames and subresources are part of the page both engines load anyway.
    """
    if refusal is None or not is_navigation or not main_frame:
        return False
    return refusal(url) is not None


async def _evaluate(page: Page, expression: str, timing: ReferenceTiming) -> Any:
    """Run a function expression in the page, with the script timeout.

    A page that navigates itself from script (a client-side redirect after load)
    destroys the document under the call; it is retried once on the new document.
    """
    from playwright.async_api import Error as PlaywrightError

    try:
        return await asyncio.wait_for(page.evaluate(expression), timing.script_timeout)
    except PlaywrightError as error:
        if _CONTEXT_DESTROYED_MARKER not in error.message:
            raise
    await asyncio.sleep(timing.scroll_pause)
    return await asyncio.wait_for(page.evaluate(expression), timing.script_timeout)


def _as_function(script_body: str) -> str:
    """Turn a W3C WebDriver script body (which uses `return`) into a function expression.

    The body goes on its own lines so a trailing line comment cannot swallow the brace.
    """
    return "() => {\n" + script_body + "\n}"


async def _installed_browser_version() -> str:
    async_playwright = _import_playwright()
    async with async_playwright() as playwright:
        browser = await _launch(playwright)
        try:
            return browser.version
        finally:
            await browser.close()


async def _launch(playwright: Playwright) -> Browser:
    from playwright.async_api import Error as PlaywrightError

    try:
        # The same allowlisted environment as servoshell: no Hister settings.
        return await playwright.chromium.launch(headless=True, env=config.child_environment())
    except PlaywrightError as error:
        if _MISSING_EXECUTABLE_MARKER in error.message:
            raise ReferenceUnavailable(
                f"Playwright's Chromium is not installed. Run: {INSTALL_COMMAND}"
            ) from error
        raise ReferenceUnavailable(f"Chromium did not start: {_describe(error)}") from error


def _import_playwright() -> Callable[[], Any]:
    try:
        from playwright.async_api import async_playwright
    except ImportError as error:
        raise ReferenceUnavailable(
            f"Playwright is not installed in this Python environment. Run: {_SYNC_COMMAND}"
        ) from error
    return async_playwright


def _navigation_error(error: PlaywrightError) -> str:
    # The full message repeats the URL; the network error code is what matters.
    code = _NET_ERROR.search(error.message)
    if code:
        return code.group(0)
    return _describe(error)


def _describe(error: BaseException) -> str:
    """The first line of an error, which is Playwright's summary without its call log."""
    message = getattr(error, "message", None) or str(error) or type(error).__name__
    return message.strip().splitlines()[0][:MAX_ERROR_MESSAGE_CHARS]


def _first_stack_frame(stack: str | None) -> tuple[str | None, int | None, int | None]:
    for line in (stack or "").splitlines():
        match = _STACK_FRAME.match(line)
        if match:
            return match.group("source"), int(match.group("line")), int(match.group("column"))
    return None, None, None


def _one_based(value: object) -> int | None:
    """A console location's 0-based line or column as the 1-based number Servo reports."""
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value + 1


def _cache_fingerprint(
    user_agent: str,
    viewport: tuple[int, int],
    probe_source: str,
    browser_version: str,
    timing: ReferenceTiming,
) -> str:
    """Identifies the settings a cached result depends on; a change invalidates it."""
    settings = {
        "format": _CACHE_FORMAT,
        "user_agent": user_agent,
        "viewport": list(viewport),
        "device_pixel_ratio": config.DEVICE_PIXEL_RATIO,
        "probe": _sha256(probe_source),
        "errors_userscript": _sha256(_errors_userscript_source()),
        "browser": browser_version,
        "timing": dataclasses.asdict(timing),
    }
    return hashlib.sha256(json.dumps(settings, sort_keys=True).encode("utf-8")).hexdigest()


@functools.cache
def _errors_userscript_source() -> str:
    return ERRORS_USERSCRIPT.read_text(encoding="utf-8")


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _read_cache_record(path: Path) -> dict[str, Any] | None:
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(record, dict) or record.get("format") != _CACHE_FORMAT:
        return None
    return record


def _parse_timestamp(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed


def _result_from_json(data: object) -> PageResult | None:
    """Rebuild a cached ok result; None when the record does not have that shape."""
    if not isinstance(data, dict):
        return None
    try:
        if Engine(data["engine"]) is not Engine.CHROMIUM:
            return None
        if PageStatus(data["status"]) is not PageStatus.OK:
            return None
        metrics = data["metrics"]
        if not isinstance(metrics, dict):
            return None
        errors = [PageError(**error) for error in data.get("errors") or []]
        return PageResult(
            engine=Engine.CHROMIUM,
            url=str(data["url"]),
            status=PageStatus.OK,
            duration_ms=int(data["duration_ms"]),
            metrics=metrics,
            errors=errors,
            navigation_timed_out=bool(data.get("navigation_timed_out", False)),
        )
    except (KeyError, TypeError, ValueError):
        return None


def _replace_file(path: Path, write: Callable[[Any], object]) -> None:
    """Write a file through a temporary sibling and rename it into place."""
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with os.fdopen(descriptor, "wb") as handle:
            write(handle)
        os.replace(temporary, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(temporary)
        raise
