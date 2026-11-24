"""33: solver lifecycle (coverage matrix V2-V4, V6-V11, K1-K3). Backend-dependent: a rebuild and the copy's
own run go through the backend under test.

Oracles: the solvers / configurations rows, the bytes the download endpoint serves against the fixture's
sha256, the files inside the app (bin/starexec_run_<name>), and, for copy vs link, whether a second solvers
row and a second directory exist. V2 (upload by URL, #221) fetches the same fixture from lib.fileserver on the
stack's own network, so it needs a Podman network and STAREXEC_URL_DOWNLOAD_ALLOW_PRIVATE_NETWORKS=true.
"""

import hashlib
import io
import posixpath
import zipfile

from capabilities.c07_solvers import configs_of, upload_solver
from lib import endpoints as ep
from lib.db import literal
from lib.fileserver import PORT, serving
from lib.report import Blocked
from lib.steps import need_fixture

CONFIG_BODY = b"#!/bin/sh\necho E2E33_ADDED\n"
# Util.DOWNLOADABLE_URL_REQUIRED: what an upload by URL answers to an address that is not http or https.
NOT_HTTP = "Archive URLs must be valid http or https URLs"


def _served_run_script(client, sid):
    """Download the solver; return the HTTP status, its starexec_run_cat entries and the first one's sha256."""
    r = client.get(
        ep.path("download"), params={"type": "solver", "id": sid}, timeout=120
    )
    try:
        z = zipfile.ZipFile(io.BytesIO(r.content))
    except zipfile.BadZipFile:
        return r.status_code, None, None
    inner = [n for n in z.namelist() if n.endswith("starexec_run_cat")]
    return (
        r.status_code,
        inner,
        hashlib.sha256(z.read(inner[0])).hexdigest() if inner else None,
    )


def _upload_by_url(client, space, name, url):
    return client.post(
        ep.path("upload_solver"),
        timeout=180,
        data={
            "space": space,
            "sn": name,
            "desc": "E2E",
            "dlable": "true",
            "upMethod": "URL",
            "url": url,
            "descMethod": "text",
            "execType": "1",
            "runTestJob": "false",
        },
        # Multipart, with the file field empty, as the browser form sends it.
        files={"f": ("", b"", "application/octet-stream")},
    )


def _solver_row(ctx, sid):
    rows = ctx.db.rows(
        "SELECT name, path, build_status, recycled, deleted, downloadable FROM starexec.solvers"
        f" WHERE id={int(sid)};"
    )
    return (
        dict(
            zip(
                ["name", "path", "build_status", "recycled", "deleted", "downloadable"],
                rows[0],
            )
        )
        if rows
        else None
    )


def _exists(ctx, path):
    if not ctx.cfg.app_exec or not path:
        return None
    code, _ = ctx.app("test", "-e", path)
    return code == 0


def run_solver_lifecycle(cap, ctx):
    space = ctx.state.need("space")
    owner = ctx.owner()
    fx = need_fixture(ctx, "c31-cat-solver.zip")
    _, sid = upload_solver(
        ctx, owner, ctx.name("c33_cat"), fx["path"], "c31-cat-solver.zip"
    )
    if not sid:
        raise Blocked("c33 solver was not created")
    ctx.state.set("solver_c33", sid)
    row = _solver_row(ctx, sid)

    # ---- V10 download bytes ----
    status, inner, served = _served_run_script(owner, sid)
    want = hashlib.sha256(
        zipfile.ZipFile(fx["path"]).read("bin/starexec_run_cat")
    ).hexdigest()
    cap.check(
        "1",
        "a solver downloads with its run script byte-exact",
        "GET /secure/download?type=solver&id returns a zip whose bin/starexec_run_cat is exactly the uploaded"
        " script (sha256 of the fixture's member)",
        want,
        {"http": status, "entries": inner, "sha256": served},
        served == want,
        ["download", "download_solver"],
    )

    # ---- V6 add a configuration ----
    new_cfg_name = "added33"
    owner.post(
        ep.path("upload_configuration"),
        timeout=60,
        data={
            "solverId": sid,
            "uploadConfigName": new_cfg_name,
            "uploadConfigDesc": "E2E c33 added",
        },
        files={
            "file": ("starexec_run_added33", CONFIG_BODY, "application/octet-stream")
        },
    )
    cfgs = configs_of(ctx, sid)
    added = cfgs.get(new_cfg_name)
    on_disk = None
    if ctx.cfg.app_exec and row and added:
        code, out = ctx.app(
            "sh",
            "-c",
            f'sha256sum "{row["path"]}/bin/starexec_run_{new_cfg_name}" 2>/dev/null || true',
        )
        on_disk = out.split()[0] if out.strip() else None
    cap.check(
        "2",
        "a configuration is added as a row and a run script",
        "POST /secure/upload/configurations creates one configurations row named added33 and writes"
        " bin/starexec_run_added33 with exactly the submitted bytes",
        {
            "configs": ["added33", "cat"],
            "script_sha256": hashlib.sha256(CONFIG_BODY).hexdigest(),
        },
        {"configs": sorted(cfgs), "script_sha256": on_disk},
        sorted(cfgs) == ["added33", "cat"]
        and (on_disk is None or on_disk == hashlib.sha256(CONFIG_BODY).hexdigest()),
        ["upload_configuration", "upload_configuration_params"],
    )
    if not added:
        raise Blocked("configuration was not added")

    # ---- V7 edit, V8 delete the configuration ----
    owner.post(
        ep.path("edit_configuration", id=added),
        data={"name": "added33", "description": "E2E c33 edited"},
        keep=["success", "message"],
    )
    desc = ctx.db.scalar(
        f"SELECT description FROM starexec.configurations WHERE id={added};"
    )
    owner.post(
        ep.path("delete_configuration"),
        data={"selectedIds[]": added},
        keep=["success", "message"],
    )
    # starexec.DeleteConfigurationById is a SOFT delete: it sets configurations.deleted and flags the solver
    # through UpdateConfigDeletedInSolvers, so the row stays and the live set is what must shrink.
    live = sorted(
        n
        for (n,) in ctx.db.rows(
            f"SELECT name FROM starexec.configurations WHERE solver_id={sid} AND deleted=false ORDER BY name;"
        )
    )
    deleted_flag = ctx.db.scalar(
        f"SELECT deleted FROM starexec.configurations WHERE id={added};"
    )
    config_deleted = ctx.db.scalar(
        f"SELECT config_deleted FROM starexec.solvers WHERE id={sid};"
    )
    script_gone = None
    if ctx.cfg.app_exec and row:
        script_gone = not _exists(ctx, f"{row['path']}/bin/starexec_run_{new_cfg_name}")
    cap.check(
        "3",
        "a configuration is edited and then deleted with its script",
        "edit stores the description; delete removes bin/starexec_run_added33, marks the configuration row"
        " deleted and sets solvers.config_deleted, leaving 'cat' as the solver's only live configuration",
        {
            "description": "E2E c33 edited",
            "live_configs": ["cat"],
            "deleted_flag": "t",
            "solver_config_deleted": "t",
            "script_gone": True,
        },
        {
            "description": desc,
            "live_configs": live,
            "deleted_flag": deleted_flag,
            "solver_config_deleted": config_deleted,
            "script_gone": script_gone,
        },
        desc == "E2E c33 edited"
        and live == ["cat"]
        and deleted_flag == "t"
        and config_deleted == "t"
        and script_gone in (True, None),
        ["edit_configuration", "delete_configuration"],
    )

    # ---- V4 rebuild ----
    before = _solver_row(ctx, sid)
    r = owner.post(
        ep.path("rebuild_solver"), data={"solverid": sid}, keep=["success", "message"]
    )
    done, status, secs = ctx.wait(
        "solver rebuild",
        lambda: (lambda v: (v in ("1", "2"), v))(
            ctx.db.scalar(f"SELECT build_status FROM starexec.solvers WHERE id={sid};")
        ),
        ctx.cfg.timeout("dispatch", 300),
        interval=3,
    )
    cap.check(
        "4",
        "a rebuild leaves the solver in a settled build state",
        "POST /secure/solver/rebuild returns and solvers.build_status settles at BUILT(1) or NOT_BUILT/none(2),"
        " never stuck in the building state",
        {"build_status": "1 or 2"},
        {
            "http": r.status_code,
            "before": before["build_status"] if before else None,
            "after": status,
            "seconds": secs,
            "response": owner.json(r),
        },
        done,
        ["rebuild_solver"],
    )

    # ---- K1 link into another space ----
    target_name = ctx.name("c33_target")
    owner.post(
        ep.path("add_space"),
        data={
            "parent": space,
            "name": target_name,
            "desc": "E2E c33",
            "locked": "false",
            "sticky": "false",
            "addSolver": "on",
            "addJob": "on",
            "addBench": "on",
        },
    )
    target = ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(target_name)} AND sa.space_id={int(space)};"
    )
    if not target:
        raise Blocked("c33 target space not created")
    solvers_before = ctx.db.int(
        "SELECT count(*) FROM starexec.solvers WHERE deleted=false;"
    )
    owner.post(
        ep.path("copy_solver_to_space", spaceId=target),
        data={"selectedIds[]": sid, "copy": "false", "copyToSubspaces": "false"},
        keep=["success", "message"],
    )
    linked = [
        int(s)
        for (s,) in ctx.db.rows(
            f"SELECT solver_id FROM starexec.solver_assoc WHERE space_id={target};"
        )
    ]
    solvers_after_link = ctx.db.int(
        "SELECT count(*) FROM starexec.solvers WHERE deleted=false;"
    )
    cap.check(
        "5",
        "linking a solver adds an association and no second solver",
        "POST /services/spaces/{target}/add/solver with copy=false associates the same solver id with the target"
        " space and creates no new solvers row",
        {"solver_assoc": [sid], "new_solver_rows": 0},
        {
            "solver_assoc": linked,
            "new_solver_rows": solvers_after_link - solvers_before,
        },
        linked == [sid] and solvers_after_link == solvers_before,
        ["copy_solver_to_space"],
    )

    # ---- K2 copy into another space ----
    copy_target_name = ctx.name("c33_copy_target")
    owner.post(
        ep.path("add_space"),
        data={
            "parent": space,
            "name": copy_target_name,
            "desc": "E2E c33",
            "locked": "false",
            "sticky": "false",
            "addSolver": "on",
            "addJob": "on",
            "addBench": "on",
        },
    )
    copy_target = ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(copy_target_name)} AND sa.space_id={int(space)};"
    )
    owner.post(
        ep.path("copy_solver_to_space", spaceId=copy_target),
        data={"selectedIds[]": sid, "copy": "true", "copyToSubspaces": "false"},
        keep=["success", "message"],
    )
    copies = [
        int(s)
        for (s,) in ctx.db.rows(
            f"SELECT solver_id FROM starexec.solver_assoc WHERE space_id={copy_target};"
        )
    ]
    copy_id = copies[0] if len(copies) == 1 else None
    copy_row = _solver_row(ctx, copy_id) if copy_id else None
    copy_cfgs = sorted(configs_of(ctx, copy_id)) if copy_id else None
    cap.check(
        "6",
        "copying a solver creates an independent solver with its own directory and configurations",
        "POST with copy=true associates a NEW solver id whose path differs from the original's, whose directory"
        " exists, and which has the same configuration names",
        {
            "is_new_solver": True,
            "different_path": True,
            "configs": ["cat"],
            "directory_exists": True,
        },
        {
            "associated": copies,
            "copy_id": copy_id,
            "copy_row": copy_row,
            "configs": copy_cfgs,
            "directory_exists": _exists(ctx, copy_row["path"]) if copy_row else None,
        },
        bool(copy_id)
        and copy_id != sid
        and copy_row
        and copy_row["path"] != (row or {}).get("path")
        and copy_cfgs == ["cat"]
        and _exists(ctx, copy_row["path"]) in (True, None),
        ["copy_solver_to_space", "copy_solver_copies"],
    )

    # ---- K5 unlink, then V9 recycle / restore / delete ----
    owner.post(
        ep.path("remove_solver_from_space", spaceId=target),
        data={"selectedIds[]": sid},
        keep=["success", "message"],
    )
    assoc_left = ctx.db.int(
        f"SELECT count(*) FROM starexec.solver_assoc WHERE space_id={target};"
    )
    still = _solver_row(ctx, sid)
    cap.check(
        "7",
        "unlinking removes only the association",
        "POST /services/remove/solver/{target} deletes the solver_assoc row while the solver stays not recycled"
        " and not deleted",
        {"assoc_left": 0, "recycled": "f", "deleted": "f"},
        {"assoc_left": assoc_left, "solver": still},
        assoc_left == 0 and still["recycled"] == "f" and still["deleted"] == "f",
        ["remove_solver_from_space"],
    )
    owner.post(
        ep.path("recycle_solver"),
        data={"selectedIds[]": sid},
        keep=["success", "message"],
    )
    recycled = _solver_row(ctx, sid)
    owner.post(
        ep.path("restore_solver"),
        data={"selectedIds[]": sid},
        keep=["success", "message"],
    )
    restored = _solver_row(ctx, sid)
    owner.post(ep.path("recycle_solver"), data={"selectedIds[]": sid})
    owner.post(
        ep.path("delete_solver"),
        data={"selectedIds[]": sid},
        keep=["success", "message"],
    )
    deleted = _solver_row(ctx, sid)
    dir_gone = not _exists(ctx, (row or {}).get("path")) if ctx.cfg.app_exec else None
    copy_dir = _exists(ctx, copy_row["path"]) if copy_row else None
    cap.check(
        "8",
        "recycle, restore and delete move the solver through its states and free its files",
        "recycle sets recycled; restore clears it; a second recycle then delete sets deleted and removes the"
        " solver's directory. The earlier COPY is unaffected: its directory still exists",
        {
            "recycled": "t",
            "restored": "f",
            "deleted": "t",
            "directory_gone": True,
            "copy_directory": True,
        },
        {
            "recycled": recycled,
            "restored": restored,
            "deleted": deleted,
            "directory_gone": dir_gone,
            "copy_directory": copy_dir,
        },
        recycled["recycled"] == "t"
        and restored["recycled"] == "f"
        and deleted["deleted"] == "t"
        and dir_gone in (True, None)
        and copy_dir in (True, None),
        ["recycle_solver", "restore_solver", "delete_solver"],
    )

    # ---- V2 upload by URL (#221): the same fixture, fetched over http from the stack's own network ----
    folder, archive = posixpath.dirname(fx["path"]), posixpath.basename(fx["path"])
    url_name = ctx.name("c33_url")
    try:
        with serving(ctx, folder, archive) as host:
            # Into c33's own target space, which 33.7 left empty, so the shared space is unchanged.
            r = _upload_by_url(
                owner, target, url_name, "http://%s:%d/%s" % (host, PORT, archive)
            )
        url_rows = ctx.db.int(
            f"SELECT count(*) FROM starexec.solvers WHERE name={literal(url_name)};"
        )
        url_sid = ctx.db.int(
            f"SELECT max(id) FROM starexec.solvers WHERE name={literal(url_name)};"
        )
        got = _served_run_script(owner, url_sid) if url_sid else (None, None, None)
        cap.check(
            "9",
            "a solver uploaded by URL is downloaded and stored byte-exact",
            "POST /secure/upload/solvers with upMethod=URL and an http address on the stack's network creates one"
            " solver, and its download carries exactly the fixture's bin/starexec_run_cat",
            {"solver_rows": 1, "sha256": want},
            {
                "http": r.status_code,
                "redirects": [h.status_code for h in r.history],
                "solver_rows": url_rows,
                "download_http": got[0],
                "entries": got[1],
                "sha256": got[2],
            },
            url_rows == 1 and got[2] == want,
            ["upload_solver", "upload_solver_url", "download", "download_solver"],
        )
    except Blocked as e:
        cap.blocked(
            "9",
            "a solver uploaded by URL is downloaded and stored byte-exact",
            str(e),
            ["upload_solver", "upload_solver_url"],
        )

    # ---- only http and https. Each address names an archive, so only its scheme can be refused ----
    refused = {}
    for scheme, address in (
        ("file", "file:///srv/" + archive),
        ("ftp", "ftp://example.invalid/" + archive),
    ):
        name = ctx.name("c33_" + scheme)
        r = _upload_by_url(owner, target, name, address)
        cookie = (
            (r.headers.get("Set-Cookie") or "").replace("%20", " ").replace("+", " ")
        )
        refused[scheme] = {
            "http": r.status_code,
            "message_seen": NOT_HTTP in (r.text or "") or NOT_HTTP in cookie,
            "solver_rows": ctx.db.int(
                f"SELECT count(*) FROM starexec.solvers WHERE name={literal(name)};"
            ),
        }
    want_refused = {"http": 400, "message_seen": True, "solver_rows": 0}
    cap.check(
        "10",
        "an address that is not http or https is refused",
        "file: and ftp: addresses are answered 400 with Util.DOWNLOADABLE_URL_REQUIRED and create no solver",
        {s: want_refused for s in refused},
        refused,
        all(v == want_refused for v in refused.values()),
        ["upload_solver", "upload_solver_url_scheme", "downloadable_url_required"],
    )
