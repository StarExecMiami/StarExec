const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/explore/communities.js'), 'utf8');
const leaf = (id, data) => ({ attr: { id, rel: 'space' }, data, state: 'leaf', children: [] });

function setup(initialCookies = {}) {
  const cookies = new Map(Object.entries(initialCookies));
  const requests = [];
  const messages = [];
  const elements = new Map();
  const $ = selector => {
    if (!elements.has(selector)) {
      elements.set(selector, {
        hidden: true, value: '', events: {},
        ready() {},
        text(value) { this.value = value; return this; },
        attr(name, value) { this[name] = value; return this; },
        prop(name, value) {
          if (arguments.length === 1) return this[name];
          this[name] = value;
          return this;
        },
        trigger(name) { this.events[name]?.(); return this; },
        on(name, fn) { this.events[name] = fn; return this; },
        one(name, fn) { return this.on(name, fn); },
        jstree(config) { this.config = config; },
        show() { this.hidden = false; },
        hide() { this.hidden = true; },
      });
    }
    return elements.get(selector);
  };
  $.get = (url, callback) => {
    const request = {
      url,
      resolve: callback,
      done(fn) { this.resolve = fn; return this; },
      fail(fn) { this.reject = fn; return this; },
    };
    requests.push(request);
    return request;
  };
  $.getJSON = url => $.get(url);
  $.cookie = (name, value) => value === undefined ? cookies.get(name) : cookies.set(name, value);
  const context = vm.createContext({ document: {}, $, starexecRoot: '/app/', log() {},
    showMessage: (...args) => messages.push(args),
  });
  vm.runInContext(source, context);
  return { $, cookies, requests, messages, context };
}

test('maps only the flat JSON contract and sorts community labels without altering them', () => {
  const { context } = setup();
  const nodes = context.communityTreeNodes([leaf(2, 'beta'), leaf(1, 'Alpha & Omega'), leaf(3, 'BETA')]);
  assert.deepEqual(JSON.parse(JSON.stringify(nodes)), [
    { id: '1', text: 'Alpha & Omega', li_attr: { rel: 'space' } },
    { id: '2', text: 'beta', li_attr: { rel: 'space' } },
    { id: '3', text: 'BETA', li_attr: { rel: 'space' } },
  ]);
  assert.equal(context.communityTreeNodes({ success: false }), null);
  assert.equal(context.communityTreeNodes([leaf('3/4', 'Wrong type')]), null);
});

test('restores a valid legacy selection and writes only the pilot cookie', () => {
  const { $, cookies, requests, context } = setup({
    'starexec-community-selection': '99', jstree_select: '#10,#2',
  });
  const selected = [];
  context.initCommunityTree(id => selected.push(id));
  assert.equal(requests[0].url, '/app/services/communities/all');
  requests[0].resolve([leaf(2, 'Beta'), leaf(1, 'Alpha')]);
  const tree = $('#exploreList');
  assert.equal(tree.config.core.force_text, true);
  assert.equal(tree.config.core.multiple, false);
  const instance = {
    get_node: id => tree.config.core.data.find(node => node.id === id),
    select_node: id => tree.events['select_node.jstree']({}, { node: { id } }),
  };
  tree.events['ready.jstree']({}, { instance });
  assert.deepEqual(selected, ['2']);
  assert.equal(tree['aria-activedescendant'], '2');
  assert.equal(cookies.get('starexec-community-selection'), '2');
  assert.equal(cookies.get('jstree_select'), '#10,#2');
});

test('shows a retry on transport or payload failures and distinguishes an empty list', () => {
  const { $, requests, context } = setup();
  context.initCommunityTree(() => assert.fail('Nothing was selected'));
  const status = $('#community-tree-status');
  const retry = $('#community-tree-retry');
  assert.match(status.value, /Loading/);
  requests[0].reject();
  assert.equal(retry.hidden, false);
  retry.trigger('click');
  retry.trigger('click');
  assert.equal(requests.length, 2);
  requests[1].resolve({ success: false });
  assert.match(status.value, /Could not load/);
  retry.trigger('click');
  requests[2].resolve([]);
  assert.match(status.value, /No communities/);
  assert.equal(retry.hidden, true);
  assert.equal($('#exploreList').config, undefined);
});

test('obsoleted community details cannot render or report an error after A–B–A', () => {
  const { $, requests, messages, context } = setup();
  const updates = [];
  context.populateDetails = data => updates.push(data);
  context.getCommunityDetails('1');
  context.getCommunityDetails('2');
  context.getCommunityDetails('1');
  requests[0].resolve('old');
  requests[1].reject();
  requests[2].resolve('current');
  assert.deepEqual(updates, ['current']);
  assert.deepEqual(messages, []);

  context.getCommunityDetails('2');
  requests[3].reject();
  assert.equal($('#loader').hidden, true);
  assert.equal(messages.length, 1);
});
