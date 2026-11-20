const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/shared/sharedFunctions.js'), 'utf8');

function setup() {
  const requests = [];
  const updates = [];
  const messages = [];
  const loader = { visible: false, show() { this.visible = true; }, hide() { this.visible = false; } };
  const $ = selector => {
    assert.equal(selector, '#loader');
    return loader;
  };
  $.post = (url, success, dataType) => {
    assert.equal(dataType, 'json');
    const request = {
      url,
      resolve: success,
      fail(callback) { this.reject = callback; },
    };
    requests.push(request);
    return request;
  };
  const context = vm.createContext({ $, starexecRoot: '/', log() {},
    showMessage: (...args) => messages.push(args),
  });
  vm.runInContext(source, context);
  context.populateSpaceDetails = (data, id) => {
    updates.push({ data, id });
    loader.hide();
  };
  return { get: context.getSpaceDetails, requests, updates, messages, loader };
}

test('only the most recent selection can update the view, including A–B–A', () => {
  const { get, requests, updates, messages, loader } = setup();
  get(10);
  get(20);
  get(10);
  assert.deepEqual(requests.map(request => request.url), [
    '/services/space/10', '/services/space/20', '/services/space/10',
  ]);

  const current = { space: { id: 10 } };
  requests[2].resolve(current);
  requests[0].resolve({ space: { id: 10 } });
  requests[1].resolve({ space: { id: 20 } });
  assert.deepEqual(updates, [{ data: current, id: 10 }]);
  assert.equal(loader.visible, false);
  assert.deepEqual(messages, []);
});

test('an obsolete failure leaves the current loader alone and the current failure clears it', () => {
  const { get, requests, updates, messages, loader } = setup();
  get(10);
  get(20);
  requests[0].reject();
  assert.equal(loader.visible, true);
  assert.deepEqual(messages, []);

  requests[1].reject();
  assert.equal(loader.visible, false);
  assert.equal(messages.length, 1);
  assert.equal(messages[0][0], 'error');
  assert.deepEqual(updates, []);
});
