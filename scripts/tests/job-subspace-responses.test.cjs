const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');

function setup(anonymous) {
  const requests = [];
  const visibility = [];
  const errors = [];
  const $ = selector => ({
    ready() {},
    hide() { visibility.push([selector, false]); },
    show() { visibility.push([selector, true]); },
    click() {},
    expandable() {},
  });
  $.getJSON = (url, respond) => {
    const request = { url, respond, fail(callback) { this.reject = callback; return this; } };
    requests.push(request);
    return request;
  };
  const context = vm.createContext({ document: {}, $, log() {}, starexecRoot: '/',
    isLocalJobPage: false, showMessage: (...args) => errors.push(args),
  });
  vm.runInContext(source, context);
  context.jobId = 3;
  context.DETAILS_JOB = {
    isAnonymousPage: anonymous, anonymousLinkUuid: 'example', primitivesToAnonymize: 'true',
  };
  const select = id => {
    context.curSpaceId = id;
    context.initializePanels();
  };
  return { context, requests, visibility, errors, select };
}

for (const anonymous of [false, true]) {
  test(`ignores a stale response even when returning to the same space (anonymous=${anonymous})`, () => {
    const { context, select, requests, visibility } = setup(anonymous);
    select(10);
    select(20);
    select(10);
    assert.deepEqual(requests.map(request => new URL(request.url, 'https://example.test').searchParams.get('id')),
      ['10', '20', '10']);
    context.panelArray = ['current panel'];
    requests[1].respond([]);
    requests[0].respond([]);
    assert.deepEqual(context.panelArray, ['current panel']);
    assert.deepEqual(visibility, []);
    requests[2].respond([]);
    assert.deepEqual(visibility, [['#subspaceSummaryField', false]]);
  });
}

test('reports only failures from the current subspace request', () => {
  const { select, requests, errors } = setup(false);
  select(10);
  select(20);
  requests[0].reject({}, 'error');
  requests[1].reject({}, 'abort');
  assert.equal(errors.length, 0);
  requests[1].reject({}, 'error');
  assert.equal(errors.length, 1);
  assert.equal(errors[0][0], 'error');
});
