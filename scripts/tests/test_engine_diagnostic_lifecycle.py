"""Exercise the actual workflow shell without a VM, SDK, or host telemetry."""

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


def fixture_command():
    workflow = (ROOT / ".github/workflows/engine.yml").read_text()
    section = workflow.split("      - name: Boot the reference emulator and run fixtures\n", 1)[1]
    section = section.split("\n      - name:", 1)[0]
    return textwrap.dedent(section.split("        run: |\n", 1)[1])


class EngineDiagnosticLifecycleTest(unittest.TestCase):
    def run_fixture(self, boot, fixture, monitor=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts/m0").mkdir(parents=True)
            (root / "scripts/m0/host_diagnostics.py").write_text(
                "import os, pathlib, signal, sys, time\n"
                "def stop(*_):\n"
                "    pathlib.Path('monitor.stopped').touch()\n"
                "    sys.exit(0)\n"
                "signal.signal(signal.SIGTERM, stop)\n"
                "pathlib.Path('monitor.ready').touch()\n"
                "failure = int(os.environ['MONITOR_RESULT'])\n"
                "if failure: sys.exit(failure)\n"
                "deadline = time.monotonic() + 8\n"
                "while time.monotonic() < deadline: time.sleep(0.02)\n"
            )
            boot_script = root / "scripts/m0/emulator.sh"
            boot_script.write_text(
                "#!/usr/bin/env bash\nset -eu\n"
                "for ((i=0; i<500; i++)); do\n"
                "  [ ! -e monitor.ready ] || break\n"
                "  sleep 0.01\n"
                "done\n"
                "[ -e monitor.ready ] || exit 99\n"
                "exit \"$BOOT_RESULT\"\n"
            )
            fixture_script = root / "scripts/check-app.sh"
            fixture_script.write_text(
                "#!/usr/bin/env bash\ntouch fixture.called\nexit \"$FIXTURE_RESULT\"\n"
            )
            boot_script.chmod(0o700)
            fixture_script.chmod(0o700)
            environment = {
                **os.environ,
                "ANDROID_AVD_HOME": str(root / "avd"),
                "BOOT_RESULT": str(boot),
                "FIXTURE_RESULT": str(fixture),
                "MONITOR_RESULT": str(monitor),
            }
            completed = subprocess.run(
                ["bash", "-e", "-c", fixture_command()], cwd=root, env=environment,
                capture_output=True, text=True, timeout=15,
            )
            self.assertEqual(boot or fixture, completed.returncode, completed.stderr)
            self.assertEqual(boot == 0, (root / "fixture.called").exists())
            if monitor == 0:
                self.assertTrue((root / "monitor.stopped").exists())
            else:
                self.assertIn("::warning::host diagnostics", completed.stdout)

    def test_device_runtime_host_is_pinned_to_the_lts_baseline(self):
        workflow = (ROOT / ".github/workflows/engine.yml").read_text()
        device = workflow.split("\n  device:\n", 1)[1].split("\n  cleanup:\n", 1)[0]
        self.assertIn("\n    runs-on: ubuntu-24.04\n", device)

    def test_boot_failure_is_preserved_and_monitor_stops(self):
        self.run_fixture(17, 0)

    def test_fixture_failure_is_preserved_and_monitor_stops(self):
        self.run_fixture(0, 23)

    def test_success_stops_the_monitor(self):
        self.run_fixture(0, 0)

    def test_failed_monitor_cannot_hide_fixture_failure(self):
        self.run_fixture(0, 23, monitor=8)

    def test_optional_kernel_argument_works_with_nounset(self):
        source = (ROOT / "scripts/m0/emulator.sh").read_text()
        preparation = source.split('diagnostic_arg=""\n', 1)[1].split('\necho "==> booting', 1)[0]
        command = 'diagnostic_arg=""\n' + preparation + '\nprintf "<%s>\\n" ${diagnostic_arg:+"$diagnostic_arg"}'
        for enabled, expected in [("0", "<>\n"), ("1", "<-show-kernel>\n")]:
            with self.subTest(enabled=enabled):
                completed = subprocess.run(
                    ["bash", "-eu", "-c", command], text=True, capture_output=True,
                    env={**os.environ, "COMBRAY_EMULATOR_DIAGNOSTICS": enabled}, check=True,
                )
                self.assertEqual(expected, completed.stdout)


if __name__ == "__main__":
    unittest.main()
