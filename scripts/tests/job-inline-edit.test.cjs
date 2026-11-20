const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');

function setup(field) {
  const requests = [];
  const errors = [];
  const elements = new Map();
  const $ = selector => {
    if (!elements.has(selector)) {
      const events = {};
      const element = {
        events, value: 'Saved', visible: true,
        ready() {},
        hide() { this.visible = false; },
        show() { this.visible = true; },
        select() {},
        contains(node) { return node === $('#input') || node === $('#button'); },
        val(value) { if (value === undefined) return this.value; this.value = value; },
        text(value) { return this.val(value); },
        prop(key, value) { this[key] = value; return this; },
        on(event, callback) { events[event] = callback; return this; },
        click(callback) { return this.on('click', callback); },
        mousedown(callback) { return this.on('mousedown', callback); },
        blur(callback) { return this.on('blur', callback); },
        fire(name, event = {}) {
          events[name]?.call(this, { preventDefault() {}, ...event });
        },
      };
      elements.set(selector, element);
    }
    return elements.get(selector);
  };
  $.post = (url, data, success) => {
    const request = {
      url, data,
      done(callback) { success = callback; return this; },
      fail(callback) { this.failure = callback; return this; },
      always(callback) { this.cleanup = callback; return this; },
      resolve(result) { success(result); this.cleanup?.(); },
      reject() { this.failure?.({}, 'error'); this.cleanup?.(); },
    };
    requests.push(request);
    return request;
  };
  const context = vm.createContext({ document: {}, $, starexecRoot: '/', log() {},
    getPrimNameRegex: () => '.+', getPrimDescRegex: () => '.+',
    parseReturnCode: result => result.success, showMessage: (...args) => errors.push(args),
  });
  vm.runInContext(source, context);
  context.jobId = 12;
  context.setupJobNameAndDescriptionEditing('#text', '#input', '#button', '#wrapper', field);
  $('#text').fire('click');
  $('#input').val('Draft');
  return { $, requests, errors };
}

test('an HTTP error keeps the editor open with the draft and enables another save', () => {
  const { $, requests, errors } = setup('description');
  $('#button').fire($('#button').events.click ? 'click' : 'mousedown');
  requests[0].reject();
  assert.equal($('#wrapper').visible, true);
  assert.equal($('#input').val(), 'Draft');
  assert.equal($('#text').text(), 'Saved');
  assert.equal($('#button').disabled, false);
  assert.equal(errors[0][0], 'error');
  $('#button').fire('click');
  assert.equal(requests.length, 2);
  assert.equal(requests[1].data.description, 'Draft');
});

test('saving a description sends it in the form body and renders the same text', () => {
  const { $, requests } = setup('description');
  const description = 'A & B + C / D';
  $('#input').val(description);
  $('#button').fire('click');

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/services/job/edit/description/12');
  assert.deepEqual(Object.entries(requests[0].data), [['description', description]]);
  assert.equal($('#text').text(), 'Saved');

  requests[0].resolve({ success: true });
  assert.equal($('#text').text(), description);
  assert.equal($('#wrapper').visible, false);
});

test('moving focus to Save works with the keyboard and requests cannot overlap', () => {
  const { $, requests } = setup('name');
  $('#wrapper').fire('focusout', { relatedTarget: $('#button') });
  assert.equal($('#wrapper').visible, true);
  $('#button').fire('click');
  $('#button').fire('click');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/services/job/edit/name/12/Draft');
  requests[0].resolve({ success: false });
  assert.equal($('#wrapper').visible, true);
  assert.equal($('#input').val(), 'Draft');
  $('#button').fire('click');
  requests[1].resolve({ success: true });
  assert.equal($('#wrapper').visible, false);
  assert.equal($('#text').text(), 'Draft');
  assert.equal($('#mainTemplateHeader').text(), 'Draft');
});
