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
  const $ = element => {
    if (element && typeof element === 'object') {
      return { ready() {} };
    }
    return {};
  };
  $.post = (url, data, callback, dataType) => {
    const request = { url, data, callback, dataType };
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
    parseReturnCode(code) {
      parsedCodes.push(code);
      return typeof parseResult === 'function' ? parseResult(code) : parseResult;
    },
    showMessage: (...args) => errors.push(args),
    window: {
      location: {
        replace: url => redirects.push(url),
      },
    },
  });
  vm.runInContext(source, context);
  return { context, posts, redirects, errors, parsedCodes };
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
