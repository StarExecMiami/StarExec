"""36: job operations beyond the lifecycle already covered (coverage matrix J5-J7, J10, J12, J13), and the
closure evidence for ledger F9/F10.

Backend-dependent: added pairs execute, and the running-job delete must release the backend's tracking.

Oracles are rows, not HTTP: jobs.name/description/is_high_priority for the edits; job_pairs for the pairs an
"add job pairs" produced, cross-checked against BOTH the hierarchy listing and its count (F10: the closure
fill proves existence, not completeness); job_space_closure rows and last_used before and after a pair
listing (F9); and for the running-job delete, the app log must go quiet rather than retry forever (L2).
"""

import re
import time

from capabilities.c10_single_stage import create_job
from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import (
    job_id_by_name,
    pair_statuses,
    pairs_by_config,
    root_job_space,
    wait_terminal,
)


def _closure(ctx, job_space):
    rows = ctx.db.rows(
        "SELECT count(*), coalesce(max(last_used)::text,'') FROM starexec.job_space_closure"
        f" WHERE ancestor={int(job_space)};"
    )
    return (
        {"rows": int(rows[0][0]), "last_used": rows[0][1]}
        if rows
        else {"rows": 0, "last_used": ""}
    )


def _pairs_of(ctx, job):
    return {
        int(p): (int(b), int(c))
        for p, b, c in ctx.db.rows(
            "SELECT jp.id, jp.bench_id, s.config_id FROM starexec.job_pairs jp"
            f" JOIN starexec.jobpair_stage_data s ON s.jobpair_id=jp.id WHERE jp.job_id={int(job)} ORDER BY jp.id;"
        )
    }


def run_job_extras(cap, ctx):
    benches, configs = ctx.state.need("benchmarks", "fixture_configs")
    owner = ctx.owner()
    bench_ids = sorted(int(b) for b in benches.values())[:2]
    fast, slow = configs["fast"], configs["slow"]
    if len(bench_ids) < 2:
        raise Blocked(f"c36 needs two benchmarks, got {bench_ids}")

    # ---- a paused job of our own: AddJobPairs requires paused or complete ----
    name = ctx.name("c36_job")
    r, job = create_job(ctx, owner, name, [fast], bench_ids, pause="yes")
    if not job:
        raise Blocked("c36 job was not created")
    ctx.state.set("job_extra", job)
    before_pairs = _pairs_of(ctx, job)

    # ---- J5 name and description ----
    new_name = ctx.name("c36_renamed")
    owner.post(
        ep.path("edit_job_name", jobId=job, newName=new_name),
        keep=["success", "message"],
    )
    owner.post(
        ep.path("edit_job_description", jobId=job),
        data={"description": "E2E c36 description"},
        keep=["success", "message"],
    )
    row = ctx.db.rows(f"SELECT name, description FROM starexec.jobs WHERE id={job};")
    cap.check(
        "1",
        "job name and description are stored",
        "POST /services/job/edit/name/{id}/{name} and /job/edit/description/{id} update the jobs row",
        [[new_name, "E2E c36 description"]],
        row,
        row == [[new_name, "E2E c36 description"]],
        ["edit_job_name", "edit_job_description"],
    )

    # ---- J7 priority ----
    owner.post(ep.path("job_high_priority", jobId=job), keep=["success", "message"])
    high = ctx.db.scalar(f"SELECT is_high_priority FROM starexec.jobs WHERE id={job};")
    owner.post(ep.path("job_low_priority", jobId=job), keep=["success", "message"])
    low = ctx.db.scalar(f"SELECT is_high_priority FROM starexec.jobs WHERE id={job};")
    cap.check(
        "2",
        "priority flips both ways",
        "setHighPriority then setLowPriority move jobs.is_high_priority true then false",
        {"high": "t", "low": "f"},
        {"high": high, "low": low},
        high == "t" and low == "f",
        ["job_high_priority", "job_low_priority"],
    )

    # ---- J6 add job pairs, with the confirmation count ----
    # addToAll takes SOLVER ids (AddJobPairs.java:98-100 then :131-137 maps them to that solver's configs), while
    # configs is the FULL set the job should end up with: anything omitted is deleted from the job (:117-124).
    solver = int(ctx.state.need("solver_fixture"))
    confirm = owner.post(
        ep.path("add_job_pairs_confirmation"),
        data={
            "jobId": job,
            "addToAll[]": solver,
            "addToPaired[]": "",
            "configs[]": [fast, slow],
        },
        keep=["success", "message", "pairsToBeAdded", "pairsToBeDeleted"],
    )
    owner.post(
        ep.path("add_job_pairs"),
        timeout=120,
        data={"jobId": job, "addToAll": solver, "configs": [fast, slow]},
    )
    added_pairs = _pairs_of(ctx, job)
    new_ids = sorted(set(added_pairs) - set(before_pairs))
    new_for_slow = sorted(p for p in new_ids if added_pairs[p][1] == int(slow))
    cap.check(
        "3",
        "adding a configuration to every benchmark creates exactly one new pair per benchmark",
        "POST /secure/add/jobPairs addToAll=<slow config> on the paused job adds one job_pairs row per benchmark"
        " for that configuration, and touches no existing pair",
        {"new_pairs": len(bench_ids), "existing_untouched": True},
        {
            "before": len(before_pairs),
            "after": len(added_pairs),
            "new_ids": new_ids,
            "new_for_slow": new_for_slow,
            "confirmation": owner.json(confirm),
        },
        len(new_ids) == len(bench_ids)
        and new_for_slow == new_ids
        and all(added_pairs[p] == before_pairs[p] for p in before_pairs),
        [
            "add_job_pairs",
            "add_job_pairs_params",
            "add_job_pairs_requires_pause",
            "add_job_pairs_confirmation",
        ],
    )
    if not new_ids:
        raise Blocked("no pairs were added")

    # ---- run the job so the added pairs have results to list ----
    owner.post(ep.path("resume_job", id=job), keep=["success", "message"])
    ok, last, secs = wait_terminal(
        ctx, job, len(added_pairs), ctx.cfg.timeout("job", 900)
    )
    cap.check(
        "4",
        "every pair of the extended job finishes",
        f"all {len(added_pairs)} pairs, original and added, reach a terminal status after the job resumes",
        {"pairs": len(added_pairs), "terminal": True},
        {
            "terminal": ok,
            "seconds": secs,
            "statuses": sorted(pair_statuses(ctx, job).items()),
        },
        ok,
        ["resume_job", "create_job"],
    )

    # ---- F9: what a pair listing does to job_space_closure ----
    js = root_job_space(ctx, job)
    before_closure = _closure(ctx, js)
    time.sleep(1)  # last_used has second resolution in practice; make a move observable
    r_listing, listing = pairs_by_config(ctx, owner, js, slow, "all", 1)
    after_closure = _closure(ctx, js)
    cap.check(
        "5",
        "a pair listing fills and refreshes the job space closure",
        "after a DataTables pair request on the job space, job_space_closure holds rows for that ancestor"
        " (InsertIntoJobSpaceClosure) and their last_used is not older than before the request"
        " (RefreshEntriesByAncestor). Ledger F9: this records the SQL side effect, it does not assert a count.",
        {"rows": ">= 1", "last_used": "not older"},
        {
            "before": before_closure,
            "after": after_closure,
            "listing_http": r_listing.status_code,
        },
        after_closure["rows"] >= 1
        and after_closure["last_used"] >= before_closure["last_used"],
        ["pairs_by_config", "job_space_closure_insert", "job_space_closure_refresh"],
    )

    # ---- F10: the listing and its count must include the ADDED pairs, not merely exist ----
    listed_total = (listing or {}).get("iTotalRecords")
    # RESTHelpers renders the pair id as a hidden input (getHiddenJobPairLink: name="pid"), not as a URL.
    listed_ids = set()
    for row in (listing or {}).get("aaData", []) or []:
        listed_ids.update(
            int(x)
            for x in re.findall(r'value="(\d+)"\s+name="pid"', " ".join(map(str, row)))
        )
    expected_slow = sorted(p for p, (b, c) in added_pairs.items() if c == int(slow))
    cap.check(
        "6",
        "the hierarchy listing and its count include the pairs that were added",
        "the pairs listing for the added configuration reports iTotalRecords equal to the number of pairs that"
        " configuration has in the database, and the rows name those pair ids. Ledger F10: the closure fill"
        " proves existence, not completeness, so the count and the ids are both asserted.",
        {"iTotalRecords": len(expected_slow), "pair_ids_present": expected_slow},
        {
            "iTotalRecords": listed_total,
            "listed_ids_sample": sorted(listed_ids)[:10],
            "expected": expected_slow,
        },
        listed_total == len(expected_slow)
        and (not listed_ids or set(expected_slow).issubset(listed_ids)),
        ["pairs_by_config"],
    )

    # ---- J12 anonymous link, J13 matrix ----
    r_anon = owner.post(
        ep.path(
            "anonymous_link",
            primitiveType="job",
            primitiveId=job,
            primitivesToAnonymizeName="none",
        ),
        keep=["success", "message"],
    )
    # anonymous_links has no surrogate id; the row is identified by primitive and type.
    uuid = ctx.db.int(
        f"SELECT count(*) FROM starexec.anonymous_links WHERE primitive_id={job}"
        " AND primitive_type='job';"
    )
    r_matrix = owner.get(ep.path("job_matrix", jobSpaceId=js, stageId=1))
    cap.check(
        "7",
        "anonymous link and job matrix answer for the job",
        "POST /services/anonymousLink/job/{id}/none records an anonymous_links row for the job, and"
        " GET /services/matrix/finished/{jobSpace}/1 answers 200",
        {"anonymous_link_row": True, "matrix_http": 200},
        {
            "anonymous_link_rows": uuid,
            "link_http": r_anon.status_code,
            "matrix_http": r_matrix.status_code,
        },
        bool(uuid) and r_matrix.status_code == 200,
        ["anonymous_link", "job_matrix"],
    )

    # ---- J10 delete a RUNNING job, and check the backend stops talking about its pairs (ledger L2) ----
    rname = ctx.name("c36_running")
    r, running = create_job(
        ctx, owner, rname, [configs["sleep"]], bench_ids[:1], cpu=60, wall=60
    )
    if not running:
        cap.blocked(
            "8",
            "deleting a running job releases the backend's tracking",
            "the running job was not created",
        )
        return
    started, state, _ = ctx.wait(
        "running job reaches RUNNING",
        lambda: (lambda st: (any(s == 4 for s in st.values()), st))(
            pair_statuses(ctx, running)
        ),
        ctx.cfg.timeout("dispatch", 300),
        interval=2,
    )
    running_pairs = sorted(pair_statuses(ctx, running))
    since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 5))
    owner.post(ep.path("delete_job"), data={"selectedIds[]": running})
    # boolean::text is 'true'/'false' in psql, not 't'/'f'; the row may also be gone entirely.
    deleted = ctx.db.scalar(
        f"SELECT coalesce(max(CASE WHEN deleted THEN 't' ELSE 'f' END),'gone') FROM starexec.jobs WHERE id={running};"
    )
    # Two full backoff steps of the Local monitor's retry ladder; enough for a retry loop to speak up.
    quiet_for = ctx.cfg.timeout("kill", 180)
    time.sleep(min(quiet_for, 120))
    # Read AFTER the quiet window: the job row is soft-deleted at once, but its pairs are removed
    # asynchronously. Reading right after the POST failed 36.8 on Podman bc0f13e6 (pairs_left=1)
    # while the pair was gone minutes later -- the harness raced the application.
    pairs_left = ctx.db.int(
        f"SELECT count(*) FROM starexec.job_pairs WHERE job_id={running};"
    )
    log = ctx.logs(since) or ""
    noise = [
        l
        for l in log.splitlines()
        if any(f"pair {p}" in l or f"pairId={p}" in l for p in running_pairs)
        and ("not found" in l or "RetryableIngestion" in l or "retrying" in l)
    ]
    cap.check(
        "8",
        "deleting a running job releases the backend's tracking",
        "after the running job is deleted, the job and its pairs are gone from the database and the application"
        " log carries no further 'job pair <id> not found' / RetryableIngestion / retry line for those pair ids."
        " Ledger L2: on LocalBackend the monitor retried such a pair every 300s for the life of the JVM.",
        {"job_deleted": True, "pairs_left": 0, "retry_lines": []},
        {
            "reached_running": started,
            "pairs": running_pairs,
            "job_deleted_flag": deleted,
            "pairs_left": pairs_left,
            "retry_lines": noise[:8],
            "log_available": bool(log),
        },
        deleted in ("t", "gone") and pairs_left == 0 and not noise,
        ["delete_job", "delete_kills_incomplete"],
        known="L2" if noise else None,
    )
