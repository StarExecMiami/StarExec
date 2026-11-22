const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const jobJs = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');
const jobScss = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/css/details/job.scss'), 'utf8');

test('CPU/wall-clock toggle switches a container class instead of mounted row spans', () => {
  const classes = new Set();
  const handlers = {};
  const makeStub = selector => {
    const stub = {
      ready() { return stub; },
      hide() { return stub; },
      show() { return stub; },
      text() { return stub; },
      attr(name) { return name === 'value' ? '{"aaData":[]}' : ''; },
      prop() { return stub; },
      val() { return []; },
      children() { return stub; },
      DataTable() { return stub; },
      clear() { return stub; },
      rows: { add() { return stub; } },
      draw() { return stub; },
      click(fn) { handlers[selector] = fn; return stub; },
      on() { return stub; },
      off() { return stub; },
      expandable() { return stub; },
      toggleClass(name, state) {
        if (selector === '#pairTblField') {
          if (state) { classes.add(name); } else { classes.delete(name); }
        }
        return stub;
      },
    };
    return stub;
  };
  makeStub.cookie = () => 'false';
  const context = vm.createContext({
    document: {},
    isLocalJobPage: true,
    curSpaceId: 1,
    includeUnknown: false,
    star: {},
    log() {},
    setTimeButtonText() {},
    attachSortButtonFunctions() {},
    $: makeStub,
  });
  vm.runInContext(jobJs, context);
  context.initUI();

  assert.equal(classes.has('show-cpu-time'), false);
  handlers['.changeTime']();
  assert.equal(classes.has('show-cpu-time'), true);
  handlers['.changeTime']();
  assert.equal(classes.has('show-cpu-time'), false);
});

test('job.scss controls .cpuTime and .wallclockTime via .show-cpu-time', () => {
  assert.match(jobScss, /\.offline-pair-table\s*\{[\s\S]*\.cpuTime\s*\{\s*display:\s*none;/);
  assert.match(jobScss, /\.show-cpu-time\s+\.offline-pair-table[\s\S]*\.wallclockTime\s*\{\s*display:\s*none;/);
  assert.match(jobScss, /\.show-cpu-time\s+\.offline-pair-table[\s\S]*\.cpuTime\s*\{\s*display:\s*inline;/);
});
