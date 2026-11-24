"""10: single-stage job creation. 11: execution and known answers. 12: solver statistics (#198, R2, #194).
19: per-type pairs listing before the statistics page was ever loaded (#197)."""

import re
from collections import defaultdict

from lib import endpoints as ep
from lib import oracle
from lib.db import literal
from lib.report import Blocked
from lib.steps import (
    TERMINAL,
    job_id_by_name,
    job_stats,
    pair_statuses,
    pairs_by_config,
    root_job_space,
    wait_terminal,
)

LIMIT_S = 10
UNKNOWN = oracle.UNKNOWN_RESULT


def create_job(
    ctx, client, name, configs, benches, cpu=LIMIT_S, wall=LIMIT_S, pause="no"
):
    space, post = ctx.state.need("space", "post_processor")
    data = {
        "sid": space,
        "name": name,
        "desc": "E2E",
        "runChoice": "choose",
        "benchChoice": "choose",
        "configs": list(configs),
        "bench": list(benches),
        "preProcess": "-1",
        "postProcess": post,
        "queue": ctx.queue(),
        "seed": "0",
        "cpuTimeout": cpu,
        "wallclockTimeout": wall,
        "maxMem": "0.5",
        "pause": pause,
        "benchmarkingFramework": "RUNSOLVER",
        "saveOtherOutput": "true",
    }
    r = client.post(ep.path("create_job"), data=data, timeout=120)
    return r, job_id_by_name(ctx, name)


def run_create(cap, ctx):
    benches, config_e, fixture = ctx.state.need(
        "benchmarks", "config_e", "fixture_configs"
    )
    e_config_name = ctx.fx.get("eprover_config", "E-3.3.3-sequential")
    configs = {
        e_config_name: config_e,
        "wrong": fixture["wrong"],
        "silent": fixture["silent"],
        "sleep": fixture["sleep"],
    }
    owner = ctx.owner()
    name = ctx.name("single")
    r, job = create_job(ctx, owner, name, configs.values(), benches.values())
    cap.check(
        "1",
        "job creation redirects to the job page",
        "POST /secure/add/job creates a jobs row and redirects to"
        " secure/details/job.jsp?id=<job>",
        "job.jsp?id=<job>",
        {"http": r.status_code, "job": job, "redirected": bool(r.history)},
        bool(job) and bool(r.history) and "job.jsp" in r.url,
        ["create_job", "create_job_params", "create_job_redirect"],
    )
    if not job:
        raise Blocked("job not created")
    rows = ctx.db.rows(
        f"SELECT p.bench_id, s.config_id, count(*) FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s"
        f" ON s.jobpair_id=p.id WHERE p.job_id={job} GROUP BY 1,2 ORDER BY 1,2;"
    )
    got = {(int(b), int(c)): int(n) for b, c, n in rows}
    want = {(b, c): 1 for b in benches.values() for c in configs.values()}
    cap.check(
        "2",
        "pairs = benchmarks x configurations, each exactly once",
        f"job_pairs/jobpair_stage_data hold exactly one stage-1 row for each of the {len(benches)} benchmarks x"
        f" {len(configs)} configurations and nothing else",
        {"pairs": len(want), "each_once": True},
        {
            "pairs": sum(got.values()),
            "missing": sorted(set(want) - set(got)),
            "extra": sorted(set(got) - set(want)),
            "duplicates": sorted(k for k, v in got.items() if v != 1),
        },
        got == want,
        ["create_job"],
    )
    queue = ctx.db.int(f"SELECT queue_id FROM starexec.jobs WHERE id={job};")
    total = ctx.db.int(f"SELECT total_pairs FROM starexec.jobs WHERE id={job};")
    cap.check(
        "3",
        "job settings are persisted",
        "jobs.queue_id equals the discovered active queue and total_pairs equals the"
        " pair count",
        {"queue": ctx.queue(), "total_pairs": len(want)},
        {"queue": queue, "total_pairs": total},
        queue == ctx.queue() and total == len(want),
        ["create_job"],
    )
    ctx.state.set("job_single", job)
    ctx.state.fact("job_single_configs", configs)


def _results(ctx, job):
    return ctx.db.rows(
        "SELECT p.id, b.name, s.config_id, p.status_code, s.status_code, coalesce(s.wallclock,0),"
        " coalesce((SELECT a.attr_value FROM starexec.job_attributes a WHERE a.pair_id=p.id AND a.stage_number=1"
        "  AND a.attr_key='starexec-result' LIMIT 1),'<null>')"
        " FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id AND s.stage_number=1"
        f" JOIN starexec.benchmarks b ON b.id=p.bench_id WHERE p.job_id={job} ORDER BY p.id;"
    )


def run_execute(cap, ctx):
    job, configs, expected = ctx.state.need(
        "job_single", "job_single_configs", "expected_status"
    )
    npairs = len(pair_statuses(ctx, job))
    if npairs < 1:
        raise Blocked(f"job {job} has no pairs to wait for")
    ok, last, seconds = wait_terminal(ctx, job, npairs, ctx.cfg.timeout("job", 1200))
    cap.check(
        "1",
        "every pair reaches a terminal execution result",
        f"all {npairs} job_pairs.status_code are in isTerminalExecutionResult within the job timeout",
        "all terminal",
        {
            "terminal": ok,
            "seconds": seconds,
            "non_terminal": [p for p, s in (last or []) if s not in TERMINAL],
        },
        ok,
        ["create_job"],
    )
    by_name = {v: k for k, v in configs.items()}
    rows = _results(ctx, job)
    groups = defaultdict(list)
    for pid, bench, config, pstatus, sstatus, wall, result in rows:
        groups[by_name.get(int(config))].append(
            (int(pid), bench, int(pstatus), int(sstatus), float(wall), result)
        )

    e_config_name = ctx.fx.get("eprover_config", "E-3.3.3-sequential")
    solved = {"Theorem": 0, "CounterSatisfiable": 0}
    wrong = []
    for pid, bench, pst, sst, wall, result in groups[e_config_name]:
        truth = expected[bench]
        if result not in (
            "<null>",
            UNKNOWN,
            "Unknown",
            "Timeout",
            "GaveUp",
            "ResourceOut",
        ):
            if result == truth:
                solved[truth] = solved.get(truth, 0) + 1
            else:
                wrong.append(
                    {"pair": pid, "bench": bench, "result": result, "expected": truth}
                )
    cap.check(
        "2",
        "E never reports an answer that contradicts the TPTP header",
        "for every E pair with a definite starexec-result, the result equals the problem's header '% Status'"
        " exactly (Unknown/Timeout/GaveUp/ResourceOut count as unsolved, never as correct)",
        [],
        wrong,
        not wrong,
        ["create_job"],
    )
    n_thm = sum(1 for v in expected.values() if v == "Theorem")
    n_csa = sum(1 for v in expected.values() if v == "CounterSatisfiable")
    fixture_ok = solved["Theorem"] == n_thm
    cap.check(
        "3",
        "known answers: every Theorem fixture is proved",
        f"E records starexec-result=Theorem for all {n_thm}"
        " AGT problems (proved in earlier qualification; a miss makes the fixture unusable)",
        n_thm,
        solved["Theorem"],
        fixture_ok,
        ["create_job"],
        status=None if fixture_ok else "BLOCKED",
    )
    cap.check(
        "4",
        "known answers: CounterSatisfiable fixtures",
        f"E records starexec-result=CounterSatisfiable for all"
        f" {n_csa} prescreened CSA problems",
        n_csa,
        solved["CounterSatisfiable"],
        solved["CounterSatisfiable"] == n_csa and n_csa > 0,
        ["create_job"],
        status=None if n_csa else "BLOCKED",
    )

    def exact(cid, label, config, predicate, want, asserted):
        items = groups[config]
        bad = [
            (pid, bench, pst, sst, result)
            for pid, bench, pst, sst, wall, result in items
            if not predicate(pst, sst, result, wall)
        ]
        cap.check(
            cid,
            label,
            asserted,
            want,
            {"pairs": len(items), "violations": bad},
            bool(items) and not bad,
            ["create_job"],
        )

    exact(
        "5",
        "fixture 'wrong' always records CounterSatisfiable",
        "wrong",
        lambda p, s, r, w: p == 7 and s == 7 and r == "CounterSatisfiable",
        "status 7, result CounterSatisfiable",
        "every 'wrong' pair is COMPLETE with starexec-result=CounterSatisfiable (SZSPostProcessor on a fixed line)",
    )
    exact(
        "6",
        "fixture 'silent' records starexec-unknown",
        "silent",
        lambda p, s, r, w: p == 7 and s == 7 and r == UNKNOWN,
        "status 7, result starexec-unknown",
        "every 'silent' pair is COMPLETE and an empty SZS status is normalised to starexec-unknown",
    )
    exact(
        "7",
        "fixture 'sleep' is a wallclock limit breach",
        "sleep",
        lambda p, s, r, w: p == 14 and s == 14,
        "status 14 (EXCEED_RUNTIME)",
        f"every 'sleep' pair (sleeps 60 s under a {LIMIT_S} s wallclock limit) ends EXCEED_RUNTIME(14) at pair and stage level",
    )


def run_stats(cap, ctx):
    job, configs = ctx.state.need("job_single", "job_single_configs")
    owner = ctx.owner()
    space = root_job_space(ctx, job)
    rows = ctx.db.rows(oracle.stage_rows_sql(job))
    expected = oracle.expected_stats(rows, include_unknown=False)
    bench_of = dict(
        ctx.db.rows(f"SELECT id, bench_id FROM starexec.job_pairs WHERE job_id={job};")
    )
    # Independent conflict count per (stage, config).
    seen = defaultdict(set)
    for pair, primary, st, code, solver, config, wall, cpu, result, exp in rows:
        if result not in ("<null>", UNKNOWN):
            seen[(int(st), bench_of[pair])].add(result)
    conflicting = {k for k, v in seen.items() if len(v) > 1}
    conflicts = defaultdict(int)
    for pair, primary, st, code, solver, config, wall, cpu, result, exp in rows:
        if (
            result not in ("<null>", UNKNOWN)
            and (int(st), bench_of[pair]) in conflicting
        ):
            conflicts[(int(st), int(config))] += 1

    names = {v: k for k, v in configs.items()}
    for stage in (1, 0):
        r, aadata = job_stats(
            ctx, owner, space, stage, include_unknown=False, wallclock=True
        )
        if aadata is None:
            cap.check(
                f"s{stage}",
                f"stage {stage} statistics are returned",
                "POST job_stats returns a DataTables object with aaData",
                "aaData",
                {"http": r.status_code},
                False,
                ["job_stats"],
            )
            continue
        shown = oracle.parse_displayed(aadata)
        want_keys = sorted(k for k in expected if k[0] == stage)
        cap.check(
            f"s{stage}-rows",
            f"stage {stage}: one row per configuration",
            "the rows returned for this stage are"
            " exactly the (stage, configuration) keys the database implies",
            want_keys,
            sorted(shown),
            sorted(shown) == want_keys,
            ["job_stats", "job_stats_row_layout", "stats_primary_row"],
        )
        for key in want_keys:
            e, s = expected[key], shown.get(key)
            if s is None:
                continue
            label = f"stage {stage} {names.get(key[1], key[1])}"
            if stage == 0:
                prim = expected.get((1, key[1]))
                intended = prim.row() if prim else None
                cap.check(
                    f"s0-{key[1]}-R2",
                    f"{label}: the Primary row carries the primary stage's statistics (R2)",
                    "the stage-0 row's solved/wrong/resourced/failed/unknown/incomplete equal the primary"
                    " (stage 1) row's; since #206 the primary SolverStats accumulates its stage (Jobs.java:7108 at 5af7357b3)",
                    {
                        k: intended[k]
                        for k in (
                            "solved",
                            "wrong",
                            "resourced",
                            "failed",
                            "unknown_intended",
                            "incomplete",
                        )
                    }
                    if intended
                    else None,
                    {
                        "displayed": {
                            k: s[k]
                            for k in (
                                "solved",
                                "wrong",
                                "resourced",
                                "failed",
                                "unknown",
                                "incomplete",
                            )
                        },
                        "as_coded_prediction": {
                            k: e.row()[k]
                            for k in (
                                "solved",
                                "wrong",
                                "resourced",
                                "failed",
                                "unknown_as_coded",
                                "incomplete",
                            )
                        },
                    },
                    bool(intended)
                    and s["solved"] == intended["solved"]
                    and s["wrong"] == intended["wrong"]
                    and s["resourced"] == intended["resourced"]
                    and s["failed"] == intended["failed"]
                    and s["incomplete"] == intended["incomplete"],
                    ["job_stats", "stats_primary_row", "stats_accumulate"],
                )
            else:
                row = e.row()
                for field in ("solved", "wrong", "resourced", "failed", "incomplete"):
                    cap.check(
                        f"s{stage}-{key[1]}-{field}",
                        f"{label}: {field}",
                        f"the displayed {field} equals the value recomputed from jobpair_stage_data, stage-matched"
                        " job_attributes and bench_attributes (addStageToSolverStats/isPairCorrect rules)",
                        row[field],
                        s[field],
                        s[field] == row[field],
                        ["job_stats", "stats_accumulate", "pair_correct"],
                    )
                cap.check(
                    f"s{stage}-{key[1]}-unknown",
                    f"{label}: unknown (#198)",
                    "displayed unknown equals the stat-complete stages that are neither correct, incorrect nor"
                    " resource-out; the as-coded formula complete-(resourceOut+correct+incorrect) is recorded beside it",
                    row["unknown_intended"],
                    {
                        "displayed": s["unknown"],
                        "as_coded_prediction": row["unknown_as_coded"],
                    },
                    s["unknown"] == row["unknown_intended"],
                    ["job_stats", "stats_unknown"],
                )
                cap.check(
                    f"s{stage}-{key[1]}-time",
                    f"{label}: wallclock time of solved pairs",
                    "displayed time equals round(100 x sum of wallclock over correct stages)",
                    row["time_x100"]["wall"],
                    s["time_x100"],
                    s["time_x100"] == row["time_x100"]["wall"],
                    ["job_stats", "stats_accumulate"],
                )
            want_conf = conflicts.get((stage if stage else 1, key[1]), 0)
            sql_conf = ctx.db.int(
                f"SELECT conflicting_benchmarks FROM starexec.GetConflictsForConfigInJob({job},{key[1]},{stage if stage else 1});"
            )
            cap.check(
                f"s{stage}-{key[1]}-conflicts",
                f"{label}: conflicts",
                "displayed conflicts equal an independent count (benchmarks with >1 distinct non-unknown result at"
                " the stage on which this configuration gave a non-unknown result) and GetConflictsForConfigInJob",
                {"independent": want_conf, "sql": sql_conf},
                s["conflicts"],
                s["conflicts"] == want_conf == sql_conf,
                ["job_stats"],
            )
            if stage == 0 and s["conflicts"]:
                page = owner.get(
                    "/secure/details/conflictingBenchmarks.jsp",
                    params={"jobId": job, "configId": key[1], "stageNumber": 0},
                )
                listed = len(re.findall(r"conflictingSolvers\.jsp\?", page.text))
                cap.check(
                    f"s0-{key[1]}-194",
                    f"{label}: the Primary row's conflicts link lists its conflicts (#194)",
                    "the page the stage-0 row links to (job.js builds stageNumber=val[STAGE]=0) lists as many"
                    " benchmarks as the row's count",
                    s["conflicts"],
                    {"http": page.status_code, "listed": listed},
                    listed == s["conflicts"],
                    ["conflicting_benchmarks_page", "conflicts_link_stage"],
                    known="#194",
                )


def run_closure(cap, ctx):
    bench = next(iter(ctx.state.need("benchmarks").values()))
    config = ctx.state.need("fixture_configs")["wrong"]
    owner = ctx.owner()
    r, job = create_job(ctx, owner, ctx.name("closure"), [config], [bench])
    if not job:
        raise Blocked("closure job not created")
    ctx.state.set("job_closure", job)
    ok, _, _ = wait_terminal(ctx, job, 1, ctx.cfg.timeout("job", 600))
    space = root_job_space(ctx, job)
    closure_before = ctx.db.int(
        f"SELECT count(*) FROM starexec.job_space_closure WHERE ancestor={space};"
    )
    r1, d1 = pairs_by_config(ctx, owner, space, config, "all", 1)
    before = (d1 or {}).get("iTotalRecords")
    job_stats(ctx, owner, space, 1)
    closure_after = ctx.db.int(
        f"SELECT count(*) FROM starexec.job_space_closure WHERE ancestor={space};"
    )
    r2, d2 = pairs_by_config(ctx, owner, space, config, "all", 1)
    after = (d2 or {}).get("iTotalRecords")
    passed = cap.check(
        "1",
        "#197: per-type pairs listing works before the statistics page was opened",
        "for a fresh terminal 1-pair job, the pairs-by-config listing (type=all, stage 1) reports 1 pair"
        " before any statistics request; closure rows and the listing are recorded before and after",
        1,
        {
            "terminal": ok,
            "closure_rows_before": closure_before,
            "listed_before": before,
            "closure_rows_after_stats": closure_after,
            "listed_after_stats": after,
        },
        before == 1,
        ["pairs_by_config", "pairs_by_config_closure", "job_stats"],
    )
    if not passed:
        cap.finding(
            "197",
            "pairs listing depends on the statistics page",
            f"job {job}: listing returned {before} before and {after} after the statistics request;"
            f" job_space_closure rows {closure_before} -> {closure_after}.",
        )
