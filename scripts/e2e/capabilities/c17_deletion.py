"""17: recycle, restore and delete for a benchmark and a solver; job deletion."""

import posixpath

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture
from capabilities.c07_solvers import upload_solver


def _flags(ctx, table, pid):
    rows = ctx.db.rows(
        f"SELECT recycled, deleted, coalesce(path,'') FROM starexec.{table} WHERE id={pid};"
    )
    return rows[0] if rows else None


def _exists_on_app(ctx, path):
    if not ctx.cfg.app_exec or not path:
        return None
    code, _ = ctx.app("test", "-e", path)
    return code == 0


def _cycle(cap, ctx, prefix, kind, pid, recycle_key, restore_key, delete_key):
    owner = ctx.owner()
    table = {"benchmark": "benchmarks", "solver": "solvers"}[kind]
    user = ctx.state.need("owner_user")
    r = owner.post(ep.path(recycle_key), data={"selectedIds[]": pid}, keep=["success"])
    f = _flags(ctx, table, pid)
    cap.check(
        prefix + "1",
        f"{kind} recycle",
        f"POST {ep.path(recycle_key)} sets {table}.recycled = t and leaves deleted = f",
        ["t", "f"],
        f[:2] if f else None,
        bool(f) and f[:2] == ["t", "f"],
        [recycle_key],
    )
    r = owner.post(ep.path(restore_key), data={"selectedIds[]": pid}, keep=["success"])
    f = _flags(ctx, table, pid)
    cap.check(
        prefix + "2",
        f"{kind} restore",
        f"POST {ep.path(restore_key)} sets recycled back to f",
        ["f", "f"],
        f[:2] if f else None,
        bool(f) and f[:2] == ["f", "f"],
        [restore_key],
    )
    owner.post(ep.path(recycle_key), data={"selectedIds[]": pid})
    disk_before = ctx.db.int(f"SELECT disk_size FROM starexec.users WHERE id={user};")
    size = ctx.db.int(f"SELECT disk_size FROM starexec.{table} WHERE id={pid};")
    path = (f or ["", "", ""])[2]
    on_disk_before = _exists_on_app(ctx, path)
    r = owner.post(ep.path(delete_key), data={"selectedIds[]": pid}, keep=["success"])
    f = _flags(ctx, table, pid)
    disk_after = ctx.db.int(f"SELECT disk_size FROM starexec.users WHERE id={user};")
    cap.check(
        prefix + "3",
        f"{kind} delete",
        f"POST {ep.path(delete_key)} on the recycled {kind} sets deleted = t",
        "t",
        f[1] if f else "row removed",
        f is None or f[1] == "t",
        [delete_key],
    )
    cap.check(
        prefix + "4",
        f"{kind} delete releases the owner's disk charge",
        f"users.disk_size decreases by exactly the {kind}'s disk_size",
        {"delta": -(size or 0)},
        {"before": disk_before, "after": disk_after, "size": size},
        disk_before is not None and disk_after == disk_before - (size or 0),
        [delete_key],
    )
    if ctx.cfg.app_exec:
        gone = _exists_on_app(ctx, path)
        cap.check(
            prefix + "5",
            f"{kind} files are removed",
            f"the {kind}'s path existed before delete and not after",
            {"before": True, "after": False},
            {"before": on_disk_before, "after": gone, "path": path},
            on_disk_before is True and gone is False,
            [delete_key],
        )


def run_deletion(cap, ctx):
    owner = ctx.owner()
    uid, space, processor = ctx.state.need("owner_user", "space", "bench_processor")
    fx = need_fixture(ctx, "fixture-solver.zip")
    r, sid = upload_solver(
        ctx, owner, ctx.name("delete_solver"), fx["path"], "fixture-solver.zip"
    )
    if not sid:
        raise Blocked("solver for deletion not created")
    _cycle(
        cap,
        ctx,
        "s",
        "solver",
        sid,
        "recycle_solver",
        "restore_solver",
        "delete_solver",
    )

    # A benchmark uploaded here and used by nothing else. The first Podman run deleted a shared axiom
    # (AGT001+0.ax) and every later job on AGT001+1.p aborted with ERROR_BENCH_DEPENDENCY_MISSING.
    from capabilities.c08_benchmarks import _upload
    from lib.steps import wait_upload

    r, upload = _upload(
        ctx, owner, space, processor, need_fixture(ctx, "delete-bench.tar"), False
    )
    ok, _, _ = (
        wait_upload(ctx, owner, upload, ctx.cfg.timeout("upload", 300))
        if upload
        else (False, None, None)
    )
    bid = ctx.db.int(
        f"SELECT id FROM starexec.benchmarks WHERE user_id={uid} AND name='e2e_delete_me.p' AND deleted=false"
        " ORDER BY id DESC LIMIT 1;"
    )
    used = ctx.db.int(
        f"SELECT count(*) FROM starexec.bench_dependency WHERE secondary_bench_id={bid or 0};"
    )
    if not bid or used:
        cap.blocked(
            "b",
            "benchmark recycle/restore/delete",
            f"the dedicated deletion benchmark was not created or is a dependency (upload ok={ok}, id={bid}, used_by={used})",
        )
    else:
        _cycle(
            cap,
            ctx,
            "b",
            "benchmark",
            bid,
            "recycle_benchmark",
            "restore_benchmark",
            "delete_benchmark",
        )

    job = ctx.state.get("job_closure")
    if not job:
        cap.blocked("j", "job delete", "the #197 closure job is not present")
        return
    r = owner.post(ep.path("delete_job"), data={"selectedIds[]": job}, keep=["success"])
    deleted = ctx.db.scalar(f"SELECT deleted FROM starexec.jobs WHERE id={job};")
    cap.check(
        "j1",
        "job delete",
        "POST /services/delete/job sets jobs.deleted = t (or removes the row)",
        "t",
        deleted,
        deleted in ("t", None),
        ["delete_job"],
    )
