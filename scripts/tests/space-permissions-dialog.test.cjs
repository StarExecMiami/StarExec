const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/explore/spaces.js'), 'utf8');

function setup({ isLeader = false } = {}) {
  const elements = new Map();
  const requests = [];
  const $ = selector => {
    if (!elements.has(selector)) {
      elements.set(selector, {
        value: '', hidden: true, href: undefined, children: [],
        ready() {},
        text(value) { this.value = value; return this; },
        empty() { this.children = []; return this; },
        append(value) { this.children.push(value); return this; },
        prop(name, value) { this[name] = value; return this; },
        attr(name, value) { this[name] = value; return this; },
        removeAttr(name) { delete this[name]; return this; },
      });
    }
    return elements.get(selector);
  };
  $.ajax = options => {
    const request = {
      options,
      done(callback) { this.resolve = callback; return this; },
      fail(callback) { this.reject = callback; return this; },
      abort() { this.aborted = true; this.reject({ status: 0 }); },
    };
    requests.push(request);
    return request;
  };
  const context = vm.createContext({ document: {}, $, starexecRoot: '/' });
  vm.runInContext(source, context);
  context.buildSpacePermissionTable = perms => perms;
  const selection = { isLeader, spaceId: '10' };
  context.EXP_SP.permissionSelection = selection;
  const open = userId => context.loadSpacePermissions({ selection, userId });
  return {
    open,
    close: context.cancelSpacePermissionRequest,
    requests,
    status: $('#space-permission-status'),
    results: $('#space-permission-results'),
    retry: $('#space-permission-retry'),
    edit: $('#space-permission-edit'),
  };
}

test('a transient failure offers a retry and a denied lookup hides the controls', () => {
  const { open, requests, status, results, retry, edit } = setup();
  open(42);
  assert.equal(requests[0].options.url, '/services/permissions/details/42/10');
  assert.equal(requests[0].options.dataType, 'json');
  requests[0].reject({ status: 503 });
  assert.match(status.value, /Try again/);
  assert.equal(retry.hidden, false);
  assert.equal(edit.hidden, true);

  open(42);
  assert.equal(requests[1].options.url, requests[0].options.url);
  assert.equal(retry.hidden, true);
  requests[1].resolve({ success: false });
  assert.match(status.value, /cannot view/);
  assert.deepEqual(results.children, []);
  assert.equal(retry.hidden, true);
  assert.equal(edit.hidden, true);
});

test('closing and reopening suppresses callbacks belonging to obsolete users', () => {
  const { open, close, requests, results, retry } = setup();
  open(42);
  close();
  assert.equal(requests[0].aborted, true);
  open(43);

  requests[0].resolve({ perm: { isLeader: true } });
  requests[0].reject({ status: 503 });
  assert.deepEqual(results.children, []);
  assert.equal(retry.hidden, true);

  const perms = { isLeader: false, removeBench: true };
  requests[1].resolve({ perm: perms });
  assert.deepEqual(results.children, [perms]);
});

test('editing is limited to administrators and leaders who may edit the target', () => {
  for (const [isLeader, role, isCommunity, targetIsLeader, canEdit] of [
    [false, 'user', false, false, false],
    [false, 'developer', false, false, false],
    [false, 'admin', true, true, true],
    [true, 'user', true, true, false],
    [true, 'user', true, false, true],
    [true, 'user', false, true, true],
  ]) {
    const { open, requests, edit } = setup({ isLeader });
    open(42);
    requests[0].resolve({ perm: { isLeader: targetIsLeader }, requester: { role }, isCommunity });
    assert.equal(edit.hidden, !canEdit, JSON.stringify({ isLeader, role, isCommunity, targetIsLeader }));
    assert.equal(edit.href, canEdit ? '/secure/edit/spacePermissions.jsp?id=10' : undefined);
  }
});
