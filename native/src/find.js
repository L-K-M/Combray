// Combray find in page. "find" searches the document's rendered text and selects one match;
// "clear" removes the selection find made. Returns { count, index, capped }: index is the
// selected match (0-based, -1 when none) and capped says count is only a lower bound.
//
// The query arrives as a JSON string literal, never as source text. A match lies within one
// text node (text split by markup is not found) and ignores case wherever lowercasing keeps the
// text's length; otherwise it is exact. Text that is not rendered is skipped: scripts, styles,
// form controls and hidden subtrees. Shadow roots and frames are not searched. Budgets: at most
// maxMatches matches, maxNodes text nodes and maxMillis milliseconds, checked as the walk goes;
// Servo cannot interrupt this script, so they are what bounds it.
//
// Combray's PDF viewer has no text to search yet: there the script answers { unavailable: true }
// instead of counting the viewer's own labels.
//
// The page's realm runs this: a page can observe the query and misreport its own matches, which
// is why only bounded integers come back. The selection find made is remembered on the document,
// with the selection the page had before it, so clearing restores that one and never removes a
// selection made since.
(function (query, wanted, action, maxMatches, maxNodes, maxMillis) {
  "use strict";

  // Parents whose text is not rendered as page text.
  var SKIPPED = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, TITLE: 1, TEXTAREA: 1, SELECT: 1,
    OPTION: 1, OPTGROUP: 1
  };
  var SHOW_TEXT = 4;
  var TIME_CHECK_INTERVAL = 256;
  var STATE = Symbol.for("ch.lkmc.combray.find");

  var selection = window.getSelection();
  var state = document[STATE] || null;

  function selectedRange() {
    try {
      return selection && selection.rangeCount ? selection.getRangeAt(0) : null;
    } catch (outsideTheDocument) {
      return null;
    }
  }

  function remember(value) {
    try {
      Object.defineProperty(document, STATE, { value: value, configurable: true, writable: true });
    } catch (frozen) {
      // A page that froze its document only loses the restoring of its own selection.
    }
    state = value;
  }

  // Gives the page back its selection, if the selection is still the one find made.
  function release() {
    if (!state) return;
    if (selection && selectedRange() === state.ours) {
      selection.removeAllRanges();
      if (state.previous) selection.addRange(state.previous);
    }
    try {
      delete document[STATE];
    } catch (frozen) {
      // Nothing else to undo.
    }
    state = null;
  }

  if (action === "clear" || !query) {
    release();
    return { count: 0, index: -1, capped: false };
  }

  __STAND_IN__

  // The viewer draws PDF pages as images; their text comes in a later step (docs/PDF.md).
  if (isCombrayPdfViewer()) return { unavailable: true };

  // Lowercase matching needs the lowercased query to keep its length, so offsets stay aligned.
  var caseless = query.toLowerCase();
  if (caseless.length !== query.length) caseless = null;

  var matches = [];
  var capped = false;
  var nodes = 0;
  var deadline = Date.now() + maxMillis;
  var root = document.body || document.documentElement;
  var walker = root ? document.createTreeWalker(root, SHOW_TEXT) : null;

  search: while (walker) {
    var node = walker.nextNode();
    if (!node) break;
    if (++nodes > maxNodes || (nodes % TIME_CHECK_INTERVAL === 0 && Date.now() > deadline)) {
      capped = true;
      break;
    }
    var parent = node.parentNode;
    if (!parent || SKIPPED[String(parent.nodeName).toUpperCase()] === 1) continue;

    var data = String(node.data);
    var lower = caseless === null ? null : data.toLowerCase();
    var folded = lower !== null && lower.length === data.length;
    var haystack = folded ? lower : data;
    var needle = folded ? caseless : query;
    var at = haystack.indexOf(needle);
    if (at < 0) continue;

    // Text whose element has no box (display: none, or in a hidden subtree) is not on the page.
    // Not the text's own Range: in Servo 0.6.0 a range within one text node reports the boxes of
    // every node after it in the document, so hidden text before visible text counted.
    var element = node.parentElement;
    if (!element || !element.getClientRects().length) continue;

    while (at >= 0) {
      if (matches.length >= maxMatches) {
        capped = true;
        break search;
      }
      matches.push([node, at]);
      at = haystack.indexOf(needle, at + needle.length);
    }
  }

  var count = matches.length;
  if (!count) {
    release();
    return { count: 0, index: -1, capped: capped };
  }

  // Wrap around; a negative index counts from the end.
  var index = wanted % count;
  if (index < 0) index += count;
  var match = matches[index];
  var range = document.createRange();
  range.setStart(match[0], match[1]);
  range.setEnd(match[0], match[1] + query.length);

  if (selection) {
    var before = selectedRange();
    if (!state || before !== state.ours) remember({ previous: before, ours: null });
    selection.removeAllRanges();
    selection.addRange(range);
    state.ours = range;
  }

  var element = match[0].parentElement;
  if (element) element.scrollIntoView({ block: "center", inline: "nearest" });
  return { count: count, index: index, capped: capped };
})(__QUERY__, __INDEX__, __ACTION__, __MAX_MATCHES__, __MAX_NODES__, __MAX_MILLIS__)
