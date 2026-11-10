"""Device-check discovery and temporary device appearance settings."""

import importlib.util
import os
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


class NightModeTest(unittest.TestCase):
    def run_mode(self, mode, result=0, switch_result=0, restore_result=0):
        source = (ROOT / "scripts/check-app.sh").read_text()
        cleanup = "cleanup() {" + source.split("cleanup() {", 1)[1].split("\ntrap cleanup EXIT", 1)[0]
        appearance = "use_light_mode() {" + source.split("use_light_mode() {", 1)[1].split(
            "\nwait_for_package_manager", 1
        )[0]
        command = r'''
PIDS=()
FIXTURE_PORT=8766
STAY_ON_ORIGINAL=""
NIGHT_ORIGINAL=""
ADB=(fake_adb)
exec 3>&1
fake_adb() {
    if [ "$1" != shell ]; then return; fi
    if [ "$#" -eq 4 ]; then
        printf 'Night mode: %s\r\n' "$MODE"
        return
    fi
    printf 'SET:%s\n' "$5" >&3
    if [ "$5" = no ]; then
        return "$SWITCH_RESULT"
    fi
    return "$RESTORE_RESULT"
}
'''
        command += cleanup + "\ntrap cleanup EXIT\n" + appearance
        command += '\nuse_light_mode\nexit "$RESULT"\n'
        return subprocess.run(
            ["bash", "-euo", "pipefail", "-c", command],
            env={
                **os.environ, "MODE": mode, "RESULT": str(result),
                "SWITCH_RESULT": str(switch_result), "RESTORE_RESULT": str(restore_result),
            },
            capture_output=True, text=True, timeout=10,
        )

    def test_dark_and_scheduled_modes_are_restored_after_success_or_failure(self):
        for mode in ("yes", "auto", "custom_schedule", "custom_bedtime"):
            for result in (0, 23):
                with self.subTest(mode=mode, result=result):
                    completed = self.run_mode(mode, result)
                    self.assertEqual(completed.returncode, result, completed.stderr)
                    self.assertEqual(
                        [line for line in completed.stdout.splitlines() if line.startswith("SET:")],
                        ["SET:no", f"SET:{mode}"],
                    )

    def test_a_light_device_is_not_changed(self):
        completed = self.run_mode("no")
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertNotIn("SET:", completed.stdout)

    def test_an_unrecognized_mode_is_not_changed(self):
        completed = self.run_mode("unknown")
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertNotIn("SET:", completed.stdout)

    def test_a_failed_restore_is_reported_without_hiding_the_test_failure(self):
        completed = self.run_mode("yes", result=23, restore_result=17)
        self.assertEqual(completed.returncode, 23, completed.stderr)
        self.assertIn("could not restore night mode", completed.stderr)

    def test_a_failed_switch_attempt_restores_the_original_and_stops(self):
        completed = self.run_mode("yes", switch_result=17)
        self.assertEqual(completed.returncode, 17, completed.stderr)
        self.assertEqual(
            [line for line in completed.stdout.splitlines() if line.startswith("SET:")],
            ["SET:no", "SET:yes"],
        )


if __name__ == "__main__":
    unittest.main()
