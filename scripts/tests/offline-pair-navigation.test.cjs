const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');

test('delegates offline pair navigation independently of the job-space tree', () => {
  const document = {};
  const destinations = [];
  let onClick;
  const $ = selector => {
    if (selector === document) {
      return { ready() {} };
    }
    assert.equal(selector, '#pairTblField');
    return {
      on(event, rowSelector, handler) {
        assert.equal(event, 'click');
        assert.equal(rowSelector, '.offline-pair-table tbody tr');
        onClick = handler;
      },
    };
  };
  const context = vm.createContext({
    document, $, window: { location: { assign: href => destinations.push(href) } },
  });
  vm.runInContext(source, context);
  context.initOfflinePairNavigation();

  const click = (href, clickedLink = false) => {
    const row = {
      querySelector(selector) {
        assert.equal(selector, '.offline-pair-link');
        return href ? { href } : null;
      },
    };
    onClick.call(row, {
      target: { closest: selector => selector === 'a' && clickedLink },
    });
  };

  click('file:///export/pair_10.html');
  click('file:///export/pair_20.html');
  click('file:///export/pair_20.html', true);
  click(null);
  assert.deepEqual(destinations, ['file:///export/pair_10.html', 'file:///export/pair_20.html']);
});
