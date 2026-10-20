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


def load_summarizer():
    spec = importlib.util.spec_from_file_location("summarize", ROOT / "scripts/summarize-instrumentation.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def summarize(raw):
    """(exit status, printed summary) for one run with this raw output, or for none."""
    summarizer = load_summarizer()
    with tempfile.TemporaryDirectory() as directory:
        (Path(directory) / "logcat.txt").write_text("not a run\n")
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

    def test_no_runs_at_all_fail(self):
        status, summary = summarize(None)
        self.assertEqual(status, 1, summary)
        self.assertIn("no results", summary)


if __name__ == "__main__":
    unittest.main()
