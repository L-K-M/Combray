import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const template = readFileSync(new URL('../find.js', import.meta.url), 'utf8');
const standIn = readFileSync(new URL('../stand_in.js', import.meta.url), 'utf8');

const text = data => ({ nodeType: 3, data });

/** An element with the display and visibility it sets itself in `style`. */
class FakeElement {
  constructor(nodeName, childNodes = [], style = {}) {
    Object.assign(this, { nodeType: 1, nodeName, childNodes, style });
    for (const child of childNodes) {
      child.parentNode = this;
      child.parentElement = this;
    }
  }

  /** As in a browser: no box inside a display: none subtree, whatever the visibility. */
  getClientRects() {
    for (let element = this; element; element = element.parentElement) {
      if (element.style.display === 'none') return [];
    }
    return [{}];
  }

  scrollIntoView() {
    this.page.scrolled.push(this);
  }
}

const element = (nodeName, childNodes, style) => new FakeElement(nodeName, childNodes, style);

/** A document whose body holds `children`, with the selection and the scrolls find makes. */
function fakePage(children, { pdfViewer = false } = {}) {
  const body = element('BODY', children);
  const page = { scrolled: [] };
  const textNodes = [];
  (function adopt(parent) {
    parent.page = page;
    for (const child of parent.childNodes) {
      if (child.nodeType === 3) textNodes.push(child);
      else adopt(child);
    }
  })(body);

  page.selection = {
    ranges: [],
    get rangeCount() {
      return this.ranges.length;
    },
    getRangeAt(index) {
      return this.ranges[index];
    },
    removeAllRanges() {
      this.ranges = [];
    },
    addRange(range) {
      this.ranges.push(range);
    },
  };
  page.document = {
    body,
    documentElement: { hasAttribute: name => pdfViewer && name === 'data-pdf-state' },
    contentType: pdfViewer ? 'application/pdf' : 'text/html',
    createTreeWalker(root, whatToShow) {
      assert.equal(root, body);
      assert.equal(whatToShow, 4);
      let next = 0;
      return { nextNode: () => textNodes[next++] || null };
    },
    createRange: () => ({
      setStart(node, offset) {
        Object.assign(this, { node, start: offset });
      },
      setEnd(node, offset) {
        assert.equal(node, this.node);
        this.end = offset;
      },
    }),
  };
  page.window = {
    origin: pdfViewer ? 'null' : 'https://synthetic.example',
    getSelection: () => page.selection,
    // Visibility is inherited: the nearest element that sets it decides.
    getComputedStyle(node) {
      for (let at = node; at; at = at.parentElement) {
        if (at.style.visibility) return { visibility: at.style.visibility };
      }
      return { visibility: 'visible' };
    },
  };
  return page;
}

function run(page, query, { index = 0, action = 'find', maxMatches = 999, maxNodes = 50000, maxMillis = 1000 } = {}) {
  const script = template
    .replace('__STAND_IN__', () => standIn)
    .replace('__INDEX__', String(index))
    .replace('__ACTION__', JSON.stringify(action))
    .replace('__MAX_MATCHES__', String(maxMatches))
    .replace('__MAX_NODES__', String(maxNodes))
    .replace('__MAX_MILLIS__', String(maxMillis))
    .replace('__QUERY__', () => JSON.stringify(query));
  const result = vm.runInNewContext(script, { document: page.document, window: page.window }, { timeout: 1000 });
  // Copied out of the script's realm, so it compares equal to an object of this one.
  return { ...result };
}

const find = (page, query, options) => run(page, query, { ...options, action: 'find' });
const clear = page => run(page, '', { action: 'clear' });

/** What the page's selection covers, as the text of each range. */
const selected = page => page.selection.ranges.map(range => range.node.data.slice(range.start, range.end));

/** A range the page made itself, over all of `node`. */
const pageRange = node => ({ node, start: 0, end: node.data.length });

test('matches are counted across text nodes, and the wanted one is selected and scrolled to', () => {
  const first = element('P', [text('one needle, two needle')]);
  const second = element('P', [text('a third needle')]);
  const page = fakePage([first, second]);

  assert.deepEqual(find(page, 'needle', { index: 2 }), { count: 3, index: 2, capped: false });

  assert.deepEqual(selected(page), ['needle']);
  assert.equal(page.selection.ranges[0].node, second.childNodes[0]);
  assert.deepEqual(page.scrolled, [second]);
});

test('the index wraps around, and a negative one counts from the end', () => {
  const page = fakePage([element('P', [text('needle needle needle')])]);

  assert.equal(find(page, 'needle', { index: 3 }).index, 0);
  assert.equal(page.selection.ranges[0].start, 0);
  assert.equal(find(page, 'needle', { index: -1 }).index, 2);
  assert.equal(page.selection.ranges[0].start, 14);
});

test('case is ignored, and the selection keeps the page\'s spelling', () => {
  const page = fakePage([element('P', [text('Needle NEEDLE needle')])]);

  assert.deepEqual(find(page, 'nEEdle', { index: 1 }), { count: 3, index: 1, capped: false });
  assert.deepEqual(selected(page), ['NEEDLE']);
});

test('where lowercasing changes the length, the match is exact', () => {
  // U+0130 lowercases to two code units, so offsets in the lowercased text would be off.
  const query = fakePage([element('P', [text('İx, İX and i̇x')])]);
  assert.deepEqual(find(query, 'İx'), { count: 1, index: 0, capped: false });
  assert.deepEqual(selected(query), ['İx']);

  const node = fakePage([element('P', [text('İ: Needle, needle')])]);
  assert.deepEqual(find(node, 'needle'), { count: 1, index: 0, capped: false });
  assert.equal(node.selection.ranges[0].start, 11);
});

test('a match does not overlap the one before it', () => {
  const page = fakePage([element('P', [text('aaaaa')])]);
  assert.equal(find(page, 'aa').count, 2);
});

test('text in scripts, styles and form controls is not page text', () => {
  const page = fakePage([
    element('SCRIPT', [text('var needle;')]),
    element('style', [text('.needle {}')]),
    element('NOSCRIPT', [text('needle')]),
    element('TEXTAREA', [text('needle')]),
    element('SELECT', [element('OPTION', [text('needle')])]),
    element('P', [text('the needle')]),
  ]);

  assert.deepEqual(find(page, 'needle'), { count: 1, index: 0, capped: false });
  assert.equal(page.selection.ranges[0].start, 4);
});

test('text without a box is not counted', () => {
  const page = fakePage([
    element('DIV', [text('needle')], { display: 'none' }),
    element('DIV', [element('SPAN', [text('needle')])], { display: 'none' }),
    element('P', [text('needle')]),
  ]);

  assert.deepEqual(find(page, 'needle'), { count: 1, index: 0, capped: false });
});

test('text that has a box but is not painted is not counted', () => {
  const shown = element('LI', [text('needle shown again')], { visibility: 'visible' });
  const page = fakePage([
    element('UL', [element('LI', [text('needle in a closed menu')]), shown], { visibility: 'hidden' }),
    element('DIV', [text('needle collapsed')], { visibility: 'collapse' }),
    element('P', [text('needle')]),
  ]);

  assert.deepEqual(find(page, 'needle'), { count: 2, index: 0, capped: false });
  assert.equal(page.selection.ranges[0].node, shown.childNodes[0]);
  assert.deepEqual(page.scrolled, [shown]);
});

test('the match budget caps the count', () => {
  const page = fakePage([element('P', [text('needle needle needle')])]);
  assert.deepEqual(find(page, 'needle', { maxMatches: 2 }), { count: 2, index: 0, capped: true });
  // Exactly the budget is not capped until one more match turns up.
  assert.deepEqual(find(page, 'needle', { maxMatches: 3 }), { count: 3, index: 0, capped: false });
});

test('the node budget ends the walk', () => {
  const paragraphs = [1, 2, 3].map(() => element('P', [text('needle')]));
  assert.deepEqual(find(fakePage(paragraphs), 'needle', { maxNodes: 2 }), { count: 2, index: 0, capped: true });
});

test('a spent time budget ends the walk at the next look at the clock', () => {
  const paragraphs = Array.from({ length: 300 }, () => element('P', [text('needle')]));
  const result = find(fakePage(paragraphs), 'needle', { maxMillis: -1 });
  assert.equal(result.capped, true);
  // How many nodes pass between two looks at the clock is the script's own business.
  assert.ok(result.count > 0 && result.count < 300, `stopped after ${result.count} of 300`);
});

test('no match selects nothing and scrolls nowhere', () => {
  const page = fakePage([element('P', [text('hay')])]);
  assert.deepEqual(find(page, 'needle'), { count: 0, index: -1, capped: false });
  assert.deepEqual(page.selection.ranges, []);
  assert.deepEqual(page.scrolled, []);
});

test('clearing gives back the selection the page had before find', () => {
  const paragraph = element('P', [text('needle needle')]);
  const page = fakePage([element('H1', [text('heading')]), paragraph]);
  const before = pageRange(page.document.body.childNodes[0].childNodes[0]);
  page.selection.addRange(before);

  find(page, 'needle', { index: 0 });
  // Stepping on keeps the page's selection, not the first match, as the one to give back.
  find(page, 'needle', { index: 1 });
  assert.deepEqual(selected(page), ['needle']);

  assert.deepEqual(clear(page), { count: 0, index: -1, capped: false });
  assert.deepEqual(page.selection.ranges, [before]);
});

test('a query that no longer matches gives the page\'s selection back too', () => {
  const page = fakePage([element('P', [text('needle')])]);
  const before = pageRange(page.document.body.childNodes[0].childNodes[0]);
  page.selection.addRange(before);

  find(page, 'needle');
  assert.equal(find(page, 'needles').count, 0);

  assert.deepEqual(page.selection.ranges, [before]);
});

test('clearing leaves a selection made since find alone', () => {
  const page = fakePage([element('P', [text('needle')])]);
  find(page, 'needle');
  const since = pageRange(page.document.body.childNodes[0].childNodes[0]);
  page.selection.removeAllRanges();
  page.selection.addRange(since);

  clear(page);
  assert.deepEqual(page.selection.ranges, [since]);

  // And find's state is gone: another clear changes nothing either.
  clear(page);
  assert.deepEqual(page.selection.ranges, [since]);
});

test('the PDF viewer answers that it cannot be searched, and its labels are not counted', () => {
  const page = fakePage([element('P', [text('Loading needle')])], { pdfViewer: true });
  assert.deepEqual(find(page, 'needle'), { unavailable: true });
  assert.deepEqual(page.selection.ranges, []);
});
