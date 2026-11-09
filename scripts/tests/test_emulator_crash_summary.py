"""Deterministic synthetic tests for the bounded emulator crash summary collector."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/m0/emulator_crash_summary.py"


def load_module():
    spec = importlib.util.spec_from_file_location("emulator_crash_summary", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Minidump:
    """Minimal synthetic MDMP builder; never reads or writes a real host dump."""

    def __init__(self):
        self.streams = []
        self.regions = []
        self.stream_rvas = {}
        self.region_rvas = {}

    def add_stream(self, stream_type, data):
        self.streams.append((stream_type, data))

    def add_region(self, tag, data):
        self.regions.append((tag, data))

    def build(self, magic=0x504D444D, version=0xA793):
        count = len(self.streams)
        offset = 32 + 12 * count
        self.region_rvas = {}
        for tag, data in self.regions:
            self.region_rvas[tag] = offset
            offset += len(data)
        resolved = []
        for stream_type, data in self.streams:
            if callable(data):
                data = data(self.region_rvas)
            resolved.append((stream_type, data))
        self.stream_rvas = {}
        for stream_type, data in resolved:
            self.stream_rvas.setdefault(stream_type, []).append(offset)
            offset += len(data)
        cursors = {}
        directory = bytearray()
        for stream_type, data in resolved:
            index = cursors.get(stream_type, 0)
            cursors[stream_type] = index + 1
            directory += struct.pack("<III", stream_type, len(data),
                                     self.stream_rvas[stream_type][index])
        header = struct.pack("<IIII", magic, version, count, 32) + b"\x00" * 16
        body = b"".join(data for _, data in self.regions)
        body += b"".join(data for _, data in resolved)
        return bytes(header + bytes(directory) + body)


def valid_dump(pid=3130, rip=0x7F0000000000, modules=None, exception_code=11,
               context_flags=0x00100001, context_size=256, misc_flags=1,
               misc_size=12, extra_streams=(), magic=0x504D444D, version=0xA793,
               name_length=None, module_count=None):
    if modules is None:
        modules = [(0x7F0000000000, 0x1000, "libgfxstream_backend.so")]
    md = Minidump()
    context = bytearray(max(context_size, 0))
    if context_size >= 256:
        struct.pack_into("<I", context, 48, context_flags)
        struct.pack_into("<Q", context, 248, rip)
    md.add_region("context", bytes(context))
    for index, (_, _, name) in enumerate(modules):
        raw_name = name.encode("utf-16-le") if isinstance(name, str) else bytes(name)
        length = len(raw_name) if name_length is None or index else name_length
        md.add_region("name{}".format(index), struct.pack("<I", length) + raw_name)
    md.add_stream(15, struct.pack("<III", misc_size, misc_flags, pid))

    def exception(rvas):
        raw = bytearray(168)
        struct.pack_into("<I", raw, 8, exception_code)
        struct.pack_into("<II", raw, 160, context_size, rvas["context"])
        return bytes(raw)

    md.add_stream(6, exception)

    def module_list(rvas):
        count = len(modules) if module_count is None else module_count
        raw = bytearray(struct.pack("<I", count))
        for index, (base, size, _) in enumerate(modules):
            entry = struct.pack("<QIIII", base, size, 0, 0, rvas["name{}".format(index)])
            raw += entry + b"\x00" * (108 - 24)
        return bytes(raw)

    md.add_stream(4, module_list)
    for stream_type, data in extra_streams:
        md.add_stream(stream_type, data)
    return md.build(magic=magic, version=version), md


def write_exit(path, pid=3130, returncode=-11, signal=11, stop=None,
               started="2026-11-09T12:12:00Z", ended="2026-11-09T12:12:03Z"):
    record = {"childPID": pid, "startedUtc": started, "endedUtc": ended,
              "returncode": returncode}
    if signal is not None:
        record["signal"] = signal
    if stop is not None:
        record["stopSignal"] = stop
    path.write_text(json.dumps(record) + "\n")


def write_log(path, db_path, pid=3130):
    path.write_text(
        "INFO         | Storing crashdata in: {}, "
        "detection is enabled for process: {}\n".format(db_path, pid))


@unittest.skipUnless(os.name == "posix", "collector filesystem checks require POSIX")
class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        temporary = tempfile.TemporaryDirectory(prefix="sentinel_private_crash_")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.crash_root = self.root / "android-runner"
        self.crash_root.mkdir()
        self.log = self.root / "emulator.log"
        self.exit_file = self.root / "emulator-exit.json"
        self.output = self.root / "summary.json"
        self.pid = 3130
        now = time.time()
        self.started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now - 10))
        self.ended = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now + 5))
        self.db_path = self.crash_root / "emu-crash-37.2.13.db"
        self.db_path.mkdir()
        self.pending = self.db_path / "pending"
        self.completed = self.db_path / "completed"
        self.pending.mkdir()
        self.completed.mkdir()
        write_log(self.log, str(self.db_path), self.pid)
        write_exit(self.exit_file, pid=self.pid, started=self.started, ended=self.ended)
        self.valid_raw, self.md = valid_dump(pid=self.pid)
        self.dump_path = self.pending / "crash.dmp"
        self.dump_path.write_bytes(self.valid_raw)

    def run_cli(self, exit_file=None, log=None, crash_root=None, output=None):
        return self.module.main([
            "--emulator-log", str(log or self.log),
            "--exit-file", str(exit_file or self.exit_file),
            "--crash-root", str(crash_root or self.crash_root),
            "--output", str(output or self.output),
        ])

    def result(self):
        return json.loads(self.output.read_text())

    def patch(self, raw, offset, fmt, *values):
        raw = bytearray(raw)
        struct.pack_into(fmt, raw, offset, *values)
        return bytes(raw)

    def record_reads(self, function, *args, **kwargs):
        reads = []
        real = self.module._pread

        def recording(fd, offset, length):
            reads.append((offset, length))
            return real(fd, offset, length)

        with mock.patch.object(self.module, "_pread", side_effect=recording):
            result = function(*args, **kwargs)
        return result, reads

    def test_valid_dump_uses_rip_and_emits_whitelisted_module(self):
        raw = bytearray(self.valid_raw)
        struct.pack_into("<Q", raw, self.md.stream_rvas[6][0] + 24, 0xDEADBEEF)
        self.dump_path.write_bytes(bytes(raw))
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.result(), {"status": "collected", "summaries": [{
            "exceptionCode": 11, "instructionModule": "libgfxstream_backend.so",
            "pidMatched": True}], "errors": []})

    def test_module_boundaries_multiple_modules_and_unmapped(self):
        modules = [(0x1000, 0x100, "libc.so.6"), (0x2000, 0x100, "libm.so.6")]
        for rip, expected in ((0x1000, "libc.so.6"), (0x10FF, "libc.so.6"),
                              (0x1100, "unmapped"), (0x20FF, "libm.so.6"),
                              (0x2100, "unmapped")):
            with self.subTest(rip=hex(rip)):
                raw, _ = valid_dump(pid=self.pid, modules=modules, rip=rip)
                self.dump_path.write_bytes(raw)
                summary = self.module.parse_dump(str(self.dump_path), self.pid)
                self.assertEqual(summary["instructionModule"], expected)

    def test_unknown_module_name_is_unrecognized_and_never_exported(self):
        raw, _ = valid_dump(pid=self.pid, rip=0x1000,
                            modules=[(0x1000, 0x100, "/tmp/SENTINEL_PATH/secret.so")])
        self.dump_path.write_bytes(raw)
        summary = self.module.parse_dump(str(self.dump_path), self.pid)
        self.assertEqual(summary["instructionModule"], "unrecognized")
        text = json.dumps(summary)
        self.assertNotIn("SENTINEL_PATH", text)
        self.assertNotIn("secret.so", text)

    def test_unsupported_context_flags_and_short_context(self):
        for flags in (0x00100000, 0x1, 0, 0x00500001):
            with self.subTest(flags=hex(flags)):
                raw, _ = valid_dump(pid=self.pid, context_flags=flags)
                self.dump_path.write_bytes(raw)
                summary = self.module.parse_dump(str(self.dump_path), self.pid)
                self.assertEqual(summary["instructionModule"], "unsupported-context")
        raw, _ = valid_dump(pid=self.pid, context_size=255)
        self.dump_path.write_bytes(raw)
        summary = self.module.parse_dump(str(self.dump_path), self.pid)
        self.assertEqual(summary["instructionModule"], "unsupported-context")

    def test_misc_info_pid_binding(self):
        raw, _ = valid_dump(pid=9999)
        self.dump_path.write_bytes(raw)
        with self.assertRaises(self.module.UnboundDumpError):
            self.module.parse_dump(str(self.dump_path), self.pid)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "unbound")
        raw, _ = valid_dump(pid=self.pid, misc_flags=0)
        self.dump_path.write_bytes(raw)
        with self.assertRaises(self.module.UnboundDumpError):
            self.module.parse_dump(str(self.dump_path), self.pid)

    def test_structural_rejections(self):
        raw, md = valid_dump(pid=self.pid)
        cases = [
            ("magic", self.patch(raw, 0, "<I", 0x12345678)),
            ("version", self.patch(raw, 4, "<I", 0x0001A794)),
            ("zero streams", self.patch(raw, 8, "<I", 0)),
            ("too many streams", self.patch(raw, 8, "<I", 129)),
            ("directory oob", self.patch(raw, 12, "<I", len(raw))),
            ("truncated", raw[:20]),
            ("exception size", self.patch(raw, 32 + 12 + 4, "<I", 167)),
            ("exception rva", self.patch(raw, 32 + 12 + 8, "<I", len(raw))),
            ("module count", self.patch(raw, md.stream_rvas[4][0], "<I", 513)),
            ("module list truncated", self.patch(raw, md.stream_rvas[4][0], "<I", 2)),
            ("module name rva", self.patch(raw, md.stream_rvas[4][0] + 24, "<I", len(raw))),
            ("context rva", self.patch(raw, md.stream_rvas[6][0] + 164, "<I", len(raw))),
            ("misc size", self.patch(raw, md.stream_rvas[15][0], "<I", 11)),
        ]
        for label, broken in cases:
            with self.subTest(label=label):
                self.dump_path.write_bytes(broken)
                with self.assertRaises(ValueError):
                    self.module.parse_dump(str(self.dump_path), self.pid)

    def test_duplicate_required_stream_and_overlapping_modules(self):
        raw, _ = valid_dump(pid=self.pid, extra_streams=[(6, b"\x00" * 168)])
        self.dump_path.write_bytes(raw)
        with self.assertRaises(ValueError):
            self.module.parse_dump(str(self.dump_path), self.pid)
        raw, _ = valid_dump(pid=self.pid, rip=0x1000, modules=[
            (0x1000, 0x100, "libc.so.6"), (0x1000, 0x100, "libm.so.6")])
        self.dump_path.write_bytes(raw)
        with self.assertRaises(ValueError):
            self.module.parse_dump(str(self.dump_path), self.pid)

    def test_module_name_encoding_bounds(self):
        for length in (3, 5000):
            with self.subTest(length=length):
                raw, _ = valid_dump(pid=self.pid, name_length=length)
                self.dump_path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    self.module.parse_dump(str(self.dump_path), self.pid)
        raw, _ = valid_dump(pid=self.pid, rip=0x1000,
                            modules=[(0x1000, 0x100, b"\x00\xd8")])
        self.dump_path.write_bytes(raw)
        with self.assertRaises(ValueError):
            self.module.parse_dump(str(self.dump_path), self.pid)

    def test_sentinel_privacy_and_ignored_streams_are_never_read(self):
        extra = [(3, b"SENTINEL_THREAD" * 8), (5, b"SENTINEL_MEMORY" * 8),
                 (0x47670007, b"SENTINEL_ENV" * 8), (0x47670006, b"SENTINEL_CMDLINE" * 8),
                 (10, b"SENTINEL_COMMENT" * 8), (0x43500001, b"SENTINEL_ANNOTATION" * 8)]
        raw, md = valid_dump(pid=self.pid, rip=0x1000, extra_streams=extra,
                             modules=[(0x1000, 0x100, "/tmp/SENTINEL_PATH/secret.so")])
        raw = bytearray(raw)
        for offset in range(0, 241, 8):
            if offset != 48:
                struct.pack_into("<Q", raw, md.region_rvas["context"] + offset, 0x5EEDF00D)
        self.dump_path.write_bytes(bytes(raw))
        summary, reads = self.record_reads(self.module.parse_dump, str(self.dump_path), self.pid)
        self.assertEqual(summary["instructionModule"], "unrecognized")
        ignored = [(md.stream_rvas[stream_type][0],
                    md.stream_rvas[stream_type][0] + len(payload))
                   for stream_type, payload in extra]
        context = md.region_rvas["context"]
        exception = md.stream_rvas[6][0]
        ignored += [(context, context + 48), (context + 52, context + 248),
                    (exception, exception + 8), (exception + 12, exception + 160)]
        for offset, length in reads:
            for start, end in ignored:
                self.assertFalse(offset < end and start < offset + length)
        text = json.dumps(summary)
        for sentinel in ("SENTINEL_PATH", "secret.so", "SENTINEL_THREAD", "SENTINEL_MEMORY",
                         "SENTINEL_ENV", "SENTINEL_CMDLINE", "SENTINEL_COMMENT",
                         "SENTINEL_ANNOTATION", "1592541197", str(self.crash_root)):
            self.assertNotIn(sentinel, text)

    def test_large_sparse_file_is_not_read_whole(self):
        big = b"\x00" * (4 * 1024 * 1024)
        raw, md = valid_dump(pid=self.pid, extra_streams=[(0x47670009, big)])
        big_rva = md.stream_rvas[0x47670009][0]
        with open(self.dump_path, "wb") as handle:
            handle.write(raw[:big_rva])
            handle.seek(len(raw) - 1)
            handle.write(b"\x00")
        summary, reads = self.record_reads(self.module.parse_dump, str(self.dump_path), self.pid)
        total = sum(length for _, length in reads)
        self.assertLessEqual(total, self.module.MAX_DUMP_READ_BYTES)
        self.assertLess(total, len(raw))
        self.assertEqual(summary["instructionModule"], "libgfxstream_backend.so")

    def test_whitelist_modules_are_emitted(self):
        for name in self.module.KNOWN_MODULES:
            with self.subTest(name=name):
                raw, _ = valid_dump(pid=self.pid, modules=[(0x1000, 0x100, name)], rip=0x1000)
                self.dump_path.write_bytes(raw)
                summary = self.module.parse_dump(str(self.dump_path), self.pid)
                self.assertEqual(summary["instructionModule"], name)

    def test_exit_and_log_binding_cases(self):
        old = self.module._parse_utc("2020-01-01T00:00:00Z")
        os.utime(self.dump_path, (old, old))
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-dump")
        started = self.module._parse_utc(self.started)
        os.utime(self.dump_path, (started, started))
        raw, _ = valid_dump(pid=9999)
        self.dump_path.write_bytes(raw)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "unbound")
        self.dump_path.write_bytes(self.valid_raw)
        write_log(self.log, str(self.db_path), 9999)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "unbound")
        write_log(self.log, str(self.db_path), self.pid)
        self.exit_file.unlink()
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-exit")
        write_exit(self.exit_file, pid=self.pid, started=self.started, ended=self.ended)
        self.dump_path.unlink()
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-dump")
        self.dump_path.write_bytes(self.valid_raw)
        write_exit(self.exit_file, returncode=0, signal=None)
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.result()["status"], "no-crash")
        write_exit(self.exit_file, returncode=-15, signal=15, stop=15)
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.result()["status"], "no-crash")
        write_exit(self.exit_file, pid=self.pid, started=self.started, ended=self.ended)

    def test_symlink_dump_is_rejected(self):
        target = self.root / "target.dmp"
        target.write_bytes(self.valid_raw)
        link = self.pending / "link.dmp"
        link.symlink_to(target)
        self.dump_path.unlink()
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-dump")
        with self.assertRaises(OSError):
            self.module.parse_dump(str(link), self.pid)

    def test_foreign_uid_directories_and_dump_are_rejected(self):
        with mock.patch.object(self.module.os, "getuid", return_value=99999):
            self.assertEqual(self.run_cli(), 1)
            self.assertEqual(self.result()["status"], "unbound")
            self.assertIn("ForeignOwnerError", self.result()["errors"])
            with self.assertRaises(self.module.ForeignOwnerError):
                self.module.parse_dump(str(self.dump_path), self.pid)

    def test_enumeration_reports_entry_and_dump_bounds(self):
        entry_root = self.root / "entries"
        entry_root.mkdir()
        (entry_root / "pending").mkdir()
        (entry_root / "completed").mkdir()
        for index in range(65):
            (entry_root / "pending" / "f{}.txt".format(index)).write_text("x")
        candidates, errors = self.module._enumerate_dumps(str(entry_root), 0, time.time() + 5)
        self.assertEqual(candidates, [])
        self.assertIn("EntryLimitError", errors)
        dump_root = self.root / "bounds"
        dump_root.mkdir()
        (dump_root / "pending").mkdir()
        (dump_root / "completed").mkdir()
        for index in range(9):
            raw, _ = valid_dump(pid=self.pid)
            (dump_root / "pending" / "d{}.dmp".format(index)).write_bytes(raw)
        candidates, errors = self.module._enumerate_dumps(str(dump_root), 0, time.time() + 5)
        self.assertEqual(len(candidates), 8)
        self.assertIn("DumpLimitError", errors)

    def test_cli_reports_dump_limit_and_still_collects(self):
        dump_root = self.root / "cli-bounds"
        dump_root.mkdir()
        db = dump_root / "emu-crash-1.0.db"
        db.mkdir()
        (db / "pending").mkdir()
        (db / "completed").mkdir()
        for index in range(9):
            raw, _ = valid_dump(pid=self.pid)
            (db / "pending" / "d{}.dmp".format(index)).write_bytes(raw)
        write_log(self.log, str(db), self.pid)
        self.assertEqual(self.run_cli(crash_root=dump_root), 1)
        record = self.result()
        self.assertEqual(record["status"], "collected")
        self.assertEqual(len(record["summaries"]), 8)
        self.assertIn("DumpLimitError", record["errors"])

    def test_cli_output_contract_and_class_only_stderr(self):
        stderr, stdout = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
            code = self.run_cli()
        self.assertEqual(code, 0)
        record = self.result()
        self.assertEqual(set(record), {"status", "summaries", "errors"})
        self.assertEqual(record["status"], "collected")
        self.assertEqual(record["errors"], [])
        summary = record["summaries"][0]
        self.assertEqual(set(summary), {"exceptionCode", "instructionModule", "pidMatched"})
        self.assertIsInstance(summary["exceptionCode"], int)
        self.assertNotIsInstance(summary["exceptionCode"], bool)
        self.assertIn(summary["instructionModule"], self.module.KNOWN_MODULES)
        self.assertIs(summary["pidMatched"], True)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(stdout.getvalue(), "")
        text = self.output.read_text()
        for sentinel in ("sentinel_private_crash_", str(self.crash_root), str(self.log),
                         str(self.exit_file)):
            self.assertNotIn(sentinel, text)

    def test_cli_error_stderr_is_class_only(self):
        self.exit_file.unlink()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = self.run_cli()
        self.assertEqual(code, 1)
        self.assertEqual(stderr.getvalue().splitlines(), ["FileNotFoundError"])
        self.assertNotIn(str(self.root), stderr.getvalue())

    def test_invalid_exit_records(self):
        self.exit_file.write_text("x" * 4097)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-exit")
        self.exit_file.write_text("{not json")
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-exit")
        self.exit_file.write_text("[]")
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-exit")
        write_exit(self.exit_file, pid=0)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-child")
        for signal in (15, None):
            write_exit(self.exit_file, returncode=-11, signal=signal)
            self.assertEqual(self.run_cli(), 1)
            self.assertEqual(self.result()["status"], "no-exit")
        write_exit(self.exit_file, pid=self.pid, started=self.started, ended=self.ended)

    def test_missing_log_is_unbound(self):
        self.log.unlink()
        self.assertEqual(self.run_cli(), 1)
        record = self.result()
        self.assertEqual(record["status"], "unbound")
        self.assertEqual(record["errors"], ["FileNotFoundError"])

    def test_log_read_is_bounded_to_header_prefix(self):
        self.log.write_text("☃" * (self.module.LOG_MAX_BYTES // 3 + 1)
                            + "\nINFO | Storing crashdata in: {}, "
                            "detection is enabled for process: {}\n".format(self.db_path, self.pid))
        self.assertEqual(self.run_cli(), 1)
        record = self.result()
        self.assertEqual(record["status"], "unbound")
        self.assertEqual(record["errors"], ["DetectionHeaderError"])

    def test_detection_path_validation(self):
        cases = ["relative/emu-crash-1.0.db",
                 str(self.crash_root / ".." / "android-runner" / "emu-crash-1.0.db"),
                 str(self.root / "outside" / "emu-crash-1.0.db"),
                 str(self.crash_root / "not-a-db.txt")]
        for path in cases:
            with self.subTest(path=path):
                write_log(self.log, path, self.pid)
                self.assertEqual(self.run_cli(), 1)
                self.assertEqual(self.result()["status"], "unbound")
        link = self.crash_root / "emu-crash-1.0.db"
        link.symlink_to(self.db_path)
        write_log(self.log, str(link), self.pid)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "unbound")
        write_log(self.log, str(self.db_path), self.pid)

    def test_metadata_cannot_point_into_an_ignored_payload(self):
        payload = bytearray(256)
        struct.pack_into("<I", payload, 48, 0x00100001)
        struct.pack_into("<Q", payload, 248, 0x7F0000000000)
        raw, md = valid_dump(pid=self.pid, extra_streams=[(0x47670007, bytes(payload))])
        start = md.stream_rvas[0x47670007][0]
        raw = self.patch(raw, md.stream_rvas[6][0] + 164, "<I", start)
        self.dump_path.write_bytes(raw)
        reads = []
        original = self.module._pread

        def checked(fd, offset, length):
            reads.append((offset, length))
            self.assertFalse(offset < start + 256 and start < offset + length)
            return original(fd, offset, length)

        with mock.patch.object(self.module, "_pread", checked), self.assertRaises(ValueError):
            self.module.parse_dump(str(self.dump_path), self.pid)

    def test_supervisor_cleanup_is_not_attributed_to_an_unassisted_crash(self):
        record = json.loads(self.exit_file.read_text())
        record["error"] = "SENTINEL_PRIVATE_ERROR"
        self.exit_file.write_text(json.dumps(record))
        with mock.patch.object(self.module, "_read_detection_path",
                               side_effect=AssertionError("must not inspect dumps")):
            self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "supervisor-error")
        self.assertNotIn("SENTINEL", self.output.read_text())

    def test_file_size_cap_is_enforced_before_reading(self):
        path = self.crash_root / "huge.dmp"
        with open(path, "wb") as handle:
            handle.seek((1 << 30) + 1)
            handle.write(b"\x00")
        with self.assertRaises(ValueError):
            self.module.parse_dump(str(path), self.pid)

    def test_parse_dump_rejects_directories(self):
        with self.assertRaises(self.module.UnsafePathError):
            self.module.parse_dump(str(self.crash_root), self.pid)

    def test_output_write_failure_is_class_only(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = self.run_cli(output=self.root / "missing" / "out.json")
        self.assertEqual(code, 1)
        self.assertEqual(stderr.getvalue().strip(), "FileNotFoundError")

    def test_cli_parse_errors_are_class_only(self):
        for arguments in (["--emulator-log", "SENTINEL_PRIVATE_PATH"],
                          ["SENTINEL_PRIVATE_ARGUMENT"]):
            with self.subTest(arguments=arguments):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    self.assertEqual(self.module.main(arguments), 1)
                self.assertEqual(stderr.getvalue().strip(), "ValueError")
                self.assertNotIn("SENTINEL", stderr.getvalue())

    def test_only_the_header_matched_database_is_scanned(self):
        (self.crash_root / "loose.dmp").write_bytes(b"not a dump")
        sibling = self.crash_root / "emu-crash-99.0.db"
        sibling.mkdir()
        (sibling / "unrelated.dmp").write_bytes(b"not a dump")
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(len(self.result()["summaries"]), 1)
        self.assertEqual(self.result()["errors"], [])

    def test_completed_state_is_supported(self):
        self.dump_path.rename(self.completed / "finished.dmp")
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(len(self.result()["summaries"]), 1)

    def test_guest_text_cannot_supply_a_detection_header(self):
        self.log.write_text("guest text: INFO | Storing crashdata in: {}, "
                            "detection is enabled for process: {}\n".format(self.db_path, self.pid))
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "unbound")

    def test_symlinked_pending_directory_is_rejected(self):
        real = self.db_path / "saved-pending"
        self.pending.rename(real)
        self.pending.symlink_to(real)
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.result()["status"], "no-dump")
        self.assertIn("UnsafePathError", self.result()["errors"])


if __name__ == "__main__":
    unittest.main()
