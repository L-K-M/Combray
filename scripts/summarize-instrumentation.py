#!/usr/bin/env python3
"""Summarize raw `am instrument -r` outputs (*.txt in a results directory) as a Markdown table.

    python3 -I scripts/summarize-instrumentation.py build/device-checks/<run>

Exits with status 1 when a test failed or errored, or a run reported no test at all.
"""

import pathlib
import re
import sys

# INSTRUMENTATION_STATUS_CODE values of AndroidJUnitRunner.
STATUS = {"0": "passed", "-1": "error", "-2": "failed", "-3": "ignored", "-4": "assumption failed"}
SKIP = {"fixtures.txt", "device.txt", "logcat.txt"}


def parse(text):
    results, current = [], {}
    for line in text.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if match:
            current[match.group(1)] = match.group(2)
            continue
        match = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if match:
            code = match.group(1)
            if code != "1" and "test" in current:
                results.append((current.get("class", "").rsplit(".", 1)[-1], current["test"], STATUS.get(code, code)))
            current = {}
    return results


def main():
    directory = pathlib.Path(sys.argv[1])
    rows, empty = [], []
    for path in sorted(directory.glob("*.txt")):
        if path.name in SKIP:
            continue
        found = parse(path.read_text(errors="replace"))
        if not found:
            empty.append(path.stem)
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
    print(", ".join(f"{status}: {count}" for status, count in sorted(counts.items())) or "no results")
    if empty:
        print(f"runs without results: {', '.join(empty)}")
    bad = any(status in ("failed", "error") for *_, status in rows)
    return 1 if bad or empty else 0


if __name__ == "__main__":
    sys.exit(main())
