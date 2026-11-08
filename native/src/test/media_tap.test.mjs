// Host tests of media_tap.js, the script that says whether a tap landed on a media element whose
// load the engine cancelled, run on a small fake DOM. Needs Node 20 or later; no browser.
//
//   node --test native/src/test/media_tap.test.mjs
//
// The fake keeps to what the script reads. Elements are painted in the order they were added,
// last on top, whatever tree (the document or a shadow root) they are in. elementsFromPoint, on
// the document or on a shadow root, hit-tests the whole page: every element whose box holds the
// point and that takes part in hit testing, topmost first, each retargeted against the tree asked
// (an element in a shadow tree the caller cannot see shows as that tree's host), without
// consecutive duplicates, as Servo does.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";

const TEMPLATE = readFileSync(new URL("../media_tap.js", import.meta.url), "utf8");
const HAVE_NOTHING = 0;
const HAVE_ENOUGH_DATA = 4;
const NETWORK_EMPTY = 0;
const NETWORK_NO_SOURCE = 3;

class Tree {
  constructor(host = null) {
    this.host = host;
    this.elements = [];
    this.fromPointCalls = 0;
    // Every element of the page, in paint order; shared by the document and its shadow roots.
    this.painted = host ? host.tree.painted : [];
  }

  add(element) {
    element.tree = this;
    this.elements.push(element);
    this.painted.push(element);
    return element;
  }

  contains(node) {
    return node !== null && node.tree === this;
  }

  /** This tree, then the trees of the hosts above it. */
  inclusiveAncestors() {
    const trees = [];
    for (let tree = this; tree; tree = tree.host?.tree) trees.push(tree);
    return trees;
  }

  retarget(element) {
    const visible = this.inclusiveAncestors();
    while (element.tree.host && !visible.includes(element.tree)) element = element.tree.host;
    return element;
  }

  elementsFromPoint(x, y) {
    const hits = [];
    for (const element of [...this.painted].reverse()) {
      if (!element.hittable || !element.holds(x, y)) continue;
      const shown = this.retarget(element);
      if (hits[hits.length - 1] !== shown) hits.push(shown);
    }
    return hits;
  }

  elementFromPoint(x, y) {
    this.fromPointCalls++;
    return this.elementsFromPoint(x, y)[0] ?? null;
  }

  querySelectorAll(selector) {
    assert.equal(selector, "video, audio");
    return this.elements.filter((element) => element.tag === "video" || element.tag === "audio");
  }
}

class Element {
  constructor(tag, [left, top, width, height], { parent = null, hittable = true, readyState = HAVE_NOTHING, networkState = NETWORK_NO_SOURCE } = {}) {
    this.tag = tag;
    this.box = { left, top, width, height, right: left + width, bottom: top + height };
    this.parentNode = parent;
    this.hittable = hittable;
    this.readyState = readyState;
    this.networkState = networkState;
    this.shadowRoot = null;
    this.tree = null;
  }

  holds(x, y) {
    const box = this.box;
    return box.width > 0 && box.height > 0 && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom;
  }

  getBoundingClientRect() {
    return this.box;
  }

  contains(node) {
    for (let at = node; at; at = at.parentNode) {
      if (at === this) return true;
    }
    return false;
  }

  /** An open shadow root (a closed one is not reachable from script). */
  attachShadow(open = true) {
    const tree = new Tree(this);
    if (open) this.shadowRoot = tree;
    return tree;
  }
}

/** A shadow root's top-level nodes have the root as their parent, which contains them all. */
function inRoot(tree) {
  return { parentNode: null, host: tree.host, tree, contains: (node) => tree.contains(node) };
}

function tapped(document, x, y) {
  const script = TEMPLATE.replace("__X__", String(x)).replace("__Y__", String(y));
  return vm.runInNewContext(script, { document });
}

/** A page with a body over the whole viewport. */
function page() {
  const document = new Tree();
  const body = document.add(new Element("body", [0, 0, 400, 800]));
  return { document, body };
}

/** A player in an open shadow root: a video without hit testing under an overlay with a button. */
function player(document, body, { open = true, readyState = HAVE_NOTHING } = {}) {
  const host = document.add(new Element("media-player", [0, 100, 400, 300], { parent: body }));
  const root = host.attachShadow(open);
  const top = inRoot(root);
  const video = root.add(new Element("video", [0, 100, 400, 300], { parent: top, hittable: false, readyState }));
  const overlay = root.add(new Element("div", [0, 100, 400, 300], { parent: top }));
  const button = root.add(new Element("button", [180, 230, 40, 40], { parent: overlay }));
  return { host, root, video, overlay, button };
}

test("a tap on a plain video whose load was cancelled counts", () => {
  const { document, body } = page();
  document.add(new Element("video", [0, 0, 400, 225], { parent: body }));

  assert.equal(tapped(document, 200, 100), true);
  assert.equal(tapped(document, 200, 400), false);
});

test("a tap on a shadow-root player's overlay or play button counts", () => {
  const { document, body } = page();
  player(document, body);

  assert.equal(tapped(document, 200, 250), true, "the play button");
  assert.equal(tapped(document, 20, 120), true, "the overlay");
  assert.equal(tapped(document, 200, 450), false, "below the player");
});

test("a banner drawn over the video takes the tap", () => {
  const { document, body } = page();
  player(document, body);
  document.add(new Element("div", [0, 300, 400, 500], { parent: body }));

  assert.equal(tapped(document, 200, 350), false, "on the banner, over the video");
  assert.equal(tapped(document, 200, 250), true, "on the player, above the banner");
});

test("a banner beside the video in the same parent does not count over it", () => {
  const { document, body } = page();
  document.add(new Element("video", [0, 0, 400, 225], { parent: body }));
  document.add(new Element("div", [0, 150, 400, 300], { parent: body }));

  assert.equal(tapped(document, 200, 200), false, "on the banner, over the video");
  assert.equal(tapped(document, 200, 100), true, "on the video, above the banner");
});

test("a sheet in its own shadow root over the video takes the tap", () => {
  const { document, body } = page();
  player(document, body);
  const host = document.add(new Element("x-sheet", [0, 0, 400, 800], { parent: body }));
  const root = host.attachShadow();
  root.add(new Element("div", [0, 0, 400, 800], { parent: inRoot(root) }));

  assert.equal(tapped(document, 200, 250), false, "on the sheet over the player");
});

test("a sheet that takes no hits leaves the tap on the player", () => {
  const { document, body } = page();
  player(document, body);
  const host = document.add(new Element("x-sheet", [0, 0, 400, 800], { parent: body, hittable: false }));
  const root = host.attachShadow();
  root.add(new Element("div", [0, 0, 400, 800], { parent: inRoot(root), hittable: false }));

  assert.equal(tapped(document, 200, 250), true, "the sheet takes no hits, so the tap lands on the player");
});

test("the topmost hit is asked once per tree", () => {
  const { document, body } = page();
  document.add(new Element("video", [0, 0, 400, 225], { parent: body }));
  document.add(new Element("video", [0, 0, 400, 225], { parent: body }));
  const { root } = player(document, body);

  assert.equal(tapped(document, 200, 100), true);
  assert.equal(document.fromPointCalls, 1, "the document tree, once for its two videos");
  assert.equal(root.fromPointCalls, 1, "the player's tree");
});

test("an overlay that reaches past the video does not count", () => {
  const { document, body } = page();
  const box = document.add(new Element("div", [0, 0, 400, 400], { parent: body }));
  document.add(new Element("video", [0, 0, 400, 225], { parent: box }));
  document.add(new Element("div", [0, 0, 400, 400], { parent: box }));

  assert.equal(tapped(document, 200, 100), false);
});

test("a tap on the player's own host counts when nothing in it takes hits", () => {
  const { document, body } = page();
  const host = document.add(new Element("media-player", [0, 100, 400, 300], { parent: body }));
  const root = host.attachShadow();
  root.add(new Element("video", [0, 100, 400, 300], { parent: inRoot(root), hittable: false }));

  assert.equal(tapped(document, 200, 250), true);
});

test("a video that was never given a source does not count", () => {
  const { document, body } = page();
  document.add(new Element("video", [0, 0, 400, 225], { parent: body, networkState: NETWORK_EMPTY }));

  assert.equal(tapped(document, 200, 100), false);
});

test("a video that has data does not count", () => {
  const { document, body } = page();
  player(document, body, { readyState: HAVE_ENOUGH_DATA });

  assert.equal(tapped(document, 200, 250), false);
});

test("an element without a box does not count", () => {
  const { document, body } = page();
  document.add(new Element("audio", [0, 0, 0, 0], { parent: body }));

  assert.equal(tapped(document, 0, 0), false);
});

test("a player inside another component's shadow root counts", () => {
  const { document, body } = page();
  const outer = document.add(new Element("feed-post", [0, 50, 400, 500], { parent: body }));
  const outerRoot = outer.attachShadow();
  const card = outerRoot.add(new Element("div", [0, 50, 400, 500], { parent: inRoot(outerRoot) }));
  player(outerRoot, card);

  assert.equal(tapped(document, 200, 250), true);
});

test("a player in a closed shadow root cannot be seen", () => {
  const { document, body } = page();
  player(document, body, { open: false });

  assert.equal(tapped(document, 200, 250), false);
});

test("the search stops after sixteen shadow roots", () => {
  const { document, body } = page();
  let tree = document;
  let parent = body;
  for (let depth = 0; depth < 16; depth++) {
    const host = tree.add(new Element("wrapper-" + depth, [0, 0, 400, 800], { parent }));
    tree = host.attachShadow();
    parent = tree.add(new Element("div", [0, 0, 400, 800], { parent: inRoot(tree) }));
  }
  player(tree, parent);

  // The player's root would be the seventeenth: not searched.
  assert.equal(tapped(document, 200, 250), false);
});

test("the template compiles unfilled, as CI checks it", () => {
  assert.doesNotThrow(() => new vm.Script(TEMPLATE));
});
