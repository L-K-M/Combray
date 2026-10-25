"""Tests for sitecheck.compare: one test per verdict path, threshold edges and signatures."""

from __future__ import annotations

import math
import string
import unittest
from typing import Any

from sitecheck import compare
from sitecheck.model import (
    CrashInfo,
    Engine,
    PageError,
    PageResult,
    PageStatus,
    Verdict,
    VerdictKind,
)

VIEWPORT_WIDTH = 412


def word(index: int) -> str:
    """A distinct letters-only word of at least three letters for each index."""
    letters = string.ascii_lowercase
    suffix = ""
    while True:
        index, remainder = divmod(index, len(letters))
        suffix = letters[remainder] + suffix
        if index == 0:
            break
    return "wrd" + suffix


def text_of(words: list[str]) -> str:
    return " ".join(words)


# 100 distinct words, about 600 characters: enough for every text rule to apply.
REFERENCE_WORDS = [word(i) for i in range(100)]
REFERENCE_TEXT = text_of(REFERENCE_WORDS)


def metrics(**overrides: Any) -> dict[str, Any]:
    text = overrides.pop("text", REFERENCE_TEXT)
    values: dict[str, Any] = {
        "readyState": "complete",
        "text": text,
        "textLength": len(text),
        "scrollWidth": VIEWPORT_WIDTH,
        "innerWidth": VIEWPORT_WIDTH,
        "imagesLoaded": 10,
        "imagesBroken": 0,
        "errors": [],
        "probeErrors": [],
    }
    values.update(overrides)
    return values


def result(
    engine: Engine,
    *,
    status: PageStatus = PageStatus.OK,
    errors: tuple[PageError, ...] = (),
    crash: CrashInfo | None = None,
    navigation_timed_out: bool = False,
    **metric_overrides: Any,
) -> PageResult:
    no_metrics = metric_overrides.pop("no_metrics", False)
    has_metrics = status is PageStatus.OK and not no_metrics
    if metric_overrides and not has_metrics:
        raise TypeError(f"metric overrides on a result without metrics: {sorted(metric_overrides)}")
    return PageResult(
        engine=engine,
        url="https://example.com/",
        status=status,
        duration_ms=1000,
        metrics=metrics(**metric_overrides) if has_metrics else None,
        errors=list(errors),
        crash=crash,
        navigation_timed_out=navigation_timed_out,
    )


def servo(**kwargs: Any) -> PageResult:
    return result(Engine.SERVO, **kwargs)


def ref(**kwargs: Any) -> PageResult:
    return result(Engine.CHROMIUM, **kwargs)


def images(
    servo_stddev: float | None = 40.0,
    ref_stddev: float | None = 40.0,
    score: float | None = 0.95,
) -> dict[str, Any]:
    """Image evidence shaped like images.compare_screenshots' result."""

    def image_stats(stddev: float | None) -> dict[str, Any] | None:
        if stddev is None:
            return None
        return {
            "width": 412,
            "height": 915,
            "mean": 200.0,
            "stddev": stddev,
            "distinct_colors_sample": 40,
        }

    return {
        "servo": image_stats(servo_stddev),
        "reference": image_stats(ref_stddev),
        "similarity": None if score is None else {"pixel": score, "ahash": score, "score": score},
        "errors": [],
    }


def kinds(verdict: Verdict) -> list[VerdictKind]:
    return [finding.kind for finding in verdict.findings]


def uncaught(message: str) -> PageError:
    return PageError("uncaught", message)


class VerdictPathTest(unittest.TestCase):
    def test_matching_pages_are_ok(self) -> None:
        verdict = compare.verdict(servo(), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.OK)
        self.assertEqual(verdict.severity, 0)
        self.assertEqual(verdict.findings, ())
        self.assertEqual(verdict.signatures, [])
        self.assertTrue(verdict.triaged)

    def test_ok_without_image_evidence(self) -> None:
        self.assertEqual(compare.verdict(servo(), ref(), None).kind, VerdictKind.OK)

    def test_servo_harness_error_is_excluded_from_triage(self) -> None:
        verdict = compare.verdict(servo(status=PageStatus.ERROR), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.HARNESS_ERROR)
        self.assertEqual(verdict.severity, 0)
        self.assertEqual(verdict.signatures, [])
        self.assertFalse(verdict.triaged)

    def test_harness_error_wins_over_failed_reference(self) -> None:
        verdict = compare.verdict(
            servo(status=PageStatus.ERROR), ref(status=PageStatus.NAVIGATION_ERROR), None
        )
        self.assertEqual(verdict.kind, VerdictKind.HARNESS_ERROR)

    def test_servo_ok_without_metrics_is_a_harness_error(self) -> None:
        verdict = compare.verdict(servo(no_metrics=True), ref(), images())
        self.assertEqual(verdict.kind, VerdictKind.HARNESS_ERROR)
        self.assertFalse(verdict.triaged)

    def test_crash_signature_uses_panic_location(self) -> None:
        crash = CrashInfo(
            "already borrowed: BorrowMutError", "components/script/dom/node.rs:123:45"
        )
        verdict = compare.verdict(servo(status=PageStatus.CRASH, crash=crash), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.CRASH)
        self.assertEqual(verdict.severity, 5)
        self.assertEqual(verdict.signatures, ["crash:components/script/dom/node.rs:123"])

    def test_crash_location_in_a_dependency_drops_the_local_path(self) -> None:
        crash = CrashInfo(
            "oops",
            "/Users/someone/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/"
            "wgpu-core-25.0.0/src/device/mod.rs:88:9",
        )
        verdict = compare.verdict(servo(status=PageStatus.CRASH, crash=crash), ref(), None)
        self.assertEqual(verdict.signatures, ["crash:wgpu-core-25.0.0/src/device/mod.rs:88"])

    def test_crash_without_location_uses_normalized_message(self) -> None:
        crash = CrashInfo("index out of bounds: the len is 3 but the index is 123456")
        verdict = compare.verdict(servo(status=PageStatus.CRASH, crash=crash), ref(), None)
        self.assertEqual(
            verdict.signatures, ["crash:index out of bounds: the len is 3 but the index is <n>"]
        )

    def test_crash_without_details(self) -> None:
        by_signal = CrashInfo("", signal=11)
        by_exit = CrashInfo("  ", exit_code=101)
        cases = [
            (None, "crash:unknown"),
            (by_signal, "crash:signal-11"),
            (by_exit, "crash:exit-101"),
        ]
        for crash, signature in cases:
            with self.subTest(signature=signature):
                verdict = compare.verdict(servo(status=PageStatus.CRASH, crash=crash), ref(), None)
                self.assertEqual(verdict.signatures, [signature])

    def test_crash_is_reported_without_a_usable_reference(self) -> None:
        crash = CrashInfo("boom", "components/layout/flow.rs:10:1")
        for reference in (None, ref(status=PageStatus.NAVIGATION_ERROR)):
            with self.subTest(reference=reference):
                verdict = compare.verdict(
                    servo(status=PageStatus.CRASH, crash=crash), reference, None
                )
                self.assertEqual(verdict.kind, VerdictKind.CRASH)

    def test_hang(self) -> None:
        verdict = compare.verdict(servo(status=PageStatus.HANG), ref(), None)

        self.assertEqual(verdict.kind, VerdictKind.HANG)
        self.assertEqual(verdict.severity, 4)
        self.assertEqual(verdict.signatures, ["hang"])

    def test_hang_detail_does_not_claim_a_wall_clock_kill(self) -> None:
        # A script timeout (seen live while scrolling a news page) is a hang too,
        # but nothing was killed at the wall clock.
        verdict = compare.verdict(servo(status=PageStatus.HANG), ref(), None)
        self.assertNotIn("wall clock and was killed", verdict.findings[0].detail)

    def test_hang_is_reported_without_a_reference(self) -> None:
        self.assertEqual(
            compare.verdict(servo(status=PageStatus.HANG), None, None).kind, VerdictKind.HANG
        )

    def test_reference_that_did_not_load_is_excluded(self) -> None:
        for status in (
            PageStatus.NAVIGATION_ERROR,
            PageStatus.HANG,
            PageStatus.CRASH,
            PageStatus.ERROR,
        ):
            with self.subTest(status=status):
                verdict = compare.verdict(
                    servo(text="", textLength=0), ref(status=status), images()
                )
                self.assertEqual(verdict.kind, VerdictKind.REFERENCE_FAILED)
                self.assertEqual(verdict.severity, 0)
                self.assertEqual(verdict.signatures, [])
                self.assertFalse(verdict.triaged)

    def test_without_a_reference_nothing_but_crash_and_hang_is_judged(self) -> None:
        cases = (
            ("loaded", servo()),
            ("navigation error", servo(status=PageStatus.NAVIGATION_ERROR)),
            ("loaded without text", servo(text="")),
        )
        for case, result in cases:
            with self.subTest(case=case):
                verdict = compare.verdict(result, None, None)
                self.assertEqual(verdict.kind, VerdictKind.NOT_COMPARED)
                self.assertEqual((verdict.severity, verdict.signatures), (0, []))
                self.assertFalse(verdict.triaged)

    def test_a_hang_in_both_engines_is_not_servos(self) -> None:
        for status in (PageStatus.HANG, PageStatus.CRASH):
            with self.subTest(reference=status):
                verdict = compare.verdict(servo(status=PageStatus.HANG), ref(status=status), None)
                self.assertEqual(verdict.kind, VerdictKind.REFERENCE_FAILED)
                self.assertIn("reference", verdict.findings[0].detail)
        self.assertEqual(
            compare.verdict(
                servo(status=PageStatus.HANG), ref(status=PageStatus.NAVIGATION_ERROR), None
            ).kind,
            VerdictKind.HANG,
        )

    def test_hang_signature_names_the_cause(self) -> None:
        result = servo(status=PageStatus.HANG)
        result.hang_cause = "script-timeout:scroll"
        verdict = compare.verdict(result, ref(), None)
        self.assertEqual(verdict.signatures, ["hang:script-timeout:scroll"])

    def test_an_ok_result_without_any_content_evidence_is_not_a_pass(self) -> None:
        unmeasured = servo()
        assert unmeasured.metrics is not None
        unmeasured.metrics.update(
            text=None, textLength=None, scrollWidth=None, imagesLoaded=None, imagesBroken=None
        )
        verdict = compare.verdict(unmeasured, ref(), images(servo_stddev=None, score=None))
        self.assertEqual(verdict.kind, VerdictKind.HARNESS_ERROR)
        self.assertFalse(verdict.triaged)


class ScopeTest(unittest.TestCase):
    """Symptom signatures are scoped to a site, so one site's mark does not hide another's."""

    def test_symptom_signatures_carry_the_scope(self) -> None:
        cases = (
            (servo(text="", textLength=0), images(), "blank:text@s1"),
            (servo(status=PageStatus.NAVIGATION_ERROR), images(), "navigation-error@s1"),
            (servo(status=PageStatus.HANG), None, "hang@s1"),
            (servo(scrollWidth=900), images(), "layout-overflow@s1"),
            (servo(imagesLoaded=1), images(), "images@s1"),
            (servo(), images(score=0.1), "visual-diff@s1"),
            (servo(navigation_timed_out=True), images(), "slow-load@s1"),
            (servo(status=PageStatus.CRASH), images(), "crash:unknown@s1"),
        )
        for result, img, expected in cases:
            with self.subTest(expected=expected):
                verdict = compare.verdict(result, ref(), img, scope="s1")
                self.assertEqual(verdict.signatures, [expected])

    def test_root_cause_signatures_are_not_scoped(self) -> None:
        crash = CrashInfo("boom", "components/layout/flow.rs:10:1")
        cases = (
            (servo(status=PageStatus.CRASH, crash=crash), "crash:components/layout/flow.rs:10"),
            (servo(errors=(uncaught("foo is not a function"),)), "js:foo is not a function"),
        )
        for result, expected in cases:
            with self.subTest(expected=expected):
                verdict = compare.verdict(result, ref(), images(), scope="s1")
                self.assertEqual(verdict.signatures, [expected])
        wide = ref(scrollWidth=980, innerWidth=980)
        verdict = compare.verdict(servo(scrollWidth=900), wide, images(), scope="s1")
        self.assertEqual(verdict.signatures, ["layout-overflow:viewport"])

    def test_reference_without_metrics_is_excluded(self) -> None:
        verdict = compare.verdict(servo(), ref(no_metrics=True), images())
        self.assertEqual(verdict.kind, VerdictKind.REFERENCE_FAILED)

    def test_reference_with_little_text_and_blank_screenshot_is_excluded(self) -> None:
        cases = [
            # (reference textLength, reference stddev, verdict)
            (49, 3.9, VerdictKind.REFERENCE_FAILED),
            (0, 0.0, VerdictKind.REFERENCE_FAILED),
            (50, 3.9, VerdictKind.OK),
            (49, 4.0, VerdictKind.OK),
        ]
        for text_length, stddev, expected in cases:
            with self.subTest(text_length=text_length, stddev=stddev):
                reference = ref(text="x" * text_length, textLength=text_length)
                verdict = compare.verdict(servo(), reference, images(ref_stddev=stddev))
                self.assertEqual(verdict.kind, expected)

    def test_reference_with_little_text_is_kept_without_image_evidence(self) -> None:
        reference = ref(text="hi", textLength=2)
        for img in (None, images(ref_stddev=None)):
            with self.subTest(img=img):
                verdict = compare.verdict(servo(text="hi", textLength=2), reference, img)
                self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_servo_navigation_error_with_a_working_reference(self) -> None:
        verdict = compare.verdict(servo(status=PageStatus.NAVIGATION_ERROR), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.BLANK)
        self.assertEqual(verdict.severity, 4)
        self.assertEqual(verdict.signatures, ["navigation-error"])


class BlankTest(unittest.TestCase):
    def test_screenshot_threshold_edges(self) -> None:
        cases = [
            # (servo stddev, reference stddev, blank)
            (3.9, 10.0, True),
            (0.0, 80.0, True),
            (4.0, 10.0, False),
            (3.9, 9.9, False),
        ]
        for servo_stddev, ref_stddev, blank in cases:
            with self.subTest(servo_stddev=servo_stddev, ref_stddev=ref_stddev):
                verdict = compare.verdict(
                    servo(), ref(), images(servo_stddev=servo_stddev, ref_stddev=ref_stddev)
                )
                self.assertEqual(VerdictKind.BLANK in kinds(verdict), blank)

    def test_blank_screenshot_verdict(self) -> None:
        verdict = compare.verdict(servo(), ref(), images(servo_stddev=0.0, score=0.2))

        self.assertEqual(verdict.kind, VerdictKind.BLANK)
        self.assertEqual(verdict.severity, 4)
        self.assertEqual(verdict.signatures, ["blank:screenshot"])

    def test_text_threshold_edges(self) -> None:
        # Servo keeps the reference's words so only textLength decides.
        cases = [
            # (servo textLength, reference textLength, blank)
            (49, 1000, True),
            (0, 200, True),
            (50, 1000, False),
            (0, 199, False),
        ]
        for servo_length, ref_length, blank in cases:
            with self.subTest(servo_length=servo_length, ref_length=ref_length):
                verdict = compare.verdict(
                    servo(textLength=servo_length), ref(textLength=ref_length), images()
                )
                self.assertEqual(VerdictKind.BLANK in kinds(verdict), blank)

    def test_blank_suppresses_content_and_visual_findings(self) -> None:
        verdict = compare.verdict(
            servo(text="", textLength=0), ref(), images(servo_stddev=0.0, score=0.1)
        )

        self.assertEqual(kinds(verdict), [VerdictKind.BLANK])
        self.assertEqual(verdict.signatures, ["blank:screenshot"])

    def test_blank_by_text_has_its_own_signature(self) -> None:
        verdict = compare.verdict(servo(text="", textLength=0), ref(), images())
        self.assertEqual(verdict.signatures, ["blank:text"])

    def test_missing_text_metrics_skip_the_text_rule(self) -> None:
        verdict = compare.verdict(servo(textLength=None), ref(), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)


class ContentTest(unittest.TestCase):
    def test_containment_threshold_edges(self) -> None:
        cases = [
            # (reference words servo has, content missing); there are 100.
            (60, False),
            (59, True),
            (0, True),
        ]
        for kept, missing in cases:
            with self.subTest(kept=kept):
                # Long enough that the blank text rule stays out of the way.
                servo_text = text_of(REFERENCE_WORDS[:kept] + ["unrelated"] * 200)
                verdict = compare.verdict(servo(text=servo_text), ref(), images())
                self.assertEqual(VerdictKind.CONTENT_MISSING in kinds(verdict), missing)

    def test_content_missing_verdict(self) -> None:
        servo_text = text_of(REFERENCE_WORDS[:50] + ["unrelated"] * 200)
        verdict = compare.verdict(servo(text=servo_text), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.CONTENT_MISSING)
        self.assertEqual(verdict.severity, 3)
        self.assertEqual(verdict.signatures, ["content-missing"])

    def test_words_are_case_insensitive_and_short_words_and_numbers_ignored(self) -> None:
        reference_text = REFERENCE_TEXT + " a an 12345 to of x9 ok"
        servo_text = REFERENCE_TEXT.upper()
        verdict = compare.verdict(servo(text=servo_text), ref(text=reference_text), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_only_the_most_frequent_reference_words_count(self) -> None:
        frequent = [word(i) for i in range(2000)]
        rare = [word(i) for i in range(2000, 2600)]
        reference_text = text_of(frequent + frequent + rare)
        servo_text = text_of(frequent)
        verdict = compare.verdict(servo(text=servo_text), ref(text=reference_text), images())
        self.assertNotIn(VerdictKind.CONTENT_MISSING, kinds(verdict))

    def test_too_few_reference_words_skip_the_rule(self) -> None:
        reference_text = text_of(REFERENCE_WORDS[:19] * 20)
        verdict = compare.verdict(
            servo(text="unrelated " * 100), ref(text=reference_text), images()
        )
        self.assertNotIn(VerdictKind.CONTENT_MISSING, kinds(verdict))


class ScriptErrorTest(unittest.TestCase):
    def test_servo_only_error_becomes_a_signature(self) -> None:
        error = uncaught("Uncaught TypeError: foo is not a function")
        verdict = compare.verdict(servo(errors=(error,)), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.JS_ERROR)
        self.assertEqual(verdict.severity, 2)
        self.assertEqual(verdict.signatures, ["js:foo is not a function"])

    def test_errors_both_engines_report_are_ignored(self) -> None:
        # Servo's log line and Chromium's pageerror for the same throw.
        servo_error = uncaught(
            "Error at https://a.example/app.js?v=3:12:40 undefinedFn is not defined"
        )
        ref_error = uncaught("ReferenceError: undefinedFn is not defined")
        verdict = compare.verdict(servo(errors=(servo_error,)), ref(errors=(ref_error,)), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_signature_strips_urls_line_numbers_and_long_quotes(self) -> None:
        message = (
            "Failed to execute 'x' on https://cdn.example/js/main.4f9a2b7c.js:1:2345: "
            '"' + "q" * 41 + '" at line 7 column 3'
        )
        verdict = compare.verdict(servo(errors=(uncaught(message),)), ref(), images())
        self.assertEqual(
            verdict.signatures, ["js:Failed to execute 'x' on <url> \"...\" at line column"]
        )

    def test_same_message_with_different_locations_is_one_signature(self) -> None:
        errors = (
            uncaught("Error at https://a.example/one.js:1:10 boom"),
            uncaught("Error at https://b.example/two.js:99:7 boom"),
            PageError("console", "boom"),
        )
        verdict = compare.verdict(servo(errors=errors), ref(), images())
        self.assertEqual(verdict.signatures, ["js:boom"])

    def test_signatures_are_capped_and_keep_the_first_errors(self) -> None:
        errors = tuple(uncaught(f"problem {name}") for name in "abcdefg")
        verdict = compare.verdict(servo(errors=errors), ref(), images())

        self.assertEqual(verdict.signatures, [f"js:problem {name}" for name in "abcde"])
        self.assertTrue(all("of 7" in finding.detail for finding in verdict.findings))

    def test_userscript_records_count_in_both_engines(self) -> None:
        servo_result = servo()
        assert servo_result.metrics is not None
        servo_result.metrics["errors"] = [
            {"type": "uncaught", "message": "early failure", "source": None, "line": 3}
        ]
        verdict = compare.verdict(servo_result, ref(), images())
        self.assertEqual(verdict.signatures, ["js:early failure"])

        reference = ref()
        assert reference.metrics is not None
        reference.metrics["errors"] = [
            {"type": "uncaught", "message": "Uncaught Error: early failure"}
        ]
        self.assertEqual(compare.verdict(servo_result, reference, images()).kind, VerdictKind.OK)

    def test_reference_pageerror_matches_servo_userscript_record(self) -> None:
        servo_result = servo()
        assert servo_result.metrics is not None
        servo_result.metrics["errors"] = [{"type": "uncaught", "message": "TypeError: x is null"}]
        reference = ref(errors=(uncaught("x is null"),))
        self.assertEqual(compare.verdict(servo_result, reference, images()).kind, VerdictKind.OK)

    def test_console_messages_are_not_compared(self) -> None:
        # Engines format logged objects and caught errors differently.
        errors = (
            PageError("console", '{"code": 42}'),
            PageError("console", '(new Error("x", "file:///a.html", 9))'),
            PageError("console", "plain message only servo logged"),
        )
        verdict = compare.verdict(servo(errors=errors), ref(), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_uncaught_errors_at_the_same_place_match_whatever_their_wording(self) -> None:
        source = "https://a.example/app.js"
        servo_error = PageError("uncaught", 'can\'t access property "foo", o is null', source, 8, 3)
        ref_error = PageError(
            "uncaught", "Cannot read properties of null (reading 'foo')", source, 8, 11
        )
        verdict = compare.verdict(servo(errors=(servo_error,)), ref(errors=(ref_error,)), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)

        elsewhere = PageError("uncaught", ref_error.message, source, 9, 11)
        verdict = compare.verdict(servo(errors=(servo_error,)), ref(errors=(elsewhere,)), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)  # The wording still matches.

    def test_property_access_wording_of_both_engines_is_one_signature(self) -> None:
        cases = (
            'can\'t access property "bar", q is null',
            'can\'t access property "bar" of null',
            "Cannot read properties of null (reading 'bar')",
            "TypeError: Cannot read properties of null (reading 'bar')",
        )
        for message in cases:
            with self.subTest(message=message):
                self.assertEqual(
                    compare.normalize_message(message), "can't access property 'bar' of null"
                )
        rejection = PageError("rejection", cases[0])
        reference_error = uncaught("Uncaught (in promise) TypeError: " + cases[2])
        verdict = compare.verdict(
            servo(errors=(rejection,)), ref(errors=(reference_error,)), images()
        )
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_a_reference_message_with_a_stack_matches_the_servo_message(self) -> None:
        # Deliberately asymmetric: Servo's console messages never become findings
        # (test_console_messages_are_not_compared), but a reference console message
        # still matches by wording, since the page may log there what it throws in Servo.
        reference_error = PageError("console", "Error: custom boom\n    at file:///x.html:1:331")
        verdict = compare.verdict(
            servo(errors=(uncaught("custom boom"),)), ref(errors=(reference_error,)), images()
        )
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_malformed_userscript_records_are_ignored(self) -> None:
        servo_result = servo()
        assert servo_result.metrics is not None
        servo_result.metrics["errors"] = [None, "text", {"message": 5}, {"type": "uncaught"}]
        self.assertEqual(compare.verdict(servo_result, ref(), images()).kind, VerdictKind.OK)


class NormalizeMessageTest(unittest.TestCase):
    def test_table(self) -> None:
        cases = [
            ("Uncaught TypeError: x is undefined", "x is undefined"),
            ("TypeError: x is undefined", "x is undefined"),
            ("uncaught exception: boom", "boom"),
            ("Uncaught (in promise) TypeError: Failed to fetch", "Failed to fetch"),
            ("Unhandled promise rejection: nope", "nope"),
            ("Error at file:///tmp/t.html:2:30 boom-type", "boom-type"),
            ("see https://example.com/a?b=1#c now", "see <url> now"),
            ("bad data:image/png;base64,AAAA here", "bad <url> here"),
            ("(at https://example.com/x.js:1:2)", "(at <url>)"),
            ("app.js:120:33 failed", "app.js failed"),
            ("failed at line 12", "failed at line"),
            ("Loading chunk 4567 failed", "Loading chunk <n> failed"),
            ("id deadbeef1234 missing", "id <id> missing"),
            ("short 'quoted text' stays", "short 'quoted text' stays"),
            ("long '" + "z" * 41 + "' goes", "long '...' goes"),
            ("40 chars '" + "z" * 40 + "' stay", "40 chars '" + "z" * 40 + "' stay"),
            ("  spaced \n\t out  ", "spaced out"),
            ("", "<empty>"),
            ("Uncaught TypeError: ", "<empty>"),
            (
                "NetworkError when attempting to fetch resource.",
                "NetworkError when attempting to fetch resource.",
            ),
            # Hosts and e-mail addresses from the page do not reach signatures.
            (
                "Error: Failed to load config from api.acme-private.ch/v2/user",
                "Failed to load config from <url>",
            ),
            ("Session expired for bob@mail.ch", "Session expired for <email>"),
            ("cannot reach www.acme-private.com now", "cannot reach <host> now"),
            # Member expressions are not hosts.
            ("article.animate is not a function", "article.animate is not a function"),
            ("window.foo.bar is undefined", "window.foo.bar is undefined"),
            # Engine stack traces after the message go.
            ("Error: x\n    at f (https://a.example/b.js:1:2)\n    at g", "x"),
        ]
        for message, expected in cases:
            with self.subTest(message=message):
                self.assertEqual(compare.normalize_message(message), expected)

    def test_length_is_capped(self) -> None:
        self.assertEqual(len(compare.normalize_message("word " * 100)), 159)


class LayoutOverflowTest(unittest.TestCase):
    def test_threshold_edges(self) -> None:
        cases = [
            # (servo scrollWidth, reference scrollWidth, overflow); viewport 412.
            (454, 412, True),  # 412 * 1.10 = 453.2
            (453, 412, False),
            (454, 420, True),  # 412 * 1.02 = 420.24
            (454, 421, False),
        ]
        for servo_width, ref_width, overflow in cases:
            with self.subTest(servo_width=servo_width, ref_width=ref_width):
                verdict = compare.verdict(
                    servo(scrollWidth=servo_width), ref(scrollWidth=ref_width), images()
                )
                self.assertEqual(VerdictKind.LAYOUT_OVERFLOW in kinds(verdict), overflow)

    def test_overflow_verdict(self) -> None:
        verdict = compare.verdict(servo(scrollWidth=800), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.LAYOUT_OVERFLOW)
        self.assertEqual(verdict.severity, 2)
        self.assertEqual(verdict.signatures, ["layout-overflow"])

    def test_missing_widths_skip_the_rule(self) -> None:
        verdict = compare.verdict(servo(scrollWidth=800, innerWidth=None), ref(), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_a_desktop_only_page_has_its_own_signature(self) -> None:
        # Seen live on a fixed-width page without a mobile viewport: Chromium's mobile
        # emulation widened its layout viewport to the page (990 px) and zoomed out;
        # Servo laid it out at 412 px. One root cause for all such pages, distinct
        # from a Servo layout bug on a page both engines lay out at 412 px.
        verdict = compare.verdict(
            servo(scrollWidth=990), ref(scrollWidth=990, innerWidth=990), images()
        )

        self.assertEqual(verdict.kind, VerdictKind.LAYOUT_OVERFLOW)
        self.assertEqual(verdict.signatures, ["layout-overflow:viewport"])
        self.assertIn("990 px viewport", verdict.findings[0].detail)


class ImagesTest(unittest.TestCase):
    def test_threshold_edges(self) -> None:
        cases = [
            # (servo loaded, servo broken, reference loaded, reference broken, finding)
            (10, 3, 10, 0, True),
            (10, 2, 10, 0, False),
            (10, 5, 10, 2, True),
            (1, 0, 4, 0, True),  # 1 < 0.5 * 4
            (2, 0, 4, 0, False),
            (0, 0, 3, 0, False),  # Too few reference images.
        ]
        for servo_loaded, servo_broken, ref_loaded, ref_broken, finding in cases:
            with self.subTest(servo=(servo_loaded, servo_broken), ref=(ref_loaded, ref_broken)):
                verdict = compare.verdict(
                    servo(imagesLoaded=servo_loaded, imagesBroken=servo_broken),
                    ref(imagesLoaded=ref_loaded, imagesBroken=ref_broken),
                    images(),
                )
                self.assertEqual(VerdictKind.IMAGES in kinds(verdict), finding)

    def test_images_verdict(self) -> None:
        verdict = compare.verdict(servo(imagesLoaded=0), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.IMAGES)
        self.assertEqual(verdict.severity, 2)
        self.assertEqual(verdict.signatures, ["images"])

    def test_boolean_counts_are_not_numbers(self) -> None:
        verdict = compare.verdict(servo(imagesBroken=True), ref(imagesBroken=False), images())
        self.assertEqual(verdict.kind, VerdictKind.OK)


class NonFiniteMetricTest(unittest.TestCase):
    """Chromium passes NaN and Infinity through from the page (which can replace
    window.innerWidth, say); such a metric was not measured."""

    def test_a_non_finite_reference_metric_skips_its_rule(self) -> None:
        # The Servo metrics that would make the metric's rule fire against a number.
        cases = {
            "innerWidth": {"scrollWidth": 800},
            "scrollWidth": {"scrollWidth": 800},
            "imagesLoaded": {"imagesLoaded": 0},
            "imagesBroken": {"imagesBroken": 9},
            "textLength": {"textLength": 5},
        }
        for metric, servo_metrics in cases.items():
            for value in (math.nan, math.inf, -math.inf):
                with self.subTest(metric=metric, value=value):
                    verdict = compare.verdict(
                        servo(**servo_metrics), ref(**{metric: value}), images()
                    )
                    self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_a_non_finite_screenshot_statistic_skips_its_rule(self) -> None:
        evidence = images(servo_stddev=math.nan, score=math.nan)
        self.assertEqual(compare.verdict(servo(), ref(), evidence).kind, VerdictKind.OK)


class SlowLoadTest(unittest.TestCase):
    def test_slow_load_when_the_page_is_otherwise_fine(self) -> None:
        verdict = compare.verdict(servo(navigation_timed_out=True), ref(), images())

        self.assertEqual(verdict.kind, VerdictKind.SLOW_LOAD)
        self.assertEqual(verdict.severity, 1)
        self.assertEqual(verdict.signatures, ["slow-load"])

    def test_not_reported_when_the_reference_also_timed_out(self) -> None:
        verdict = compare.verdict(
            servo(navigation_timed_out=True), ref(navigation_timed_out=True), images()
        )
        self.assertEqual(verdict.kind, VerdictKind.OK)

    def test_not_reported_alongside_other_findings(self) -> None:
        verdict = compare.verdict(
            servo(navigation_timed_out=True, scrollWidth=800), ref(), images()
        )
        self.assertEqual(kinds(verdict), [VerdictKind.LAYOUT_OVERFLOW])

    def test_not_reported_alongside_a_visual_difference(self) -> None:
        verdict = compare.verdict(servo(navigation_timed_out=True), ref(), images(score=0.3))
        self.assertEqual(kinds(verdict), [VerdictKind.VISUAL_DIFF])
        self.assertEqual(verdict.signatures, ["visual-diff"])


class VisualDiffTest(unittest.TestCase):
    def test_threshold_edges(self) -> None:
        for score, finding in ((0.54, True), (0.55, False)):
            with self.subTest(score=score):
                verdict = compare.verdict(servo(), ref(), images(score=score))
                self.assertEqual(VerdictKind.VISUAL_DIFF in kinds(verdict), finding)

    def test_visual_diff_verdict(self) -> None:
        verdict = compare.verdict(servo(), ref(), images(score=0.3))

        self.assertEqual(verdict.kind, VerdictKind.VISUAL_DIFF)
        self.assertEqual(verdict.severity, 1)
        self.assertEqual(verdict.signatures, ["visual-diff"])

    def test_uses_the_score_not_the_individual_measures(self) -> None:
        evidence = images()
        evidence["similarity"] = {"pixel": 0.1, "ahash": 0.1, "score": 0.9}
        self.assertEqual(compare.verdict(servo(), ref(), evidence).kind, VerdictKind.OK)

        evidence["similarity"] = {"pixel": 0.9, "ahash": 0.9}
        self.assertEqual(compare.verdict(servo(), ref(), evidence).kind, VerdictKind.OK)

    def test_missing_similarity_skips_the_rule(self) -> None:
        verdict = compare.verdict(servo(), ref(), images(score=None))
        self.assertEqual(verdict.kind, VerdictKind.OK)


class CombinedFindingsTest(unittest.TestCase):
    def test_findings_are_ordered_by_severity_then_kind(self) -> None:
        servo_text = text_of(REFERENCE_WORDS[:30] + ["unrelated"] * 200)
        verdict = compare.verdict(
            servo(
                text=servo_text,
                scrollWidth=900,
                imagesLoaded=1,
                errors=(uncaught("first"), uncaught("second")),
                navigation_timed_out=True,
            ),
            ref(),
            images(score=0.2),
        )

        self.assertEqual(
            kinds(verdict),
            [
                VerdictKind.CONTENT_MISSING,
                VerdictKind.JS_ERROR,
                VerdictKind.JS_ERROR,
                VerdictKind.LAYOUT_OVERFLOW,
                VerdictKind.IMAGES,
                VerdictKind.VISUAL_DIFF,
            ],
        )
        self.assertEqual(verdict.kind, VerdictKind.CONTENT_MISSING)
        self.assertEqual(verdict.severity, 3)
        self.assertEqual(
            verdict.signatures,
            [
                "content-missing",
                "js:first",
                "js:second",
                "layout-overflow",
                "images",
                "visual-diff",
            ],
        )

    def test_page_text_never_reaches_findings(self) -> None:
        marker = "secretmarkerword"
        reference_text = text_of(REFERENCE_WORDS + [marker] * 50)
        verdict = compare.verdict(
            servo(text="unrelated " * 100), ref(text=reference_text), images(score=0.1)
        )
        self.assertEqual(kinds(verdict), [VerdictKind.CONTENT_MISSING, VerdictKind.VISUAL_DIFF])
        for finding in verdict.findings:
            self.assertNotIn(marker, finding.detail)
            self.assertNotIn(marker, finding.signature or "")

    def test_every_verdict_kind_has_a_severity(self) -> None:
        self.assertEqual(set(compare.SEVERITY), set(VerdictKind))
        self.assertTrue(all(0 <= severity <= 5 for severity in compare.SEVERITY.values()))

    def test_arguments_in_the_wrong_order_are_rejected(self) -> None:
        with self.assertRaises(AssertionError):
            compare.verdict(ref(), servo(), None)


if __name__ == "__main__":
    unittest.main()
