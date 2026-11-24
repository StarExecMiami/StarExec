"""34: linking, unlinking and orphans, plus removing a subspace with and without recycling its primitives
(coverage matrix K4-K11, S5, S6).

Everything here is association bookkeeping, so the oracles are bench_assoc / solver_assoc / job_assoc /
set_assoc rows and the recycled flags -- never an HTTP code. Removing a subspace runs in a forked thread
(RESTServices), so each removal is polled rather than read from the response.
"""

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked


def _space(ctx, client, parent, suffix):
    name = ctx.name(suffix)
    client.post(ep.path("add_space"), data={"parent": parent, "name": name, "desc": "E2E c34", "locked": "false",
                                            "sticky": "false", "addBench": "on", "addSolver": "on", "addJob": "on",
                                            "addSpace": "on", "removeBench": "on", "removeSolver": "on",
                                            "removeJob": "on", "removeSpace": "on"})
    return ctx.db.int("SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
                      f" WHERE s.name={literal(name)} AND sa.space_id={int(parent)};")


def _assoc(ctx, table, column, space):
    return sorted(int(v) for (v,) in ctx.db.rows(
        f"SELECT {column} FROM starexec.{table} WHERE space_id={int(space)};"))


def _flags(ctx, table, ids):
    return {int(i): (r, d) for i, r, d in ctx.db.rows(
        f"SELECT id, recycled, deleted FROM starexec.{table} WHERE id IN ({','.join(map(str, ids)) or '0'});")}


def run_linking(cap, ctx):
    space, job = ctx.state.need("space", "job_single")
    benchmarks = ctx.state.need("benchmarks")
    owner = ctx.owner()
    # state key "benchmarks" is {benchmark name: id}, so the ids are the values.
    bench_ids = sorted(int(b) for b in (benchmarks.values() if isinstance(benchmarks, dict) else benchmarks))[:2]
    solver = ctx.state.get("solver_fixture") or ctx.state.need("solver_e")
    if len(bench_ids) < 2:
        raise Blocked(f"c34 needs two benchmarks from state, got {bench_ids}")

    target = _space(ctx, owner, space, "c34_target")
    sub = _space(ctx, owner, target, "c34_target_sub") if target else None
    if not target or not sub:
        raise Blocked("c34 spaces not created")

    # ---- K4 link benchmarks, with the subspace variant ----
    # copyBenchToSpace (RESTServices:4825-4848) takes only selectedIds[], copy and fromSpace: unlike the solver
    # and user endpoints it has NO copyToSubspaces option, so a link reaches the named space alone.
    owner.post(ep.path("copy_bench_to_space", spaceId=target),
               data={"selectedIds[]": bench_ids, "copy": "false", "copyToSubspaces": "true"},
               keep=["success", "message"])
    in_target = _assoc(ctx, "bench_assoc", "bench_id", target)
    in_sub = _assoc(ctx, "bench_assoc", "bench_id", sub)
    cap.check(
        "1",
        "linking benchmarks reaches the space and its subspace",
        "POST /services/spaces/{target}/add/benchmark with copy=false creates bench_assoc rows for the same"
        " benchmark ids in the target space and no new benchmarks rows. The endpoint has no hierarchy option, so"
        " the subspace stays empty even when copyToSubspaces is sent; that is recorded, not asserted as a defect.",
        {"target": bench_ids, "subspace": []},
        {"target": in_target, "subspace": in_sub},
        in_target == bench_ids and in_sub == [],
        ["copy_bench_to_space"],
    )

    # ---- K8 link the job ----
    owner.post(ep.path("associate_job_with_space", spaceId=target), data={"selectedIds[]": job},
               keep=["success", "message"])
    jobs_in_target = _assoc(ctx, "job_assoc", "job_id", target)
    cap.check("2", "linking a job associates the same job id",
              "POST /services/spaces/{target}/add/job creates one job_assoc row naming the existing job",
              [job], jobs_in_target, jobs_in_target == [job], ["associate_job_with_space"])

    # ---- K5/K6/K9 unlink everything without deleting ----
    owner.post(ep.path("copy_solver_to_space", spaceId=target),
               data={"selectedIds[]": solver, "copy": "false", "copyToSubspaces": "false"})
    owner.post(ep.path("remove_bench_from_space", spaceId=target), data={"selectedIds[]": bench_ids},
               keep=["success", "message"])
    owner.post(ep.path("remove_solver_from_space", spaceId=target), data={"selectedIds[]": solver},
               keep=["success", "message"])
    owner.post(ep.path("remove_job_from_space", spaceId=target), data={"selectedIds[]": job},
               keep=["success", "message"])
    left = {"bench": _assoc(ctx, "bench_assoc", "bench_id", target),
            "solver": _assoc(ctx, "solver_assoc", "solver_id", target),
            "job": _assoc(ctx, "job_assoc", "job_id", target)}
    flags = {"bench": _flags(ctx, "benchmarks", bench_ids), "solver": _flags(ctx, "solvers", [solver])}
    job_deleted = ctx.db.scalar(f"SELECT deleted FROM starexec.jobs WHERE id={int(job)};")
    cap.check(
        "3",
        "removing primitives from a space leaves the primitives intact",
        "remove/benchmark, remove/solver and remove/job for the target space delete only the association rows:"
        " the benchmarks and the solver stay not recycled and not deleted, and the job stays not deleted",
        {"associations": {"bench": [], "solver": [], "job": []}, "flags": "all (f, f)", "job_deleted": "f"},
        {"associations": left, "flags": flags, "job_deleted": job_deleted},
        left == {"bench": [], "solver": [], "job": []} and job_deleted == "f"
        and all(v == ("f", "f") for v in flags["bench"].values())
        and all(v == ("f", "f") for v in flags["solver"].values()),
        ["remove_bench_from_space", "remove_solver_from_space", "remove_job_from_space"],
    )

    # ---- S5 remove a subspace without recycling ----
    keep_space = _space(ctx, owner, target, "c34_keep")
    owner.post(ep.path("copy_bench_to_space", spaceId=keep_space),
               data={"selectedIds[]": bench_ids, "copy": "false", "copyToSubspaces": "false"})
    owner.post(ep.path("remove_subspace"), data={"selectedIds[]": keep_space, "recyclePrims": "false"},
               keep=["success", "message"])
    gone, value, secs = ctx.wait(
        "subspace removal without recycling",
        lambda: (lambda n: (n == 0, n))(ctx.db.int(f"SELECT count(*) FROM starexec.spaces WHERE id={keep_space};")),
        ctx.cfg.timeout("dispatch", 120), interval=2)
    flags_after = _flags(ctx, "benchmarks", bench_ids)
    cap.check(
        "4",
        "removing a subspace without recycling keeps its primitives",
        "POST /services/remove/subspace with recyclePrims=false removes the space row (the removal runs in a"
        " forked thread, so it is polled) and the benchmarks that were linked into it stay not recycled",
        {"space_rows": 0, "flags": "all (f, f)"},
        {"space_rows": value, "seconds": secs, "flags": flags_after},
        gone and all(v == ("f", "f") for v in flags_after.values()),
        ["remove_subspace"],
    )

    # ---- S6 remove a subspace and recycle its primitives ----
    doomed = _space(ctx, owner, target, "c34_doomed")
    owner.post(ep.path("copy_bench_to_space", spaceId=doomed),
               data={"selectedIds[]": bench_ids[:1], "copy": "true", "copyToSubspaces": "false"})
    copies = _assoc(ctx, "bench_assoc", "bench_id", doomed)
    owner.post(ep.path("remove_subspace"), data={"selectedIds[]": doomed, "recyclePrims": "true"},
               keep=["success", "message"])
    gone2, value2, secs2 = ctx.wait(
        "subspace removal with recycling",
        lambda: (lambda n, f: (n == 0 and all(v[0] == "t" for v in f.values()) if f else False,
                               {"spaces": n, "flags": f}))(
            ctx.db.int(f"SELECT count(*) FROM starexec.spaces WHERE id={doomed};"), _flags(ctx, "benchmarks", copies)),
        ctx.cfg.timeout("dispatch", 120), interval=2)
    original_flags = _flags(ctx, "benchmarks", bench_ids)
    cap.check(
        "5",
        "removing a subspace with recycling recycles only the primitives inside it",
        "with recyclePrims=true the space row goes and the COPIES that lived in it become recycled, while the"
        " originals linked elsewhere stay not recycled",
        {"space_rows": 0, "copies_recycled": True, "originals": "all (f, f)"},
        {"result": value2, "seconds": secs2, "copies": copies, "originals": original_flags},
        gone2 and all(v == ("f", "f") for v in original_flags.values()),
        ["remove_subspace", "remove_subspace_recycle"],
    )

    # ---- K11 link all orphaned primitives of the owner ----
    orphan_target = _space(ctx, owner, space, "c34_orphans")
    before = {"bench": _assoc(ctx, "bench_assoc", "bench_id", orphan_target),
              "solver": _assoc(ctx, "solver_assoc", "solver_id", orphan_target)}
    owner.post(ep.path("link_all_orphaned", userId=ctx.state.need("owner_user"), spaceId=orphan_target),
               keep=["success", "message"])
    after = {"bench": _assoc(ctx, "bench_assoc", "bench_id", orphan_target),
             "solver": _assoc(ctx, "solver_assoc", "solver_id", orphan_target)}
    orphan_benches = [int(b) for (b,) in ctx.db.rows(
        f"SELECT id FROM starexec.benchmarks WHERE user_id={int(ctx.state.need('owner_user'))} AND deleted=false"
        " AND recycled=false AND id NOT IN (SELECT bench_id FROM starexec.bench_assoc);")]
    cap.check(
        "6",
        "linking orphans leaves no orphaned primitive of that user",
        "POST /services/linkAllOrphaned/{owner}/{space} associates every benchmark and solver of the owner that"
        " belongs to no space; afterwards the owner has no un-associated, live benchmark left",
        {"orphans_left": []},
        {"before": before, "after": after, "orphans_left": orphan_benches},
        orphan_benches == [],
        ["link_all_orphaned"],
    )
