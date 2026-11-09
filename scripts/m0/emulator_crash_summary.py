#!/usr/bin/env python3
"""Bounded read-only emulator crash summary collector.

    python3 -I scripts/m0/emulator_crash_summary.py --emulator-log PATH --exit-file PATH \
        --crash-root PATH --output PATH

The exit record is data, never authority to signal anything. The collector confirms the emulator
crash-detection header names the same owned child, enumerates only fresh ``*.dmp`` children of
that database's pending/completed directories, and reads bounded Exception/ModuleList/MiscInfo
metadata plus the AMD64 control flag and instruction pointer. It emits only numeric exception
codes, fixed whitelist module literals, binding status and class-name errors. It never emits
paths or addresses and never reads stack, memory, environment, command-line or attachment data.

Status values: collected, no-exit, no-child, no-crash, supervisor-error, no-dump, unbound,
no-owned-crash, error.
"""

import argparse
import json
import os
import re
import stat
import struct
import sys
from datetime import datetime, timedelta

EXIT_MAX_BYTES = 4096
LOG_MAX_BYTES = 128 * 1024
MAX_ENTRIES = 64
MAX_DUMPS = 8
MAX_FILE_BYTES = 1 << 30
MAX_DUMP_READ_BYTES = 1 << 20
MAX_STREAMS = 128
MAX_MODULES = 512
MAX_NAME_BYTES = 4096
MDMP_SIGNATURE = 0x504D444D
MDMP_VERSION = 0xA793
STREAM_MODULE_LIST = 4
STREAM_EXCEPTION = 6
STREAM_MISC_INFO = 15
MD_MODULE_SIZE = 108
# google/breakpad minidump_format.h and minidump_cpu_amd64.h define these layouts.
MD_CONTEXT_AMD64 = 0x00100000
MD_CONTEXT_CPU_MASK = 0xFFFFFF00
MD_CONTEXT_CONTROL = 0x1
MISC_INFO_PROCESS_ID = 0x1
DETECTION_MARKER = "Storing crashdata in: "
DETECTION_SUFFIX = ", detection is enabled for process: "
DB_NAME = re.compile(r"^emu-crash-\d+(?:\.\d+)*\.db$")
UTC_TEXT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|\+00:00)$")
KNOWN_MODULES = frozenset((
    "emulator", "qemu-system-x86_64", "crashpad_handler", "libgfxstream_backend.so",
    "libOpenglRender.so", "libvulkan.so", "libvulkan.so.1", "libvk_swiftshader.so",
    "libGLESv2.so", "libEGL.so", "libc.so.6", "libpthread.so.0", "libstdc++.so.6",
    "libgcc_s.so.1", "libm.so.6", "libdl.so.2", "libQt6CoreAndroidEmu.so.6",
    "libQt6GuiAndroidEmu.so.6", "libQt6WidgetsAndroidEmu.so.6",
))
UNMAPPED = "unmapped"
UNRECOGNIZED = "unrecognized"
UNSUPPORTED_CONTEXT = "unsupported-context"


class SummaryError(ValueError):
    """Explicit collector failure reported only as a class name."""


class NoChildError(SummaryError): pass
class UnsafePathError(SummaryError): pass
class ForeignOwnerError(SummaryError): pass
class UnboundDumpError(SummaryError): pass
class EntryLimitError(SummaryError): pass
class DumpLimitError(SummaryError): pass
class DetectionHeaderError(SummaryError): pass


def _pread(fd, offset, length):
    if hasattr(os, "pread"):
        return os.pread(fd, length, offset)
    os.lseek(fd, offset, os.SEEK_SET)
    return os.read(fd, length)


class _BoundedReader:
    """Read fixed ranges from one open dump, never more than the byte budget."""

    def __init__(self, fd, file_size):
        self.fd = fd
        self.file_size = file_size
        self.bytes_read = 0
        self.forbidden_ranges = ()

    def read_at(self, offset, length):
        if offset < 0 or length < 0:
            raise ValueError("negative read range")
        if offset + length > self.file_size:
            raise ValueError("read past end of dump")
        if self.bytes_read + length > MAX_DUMP_READ_BYTES:
            raise ValueError("dump read budget exceeded")
        if any(offset < end and start < offset + length
               for start, end in self.forbidden_ranges):
            raise ValueError("metadata references an ignored stream")
        data = _pread(self.fd, offset, length)
        if len(data) != length:
            raise ValueError("short read")
        self.bytes_read += length
        return data


def _current_uid():
    getuid = getattr(os, "getuid", None)
    return getuid() if getuid is not None else None


def _open_dump(path):
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise UnsafePathError("dump is not a regular file")
        uid = _current_uid()
        if uid is not None and info.st_uid != uid:
            raise ForeignOwnerError("dump has a foreign owner")
        if info.st_size > MAX_FILE_BYTES:
            raise ValueError("dump exceeds size cap")
        return fd, info.st_size
    except Exception:
        os.close(fd)
        raise


def _read_header(reader):
    header = reader.read_at(0, 32)
    signature, version, stream_count, dir_rva = struct.unpack_from("<IIII", header, 0)
    if signature != MDMP_SIGNATURE:
        raise ValueError("bad minidump signature")
    if version & 0xFFFF != MDMP_VERSION:
        raise ValueError("bad minidump version")
    if stream_count == 0 or stream_count > MAX_STREAMS:
        raise ValueError("invalid stream count")
    if dir_rva > reader.file_size or 12 * stream_count > reader.file_size - dir_rva:
        raise ValueError("stream directory out of bounds")
    return stream_count, dir_rva


def _read_directory(reader, stream_count, dir_rva):
    raw = reader.read_at(dir_rva, 12 * stream_count)
    entries = []
    for index in range(stream_count):
        stream_type, size, rva = struct.unpack_from("<III", raw, index * 12)
        if size > reader.file_size or rva > reader.file_size - size:
            raise ValueError("stream out of bounds")
        entries.append((stream_type, size, rva))
    return entries


def _required_stream(entries, stream_type):
    matches = [entry for entry in entries if entry[0] == stream_type]
    if not matches:
        raise ValueError("missing required stream")
    if len(matches) > 1:
        raise ValueError("duplicate required stream")
    return matches[0]


def _read_misc_info(reader, entry, owned_pid):
    _, size, rva = entry
    if size < 12:
        raise ValueError("misc info too small")
    sizeof_info, flags1, process_id = struct.unpack("<III", reader.read_at(rva, 12))
    if sizeof_info < 12 or sizeof_info > size:
        raise ValueError("invalid misc info size")
    if not flags1 & MISC_INFO_PROCESS_ID:
        raise UnboundDumpError("misc info has no process id")
    if process_id != owned_pid:
        raise UnboundDumpError("misc info process id mismatch")


def _read_exception(reader, entry):
    _, size, rva = entry
    if size < 168:
        raise ValueError("exception stream too small")
    # Skip the fault address and exception parameters, which may contain private values.
    exception_code = struct.unpack("<I", reader.read_at(rva + 8, 4))[0]
    context_size, context_rva = struct.unpack("<II", reader.read_at(rva + 160, 8))
    return exception_code, context_size, context_rva


def _read_context(reader, context_size, context_rva):
    if context_rva > reader.file_size or context_size > reader.file_size - context_rva:
        raise ValueError("context out of bounds")
    if context_size < 256:
        return None
    flags = struct.unpack("<I", reader.read_at(context_rva + 48, 4))[0]
    if flags & MD_CONTEXT_CPU_MASK != MD_CONTEXT_AMD64 or not flags & MD_CONTEXT_CONTROL:
        return None
    return struct.unpack("<Q", reader.read_at(context_rva + 248, 8))[0]


def _read_modules(reader, entry, rip):
    _, size, rva = entry
    if size < 4:
        raise ValueError("module list too small")
    count = struct.unpack("<I", reader.read_at(rva, 4))[0]
    if count > MAX_MODULES:
        raise ValueError("invalid module count")
    if size < 4 + count * MD_MODULE_SIZE:
        raise ValueError("module list truncated")
    matches = []
    for index in range(count):
        raw = reader.read_at(rva + 4 + index * MD_MODULE_SIZE, 24)
        base, module_size = struct.unpack_from("<QI", raw, 0)
        name_rva = struct.unpack_from("<I", raw, 20)[0]
        if base + module_size > 1 << 64:
            raise ValueError("module address range overflows")
        if base <= rip < base + module_size:
            matches.append(name_rva)
    if not matches:
        return UNMAPPED
    if len(matches) > 1:
        raise ValueError("overlapping modules")
    basename = _read_module_name(reader, matches[0])
    return basename if basename in KNOWN_MODULES else UNRECOGNIZED


def _read_module_name(reader, name_rva):
    if name_rva > reader.file_size or 4 > reader.file_size - name_rva:
        raise ValueError("module name out of bounds")
    length = struct.unpack("<I", reader.read_at(name_rva, 4))[0]
    if length > MAX_NAME_BYTES or length % 2:
        raise ValueError("invalid module name length")
    if length == 0:
        return ""
    if length > reader.file_size - name_rva - 4:
        raise ValueError("module name out of bounds")
    raw = reader.read_at(name_rva + 4, length)
    try:
        text = raw.decode("utf-16-le", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValueError("invalid module name encoding") from exc
    if any(0xD800 <= ord(character) <= 0xDFFF for character in text):
        raise ValueError("invalid module name encoding")
    return text.rsplit("/", 1)[-1]


def parse_dump(path, owned_pid):
    """Return the safe summary for one dump, or raise a class-only failure."""
    fd, file_size = _open_dump(path)
    try:
        reader = _BoundedReader(fd, file_size)
        stream_count, dir_rva = _read_header(reader)
        entries = _read_directory(reader, stream_count, dir_rva)
        reader.forbidden_ranges = tuple(
            (rva, rva + size) for kind, size, rva in entries
            if kind not in (STREAM_EXCEPTION, STREAM_MODULE_LIST, STREAM_MISC_INFO)
        )
        misc_entry = _required_stream(entries, STREAM_MISC_INFO)
        exception_entry = _required_stream(entries, STREAM_EXCEPTION)
        module_entry = _required_stream(entries, STREAM_MODULE_LIST)
        _read_misc_info(reader, misc_entry, owned_pid)
        exception_code, context_size, context_rva = _read_exception(reader, exception_entry)
        rip = _read_context(reader, context_size, context_rva)
        module = UNSUPPORTED_CONTEXT if rip is None else _read_modules(reader, module_entry, rip)
        return {"exceptionCode": exception_code, "instructionModule": module,
                "pidMatched": True}
    finally:
        os.close(fd)


def _parse_utc(value):
    if not isinstance(value, str) or not UTC_TEXT.match(value):
        raise ValueError("invalid UTC timestamp")
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exc:
        raise ValueError("invalid UTC timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise ValueError("timestamp is not UTC")
    return parsed.timestamp()


def _read_exit_record(path):
    info = os.stat(path)
    if info.st_size > EXIT_MAX_BYTES:
        raise ValueError("exit record too large")
    with open(path, "rb") as handle:
        raw = handle.read(EXIT_MAX_BYTES + 1)
    if len(raw) > EXIT_MAX_BYTES:
        raise ValueError("exit record too large")
    record = json.loads(raw)
    if not isinstance(record, dict):
        raise ValueError("exit record is not an object")
    child_pid = record.get("childPID")
    if isinstance(child_pid, bool) or not isinstance(child_pid, int) or child_pid <= 0:
        raise NoChildError("invalid childPID")
    started = _parse_utc(record.get("startedUtc"))
    ended = _parse_utc(record.get("endedUtc"))
    if started > ended:
        raise ValueError("started after ended")
    returncode = record.get("returncode")
    if isinstance(returncode, bool) or not isinstance(returncode, int):
        raise ValueError("invalid returncode")
    signal = record.get("signal")
    if returncode < 0:
        if isinstance(signal, bool) or not isinstance(signal, int) or signal != -returncode:
            raise ValueError("inconsistent signal")
    elif signal is not None:
        raise ValueError("signal on a normal exit")
    stop_signal = record.get("stopSignal")
    if stop_signal is not None and (isinstance(stop_signal, bool)
                                    or not isinstance(stop_signal, int)):
        raise ValueError("invalid stopSignal")
    return {"childPID": child_pid, "started": started, "ended": ended,
            "returncode": returncode, "stopSignal": stop_signal,
            "supervisorError": record.get("error") is not None}


def _read_detection_path(log_path, child_pid):
    with open(log_path, "rb") as handle:
        text = handle.read(LOG_MAX_BYTES).decode("utf-8", errors="replace")
    prefix = re.compile(r"^INFO[ \t]*\|[ \t]*" + re.escape(DETECTION_MARKER))
    for line in text.splitlines():
        header = prefix.match(line)
        if header is None:
            continue
        rest = line[header.end():]
        suffix = rest.find(DETECTION_SUFFIX)
        if suffix < 0:
            continue
        path = rest[:suffix].strip()
        pid_text = rest[suffix + len(DETECTION_SUFFIX):].strip()
        if pid_text.isdigit() and int(pid_text) == child_pid:
            return path
    raise DetectionHeaderError("no matching crash detection header")


def _validate_detection_path(path, crash_root):
    if not path or not os.path.isabs(path):
        raise UnsafePathError("detection path is not absolute")
    if any(part in (".", "..") for part in path.split("/")):
        raise UnsafePathError("detection path has traversal")
    if os.path.islink(path):
        raise UnsafePathError("detection path is a symlink")
    root_real = os.path.realpath(crash_root)
    path_real = os.path.realpath(path)
    if os.path.dirname(path_real) != root_real:
        raise UnsafePathError("detection path is outside the crash root")
    if not DB_NAME.fullmatch(os.path.basename(path_real)):
        raise UnsafePathError("unexpected crash data basename")
    for directory in (crash_root, path):
        _owned_directory(directory)
    return path_real


def _owned_directory(path):
    if os.path.islink(path):
        raise UnsafePathError("directory is a symlink")
    info = os.stat(path, follow_symlinks=False)
    if not stat.S_ISDIR(info.st_mode):
        raise UnsafePathError("not a directory")
    uid = _current_uid()
    if uid is not None and info.st_uid != uid:
        raise ForeignOwnerError("directory has a foreign owner")


def _enumerate_dumps(database, started, ended):
    _owned_directory(database)
    uid = _current_uid()
    candidates = []
    errors = []
    scanned = 0
    # Crashpad finalizes new/*.dmp into pending/, then may move it to completed/.
    # Never inspect new/, attachments, sibling databases or loose files in the temp root.
    for state in ("pending", "completed"):
        directory = os.path.join(database, state)
        _owned_directory(directory)
        with os.scandir(directory) as iterator:
            for entry in iterator:
                scanned += 1
                if scanned > MAX_ENTRIES:
                    errors.append(EntryLimitError.__name__)
                    return sorted(candidates)[:MAX_DUMPS], errors
                try:
                    if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                        continue
                    if not entry.name.endswith(".dmp"):
                        continue
                    info = entry.stat(follow_symlinks=False)
                except OSError as exc:
                    errors.append(type(exc).__name__)
                    continue
                if uid is not None and info.st_uid != uid:
                    continue
                if not started <= info.st_mtime <= ended + 5:
                    continue
                candidates.append(entry.path)
    candidates.sort()
    if len(candidates) > MAX_DUMPS:
        candidates = candidates[:MAX_DUMPS]
        errors.append(DumpLimitError.__name__)
    return candidates, errors


def summarize(emulator_log, exit_file, crash_root):
    """Collect bounded summaries and class-name errors; never raise for input failures."""
    try:
        record = _read_exit_record(exit_file)
    except NoChildError as exc:
        return {"status": "no-child", "summaries": [], "errors": [type(exc).__name__]}
    except Exception as exc:
        return {"status": "no-exit", "summaries": [], "errors": [type(exc).__name__]}
    if record["supervisorError"]:
        return {"status": "supervisor-error", "summaries": [], "errors": []}
    if record["returncode"] >= 0 or record["stopSignal"] is not None:
        return {"status": "no-crash", "summaries": [], "errors": []}
    try:
        database = _validate_detection_path(
            _read_detection_path(emulator_log, record["childPID"]), crash_root)
    except Exception as exc:
        return {"status": "unbound", "summaries": [], "errors": [type(exc).__name__]}
    try:
        candidates, errors = _enumerate_dumps(database, record["started"], record["ended"])
    except Exception as exc:
        return {"status": "no-dump", "summaries": [], "errors": [type(exc).__name__]}
    if not candidates:
        return {"status": "no-dump", "summaries": [], "errors": errors}
    summaries = []
    unbound = False
    for path in candidates:
        try:
            summaries.append(parse_dump(path, record["childPID"]))
        except UnboundDumpError:
            unbound = True
        except Exception as exc:
            errors.append(type(exc).__name__)
    if summaries:
        return {"status": "collected", "summaries": summaries, "errors": errors}
    if unbound:
        return {"status": "unbound", "summaries": [], "errors": errors}
    return {"status": "no-owned-crash", "summaries": [], "errors": errors}


def _write_output(path, record):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(record, sort_keys=True) + "\n")


class _ClassOnlyParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes rejected values, which may contain private paths or input.
        raise ValueError("invalid arguments")


def _parse_arguments(argv):
    parser = _ClassOnlyParser(
        description="Bounded read-only emulator crash summary collector.")
    parser.add_argument("--emulator-log", required=True)
    parser.add_argument("--exit-file", required=True)
    parser.add_argument("--crash-root", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args(argv)


def main(argv=None):
    try:
        args = _parse_arguments(argv)
    except Exception as exc:
        print(type(exc).__name__, file=sys.stderr)
        return 1
    try:
        record = summarize(args.emulator_log, args.exit_file, args.crash_root)
    except Exception as exc:
        record = {"status": "error", "summaries": [], "errors": [type(exc).__name__]}
    try:
        _write_output(args.output, record)
    except Exception as exc:
        print(type(exc).__name__, file=sys.stderr)
        return 1
    for error in record["errors"]:
        print(error, file=sys.stderr)
    if record["errors"] or record["status"] not in ("collected", "no-crash"):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
