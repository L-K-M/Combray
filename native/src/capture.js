// Combray capture script. Returns one snapshot of the current document:
// { url, title, text, truncated, nodes, nodeBudgetExhausted, timeBudgetExhausted }.
//
// Budgets: at most __MAX_TEXT_BYTES__ bytes of UTF-8 text, __MAX_NODES__ visited nodes and
// __MAX_MILLIS__ milliseconds. The page's own scripts wait while this runs (Servo cannot
// interrupt it), so the walk stops in time and returns what it has.
// Skips scripts, styles, form controls, editable content and hidden subtrees. Follows open
// shadow roots and slot assignments; closed shadow roots are not reachable from script.
(function (maxTextBytes, maxNodes, maxMillis) {
  "use strict";

  // How often the clock is read: reading it costs more than visiting a node. Work within one
  // node (a huge text node, a page-defined getter) is not clocked; only the node budget bounds it.
  var CLOCK_EVERY_NODES = 128;

  var SKIPPED = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, TITLE: 1, META: 1, LINK: 1,
    INPUT: 1, TEXTAREA: 1, SELECT: 1, OPTION: 1, BUTTON: 1,
    IFRAME: 1, FRAME: 1, OBJECT: 1, EMBED: 1, CANVAS: 1, SVG: 1, VIDEO: 1, AUDIO: 1
  };
  var BLOCKS = {
    ADDRESS: 1, ARTICLE: 1, ASIDE: 1, BLOCKQUOTE: 1, BR: 1, DD: 1, DETAILS: 1, DIV: 1,
    DL: 1, DT: 1, FIELDSET: 1, FIGCAPTION: 1, FIGURE: 1, FOOTER: 1, FORM: 1, H1: 1,
    H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, HEADER: 1, HR: 1, LI: 1, MAIN: 1, NAV: 1,
    OL: 1, P: 1, PRE: 1, SECTION: 1, SUMMARY: 1, TABLE: 1, TD: 1, TH: 1, TR: 1, UL: 1
  };

  var parts = [];
  var bytes = 0;
  var nodes = 0;
  var truncated = false;
  var nodeBudgetExhausted = false;
  var timeBudgetExhausted = false;
  // The page can replace the clock. One that returns NaN or a non-number stops the walk (the
  // comparison below fails closed); one that fakes a slow clock removes the time guarantee and
  // leaves only the node budget. Only interruption in Servo would close that.
  var now = typeof performance === "object" && performance && typeof performance.now === "function"
    ? function () { return performance.now(); }
    : function () { return Date.now(); };
  var started = now();

  // UTF-8 width of one UTF-16 position; lone surrogates become U+FFFD (3 bytes).
  function widthAt(text, index) {
    var code = text.charCodeAt(index);
    if (code < 0x80) return [1, 1];
    if (code < 0x800) return [2, 1];
    if (code >= 0xD800 && code <= 0xDBFF && index + 1 < text.length) {
      var next = text.charCodeAt(index + 1);
      if (next >= 0xDC00 && next <= 0xDFFF) return [4, 2];
    }
    return [3, 1];
  }

  // Appends as much of `text` as fits; returns false once the byte budget is full.
  function append(text) {
    var index = 0;
    while (index < text.length) {
      var width = widthAt(text, index);
      if (bytes + width[0] > maxTextBytes) {
        parts.push(text.slice(0, index));
        truncated = true;
        return false;
      }
      bytes += width[0];
      index += width[1];
    }
    parts.push(text);
    return true;
  }

  function newline() {
    var last = parts.length ? parts[parts.length - 1] : "\n";
    return last.charAt(last.length - 1) === "\n" ? true : append("\n");
  }

  function hidden(element) {
    if (element.hasAttribute("hidden") || element.getAttribute("aria-hidden") === "true" ||
      element.hasAttribute("inert") || element.isContentEditable) {
      return true;
    }
    // In Servo every computed-style read is a layout query: read each property once, and
    // stop at the first that hides the element.
    var style = window.getComputedStyle(element);
    if (style.display === "none") return true;
    var visibility = style.visibility;
    if (visibility === "hidden" || visibility === "collapse") return true;
    return style.contentVisibility === "hidden";
  }

  __STAND_IN__

  // Servo's stand-in for a file it cannot show (a PDF) has no text of that file: read nothing.
  var root = isServoStandIn() ? null : document.body || document.documentElement;
  // Explicit stack: [node, closing] where closing marks the end of a block element.
  var stack = root ? [[root, false]] : [];
  // No time at all (a hidden page whose deadline leaves none): read nothing.
  if (!(maxMillis > 0)) {
    stack = [];
    timeBudgetExhausted = true;
    truncated = true;
  }
  while (stack.length) {
    var entry = stack.pop();
    var node = entry[0];
    if (entry[1]) {
      if (!newline()) break;
      continue;
    }
    if (++nodes > maxNodes) {
      nodeBudgetExhausted = true;
      break;
    }
    if (nodes % CLOCK_EVERY_NODES === 0 && !(now() - started <= maxMillis)) {
      timeBudgetExhausted = true;
      truncated = true;
      break;
    }
    if (node.nodeType === 3) {
      var data = String(node.data).replace(/\s+/g, " ");
      if (data !== " " && data !== "" && !append(data)) break;
      continue;
    }
    if (node.nodeType !== 1) continue;

    var tag = String(node.tagName).toUpperCase();
    if (SKIPPED[tag] || hidden(node)) continue;

    var block = BLOCKS[tag] === 1;
    if (block && !newline()) break;
    if (block) stack.push([node, true]);
    // Rendered children: an open shadow tree replaces the light DOM; a slot shows its
    // assigned nodes, or its fallback content when nothing is assigned.
    var children = node.shadowRoot ? node.shadowRoot.childNodes : node.childNodes;
    if (tag === "SLOT" && node.assignedNodes) {
      var assigned = node.assignedNodes({ flatten: true });
      if (assigned.length) children = assigned;
    }
    // Children are visited before anything older on the stack, so at most the first
    // (node budget left + 1) of them can be reached. Pushing more would let a page that reports
    // a huge child list exhaust memory before the node budget applies.
    var reachable = Math.min(children.length, maxNodes - nodes + 1);
    for (var index = reachable - 1; index >= 0; index--) {
      stack.push([children[index], false]);
    }
  }

  var text = parts.join("").replace(/[ \t]*\n[ \t\n]*/g, "\n").trim();
  return {
    url: String(document.URL),
    title: String(document.title),
    text: text,
    truncated: truncated,
    nodes: nodes,
    nodeBudgetExhausted: nodeBudgetExhausted,
    timeBudgetExhausted: timeBudgetExhausted
  };
})(__MAX_TEXT_BYTES__, __MAX_NODES__, __MAX_MILLIS__)
