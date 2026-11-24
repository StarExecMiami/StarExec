"""26: the per-type pair listings linked from the solver summary classify pairs the way the summary counts them.

F1: a two-stage pair whose stage 1 completes and stage 2 breaches its wallclock (pair status 14). The stage-1
    summary row counts it as solved, not resource-out; its 'resource' listing must not list it.
F2: a pair at a failed status (8-13, 18, 24). The summary counts it only as failed; its 'incomplete' listing
    must not list it. Needs a backend that records a failed pair status: on PodmanBackend every such status is
    written at stage 0 and held, so F2 is BLOCKED there unless the fixture pair really ends failed. LocalBackend
    at 5af7357b3 behaves the same for the failvar fixture (pair 51 held at 4, InvalidSnapshotException).
Listings: POST /services/jobs/pairs/pagination/{space}/{config}/{type}/{wall}/{stage} (endpoint pairs_by_config).
Summary: POST /services/jobs/solvers/pagination/... (endpoint job_stats).
"""

import io
import time
import zipfile

from lib import endpoints as ep
from lib import oracle
from lib.db import literal
from lib.report import Blocked
from lib.steps import (
    job_id_by_name,
    job_stats,
    need_fixture,
    pair_statuses,
    pairs_by_config,
    root_job_space,
    wait_terminal,
)
from capabilities.c07_solvers import configs_of, upload_solver

FAILED = {8, 9, 10, 11, 12, 13, 18, 24}
TYPES = ("all", "solved", "wrong", "unknown", "resource", "failed", "incomplete")


def _listing_counts(ctx, owner, js, config, stage):
    out = {}
    for t in TYPES:
        r, d = pairs_by_config(ctx, owner, js, config, t, stage)
        out[t] = (d or {}).get("iTotalRecords") if isinstance(d, dict) else None
    return out


def _summary_row(ctx, owner, js, stage, config):
    r, aadata = job_stats(ctx, owner, js, stage)
    return oracle.parse_displayed(aadata or []).get((stage, config))


def _compare(cap, cid, label, row, listed, fields, endpoints, known=None):
    """solved is 'correct/complete'; the other fields are integers."""
    want = {}
    for f in fields:
        if f == "solved":
            want[f] = int(str(row["solved"]).split("/")[0]) if row else None
        else:
            want[f] = row.get({"resource": "resourced"}.get(f, f)) if row else None
    got = {f: listed.get(f) for f in fields}
    return cap.check(
        cid,
        label,
        "for each type, the listing's iTotalRecords equals the summary row's count of that type"
        " (the summary classifies by STAGE status, Jobs.addStageToSolverStats)",
        want,
        {"listing": got, "summary_row": row},
        bool(row) and want == got,
        endpoints,
        known=known,
    )


def run_classification(cap, ctx):
    owner = ctx.owner()
    space, post, benches, cfgs = ctx.state.need(
        "space", "post_processor", "benchmarks", "fixture_configs"
    )
    bench = benches.get("SYN916+1.p") or next(iter(benches.values()))
    queue = ctx.queue()

    # ---------------- F1
    name = ctx.name("f1")
    xml = (
        f'<?xml version="1.0" encoding="UTF-8"?>\n<tns:Jobs xmlns:tns="https://www.starexec.org/starexec/public/batchJobSchema.xsd">\n'
        f'<SolverPipeline name="f1"><PipelineStage config-id="{cfgs["wrong"]}" primary="true"/>'
        f'<PipelineStage config-id="{cfgs["sleep"]}"/></SolverPipeline>'
        f'<Job name="{name}"><JobAttributes><description value="F1"/><queue-id value="{queue}"/><cpu-timeout value="30"/>'
        f'<wallclock-timeout value="30"/><mem-limit value="0.5"/><postproc-id value="{post}"/><bench-framework value="runsolver"/>'
        f'</JobAttributes><StageAttributes><stage-num value="2"/><cpu-timeout value="5"/><wallclock-timeout value="5"/>'
        f'<mem-limit value="0.5"/><postproc-id value="{post}"/></StageAttributes>'
        f'<JobLine pipe-name="f1" bench-id="{bench}" job-space-id="{space}"/></Job></tns:Jobs>'
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("job.xml", xml)
    owner.post(
        ep.path("upload_job_xml"),
        data={"space": space},
        files={"f": ("f1.zip", buf.getvalue(), "application/zip")},
    )
    job = job_id_by_name(ctx, name)
    if not job:
        cap.blocked("F1", "F1 fixture job", "the F1 pipeline job was not created")
    else:
        ok, _, _ = wait_terminal(ctx, job, 1, ctx.cfg.timeout("job", 600))
        rows = ctx.db.rows(
            f"SELECT p.status_code, string_agg(s.status_code::text, ',' ORDER BY s.stage_number)"
            f" FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
            f" WHERE p.job_id={job} GROUP BY p.status_code;"
        )
        valid = (
            ok
            and rows
            and rows[0][0] in ("14", "15")
            and rows[0][1] == f"7,{rows[0][0]}"
        )
        cap.check(
            "F1-fixture",
            "F1 fixture: stage 1 COMPLETE, stage 2 limit breach",
            "the pair ends 14/15 with stages 7 and 14/15",
            "pair 14|15, stages 7,14|15",
            {"terminal": ok, "rows": rows},
            bool(valid),
            ["upload_job_xml"],
            status=None if valid else "BLOCKED",
        )
        if valid:
            js = root_job_space(ctx, job)
            row = _summary_row(
                ctx, owner, js, 1, cfgs["wrong"]
            )  # the summary first: it populates the closure (#207)
            listed = _listing_counts(ctx, owner, js, cfgs["wrong"], 1)
            _compare(
                cap,
                "F1",
                "F1: stage-1 listings match the stage-1 summary row",
                row,
                listed,
                ["solved", "resource", "failed", "incomplete"],
                ["pairs_by_config", "job_stats"],
                known="F1",
            )

    # ---------------- F2
    fx = need_fixture(ctx, "fail-solver.zip")
    r, sid = upload_solver(
        ctx, owner, ctx.name("failvar"), fx["path"], "fail-solver.zip"
    )
    fcfg = configs_of(ctx, sid).get("failvar") if sid else None
    if not fcfg:
        cap.blocked(
            "F2",
            "F2 fixture solver",
            "the failvar solver/configuration was not created",
        )
        return
    jname = ctx.name("f2")
    data = {
        "sid": space,
        "name": jname,
        "desc": "F2",
        "runChoice": "choose",
        "benchChoice": "choose",
        "configs": [fcfg],
        "bench": [bench],
        "preProcess": "-1",
        "postProcess": post,
        "queue": queue,
        "seed": "0",
        "cpuTimeout": 10,
        "wallclockTimeout": 10,
        "maxMem": "0.5",
        "pause": "no",
        "benchmarkingFramework": "RUNSOLVER",
        "saveOtherOutput": "true",
    }
    owner.post(ep.path("create_job"), data=data, timeout=120)
    job2 = job_id_by_name(ctx, jname)
    ok, last, _ = (
        wait_terminal(ctx, job2, 1, ctx.cfg.timeout("dispatch", 300))
        if job2
        else (False, None, None)
    )
    status = next(iter(pair_statuses(ctx, job2).values()), None) if job2 else None
    valid = ok and status in FAILED
    cap.check(
        "F2-fixture",
        "F2 fixture: the pair ends at a failed status",
        "the failvar pair ends with a status in"
        " 8-13, 18 or 24 in job_pairs (no status is ever written by the harness)",
        sorted(FAILED),
        {"job": job2, "terminal": ok, "pair_status": status},
        bool(valid),
        ["create_job"],
        # Ledger B3 (#145/#165): the failvar pair is held with a stage-0 final status instead of reaching a
        # failed status, so this is a reproduced known defect rather than a missing precondition.
        known=None if valid else "B3",
    )
    if not valid:
        if job2:
            owner.post(ep.path("delete_job"), data={"selectedIds[]": job2})
            ctx.run.recorder.note(f"deleted held F2 job {job2}")
        return
    js = root_job_space(ctx, job2)
    row = _summary_row(ctx, owner, js, 1, fcfg)
    listed = _listing_counts(ctx, owner, js, fcfg, 1)
    _compare(
        cap,
        "F2",
        "F2: failed and incomplete listings match the summary row",
        row,
        listed,
        ["failed", "incomplete", "resource", "solved"],
        ["pairs_by_config", "job_stats"],
        known="F2",
    )
