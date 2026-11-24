"""35: settings profiles beyond create/select (coverage matrix T3-T5): delete the default profile, clear the
default, ownership, and community default settings.

Oracles: default_settings rows, users.default_settings_profile (FK ON DELETE SET NULL), and the community
profile row (setting_type 1). The default-benchmark list (T5, default_bench_assoc) needs a benchmark and
is exercised in c32.
"""

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked

COLS = "prim_id, setting_type, cpu_timeout, clock_timeout, dependencies_enabled, benchmarking_framework"


def _profile(ctx, client, uid, name, cpu):
    client.post(ep.path("add_profile"), data={
        "name": name, "userIdOfOwner": uid, "postp": "-1", "prep": "-1", "benchp": "-1",
        "cpu": str(cpu), "wall": str(cpu + 1), "dep": "false", "mem": "0.5", "benchmarkingFramework": "RUNSOLVER"})
    rows = ctx.db.rows(f"SELECT id, {COLS} FROM starexec.default_settings WHERE name={literal(name)};")
    return rows


def _default_of(ctx, uid):
    return ctx.db.scalar(f"SELECT coalesce(default_settings_profile::text,'NULL') FROM starexec.users WHERE id={int(uid)};")


def run_settings_profiles(cap, ctx):
    uid = ctx.state.need("acct_user")
    user = ctx.session("acct", uid)

    p1 = _profile(ctx, user, uid, ctx.name("prof1")[:32], 11)
    p2 = _profile(ctx, user, uid, ctx.name("prof2")[:32], 21)
    cap.check(
        "1",
        "two user profiles are created with their values",
        "POST /secure/add/profile twice writes one default_settings row each: prim_id=user, setting_type 0,"
        " cpu/wall as submitted, dependencies off, RUNSOLVER",
        [[str(uid), "0", "11", "12", "f", "RUNSOLVER"], [str(uid), "0", "21", "22", "f", "RUNSOLVER"]],
        [r[1:] for r in p1 + p2],
        [r[1:] for r in p1 + p2] == [[str(uid), "0", "11", "12", "f", "RUNSOLVER"],
                                     [str(uid), "0", "21", "22", "f", "RUNSOLVER"]],
        ["add_profile", "add_profile_params", "setting_type_community"],
    )
    if len(p1) != 1 or len(p2) != 1:
        raise Blocked("profiles not created")
    id1, id2 = int(p1[0][0]), int(p2[0][0])

    # ---- T3 delete the profile that is the user's default ----
    user.post(ep.path("set_default_settings", id=id1, userIdOfOwner=uid))
    was = _default_of(ctx, uid)
    r = user.post(ep.path("delete_profile", id=id1), keep=["success", "message"])
    gone = ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id1};")
    now = _default_of(ctx, uid)
    cap.check(
        "2",
        "deleting the default profile removes it and clears the user's default",
        "with users.default_settings_profile = p1, POST /services/delete/defaultSettings/{p1} deletes the row and"
        " the user's default becomes NULL (FK ON DELETE SET NULL); p2 is untouched",
        {"default_before": str(id1), "rows": 0, "default_after": "NULL", "p2_rows": 1},
        {"default_before": was, "rows": gone, "default_after": now,
         "p2_rows": ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};"),
         "response": user.json(r)},
        was == str(id1) and gone == 0 and now == "NULL"
        and ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};") == 1,
        ["delete_profile", "users_default_profile_fk", "set_default_settings"],
    )

    # ---- T4 clear default ----
    user.post(ep.path("set_default_settings", id=id2, userIdOfOwner=uid))
    was = _default_of(ctx, uid)
    user.post(ep.path("clear_default_profile", userIdOfOwner=uid), keep=["success", "message"])
    cap.check(
        "3",
        "clearing the default keeps the profile",
        "after selecting p2, POST /services/clear/defaultSettings/{user} sets users.default_settings_profile to NULL"
        " and the p2 row still exists",
        {"before": str(id2), "after": "NULL", "p2_rows": 1},
        {"before": was, "after": _default_of(ctx, uid),
         "p2_rows": ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};")},
        was == str(id2) and _default_of(ctx, uid) == "NULL"
        and ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};") == 1,
        ["clear_default_profile"],
    )

    # ---- ownership: another user cannot delete or clear ----
    other = ctx.state.get("leader_user")
    if not other:
        cap.blocked("4", "another user cannot delete a profile", "state has no leader_user (run c30)")
    else:
        stranger = ctx.session("leader30", other)
        user.post(ep.path("set_default_settings", id=id2, userIdOfOwner=uid))
        stranger.post(ep.path("delete_profile", id=id2), keep=["success", "message"])
        stranger.post(ep.path("clear_default_profile", userIdOfOwner=uid), keep=["success", "message"])
        cap.check(
            "4",
            "another user can neither delete the profile nor clear its owner's default",
            "delete/defaultSettings/{p2} and clear/defaultSettings/{owner} called by a different non-admin user"
            " leave the p2 row and users.default_settings_profile = p2",
            {"p2_rows": 1, "default": str(id2)},
            {"p2_rows": ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};"),
             "default": _default_of(ctx, uid)},
            ctx.db.int(f"SELECT count(*) FROM starexec.default_settings WHERE id={id2};") == 1
            and _default_of(ctx, uid) == str(id2),
            ["delete_profile", "clear_default_profile", "settings_security_user"],
        )

    # ---- community default settings ----
    comm = ctx.state.get("membership_community")
    if not comm:
        cap.blocked("5", "community default settings edit", "state has no membership_community (run c30)")
        return
    admin = ctx.admin()
    admin.get("/secure/edit/community.jsp", params={"cid": comm})  # creates the lazy row; body not read
    row = ctx.db.rows(f"SELECT id FROM starexec.default_settings WHERE prim_id={int(comm)} AND setting_type=1;")
    if len(row) != 1:
        cap.check("5", "the community has exactly one community profile", "one default_settings row with"
                  " prim_id=community and setting_type 1 after the community page is opened", 1, len(row),
                  False, ["community_settings_lazy", "setting_type_community"])
        return
    sid = int(row[0][0])
    for attr, val in (("CpuTimeout", "37"), ("ClockTimeout", "41"), ("DependenciesEnabled", "1")):
        admin.post(ep.path("edit_community_defaults", attr=attr, id=sid), data={"val": val}, keep=["success", "message"])
    got = ctx.db.rows(f"SELECT cpu_timeout, clock_timeout, dependencies_enabled FROM starexec.default_settings WHERE id={sid};")[0]
    cap.check(
        "5",
        "community default settings are edited attribute by attribute",
        "POST /services/edit/defaultSettings/{CpuTimeout|ClockTimeout|DependenciesEnabled}/{community profile}"
        " stores 37, 41 and true in that row",
        ["37", "41", "t"], got, got == ["37", "41", "t"],
        ["edit_community_defaults", "community_settings_lazy"],
    )
