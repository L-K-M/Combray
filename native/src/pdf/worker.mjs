// Combray's PDF worker: PDFium compiled to WebAssembly (@embedpdf/pdfium 2.15.1), one instance
// per viewer document (docs/PDF.md). The viewer starts it at once, so PDFium compiles while the
// PDF still downloads; then the worker reads the PDF from the blob: URL the engine made, renders
// pages to RGBA and reads their link annotations.
//
//   viewer.mjs                         worker.mjs
//   new Worker ────────────────────▶  import pdfium/index.browser.js, fetch pdfium/pdfium.wasm,
//                                      compile and initialise PDFium
//   { id, op: "open", src } ───────▶  fetch(src), copy into the wasm heap, FPDF_LoadMemDocument
//                           ◀───────  { id, ok, count, pages: [[width, height], ...], title }
//   { id, op: "render", index, ───▶  FPDF_RenderPageBitmap in RGBA, links of the page
//     width, height }       ◀───────  { id, ok, pixels (transferred), links }
//
// A failure answers { id, ok: false, error: { kind, message } }; kind is one of ERROR. The worker
// keeps the document open until the viewer ends it on pagehide (terminate), which frees the wasm
// memory with it.
//
// The page and this worker share one opaque origin and the viewer's CSP (the scheme sends it with
// this file): scripts and fetches only from the viewer's own origin and the blob: URL, and
// WebAssembly compilation but no eval.

// A static import, as in the probe that ran PDFium in Servo (docs/PDF.md). It resolves against
// this file's address: combray-pdf://viewer-<token>/pdfium/index.browser.js.
import { init } from "./pdfium/index.browser.js";

/** Why a request failed, as the viewer shows it. */
export const ERROR = Object.freeze({
  /** Not a PDF, or damaged beyond what PDFium repairs. */
  CORRUPT: "corrupt",
  /** Needs a password or a security handler PDFium lacks. */
  PROTECTED: "protected",
  /** The wasm heap could not hold the PDF or a page. */
  MEMORY: "memory",
  /** PDFium did not start, the PDF could not be read, or a page did not draw. */
  VIEWER: "viewer",
});

// FPDF_GetLastError (fpdfview.h).
const FPDF_ERR_PASSWORD = 4;
const FPDF_ERR_SECURITY = 5;
// FPDF_RenderPageBitmap flags (fpdfview.h): draw annotations, and write RGBA instead of BGRA, so
// the bytes go to ImageData unchanged.
const FPDF_ANNOT = 0x01;
const FPDF_REVERSE_BYTE_ORDER = 0x10;
// FPDFAction_GetType (fpdf_doc.h).
const PDFACTION_GOTO = 1;
const PDFACTION_URI = 3;
const OPAQUE_WHITE = 0xffffffff;
const BYTES_PER_PIXEL = 4;
/** Device units across a page when mapping link rectangles to fractions of it. */
const LINK_GRID = 10000;
/** Pages the viewer lays out; sizes past it are not read. */
export const MAX_PAGES = 10000;
/** Links kept per page; a PDF can hold any number, and each becomes an element. */
export const MAX_LINKS_PER_PAGE = 500;
/** Link annotations looked at per page, kept or not. */
const MAX_LINK_ANNOTATIONS = 4 * MAX_LINKS_PER_PAGE;
/** Longest link URI, in bytes (the engine's MAX_URL_BYTES for captured addresses). */
export const MAX_URI_BYTES = 8 * 1024;
/** Longest title taken from the PDF's metadata, in UTF-16 code units. */
export const MAX_TITLE_LENGTH = 1000;

class ViewerError extends Error {
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

/** The error kind for FPDF_GetLastError's `code` after a document failed to load. */
export function loadErrorKind(code) {
  return code === FPDF_ERR_PASSWORD || code === FPDF_ERR_SECURITY ? ERROR.PROTECTED : ERROR.CORRUPT;
}

/** A metadata title as the viewer's title: control characters out, spaces collapsed, bounded. */
export function cleanTitle(raw) {
  // eslint-disable-next-line no-control-regex
  const title = String(raw).replace(/[\u0000-\u001f\u007f]+/g, " ").replace(/\s+/g, " ").trim();
  return title.slice(0, MAX_TITLE_LENGTH);
}

/**
 * The PDF document operations over an initialised @embedpdf/pdfium module. One document at a
 * time; `open` closes the one before.
 */
export class PdfDocument {
  constructor(pdfium) {
    this.pdfium = pdfium;
    this.module = pdfium.pdfium;
    this.document = 0;
    // PDFium reads the bytes in place for the document's whole life.
    this.bytes = 0;
  }

  /**
   * Opens `data` (a Uint8Array). Returns the number of pages, the sizes in points of the first
   * MAX_PAGES and the metadata title.
   */
  open(data) {
    this.close();
    const { pdfium, module } = this;
    const bytes = module._malloc(Math.max(1, data.length));
    if (!bytes) throw new ViewerError(ERROR.MEMORY, "no memory for the PDF");
    module.HEAPU8.set(data, bytes);
    const document = pdfium.FPDF_LoadMemDocument(bytes, data.length, "");
    if (!document) {
      const code = pdfium.FPDF_GetLastError();
      module._free(bytes);
      throw new ViewerError(loadErrorKind(code), `PDFium could not open the PDF (error ${code})`);
    }
    this.document = document;
    this.bytes = bytes;

    const count = pdfium.FPDF_GetPageCount(document);
    const pages = [];
    const size = module._malloc(8);
    if (!size) throw new ViewerError(ERROR.MEMORY, "no memory for a page size");
    try {
      for (let index = 0; index < Math.min(count, MAX_PAGES); index++) {
        // A page whose size PDFium cannot read keeps US Letter, so the others stay in place.
        const known = pdfium.FPDF_GetPageSizeByIndexF(document, index, size);
        const width = known ? module.getValue(size, "float") : 612;
        const height = known ? module.getValue(size + 4, "float") : 792;
        pages.push([width, height]);
      }
    } finally {
      module._free(size);
    }
    return { count, pages, title: this.metaText("Title") };
  }

  /** The document's Info entry `tag`, cleaned; "" when absent. */
  metaText(tag) {
    const { pdfium, module } = this;
    // UTF-16LE with a two-byte terminator; 2 or less means empty.
    const length = pdfium.FPDF_GetMetaText(this.document, tag, 0, 0);
    if (length <= 2) return "";
    const buffer = module._malloc(length);
    if (!buffer) return "";
    try {
      pdfium.FPDF_GetMetaText(this.document, tag, buffer, length);
      return cleanTitle(module.UTF16ToString(buffer, length));
    } finally {
      module._free(buffer);
    }
  }

  /**
   * Page `index` drawn at `width` × `height` pixels as RGBA, and its links: `rect` is
   * [left, top, right, bottom] in fractions of the page, then `uri` (unchecked) or `page`.
   */
  render(index, width, height) {
    const { pdfium, module } = this;
    const page = pdfium.FPDF_LoadPage(this.document, index);
    if (!page) throw new ViewerError(ERROR.VIEWER, `PDFium could not load page ${index + 1}`);
    try {
      const pixels = this.draw(page, width, height);
      return { pixels, links: this.links(page) };
    } finally {
      pdfium.FPDF_ClosePage(page);
    }
  }

  draw(page, width, height) {
    const { pdfium, module } = this;
    // No alpha channel: the white fill makes every pixel opaque.
    const bitmap = pdfium.FPDFBitmap_Create(width, height, 0);
    if (!bitmap) throw new ViewerError(ERROR.MEMORY, `no memory for a ${width}×${height} page`);
    try {
      pdfium.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, OPAQUE_WHITE);
      pdfium.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0,
        FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER);
      const stride = pdfium.FPDFBitmap_GetStride(bitmap);
      const buffer = pdfium.FPDFBitmap_GetBuffer(bitmap);
      const row = width * BYTES_PER_PIXEL;
      // The heap view is read after every allocation: growing the memory replaces it.
      const heap = module.HEAPU8;
      if (stride === row) return heap.slice(buffer, buffer + row * height);
      const pixels = new Uint8Array(row * height);
      for (let y = 0; y < height; y++) {
        pixels.set(heap.subarray(buffer + y * stride, buffer + y * stride + row), y * row);
      }
      return pixels;
    } finally {
      pdfium.FPDFBitmap_Destroy(bitmap);
    }
  }

  links(page) {
    const { pdfium, module } = this;
    const found = [];
    // start_pos, the link handle, an FS_RECTF and two device coordinates.
    const scratch = module._malloc(4 + 4 + 16 + 8);
    if (!scratch) return found;
    const position = scratch;
    const handle = scratch + 4;
    const rect = scratch + 8;
    const device = scratch + 24;
    try {
      module.setValue(position, 0, "i32");
      let seen = 0;
      while (found.length < MAX_LINKS_PER_PAGE && seen++ < MAX_LINK_ANNOTATIONS &&
        pdfium.FPDFLink_Enumerate(page, position, handle)) {
        const link = module.getValue(handle, "i32");
        if (!pdfium.FPDFLink_GetAnnotRect(link, rect)) continue;
        const target = this.linkTarget(link);
        if (!target) continue;
        // FS_RECTF is left, top, right, bottom in page space; map two corners as rendering does,
        // so rotated and offset pages line up.
        const corner = (x, y) => {
          pdfium.FPDF_PageToDevice(page, 0, 0, LINK_GRID, LINK_GRID, 0, x, y, device, device + 4);
          return [module.getValue(device, "i32") / LINK_GRID, module.getValue(device + 4, "i32") / LINK_GRID];
        };
        const [x0, y0] = corner(module.getValue(rect, "float"), module.getValue(rect + 4, "float"));
        const [x1, y1] = corner(module.getValue(rect + 8, "float"), module.getValue(rect + 12, "float"));
        const box = [Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)];
        found.push({ rect: box.map((value) => Math.min(1, Math.max(0, value))), ...target });
      }
    } finally {
      module._free(scratch);
    }
    return found;
  }

  /** `{ uri }` for a URI action, `{ page }` for a destination in this document, else null. */
  linkTarget(link) {
    const { pdfium, module } = this;
    const action = pdfium.FPDFLink_GetAction(link);
    let destination = 0;
    if (action) {
      const type = pdfium.FPDFAction_GetType(action);
      if (type === PDFACTION_URI) {
        const length = pdfium.FPDFAction_GetURIPath(this.document, action, 0, 0);
        if (length <= 1 || length > MAX_URI_BYTES + 1) return null;
        const buffer = module._malloc(length);
        if (!buffer) return null;
        try {
          pdfium.FPDFAction_GetURIPath(this.document, action, buffer, length);
          return { uri: module.UTF8ToString(buffer, length) };
        } finally {
          module._free(buffer);
        }
      }
      // Launch, remote and embedded go-to and every other action are ignored.
      if (type !== PDFACTION_GOTO) return null;
      destination = pdfium.FPDFAction_GetDest(this.document, action);
    } else {
      destination = pdfium.FPDFLink_GetDest(this.document, link);
    }
    if (!destination) return null;
    const page = pdfium.FPDFDest_GetDestPageIndex(this.document, destination);
    return page >= 0 ? { page } : null;
  }

  close() {
    if (this.document) this.pdfium.FPDF_CloseDocument(this.document);
    if (this.bytes) this.module._free(this.bytes);
    this.document = 0;
    this.bytes = 0;
  }
}

/** Compiles and initialises PDFium from the viewer's own origin, never the package's CDN. */
async function startPdfium() {
  const response = await fetch(new URL("pdfium/pdfium.wasm", import.meta.url));
  if (!response.ok) throw new Error(`pdfium.wasm: HTTP ${response.status}`);
  const pdfium = await init({ wasmBinary: await response.arrayBuffer() });
  pdfium.PDFiumExt_Init();
  return new PdfDocument(pdfium);
}

async function readPdf(src) {
  const response = await fetch(src);
  if (!response.ok) throw new ViewerError(ERROR.VIEWER, `the PDF could not be read (${response.status})`);
  return new Uint8Array(await response.arrayBuffer());
}

function serveRequests() {
  const pdf = startPdfium();
  // Answered before the first request, so a failed start shows even while the PDF downloads.
  pdf.catch((error) => self.postMessage({ id: 0, ok: false, error: describe(error) }));

  const operations = {
    async open({ src }) {
      const [document, data] = await Promise.all([pdf, readPdf(src)]);
      return { result: document.open(data) };
    },
    async render({ index, width, height }) {
      const { pixels, links } = (await pdf).render(index, width, height);
      return { result: { pixels, links }, transfer: [pixels.buffer] };
    },
  };

  self.addEventListener("message", async ({ data }) => {
    const operation = operations[data && data.op];
    if (!operation) return;
    try {
      const { result, transfer = [] } = await operation(data);
      self.postMessage({ id: data.id, ok: true, ...result }, transfer);
    } catch (error) {
      self.postMessage({ id: data.id, ok: false, error: describe(error) });
    }
  });
}

function describe(error) {
  const kind = error instanceof ViewerError ? error.kind : ERROR.VIEWER;
  return { kind, message: String((error && error.message) || error) };
}

// Imported by the host tests in Node (next to an inflated PDFium), where there is no worker scope.
if (typeof WorkerGlobalScope !== "undefined" && self instanceof WorkerGlobalScope) serveRequests();
