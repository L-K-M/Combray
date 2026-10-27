"""Loads one page in a fresh headless servoshell and reports what Servo did with it.

Each page gets its own servoshell process, started in its own process group with
an empty temporary profile (its temporary directory included, where Servo puts its
disk cache), so a crash or a stuck page cannot affect the next one and no cookies,
storage or cache carry over. The group is led by ``supervisor.py``, which kills it
when the harness dies, however it dies. The runner drives servoshell over W3C
WebDriver: wait for readiness, navigate and wait until the page replaced the initial
about:blank, settle, scroll down and back up, run ``probe.js``, take a screenshot. A
watchdog kills the whole process group when the page exceeds its wall-clock budget.

servoshell's WebDriver server listens on every interface (the downstream build has no
option for the address). The harness creates its session as soon as the server is
ready, which leaves another client a moment to connect first; ``webdriver_exposure``
reports whether the port answers on the machine's network address, and the harness
warns about it once per command.

Evidence comes from three places:

- WebDriver answers: probe metrics, the screenshot, navigation timeouts.
- servoshell's log (stdout and stderr, captured to ``servo.log`` in the page's
  artifact directory). Headless servoshell prints every console message to
  stdout, and logs console errors, uncaught exceptions, unhandled rejections and
  panics to stderr; ``parse_log`` turns the stderr lines into ``PageError`` and
  ``CrashInfo``. stdout lines are stored with a ``stdout| `` prefix, and logged
  console text stays inside its log record, so text a page prints can never pass
  for an engine message: panic patterns are matched only on lines outside both.
- The process itself: an exit the runner did not cause is a crash.

Servo also runs Combray's own page script (``app_page_script``), as the app does.
``errors_userscript.js`` also runs in Servo; its records arrive in the probe's
``errors`` metric. They are a second, partial source (servoshell runs userscripts
only after ``<head>`` is inserted), so ``PageResult.errors`` comes from the log
alone and the userscript records stay in ``metrics["errors"]``.

Log formats were recorded from the downstream servoshell 0.6.0 in October 2026;
see ``parse_log``.
"""

from __future__ import annotations

import contextlib
import enum
import functools
import json
import logging
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import IO, Any

from . import config
from .model import MAX_ERROR_MESSAGE_CHARS, CrashInfo, Engine, PageError, PageResult, PageStatus
from .webdriver import (
    ERROR_SCRIPT_TIMEOUT,
    ERROR_SESSION_NOT_CREATED,
    ERROR_TIMEOUT,
    WebDriverClient,
    WebDriverConnectionError,
    WebDriverError,
    WebDriverTimeout,
)

_LOGGER = logging.getLogger(__name__)

_PACKAGE_DIR = Path(__file__).resolve().parent
_PROBE_PATH = _PACKAGE_DIR / "probe.js"
# Installed for servoshell with --userscripts; reference.py installs it in Chromium.
ERRORS_USERSCRIPT_PATH = _PACKAGE_DIR / "errors_userscript.js"
# Combray's Android build runs native/src/animations.js, with the web-animations-js
# polyfill in its placeholder, before every document's own scripts (see
# native/src/animations.rs). servoshell runs it too, so a page that works only with it
# is judged as the app shows it. Chromium does not need it: the script steps aside for
# a native Element.animate().
_APP_SOURCE_DIR = _PACKAGE_DIR.parents[2] / "native" / "src"
_APP_PAGE_SCRIPT_TEMPLATE = _APP_SOURCE_DIR / "animations.js"
_APP_PAGE_SCRIPT_POLYFILL = _APP_SOURCE_DIR / "vendor" / "web-animations-next-lite.min.js"
_APP_PAGE_SCRIPT_PLACEHOLDER = "__WEB_ANIMATIONS__"
# servoshell runs userscripts in file-name order, so the error capture comes first and
# also records this script's errors.
APP_PAGE_SCRIPT_NAME = "page_script.js"
_SUPERVISOR_PATH = _PACKAGE_DIR / "supervisor.py"
# The supervisor imports nothing outside the standard library; -I keeps the
# harness's environment and paths out, -S skips site packages for a quick start.
_SUPERVISOR_FLAGS = ("-I", "-S")

# Artifact names inside the page directory (config.py owns the directory layout).
_LOG_NAME = "servo.log"
SCREENSHOT_NAME = "servo.png"

# The scroll sequence both engines run after settling, so lazy-loaded content and
# scroll-triggered scripts get the same chance in each. Function bodies, like probe.js.
SCROLL_TO_BOTTOM_SCRIPT = (
    "var element = document.scrollingElement || document.documentElement;\n"
    "window.scrollTo(0, element ? element.scrollHeight : 0);\n"
    "return null;"
)
SCROLL_TO_TOP_SCRIPT = "window.scrollTo(0, 0);\nreturn null;"

_PAGE_SCHEMES = frozenset({"http", "https", "file"})
# servoshell starts on a blank page; the page under test is loaded over WebDriver
# so its URL never appears on a command line (visible to every local user in ps).
_INITIAL_URL = "about:blank"
_PREF_NAME = re.compile(r"[A-Za-z0-9_.]+")

# servoshell's WebDriver server listens on all interfaces; the client uses loopback.
_WEBDRIVER_HOST = "127.0.0.1"
_STATUS_TIMEOUT_S = 2.0
_READY_POLL_INTERVAL_S = 0.1
_SESSION_TIMEOUT_S = 30.0
# Servo's screenshot command first waits for a requestAnimationFrame (bounded by the
# session's script timeout), then up to SCREENSHOT_TIMEOUT for the page to be ready
# (components/webdriver_server/timeout.rs); the socket timeout adds the HTTP margin.
_SERVO_SCREENSHOT_WAIT_S = 10.0
_DELETE_SESSION_TIMEOUT_S = 5.0
# After a dropped connection, how long the process gets to finish dying before the
# runner concludes it is alive but unresponsive.
_EXIT_OBSERVATION_S = 2.0
_READER_JOIN_TIMEOUT_S = 5.0
# Navigation: servoshell can answer the navigate command when the initial
# about:blank finishes loading, before the requested page replaced it, so the
# runner polls the document until it is the page and complete.
_LOCATION_SCRIPT = "return [String(location.href), document.readyState];"
_READY_STATE_COMPLETE = "complete"
_DOCUMENT_POLL_INTERVAL_S = 0.25
_INITIAL_DOCUMENT_TIMEOUT_S = 5.0
# A destination nothing listens on, for finding the machine's network address
# (connecting a UDP socket sends nothing; RFC 5737 TEST-NET-1).
_ROUTE_PROBE_ADDRESS = ("192.0.2.1", 9)
_EXPOSURE_CONNECT_TIMEOUT_S = 1.0

# RUST_LOG=error is servoshell's default; set explicitly because parse_log depends on
# the ERROR lines being present, whatever the caller's environment says.
_CHILD_ENV_OVERRIDES = {"RUST_BACKTRACE": "1", "RUST_LOG": "error"}
_PROFILE_PREFIX = "sitecheck-servo-"
_PROFILE_TMP_NAME = "tmp"

# Bounds on what a page can make the harness store: a page that logs in a loop
# would otherwise fill the disk.
_MAX_LOG_BYTES = 32 * 1024 * 1024
_MAX_LINE_BYTES = 64 * 1024
_STDOUT_PREFIX = b"stdout| "
_STDOUT_PREFIX_TEXT = _STDOUT_PREFIX.decode("ascii")
_LINE_TRUNCATED_SUFFIX = b" [line truncated by sitecheck]\n"
_LOG_TRUNCATED_LINE = b"[sitecheck] log truncated: size cap reached\n"
_MAX_ERRORS = 200
# How much of servo.log an error message quotes when the log itself goes away.
_LOG_TAIL_LINES = 10

# Servo replaces a page it could not load, or whose script thread crashed, with a
# built-in page (components/default-resources/resources/*.html). location.href keeps
# the requested URL, so the page is recognized by its text, its title and its size:
# a real page may well start with the same words.
_NETERROR_TEXT_PREFIX = "Could not load the requested page:"  # neterror.html
_NETERROR_TITLE_LENGTH = len("Error loading page")
_CRASH_PAGE_TEXT_PREFIX = "Servo crashed!"  # crash.html, which has no title
_BADCERT_TEXT = "Go back (recommended)"  # badcert.html
_BADCERT_TITLE_LENGTH = len("Certificate error")
_BUILTIN_PAGE_MAX_ELEMENTS = 12

# Process groups of running servoshells, so terminate_all() can reach them from the
# CLI's exit and signal handlers. Mutated under the lock; terminate_all reads it
# without the lock because it may run in a signal handler on a thread holding it.
_live_groups: set[int] = set()
_live_groups_lock = threading.Lock()
_shutdown_requested = False
# Whether this process has checked servoshell's WebDriver exposure (once per command).
_exposure_checked = False


@dataclass(frozen=True)
class ServoTiming(config.VisitTiming):
    """Time limits for one page, in seconds: the visit timing both engines share, plus
    what only a servoshell process needs. The wall clock counts from process start."""

    ready_timeout: float = 30.0  # Process start until WebDriver reports ready.
    kill_grace: float = 5.0  # SIGTERM to SIGKILL.
    # Added to a WebDriver timeout to get the socket timeout, so Servo's own timeout
    # error normally arrives first.
    http_margin: float = 15.0


DEFAULT_TIMING = ServoTiming()


@dataclass(frozen=True)
class ServoLog:
    """What servoshell's log says about a page."""

    errors: tuple[PageError, ...]
    crash: CrashInfo | None
    # Panic locations servoshell's hook printed for panics Servo caught and survived
    # (resvg runs inside catch_unwind): reported, not a crash.
    caught_panics: tuple[str, ...] = ()


def run_page(
    binary: Path,
    url: str,
    out_dir: Path,
    *,
    user_agent: str,
    prefs: Mapping[str, bool | int | float | str],
    viewport: tuple[int, int],
    device_pixel_ratio: float,
    timing: ServoTiming = DEFAULT_TIMING,
) -> PageResult:
    """Load ``url`` in a new servoshell and return what happened.

    Writes ``servo.log`` (always) and ``servo.png`` (when a screenshot was taken)
    into ``out_dir``. The process group and the temporary profile are gone when
    this returns, whatever the outcome. Raises ``ValueError`` or
    ``FileNotFoundError`` for invalid arguments, ``RuntimeError`` after
    ``terminate_all()``, and ``OSError`` when ``servo.log`` could not be written
    in full.
    """
    _validate_page_url(url)
    _validate_binary(binary)
    _validate_prefs(prefs)

    started = time.monotonic()
    out_dir.mkdir(parents=True, exist_ok=True)
    log_path = out_dir / _LOG_NAME
    screenshot_path = out_dir / SCREENSHOT_NAME
    # A screenshot left by an earlier attempt must not pass for this one's.
    screenshot_path.unlink(missing_ok=True)

    capture = _Capture()
    profile_root = Path(tempfile.mkdtemp(prefix=_PROFILE_PREFIX))
    try:
        config_dir, userscripts_dir, tmp_dir = _prepare_profile(profile_root)
        port = _free_port()
        argv = _servoshell_argv(
            binary,
            port=port,
            config_dir=config_dir,
            userscripts_dir=userscripts_dir,
            user_agent=user_agent,
            prefs=prefs,
            viewport=viewport,
            device_pixel_ratio=device_pixel_ratio,
        )
        with _ServoProcess(
            argv, profile_root=profile_root, tmp_dir=tmp_dir, log_path=log_path, timing=timing
        ) as process:
            client = WebDriverClient(f"http://{_WEBDRIVER_HOST}:{port}")
            _drive(process, client, url, port, screenshot_path, timing, capture)
            _end_session(client, process, capture)
        exit_info = process.exit_info()
        log_error = process.log_error
    finally:
        _remove_profile(profile_root)

    if log_error is not None:
        # A verdict from an incomplete log could miss a panic or the page's errors.
        raise OSError(f"servo.log could not be written: {log_error}") from log_error

    log = parse_log(read_log(log_path))
    classified = _classify(capture, exit_info, log, timing)
    return PageResult(
        engine=Engine.SERVO,
        url=url,
        status=classified.status,
        duration_ms=round((time.monotonic() - started) * 1000),
        metrics=capture.metrics,
        screenshot=capture.screenshot,
        log=log_path,
        errors=list(log.errors),
        crash=classified.crash,
        navigation_timed_out=capture.navigation_timed_out,
        detail=classified.detail,
        hang_cause=classified.hang_cause,
    )


def webdriver_exposure(port: int) -> str | None:
    """The network address:port where a local WebDriver server on `port` also answers.

    None when it answers on loopback only, or the machine has no network address.
    Anyone who reaches a servoshell's WebDriver port before the harness creates its
    session can drive the engine, including reading local files through file: URLs.
    """
    address = _network_address()
    if address is None:
        return None
    try:
        with socket.create_connection((address, port), timeout=_EXPOSURE_CONNECT_TIMEOUT_S):
            return f"{address}:{port}"
    except OSError:
        return None


def check_webdriver_exposure(binary: Path, user_agent: str) -> str | None:
    """Start servoshell without a page and return where else its WebDriver port answers.

    For `sitecheck doctor`: the network address:port (see `webdriver_exposure`), or
    None when it answers on loopback only. Raises RuntimeError when servoshell does
    not become ready, with the end of its log (no page is loaded, so it holds no
    page data), and FileNotFoundError when the binary is missing.
    """
    _validate_binary(binary)
    timing = DEFAULT_TIMING
    profile_root = Path(tempfile.mkdtemp(prefix=_PROFILE_PREFIX))
    try:
        config_dir, userscripts_dir, tmp_dir = _prepare_profile(profile_root)
        port = _free_port()
        argv = _servoshell_argv(
            binary,
            port=port,
            config_dir=config_dir,
            userscripts_dir=userscripts_dir,
            user_agent=user_agent,
            prefs={},
            viewport=(config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT),
            device_pixel_ratio=config.DEVICE_PIXEL_RATIO,
        )
        log_path = profile_root / _LOG_NAME
        with _ServoProcess(
            argv, profile_root=profile_root, tmp_dir=tmp_dir, log_path=log_path, timing=timing
        ) as process:
            client = WebDriverClient(f"http://{_WEBDRIVER_HOST}:{port}")
            not_ready = _wait_until_ready(process, client, timing.ready_timeout)
            if not_ready is None:
                return webdriver_exposure(port)
        # close() has joined the output readers, so the log is complete; it goes with
        # the profile, so its end (a rejected flag, a panic) travels in the error.
        tail = _log_tail(log_path)
        raise RuntimeError(_join(not_ready, f"servo.log ends with: {tail}" if tail else None))
    finally:
        _remove_profile(profile_root)


def read_log(path: Path) -> str:
    """servo.log as text, with no newline translation: only "\\n" ends a line, so a
    carriage return a page logs cannot start a line of its own."""
    return path.read_bytes().decode("utf-8", errors="replace")


def terminate_all() -> None:
    """SIGKILL every running servoshell process group and refuse to start new ones.

    For the CLI's atexit and signal handlers: it neither waits nor takes locks, so
    it is safe to call from a signal handler. A ``run_page`` in progress then
    returns ``PageStatus.ERROR``.
    """
    global _shutdown_requested
    _shutdown_requested = True
    # tuple() copies the set in one step under the GIL.
    for group in tuple(_live_groups):
        with contextlib.suppress(OSError):
            os.killpg(group, signal.SIGKILL)


@functools.cache
def load_probe_source() -> str:
    """Return probe.js: a function body that ends in ``return result;``.

    WebDriver's execute/sync takes it as is. Playwright's ``page.evaluate`` needs a
    function expression, so reference.py evaluates ``"() => {\\n" + body + "\\n}"``.
    """
    return _PROBE_PATH.read_text(encoding="utf-8")


# --- servoshell log -----------------------------------------------------------------

# env_logger lines: "[2026-10-24T10:13:16Z ERROR servoshell::desktop::headless_window] msg".
# A record's further lines (a multi-line message) are indented.
_ERROR_LINE = re.compile(r"^\[\S+ ERROR (?P<target>[\w:]+)\] ?(?P<message>.*)$")
_LOG_RECORD = re.compile(r"^\[\S+ (?:ERROR|WARN|INFO|DEBUG|TRACE) [\w:]+\]")
# headless_window logs every console.error message, and every uncaught exception as
# "Error at <script url>:<line>:<column> <message>" (GlobalScope::report_an_error;
# the message has no "TypeError:" prefix). Muted cross-origin errors have an empty URL.
_CONSOLE_TARGET = "servoshell::desktop::headless_window"
_UNCAUGHT = re.compile(
    r"^Error at (?P<source>\S*):(?P<line>\d+):(?P<column>\d+)(?: (?P<message>.*))?$"
)
# script_runtime logs "Unhandled promise rejection: <JS source of the reason>", e.g.
# '(new Error("boom", "https://example.org/app.js", 4))'.
_REJECTION_TARGET = "script::runtime::script_runtime"
_REJECTION_PREFIX = "Unhandled promise rejection: "
_JS_STRING = r'"(?:[^"\\]|\\.)*"'
_ERROR_SOURCE = re.compile(
    rf"^\(new \w*Error\((?P<message>{_JS_STRING})"
    rf"(?:, (?P<source>{_JS_STRING}))?(?:, (?P<line>\d+))?\)\)$"
)
# servoshell's panic hook (ports/servoshell/panic_hook.rs) prints
# "<message> (thread <name>, at <file>:<line>)" to stderr, then a backtrace, then logs
# the message's first line as "[...] ERROR servoshell::panic_hook] <message>" unless
# Servo catches the panic (resvg rendering runs inside catch_unwind): the record is
# what makes it a crash.
_PANIC_HOOK_TARGET = "servoshell::panic_hook"
_HOOK_PANIC_LOCATION = re.compile(
    r"^(?P<message>.*) \(thread [^,]*, at (?P<file>\S+):(?P<line>\d+)\)$"
)
# Rust's default hook, for panics before servoshell installs its own:
# "thread '<name>' panicked at <file>:<line>:<column>:" followed by the message, and
# the pre-1.73 one-line form "thread '<name>' panicked at '<message>', <file>:<l>:<c>".
_STD_PANIC = re.compile(r"^thread '[^']*' panicked at (?P<location>\S+?:\d+:\d+):$")
_STD_PANIC_ONE_LINE = re.compile(
    r"^thread '[^']*' panicked at '(?P<message>.*)', (?P<location>\S+:\d+:\d+)$"
)
# The constellation's hard-fail exit (only with --hard-fail, which the runner does not
# pass). The crate is servo-constellation; its log target depends on the lib name.
_CONSTELLATION_TARGETS = ("servo_constellation::", "constellation::")
_PIPELINE_FAILED_PREFIX = "Pipeline failed"


def parse_log(text: str) -> ServoLog:
    """Extract JS errors and the first crash from a ``servo.log`` (see `read_log`).

    Only engine lines count: stdout lines (``stdout| `` prefix) are what pages
    printed. Lines split only at "\\n", as servo.log was written; other line
    breaks (U+2028, a carriage return) are page text inside a line. Unknown lines,
    such as GL driver noise ("UNSUPPORTED (log once): ..."), are ignored.
    """
    lines = [line for line in text.split("\n") if not line.startswith(_STDOUT_PREFIX_TEXT)]
    errors: list[PageError] = []
    for line in lines:
        if len(errors) >= _MAX_ERRORS:
            break
        error = _parse_error_line(line)
        if error is not None:
            errors.append(error)
    crash, caught_panics = _find_crash(lines, _outside_records(lines))
    return ServoLog(errors=tuple(errors), crash=crash, caught_panics=caught_panics)


def _outside_records(lines: list[str]) -> list[bool]:
    """For each line, whether it is raw stderr: neither a log record nor an indented
    further line of one. Page text reaches stderr only inside records (console
    messages), so only raw lines can be the panic hook's or Rust's own output."""
    raw: list[bool] = []
    in_record = False
    for line in lines:
        if _LOG_RECORD.match(line):
            in_record = True
            raw.append(False)
        elif in_record and line[:1].isspace():
            raw.append(False)
        else:
            in_record = False
            raw.append(True)
    return raw


def _parse_error_line(line: str) -> PageError | None:
    match = _ERROR_LINE.match(line)
    if match is None:
        return None

    target, message = match["target"], match["message"]
    if target == _CONSOLE_TARGET:
        uncaught = _UNCAUGHT.match(message)
        if uncaught is None:
            # console.error(error) logs SpiderMonkey's source form of the Error.
            return _unwrapped_error("console", message)
        return PageError(
            kind="uncaught",
            message=_clip(uncaught["message"] or ""),
            source=uncaught["source"] or None,
            line=int(uncaught["line"]),
            column=int(uncaught["column"]),
        )

    if target == _REJECTION_TARGET and message.startswith(_REJECTION_PREFIX):
        return _unwrapped_error("rejection", message.removeprefix(_REJECTION_PREFIX))
    return None


def _unwrapped_error(kind: str, text: str) -> PageError:
    """Unwrap Servo's source form of an Error ('(new Error("x", "src", 4))'); keep
    anything else verbatim."""
    match = _ERROR_SOURCE.match(text)
    if match is None:
        return PageError(kind=kind, message=_clip(text))
    return PageError(
        kind=kind,
        message=_clip(_js_string(match["message"])),
        source=_js_string(match["source"]) if match["source"] else None,
        line=int(match["line"]) if match["line"] else None,
    )


def _js_string(literal: str) -> str:
    # The escapes SpiderMonkey emits for these strings are JSON-compatible; fall back
    # to the raw characters for anything that is not.
    try:
        return json.loads(literal)
    except json.JSONDecodeError:
        return literal[1:-1]


def _find_crash(lines: list[str], raw: list[bool]) -> tuple[CrashInfo | None, tuple[str, ...]]:
    """The first panic that ended in a crash, and the locations of caught panics.

    The panic hook's location line counts only when its panic_hook ERROR record
    follows; without one, Servo caught the panic and carried on.
    """
    unconfirmed: list[str] = []  # Hook locations still waiting for their record.
    for index, line in enumerate(lines):
        if raw[index]:
            hook = _HOOK_PANIC_LOCATION.match(line)
            if hook is not None:
                unconfirmed.append(f"{hook['file']}:{hook['line']}")
                continue

            standard = _STD_PANIC.match(line)
            if standard is not None:
                message = _next_raw_line(lines, raw, index)
                crash = CrashInfo(message=_clip(message.strip()), location=standard["location"])
                return crash, tuple(unconfirmed)

            one_line = _STD_PANIC_ONE_LINE.match(line)
            if one_line is not None:
                crash = CrashInfo(message=_clip(one_line["message"]), location=one_line["location"])
                return crash, tuple(unconfirmed)
            continue

        logged = _ERROR_LINE.match(line)
        if logged is None:
            continue
        if logged["target"] == _PANIC_HOOK_TARGET:
            # The record carries the message's first line, the better summary of a
            # multi-line message; the location is the hook line just before it.
            location = unconfirmed.pop() if unconfirmed else None
            crash = CrashInfo(message=_clip(logged["message"].strip()), location=location)
            return crash, tuple(unconfirmed)
        if logged["target"].startswith(_CONSTELLATION_TARGETS) and logged["message"].startswith(
            _PIPELINE_FAILED_PREFIX
        ):
            return CrashInfo(message=_clip(logged["message"].strip())), tuple(unconfirmed)
    return None, tuple(unconfirmed)


def _next_raw_line(lines: list[str], raw: list[bool], index: int) -> str:
    for following in range(index + 1, len(lines)):
        if raw[following]:
            return lines[following]
    return ""


def _clip(message: str) -> str:
    return message[:MAX_ERROR_MESSAGE_CHARS]


def _log_tail(path: Path) -> str:
    """The last non-empty lines of a servo.log joined by " | ", for an error message;
    empty when there are none or the log cannot be read."""
    try:
        text = read_log(path)
    except OSError:
        return ""
    lines = [line for line in text.split("\n") if line.strip()]
    return " | ".join(lines[-_LOG_TAIL_LINES:])


# --- driving one page ---------------------------------------------------------------


class _Stage(enum.Enum):
    STARTUP = "startup"
    SESSION = "session"
    NAVIGATE = "navigate"
    SETTLE = "settle"
    SCROLL = "scroll"
    PROBE = "probe"
    SCREENSHOT = "screenshot"
    DONE = "done"


_STAGES_BEFORE_PAGE = frozenset({_Stage.STARTUP, _Stage.SESSION})
_STAGES_AFTER_PROBE = frozenset({_Stage.SCREENSHOT, _Stage.DONE})


class _FailureKind(enum.Enum):
    UNRESPONSIVE = enum.auto()  # A command got no answer, or the page's script timed out.
    CONNECTION_LOST = enum.auto()  # Crash if the process exited, else unresponsive.
    HARNESS = enum.auto()  # Startup, session or probe failure; not evidence about the page.


# Hang causes, the first part of PageResult.hang_cause ("<cause>:<stage>").
_CAUSE_SCRIPT_TIMEOUT = "script-timeout"
_CAUSE_UNRESPONSIVE = "unresponsive"
_CAUSE_CONNECTION_LOST = "connection-lost"
_CAUSE_WALL_CLOCK = "wall-clock"


@dataclass(frozen=True)
class _Failure:
    kind: _FailureKind
    detail: str
    cause: str | None = None  # For a hang: what stopped answering.


@dataclass(frozen=True)
class _Classification:
    status: PageStatus
    crash: CrashInfo | None = None
    detail: str | None = None
    hang_cause: str | None = None


@dataclass
class _Capture:
    """What the runner got out of servoshell before stopping it."""

    stage: _Stage = _Stage.STARTUP
    navigation_timed_out: bool = False
    navigation_error: str | None = None
    metrics: dict[str, Any] | None = None
    screenshot: Path | None = None
    failure: _Failure | None = None
    notes: list[str] = field(default_factory=list)


def _drive(
    process: _ServoProcess,
    client: WebDriverClient,
    url: str,
    port: int,
    screenshot_path: Path,
    timing: ServoTiming,
    capture: _Capture,
) -> None:
    """Run the page sequence, recording the first failure in ``capture``."""
    try:
        _drive_steps(process, client, url, port, screenshot_path, timing, capture)
    except WebDriverConnectionError as error:
        # A process that is going away drops connections a moment before it exits.
        process.wait_for_exit(_EXIT_OBSERVATION_S)
        capture.failure = _Failure(
            _FailureKind.CONNECTION_LOST, f"{capture.stage.value}: {error}", _CAUSE_CONNECTION_LOST
        )
    except WebDriverTimeout as error:
        capture.failure = _Failure(
            _FailureKind.UNRESPONSIVE, f"{capture.stage.value}: {error}", _CAUSE_UNRESPONSIVE
        )
    except WebDriverError as error:
        if error.error == ERROR_SCRIPT_TIMEOUT:
            failure = _Failure(
                _FailureKind.UNRESPONSIVE, f"{capture.stage.value}: {error}", _CAUSE_SCRIPT_TIMEOUT
            )
        else:
            failure = _Failure(_FailureKind.HARNESS, f"{capture.stage.value}: {error}")
        capture.failure = failure


def _drive_steps(
    process: _ServoProcess,
    client: WebDriverClient,
    url: str,
    port: int,
    screenshot_path: Path,
    timing: ServoTiming,
    capture: _Capture,
) -> None:
    not_ready = _wait_until_ready(process, client, timing.ready_timeout)
    if not_ready is not None:
        capture.failure = _Failure(_FailureKind.HARNESS, f"{_Stage.STARTUP.value}: {not_ready}")
        return

    capture.stage = _Stage.SESSION
    try:
        client.new_session(_capabilities(timing), timeout=_SESSION_TIMEOUT_S)
    except WebDriverError as error:
        if error.error == ERROR_SESSION_NOT_CREATED:
            # servoshell allows one session; ours comes right after readiness.
            _LOGGER.warning(
                "servoshell refused the harness's WebDriver session: another client may "
                "have connected to its port first"
            )
        raise
    _warn_once_if_exposed(port)

    capture.stage = _Stage.NAVIGATE
    _navigate(process, client, url, timing, capture)

    capture.stage = _Stage.SETTLE
    process.sleep(timing.settle)

    capture.stage = _Stage.SCROLL
    script_call_timeout = timing.script_timeout + timing.http_margin
    for script in (SCROLL_TO_BOTTOM_SCRIPT, SCROLL_TO_TOP_SCRIPT):
        _run_scroll(client, script, script_call_timeout, capture)
        process.sleep(timing.scroll_pause)

    capture.stage = _Stage.PROBE
    metrics = client.execute(load_probe_source(), timeout=script_call_timeout)
    if not isinstance(metrics, dict):
        capture.failure = _Failure(
            _FailureKind.HARNESS, f"probe returned {type(metrics).__name__}, not an object"
        )
        return
    capture.metrics = metrics

    capture.stage = _Stage.SCREENSHOT
    try:
        png = client.screenshot(timeout=_screenshot_timeout(timing))
    except (WebDriverError, WebDriverTimeout) as error:
        # The probe answered, so the page is not hung: a page whose rendering stays
        # blocked (a stalled stylesheet or image) gets no screenshot from Servo. The
        # metrics stand on their own; the screenshot rules are skipped.
        capture.notes.append(f"screenshot failed: {error}")
        return
    screenshot_path.write_bytes(png)
    capture.screenshot = screenshot_path
    capture.stage = _Stage.DONE


def _screenshot_timeout(timing: ServoTiming) -> float:
    return timing.script_timeout + _SERVO_SCREENSHOT_WAIT_S + timing.http_margin


def _warn_once_if_exposed(port: int) -> None:
    global _exposure_checked
    if _exposure_checked:
        return
    _exposure_checked = True
    exposed = webdriver_exposure(port)
    if exposed is not None:
        _LOGGER.warning(
            "servoshell's WebDriver server also answers on the network at %s; until the "
            "downstream build binds it to 127.0.0.1, block incoming connections to this "
            "machine",
            exposed,
        )


def _network_address() -> str | None:
    """The address this machine uses on its network, or None without one."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(_ROUTE_PROBE_ADDRESS)  # Chooses a route; sends nothing.
        except OSError:
            return None
        address = probe.getsockname()[0]
    if address.startswith("127.") or address == "0.0.0.0":
        return None
    return address


def _wait_until_ready(
    process: _ServoProcess, client: WebDriverClient, timeout: float
) -> str | None:
    """Poll ``GET /status`` until ready; return why not, or None once ready."""
    deadline = time.monotonic() + timeout
    last_problem = "no answer"
    while time.monotonic() < deadline:
        if process.has_exited():
            return f"servoshell exited ({process.describe_exit()}) before WebDriver was ready"
        try:
            status = client.status(timeout=_STATUS_TIMEOUT_S)
        except (WebDriverConnectionError, WebDriverTimeout, WebDriverError) as error:
            last_problem = str(error)
        else:
            if status.get("ready") is True:
                return None
            last_problem = f"not ready: {status.get('message')!r}"
        if process.sleep(_READY_POLL_INTERVAL_S):
            return "stopped while waiting for WebDriver"
    return f"WebDriver not ready after {timeout:g} s (last: {last_problem})"


def _capabilities(timing: ServoTiming) -> dict[str, Any]:
    return {
        "pageLoadStrategy": "normal",
        "timeouts": {
            "pageLoad": round(timing.page_load_timeout * 1000),
            "script": round(timing.script_timeout * 1000),
        },
        # Chromium under Playwright dismisses dialogs nobody handles; do the same so
        # an alert() does not block the probe in one engine only.
        "unhandledPromptBehavior": "dismiss",
    }


def _navigate(
    process: _ServoProcess,
    client: WebDriverClient,
    url: str,
    timing: ServoTiming,
    capture: _Capture,
) -> None:
    """Load the page. A load that never completes is recorded and probing continues:
    many pages work but never fire their load event.

    servoshell can answer the navigate command when the initial about:blank finishes
    loading, before the requested page even started (seen on cold starts), so the
    document is polled until it is no longer about:blank and complete, within the
    page load timeout.
    """
    started = time.monotonic()
    _wait_for_document(
        process, client, timing, started + _INITIAL_DOCUMENT_TIMEOUT_S, require_page=False
    )
    try:
        client.navigate(url, timeout=timing.page_load_timeout + timing.http_margin)
    except WebDriverTimeout as error:
        capture.navigation_timed_out = True
        capture.notes.append(f"navigate: {error}")
        return
    except WebDriverError as error:
        if error.error == ERROR_TIMEOUT:
            capture.navigation_timed_out = True
            return
        capture.navigation_error = f"navigate: {error}"
        return
    deadline = started + timing.page_load_timeout
    if not _wait_for_document(process, client, timing, deadline, require_page=True):
        capture.navigation_timed_out = True


def _wait_for_document(
    process: _ServoProcess,
    client: WebDriverClient,
    timing: ServoTiming,
    deadline: float,
    *,
    require_page: bool,
) -> bool:
    """Poll until the document is complete (and, with `require_page`, no longer the
    initial about:blank); return whether it got there before `deadline`.

    A script timeout or another script error while a document is being replaced
    only means "not yet"; a command that gets no answer at all propagates (hang).
    """
    while True:
        try:
            state = client.execute(
                _LOCATION_SCRIPT, timeout=timing.script_timeout + timing.http_margin
            )
        except WebDriverError:
            state = None
        if isinstance(state, list) and len(state) == 2:
            href, ready_state = state
            is_page = href != _INITIAL_URL or not require_page
            if is_page and ready_state == _READY_STATE_COMPLETE:
                return True
        if time.monotonic() >= deadline or process.sleep(_DOCUMENT_POLL_INTERVAL_S):
            return False


def _run_scroll(client: WebDriverClient, script: str, timeout: float, capture: _Capture) -> None:
    try:
        client.execute(script, timeout=timeout)
    except WebDriverError as error:
        # A page whose script thread is stuck is a hang (handled by _drive); any
        # other scroll failure only costs lazy-loaded content.
        if error.error == ERROR_SCRIPT_TIMEOUT:
            raise
        capture.notes.append(f"scroll failed: {error}")


def _end_session(client: WebDriverClient, process: _ServoProcess, capture: _Capture) -> None:
    if client.session_id is None or capture.failure is not None or process.stop_requested:
        return
    try:
        client.delete_session(timeout=_DELETE_SESSION_TIMEOUT_S)
    except (WebDriverError, WebDriverTimeout, WebDriverConnectionError) as error:
        # The process is killed next either way; this only affects the detail text.
        capture.notes.append(f"delete session failed: {error}")


def _classify(
    capture: _Capture, exit_info: _ExitInfo, log: ServoLog, timing: ServoTiming
) -> _Classification:
    """Turn everything observed into a status, worst evidence first."""
    stage = capture.stage.value
    notes = (
        "; ".join(
            [*capture.notes, *(f"Servo caught a panic at {where}" for where in log.caught_panics)]
        )
        or None
    )

    # terminate_all() kills the group from outside, which looks like a crash otherwise.
    if exit_info.harness_shutdown:
        return _Classification(PageStatus.ERROR, detail=f"harness shut down during {stage}")

    crash = _crash_evidence(capture, exit_info, log)
    before_page = capture.stage in _STAGES_BEFORE_PAGE
    if crash is not None and before_page:
        # Nothing of the page had loaded: a servoshell or setup problem that would hit
        # every page, so not evidence about this one. The crash is kept for diagnosis.
        detail = f"servoshell crashed during {stage}, before loading the page"
        return _Classification(PageStatus.ERROR, crash, _join(detail, notes))
    if crash is not None:
        return _Classification(PageStatus.CRASH, crash, _join(f"crashed during {stage}", notes))

    wall_clock = exit_info.stop_reason is _StopReason.WALL_CLOCK
    failure = capture.failure
    if before_page and (failure is not None or wall_clock):
        # No page was requested yet: servoshell itself did not answer.
        detail = f"servoshell did not answer during {stage}, before loading the page"
        if failure is not None:
            detail = _join(detail, failure.detail)
        return _Classification(PageStatus.ERROR, detail=_join(detail, notes))

    if wall_clock and capture.metrics is not None and capture.stage in _STAGES_AFTER_PROBE:
        # The probe answered, so the page is not hung: only the screenshot waited for a
        # render that never came, or the watchdog fired while the session was ending.
        if capture.stage is _Stage.SCREENSHOT:
            note = f"screenshot: wall clock of {timing.wall_clock:g} s reached"
        else:
            note = f"wall clock of {timing.wall_clock:g} s reached while ending the session"
        return _page_outcome(capture, _join(note, notes))
    if wall_clock:
        detail = f"wall clock of {timing.wall_clock:g} s exceeded during {stage}"
        return _Classification(
            PageStatus.HANG, detail=_join(detail, notes), hang_cause=f"{_CAUSE_WALL_CLOCK}:{stage}"
        )

    if failure is not None and failure.kind is _FailureKind.HARNESS:
        return _Classification(PageStatus.ERROR, detail=_join(failure.detail, notes))
    if failure is not None:
        # Unresponsive, or a dropped connection while the process stayed alive.
        cause = f"{failure.cause or _CAUSE_UNRESPONSIVE}:{stage}"
        return _Classification(
            PageStatus.HANG, detail=_join(failure.detail, notes), hang_cause=cause
        )

    if capture.navigation_error is not None:
        return _Classification(
            PageStatus.NAVIGATION_ERROR, detail=_join(capture.navigation_error, notes)
        )
    return _page_outcome(capture, notes)


def _page_outcome(capture: _Capture, notes: str | None) -> _Classification:
    """The status of a page that answered the probe."""
    if (capture.metrics or {}).get("url") == _INITIAL_URL:
        # The page never replaced the initial document: the probe measured nothing of it.
        detail = f"the probe ran on {_INITIAL_URL}: the page never loaded"
        return _Classification(PageStatus.ERROR, detail=_join(detail, notes))
    error_page = _servo_error_page(capture.metrics)
    if error_page is not None:
        return _Classification(PageStatus.NAVIGATION_ERROR, detail=_join(error_page, notes))
    return _Classification(PageStatus.OK, detail=notes)


def _crash_evidence(capture: _Capture, exit_info: _ExitInfo, log: ServoLog) -> CrashInfo | None:
    exit_code, signal_number = exit_info.unexpected_exit_status()
    if log.crash is not None:
        return CrashInfo(
            message=log.crash.message,
            location=log.crash.location,
            exit_code=exit_code,
            signal=signal_number,
        )

    if exit_info.exited_on_its_own:
        # An exit with a status during startup is a usage or setup problem (bad
        # flag, missing library), reported as a harness error by the startup step.
        if capture.stage is _Stage.STARTUP and signal_number is None:
            return None
        return CrashInfo(
            message=f"servoshell exited unexpectedly ({exit_info.description})",
            exit_code=exit_code,
            signal=signal_number,
        )

    if _shows_crash_page(capture.metrics):
        return CrashInfo(message="Servo showed its crash page, but the log has no panic")
    return None


def _servo_error_page(metrics: dict[str, Any] | None) -> str | None:
    text = _page_text(metrics)
    if text.startswith(_NETERROR_TEXT_PREFIX) and _is_builtin_page(metrics, _NETERROR_TITLE_LENGTH):
        return "Servo showed its network error page"
    if _BADCERT_TEXT in text and _is_builtin_page(metrics, _BADCERT_TITLE_LENGTH):
        return "Servo showed its certificate error page"
    return None


def _shows_crash_page(metrics: dict[str, Any] | None) -> bool:
    return _page_text(metrics).startswith(_CRASH_PAGE_TEXT_PREFIX) and _is_builtin_page(metrics, 0)


def _is_builtin_page(metrics: dict[str, Any] | None, title_length: int) -> bool:
    """Whether the page has the title and the handful of elements of a built-in page."""
    if metrics is None or metrics.get("titleLength") != title_length:
        return False
    elements = metrics.get("elementCount")
    return isinstance(elements, int) and elements <= _BUILTIN_PAGE_MAX_ELEMENTS


def _page_text(metrics: dict[str, Any] | None) -> str:
    text = metrics.get("text") if metrics is not None else None
    return text.lstrip() if isinstance(text, str) else ""


def _join(first: str, second: str | None) -> str:
    return f"{first}; {second}" if second else first


# --- servoshell process -------------------------------------------------------------


class _StopReason(enum.Enum):
    FINISHED = "finished"  # The runner is done with the page.
    WALL_CLOCK = "wall-clock"  # The watchdog fired.


@dataclass(frozen=True)
class _ExitInfo:
    returncode: int | None
    exited_on_its_own: bool  # Before the harness stopped it, so not our signal.
    stop_reason: _StopReason | None
    harness_shutdown: bool  # terminate_all() ran while the page was loading.

    @property
    def description(self) -> str:
        return _describe_returncode(self.returncode)

    def unexpected_exit_status(self) -> tuple[int | None, int | None]:
        """(exit code, signal) when the process ended by itself, else (None, None)."""
        if not self.exited_on_its_own or self.returncode is None:
            return None, None
        if self.returncode < 0:
            return None, -self.returncode
        return self.returncode, None


class _ServoProcess:
    """One servoshell process group, its log capture and its wall-clock watchdog.

    The group's leader is supervisor.py, which runs servoshell and forwards its exit
    status; it holds the read end of a pipe whose write end only this process holds,
    so the group dies with the harness even when nothing here gets to run (SIGKILL,
    a memory-pressure kill, a crash of the interpreter).

    The main thread drives the page; the watchdog thread may call ``stop`` at any
    time, which is why stopping is serialized by ``_stop_lock`` and observed through
    ``_stopped``.
    """

    def __init__(
        self,
        argv: list[str],
        *,
        profile_root: Path,
        tmp_dir: Path,
        log_path: Path,
        timing: ServoTiming,
    ) -> None:
        self._argv = argv
        self._profile_root = profile_root
        self._tmp_dir = tmp_dir
        self._log_path = log_path
        self._timing = timing
        self._proc: subprocess.Popen[bytes] | None = None
        self._log: _LogWriter | None = None
        self._readers: list[threading.Thread] = []
        self._watchdog: threading.Timer | None = None
        self._stop_lock = threading.Lock()
        self._stopped = threading.Event()
        self._stop_reason: _StopReason | None = None
        self._exited_on_its_own = False

    def __enter__(self) -> _ServoProcess:
        if _shutdown_requested:
            raise RuntimeError("terminate_all() was called; not starting servoshell")
        try:
            self._start()
        except BaseException:
            self.close()
            raise
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    @property
    def stop_requested(self) -> bool:
        return self._stopped.is_set()

    @property
    def log_error(self) -> OSError | None:
        """The error that kept servo.log from being written in full, or None."""
        return self._log.error if self._log is not None else None

    def has_exited(self) -> bool:
        assert self._proc is not None
        if self._proc.poll() is None:
            return False
        self._note_exit()
        return True

    def wait_for_exit(self, timeout: float) -> bool:
        assert self._proc is not None
        try:
            self._proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return False
        self._note_exit()
        return True

    def describe_exit(self) -> str:
        assert self._proc is not None
        return _describe_returncode(self._proc.returncode)

    def sleep(self, seconds: float) -> bool:
        """Sleep, waking early if the process is stopped; return whether it was."""
        return self._stopped.wait(seconds)

    def stop(self, reason: _StopReason) -> None:
        """Kill the process group (SIGTERM, then SIGKILL after the grace period). Idempotent."""
        with self._stop_lock:
            if self._stop_reason is not None or self._proc is None:
                return
            if self._proc.poll() is not None:
                self._exited_on_its_own = True
            self._stop_reason = reason
            self._stopped.set()
            _kill_group(self._proc, self._timing.kill_grace)
            with _live_groups_lock:
                _live_groups.discard(self._proc.pid)

    def close(self) -> None:
        if self._watchdog is not None:
            self._watchdog.cancel()
        self.stop(_StopReason.FINISHED)
        if self._proc is not None and self._proc.stdin is not None:
            # The supervisor's lifeline; the group is gone already.
            with contextlib.suppress(OSError):
                self._proc.stdin.close()
        for reader in self._readers:
            reader.join(_READER_JOIN_TIMEOUT_S)
            if reader.is_alive():
                # Only possible if something outside the group still holds the pipe.
                _LOGGER.warning("servoshell output reader did not finish; log may be incomplete")
        if self._log is not None:
            self._log.close()

    def exit_info(self) -> _ExitInfo:
        assert self._proc is not None
        return _ExitInfo(
            returncode=self._proc.returncode,
            exited_on_its_own=self._exited_on_its_own,
            stop_reason=self._stop_reason,
            harness_shutdown=_shutdown_requested,
        )

    def _start(self) -> None:
        self._log = _LogWriter(self._log_path)
        self._proc = subprocess.Popen(
            [
                sys.executable,
                *_SUPERVISOR_FLAGS,
                str(_SUPERVISOR_PATH),
                str(self._profile_root),
                *self._argv,
            ],
            cwd=self._profile_root,
            env=_child_env(self._tmp_dir),
            # Never written to: end of file tells the supervisor the harness is gone.
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            # A new session makes the supervisor the leader of a process group that
            # servoshell joins, so stop() can kill both and anything servoshell
            # spawned in one call.
            start_new_session=True,
        )
        with _live_groups_lock:
            _live_groups.add(self._proc.pid)

        assert self._proc.stdout is not None and self._proc.stderr is not None
        self._readers = [
            threading.Thread(
                target=_pump, args=(self._proc.stdout, self._log, _STDOUT_PREFIX), daemon=True
            ),
            threading.Thread(target=_pump, args=(self._proc.stderr, self._log, b""), daemon=True),
        ]
        for reader in self._readers:
            reader.start()

        self._watchdog = threading.Timer(
            self._timing.wall_clock, self.stop, args=(_StopReason.WALL_CLOCK,)
        )
        self._watchdog.daemon = True
        self._watchdog.start()

    def _note_exit(self) -> None:
        if self._stop_reason is None:
            self._exited_on_its_own = True


def _kill_group(proc: subprocess.Popen[bytes], grace: float) -> None:
    # A leader that already exited needs no SIGTERM; polling also reaps it.
    if proc.poll() is None:
        _signal_group(proc, signal.SIGTERM)
        with contextlib.suppress(subprocess.TimeoutExpired):
            proc.wait(timeout=grace)
    # Children can outlive the leader or ignore SIGTERM; kill whatever is left.
    _signal_group(proc, signal.SIGKILL)
    proc.wait()


def _signal_group(proc: subprocess.Popen[bytes], signal_number: int) -> None:
    group = proc.pid  # The leader's pid is the group id (start_new_session).
    try:
        os.killpg(group, signal_number)
    except ProcessLookupError:
        pass  # The whole group is already gone.
    except PermissionError:
        # macOS answers EPERM for a group whose only members are zombies, which is
        # what remains once the leader died and before it is reaped: nothing to kill.
        if proc.poll() is not None or _shutdown_requested:
            _LOGGER.debug("process group %d has only exited members left", group)
            return
        _LOGGER.warning("not allowed to signal process group %d", group)


def _describe_returncode(returncode: int | None) -> str:
    if returncode is None:
        return "still running"
    if returncode < 0:
        try:
            return f"signal {signal.Signals(-returncode).name}"
        except ValueError:
            return f"signal {-returncode}"
    return f"exit code {returncode}"


class _LogWriter:
    """Writes whole lines from servoshell's stdout and stderr into one capped file.

    A write that fails (a full disk) does not stop the readers: they go on reading
    and discarding servoshell's output, so it neither blocks on a full pipe nor dies
    of a closed one. The first error is kept in ``error`` for the caller to report,
    since the log then misses lines.
    """

    def __init__(self, path: Path) -> None:
        self._file = path.open("wb")
        self._lock = threading.Lock()
        self._size = 0
        self._full = False
        self._error: OSError | None = None

    @property
    def error(self) -> OSError | None:
        """The first write error, after which the log took no more lines; or None."""
        return self._error

    def write_line(self, prefix: bytes, line: bytes) -> None:
        with self._lock:
            if self._full or self._file.closed:
                return
            entry = prefix + line
            try:
                if self._size + len(entry) > _MAX_LOG_BYTES:
                    self._full = True
                    self._file.write(_LOG_TRUNCATED_LINE)
                    return
                self._file.write(entry)
                # Flushed per line so a stuck page's log can be followed while it runs.
                self._file.flush()
            except OSError as error:
                self._error = error  # The first one: being full, the log takes no more.
                self._full = True
                return
            self._size += len(entry)

    def close(self) -> None:
        with self._lock:
            if self._error is None:
                self._file.close()
                return
            # Closing flushes what the failed write left buffered, which may fail
            # again; the error is reported already.
            with contextlib.suppress(OSError):
                self._file.close()


def _pump(stream: IO[bytes], log: _LogWriter, prefix: bytes) -> None:
    """Copy ``stream`` into ``log`` line by line until servoshell closes it."""
    with stream:
        skipping = False  # Inside an over-long line whose start was already written.
        while chunk := stream.readline(_MAX_LINE_BYTES):
            ends_line = chunk.endswith(b"\n")
            if skipping:
                skipping = not ends_line
                continue
            if not ends_line and len(chunk) >= _MAX_LINE_BYTES:
                log.write_line(prefix, chunk + _LINE_TRUNCATED_SUFFIX)
                skipping = True
                continue
            log.write_line(prefix, chunk if ends_line else chunk + b"\n")


# --- setup --------------------------------------------------------------------------


@functools.cache
def app_page_script() -> str:
    """Combray's page script, built as native/src/animations.rs builds it."""
    template = _APP_PAGE_SCRIPT_TEMPLATE.read_text(encoding="utf-8")
    if template.count(_APP_PAGE_SCRIPT_PLACEHOLDER) != 1:
        raise config.ConfigError(
            f"{_APP_PAGE_SCRIPT_TEMPLATE} must hold {_APP_PAGE_SCRIPT_PLACEHOLDER} exactly once"
        )
    polyfill = _APP_PAGE_SCRIPT_POLYFILL.read_text(encoding="utf-8")
    return template.replace(_APP_PAGE_SCRIPT_PLACEHOLDER, polyfill, 1)


def _prepare_profile(profile_root: Path) -> tuple[Path, Path, Path]:
    """Create the empty config, userscripts and temporary directories; return them."""
    config_dir = profile_root / "config"
    config_dir.mkdir()
    # servoshell runs every file in this directory, so it holds only our scripts.
    userscripts_dir = profile_root / "userscripts"
    userscripts_dir.mkdir()
    shutil.copyfile(ERRORS_USERSCRIPT_PATH, userscripts_dir / ERRORS_USERSCRIPT_PATH.name)
    (userscripts_dir / APP_PAGE_SCRIPT_NAME).write_text(app_page_script(), encoding="utf-8")
    # Servo's --temporary-storage disk cache goes to $TMPDIR/cache.sqlite3 (it names
    # the file next to a temporary directory instead of inside it), so TMPDIR points
    # into the profile and the cache goes with it.
    tmp_dir = profile_root / _PROFILE_TMP_NAME
    tmp_dir.mkdir()
    return config_dir, userscripts_dir, tmp_dir


def _servoshell_argv(
    binary: Path,
    *,
    port: int,
    config_dir: Path,
    userscripts_dir: Path,
    user_agent: str,
    prefs: Mapping[str, bool | int | float | str],
    viewport: tuple[int, int],
    device_pixel_ratio: float,
) -> list[str]:
    """Build servoshell's command line (flags: ports/servoshell/prefs.rs)."""
    width, height = viewport
    size = f"{width}x{height}"
    argv = [
        str(binary),
        "--headless",
        f"--webdriver={port}",
        f"--window-size={size}",
        f"--screen-size={size}",
        f"--device-pixel-ratio={device_pixel_ratio:g}",
        f"--user-agent={user_agent}",
        f"--config-dir={config_dir}",
        "--temporary-storage",
        f"--userscripts={userscripts_dir}",
    ]
    for name, value in sorted(prefs.items()):
        argv += ["--pref", f"{name}={_pref_value(value)}"]
    argv.append(_INITIAL_URL)
    return argv


def _pref_value(value: bool | int | float | str) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _child_env(tmp_dir: Path) -> dict[str, str]:
    env = config.child_environment()
    env.update(_CHILD_ENV_OVERRIDES)
    env["TMPDIR"] = str(tmp_dir)
    return env


def _free_port() -> int:
    # servoshell binds the port on all interfaces, so it must be free on all of them.
    # Another process could still take it before servoshell binds it; startup then
    # fails and the page is reported as a harness error, not as a Servo failure.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("0.0.0.0", 0))
        return probe.getsockname()[1]


def _remove_profile(profile_root: Path) -> None:
    try:
        shutil.rmtree(profile_root)
    except OSError as error:
        # It may hold cookies from a visited page; say where, so it can be removed.
        _LOGGER.warning("could not remove temporary Servo profile %s: %s", profile_root, error)


def _validate_page_url(url: str) -> None:
    scheme = urllib.parse.urlsplit(url).scheme.lower()
    if scheme not in _PAGE_SCHEMES:
        raise ValueError(
            f"unsupported URL scheme {scheme!r}; expected one of {sorted(_PAGE_SCHEMES)}"
        )


def _validate_binary(binary: Path) -> None:
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise FileNotFoundError(f"servoshell binary is not an executable file: {binary}")


def _validate_prefs(prefs: Mapping[str, bool | int | float | str]) -> None:
    for name, value in prefs.items():
        if not _PREF_NAME.fullmatch(name):
            raise ValueError(f"invalid Servo preference name {name!r}")
        if not isinstance(value, (bool, int, float, str)):
            raise ValueError(f"preference {name} has unsupported value type {type(value).__name__}")
