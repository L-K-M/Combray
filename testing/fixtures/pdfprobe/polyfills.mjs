// What the probe adds to Servo 0.7 for pdf.js, in the page and in its worker. Returns the names of
// the polyfills it installed (none where the engine already has the feature).
//
// ReadableStream async iteration: Servo's ReadableStream.webidl leaves `async iterable` out, and
// pdf.js reads getTextContent()'s stream with `for await`.
export function installPolyfills() {
  const installed = [];
  if (typeof ReadableStream === "function" && typeof ReadableStream.prototype[Symbol.asyncIterator] !== "function") {
    ReadableStream.prototype[Symbol.asyncIterator] = async function* () {
      const reader = this.getReader();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) return;
          yield value;
        }
      } finally {
        reader.releaseLock();
      }
    };
    installed.push("ReadableStream async iteration");
  }
  return installed;
}
