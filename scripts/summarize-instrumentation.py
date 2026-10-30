#!/usr/bin/env python3
"""Summarize raw `am instrument -r` outputs (*.txt in a results directory) as a Markdown table.

    python3 -I scripts/summarize-instrumentation.py build/device-checks/<run>

Exits with status 1 when a test failed or errored, there was no run or a run reported no test,
or a run stopped early (its process crashed or was killed as not responding): the test in
progress shows as crashed and the tests it never reached are counted as not run.

From the run's logcat.txt it also lists every failed report-only check (a `REPORT <name>:
failed` line) and every check slower than SLOW_SECONDS. Neither changes the exit status: a
report-only check passes as a test even when it timed out, so it must not pass unseen.
"""

import datetime
import pathlib
import re
import sys

# INSTRUMENTATION_STATUS_CODE values of AndroidJUnitRunner.
STATUS = {"0": "passed", "-1": "error", "-2": "failed", "-3": "ignored", "-4": "assumption failed"}
LOGCAT = "logcat.txt"
SKIP = {"fixtures.txt", "device.txt", LOGCAT}
STARTED = "1"
CRASHED = "crashed"
NOT_RUN = "not run"
# A check that waits out one of its timeouts takes longer than this.
SLOW_SECONDS = 30
# `logcat -v threadtime`: date, time, pid, tid, level, tag padded before ": ", message.
LOGCAT_LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\d+\s+\d+\s+[VDIWEFA]\s+(.*?)\s*: (.*)$")
# AndroidJUnitRunner's own lines around each test.
TEST_EVENT = re.compile(r"^(started|finished): (\w+)\((?:[\w$]+\.)*([\w$]+)\)$")
REPORT_FAILED = re.compile(r"^REPORT .+?: failed\b")


def parse(text):
    """(class, test, status) per test, the number of tests the run announced, and why the run
    stopped early (None when it finished)."""
    results, current, started, announced = [], {}, None, 0
    for line in text.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if match:
            current[match.group(1)] = match.group(2)
            continue
        match = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if not match:
            continue
        code = match.group(1)
        announced = max(announced, int(current.get("numtests") or 0))
        test = (current.get("class", "").rsplit(".", 1)[-1], current["test"]) if "test" in current else None
        current = {}
        if test is None:
            continue
        # Code 1 starts a test; any other code ends it.
        if code == STARTED:
            started = test
            continue
        results.append((*test, STATUS.get(code, code)))
        started = None

    # The runner reports a dead process (crash, ANR) as the run's result instead of a test's.
    stopped = re.search(r"^INSTRUMENTATION_RESULT: shortMsg=(.*)$", text, re.M)
    if started is not None:
        results.append((*started, CRASHED))
    return results, announced, stopped.group(1) if stopped else None


def logcat_time(stamp):
    """A threadtime timestamp; it has no year, and a leap year accepts 02-29."""
    return datetime.datetime.strptime(f"2000-{stamp}", "%Y-%m-%d %H:%M:%S.%f")


def logcat_findings(text):
    """The failed report-only checks' log lines, and (class, check, seconds) for each check
    slower than SLOW_SECONDS, in log order."""
    reports, slow, started = [], [], {}
    for line in text.splitlines():
        match = LOGCAT_LINE.match(line)
        if not match:
            continue
        stamp, tag, message = match.groups()
        if REPORT_FAILED.match(message):
            reports.append(f"{stamp} {tag}: {message}")
            continue
        event = TEST_EVENT.match(message) if tag == "TestRunner" else None
        if event is None:
            continue
        kind, test, test_class = event.groups()
        if kind == "started":
            started[(test_class, test)] = logcat_time(stamp)
            continue
        began = started.pop((test_class, test), None)
        if began is None:
            continue
        ended = logcat_time(stamp)
        # A check that ran across New Year.
        if ended < began:
            ended = ended.replace(year=began.year + 1)
        seconds = (ended - began).total_seconds()
        if seconds > SLOW_SECONDS:
            slow.append((test_class, test, seconds))
    return reports, slow


def main():
    directory = pathlib.Path(sys.argv[1])
    rows, empty, stopped, not_run = [], [], [], 0
    for path in sorted(directory.glob("*.txt")):
        if path.name in SKIP:
            continue
        found, announced, reason = parse(path.read_text(errors="replace"))
        if not found:
            empty.append(path.stem)
        if reason is not None:
            stopped.append(f"{path.stem} ({reason})")
        not_run += max(announced - len(found), 0)
        rows += [(path.stem, *result) for result in found]

    print("| Run | Class | Check | Result |")
    print("| --- | --- | --- | --- |")
    for run, test_class, test, status in rows:
        shown = status if status in ("passed", "ignored") else f"**{status}**"
        print(f"| {run} | {test_class} | `{test}` | {shown} |")
    counts = {}
    for *_, status in rows:
        counts[status] = counts.get(status, 0) + 1
    print()
    if not_run:
        counts[NOT_RUN] = not_run
    print(", ".join(f"{status}: {count}" for status, count in sorted(counts.items())) or "no results")
    if empty:
        print(f"runs without results: {', '.join(empty)}")
    if stopped:
        print(f"runs that stopped early: {', '.join(stopped)}")
    logcat = directory / LOGCAT
    if logcat.is_file():
        reports, slow = logcat_findings(logcat.read_text(errors="replace"))
        print()
        print("Report-only checks that failed:" if reports else "Report-only checks that failed: none")
        for report in reports:
            print(f"- {report}")
        print()
        print(f"Checks slower than {SLOW_SECONDS} s:" if slow else f"Checks slower than {SLOW_SECONDS} s: none")
        for test_class, test, seconds in slow:
            print(f"- {test_class} `{test}`: {seconds:.1f} s")
    bad = any(status in ("failed", "error", CRASHED) for *_, status in rows)
    return 1 if bad or empty or stopped or not_run or not rows else 0


if __name__ == "__main__":
    sys.exit(main())
