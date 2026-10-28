# PDF support: research and design

Research date: 27 October 2026; probe results and the renderer decision added 28 October 2026. Engine: Servo v0.7.0, downstream `L-K-M/servo` `combray/0.7` at 6e49369. Sources: Servo and Combray source, pdf.js 6.4.299, `@embedpdf/pdfium` 2.15.1, Android and androidx.pdf documentation, and the PDF viewers of Firefox, Chromium, Ladybird and WebKit. A second pass re-checked the load-bearing claims; its corrections are folded in below.

Status: phase 0a and the probe (phase 0b) are done ([below](#phases)). Next is phase 1 with PDFium-wasm. The renderer is an owner decision; the rest of the design is a proposal.

## Owner decisions

- **Scope of "Servo is mandatory"** (27 October 2026): the rule does not apply to non-HTML content, so a PDF may use another renderer. At the time pdf.js looked like the best option; the 28 October decision below replaces that.
- **PDF text in Hister** (27 October 2026): same opt-in and exclusions as pages, no separate switch.
- **Next steps** (27 October 2026): fix the stand-in page capture (phase 0a), then run the probe (phase 0b). Both are done.
- **Renderer** (28 October 2026, after the probe results): PDFium compiled to WebAssembly (`@embedpdf/pdfium` 2.15.1) inside the planned Servo viewer document, instead of pdf.js. pdf.js stays a later option if the Servo memory leak the probe found is located and fixed.

## What happens today

Servo paths are relative to the Servo checkout.

- A top-level navigation to `application/pdf` becomes Servo's synthesized page "Unknown content type (application/pdf)." The same happens for `application/octet-stream` and any other type Servo has no viewer for (`components/script/dom/servoparser/mod.rs`, `load_document`). Servo ignores `Content-Disposition` when it navigates: a response sent as an attachment is shown by its type alone, so a PDF attachment also becomes the stand-in page.
  - The page commits at the PDF's URL with an opaque origin (`Document::mark_as_internal`).
- Servo keeps downloading the whole body and drops every chunk (`is_synthesized_document` in `servoparser/mod.rs`).
  - There is no download API, `<a download>` is not implemented, and `<embed>`/`<object>` fetch nothing.
- The embedder API carries no MIME type, and `WebResourceLoad` sees requests only.
- Until phase 0a, capture sent Hister the sentence "Unknown content type (application/pdf)." as the PDF's text, and reader view offered it as an article.

## Probe results (27 October 2026)

Phase 0b ran as a draft PR that was never merged and is now closed: [L-K-M/Combray#142](https://github.com/L-K-M/Combray/pull/142), commits de501ae to e959209, with its harness in `testing/fixtures/pdfprobe/` there. It ran in Engine CI on the API 30 x86_64 emulator (SwiftShader GPU), inside Combray's real Servo build (`combray/0.7` at 6e49369). Each engine ran in its own app process. The Servo gaps it found are recorded in [ENGINE_SPIKE.md section 27](ENGINE_SPIKE.md#27-pdf-probe-in-servo-07-27-october-2026).

- **Corpus:** three generated files (`fixture.pdf` in non-embedded Helvetica, `cjk.pdf` in a non-embedded Japanese CID font through UniJIS-UCS2-H, and `pages-300.pdf`), plus nine mozilla/pdf.js test files pinned at ea6cf5c by sha256: tracemonkey, TAMReview, ArabicCIDTrueType, jp2k-resetprob, jpx_smaskindata, bitmap-symbol, function_based_shading, axial_shading_many_inputs and issue19971.
- **Run 1** (Engine run 40500206133, commit 382e31b): the modern and legacy pdf.js builds under the strict viewer CSP, the legacy build without it, and PDFium-wasm.
- **Run 2** (Engine run 40506187604, commit 9806129): adds memory samples after `TestUtils.gc()`, a ReadableStream async-iteration polyfill, and a sample after the tab leaves the page. Servo may keep the left document in session history, so that sample is not a full teardown.
- **Run 3** (Engine run 40514996703, commit e959209): each engine reads the corpus twice, pdf.js runs once more with FontFace off, and the page draws Japanese with canvas text.

Emulator figures across the three runs. Memory is the app's PSS, from run 3, with pdf.js's legacy build and FontFace on.

| | pdf.js 6.4.299 | PDFium-wasm 2.15.1 |
|---|---|---|
| Renders the corpus | Yes; ink close to the same build in local headless Chromium (`fixture.pdf` 0.0024 against 0.0026), except `cjk.pdf` | Yes, except `cjk.pdf` (no ink without a font of its own) and `axial_shading_many_inputs`, which the same wasm build also leaves blank in headless Chromium |
| Text, 300 pages | With the polyfill only, 950,904 characters in 552 to 873 ms | 962,604 characters in 370 to 465 ms |
| Page time, median of 50 pages | 76 to 99 ms (63 ms with FontFace off) | 50 to 57 ms |
| Open, per document | 160 to 270 ms | 523 to 918 ms |
| Growth on a second corpus pass, after GC | +469.6 MB (libc malloc +311.6 MB) | -29.3 MB (libc malloc -1.6 MB) |
| Three documents open, then closed, after GC | 1,491.3 MB, then 1,478.9 MB | 558.2 MB, then 384.7 MB |
| After leaving the page | 1,378.5 MB (baseline 174.1 MB) | 335.9 MB (baseline 171.9 MB) |
| Non-embedded Japanese | Empty boxes | Nothing drawn (no font) |

- **pdf.js renders correctly in Servo.** Both builds run in a real module worker under the strict viewer CSP (`'wasm-unsafe-eval'`, no eval), with the same results without the CSP. Embedded Type1, CID TrueType right to left, the JPEG photo, JPEG 2000 and JBIG2 through its wasm decoders, and the shadings all draw.
- **pdf.js text extraction fails in Servo 0.7** with "readableStream is not iterable": Servo's ReadableStream lacks async iteration (its WebIDL comments it out). A small polyfill fixes it (runs 2 and 3).
- **No OffscreenCanvas or ImageDecoder** in the page and in workers, and no FontFace in workers. Servo's OffscreenCanvas sits behind the `dom_offscreen_canvas_enabled` preference, which is off by default and which Combray does not set; the probe did not try it. pdf.js falls back to decoding images in JavaScript. FontFace from bytes works in the page.
- **Japanese without an embedded font fails in both.** pdf.js draws empty boxes. Canvas `fillText` with Japanese in a generic family draws boxes too, so Servo's canvas text found no CJK fallback on the emulator image. PDFium draws nothing for `cjk.pdf` without a font of its own. Both extract the Japanese text.
- **pdf.js leaks memory in Servo.** A second pass over the same corpus kept 469.6 MB more after GC, 311.6 MB of it in libc malloc; closing the documents and leaving the page released little. With FontFace off the second pass still kept 464.3 MB (libc malloc +319.1 MB), so web fonts are not the cause. The cause is not yet localized. pdf.js's canvas drawing calls are the likeliest place, since PDFium-wasm, which draws only through `putImageData`, stayed flat; canvas-heavy sites may be affected too.
- **PDFium-wasm holds memory flat.** It did not grow between passes and dropped back after closing its documents.
- **Open time.** pdf.js opened a document in about 0.2 s. PDFium took longer because each document started its own worker and compiled the 4.6 MB module again: starting one instance alone took 510 to 660 ms.

## Recommendation: a PDFium-wasm viewer document inside Servo

Firefox (desktop, and Android since version 111) and Ladybird (since April 2026) show a PDF as a viewer document inside the engine, with pdf.js. WebKitGTK and WPE use a similar pdf.js setup. Combray keeps that design and renders with PDFium, Chromium's PDF engine, compiled to WebAssembly (owner decision, 28 October 2026).

1. **One small downstream Servo change** at the spec step that now writes "Unknown content type". For a top-level PDF, Servo builds a viewer document instead:
   - It is committed at the PDF's own URL, so Back, Recent, titles and restore work unchanged.
   - It gets a fresh opaque origin, so the viewer never runs as the site. The response's CSP and its CSP-derived sandbox flags are replaced by Combray's strict viewer CSP.
   - It is fed the bytes of the original response, so there is no second request. Cookies (HttpOnly too), HTTP sign-in, POST results and one-time links all work, and nothing leaves Servo's profile.
   - Size: about 130 to 180 production lines in `servoparser/mod.rs`, `document.rs` and `prefs.rs`, plus webview tests and a DOWNSTREAM.md row.
2. **PDFium-wasm (`@embedpdf/pdfium` 2.15.1) as a library, in one module worker per viewer.** Only the engine package is used; Combray draws its own controls at the bottom.
   - Its files are served from a `combray-pdf:` scheme that Combray registers. The scheme serves static, allowlisted files only; PDF bytes never pass through it.
   - The worker renders each page to RGBA in wasm memory, and the page draws it with `putImageData`. `putImageData` is the only canvas call; the drawing calls pdf.js makes, the likeliest place of its leak, are not used.
   - Text comes from PDFium's text API (`FPDFText_*`) for capture, Find and Read as text.
   - The viewer compiles the module once per document, starting while the PDF still downloads, and reuses that instance for every page and for text. Starting an instance (worker, fetch and compile) took 510 to 660 ms in the probe, most of PDFium's open time. Whether Servo can cache the compiled module across viewer documents is unverified.
   - No PDF scripting or form environment, no eval, and no network access. The viewer CSP allows `'wasm-unsafe-eval'` for the module.
   - **Japanese and other CJK text:** PDFium in the browser sandbox cannot read system fonts. The scheme serves the phone's own Noto CJK font from `/system/fonts`, and the viewer hands it to PDFium. Unverified; phase 1 work.
3. **Combray's in-page scripts get a PDF branch:**
   - `capture.js` sends the PDF's text to Hister through the existing capture path.
   - `find.js` searches the extracted text, with an "indexing" state for long PDFs.
   - `reader.js` opens the PDF as text in the native Reader view. This brings TalkBack, text size and the dark theme.

Why this one, now that another renderer is allowed:

- **Hister is a core requirement, and the viewer extracts text on every Android version.** That includes Android 11 (minSdk 30) and the API 30 emulator that Engine CI uses. The native option, androidx.pdf, has no text, search or links there.
- **Memory and speed in Servo.** In the probe, PDFium-wasm held memory flat and drew pages faster; pdf.js leaked.
- **Security.** PDFium is C++, but compiled to WebAssembly a memory error can corrupt only the module's own memory, in a worker of an opaque origin under a strict CSP. Combray still follows PDFium's security fixes by updating the package. Android's `PdfRenderer` runs native PDFium in Combray's own process, which Android's documentation advises against for untrusted files.
- **Fit.** A PDF becomes one more Servo document with an identity, so the paths Combray guards per document (history, capture, find, reader, restore) apply unchanged.

## Requirements for the engine change

- Push the viewer markup through the parser, as the JSON viewer does (`load_json_document`). A parser-created script appended through DOM calls never runs.
- Clear the CSP-derived sandbox flags too. Otherwise a PDF sent with `Content-Security-Policy: sandbox` never runs its viewer, as with some raw-file hosts that send PDFs as octet-stream with `nosniff`.
- When Content-Length is above the cap, refuse at the headers. Ideally stream chunks to the viewer as Ladybird does, rather than buffering them in Rust.
- Memory peaks at several copies of the file during load: the net layer's copy, the buffer, the copy sent to the worker, and PDFium's copy in wasm memory, which it reads for the document's life. Start with a 32 MiB cap until it is measured on a phone.
- Servo keeps up to 20 past documents per tab. On `pagehide` the viewer must close its PDFium document, end its worker and free its canvases. WebAssembly memory never shrinks while its instance lives, so ending the worker is what returns it.
- Extract text lazily and within a budget (on capture, Find or Read as text), and return partial text marked as truncated. Capture then needs a new "pending/partial" status through Rust, JNI and Kotlin.

## What the probe settled, and what is still unknown

Settled on the emulator:

- Servo runs both renderers in a real module worker under the strict viewer CSP, with WebAssembly. The earlier unknowns (FontFace from bytes, the module worker, the wasm decoders) all work.
- pdf.js needs a polyfill for text and leaks memory in Servo 0.7. PDFium-wasm holds memory flat and draws pages faster.

Still unknown:

- **Speed on a phone.** Every figure comes from the x86_64 emulator with a software GPU; no run on the owner's arm64 phone has happened.
- **Memory on a real arm64 device,** including how much of PDFium's 164.1 MB above baseline after leaving the page is the document Servo keeps in session history.
- **The CJK font path:** whether PDFium-wasm can use the phone's Noto CJK font served by `combray-pdf:`, and what loading it costs in time and memory.
- **The Servo canvas leak:** where it is, and whether canvas-heavy web pages hit it too (unverified).
- **Open time:** whether starting the worker during the download, or caching the compiled module, brings PDFium's open time close to pdf.js's.

## Phases

0. **Done:**
   - **0a:** pages Servo synthesizes for files it cannot show no longer reach Hister or Reader. `native/src/stand_in.js` recognises them, with device checks in `HisterTest` and `ReaderTest`. Merged as [L-K-M/Combray#141](https://github.com/L-K-M/Combray/pull/141). Failure first: on the checks-only commit d928753 (Engine run 40483083824, attempt 2) both new checks failed: the fake Hister received the PDF's address, and reader view was Ready with the stand-in sentence as its one paragraph. The other 127 checks passed. On the final head 739d66e all 129 device checks passed.
   - **0b:** the probe ([results](#probe-results-27-october-2026)).
1. **"Tap a PDF link and read it" (about 1 to 1.5 weeks):**
   - the engine change in L-K-M/servo and a pin bump;
   - `native/src/pdf.rs` (the scheme handler) and vendored `@embedpdf/pdfium` 2.15.1 with a pinned hash;
   - `viewer.mjs`: pages, links, title, and the loading, error and too-large states;
   - the PDFium worker: one instance per document, pages rendered to RGBA, the document closed and the worker ended on `pagehide`;
   - font serving: the phone's Noto CJK font from `/system/fonts` through the scheme, with a device check that `cjk.pdf` draws its glyphs;
   - notices: the package's own code is MIT (`LICENSE`). PDFium is BSD-3-Clause, and its licence file (`LICENSE.pdfium`) also carries the Apache License 2.0 text. `pdfium.wasm` also builds in FreeType, OpenJPEG, libpng and zlib (their strings are in the binary), whose notices the package does not ship;
   - APK size: about 2.2 MB compressed (`pdfium.wasm` is 4,646,932 bytes, about 2.1 MB with `gzip -9`; `index.browser.js` is 293,211 bytes, about 63 KB);
   - `PdfTest`: cookie-gated and HTTP-sign-in PDFs, one request only, a CSP-sandbox PDF, a corrupt PDF, one that is too large, Back, and restore after process death.
2. **Text (about 1 week):** Hister capture of PDF text, Find across pages, and Read as text, each through PDFium's text API and with a device check.
3. **Polish:**
   - zoom (Servo pinch or viewer pinch) and the text size mapping;
   - password PDFs (a native sheet);
   - progressive display for large files;
   - octet-stream PDFs (Firefox's rule: a `.pdf` name plus a `%PDF-` header);
   - dark page colours;
   - a byte cap on Servo's in-memory HTTP cache.
4. **Owner-gated:** Save a copy, Open in another app, Karakeep upload for PDFs that need a login, and downloads in general. These need a second engine hook that hands a response body to Combray.

## Open questions for the owner

1. **The downstream engine change:** about 130 to 180 lines, never offered upstream.
2. **Size cap:** 32 MiB to start. Above it, "too large to show here", with Save to Karakeep.
3. **APK contents:** about 2.2 MB compressed for PDFium-wasm. The earlier question about pdf.js's Liberation fonts no longer applies.
4. **CJK fonts:** the plan uses the phone's own Noto CJK font. If that does not work, bundle a CJK font (a large addition to the APK), or leave such text undrawn while it stays searchable?
5. **Attachments and octet-stream:** Servo ignores `Content-Disposition`, so the viewer would show PDFs sent as attachments inline, as Firefox for Android does. Keep that, or honour `attachment` once a download hook exists? Treat `application/octet-stream` with a `.pdf` name and a `%PDF-` header as a PDF?
6. **Phase 4 scope.**
7. **A run on the owner's arm64 phone** for speed and memory. The probe ran only on the emulator; phase 1's viewer can be measured on the phone instead.

## Rejected options, with reasons

- **pdf.js, for now:** it rendered the corpus correctly and opens faster, but it leaks in Servo 0.7. In the probe the second pass over the corpus kept 469.6 MB more after GC (311.6 MB of it in libc malloc), and leaving the page released little. It is also larger, uncompressed: the build is 3.1 MB (3.5 MB legacy), the CJK CMaps 1.2 MB, the standard fonts 0.8 MB and `wasm/` 1.5 MB (image and colour decoders, their JavaScript fallbacks and QuickJS), against 4.9 MB for PDFium-wasm. Revisit if the leak in Servo is found and fixed.
- **Injecting a viewer into today's synthesized page:** the bytes are dropped and the origin is opaque, so a fetch with cookies is impossible.
- **Serving the viewer under the PDF URL by request interception:**
  - The viewer would run in the site's real origin, so viewer bugs become cross-site scripting.
  - The PDF could only be recognised by guessing from the URL.
- **Downloading again with Servo's cookies (DuckDuckGo's model):**
  - Session cookies leave Servo's profile.
  - HTTP sign-in, POST results and one-time links fail.
  - Every PDF downloads twice.
- **Platform `PdfRenderer` directly:** PDFium runs in Combray's process, with no text or search below Android 15 or S-extension 13.
- **Bundled native PDFium:** about 6.6 MB per ABI. It would run as native code with no wasm sandbox, so a PDFium memory bug (about 25 CVEs in 2026) would reach Combray's process unless Combray built and maintained a separate sandboxed process.
- **MuPDF:** AGPL, and a 21.6 MB AAR.
- **androidx.pdf overlay (the probe's fallback):** not needed: you chose PDFium-wasm after the emulator results. The phone speed check is still open. It runs PDFium in an isolated process, but the library is beta, and text and search need Android 12+ with S-extension 13.
- **Handing off to another app by default:**
  - The PDF leaves Combray: no Hister, Find or Recent title.
  - It needs the download hook anyway.
  - It stays as a phase 4 option.
