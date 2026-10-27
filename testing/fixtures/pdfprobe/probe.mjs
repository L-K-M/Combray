// Combray PDF probe (docs/PDF.md, phase 0b): can Servo render PDFs with pdf.js, or with PDFium
// compiled to WebAssembly, fast enough and within memory on a phone?
//
//   index.html?engine=pdfjs | pdfjs-legacy | pdfium
//
// Served under /pdfprobe/strict/ (with the viewer's Content-Security-Policy) or /pdfprobe/open/.
// Results go to the fixture server (POST /_admin/probe-report); the device check reads the
// title: PROBE:checkpoint:<name> while it samples memory (the page waits on
// ./checkpoint until it is released), PROBE:done at the end, PROBE:fail:<why> if the
// harness itself broke. A document that fails is a result, not a harness failure.
import { detect } from "./features.mjs";
import { installPolyfills } from "./polyfills.mjs";

const params = new URLSearchParams(location.search);
const ENGINE = params.get("engine") || "pdfjs";
// fonts=off: pdf.js draws glyphs as paths instead of loading each embedded font as a FontFace.
const FONT_FACES = params.get("fonts") !== "off";
// rounds=N: the corpus runs N times, to tell a one-off cost from memory that keeps growing.
const ROUNDS = Math.max(1, Math.min(3, Number(params.get("rounds")) || 1));
// How this run is named in reports and checkpoints.
const LABEL = ENGINE + (FONT_FACES ? "" : "-nofontface") + (ROUNDS > 1 ? `-x${ROUNDS}` : "");
const CSP = location.pathname.includes("/strict/") ? "strict" : "open";
const BASE = new URL("./", import.meta.url).href;
const PDFJS = `${BASE}vendor/pdfjs/package/`;

// A phone-width page in device pixels (412 CSS px at a density of 2.625).
const PAGE_WIDTH = 1080;
const SNAPSHOT_WIDTH = 540;
const DOC_TIMEOUT_MS = 120_000;
const WORKER_TIMEOUT_MS = 10_000;
const CORPUS_PAGES = 3;
const MANY_PAGES = 50;
const KEEP_CANVASES = 3;
const TEXT_BUDGET_MS = 60_000;
// Bounds for a slow engine, so all four probes fit in the CI job: later pages of a corpus
// document are skipped once it has taken this long, and the long document stops drawing here.
const DOCUMENT_BUDGET_MS = 30_000;
const MANY_PAGES_BUDGET_MS = 300_000;
const SETTLE_MS = 3_000;
// The whole run stops starting new work after this, keeping time for the three-open step and the
// last memory samples before the device check gives up (25 minutes).
const RUN_DEADLINE_MS = 18 * 60_000;
const THREE_OPEN_RESERVE_MS = 4 * 60_000;
const runStarted = performance.now();
const timeLeft = () => runStarted + RUN_DEADLINE_MS - performance.now();

const CORPUS = [
  { name: "fixture", url: "generated/fixture.pdf", kind: "standard font (Helvetica), not embedded", expect: /PDF-FIXTURE-TEXT/ },
  { name: "cjk", url: "generated/cjk.pdf", kind: "Japanese, non-embedded CID font, UniJIS-UCS2-H CMap", expect: /日本語/ },
  { name: "tracemonkey", url: "vendor/corpus/tracemonkey.pdf", kind: "embedded Type1, 14 pages", expect: /Trace-based Just-in-Time/ },
  { name: "TAMReview", url: "vendor/corpus/TAMReview.pdf", kind: "ICCBased colours, embedded fonts, 23 pages", expect: /Technology Acceptance Model/ },
  { name: "ArabicCIDTrueType", url: "vendor/corpus/ArabicCIDTrueType.pdf", kind: "CID TrueType, right to left", expect: /[\u0600-\u06FF\uFE70-\uFEFF]/ },
  { name: "jp2k-resetprob", url: "vendor/corpus/jp2k-resetprob.pdf", kind: "JPEG 2000 (OpenJPEG wasm)" },
  { name: "jpx_smaskindata", url: "vendor/corpus/jpx_smaskindata.pdf", kind: "JPEG 2000 with SMaskInData" },
  { name: "bitmap-symbol", url: "vendor/corpus/bitmap-symbol.pdf", kind: "JBIG2 (wasm)" },
  { name: "function_based_shading", url: "vendor/corpus/function_based_shading.pdf", kind: "function-based shading" },
  { name: "axial_shading_many_inputs", url: "vendor/corpus/axial_shading_many_inputs.pdf", kind: "axial shading" },
  // Without ImageDecoder and OffscreenCanvas (both off in Servo) pdf.js decodes JPEG in JS.
  { name: "issue19971", url: "vendor/corpus/issue19971.pdf", kind: "JPEG photo 2500x1364 with ICC profile, 6 pages" },
];
const MANY = "generated/pages-300.pdf";
const THREE = ["vendor/corpus/tracemonkey.pdf", "vendor/corpus/TAMReview.pdf", MANY];

const pagesElement = document.getElementById("pages");
const logElement = document.getElementById("log");

// What the page logs (pdf.js warns on the main thread too).
const pageLog = [];
for (const level of ["log", "warn", "error"]) {
  const original = console[level];
  console[level] = (...args) => {
    if (pageLog.length < 300) pageLog.push(`${level}: ${args.map(String).join(" ")}`.slice(0, 400));
    original.apply(console, args);
  };
}
addEventListener("error", event => pageLog.push(`uncaught: ${event.message}`));
addEventListener("unhandledrejection", event => pageLog.push(`unhandled rejection: ${event.reason}`));

function show(line) {
  logElement.textContent += `${line}\n`;
}

// Sent as the run goes, so a run the device check gives up on still has its log.
async function flushPageLog(after) {
  if (pageLog.length) await report("page-log", { after, lines: pageLog.splice(0) });
}

function status(text) {
  document.title = `PROBE:${text}`;
}

function errorText(error) {
  return String(error && error.stack ? `${error}\n${error.stack}` : error).slice(0, 2000);
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label}: no answer in ${ms} ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function report(kind, data) {
  const body = JSON.stringify({ engine: LABEL, csp: CSP, kind, ...data });
  try {
    await fetch("/_admin/probe-report", { method: "POST", headers: { "Content-Type": "application/json" }, body });
  } catch (error) {
    show(`report failed: ${error}`);
  }
}

// Holds the page while the device check samples memory: as it is, then (where Servo offers
// TestUtils.gc) after garbage collection, so retained memory shows apart from garbage.
async function checkpoint(name) {
  await hold(name);
  if (typeof TestUtils !== "object" || typeof TestUtils.gc !== "function") return;
  await TestUtils.gc();
  await TestUtils.gc();
  await sleep(SETTLE_MS);
  await hold(`${name}-gc`);
}

async function hold(name) {
  status(`checkpoint:${name}`);
  show(`checkpoint ${name}`);
  const response = await fetch(new URL(`checkpoint?engine=${LABEL}&csp=${CSP}&name=${name}`, BASE));
  await response.text();
}

function statistics(times) {
  if (!times.length) return null;
  const sorted = [...times].sort((a, b) => a - b);
  const at = fraction => sorted[Math.min(sorted.length - 1, Math.floor(fraction * sorted.length))];
  const round = value => Math.round(value);
  return { count: times.length, median: round(at(0.5)), p90: round(at(0.9)), max: round(sorted[sorted.length - 1]), total: round(times.reduce((a, b) => a + b, 0)) };
}

// Share of sampled pixels that are not near white: 0 for a blank page.
function inkRatio(canvas) {
  if (!canvas.width || !canvas.height) return 0;
  const { data } = canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height);
  const STEP = 4;
  let inked = 0;
  let sampled = 0;
  for (let y = 0; y < canvas.height; y += STEP) {
    for (let x = 0; x < canvas.width; x += STEP) {
      const at = (y * canvas.width + x) * 4;
      sampled++;
      if (data[at] < 230 || data[at + 1] < 230 || data[at + 2] < 230) inked++;
    }
  }
  return Math.round((inked / sampled) * 10000) / 10000;
}

function snapshot(canvas) {
  if (!canvas.width || !canvas.height) return null;
  const small = document.createElement("canvas");
  small.width = SNAPSHOT_WIDTH;
  small.height = Math.max(1, Math.round((canvas.height * SNAPSHOT_WIDTH) / canvas.width));
  small.getContext("2d").drawImage(canvas, 0, 0, small.width, small.height);
  return small.toDataURL("image/png");
}

function newCanvas() {
  const canvas = document.createElement("canvas");
  pagesElement.appendChild(canvas);
  return canvas;
}

function dropCanvas(canvas) {
  canvas.width = 0;
  canvas.height = 0;
  canvas.remove();
}

// --- Engines -----------------------------------------------------------------------------------

class PdfjsEngine {
  constructor(legacy) {
    this.legacy = legacy;
  }

  async init() {
    const started = performance.now();
    this.lib = await import(this.legacy ? "./vendor/pdfjs/package/legacy/build/pdf.mjs" : "./vendor/pdfjs/package/build/pdf.mjs");
    this.lib.GlobalWorkerOptions.workerSrc = new URL(this.legacy ? "./probe-worker-legacy.mjs" : "./probe-worker.mjs", import.meta.url).href;
    return { ms: performance.now() - started, version: this.lib.version };
  }

  async open(url) {
    const task = this.lib.getDocument({
      url,
      cMapUrl: `${PDFJS}cmaps/`,
      cMapPacked: true,
      standardFontDataUrl: `${PDFJS}standard_fonts/`,
      wasmUrl: `${PDFJS}wasm/`,
      iccUrl: `${PDFJS}iccs/`,
      enableXfa: false,
      disableFontFace: !FONT_FACES,
      verbosity: this.lib.VerbosityLevel.WARNINGS,
    });
    try {
      const doc = await task.promise;
      return { task, doc, pages: doc.numPages };
    } catch (error) {
      await task.destroy().catch(() => {});
      throw error;
    }
  }

  realWorker(handle) {
    const port = handle.task._worker && handle.task._worker.port;
    return typeof Worker === "function" && port instanceof Worker;
  }

  async render(handle, index, canvas) {
    const page = await handle.doc.getPage(index + 1);
    const scale = PAGE_WIDTH / page.getViewport({ scale: 1 }).width;
    const viewport = page.getViewport({ scale });
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    await page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
    page.cleanup();
  }

  async text(handle, index) {
    const page = await handle.doc.getPage(index + 1);
    const content = await page.getTextContent();
    page.cleanup();
    return content.items.map(item => item.str || "").join(" ");
  }

  async workerLog(handle) {
    const port = handle.task._worker && handle.task._worker.port;
    if (!(typeof Worker === "function" && port instanceof Worker)) return [];
    return withTimeout(new Promise(resolve => {
      const listener = ({ data }) => {
        if (!data || !data.probeLog) return;
        port.removeEventListener("message", listener);
        resolve(data.probeLog);
      };
      port.addEventListener("message", listener);
      port.postMessage({ probeFlush: true });
    }), WORKER_TIMEOUT_MS, "worker log").catch(error => [`no worker log: ${error.message}`]);
  }

  // A wedged worker never answers pdf.js's Terminate message: stop waiting and kill it.
  async close(handle) {
    try {
      await withTimeout(handle.task.destroy(), WORKER_TIMEOUT_MS, "close");
    } catch (error) {
      if (handle.task._worker) handle.task._worker.destroy();
      throw error;
    }
  }
}

// One worker and one wasm instance per document, as pdf.js starts one worker per document, so
// open times and memory compare like for like.
class PdfiumInstance {
  constructor() {
    this.pending = new Map();
    this.nextId = 1;
    this.errors = [];
  }

  async start() {
    this.worker = new Worker(new URL("./pdfium-worker.mjs", import.meta.url), { type: "module" });
    this.worker.addEventListener("error", event => this.errors.push(`worker error: ${event.message}`));
    this.worker.addEventListener("message", ({ data }) => {
      const pending = this.pending.get(data.id);
      if (!pending) return;
      this.pending.delete(data.id);
      if (data.error) pending.reject(new Error(data.error));
      else pending.resolve(data);
    });
    await this.call("init", { wasmUrl: new URL("./vendor/pdfium/package/dist/pdfium.wasm", import.meta.url).href });
  }

  call(op, args = {}, transfer = []) {
    const id = this.nextId++;
    return withTimeout(new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.worker.postMessage({ id, op, ...args }, transfer);
    }), DOC_TIMEOUT_MS, `pdfium ${op}`);
  }

  stop() {
    this.worker.terminate();
    for (const pending of this.pending.values()) pending.reject(new Error("worker stopped"));
    this.pending.clear();
  }
}

class PdfiumEngine {
  // Starts and stops one instance: how long the worker and wasm take to come up.
  async init() {
    const started = performance.now();
    const instance = new PdfiumInstance();
    try {
      await instance.start();
    } finally {
      instance.stop();
    }
    return { ms: performance.now() - started, version: "@embedpdf/pdfium 2.15.1" };
  }

  async open(url) {
    const instance = new PdfiumInstance();
    try {
      await instance.start();
      const response = await fetch(url);
      if (!response.ok) throw new Error(`${url}: ${response.status}`);
      const bytes = await response.arrayBuffer();
      const { doc, pages } = await instance.call("open", { bytes }, [bytes]);
      return { instance, doc, pages };
    } catch (error) {
      instance.stop();
      throw error;
    }
  }

  realWorker() {
    return true;
  }

  async render(handle, index, canvas) {
    const { width, height, pixels } = await handle.instance.call("render", { doc: handle.doc, index, width: PAGE_WIDTH });
    canvas.width = width;
    canvas.height = height;
    canvas.getContext("2d").putImageData(new ImageData(pixels, width, height), 0, 0);
  }

  async text(handle, index) {
    return (await handle.instance.call("text", { doc: handle.doc, index })).text;
  }

  async workerLog(handle) {
    return handle.instance.errors.splice(0);
  }

  async close(handle) {
    try {
      await handle.instance.call("close", { doc: handle.doc });
    } finally {
      handle.instance.stop();
    }
  }
}

// --- Steps -------------------------------------------------------------------------------------

async function features() {
  const page = await detect();
  let worker;
  try {
    const probe = new Worker(new URL("./feature-worker.mjs", import.meta.url), { type: "module" });
    worker = await withTimeout(new Promise((resolve, reject) => {
      probe.addEventListener("message", ({ data }) => (data.error ? reject(new Error(data.error)) : resolve(data.features)));
      probe.addEventListener("error", event => reject(new Error(`worker error: ${event.message}`)));
    }), WORKER_TIMEOUT_MS, "module worker");
    probe.terminate();
  } catch (error) {
    worker = { error: errorText(error) };
  }
  return { page, worker, fontFace: await fontFaceFromBytes(), canvasCjk: canvasCjk() };
}

// Japanese drawn with canvas text in a generic family: a PDF's non-embedded CJK font ends up
// here. The snapshot shows glyphs or empty boxes.
function canvasCjk() {
  try {
    const canvas = document.createElement("canvas");
    canvas.width = 480;
    canvas.height = 80;
    const context = canvas.getContext("2d");
    context.fillStyle = "#fff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#000";
    context.font = "48px sans-serif";
    context.fillText("日本語の文書", 10, 60);
    return { ink: inkRatio(canvas), width: context.measureText("日本語の文書").width, snapshot: canvas.toDataURL("image/png") };
  } catch (error) {
    return { error: errorText(error) };
  }
}

// pdf.js loads embedded fonts as FontFace objects made from bytes; a face that loads must also
// change what canvas text measures.
async function fontFaceFromBytes() {
  try {
    const ttf = await (await fetch(`${PDFJS}standard_fonts/LiberationSans-Regular.ttf`)).arrayBuffer();
    const face = new FontFace("ProbeLiberation", ttf);
    await withTimeout(face.load(), WORKER_TIMEOUT_MS, "FontFace.load");
    document.fonts.add(face);
    const context = document.createElement("canvas").getContext("2d");
    const sample = "Probe WWW iii 0123";
    context.font = "40px monospace";
    const fallback = context.measureText(sample).width;
    context.font = "40px ProbeLiberation, monospace";
    const loaded = context.measureText(sample).width;
    return { status: face.status, canvasUsesFace: Math.abs(loaded - fallback) > 1, widths: [fallback, loaded] };
  } catch (error) {
    return { error: errorText(error) };
  }
}

async function runDocument(engine, entry) {
  const result = { name: entry.name, feature: entry.kind };
  const started = performance.now();
  let handle = null;
  const canvases = [];
  try {
    handle = await withTimeout(engine.open(new URL(entry.url, BASE).href), DOC_TIMEOUT_MS, "open");
    result.openMs = Math.round(performance.now() - started);
    result.pages = handle.pages;
    result.realWorker = engine.realWorker(handle);

    const times = [];
    for (let index = 0; index < Math.min(handle.pages, CORPUS_PAGES); index++) {
      if (index > 0 && performance.now() - started > DOCUMENT_BUDGET_MS) break;
      const canvas = newCanvas();
      canvases.push(canvas);
      const pageStarted = performance.now();
      await withTimeout(engine.render(handle, index, canvas), DOC_TIMEOUT_MS, `render page ${index + 1}`);
      // Servo's canvas thread rasterises lazily: reading a pixel waits for the page's pixels, so
      // the time covers drawing as PDFium's (which rasterises in its worker) does.
      if (canvas.width && canvas.height) canvas.getContext("2d").getImageData(0, 0, 1, 1);
      times.push(performance.now() - pageStarted);
      if (index === 0) {
        result.ink = inkRatio(canvas);
        result.snapshot = snapshot(canvas);
      }
    }
    result.renderMs = times.map(Math.round);

    const textStarted = performance.now();
    const text = await withTimeout(engine.text(handle, 0), DOC_TIMEOUT_MS, "text");
    result.textMs = Math.round(performance.now() - textStarted);
    result.textChars = text.length;
    result.textSample = text.slice(0, 200);
    if (entry.expect) result.textFound = entry.expect.test(text);
  } catch (error) {
    result.error = errorText(error);
  }
  if (handle) {
    result.workerLog = await engine.workerLog(handle);
    await engine.close(handle).catch(error => { result.closeError = errorText(error); });
  }
  canvases.forEach(dropCanvas);
  result.totalMs = Math.round(performance.now() - started);
  return result;
}

// Fifty pages of one long document, as scrolling through it would draw them, keeping only the
// last few canvases as a viewer would; then the text of every page, as capture or Find needs.
async function manyPages(engine) {
  const result = { name: "pages-300" };
  let handle = null;
  const canvases = [];
  try {
    const started = performance.now();
    handle = await withTimeout(engine.open(new URL(MANY, BASE).href), DOC_TIMEOUT_MS, "open");
    result.openMs = Math.round(performance.now() - started);
    result.pages = handle.pages;
    const times = [];
    const drawStarted = performance.now();
    const drawBudget = Math.min(MANY_PAGES_BUDGET_MS, timeLeft() - THREE_OPEN_RESERVE_MS);
    for (let index = 0; index < Math.min(handle.pages, MANY_PAGES); index++) {
      if (performance.now() - drawStarted > drawBudget) break;
      status(`running:page-${index + 1}`);
      const canvas = newCanvas();
      canvases.push(canvas);
      const pageStarted = performance.now();
      await withTimeout(engine.render(handle, index, canvas), DOC_TIMEOUT_MS, `render page ${index + 1}`);
      times.push(performance.now() - pageStarted);
      while (canvases.length > KEEP_CANVASES) dropCanvas(canvases.shift());
    }
    result.render = statistics(times);
    await checkpoint("after-50-pages");

    const textStarted = performance.now();
    const textBudget = Math.min(TEXT_BUDGET_MS, timeLeft() - THREE_OPEN_RESERVE_MS);
    let chars = 0;
    let pagesRead = 0;
    for (let index = 0; index < handle.pages && performance.now() - textStarted < textBudget; index++) {
      chars += (await withTimeout(engine.text(handle, index), DOC_TIMEOUT_MS, `text page ${index + 1}`)).length;
      pagesRead++;
    }
    result.text = { pagesRead, chars, ms: Math.round(performance.now() - textStarted) };
  } catch (error) {
    result.error = errorText(error);
  }
  if (handle) {
    result.workerLog = await engine.workerLog(handle);
    await engine.close(handle).catch(error => { result.closeError = errorText(error); });
  }
  canvases.forEach(dropCanvas);
  return result;
}

// Three documents open in one tab, one page drawn of each; then all closed again.
async function threeOpen(engine) {
  const result = { name: "three-open" };
  const handles = [];
  const canvases = [];
  try {
    for (const url of THREE) {
      const handle = await withTimeout(engine.open(new URL(url, BASE).href), DOC_TIMEOUT_MS, "open");
      handles.push(handle);
      const canvas = newCanvas();
      canvases.push(canvas);
      await withTimeout(engine.render(handle, 0, canvas), DOC_TIMEOUT_MS, "render");
    }
    await checkpoint("three-open");
  } catch (error) {
    result.error = errorText(error);
  }
  for (const handle of handles) await engine.close(handle).catch(() => {});
  canvases.forEach(dropCanvas);
  await sleep(SETTLE_MS);
  await checkpoint("after-close");
  return result;
}

async function main() {
  status("running:features");
  const found = await features();
  const { snapshot: cjkSnapshot, ...cjk } = found.canvasCjk;
  await report("features", { ...found, canvasCjk: cjk, polyfilled: installPolyfills() });
  if (cjkSnapshot) await report("canvas-cjk", { name: "canvas-cjk", snapshot: cjkSnapshot });
  await checkpoint("baseline");

  status("running:init");
  const engine = ENGINE === "pdfium" ? new PdfiumEngine() : new PdfjsEngine(ENGINE === "pdfjs-legacy");
  let init;
  try {
    init = await withTimeout(engine.init(), DOC_TIMEOUT_MS, "init");
  } catch (error) {
    await report("init", { error: errorText(error), pageLog });
    status("done");
    return;
  }
  await report("init", init);

  for (let round = 1; round <= ROUNDS; round++) {
    for (const corpusEntry of CORPUS) {
      const entry = round === 1 ? corpusEntry : { ...corpusEntry, name: `${corpusEntry.name}#${round}` };
      if (timeLeft() < THREE_OPEN_RESERVE_MS) {
        await report("document", { name: entry.name, feature: entry.kind, skipped: "probe deadline" });
        continue;
      }
      status(`running:${entry.name}`);
      const result = await runDocument(engine, entry);
      show(`${entry.name}: ${result.error ? "error" : `${result.renderMs} ms, ink ${result.ink}, text ${result.textFound}`}`);
      await report("document", result);
      await flushPageLog(entry.name);
    }
    await checkpoint(round === 1 ? "after-corpus" : `after-corpus-${round}`);
  }

  status("running:many-pages");
  await report("many-pages", await manyPages(engine));
  await flushPageLog("many-pages");

  status("running:three-open");
  await report("three-open", await threeOpen(engine));

  await flushPageLog("three-open");
  status("done");
}

main().catch(async error => {
  await report("harness-error", { error: errorText(error), pageLog });
  status(`fail:${String(error).slice(0, 80)}`);
});
