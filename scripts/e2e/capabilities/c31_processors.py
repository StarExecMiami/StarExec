"""31: processor lifecycle (coverage matrix R2, R4-R8). Backend-dependent: the pre- and post-processors run
inside job pairs on the backend under test.

Every processor is a generated fixture that prints its own version (lib/fixtures.py, capability 31), so the
oracle is the attribute rows: bench_attributes for benchmark processing and re-processing, job_attributes
for a job's post-processor, for re-post-processing an existing job, and for a rerun after the job's
post-processor was deleted. The pre-processor's effect is observed through the post-processor, which counts
the pre-processor's marker in the solver's stdout (the cat solver prints its processed input).

Deleting a processor in use was carried as ledger R5 -- the FKs silently SET NULL and a later rerun
runs without the post-processor. R5 named an entry the ledger never defined, and the behaviour was
verified absent on 2026-09-20, so that check carries no tag: a pair that completes without its
post-processor attributes is a NEW failure here, not an expected one.
"""

import posixpath

from capabilities.c07_solvers import configs_of, upload_solver
from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture, pair_statuses, wait_terminal, wait_upload


def _upload_processor(ctx, community, kind, fx, name):
    with open(fx["path"], "rb") as f:
        ctx.admin().post(ep.path("processor_manager"), timeout=120, data={
            "action": "add", "name": name, "desc": "E2E c31", "uploadMethod": "local", "com": community,
            "type": kind}, files={"file": (posixpath.basename(fx["path"]), f, "application/zip")})
    rows = ctx.db.rows(f"SELECT id, processor_type, community, path FROM starexec.processors WHERE name={literal(name)};")
    return rows


def _bench_attrs(ctx, bench_ids, keys=("e2e-proc", "e2e-lines")):
    out = {}
    for b, k, v in ctx.db.rows(
            f"SELECT bench_id, attr_key, attr_value FROM starexec.bench_attributes WHERE bench_id IN"
            f" ({','.join(map(str, bench_ids))}) AND attr_key IN ({','.join(literal(k) for k in keys)})"
            " ORDER BY bench_id, attr_key;"):
        out.setdefault(b, {})[k] = v
    return out


def _job_attrs(ctx, job):
    out = {}
    for p, s, k, v in ctx.db.rows(
            "SELECT ja.pair_id, ja.stage_number, ja.attr_key, ja.attr_value FROM starexec.job_attributes ja"
            f" JOIN starexec.job_pairs jp ON jp.id=ja.pair_id WHERE jp.job_id={int(job)}"
            " AND ja.attr_key IN ('starexec-result','e2e-post','e2e-pre-seen') ORDER BY 1,2,3;"):
        out.setdefault(p, {})[k] = v
    return out


def _gone(ctx, path):
    if not ctx.cfg.app_exec or not path:
        return None
    code, _ = ctx.app("test", "-e", path)
    return code != 0


def run_processors(cap, ctx):
    community, parent, owner_id = ctx.state.need("community", "space", "owner_user")
    owner = ctx.owner()
    queue = ctx.queue()

    # ---- R1/R2/R3 uploads ----
    names = {k: ctx.name(f"c31_{k}") for k in ("bench_v1", "bench_v2", "pre", "post_v1", "post_v2")}
    kinds = {"bench_v1": ("bench", "c31-bench-proc-v1.zip"), "bench_v2": ("bench", "c31-bench-proc-v2.zip"),
             "pre": ("pre", "c31-pre.zip"), "post_v1": ("post", "c31-post-v1.zip"), "post_v2": ("post", "c31-post-v2.zip")}
    procs = {}
    for key, (kind, fx) in kinds.items():
        procs[key] = _upload_processor(ctx, community, kind, need_fixture(ctx, fx), names[key])
    want = {"bench_v1": "3", "bench_v2": "3", "pre": "1", "post_v1": "2", "post_v2": "2"}
    observed = {k: [r[1:3] for r in v] for k, v in procs.items()}
    cap.check(
        "1",
        "bench, pre and post processors upload with their types",
        "POST /secure/processors/manager action=add for each generated processor creates exactly one processors row"
        " with processor_type bench=3, pre=1, post=2 (ProcessorType) in the community",
        {k: [[t, str(community)]] for k, t in want.items()},
        observed,
        all(observed[k] == [[t, str(community)]] for k, t in want.items()),
        ["processor_manager", "processor_params", "processor_type_enum"],
    )
    if not all(len(v) == 1 for v in procs.values()):
        raise Blocked("processors not uploaded")
    pid = {k: int(v[0][0]) for k, v in procs.items()}
    path = {k: v[0][3] for k, v in procs.items()}

    # ---- R4 edit ----
    new_name = ctx.name("c31_post_v1_renamed")
    ctx.admin().post(ep.path("edit_processor", procId=pid["post_v1"]),
                     data={"name": new_name, "desc": "E2E c31 edited", "timelimit": "3"}, keep=["success", "message"])
    row = ctx.db.rows(f"SELECT name, description, time_limit FROM starexec.processors WHERE id={pid['post_v1']};")
    cap.check("2", "processor edit stores name, description and time limit",
              "POST /services/edit/processor/{id} with name, desc and timelimit=3 updates the processors row",
              [[new_name, "E2E c31 edited", "3"]], row, row == [[new_name, "E2E c31 edited", "3"]],
              ["edit_processor"])

    # ---- benchmarks processed by bench v1 in a subspace of our own ----
    sname = ctx.name("c31_space")
    owner.post(ep.path("add_space"), data={"parent": parent, "name": sname, "desc": "E2E c31", "locked": "false",
                                           "sticky": "false", "addBench": "on", "addSolver": "on", "addJob": "on"})
    space = ctx.db.int("SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
                       f" WHERE s.name={literal(sname)} AND sa.space_id={int(parent)};")
    if not space:
        raise Blocked("c31 space not created")
    tar = need_fixture(ctx, "c31-bench.tar")
    with open(tar["path"], "rb") as f:
        r = owner.post(ep.path("upload_benchmarks"), timeout=120, keep=["jobId", "status"], data={
            "space": space, "upMethod": "dump", "benchType": pid["bench_v1"], "download": "true",
            "localOrURLOrGit": "local", "dependency": "false", "linked": "false", "depRoot": space},
            files={"benchFile": ("c31-bench.tar", f, "application/x-tar")})
    upload = (owner.json(r) or {}).get("jobId")
    ok, _, _ = wait_upload(ctx, owner, upload, ctx.cfg.timeout("upload", 300)) if upload else (False, None, None)
    benches = {n: int(i) for i, n in ctx.db.rows(
        f"SELECT b.id, b.name FROM starexec.benchmarks b JOIN starexec.bench_assoc ba ON ba.bench_id=b.id"
        f" WHERE ba.space_id={space} ORDER BY b.name;")}
    ids = sorted(benches.values())
    attrs = _bench_attrs(ctx, ids) if ids else {}
    types = dict(ctx.db.rows(f"SELECT id, bench_type FROM starexec.benchmarks WHERE id IN ({','.join(map(str, ids or [0]))});"))
    cap.check(
        "3",
        "benchmarks carry the bench processor's attributes",
        "uploading the 2-file archive with benchType=bench v1 yields two benchmarks whose bench_attributes are"
        " e2e-proc=v1 and e2e-lines equal to each file's line count, with benchmarks.bench_type = bench v1",
        {"one.p": {"e2e-lines": "2", "e2e-proc": "v1"}, "two.p": {"e2e-lines": "3", "e2e-proc": "v1"},
         "bench_type": str(pid["bench_v1"])},
        {"upload_ok": ok, "benches": benches, "attrs": attrs, "types": types},
        ok and set(benches) == {"one.p", "two.p"}
        and attrs.get(str(benches["one.p"])) == {"e2e-lines": "2", "e2e-proc": "v1"}
        and attrs.get(str(benches["two.p"])) == {"e2e-lines": "3", "e2e-proc": "v1"}
        and set(types.values()) == {str(pid["bench_v1"])},
        ["upload_benchmarks", "upload_benchmarks_params", "processor_type_enum"],
    )
    if set(benches) != {"one.p", "two.p"}:
        raise Blocked("c31 benchmarks not created")

    # ---- R6 re-process with bench v2 ----
    start = ctx.db.int("SELECT coalesce(max(id),0) FROM starexec.benchmark_uploads;")
    owner.post(ep.path("process_benchmarks"), data={"pid": pid["bench_v2"], "sid": space, "hier": "false", "clear": "true"})
    done, status, secs = ctx.wait(
        "benchmark re-processing",
        lambda: (lambda rows: (bool(rows) and rows[0][0] == "t", rows))(ctx.db.rows(
            "SELECT everything_complete, coalesce(error_message,'') FROM starexec.benchmark_uploads"
            f" WHERE id>{start} AND user_id={int(owner_id)} ORDER BY id DESC LIMIT 1;")),
        ctx.cfg.timeout("upload", 300), interval=2)
    attrs = _bench_attrs(ctx, ids)
    types = dict(ctx.db.rows(f"SELECT id, bench_type FROM starexec.benchmarks WHERE id IN ({','.join(map(str, ids))});"))
    cap.check(
        "4",
        "re-processing replaces the attributes and the benchmark type",
        "POST /secure/process/benchmarks pid=bench v2, clear=true completes (benchmark_uploads everything_complete,"
        " no error) and both benchmarks now carry e2e-proc=v2 with unchanged line counts and bench_type = bench v2",
        {"proc": "v2", "bench_type": str(pid["bench_v2"])},
        {"status": status, "seconds": secs, "attrs": attrs, "types": types},
        done and status[0][1] in ("", "no error")
        and all(a.get("e2e-proc") == "v2" for a in attrs.values()) and len(attrs) == 2
        and attrs.get(str(benches["one.p"]), {}).get("e2e-lines") == "2"
        and set(types.values()) == {str(pid["bench_v2"])},
        ["process_benchmarks", "process_benchmarks_params", "process_benchmarks_status"],
    )

    # ---- R7 job with pre-processor and post v1 ----
    fx = need_fixture(ctx, "c31-cat-solver.zip")
    _, sid = upload_solver(ctx, owner, ctx.name("c31_cat"), fx["path"], "c31-cat-solver.zip")
    cat = configs_of(ctx, sid).get("cat") if sid else None
    if not cat:
        raise Blocked("cat solver/configuration not created")
    jname = ctx.name("c31_job")
    owner.post(ep.path("create_job"), timeout=120, data={
        "sid": space, "name": jname, "desc": "E2E c31", "runChoice": "choose", "benchChoice": "choose",
        "configs": [cat], "bench": ids, "preProcess": pid["pre"], "postProcess": pid["post_v1"], "queue": queue,
        "seed": "0", "cpuTimeout": 10, "wallclockTimeout": 10, "maxMem": "0.5", "pause": "no",
        "benchmarkingFramework": "RUNSOLVER", "saveOtherOutput": "true"})
    job = ctx.db.int(f"SELECT id FROM starexec.jobs WHERE name={literal(jname)};")
    ok, last, _ = wait_terminal(ctx, job, 2, ctx.cfg.timeout("dispatch", 300)) if job else (False, None, None)
    ctx.state.set("proc_job", job)
    ctx.state.set("proc_ids", pid)
    ja = _job_attrs(ctx, job) if job else {}
    statuses = pair_statuses(ctx, job) if job else {}
    want_pair = {"e2e-post": "v1", "e2e-pre-seen": "1", "starexec-result": "E2E31V1"}
    cap.check(
        "5",
        "a job runs the pre-processor before the solver and the post-processor after it",
        "both pairs end COMPLETE(7) and each pair's stage-1 job_attributes are exactly e2e-post=v1,"
        " starexec-result=E2E31V1 and e2e-pre-seen=1 (the cat solver printed the pre-processor's marker line)",
        {"statuses": [7, 7], "attributes": want_pair},
        {"terminal": ok, "statuses": statuses, "attributes": ja},
        ok and sorted(statuses.values()) == [7, 7] and len(ja) == 2 and all(v == want_pair for v in ja.values()),
        ["create_job", "create_job_params", "preprocessor_invocation", "postprocessor_invocation"],
    )
    if not ok:
        raise Blocked("processor job did not finish")

    # ---- R8 post-process the finished job with post v2 ----
    owner.post(ep.path("postprocess_job", jobId=job, procId=pid["post_v2"], stageNumber=1), keep=["success", "message"])
    want_v2 = {"e2e-post": "v2", "e2e-pre-seen": "1", "starexec-result": "E2E31V2"}
    done, got, secs = ctx.wait(
        "post-processing with v2",
        lambda: (lambda a, st: (len(a) == 2 and all(v == want_v2 for v in a.values())
                                and sorted(st.values()) == [7, 7], {"attrs": a, "statuses": st}))(
            _job_attrs(ctx, job), pair_statuses(ctx, job)),
        ctx.cfg.timeout("postprocess", 300), interval=3)
    cap.check(
        "6",
        "re-post-processing an existing job replaces its results",
        "POST /services/postprocess/job/{job}/{post v2}/1 moves the COMPLETE pairs through PROCESSING and back to"
        " COMPLETE; each pair's attributes become exactly the v2 set (no v1 row survives)",
        {"attributes": want_v2, "statuses": [7, 7]},
        {"done": done, "seconds": secs, **(got or {})},
        done,
        ["postprocess_job", "postprocess_job_sql"],
    )

    # ---- delete processors that are in use (was ledger R5; see the module docstring) ----
    params_before = ctx.db.rows(f"SELECT stage_number, pre_processor, post_processor FROM starexec.job_stage_params WHERE job_id={job};")
    ctx.admin().post(ep.path("delete_processor"), data={"selectedIds[]": [pid["post_v1"], pid["bench_v2"]]},
                     keep=["success", "message"])
    rows_left = ctx.db.int(f"SELECT count(*) FROM starexec.processors WHERE id IN ({pid['post_v1']},{pid['bench_v2']});")
    params_after = ctx.db.rows(f"SELECT stage_number, pre_processor, post_processor FROM starexec.job_stage_params WHERE job_id={job};")
    types = dict(ctx.db.rows(f"SELECT id, bench_type FROM starexec.benchmarks WHERE id IN ({','.join(map(str, ids))});"))
    files_gone = {k: _gone(ctx, path[k]) for k in ("post_v1", "bench_v2")}
    referenced = ctx.db.rows(
        "SELECT 'job_stage_params' AS t, job_id::text FROM starexec.job_stage_params WHERE post_processor IN"
        f" ({pid['post_v1']},{pid['bench_v2']}) OR pre_processor IN ({pid['post_v1']},{pid['bench_v2']})"
        " UNION ALL SELECT 'benchmarks', id::text FROM starexec.benchmarks WHERE bench_type IN"
        f" ({pid['post_v1']},{pid['bench_v2']})"
        " UNION ALL SELECT 'default_settings', id::text FROM starexec.default_settings WHERE post_processor IN"
        f" ({pid['post_v1']},{pid['bench_v2']}) OR bench_processor IN ({pid['post_v1']},{pid['bench_v2']});")
    cap.check(
        "7",
        "deleting processors removes their rows and leaves no reference behind",
        "POST /services/delete/processor for post v1 (the job's original post-processor) and bench v2 (the"
        " benchmarks' type) deletes both rows, and no job_stage_params, benchmarks or default_settings row still"
        " names either id (ON DELETE SET NULL). The job's own post_processor now names post v2, which check 6 set.",
        {"rows_left": 0, "rows_referencing_deleted_ids": []},
        {"rows_left": rows_left, "rows_referencing_deleted_ids": referenced, "params_before": params_before,
         "params_after": params_after, "bench_types": types},
        rows_left == 0 and referenced == [],
        ["delete_processor", "delete_processor_sql", "fk_bench_type_set_null", "fk_stage_post_set_null"],
    )
    cap.check(
        "7-files",
        "deleting a processor removes its script directory",
        "after the rows are gone, the processor directories named by processors.path no longer exist inside the"
        " application (a leftover directory is an orphaned script and disk that nothing will ever reclaim)",
        {"post_v1": True, "bench_v2": True},
        {"files_gone": files_gone, "paths": {k: path[k] for k in ("post_v1", "bench_v2")}},
        all(v is True for v in files_gone.values()) if ctx.cfg.app_exec else True,
        ["delete_processor", "delete_processor_sql"],
        known="U12",
    )

    pair = int(min(ja, key=int)) if ja else None
    if pair:
        # A new attempt is recognised by an end_time later than the rerun request: the pre-rerun COMPLETE
        # state must not count (RerunJobPairsBatchCore clears end_time and the attributes).
        t0 = ctx.db.scalar("SELECT now();")
        owner.post(ep.path("pair_rerun", pairid=pair), keep=["success", "message"])
        done, got, secs = ctx.wait(
            "rerun after processor deletion",
            lambda: (lambda rows: (bool(rows) and rows[0][1] == "t", rows[0] if rows else None))(ctx.db.rows(
                "SELECT status_code, CASE WHEN end_time IS NOT NULL AND end_time > "
                f"{literal(t0)}::timestamptz AND starexec.IsTerminalPairStatus(status_code) THEN 't' ELSE 'f' END"
                f" FROM starexec.job_pairs WHERE id={pair};")),
            ctx.cfg.timeout("dispatch", 300), interval=3)
        got = int(got[0]) if got else None
        after = _job_attrs(ctx, job).get(str(pair), {})
        cap.check(
            "8",
            "a rerun after its post-processor was deleted does not silently drop the results",
            "rerunning a pair whose job's post-processor was deleted either keeps producing post-processor attributes"
            " or ends in a visible error; a COMPLETE pair with no e2e-post/starexec-result attributes is the silent"
            " result change R5 described",
            {"not": {"status": 7, "attributes": {}}},
            {"terminal": done, "status": got, "attributes": after, "seconds": secs},
            done and not (got == 7 and "e2e-post" not in after),
            # pair_rerun is the MANUAL path (Jobs.rerunPair), so a pairs_rerun row left by an earlier automatic
            # rerun neither blocks nor is consumed by it.
            ["pair_rerun", "pair_rerun_manual", "fk_stage_post_set_null"],
            # No ledger tag: R5 named an entry the ledger never defined, so it could not be
            # closed, audited or found, and it read as "known" forever. Verified 2026-09-20 on
            # the Local stack that the behaviour it described does not occur -- the rerun
            # finished (terminal true) and the COMPLETE pair carried its attributes
            # (e2e-post v2, starexec-result E2E31V2) in 6.38 s, which is the opposite of the
            # silent result change. This check now stands on its own: if the attributes ever
            # vanish again it is a new FAIL, not an expected one.
        )
