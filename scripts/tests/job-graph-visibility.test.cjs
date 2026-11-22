const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');

function createHarness(initiallyVisible) {
  let graphVisible = initiallyVisible;
  const handlers = {};
  const posts = [];
  let reloadCallback;

  const makeElement = selector => {
    const stub = {
      length: 1,
      ready() { return stub; },
      hide() {
        if (selector === '#graphField') { graphVisible = false; }
        return stub;
      },
      show() {
        if (selector === '#graphField') { graphVisible = true; }
        return stub;
      },
      is(query) {
        if (selector === '#graphField' && query === ':visible') {
          return graphVisible;
        }
        return false;
      },
      empty() { return stub; },
      append() { return stub; },
      children() { return stub; },
      prop() { return stub; },
      val() { return []; },
      attr() { return selector.indexOf('solverChoice') !== -1 ? '10' : ''; },
      text() { return stub; },
      click(fn) { handlers[selector] = fn; return stub; },
      on() { return stub; },
      off() { return stub; },
      expandable() { return stub; },
      toggleClass() { return stub; },
    };
    return stub;
  };
  makeElement.cookie = () => 'false';
  makeElement.post = (url, data, cb) => {
    posts.push(url);
    return { fail() {} };
  };

  const documentFragment = () => ({ appendChild() {}, cloneNode() { return {}; } });
  const context = vm.createContext({
    document: {
      createDocumentFragment: documentFragment,
      createElement: () => ({}),
      getElementById: () => ({ appendChild() {} }),
    },
    isLocalJobPage: false,
    curSpaceId: 42,
    starexecRoot: '/starexec/',
    star: {},
    log() {},
    setTimeButtonText() {},
    attachSortButtonFunctions() {},
    startGraphRequest() { return () => true; },
    onGraphRequestError() { return () => {}; },
    getSelectedStage() { return '0'; },
    summaryTable: {
      api() {
        return {
          page: { info: () => ({ recordsTotal: 2 }) },
          data: () => ({ toArray: () => [[1, '10', 'S1', 'C1'], [2, '20', 'S2', 'C2']] }),
          ajax: { reload: cb => { reloadCallback = cb; } },
        };
      },
    },
    $: makeElement,
  });

  vm.runInContext(source, context);
  context.initUI();

  return {
    context,
    posts,
    handlers,
    setGraphVisible(v) { graphVisible = v; },
    runStatsReload() {
      context.refreshStats(42);
      reloadCallback();
    },
  };
}

test('refreshStats does not request solverComparison while #graphField is hidden', () => {
  const h = createHarness(false);
  h.runStatsReload();
  assert.deepEqual(h.posts, []);
  assert.equal(h.context.solverComparisonPendingLoad, true);
});

test('explicitly selecting or updating solverComparison requests it once #graphField is visible', () => {
  const h = createHarness(false);
  h.runStatsReload();
  assert.equal(h.posts.length, 0);

  // Clicking tab while panel is still hidden does not trigger a request.
  h.handlers['#selectSolverComparison']();
  assert.equal(h.posts.length, 0);

  // Once panel becomes visible, switching to Solver Comparison triggers the deferred load once.
  h.setGraphVisible(true);
  h.handlers['#selectSolverComparison']();
  assert.equal(h.posts.length, 1);
  assert.match(h.posts[0], /\/services\/jobs\/42\/graphs\/solverComparison\/10\/10\/300\/white\/0$/);

  // Revisiting the tab after loading does not re-request automatically.
  h.handlers['#selectSolverComparison']();
  assert.equal(h.posts.length, 1);

  // Explicit Update always triggers a request.
  h.handlers['#solverComparisonUpdate']();
  assert.equal(h.posts.length, 2);
});
