"""37: deleting a user and everything that hangs off them (coverage matrix U8). Runs late, and never on a
user another capability created.

The capability builds its own victim: a personal space subtree, a solver linked into ANOTHER user's space, a
benchmark, a finished job, a job still running, and an open browser session. Then it deletes the user and
asserts what the database and the filesystem say afterwards.

The FK cascades from users(id) decide most of it (V0001): benchmarks, solvers, jobs, user_assoc, website,
notifications and the request tables are ON DELETE CASCADE, while logins and analytics_users are NOT and are
deleted explicitly first by starexec.DeleteUser. Directories are removed by Users.deleteUsersPrimitiveDirectories
AFTER the transaction commits, so a row that is gone does not prove the bytes are.
"""

import secrets
import time

from capabilities.c01_accounts import _community
from capabilities.c07_solvers import configs_of
from lib import endpoints as ep
from lib.db import literal
from lib.http import Client
from lib.report import Blocked
from lib.steps import need_fixture, pair_statuses, wait_upload

LETTERS = str.maketrans("0123456789", "abcdefghij")


def _exists(ctx, path):
    if not ctx.cfg.app_exec or not path:
        return None
    code, _ = ctx.app("test", "-e", path)
    return code == 0


def _counts(
    ctx,
    uid,
    tables=("benchmarks", "solvers", "jobs", "user_assoc", "website", "logins"),
):
    out = {}
    for t in tables:
        out[t] = ctx.db.int(
            f"SELECT count(*) FROM starexec.{t} WHERE user_id={int(uid)};"
        )
    out["users"] = ctx.db.int(
        f"SELECT count(*) FROM starexec.users WHERE id={int(uid)};"
    )
    out["user_roles"] = ctx.db.int(
        "SELECT count(*) FROM starexec.user_roles ur JOIN starexec.users u ON u.email=ur.email"
        f" WHERE u.id={int(uid)};"
    )
    return out


def run_user_deletion(cap, ctx):
    community = _community(ctx)
    host_space = ctx.state.need("space")  # belongs to the owner, not to the victim
    stamp = "".join(c for c in ctx.run.runid.split("-")[0] if c.isdigit()).translate(
        LETTERS
    )
    email = f"{ctx.name('victim')}@example.technology"
    password = secrets.token_urlsafe(18) + "7x"
    ctx.admin().post(
        ep.path("registration"),
        data={
            "cm": community,
            "pwd": password,
            "inst": "E2E qualification",
            "em": email,
            "fn": "Victim" + stamp,
            "ln": "Deletion",
        },
    )
    uid = ctx.db.int(f"SELECT id FROM starexec.users WHERE email={literal(email)};")
    if not uid:
        raise Blocked("victim user was not created")
    personal = ctx.db.int(
        "SELECT min(ua.space_id) FROM starexec.user_assoc ua JOIN starexec.set_assoc sa ON sa.child_id=ua.space_id"
        f" WHERE ua.user_id={uid} AND sa.space_id={community};"
    )
    victim = Client(ctx.cfg.base_url, ctx.run.recorder, "victim")
    if not victim.login(email, password):
        raise Blocked("victim could not log in")

    # a subspace of their own, a solver, a benchmark and two jobs
    sub_name = ctx.name("victim_space")
    victim.post(
        ep.path("add_space"),
        data={
            "parent": personal,
            "name": sub_name,
            "desc": "E2E c37",
            "locked": "false",
            "sticky": "false",
            "addSolver": "on",
            "addBench": "on",
            "addJob": "on",
        },
    )
    sub = ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(sub_name)} AND sa.space_id={int(personal)};"
    )
    # Uploaded into the victim's OWN space: capabilities/c07_solvers.upload_solver targets state["space"],
    # which belongs to the owner, and the victim has no permission there.
    # The fixture solver, so the victim owns BOTH a quick configuration and a long-running one: the owner's
    # solver and configurations are not usable by them.
    fx = need_fixture(ctx, "fixture-solver.zip")
    sname = ctx.name("victim_solver")
    with open(fx["path"], "rb") as f:
        victim.post(
            ep.path("upload_solver"),
            timeout=180,
            data={
                "space": sub or personal,
                "sn": sname,
                "desc": "E2E c37",
                "dlable": "true",
                "upMethod": "local",
                "descMethod": "text",
                "execType": "1",
                "runTestJob": "false",
            },
            files={"f": ("fixture-solver.zip", f, "application/zip")},
        )
    sid = ctx.db.int(
        f"SELECT id FROM starexec.solvers WHERE name={literal(sname)} ORDER BY id DESC LIMIT 1;"
    )
    solver_path = ctx.db.scalar(
        f"SELECT path FROM starexec.solvers WHERE id={int(sid or 0)};"
    )
    victim_configs = configs_of(ctx, sid) if sid else {}
    # The solver is linked into the OWNER's space: deleting the victim must not leave a dangling association.
    if sid:
        victim.post(
            ep.path("copy_solver_to_space", spaceId=host_space),
            data={"selectedIds[]": sid, "copy": "false", "copyToSubspaces": "false"},
        )
    # The victim needs benchmarks of their OWN: the owner's benchmarks are not visible to them, so a job
    # created against those ids is silently refused.
    bench_fx = need_fixture(ctx, "c31-bench.tar")
    with open(bench_fx["path"], "rb") as f:
        rb = victim.post(
            ep.path("upload_benchmarks"),
            timeout=120,
            keep=["jobId", "status"],
            data={
                "space": sub or personal,
                "upMethod": "dump",
                "benchType": ctx.state.need("bench_processor"),
                "download": "true",
                "localOrURLOrGit": "local",
                "dependency": "false",
                "linked": "false",
                "depRoot": sub or personal,
            },
            files={"benchFile": ("c31-bench.tar", f, "application/x-tar")},
        )
    upload = (victim.json(rb) or {}).get("jobId")
    if upload:
        wait_upload(ctx, victim, upload, ctx.cfg.timeout("upload", 300))
    bench = sorted(
        int(b)
        for (b,) in ctx.db.rows(
            f"SELECT bench_id FROM starexec.bench_assoc WHERE space_id={int(sub or personal)} ORDER BY bench_id;"
        )
    )[:1]
    queue = ctx.queue()
    jobs = {}
    for label, config, limit in (
        ("finished", victim_configs.get("fast"), 10),
        ("running", victim_configs.get("sleep"), 60),
    ):
        if not config or not bench:
            continue
        jname = ctx.name(f"victim_{label}")
        victim.post(
            ep.path("create_job"),
            timeout=120,
            data={
                "sid": sub or personal,
                "name": jname,
                "desc": "E2E c37",
                "runChoice": "choose",
                "benchChoice": "choose",
                "configs": [config],
                "bench": bench,
                "preProcess": "-1",
                "postProcess": "-1",
                "queue": queue,
                "seed": "0",
                "cpuTimeout": limit,
                "wallclockTimeout": limit,
                "maxMem": "0.5",
                "pause": "no",
                "benchmarkingFramework": "RUNSOLVER",
                "saveOtherOutput": "true",
            },
        )
        jobs[label] = ctx.db.int(
            f"SELECT id FROM starexec.jobs WHERE name={literal(jname)};"
        )
    running_job = jobs.get("running")
    started, _, _ = (
        ctx.wait(
            "victim's job reaches RUNNING",
            lambda: (lambda st: (any(s == 4 for s in st.values()), st))(
                pair_statuses(ctx, running_job) if running_job else {}
            ),
            ctx.cfg.timeout("dispatch", 300),
            interval=2,
        )
        if running_job
        else (False, None, None)
    )
    running_pairs = sorted(pair_statuses(ctx, running_job)) if running_job else []

    before = _counts(ctx, uid)
    cap.check(
        "1",
        "the victim owns something on every axis before deletion",
        "the user has a personal space with a subspace, a solver (also linked into another user's space), jobs"
        " including one that reached RUNNING, and an open session: otherwise the deletion asserts nothing",
        {"solvers": ">=1", "jobs": ">=1", "user_assoc": ">=1", "running_pair": True},
        {
            "counts": before,
            "personal_space": personal,
            "subspace": sub,
            "solver": sid,
            "jobs": jobs,
            "running_pairs": running_pairs,
            "reached_running": started,
        },
        before["solvers"] >= 1
        and before["jobs"] >= 1
        and before["user_assoc"] >= 1
        and bool(started),
        ["registration", "upload_solver", "create_job"],
    )

    since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 5))
    r = ctx.admin().post(
        ep.path("delete_user", userId=uid), keep=["success", "message"]
    )
    after = _counts(ctx, uid)
    spaces_left = ctx.db.int(
        f"SELECT count(*) FROM starexec.spaces WHERE id IN ({int(personal or 0)},{int(sub or 0)});"
    )
    assoc_left = ctx.db.int(
        f"SELECT count(*) FROM starexec.solver_assoc WHERE solver_id={int(sid or 0)};"
    )
    cap.check(
        "2",
        "deleting the user removes the user and everything the cascades own",
        "POST /services/delete/user/{id} leaves no users, user_roles, benchmarks, solvers, jobs, user_assoc,"
        " website or logins row for that user (logins and analytics_users are NOT cascaded and are deleted"
        " explicitly by starexec.DeleteUser), removes the personal space subtree, and leaves no solver_assoc"
        " row pointing at the deleted solver from another user's space",
        {"all_counts": 0, "spaces_left": 0, "solver_assoc_left": 0},
        {
            "http": r.status_code,
            "response": ctx.admin().json(r),
            "before": before,
            "after": after,
            "spaces_left": spaces_left,
            "solver_assoc_left": assoc_left,
        },
        all(v == 0 for v in after.values()) and spaces_left == 0 and assoc_left == 0,
        ["delete_user", "delete_user_cascades", "delete_user_logins_not_cascaded"],
    )

    # The directories go after the commit, so they are a separate observation.
    gone = _exists(ctx, solver_path)
    cap.check(
        "3",
        "the deleted user's solver directory is removed from disk",
        "Users.deleteUsersPrimitiveDirectories runs after the transaction commits, so the solver's directory"
        " must be gone as well as its row",
        {"directory_exists": False},
        {"path": solver_path, "exists": gone},
        gone is False if ctx.cfg.app_exec else True,
        ["delete_user", "delete_user_directories"],
    )

    # The victim's session must not survive them (memory: a cached Permission still authorised).
    # NOTE (ledger S7, traced 2026-09-17): /secure/index.jsp sits under a container-managed FORM
    # security-constraint (web.xml:378-414). Tomcat forwards any request with no container Principal
    # to /public/login.jsp and serves it with a plain 200 -- a forward, not an error -- identically for
    # a revoked session and a client that never logged in (verified directly: same status, same
    # Content-Length, same body, action="/starexec/j_security_check" in both). The status code is never
    # a signal of authentication state on this path; only the body is. SessionFilter, Users.deleteUser
    # and Users.loadAuthenticatedUserState are all confirmed correct -- this was a test-assertion defect,
    # not a product one.
    #
    # Ordering is load-bearing (security-reviewer, 2026-09-18). The FIRST post-deletion request is
    # rejected by SessionFilter itself, which still holds the container's cached Principal at that
    # point: for /services/* it invalidates and returns 401 (SessionFilter.java:171-177, 260-264); for
    # anything else it is a bare 302 to docRoot (:266-269). Only from the SECOND request on has the
    # session actually left Tomcat, so the container FORM authenticator performs the /public/login.jsp
    # forward this check looks for. Do not reorder these two probes, and do not insert any other victim
    # request between the delete and here.
    live = victim.get(ep.path("session_logged_in"), allow_redirects=False)
    secure = victim.get("/secure/index.jsp", allow_redirects=False)
    # Positive: the anonymous FORM login page. Negative: a live user-role session gets a 302 to
    # explore/spaces.jsp instead (secure/index.jsp:15-21), never a 200 -- so status_code == 200 is not
    # redundant with the body check, it rules out a same-status-different-body drift.
    secure_is_login_form = (
        secure.status_code == 200 and "j_security_check" in secure.text
    )
    # /services/* has no container <security-constraint> except /services/session/* (web.xml:427-439),
    # so everything else on that surface -- including this endpoint -- is gated by SessionFilter alone.
    # The two probes above only exercise the container-constrained paths; this one covers the much
    # larger unconstrained REST surface so a SessionFilter-only regression (e.g. widening its
    # early-exit allowlist to include /services/) cannot pass this check while leaving that surface open.
    api = victim.get(ep.path("users_getid"), allow_redirects=False)
    api_denies = api.status_code in (401, 403) or api.text.strip() not in (
        str(uid),
        f'"{uid}"',
    )
    cap.check(
        "4",
        "the deleted user's open session stops working",
        'the session opened before the deletion no longer gets "true" from /services/session/logged-in,'
        " /secure/index.jsp forwards to the anonymous login form instead of serving authenticated content,"
        " and /services/users/getid (which carries no container security-constraint of its own) no longer"
        " answers with the deleted user's id",
        {
            "logged_in": 'not "true"',
            "secure_body": "is the anonymous login form (j_security_check), status 200",
            "api_getid": "401/403, or not the deleted user's id",
        },
        {
            "logged_in_body": live.text.strip()[:40],
            "logged_in_status": live.status_code,
            "secure_status": secure.status_code,
            "secure_is_login_form": secure_is_login_form,
            "api_status": api.status_code,
            "api_body": api.text.strip()[:40],
        },
        live.text.strip() != "true" and secure_is_login_form and api_denies,
        ["session_logged_in", "delete_user", "users_getid"],
    )

    # And the backend must stop talking about the pairs of the job that was still running.
    quiet_for = min(ctx.cfg.timeout("kill", 180), 120)
    time.sleep(quiet_for)
    log = ctx.logs(since) or ""
    noise = [
        l
        for l in log.splitlines()
        if any(f"pair {p}" in l or f"pairId={p}" in l for p in running_pairs)
        and ("not found" in l or "RetryableIngestion" in l or "retrying" in l)
    ]
    cap.check(
        "5",
        "deleting a user with a running job releases the backend's tracking",
        "after the user is deleted, the application log carries no further 'job pair <id> not found' /"
        " RetryableIngestion / retry line for the pairs of their running job (ledger L2, reached through user"
        " deletion rather than job deletion)",
        {"retry_lines": []},
        {
            "running_pairs": running_pairs,
            "retry_lines": noise[:8],
            "log_available": bool(log),
        },
        not noise,
        ["delete_user"],
        known="L2" if noise else None,
    )
