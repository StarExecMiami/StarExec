"""7: solver upload, build status, configurations. 18: #101 solver description with + and -."""

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture

BUILT = "1"  # SolverBuildStatusCode.BUILT(1); a package without a build script is set to 1 (UploadSolver.java)


def upload_solver(ctx, client, name, path, filename, desc_method="text", desc="E2E"):
    with open(path, "rb") as f:
        r = client.post(
            ep.path("upload_solver"),
            timeout=180,
            data={
                "space": ctx.state.need("space"),
                "sn": name,
                "desc": desc,
                "dlable": "true",
                "upMethod": "local",
                "descMethod": desc_method,
                "execType": "1",
                "runTestJob": "false",
            },
            files={"f": (filename, f, "application/zip")},
        )
    sid = ctx.db.int(
        f"SELECT id FROM starexec.solvers WHERE name={literal(name)} ORDER BY id DESC LIMIT 1;"
    )
    return r, sid


def configs_of(ctx, sid):
    return {
        n: int(i)
        for i, n in ctx.db.rows(
            f"SELECT id, name FROM starexec.configurations WHERE solver_id={sid} ORDER BY name;"
        )
    }


def run_solvers(cap, ctx):
    owner = ctx.owner()
    e_fx = need_fixture(ctx, "E-3.3.3-sequential.zip")
    e_config_name = ctx.fx.get("eprover_config", "E-3.3.3-sequential")
    r, sid = upload_solver(
        ctx, owner, ctx.name("E"), e_fx["path"], "E-3.3.3-sequential.zip"
    )
    row = ctx.db.rows(
        f"SELECT build_status, deleted, recycled FROM starexec.solvers WHERE id={sid or 0};"
    )
    cap.check(
        "1",
        f"{e_config_name} uploads and is marked built",
        "POST /secure/upload/solvers with the verified zip creates"
        " one solver whose build_status is BUILT(1), not deleted or recycled",
        [BUILT, "f", "f"],
        {"http": r.status_code, "row": row[0] if row else None},
        bool(row) and row[0] == [BUILT, "f", "f"],
        ["upload_solver", "upload_solver_params"],
    )
    if not sid:
        raise Blocked("E solver not created")
    ctx.state.set("solver_e", sid)
    cfg = configs_of(ctx, sid)
    cap.check(
        "2",
        "exactly the starexec_run_ configuration is discovered",
        f"configurations for the solver are exactly {{{e_config_name}}}: the zip's bin/starexec_run_{e_config_name};"
        " non-starexec_run_* scripts must not become configurations",
        [e_config_name],
        sorted(cfg),
        sorted(cfg) == [e_config_name],
        ["upload_solver"],
    )
    if cfg.get(e_config_name):
        ctx.state.set("config_e", cfg[e_config_name])
    r = owner.get(ep.path("solver_buildoutput", id=sid))
    cap.check(
        "3",
        "build output endpoint answers for a non-build solver",
        "GET /services/solvers/{id}/buildoutput"
        " returns 200 or an explicit not-found for a package without a build script (recorded, not judged"
        " beyond 'no 5xx')",
        "status < 500",
        r.status_code,
        r.status_code < 500,
        ["solver_buildoutput"],
    )

    fx = need_fixture(ctx, "fixture-solver.zip")
    r, fsid = upload_solver(
        ctx, owner, ctx.name("fixture"), fx["path"], "fixture-solver.zip"
    )
    fcfg = configs_of(ctx, fsid) if fsid else {}
    want = sorted(ctx.fx["fixture_configs"])
    cap.check(
        "4",
        "the generated fixture solver exposes its five configurations",
        f"configurations of the fixture solver are exactly {want}",
        want,
        sorted(fcfg),
        sorted(fcfg) == want,
        ["upload_solver"],
    )
    if fsid:
        ctx.state.set("solver_fixture", fsid)
        ctx.state.set("fixture_configs", fcfg)

    vx = need_fixture(ctx, "verbatim-szs.zip")
    r, vsid = upload_solver(
        ctx, owner, ctx.name("verbatim"), vx["path"], "verbatim-szs.zip"
    )
    vcfg = configs_of(ctx, vsid) if vsid else {}
    cap.check(
        "5",
        "the verbatim-SZS stage-1 solver uploads",
        "configurations are exactly {verbatim}",
        ["verbatim"],
        sorted(vcfg),
        sorted(vcfg) == ["verbatim"],
        ["upload_solver"],
    )
    if vcfg.get("verbatim"):
        ctx.state.set("config_verbatim", vcfg["verbatim"])


def run_description(cap, ctx):
    owner = ctx.owner()
    valid = need_fixture(ctx, "desc-valid-solver.zip")
    name = ctx.name("desc_ok")
    # descMethod other than text/file takes the default branch, which reads starexec_description.txt.
    r, sid = upload_solver(
        ctx,
        owner,
        name,
        valid["path"],
        "desc-valid-solver.zip",
        desc_method="upload",
        desc="",
    )
    stored = ctx.db.scalar(
        f"SELECT encode(convert_to(description,'UTF8'),'hex') FROM starexec.solvers WHERE id={sid or 0};"
    )
    want = ctx.fx["descriptions"]["valid"].encode().hex()
    cap.check(
        "1",
        "#101: a description with '+' and '-' is accepted byte-exact",
        "uploading a package whose starexec_description.txt is exactly"
        f" {ctx.fx['descriptions']['valid']!r} creates the solver, and solvers.description hex-equals the file",
        want,
        {"http": r.status_code, "solver": sid, "stored_hex": stored},
        bool(sid) and stored == want,
        [
            "upload_solver",
            "solver_desc_file_branch",
            "solver_desc_validate",
            "solver_desc_path",
        ],
    )
    invalid = need_fixture(ctx, "desc-invalid-solver.zip")
    bad_name = ctx.name("desc_bad")
    r, bad = upload_solver(
        ctx,
        owner,
        bad_name,
        invalid["path"],
        "desc-invalid-solver.zip",
        desc_method="upload",
        desc="",
    )
    cap.check(
        "2",
        "negative control: a description containing '<' is refused",
        "uploading the same package with '<' in starexec_description.txt creates no solver row"
        " (UploadSolverStatus.DESCRIPTION_MALFORMED)",
        None,
        {"http": r.status_code, "solver": bad},
        bad is None,
        ["upload_solver", "solver_desc_validate"],
    )
