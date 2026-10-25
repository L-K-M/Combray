// Records a page's uncaught script errors and unhandled promise rejections in
// window.__sitecheck_errors, where probe.js reads them.
//
// Servo loads it with `servoshell --userscripts=<dir>`; servoshell runs
// userscripts as a delayed task once <head> is inserted, so errors thrown by the
// very first scripts can be missed. The runner therefore takes Servo's errors
// from servoshell's log and keeps these records as a second source. Chromium
// installs the same file with add_init_script for parity.
//
// Each record is {type, message, source, line}, where type is "uncaught" or
// "rejection" (the same kinds as sitecheck.model.PageError).
(function () {
  "use strict";

  // Bounds what a page that throws in a loop can make the probe return. Messages
  // are clipped like the harness clips them in both engines
  // (sitecheck.model.MAX_ERROR_MESSAGE_CHARS; a test keeps the two equal).
  var MAX_ENTRIES = 50;
  var MAX_MESSAGE_LENGTH = 1000;

  // Chromium runs init scripts once per document, but a page could also load
  // this twice (or define the name itself); keep the first array.
  if (Array.isArray(window.__sitecheck_errors)) {
    return;
  }
  var errors = [];
  window.__sitecheck_errors = errors;

  function text(value) {
    var message;
    try {
      message = String(value);
    } catch (ignored) {
      // Symbols and objects whose toString throws.
      message = Object.prototype.toString.call(value);
    }
    return message.length > MAX_MESSAGE_LENGTH ? message.slice(0, MAX_MESSAGE_LENGTH) : message;
  }

  function record(type, message, source, line) {
    if (errors.length >= MAX_ENTRIES) {
      return;
    }
    errors.push({
      type: type,
      message: text(message),
      source: source ? text(source) : null,
      line: typeof line === "number" && line > 0 ? line : null,
    });
  }

  // Capture phase, so a page handler that stops propagation cannot hide the error.
  window.addEventListener("error", function (event) {
    // Failed <img>/<script> loads also reach window in the capture phase; they
    // are not script errors (probe.js counts broken images separately).
    if (event.target !== window) {
      return;
    }
    try {
      // The thrown Error's own message has no engine framing, whereas event.message
      // has (Chromium: "Uncaught TypeError: x", Servo: "x"). The wording itself can
      // still differ between engines; the harness also matches errors by place.
      // Muted cross-origin errors have no error object, only "Script error.".
      var thrown = event.error;
      var message = thrown && typeof thrown === "object" && typeof thrown.message === "string"
        ? thrown.message
        : event.message || thrown;
      record("uncaught", message || "unknown error", event.filename, event.lineno);
    } catch (ignored) {
      // A throwing getter on the error object; never let the recorder itself throw.
    }
  }, true);

  window.addEventListener("unhandledrejection", function (event) {
    try {
      var reason = event.reason;
      var message = reason && typeof reason === "object" && "message" in reason ? reason.message : reason;
      record("rejection", message, null, null);
    } catch (ignored) {
      // As above: a hostile or broken reason object must not raise a new error.
    }
  }, true);
})();
