"""Summarises the PDF probe's reports.jsonl (written by the fixture server) as Markdown.

    python3 -I testing/fixtures/pdfprobe/summarize.py <probe-out dir>
"""

import collections
import json
import pathlib
import sys


def first_line(text):
    return (text or "").strip().splitlines()[0][:160] if text else ""


def main():
    out = pathlib.Path(sys.argv[1])
    records = [json.loads(line) for line in (out / "reports.jsonl").read_text(encoding="utf-8").splitlines() if line]
    runs = collections.OrderedDict()
    for record in records:
        runs.setdefault((record.get("engine"), record.get("csp")), []).append(record)

    lines = ["# PDF probe", ""]
    for (engine, csp), items in runs.items():
        lines += [f"## {engine}, CSP {csp}", ""]
        for item in items:
            kind = item.get("kind")
            if kind == "features":
                missing = sorted(name for name, value in item.get("page", {}).items() if value is not True)
                worker = item.get("worker", {})
                worker_missing = (sorted(name for name, value in worker.items() if value is not True)
                                  if "error" not in worker else [f"module worker failed: {first_line(worker['error'])}"])
                lines += [f"- Page lacks: {', '.join(missing) or 'nothing'}",
                          f"- Module worker lacks: {', '.join(worker_missing) or 'nothing'}",
                          f"- FontFace from bytes: {json.dumps(item.get('fontFace'))}", ""]
            elif kind == "init":
                outcome = first_line(item["error"]) if "error" in item else f"{round(item.get('ms', 0))} ms"
                lines += [f"- Init: {outcome}", ""]
        documents = [item for item in items if item.get("kind") == "document"]
        if documents:
            lines += ["| Document | Open ms | Page ms | Ink | Text | Real worker | Problem |", "|---|---|---|---|---|---|---|"]
            for doc in documents:
                problem = first_line(doc.get("error")) or "; ".join(doc.get("workerLog", [])[:2])[:160]
                lines.append(f"| {doc['name']} | {doc.get('openMs', '')} | {doc.get('renderMs', '')} | {doc.get('ink', '')} "
                             f"| {doc.get('textFound', '')} | {doc.get('realWorker', '')} | {problem.replace('|', '/')} |")
            lines.append("")
        for item in items:
            if item.get("kind") == "many-pages":
                lines += [f"- 300-page document: open {item.get('openMs')} ms, pages drawn {json.dumps(item.get('render'))}, "
                          f"text {json.dumps(item.get('text'))} {first_line(item.get('error'))}", ""]
            if item.get("kind") == "harness-error":
                lines += [f"- Harness error: {first_line(item.get('error'))}", ""]
        memory = [item for item in items if item.get("kind") == "memory"]
        if memory:
            lines += ["| Checkpoint | PSS MB | Native heap MB | Graphics MB |", "|---|---|---|---|"]
            for sample in memory:
                mb = lambda key: round(int(sample.get(key) or 0) / 1024, 1)
                lines.append(f"| {sample['name']} | {mb('totalPssKb')} | {mb('nativeHeapKb')} | {mb('graphicsKb')} |")
            lines.append("")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
