// Combray reader script. Mozilla's Readability (vendor/Readability.js, inserted below) finds the
// article; this turns it into bounded JSON blocks. Both run inside one function, so neither
// defines a global in the page.
//
// Returns the capture shape { url, title, text, truncated, nodes, nodeBudgetExhausted }, where
// `text` is "" when Readability finds no article, or the article as JSON:
//
//   { "title": ..., "byline": ..., "site": ..., "rtl": false,
//     "blocks": [ { "k": "h", "l": 2, "s": [["Heading"]] },
//                 { "k": "p", "s": [["Read the "], ["docs", "https://example.org/docs"], ["."]] } ] }
//
// Kinds: "h" heading (l = 1..6), "p" paragraph, "li" list item (l = nesting depth), "q" quote,
// "pre" preformatted text, "cap" caption. A run is [text] or [text, absolute http(s) link].
// Images, media and forms are left out. Budgets: at most __MAX_TEXT_BYTES__ bytes of UTF-8 JSON
// (whole blocks are dropped past it) and __MAX_NODES__ elements, for Readability and for this
// walk each.
(function (maxBytes, maxNodes) {
  __READABILITY__

  return (function () {
    "use strict";

    var result = {
      url: String(document.URL),
      title: String(document.title),
      text: "",
      truncated: false,
      nodes: 0,
      nodeBudgetExhausted: false
    };
    var article = new Readability(document.cloneNode(true), {
      maxElemsToParse: maxNodes,
      // The content element itself rather than its HTML: it is walked below, never serialized.
      serializer: function (element) { return element; }
    }).parse();
    if (!article || !article.content) return result;

    var HEADINGS = { H1: 1, H2: 2, H3: 3, H4: 4, H5: 5, H6: 6 };
    var SKIPPED = {
      SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, IMG: 1, PICTURE: 1, SVG: 1, VIDEO: 1,
      AUDIO: 1, IFRAME: 1, CANVAS: 1, OBJECT: 1, EMBED: 1, FORM: 1, BUTTON: 1, INPUT: 1,
      SELECT: 1, TEXTAREA: 1, HR: 1
    };
    var BLOCKS = {
      ADDRESS: 1, ARTICLE: 1, ASIDE: 1, BLOCKQUOTE: 1, DD: 1, DETAILS: 1, DIV: 1, DL: 1,
      DT: 1, FIGCAPTION: 1, FIGURE: 1, FOOTER: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1,
      HEADER: 1, LI: 1, MAIN: 1, NAV: 1, OL: 1, P: 1, PRE: 1, SECTION: 1, SUMMARY: 1,
      TABLE: 1, TBODY: 1, TD: 1, TFOOT: 1, TH: 1, THEAD: 1, TR: 1, UL: 1
    };
    var MAX_TITLE_CHARS = 1000;
    var MAX_BYLINE_CHARS = 300;

    var blocks = [];
    var visited = 0;
    var stopped = false;

    function utf8Length(text) {
      var length = 0;
      for (var index = 0; index < text.length; index++) {
        var code = text.charCodeAt(index);
        if (code < 0x80) {
          length += 1;
        } else if (code < 0x800) {
          length += 2;
        } else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < text.length) {
          length += 4;
          index++;
        } else {
          length += 3;
        }
      }
      return length;
    }

    function clip(text, limit) {
      return typeof text === "string" && text.trim() ? text.trim().slice(0, limit) : null;
    }

    var header = {
      title: clip(article.title, MAX_TITLE_CHARS),
      byline: clip(article.byline, MAX_BYLINE_CHARS),
      site: clip(article.siteName, MAX_BYLINE_CHARS),
      rtl: article.dir === "rtl",
      blocks: blocks
    };
    var budget = maxBytes - utf8Length(JSON.stringify(header));

    function tagOf(node) {
      return String(node.tagName).toUpperCase();
    }

    function linkOf(anchor) {
      var href = anchor.href;
      return typeof href === "string" && /^https?:/i.test(href) ? href : undefined;
    }

    // Counts an element against the node budget; false once it is spent.
    function visit() {
      if (++visited <= maxNodes) return true;
      result.nodeBudgetExhausted = true;
      result.truncated = true;
      stopped = true;
      return false;
    }

    // Inline content of `nodes` as runs, whitespace collapsed as rendered. Block elements inside
    // are skipped when `inlineOnly` (their content becomes blocks of its own).
    function runsOf(nodes, inlineOnly) {
      var runs = [];

      function add(text, href) {
        if (!text) return;
        var last = runs[runs.length - 1];
        if (last && last[1] === href) {
          last[0] += text;
        } else {
          runs.push(href ? [text, href] : [text]);
        }
      }

      function walk(node, href) {
        if (stopped) return;
        if (node.nodeType === 3) {
          add(String(node.textContent).replace(/\s+/g, " "), href);
          return;
        }
        if (node.nodeType !== 1 || !visit()) return;
        var tag = tagOf(node);
        if (SKIPPED[tag] || (inlineOnly && BLOCKS[tag])) return;
        if (tag === "BR") {
          add("\n", href);
          return;
        }
        if (tag === "A") href = linkOf(node) || href;
        for (var child = node.firstChild; child; child = child.nextSibling) walk(child, href);
      }

      for (var index = 0; index < nodes.length; index++) walk(nodes[index], undefined);

      // Trim around line breaks and at both ends, then drop runs left empty.
      for (var at = 0; at < runs.length; at++) runs[at][0] = runs[at][0].replace(/ *\n */g, "\n");
      if (runs.length) {
        runs[0][0] = runs[0][0].replace(/^\s+/, "");
        var end = runs[runs.length - 1];
        end[0] = end[0].replace(/\s+$/, "");
      }
      return runs.filter(function (run) { return run[0] !== ""; });
    }

    // Adds a block if it fits the byte budget; false once the budget is spent.
    function emit(kind, level, runs) {
      if (stopped) return false;
      if (!runs.length) return true;
      var block = { k: kind, s: runs };
      if (level) block.l = level;
      var size = utf8Length(JSON.stringify(block)) + 1;
      if (size > budget) {
        result.truncated = true;
        stopped = true;
        return false;
      }
      budget -= size;
      blocks.push(block);
      return true;
    }

    // The children of `element`: block elements become blocks; inline content between them
    // becomes one block of `context.kind`.
    function blocksIn(element, context) {
      var pending = [];
      function flush() {
        var runs = runsOf(pending, false);
        pending = [];
        return emit(context.kind, context.level, runs);
      }
      for (var child = element.firstChild; child; child = child.nextSibling) {
        if (stopped) return false;
        var block = child.nodeType === 1 && BLOCKS[tagOf(child)];
        if (!block) {
          pending.push(child);
          continue;
        }
        if (!flush() || !blockOf(child, context)) return false;
      }
      return flush();
    }

    function blockOf(element, context) {
      if (!visit()) return false;
      var tag = tagOf(element);
      if (HEADINGS[tag]) return emit("h", HEADINGS[tag], runsOf([element], false));
      if (tag === "PRE") {
        var code = String(element.textContent).replace(/\s+$/, "");
        return emit("pre", 0, code ? [[code]] : []);
      }
      if (tag === "FIGCAPTION") return emit("cap", 0, runsOf([element], false));
      if (tag === "UL" || tag === "OL") {
        var depth = context.kind === "li" ? context.level + 1 : 1;
        return blocksIn(element, { kind: "li", level: depth });
      }
      if (tag === "BLOCKQUOTE") return blocksIn(element, { kind: "q", level: 0 });
      if (tag === "TR") {
        // One line per row, cells separated by " · ".
        var cells = [];
        for (var cell = element.firstChild; cell; cell = cell.nextSibling) {
          if (cell.nodeType !== 1) continue;
          var runs = runsOf([cell], false);
          if (!runs.length) continue;
          if (cells.length) cells.push([" · "]);
          cells = cells.concat(runs);
        }
        return emit(context.kind, context.level, cells);
      }
      // Paragraphs, list items and containers: their own text takes the surrounding kind.
      return blocksIn(element, context);
    }

    blocksIn(article.content, { kind: "p", level: 0 });
    result.nodes = visited;
    result.text = blocks.length ? JSON.stringify(header) : "";
    return result;
  })();
})(__MAX_TEXT_BYTES__, __MAX_NODES__)
