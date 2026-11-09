// Combray: whether the document is the page Servo shows in place of a response it cannot render,
// such as a PDF: "<html><body><p>Unknown content type (application/pdf).</p></body></html>", or
// "Unable to load font with content type (...)." for a font (ParserContext::load_document in
// components/script/dom/servoparser/mod.rs). It commits at the response's address, so its
// sentence would pass for that address's content. Servo gives it an opaque origin and no other
// markup, so this shape identifies it. Servo writes the Content-Type into it unescaped, so a
// server can add markup through a quoted parameter; that page then fails the check and is read
// like a page, which the server could have served anyway. Inserted into capture.js, reader.js and
// find.js: capture and reader read nothing from the stand-in page.
function isServoStandIn() {
  var STAND_IN_TEXT = /^(?:Unknown content type|Unable to load font with content type) \(.*\)\.$/;
  if (String(window.origin) !== "null") return false;
  var body = document.body;
  if (!body || body.childNodes.length !== 1) return false;
  var paragraph = body.firstChild;
  return paragraph.nodeType === 1 && String(paragraph.tagName).toUpperCase() === "P" &&
    STAND_IN_TEXT.test(String(paragraph.textContent).trim());
}

// Combray: whether the document is Combray's PDF viewer (native/src/pdf/viewer.mjs). The engine
// makes it for a top-level PDF response: an opaque origin, the response's Content-Type (with any
// parameters), and data-pdf-state on the root element. Its text is the viewer's own labels, not
// the PDF's, so capture and reader read nothing from it and find says it cannot search it yet.
// A page cannot pass for it: an opaque-origin document of type application/pdf is the viewer.
function isCombrayPdfViewer() {
  var root = document.documentElement;
  return String(window.origin) === "null" &&
    String(document.contentType).split(";")[0].trim().toLowerCase() === "application/pdf" &&
    !!root && root.hasAttribute("data-pdf-state");
}
