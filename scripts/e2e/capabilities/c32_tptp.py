"""32: TPTP problems and axioms (coverage matrix A1, A3, A5, A7, A9).

Backend-dependent through A7 only: the last check runs a job on a problem whose axiom was deleted.

Oracles: the fixtures manifest (each axiom's and problem's sha256 and its include() list), the benchmarks /
bench_assoc / set_assoc rows for convert vs dump, bench_dependency rows for the include closure, and the
bytes the download endpoint serves. Deleting an axiom other problems depend on is ledger A7.
"""

import hashlib
import io
import posixpath
import zipfile

from capabilities.c07_solvers import configs_of, upload_solver
from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture, pair_statuses, wait_terminal, wait_upload


def _space(ctx, client, parent, suffix):
    name = ctx.name(suffix)
    client.post(ep.path("add_space"), data={"parent": parent, "name": name, "desc": "E2E c32", "locked": "false",
                                            "sticky": "false", "addBench": "on", "addSolver": "on", "addJob": "on"})
    return ctx.db.int("SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
                      f" WHERE s.name={literal(name)} AND sa.space_id={int(parent)};")


def _upload(ctx, client, space, fixture, method, processor, dependency=False, dep_root=None):
    with open(fixture["path"], "rb") as f:
        r = client.post(ep.path("upload_benchmarks"), timeout=180, keep=["jobId", "status"], data={
            "space": space, "upMethod": method, "benchType": processor, "download": "true",
            "localOrURLOrGit": "local", "dependency": str(dependency).lower(), "linked": "false",
            "depRoot": dep_root if dep_root else space},
            files={"benchFile": (posixpath.basename(fixture["path"]), f, "application/x-tar")})
    job = (client.json(r) or {}).get("jobId")
    ok, state, secs = wait_upload(ctx, client, job, ctx.cfg.timeout("upload", 300)) if job else (False, None, None)
    # wait_upload only reports that the job stopped moving; FAILED is a stop too, so COMPLETED is required here.
    ok = bool(ok) and (state or {}).get("status") == "COMPLETED"
    return ok, state, secs


def _benchmarks_under(ctx, space):
    """{name: id} for benchmarks associated with the space itself."""
    return {n: int(i) for i, n in ctx.db.rows(
        "SELECT b.id, b.name FROM starexec.benchmarks b JOIN starexec.bench_assoc ba ON ba.bench_id=b.id"
        f" WHERE ba.space_id={int(space)} AND b.deleted=false ORDER BY b.name;")}


def _hierarchy(ctx, root):
    """{space name: {benchmark names}} for the root and every space under it."""
    out = {}
    rows = ctx.db.rows(
        "SELECT s.id, s.name FROM starexec.spaces s JOIN starexec.closure c ON c.descendant=s.id"
        f" WHERE c.ancestor={int(root)};")
    for sid, sname in rows:
        out[sname] = set(_benchmarks_under(ctx, int(sid)))
    return out


def run_tptp(cap, ctx):
    parent, bench_proc = ctx.state.need("space", "bench_processor")
    owner = ctx.owner()
    axioms_fx, problems_fx = need_fixture(ctx, "e2e-axioms.tar"), need_fixture(ctx, "e2e-problems.tar")
    axiom_sha = ctx.fx.get("axioms", {})
    problem_meta = ctx.fx.get("problems", {})

    # ---- A1 convert: a space per archive directory ----
    root = _space(ctx, owner, parent, "c32_convert")
    if not root:
        raise Blocked("c32 convert space not created")
    ok, state, secs = _upload(ctx, owner, root, axioms_fx, "convert", bench_proc)
    tree = _hierarchy(ctx, root)
    want_names = {posixpath.basename(n) for n in axiom_sha}
    got_names = set().union(*tree.values()) if tree else set()
    cap.check(
        "1",
        "convert mirrors the archive's directories as spaces",
        "uploading e2e-axioms.tar with upMethod=convert creates a subspace named for the archive's Axioms"
        " directory holding exactly the archive's files, and no benchmark is left in the upload target space",
        {"Axioms": sorted(want_names), "target_space_benchmarks": []},
        {"upload_ok": ok, "state": state, "seconds": secs, "tree": {k: sorted(v) for k, v in tree.items()}},
        ok and tree.get("Axioms") == want_names and not tree.get(ctx.db.scalar(
            f"SELECT name FROM starexec.spaces WHERE id={root};"), set()),
        ["upload_benchmarks", "upload_benchmarks_params", "upload_bench_convert"],
    )
    axioms_space = ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE sa.space_id={root} AND s.name='Axioms';")
    axioms = _benchmarks_under(ctx, axioms_space) if axioms_space else {}
    if not axioms:
        raise Blocked("axioms were not uploaded")

    # ---- A3 dump: everything in one space ----
    flat = _space(ctx, owner, parent, "c32_dump")
    ok_dump, state_dump, _ = _upload(ctx, owner, flat, axioms_fx, "dump", bench_proc)
    dump_here = set(_benchmarks_under(ctx, flat))
    dump_tree = _hierarchy(ctx, flat)
    cap.check(
        "2",
        "dump puts every file in the target space and creates no subspace",
        "the same archive with upMethod=dump gives the target space exactly the archive's files and no subspaces",
        {"benchmarks": sorted(want_names), "subspaces": 0},
        {"upload_ok": ok_dump, "state": state_dump, "benchmarks": sorted(dump_here),
         "subspaces": len(dump_tree) - 1},
        ok_dump and dump_here == want_names and len(dump_tree) == 1,
        ["upload_benchmarks", "upload_bench_dump"],
    )

    # ---- A2 dependencies: the include() closure ----
    probs_space = _space(ctx, owner, parent, "c32_problems")
    # depRoot is the space the include() PATHS are resolved against: the includes read "Axioms/<file>", so the
    # root is the convert target that holds the Axioms subspace, not the Axioms subspace itself.
    ok_p, state_p, _ = _upload(ctx, owner, probs_space, problems_fx, "dump", bench_proc,
                               dependency=True, dep_root=root)
    problems = _benchmarks_under(ctx, probs_space)
    deps = {}
    for pname, bid in problems.items():
        deps[pname] = sorted(n for (n,) in ctx.db.rows(
            "SELECT s.name FROM starexec.bench_dependency d JOIN starexec.benchmarks s ON s.id=d.secondary_bench_id"
            f" WHERE d.primary_bench_id={bid} ORDER BY s.name;"))
    want_deps = {posixpath.basename(p): sorted(posixpath.basename(i) for i in m.get("includes", []))
                 for p, m in problem_meta.items()}
    want_deps = {k: v for k, v in want_deps.items() if k in problems}
    cap.check(
        "3",
        "every include() becomes a bench_dependency row",
        "uploading the problems with dependency=true and depRoot=<Axioms space> records exactly the include()"
        " list of each problem from the fixtures manifest",
        want_deps,
        {"upload_ok": ok_p, "state": state_p, "dependencies": deps},
        # want_deps is derived from the manifest and must not be empty: an upload that produced no problems
        # would otherwise compare {} to {} and pass without asserting anything.
        ok_p and bool(want_deps) and deps == want_deps,
        ["upload_benchmarks", "upload_benchmarks_params"],
    )

    # ---- A9 served bytes ----
    name, bid = sorted(axioms.items())[0]
    r = owner.get(ep.path("download"), params={"type": "bench", "id": bid}, timeout=60)
    # Download serves a zip holding the benchmark, so the member's bytes are what must match the archive.
    served, entries = None, None
    try:
        z = zipfile.ZipFile(io.BytesIO(r.content))
        entries = z.namelist()
        member = [n for n in entries if n.endswith(name)]
        served = hashlib.sha256(z.read(member[0])).hexdigest() if member else None
    except zipfile.BadZipFile:
        served = hashlib.sha256(r.content).hexdigest()
    want_sha = next((v for k, v in axiom_sha.items() if posixpath.basename(k) == name), None)
    contents = owner.get(ep.path("benchmark_contents", id=bid), params={"limit": -1})
    cap.check(
        "4",
        "a benchmark downloads byte-exact",
        "GET /secure/download?type=bench&id=<axiom> returns a zip whose member is exactly the archive member's"
        " bytes (sha256 from the fixtures manifest), and /services/benchmarks/{id}/contents answers 200",
        want_sha,
        {"download_sha256": served, "zip_entries": entries, "bytes": len(r.content),
         "contents_http": contents.status_code},
        served == want_sha and contents.status_code == 200,
        ["download", "download_bench", "benchmark_contents"],
    )

    # ---- A5 edit ----
    new_name = ctx.name("c32_renamed")[:255]
    owner.post(ep.path("edit_benchmark", benchId=bid), data={"name": new_name, "description": "E2E c32 edited",
                                                             "downloadable": "false", "type": bench_proc},
               keep=["success", "message"])
    row = ctx.db.rows(f"SELECT name, description, downloadable, bench_type FROM starexec.benchmarks WHERE id={bid};")
    cap.check("5", "benchmark edit stores name, description and downloadable",
              "POST /services/edit/benchmark/{id} updates the benchmarks row",
              [[new_name, "E2E c32 edited", "f", str(bench_proc)]], row,
              row == [[new_name, "E2E c32 edited", "f", str(bench_proc)]], ["edit_benchmark"])

    # ---- A7 delete an axiom other problems depend on ----
    depended = ctx.db.rows(
        "SELECT d.secondary_bench_id, count(*) FROM starexec.bench_dependency d WHERE d.primary_bench_id IN"
        f" ({','.join(str(v) for v in problems.values()) or '0'}) GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 1;")
    if not depended:
        cap.blocked("6", "delete an axiom other problems depend on", "no dependency rows to work with")
        return
    axiom_id = int(depended[0][0])
    dependents = [int(p) for (p,) in ctx.db.rows(
        f"SELECT primary_bench_id FROM starexec.bench_dependency WHERE secondary_bench_id={axiom_id} ORDER BY 1;")]
    path = ctx.db.scalar(f"SELECT path FROM starexec.benchmarks WHERE id={axiom_id};")
    rows_before = ctx.db.int(f"SELECT count(*) FROM starexec.bench_dependency WHERE secondary_bench_id={axiom_id};")
    owner.post(ep.path("recycle_benchmark"), data={"selectedIds[]": axiom_id}, keep=["success", "message"])
    owner.post(ep.path("delete_benchmark"), data={"selectedIds[]": axiom_id}, keep=["success", "message"])
    state_row = ctx.db.rows(f"SELECT recycled, deleted FROM starexec.benchmarks WHERE id={axiom_id};")
    rows_after = ctx.db.int(f"SELECT count(*) FROM starexec.bench_dependency WHERE secondary_bench_id={axiom_id};")
    file_gone = None
    if ctx.cfg.app_exec and path:
        code, _ = ctx.app("test", "-e", path)
        file_gone = code != 0
    cap.check(
        "6",
        "deleting an axiom is a soft delete that leaves its dependency rows behind",
        "recycling then deleting an axiom sets benchmarks.deleted and removes the file, while the"
        " bench_dependency rows naming it survive (the FK cascades only on a real row delete)",
        {"deleted": "t", "dependency_rows": rows_before, "file_gone": True},
        {"state": state_row, "dependency_rows_before": rows_before, "dependency_rows_after": rows_after,
         "file_gone": file_gone, "dependents": dependents},
        bool(state_row) and state_row[0][1] == "t" and rows_after == rows_before
        and file_gone in (True, None),
        ["recycle_benchmark", "delete_benchmark", "delete_benchmark_soft", "bench_dependency_fk"],
    )

    # A job on a problem that depends on the deleted axiom.
    fx = need_fixture(ctx, "c31-cat-solver.zip")
    _, sid = upload_solver(ctx, owner, ctx.name("c32_cat"), fx["path"], "c31-cat-solver.zip")
    cat = configs_of(ctx, sid).get("cat") if sid else None
    if not cat or not dependents:
        cap.blocked("7", "a job on a problem whose axiom was deleted", "no cat configuration or no dependent problem")
        return
    jname = ctx.name("c32_job")
    owner.post(ep.path("create_job"), timeout=120, data={
        "sid": probs_space, "name": jname, "desc": "E2E c32 A7", "runChoice": "choose", "benchChoice": "choose",
        "configs": [cat], "bench": [dependents[0]], "preProcess": "-1", "postProcess": "-1",
        "queue": ctx.queue(), "seed": "0", "cpuTimeout": 10, "wallclockTimeout": 10, "maxMem": "0.5",
        "pause": "no", "benchmarkingFramework": "RUNSOLVER", "saveOtherOutput": "true"})
    job = ctx.db.int(f"SELECT id FROM starexec.jobs WHERE name={literal(jname)};")
    ok_job, last, secs = wait_terminal(ctx, job, 1, ctx.cfg.timeout("dispatch", 300)) if job else (False, None, None)
    status = next(iter(pair_statuses(ctx, job).values()), None) if job else None
    # What the job script actually reported, which is not what the pair row can show while the status is held.
    reported = None
    if ctx.cfg.app_exec and job:
        pair = next(iter(pair_statuses(ctx, job)), None)
        code, out = ctx.app("sh", "-c",
                            f'cat /app/data/logs/{int(job)}/{int(job)}/pair_{int(pair)}/status.json 2>/dev/null || true')
        reported = out.strip()[:200]
    held_at_stage_zero = bool(reported) and '"stageNumber":0' in reported.replace(" ", "")
    cap.check(
        "7",
        "a pair whose benchmark depends on a deleted axiom fails visibly instead of hanging",
        "after the axiom is deleted, a pair on a problem that include()s it reaches"
        " ERROR_BENCH_DEPENDENCY_MISSING(24) in job_pairs. The job script DOES detect the missing dependency and"
        " reports 24, but it reports it on the pair-level channel (jobscript:217 sendStatus with no stage), so the"
        " monitor refuses the stage-0 status and the pair is held at RUNNING(4): that is ledger B3 / #145 / #165"
        " swallowing a correctly detected dependency failure.",
        {"pair_status": 24},
        {"job": job, "terminal": ok_job, "pair_status": status, "seconds": secs, "axiom": axiom_id,
         "benchmark": dependents[0], "status_json_written_by_the_job_script": reported},
        status == 24,
        ["create_job", "bench_dependency_fk"],
        known="B3" if held_at_stage_zero else ("A7" if ok_job else None),
    )
    if held_at_stage_zero and job:
        # Leave no pair pinned in RUNNING for later capabilities or for the next run to trip over.
        owner.post(ep.path("delete_job"), data={"selectedIds[]": job})
        ctx.run.recorder.note(f"deleted held c32 dependency job {job}")
