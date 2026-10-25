// Page metrics, measured the same way in Servo and in the reference engine.
//
// This file is a function *body*, not a program: it ends in `return result;`.
// - Servo runs it through W3C WebDriver "execute/sync", which wraps the script
//   in a function itself.
// - Playwright's page.evaluate needs a function expression, so reference.py
//   evaluates "() => {\n" + body + "\n}" (see sitecheck.servo.load_probe_source).
//
// Every metric is computed in its own try/catch: an API Servo lacks or a page
// that broke the DOM makes that one metric null and adds a line to
// `probeErrors`, instead of losing the whole probe. Keep it to basic DOM APIs
// and plain JSON values, so both engines return the same shape.
"use strict";

// Characters of the page text returned; the full length is still reported in
// textLength. Bounds the WebDriver response for very long pages.
var TEXT_LIMIT = 100000;
// Form controls whose contents the engines' innerText treats differently: Chromium
// lists a <select>'s options, Servo does not. Their text is left out of `text` in
// both engines, so options never count as content Servo is missing.
var FORM_CONTROLS = "select, datalist, textarea";
var ELEMENT_NODE = 1;
var TEXT_NODE = 3;
// Characters kept from one probe error, enough to recognize the failing API.
var PROBE_ERROR_LIMIT = 200;
// Error records the userscript may have captured (it caps itself at 50).
var ERRORS_LIMIT = 50;

var result = { probeErrors: [] };

function describe(error) {
  var message;
  try {
    message = error && error.message ? String(error.message) : String(error);
  } catch (ignored) {
    message = "unprintable error";
  }
  return message.slice(0, PROBE_ERROR_LIMIT);
}

function measure(name, compute) {
  try {
    result[name] = compute();
  } catch (error) {
    result[name] = null;
    result.probeErrors.push(name + ": " + describe(error));
  }
}

function scrollingElement() {
  return document.scrollingElement || document.documentElement;
}

measure("readyState", function () { return document.readyState; });
measure("url", function () { return String(location.href); });
measure("hasTitle", function () { return document.title.trim().length > 0; });
measure("titleLength", function () { return document.title.length; });

// The rendered text of `element` (innerText) without the contents of form controls.
// Only the branches that contain a form control are walked; everything else is one
// innerText call, so hidden content stays out as innerText leaves it out.
function comparableText(element) {
  if (!element.querySelector(FORM_CONTROLS)) {
    return String(element.innerText || "");
  }
  var parts = [];
  var visible = window.getComputedStyle(element).visibility !== "hidden";
  var children = element.childNodes;
  for (var i = 0; i < children.length; i++) {
    var child = children[i];
    if (child.nodeType === TEXT_NODE) {
      if (visible) {
        parts.push(child.data.replace(/\s+/g, " "));
      }
    } else if (child.nodeType === ELEMENT_NODE && !child.matches(FORM_CONTROLS)) {
      if (window.getComputedStyle(child).display !== "none") {
        parts.push(comparableText(child));
      }
    }
  }
  return parts.join("\n");
}

// innerText forces layout and can be slow, so the text is read once for both fields.
var fullText = null;
measure("text", function () {
  fullText = document.body ? comparableText(document.body) : "";
  return fullText.length > TEXT_LIMIT ? fullText.slice(0, TEXT_LIMIT) : fullText;
});
measure("textLength", function () {
  if (fullText === null) {
    throw new Error("page text unavailable");
  }
  return fullText.length;
});
// For diagnosis only: the plain innerText length, form controls included.
measure("innerTextLength", function () {
  return document.body ? String(document.body.innerText || "").length : 0;
});

measure("elementCount", function () { return document.getElementsByTagName("*").length; });
measure("linkCount", function () { return document.links.length; });
measure("imageCount", function () { return document.images.length; });

// An image that finished loading but has no intrinsic width failed to decode or
// load. Images without a src (placeholders filled in by script) are not broken.
var imageState = null;
function countImages() {
  if (imageState !== null) {
    return imageState;
  }
  var loaded = 0;
  var broken = 0;
  var images = document.images;
  for (var i = 0; i < images.length; i++) {
    var image = images[i];
    if (!image.complete) {
      continue;
    }
    if (image.naturalWidth > 0) {
      loaded++;
      continue;
    }
    if (image.getAttribute("src") || image.currentSrc) {
      broken++;
    }
  }
  imageState = { loaded: loaded, broken: broken };
  return imageState;
}
measure("imagesLoaded", function () { return countImages().loaded; });
measure("imagesBroken", function () { return countImages().broken; });

measure("iframeCount", function () { return document.getElementsByTagName("iframe").length; });
measure("formCount", function () { return document.forms.length; });
measure("scriptCount", function () { return document.scripts.length; });
measure("styleSheetCount", function () { return document.styleSheets.length; });
measure("scrollWidth", function () { return scrollingElement().scrollWidth; });
measure("scrollHeight", function () { return scrollingElement().scrollHeight; });
measure("innerWidth", function () { return window.innerWidth; });
measure("innerHeight", function () { return window.innerHeight; });

measure("bodyRect", function () {
  if (!document.body) {
    return null;
  }
  var rect = document.body.getBoundingClientRect();
  return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
});

// Records from errors_userscript.js, copied field by field so a page that
// tampered with the array cannot make the result unserializable.
measure("errors", function () {
  var captured = window.__sitecheck_errors;
  if (!Array.isArray(captured)) {
    return [];
  }
  var copies = [];
  for (var i = 0; i < captured.length && i < ERRORS_LIMIT; i++) {
    var entry = captured[i] || {};
    copies.push({
      type: entry.type == null ? null : String(entry.type),
      message: entry.message == null ? null : String(entry.message),
      source: entry.source == null ? null : String(entry.source),
      line: typeof entry.line === "number" ? entry.line : null,
    });
  }
  return copies;
});

return result;
