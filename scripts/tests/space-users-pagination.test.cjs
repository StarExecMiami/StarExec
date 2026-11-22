const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/explore/spaces.js'), 'utf8');

function setup() {
  const calls = [];
  const pages = [];
  const errors = [];
  const actions = [];
  let selection;
  const document = {};
  const $ = element => {
    if (element === document) return { ready() {} };
    assert.equal(element, '#exploreList');
    return { jstree: () => ({ get_selected: () => selection ? [selection.spaceId] : [] }) };
  };
  $.ajax = options => {
    const xhr = {
      options,
      done(handler) { this.resolve = handler; return this; },
      fail(handler) { this.reject = handler; return this; },
    };
    calls.push(xhr);
    return xhr;
  };
  const context = vm.createContext({
    $, document, starexecRoot: '/', parseReturnCode: page => page.success !== false,
    showMessage: (...args) => errors.push(args),
  });
  vm.runInContext(source, context);
  context.addSpacePermissionButtons = space => actions.push(space.spaceId);
  context.initDraggable = table => actions.push(table);
  return {
    select: spaceId => { context.EXP_SP.permissionSelection = selection = {spaceId}; },
    request: (draw, order = [{column: 1, dir: 'desc'}]) => context.loadUsersPage(
      {draw, start: 10, length: 5, search: {value: 'Ada'}, order},
      page => pages.push(JSON.parse(JSON.stringify(page)))),
    calls, pages, errors, actions,
  };
}

const response = (draw, user = 'Ada') => ({
  sEcho: draw, iTotalRecords: 20, iTotalDisplayRecords: 12, aaData: [[user]],
});

test('maps paging and sorting to the authenticated users endpoint', () => {
  const {select, request, pages, actions} = setup();
  select('10');
  const xhr = request(2);
  const {type, url, data} = xhr.options;
  assert.equal(type, 'POST');
  assert.equal(url, '/services/space/10/users/pagination');
  assert.deepEqual({...data}, {
    sEcho: 2, iDisplayStart: 10, iDisplayLength: 5, sSearch: 'Ada',
    iSortCol_0: 1, sSortDir_0: 'desc',
  });
  xhr.resolve(response(2));
  assert.deepEqual(pages, [{draw: 2, recordsTotal: 20, recordsFiltered: 12, data: [['Ada']]}]);
  assert.deepEqual(actions, ['10', '#users']);
});

test('suppresses stale requests across redraws and A–B–A space changes', () => {
  const {select, request, pages, actions, errors} = setup();
  select('10');
  const first = request(1);
  select('20');
  first.resolve(response(1, 'old'));
  const second = request(2);
  select('10');
  const third = request(3);
  const fourth = request(4);
  fourth.resolve(response(4, 'current'));
  first.resolve(response(1, 'old'));
  second.reject({}, 'error');
  third.resolve(response(3, 'superseded'));
  assert.deepEqual(pages.map(page => page.data), [[['current']]]);
  assert.deepEqual(actions, ['10', '#users']);
  assert.deepEqual(errors, []);
});

test('clears the failing draw and can recover on retry', () => {
  const {select, request, pages, errors} = setup();
  select('10');
  request(1).reject({}, 'error');
  request(2).resolve(response(1));
  const retry = request(3, []);
  assert.equal(retry.options.data.iSortCol_0, 0);
  retry.resolve(response(3));
  assert.deepEqual(pages.map(page => page.data), [[], [], [['Ada']]]);
  assert.equal(errors.length, 2);
});
