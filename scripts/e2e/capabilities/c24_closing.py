"""Closing-evidence checks for the merged image.

24 (#183): rerun a two-stage pipeline pair; both stages' attributes and all six measurements equal the NEW
    attempt's own runsolver files, captured by the read-only watcher.
25 (#186, #190): the job attributes page (jobAttributes.jsp) totals and per-configuration table equal the
    stage-matched database sums, formatted exactly as the page formats them (%.4f).
23 (#187, #189): conflicts on a two-stage job where one configuration is unknown at one stage and definite
    at the other; and a second job on the same benchmark and configuration does not change this job's count.
"""

import html
import io
import re
import subprocess
import time
import zipfile
from decimal import ROUND_HALF_UP, Decimal

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import (
    job_id_by_name,
    job_stats,
    need_fixture,
    pair_statuses,
    root_job_space,
    stop_watcher,
    wait_terminal,
)
from lib import oracle
from capabilities.c07_solvers import configs_of, upload_solver
from capabilities.c13_pipeline import EXPECTED, FIELDS, WATCHER, parse_events, utc


# ------------------------------------------------------------------------------------------- 24: #183
def _attempt(ctx, pair):
    return ctx.db.int(
        f"SELECT current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id={pair};"
    )


def _stage_measurements(ctx, pair):
    cols = ", ".join(f"coalesce({c}::text,'NULL')" for c, _, _ in FIELDS)
    rows = ctx.db.rows(
        f"SELECT stage_number, {cols} FROM starexec.jobpair_stage_data WHERE jobpair_id={pair} ORDER BY 1;"
    )
    return {int(r[0]): dict(zip([c for c, _, _ in FIELDS], r[1:])) for r in rows}


def run_pipeline_rerun(cap, ctx):
    job = ctx.state.need("job_pipeline")
    if not ctx.cfg.app_exec:
        raise Blocked(
            "app_exec is required to observe the new attempt's runsolver files"
        )
    owner = ctx.owner()
    pairs = sorted(pair_statuses(ctx, job))
    pair = next(
        (
            p
            for p in pairs
            if ctx.db.rows(
                f"SELECT 1 FROM starexec.job_pairs WHERE id={p} AND status_code=7"
            )
        ),
        None,
    )
    if not pair:
        raise Blocked(f"no COMPLETE pair in pipeline job {job}")
    before = _stage_measurements(ctx, pair)
    attempt_before = _attempt(ctx, pair)
    watcher = subprocess.Popen(
        list(ctx.cfg.app_exec)
        + ["timeout", "900", "bash", "-s", "--", str(pair), ctx.cfg.app_data_root],
        stdin=open(WATCHER),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True, start_new_session=True,
    )
    time.sleep(2)
    t0 = utc()
    r = owner.post(ep.path("pair_rerun", pairid=pair), keep=["success", "message"])
    body = owner.json(r) or {}

    def done():
        a = _attempt(ctx, pair)
        st = ctx.db.rows(
            f"SELECT p.status_code, string_agg(s.status_code::text, ',' ORDER BY s.stage_number)"
            f" FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
            f" WHERE p.id={pair} GROUP BY p.status_code;"
        )
        ok = a not in (None, attempt_before) and bool(st) and st[0] == ["7", "7,7"]
        return ok, {"attempt": a, "status": st}

    ok, state, seconds = ctx.wait(
        "pipeline pair rerun", done, ctx.cfg.timeout("job", 600), 1.0
    )
    time.sleep(3)
    t1 = utc()
    stop_watcher(watcher)
    events = [
        e
        for e in parse_events(watcher.communicate(timeout=10)[0])
        if e["pair"] == pair and t0 <= e["t"] <= t1
    ]
    cap.check(
        "1",
        "rerun of a pipeline pair executes a new attempt",
        "POST /services/jobs/pairs/rerun/{pair} succeeds, the attempt number changes and the pair and both stages"
        " end COMPLETE",
        {"success": True, "attempt_changed": True, "status": ["7", "7,7"]},
        {
            "success": body.get("success"),
            "attempt_before": attempt_before,
            **(state or {}),
            "seconds": seconds,
        },
        body.get("success") is True and ok,
        ["pair_rerun"],
    )
    if not ok:
        return

    attrs = {}
    for s, k, v in ctx.db.rows(
        f"SELECT stage_number, attr_key, attr_value FROM starexec.job_attributes WHERE pair_id={pair} ORDER BY 1,2;"
    ):
        attrs.setdefault(int(s), {})[k] = v
    for s in (1, 2):
        cap.check(
            f"attrs-{s}",
            f"stage {s} attributes after the rerun are exactly the new attempt's (#183)",
            f"job_attributes for stage {s} are exactly the SZSPostProcessor output of that stage",
            EXPECTED[s],
            attrs.get(s, {}),
            attrs.get(s, {}) == EXPECTED[s],
            ["pair_rerun"],
        )
    cap.check(
        "attrs-extra",
        "no attribute rows outside stages 1-2 after the rerun",
        "no job_attributes row carries another stage",
        [],
        sorted(set(attrs) - {1, 2}),
        not (set(attrs) - {1, 2}),
        ["pair_rerun"],
    )

    after = _stage_measurements(ctx, pair)
    for s in (1, 2):
        src = {"var": [], "watcher": []}
        for e in events:
            if (
                e["stage"] == s
                and e["kind"] in src
                and e["values"]
                and e["values"] not in src[e["kind"]]
            ):
                src[e["kind"]].append(e["values"])
        if len(src["var"]) != 1 or len(src["watcher"]) != 1:
            cap.blocked(
                f"m{s}",
                f"stage {s}: exactly one capture of the new attempt",
                f"got {len(src['var'])} var.out and {len(src['watcher'])} watcher.out captures in the rerun window",
            )
            continue
        distinct = any(
            before.get(s, {}).get(c) != src[k][0].get(key) for c, k, key in FIELDS[:2]
        )
        cap.check(
            f"m{s}-distinct",
            f"stage {s}: the new attempt is distinguishable from the old values",
            "the new var.out WCTIME or CPUTIME differs from the pre-rerun database values, so stale values would be"
            " detected (fixture validity)",
            "differs",
            {
                "before": {c: before.get(s, {}).get(c) for c, _, _ in FIELDS[:2]},
                "new_source": {key: src[k][0].get(key) for _, k, key in FIELDS[:2]},
            },
            distinct,
            ["pair_rerun"],
            status=None if distinct else "BLOCKED",
        )
        for column, kind, key in FIELDS:
            want, got = src[kind][0].get(key), after.get(s, {}).get(column)
            okv = (
                want not in (None, "")
                and got not in (None, "NULL")
                and float(got) == float(want)
            )
            cap.check(
                f"m{s}-{column}",
                f"stage {s}: {column} == new attempt {kind}.{key} (#183)",
                f"after the rerun, jobpair_stage_data.{column} for stage {s} equals, as a double, {key} from the NEW"
                f" attempt's {kind}.out",
                want,
                {"db": got, "before_rerun": before.get(s, {}).get(column)},
                okv,
                ["pair_rerun"],
            )


# ------------------------------------------------------------------------------------- 25: #186 #190
def java_4f(value):
    """String.format("%.4f", double): HALF_UP on the double's shortest decimal digits, not its exact binary
    value (java.util.Formatter via FormattedFloatingDecimal; 1.40534+1.44721 -> 2.8526). repr() gives the
    same shortest digits."""
    return str(
        Decimal(repr(float(value))).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    )


def _cells(fragment):
    return [
        html.unescape(re.sub(r"<[^>]+>", " ", c)).split()
        for c in re.findall(r"<td[^>]*>(.*?)</td>", fragment, re.S)
    ]


def run_attribute_totals(cap, ctx):
    job = ctx.state.need("job_pipeline")
    owner = ctx.owner()
    spaces = [
        int(s)
        for (s,) in ctx.db.rows(
            f"SELECT DISTINCT job_space_id FROM starexec.job_pairs WHERE job_id={job};"
        )
    ]
    if len(spaces) != 1:
        raise Blocked(
            f"expected the pipeline job's pairs in one job space, found {spaces}"
        )
    js = spaces[0]
    rows = ctx.db.rows(
        "SELECT ja.attr_value, s.config_id, count(*), coalesce(sum(s.wallclock),0)::float8::text, coalesce(sum(s.cpu),0)::float8::text"
        " FROM starexec.job_attributes ja JOIN starexec.job_pairs jp ON jp.id=ja.pair_id"
        " JOIN starexec.jobpair_stage_data s ON s.jobpair_id=jp.id AND s.stage_number=ja.stage_number"
        f" WHERE ja.attr_key='starexec-result' AND jp.job_space_id={js} GROUP BY 1,2 ORDER BY 1,2;"
    )
    by_value, by_cell = {}, {}
    for value, config, n, wall, cpu in rows:
        acc = by_value.setdefault(value, [0, Decimal(0), Decimal(0)])
        acc[0] += int(n)
        acc[1] += Decimal(wall)
        acc[2] += Decimal(cpu)
        by_cell[(int(config), value)] = (int(n), java_4f(wall), java_4f(cpu))
    want_totals = {v: [a[0], java_4f(a[1]), java_4f(a[2])] for v, a in by_value.items()}

    page = owner.get(ep.path("job_attributes_page"), params={"id": js})
    text = page.text
    totals_html = re.search(
        r'<table id="attributeTotalsTable">(.*?)</table>', text, re.S
    )
    got_totals = {}
    if totals_html:
        for tr in re.findall(r"<tr>(.*?)</tr>", totals_html.group(1), re.S):
            c = _cells(tr)
            if len(c) == 3 and len(c[2]) == 2:
                got_totals[" ".join(c[0])] = [
                    int(c[1][0]),
                    c[2][0].replace(",", "."),
                    c[2][1].replace(",", "."),
                ]
    counts_ok = {v: got_totals.get(v, [None])[0] for v in want_totals} == {
        v: t[0] for v, t in want_totals.items()
    }
    cap.check(
        "1",
        "attribute totals count each result once per stage (#186)",
        "on jobAttributes.jsp, each totals row's count equals the number of stage-matched job_attributes rows with"
        " that starexec-result in the job space",
        {v: t[0] for v, t in want_totals.items()},
        {"http": page.status_code, "rows": {v: t[0] for v, t in got_totals.items()}},
        page.status_code == 200 and counts_ok,
        ["job_attributes_page", "attributes_sum_sql"],
    )
    times_ok = {v: got_totals.get(v, [None, None, None])[1:] for v in want_totals} == {
        v: t[1:] for v, t in want_totals.items()
    }
    cap.check(
        "2",
        "attribute totals keep sub-second time (#190)",
        "each totals row's wallclock and cpu equal String.format(\"%.4f\") of the exact sum of that result's stage"
        " wallclock/cpu (a BIGINT cast would show whole seconds)",
        {v: t[1:] for v, t in want_totals.items()},
        {v: t[1:] for v, t in got_totals.items()},
        page.status_code == 200 and times_ok,
        ["job_attributes_page", "attributes_totals_format", "attributes_sum_sql"],
    )

    table_html = re.search(r'<table id="attributeTable">(.*?)</table>', text, re.S)
    got_cells, headers = {}, []
    if table_html:
        headers = [
            html.unescape(h).strip()
            for h in re.findall(r"<th[^>]*>\s*(.*?)\s*</th>", table_html.group(1), re.S)
        ][2:]
        for tr in re.findall(
            r"<tr>(.*?)</tr>", table_html.group(1).split("</thead>")[-1], re.S
        ):
            config = re.search(r"configDeleted\.jsp\?id=(\d+)", tr)
            cells = _cells(tr)[2:]
            for value, cell in zip(headers, cells):
                if config and len(cell) >= 4:
                    got_cells[(int(config.group(1)), value)] = (
                        int(cell[0]),
                        cell[2].replace(",", "."),
                        cell[3].replace(",", "."),
                    )
    relevant = {k: v for k, v in got_cells.items() if v[0] or k in by_cell}
    cap.check(
        "3",
        "per-configuration table equals the stage-matched sums at page precision (#190)",
        "every non-empty cell of the attributes table is count / %.4f wallclock / %.4f cpu of exactly the"
        " stage-matched rows for that configuration and result",
        {f"{k[0]}:{k[1]}": v for k, v in by_cell.items()},
        {f"{k[0]}:{k[1]}": v for k, v in relevant.items()},
        relevant == by_cell,
        ["job_attributes_page", "attributes_table_format"],
    )
    per_value = {}
    for (config, value), (n, _, _) in got_cells.items():
        per_value[value] = per_value.get(value, 0) + n
    cap.check(
        "4",
        "table and totals agree",
        "summing the table's counts per result gives the totals row's count",
        {v: t[0] for v, t in got_totals.items()},
        per_value,
        bool(got_totals)
        and all(per_value.get(v, 0) == t[0] for v, t in got_totals.items()),
        ["job_attributes_page"],
    )


# ------------------------------------------------------------------------------------- 23: #187 #189
PIPES = {  # pipeline name -> (stage-1 config, stage-2 config)
    "p_silent": ("silent", "csa"),
    "p_csa": ("csa", "csa"),
    "p_theorem": ("theorem", "csa"),
    "p_variant": ("jobvariant", "csa"),
}
# Stage 1 conflicts (Theorem vs CounterSatisfiable); stage 2 agrees. silent and jobvariant are unknown at stage 1
# in this job (wallclock 30), so they have no conflicts there, even though their pairs are definite at stage 2.
EXPECTED_CONFLICTS = {
    (1, "silent"): 0,
    (1, "csa"): 1,
    (1, "theorem"): 1,
    (1, "jobvariant"): 0,
    (2, "csa"): 0,
}


def _conflict_counts(ctx, owner, job, js, cfg, stage_configs):
    shown = {}
    for stage in (1, 2):
        r, aadata = job_stats(ctx, owner, js, stage)
        for (st, config), row in oracle.parse_displayed(aadata or []).items():
            shown[(st, config)] = row["conflicts"]
    listed = {}
    for stage, name in stage_configs:
        page = owner.get(
            "/secure/details/conflictingBenchmarks.jsp",
            params={"jobId": job, "configId": cfg[name], "stageNumber": stage},
        )
        listed[(stage, name)] = (
            page.status_code,
            len(re.findall(r"conflictingSolvers\.jsp\?", page.text)),
        )
    return shown, listed


def run_conflicts(cap, ctx):
    owner = ctx.owner()
    space, post, benches = ctx.state.need("space", "post_processor", "benchmarks")
    bench = benches.get("SYN916+1.p") or next(iter(benches.values()))
    fx = need_fixture(ctx, "conflict-solver.zip")
    r, sid = upload_solver(
        ctx, owner, ctx.name("conflicts"), fx["path"], "conflict-solver.zip"
    )
    cfg = configs_of(ctx, sid) if sid else {}
    if sorted(cfg) != ["csa", "jobvariant", "silent", "theorem"]:
        raise Blocked(f"conflict solver configurations not discovered: {sorted(cfg)}")
    name = ctx.name("conflicts_a")
    pipes = "".join(
        f'<SolverPipeline name="{p}"><PipelineStage config-id="{cfg[a]}" primary="true"/>'
        f'<PipelineStage config-id="{cfg[b]}"/></SolverPipeline>'
        for p, (a, b) in PIPES.items()
    )
    lines = "".join(
        f'<JobLine pipe-name="{p}" bench-id="{bench}" job-space-id="{space}"/>'
        for p in PIPES
    )
    xml = (
        f'<?xml version="1.0" encoding="UTF-8"?>\n<tns:Jobs xmlns:tns="https://www.starexec.org/starexec/public/batchJobSchema.xsd">\n{pipes}\n'
        f'<Job name="{name}"><JobAttributes><description value="E2E conflicts"/><queue-id value="{ctx.queue()}"/>'
        f'<cpu-timeout value="30"/><wallclock-timeout value="30"/><mem-limit value="0.5"/><postproc-id value="{post}"/>'
        f'<bench-framework value="runsolver"/></JobAttributes><StageAttributes><stage-num value="2"/><cpu-timeout value="30"/>'
        f'<wallclock-timeout value="30"/><mem-limit value="0.5"/><postproc-id value="{post}"/></StageAttributes>{lines}</Job></tns:Jobs>'
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("job.xml", xml)
    owner.post(
        ep.path("upload_job_xml"),
        data={"space": space},
        files={"f": ("conflicts.zip", buf.getvalue(), "application/zip")},
    )
    job = job_id_by_name(ctx, name)
    if not job:
        raise Blocked("conflicts job A not created")
    ok, _, _ = wait_terminal(ctx, job, len(PIPES), ctx.cfg.timeout("job", 600))
    js = root_job_space(ctx, job)
    results = ctx.db.rows(
        "SELECT s.stage_number, s.config_id, coalesce((SELECT a.attr_value FROM starexec.job_attributes a WHERE a.pair_id=p.id"
        " AND a.stage_number=s.stage_number AND a.attr_key='starexec-result' LIMIT 1),'<null>')"
        f" FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id WHERE p.job_id={job} ORDER BY 1,2;"
    )
    names = {v: k for k, v in cfg.items()}
    observed = sorted((int(s), names.get(int(c)), res) for s, c, res in results)
    want = sorted(
        [
            (1, "silent", "starexec-unknown"),
            (1, "csa", "CounterSatisfiable"),
            (1, "theorem", "Theorem"),
            (1, "jobvariant", "starexec-unknown"),
        ]
        + [(2, "csa", "CounterSatisfiable")] * 4
    )
    fixture_ok = ok and observed == want
    cap.check(
        "1",
        "conflict fixture produced the designed per-stage results",
        "job A (4 two-stage pipelines on one benchmark) is terminal and each stage recorded exactly its fixture verdict",
        want,
        {"terminal": ok, "results": observed},
        fixture_ok,
        ["upload_job_xml", "solver_env_wallclock"],
        status=None if fixture_ok else "BLOCKED",
    )
    if not fixture_ok:
        return
    stage_configs = [(st, n) for (st, n) in EXPECTED_CONFLICTS]
    shown, listed = _conflict_counts(ctx, owner, job, js, cfg, stage_configs)
    for (stage, cname), want_n in EXPECTED_CONFLICTS.items():
        got = shown.get((stage, cfg[cname]))
        cap.check(
            f"187-{stage}-{cname}",
            f"stage {stage} {cname}: conflicts counted on its own stage (#187)",
            "the job page's solver-stats conflicts for this (stage, configuration) row equal the designed count;"
            " a configuration unknown at this stage has none even if its pair is definite at the other stage",
            want_n,
            got,
            got == want_n,
            ["job_stats", "conflicts_sql"],
        )
        code, n = listed[(stage, cname)]
        cap.check(
            f"187-{stage}-{cname}-list",
            f"stage {stage} {cname}: conflictingBenchmarks lists the same count (#187)",
            "conflictingBenchmarks.jsp for this (job, configuration, stage) lists exactly that many benchmarks",
            want_n,
            {"http": code, "listed": n},
            code == 200 and n == want_n,
            ["conflicting_benchmarks_page", "conflicts_sql"],
        )

    # #189: job B, same benchmark and configuration 'jobvariant', wallclock 29 -> definite Theorem at stage 1.
    bname = ctx.name("conflicts_b")
    data = {
        "sid": space,
        "name": bname,
        "desc": "E2E #189",
        "runChoice": "choose",
        "benchChoice": "choose",
        "configs": [cfg["jobvariant"]],
        "bench": [bench],
        "preProcess": "-1",
        "postProcess": post,
        "queue": ctx.queue(),
        "seed": "0",
        "cpuTimeout": 29,
        "wallclockTimeout": 29,
        "maxMem": "0.5",
        "pause": "no",
        "benchmarkingFramework": "RUNSOLVER",
        "saveOtherOutput": "true",
    }
    owner.post(ep.path("create_job"), data=data, timeout=120)
    job_b = job_id_by_name(ctx, bname)
    okb = bool(job_b) and wait_terminal(ctx, job_b, 1, ctx.cfg.timeout("job", 300))[0]
    res_b = ctx.db.scalar(
        f"SELECT a.attr_value FROM starexec.job_attributes a JOIN starexec.job_pairs p ON p.id=a.pair_id"
        f" WHERE p.job_id={job_b or 0} AND a.stage_number=1 AND a.attr_key='starexec-result';"
    )
    fixture_b = okb and res_b == "Theorem"
    cap.check(
        "189-fixture",
        "job B gives the same configuration a definite, disagreeing result on the same benchmark",
        "job B (wallclock 29) ran 'jobvariant' on the same benchmark and recorded Theorem at stage 1",
        "Theorem",
        {"job": job_b, "terminal": okb, "result": res_b},
        fixture_b,
        ["create_job", "solver_env_wallclock"],
        status=None if fixture_b else "BLOCKED",
    )
    if not fixture_b:
        return
    shown_b, listed_b = _conflict_counts(ctx, owner, job, js, cfg, [(1, "jobvariant")])
    got = shown_b.get((1, cfg["jobvariant"]))
    baseline = shown.get((1, cfg["jobvariant"]))
    if baseline != 0 or listed[(1, "jobvariant")][1] != 0:
        # With #187 unfixed the count is already 1 before job B exists, so a 1 afterwards says nothing about
        # #189. Only a clean baseline makes this discriminating.
        cap.blocked(
            "189-count",
            "another job's result is not this job's conflict (#189)",
            f"baseline before job B must be 0 (count {baseline}, listed {listed[(1, 'jobvariant')][1]});"
            " #187 must be fixed first",
        )
        cap.blocked(
            "189-list",
            "another job's result is not listed as this job's conflict (#189)",
            "same baseline prerequisite as 189-count",
        )
        return
    cap.check(
        "189-count",
        "another job's result is not this job's conflict (#189)",
        "after job B exists, job A's stage-1 'jobvariant' conflicts are still 0 (A's own result there is unknown)",
        0,
        {"before_job_b": shown.get((1, cfg["jobvariant"])), "after_job_b": got},
        got == 0,
        ["job_stats", "conflicts_sql"],
    )
    code, n = listed_b[(1, "jobvariant")]
    cap.check(
        "189-list",
        "another job's result is not listed as this job's conflict (#189)",
        "conflictingBenchmarks.jsp for job A, 'jobvariant', stage 1 still lists no benchmark",
        0,
        {"http": code, "listed": n},
        code == 200 and n == 0,
        ["conflicting_benchmarks_page", "conflicts_sql"],
    )
