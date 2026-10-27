// pdf.js's worker (modern build) with its console kept for the probe report.
export * from "./vendor/pdfjs/package/build/pdf.worker.mjs";
import { captureConsole } from "./worker-console.mjs";

captureConsole();
