"""0: deployment preflight. 21: solver upload with runTestJob=true (H1, H2a, H2b).
22: job-container creation log lines (Java client vs curl fallback, and whether a cause is logged)."""

import re
import time
from datetime import datetime, timezone

import requests

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import TERMINAL, need_fixture, pair_statuses

SAMPLES_S = (0, 60, 180)


def utc_rfc3339():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def readiness(ctx):
    try:
        return requests.get(
            ctx.cfg.base_url + "/public/health/readiness", timeout=10
        ).status_code
    except requests.RequestException as exc:
        return f"{type(exc).__name__}"


def run_preflight(cap, ctx):
    code = readiness(ctx)
    cap.check(
        "1",
        "application readiness",
        "GET /public/health/readiness returns 200 (it checks DB connectivity)",
        200,
        code,
        code == 200,
    )
    if code != 200:
        raise Blocked("the application is not ready")
    rows = ctx.db.rows("SELECT id, name, status FROM starexec.queues ORDER BY id;")
    ctx.state.fact("queue_table", rows)
    try:
        q = ctx.queue()
    except Blocked as exc:
        q = None
        cap.check(
            "2",
            "exactly one ACTIVE queue is discovered",
            "starexec.queues has exactly one ACTIVE row (the"
            " harness never assumes queue 1)",
            "one ACTIVE",
            rows,
            False,
        )
        raise
    cap.check(
        "2",
        "the submission queue is discovered by SQL",
        "starexec.queues has exactly one ACTIVE row, used as"
        " the queue for every job this run creates",
        "one ACTIVE",
        {"queues": rows, "chosen": q},
        True,
    )
    flags = ctx.db.rows(
        "SELECT paused, test_queue, read_only FROM starexec.system_flags;"
    )
    migrations = ctx.db.rows(
        "SELECT count(*), count(*) FILTER (WHERE NOT success), max(version) FROM starexec.flyway_schema_history;"
    )
    active = ctx.db.int(
        "SELECT count(*) FROM starexec.job_pairs WHERE status_code BETWEEN 1 AND 6;"
    )
    ctx.state.fact("system_flags", flags)
    cap.check(
        "3",
        "database state before the run",
        "no failed Flyway migration, the system is not paused or"
        " read-only, and no pair is pending/enqueued/running before the run starts",
        {"failed_migrations": "0", "paused": "f", "read_only": "f", "active_pairs": 0},
        {"migrations": migrations, "system_flags": flags, "active_pairs": active},
        bool(migrations)
        and migrations[0][1] == "0"
        and bool(flags)
        and flags[0][0] == "f"
        and flags[0][2] == "f"
        and active == 0,
    )


def _subspace(ctx, owner, label):
    personal = ctx.state.need("owner_personal_space")
    name = ctx.name(label)
    owner.post(
        ep.path("add_space"),
        data={
            "parent": personal,
            "name": name,
            "desc": "E2E test-job case",
            "locked": "false",
            "sticky": "false",
            "addBench": "on",
            "addSolver": "on",
            "addSpace": "on",
            "addJob": "on",
        },
    )
    sid = ctx.db.int(f"SELECT id FROM starexec.spaces WHERE name={literal(name)};")
    if not sid:
        raise Blocked(f"subspace {name} not created")
    return sid


def _profile(ctx, owner, label, pre, post, bench_processor, bench):
    uid = ctx.state.need("owner_user")
    name = ctx.name(label)
    # Default benchmark set the way the settings form does: bench[] on /secure/add/profile, which
    # AddSettingProfile.getBenchIds reads (:193-194) and adds with d.addBenchId (:168).
    owner.post(
        ep.path("add_profile"),
        data={
            "name": name,
            "userIdOfOwner": uid,
            "postp": post,
            "prep": pre,
            "benchp": bench_processor,
            "cpu": "10",
            "wall": "10",
            "dep": "true",
            "mem": "0.5",
            "benchmarkingFramework": "RUNSOLVER",
            "bench[]": bench,
        },
    )
    pid = ctx.db.int(
        f"SELECT id FROM starexec.default_settings WHERE name={literal(name)};"
    )
    if not pid:
        raise Blocked(f"settings profile {name} not created")
    row = ctx.db.rows(
        f"SELECT coalesce(pre_processor::text,'NULL'), coalesce(post_processor::text,'NULL') FROM"
        f" starexec.default_settings WHERE id={pid};"
    )[0]
    benches = [
        b
        for (b,) in ctx.db.rows(
            f"SELECT bench_id FROM starexec.default_bench_assoc WHERE setting_id={pid};"
        )
    ]
    return pid, {
        "pre_processor": row[0],
        "post_processor": row[1],
        "default_benchmarks": benches,
    }


def _upload_with_test_job(ctx, owner, space, settings, label):
    fx = need_fixture(ctx, "verbatim-szs.zip")
    name = ctx.name(label)
    t0 = utc_rfc3339()
    with open(fx["path"], "rb") as f:
        r = owner.post(
            ep.path("upload_solver"),
            timeout=180,
            data={
                "space": space,
                "sn": name,
                "desc": "E2E test job",
                "dlable": "true",
                "upMethod": "local",
                "descMethod": "text",
                "execType": "1",
                "runTestJob": "true",
                "settingId": settings,
            },
            files={"f": ("verbatim-szs.zip", f, "application/zip")},
        )
    uid = ctx.state.need("owner_user")
    solver = ctx.db.int(
        f"SELECT id FROM starexec.solvers WHERE name={literal(name)} ORDER BY id DESC LIMIT 1;"
    )
    # JobManager.setupJob names the test job after the solver (CreateJob.buildSolverTestJob).
    job = ctx.db.int(
        f"SELECT id FROM starexec.jobs WHERE user_id={uid} AND name={literal(name)} ORDER BY id DESC LIMIT 1;"
    )
    http = {
        "status": r.status_code,
        "redirects": [h.status_code for h in r.history],
        "final_path": r.url.split("?")[0].replace(ctx.cfg.base_url, ""),
        "final_query_keys": sorted(
            p.split("=")[0]
            for p in (r.url.split("?", 1)[1].split("&") if "?" in r.url else [])
        ),
    }
    return name, solver, job, http, t0


def _exception_excerpt(ctx, since, needle="Exception"):
    text = ctx.logs(since)
    if text is None:
        return None
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        if needle in line and (
            "ERROR" in line
            or "WARN" in line
            or line.lstrip().startswith(("java.", "Caused by"))
        ):
            block = [line[:300]]
            for nxt in lines[i + 1 : i + 40]:
                if nxt.lstrip().startswith("at ") or nxt.lstrip().startswith(
                    ("java.", "Caused by")
                ):
                    block.append(nxt.strip()[:200])
                elif block and len(block) > 1:
                    break
            out.append(block[:9])
    return out


def run_test_jobs(cap, ctx):
    owner = ctx.owner()
    uid, bench_processor, post, benches, community = ctx.state.need(
        "owner_user", "bench_processor", "post_processor", "benchmarks", "community"
    )
    bench = benches.get("AGT001+1.p") or next(iter(benches.values()))
    test_queue = ctx.db.int("SELECT test_queue FROM starexec.system_flags;")
    queues = dict(
        (int(i), s)
        for i, _, s in ctx.db.rows(
            "SELECT id, name, status FROM starexec.queues ORDER BY id;"
        )
    )
    created_jobs = []

    # ---------------- H1: no processors, one default benchmark
    space = _subspace(ctx, owner, "h1_space")
    pid, prof = _profile(ctx, owner, "h1_settings", "-1", "-1", bench_processor, bench)
    name, solver, job, http, t0 = _upload_with_test_job(
        ctx, owner, space, pid, "h1_solver"
    )
    samples = {}
    queue_id = (
        ctx.db.int(f"SELECT queue_id FROM starexec.jobs WHERE id={job};")
        if job
        else None
    )
    if job:
        created_jobs.append(job)
        start = time.monotonic()
        for at in SAMPLES_S:
            while time.monotonic() - start < at:
                time.sleep(2)
            samples[at] = sorted(pair_statuses(ctx, job).items())
    final = dict(samples.get(SAMPLES_S[-1], []))
    terminal = bool(final) and all(s in TERMINAL for s in final.values())
    on_active = queue_id is not None and queues.get(queue_id) == "ACTIVE"
    observed = {
        "profile": prof,
        "http": http,
        "solver": solver,
        "job": job,
        "jobs.queue_id": queue_id,
        "queue_status": queues.get(queue_id),
        "system_flags.test_queue": test_queue,
        "pair_statuses_at_s": samples,
    }
    if (
        job
        and queue_id == test_queue
        and queues.get(queue_id) != "ACTIVE"
        and not terminal
    ):
        verdict = "CONFIRMED"
    elif job and on_active and terminal:
        verdict = "REFUTED"
    else:
        verdict = "INCONCLUSIVE"
    cap.check(
        "H1",
        "test job for a solver uploaded with runTestJob runs to completion",
        "with a profile that has no pre/post processor and one default benchmark, uploading a solver with"
        " runTestJob=true creates a test job on an ACTIVE queue whose pairs are all terminal within 180 s."
        " Prediction (code reading): FAIL, because CreateJob.buildSolverTestJob uses Queues.getTestQueue()"
        " and JobManager schedules only Queues.getAllActive()",
        {"queue_status": "ACTIVE", "all_terminal_by_180s": True},
        observed,
        bool(job) and on_active and terminal,
        ["upload_solver", "upload_solver_params", "add_profile", "add_profile_params"],
        extra={"prediction": verdict},
    )

    # ---------------- H2a: post-processor set, no pre-processor
    space = _subspace(ctx, owner, "h2a_space")
    pid, prof = _profile(ctx, owner, "h2a_settings", "-1", post, bench_processor, bench)
    name, solver, job, http, t0 = _upload_with_test_job(
        ctx, owner, space, pid, "h2a_solver"
    )
    if job:
        created_jobs.append(job)
    time.sleep(3)
    excerpt = _exception_excerpt(ctx, t0, "NullPointerException")
    npe_in_test_job = bool(excerpt) and any(
        "buildSolverTestJob" in " ".join(b) for b in excerpt
    )
    observed = {
        "profile": prof,
        "http": http,
        "solver_row": solver,
        "jobs_row": job,
        "log_npe_blocks": excerpt if excerpt is not None else "app_logs not configured",
    }
    ok = bool(job) and bool(solver) and "job.jsp" in http["final_path"]
    if excerpt is None:
        verdict = "INCONCLUSIVE"
    elif npe_in_test_job and not job:
        verdict = "CONFIRMED"
    elif job and not npe_in_test_job:
        verdict = "REFUTED"
    else:
        verdict = "INCONCLUSIVE"
    cap.check(
        "H2a",
        "a post-processor-only profile creates the test job and reports the upload correctly",
        "with post=SZS and no pre-processor, the upload creates the solver and a test job and redirects to the"
        " job page. Prediction (code reading): FAIL, because CreateJob.java:113"
        " `(getPostProcessorId()==null) ? -1 : settings.getPreProcessorId()` unboxes a null Integer (NPE)",
        {"solver_row": True, "jobs_row": True, "redirect": "secure/details/job.jsp"},
        observed,
        ok,
        ["upload_solver", "upload_solver_params"],
        extra={"prediction": verdict},
    )

    # ---------------- H2b: pre and post processors both set
    pre_fx = need_fixture(ctx, "pre-processor.zip")
    pre_name = ctx.name("pre_processor")
    with open(pre_fx["path"], "rb") as f:
        ctx.admin().post(
            ep.path("processor_manager"),
            timeout=120,
            data={
                "action": "add",
                "name": pre_name,
                "desc": "E2E pre",
                "uploadMethod": "local",
                "com": community,
                "type": "pre",
            },
            files={"file": ("pre-processor.zip", f, "application/zip")},
        )
    pre = ctx.db.int(
        f"SELECT id FROM starexec.processors WHERE name={literal(pre_name)} AND processor_type=1;"
    )
    if not pre:
        cap.blocked(
            "H2b",
            "test job carries the profile's post-processor",
            "the PRE-type processor was not created",
        )
    else:
        space = _subspace(ctx, owner, "h2b_space")
        pid, prof = _profile(
            ctx, owner, "h2b_settings", pre, post, bench_processor, bench
        )
        name, solver, job, http, t0 = _upload_with_test_job(
            ctx, owner, space, pid, "h2b_solver"
        )
        params = (
            ctx.db.rows(
                f"SELECT stage_number, coalesce(pre_processor::text,'NULL'), coalesce(post_processor::text,'NULL')"
                f" FROM starexec.job_stage_params WHERE job_id={job or 0} ORDER BY 1;"
            )
            if job
            else []
        )
        if job:
            created_jobs.append(job)
        posts = {p for _, _, p in params}
        observed = {
            "profile": prof,
            "pre_id": pre,
            "post_id": post,
            "http": http,
            "job": job,
            "job_stage_params(stage,pre,post)": params,
        }
        ok = bool(params) and posts == {str(post)}
        if not params:
            verdict = "INCONCLUSIVE"
        elif posts == {str(pre)}:
            verdict = "CONFIRMED"
        elif posts == {str(post)}:
            verdict = "REFUTED"
        else:
            verdict = "INCONCLUSIVE"
        cap.check(
            "H2b",
            "the test job carries the profile's post-processor",
            "with pre=<PRE processor> and post=SZS, job_stage_params.post_processor for the test job equals the"
            " profile's post-processor id (jobs has no processor columns; checked with \\d starexec.jobs)."
            " Prediction (code reading): FAIL, post = pre id",
            str(post),
            observed,
            ok,
            ["upload_solver", "processor_manager", "add_profile"],
            extra={"prediction": verdict},
        )

    # Leave the stack without stranded test jobs on an inactive queue.
    for job in created_jobs:
        owner.post(ep.path("delete_job"), data={"selectedIds[]": job})
    ctx.run.recorder.note(f"deleted test jobs {created_jobs} after recording")


LOG_PATTERNS = {
    "created_java_client": r"Container created via Java client",
    "creation_failed": r"Container creation failed\. Transient error: \w+, Exception: \S+",
    "fallback_to_curl": r"falling back to curl",
    "created_curl": r"Container created via curl",
}


def run_container_logs(cap, ctx):
    since = ctx.run.data["started_at"][:19] + "Z"
    text = ctx.logs(since)
    if text is None:
        cap.blocked(
            "1", "container creation logging", "no app_logs template configured"
        )
        return
    # #202: saving job statistics failed on every request ("no unique or exclusion constraint matching the ON
    # CONFLICT specification"). Checked on every backend, before the Podman-only container lines.
    stat_errors = [
        re.sub(r"^\S+ \S+ \[[^\]]+\] ", "", l)[:240]
        for l in text.splitlines()
        if re.search(
            r"save a single stat|saving stats failed|ON CONFLICT specification", l
        )
    ]
    cap.check(
        "stats",
        "no job-statistics save error in the app log (#202)",
        "since the run started the app log has no 'caught an exception while trying to save a single stat',"
        " 'saving stats failed' or ON CONFLICT specification error",
        0,
        {"count": len(stat_errors), "samples": stat_errors[:3]},
        not stat_errors,
    )
    if ctx.cfg.backend != "podman":
        return
    # Only these INFO/WARN lines are kept; the DEBUG container-creation JSON carries the job environment.
    counts, samples = {}, {}
    for key, pat in LOG_PATTERNS.items():
        hits = [l for l in text.splitlines() if re.search(pat, l) and "DEBUG" not in l]
        counts[key] = len(hits)
        samples[key] = [re.sub(r"^\S+ \S+ \[[^\]]+\] ", "", h)[:240] for h in hits[:3]]
    created = counts["created_java_client"] + counts["created_curl"]
    cap.check(
        "1",
        "every job container was created",
        "since the run started, each fallback to curl is followed by a"
        " 'Container created via curl' line (no creation ends without a container)",
        {
            "fallback_to_curl": counts["fallback_to_curl"],
            "created_curl": counts["fallback_to_curl"],
        },
        {"counts": counts, "samples": samples},
        counts["created_curl"] >= counts["fallback_to_curl"],
    )
    cause_logged = all(
        len(s.split("Exception: ", 1)[-1].split()) > 1
        for s in samples["creation_failed"]
    )
    passed = cap.check(
        "2",
        "a creation failure logs its cause, not only the class name",
        "each 'Container creation failed' WARN carries the exception message or cause beyond the class"
        " name (PodmanBackend logs e.getClass().getName() only)",
        "message or cause present",
        {
            "creation_failed": counts["creation_failed"],
            "samples": samples["creation_failed"],
            "java_client_created": counts["created_java_client"],
            "containers_created": created,
        },
        counts["creation_failed"] == 0 or cause_logged,
    )
    if counts["creation_failed"] and not cause_logged:
        cap.finding(
            "podman-fallback-cause",
            "container creation failure logs only the exception class",
            f"{counts['creation_failed']} creation failures fell back to curl; each WARN names only the class"
            " (PodmanBackend.java:1504-1509), so the transient cause is not diagnosable from the log.",
        )
