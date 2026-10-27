// pdf.js's worker (legacy build, with polyfills) with its console kept for the probe report.
export * from "./vendor/pdfjs/package/legacy/build/pdf.worker.mjs";
import { captureConsole } from "./worker-console.mjs";

captureConsole();
