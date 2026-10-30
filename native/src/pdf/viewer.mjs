// Combray's PDF viewer (docs/PDF.md). Servo (L-K-M/servo, the PDF viewer document) turns a
// top-level PDF response into a document at the PDF's own address, with an opaque origin and the
// viewer's CSP, whose markup loads this module:
//
//   <html data-pdf-state="loading" data-pdf-length="{Content-Length or empty}">
//     <head>… <script type="module" async src="combray-pdf://viewer-<token>/viewer.mjs"> </head>
//     <body></body></html>
//
// data-pdf-state then moves to "ready" (data-pdf-src is the PDF's blob: URL), "too-large"
// (data-pdf-limit is the cap) or "failed". This module may run before or after that, so it reads
// the state first and only then watches for the change. It starts the PDFium worker at once, so
// PDFium compiles while the PDF still downloads.
//
// Pages are laid out from their sizes and drawn lazily: only pages near the screen have elements,
// at most MAX_LIVE_PAGES of them (or the pages on screen, if more) hold a bitmap, and each bitmap
// is at most MAX_PAGE_PIXELS.
// Links become <a> overlays for http, https, mailto and tel, or scroll to their page (owner
// decision, 29 October 2026). The title is the PDF's metadata title, else its file name.
//
// On pagehide the worker ends and the bitmaps go. Servo ends an idle worker only once a message
// wakes it (PdfWorker.terminate), and then drops its runtime with PDFium's wasm memory. A page in
// the back-forward cache starts again on pageshow from the same blob: URL, which the engine keeps
// while the document lives: never revoke it, and add no unload or beforeunload listener, which
// would keep the document out of that cache and free the PDF on leaving. Servo sends such a page
// no resize event, so it lays the pages out again at the current width.

/** The root element's data-pdf-state values the engine sets. */
export const ENGINE_STATE = Object.freeze({
  LOADING: "loading",
  READY: "ready",
  TOO_LARGE: "too-large",
  FAILED: "failed",
});

/** Space around and between pages, in CSS pixels. */
export const PAGE_MARGIN = 8;
/** A page taller than this at the screen's width is narrowed to it, in CSS pixels. */
export const MAX_PAGE_HEIGHT = 20000;
/** Pages stop being laid out past this height, so layout never overflows, in CSS pixels. */
export const MAX_DOCUMENT_HEIGHT = 8000000;
/** Pages holding a bitmap at once. */
export const MAX_LIVE_PAGES = 6;
/** Largest page bitmap, in pixels (4 bytes each); a larger page is drawn at a lower resolution. */
export const MAX_PAGE_PIXELS = 4 * 1024 * 1024;
/** Largest bitmap side, in pixels. */
export const MAX_BITMAP_SIDE = 8192;
/** Longest title taken from the file name. */
export const MAX_TITLE_LENGTH = 1000;
/** Link schemes a PDF may open (owner decision, 29 October 2026). */
export const LINK_SCHEMES = Object.freeze(["http:", "https:", "mailto:", "tel:"]);
/** Longest link address, in UTF-16 code units (about the engine's MAX_URL_BYTES). */
export const MAX_LINK_LENGTH = 8 * 1024;
/** Pages kept with elements beyond the screen, in screen heights above and below it. */
const NEAR_SCREENS = 1;
const MEBIBYTE = 1024 * 1024;
const KIBIBYTE = 1024;

/** The viewer's text, in English (owner decision, 29 October 2026). */
const TEXT = Object.freeze({
  document: "PDF document",
  loading: "Loading PDF…",
  loadingSize: (size) => `Loading PDF (${size})…`,
  page: (number, count) => `Page ${number} of ${count}`,
  pageImage: (number) => `Page ${number}`,
  pageFailed: (number) => `Page ${number} could not be drawn.`,
  goToPage: (number) => `Go to page ${number}`,
  more: (shown, count) => `Showing the first ${shown.toLocaleString("en")} of ${count.toLocaleString("en")} pages.`,
});

/** What the page shows instead of the PDF: heading and explanation, by state. */
export const PROBLEM = Object.freeze({
  "too-large": (length, limit) => [
    "This PDF is too large",
    length > limit
      ? `It is ${formatBytes(length)}, and Combray shows PDFs up to ${formatBytes(limit)}.`
      : `Combray shows PDFs up to ${formatBytes(limit)}.`,
  ],
  failed: () => ["The PDF did not download", "The connection ended before the whole file arrived. Reload to try again."],
  corrupt: () => ["This file cannot be shown", "It is damaged or not a PDF."],
  protected: () => ["This PDF is protected", "Combray cannot open password-protected PDFs yet."],
  memory: () => ["This PDF is too large to show", "There is not enough memory for it."],
  viewer: () => ["The PDF viewer stopped", "Reload to try again."],
});

/** "2.4 MB", "64 MB", "512 KB": binary units, as the cap is set. */
export function formatBytes(bytes) {
  if (bytes >= MEBIBYTE) {
    const value = bytes / MEBIBYTE;
    return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} MB`;
  }
  return `${Math.max(1, Math.round(bytes / KIBIBYTE))} KB`;
}

/** A non-negative decimal attribute value, or null when it is empty or anything else. */
export function byteCount(value) {
  return /^\d{1,16}$/.test(value || "") ? Number(value) : null;
}

/** The decoded last path segment of a web `address`, or "PDF" (also for data: and blob: ones). */
export function fileTitle(address) {
  let segment = "";
  try {
    const url = new URL(address);
    if (url.protocol !== "http:" && url.protocol !== "https:") return "PDF";
    segment = url.pathname.split("/").pop();
  } catch {
    return "PDF";
  }
  try {
    segment = decodeURIComponent(segment);
  } catch {
    // A malformed escape: the segment as it is.
  }
  // eslint-disable-next-line no-control-regex
  const title = segment.replace(/[\u0000-\u001f\u007f]+/g, " ").trim().slice(0, MAX_TITLE_LENGTH);
  return title || "PDF";
}

/** The address a link annotation's `uri` opens, resolved against the PDF, or null. */
export function linkHref(uri, base) {
  if (typeof uri !== "string" || !uri || uri.length > MAX_LINK_LENGTH) return null;
  let url;
  try {
    url = new URL(uri.trim(), base);
  } catch {
    return null;
  }
  if (!LINK_SCHEMES.includes(url.protocol) || url.href.length > MAX_LINK_LENGTH) return null;
  return url.href;
}

/**
 * Where each page goes at `viewportWidth` CSS pixels: pages of `sizes` ([width, height] in
 * points) fit the width between margins, one under another. Returns the boxes of the pages that
 * fit under MAX_DOCUMENT_HEIGHT (`shown` of them) and the height of all.
 */
export function layoutPages(sizes, viewportWidth) {
  const available = Math.max(1, viewportWidth - 2 * PAGE_MARGIN);
  const pages = [];
  let top = PAGE_MARGIN;
  for (const [pointsWide, pointsHigh] of sizes) {
    const aspect = pointsWide > 0 && pointsHigh > 0 ? pointsHigh / pointsWide : 792 / 612;
    let width = available;
    let height = width * aspect;
    if (height > MAX_PAGE_HEIGHT) {
      height = MAX_PAGE_HEIGHT;
      width = Math.max(1, height / aspect);
    }
    width = Math.round(width);
    height = Math.max(1, Math.round(height));
    if (pages.length && top + height > MAX_DOCUMENT_HEIGHT) break;
    pages.push({ top, left: PAGE_MARGIN + Math.round((available - width) / 2), width, height });
    top += height + PAGE_MARGIN;
  }
  return { pages, shown: pages.length, height: top };
}

/** The index of the first page in `pages` that ends below `y` (pages.length if none does). */
export function firstPageEndingBelow(pages, y) {
  let low = 0;
  let high = pages.length;
  while (low < high) {
    const middle = (low + high) >> 1;
    if (pages[middle].top + pages[middle].height <= y) low = middle + 1;
    else high = middle;
  }
  return low;
}

/** The first and last page that overlap [from, to) in `pages`, or null. */
export function pagesBetween(pages, from, to) {
  const low = firstPageEndingBelow(pages, from);
  if (low >= pages.length || pages[low].top >= to) return null;
  let last = low;
  while (last + 1 < pages.length && pages[last + 1].top < to) last++;
  return [low, last];
}

/** The bitmap size for a page shown at `width` × `height` CSS pixels. */
export function bitmapSize(width, height, ratio) {
  const scale = Number.isFinite(ratio) && ratio > 0 ? ratio : 1;
  let pixelsWide = Math.max(1, Math.round(width * scale));
  let pixelsHigh = Math.max(1, Math.round(height * scale));
  const shrink = Math.min(
    1,
    Math.sqrt(MAX_PAGE_PIXELS / (pixelsWide * pixelsHigh)),
    MAX_BITMAP_SIDE / pixelsWide,
    MAX_BITMAP_SIDE / pixelsHigh,
  );
  if (shrink < 1) {
    pixelsWide = Math.max(1, Math.floor(pixelsWide * shrink));
    pixelsHigh = Math.max(1, Math.floor(pixelsHigh * shrink));
  }
  return { width: pixelsWide, height: pixelsHigh };
}

/**
 * How many pages may hold a bitmap: MAX_LIVE_PAGES, or every page on screen when more fit on it
 * (small pages), so the pages on screen never take each other's bitmaps in turn.
 */
export function bitmapBudget(visible) {
  return Math.max(MAX_LIVE_PAGES, visible ? visible[1] - visible[0] + 1 : 0);
}

/** Pages to draw first: those on screen from the top, then those below, then above. */
export function drawOrder(near, visible) {
  if (!near) return [];
  const order = [];
  const [first, last] = visible || near;
  for (let index = first; index <= last; index++) order.push(index);
  for (let index = last + 1; index <= near[1]; index++) order.push(index);
  for (let index = first - 1; index >= near[0]; index--) order.push(index);
  return order;
}

/** Requests to the PDFium worker, answered by id. */
export class PdfWorker {
  constructor(onFailure) {
    this.pending = new Map();
    this.nextId = 1;
    this.worker = new Worker(new URL("worker.mjs", import.meta.url).href, { type: "module" });
    this.worker.addEventListener("message", ({ data }) => {
      // Id 0: PDFium did not start.
      if (!data || data.id === 0) {
        onFailure((data && data.error) || { kind: "viewer", message: "no answer" });
        return;
      }
      const request = this.pending.get(data.id);
      if (!request) return;
      this.pending.delete(data.id);
      if (data.ok) request.resolve(data);
      else request.reject(data.error);
    });
    this.worker.addEventListener("error", (event) => {
      event.preventDefault();
      onFailure({ kind: "viewer", message: `worker: ${event.message || "failed to start"}` });
    });
  }

  request(op, values) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.worker.postMessage({ id, op, ...values });
    });
  }

  terminate() {
    this.worker.terminate();
    // Servo's terminate() only flags the worker and interrupts running script. An idle worker
    // waits for a message and keeps PDFium's wasm memory until one comes; woken, it sees the flag,
    // ends and drops its runtime. worker.mjs would ignore this empty message.
    this.worker.postMessage(null);
    for (const request of this.pending.values()) request.reject({ kind: "stopped", message: "stopped" });
    this.pending.clear();
  }
}

/** The viewer of one PDF document; exported for the host tests, which drive it on a fake page. */
export class Viewer {
  constructor(root) {
    this.root = root;
    this.worker = null;
    // Whether this.worker has the PDF open: pages are drawn only then.
    this.ready = false;
    this.stopped = false;
    this.problem = null;
    // From the worker once the PDF is open.
    this.count = 0;
    this.sizes = null;
    this.layout = null;
    this.layoutWidth = 0;
    // Pages that may hold a bitmap, most wanted first (drawOrder, cut to bitmapBudget).
    this.wanted = [];
    // Page index -> { element, canvas, drawn: "<width>x<height>" or "", failed, links }.
    this.views = new Map();
    // Bumped by every layout and restart, so a late bitmap for an old one is dropped.
    this.generation = 0;
    this.drawing = false;
    this.frameRequested = false;

    this.main = document.createElement("main");
    this.main.setAttribute("aria-label", TEXT.document);
    this.main.setAttribute("aria-busy", "true");
    this.pages = document.createElement("div");
    this.pages.className = "pages";
    this.pages.style.position = "relative";
    this.main.append(this.pages);
    this.status = document.createElement("div");
    this.status.className = "status";
    const body = document.body || root;
    // Also in viewer.css; set here so the page positions hold before the style sheet arrives.
    body.style.margin = "0";
    body.append(this.main, this.status);
  }

  start() {
    this.startWorker();
    const stylesheet = document.createElement("link");
    stylesheet.rel = "stylesheet";
    stylesheet.href = new URL("viewer.css", import.meta.url).href;
    document.head.append(stylesheet);
    document.title = fileTitle(document.URL);

    addEventListener("scroll", () => this.requestUpdate(), { passive: true });
    addEventListener("resize", () => this.relayout());
    addEventListener("pagehide", () => this.stop());
    addEventListener("pageshow", (event) => {
      if (event.persisted) this.resume();
    });

    // A small PDF can be complete before this module runs: read the state before watching it.
    if (!this.settle()) {
      const observer = new MutationObserver(() => {
        if (this.settle()) observer.disconnect();
      });
      observer.observe(this.root, { attributes: true, attributeFilter: ["data-pdf-state"] });
    }
  }

  startWorker() {
    this.ready = false;
    this.worker = new PdfWorker((error) => this.fail(error));
  }

  /** Acts on the engine's state; false while it is still loading. */
  settle() {
    const state = this.root.getAttribute("data-pdf-state");
    const length = byteCount(this.root.getAttribute("data-pdf-length"));
    switch (state) {
      case ENGINE_STATE.LOADING:
        this.showStatus(length === null ? TEXT.loading : TEXT.loadingSize(formatBytes(length)));
        return false;
      case ENGINE_STATE.READY:
        this.open();
        return true;
      case ENGINE_STATE.TOO_LARGE:
        this.endWorker();
        this.showProblem("too-large", length, byteCount(this.root.getAttribute("data-pdf-limit")) || 0);
        return true;
      case ENGINE_STATE.FAILED:
        this.endWorker();
        this.showProblem("failed");
        return true;
      default:
        // Not a state this viewer knows: nothing will come.
        this.endWorker();
        this.showProblem("viewer");
        return true;
    }
  }

  async open() {
    const src = this.root.getAttribute("data-pdf-src") || "";
    const worker = this.worker;
    if (!worker || !src.startsWith("blob:")) {
      this.fail({ kind: "viewer", message: "no PDF address" });
      return;
    }
    // PDFium may still be compiling.
    this.showStatus(TEXT.loading);
    let opened;
    try {
      opened = await worker.request("open", { src });
    } catch (error) {
      if (worker === this.worker) this.fail(error);
      return;
    }
    if (worker !== this.worker) return;
    if (!opened.count) {
      this.fail({ kind: "corrupt", message: "the PDF has no pages" });
      return;
    }
    if (opened.title) document.title = opened.title;
    this.count = opened.count;
    this.sizes = opened.pages;
    this.ready = true;
    // Also back from the back-forward cache: Servo fires no resize event for a change of the
    // screen's width while the page was away.
    this.relayout();
    this.hideStatus();
    this.main.setAttribute("aria-busy", "false");
    this.requestUpdate();
  }

  relayout() {
    if (!this.sizes) return;
    const width = document.documentElement.clientWidth || innerWidth;
    // Only the height changed (a keyboard, a bar): the same layout, other pages on screen.
    if (this.layout && width === this.layoutWidth) {
      this.requestUpdate();
      return;
    }
    // Keep the page and the point in it at the top of the screen.
    const anchor = this.anchor();
    this.layoutWidth = width;
    this.layout = layoutPages(this.sizes, width);
    this.generation++;
    this.pages.style.height = `${this.layout.height}px`;
    // A wider screen makes pages taller, so fewer may fit under MAX_DOCUMENT_HEIGHT.
    for (const [index, view] of this.views) {
      if (index >= this.layout.shown) this.remove(index, view);
      else this.place(index, view.element);
    }
    this.showMore();
    if (anchor) {
      const page = this.layout.pages[Math.min(anchor.index, this.layout.shown - 1)];
      scrollTo(0, page.top + anchor.fraction * page.height);
    }
    this.requestUpdate();
  }

  anchor() {
    if (!this.layout || scrollY <= 0) return null;
    const index = firstPageEndingBelow(this.layout.pages, scrollY);
    if (index >= this.layout.shown) return null;
    const page = this.layout.pages[index];
    return { index, fraction: Math.max(0, (scrollY - page.top) / page.height) };
  }

  showMore() {
    let note = this.main.querySelector(".more");
    if (this.layout.shown >= this.count) {
      if (note) note.remove();
      return;
    }
    if (!note) {
      note = document.createElement("p");
      note.className = "more";
      this.main.append(note);
    }
    note.textContent = TEXT.more(this.layout.shown, this.count);
  }

  requestUpdate() {
    if (this.frameRequested) return;
    this.frameRequested = true;
    requestAnimationFrame(() => {
      this.frameRequested = false;
      this.update();
    });
  }

  /** Adds the elements of pages near the screen, removes the others, and draws what is missing. */
  update() {
    if (this.stopped || !this.layout) return;
    const top = scrollY;
    const height = innerHeight;
    const near = pagesBetween(this.layout.pages, top - NEAR_SCREENS * height, top + (1 + NEAR_SCREENS) * height);
    for (const [index, view] of this.views) {
      if (!near || index < near[0] || index > near[1]) this.remove(index, view);
    }
    if (near) {
      for (let index = near[0]; index <= near[1]; index++) {
        if (!this.views.has(index)) this.add(index);
      }
    }
    const visible = pagesBetween(this.layout.pages, top, top + height);
    this.wanted = drawOrder(near, visible).slice(0, bitmapBudget(visible));
    this.drawNext();
  }

  add(index) {
    const element = document.createElement("section");
    element.className = "page";
    element.id = `page-${index + 1}`;
    element.setAttribute("aria-label", TEXT.page(index + 1, this.count));
    const canvas = document.createElement("canvas");
    canvas.width = 0;
    canvas.height = 0;
    canvas.setAttribute("role", "img");
    canvas.setAttribute("aria-label", TEXT.pageImage(index + 1));
    element.append(canvas);
    this.place(index, element);
    this.pages.append(element);
    this.views.set(index, { element, canvas, drawn: "", failed: false, links: false });
  }

  place(index, element) {
    const box = this.layout.pages[index];
    // CSSOM declarations, which the viewer's CSP allows (a style attribute in markup it would not).
    // The position is here rather than only in viewer.css, so pages stay apart without it.
    element.style.position = "absolute";
    element.style.top = `${box.top}px`;
    element.style.left = `${box.left}px`;
    element.style.width = `${box.width}px`;
    element.style.height = `${box.height}px`;
  }

  remove(index, view) {
    this.release(view);
    view.element.remove();
    this.views.delete(index);
  }

  release(view) {
    // Servo frees a canvas's pixmap and WebRender image when it is resized to a size it can paint;
    // a 0x0 canvas keeps both until garbage collection.
    view.canvas.width = 1;
    view.canvas.height = 1;
    view.drawn = "";
  }

  async drawNext() {
    if (this.drawing || this.stopped || !this.worker || !this.ready) return;
    const next = this.nextToDraw();
    if (!next) return;
    this.drawing = true;
    const { index, size, key } = next;
    const generation = this.generation;
    const worker = this.worker;
    try {
      const page = await worker.request("render", { index, width: size.width, height: size.height });
      const view = this.views.get(index);
      if (view && generation === this.generation && worker === this.worker) {
        this.paint(view, page, size, key);
        if (!view.links) this.addLinks(view, page.links);
      }
    } catch (error) {
      const view = this.views.get(index);
      if (error && error.kind !== "stopped" && view && worker === this.worker) this.pageFailed(index, view, error);
    } finally {
      this.drawing = false;
    }
    this.drawNext();
  }

  nextToDraw() {
    const ratio = window.devicePixelRatio || 1;
    for (const index of this.wanted) {
      const view = this.views.get(index);
      if (!view || view.failed) continue;
      const box = this.layout.pages[index];
      const size = bitmapSize(box.width, box.height, ratio);
      const key = `${size.width}x${size.height}`;
      if (view.drawn !== key) return { index, size, key };
    }
    return null;
  }

  paint(view, page, size, key) {
    this.releaseUnwanted();
    view.canvas.width = size.width;
    view.canvas.height = size.height;
    const pixels = new Uint8ClampedArray(page.pixels.buffer || page.pixels);
    view.canvas.getContext("2d").putImageData(new ImageData(pixels, size.width, size.height), 0, 0);
    view.drawn = key;
  }

  /** Releases the bitmaps of pages no longer among the wanted ones, before drawing another. */
  releaseUnwanted() {
    const wanted = new Set(this.wanted);
    for (const [index, view] of this.views) {
      if (view.drawn && !wanted.has(index)) this.release(view);
    }
  }

  addLinks(view, links) {
    view.links = true;
    for (const link of links || []) {
      const anchor = document.createElement("a");
      anchor.className = "link";
      if (Number.isInteger(link.page)) {
        if (link.page < 0 || link.page >= this.layout.shown) continue;
        anchor.href = `#page-${link.page + 1}`;
        anchor.setAttribute("aria-label", TEXT.goToPage(link.page + 1));
        // Scrolls without changing the address: the tab's address stays the PDF's.
        anchor.addEventListener("click", (event) => {
          event.preventDefault();
          // The screen may have widened since, and then fewer pages are shown.
          const page = this.layout.pages[link.page];
          if (page) scrollTo(0, page.top - PAGE_MARGIN);
        });
      } else {
        const href = linkHref(link.uri, document.URL);
        if (!href) continue;
        anchor.href = href;
        anchor.setAttribute("aria-label", href);
      }
      const [left, top, right, bottom] = link.rect;
      anchor.style.left = `${left * 100}%`;
      anchor.style.top = `${top * 100}%`;
      anchor.style.width = `${(right - left) * 100}%`;
      anchor.style.height = `${(bottom - top) * 100}%`;
      view.element.append(anchor);
    }
  }

  pageFailed(index, view, error) {
    console.error(`PDF page ${index + 1}: ${error.message}`);
    view.failed = true;
    this.release(view);
    const note = document.createElement("p");
    note.className = "page-error";
    note.textContent = TEXT.pageFailed(index + 1);
    view.element.append(note);
  }

  /** A failure of the whole viewer: the worker's, PDFium's or the PDF's. */
  fail(error) {
    if (this.stopped || this.problem) return;
    console.error(`PDF viewer: ${error && error.message}`);
    this.endWorker();
    this.showProblem(error && PROBLEM[error.kind] ? error.kind : "viewer");
  }

  endWorker() {
    if (this.worker) this.worker.terminate();
    this.worker = null;
  }

  showStatus(text) {
    this.status.replaceChildren();
    this.status.className = "status";
    this.status.setAttribute("role", "status");
    this.status.hidden = false;
    const spinner = document.createElement("div");
    spinner.className = "spinner";
    spinner.setAttribute("aria-hidden", "true");
    const message = document.createElement("p");
    message.textContent = text;
    this.status.append(spinner, message);
  }

  showProblem(kind, ...values) {
    this.problem = kind;
    const [heading, detail] = PROBLEM[kind](...values);
    this.pages.replaceChildren();
    this.views.clear();
    this.main.setAttribute("aria-busy", "false");
    this.status.replaceChildren();
    this.status.setAttribute("role", "alert");
    this.status.className = "status problem";
    this.status.hidden = false;
    const title = document.createElement("h1");
    title.textContent = heading;
    const text = document.createElement("p");
    text.textContent = detail;
    this.status.append(title, text);
  }

  hideStatus() {
    this.status.hidden = true;
    this.status.replaceChildren();
  }

  /** Leaving the page: end PDFium and drop every bitmap; the layout stays for a return. */
  stop() {
    this.stopped = true;
    this.endWorker();
    for (const [index, view] of this.views) this.remove(index, view);
  }

  /** Back to a page from the back-forward cache: the same blob: URL, read again. */
  resume() {
    if (!this.stopped) return;
    this.stopped = false;
    this.drawing = false;
    this.generation++;
    if (this.problem || this.root.getAttribute("data-pdf-state") !== ENGINE_STATE.READY) return;
    this.startWorker();
    this.open();
  }
}

// Imported by the host tests in Node, where there is no document.
if (typeof document !== "undefined" && document.documentElement.hasAttribute("data-pdf-state")) {
  new Viewer(document.documentElement).start();
}
