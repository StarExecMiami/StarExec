const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const alertsSrc = fs.readFileSync(
  path.join(root, 'starexec-app/src/main/webapp/js/common/alerts.js'),
  'utf8'
);
const sharedSrc = fs.readFileSync(
  path.join(root, 'starexec-app/src/main/webapp/js/details/shared.js'),
  'utf8'
);

function createFakeDom() {
  class FakeNode {
    constructor(tagName) {
      this.tagName = tagName;
      this.children = [];
      this.attributes = {};
      this.style = {};
      this._textContent = '';
      this.innerHTMLCalls = [];
    }
    setAttribute(k, v) { this.attributes[k] = v; }
    appendChild(child) { this.children.push(child); return child; }
    get textContent() { return this._textContent; }
    set textContent(v) { this._textContent = String(v); }
    set innerHTML(v) { this.innerHTMLCalls.push(v); }
  }
  const document = {
    createElement(tag) { return new FakeNode(tag); },
    createTextNode(text) { return { nodeType: 3, textContent: String(text) }; },
  };
  return { document, FakeNode };
}

test('Alerts.create treats HTML-like message as plain text by default', () => {
  const { document } = createFakeDom();
  const window = { Alerts: null };
  const context = vm.createContext({ window, document, console });
  vm.runInContext(alertsSrc, context);

  const payload = '<img src=x onerror=alert(1)> & test';
  const el = window.Alerts.create({ type: 'error', message: payload, dismissible: false });
  const contentDiv = el.children[0];
  assert.equal(contentDiv.children.length, 1);
  assert.equal(contentDiv.children[0].nodeType, 3);
  assert.equal(contentDiv.children[0].textContent, payload);
  assert.equal(contentDiv.innerHTMLCalls.length, 0);
});

test('makeAnonymousLinkPost rejects javascript: URLs and constructs link safely', () => {
  const createdNodes = [];
  const dialogText = [];
  const dialog = {
    empty() { createdNodes.length = 0; dialogText.length = 0; return this; },
    append(node) { createdNodes.push(node); return this; },
    text(val) { dialogText.push(val); return this; },
    dialog() { return this; },
  };
  function $(selector, props) {
    if (selector === '<a>') return { tag: 'a', ...props };
    if (selector === '#dialog-show-anonymous-link') return dialog;
    return { ready() {}, click() {}, button() {}, hide() {} };
  }
  const messages = [];
  let destroyed = 0;
  $.post = (_url, _data, cb) => {
    cb({ success: true, message: 'javascript:alert(1)' });
    return { fail() { return this; } };
  };

  const context = vm.createContext({
    $,
    document: {},
    starexecRoot: '/starexec/',
    log() {},
    createDialog() {},
    destroyDialog() { destroyed++; },
    parseReturnCode() {},
    showMessage(type, msg) { messages.push({ type, msg }); },
  });
  vm.runInContext(sharedSrc, context);

  context.makeAnonymousLinkPost('job', 1, 'all');
  assert.equal(createdNodes.length, 0);
  assert.deepEqual(dialogText, ['javascript:alert(1)']);

  $.post = (_url, _data, cb) => {
    cb({ success: true, message: 'https://example.org/anon?id=1"><script>' });
    return { fail() { return this; } };
  };
  context.makeAnonymousLinkPost('job', 1, 'all');
  assert.equal(createdNodes.length, 1);
  assert.equal(createdNodes[0].tag, 'a');
  assert.equal(createdNodes[0].href, 'https://example.org/anon?id=1"><script>');
  assert.equal(createdNodes[0].text, 'https://example.org/anon?id=1"><script>');

  $.post = () => ({ fail(fn) { fn(); return this; } });
  const beforeDestroyed = destroyed;
  context.makeAnonymousLinkPost('job', 1, 'all');
  assert.equal(destroyed, beforeDestroyed + 1);
  assert.equal(messages.at(-1).type, 'error');
});
