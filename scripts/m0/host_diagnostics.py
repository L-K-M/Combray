#!/usr/bin/env python3
"""Bounded read-only host telemetry for M0 emulator diagnostics.
    python3 scripts/m0/host_diagnostics.py --output PATH --interval-seconds 2 --duration-seconds 9000
One JSON snapshot per interval, initial and final included. Reads only /proc and cgroup-v2 files;
no process is started or signalled, no subprocess/network/ADB, no cmdline/environ reads. Absent
or racing files are explicit nulls and class-name errors, never fabricated zeros.
"""

import argparse
import json
import math
import os
import signal
import sys
import threading
import time

MEMINFO_COUNTERS = ("MemTotal", "MemFree", "MemAvailable", "SwapTotal", "SwapFree", "Dirty", "Writeback", "Slab")
STATUS_COUNTERS = ("VmRSS", "VmHWM", "VmSize", "RssAnon", "RssFile", "Threads")
MIN_INTERVAL_SECONDS = 0.1
MAX_INTERVAL_SECONDS = 60
MAX_DURATION_SECONDS = 9_000

def _read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()

def _record(errors, exc):
    errors.append(type(exc).__name__)
def _key_values(text, wanted, errors, strings=()):
    values = {key: None for key in wanted}
    for line in text.splitlines():
        key, separator, rest = line.partition(":")
        key = key.strip()
        if not separator or key not in values:
            continue
        if key in strings:
            values[key] = rest.strip() or None
            continue
        parts = rest.split()
        if not parts:
            continue
        try:
            values[key] = int(parts[0])
        except ValueError as exc:
            _record(errors, exc)
    return values

def _meminfo(proc_root):
    errors = []
    try:
        text = _read(os.path.join(proc_root, "meminfo"))
    except (OSError, ValueError) as exc:
        _record(errors, exc)
        text = ""
    return _key_values(text, MEMINFO_COUNTERS, errors), errors

def _stat_starttime(proc_dir, errors):
    try:
        text = _read(os.path.join(proc_dir, "stat"))
    except (OSError, ValueError) as exc:
        _record(errors, exc)
        return None
    close = text.rfind(")")
    fields = text[close + 1:].split() if close >= 0 else []
    if len(fields) < 20:
        _record(errors, ValueError())
        return None
    try:
        return int(fields[19])
    except ValueError as exc:
        _record(errors, exc)
        return None

def _processes(proc_root):
    found, errors = [], []
    try:
        entries = sorted((entry for entry in os.listdir(proc_root) if entry.isdigit()), key=int)
    except OSError as exc:
        _record(errors, exc)
        return found, errors
    for entry in entries:
        proc_dir = os.path.join(proc_root, entry)
        try:
            comm = _read(os.path.join(proc_dir, "comm")).strip()
        except (OSError, ValueError) as exc:
            _record(errors, exc)
            continue
        if comm != "emulator" and not comm.startswith("qemu"):
            continue
        record_errors = []
        try:
            text = _read(os.path.join(proc_dir, "status"))
        except (OSError, ValueError) as exc:
            _record(record_errors, exc)
            text = ""
        values = _key_values(text, STATUS_COUNTERS + ("State",), record_errors, ("State",))
        record = {"pid": int(entry), "comm": comm, "state": values.pop("State"), "errors": record_errors,
                  "starttime_ticks": _stat_starttime(proc_dir, record_errors)}
        record.update(values)
        found.append(record)
    return found, errors

def _cgroup_dir(directory):
    errors = []
    result = {"events": {}, "current": None, "peak": None, "max": None}
    try:
        lines = _read(os.path.join(directory, "memory.events")).splitlines()
        for line in lines:
            parts = line.split()
            if len(parts) == 2:
                try:
                    result["events"][parts[0]] = int(parts[1])
                except ValueError as exc:
                    _record(errors, exc)
            else:
                _record(errors, ValueError())
    except (OSError, ValueError) as exc:
        _record(errors, exc)
    for key, name in (("current", "memory.current"), ("peak", "memory.peak"), ("max", "memory.max")):
        try:
            text = _read(os.path.join(directory, name)).strip()
            result[key] = "max" if text == "max" else int(text)
        except (OSError, ValueError) as exc:
            _record(errors, exc)
    result["errors"] = errors
    return result

def _self_cgroup_path(proc_root, errors):
    try:
        lines = _read(os.path.join(proc_root, "self", "cgroup")).splitlines()
    except (OSError, ValueError) as exc:
        _record(errors, exc)
        return None
    for line in lines:
        fields = line.split(":", 2)
        if len(fields) != 3:
            _record(errors, ValueError())
            continue
        # A hybrid hierarchy can list v1 controllers before the unified v2 entry.
        if fields[0] == "0" and fields[1] == "":
            return fields[2].strip() or "/"
    _record(errors, ValueError())
    return None

def _resolve_cgroup(cgroup_root, path, errors):
    root = os.path.realpath(cgroup_root)
    candidate = os.path.realpath(os.path.join(root, path.lstrip("/")))
    if candidate != root and not candidate.startswith(root + os.sep):
        _record(errors, ValueError())
        return None
    return candidate

def _cgroup_self(proc_root, cgroup_root):
    errors = []
    path = _self_cgroup_path(proc_root, errors)
    data = {"path": path, "events": {}, "current": None, "peak": None, "max": None}
    if path is not None:
        directory = _resolve_cgroup(cgroup_root, path, errors)
        if directory is not None:
            data = _cgroup_dir(directory)
            data["path"] = path
            errors.extend(data.pop("errors"))
    data["errors"] = errors
    return data

def snapshot(proc_root, cgroup_root, elapsed_seconds=0.0):
    """One read-only snapshot from the supplied proc and cgroup-v2 roots."""
    meminfo, meminfo_errors = _meminfo(proc_root)
    processes, process_errors = _processes(proc_root)
    return {"timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "elapsed_seconds": round(elapsed_seconds, 3), "meminfo": meminfo,
            "meminfo_errors": meminfo_errors, "processes": processes,
            "process_errors": process_errors, "cgroup": {
                "root": _cgroup_dir(cgroup_root), "self": _cgroup_self(proc_root, cgroup_root)}}

def _parse_arguments(argv):
    parser = argparse.ArgumentParser(description="Bounded read-only host telemetry snapshots.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--interval-seconds", required=True, type=float)
    parser.add_argument("--duration-seconds", required=True, type=float)
    args = parser.parse_args(argv)
    for name in ("interval_seconds", "duration_seconds"):
        if not math.isfinite(getattr(args, name)) or getattr(args, name) <= 0:
            parser.error("--{} must be a positive finite number".format(name.replace("_", "-")))
    if not MIN_INTERVAL_SECONDS <= args.interval_seconds <= MAX_INTERVAL_SECONDS:
        parser.error("--interval-seconds must be between 0.1 and 60")
    if args.duration_seconds > MAX_DURATION_SECONDS:
        parser.error("--duration-seconds must not exceed 9000")
    if args.duration_seconds < args.interval_seconds:
        parser.error("--duration-seconds must be at least --interval-seconds")
    return args

def run(output, interval_seconds, duration_seconds, proc_root="/proc", cgroup_root="/sys/fs/cgroup"):
    start = time.monotonic()
    stop = threading.Event()
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, lambda signum, frame: stop.set())
    with open(output, "w", encoding="utf-8", buffering=1) as handle:
        def emit():
            record = snapshot(proc_root, cgroup_root, time.monotonic() - start)
            handle.write(json.dumps(record, sort_keys=True) + "\n")
        emit()
        last, stopped = 0.0, False
        while not stop.is_set():
            remaining = duration_seconds - (time.monotonic() - start)
            if remaining <= 0:
                break
            stop.wait(min(interval_seconds, remaining))
            if stop.is_set():
                stopped = True
                break
            emit()
            last = time.monotonic() - start
        if stopped or last < duration_seconds:
            emit()

def main(argv=None):
    args = _parse_arguments(argv)
    try:
        run(args.output, args.interval_seconds, args.duration_seconds)
    except Exception as exc:
        print(type(exc).__name__, file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
