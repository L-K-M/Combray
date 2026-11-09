"""Deterministic tests for the bounded host telemetry snapshot."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
CLASS_NAMES = {"FileNotFoundError", "ValueError", "OSError"}

def load_module():
    spec = importlib.util.spec_from_file_location("host_diagnostics", ROOT / "scripts/m0/host_diagnostics.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        temporary = tempfile.TemporaryDirectory(prefix="host diagnostics ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.proc = self.root / "proc"
        self.cgroup = self.root / "cgroup"
        self.proc.mkdir()
        self.cgroup.mkdir()

    def snapshot(self):
        return self.module.snapshot(str(self.proc), str(self.cgroup))

    def add_process(self, pid, comm, stat=None, status=None):
        directory = self.proc / str(pid)
        directory.mkdir()
        (directory / "comm").write_text(comm + "\n")
        if stat is not None:
            (directory / "stat").write_text(stat)
        if status is not None:
            (directory / "status").write_text(status)

    def test_meminfo_counters_and_absent_ones_stay_null(self):
        (self.proc / "meminfo").write_text(
            "MemTotal: 16000 kB\nMemFree: 1000 kB\nMemAvailable: 2000 kB\nSwapTotal: 0 kB\n"
            "SwapFree: 0 kB\nDirty: 10 kB\nWriteback: 0 kB\nSlab: 500 kB\n")
        self.assertEqual(self.snapshot()["meminfo"]["Slab"], 500)
        (self.proc / "meminfo").write_text("MemTotal: 16000 kB\n")
        meminfo = self.snapshot()["meminfo"]
        self.assertIsNone(meminfo["MemFree"])
        self.assertIsNone(meminfo["Dirty"])

    def test_emulator_and_qemu_processes_are_parsed_after_final_parenthesis(self):
        stat = "1 (emulator (x86)) S " + " ".join(["1"] * 18) + " 12345\n"
        status = ("Name:\temulator\nState:\tS (sleeping)\nVmRSS: 2048 kB\nVmHWM: 4096 kB\n"
                  "VmSize: 100000 kB\nRssAnon: 1024 kB\nRssFile: 512 kB\nThreads: 7\n")
        for pid, comm in ((200, "emulator"), (201, "qemu-system-x86_64"),
                          (202, "emulator64"), (203, "bash")):
            self.add_process(pid, comm, stat, status)
        processes = self.snapshot()["processes"]
        self.assertEqual([process["pid"] for process in processes], [200, 201])
        self.assertEqual([processes[0]["starttime_ticks"], processes[0]["state"],
                          processes[0]["VmRSS"], processes[0]["Threads"]],
                         [12345, "S (sleeping)", 2048, 7])
        self.assertEqual(processes[1]["comm"], "qemu-system-x86_64")

    def test_vanished_process_is_explicit_without_crashing(self):
        self.add_process(300, "qemu-system-x86_64")
        (self.proc / "301").mkdir()
        record = self.snapshot()
        self.assertEqual([record["processes"][0]["starttime_ticks"], record["process_errors"],
                          record["processes"][0]["errors"]],
                         [None, ["FileNotFoundError"], ["FileNotFoundError", "FileNotFoundError"]])

    def test_cgroup_root_and_self_counters_are_captured(self):
        for name, text in (("memory.events", "low 0\nhigh 1\nmax 0\noom 2\n"),
                           ("memory.current", "1048576\n"), ("memory.peak", "2097152\n"),
                           ("memory.max", "max\n")):
            (self.cgroup / name).write_text(text)
        (self.proc / "self").mkdir()
        (self.proc / "self" / "cgroup").write_text("0::/user.slice/emulator\n")
        nested = self.cgroup / "user.slice" / "emulator"
        nested.mkdir(parents=True)
        for name, text in (("memory.events", "oom 3\n"), ("memory.current", "524288\n"),
                           ("memory.peak", "max\n"), ("memory.max", "4194304\n")):
            (nested / name).write_text(text)
        cgroup = self.snapshot()["cgroup"]
        self.assertEqual([cgroup["root"]["current"], cgroup["root"]["max"],
                          cgroup["root"]["events"]["oom"]], [1048576, "max", 2])
        self.assertEqual([cgroup["self"]["path"], cgroup["self"]["current"],
                          cgroup["self"]["peak"], cgroup["self"]["max"]],
                         ["/user.slice/emulator", 524288, "max", 4194304])

    def test_missing_files_are_null_not_zero(self):
        record = self.snapshot()
        self.assertEqual([record["cgroup"]["root"]["current"], record["cgroup"]["root"]["max"],
                          record["cgroup"]["root"]["events"], record["cgroup"]["self"]["current"]],
                         [None, None, {}, None])
        self.assertIn("FileNotFoundError", record["cgroup"]["root"]["errors"])
        self.assertIn("FileNotFoundError", record["cgroup"]["self"]["errors"])

    def test_unsafe_self_cgroup_path_is_rejected(self):
        (self.root / "outside").mkdir()
        (self.root / "outside" / "memory.current").write_text("999999\n")
        (self.proc / "self").mkdir()
        (self.proc / "self" / "cgroup").write_text("0::/../../outside\n")
        record = self.snapshot()
        self.assertIsNone(record["cgroup"]["self"]["current"])
        self.assertIn("ValueError", record["cgroup"]["self"]["errors"])
        self.assertNotIn("999999", json.dumps(record))

    def test_malformed_files_report_class_names_only(self):
        (self.proc / "meminfo").write_text("MemTotal: not-a-number kB\n")
        self.add_process(400, "qemu", stat="400 (qemu\n", status="VmRSS: abc kB\n")
        (self.cgroup / "memory.events").write_text("low nope\n")
        (self.cgroup / "memory.max").write_text("unlimited\n")
        record = self.snapshot()
        self.assertNotIn(str(self.root), json.dumps(record))
        errors = (record["meminfo_errors"] + record["processes"][0]["errors"]
                  + record["cgroup"]["root"]["errors"])
        for error in errors:
            self.assertIn(error, CLASS_NAMES)
        self.assertEqual([record["meminfo"]["MemTotal"], record["processes"][0]["starttime_ticks"],
                          record["cgroup"]["root"]["max"]], [None, None, None])

    def test_read_closes_its_file(self):
        handle = io.StringIO("sample")
        with mock.patch("builtins.open", return_value=handle):
            self.assertEqual("sample", self.module._read("unused"))
        self.assertTrue(handle.closed)

    def test_v1_entries_do_not_hide_the_v2_self_path(self):
        (self.proc / "self").mkdir()
        (self.proc / "self/cgroup").write_text("5:cpu:/legacy\n0::/unified\n")
        nested = self.cgroup / "unified"
        nested.mkdir()
        (nested / "memory.current").write_text("42\n")
        self.assertEqual(42, self.snapshot()["cgroup"]["self"]["current"])

    def test_cli_duration_and_interval_are_bounded(self):
        for interval, duration in [("61", "100"), ("2", "9001")]:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.module._parse_arguments([
                    "--output", "unused", "--interval-seconds", interval,
                    "--duration-seconds", duration,
                ])

    def test_cli_rejects_invalid_interval_and_duration(self):
        output = str(self.root / "out.json")
        for interval, duration in (("0", "10"), ("-1", "10"), ("nan", "10"), ("inf", "10"), ("2", "1")):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.module._parse_arguments(
                    ["--output", output, "--interval-seconds", interval, "--duration-seconds", duration])
