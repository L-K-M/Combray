"""Deterministic tests for the bounded emulator-child supervisor."""

import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "scripts/m0/emulator_process.py"
POSIX_ONLY = unittest.skipIf(os.name == "nt", "POSIX signal semantics")


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="emulator process ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.pid_file = self.root / "emulator.pid"
        self.exit_file = self.root / "emulator-exit.json"

    def child(self, code, *arguments):
        return [sys.executable, "-I", "-c", code, *arguments]

    def supervise(self, command, pid_file=None, exit_file=None, timeout=20):
        return subprocess.run(
            [sys.executable, "-I", str(WRAPPER),
             "--pid-file", str(pid_file or self.pid_file),
             "--exit-file", str(exit_file or self.exit_file), "--", *command],
            capture_output=True, text=True, timeout=timeout)

    def metadata(self):
        return json.loads(self.exit_file.read_text())

    def bounded(self, code):
        process = subprocess.Popen(self.child(code))
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        return process

    def assert_reaped(self, pid):
        with self.assertRaises(OSError):
            os.kill(pid, 0)

    def test_normal_exit_zero_is_forwarded_with_metadata(self):
        result = self.supervise(self.child("print('child stdout')"))
        self.assertEqual(result.returncode, 0)
        self.assertIn("child stdout", result.stdout)
        record = self.metadata()
        self.assertEqual(record["returncode"], 0)
        self.assertEqual(set(record), {"childPID", "startedUtc", "endedUtc", "returncode"})
        self.assertEqual(record["childPID"], int(self.pid_file.read_text()))
        self.assertTrue(record["startedUtc"].endswith("Z"))
        self.assertTrue(record["endedUtc"].endswith("Z"))

    def test_nonzero_exit_is_forwarded(self):
        result = self.supervise(self.child("raise SystemExit(17)"))
        self.assertEqual(result.returncode, 17)
        self.assertEqual(self.metadata()["returncode"], 17)

    @POSIX_ONLY
    def test_child_signal_is_raw_in_metadata_and_normalized_for_cli(self):
        result = self.supervise(self.child("import os, signal; os.kill(os.getpid(), signal.SIGTERM)"))
        self.assertEqual(result.returncode, 143)
        self.assertEqual([self.metadata()["returncode"], self.metadata()["signal"]], [-15, 15])

    @POSIX_ONLY
    def test_supervisor_term_stops_only_owned_child_and_writes_metadata(self):
        bystander = self.bounded("import time; time.sleep(5)")
        process = subprocess.Popen(
            [sys.executable, "-I", str(WRAPPER),
             "--pid-file", str(self.pid_file), "--exit-file", str(self.exit_file),
             "--", *self.child("import time; time.sleep(5)")],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        deadline = time.monotonic() + 10
        while not self.pid_file.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(self.pid_file.exists())
        child_pid = int(self.pid_file.read_text())
        self.assertNotEqual(child_pid, process.pid)
        self.assertNotEqual(child_pid, bystander.pid)
        process.send_signal(signal.SIGTERM)
        process.communicate(timeout=15)
        self.assertEqual(process.returncode, 143)
        record = self.metadata()
        self.assertEqual([record["childPID"], record["returncode"], record["stopSignal"]],
                         [child_pid, -15, 15])
        self.assert_reaped(child_pid)
        self.assertIsNone(bystander.poll())
        bystander.kill()
        bystander.wait()

    def test_pid_file_is_record_only_and_never_signalled(self):
        bystander = self.bounded("import time; time.sleep(5)")
        self.pid_file.write_text("{}\n".format(bystander.pid))
        result = self.supervise(self.child("raise SystemExit(0)"))
        self.assertEqual(result.returncode, 0)
        self.assertNotEqual(int(self.pid_file.read_text()), bystander.pid)
        self.assertIsNone(bystander.poll())
        bystander.kill()
        bystander.wait()

    def test_startup_failure_is_class_only(self):
        result = self.supervise([str(self.root / "missing-emulator-binary")])
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr.strip(), "FileNotFoundError")
        self.assertFalse(self.exit_file.exists())

    def test_pid_file_failure_terminates_child_and_reports_error(self):
        result = self.supervise(
            self.child("import time; time.sleep(5)"),
            pid_file=self.root / "missing" / "emulator.pid")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr.strip(), "FileNotFoundError")
        record = self.metadata()
        self.assertEqual(record["error"], "FileNotFoundError")
        self.assert_reaped(record["childPID"])

    def test_exit_file_failure_never_reports_success_or_leaks_child(self):
        result = self.supervise(
            self.child("raise SystemExit(17)"),
            exit_file=self.root / "missing" / "emulator-exit.json")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr.strip(), "FileNotFoundError")
        self.assert_reaped(int(self.pid_file.read_text()))

    @POSIX_ONLY
    def test_ignored_stop_signal_is_escalated_and_reaped(self):
        spec = importlib.util.spec_from_file_location("emulator_process", WRAPPER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        ready = self.root / "ignore-ready"
        child = subprocess.Popen(self.child(
            "import pathlib, signal, sys, time; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "pathlib.Path(sys.argv[1]).touch(); time.sleep(5)", str(ready),
        ))
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            with mock.patch.object(module, "STOP_GRACE_SECONDS", 0.1):
                self.assertEqual(-signal.SIGKILL, module._finish_child(child, signal.SIGTERM))
            self.assert_reaped(child.pid)
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)

    def test_launch_script_records_child_and_keeps_the_exact_emulator_arguments(self):
        source = (ROOT / "scripts/m0/emulator.sh").read_text()
        block = source[source.index('diagnostic_arg=""'):source.index('started=$(date +%s)')]
        scripts = self.root / "scripts/m0"
        scripts.mkdir(parents=True)
        (scripts / "emulator_process.py").write_text(WRAPPER.read_text())
        fake = self.root / "fake-emulator"
        fake.write_text(
            "#!" + sys.executable + "\n"
            "import json, os, pathlib, sys\n"
            "pathlib.Path(os.environ['FAKE_ARGV_OUT']).write_text(json.dumps(sys.argv[1:]))\n"
            "raise SystemExit(17)\n"
        )
        fake.chmod(0o700)
        launch = scripts / "launch.sh"
        launch.write_text(
            'set -euo pipefail\nAVD=fixture\nPORT=5560\naccel=off\n'
            'LOG="$PWD/emulator.log"\nEMULATOR="$PWD/fake-emulator"\n' + block +
            '\nprintf "%s\\n" "$emulator_pid" > supervisor.pid\n'
            'result=0\nwait "$emulator_pid" || result=$?\nexit "$result"\n'
        )
        expected = ["@fixture", "-port", "5560", "-no-window", "-no-audio", "-no-boot-anim",
                    "-no-snapshot", "-wipe-data", "-gpu", "swiftshader_indirect",
                    "-camera-back", "none", "-camera-front", "none", "-accel", "off"]
        arguments = self.root / "arguments.json"
        for enabled in ("0", "1"):
            with self.subTest(enabled=enabled):
                result = subprocess.run(
                    ["bash", str(launch)], cwd=self.root, capture_output=True, text=True,
                    env={**os.environ, "COMBRAY_EMULATOR_DIAGNOSTICS": enabled,
                         "FAKE_ARGV_OUT": str(arguments)}, timeout=10,
                )
                self.assertEqual(17, result.returncode, result.stderr)
                self.assertEqual(expected + (["-show-kernel"] if enabled == "1" else []),
                                 json.loads(arguments.read_text()))
                self.assertNotEqual(int((self.root / "supervisor.pid").read_text()),
                                    int(self.pid_file.read_text()))
                self.assertEqual(17, self.metadata()["returncode"])

    def test_metadata_holds_no_command_or_environment(self):
        marker = "marker-secret-1234"
        with mock.patch.dict(os.environ, {"SYNTHETIC_SUPERVISOR_SECRET": marker}):
            result = self.supervise(self.child("import os; raise SystemExit(0)", marker))
        self.assertEqual(result.returncode, 0)
        text = self.exit_file.read_text()
        self.assertNotIn(marker, text)
        self.assertNotIn(sys.executable, text)


if __name__ == "__main__":
    unittest.main()
