"""Summarises the PDF probe's reports.jsonl (written by the fixture server) as Markdown.

    python3 -I testing/fixtures/pdfprobe/summarize.py <probe-out dir>
"""

import collections
import json
import pathlib
import re
import sys

# Log lines that change what the timings mean: a fallback pdf.js or Servo took instead.
FALLBACK = re.compile(r"fake worker|Failed to load font|Cannot load system font|instantiateWasm|getJsModule"
                      r"|ICCBased|falling back|worker error|uncaught|unhandled", re.IGNORECASE)


def first_line(text):
    return (text or "").strip().splitlines()[0][:160] if text else ""


def fallbacks(lines):
    return list(dict.fromkeys(line[:200] for line in lines if FALLBACK.search(line)))


def mb(kb):
    return round(int(kb or 0) / 1024, 1)


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
                problem = (doc.get("skipped") or first_line(doc.get("error"))
                           or "; ".join(fallbacks(doc.get("workerLog", [])))[:300])
                lines.append(f"| {doc['name']} | {doc.get('openMs', '')} | {doc.get('renderMs', '')} | {doc.get('ink', '')} "
                             f"| {doc.get('textFound', '')} | {doc.get('realWorker', '')} | {problem.replace('|', '/')} |")
            lines += ["", "Page ms: until Servo's canvas holds the page's pixels (PDFium: rasterised in its worker, then "
                      "copied in). pdf.js and PDFium both start one worker per document, inside Open ms.", ""]
        for item in items:
            if item.get("kind") == "three-open":
                lines += [f"- Three documents open at once: {first_line(item.get('error')) or 'ok'}", ""]
            if item.get("kind") == "page-log":
                notes = fallbacks(item.get("lines", []))
                if notes:
                    lines += [f"- Page log after {item.get('after')}: {line}" for line in notes] + [""]
            if item.get("kind") == "many-pages":
                lines += [f"- 300-page document: open {item.get('openMs')} ms, pages drawn {json.dumps(item.get('render'))}, "
                          f"text {json.dumps(item.get('text'))} {first_line(item.get('error'))}", ""]
            if item.get("kind") == "harness-error":
                lines += [f"- Harness error: {first_line(item.get('error'))}", ""]
        memory = [item for item in items if item.get("kind") == "memory"]
        if memory:
            baseline = next((m for m in memory if m.get("name") == "baseline"), memory[0])
            lines += ["| Checkpoint | PSS MB | PSS change MB | libc malloc MB | Unnamed mappings MB | Graphics MB |",
                      "|---|---|---|---|---|---|"]
            for sample in memory:
                change = mb(int(sample.get("totalPssKb") or 0) - int(baseline.get("totalPssKb") or 0))
                lines.append(f"| {sample['name']} | {mb(sample.get('totalPssKb'))} | {change} | {mb(sample.get('nativeHeapKb'))} "
                             f"| {mb(sample.get('privateOtherKb'))} | {mb(sample.get('graphicsKb'))} |")
            lines += ["", "Servo's Rust heap (jemalloc), SpiderMonkey's GC heap and wasm memory are unnamed mappings, "
                      "not libc malloc. Graphics means little on the emulator's software GPU.", ""]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
