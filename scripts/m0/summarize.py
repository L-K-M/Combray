#!/usr/bin/env python3
"""Summarize M0 check reports (report-*.json) in a results directory as a Markdown table.

    python3 -I scripts/m0/summarize.py servo-checkout/results/<run>

Exits with status 1 when any check failed or a report has no results, so callers can tell a red
run from a green one.
"""

import json
import pathlib
import sys


def main():
    results = pathlib.Path(sys.argv[1])
    rows = []
    empty = []
    for report_file in sorted(results.glob("report-*.json")):
        report = json.loads(report_file.read_text())
        if not report["results"]:
            empty.append(report_file.name)
        for result in report["results"]:
            rows.append((report_file.stem.removeprefix("report-"), result["id"], result["status"]))

    counts = {}
    for _, _, status in rows:
        counts[status] = counts.get(status, 0) + 1

    print("| Phase | Check | Result |")
    print("| --- | --- | --- |")
    for phase, check, status in rows:
        print(f"| {phase} | `{check}` | {status} |")
    print()
    print(", ".join(f"{status}: {count}" for status, count in sorted(counts.items())))
    # A phase that recorded nothing established nothing.
    if empty:
        print(f"reports without results: {', '.join(empty)}")
    return 1 if counts.get("failed") or empty else 0


if __name__ == "__main__":
    sys.exit(main())
