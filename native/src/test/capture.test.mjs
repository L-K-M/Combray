import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const template = readFileSync(new URL('../capture.js', import.meta.url), 'utf8');
const text = data => ({ nodeType: 3, data });
const element = (tagName, childNodes, style = {}) => ({
  nodeType: 1,
  tagName,
  childNodes,
  style,
  hasAttribute: () => false,
  getAttribute: () => null,
});

function capture(children, maxBytes = 4096) {
  const script = template
    .replaceAll('__MAX_TEXT_BYTES__', String(maxBytes))
    .replaceAll('__MAX_NODES__', '1000')
    .replaceAll('__MAX_MILLIS__', '1000')
    .replace('__STAND_IN__', 'function isServoStandIn() { return false; } function isCombrayPdfViewer() { return false; }');
  return vm.runInNewContext(script, {
    document: { URL: 'https://synthetic.example/article', title: 'Synthetic article', body: element('BODY', children) },
    window: { getComputedStyle: node => ({ display: 'inline', visibility: 'visible', ...node.style }) },
    performance: { now: () => 0 },
  }, { timeout: 1000 });
}

test('a whitespace-only node separates rendered inline words', () => {
  assert.equal(capture([element('B', [text('one')]), text(' '), element('I', [text('two')])]).text, 'one two');
});

test('tabs and newlines between inline links collapse to one separator', () => {
  assert.equal(capture([element('A', [text('one')]), text('\n\t '), element('A', [text('two')])]).text, 'one two');
});

test('spaces across adjacent text and element boundaries collapse', () => {
  const nodes = [text('one '), text(' '), element('SPAN', [text('\t ')]), text(' two')];
  assert.equal(capture(nodes).text, 'one two');
});

test('markup with no rendered separator does not split a word', () => {
  assert.equal(capture([element('B', [text('extra')]), element('I', [text('ordinary')])]).text, 'extraordinary');
});

test('block boundaries stay newlines, not extra blank lines or spaces', () => {
  assert.equal(capture([element('P', [text('one')]), text('\n '), element('P', [text('two')])]).text, 'one\ntwo');
});

test('hidden subtrees cannot introduce a separator or hidden words', () => {
  const hidden = element('SPAN', [text(' secret ')], { display: 'none' });
  assert.equal(capture([text('one'), hidden, text('two')]).text, 'onetwo');
});

test('leading whitespace does not spend a text byte', () => {
  const result = capture([text(' \n\t '), text('one')], 3);
  assert.equal(result.text, 'one');
  assert.equal(result.truncated, false);
});

test('separators obey the UTF-8 cap without splitting multibyte characters', () => {
  const nodes = [text('é'), text(' '), element('I', [text('猫')])];
  for (const [cap, expected, truncated] of [[2, 'é', true], [3, 'é', true], [5, 'é', true], [6, 'é 猫', false]]) {
    const result = capture(nodes, cap);
    assert.equal(result.text, expected, `cap ${cap}`);
    assert.equal(result.truncated, truncated, `cap ${cap}`);
    assert.ok(Buffer.byteLength(result.text, 'utf8') <= cap);
    assert.ok(!result.text.includes('\uFFFD'));
  }
});
