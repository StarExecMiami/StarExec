const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../starexec-app/src/main/webapp/js/details/job.js'), 'utf8');

function createHarness() {
  const elements = {};
  const postCalls = [];
  const navigations = [];
  const chartConstructions = [];
  const destroyedCharts = [];

  class FakeChart {
    constructor(ctx, config) {
      this.ctx = ctx;
      this.config = config;
      this.data = config.data;
      this.canvas = { style: {} };
      chartConstructions.push({ id: ctx.canvasId, config });
    }
    destroy() {
      destroyedCharts.push(this.ctx.canvasId);
    }
  }

  function stateObj(selector) {
    if (!elements[selector]) {
      elements[selector] = {
        selector,
        classes: new Set(selector.indexOf('dialog') === -1 ? ['is-loading'] : ['is-empty']),
        attrs: {},
        visible: selector === '#graphField',
        handlers: {},
        dialogOpts: null,
        selectedValues: selector === '#spaceOverviewSelections' ? ['10', '20'] : [],
        stateImg: selector.indexOf('State') !== -1,
      };
    }
    return elements[selector];
  }

  function $(selector) {
    const key = typeof selector === 'string' ? selector : '__doc__';
    const st = stateObj(key);
    const jq = {
      length: 1,
      0: { id: key.replace(/^#/, ''), getContext: () => ({ canvasId: key.replace(/^#/, '') }) },
      ready() { return jq; },
      show() { st.visible = true; return jq; },
      hide() { st.visible = false; return jq; },
      is(q) { return q === ':visible' ? st.visible : false; },
      empty() { return jq; },
      append() { return jq; },
      children() { return jq; },
      each(fn) {
        if (selector === '#spaceOverviewSelections option:selected') {
          ['10', '20'].forEach((v, idx) => fn.call({ value: v, index: idx }));
        }
        return jq;
      },
      prop() { return selector === '#logScale'; },
      val() { return st.selectedValues; },
      attr(k, v) {
        if (v !== undefined) {
          st.attrs[k] = v;
          return jq;
        }
        if (k === 'value') {
          if (selector === '#solverChoice1 option:selected') return '10';
          if (selector === '#solverChoice2 option:selected') return '20';
          return '';
        }
        return st.attrs[k] || '';
      },
      text() { return jq; },
      addClass(cls) {
        cls.split(/\s+/).filter(Boolean).forEach(c => st.classes.add(c));
        return jq;
      },
      removeClass(cls) {
        cls.split(/\s+/).filter(Boolean).forEach(c => st.classes.delete(c));
        return jq;
      },
      hasClass(cls) {
        return st.classes.has(cls);
      },
      find(sub) {
        if (sub === '.job-chart__state') {
          return $(selector + '__state');
        }
        if (sub === 'canvas') {
          return $(selector + 'Canvas');
        }
        return jq;
      },
      click(fn) {
        if (fn) {
          st.handlers.click = fn;
          return jq;
        }
        if (st.handlers.click) st.handlers.click();
        return jq;
      },
      on(ev, fn) { st.handlers[ev] = fn; return jq; },
      off() { return jq; },
      expandable() { return jq; },
      toggleClass() { return jq; },
      dialog(opts) {
        st.dialogOpts = opts;
        st.visible = true;
        if (opts && typeof opts.open === 'function') {
          opts.open();
        }
        return jq;
      },
    };
    return jq;
  }

  $.cookie = () => 'false';
  $.post = (url, data, cb) => {
    const entry = { url, data, cb };
    postCalls.push(entry);
    return { fail() {} };
  };

  const context = vm.createContext({
    Chart: FakeChart,
    document: {
      documentElement: { clientWidth: 1200, clientHeight: 900 },
      createDocumentFragment: () => ({ appendChild() {}, cloneNode() { return {}; } }),
      createElement: () => ({}),
      getElementById: () => ({ appendChild() {} }),
    },
    window: {
      innerWidth: 1200,
      innerHeight: 900,
      location: { assign: url => navigations.push(url) },
    },
    setTimeout,
    isLocalJobPage: false,
    curSpaceId: 42,
    jobId: 9,
    starexecRoot: '/starexec/',
    DETAILS_JOB: { isAnonymousPage: false, primitivesToAnonymize: 'none', starexecUrl: '/starexec/' },
    star: {},
    log() {},
    setTimeButtonText() {},
    attachSortButtonFunctions() {},
    parseReturnCode(code) { return !(code && code.success === false); },
    getSelectedStage() { return '1'; },
    showMessage() {},
    $: $,
  });

  vm.runInContext(source, context);
  context.DETAILS_JOB.isAnonymousPage = false;
  context.curSpaceId = 42;
  context.jobId = 9;
  context.initUI();

  return {
    context,
    elements,
    postCalls,
    navigations,
    chartConstructions,
    destroyedCharts,
    $,
  };
}

test('solver comparison sends one JSON request, caches payload for dialog, and links non-anonymized benchmarks', () => {
  const h = createHarness();
  h.context.updateSolverComparison(true);
  assert.equal(h.postCalls.length, 1);
  assert.match(h.postCalls[0].url, /\/services\/jobs\/42\/graphs\/solverComparison\/10\/20\/1$/);

  const payload = {
    chartType: 'solverComparison',
    title: 'Solver Comparison Plot',
    xLabel: 'CVC5/default time(s)',
    yLabel: 'Z3/fast time(s)',
    maxAxis: 5.5,
    benchmarksAnonymized: false,
    points: [{ x: 1.2, y: 2.3, name: 'sat-1.smt2', benchmarkId: 701 }],
  };
  h.postCalls[0].cb(JSON.stringify(payload));

  assert.equal(h.postCalls.length, 1, 'must not issue a second 800/black request');
  assert.equal(h.elements['#solverComparison300'].classes.has('is-ready'), true);
  assert.equal(h.elements['#solverComparison300'].classes.has('is-loading'), false);
  assert.equal(h.chartConstructions.length, 1);

  const cfg = h.chartConstructions[0].config;
  assert.equal(cfg.type, 'scatter');
  assert.equal(cfg.options.scales.x.max, 5.5);
  assert.equal(cfg.options.scales.y.max, 5.5);
  assert.equal(cfg.options.plugins.tooltip.callbacks.title([{ raw: payload.points[0] }]), 'sat-1.smt2');

  // Opening the dialog reuses latestChartData without issuing another POST.
  h.$('#solverComparison300').click();
  assert.equal(h.postCalls.length, 1);
  assert.equal(h.chartConstructions.length, 2);
  assert.equal(h.chartConstructions[1].id, 'solverComparison800Canvas');

  // Clicking a data point navigates to benchmark.jsp.
  const chartStub = { data: cfg.data, canvas: { style: {} } };
  cfg.options.onClick({ native: { stopPropagation() {} } }, [{ datasetIndex: 0, index: 0 }], chartStub);
  assert.deepEqual(h.navigations, ['/starexec/secure/details/benchmark.jsp?id=701']);
});

test('anonymized solver comparison keeps tooltip name and blocks benchmark navigation', () => {
  const h = createHarness();
  const payload = {
    chartType: 'solverComparison',
    title: 'Solver Comparison Plot',
    xLabel: 'Solver 1 time(s)',
    yLabel: 'Solver 2 time(s)',
    maxAxis: 2.0,
    benchmarksAnonymized: true,
    points: [{ x: 0.5, y: 1.0, name: 'Benchmark 1' }],
  };
  h.context.renderSolverComparisonChart('#solverComparison300', payload, false);
  const cfg = h.chartConstructions[0].config;
  assert.equal(cfg.options.plugins.tooltip.callbacks.title([{ raw: payload.points[0] }]), 'Benchmark 1');
  const lines = cfg.options.plugins.tooltip.callbacks.label({ parsed: { x: 0.5, y: 1.0 }, raw: payload.points[0] });
  assert.equal(lines.length, 1);
  cfg.options.onClick({}, [{ datasetIndex: 0, index: 0 }], { data: cfg.data, canvas: { style: {} } });
  assert.deepEqual(h.navigations, []);
});

test('refused or failed graph responses transition containers to empty state with noDisplayGraph.svg', () => {
  const h = createHarness();
  h.context.updateSpaceOverviewGraph(true);
  assert.equal(h.elements['#spaceOverview'].classes.has('is-loading'), true);
  h.postCalls[0].cb(JSON.stringify({ success: false, message: 'Nothing to show' }));
  assert.equal(h.elements['#spaceOverview'].classes.has('is-empty'), true);
  assert.equal(h.elements['#spaceOverview__state'].attrs.src, '/starexec/images/noDisplayGraph.svg');
});
