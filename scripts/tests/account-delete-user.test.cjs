const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/edit/account.js'), 'utf8');

function setup(parseResult) {
  const posts = [];
  const redirects = [];
  const errors = [];
  const parsedCodes = [];
  let reloads = 0;
  const elements = new Map();
  let readyCallback = null;
  const el = selector => {
    if (!elements.has(selector)) {
      const listeners = {};
      elements.set(selector, {
        value: '',
        listeners,
        ready(fn) { readyCallback = fn; },
        click(fn) {
          if (typeof fn === 'function') {
            listeners.click = fn;
          } else if (listeners.click) {
            listeners.click();
          }
          return this;
        },
        on(evt, fn) { listeners[evt] = fn; return this; },
        val(v) {
          if (arguments.length === 0) return this.value;
          this.value = v;
          return this;
        },
        attr() { return ''; },
        find() { return this; },
        each() { return this; },
        data() { return 42; },
        expandable() { return this; },
        dataTable() { return this; },
        validate() { return this; },
        submit() { return this; },
        hide() { return this; },
        show() { return this; },
        prop() { return this; },
        children() { return this; },
        dialog(opts) {
          this.dialogOptions = opts;
          return this;
        },
      });
    }
    return elements.get(selector);
  };
  const $ = element => {
    if (element && typeof element === 'object') {
      return element.dialog ? element : el(element);
    }
    return el(element);
  };
  $.validator = {
    passwordStrengthMeter() {},
    addMethod() {},
  };
  $.post = (url, data, callback, dataType) => {
    const request = {
      url,
      data,
      callback: typeof data === 'function' ? data : callback,
      dataType,
    };
    posts.push(request);
    return {
      fail(handler) {
        request.reject = handler;
        return this;
      },
    };
  };
  const context = vm.createContext({
    $,
    document: {},
    starexecRoot: '/starexec/',
    defaultPageSize: 10,
    log() {},
    addValidators() {},
    getDataTablesDom: () => 'rt',
    fnSolverPaginationHandler() {},
    fnBenchmarkPaginationHandler() {},
    parseReturnCode(code) {
      parsedCodes.push(code);
      return typeof parseResult === 'function' ? parseResult(code) : parseResult;
    },
    showMessage: (...args) => errors.push(args),
    location: {
      reload() { reloads += 1; },
    },
    window: {
      location: {
        replace: url => redirects.push(url),
      },
    },
  });
  vm.runInContext(source, context);
  return {
    context,
    posts,
    redirects,
    errors,
    parsedCodes,
    el,
    initReady: () => readyCallback && readyCallback(),
    getReloads: () => reloads,
  };
}

test('sendDeleteUserRequest redirects only when parseReturnCode indicates success', () => {
  const failed = setup(code => Boolean(code && code.success));
  failed.context.sendDeleteUserRequest(44);
  assert.equal(failed.posts.length, 1);
  assert.equal(failed.posts[0].url, '/starexec/services/delete/user/44');
  failed.posts[0].callback({ success: false, message: 'Unable to delete user.' });
  assert.equal(failed.parsedCodes.length, 1);
  assert.deepEqual(failed.redirects, []);

  const succeeded = setup(code => Boolean(code && code.success));
  succeeded.context.sendDeleteUserRequest(42);
  assert.equal(succeeded.posts.length, 1);
  assert.equal(succeeded.posts[0].url, '/starexec/services/delete/user/42');
  succeeded.posts[0].callback({ success: true, message: 'The user has been successfully deleted.' });
  assert.equal(succeeded.parsedCodes.length, 1);
  assert.deepEqual(succeeded.redirects, ['/starexec/']);
});

test('sendDeleteUserRequest reports internal error and does not redirect when request fails', () => {
  const { context, posts, redirects, errors } = setup(true);
  context.sendDeleteUserRequest(50);
  posts[0].reject();
  assert.deepEqual(redirects, []);
  assert.deepEqual(errors, [['error', 'Internal error deleting user', 5000]]);
});

test('#saveProfile validates via parseReturnCode, never hardcodes success toast, and handles failure', () => {
  const denied = setup(code => Boolean(code && code.success));
  denied.initReady();
  denied.el('#saveProfile').click();
  assert.equal(denied.posts.length, 1);
  assert.equal(denied.posts[0].url, '/starexec/secure/add/profile');
  denied.posts[0].callback({ success: false, message: 'Invalid timeout' });
  assert.equal(denied.parsedCodes.length, 1);
  assert.equal(denied.getReloads(), 0);
  assert.ok(!denied.errors.some(e => e[0] === 'success'));

  const invalidJson = setup(true);
  invalidJson.initReady();
  invalidJson.el('#saveProfile').click();
  invalidJson.posts[0].callback('{bad');
  assert.equal(invalidJson.getReloads(), 0);
  assert.equal(invalidJson.errors[0][0], 'error');

  const emptySuccess = setup(code => code && code.success === undefined ? true : Boolean(code && code.success));
  emptySuccess.initReady();
  emptySuccess.el('#saveProfile').click();
  emptySuccess.posts[0].callback('');
  assert.equal(emptySuccess.getReloads(), 1);
  assert.deepEqual(emptySuccess.errors, []);

  const failed = setup(true);
  failed.initReady();
  failed.el('#saveProfile').click();
  failed.posts[0].reject({}, 'error', 'Bad Request');
  assert.equal(failed.getReloads(), 0);
  assert.equal(failed.errors[0][0], 'error');
});

test('#createProfile validates via parseReturnCode, never hardcodes success toast, and handles failure', () => {
  const denied = setup(code => Boolean(code && code.success));
  denied.initReady();
  denied.el('#createProfile').click();
  const dialog = denied.el('#dialog-createSettingsProfile');
  let closed = false;
  dialog.dialog = function(arg) {
    if (arg === 'close') {
      closed = true;
      return this;
    }
    this.dialogOptions = arg;
    return this;
  };
  denied.el('#createProfile').click();
  dialog.dialogOptions.buttons.create.call(dialog);
  assert.equal(closed, true);
  assert.equal(denied.posts.length, 1);
  assert.equal(denied.posts[0].url, '/starexec/secure/add/profile');
  denied.posts[0].callback(JSON.stringify({ success: false, message: 'Denied' }));
  assert.equal(denied.parsedCodes.length, 1);
  assert.equal(denied.getReloads(), 0);
  assert.ok(!denied.errors.some(e => e[0] === 'success'));

  const succeeded = setup(code => code && code.success === undefined ? true : Boolean(code && code.success));
  succeeded.initReady();
  succeeded.el('#createProfile').click();
  const okDialog = succeeded.el('#dialog-createSettingsProfile');
  okDialog.dialog = function(arg) {
    if (arg === 'close') return this;
    this.dialogOptions = arg;
    return this;
  };
  succeeded.el('#createProfile').click();
  okDialog.dialogOptions.buttons.create.call(okDialog);
  succeeded.posts[0].callback('');
  assert.equal(succeeded.getReloads(), 1);
  assert.deepEqual(succeeded.errors, []);

  const forbidden = setup(true);
  forbidden.initReady();
  forbidden.el('#createProfile').click();
  const fbDialog = forbidden.el('#dialog-createSettingsProfile');
  fbDialog.dialog = function(arg) {
    if (arg === 'close') return this;
    this.dialogOptions = arg;
    return this;
  };
  forbidden.el('#createProfile').click();
  fbDialog.dialogOptions.buttons.create.call(fbDialog);
  forbidden.posts[0].reject({ statusText: 'Forbidden' }, 'error', 'Forbidden');
  assert.equal(forbidden.getReloads(), 0);
  assert.equal(forbidden.errors[0][0], 'error');
});
