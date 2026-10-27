// Combray: whether the document is the page Servo shows in place of a response it cannot render,
// such as a PDF: "<html><body><p>Unknown content type (application/pdf).</p></body></html>", or
// "Unable to load font with content type (...)." for a font (ServoParser::load_document).
// It commits at the response's address, so its sentence would pass for that address's content.
// Servo gives it an opaque origin and writes no other markup or script into it, so this exact
// shape identifies it. Inserted into capture.js and reader.js, which read nothing from it.
function isServoStandIn() {
  var STAND_IN_TEXT = /^(?:Unknown content type|Unable to load font with content type) \(.*\)\.$/;
  if (String(window.origin) !== "null") return false;
  var body = document.body;
  if (!body || body.childNodes.length !== 1) return false;
  var paragraph = body.firstChild;
  return paragraph.nodeType === 1 && String(paragraph.tagName).toUpperCase() === "P" &&
    STAND_IN_TEXT.test(String(paragraph.textContent));
}
