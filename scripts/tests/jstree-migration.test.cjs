const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/util/tree.js'), 'utf8');
const feed = (id, rel, state, children = []) => ({
  attr: { id, rel, defaultQueueId: 9, maxStages: 3, cLass: 'rootNode' },
  data: 'Research & Logic', state, children,
});
function api($ = {}) {
  const context = { star: {}, $, starexecRoot: '/app/', showMessage() {} };
  vm.runInNewContext(source, context);
  return context.star;
}

test('keeps offline children and metadata while only remote closed nodes load lazily', () => {
  const star = api();
  const nodes = star.treeNodes([feed(2, 'space', 'closed', [feed(3, 'space', 'leaf')])], false, false);
  assert.equal(nodes[0].id, '2');
  assert.equal(nodes[0].text, 'Research & Logic');
  assert.equal(nodes[0].li_attr.class, 'rootNode');
  assert.equal(nodes[0].children[0].data.maxStages, 3);
  assert.equal(nodes[0].children[0].children, false);
  assert.equal(star.treeNodes([feed(2, 'space', 'closed')], true, false)[0].children, true);
  assert.equal(star.treeNodes([feed(2, 'space', 'closed')], false, false)[0].children, false);
});

test('queue and worker IDs do not collide and API identifiers stay numeric strings', () => {
  const nodes = api().treeNodes([feed(7, 'active_queue', 'closed'), feed(7, 'enabled_node', 'leaf')], true, true);
  assert.equal(nodes[0].id, 'queue-7');
  assert.equal(nodes[1].id, 'node-7');
  assert.equal(nodes[0].data.id, '7');
  assert.equal(nodes[1].data.id, '7');
});

test('requests the root and then a lazy child using the original backend IDs', () => {
  const requests = [];
  const star = api({ getJSON(url, params) {
    const request = { url, params, done(fn) { this.resolve = fn; return this; }, fail(fn) { this.reject = fn; } };
    requests.push(request);
    return request;
  } });
  const $tree = { on() { return this; }, one() { return this; }, jstree(settings) { return settings; } };
  const settings = star.mountTree($tree, '/app/queues', { rootId: -1, queues: true, stateKey: 'cluster', select() {} });
  const results = [];
  settings.core.data({ id: '#' }, value => results.push(value));
  requests[0].resolve([feed(6, 'active_queue', 'closed')]);
  settings.core.data(results[0][0], value => results.push(value));
  requests[1].reject();
  assert.deepEqual(requests.map(request => request.params.id), [-1, '6']);
  assert.equal(results[1], false);
  assert.equal(settings.core.force_text, true);
  assert.equal(settings.state.key, 'starexec-tree-/app/cluster');
});

test('walks a deep link only after each lazy parent loads and reports a missing child', () => {
  const star = api();
  const available = new Set(['1']);
  const opened = [];
  const tree = {
    get_node: id => available.has(id),
    open_node(id, done) {
      opened.push(id);
      available.add(id === '1' ? '2' : '3');
      done();
    },
  };
  let selected;
  star.openTreePath(tree, ['1', '2', '3'], id => { selected = id; });
  assert.deepEqual(opened, ['1', '2']);
  assert.equal(selected, '3');
  star.openTreePath(tree, ['1', '8'], id => { selected = id; });
  assert.equal(selected, null);
});
