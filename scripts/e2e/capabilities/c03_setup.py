"""3: subspace and permissions. 4/5: benchmark and post processors. 6: default settings profile."""

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture

PERM_COLUMNS = [
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
    "is_leader",
]


def _perm(ctx, uid, space):
    rows = ctx.db.rows(
        f"SELECT {', '.join('p.' + c for c in PERM_COLUMNS)} FROM starexec.user_assoc ua"
        f" JOIN starexec.permissions p ON p.id=ua.permission WHERE ua.user_id={uid} AND ua.space_id={space};"
    )
    return dict(zip(PERM_COLUMNS, rows[0])) if rows else None


def run_spaces(cap, ctx):
    owner_id, second_id, personal = ctx.state.need(
        "owner_user", "second_user", "owner_personal_space"
    )
    owner = ctx.owner()
    name = ctx.name("space")
    r = owner.post(
        ep.path("add_space"),
        data={
            "parent": personal,
            "name": name,
            "desc": "E2E subspace",
            "locked": "false",
            "sticky": "false",
            "addBench": "on",
            "addSolver": "on",
            "addSpace": "on",
            "addJob": "on",
        },
    )
    sid = ctx.db.int(
        f"SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(name)} AND sa.space_id={personal};"
    )
    cap.check(
        "1",
        "owner creates a subspace under the personal space",
        "POST /secure/add/space creates exactly one space with that name whose parent (set_assoc) is the"
        " owner's personal space",
        {"created": True},
        {"http": r.status_code, "space_id": sid},
        bool(sid),
        ["add_space", "add_space_params"],
    )
    if not sid:
        raise Blocked("subspace not created")
    ctx.state.set("space", sid)
    perm = _perm(ctx, owner_id, sid)
    cap.check(
        "2",
        "the creator leads the new space",
        "user_assoc/permissions for the owner on the new space has is_leader = t",
        "t",
        perm and perm["is_leader"],
        bool(perm) and perm["is_leader"] == "t",
        ["add_space"],
    )

    second = ctx.second()
    denied_name = ctx.name("space_denied")
    r = second.post(
        ep.path("add_space"),
        data={
            "parent": sid,
            "name": denied_name,
            "desc": "negative control",
            "locked": "false",
            "sticky": "false",
        },
    )
    created = ctx.db.int(
        f"SELECT count(*) FROM starexec.spaces WHERE name={literal(denied_name)};"
    )
    cap.check(
        "3",
        "a non-member cannot create a subspace in it",
        "POST /secure/add/space by the second user (not a member) creates no space",
        0,
        {"http": r.status_code, "rows": created},
        created == 0,
        ["add_space"],
    )

    r = owner.post(
        ep.path("space_add_user", spaceId=sid),
        data={"selectedIds[]": second_id, "copyToSubspaces": "false"},
        keep=["success", "message"],
    )
    member = _perm(ctx, second_id, sid)
    cap.check(
        "4",
        "leader adds the second user",
        "POST /services/spaces/{sid}/add/user makes the second user a"
        " member (a user_assoc row exists)",
        "member",
        member is not None,
        member is not None,
        ["space_add_user"],
    )
    r = owner.post(
        ep.path("space_perm_edit", spaceId=sid, userId=second_id),
        data={
            "addBench": "true",
            "removeBench": "false",
            "addSolver": "false",
            "removeSolver": "false",
            "addJob": "false",
            "removeJob": "false",
            "addUser": "false",
            "removeUser": "false",
            "addSpace": "false",
            "removeSpace": "false",
            "isLeader": "false",
        },
        keep=["success"],
    )
    member = _perm(ctx, second_id, sid)
    want = {c: ("t" if c == "add_bench" else "f") for c in PERM_COLUMNS}
    cap.check(
        "5",
        "leader sets exact permissions",
        "after POST edit/perm the second user's permissions row equals"
        " exactly add_bench only",
        want,
        member,
        member == want,
        ["space_perm_edit"],
    )
    r = second.post(
        ep.path("space_perm_get", spaceId=sid, userId=second_id)
    )  # @POST, RESTServices.java:3326
    body = second.json(r) or {}
    cap.check(
        "6",
        "the member reads back its own permissions",
        "GET /services/space/{sid}/perm/{uid} as that user reports addBenchmark true and addSolver false",
        {"addBenchmark": True, "addSolver": False},
        {k: body.get(k) for k in ("addBenchmark", "addSolver", "isLeader")},
        body.get("addBenchmark") is True and body.get("addSolver") is False,
        ["space_perm_get"],
    )


def _processor(cap, ctx, cid, kind, archive):
    admin = ctx.admin()
    community = ctx.state.need("community")
    name = ctx.name(kind + "_processor")
    fx = need_fixture(ctx, archive)
    with open(fx["path"], "rb") as f:
        r = admin.post(
            ep.path("processor_manager"),
            timeout=120,
            data={
                "action": "add",
                "name": name,
                "desc": "E2E",
                "uploadMethod": "local",
                "com": community,
                "type": kind,
            },
            files={"file": (archive, f, "application/zip")},
        )
    rows = ctx.db.rows(
        f"SELECT id, processor_type, community FROM starexec.processors WHERE name={literal(name)};"
    )
    want_type = {"bench": "3", "post": "2"}[kind]
    ok = len(rows) == 1 and rows[0][1] == want_type and rows[0][2] == str(community)
    cap.check(
        cid,
        f"{kind} processor upload",
        f"POST /secure/processors/manager action=add type={kind} with the"
        f" verified {archive} creates one processors row with processor_type={want_type} in community {community}",
        {"rows": 1, "processor_type": want_type, "community": str(community)},
        {"http": r.status_code, "rows": rows},
        ok,
        ["processor_manager", "processor_params"],
    )
    if ok:
        ctx.state.set(kind + "_processor", int(rows[0][0]))


def run_bench_processor(cap, ctx):
    _processor(cap, ctx, "1", "bench", "TPTPBenchmarkProcessor.zip")


def run_post_processor(cap, ctx):
    _processor(cap, ctx, "1", "post", "SZSPostProcessor.zip")


def run_settings(cap, ctx):
    uid, post, bench = ctx.state.need("owner_user", "post_processor", "bench_processor")
    owner = ctx.owner()
    name = ctx.name("settings")
    r = owner.post(
        ep.path("add_profile"),
        data={
            "name": name,
            "userIdOfOwner": uid,
            "postp": post,
            "prep": "-1",
            "benchp": bench,
            "cpu": "10",
            "wall": "10",
            "dep": "true",
            "mem": "0.5",
            "benchmarkingFramework": "RUNSOLVER",
        },
    )
    rows = ctx.db.rows(
        f"SELECT id, prim_id, post_processor, bench_processor, cpu_timeout, clock_timeout,"
        f" dependencies_enabled FROM starexec.default_settings WHERE name={literal(name)};"
    )
    want = [str(uid), str(post), str(bench), "10", "10", "t"]
    ok = len(rows) == 1 and rows[0][1:] == want
    cap.check(
        "1",
        "settings profile is created with the submitted values",
        "POST /secure/add/profile creates one"
        " default_settings row for the owner with post/bench processor, cpu 10, wall 10, dependencies on",
        want,
        {"http": r.status_code, "rows": rows},
        ok,
        ["add_profile", "add_profile_params"],
    )
    if not rows:
        raise Blocked("settings profile not created")
    pid = int(rows[0][0])
    ctx.state.set("settings", pid)
    r = owner.post(
        ep.path("set_default_settings", id=pid, userIdOfOwner=uid), keep=["success"]
    )
    current = ctx.db.int(
        f"SELECT default_settings_profile FROM starexec.users WHERE id={uid};"
    )
    cap.check(
        "2",
        "the profile becomes the owner's default",
        "POST /services/set/defaultSettings/{id}/{owner} sets"
        " users.default_settings_profile to the new profile",
        pid,
        current,
        current == pid,
        ["set_default_settings"],
    )
