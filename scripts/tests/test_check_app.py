"""check-app.sh's default run covers every device-check class, so a new one is never skipped."""

import importlib.util
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID_TESTS = ROOT / "app/src/androidTest/kotlin/ch/lkmc/combray"
PACKAGE = "ch.lkmc.combray"
PROCESS_DEATH = f"{PACKAGE}.ProcessDeathTest"


def load_summarizer():
    spec = importlib.util.spec_from_file_location("summarize", ROOT / "scripts/summarize-instrumentation.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DefaultRunTest(unittest.TestCase):
    def setUp(self):
        # --list needs no device, SDK or APK.
        listed = subprocess.run(
            ["bash", str(ROOT / "scripts/check-app.sh"), "--list"], capture_output=True, text=True, timeout=60
        )
        self.assertEqual(listed.returncode, 0, listed.stdout + listed.stderr)
        self.classes = listed.stdout.split()

    def test_every_device_check_class_runs_once(self):
        expected = [f"{PACKAGE}.{path.stem}" for path in ANDROID_TESTS.glob("*Test.kt")]
        self.assertIn(f"{PACKAGE}.ReaderTest", expected)
        self.assertEqual(sorted(self.classes), sorted(expected))

    def test_process_death_runs_last(self):
        self.assertEqual(self.classes[-1], PROCESS_DEATH)

    def test_no_run_is_named_like_a_file_the_summary_skips(self):
        # check-app.sh names each class's output file after the class's simple name.
        skipped = {name.lower() for name in load_summarizer().SKIP}
        for test_class in self.classes:
            self.assertNotIn(f"{test_class.rsplit('.', 1)[-1]}.txt".lower(), skipped)


if __name__ == "__main__":
    unittest.main()
