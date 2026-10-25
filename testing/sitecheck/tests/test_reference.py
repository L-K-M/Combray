"""Tests for sitecheck.reference that need no browser: the reference cache and helpers,
and how the comparison's image loading treats a screenshot it must not decode."""

from __future__ import annotations

import asyncio
import dataclasses
import errno
import http.server
import importlib.util
import json
import os
import re
import struct
import tempfile
import threading
import unittest
import zlib
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from unittest import mock

from sitecheck import config, images, model, reference, servo
from sitecheck.model import Engine, PageError, PageResult, PageStatus

URL = "https://example.com/page"
UA = "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.6.0 Firefox/153.0"
PROBE = "return {readyState: document.readyState};"
NOW = datetime(2026, 10, 24, 12, 0, tzinfo=UTC)


class ReferenceCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = config.ensure_private_dir(self.root / "data")
        self.page_dir = self.root / "run" / "page"
        self.page_dir.mkdir(parents=True)
        self.png = self.page_dir / reference.SCREENSHOT_NAME
        self.png.write_bytes(b"\x89PNG fake screenshot")

    def result(self, status: PageStatus = PageStatus.OK) -> PageResult:
        return PageResult(
            engine=Engine.CHROMIUM,
            url=URL,
            status=status,
            duration_ms=1234,
            metrics={"text": "hello", "textLength": 5} if status is PageStatus.OK else None,
            screenshot=self.png if status is PageStatus.OK else None,
            errors=[PageError("uncaught", "boom", "https://example.com/a.js", 3, 7)],
            navigation_timed_out=True,
        )

    def store(self, result: PageResult | None = None, now: datetime = NOW) -> bool:
        return reference.store_cached_reference(
            self.data, result or self.result(), user_agent=UA, probe_source=PROBE, now=now
        )

    def load(self, **overrides: object) -> reference.CachedReference | None:
        arguments: dict[str, object] = {"user_agent": UA, "probe_source": PROBE, "now": NOW}
        arguments.update(overrides)
        out_dir = self.root / "next-run" / "page"
        return reference.load_cached_reference(self.data, URL, out_dir, **arguments)  # type: ignore[arg-type]

    def test_round_trip_copies_the_screenshot_into_the_page_directory(self) -> None:
        self.assertTrue(self.store())
        cached = self.load(now=NOW + timedelta(days=2))

        assert cached is not None
        self.assertEqual(cached.captured_at, NOW)
        self.assertEqual(cached.result.url, URL)
        self.assertEqual(cached.result.status, PageStatus.OK)
        self.assertEqual(cached.result.metrics, {"text": "hello", "textLength": 5})
        self.assertEqual(cached.result.errors, self.result().errors)
        self.assertTrue(cached.result.navigation_timed_out)
        self.assertEqual(
            cached.result.screenshot, self.root / "next-run" / "page" / "reference.png"
        )
        self.assertEqual(cached.result.screenshot.read_bytes(), self.png.read_bytes())

    def test_cache_directories_are_private(self) -> None:
        self.store()
        entry = config.reference_cache_dir(self.data, URL)
        for path in (entry.parent, entry):
            self.assertEqual(path.stat().st_mode & 0o777, 0o700)
        self.assertEqual((entry / "reference.json").stat().st_mode & 0o077, 0)

    def test_entries_expire(self) -> None:
        self.store()
        self.assertIsNone(self.load(now=NOW + reference.DEFAULT_CACHE_MAX_AGE))
        self.assertIsNone(self.load(now=NOW + timedelta(hours=1), max_age=timedelta(hours=1)))
        self.assertIsNotNone(self.load(now=NOW + timedelta(minutes=59), max_age=timedelta(hours=1)))

    def test_entries_from_the_future_are_ignored(self) -> None:
        self.store(now=NOW + timedelta(days=1))
        self.assertIsNone(self.load())

    def test_changed_settings_invalidate_entries(self) -> None:
        self.store()
        self.assertIsNone(self.load(user_agent=UA + " changed"))
        self.assertIsNone(self.load(probe_source=PROBE + "\n// changed"))
        self.assertIsNone(self.load(viewport=(360, 800)))
        self.assertIsNotNone(self.load())

    def test_browser_and_timing_changes_invalidate_entries(self) -> None:
        reference.store_cached_reference(
            self.data,
            self.result(),
            user_agent=UA,
            probe_source=PROBE,
            now=NOW,
            browser_version="chromium 1",
        )
        self.assertIsNotNone(self.load(browser_version="chromium 1"))
        self.assertIsNone(self.load(browser_version="chromium 2"))
        slower = dataclasses.replace(reference.DEFAULT_TIMING, settle=10.0)
        self.assertIsNone(self.load(browser_version="chromium 1", timing=slower))

    def test_refresh_policy_ignores_entries(self) -> None:
        self.store()
        self.assertIsNone(self.load(policy=reference.CachePolicy.REFRESH))

    def test_failed_results_are_not_stored(self) -> None:
        for status in (PageStatus.NAVIGATION_ERROR, PageStatus.HANG, PageStatus.ERROR):
            with self.subTest(status=status):
                self.assertFalse(self.store(self.result(status)))
        self.assertFalse((self.data / config.REFERENCE_CACHE_DIR_NAME).exists())

    def test_corrupt_entries_are_misses_and_get_pruned(self) -> None:
        self.store()
        record = config.reference_cache_dir(self.data, URL) / "reference.json"
        record.write_text("{not json", encoding="utf-8")

        self.assertIsNone(self.load())
        self.assertEqual(reference.prune_reference_cache(self.data, now=NOW), 1)
        self.assertFalse(record.parent.exists())

    def test_record_for_another_url_is_a_miss(self) -> None:
        self.store()
        record_path = config.reference_cache_dir(self.data, URL) / "reference.json"
        record = json.loads(record_path.read_text(encoding="utf-8"))
        record["url"] = "https://example.com/other"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        self.assertIsNone(self.load())

    def test_prune_keeps_fresh_entries_and_removes_stale_ones(self) -> None:
        self.store()
        self.assertEqual(reference.prune_reference_cache(self.data, now=NOW + timedelta(days=1)), 0)
        self.assertEqual(reference.prune_reference_cache(self.data, now=NOW + timedelta(days=3)), 1)
        self.assertEqual(reference.prune_reference_cache(self.data, now=NOW), 0)

    def test_prune_without_a_cache_directory(self) -> None:
        self.assertEqual(reference.prune_reference_cache(self.root / "missing"), 0)

    def test_an_interrupted_overwrite_leaves_no_entry(self) -> None:
        self.store()
        newer_png = self.page_dir / "newer.png"
        newer_png.write_bytes(b"\x89PNG newer screenshot")
        newer = dataclasses.replace(
            self.result(), metrics={"text": "newer", "textLength": 5}, screenshot=newer_png
        )
        replace_file = reference._replace_file

        def fail_on_the_record(path: Path, write: Any) -> None:
            if path.name == "reference.json":
                raise OSError(errno.ENOSPC, os.strerror(errno.ENOSPC))
            replace_file(path, write)

        with (
            mock.patch.object(reference, "_replace_file", fail_on_the_record),
            self.assertRaises(OSError),
        ):
            self.store(newer)

        # The new screenshot is in place, so the old record must not vouch for it.
        self.assertIsNone(self.load())
        self.assertEqual(reference.prune_reference_cache(self.data, now=NOW), 1)


def png_header_only(width: int, height: int) -> bytes:
    """A PNG that declares `width` x `height` RGB pixels and holds none."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", b"") + chunk(b"IEND", b"")


@unittest.skipUnless(importlib.util.find_spec("PIL"), "Pillow is not installed")
class ScreenshotLoadingTest(unittest.TestCase):
    def test_a_decompression_bomb_only_skips_the_image_rules(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as directory:
            readable = Path(directory) / "reference.png"
            Image.new("RGB", (412, 915), "white").save(readable)
            # Pillow refuses to decode anything over twice its MAX_IMAGE_PIXELS.
            bomb = Path(directory) / "servo.png"
            bomb.write_bytes(png_header_only(20000, 20000))

            evidence = images.compare_screenshots(bomb, readable)

        self.assertIsNone(evidence["servo"])
        self.assertIsNotNone(evidence["reference"])
        self.assertIsNone(evidence["similarity"])
        (error,) = evidence["errors"]
        self.assertTrue(error.startswith("servo: cannot read servo.png"), error)


class _RecordingLauncher:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def launch(self, **kwargs: Any) -> str:
        self.calls.append(kwargs)
        return "browser"

    async def new_context(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return _RecordingContext()


class _RecordingContext:
    async def add_init_script(self, **kwargs: Any) -> None:
        pass


class EngineParityTest(unittest.TestCase):
    """Chromium gets the same conditions as servoshell, and nothing more."""

    @unittest.skipUnless(importlib.util.find_spec("playwright"), "Playwright is not installed")
    def test_chromium_starts_without_the_hister_token(self) -> None:
        launcher = _RecordingLauncher()
        playwright = mock.Mock(chromium=launcher)
        secrets = {"HISTER_TOKEN": "not-a-real-token", "HISTER_URL": "https://h.example.net"}
        with mock.patch.dict(os.environ, secrets):
            asyncio.run(reference._launch(playwright))
        (call,) = launcher.calls
        self.assertNotIn("HISTER_TOKEN", call["env"])
        self.assertNotIn("HISTER_URL", call["env"])
        self.assertIn("PATH", call["env"])

    def test_the_context_emulates_no_touch_screen_at_the_shared_pixel_ratio(self) -> None:
        browser = _RecordingLauncher()
        asyncio.run(reference.ChromiumReference()._new_context(browser, UA))  # type: ignore[arg-type]
        (call,) = browser.calls
        # Servo's desktop build reports a fine pointer with hover; touch emulation
        # would make pages branch differently in the two engines.
        self.assertFalse(call["has_touch"])
        self.assertEqual(call["device_scale_factor"], config.DEVICE_PIXEL_RATIO)

    def test_both_engines_share_the_visit_timing(self) -> None:
        self.assertIs(reference.ReferenceTiming, config.VisitTiming)
        shared = {field.name for field in dataclasses.fields(config.VisitTiming)}
        servo_timing = servo.ServoTiming()
        for name in shared:
            with self.subTest(name=name):
                self.assertEqual(
                    getattr(servo_timing, name), getattr(config.DEFAULT_VISIT_TIMING, name)
                )

    def test_error_messages_are_clipped_alike_everywhere(self) -> None:
        capture = reference._Capture()
        capture._add(PageError("console", "x" * 5000))
        self.assertEqual(len(capture.errors[0].message), model.MAX_ERROR_MESSAGE_CHARS)
        userscript = servo.ERRORS_USERSCRIPT_PATH.read_text(encoding="utf-8")
        declared = re.search(r"MAX_MESSAGE_LENGTH = (\d+);", userscript)
        assert declared is not None
        self.assertEqual(int(declared.group(1)), model.MAX_ERROR_MESSAGE_CHARS)
        self.assertEqual(reference.ERRORS_USERSCRIPT, servo.ERRORS_USERSCRIPT_PATH)


class NavigationGuardTest(unittest.TestCase):
    def test_only_main_frame_navigations_the_policy_refuses_are_blocked(self) -> None:
        def refusal(url: str) -> str | None:
            return "sensitive" if "logout" in url else None

        blocked = reference._blocks_navigation
        self.assertTrue(blocked("https://a.example/logout", True, True, refusal))
        self.assertFalse(blocked("https://a.example/page", True, True, refusal))
        self.assertFalse(blocked("https://a.example/logout", True, False, refusal))  # An iframe.
        self.assertFalse(blocked("https://a.example/logout", False, True, refusal))  # A fetch.
        self.assertFalse(blocked("https://a.example/logout", True, True, None))


LIVE_CHROMIUM_ENV = "SITECHECK_LIVE_CHROMIUM"
FIXTURES = Path(__file__).resolve().parent / "fixtures"


@unittest.skipUnless(os.environ.get(LIVE_CHROMIUM_ENV), f"set {LIVE_CHROMIUM_ENV}=1 to run")
class LiveChromiumTests(unittest.TestCase):
    """Runs ChromiumReference against local fixtures and a local server (opt-in)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.chromium = reference.ChromiumReference()
        cls.chromium.__enter__()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.chromium.__exit__(None, None, None)

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.out_dir = Path(temporary.name)

    def run_url(self, url: str, **options: Any) -> PageResult:
        timing = dataclasses.replace(reference.DEFAULT_TIMING, settle=0.3, scroll_pause=0.1)
        options.setdefault("timing", timing)
        return self.chromium.run_page(
            url, self.out_dir, user_agent=UA, probe_source=servo.load_probe_source(), **options
        )

    def test_simple_fixture(self) -> None:
        result = self.run_url((FIXTURES / "simple.html").as_uri())
        self.assertEqual(result.status, PageStatus.OK, result.detail)
        self.assertEqual(result.metrics["innerWidth"], config.VIEWPORT_WIDTH)
        self.assertTrue(result.screenshot.read_bytes().startswith(b"\x89PNG"))

    def test_select_options_are_not_page_text(self) -> None:
        from tests.test_servo import assert_comparable_text

        result = self.run_url((FIXTURES / "select.html").as_uri())
        self.assertEqual(result.status, PageStatus.OK, result.detail)
        assert_comparable_text(self, result.metrics)

    def test_navigation_error(self) -> None:
        result = self.run_url("http://127.0.0.1:1/")
        self.assertEqual(result.status, PageStatus.NAVIGATION_ERROR)

    def test_a_busy_page_is_a_hang_and_the_browser_comes_back(self) -> None:
        timing = dataclasses.replace(
            reference.DEFAULT_TIMING, settle=0.5, scroll_pause=0.1, script_timeout=3
        )
        result = self.run_url((FIXTURES / "busy.html").as_uri(), timing=timing)
        self.assertEqual(result.status, PageStatus.HANG)

        self.chromium._discard_browser()
        after = self.run_url((FIXTURES / "simple.html").as_uri())
        self.assertEqual(after.status, PageStatus.OK, after.detail)

    def test_script_navigations_the_policy_refuses_are_blocked(self) -> None:
        requests: list[str] = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                requests.append(self.path)
                body = b"<p>start page with enough words to be a page</p>"
                if self.path == "/start":
                    body += (
                        b"<script>setTimeout(() => location.replace('/logout?t=1'), 50)</script>"
                    )
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_: object) -> None:
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_address[1]}"

        refused: list[str] = []

        def refusal(url: str) -> str | None:
            if "logout" not in url:
                return None
            refused.append(url)
            return "sensitive"

        self.run_url(f"{base}/start", navigation_refusal=refusal)

        self.assertNotIn("/logout?t=1", requests)
        self.assertEqual(refused, [f"{base}/logout?t=1"])  # The runner discards the page.


class HelperTest(unittest.TestCase):
    def test_probe_body_becomes_a_function_expression(self) -> None:
        self.assertEqual(
            reference._as_function("return 1; // trailing comment"),
            "() => {\nreturn 1; // trailing comment\n}",
        )

    def test_first_stack_frame(self) -> None:
        cases = [
            (
                "TypeError: x\n    at f (https://a.example/x.js:12:34)",
                ("https://a.example/x.js", 12, 34),
            ),
            ("Error\n    at https://a.example/y.js?v=1:3:9", ("https://a.example/y.js?v=1", 3, 9)),
            ("Error: no frames", (None, None, None)),
            (None, (None, None, None)),
        ]
        for stack, expected in cases:
            with self.subTest(stack=stack):
                self.assertEqual(reference._first_stack_frame(stack), expected)

    def test_console_locations_become_one_based(self) -> None:
        self.assertEqual(reference._one_based(0), 1)
        self.assertIsNone(reference._one_based(-1))
        self.assertIsNone(reference._one_based(None))
        self.assertIsNone(reference._one_based(True))


if __name__ == "__main__":
    unittest.main()
