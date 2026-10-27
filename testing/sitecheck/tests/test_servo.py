"""Tests for sitecheck.servo: servoshell log parsing, and run_page against a fake servoshell.

The fake (fixtures/fake_servoshell.py) speaks just enough WebDriver and prints the
log formats recorded from the real servoshell. No network and no real servoshell
are needed, except for LiveServoTests, which run only when SITECHECK_LIVE_SERVO
names a servoshell binary and load local file:// fixtures.
"""

from __future__ import annotations

import contextlib
import dataclasses
import errno
import json
import logging
import os
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import IO
from unittest import mock

from sitecheck import model, servo
from sitecheck.model import CrashInfo, Engine, PageError, PageResult, PageStatus

FIXTURES = Path(__file__).resolve().parent / "fixtures"
FAKE_SERVOSHELL = FIXTURES / "fake_servoshell.py"
LIVE_SERVO_ENV = "SITECHECK_LIVE_SERVO"

USER_AGENT = "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.6.0 Firefox/153.0"
PREFS = {"viewport_meta_enabled": True, "dom_intersection_observer_enabled": True}
VIEWPORT = (412, 915)
FAKE_URL = "https://fake.example/"

# Short enough for unit tests; the fake answers instantly.
FAST = servo.ServoTiming(
    ready_timeout=10,
    page_load_timeout=5,
    script_timeout=5,
    settle=0,
    scroll_pause=0,
    wall_clock=20,
    kill_grace=0.5,
    http_margin=2,
)

# servo.log from the real servoshell 0.6.0 (October 2026) loading fixtures/errors.html;
# only the file URL is shortened.
RECORDED_ERRORS_LOG = """\
stdout| sitecheck-console-error
[2026-10-24T10:27:09Z ERROR servoshell::desktop::headless_window] sitecheck-console-error
stdout| Error at file:///fixtures/errors.html:17:7 sitecheck-uncaught
[2026-10-24T10:27:09Z ERROR servoshell::desktop::headless_window] Error at file:///fixtures/errors.html:17:7 sitecheck-uncaught
[2026-10-24T10:27:09Z ERROR script::runtime::script_runtime] Unhandled promise rejection: (new Error("sitecheck-rejection", "file:///fixtures/errors.html", 10))
[2026-10-24T10:27:09Z ERROR servoshell::desktop::headless_window] Error at file:///fixtures/errors.html:16:32 sitecheck-late-error
stdout| Error at file:///fixtures/errors.html:16:32 sitecheck-late-error
UNSUPPORTED (log once): POSSIBLE ISSUE: unit 1 GLD_TEXTURE_INDEX_2D is unloadable and bound to sampler type (Float) - using zero texture because texture unloadable
"""

# stderr of the real servoshell after ServoTestUtils.panic() (pref
# dom_servo_helpers_enabled), backtrace shortened.
RECORDED_PANIC_LOG = """\
explicit panic from script (thread Script#1, at components/script/dom/testing/servotestutils.rs:77)
      trace_unsynchronized<servoshell::backtrace::{impl#0}::fmt::{closure_env#1}>
      std::panicking::panic_with_hook
      <std::sys::thread::unix::Thread>::new::thread_start
[2026-10-24T10:14:42Z ERROR servoshell::panic_hook] explicit panic from script
UNSUPPORTED (log once): POSSIBLE ISSUE: unit 1 GLD_TEXTURE_INDEX_2D is unloadable and bound to sampler type (Float) - using zero texture because texture unloadable
"""

ERRORS_FIXTURE_URL = "file:///fixtures/errors.html"
EXPECTED_RECORDED_ERRORS = (
    PageError(kind="console", message="sitecheck-console-error"),
    PageError(
        kind="uncaught", message="sitecheck-uncaught", source=ERRORS_FIXTURE_URL, line=17, column=7
    ),
    PageError(kind="rejection", message="sitecheck-rejection", source=ERRORS_FIXTURE_URL, line=10),
    PageError(
        kind="uncaught",
        message="sitecheck-late-error",
        source=ERRORS_FIXTURE_URL,
        line=16,
        column=32,
    ),
)

PROBE_METRICS = (
    "readyState",
    "url",
    "hasTitle",
    "titleLength",
    "text",
    "textLength",
    "elementCount",
    "linkCount",
    "imageCount",
    "imagesLoaded",
    "imagesBroken",
    "iframeCount",
    "formCount",
    "scriptCount",
    "styleSheetCount",
    "scrollWidth",
    "scrollHeight",
    "innerWidth",
    "innerHeight",
    "bodyRect",
    "errors",
)


def console_line(message: str) -> str:
    return f"[2026-10-24T10:27:09Z ERROR servoshell::desktop::headless_window] {message}"


class ParseLogTests(unittest.TestCase):
    def test_recorded_log_yields_each_error_once(self) -> None:
        log = servo.parse_log(RECORDED_ERRORS_LOG)

        self.assertEqual(log.errors, EXPECTED_RECORDED_ERRORS)
        self.assertIsNone(log.crash)

    def test_recorded_panic_gives_message_and_location(self) -> None:
        log = servo.parse_log(RECORDED_PANIC_LOG)

        self.assertEqual(
            log.crash,
            CrashInfo(
                message="explicit panic from script",
                location="components/script/dom/testing/servotestutils.rs:77",
            ),
        )
        self.assertEqual(log.errors, ())

    def test_multi_line_panic_uses_first_line_from_hook_log(self) -> None:
        text = "\n".join(
            [
                "assertion `left == right` failed",
                "  left: 1",
                " right: 2 (thread Script#1, at components/layout/flow.rs:10)",
                "      std::panicking::panic_with_hook",
                "[2026-10-24T10:14:42Z ERROR servoshell::panic_hook] assertion `left == right` failed",
            ]
        )

        crash = servo.parse_log(text).crash

        self.assertEqual(
            crash,
            CrashInfo(
                message="assertion `left == right` failed", location="components/layout/flow.rs:10"
            ),
        )

    def test_rust_default_panic_format(self) -> None:
        text = "\n".join(
            [
                "thread 'main' panicked at components/fake/lib.rs:12:5:",
                "called `Option::unwrap()` on a `None` value",
                "note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace",
            ]
        )

        crash = servo.parse_log(text).crash

        self.assertEqual(
            crash,
            CrashInfo(
                message="called `Option::unwrap()` on a `None` value",
                location="components/fake/lib.rs:12:5",
            ),
        )

    def test_rust_one_line_panic_format(self) -> None:
        crash = servo.parse_log(
            "thread 'Script' panicked at 'index out of bounds', components/a.rs:3:9"
        ).crash

        self.assertEqual(
            crash, CrashInfo(message="index out of bounds", location="components/a.rs:3:9")
        )

    def test_first_panic_wins(self) -> None:
        text = "\n".join(
            [
                "first (thread A, at components/first.rs:1)",
                "[2026-10-24T10:14:42Z ERROR servoshell::panic_hook] first",
                "second (thread B, at components/second.rs:2)",
                "[2026-10-24T10:14:42Z ERROR servoshell::panic_hook] second",
            ]
        )

        self.assertEqual(
            servo.parse_log(text).crash,
            CrashInfo(message="first", location="components/first.rs:1"),
        )

    def test_hard_fail_pipeline_failure_is_a_crash(self) -> None:
        text = "[2026-10-24T10:14:42Z ERROR servo_constellation::constellation] Pipeline failed in hard-fail mode.  Crashing!"

        crash = servo.parse_log(text).crash

        self.assertEqual(crash, CrashInfo(message="Pipeline failed in hard-fail mode.  Crashing!"))

    def test_page_output_cannot_fake_a_crash(self) -> None:
        text = "\n".join(
            [
                "stdout| thread 'main' panicked at forged.rs:1:1:",
                "stdout| forged (thread Script#1, at forged.rs:1)",
                console_line("Pipeline failed, says the page"),
                console_line("the server crashed"),
            ]
        )

        log = servo.parse_log(text)

        self.assertIsNone(log.crash)
        self.assertEqual([error.kind for error in log.errors], ["console", "console"])

    def test_muted_cross_origin_error_has_no_source(self) -> None:
        log = servo.parse_log(console_line("Error at :0:0 Script error."))

        self.assertEqual(
            log.errors,
            (PageError(kind="uncaught", message="Script error.", source=None, line=0, column=0),),
        )

    def test_script_url_with_port(self) -> None:
        log = servo.parse_log(
            console_line("Error at https://cdn.example:8443/app.js:1:2 x is not defined")
        )

        self.assertEqual(
            log.errors,
            (
                PageError(
                    kind="uncaught",
                    message="x is not defined",
                    source="https://cdn.example:8443/app.js",
                    line=1,
                    column=2,
                ),
            ),
        )

    def test_rejection_with_non_error_reason_is_kept_verbatim(self) -> None:
        text = "\n".join(
            [
                '[2026-10-24T10:27:09Z ERROR script::runtime::script_runtime] Unhandled promise rejection: "plain string"',
                "[2026-10-24T10:27:09Z ERROR script::runtime::script_runtime] Unhandled promise rejection: (void 0)",
            ]
        )

        log = servo.parse_log(text)

        self.assertEqual(
            log.errors,
            (
                PageError(kind="rejection", message='"plain string"'),
                PageError(kind="rejection", message="(void 0)"),
            ),
        )

    def test_rejection_unescapes_message(self) -> None:
        text = (
            "[2026-10-24T10:27:09Z ERROR script::runtime::script_runtime] Unhandled promise rejection: "
            '(new TypeError("say \\"hi\\"", "https://a.example/x.js", 7))'
        )

        (error,) = servo.parse_log(text).errors

        self.assertEqual(
            error,
            PageError(
                kind="rejection", message='say "hi"', source="https://a.example/x.js", line=7
            ),
        )

    def test_other_error_targets_are_ignored(self) -> None:
        log = servo.parse_log("[2026-10-24T10:27:09Z ERROR net::http_loader] Request failed")

        self.assertEqual(log, servo.ServoLog(errors=(), crash=None))

    def test_long_messages_and_error_count_are_capped(self) -> None:
        text = "\n".join(console_line("x" * 5000) for _ in range(500))

        log = servo.parse_log(text)

        self.assertEqual(len(log.errors), 200)
        self.assertEqual(len(log.errors[0].message), model.MAX_ERROR_MESSAGE_CHARS)

    def test_logged_error_objects_are_unwrapped(self) -> None:
        log = servo.parse_log(console_line('(new TypeError("x is null", "file:///a.html", 9))'))
        self.assertEqual(
            log.errors,
            (PageError(kind="console", message="x is null", source="file:///a.html", line=9),),
        )


class ForgedCrashTests(unittest.TestCase):
    """What a page prints must never pass for a panic: crash is the strongest verdict."""

    def assert_no_crash(self, text: str) -> None:
        log = servo.parse_log(text)
        self.assertIsNone(log.crash)

    def test_console_text_shaped_like_the_panic_hook(self) -> None:
        self.assert_no_crash(console_line("harmless message (thread main, at a.rs:1)"))

    def test_continuation_lines_of_a_console_record(self) -> None:
        self.assert_no_crash(console_line("x") + "\n     right: 2 (thread main, at a.rs:1)")

    def test_unicode_line_separators_in_page_text(self) -> None:
        for separator in ("\u2028", "\u2029", "\x85", "\x0b", "\x0c", "\r"):
            with self.subTest(separator=repr(separator)):
                self.assert_no_crash(
                    console_line(
                        f"harmless{separator}thread 'main' panicked at src/x.rs:1:2:"
                        f"{separator}fake panic"
                    )
                )
                self.assert_no_crash(
                    f"stdout| harmless{separator}thread 'main' panicked at src/x.rs:1:2:"
                )

    def test_the_log_file_is_read_without_newline_translation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "servo.log"
            path.write_bytes(b"stdout| harmless\rthread 'main' panicked at src/x.rs:1:2:\nboom\n")
            self.assertIsNone(servo.parse_log(servo.read_log(path)).crash)

    def test_a_panic_servo_catches_is_not_a_crash(self) -> None:
        # resvg panics inside catch_unwind; servoshell's hook prints the location but
        # logs no ERROR record, and the page goes on rendering.
        text = "\n".join(
            [
                "attempt to subtract with overflow (thread ImageCache#1, at tiny-skia-0.12.0/src/pipeline/mod.rs:123)",
                "      std::panicking::panic_with_hook",
                console_line("the page goes on"),
            ]
        )
        log = servo.parse_log(text)
        self.assertIsNone(log.crash)
        self.assertEqual(log.caught_panics, ("tiny-skia-0.12.0/src/pipeline/mod.rs:123",))


class ClassifyTests(unittest.TestCase):
    """_classify on captures the fake servoshell cannot easily produce."""

    def exit_info(self, stop_reason: object = None) -> object:
        return servo._ExitInfo(
            returncode=-15,
            exited_on_its_own=False,
            stop_reason=stop_reason or servo._StopReason.FINISHED,
            harness_shutdown=False,
        )

    def classify(self, capture: object, stop_reason: object = None) -> object:
        return servo._classify(
            capture, self.exit_info(stop_reason), servo.ServoLog((), None), servo.DEFAULT_TIMING
        )

    def test_no_answer_while_creating_the_session_is_a_harness_error(self) -> None:
        for kind in (servo._FailureKind.UNRESPONSIVE, servo._FailureKind.CONNECTION_LOST):
            with self.subTest(kind=kind):
                capture = servo._Capture(
                    stage=servo._Stage.SESSION,
                    failure=servo._Failure(kind, "session: no answer", "unresponsive"),
                )
                self.assertEqual(self.classify(capture).status, PageStatus.ERROR)
        capture = servo._Capture(stage=servo._Stage.SESSION)
        classified = self.classify(capture, servo._StopReason.WALL_CLOCK)
        self.assertEqual(classified.status, PageStatus.ERROR)

    def test_the_wall_clock_during_the_screenshot_of_a_probed_page_is_no_hang(self) -> None:
        capture = servo._Capture(stage=servo._Stage.SCREENSHOT, metrics={"text": "page"})
        classified = self.classify(capture, servo._StopReason.WALL_CLOCK)
        self.assertEqual(classified.status, PageStatus.OK)
        self.assertIn("screenshot", classified.detail)

    def test_the_wall_clock_while_ending_the_session_of_a_finished_page_is_no_hang(
        self,
    ) -> None:
        # Everything was captured; the watchdog fired before close() cancelled it.
        capture = servo._Capture(stage=servo._Stage.DONE, metrics={"text": "page"})
        classified = self.classify(capture, servo._StopReason.WALL_CLOCK)
        self.assertEqual(classified.status, PageStatus.OK)
        self.assertIsNone(classified.hang_cause)
        self.assertIn("ending the session", classified.detail)

    def test_a_probe_of_about_blank_is_not_the_page(self) -> None:
        capture = servo._Capture(
            stage=servo._Stage.DONE, metrics={"url": "about:blank", "text": "", "textLength": 0}
        )
        classified = self.classify(capture)
        self.assertEqual(classified.status, PageStatus.ERROR)
        self.assertIn("about:blank", classified.detail)

    def test_real_pages_that_read_like_servos_own_pages(self) -> None:
        for text in (
            "Servo crashed! What we learned from a week of fuzzing",
            "Could not load the requested page: how to debug it",
        ):
            with self.subTest(text=text):
                capture = servo._Capture(
                    stage=servo._Stage.DONE,
                    metrics={"text": text, "titleLength": 30, "elementCount": 400},
                )
                self.assertEqual(self.classify(capture).status, PageStatus.OK)

    def test_hang_causes(self) -> None:
        capture = servo._Capture(
            stage=servo._Stage.SCROLL,
            failure=servo._Failure(
                servo._FailureKind.UNRESPONSIVE, "scroll: script timeout", "script-timeout"
            ),
        )
        self.assertEqual(self.classify(capture).hang_cause, "script-timeout:scroll")
        capture = servo._Capture(stage=servo._Stage.NAVIGATE)
        classified = self.classify(capture, servo._StopReason.WALL_CLOCK)
        self.assertEqual(classified.hang_cause, "wall-clock:navigate")


class ProcessGroupTests(unittest.TestCase):
    def test_killing_an_already_exited_group_logs_no_warning(self) -> None:
        # The sleep lets `true` exit first. _kill_group's poll() then reaps the leader,
        # so killpg answers ESRCH (the group is gone) and nothing is logged.
        process = subprocess.Popen(["true"], start_new_session=True)
        time.sleep(0.3)
        with self.assertNoLogs(servo._LOGGER, level=logging.WARNING):
            servo._kill_group(process, 0.1)

    @unittest.skipUnless(
        sys.platform == "darwin", "only macOS answers killpg with EPERM for a zombie group"
    )
    def test_signalling_a_group_of_only_an_unreaped_zombie_logs_no_warning(self) -> None:
        process = subprocess.Popen(["true"], start_new_session=True)
        self.addCleanup(process.wait)
        deadline = time.monotonic() + 5
        while True:
            try:
                os.killpg(process.pid, 0)
            except PermissionError:
                break  # Exited but not yet reaped: a zombie group.
            self.assertLess(time.monotonic(), deadline, "the child never became a zombie")
            time.sleep(0.01)
        with self.assertNoLogs(servo._LOGGER, level=logging.WARNING):
            servo._signal_group(process, signal.SIGKILL)
        self.assertIsNotNone(process.returncode)

    def test_exposure_check_tells_loopback_from_all_interfaces(self) -> None:
        for host, exposed in (("127.0.0.1", False), ("0.0.0.0", True)):
            with self.subTest(host=host), socket.socket() as listener:
                listener.bind((host, 0))
                listener.listen()
                address = servo._network_address()
                if exposed and address is None:
                    self.skipTest("this machine has no network address")
                found = servo.webdriver_exposure(listener.getsockname()[1])
                self.assertEqual(found is not None, exposed)


class SupervisorTests(unittest.TestCase):
    def test_refuses_to_run_outside_a_group_of_its_own(self) -> None:
        # The supervisor ends by killing its whole process group, so as an ordinary
        # child it would kill its parent's group. Here the parent leads a new group,
        # which is all a supervisor without the check could kill.
        with tempfile.TemporaryDirectory() as directory:
            supervisor = [
                sys.executable,
                "-I",
                "-S",
                str(servo._SUPERVISOR_PATH),
                directory,
                "true",
            ]
            # The supervisor's stdin is at end of file at once: "sitecheck is gone".
            parent = (
                "import subprocess, sys; "
                f"sys.exit(subprocess.run({supervisor!r}, stdin=subprocess.DEVNULL).returncode)"
            )
            completed = subprocess.run(
                [sys.executable, "-I", "-c", parent],
                start_new_session=True,
                capture_output=True,
                text=True,
                timeout=10,
            )

        self.assertEqual(completed.returncode, 127, completed.stderr)
        self.assertIn("must be started as a process group leader", completed.stderr)


class _FailingFlushFile:
    """servo.log's file, failing one flush with ENOSPC after `flushes` successful ones,
    like a disk that filled up for a moment."""

    def __init__(self, file: IO[bytes], flushes: int) -> None:
        self._file = file
        self._flushes_left = flushes

    @property
    def closed(self) -> bool:
        return self._file.closed

    def write(self, data: bytes) -> int:
        return self._file.write(data)

    def flush(self) -> None:
        self._flushes_left -= 1
        if self._flushes_left == -1:
            raise OSError(errno.ENOSPC, os.strerror(errno.ENOSPC))
        self._file.flush()

    def close(self) -> None:
        self._file.close()


def log_writer_failing_after(flushes: int) -> type[servo._LogWriter]:
    class FailingLogWriter(servo._LogWriter):
        def __init__(self, path: Path) -> None:
            super().__init__(path)
            self._file = _FailingFlushFile(self._file, flushes)  # type: ignore[assignment]

    return FailingLogWriter


class LogCaptureTests(unittest.TestCase):
    def test_a_failed_log_write_keeps_draining_the_output_and_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "servo.log"
            writer = log_writer_failing_after(3)(log_path)
            # More output than a pipe holds: a reader that stopped would leave the child
            # blocked, and a closed pipe would kill it.
            child = subprocess.Popen(
                [sys.executable, "-I", "-c", "for n in range(20000): print('line', n)"],
                stdout=subprocess.PIPE,
            )
            self.addCleanup(child.wait)
            self.addCleanup(child.kill)
            assert child.stdout is not None

            servo._pump(child.stdout, writer, b"")
            writer.close()

            self.assertEqual(child.wait(timeout=10), 0)
            self.assertIsInstance(writer.error, OSError)
            self.assertEqual(writer.error.errno, errno.ENOSPC)
            written = log_path.read_text()
            self.assertIn("line 0\n", written)  # Lines from before the failure survive.
            # Lines after the failure are read and discarded, not written.
            self.assertNotIn("line 19999", written)


class PageScriptTests(unittest.TestCase):
    def test_probe_is_a_function_body_measuring_every_metric(self) -> None:
        source = servo.load_probe_source()

        self.assertTrue(source.rstrip().endswith("return result;"))
        for name in PROBE_METRICS:
            with self.subTest(metric=name):
                self.assertIn(f'measure("{name}"', source)

    def test_userscript_records_into_the_probed_global(self) -> None:
        source = servo.ERRORS_USERSCRIPT_PATH.read_text(encoding="utf-8")

        self.assertIn("window.__sitecheck_errors", source)
        self.assertIn("window.__sitecheck_errors", servo.load_probe_source())
        self.assertIn('addEventListener("error"', source)
        self.assertIn('addEventListener("unhandledrejection"', source)


    def test_app_page_script_embeds_the_polyfill_once(self) -> None:
        source_dir = Path(__file__).resolve().parents[3] / "native" / "src"
        template = (source_dir / "animations.js").read_text(encoding="utf-8")
        polyfill = (source_dir / "vendor" / "web-animations-next-lite.min.js").read_text(
            encoding="utf-8"
        )
        script = servo.app_page_script()

        self.assertNotIn("__WEB_ANIMATIONS__", script)
        self.assertEqual(script.count(polyfill), 1)
        self.assertEqual(script, template.replace("__WEB_ANIMATIONS__", polyfill))

    def test_error_capture_runs_before_the_app_page_script(self) -> None:
        self.assertLess(servo.ERRORS_USERSCRIPT_PATH.name, servo.APP_PAGE_SCRIPT_NAME)


class FakeServoshellTestCase(unittest.TestCase):
    """Runs run_page against fixtures/fake_servoshell.py in a chosen mode."""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.temp = Path(temp.name)
        self.out_dir = self.temp / "page"
        self.addCleanup(self._reset_shutdown_flag)

    def fake_binary(self, mode: str) -> Path:
        path = self.temp / f"servoshell-{mode}"
        command = [sys.executable, "-I", str(FAKE_SERVOSHELL), f"--mode={mode}"]
        path.write_text(
            "#!/bin/sh\nexec " + " ".join(shlex.quote(part) for part in command) + ' "$@"\n'
        )
        path.chmod(0o755)
        return path

    def run_fake(
        self, mode: str, timing: servo.ServoTiming = FAST
    ) -> tuple[PageResult, dict[str, list[str]]]:
        result = servo.run_page(
            self.fake_binary(mode),
            FAKE_URL,
            self.out_dir,
            user_agent=USER_AGENT,
            prefs=PREFS,
            viewport=VIEWPORT,
            device_pixel_ratio=1,
            timing=timing,
        )
        facts = self.fake_facts()
        self.assert_cleaned_up(facts)
        return result, facts

    def fake_facts(self) -> dict[str, list[str]]:
        """The fake's ``FAKE key=value`` reports from servo.log."""
        facts: dict[str, list[str]] = {}
        for line in (self.out_dir / "servo.log").read_text().splitlines():
            if not line.startswith("stdout| FAKE "):
                continue
            key, _, value = line.removeprefix("stdout| FAKE ").partition("=")
            facts.setdefault(key, []).append(value)
        return facts

    def wait_for_fact(self, key: str) -> None:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if (self.out_dir / "servo.log").exists() and key in self.fake_facts():
                return
            time.sleep(0.05)
        self.fail(f"the fake never reported {key}")

    def assert_cleaned_up(self, facts: dict[str, list[str]]) -> None:
        self.assertEqual(servo._live_groups, set())
        for pid in facts.get("pid", []) + facts.get("child_pid", []):
            self.assert_process_gone(int(pid))
        for config_dir in facts.get("config_dir", []):
            self.assertFalse(Path(config_dir).parent.exists(), "temporary profile left behind")

    def assert_process_gone(self, pid: int) -> None:
        # An orphaned grandchild is reaped by init/launchd, which can take a moment.
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.05)
        self.fail(f"process {pid} is still running")

    @staticmethod
    def _reset_shutdown_flag() -> None:
        servo._shutdown_requested = False


class RunPageTests(FakeServoshellTestCase):
    def test_normal_page(self) -> None:
        with mock.patch.dict(os.environ, {"HISTER_TOKEN": "not-a-real-token"}):
            result, facts = self.run_fake("normal")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertEqual(result.engine, Engine.SERVO)
        self.assertEqual(result.url, FAKE_URL)
        self.assertIsNone(result.detail)
        self.assertIsNone(result.crash)
        self.assertFalse(result.navigation_timed_out)
        self.assertEqual(result.metrics["innerWidth"], 412)
        self.assertEqual(result.screenshot, self.out_dir / "servo.png")
        self.assertTrue(result.screenshot.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertEqual(result.log, self.out_dir / "servo.log")
        self.assertGreater(result.duration_ms, 0)
        self.assertEqual(
            result.errors,
            [
                PageError(kind="console", message="fake-console-error"),
                PageError(
                    kind="uncaught",
                    message="fake-uncaught",
                    source="https://fake.example/app.js",
                    line=3,
                    column=7,
                ),
                PageError(
                    kind="rejection",
                    message="fake-rejection",
                    source="https://fake.example/app.js",
                    line=10,
                ),
            ],
        )
        # The stdout line that imitates a panic was stored but not believed.
        self.assertIn(
            "stdout| thread 'main' panicked at forged/by/page.rs:1:1:", result.log.read_text()
        )

        self.assertEqual(facts["navigate"], [FAKE_URL])
        self.assertEqual(len(facts["scroll"]), 2)
        self.assertEqual(facts["deleted"], [""])
        self.assertEqual(facts["userscripts"], ['["errors_userscript.js", "page_script.js"]'])
        self.assertEqual(facts["config_dir_entries"], ["[]"])
        self.assertEqual(facts["rust_backtrace"], ["1"])
        self.assertNotIn("HISTER_TOKEN", facts["env"][0])

    def test_the_engine_gets_a_temporary_directory_inside_its_profile(self) -> None:
        # Servo's --temporary-storage disk cache lands in $TMPDIR, outside the profile.
        _, facts = self.run_fake("normal")
        profile = Path(facts["config_dir"][0]).parent
        self.assertTrue(Path(facts["tmpdir"][0]).is_relative_to(profile), facts["tmpdir"])

    def test_the_probe_waits_until_the_page_replaced_about_blank(self) -> None:
        result, facts = self.run_fake("late-load")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertEqual(result.metrics["url"], FAKE_URL)
        self.assertEqual(facts["probed"], [FAKE_URL])

    def test_a_screenshot_without_answer_after_the_probe_is_no_hang(self) -> None:
        timing = dataclasses.replace(FAST, script_timeout=1, http_margin=0.5)
        with mock.patch.object(servo, "_SERVO_SCREENSHOT_WAIT_S", 0):
            result, _ = self.run_fake("slow-screenshot", timing)

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertIsNone(result.screenshot)
        self.assertIn("screenshot", result.detail)

    def test_the_wall_clock_during_a_screenshot_is_no_hang(self) -> None:
        timing = dataclasses.replace(FAST, wall_clock=3)
        result, _ = self.run_fake("stuck-screenshot", timing)

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertIsNotNone(result.metrics)

    def test_no_answer_to_the_session_request_is_a_harness_error(self) -> None:
        with mock.patch.object(servo, "_SESSION_TIMEOUT_S", 0.5):
            result, _ = self.run_fake("slow-session")

        self.assertEqual(result.status, PageStatus.ERROR, result.detail)

    def test_command_line(self) -> None:
        _, facts = self.run_fake("normal")

        argv = json.loads(facts["argv"][0])
        port = next(arg for arg in argv if arg.startswith("--webdriver="))
        self.assertTrue(port.removeprefix("--webdriver=").isdigit())
        config_dir = facts["config_dir"][0]
        userscripts_dir = str(Path(config_dir).parent / "userscripts")
        self.assertEqual(
            argv,
            [
                "--headless",
                port,
                "--window-size=412x915",
                "--screen-size=412x915",
                "--device-pixel-ratio=1",
                f"--user-agent={USER_AGENT}",
                f"--config-dir={config_dir}",
                "--temporary-storage",
                f"--userscripts={userscripts_dir}",
                "--pref",
                "dom_intersection_observer_enabled=true",
                "--pref",
                "viewport_meta_enabled=true",
                "about:blank",
            ],
        )
        self.assertNotIn(FAKE_URL, argv)
        self.assertEqual(
            json.loads(facts["capabilities"][0]),
            {
                "capabilities": {
                    "alwaysMatch": {
                        "pageLoadStrategy": "normal",
                        "timeouts": {"pageLoad": 5000, "script": 5000},
                        "unhandledPromptBehavior": "dismiss",
                    }
                }
            },
        )

    def test_waits_for_readiness(self) -> None:
        result, _ = self.run_fake("slow-start")

        self.assertEqual(result.status, PageStatus.OK, result.detail)

    def test_never_ready_is_a_harness_error(self) -> None:
        timing = dataclasses.replace(FAST, ready_timeout=1)

        result, _ = self.run_fake("never-ready", timing)

        self.assertEqual(result.status, PageStatus.ERROR)
        self.assertIn("not ready after 1 s", result.detail)
        self.assertIsNone(result.metrics)

    def test_startup_exit_is_a_harness_error(self) -> None:
        result, _ = self.run_fake("usage-error")

        self.assertEqual(result.status, PageStatus.ERROR)
        self.assertIsNone(result.crash)
        self.assertIn("exit code 2", result.detail)
        self.assertIn("error: unexpected argument", result.log.read_text())

    def test_crash_before_loading_the_page_is_a_harness_error(self) -> None:
        result, _ = self.run_fake("startup-panic")

        self.assertEqual(result.status, PageStatus.ERROR)
        self.assertIn("before loading the page", result.detail)
        self.assertEqual(
            result.crash,
            CrashInfo(
                message="Address already in use",
                location="components/webdriver_server/lib.rs:150",
                exit_code=101,
            ),
        )

    def test_navigation_timeout_still_probes(self) -> None:
        result, facts = self.run_fake("nav-timeout")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertTrue(result.navigation_timed_out)
        self.assertEqual(result.metrics["textLength"], 34)
        self.assertIsNotNone(result.screenshot)
        self.assertEqual(len(facts["scroll"]), 2)

    def test_navigation_error(self) -> None:
        result, _ = self.run_fake("nav-error")

        self.assertEqual(result.status, PageStatus.NAVIGATION_ERROR)
        self.assertIn("fake navigation failure", result.detail)
        self.assertIsNotNone(result.metrics)

    def test_servo_network_error_page(self) -> None:
        result, _ = self.run_fake("neterror-page")

        self.assertEqual(result.status, PageStatus.NAVIGATION_ERROR)
        self.assertIn("network error page", result.detail)

    def test_script_thread_panic_is_a_crash(self) -> None:
        result, _ = self.run_fake("script-panic")

        self.assertEqual(result.status, PageStatus.CRASH)
        self.assertEqual(
            result.crash,
            CrashInfo(
                message="explicit panic from script",
                location="components/script/dom/testing/servotestutils.rs:77",
            ),
        )
        self.assertIsNotNone(result.metrics)

    def test_panic_then_exit_is_a_crash_with_exit_code(self) -> None:
        result, _ = self.run_fake("std-panic-exit")

        self.assertEqual(result.status, PageStatus.CRASH)
        self.assertEqual(
            result.crash,
            CrashInfo(
                message="called `Option::unwrap()` on a `None` value",
                location="components/fake/lib.rs:12:5",
                exit_code=101,
            ),
        )
        self.assertIn("navigate", result.detail)

    def test_process_exit_is_a_crash(self) -> None:
        result, _ = self.run_fake("exit-code")

        self.assertEqual(result.status, PageStatus.CRASH)
        self.assertEqual(
            result.crash,
            CrashInfo(message="servoshell exited unexpectedly (exit code 3)", exit_code=3),
        )
        self.assertIsNone(result.metrics)

    def test_death_by_signal_is_a_crash(self) -> None:
        result, _ = self.run_fake("killed")

        self.assertEqual(result.status, PageStatus.CRASH)
        self.assertEqual(
            result.crash,
            CrashInfo(message="servoshell exited unexpectedly (signal SIGKILL)", signal=9),
        )

    def test_hang_kills_the_process_group(self) -> None:
        timing = dataclasses.replace(FAST, wall_clock=1.5)

        started = time.monotonic()
        result, facts = self.run_fake("hang", timing)
        elapsed = time.monotonic() - started

        self.assertEqual(result.status, PageStatus.HANG)
        self.assertIn("wall clock of 1.5 s exceeded during navigate", result.detail)
        self.assertIsNone(result.crash)
        self.assertEqual(len(facts["child_pid"]), 1)  # assert_cleaned_up checked it is gone.
        # Wall clock + SIGTERM grace + slack.
        self.assertLess(elapsed, timing.wall_clock + timing.kill_grace + 4)

    def test_script_timeout_is_a_hang(self) -> None:
        result, _ = self.run_fake("script-timeout")

        self.assertEqual(result.status, PageStatus.HANG)
        self.assertIn("probe", result.detail)
        self.assertIsNone(result.metrics)
        self.assertEqual(result.hang_cause, "script-timeout:probe")

    def test_missing_screenshot_keeps_metrics(self) -> None:
        result, _ = self.run_fake("no-screenshot")

        self.assertEqual(result.status, PageStatus.OK)
        self.assertIsNone(result.screenshot)
        self.assertIsNotNone(result.metrics)
        self.assertIn("screenshot failed", result.detail)

    def test_stale_screenshot_is_removed(self) -> None:
        self.out_dir.mkdir()
        (self.out_dir / "servo.png").write_bytes(b"old attempt")

        result, _ = self.run_fake("exit-code")

        self.assertIsNone(result.screenshot)
        self.assertFalse((self.out_dir / "servo.png").exists())

    def test_terminate_all_stops_a_running_page(self) -> None:
        outcome: list[PageResult] = []
        binary = self.fake_binary("hang")
        worker = threading.Thread(
            target=lambda: outcome.append(
                servo.run_page(
                    binary,
                    FAKE_URL,
                    self.out_dir,
                    user_agent=USER_AGENT,
                    prefs=PREFS,
                    viewport=VIEWPORT,
                    device_pixel_ratio=1,
                    timing=FAST,
                )
            )
        )
        worker.start()
        # If an assertion fails, still stop the fake before the temp dir goes away
        # (cleanups run last-in, first-out).
        self.addCleanup(worker.join, 10)
        self.addCleanup(servo.terminate_all)
        self.wait_for_fact("child_pid")

        servo.terminate_all()
        worker.join(10)

        self.assertFalse(worker.is_alive())
        (result,) = outcome
        self.assertEqual(result.status, PageStatus.ERROR)
        self.assertIn("harness shut down", result.detail)
        self.assert_cleaned_up(self.fake_facts())
        with self.assertRaises(RuntimeError):
            self.run_fake("normal")

    def test_a_log_that_could_not_be_written_fails_the_page(self) -> None:
        # A verdict from an incomplete log could miss a panic.
        with (
            mock.patch.object(servo, "_LogWriter", log_writer_failing_after(3)),
            self.assertRaises(OSError) as raised,
        ):
            self.run_fake("normal")

        self.assertIn("servo.log could not be written", str(raised.exception))
        self.assert_cleaned_up(self.fake_facts())


class ExposureCheckTests(FakeServoshellTestCase):
    def test_a_servoshell_that_never_gets_ready_reports_the_end_of_its_log(self) -> None:
        # The log lives in the temporary profile, which is gone when the error arrives.
        for mode, last_words in (
            ("usage-error", "error: unexpected argument"),
            ("startup-panic", "Address already in use"),
        ):
            with self.subTest(mode=mode):
                with self.assertRaises(RuntimeError) as raised:
                    servo.check_webdriver_exposure(self.fake_binary(mode), USER_AGENT)
                self.assertIn("before WebDriver was ready", str(raised.exception))
                self.assertIn(last_words, str(raised.exception))
        self.assertEqual(servo._live_groups, set())


class HarnessDeathTests(FakeServoshellTestCase):
    """servoshell must not outlive the harness, even when the harness is SIGKILLed."""

    def test_servoshell_dies_with_a_killed_harness(self) -> None:
        package_root = Path(servo.__file__).resolve().parent.parent
        script = (
            "import sys; from pathlib import Path; "
            f"sys.path.insert(0, {str(package_root)!r}); "
            "from sitecheck import servo; "
            f"servo.run_page(Path({str(self.fake_binary('hang'))!r}), {FAKE_URL!r}, "
            f"Path({str(self.out_dir)!r}), user_agent='UA', prefs={{}}, viewport=(412, 915), "
            "device_pixel_ratio=1, timing=servo.ServoTiming(wall_clock=300))"
        )
        harness = subprocess.Popen([sys.executable, "-I", "-c", script])
        self.addCleanup(self._kill_reported_pids)
        try:
            self.wait_for_fact("child_pid")
        finally:
            harness.kill()
            harness.wait()

        facts = self.fake_facts()
        for pid in facts["pid"] + facts["child_pid"]:
            self.assert_process_gone(int(pid))
        profile = Path(facts["config_dir"][0]).parent
        deadline = time.monotonic() + 5
        while profile.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse(profile.exists(), "temporary profile left behind")

    def _kill_reported_pids(self) -> None:
        if not (self.out_dir / "servo.log").exists():
            return
        facts = self.fake_facts()
        for pid in facts.get("pid", []) + facts.get("child_pid", []):
            with contextlib.suppress(ProcessLookupError):
                os.kill(int(pid), signal.SIGKILL)


class ArgumentValidationTests(unittest.TestCase):
    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.out_dir = Path(temp.name)
        self.binary = Path(sys.executable)

    def run_page(self, **overrides: object) -> PageResult:
        arguments: dict[str, object] = {
            "user_agent": USER_AGENT,
            "prefs": PREFS,
            "viewport": VIEWPORT,
            "device_pixel_ratio": 1,
        }
        binary = overrides.pop("binary", self.binary)
        url = overrides.pop("url", FAKE_URL)
        arguments.update(overrides)
        return servo.run_page(binary, url, self.out_dir, **arguments)  # type: ignore[arg-type]

    def test_rejects_non_web_schemes(self) -> None:
        for url in ("javascript:alert(1)", "data:text/html,x", "about:blank", "ftp://example.org/"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.run_page(url=url)

    def test_rejects_missing_binary(self) -> None:
        with self.assertRaises(FileNotFoundError):
            self.run_page(binary=self.out_dir / "no-such-servoshell")

    def test_rejects_bad_preference_names(self) -> None:
        with self.assertRaises(ValueError):
            self.run_page(prefs={"a=b": True})


@unittest.skipUnless(os.environ.get(LIVE_SERVO_ENV), f"set {LIVE_SERVO_ENV} to a servoshell binary")
class LiveServoTests(unittest.TestCase):
    """Loads local fixtures in a real servoshell; checks the log formats still parse."""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.out_dir = Path(temp.name)

    def run_fixture(self, name: str, extra_prefs: dict[str, bool] | None = None) -> PageResult:
        return self.run_url((FIXTURES / name).as_uri(), extra_prefs)

    def run_url(self, url: str, extra_prefs: dict[str, bool] | None = None) -> PageResult:
        return servo.run_page(
            Path(os.environ[LIVE_SERVO_ENV]),
            url,
            self.out_dir,
            user_agent=USER_AGENT,
            prefs={**PREFS, **(extra_prefs or {})},
            viewport=VIEWPORT,
            device_pixel_ratio=1,
            timing=servo.ServoTiming(settle=0.5, scroll_pause=0.2),
        )

    def test_errors_fixture(self) -> None:
        result = self.run_fixture("errors.html")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        source = (FIXTURES / "errors.html").as_uri()
        self.assertEqual(
            sorted((error.kind, error.message) for error in result.errors),
            [
                ("console", "sitecheck-console-error"),
                ("rejection", "sitecheck-rejection"),
                ("uncaught", "sitecheck-late-error"),
                ("uncaught", "sitecheck-uncaught"),
            ],
        )
        self.assertTrue(all(error.source in (None, source) for error in result.errors))
        self.assertEqual(result.metrics["imagesBroken"], 1)
        self.assertEqual(result.metrics["probeErrors"], [])
        # The userscript ran: it saw at least the errors thrown after <head>.
        self.assertIn(
            "sitecheck-late-error", [entry["message"] for entry in result.metrics["errors"]]
        )

    def test_simple_fixture(self) -> None:
        result = self.run_fixture("simple.html")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertEqual(result.errors, [])
        metrics = result.metrics
        self.assertEqual((metrics["innerWidth"], metrics["innerHeight"]), VIEWPORT)
        self.assertEqual(metrics["scrollWidth"], 412)
        self.assertGreater(metrics["scrollHeight"], 2000)
        self.assertEqual(
            (metrics["linkCount"], metrics["formCount"], metrics["iframeCount"]), (2, 1, 1)
        )
        self.assertEqual(metrics["probeErrors"], [])
        self.assertTrue(result.screenshot.read_bytes().startswith(b"\x89PNG"))

    def test_script_thread_panic(self) -> None:
        result = self.run_fixture("panic.html", {"dom_servo_helpers_enabled": True})

        self.assertEqual(result.status, PageStatus.CRASH)
        self.assertEqual(
            result.crash,
            CrashInfo(
                message="explicit panic from script",
                location="components/script/dom/testing/servotestutils.rs:77",
            ),
        )

    def test_unanswered_dialog_does_not_block(self) -> None:
        result = self.run_fixture("dialog.html")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertIn("After the dialog.", result.metrics["text"])

    def test_network_error_page(self) -> None:
        # Port 1 is on the fetch spec's bad-port list, so nothing is contacted.
        result = self.run_url("http://127.0.0.1:1/")

        self.assertEqual(result.status, PageStatus.NAVIGATION_ERROR)
        self.assertIn("network error page", result.detail)

    def test_select_options_are_not_page_text(self) -> None:
        result = self.run_fixture("select.html")

        self.assertEqual(result.status, PageStatus.OK, result.detail)
        assert_comparable_text(self, result.metrics)


def assert_comparable_text(test: unittest.TestCase, metrics: dict[str, object]) -> None:
    """The probe's text of fixtures/select.html: visible words only, no form controls."""
    text = str(metrics["text"])
    for visible in ("Choose a country", "Visible paragraph text stays.", "Closing words"):
        test.assertIn(visible, text)
    for hidden in ("Optionalpha", "Datalistword", "Textareaword", "Hiddenword", "Hiddenoption"):
        test.assertNotIn(hidden, text)
    test.assertEqual(metrics["textLength"], len(text))
    test.assertIsInstance(metrics["innerTextLength"], int)


if __name__ == "__main__":
    unittest.main()
