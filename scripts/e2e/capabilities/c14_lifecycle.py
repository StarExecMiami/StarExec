"""14: pause/resume, rerun one pair, rerun all pairs, and kill (reachable only as delete of a running job)."""

import time

from lib import endpoints as ep
from lib import oracle
from lib.report import Blocked
from lib.steps import TERMINAL, job_stats, pair_statuses, root_job_space, wait_terminal
from capabilities.c10_single_stage import create_job

RUNNING_OR_QUEUED = set(range(1, 7))
PAUSED = 20


def _attempts(ctx, pairs):
    ids = ",".join(str(p) for p in pairs) or "0"
    return {
        int(p): int(a)
        for p, a in ctx.db.rows(
            f"SELECT pair_id, current_attempt_no FROM starexec.job_pair_attempts WHERE pair_id IN ({ids});"
        )
    }


def run_pause_resume(cap, ctx):
    benches, cfgs = ctx.state.need("benchmarks", "fixture_configs")
    owner = ctx.owner()
    r, job = create_job(
        ctx,
        owner,
        ctx.name("pause"),
        [cfgs["slow"]],
        list(benches.values()),
        cpu=30,
        wall=30,
    )
    if not job:
        raise Blocked("pause job not created")
    n = len(pair_statuses(ctx, job))
    r = owner.post(ep.path("pause_job", id=job), keep=["success", "message"])
    body = owner.json(r) or {}

    def settled():
        st = pair_statuses(ctx, job)
        return all(s in TERMINAL or s == PAUSED for s in st.values()), sorted(
            st.items()
        )

    ok, statuses, seconds = ctx.wait(
        "paused job settles", settled, ctx.cfg.timeout("pause", 120), 1.0
    )
    paused_flag = ctx.db.scalar(f"SELECT paused FROM starexec.jobs WHERE id={job};")
    cap.check(
        "p1",
        "pause stops dispatch",
        "POST /services/pause/job/{id} succeeds, jobs.paused = t, and every pair is"
        " either terminal or STATUS_PAUSED(20) with none left enqueued or running",
        {"success": True, "paused": "t", "active_pairs": 0},
        {
            "success": body.get("success"),
            "paused": paused_flag,
            "statuses": statuses,
            "seconds": seconds,
        },
        body.get("success") is True and paused_flag == "t" and ok,
        ["pause_job"],
    )
    snapshot = pair_statuses(ctx, job)
    time.sleep(10)
    later = pair_statuses(ctx, job)
    cap.check(
        "p2",
        "nothing runs while paused",
        "over 10 s after settling, no paused pair changes status",
        {p: s for p, s in snapshot.items() if s == PAUSED},
        {p: later[p] for p, s in snapshot.items() if s == PAUSED},
        all(later[p] == PAUSED for p, s in snapshot.items() if s == PAUSED),
        ["pause_job"],
    )
    r = owner.post(ep.path("resume_job", id=job), keep=["success", "message"])
    body = owner.json(r) or {}
    ok, last, seconds = wait_terminal(ctx, job, n, ctx.cfg.timeout("job", 900))
    final = pair_statuses(ctx, job)
    cap.check(
        "p3",
        "resume completes every pair",
        "POST /services/resume/job/{id} succeeds, jobs.paused = f and every"
        " pair ends STATUS_COMPLETE(7)",
        {"success": True, "paused": "f", "all_complete": True},
        {
            "success": body.get("success"),
            "paused": ctx.db.scalar(
                f"SELECT paused FROM starexec.jobs WHERE id={job};"
            ),
            "statuses": sorted(final.items()),
            "seconds": seconds,
        },
        body.get("success") is True and all(s == 7 for s in final.values()),
        ["resume_job"],
    )


def run_rerun(cap, ctx):
    job = ctx.state.need("job_single")
    owner = ctx.owner()
    config_e = ctx.state.need("config_e")
    pair = ctx.db.int(
        f"SELECT p.id FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
        f" WHERE p.job_id={job} AND s.config_id={config_e} AND p.status_code=7 ORDER BY p.id LIMIT 1;"
    )
    if not pair:
        raise Blocked("no completed E pair to rerun")
    space = root_job_space(ctx, job)
    job_stats(
        ctx, owner, space, 1
    )  # make sure a statistics cache exists before the rerun
    before = _attempts(ctx, [pair]).get(pair)
    r = owner.post(ep.path("pair_rerun", pairid=pair), keep=["success", "message"])
    body = owner.json(r) or {}

    def reran():
        a = _attempts(ctx, [pair]).get(pair)
        st = ctx.db.int(f"SELECT status_code FROM starexec.job_pairs WHERE id={pair};")
        return a is not None and a != before and st in TERMINAL, {
            "attempt": a,
            "status": st,
        }

    ok, state, seconds = ctx.wait("pair rerun", reran, ctx.cfg.timeout("job", 600), 1.0)
    result = ctx.db.scalar(
        f"SELECT attr_value FROM starexec.job_attributes WHERE pair_id={pair} AND stage_number=1"
        " AND attr_key='starexec-result';"
    )
    cap.check(
        "r1",
        "rerun of one pair executes a new attempt",
        "POST /services/jobs/pairs/rerun/{pair} succeeds, the"
        " attempt number changes, and the pair is terminal again with one stage-1 starexec-result",
        {"success": True, "attempt_changed": True, "status": 7},
        {
            "success": body.get("success"),
            "attempt_before": before,
            **(state or {}),
            "result": result,
            "seconds": seconds,
        },
        body.get("success") is True and ok and (state or {}).get("status") == 7,
        ["pair_rerun"],
    )
    rows = ctx.db.rows(oracle.stage_rows_sql(job))
    want = oracle.expected_stats(rows)[(1, config_e)].row()
    r, aadata = job_stats(ctx, owner, space, 1)
    shown = oracle.parse_displayed(aadata or []).get((1, config_e), {})
    cap.check(
        "r2",
        "statistics after a rerun reflect the new attempt",
        "the E row's solved/wrong/incomplete after the"
        " rerun equal the database recomputation (a cached pre-rerun row would differ once results change)",
        {k: want[k] for k in ("solved", "wrong", "incomplete")},
        {k: shown.get(k) for k in ("solved", "wrong", "incomplete")},
        all(shown.get(k) == want[k] for k in ("solved", "wrong", "incomplete")),
        ["job_stats", "pair_rerun"],
    )

    closure_job = ctx.state.get("job_closure")
    if not closure_job:
        cap.blocked(
            "r3",
            "rerun all pairs",
            "the #197 closure job from capability 19 is not present",
        )
        return
    pairs = list(pair_statuses(ctx, closure_job))
    before = _attempts(ctx, pairs)
    r = owner.post(ep.path("rerun_all", id=closure_job), keep=["success", "message"])
    body = owner.json(r) or {}

    def all_reran():
        a = _attempts(ctx, pairs)
        st = pair_statuses(ctx, closure_job)
        return all(a.get(p) not in (None, before.get(p)) for p in pairs) and all(
            s in TERMINAL for s in st.values()
        ), {"attempts": a, "statuses": st}

    ok, state, seconds = ctx.wait(
        "rerun all", all_reran, ctx.cfg.timeout("job", 600), 1.0
    )
    cap.check(
        "r3",
        "rerun all pairs re-executes every pair",
        "POST /services/jobs/rerunallpairs/{job} succeeds and every"
        " pair gets a new attempt and is terminal again",
        {"success": True, "all_new_attempts": True},
        {
            "success": body.get("success"),
            "before": before,
            **(state or {}),
            "seconds": seconds,
        },
        body.get("success") is True and ok,
        ["rerun_all"],
    )


def run_kill(cap, ctx):
    benches, cfgs = ctx.state.need("benchmarks", "fixture_configs")
    owner = ctx.owner()
    r, job = create_job(
        ctx,
        owner,
        ctx.name("kill"),
        [cfgs["sleep"]],
        list(benches.values())[:2],
        cpu=120,
        wall=120,
    )
    if not job:
        raise Blocked("kill job not created")

    def running():
        st = pair_statuses(ctx, job)
        return any(s == 4 for s in st.values()), sorted(st.items())

    ok, statuses, _ = ctx.wait(
        "a pair is running", running, ctx.cfg.timeout("dispatch", 180), 1.0
    )
    if not ok:
        raise Blocked(f"no pair of job {job} started running: {statuses}")
    r = owner.post(
        ep.path("delete_job"), data={"selectedIds[]": job}, keep=["success", "message"]
    )
    body = owner.json(r) or {}

    def stopped():
        st = pair_statuses(ctx, job)
        return not any(s in RUNNING_OR_QUEUED for s in st.values()), sorted(st.items())

    ok, statuses, seconds = ctx.wait(
        "deleted job stops", stopped, ctx.cfg.timeout("kill", 120), 1.0
    )
    deleted = ctx.db.scalar(f"SELECT deleted FROM starexec.jobs WHERE id={job};")
    cap.check(
        "k1",
        "deleting a running job kills it",
        "there is no kill endpoint; Jobs.delete kills an incomplete job"
        " first. POST /services/delete/job marks jobs.deleted = t and, within the kill timeout, no pair of the job"
        " is still pending, enqueued or running (statuses 1-6)",
        {"success": True, "deleted": "t", "active": 0},
        {
            "success": body.get("success"),
            "deleted": deleted,
            "statuses": statuses,
            "seconds": seconds,
        },
        body.get("success") is True and deleted == "t" and ok,
        ["delete_job", "delete_kills_incomplete"],
    )
    if ctx.cfg.app_exec and ctx.cfg.backend == "local":
        time.sleep(5)
        code, out = ctx.app(
            "sh", "-c", "ps -eo args | grep -c '[s]tarexec_run_sleep' || true"
        )
        cap.check(
            "k2",
            "no fixture sleep process survives (Local)",
            "inside the app container no starexec_run_sleep"
            " process remains 5 s after the pairs stopped",
            "0",
            out.strip(),
            out.strip() == "0",
            ["delete_kills_incomplete"],
        )
