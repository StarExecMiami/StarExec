const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../../starexec-app/src/main/webapp/js/explore/cluster.js'),
  'utf8'
);

function setup() {
  const gets = [];
  const messages = [];
  const elements = new Map();
  const el = (id) => {
    if (!elements.has(id)) {
      elements.set(id, {
        value: '',
        textValue: '',
        visible: true,
        val(v) {
          if (arguments.length === 0) return this.value;
          this.value = v;
          return this;
        },
        text(v) {
          if (arguments.length === 0) return this.textValue;
          this.textValue = v;
          return this;
        },
        show() { this.visible = true; return this; },
        hide() { this.visible = false; return this; },
        css() { return this; },
        html() { return this; },
        on() { return this; },
        ready() { return this; },
        button() { return this; },
        expandable() { return this; },
        dataTable() { return { api: () => ({ ajax: { url: () => ({ load() {} }) } }), fnClearTable() {}, fnDraw() {} }; },
        DataTable() { return { data: () => [] }; },
      });
    }
    return elements.get(id);
  };

  let mountedOptions = null;
  const $ = (selector) => {
    if (typeof selector === 'function') return undefined;
    return el(selector);
  };
  $.get = (url, data, done) => {
    const req = {
      url,
      data,
      doneCb: typeof data === 'function' ? data : done,
      failCb: null,
      done(fn) { this.doneCb = fn; return this; },
      fail(fn) { this.failCb = fn; return this; },
    };
    gets.push(req);
    return req;
  };
  $.ajax = () => ({ done() { return this; }, fail() { return this; }, always() { return this; } });

  const selectedCalls = [];
  const star = {
    mountTree($tree, feedUrl, options) {
      mountedOptions = options;
      return $tree;
    },
    selectTreeNode(tree, id) {
      selectedCalls.push(id);
    },
  };

  const context = {
    $,
    jQuery: $,
    star,
    window: { setInterval: () => 1, clearInterval: () => {} },
    document: {},
    starexecRoot: '/',
    addNodeCountsToTree: () => {},
    showMessage: (...args) => messages.push(args),
    setInterval: () => 1,
    clearInterval: () => {},
    clearTimeout: () => {},
    setTimeout: () => 1,
  };
  vm.runInNewContext(source, context);
  context.jobPairTable = el('#details').dataTable();
  return { context, gets, messages, el, mountedOptions: () => mountedOptions, selectedCalls };
}

test('initClusterExplorer selects the first active_queue instead of hardcoding queue-1', () => {
  const { context, mountedOptions, selectedCalls } = setup();
  context.initClusterExplorer();
  const opts = mountedOptions();
  assert.equal(opts.initial, undefined);

  const nodes = {
    '#': { id: '#', children: ['queue-1', 'queue-14235'] },
    'queue-1': { id: 'queue-1', data: { id: '1', rel: 'inactive_queue' } },
    'queue-14235': { id: 'queue-14235', data: { id: '14235', rel: 'active_queue' } },
  };
  const fakeTree = {
    get_node: id => nodes[id],
    get_selected: () => [],
  };
  opts.ready(fakeTree);
  assert.deepEqual(selectedCalls, ['queue-14235']);
});

test('selecting an inactive queue avoids the internal error toast for loads', () => {
  const { context, gets, messages, el } = setup();
  context.getDetails('1', 'inactive_queue', null);

  assert.ok(!gets.some(r => r.url.includes('services/cluster/loads/')));
  assert.match(el('#loadOutput').val(), /inactive/i);
  assert.deepEqual(messages, []);
});

test('loadQueueLoads ignores 404 but reports 500 errors for active queues', () => {
  const { context, gets, messages, el } = setup();
  context.getDetails('14235', 'active_queue', null);

  const loadsReq404 = gets.find(r => r.url === '/services/cluster/loads/14235');
  assert.ok(loadsReq404);
  loadsReq404.failCb({ status: 404 });
  assert.deepEqual(messages, []);
  assert.match(el('#loadOutput').val(), /not available/i);

  loadsReq404.failCb({ status: 500 });
  assert.equal(messages.length, 1);
  assert.equal(messages[0][0], 'error');
  assert.equal(messages[0][1], 'Internal error loading queue loads');
});
