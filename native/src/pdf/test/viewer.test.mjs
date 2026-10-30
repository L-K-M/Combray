// Host tests of the PDF viewer's logic and of its worker against the vendored PDFium, with the
// device checks' fixture PDFs (testing/fixtures/server.py --write-pdfs), and of the viewer's
// lifecycle on a fake page. Needs Node 20 or later and python3; no browser.
//
//   node --test native/src/pdf/test/viewer.test.mjs

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";

import {
  ENGINE_STATE,
  MAX_BITMAP_SIDE,
  MAX_DOCUMENT_HEIGHT,
  MAX_LIVE_PAGES,
  MAX_PAGE_HEIGHT,
  MAX_PAGE_PIXELS,
  PAGE_MARGIN,
  PROBLEM,
  PdfWorker,
  Viewer,
  bitmapBudget,
  bitmapSize,
  byteCount,
  drawOrder,
  fileTitle,
  firstPageEndingBelow,
  formatBytes,
  layoutPages,
  linkHref,
  pagesBetween,
} from "../viewer.mjs";

const repository = fileURLToPath(new URL("../../../../", import.meta.url));
const vendor = join(repository, "native/src/pdf/vendor");
const scratch = mkdtempSync(join(tmpdir(), "combray-pdf-test-"));
after(() => rmSync(scratch, { recursive: true, force: true }));

// The fixture PDFs, as the fixture server sends them.
const pdfs = join(scratch, "pdfs");
const written = spawnSync("python3", ["-I", join(repository, "testing/fixtures/server.py"), "--write-pdfs", pdfs], {
  encoding: "utf8",
});
assert.equal(written.status, 0, written.stderr);
const pdf = (name) => new Uint8Array(readFileSync(join(pdfs, name)));

// The worker next to the vendored PDFium, inflated, as the scheme serves them: its import of
// ./pdfium/index.browser.js then resolves as on a device.
mkdirSync(join(scratch, "pdfium"));
const script = join(scratch, "pdfium/index.browser.js");
writeFileSync(script, gunzipSync(readFileSync(join(vendor, "index.browser.js.gz"))));
copyFileSync(fileURLToPath(new URL("../worker.mjs", import.meta.url)), join(scratch, "worker.mjs"));
const { ERROR, MAX_PAGES, MAX_TITLE_LENGTH, PdfDocument, cleanTitle, loadErrorKind } = await import(
  pathToFileURL(join(scratch, "worker.mjs")).href
);
const { init } = await import(pathToFileURL(script).href);
const pdfium = await init({ wasmBinary: gunzipSync(readFileSync(join(vendor, "pdfium.wasm.gz"))) });
pdfium.PDFiumExt_Init();

// Band colours of testing/fixtures/server.py.
const PLUM = [0x7a, 0x2e, 0x64, 0xff];
const TEAL = [0x2a, 0x8a, 0x8a, 0xff];
const NAVY = [0x24, 0x30, 0x6e, 0xff];
const WHITE = [0xff, 0xff, 0xff, 0xff];
const LETTER = [612, 792];

function pixel(pixels, width, x, y) {
  const at = (y * width + x) * 4;
  return [...pixels.subarray(at, at + 4)];
}

function opened(name) {
  const document = new PdfDocument(pdfium);
  return { document, ...document.open(pdf(name)) };
}

test("a PDF opens with its page sizes and metadata title", () => {
  const { document, count, pages, title } = opened("plain.pdf");
  assert.equal(count, 1);
  assert.deepEqual(pages, [LETTER]);
  assert.equal(title, "PDF-PLAIN-TITLE");
  document.close();
});

test("a PDF without a title has none", () => {
  const { document, title } = opened("untitled.pdf");
  assert.equal(title, "");
  document.close();
});

test("a page is drawn in RGBA at the size asked, opaque", () => {
  const { document } = opened("plain.pdf");
  const width = 100;
  const height = 129;
  const { pixels } = document.render(0, width, height);
  assert.equal(pixels.length, width * height * 4);
  // The band covers the top 65% of the page; the rest is white paper.
  assert.deepEqual(pixel(pixels, width, 50, 10), PLUM);
  assert.deepEqual(pixel(pixels, width, 50, height - 3), WHITE);
  for (let index = 3; index < pixels.length; index += 4) assert.equal(pixels[index], 0xff);
  document.close();
});

test("every page of a long PDF has its size, and later pages draw", () => {
  const { document, count, pages, title } = opened("pages.pdf");
  assert.equal(count, 300);
  assert.equal(pages.length, 300);
  assert.equal(title, "PDF-PAGES-TITLE");
  const { pixels } = document.render(1, 60, 78);
  assert.deepEqual(pixel(pixels, 60, 30, 5), NAVY);
  document.close();
});

test("opening another PDF closes the first", () => {
  const document = new PdfDocument(pdfium);
  document.open(pdf("pages.pdf"));
  const { count } = document.open(pdf("plain.pdf"));
  assert.equal(count, 1);
  assert.deepEqual(pixel(document.render(0, 10, 13).pixels, 10, 5, 1), PLUM);
  document.close();
  document.close();
});

test("a link to a page of the PDF is found with its rectangle", () => {
  const { document } = opened("page-link.pdf");
  const { links, pixels } = document.render(0, 20, 26);
  assert.deepEqual(pixel(pixels, 20, 10, 2), TEAL);
  assert.equal(links.length, 1);
  assert.equal(links[0].page, 2);
  const expected = [0.05, 0.05, 0.95, 0.6];
  links[0].rect.forEach((value, index) => assert.ok(Math.abs(value - expected[index]) < 0.01, `${links[0].rect}`));
  document.close();
});

test("web links keep their address, and only http, https, mailto and tel ones become links", () => {
  const { document } = opened("web-link.pdf");
  const { links } = document.render(0, 20, 26);
  assert.deepEqual(
    links.map((link) => link.uri),
    [
      "http://localhost:8766/solid/2e6b4f.html",
      "javascript:document.title='PDF-SCRIPT-RAN'",
      "file:///system/etc/hosts",
      "intent://fixture#Intent;scheme=http;end",
    ],
  );
  const base = "http://localhost:8766/pdf/web-link.pdf";
  assert.deepEqual(
    links.map((link) => linkHref(link.uri, base)).filter(Boolean),
    ["http://localhost:8766/solid/2e6b4f.html"],
  );
  document.close();
});

test("a damaged file is corrupt and a password-protected one is protected", () => {
  const document = new PdfDocument(pdfium);
  assert.throws(() => document.open(pdf("corrupt.pdf")), (error) => error.kind === ERROR.CORRUPT);
  assert.throws(() => document.open(pdf("protected.pdf")), (error) => error.kind === ERROR.PROTECTED);
  assert.throws(() => document.open(new Uint8Array(0)), (error) => error.kind === ERROR.CORRUPT);
  // Still usable afterwards.
  assert.equal(document.open(pdf("plain.pdf")).count, 1);
  document.close();
});

test("PDFium's load errors map to what the viewer shows", () => {
  // FPDF_ERR_UNKNOWN, _FILE, _FORMAT and _PAGE are a damaged file; _PASSWORD and _SECURITY protected.
  for (const code of [0, 1, 2, 3, 6]) assert.equal(loadErrorKind(code), ERROR.CORRUPT);
  for (const code of [4, 5]) assert.equal(loadErrorKind(code), ERROR.PROTECTED);
  for (const kind of Object.values(ERROR)) assert.equal(typeof PROBLEM[kind], "function", kind);
});

test("titles lose control characters and stay bounded", () => {
  assert.equal(cleanTitle(" A\u0000report\n\tfor  2026 "), "A report for 2026");
  assert.equal(cleanTitle("x".repeat(MAX_TITLE_LENGTH + 5)).length, MAX_TITLE_LENGTH);
  assert.ok(MAX_PAGES >= 1000);
});

test("the file name is the title until the PDF names itself", () => {
  assert.equal(fileTitle("http://localhost:8766/pdf/a%20report.pdf?k=1#page=2"), "a report.pdf");
  assert.equal(fileTitle("https://files.example.test/download/"), "PDF");
  assert.equal(fileTitle("http://localhost/%E0%A4%A.pdf"), "%E0%A4%A.pdf");
  assert.equal(fileTitle("http://localhost/a%0Ab.pdf"), "a b.pdf");
  assert.equal(fileTitle("data:application/pdf;base64,JVBERi0="), "PDF");
  assert.equal(fileTitle("blob:null/9b7c0c8e-0d1c-4a7e-8f1b-2b6c0f3a9d11"), "PDF");
  assert.equal(fileTitle("not a url"), "PDF");
});

test("only http, https, mailto and tel links are followed", () => {
  const base = "https://files.example.test/docs/guide.pdf";
  assert.equal(linkHref("https://example.test/a?b=1#c", base), "https://example.test/a?b=1#c");
  assert.equal(linkHref("http://example.test", base), "http://example.test/");
  assert.equal(linkHref("mailto:someone@example.test", base), "mailto:someone@example.test");
  assert.equal(linkHref("tel:+41000000000", base), "tel:+41000000000");
  // Relative to the PDF, as a page's links are.
  assert.equal(linkHref("chapter-2.pdf", base), "https://files.example.test/docs/chapter-2.pdf");
  for (const refused of [
    "javascript:alert(1)",
    " JavaScript:alert(1)",
    "file:///system/etc/hosts",
    "intent://x#Intent;end",
    "data:text/html,x",
    "blob:null/1",
    "ftp://example.test/",
    "combray-pdf://viewer-0/viewer.mjs",
    "",
    `https://example.test/${"a".repeat(9000)}`,
  ]) {
    assert.equal(linkHref(refused, base), null, refused);
  }
  assert.equal(linkHref(undefined, base), null);
});

test("sizes and limits read from the engine's attributes", () => {
  assert.equal(byteCount("67108864"), 67108864);
  assert.equal(byteCount("0"), 0);
  for (const bad of ["", null, "-1", "1e3", " 12", "12 ", "0x10", "99999999999999999"]) {
    assert.equal(byteCount(bad), null, String(bad));
  }
  assert.equal(formatBytes(64 * 1024 * 1024), "64 MB");
  assert.equal(formatBytes(65 * 1024 * 1024), "65 MB");
  assert.equal(formatBytes(2.44 * 1024 * 1024), "2.4 MB");
  assert.equal(formatBytes(512 * 1024), "512 KB");
  assert.equal(formatBytes(10), "1 KB");
  assert.deepEqual(PROBLEM["too-large"](80 * 1024 * 1024, 64 * 1024 * 1024), [
    "This PDF is too large",
    "It is 80 MB, and Combray shows PDFs up to 64 MB.",
  ]);
  assert.deepEqual(PROBLEM["too-large"](null, 64 * 1024 * 1024)[1], "Combray shows PDFs up to 64 MB.");
  assert.deepEqual(Object.values(ENGINE_STATE), ["loading", "ready", "too-large", "failed"]);
});

test("pages fit the screen's width, one under another", () => {
  const { pages, shown, height } = layoutPages([LETTER, [792, 612]], 360);
  const width = 360 - 2 * PAGE_MARGIN;
  assert.equal(shown, 2);
  assert.deepEqual(pages[0], { top: PAGE_MARGIN, left: PAGE_MARGIN, width, height: Math.round((width * 792) / 612) });
  assert.equal(pages[1].top, pages[0].top + pages[0].height + PAGE_MARGIN);
  assert.equal(pages[1].height, Math.round((width * 612) / 792));
  assert.equal(height, pages[1].top + pages[1].height + PAGE_MARGIN);
});

test("a page far taller than wide is narrowed, and an empty size is US Letter", () => {
  const { pages } = layoutPages([[3, 14400], [0, 0]], 360);
  assert.equal(pages[0].height, MAX_PAGE_HEIGHT);
  assert.ok(pages[0].width >= 1 && pages[0].width < 10);
  // Centred in the width.
  assert.ok(pages[0].left > 100);
  assert.equal(pages[1].height, Math.round((344 * 792) / 612));
});

test("layout stops before the document gets too tall to lay out", () => {
  const sizes = Array.from({ length: 30000 }, () => LETTER);
  const { pages, shown, height } = layoutPages(sizes, 360);
  assert.ok(shown < sizes.length && shown > 10000, `${shown}`);
  assert.equal(pages.length, shown);
  assert.ok(height <= MAX_DOCUMENT_HEIGHT + PAGE_MARGIN);
  // A single page always shows.
  assert.equal(layoutPages([[10, 10 * 4800]], 360).shown, 1);
});

test("the pages on screen are found between their tops and bottoms", () => {
  const { pages } = layoutPages(Array.from({ length: 10 }, () => LETTER), 360);
  const step = pages[1].top - pages[0].top;
  // The margin above the first page holds none of it.
  assert.equal(pagesBetween(pages, 0, 1), null);
  assert.deepEqual(pagesBetween(pages, 0, 10), [0, 0]);
  assert.equal(firstPageEndingBelow(pages, pages[0].top + pages[0].height), 1);
  assert.equal(firstPageEndingBelow(pages, 1e9), pages.length);
  // Half open: a page starting at the end is not in.
  assert.deepEqual(pagesBetween(pages, pages[3].top, pages[3].top + step), [3, 3]);
  assert.deepEqual(pagesBetween(pages, pages[3].top, pages[3].top + step + 1), [3, 4]);
  assert.deepEqual(pagesBetween(pages, -1000, 100000), [0, 9]);
  // In the gap between two pages, and past the end.
  assert.equal(pagesBetween(pages, pages[0].top + pages[0].height, pages[1].top), null);
  assert.equal(pagesBetween(pages, 1e7, 1e7 + 10), null);
  assert.equal(pagesBetween([], 0, 10), null);
});

test("bitmaps follow the device pixel ratio up to their limits", () => {
  assert.deepEqual(bitmapSize(344, 445, 2), { width: 688, height: 890 });
  assert.deepEqual(bitmapSize(344, 445, NaN), { width: 344, height: 445 });
  const large = bitmapSize(4000, 5000, 3);
  assert.ok(large.width * large.height <= MAX_PAGE_PIXELS);
  assert.ok(Math.abs(large.width / large.height - 0.8) < 0.01);
  const tall = bitmapSize(10, 20000, 3);
  assert.ok(tall.height <= MAX_BITMAP_SIDE && tall.width >= 1);
  assert.deepEqual(bitmapSize(0, 0, 2), { width: 1, height: 1 });
});

test("pages on screen draw first, then those below, then those above", () => {
  assert.deepEqual(drawOrder([2, 8], [4, 5]), [4, 5, 6, 7, 8, 3, 2]);
  assert.deepEqual(drawOrder([0, 1], null), [0, 1]);
  assert.deepEqual(drawOrder(null, null), []);
});

test("a few pages hold bitmaps, or every page on screen when more fit", () => {
  assert.equal(bitmapBudget([4, 5]), MAX_LIVE_PAGES);
  assert.equal(bitmapBudget(null), MAX_LIVE_PAGES);
  // Small pages: all twenty on screen keep their bitmaps rather than taking turns.
  assert.equal(bitmapBudget([10, 29]), 20);
  const wanted = drawOrder([0, 40], [10, 29]).slice(0, bitmapBudget([10, 29]));
  assert.deepEqual(wanted, Array.from({ length: 20 }, (_, index) => index + 10));
});

// The Viewer on a fake page: just the DOM, window and Worker calls viewer.mjs makes. The test
// plays the worker's part, and runs animation frames and page events itself.

class FakeElement {
  constructor(name) {
    this.localName = name;
    this.children = [];
    this.parent = null;
    this.attributes = new Map();
    this.listeners = {};
    this.style = {};
    this.className = "";
    this.textContent = "";
    this.hidden = false;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  append(...nodes) {
    for (const node of nodes) {
      node.remove();
      node.parent = this;
      this.children.push(node);
    }
  }

  remove() {
    if (!this.parent) return;
    this.parent.children.splice(this.parent.children.indexOf(this), 1);
    this.parent = null;
  }

  replaceChildren(...nodes) {
    for (const child of [...this.children]) child.remove();
    this.append(...nodes);
  }

  /** The first descendant with the class in `selector` (".name"; the only kind the viewer uses). */
  querySelector(selector) {
    for (const child of this.children) {
      if (child.className.split(" ").includes(selector.slice(1))) return child;
      const found = child.querySelector(selector);
      if (found) return found;
    }
    return null;
  }

  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }

  getContext() {
    return { putImageData() {} };
  }
}

class FakeWorker {
  constructor() {
    this.calls = [];
    this.answered = new Set();
    this.listeners = {};
  }

  postMessage(data) {
    this.calls.push({ call: "postMessage", data });
  }

  terminate() {
    this.calls.push({ call: "terminate" });
  }

  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }

  requests(op) {
    return this.calls.filter(({ data }) => data && data.op === op).map(({ data }) => data);
  }

  reply(request, values) {
    this.answered.add(request.id);
    for (const listener of this.listeners.message) listener({ data: { id: request.id, ok: true, ...values } });
  }
}

/** Lets the viewer's awaits continue. */
const tick = () => new Promise((resolve) => setImmediate(resolve));

/** Runs `run` on a fake page 360 CSS pixels wide whose PDF is ready, then restores Node's globals. */
async function onFakePage(run) {
  const root = new FakeElement("html");
  root.clientWidth = 360;
  root.setAttribute("data-pdf-state", ENGINE_STATE.READY);
  root.setAttribute("data-pdf-src", "blob:null/1");
  const listeners = {};
  const frames = [];
  const workers = [];
  const page = {
    root,
    workers,
    get worker() {
      return workers.at(-1);
    },
    dispatch(type, event = {}) {
      for (const listener of listeners[type] || []) listener(event);
    },
    frame() {
      for (const callback of frames.splice(0)) callback();
    },
  };
  const globals = {
    document: {
      documentElement: root,
      head: new FakeElement("head"),
      body: new FakeElement("body"),
      title: "",
      URL: "http://localhost:8766/pdf/a.pdf",
      createElement: (name) => new FakeElement(name),
    },
    window: globalThis,
    Worker: class extends FakeWorker {
      constructor() {
        super();
        workers.push(this);
      }
    },
    MutationObserver: class {
      observe() {}
      disconnect() {}
    },
    ImageData: class {
      constructor(data, width, height) {
        Object.assign(this, { data, width, height });
      }
    },
    addEventListener: (type, listener) => (listeners[type] ||= []).push(listener),
    requestAnimationFrame: (callback) => frames.push(callback),
    scrollTo: (x, y) => {
      globalThis.scrollY = y;
    },
    scrollY: 0,
    innerWidth: 360,
    innerHeight: 640,
    devicePixelRatio: 1,
  };
  const saved = Object.keys(globals).map((name) => [name, Object.getOwnPropertyDescriptor(globalThis, name)]);
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true });
  }
  try {
    await run(page);
  } finally {
    for (const [name, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
}

/** A viewer on `page` whose worker opened a PDF of `sizes`, with the first frame laid out. */
async function openViewer(page, sizes) {
  const viewer = new Viewer(page.root);
  viewer.start();
  page.worker.reply(page.worker.requests("open")[0], { count: sizes.length, pages: sizes, title: "" });
  await tick();
  page.frame();
  return viewer;
}

/** Answers each page the viewer asks the current worker for, with white pixels and `links` by page. */
async function drawPages(page, links = {}) {
  const worker = page.worker;
  for (;;) {
    const request = worker.requests("render").find(({ id }) => !worker.answered.has(id));
    if (!request) return;
    const pixels = new Uint8Array(request.width * request.height * 4).fill(0xff);
    worker.reply(request, { pixels, links: links[request.index] || [] });
    await tick();
  }
}

test("ending the worker also wakes it: Servo ends an idle worker only on its next message", async () => {
  await onFakePage(async (page) => {
    const worker = new PdfWorker(() => assert.fail("no failure expected"));
    worker.terminate();
    const calls = page.worker.calls;
    assert.deepEqual(calls.map(({ call }) => call), ["terminate", "postMessage"]);
    // worker.mjs ignores a message without data.
    assert.equal(calls[1].data, null);
  });
});

test("back from the back-forward cache, no page is drawn before the PDF is open again", async () => {
  await onFakePage(async (page) => {
    const viewer = await openViewer(page, [LETTER, LETTER]);
    await drawPages(page);
    page.dispatch("pagehide");
    page.dispatch("pageshow", { persisted: true });
    const worker = page.worker;
    assert.equal(page.workers.length, 2);

    // A scroll while the new worker still opens the PDF.
    page.dispatch("scroll");
    page.frame();
    assert.deepEqual(worker.requests("render"), []);

    worker.reply(worker.requests("open")[0], { count: 2, pages: [LETTER, LETTER], title: "" });
    await tick();
    page.frame();
    await drawPages(page);
    assert.ok(worker.requests("render").length > 0);
    assert.ok([...viewer.views.values()].every((view) => !view.failed && view.drawn));
  });
});

test("a released bitmap is 1x1, which Servo frees at once (a 0x0 one it keeps until GC)", async () => {
  await onFakePage(async (page) => {
    const viewer = await openViewer(page, [LETTER]);
    await drawPages(page);
    const { canvas } = viewer.views.get(0);
    assert.deepEqual([canvas.width, canvas.height], [344, 445]);
    page.dispatch("pagehide");
    assert.deepEqual([canvas.width, canvas.height], [1, 1]);
  });
});

test("back after the screen turned while away, the pages fit its new width", async () => {
  await onFakePage(async (page) => {
    const viewer = await openViewer(page, [LETTER, LETTER]);
    page.dispatch("pagehide");
    // Servo fires no resize event for a change made while the page was in the back-forward cache.
    page.root.clientWidth = 720;
    page.dispatch("pageshow", { persisted: true });
    page.worker.reply(page.worker.requests("open")[0], { count: 2, pages: [LETTER, LETTER], title: "" });
    await tick();
    page.frame();
    assert.equal(viewer.layoutWidth, 720);
    assert.equal(viewer.views.get(0).element.style.width, `${720 - 2 * PAGE_MARGIN}px`);
  });
});

test("a wider screen that shows fewer pages drops the others, and links to them do nothing", async () => {
  await onFakePage(async (page) => {
    const sizes = Array.from({ length: MAX_PAGES }, () => LETTER);
    const narrow = layoutPages(sizes, 360);
    const shownWide = layoutPages(sizes, 720).shown;
    assert.ok(shownWide < narrow.shown);
    const viewer = await openViewer(page, sizes);

    // Reading just above the last page the wide layout shows: pages past it have elements, and
    // this page links to a page past it.
    const index = shownWide - 2;
    scrollTo(0, narrow.pages[index].top);
    page.dispatch("scroll");
    page.frame();
    await drawPages(page, { [index]: [{ rect: [0, 0, 1, 1], page: shownWide + 100 }] });
    const withElements = () => [...viewer.views.keys()];
    assert.ok(withElements().some((at) => at >= shownWide));
    const link = viewer.views.get(index).element.querySelector(".link");
    assert.ok(link);

    page.root.clientWidth = 720;
    page.dispatch("resize");
    assert.equal(viewer.layout.shown, shownWide);
    assert.ok(withElements().every((at) => at < shownWide));
    const top = scrollY;
    link.listeners.click[0]({ preventDefault() {} });
    assert.equal(scrollY, top);
  });
});
