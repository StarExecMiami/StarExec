const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/add/benchmarks.js'), 'utf8');

function setup() {
  const puts = [];
  const reads = [];
  const timers = new Map();
  const messages = [];
  const $ = () => ({ ready() {} });
  const request = (requests, url) => {
    const pending = {
      url,
      done(callback) { this.resolve = callback; return this; },
      fail(callback) { this.reject = callback; return this; },
      abort() { this.reject({ status: 0 }, 'abort'); },
    };
    requests.push(pending);
    return pending;
  };
  $.ajax = options => request(puts, options.url);
  $.getJSON = url => request(reads, url);
  let timerId = 0;
  const context = vm.createContext({ document: {}, $, starexecRoot: '/', log() {},
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
  });
  context.window = context;
  vm.runInContext(source, context);
  context.updateUploadControlState = () => {};
  context.updateUploadProgressUi = (...args) => messages.push(args);
  context.persistStoredSession = () => {};
  let finalizations = 0;
  context.finalizeResumableUpload = () => { finalizations++; };
  context.resumableUploadState = {
    sessionId: 3, file: { size: 8, slice() {} }, chunkSize: 4, totalChunks: 2,
    nextChunkIndex: 0, bytesReceived: 0, retryCount: 0, retryTimer: null,
  };
  const session = next => ({
    success: true, id: 3, chunkSize: 4, totalChunks: 2, status: 'UPLOADING',
    nextChunkIndex: next, bytesReceived: next * 4, totalBytes: 8,
  });
  function fireTimer() {
    assert.equal(timers.size, 1);
    const [[id, timer]] = timers;
    timers.delete(id);
    timer.callback();
    return timer.delay;
  }
  context.uploadNextChunk();
  return { context, puts, reads, timers, session, messages, fireTimer,
    finalizations: () => finalizations };
}

test('permanent chunk rejection pauses without retrying or discarding the session', () => {
  const h = setup();
  h.puts[0].reject({ status: 413 }, 'error');
  assert.equal(h.reads.length, 0);
  assert.equal(h.timers.size, 0);
  assert.equal(h.context.resumableUploadState.sessionId, 3);
  assert.equal(h.context.resumableUploadState.isPaused, true);
  assert.equal(h.messages.at(-1)[0], 'Upload paused.');
});

test('transient failure retries with backoff and pauses when the budget is exhausted', () => {
  const h = setup();
  for (let attempt = 0; attempt < 3; attempt++) {
    h.puts[attempt].reject({ status: 503 }, 'error');
    h.reads[attempt].resolve(h.session(0));
    assert.equal(h.puts.length, attempt + 1);
    assert.equal(h.fireTimer(), 1000 * 2 ** attempt);
  }
  h.puts[3].reject({ status: 503 }, 'error');
  h.reads[3].resolve(h.session(0));
  assert.equal(h.puts.length, 4);
  assert.equal(h.timers.size, 0);
  assert.equal(h.context.resumableUploadState.isPaused, true);
});

test('a lost response advances to the next acknowledged chunk without replaying it', () => {
  const h = setup();
  h.puts[0].reject({ status: 0 }, 'error');
  h.reads[0].resolve(h.session(1));
  assert.deepEqual(h.puts.map(request => request.url.split('/').at(-1)), ['0', '1']);
  assert.equal(h.timers.size, 0);
});

test('an acknowledged final chunk goes straight to finalization after a lost response', () => {
  const h = setup();
  h.puts[0].reject({ status: 0 }, 'error');
  h.reads[0].resolve({ ...h.session(2), status: 'READY' });
  assert.equal(h.finalizations(), 1);
  assert.equal(h.puts.length, 1);
});

test('a recovery response from an earlier upload cannot advance a replacement upload', () => {
  const h = setup();
  h.puts[0].reject({ status: 0 }, 'error');
  const replacement = { ...h.context.resumableUploadState, sessionId: 4, xhr: null };
  h.context.resumableUploadState = replacement;
  h.reads[0].resolve(h.session(1));
  assert.equal(replacement.nextChunkIndex, 0);
  assert.equal(replacement.sessionId, 4);
  assert.equal(h.puts.length, 1);
});

test('pause cancels a pending retry, and duplicate Resume clicks share one state read', () => {
  const h = setup();
  h.puts[0].reject({ status: 503 }, 'error');
  h.reads[0].resolve(h.session(0));
  assert.equal(h.timers.size, 1);
  h.context.pauseResumableUpload();
  assert.equal(h.timers.size, 0);
  h.context.resumeResumableUpload();
  h.context.resumeResumableUpload();
  assert.equal(h.reads.length, 2);
  h.reads[1].resolve(h.session(1));
  assert.deepEqual(h.puts.map(request => request.url.split('/').at(-1)), ['0', '1']);
});

function setupFinalize() {
  const posts = [];
  const reads = [];
  const timers = new Map();
  const messages = [];
  const errors = [];
  let cleared = 0;
  const $ = () => ({ ready() {} });
  const request = (requests, url) => {
    const pending = {
      url,
      done(callback) { this.resolve = callback; return this; },
      fail(callback) { this.reject = callback; return this; },
      abort() { this.reject({ status: 0 }, 'abort'); },
    };
    requests.push(pending);
    return pending;
  };
  $.ajax = options => request(posts, options.url);
  $.getJSON = url => request(reads, url);
  let timerId = 0;
  const context = vm.createContext({ document: {}, $, starexecRoot: '/', log() {},
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
  });
  context.window = context;
  context.location = { href: '' };
  vm.runInContext(source, context);
  context.updateUploadControlState = () => {};
  context.updateUploadProgressUi = (...args) => messages.push(args);
  context.persistStoredSession = () => {};
  context.clearStoredSession = () => { cleared++; };
  context.showResumableUploadError = message => errors.push(message);
  context.resumableUploadState = {
    sessionId: 9, file: { name: 'large.tgz', size: 1024 }, chunkSize: 512, totalChunks: 2,
    nextChunkIndex: 2, bytesReceived: 1024, retryCount: 0, retryTimer: null,
    finalizeRetryCount: 0, finalizeTimer: null, isFinalizing: false, isStopped: false,
  };
  function fireTimer() {
    assert.equal(timers.size, 1);
    const [[id, timer]] = timers;
    timers.delete(id);
    timer.callback();
    return timer.delay;
  }
  return { context, posts, reads, timers, messages, errors, fireTimer, cleared: () => cleared };
}

test('async FINALIZING response polls session until COMPLETE and redirects', () => {
  const h = setupFinalize();
  h.context.finalizeResumableUpload();
  assert.equal(h.posts.length, 1);

  h.posts[0].resolve({ success: true, id: 9, sessionId: 9, status: 'FINALIZING' });
  assert.equal(h.timers.size, 1);
  assert.equal(h.fireTimer(), 2000);
  assert.equal(h.reads.length, 1);

  h.reads[0].resolve({ success: true, id: 9, status: 'FINALIZING' });
  assert.equal(h.fireTimer(), 2000);
  assert.equal(h.reads.length, 2);

  h.reads[1].resolve({ success: true, id: 9, status: 'COMPLETE', jobId: 88 });
  assert.equal(h.cleared(), 1);
  assert.equal(h.context.window.location.href, '/secure/details/uploadStatus.jsp?id=88');
  assert.equal(h.errors.length, 0);
});

test('transient 504 on finalize recovers via session polling without aborting finalization', () => {
  const h = setupFinalize();
  h.context.finalizeResumableUpload();
  h.posts[0].reject({ status: 504 }, 'error');

  assert.equal(h.errors.length, 0);
  assert.equal(h.fireTimer(), 1000);
  assert.equal(h.reads.length, 1);

  h.reads[0].resolve({ success: true, id: 9, status: 'COMPLETE', jobId: 99 });
  assert.equal(h.cleared(), 1);
  assert.equal(h.context.window.location.href, '/secure/details/uploadStatus.jsp?id=99');
});

test('terminal FAILED state during finalization polling surfaces server errorMessage', () => {
  const h = setupFinalize();
  h.context.finalizeResumableUpload();
  h.posts[0].resolve({ success: true, id: 9, sessionId: 9, status: 'FINALIZING' });
  h.fireTimer();
  h.reads[0].resolve({ success: true, id: 9, status: 'FAILED', errorMessage: 'Missing chunk 1' });

  assert.equal(h.cleared(), 1);
  assert.deepEqual(h.errors, ['Missing chunk 1']);
  assert.equal(h.context.resumableUploadState.isFinalizing, false);
});
