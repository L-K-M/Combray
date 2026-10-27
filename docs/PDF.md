# PDF support: research and proposed design

Research date: 27 October 2026. Engine: Servo v0.7.0, downstream `L-K-M/servo` `combray/0.7` at 6e49369. Sources: Servo and Combray source, pdf.js 6.4.299, Android and androidx.pdf documentation, and the PDF viewers of Firefox, Chromium, Ladybird and WebKit. A second pass re-checked the load-bearing claims; its corrections are folded in below.

Status: phase 0a is done ([below](#phases)). The rest is a proposal awaiting the probe.

## Owner decisions

- **Scope of "Servo is mandatory"** (27 October 2026): it covers web content. Non-HTML content such as PDF may use another renderer. pdf.js inside Servo remains the preferred direction.
- **PDF text in Hister** (27 October 2026): same opt-in and exclusions as pages, no separate switch.
- **Next steps** (27 October 2026): fix the stand-in page capture (phase 0a), then run the probe (phase 0b).

## What happens today

Servo paths are relative to the Servo checkout.

- A top-level navigation to `application/pdf` becomes Servo's synthesized page "Unknown content type (application/pdf)." The same happens for `application/octet-stream` and for any response with `Content-Disposition: attachment` (`components/script/dom/servoparser/mod.rs`, `load_document`).
  - The page commits at the PDF's URL with an opaque origin (`Document::mark_as_internal`).
- Servo keeps downloading the whole body and drops every chunk (`is_synthesized_document` in `servoparser/mod.rs`).
  - There is no download API, `<a download>` is not implemented, and `<embed>`/`<object>` fetch nothing.
- The embedder API carries no MIME type, and `WebResourceLoad` sees requests only.
- Until phase 0a, capture sent Hister the sentence "Unknown content type (application/pdf)." as the PDF's text, and reader view offered it as an article.

## Recommendation: a pdf.js viewer document inside Servo

Firefox (desktop, and Android since version 111) and Ladybird (since April 2026) work this way. WebKitGTK and WPE use a similar pdf.js setup.

1. **One small downstream Servo change** at the spec step that now writes "Unknown content type". For a top-level PDF, Servo builds a viewer document instead:
   - It is committed at the PDF's own URL, so Back, Recent, titles and restore work unchanged.
   - It gets a fresh opaque origin, so the viewer never runs as the site. The response's CSP and its CSP-derived sandbox flags are replaced by Combray's strict viewer CSP.
   - It is fed the bytes of the original response, so there is no second request. Cookies (HttpOnly too), HTTP sign-in, POST results and one-time links all work, and nothing leaves Servo's profile.
   - Size: about 130 to 180 production lines in `servoparser/mod.rs`, `document.rs` and `prefs.rs`, plus webview tests and a DOWNSTREAM.md row.
2. **pdf.js 6.4.299 used as a library.** Its stock viewer is not used: its top toolbar breaks the bottom-controls rule.
   - pdf.js is served from a `combray-pdf:` scheme that Combray registers. The scheme serves static, allowlisted files only; PDF bytes never pass through it.
   - It runs in a real module worker, without PDF scripting, QuickJS or XFA, and with no eval and no network access.
3. **Combray's in-page scripts get a PDF branch:**
   - `capture.js` sends the PDF's text to Hister through the existing capture path.
   - `find.js` searches the extracted text, with an "indexing" state for long PDFs.
   - `reader.js` opens the PDF as text in the native Reader view. This brings TalkBack, text size and the dark theme.

Why this one, now that another renderer would be allowed:

- **Hister is a core requirement, and pdf.js extracts text on every Android version.** That includes Android 11 (minSdk 30) and the API 30 emulator that Engine CI uses. The native option, androidx.pdf, has no text, search or links there.
- **Security.** The parser is memory-safe JavaScript and WebAssembly, in an opaque origin under a strict CSP. Android's `PdfRenderer` runs PDFium in Combray's own process, which Android's documentation advises against for untrusted files.
- **Fit.** A PDF becomes one more Servo document with an identity, so the paths Combray guards per document (history, capture, find, reader, restore) apply unchanged.

## Requirements for the engine change

- Push the viewer markup through the parser, as the JSON viewer does (`load_json_document`). A parser-created script appended through DOM calls never runs.
- Clear the CSP-derived sandbox flags too. Otherwise a PDF sent with `Content-Security-Policy: sandbox` never runs its viewer, as with some raw-file hosts that send PDFs as octet-stream with `nosniff`.
- When Content-Length is above the cap, refuse at the headers. Ideally stream chunks to the viewer as Ladybird does, rather than buffering them in Rust.
- Memory peaks at about three to four times the file size during load: the net layer's copy, the buffer, and the structured-clone copy to the worker. Start with a 32 MiB cap until it is measured on a phone.
- Servo keeps up to 20 past documents per tab. On `pagehide` the viewer must destroy its pdf.js document and worker and free its canvases.
- Extract text lazily and within a budget (on capture, Find or Read as text), and return partial text marked as truncated. Capture then needs a new "pending/partial" status through Rust, JNI and Kotlin.

## Biggest unknown, and the probe that settles it

**Unknown:** whether pdf.js draws fast enough and within memory in Servo on a phone. Servo's canvas is CPU-rendered (vello_cpu), and pdf.js draws text one glyph at a time. Also unproven:

- embedded fonts through `FontFace` (fixed upstream in March 2026, untested on 0.7);
- the module worker;
- the WebAssembly decoders.

**Probe (phase 0b, about one day, a draft PR that is never merged):**

- A fixture page loads pdf.js 6.4.299 from the fixture server and renders a set of PDFs.
  - The set is generated files plus pinned pdf.js-corpus files: embedded Type1, CJK/CID, JPX, JBIG2, ICC, shading, and a 300-page file.
  - The fixture server sends the product CSP as a header.
- It measures:
  - a real worker, not the fake one;
  - WebAssembly in use;
  - text extraction;
  - glyphs versus boxes;
  - time per page;
  - PSS over 50 pages, and with three PDFs in one tab.
- It runs as a new device check in Engine CI (API 30 emulator), plus once on the owner's arm64 phone for the speed gate.
- **A/B:** the same pages through PDFium compiled to WebAssembly (`@embedpdf/pdfium`, MIT, about 4.6 MB). It rasterises whole pages in the worker and avoids per-glyph canvas calls.

**Outcomes:**

- **pdf.js passes:** go to phase 1.
- **pdf.js is too slow but WebAssembly runs well:** switch the renderer to PDFium-wasm inside the same viewer document. Everything else stays: the engine change, the scripts, Hister, Find and Reader.
- **Both fail:** use an androidx.pdf overlay (PDFium in an isolated process, beta). The owner's clarification allows it. It needs Android 12+ with S-extension 13 for text and search.

## Phases

0. **Now, independent of the rest:**
   - **0a, done:** pages Servo synthesizes for files it cannot show no longer reach Hister or Reader. `native/src/stand_in.js` recognises them, with device checks in `HisterTest` and `ReaderTest`.
   - **0b:** the probe.
1. **"Tap a PDF link and read it" (about 1 to 1.5 weeks):**
   - the engine change in L-K-M/servo and a pin bump;
   - `native/src/pdf.rs` (the scheme handler) and vendored pdf.js with a pinned hash;
   - `viewer.mjs`: pages, links, title, and the loading, error and too-large states;
   - notices;
   - `PdfTest`: cookie-gated and HTTP-sign-in PDFs, one request only, a CSP-sandbox PDF, a corrupt PDF, one that is too large, Back, and restore after process death.
2. **Text (about 1 week):** Hister capture of PDF text, Find across pages, and Read as text, each with a device check.
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
3. **APK contents:** about 2.2 to 4.2 MB inside the native library (CJK cmaps 1.2 MB, standard fonts 0.8 MB). Drop the Liberation fonts, which are GPLv2 with a font exception?
4. **Attachments and octet-stream:** show PDFs sent as attachments inline, as Firefox for Android does? Treat `application/octet-stream` with a `.pdf` name and a `%PDF-` header as a PDF?
5. **Phase 4 scope.**
6. **The probe on the owner's arm64 phone,** for the speed gate.

## Rejected options, with reasons

- **Injecting pdf.js into today's synthesized page:** the bytes are dropped and the origin is opaque, so a fetch with cookies is impossible.
- **Serving the viewer under the PDF URL by request interception:**
  - The viewer would run in the site's real origin, so pdf.js bugs become cross-site scripting.
  - The PDF could only be recognised by guessing from the URL.
- **Downloading again with Servo's cookies (DuckDuckGo's model):**
  - Session cookies leave Servo's profile.
  - HTTP sign-in, POST results and one-time links fail.
  - Every PDF downloads twice.
- **Platform `PdfRenderer` directly:** PDFium runs in Combray's process, with no text or search below Android 15 or S-extension 13.
- **Bundled native PDFium:** about 6.6 MB per ABI, and Combray would own its CVEs (about 25 in 2026) and a sandbox.
- **MuPDF:** AGPL, and a 21.6 MB AAR.
- **Handing off to another app by default:**
  - The PDF leaves Combray: no Hister, Find or Recent title.
  - It needs the download hook anyway.
  - It stays as a phase 4 option.
