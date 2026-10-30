"""The device-check summary never reports a run that stopped early as passed."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]

# Raw `am instrument -r` output of a run whose process died in its second of three tests.
CRASHED_RUN = """\
INSTRUMENTATION_STATUS: class=ch.lkmc.combray.AccessibilityTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=first
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=ch.lkmc.combray.AccessibilityTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=first
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=ch.lkmc.combray.AccessibilityTest
INSTRUMENTATION_STATUS: current=2
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=second
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_RESULT: longMsg=Input dispatching timed out
INSTRUMENTATION_RESULT: shortMsg=keyDispatchingTimedOut
INSTRUMENTATION_CODE: 0
"""

# A finished run ends with a result block too, without a shortMsg.
PASSED_RUN = (
    CRASHED_RUN.split("INSTRUMENTATION_STATUS: class=ch.lkmc.combray.AccessibilityTest\nINSTRUMENTATION_STATUS: current=2")[0]
    .replace("numtests=3", "numtests=1")
    + "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n"
)

# The process died after the first test ended, before the second started.
BETWEEN_TESTS_RUN = CRASHED_RUN.replace(
    "INSTRUMENTATION_STATUS: class=ch.lkmc.combray.AccessibilityTest\n"
    "INSTRUMENTATION_STATUS: current=2\n"
    "INSTRUMENTATION_STATUS: numtests=3\n"
    "INSTRUMENTATION_STATUS: test=second\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n",
    "",
)


# `logcat -v threadtime` of a run: a report-only check that timed out and one that passed, a check
# that waited out a timeout and a quick one.
LOGCAT = """\
10-30 09:00:00.000  4242  4260 I TestRunner: started: slowCheck(ch.lkmc.combray.MediaPlayerTest)
10-30 09:04:00.500  4242  4243 W MediaPlayerTest: REPORT stop and start: failed: Condition still not satisfied after 240000 ms
10-30 09:04:00.600  4242  4260 I TestRunner: finished: slowCheck(ch.lkmc.combray.MediaPlayerTest)
10-30 09:04:01.000  4242  4260 I TestRunner: started: quickCheck(ch.lkmc.combray.MediaPlayerTest)
10-30 09:04:03.000  4242  4243 I MediaPlayerTest: REPORT audio: passed
10-30 09:04:03.100  4242  4243 I MediaPlayerTest: REPORT audio position advanced: false
10-30 09:04:04.000  4242  4260 I TestRunner: finished: quickCheck(ch.lkmc.combray.MediaPlayerTest)
"""


def load_summarizer():
    spec = importlib.util.spec_from_file_location("summarize", ROOT / "scripts/summarize-instrumentation.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def summarize(raw, logcat="not a run\n"):
    """(exit status, printed summary) for one run with this raw output, or for none."""
    summarizer = load_summarizer()
    with tempfile.TemporaryDirectory() as directory:
        (Path(directory) / "logcat.txt").write_text(logcat)
        if raw is not None:
            (Path(directory) / "accessibility.txt").write_text(raw)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch("sys.argv", ["summarize", directory]):
            status = summarizer.main()
    return status, output.getvalue()


class SummaryTest(unittest.TestCase):
    def test_a_complete_run_passes(self):
        status, summary = summarize(PASSED_RUN)
        self.assertEqual(status, 0, summary)
        self.assertIn("passed: 1", summary)

    def test_a_crashed_run_fails_and_says_what_did_not_run(self):
        status, summary = summarize(CRASHED_RUN)
        self.assertEqual(status, 1, summary)
        self.assertIn("| accessibility | AccessibilityTest | `second` | **crashed** |", summary)
        self.assertIn("not run: 1", summary)
        self.assertIn("keyDispatchingTimedOut", summary)

    def test_a_run_that_died_between_tests_fails(self):
        status, summary = summarize(BETWEEN_TESTS_RUN)
        self.assertEqual(status, 1, summary)
        self.assertIn("not run: 2", summary)
        self.assertIn("runs that stopped early: accessibility (keyDispatchingTimedOut)", summary)
        self.assertNotIn("crashed", summary)

    def test_failed_reports_and_slow_checks_are_listed_without_failing_the_run(self):
        status, summary = summarize(PASSED_RUN, LOGCAT)
        self.assertEqual(status, 0, summary)
        self.assertIn(
            "- 10-30 09:04:00.500 MediaPlayerTest: REPORT stop and start: failed: "
            "Condition still not satisfied after 240000 ms",
            summary,
        )
        self.assertNotIn("REPORT audio", summary)
        self.assertIn("- MediaPlayerTest `slowCheck`: 240.6 s", summary)
        self.assertNotIn("quickCheck", summary)

    def test_a_quiet_log_says_so(self):
        status, summary = summarize(PASSED_RUN)
        self.assertEqual(status, 0, summary)
        self.assertIn("Report-only checks that failed: none", summary)
        self.assertIn("Checks slower than 30 s: none", summary)

    def test_a_check_across_new_year_is_timed(self):
        logcat = (
            "12-31 23:59:50.000  1  2 I TestRunner: started: late(ch.lkmc.combray.BrowsingTest)\n"
            "01-01 00:00:40.000  1  2 I TestRunner: finished: late(ch.lkmc.combray.BrowsingTest)\n"
        )
        _, summary = summarize(PASSED_RUN, logcat)
        self.assertIn("- BrowsingTest `late`: 50.0 s", summary)

    def test_no_runs_at_all_fail(self):
        status, summary = summarize(None)
        self.assertEqual(status, 1, summary)
        self.assertIn("no results", summary)


if __name__ == "__main__":
    unittest.main()
