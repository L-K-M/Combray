// PDFium compiled to WebAssembly (@embedpdf/pdfium), in a module worker: whole pages are
// rasterised here and handed to the page as RGBA pixels, so the page makes one putImageData call
// per page instead of pdf.js's per-glyph canvas calls.
//
// Requests: { id, op, ...args }; answers: { id, ...result } or { id, error }.
import { init } from "./vendor/pdfium/package/dist/index.browser.js";

// FPDF_RenderPageBitmap flags (fpdfview.h): draw annotations.
const FPDF_ANNOT = 0x01;
const WHITE = 0xffffffff;

let pdfium = null;
// Document handle -> the heap copy of its bytes, which PDFium reads for the document's lifetime.
const documents = new Map();

const ops = {
  async init({ wasmUrl }) {
    const started = performance.now();
    const response = await fetch(wasmUrl);
    if (!response.ok) throw new Error(`wasm ${response.status}`);
    pdfium = await init({ wasmBinary: await response.arrayBuffer() });
    pdfium.PDFiumExt_Init();
    return { ms: performance.now() - started };
  },

  open({ bytes }) {
    const data = new Uint8Array(bytes);
    const pointer = pdfium.pdfium._malloc(data.length);
    pdfium.pdfium.HEAPU8.set(data, pointer);
    const doc = pdfium.FPDF_LoadMemDocument(pointer, data.length, "");
    if (!doc) {
      pdfium.pdfium._free(pointer);
      throw new Error(`FPDF_LoadMemDocument failed: error ${pdfium.FPDF_GetLastError()}`);
    }
    documents.set(doc, pointer);
    return { doc, pages: pdfium.FPDF_GetPageCount(doc) };
  },

  render({ doc, index, width }) {
    const started = performance.now();
    const page = pdfium.FPDF_LoadPage(doc, index);
    if (!page) throw new Error(`FPDF_LoadPage ${index} failed`);
    try {
      const scale = width / pdfium.FPDF_GetPageWidthF(page);
      const height = Math.max(1, Math.round(pdfium.FPDF_GetPageHeightF(page) * scale));
      // 0: no alpha channel, BGRx.
      const bitmap = pdfium.FPDFBitmap_Create(width, height, 0);
      try {
        pdfium.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, WHITE);
        pdfium.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, FPDF_ANNOT);
        const stride = pdfium.FPDFBitmap_GetStride(bitmap);
        const source = pdfium.FPDFBitmap_GetBuffer(bitmap);
        const heap = pdfium.pdfium.HEAPU8;
        const pixels = new Uint8ClampedArray(width * height * 4);
        for (let y = 0; y < height; y++) {
          let from = source + y * stride;
          let to = y * width * 4;
          for (let x = 0; x < width; x++, from += 4, to += 4) {
            pixels[to] = heap[from + 2];
            pixels[to + 1] = heap[from + 1];
            pixels[to + 2] = heap[from];
            pixels[to + 3] = 255;
          }
        }
        return { width, height, pixels, ms: performance.now() - started, transfer: [pixels.buffer] };
      } finally {
        pdfium.FPDFBitmap_Destroy(bitmap);
      }
    } finally {
      pdfium.FPDF_ClosePage(page);
    }
  },

  text({ doc, index }) {
    const started = performance.now();
    const page = pdfium.FPDF_LoadPage(doc, index);
    if (!page) throw new Error(`FPDF_LoadPage ${index} failed`);
    const textPage = pdfium.FPDFText_LoadPage(page);
    try {
      const count = pdfium.FPDFText_CountChars(textPage);
      if (count <= 0) return { text: "", ms: performance.now() - started };
      const bytes = (count + 1) * 2;
      const buffer = pdfium.pdfium._malloc(bytes);
      try {
        pdfium.pdfium.HEAPU8.fill(0, buffer, buffer + bytes);
        pdfium.FPDFText_GetText(textPage, 0, count, buffer);
        return { text: pdfium.pdfium.UTF16ToString(buffer, bytes), ms: performance.now() - started };
      } finally {
        pdfium.pdfium._free(buffer);
      }
    } finally {
      pdfium.FPDFText_ClosePage(textPage);
      pdfium.FPDF_ClosePage(page);
    }
  },

  close({ doc }) {
    pdfium.FPDF_CloseDocument(doc);
    pdfium.pdfium._free(documents.get(doc));
    documents.delete(doc);
    return {};
  },
};

self.addEventListener("message", async ({ data }) => {
  try {
    const { transfer = [], ...result } = await ops[data.op](data);
    self.postMessage({ id: data.id, ...result }, transfer);
  } catch (error) {
    self.postMessage({ id: data.id, error: String(error && error.stack || error) });
  }
});
