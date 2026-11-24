"""1: admin creates a user who logs in, picture upload with exact byte retrieval.
2: public registration through SMTP capture, email verification, leader approval (optional)."""

import hashlib
import re
import secrets
import time

import requests

from lib import endpoints as ep
from lib.db import literal
from lib.http import Client
from lib.report import Blocked
from lib.steps import need_fixture

LETTERS = str.maketrans("0123456789", "abcdefghij")


def _person(ctx, role):
    stamp = "".join(c for c in ctx.run.runid.split("-")[0] if c.isdigit())
    first = ("Esq" + stamp).translate(
        LETTERS
    )  # Validator.isValidUserName: letters only
    return first, role.capitalize(), f"{ctx.name(role)}@example.technology"


def _community(ctx):
    if ctx.cfg.community_id:
        return int(ctx.cfg.community_id)
    cid = ctx.db.int("SELECT min(child_id) FROM starexec.set_assoc WHERE space_id=1;")
    if not cid:
        raise Blocked("no community space under root; set community_id in the config")
    return cid


def _leaders(ctx, community):
    """The addresses Mail.sendCommunityRequest mails: every leader of the community."""
    return [
        e
        for (e,) in ctx.db.rows(
            "SELECT u.email FROM starexec.user_assoc ua JOIN starexec.permissions p ON p.id=ua.permission"
            f" JOIN starexec.users u ON u.id=ua.user_id WHERE ua.space_id={int(community)} AND p.is_leader"
            " ORDER BY u.email;"
        )
    ]


def _give_community_a_leader(ctx, community):
    """Make a new member of `community` its leader through the product API; return the member's id or None.

    The admin registers the member into the community, then promotes it with POST makeLeader, as the
    community page does. Permissions are never written directly. 30.1 checks makeLeader itself.
    """
    admin = ctx.admin()
    first, last, email = _person(ctx, "leader")
    admin.post(
        ep.path("registration"),
        data={
            "cm": community,
            "pwd": secrets.token_urlsafe(24),
            "inst": "E2E qualification",
            "em": email,
            "fn": first,
            "ln": last,
        },
    )
    uid = ctx.db.int(f"SELECT id FROM starexec.users WHERE email={literal(email)};")
    if uid:
        admin.post(
            ep.path("make_leader", spaceId=community),
            data={"selectedIds[]": uid},
            keep=["success", "message"],
        )
    return uid


def create_user(cap, ctx, role, cid):
    admin = ctx.admin()
    community = _community(ctx)
    first, last, email = _person(ctx, role)
    password = secrets.token_urlsafe(24)
    r = admin.post(
        ep.path("registration"),
        data={
            "cm": community,
            "pwd": password,
            "inst": "E2E qualification",
            "em": email,
            "fn": first,
            "ln": last,
        },
    )
    uid = ctx.db.int(f"SELECT id FROM starexec.users WHERE email={literal(email)};")
    member = ctx.db.int(
        f"SELECT count(*) FROM starexec.user_assoc WHERE user_id={uid or 0} AND space_id={community};"
    )
    cap.check(
        cid,
        f"admin creates the {role} user",
        f"POST registration as admin creates exactly one users row for"
        f" {email} and associates it with community {community}",
        {"users_rows": 1, "community_member": 1},
        {"http": r.status_code, "user_id": uid, "community_member": member},
        bool(uid) and member == 1,
        ["registration", "registration_params"],
    )
    if not uid:
        raise Blocked(f"{role} user was not created")
    ctx.state.set(f"{role}_user", uid)
    ctx.state.set("community", community)
    # Registration.java creates a personal subspace for admin-created users (Communities.createPersonalSubspace).
    personal = ctx.db.int(
        f"SELECT min(ua.space_id) FROM starexec.user_assoc ua JOIN starexec.set_assoc sa ON sa.child_id=ua.space_id"
        f" WHERE ua.user_id={uid} AND sa.space_id={community};"
    )
    ctx.state.set(f"{role}_personal_space", personal)
    client = Client(ctx.cfg.base_url, ctx.run.recorder, role)
    ok = client.login(email, password)
    cap.check(
        cid + "-login",
        f"{role} logs in with the password the admin set",
        "form login followed by GET /services/session/logged-in returns 200 'true'",
        "true",
        ok,
        ok,
        ["login", "session_logged_in"],
    )
    ctx._sessions[role] = client
    return uid, client


def run_accounts(cap, ctx):
    uid, owner = create_user(cap, ctx, "owner", "1")
    create_user(cap, ctx, "second", "2")

    pic = need_fixture(ctx, "picture.png")
    data = open(pic["path"], "rb").read()
    r = owner.post(
        ep.path("upload_picture"),
        data={"Id": uid, "type": "user"},
        files={"f": ("e2e.png", data, "image/png")},
    )
    cap.check(
        "3",
        "profile picture upload is accepted",
        "POST /secure/upload/pictures as the owner, for the owner,"
        " ends in a redirect (UploadPicture success path) rather than an error status",
        "3xx then 200",
        {"status": r.status_code, "redirects": [h.status_code for h in r.history]},
        r.status_code == 200 and bool(r.history),
        ["upload_picture", "upload_picture_params"],
    )
    got = owner.get(ep.path("get_picture"), params={"Id": uid, "type": "uorg"})
    digest = hashlib.sha256(got.content).hexdigest()
    cap.check(
        "4",
        "the original picture is retrieved byte-exact",
        "GET /secure/get/pictures?type=uorg returns bytes whose sha256 equals the uploaded file's"
        " (uorg is the original written unmodified; thumbnails are re-encoded and not compared)",
        pic["sha256"],
        {"status": got.status_code, "sha256": digest, "bytes": len(got.content)},
        got.status_code == 200 and digest == pic["sha256"],
        ["get_picture", "get_picture_uorg", "upload_picture_original_write"],
    )
    r = ctx.second().post(
        ep.path("upload_picture"),
        data={"Id": uid, "type": "user"},
        files={"f": ("x.png", data, "image/png")},
    )
    cap.check(
        "5",
        "another user cannot replace the owner's picture",
        "POST /secure/upload/pictures by the second user for the owner's id is refused with 403",
        403,
        r.status_code,
        r.status_code == 403,
        ["upload_picture"],
    )


def _maildev_list(base):
    # MailDev 1.x serves the REST API at /email; 2.x (the image on the Podman stack) at /api/email.
    for path in ("/api/email", "/email"):
        r = requests.get(base + path, timeout=15)
        if r.status_code == 200:
            return r.json()
    raise Blocked(f"MailDev API not found at {base}/api/email or {base}/email")


def _capture_messages(cfg):
    kind = cfg["kind"]
    if kind == "maildev":
        return [
            {
                "to": [a.get("address") for a in m.get("to", [])],
                "subject": m.get("subject"),
                "text": m.get("text") or "",
                "html": m.get("html") or "",
            }
            for m in _maildev_list(cfg["url"].rstrip("/"))
        ]
    if kind == "mailpit":
        out = []
        for m in (
            requests.get(cfg["url"].rstrip("/") + "/api/v1/messages", timeout=15)
            .json()
            .get("messages", [])
        ):
            full = requests.get(
                cfg["url"].rstrip("/") + f"/api/v1/message/{m['ID']}", timeout=15
            ).json()
            out.append(
                {
                    "to": [a.get("Address") for a in full.get("To", [])],
                    "subject": full.get("Subject"),
                    "text": full.get("Text") or "",
                    "html": full.get("HTML") or "",
                }
            )
        return out
    raise Blocked(f"unsupported smtp_capture kind {kind!r}")


def run_registration(cap, ctx):
    cfgc = ctx.cfg.smtp_capture
    if not cfgc:
        cap.blocked(
            "1",
            "public registration with email verification",
            "no smtp_capture configured",
        )
        return
    community = _community(ctx)
    first, last, email = _person(ctx, "registrant")
    # The join request is mailed to the community's leaders when the registrant verifies (Verify.java:204),
    # so the community needs a leader before 2.3. A fresh stack's test community has none.
    promoted = None
    leaders = _leaders(ctx, community)
    if not leaders:
        promoted = _give_community_a_leader(ctx, community)
        leaders = _leaders(ctx, community)
    anon = Client(ctx.cfg.base_url, ctx.run.recorder, "anonymous")
    r = anon.post(
        ep.path("registration"),
        data={
            "cm": community,
            "pwd": secrets.token_urlsafe(24),
            "inst": "E2E qualification",
            "em": email,
            "fn": first,
            "ln": last,
            "msg": "E2E registration request",
        },
    )
    pending = ctx.db.int(
        f"SELECT count(*) FROM starexec.users WHERE email={literal(email)};"
    )
    cap.check(
        "1",
        "anonymous registration request is stored",
        "POST registration without a session creates one"
        " users row for the registrant (unverified)",
        1,
        {"http": r.status_code, "rows": pending},
        pending == 1,
        ["registration", "registration_params"],
    )

    code = None
    deadline = time.monotonic() + ctx.cfg.timeout("smtp", 60)
    while time.monotonic() < deadline and code is None:
        for m in _capture_messages(cfgc):
            if email in m["to"]:
                found = re.search(r"[?&]conf=([A-Za-z0-9-]+)", m["text"] + m["html"])
                if found:
                    code = found.group(1)
        time.sleep(2)
    cap.check(
        "2",
        "verification mail is delivered to the capture",
        f"a message addressed to {email} arrives within"
        " the smtp timeout and carries a conf= activation code",
        "code present",
        bool(code),
        bool(code),
        ["mail_code_param"],
    )
    if not code:
        return
    r = anon.get(ep.path("verify_email"), params={"conf": code})
    cap.check(
        "3",
        "the activation link is accepted",
        "GET /public/verification/email?conf=<code> redirects to the"
        " activation message page",
        "public/messages/email_activated.jsp",
        {"status": r.status_code, "path": r.url.split("?")[0].rsplit("/", 1)[-1]},
        r.status_code == 200 and "email_activated" in r.url,
        ["verify_email", "mail_code_param"],
    )
    # Mail.sendCommunityRequest mails every leader of the community. Without one no approval mail can
    # exist, so the check has no subject; that happens only if makeLeader failed above (30.1 checks it).
    if not leaders:
        cap.blocked(
            "4",
            "the leader receives an approval request",
            f"community {community} has no leader, and makeLeader did not give it one (see 30.1)",
            ["make_leader"],
        )
        return
    leader_code = None
    deadline = time.monotonic() + ctx.cfg.timeout("smtp", 60)
    while time.monotonic() < deadline and leader_code is None:
        for m in _capture_messages(cfgc):
            body = m["text"] + m["html"]
            if set(m["to"]) & set(leaders) and (
                email in body or f"{first} {last}" in body
            ):
                found = re.search(
                    r"[?&]conf=([A-Za-z0-9-]+)&(?:amp;)?lead=approve", body
                )
                if found:
                    leader_code = found.group(1)
        time.sleep(2)
    # Ledger M3: Mail.sendCommunityRequest puts every leader on ONE message, so a single address that
    # javax.mail rejects (the seeded admin's is the bare word "admin") drops the mail for all of them.
    unroutable = [a for a in leaders if "@" not in a]
    cap.check(
        "4",
        "the leader receives an approval request",
        "a community-request mail addressed to the community's leaders, naming the registrant, carries"
        " an approve link (conf=...&lead=approve)",
        "link present",
        {
            "link": bool(leader_code),
            "leaders": leaders,
            "leader_made_by_harness": promoted,
        },
        bool(leader_code),
        ["mail_leader_param", "verify_sends_community_request", "make_leader"],
        known="M3" if unroutable else None,
    )
    if not leader_code:
        return
    r = ctx.admin().get(
        ep.path("verify_email"), params={"conf": leader_code, "lead": "approve"}
    )
    uid = ctx.db.int(f"SELECT id FROM starexec.users WHERE email={literal(email)};")
    member = ctx.db.int(
        f"SELECT count(*) FROM starexec.user_assoc WHERE user_id={uid or 0} AND space_id={community};"
    )
    cap.check(
        "5",
        "approval makes the registrant a community member",
        "after the leader approves, user_assoc holds the registrant in the community",
        1,
        {"http": r.status_code, "member": member},
        member == 1,
        ["verify_email", "mail_leader_param"],
    )
