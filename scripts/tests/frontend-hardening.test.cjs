const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function readJs(relPath) {
  return fs.readFileSync(path.join(__dirname, '../../starexec-app/src/main/webapp/js', relPath), 'utf8');
}

function createHarness(relPath, extraGlobals = {}) {
  const posts = [];
  const gets = [];
  const ajaxCalls = [];
  const messages = [];
  const dialogsCreated = [];
  let dialogsDestroyed = 0;
  let reloads = 0;
  const navAssignments = [];
  const navReplaces = [];
  const elements = new Map();
  const timers = [];
  let readyFn = null;

  const makeEl = (selector) => {
    if (!elements.has(selector)) {
      const listeners = {};
      const attrs = {};
      const dataMap = {};
      let valStore = '';
      let textStore = '';
      const self = {
        selector,
        length: 1,
        listeners,
        attrs,
        dataMap,
        dialogOptions: null,
        dialogClosed: false,
        dialogInitialized: false,
        checked: false,
        removed: false,
        drawn: [],
        reloadedCount: 0,
        ready(fn) { readyFn = fn; return self; },
        on(evt, selOrFn, maybeFn) {
          const fn = typeof selOrFn === 'function' ? selOrFn : maybeFn;
          const key = typeof selOrFn === 'string' ? `${evt}:${selOrFn}` : evt;
          listeners[key] = fn;
          return self;
        },
        off() { return self; },
        click(fn) {
          if (typeof fn === 'function') {
            listeners.click = fn;
          } else if (listeners.click) {
            listeners.click.call(self, { preventDefault() {} });
          }
          return self;
        },
        val(v) {
          if (arguments.length === 0) return valStore;
          valStore = v;
          return self;
        },
        text(v) {
          if (arguments.length === 0) return textStore;
          textStore = v;
          return self;
        },
        html() { return self; },
        attr(k, v) {
          if (arguments.length === 1) return attrs[k];
          attrs[k] = v;
          return self;
        },
        data(k, v) {
          if (arguments.length === 1) return dataMap[k];
          dataMap[k] = v;
          return self;
        },
        prop(k, v) {
          if (arguments.length === 1) return self[k];
          self[k] = v;
          return self;
        },
        is(sel) {
          if (sel === ':checked') return self.checked;
          if (sel === ':visible') return true;
          return false;
        },
        hasClass(cls) {
          if (cls === 'ui-dialog-content') return self.dialogInitialized;
          return false;
        },
        addClass() { return self; },
        removeClass() { return self; },
        show() { return self; },
        hide() { return self; },
        fadeIn() { return self; },
        fadeOut() { return self; },
        slideToggle() { return self; },
        css() { return self; },
        append() { return self; },
        prependTo() { return self; },
        detach() { return self; },
        remove() { self.removed = true; return self; },
        find() { return makeEl(`${selector}:find`); },
        children() { return makeEl(`${selector}:children`); },
        parent() { return makeEl(`${selector}:parent`); },
        parents() { return makeEl(`${selector}:parents`); },
        button() { return self; },
        expandable() { return self; },
        validate() { return self; },
        valid() { return true; },
        submit() { return self; },
        dataTable() {
          return {
            fnClearTable() {},
            fnAddData() {},
            fnDraw(arg) { self.drawn.push(arg); },
            fnFilterOnDoneTyping() {},
            api() { return { ajax: { reload() { self.reloadedCount += 1; } } }; },
          };
        },
        DataTable() {
          return {
            ajax: {
              reload() { self.reloadedCount += 1; },
            },
          };
        },
        dialog(arg) {
          if (arg === 'close') {
            if (!self.dialogInitialized) {
              throw new Error("cannot call methods on dialog prior to initialization; attempted to call method 'close'");
            }
            self.dialogClosed = true;
            return self;
          }
          if (arg === 'destroy') {
            self.dialogInitialized = false;
            return self;
          }
          self.dialogInitialized = true;
          self.dialogClosed = false;
          self.dialogOptions = arg;
          return self;
        },
      };
      elements.set(selector, self);
    }
    return elements.get(selector);
  };

  const $ = (sel) => {
    if (typeof sel === 'function') {
      readyFn = sel;
      return undefined;
    }
    if (sel && typeof sel === 'object' && sel.selector) {
      return sel;
    }
    return makeEl(String(sel));
  };
  $.extend = Object.assign;
  $.inArray = (val, arr) => arr.indexOf(val);
  $.each = (col, fn) => {
    if (Array.isArray(col)) {
      col.forEach((v, i) => fn.call(v, i, v));
    } else if (col && typeof col === 'object') {
      Object.keys(col).forEach(k => fn.call(col[k], k, col[k]));
    }
  };
  $.cookie = () => 'false';
  $.post = (url, data, callback, dataType) => {
    const req = {
      url,
      data: typeof data === 'function' ? undefined : data,
      callback: typeof data === 'function' ? data : callback,
      dataType,
      doneCb: null,
      failCb: null,
      done(fn) { this.doneCb = fn; return this; },
      fail(fn) { this.failCb = fn; return this; },
    };
    posts.push(req);
    return req;
  };
  $.get = (url, data, callback, dataType) => {
    const req = {
      url,
      data: typeof data === 'function' ? undefined : data,
      callback: typeof data === 'function' ? data : callback,
      dataType,
      doneCb: null,
      failCb: null,
      done(fn) { this.doneCb = fn; return this; },
      fail(fn) { this.failCb = fn; return this; },
    };
    gets.push(req);
    return req;
  };
  $.ajax = (options) => {
    const req = {
      options,
      doneCb: null,
      failCb: null,
      done(fn) { this.doneCb = fn; return this; },
      fail(fn) { this.failCb = fn; return this; },
    };
    ajaxCalls.push(req);
    return req;
  };

  let parseImpl = code => !code || code.success !== false;
  const locationObj = {
    reload() { reloads += 1; },
    replace(url) { navReplaces.push(url); },
    assign(url) { navAssignments.push(url); },
  };
  const starObj = {
    DataTableConfig: function (opts) { return opts || {}; },
    mountTree() {},
    openDialog(cfg) { makeEl('#recycle-bin-dialog').dialog(cfg); },
    reloadOnSucess() { throw new Error('reloadOnSucess should not be bound to trigger button'); },
  };
  const windowObj = {
    location: locationObj,
    stop() {},
    innerWidth: 1024,
    innerHeight: 768,
    history: { replaceState() {} },
    star: starObj,
  };

  const context = vm.createContext({
    $,
    jQuery: $,
    document: { location: locationObj, documentElement: { clientWidth: 1024, clientHeight: 768 } },
    window: windowObj,
    location: locationObj,
    starexecRoot: '/starexec/',
    star: starObj,
    log() {},
    addValidators() {},
    addFilterOnDoneTyping() {},
    makeSpaceTree() {},
    getParameterByName: () => '99',
    getPrimNameRegex: () => '.*',
    getPrimDescRegex: () => '.*',
    stringExists: s => typeof s === 'string' && s.length > 0,
    createDialog: msg => dialogsCreated.push(msg),
    destroyDialog: () => { dialogsDestroyed += 1; },
    destroyOnReturn() {},
    registerAnonymousLinkButtonEventHandler() {},
    killAjaxRequests() {},
    showMessage: (...args) => messages.push(args),
    parseReturnCode: code => parseImpl(code),
    setTimeout(fn, ms) {
      timers.push({ fn, ms });
      return timers.length;
    },
    clearTimeout() {},
    ...extraGlobals,
  });

  vm.runInContext(readJs(relPath), context);
  return {
    context,
    posts,
    gets,
    ajaxCalls,
    messages,
    dialogsCreated,
    getDialogsDestroyed: () => dialogsDestroyed,
    getReloads: () => reloads,
    navAssignments,
    navReplaces,
    el: makeEl,
    timers,
    initReady: () => readyFn && readyFn($),
    setParseReturnCode: fn => { parseImpl = fn; },
    getWindowLocation: () => windowObj.location,
  };
}

test('edit/community.js: removeUser only reloads on parseReturnCode truthy; #addWebsite and .deleteBenchmark handle .fail()', () => {
  const h = createHarness('edit/community.js', {
    populateDefaultsWithId() {},
  });
  h.el('#comId').val('7');
  h.el('#settingId').attr('value', '12');
  h.initReady();

  h.context.removeUser(3, 7);
  assert.equal(h.posts.length, 1);
  assert.equal(h.timers.length, 0);
  h.posts[0].callback({ success: false, message: 'Cannot remove leader' });
  assert.equal(h.timers.length, 0);

  h.context.removeUser(4, 7);
  h.posts[1].failCb();
  assert.equal(h.timers.length, 0);
  assert.equal(h.messages[0][0], 'error');

  h.context.removeUser(5, 7);
  h.posts[2].callback({ success: true });
  assert.equal(h.timers.length, 1);
  h.timers[0].fn();
  assert.equal(h.getReloads(), 1);

  h.el('#website_name').val('Docs');
  h.el('#website_url').val('http://example.org/docs');
  h.el('#addWebsite').click();
  assert.equal(h.posts[3].url, '/starexec/services/website/add/space/7');
  h.posts[3].failCb();
  assert.equal(h.messages[1][1], 'Internal error adding website');

  h.el('.deleteBenchmark').attr('value', '55').click();
  assert.equal(h.posts[4].url, '/starexec/services/delete/defaultBenchmark/12/55');
  h.posts[4].failCb();
  assert.equal(h.messages[2][1], 'Internal error deleting benchmark');
});

test('explore/reports.js: makeSubscribeOrUnsubscribePost reloads only on truthy parseReturnCode and reports .fail()', () => {
  const h = createHarness('explore/reports.js');
  h.el('#userId').attr('value', '21');
  h.initReady();

  h.el('#subscribe').click();
  assert.equal(h.posts.length, 1);
  assert.equal(h.posts[0].url, '/starexec/services/subscribe/user/21');
  h.posts[0].callback({ success: false, message: 'Already subscribed' });
  assert.equal(h.timers.length, 0);

  h.el('#unsubscribe').click();
  assert.equal(h.posts[1].url, '/starexec/services/unsubscribe/user/21');
  h.posts[1].failCb();
  assert.equal(h.timers.length, 0);
  assert.equal(h.messages[0][0], 'error');

  h.el('#subscribe').click();
  h.posts[2].callback({ success: true });
  assert.equal(h.timers.length, 1);
  h.timers[0].fn();
  assert.equal(h.getReloads(), 1);
});

test('details/solver.js: #rebuildSolver safely parses JSON, always destroys dialog, and checks HTTP status', () => {
  const h = createHarness('details/solver.js');
  h.initReady();

  const triggerRebuild = () => {
    h.el('#rebuildSolver').click();
    const dialogs = [...new Set([h.el('<div>')])];
    const dlg = dialogs[0];
    dlg.dialogOptions.buttons['Rebuild solver'].call(dlg);
  };

  triggerRebuild();
  assert.equal(h.dialogsCreated.length, 1);
  h.ajaxCalls[0].options.complete({ status: 200, responseText: '<html>bad gateway</html>' });
  assert.equal(h.getDialogsDestroyed(), 1);
  assert.deepEqual(h.navReplaces, []);
  assert.equal(h.messages[0][0], 'error');

  triggerRebuild();
  h.ajaxCalls[1].options.complete({ status: 500, responseText: '' });
  assert.equal(h.getDialogsDestroyed(), 2);
  assert.deepEqual(h.navReplaces, []);
  assert.equal(h.messages[1][0], 'error');

  triggerRebuild();
  h.ajaxCalls[2].options.complete({
    status: 400,
    responseText: JSON.stringify({ success: false, message: 'Solver cannot be built by StarExec' }),
  });
  assert.equal(h.getDialogsDestroyed(), 3);
  assert.deepEqual(h.navReplaces, []);

  triggerRebuild();
  h.ajaxCalls[3].options.complete({
    status: 500,
    responseText: '{}',
  });
  assert.equal(h.getDialogsDestroyed(), 4);
  assert.deepEqual(h.navReplaces, []);
  assert.equal(h.messages[2][0], 'error');

  triggerRebuild();
  h.ajaxCalls[4].options.complete({
    status: 200,
    responseText: JSON.stringify({ success: true, message: '' }),
  });
  assert.equal(h.getDialogsDestroyed(), 5);
  assert.deepEqual(h.navReplaces, ['?id=99&buildmsg=Rebuilding%20solver']);
});

test('details/job.js: setupResumeJobButton destroys dialog on false parseReturnCode and on .fail()', () => {
  const h = createHarness('details/job.js', {
    initializeGlobalPageVariables() {},
    initUI() {},
    initDataTables() {},
    initSpaceExplorer() {},
    initOfflinePairNavigation() {},
    setupButtons() {},
    attachSortListeners() {},
    refreshPanels() {},
  });
  h.context.setupResumeJobButton();

  h.el('#resumeJob').click();
  assert.equal(h.dialogsCreated.length, 1);
  h.posts[0].callback({ success: false, message: 'Job is already running' });
  assert.equal(h.getDialogsDestroyed(), 1);
  assert.equal(h.getReloads(), 0);

  h.el('#resumeJob').click();
  h.posts[1].failCb();
  assert.equal(h.getDialogsDestroyed(), 2);
  assert.equal(h.getReloads(), 0);
  assert.equal(h.messages[0][1], 'Internal error resuming job');

  h.el('#resumeJob').click();
  h.posts[2].callback({ success: true });
  assert.equal(h.getReloads(), 1);
});

test('shared/sharedFunctions.js: handleRequest checks parseReturnCode before reloading #commRequests and handles .fail()', () => {
  const h = createHarness('shared/sharedFunctions.js');
  h.el('#emailCode').attr('value', 'code');
  h.el('#leaderResponse').attr('value', 'resp');
  h.el('#approveRequest').attr('value', 'approve');
  h.el('#declineRequest').attr('value', 'decline');
  h.el('#communityPage').attr('value', 'fromPage');

  h.context.handleRequest('abc', true);
  assert.equal(h.gets.length, 1);
  h.gets[0].callback({ success: false, message: 'Already handled' });
  assert.equal(h.el('#commRequests').reloadedCount, 0);

  h.context.handleRequest('abc', false);
  h.gets[1].failCb();
  assert.equal(h.el('#commRequests').reloadedCount, 0);
  assert.equal(h.messages[0][0], 'error');

  h.context.handleRequest('abc', true);
  h.gets[2].callback({ success: true });
  assert.equal(h.el('#commRequests').reloadedCount, 1);
});

test('edit/solver.js: #delete, #update, and #addWebsite handle .fail() and false parseReturnCode', () => {
  const h = createHarness('edit/solver.js');
  h.initReady();

  h.el('#delete').click();
  const delDlg = h.el('#dialog-confirm-delete');
  delDlg.dialogOptions.buttons.OK.call(delDlg);
  assert.equal(h.posts[0].url, '/starexec/services/recycle/solver');
  h.posts[0].failCb();
  assert.equal(h.messages[0][1], 'Internal error deleting solver');
  assert.equal(typeof h.getWindowLocation(), 'object');

  h.el('#update').click();
  assert.equal(h.posts[1].url, '/starexec/services/edit/solver/99');
  h.posts[1].failCb();
  assert.equal(h.messages[1][1], 'Internal error updating solver');

  h.el('#website_name').val('Solver Site');
  h.el('#website_url').val('https://example.org/solver');
  h.el('#addWebsite').click();
  assert.equal(h.posts[2].url, '/starexec/services/website/add/solver/99');
  h.posts[2].failCb();
  assert.equal(h.messages[2][1], 'Internal error adding website');
  assert.equal(h.getReloads(), 0);
});

test('edit/spacePermissions.js: guards uninitialized spaceId, changePermissions .fail(), and #makePublic modal close', () => {
  const h = createHarness('edit/spacePermissions.js', {
    getPermissionDetails() {},
  });
  assert.equal(h.context.spaceId, null);

  h.context.changePermissions(true, false);
  assert.equal(h.posts.length, 0);
  assert.equal(h.messages[0][0], 'error');

  h.context.spaceId = '42';
  h.context.lastSelectedUserId = '88';
  h.el('#dialog-confirm-update').dialog({ buttons: {} });
  h.context.changePermissions(false, false);
  assert.equal(h.el('#dialog-confirm-update').dialogClosed, true);
  assert.equal(h.posts[0].url, '/starexec/services/space/42/edit/perm/88');
  h.posts[0].failCb();
  assert.equal(h.messages[1][1], 'Internal error updating permissions');

  h.context.spaceId = null;
  h.context.setUpButtons();
  h.el('#makePublic').click();
  assert.equal(h.posts.length, 1);

  h.context.spaceId = '42';
  h.context.spaceName = 'LogicSpace';
  h.el('#makePublic').click();
  const pubDialog = h.el('#dialog-confirm-change');
  assert.equal(pubDialog.dialogInitialized, true);
  pubDialog.dialogOptions.buttons.space.call(pubDialog);
  assert.equal(pubDialog.dialogClosed, true);
  assert.equal(h.posts[1].url, '/starexec/services/space/changePublic/42/false/true');
  h.posts[1].callback({ success: false, message: 'Forbidden' });
  assert.equal(h.getReloads(), 0);

  h.el('#makePublic').click();
  pubDialog.dialogOptions.buttons['space and all subspaces'].call(pubDialog);
  assert.equal(pubDialog.dialogClosed, true);
  h.posts[2].failCb();
  assert.equal(h.messages[3][1], 'Internal error changing space visibility');
  assert.equal(h.getReloads(), 0);
});

test('details/user.js: blocking modals always destroyDialog() on .fail() and false parseReturnCode', () => {
  const h = createHarness('details/user.js', {
    handleSelectChange() {},
  });
  h.context.getSelectedRows = () => ['1', '2'];
  h.context.userId = '7';
  h.context.spaceId = '15';
  h.context.solverTable = h.el('#solvers').dataTable();
  h.context.benchTable = h.el('#benchmarks').dataTable();
  h.context.jTable = h.el('#jobs').dataTable();

  h.context.recycleSelected('solver');
  h.el('#dialog-confirm-recycle').dialogOptions.buttons['Move to Trash'].call(h.el('#dialog-confirm-recycle'));
  h.posts[0].failCb();
  assert.equal(h.getDialogsDestroyed(), 1);

  h.context.linkAllOrphaned();
  h.el('#dialog-confirm-copy').dialogOptions.buttons['link all'].call(h.el('#dialog-confirm-copy'));
  h.posts[1].failCb();
  assert.equal(h.getDialogsDestroyed(), 2);

  h.context.recycleOrphaned('benchmark');
  h.el('#dialog-confirm-recycle').dialogOptions.buttons['Move to Trash'].call(h.el('#dialog-confirm-recycle'));
  h.posts[2].failCb();
  assert.equal(h.getDialogsDestroyed(), 3);

  h.context.deleteSelectedJobs();
  h.el('#dialog-confirm-delete').dialogOptions.buttons['delete permanently'].call(h.el('#dialog-confirm-delete'));
  h.posts[3].failCb();
  assert.equal(h.getDialogsDestroyed(), 4);

  h.context.deleteOrphanedJobs();
  h.el('#dialog-confirm-delete').dialogOptions.buttons['delete permanently'].call(h.el('#dialog-confirm-delete'));
  h.posts[4].callback({ success: false, message: 'Error' });
  assert.equal(h.getDialogsDestroyed(), 5);

  h.context.deleteOrphanedJobs();
  h.el('#dialog-confirm-delete').dialogOptions.buttons['delete permanently'].call(h.el('#dialog-confirm-delete'));
  h.posts[5].failCb();
  assert.equal(h.getDialogsDestroyed(), 6);
  assert.equal(h.messages.length, 5);
});

test('details/recycleBin.js: all trash actions destroyDialog() and show error on .fail() and false parseReturnCode', () => {
  const h = createHarness('details/recycleBin.js', {
    handleClassChange() {},
  });
  h.context.getSelectedRows = () => ['10'];
  h.context.solverTable = h.el('#rsolvers').dataTable();
  h.context.benchTable = h.el('#rbenchmarks').dataTable();

  h.context.deleteAll('solver');
  h.el('#recycle-bin-dialog').dialogOptions.buttons['delete permanently'].call(h.el('#recycle-bin-dialog'));
  h.posts[0].failCb();
  assert.equal(h.getDialogsDestroyed(), 1);

  h.context.restoreAll('benchmark');
  h.el('#recycle-bin-dialog').dialogOptions.buttons.restore.call(h.el('#recycle-bin-dialog'));
  h.posts[1].failCb();
  assert.equal(h.getDialogsDestroyed(), 2);

  h.context.deleteSelected('solver');
  h.el('#recycle-bin-dialog').dialogOptions.buttons['delete permanently'].call(h.el('#recycle-bin-dialog'));
  h.posts[2].failCb();
  assert.equal(h.getDialogsDestroyed(), 3);

  h.context.restoreSelected('benchmark');
  h.el('#recycle-bin-dialog').dialogOptions.buttons.restore.call(h.el('#recycle-bin-dialog'));
  h.posts[3].failCb();
  assert.equal(h.getDialogsDestroyed(), 4);

  h.context.restoreSelected('benchmark');
  h.el('#recycle-bin-dialog').dialogOptions.buttons.restore.call(h.el('#recycle-bin-dialog'));
  h.posts[4].callback({ success: false, message: 'Cannot restore' });
  assert.equal(h.getDialogsDestroyed(), 5);
  assert.equal(h.messages.length, 4);
});

test('shared/copyToStardev.js: avoids hardcoded procId 1 for copy-bench-with-proc-to-stardev while preserving benchProcessorId', () => {
  const h = createHarness('shared/copyToStardev.js');
  h.el('.thisPrimitivesType').attr('value', 'BENCHMARK');
  h.el('.processorPrimitiveType').attr('value', 'PROCESSOR');
  h.el('.benchmarkPrimitiveType').attr('value', 'BENCHMARK');
  h.el('.solverPrimitiveType').attr('value', 'SOLVER');
  h.el('.thisPrimitivesId').attr('value', '77');
  h.el('.benchProcessorId').attr('value', '33');
  h.el('.instanceName').val('dev');
  h.el('.stardevUsername').val('alice');
  h.el('.stardevPassword').val('secret');
  h.el('.stardevSpaceId').val('12');
  h.initReady();

  h.el('.uploadProcessorWithBenchmarkCheckbox').checked = true;
  h.el('.copyToStarDev').click();
  const dlg = h.el('.dialog-copy-to-stardev');
  dlg.dialogOptions.buttons.submit.call(dlg);
  assert.equal(h.posts[0].url, '/starexec/services/copy-bench-with-proc-to-stardev/dev/77');
  assert.equal('procId' in h.posts[0].data, false);

  h.el('.uploadProcessorWithBenchmarkCheckbox').checked = false;
  h.el('.copyToStarDev').click();
  dlg.dialogOptions.buttons.submit.call(dlg);
  assert.equal(h.posts[1].url, '/starexec/services/copy-to-stardev/dev/BENCHMARK/77');
  assert.equal(h.posts[1].data.procId, '33');
});
