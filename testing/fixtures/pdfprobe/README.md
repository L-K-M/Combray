# PDF probe

An experiment for [docs/PDF.md](../../../docs/PDF.md) (phase 0b) that lives on a draft PR and is never merged. It answers whether Servo can render PDFs with pdf.js, or with PDFium compiled to WebAssembly, fast enough and within memory on a phone.

- `fetch.py` downloads pdfjs-dist 6.4.299, @embedpdf/pdfium 2.15.1 and nine pdf.js test-corpus files into `vendor/` (git-ignored), checking each against a pinned hash.
- The fixture server serves the probe at `/pdfprobe/strict/` (with a viewer's Content-Security-Policy) and `/pdfprobe/open/` (without it), plus three generated PDFs: a standard-font page, a page in a non-embedded Japanese font only and a 300-page document.
- `index.html?engine=pdfjs|pdfjs-legacy|pdfium` checks the engine's features, renders the corpus and 50 pages of the long document, extracts text, opens three documents at once and closes them. It reports each result to the fixture server.
- `PdfProbeTest` drives the page on the device and samples the app's memory at each checkpoint. Each engine runs in its own app process (one instrumentation run per test method), so a crash in one cannot spoil the others and every engine starts from the same memory. `summarize.py` turns the reports into Markdown. Engine CI runs only this check on the probe branch, then uploads the reports and page snapshots with the device-check evidence.
- Comparable by design: pdf.js and PDFium both start one worker per document. A corpus page's time runs until Servo's canvas holds the page's pixels; the 50-page figures are pipelined throughput, as scrolling would draw them.

Run on a device or emulator:

```
python3 -I testing/fixtures/pdfprobe/fetch.py
scripts/check-app.sh <serial> ch.lkmc.combray.PdfProbeTest#pdfjsLegacy ch.lkmc.combray.PdfProbeTest#pdfiumWasm \
  ch.lkmc.combray.PdfProbeTest#pdfjsLegacyWithoutCsp ch.lkmc.combray.PdfProbeTest#pdfjsModern
python3 -I testing/fixtures/pdfprobe/summarize.py build/device-checks/<run>/pdfprobe
```
