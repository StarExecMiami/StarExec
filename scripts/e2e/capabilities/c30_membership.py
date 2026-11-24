"""30: community leadership and membership on a community this capability creates (coverage matrix L2,
L4-L7, S11, K10).

Oracles: user_assoc and permissions rows (is_leader and the ten add/remove columns), users.disk_quota and
job_pair_quota for a promoted leader, community_requests rows, and the approval mail in the stack's local
catcher addressed to the new leader (which is what c02.4 could not observe on a leaderless community).
"""

import re
import secrets
import time

from capabilities.c01_accounts import _capture_messages, _community
from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked

LETTERS = str.maketrans("0123456789", "abcdefghij")
# Ledger M3: one unroutable leader address drops the join-request mail for every leader (CONFIRMED).
MAIL_LEDGER_ID = "M3"

# Why the join-request mail never arrives, recorded with the check that observes it.
FINDING_TEXT = (
    "Mail.sendCommunityRequest collects every leader address and Mail.mail adds them all to ONE message "
    "(Mail.java:77-79). The seeded admin account's address is the literal string admin, which javax.mail "
    "rejects, so the whole send throws AddressException; Mail.mail swallows it (:83-88) and the caller still "
    "logs that the acceptance email was sent. App log evidence: a 'Sending email: STAREXEC - Request to join "
    "... to [admin, <leader>@example.technology]' line immediately followed by 'Failed to send email via SMTP: "
    "javax.mail.internet.AddressException: Missing final @domain in string admin', no such message in the "
    "catcher, and every single-recipient mail of the same run delivered."
)
PERMS = ["add_solver", "add_bench", "add_user", "add_space", "add_job",
         "remove_solver", "remove_bench", "remove_user", "remove_space", "remove_job", "is_leader"]


def _new_user(ctx, role, community):
    stamp = "".join(c for c in ctx.run.runid.split("-")[0] if c.isdigit()).translate(LETTERS)
    email = f"{ctx.name(role)}@example.technology"
    ctx.admin().post(ep.path("registration"), data={
        "cm": community, "pwd": secrets.token_urlsafe(18) + "7x", "inst": "E2E qualification",
        # Validator.isValidUserName is letters only, so the role's digits are translated, never passed through.
        "em": email, "fn": "Memb" + stamp, "ln": role.translate(LETTERS).capitalize()})
    uid = ctx.db.int(f"SELECT id FROM starexec.users WHERE email={literal(email)};")
    if not uid:
        raise Blocked(f"could not create {role}")
    return uid, email


def _perm(ctx, uid, space):
    rows = ctx.db.rows(
        f"SELECT {', '.join('p.' + c for c in PERMS)} FROM starexec.user_assoc ua JOIN starexec.permissions p"
        f" ON p.id=ua.permission WHERE ua.user_id={int(uid)} AND ua.space_id={int(space)};")
    return dict(zip(PERMS, rows[0])) if rows else None


def _members(ctx, uid, spaces):
    return sorted(int(s) for (s,) in ctx.db.rows(
        f"SELECT space_id FROM starexec.user_assoc WHERE user_id={int(uid)}"
        f" AND space_id IN ({','.join(map(str, spaces))});"))


def _mailed(ctx, to, code, verdict):
    """True when a message to `to` carries the link conf=<code>&lead=<verdict> for exactly this request."""
    pattern = re.compile(rf"[?&]conf={re.escape(code)}&(?:amp;)?lead={verdict}\b")
    deadline = time.monotonic() + ctx.cfg.timeout("smtp", 60)
    while time.monotonic() < deadline:
        for m in _capture_messages(ctx.cfg.smtp_capture):
            if to in m["to"] and pattern.search(m["text"] + m["html"]):
                return True
        time.sleep(2)
    return False


def _request_code(ctx, uid, comm):
    # The code is the request's identity; it is used to match the mail and is never recorded.
    return ctx.db.scalar(
        f"SELECT code FROM starexec.community_requests WHERE user_id={int(uid)} AND community={int(comm)};",
        record=False)


def run_membership(cap, ctx):
    admin = ctx.admin()
    home = _community(ctx)

    # ---- a community of our own, so no existing capability's community changes ----
    cname = ctx.name("community")
    admin.post(ep.path("add_space"), data={"parent": 1, "name": cname, "desc": "E2E community 30",
                                            "locked": "false", "sticky": "false"})
    comm = ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(cname)} AND sa.space_id=1;")
    if not comm:
        raise Blocked("admin could not create a community under root")
    leader, leader_email = _new_user(ctx, "leader30", comm)
    j1, _ = _new_user(ctx, "joinera30", home)
    j2, _ = _new_user(ctx, "joinerb30", home)
    ctx.state.set("leader_user", leader)
    ctx.state.set("membership_community", comm)

    # ---- L4 make leader: a non-admin member cannot promote itself ----
    self_lead = ctx.session("leader30", leader)
    denied = self_lead.post(ep.path("make_leader", spaceId=comm), data={"selectedIds[]": leader},
                            keep=["success", "message"])
    cap.check("1-neg", "a non-admin member cannot promote itself",
              "makeLeader called by the (not yet leader) member on itself leaves permissions.is_leader false",
              "f", {"is_leader": (_perm(ctx, leader, comm) or {}).get("is_leader"), "response": self_lead.json(denied)},
              (_perm(ctx, leader, comm) or {}).get("is_leader") == "f", ["make_leader"])

    base_quota = ctx.db.rows(f"SELECT disk_quota, job_pair_quota FROM starexec.users WHERE id={j1};")[0]
    before = _perm(ctx, leader, comm)
    r = admin.post(ep.path("make_leader", spaceId=comm), data={"selectedIds[]": leader}, keep=["success", "message"])
    after = _perm(ctx, leader, comm)
    quota = ctx.db.rows(f"SELECT disk_quota, job_pair_quota FROM starexec.users WHERE id={leader};")[0]
    want_quota = [str(int(1.5 * int(base_quota[0]))), str(int(1.5 * int(base_quota[1])))]
    cap.check(
        "1",
        "make leader grants full permissions and community-leader quotas",
        "POST /services/makeLeader/{community} (admin) turns the member's permissions row into all-true with"
        " is_leader, and sets users.disk_quota/job_pair_quota to 1.5x a regular user's (R.CL_* constants)",
        {"before_leader": "f", "after": {c: "t" for c in PERMS}, "quota": want_quota},
        {"before": before, "after": after, "quota": quota, "regular_quota": base_quota, "response": admin.json(r)},
        bool(before) and before["is_leader"] == "f" and after == {c: "t" for c in PERMS} and quota == want_quota,
        ["make_leader", "make_leader_quota", "cl_quota"],
    )

    # ---- L6 join request: approve (mail to the new leader) ----
    member = ctx.session("joinera30", j1)
    lead = ctx.session("leader30", leader, fresh=True)

    # The subspace is created BY THE LEADER. Users.associate copies into the subspaces the caller can see
    # (SpaceSecurity/Spaces.trimSubSpaces), so a subspace the leader is not a member of is legitimately skipped.
    sub_name = ctx.name("community_sub")
    lead.post(ep.path("add_space"), data={"parent": comm, "name": sub_name, "desc": "E2E c30",
                                          "locked": "false", "sticky": "false", "addUser": "on",
                                          "removeUser": "on"})
    sub = ctx.db.int("SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
                     f" WHERE s.name={literal(sub_name)} AND sa.space_id={comm};")
    if not sub:
        raise Blocked("the leader could not create the community's subspace")

    # ---- L6 join request, approved through the link the request itself carries ----
    # The approval uses the request row's own code, so a mail defect cannot hide the approval semantics;
    # whether the mail carrying that code reaches the leader is a separate check below.
    member.post(ep.path("community_request"), data={"cm": comm, "msg": "E2E join request 30"})
    pending = ctx.db.int(f"SELECT count(*) FROM starexec.community_requests WHERE user_id={j1} AND community={comm};")
    code_j1 = _request_code(ctx, j1, comm)
    if code_j1:
        lead.get(ep.path("verify_email"), params={"conf": code_j1, "lead": "approve"})
    left = ctx.db.int(f"SELECT count(*) FROM starexec.community_requests WHERE user_id={j1} AND community={comm};")
    cap.check(
        "2",
        "a join request is stored and the leader's approval makes the user a member",
        "POST to_community/request writes one community_requests row; the leader visiting"
        " /public/verification/email?conf=<that row's code>&lead=approve deletes the row and adds"
        " user_assoc(joiner, community)",
        {"pending": 1, "left": 0, "member_of": [comm]},
        {"pending": pending, "left": left, "member_of": _members(ctx, j1, [comm])},
        pending == 1 and left == 0 and _members(ctx, j1, [comm]) == [comm],
        ["community_request", "community_request_verdict", "verify_email"],
    )

    # ---- L6 decline ----
    second = ctx.session("joinerb30", j2)
    second.post(ep.path("community_request"), data={"cm": comm, "msg": "E2E join request 30b"})
    pending2 = ctx.db.int(f"SELECT count(*) FROM starexec.community_requests WHERE user_id={j2} AND community={comm};")
    code_j2 = _request_code(ctx, j2, comm)
    if code_j2:
        lead.get(ep.path("verify_email"), params={"conf": code_j2, "lead": "decline"})
    left2 = ctx.db.int(f"SELECT count(*) FROM starexec.community_requests WHERE user_id={j2} AND community={comm};")
    cap.check(
        "3",
        "a declined request leaves no membership",
        "the second joiner's request writes one row; the leader's decline deletes it and adds no user_assoc",
        {"pending": 1, "left": 0, "member_of": []},
        {"pending": pending2, "left": left2, "member_of": _members(ctx, j2, [comm])},
        pending2 == 1 and left2 == 0 and _members(ctx, j2, [comm]) == [],
        ["community_request", "community_request_verdict", "verify_email"],
    )

    # ---- the mail that carries those links ----
    if not ctx.cfg.smtp_capture:
        cap.blocked("2-mail", "the leader receives the join request by mail", "no smtp_capture configured")
    else:
        leaders_now = ctx.db.rows(
            "SELECT u.email FROM starexec.user_assoc ua JOIN starexec.permissions p ON p.id=ua.permission"
            f" JOIN starexec.users u ON u.id=ua.user_id WHERE ua.space_id={comm} AND p.is_leader ORDER BY u.email;")
        mailed = bool(code_j1) and _mailed(ctx, leader_email, code_j1, "approve")
        ok_mail = cap.check(
            "2-mail",
            "the join request reaches the community's leader by mail",
            "with the community holding a leader whose address is routable, a message carrying"
            " conf=<request code>&lead=approve arrives for that leader in the stack's mail catcher",
            {"mail_to_leader": True},
            {"mail_to_leader": mailed, "leaders": leaders_now},
            mailed,
            ["community_request", "mail_leader_param"],
            known=MAIL_LEDGER_ID,
        )
        if not ok_mail:
            cap.finding("MAIL-one-bad-recipient",
                        "one unroutable leader address silently drops the join-request mail for every leader",
                        FINDING_TEXT, {"leaders": leaders_now})

    # ---- K10 / L1 add user with copyToSubspaces, then L2 remove with hierarchy ----
    # j1 was approved above, so it is a community member; SpaceSecurity.canAddUserToSpace:771-778 refuses a
    # non-member, which is why the declined j2 cannot be used here.
    lead.post(ep.path("space_add_user", spaceId=comm), data={"selectedIds[]": j1, "copyToSubspaces": "true"},
              keep=["success", "message"])
    added = _members(ctx, j1, [comm, sub])
    cap.check("4", "adding a user to subspaces too", "POST /services/spaces/{community}/add/user with"
              " copyToSubspaces=true adds user_assoc for the community and its subspace",
              sorted([comm, sub]), added, added == sorted([comm, sub]), ["space_add_user"])
    lead.post(ep.path("remove_user", spaceId=comm), data={"selectedIds[]": j1, "hierarchy": "true"},
              keep=["success", "message"])
    removed = _members(ctx, j1, [comm, sub])
    cap.check("5", "removing a user from the hierarchy", "POST /services/remove/user/{community} with hierarchy=true"
              " deletes the user's user_assoc rows in the community and its subspace; the user row remains",
              {"member_of": [], "user_exists": 1},
              {"member_of": removed, "user_exists": ctx.db.int(f"SELECT count(*) FROM starexec.users WHERE id={j1};")},
              removed == [] and ctx.db.int(f"SELECT count(*) FROM starexec.users WHERE id={j1};") == 1,
              ["remove_user"])

    # ---- S11 leave community ----
    if _members(ctx, j1, [comm]) != [comm]:
        lead.post(ep.path("space_add_user", spaceId=comm), data={"selectedIds[]": j1, "copyToSubspaces": "true"})
    member = ctx.session("joinera30", j1, fresh=True)
    member.post(ep.path("leave_space", spaceId=comm), keep=["success", "message"])
    left = _members(ctx, j1, [comm, sub])
    cap.check("6", "leaving a community removes every membership below it",
              "POST /services/leave/space/{community} as the member deletes user_assoc for every closure descendant"
              " (LeaveHierarchy)", [], left, left == [], ["leave_space", "leave_hierarchy_sql"])

    # ---- L5 demote, including the only leader ----
    admin.post(ep.path("demote_leader", spaceId=comm, userId=leader), keep=["success", "message"])
    perm = _perm(ctx, leader, comm)
    leaders = ctx.db.int(
        "SELECT count(*) FROM starexec.user_assoc ua JOIN starexec.permissions p ON p.id=ua.permission"
        f" WHERE ua.space_id={comm} AND p.is_leader;")
    want = {c: "t" for c in PERMS}
    want["is_leader"] = "f"
    cap.check(
        "7",
        "demotion clears is_leader and keeps the other permissions",
        "POST /services/demoteLeader/{community}/{leader} (admin) leaves the permissions row all-true except"
        " is_leader=false. At the commit under test there is no last-leader guard (SpaceSecurity.canDemoteLeader"
        " checks admin only), so the community is left with 0 leaders; that count is recorded, not asserted.",
        want,
        {"permissions": perm, "leaders_left": leaders},
        perm == want,
        ["demote_leader", "demote_leader_rule"],
    )
    if leaders == 0:
        cap.finding(
            "L-last-leader",
            "demoting the only leader leaves a community without a leader",
            f"community {comm}: after demoteLeader the count of is_leader memberships is 0. With no leader,"
            " Mail.sendCommunityRequest has no recipient and join requests are silently unanswerable (see c02.4).",
            {"community": comm, "leaders_left": leaders},
        )
