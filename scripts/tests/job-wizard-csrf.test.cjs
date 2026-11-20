const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../../starexec-app/src/main/webapp/js/add/job.js'),
  'utf8'
);

async function openWizard(options = {}) {
  const { token = 'synthetic-csrf-token', rows = [], ok = true, status = 200 } = options;
  const header = Object.hasOwn(options, 'header') ? options.header : 'X-CSRF-Token';
  const requests = [];
  const errors = [];
  let ready;
  const document = {};

  function $(selector) {
    const chain = {
      ready(callback) { ready = callback; return chain; },
      attr(name) {
        if (selector === '#spaceIdInput') return '42';
        if (selector === '#remainingQuota') return '100';
        if (selector === 'meta[name="csrf-token"]' && name === 'content') return token;
        if (selector === 'meta[name="csrf-header"]' && name === 'content') return header;
        return undefined;
      },
      prop() { return chain; },
      submit() { return chain; },
    };
    return chain;
  }
  $.get = () => ({ fail() {} });

  const context = vm.createContext({
    $,
    document,
    starexecRoot: '/starexec/',
    log() {},
    setTimeout() {},
    window: { location: {} },
    console: { log() {}, error: (...args) => errors.push(args) },
    fetch: async (url, fetchOptions) => {
      requests.push({ url, options: fetchOptions });
      const accepted = Boolean(token) && fetchOptions.headers?.[header || 'X-CSRF-Token'] === token;
      return {
        ok: ok && accepted,
        status: accepted ? status : 403,
        json: async () => {
          if (!accepted) throw new SyntaxError('Forbidden response is not JSON');
          return { aaData: rows };
        },
      };
    },
  });

  vm.runInContext(source, context);
  context.checkIfLeafSpace = () => {};
  context.initUI = () => {};
  context.attachFormValidation = () => {};
  ready();
  await new Promise(resolve => setImmediate(resolve));
  return { context, requests, errors };
}

test('job wizard benchmark pagination POST sends X-CSRF-Token header', async () => {
  const { context, requests, errors } = await openWizard();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/starexec/services/job/42/allbench/pagination');
  assert.equal(requests[0].options.method, 'POST');
  assert.equal(requests[0].options.headers?.['X-CSRF-Token'], 'synthetic-csrf-token');
  assert.equal(context.hasNoBenchmarks, true);
  assert.equal(errors.length, 0);
});

test('job wizard preserves custom csrf-header meta name and non-empty benchmark list', async () => {
  const { context, requests, errors } = await openWizard({
    header: 'X-Custom-CSRF',
    rows: [['bench-1']],
  });
  assert.equal(requests[0].options.headers?.['X-Custom-CSRF'], 'synthetic-csrf-token');
  assert.equal(context.hasNoBenchmarks, false);
  assert.equal(errors.length, 0);
});

test('missing CSRF token fails cleanly instead of marking space empty', async () => {
  const { context, errors } = await openWizard({ token: '' });
  assert.equal(context.hasNoBenchmarks, false);
  assert.equal(errors.length, 1);
});
