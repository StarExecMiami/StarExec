"""43: the permissions chosen on the benchmark upload form reach the spaces the upload creates.

The form's permission checkboxes set the default permissions of every space an upload with
"convert" creates from the archive's directories. Each check grants an unusual set (add benchmarks
and remove solvers only) and reads back the created subspace's default permission row.

The two server paths are checked separately:
- 43.1, the multipart POST, which URL and Git uploads and StarexecCommand use;
- 43.2, the resumable session, which the page uses for every local file.
"""

import math
import posixpath

from lib import endpoints as ep
from lib.steps import need_fixture, wait_upload

FLAGS = (
    "add_solver",
    "add_bench",
    "add_user",
    "add_space",
    "add_job",
    "remove_solver",
    "remove_bench",
    "remove_user",
    "remove_space",
    "remove_job",
)
GRANTED = {"add_bench", "remove_solver"}
# The upload form's checkbox names: the server tests the multipart form for their presence, and the
# page sends them, by name, in the session it creates.
FORM = {"add_bench": "addBench", "remove_solver": "removeSolver"}
WANT = {f: f in GRANTED for f in FLAGS}


def _created_permissions(ctx, space, newest):
    created = ctx.db.scalar(
        f"SELECT min(c.child_id) FROM starexec.set_assoc c WHERE c.space_id = {int(space)} "
        f"AND c.child_id > {int(newest)};",
        record=False,
    )
    if not created:
        return None, {}
    row = ctx.db.scalar(
        "SELECT "
        + " || ',' || ".join(f"p.{f}::text" for f in FLAGS)
        + " FROM starexec.spaces s JOIN starexec.permissions p ON p.id = s.default_permission "
        f"WHERE s.id = {int(created)};",
        record=False,
    )
    return created, (
        dict(zip(FLAGS, (v == "true" for v in row.split(",")))) if row else {}
    )


def _multipart(cap, ctx, space, processor, fixture):
    owner = ctx.owner()
    newest = ctx.db.int("SELECT coalesce(max(id), 0) FROM starexec.spaces;")
    with open(fixture["path"], "rb") as f:
        r = owner.post(
            ep.path("upload_benchmarks"),
            timeout=120,
            keep=["jobId", "status"],
            data=dict(
                {
                    "space": space,
                    "upMethod": "convert",
                    "benchType": processor,
                    "download": "true",
                    "localOrURLOrGit": "local",
                    "dependency": "false",
                    "linked": "false",
                    "depRoot": space,
                },
                **{FORM[k]: "on" for k in GRANTED},
            ),
            files={
                "benchFile": (
                    posixpath.basename(fixture["path"]),
                    f,
                    "application/x-tar",
                )
            },
        )
    job = (owner.json(r) or {}).get("jobId")
    done, state, _ = (
        wait_upload(ctx, owner, job, ctx.cfg.timeout("upload", 300))
        if job
        else (False, {}, 0)
    )
    created, got = _created_permissions(ctx, space, newest)
    cap.check(
        "1",
        "multipart upload: the form's permissions become the new subspace's default permissions",
        "POST upload_benchmarks with convert, granting add-benchmark and remove-solver only: the"
        " subspace created from the archive has exactly those two default permissions",
        {"default_permission": WANT},
        {
            "http": r.status_code,
            "upload_job": job,
            "upload_status": state.get("status"),
            "subspace": created,
            "default_permission": got,
        },
        done and got == WANT,
        ["upload_benchmarks", "upload_job_status"],
    )


def _session(cap, ctx, space, processor, fixture):
    owner = ctx.owner()
    newest = ctx.db.int("SELECT coalesce(max(id), 0) FROM starexec.spaces;")
    with open(fixture["path"], "rb") as f:
        data = f.read()
    # The payload buildResumableUploadPayload() sends.
    payload = {
        "fileName": posixpath.basename(fixture["path"]),
        "totalBytes": len(data),
        "spaceId": int(space),
        "uploadMethod": "convert",
        "benchmarkTypeId": int(processor),
        "downloadable": True,
        "hasDependencies": False,
        "depRootSpaceId": None,
        "linked": False,
        "spacePermissions": sorted(FORM[k] for k in GRANTED),
    }
    r = owner.request(
        "POST", "/services/uploads/sessions", json=payload, keep=["id", "chunkSize"]
    )
    session = owner.json(r) or {}
    sid, size = session.get("id"), int(session.get("chunkSize") or 0)
    stored = (
        ctx.db.scalar(
            f"SELECT space_permissions FROM starexec.upload_sessions WHERE id = {int(sid)};",
            record=False,
        )
        if sid
        else None
    )
    job, done, state = None, False, {}
    if sid and size > 0:
        for i in range(math.ceil(len(data) / size)):
            owner.request(
                "PUT",
                f"/services/uploads/sessions/{int(sid)}/chunks/{i}",
                data=data[i * size : (i + 1) * size],
                headers={"Content-Type": "application/octet-stream"},
            )
        fin = owner.request(
            "POST", f"/services/uploads/sessions/{int(sid)}/finalize", keep=["jobId"]
        )
        job = (owner.json(fin) or {}).get("jobId")
        if job:
            done, state, _ = wait_upload(
                ctx, owner, job, ctx.cfg.timeout("upload", 300)
            )
    created, got = _created_permissions(ctx, space, newest)
    cap.check(
        "2",
        "resumable upload: the form's permissions become the new subspace's default permissions",
        "create a session as the page does, granting add-benchmark and remove-solver only, upload"
        " the chunks and finalize: the session stores the choice, and the subspace created from the"
        " archive has exactly those two default permissions",
        {"stored": "addBench,removeSolver", "default_permission": WANT},
        {
            "http": r.status_code,
            "session": sid,
            "stored": stored,
            "upload_job": job,
            "upload_status": state.get("status"),
            "subspace": created,
            "default_permission": got,
        },
        done and stored == "addBench,removeSolver" and got == WANT,
        ["upload_job_status"],
    )


def run_upload_space_permissions(cap, ctx):
    space, processor = ctx.state.need("space", "bench_processor")
    fixture = need_fixture(ctx, "e2e-axioms.tar")
    _multipart(cap, ctx, space, processor, fixture)
    _session(cap, ctx, space, processor, fixture)
