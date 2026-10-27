// What the engine offers that pdf.js or PDFium-wasm may need, as name -> true/false/"error: ...".
// Used in the page and in a module worker (feature-worker.mjs).

// The smallest valid WebAssembly module: magic number and version.
const EMPTY_WASM = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);

const BUILT_INS = {
  "Promise.withResolvers": () => typeof Promise.withResolvers === "function",
  "Promise.try": () => typeof Promise.try === "function",
  "Map.prototype.getOrInsertComputed": () => typeof Map.prototype.getOrInsertComputed === "function",
  "Map.groupBy": () => typeof Map.groupBy === "function",
  "Array.fromAsync": () => typeof Array.fromAsync === "function",
  "Array.prototype.toSorted": () => typeof Array.prototype.toSorted === "function",
  "Array.prototype.findLast": () => typeof Array.prototype.findLast === "function",
  "Uint8Array.fromBase64": () => typeof Uint8Array.fromBase64 === "function",
  "Uint8Array.prototype.toHex": () => typeof Uint8Array.prototype.toHex === "function",
  "Math.sumPrecise": () => typeof Math.sumPrecise === "function",
  "Float16Array": () => typeof Float16Array === "function",
  "Iterator.prototype.map": () => typeof Iterator === "function" && typeof Iterator.prototype.map === "function",
  "Set.prototype.union": () => typeof Set.prototype.union === "function",
  "RegExp.escape": () => typeof RegExp.escape === "function",
  "Error.isError": () => typeof Error.isError === "function",
  "URL.parse": () => typeof URL.parse === "function",
  "structuredClone": () => typeof structuredClone === "function",
  "ReadableStream": () => typeof ReadableStream === "function",
  "ReadableStream async iteration": () => typeof ReadableStream === "function" &&
    typeof ReadableStream.prototype[Symbol.asyncIterator] === "function",
  "DecompressionStream": () => typeof DecompressionStream === "function",
  "TestUtils.gc": () => typeof TestUtils === "object" && typeof TestUtils.gc === "function",
  "TextDecoder": () => typeof TextDecoder === "function",
  "fetch": () => typeof fetch === "function",
  "OffscreenCanvas": () => typeof OffscreenCanvas === "function",
  "OffscreenCanvas 2d": () => typeof OffscreenCanvas === "function" && !!new OffscreenCanvas(1, 1).getContext("2d"),
  "createImageBitmap": () => typeof createImageBitmap === "function",
  "ImageDecoder": () => typeof ImageDecoder === "function",
  "Path2D": () => typeof Path2D === "function",
  "DOMMatrix": () => typeof DOMMatrix === "function",
  "FontFace": () => typeof FontFace === "function",
  "WebAssembly": () => typeof WebAssembly === "object",
  "WebAssembly.validate": () => WebAssembly.validate(EMPTY_WASM),
};

async function instantiates() {
  const { instance } = await WebAssembly.instantiate(EMPTY_WASM);
  return instance instanceof WebAssembly.Instance;
}

export async function detect() {
  const result = {};
  for (const [name, test] of Object.entries(BUILT_INS)) {
    try {
      result[name] = !!test();
    } catch (error) {
      result[name] = `error: ${error}`;
    }
  }
  try {
    result["WebAssembly.instantiate"] = await instantiates();
  } catch (error) {
    result["WebAssembly.instantiate"] = `error: ${error}`;
  }
  return result;
}
