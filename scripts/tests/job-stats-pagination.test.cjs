const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'starexec-app/src/main/webapp/js/details/job.js'), 'utf8');
const handler = source.slice(source.indexOf('function fnStatsPaginationHandler('), source.indexOf('\n//\r\n//  Handles querying', source.indexOf('function fnStatsPaginationHandler(')));

function setup(anonymous = false) {
    const requests = [], errors = [], notifications = [], visibility = {};
    const table = {draw: 0, rows: [], processing: false, accepted: 0};
    const $ = selector => ({show() { visibility[selector] = true; }, hide() { visibility[selector] = false; }});
    $.post = (url, data, success) => {
        const req = {url, data, success, fail(fn) { this.failure = fn; return this; }, abort() { this.failure({}, 'abort'); }};
        requests.push(req);
        return req;
    };
    const context = { $, curSpaceId: undefined, stage: 0, useWallclock: false, includeUnknown: true,
        DETAILS_JOB: {isAnonymousPage: anonymous, primitivesToAnonymize: 'true'},
        getParameterByName: () => 'synthetic-link', openAjaxRequests: [],
        parseReturnCode: result => {
            if (result.success === false && result.message) notifications.push(result.message);
            return result.success !== false;
        },
        handleAjaxError: status => { if (status !== 'abort') { errors.push(status); notifications.push('generic'); } },
        getSelectedStage() { return context.stage; }
    };
    vm.createContext(context); vm.runInContext(handler, context);
    function draw() {
        const echo = ++table.draw;
        table.processing = true;
        context.fnStatsPaginationHandler('/services/jobs/', [{name: 'sEcho', value: echo}], page => {
            // Bundled DataTables It returns on old echoes BEFORE clearing rows or processing.
            if (+page.sEcho < table.draw) return;
            table.draw = +page.sEcho;
            table.rows = page.aaData;
            table.processing = false;
            table.accepted++;
        });
        return echo;
    }
    const page = (echo, label = 'row') => ({sEcho: echo, iTotalRecords: 1, iTotalDisplayRecords: 1, aaData: [[label]]});
    return {context, requests, errors, notifications, visibility, table, draw, page};
}

test('draw model is anchored to bundled DataTables stale-echo guard', () => {
    const dt = fs.readFileSync(path.join(root, 'starexec-app/src/main/webapp/js/lib/jquery.dataTables.min.js'), 'utf8');
    const it = dt.slice(dt.indexOf('function It('), dt.indexOf('function It(') + 1000);
    assert.match(it, /if\(\+r<t\.iDraw\)return/);
    assert.ok(it.indexOf('if(+r<t.iDraw)return') < it.indexOf('pt(t)'));
});
test('uninitialized draw settles, then initialized space requests data', () => {
    const h = setup(); h.draw(); assert.equal(h.table.processing, false); assert.equal(h.requests.length, 0);
    h.context.curSpaceId = 7; const echo = h.draw(); assert.equal(h.requests.length, 1);
    h.requests[0].success(h.page(echo)); assert.equal(h.table.rows[0][0], 'row');
});
for (const anonymous of [false, true]) test(`Primary stage zero route anonymous=${anonymous}`, () => {
    const h = setup(anonymous); h.context.curSpaceId = 7; const echo = h.draw();
    assert.equal(h.requests[0].url, '/services/jobs/solvers/' + (anonymous ? 'anonymousLink/pagination/7/synthetic-link/true' : 'pagination/7') + '/false/false/0/true');
    h.requests[0].success(h.page(echo)); assert.equal(h.table.processing, false);
    assert.equal(h.visibility['#solverSummaryField'], true);
    assert.equal(h.visibility['#graphField'], true);
});
for (const malformed of [null, {}, {aaData: 'bad', iTotalRecords: 1, iTotalDisplayRecords: 1}]) test(`bad response ${JSON.stringify(malformed)}`, () => {
    const h = setup(); h.context.curSpaceId = 7; h.draw(); h.requests[0].success(malformed);
    assert.equal(h.table.processing, false); assert.equal(h.visibility['#statsErrorField'], false); assert.equal(h.errors.length, 1);
});
test('application error retains descriptive notification without generic replacement', () => {
    const h = setup(); h.context.curSpaceId = 7; h.draw();
    h.requests[0].success({success: false, message: 'Specific permission failure'});
    assert.equal(h.table.processing, false);
    assert.deepEqual(h.notifications, ['Specific permission failure']);
    assert.equal(h.visibility['#graphField'], false);
});
test('application error without message still settles without invented transport failure', () => {
    const h = setup(); h.context.curSpaceId = 7; h.draw();
    h.requests[0].success({success: false});
    assert.equal(h.table.processing, false); assert.equal(h.errors.length, 0);
});
for (const status of ['error', 'parsererror', 'abort']) test(`transport ${status} settles`, () => {
    const h = setup(); h.context.curSpaceId = 7; h.draw(); h.requests[0].failure({}, status);
    assert.equal(h.table.processing, false); assert.equal(h.errors.length, status === 'abort' ? 0 : 1);
    if (status !== 'abort') assert.equal(h.visibility['#statsErrorField'], false);
});
for (const count of [null, '', true, -1, 1.5]) test(`malformed record count ${JSON.stringify(count)}`, () => {
    const h = setup(); h.context.curSpaceId = 7; const echo = h.draw();
    h.requests[0].success({...h.page(echo), iTotalRecords: count});
    assert.equal(h.table.processing, false); assert.equal(h.visibility['#statsErrorField'], false); assert.equal(h.errors.length, 1);
});
test('older abort does not stop current spinner; same-query reverse responses cannot replace newest', () => {
    const h = setup(); h.context.curSpaceId = 7; const old = h.draw(); const current = h.draw();
    h.requests[0].abort(); assert.equal(h.table.processing, true);
    h.requests[1].success(h.page(current, 'new')); h.requests[0].success(h.page(old, 'old'));
    assert.equal(h.table.rows[0][0], 'new'); assert.equal(h.table.accepted, 1);
});
test('changed stage rejects stale response even before next draw', () => {
    const h = setup(); h.context.curSpaceId = 7; const echo = h.draw(); h.context.stage = 1;
    h.requests[0].success(h.page(echo, 'stale')); assert.equal(h.table.rows.length, 0); assert.equal(h.table.processing, false);
});
test('same-query reverse success without abort preserves newest rows and visibility', () => {
    const h = setup(); h.context.curSpaceId = 7; const old = h.draw(); const current = h.draw();
    h.requests[1].success(h.page(current, 'new')); h.requests[0].success(h.page(old, 'old'));
    assert.equal(h.table.rows[0][0], 'new'); assert.equal(h.table.accepted, 1);
    assert.equal(h.visibility['#statsErrorField'], false);
});
test('success followed by erroneous failure settles once', () => {
    const h = setup(); h.context.curSpaceId = 7; const echo = h.draw();
    h.requests[0].success(h.page(echo)); h.requests[0].failure({}, 'error');
    assert.equal(h.table.accepted, 1); assert.equal(h.errors.length, 0);
});
test('older failure does not show error on current successful draw', () => {
    const h = setup(); h.context.curSpaceId = 7; h.draw(); const current = h.draw();
    h.requests[1].success(h.page(current, 'new')); h.requests[0].failure({}, 'error');
    assert.equal(h.table.rows[0][0], 'new'); assert.equal(h.errors.length, 0);
    assert.equal(h.visibility['#statsErrorField'], false);
});
