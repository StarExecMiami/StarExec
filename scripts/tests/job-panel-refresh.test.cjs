const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/jobPanelView.js'), 'utf8');
const context = vm.createContext({ document: {}, $: () => ({ ready() {} }) });
vm.runInContext(source, context);

test('job panel can refresh before the initial request returns or after it fails', () => {
  assert.doesNotThrow(() => context.refreshPanels());
});

test('job panel refreshes each initialized table without resetting its page', () => {
  const requests = [];
  context.panelArray = [1, 2].map(id => ({
    api: () => ({ ajax: { reload: (...args) => requests.push([id, ...args]) } }),
  }));
  context.refreshPanels();
  assert.deepEqual(requests, [[1, null, false], [2, null, false]]);
});
