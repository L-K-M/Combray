"""Tests for sitecheck.runner with fake engines: no servoshell, no browser, no network."""

from __future__ import annotations

import gzip
import io
import json
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest import mock

from sitecheck import compare, config, reference, runner, store
from sitecheck.model import Engine, Finding, PageResult, PageStatus, Verdict, VerdictKind
from tests import fakes

URL = "https://news.example-site.org/article"
OTHER_URL = "https://shop.example-store.net/"


def verdict(kind: VerdictKind, signature: str | None = None) -> Verdict:
    severity = compare.SEVERITY[kind]
    return Verdict(kind, severity, (Finding(kind, severity, "detail", signature),))


class FinalVerdictTest(unittest.TestCase):
    def test_a_failure_then_ok_is_flaky_and_keeps_the_failure_signature(self) -> None:
        final = runner.final_verdict(
            [verdict(VerdictKind.CRASH, "crash:a.rs:1"), verdict(VerdictKind.OK)]
        )
        self.assertIs(final.kind, VerdictKind.FLAKY)
        self.assertEqual(final.severity, 1)
        self.assertEqual(final.signatures, ["flaky:crash:a.rs:1"])
        self.assertIn("crash, ok", final.findings[0].detail)

    def test_ok_then_failure_is_flaky_too(self) -> None:
        final = runner.final_verdict([verdict(VerdictKind.OK), verdict(VerdictKind.JS_ERROR)])
        self.assertIs(final.kind, VerdictKind.FLAKY)
        self.assertEqual(final.signatures, ["flaky"])

    def test_a_repeated_failure_keeps_the_most_severe_and_every_signature(self) -> None:
        first = verdict(VerdictKind.HANG, "hang")
        second = verdict(VerdictKind.CRASH, "crash:b.rs:2")
        final = runner.final_verdict([first, second])
        self.assertEqual((final.kind, final.severity), (VerdictKind.CRASH, 5))
        self.assertEqual(final.signatures, ["crash:b.rs:2", "hang"])

    def test_a_crash_is_not_replaced_by_a_milder_failure_on_the_retry(self) -> None:
        crash = verdict(VerdictKind.CRASH, "crash:components/x.rs:12")
        final = runner.final_verdict([crash, verdict(VerdictKind.JS_ERROR, "js:foo")])
        self.assertEqual(final.kind, VerdictKind.CRASH)
        self.assertEqual(final.signatures, ["crash:components/x.rs:12", "js:foo"])

        final = runner.final_verdict(
            [verdict(VerdictKind.HANG, "hang"), verdict(VerdictKind.VISUAL_DIFF, "visual-diff")]
        )
        self.assertEqual(
            (final.kind, final.signatures), (VerdictKind.HANG, ["hang", "visual-diff"])
        )

    def test_a_signature_seen_in_several_attempts_counts_once(self) -> None:
        js = verdict(VerdictKind.JS_ERROR, "js:foo")
        final = runner.final_verdict([js, verdict(VerdictKind.JS_ERROR, "js:foo")])
        self.assertEqual(final.signatures, ["js:foo"])

    def test_a_missing_baseline_does_not_hide_an_earlier_crash(self) -> None:
        crash = verdict(VerdictKind.CRASH, "crash:a.rs:1")
        final = runner.final_verdict([crash, verdict(VerdictKind.REFERENCE_FAILED)])
        self.assertIs(final, crash)

    def test_harness_errors_are_not_evidence(self) -> None:
        ok = verdict(VerdictKind.OK)
        self.assertIs(runner.final_verdict([verdict(VerdictKind.HARNESS_ERROR), ok]), ok)
        harness = verdict(VerdictKind.HARNESS_ERROR)
        self.assertIs(runner.final_verdict([verdict(VerdictKind.HARNESS_ERROR), harness]), harness)

    def test_no_attempts_is_a_programming_error(self) -> None:
        with self.assertRaises(ValueError):
            runner.final_verdict([])


class AttemptPolicyTest(unittest.TestCase):
    def test_factories(self) -> None:
        self.assertEqual(runner.AttemptPolicy.retrying(1), runner.AttemptPolicy(1, 2))
        self.assertEqual(runner.AttemptPolicy.retrying(0), runner.AttemptPolicy(1, 1))
        self.assertEqual(runner.AttemptPolicy.repeating(3), runner.AttemptPolicy(3, 3))

    def test_invalid_policies(self) -> None:
        for minimum, maximum in ((0, 1), (2, 1)):
            with self.subTest(minimum=minimum, maximum=maximum), self.assertRaises(ValueError):
                runner.AttemptPolicy(minimum, maximum)
        with self.assertRaises(ValueError):
            runner.AttemptPolicy.retrying(-1)


class RunnerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.data = config.ensure_private_dir(Path(temporary.name) / "data")
        self.store = store.open_store(config.state_db_path(self.data), clock=fakes.StepClock())
        self.addCleanup(self.store.close)
        self.servo = fakes.FakeServo()
        self.reference = fakes.FakeReference()
        self.progress = io.StringIO()

    def engines(self, *, with_reference: bool = True) -> runner.Engines:
        return runner.Engines(
            servo=self.servo,
            reference=self.reference if with_reference else None,
            compare_images=fakes.fake_images if with_reference else None,
        )

    def run_pages(self, *urls: str, **options: object) -> runner.RunSummary:
        run_id = self.store.create_run(label="test")
        targets = [runner.RunTarget(url, "new") for url in urls]
        engines = options.pop("engines", None) or self.engines()
        return runner.run(
            self.store, self.data, run_id, targets, engines, progress=self.progress, **options
        )


class RunTest(RunnerTestCase):
    def test_an_ok_page_is_recorded_with_its_artifacts_and_without_text(self) -> None:
        summary = self.run_pages(URL)

        self.assertEqual(summary.verdicts, {"ok": 1})
        results = self.store.results_for_run(summary.run_id)
        self.assertEqual(len(results), 1)
        result = results[0]
        page_dir = config.page_dir(summary.run_dir, URL)
        self.assertEqual(result["artifacts"], page_dir.relative_to(summary.run_dir).as_posix())
        self.assertEqual(result["verdict"], "ok")
        self.assertEqual(result["metrics"]["bucket"], "new")
        self.assertEqual(result["metrics"]["reference"]["status"], "ok")
        self.assertNotIn("text", result["metrics"]["servo"]["metrics"])
        self.assertNotIn("text", result["metrics"]["reference"]["metrics"])
        self.assertNotIn(fakes.WORDS, json.dumps(result))

        servo_record = json.loads((page_dir / runner.SERVO_RECORD_NAME).read_text())
        self.assertNotIn("text", servo_record["metrics"])
        self.assertTrue((page_dir / runner.REFERENCE_RECORD_NAME).is_file())
        text = gzip.decompress((page_dir / "servo.text.gz").read_bytes()).decode()
        self.assertEqual(text, fakes.WORDS)
        self.assertEqual((page_dir / "servo.text.gz").stat().st_mode & 0o777, 0o600)
        for directory in (page_dir, page_dir.parent, summary.run_dir):
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700, directory)

        run = self.store.get_run(summary.run_id)
        assert run is not None
        self.assertIsNotNone(run["finished"])

    def test_progress_names_the_host_but_never_the_url(self) -> None:
        self.run_pages(URL, "file:///tmp/fixture.html")
        lines = self.progress.getvalue().splitlines()
        self.assertEqual(lines, ["[1/2] ok news.example-site.org", "[2/2] ok (local file)"])
        self.assertNotIn("/article", self.progress.getvalue())

    def test_a_crash_that_does_not_repeat_is_flaky(self) -> None:
        self.servo.script[URL] = [fakes.CRASH_LOAD, fakes.OK_LOAD]
        summary = self.run_pages(URL)

        first, second = self.store.results_for_run(summary.run_id)
        self.assertEqual((first["attempt"], first["verdict"]), (1, "crash"))
        self.assertEqual(first["signatures"], ["crash:components/layout/flow.rs:42"])
        self.assertEqual(
            (second["attempt"], second["verdict"], second["severity"]), (2, "flaky", 1)
        )
        self.assertTrue(second["metrics"]["flaky"])
        self.assertEqual(second["metrics"]["attempt_verdict"], "ok")
        self.assertEqual(second["artifacts"].rsplit("/", 1)[-1], "attempt-2")
        self.assertEqual(
            [issue["signature"] for issue in self.store.issues()],
            ["flaky:crash:components/layout/flow.rs:42"],
        )
        # The reference is loaded once and reused by the retry.
        self.assertEqual(self.reference.calls, [URL])

    def test_a_repeated_failure_is_recorded_as_an_issue(self) -> None:
        self.servo.script[URL] = [fakes.JS_ERROR_LOAD]
        summary = self.run_pages(URL)

        outcome = self.store.outcomes_for_run(summary.run_id)[URL]
        self.assertEqual((outcome["attempt"], outcome["verdict"]), (2, "js-error"))
        issues = self.store.issues()
        self.assertEqual([issue["signature"] for issue in issues], ["js:fakeFn is not defined"])
        self.assertEqual(issues[0]["kind"], "js-error")
        self.assertEqual(issues[0]["url_count"], 1)

    def test_retries_follow_the_policy(self) -> None:
        self.servo.script[URL] = [fakes.HANG_LOAD]
        self.run_pages(URL, policy=runner.AttemptPolicy.retrying(0))
        self.assertEqual(len(self.servo.calls), 1)

    def test_a_page_without_a_usable_reference_is_not_retried(self) -> None:
        self.reference.failing = frozenset({URL})
        self.servo.script[URL] = [fakes.JS_ERROR_LOAD]
        summary = self.run_pages(URL)

        self.assertEqual(summary.verdicts, {"reference-failed": 1})
        self.assertEqual(len(self.servo.calls), 1)
        self.assertEqual(self.store.issues(), [])

    def test_without_a_reference_crashes_still_count(self) -> None:
        self.servo.script[URL] = [fakes.CRASH_LOAD]
        summary = self.run_pages(URL, OTHER_URL, engines=self.engines(with_reference=False))
        outcomes = self.store.outcomes_for_run(summary.run_id)
        self.assertEqual(outcomes[URL]["verdict"], "crash")
        self.assertEqual(outcomes[OTHER_URL]["verdict"], "not-compared")
        self.assertIsNone(outcomes[OTHER_URL]["metrics"]["reference"])

    def test_the_time_budget_stops_starting_pages(self) -> None:
        ticks = iter([0.0, 10.0, 61.0])
        summary = self.run_pages(
            URL,
            OTHER_URL,
            "https://third.example-site.org/",
            time_budget=timedelta(minutes=1),
            monotonic=lambda: next(ticks),
        )
        self.assertEqual((summary.planned, summary.checked, summary.skipped), (3, 1, 2))
        self.assertIn("2 planned pages not started", self.progress.getvalue())
        run = self.store.get_run(summary.run_id)
        assert run is not None
        self.assertIsNotNone(run["finished"])

    def test_an_unexpected_exception_is_a_harness_error_and_the_run_goes_on(self) -> None:
        self.servo.script[URL] = [OSError("disk on fire")]
        summary = self.run_pages(URL, OTHER_URL)

        outcomes = self.store.outcomes_for_run(summary.run_id)
        self.assertEqual(outcomes[URL]["verdict"], "harness-error")
        self.assertIn("disk on fire", outcomes[URL]["metrics"]["servo"]["detail"])
        self.assertEqual(outcomes[OTHER_URL]["verdict"], "ok")
        self.assertIn("OSError", self.progress.getvalue())

    def test_an_exception_in_a_retry_keeps_the_earlier_crash(self) -> None:
        self.servo.script[URL] = [fakes.CRASH_LOAD, OSError("disk on fire")]
        summary = self.run_pages(URL)

        results = self.store.results_for_run(summary.run_id)
        self.assertEqual(
            [r["metrics"]["attempt_verdict"] for r in results], ["crash", "harness-error"]
        )
        self.assertEqual(results[-1]["verdict"], "crash")
        self.assertEqual(
            [issue["signature"] for issue in self.store.issues()],
            ["crash:components/layout/flow.rs:42"],
        )

    def test_harness_exceptions_keep_urls_out_of_the_progress_stream(self) -> None:
        self.servo.script[URL] = [ValueError(f"cannot load {URL}")]
        summary = self.run_pages(URL)

        self.assertNotIn("/article", self.progress.getvalue())
        self.assertIn("ValueError", self.progress.getvalue())
        page_dir = config.page_dir(summary.run_dir, URL)
        traceback_file = page_dir / runner.HARNESS_ERROR_NAME
        self.assertIn("cannot load", traceback_file.read_text())
        self.assertEqual(traceback_file.stat().st_mode & 0o777, 0o600)

    def test_missing_dependencies_are_not_page_problems(self) -> None:
        self.servo.script[URL] = [reference.ReferenceUnavailable("no chromium")]
        with self.assertRaises(reference.ReferenceUnavailable):
            self.run_pages(URL)

    def test_consecutive_harness_errors_abort_the_run(self) -> None:
        self.servo.default = fakes.ERROR_LOAD
        urls = [f"https://site{n}.example-site.org/" for n in range(7)]
        run_id = self.store.create_run()
        with self.assertRaises(runner.RunAborted):
            runner.run(
                self.store,
                self.data,
                run_id,
                [runner.RunTarget(url) for url in urls],
                self.engines(),
                progress=self.progress,
            )
        self.assertEqual(
            len(self.store.outcomes_for_run(run_id)), runner.MAX_CONSECUTIVE_HARNESS_ERRORS
        )
        run = self.store.get_run(run_id)
        assert run is not None
        self.assertIsNone(run["finished"])  # An aborted run stays unfinished.


class _Refusing:
    """A navigation policy that refuses URLs containing a word."""

    def __init__(self, word: str) -> None:
        self.word = word

    def refusal(self, url: str, target: runner.RunTarget) -> str | None:
        return "sensitive" if self.word in url else None


class RunSafetyTest(RunnerTestCase):
    def test_a_page_that_navigates_out_of_the_policy_is_discarded(self) -> None:
        self.store.upsert_pages(
            [
                store.PageRow(
                    URL, "news.example-site.org", "example-site.org", None, "hister", True, "ok"
                )
            ]
        )
        logout = "https://news.example-site.org/account/logout?token=abc"
        self.servo.script[URL] = [fakes.Load(metrics={**fakes.good_metrics(), "url": logout})]

        summary = self.run_pages(URL, navigation=_Refusing("logout"))

        self.assertEqual(summary.verdicts, {"excluded": 1})
        self.assertEqual(self.reference.calls, [])  # Not loaded a second time.
        self.assertEqual(len(self.servo.calls), 1)
        self.assertEqual(self.store.selection_rows(), [])
        self.assertEqual(self.store.issues(), [])

    def test_a_navigation_the_reference_blocked_discards_the_page(self) -> None:
        self.reference.navigates_to[URL] = "https://news.example-site.org/logout"
        summary = self.run_pages(URL, navigation=_Refusing("logout"))
        self.assertEqual(summary.verdicts, {"excluded": 1})
        self.assertEqual(len(self.servo.calls), 1)

    def test_the_reference_gets_the_navigation_policy(self) -> None:
        self.run_pages(URL, navigation=_Refusing("logout"))
        (refusal,) = self.reference.refusals
        assert refusal is not None
        self.assertEqual(refusal("https://news.example-site.org/logout"), "sensitive")
        self.assertIsNone(refusal(URL))

    def test_a_failure_against_a_cached_reference_is_judged_again_with_a_fresh_one(self) -> None:
        # Yesterday's front page: none of today's words.
        yesterday = " ".join(f"stale{chr(97 + i % 26)}{chr(97 + i // 26)}" for i in range(60))
        self.reference.stale[URL] = {
            **fakes.good_metrics(),
            "text": yesterday,
            "textLength": len(yesterday),
        }

        summary = self.run_pages(URL)

        self.assertEqual(summary.verdicts, {"ok": 1})
        self.assertEqual(self.reference.policies, [None, reference.CachePolicy.REFRESH])
        self.assertEqual(len(self.servo.calls), 1)
        record = summary.pages[0]["reference"]
        self.assertFalse(record["cached"])

    def test_symptom_signatures_are_scoped_to_the_site(self) -> None:
        scope = runner.SiteScope(b"k" * 16)
        self.servo.default = fakes.HANG_LOAD
        same_site = "https://www.example-site.org/other"

        self.run_pages(URL, same_site, OTHER_URL, scope=scope)

        tag = scope(URL)
        self.assertEqual(tag, scope(same_site))
        self.assertNotEqual(tag, scope(OTHER_URL))
        self.assertEqual(
            sorted(issue["signature"] for issue in self.store.issues()),
            sorted([f"hang@{tag}", f"hang@{scope(OTHER_URL)}"]),
        )
        self.assertNotIn("example", tag)

    def test_hosts_seen_while_loading_are_kept_for_the_leak_check(self) -> None:
        landing = "https://www.acme-group.example-site.org/landing"
        script_error = fakes.PageError(
            "uncaught", "x", "https://static.acme-cdn.example-cdn.net/app.js", 1, 1
        )
        self.servo.script[URL] = [
            fakes.Load(
                metrics={**fakes.good_metrics(), "url": landing},
                errors=(script_error,),
            )
        ]

        self.run_pages(URL)

        self.assertTrue(
            {"www.acme-group.example-site.org", "static.acme-cdn.example-cdn.net"}
            <= self.store.derived_hosts()
        )

    def test_a_full_disk_does_not_end_the_run_with_a_traceback(self) -> None:
        self.servo.script[URL] = [OSError(28, "No space left on device")]
        with mock.patch(
            "sitecheck.config.write_private_file", side_effect=OSError(28, "No space left")
        ):
            summary = self.run_pages(URL, OTHER_URL)
        self.assertEqual(summary.checked, 2)
        self.assertEqual(summary.verdicts, {"harness-error": 2})

    def test_new_and_reopened_issues_are_reported(self) -> None:
        self.servo.script[URL] = [fakes.JS_ERROR_LOAD]
        first = self.run_pages(URL)
        self.assertEqual(first.new_issues, ("js:fakeFn is not defined",))

        self.store.mark_issue("js:fakeFn is not defined", store.IssueStatus.FIXED)
        second = self.run_pages(URL)
        self.assertEqual(second.new_issues, ())
        self.assertEqual(second.reopened_issues, ("js:fakeFn is not defined",))


class CheckPageTest(RunnerTestCase):
    def test_repeating_loads_every_time_and_reports_flakiness(self) -> None:
        self.servo.script[URL] = [fakes.OK_LOAD, fakes.CRASH_LOAD, fakes.OK_LOAD]
        page_dir = self.data / "check" / "page"
        outcome = runner.check_page(
            URL, page_dir, self.engines(), runner.AttemptPolicy.repeating(3)
        )

        self.assertEqual(
            [attempt.verdict.kind.value for attempt in outcome.attempts], ["ok", "crash", "ok"]
        )
        self.assertIs(outcome.verdict.kind, VerdictKind.FLAKY)
        self.assertEqual(
            [attempt.directory.name for attempt in outcome.attempts],
            ["page", "attempt-2", "attempt-3"],
        )
        record = runner.outcome_record(outcome, page_dir.parent)
        self.assertEqual(record["verdict"], "flaky")
        self.assertEqual(
            [a["artifacts"] for a in record["attempts"]],
            ["page", "page/attempt-2", "page/attempt-3"],
        )
        self.assertNotIn(fakes.WORDS, json.dumps(record))

    def test_an_ok_page_loads_once_by_default(self) -> None:
        outcome = runner.check_page(URL, self.data / "page", self.engines())
        self.assertEqual(len(outcome.attempts), 1)
        self.assertIs(outcome.verdict.kind, VerdictKind.OK)


class ServoshellEngineTest(unittest.TestCase):
    def test_passes_combrays_engine_settings(self) -> None:
        engine = runner.ServoshellEngine(Path("/opt/servoshell"), "UA/1")
        with mock.patch("sitecheck.servo.run_page") as run_page:
            engine.run_page(URL, Path("/tmp/out"))
        run_page.assert_called_once()
        _, kwargs = run_page.call_args
        self.assertEqual(kwargs["user_agent"], "UA/1")
        self.assertEqual(dict(kwargs["prefs"]), dict(config.ENGINE_PREFS))
        self.assertEqual(kwargs["viewport"], (config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT))
        self.assertEqual(kwargs["device_pixel_ratio"], config.DEVICE_PIXEL_RATIO)


class _FakeChromium:
    browser_version = "chromium 0.0"

    def __init__(self) -> None:
        self.loads: list[str] = []

    def run_page(self, url: str, out_dir: Path, **_: object) -> PageResult:
        self.loads.append(url)
        out_dir.mkdir(parents=True, exist_ok=True)
        screenshot = out_dir / reference.SCREENSHOT_NAME
        screenshot.write_bytes(fakes.FAKE_PNG)
        return PageResult(
            Engine.CHROMIUM,
            url,
            PageStatus.OK,
            900,
            metrics=fakes.good_metrics(),
            screenshot=screenshot,
        )


class ChromiumSourceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = config.ensure_private_dir(self.root / "data")
        self.chromium = _FakeChromium()

    def source(
        self, policy: reference.CachePolicy = reference.CachePolicy.USE
    ) -> runner.ChromiumSource:
        return runner.ChromiumSource(self.chromium, self.data, user_agent="UA/1", policy=policy)  # type: ignore[arg-type]

    def test_web_pages_are_cached_between_loads(self) -> None:
        first = self.source().load(URL, self.root / "run1")
        second = self.source().load(URL, self.root / "run2")
        self.assertFalse(first.cached)
        self.assertTrue(second.cached)
        self.assertEqual(self.chromium.loads, [URL])
        self.assertTrue((self.root / "run2" / reference.SCREENSHOT_NAME).is_file())
        self.assertEqual(self.source().name, "chromium 0.0")

    def test_refresh_ignores_the_cache(self) -> None:
        self.source().load(URL, self.root / "run1")
        refreshed = self.source(reference.CachePolicy.REFRESH).load(URL, self.root / "run2")
        self.assertFalse(refreshed.cached)
        self.assertEqual(self.chromium.loads, [URL, URL])

    def test_local_servers_are_never_cached(self) -> None:
        # Reduced test cases served locally change while someone works on them.
        for local in (
            "http://127.0.0.1:8000/case.html",
            "http://localhost:8080/case.html",
            "http://case.test/",
            "https://example-site.org:8443/case.html",
        ):
            with self.subTest(url=local):
                self.source().load(local, self.root / "run1")
                self.source().load(local, self.root / "run2")
                self.assertEqual(self.chromium.loads[-2:], [local, local])
        self.assertFalse((self.data / config.REFERENCE_CACHE_DIR_NAME).exists())

    def test_an_entry_from_another_browser_version_is_a_miss(self) -> None:
        self.source().load(URL, self.root / "run1")
        self.chromium.browser_version = "chromium 0.1"
        self.assertFalse(self.source().load(URL, self.root / "run2").cached)

    def test_a_load_can_ask_for_a_fresh_result(self) -> None:
        self.source().load(URL, self.root / "run1")
        fresh = self.source().load(URL, self.root / "run2", cache=reference.CachePolicy.REFRESH)
        self.assertFalse(fresh.cached)
        self.assertTrue(self.source().load(URL, self.root / "run3").cached)

    def test_local_files_are_never_cached(self) -> None:
        local = "file:///tmp/fixture.html"
        self.source().load(local, self.root / "run1")
        self.source().load(local, self.root / "run2")
        self.assertEqual(self.chromium.loads, [local, local])
        self.assertFalse((self.data / config.REFERENCE_CACHE_DIR_NAME).exists())


if __name__ == "__main__":
    unittest.main()
