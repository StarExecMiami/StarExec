const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const web = path.join(__dirname, '../../starexec-app/src/main/webapp');

function pageEvent(window, type, persisted = true) {
  const event = new Event(type);
  event.persisted = persisted;
  window.dispatchEvent(event);
}

test('polling stops on pagehide and starts once on a persisted pageshow', () => {
  const window = new EventTarget();
  const active = new Map();
  let next = 0;
  window.setInterval = (callback, delay) => {
    active.set(++next, [callback, delay]);
    return next;
  };
  window.clearInterval = id => active.delete(id);
  const star = {};
  const master = fs.readFileSync(path.join(web, 'js/master.js'), 'utf8');
  const start = master.indexOf('star.startPagePolling =');
  vm.runInNewContext(master.slice(start, master.indexOf('\n/**', start)), { star, window });
  const refresh = () => {};
  star.startPagePolling(refresh, 5000);
  assert.deepEqual([...active.values()], [[refresh, 5000]]);

  pageEvent(window, 'pagehide');
  assert.equal(active.size, 0);
  pageEvent(window, 'pageshow', false);
  assert.equal(active.size, 0);
  pageEvent(window, 'pageshow');
  pageEvent(window, 'pageshow');
  assert.deepEqual([...active.values()], [[refresh, 5000]]);
});

test('live log resumes at the last rendered byte and stays closed after completion', () => {
  const window = new EventTarget();
  const sources = [];
  class EventSource extends EventTarget {
    constructor(url) {
      super();
      this.url = url;
      this.closed = false;
      sources.push(this);
    }
    close() { this.closed = true; }
  }
  window.EventSource = EventSource;
  const log = { textContent: 'initial JSP log' };
  const document = { getElementById: id => ({
    pairId: { getAttribute: () => '17' },
    jobLogContent: log,
    liveLogBadge: {},
    liveLogAutoScroll: { checked: false },
  })[id] };
  const pair = fs.readFileSync(path.join(web, 'js/details/pair.js'), 'utf8');
  const start = pair.indexOf('function initLiveJobLogStream');
  const end = pair.indexOf('function appendLogText', start);
  const context = vm.createContext({
    window, document, EventSource, starexecRoot: '/', safeJsonParse: JSON.parse,
    appendLogText: (element, text) => { element.textContent += text; },
    setLiveBadgeState: () => {},
  });
  vm.runInContext(pair.slice(start, end), context);
  context.initLiveJobLogStream();
  sources[0].dispatchEvent(new MessageEvent('chunk', {
    data: JSON.stringify({ offsetStart: 0, offsetEnd: 2, text: 'é' }),
  }));
  pageEvent(window, 'pagehide');
  assert.equal(sources[0].closed, true);

  pageEvent(window, 'pageshow');
  pageEvent(window, 'pageshow');
  assert.equal(sources.length, 2);
  assert.equal(sources[1].url, '/services/jobs/pairs/17/log/stream?offset=2');
  sources[1].dispatchEvent(new MessageEvent('chunk', {
    data: JSON.stringify({ offsetStart: 2, offsetEnd: 3, text: '!' }),
  }));
  assert.equal(log.textContent, 'é!');

  sources[1].dispatchEvent(new Event('reset'));
  pageEvent(window, 'pagehide');
  pageEvent(window, 'pageshow');
  assert.equal(sources[2].url, '/services/jobs/pairs/17/log/stream');
  sources[2].dispatchEvent(new Event('complete'));
  pageEvent(window, 'pagehide');
  pageEvent(window, 'pageshow');
  assert.equal(sources[2].closed, true);
  assert.equal(sources.length, 3);
});
