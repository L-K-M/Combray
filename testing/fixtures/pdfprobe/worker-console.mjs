// Keeps what a worker logs (pdf.js warns there about fonts, images and wasm) and hands it to the
// page on a { probeFlush } message as { probeLog }. pdf.js's own message handler ignores both:
// they carry no targetName. In the page itself (pdf.js's fake-worker fallback) it does nothing.
const MAX_LINES = 300;
const MAX_LINE = 400;

export function captureConsole() {
  if (typeof WorkerGlobalScope === "undefined" || !(self instanceof WorkerGlobalScope)) return;
  const lines = [];
  const keep = line => {
    if (lines.length < MAX_LINES) lines.push(line.slice(0, MAX_LINE));
  };
  for (const level of ["log", "info", "warn", "error"]) {
    const original = console[level];
    console[level] = (...args) => {
      keep(`${level}: ${args.map(String).join(" ")}`);
      original.apply(console, args);
    };
  }
  self.addEventListener("error", event => keep(`uncaught: ${event.message}`));
  self.addEventListener("unhandledrejection", event => keep(`unhandled rejection: ${event.reason}`));
  self.addEventListener("message", event => {
    if (event.data && event.data.probeFlush) self.postMessage({ probeLog: lines.splice(0) });
  });
}
