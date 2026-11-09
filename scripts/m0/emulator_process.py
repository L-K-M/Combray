#!/usr/bin/env python3
"""Supervise exactly one emulator child and record bounded exit evidence.

    python3 -I scripts/m0/emulator_process.py --pid-file PATH --exit-file PATH -- EMULATOR ARGS...

The launch script keeps this supervisor's PID as its live handle. This process starts the exact
command after --, records the owned child PID, waits for it, and writes JSON exit metadata. The
pid file is record-only: it is never read and never used to signal a process. Only the owned
child is ever signalled, and only from normal control flow.
"""

import argparse
import json
import signal
import subprocess
import sys
import time

POLL_SECONDS = 0.2
CLEANUP_POLL_SECONDS = 0.05
STOP_GRACE_SECONDS = 5.0
STOP_SIGNALS = (signal.SIGTERM, signal.SIGINT)


def _utc_now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _write_text(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def _metadata_json(child_pid, started, ended, returncode, stop_signal=None, error=None):
    record = {"childPID": child_pid, "startedUtc": started, "endedUtc": ended,
              "returncode": returncode}
    if returncode is not None and returncode < 0:
        record["signal"] = -returncode
    if stop_signal is not None:
        record["stopSignal"] = stop_signal
    if error is not None:
        record["error"] = error
    return json.dumps(record, sort_keys=True) + "\n"


def _finish_child(child, first_signal):
    """Forward one stop signal, then bound the wait and reap this child only."""
    if child.poll() is None:
        child.send_signal(first_signal)
    deadline = time.monotonic() + STOP_GRACE_SECONDS
    while child.poll() is None and time.monotonic() < deadline:
        time.sleep(CLEANUP_POLL_SECONDS)
    if child.poll() is None:
        child.kill()
    return child.wait()


def _supervise(child, state):
    while True:
        returncode = child.poll()
        if returncode is not None:
            return returncode
        if state["stop"] is not None:
            return _finish_child(child, state["stop"])
        time.sleep(POLL_SECONDS)


def _parse_arguments(argv):
    if "--" not in argv:
        raise ValueError("missing -- before the emulator command")
    separator = argv.index("--")
    command = argv[separator + 1:]
    if not command:
        raise ValueError("empty emulator command")
    parser = argparse.ArgumentParser(description="Supervise one emulator child.")
    parser.add_argument("--pid-file", required=True)
    parser.add_argument("--exit-file", required=True)
    args = parser.parse_args(argv[:separator])
    return args.pid_file, args.exit_file, command


def run(pid_file, exit_file, command):
    started = _utc_now()
    state = {"stop": None}

    def request_stop(signum, frame):
        # A handler can interrupt Popen or a lock: store a flag only, never wait or signal.
        if state["stop"] is None:
            state["stop"] = signum

    for number in STOP_SIGNALS:
        signal.signal(number, request_stop)
    try:
        child = subprocess.Popen(command)
    except Exception as exc:
        print(type(exc).__name__, file=sys.stderr)
        return 1
    try:
        _write_text(pid_file, "{}\n".format(child.pid))
        returncode = _supervise(child, state)
        ended = _utc_now()
        _write_text(exit_file, _metadata_json(child.pid, started, ended, returncode,
                                              state["stop"]))
    except Exception as exc:
        # Never leak the owned child and never report success without exit metadata.
        error = type(exc).__name__
        try:
            returncode = _finish_child(child, signal.SIGTERM)
        except Exception:
            returncode = None
        try:
            _write_text(exit_file, _metadata_json(child.pid, started, _utc_now(),
                                                  returncode, state["stop"], error))
        except Exception:
            pass
        print(error, file=sys.stderr)
        return 1
    return 128 - returncode if returncode < 0 else returncode


def main(argv=None):
    try:
        pid_file, exit_file, command = _parse_arguments(sys.argv[1:] if argv is None else argv)
    except Exception as exc:
        print(type(exc).__name__, file=sys.stderr)
        return 1
    return run(pid_file, exit_file, command)


if __name__ == "__main__":
    sys.exit(main())
