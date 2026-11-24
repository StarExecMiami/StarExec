"""27: account lifecycle on a user this capability creates: profile fields, websites, password change,
email change, password reset, suspend/reinstate, logout (coverage matrix U3-U7, U9, U12).

Every assertion reads the users / user_roles / website / change_email_requests / pass_reset_request rows,
not only the HTTP answer. Passwords live in memory for the capability's duration and are never recorded;
password hashes are compared inside SQL (md5 of the stored hash) with recording off.
"""

import re
import secrets
import time

from capabilities.c01_accounts import _capture_messages, _community
from lib import endpoints as ep
from lib.db import literal
from lib.http import Client
from lib.report import Blocked

LETTERS = str.maketrans("0123456789", "abcdefghij")


def _password():
    # R.PASSWORD_PATTERN: letters, plus at least one digit or symbol.
    return secrets.token_urlsafe(18).replace("-", "a").replace("_", "b") + "7x"


def _user_row(ctx, uid):
    rows = ctx.db.rows(
        f"SELECT email, first_name, last_name, institution FROM starexec.users WHERE id={int(uid)};"
    )
    return (
        dict(zip(["email", "first_name", "last_name", "institution"], rows[0]))
        if rows
        else None
    )


def _hash_digest(ctx, uid):
    """md5 of the stored password hash: detects a change without recording the hash."""
    return ctx.db.scalar(
        f"SELECT md5(password) FROM starexec.users WHERE id={int(uid)};", record=False
    )


def _roles(ctx, uid):
    return sorted(
        r[0]
        for r in ctx.db.rows(
            "SELECT ur.role FROM starexec.user_roles ur JOIN starexec.users u ON u.email=ur.email"
            f" WHERE u.id={int(uid)};"
        )
    )


def _login(ctx, label, email, password):
    c = Client(ctx.cfg.base_url, ctx.run.recorder, label)
    return c, c.login(email, password)


def _wait_mail(ctx, address, pattern):
    cfgc = ctx.cfg.smtp_capture
    deadline = time.monotonic() + ctx.cfg.timeout("smtp", 60)
    while time.monotonic() < deadline:
        for m in _capture_messages(cfgc):
            if address in m["to"]:
                found = re.search(pattern, m["text"] + m["html"])
                if found:
                    return found.group(1)
        time.sleep(2)
    return None


def run_account_lifecycle(cap, ctx):
    community = _community(ctx)
    stamp = "".join(c for c in ctx.run.runid.split("-")[0] if c.isdigit()).translate(
        LETTERS
    )
    first, last = "Acct" + stamp, "Lifecycle"
    email = f"{ctx.name('acct')}@example.technology"
    password = _password()

    # ---- create (admin registration with a password known only to this capability) ----
    r = ctx.admin().post(
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
    cap.check(
        "1",
        "admin creates the lifecycle user",
        "POST registration as admin creates one users row for the email, with role 'user'",
        {"role": ["user"]},
        {
            "http": r.status_code,
            "user_id": uid,
            "roles": _roles(ctx, uid) if uid else None,
        },
        bool(uid) and _roles(ctx, uid) == ["user"],
        ["registration", "registration_params"],
    )
    if not uid:
        raise Blocked("lifecycle user was not created")
    ctx.state.set("acct_user", uid)

    user, ok = _login(ctx, "acct", email, password)
    cap.check(
        "2",
        "the lifecycle user logs in",
        "form login then logged-in = true",
        True,
        ok,
        ok,
        ["login", "session_logged_in"],
    )
    if not ok:
        raise Blocked("lifecycle user cannot log in")

    # ---- U4 profile fields ----
    wanted = {
        "firstname": ("first_name", "Ada-Marie"),
        "lastname": ("last_name", "O'Neil"),
        "institution": ("institution", "E2E Institute 27"),
    }
    responses = {}
    for attr, (_, value) in wanted.items():
        resp = user.post(
            ep.path("edit_user_attr", userId=uid),
            json={"attribute": attr, "value": value},
            keep=["success", "message"],
        )
        responses[attr] = user.json(resp)
    row = _user_row(ctx, uid)
    observed = {col: row[col] for col, _ in wanted.values()}
    expected = {col: value for col, value in wanted.values()}
    cap.check(
        "3",
        "profile fields are stored exactly",
        "POST /services/edit/user/{id} with a JSON {attribute, value} body updates users.first_name, last_name"
        " and institution to the submitted strings (hyphen and apostrophe are inside R.USER_NAME_PATTERN)",
        expected,
        {"db": observed, "responses": responses},
        observed == expected,
        ["edit_user_attr", "user_name_pattern"],
    )
    before = _user_row(ctx, uid)["first_name"]
    resp = user.post(
        ep.path("edit_user_attr", userId=uid),
        json={"attribute": "firstname", "value": "Ada<b>"},
        keep=["success", "message"],
    )
    after = _user_row(ctx, uid)["first_name"]
    cap.check(
        "3-neg",
        "a first name outside the pattern is refused and not stored",
        "the same call with '<' in the value is refused (success=false) and users.first_name is unchanged",
        {"success": False, "first_name": before},
        {"response": user.json(resp), "first_name": after},
        after == before and (user.json(resp) or {}).get("success") is False,
        ["edit_user_attr", "user_name_pattern"],
    )

    # ---- U12 websites ----
    url, wname = "https://example.org/e2e-27", "E2E site"
    resp = user.post(
        ep.path("website_add", type="user", id=uid),
        data={"name": wname, "url": url},
        keep=["success", "message"],
    )
    sites = ctx.db.rows(
        f"SELECT id, url, name FROM starexec.website WHERE user_id={uid};"
    )
    cap.check(
        "4",
        "a user website is stored",
        "POST /services/website/add/user/{id} creates exactly one website row with that user_id, url and name",
        [[url, wname]],
        {"rows": [s[1:] for s in sites], "response": user.json(resp)},
        [list(s[1:]) for s in sites] == [[url, wname]],
        ["website_add"],
    )
    if sites:
        resp = user.post(
            ep.path("website_delete", websiteId=sites[0][0]),
            keep=["success", "message"],
        )
        left = ctx.db.int(
            f"SELECT count(*) FROM starexec.website WHERE id={int(sites[0][0])};"
        )
        cap.check(
            "4-del",
            "the website is deleted",
            "POST /services/websites/delete/{id} removes the row",
            0,
            {"rows": left, "response": user.json(resp)},
            left == 0,
            ["website_delete"],
        )

    # ---- U5 password change ----
    old_digest, new_password = _hash_digest(ctx, uid), _password()
    resp = user.post(
        ep.path("edit_user_password", userId=uid),
        data={"current": password, "newpass": new_password, "confirm": new_password},
        keep=["success", "message"],
    )
    _, old_ok = _login(ctx, "acct-oldpw", email, password)
    _, new_ok = _login(ctx, "acct-newpw", email, new_password)
    changed = _hash_digest(ctx, uid) != old_digest
    cap.check(
        "5",
        "password change replaces the stored hash",
        "POST /services/edit/user/password/{id} changes users.password; afterwards the old password no longer"
        " logs in and the new one does",
        {"hash_changed": True, "old_login": False, "new_login": True},
        {
            "hash_changed": changed,
            "old_login": old_ok,
            "new_login": new_ok,
            "response": user.json(resp),
        },
        changed and not old_ok and new_ok,
        ["edit_user_password", "login"],
    )
    if new_ok:
        password = new_password

    # ---- U6 email change (user-initiated, verified through the local mail catcher) ----
    new_email = f"{ctx.name('acct2')}@example.technology"
    resp = user.post(
        ep.path("edit_user_attr", userId=uid),
        json={"attribute": "email", "value": new_email},
        keep=["success", "message"],
    )
    pending = ctx.db.rows(
        f"SELECT new_email FROM starexec.change_email_requests WHERE user_id={uid};"
    )
    unchanged = _user_row(ctx, uid)["email"]
    cap.check(
        "6",
        "an email change is held until verified",
        "a non-admin email edit writes change_email_requests(user, new_email) and leaves users.email unchanged",
        {"pending": [[new_email]], "email": email},
        {"pending": pending, "email": unchanged, "response": user.json(resp)},
        [list(p) for p in pending] == [[new_email]] and unchanged == email,
        ["edit_user_attr", "email_change_admin_direct"],
    )
    if not ctx.cfg.smtp_capture:
        cap.blocked(
            "6-verify", "email change verification", "no smtp_capture configured"
        )
    else:
        code = _wait_mail(ctx, new_email, r"[?&]changeEmail=([A-Za-z0-9-]+)")
        if not code:
            cap.check(
                "6-verify",
                "email change verification mail arrives",
                f"a message to {new_email} carries changeEmail=<code>",
                "code",
                None,
                False,
                ["mail_change_email_param"],
            )
        else:
            user.get(ep.path("verify_email"), params={"changeEmail": code})
            row = _user_row(ctx, uid)
            left = ctx.db.int(
                f"SELECT count(*) FROM starexec.change_email_requests WHERE user_id={uid};"
            )
            roles = _roles(ctx, uid)
            cap.check(
                "6-verify",
                "verifying the code switches the email and keeps the role",
                "GET /public/verification/email?changeEmail=<code> in the user's session sets users.email to the"
                " new address, deletes the request, and user_roles follows (FK ON UPDATE CASCADE)",
                {"email": new_email, "requests": 0, "roles": ["user"]},
                {"email": row["email"], "requests": left, "roles": roles},
                row["email"] == new_email and left == 0 and roles == ["user"],
                ["verify_email", "mail_change_email_param"],
            )
            if row["email"] == new_email:
                email = new_email

    # ---- U7 password reset through the public page ----
    if not ctx.cfg.smtp_capture:
        cap.blocked("7", "password reset", "no smtp_capture configured")
    else:
        anon = Client(ctx.cfg.base_url, ctx.run.recorder, "anonymous-reset")
        page = anon.get(ep.path("password_reset_page"))
        token = re.search(
            r'name="csrfToken" value="([^"]+)"', page.text
        )  # the value is used, never recorded
        old_digest = _hash_digest(ctx, uid)
        anon.post(
            ep.path("password_reset"),
            data={
                "csrfToken": token.group(1) if token else "",
                "fn": _user_row(ctx, uid)["first_name"],
                "ln": _user_row(ctx, uid)["last_name"],
                "em": email,
            },
        )
        requested = ctx.db.int(
            f"SELECT count(*) FROM starexec.pass_reset_request WHERE user_id={uid};"
        )
        code = (
            _wait_mail(ctx, email, r"[?&]reset=([A-Za-z0-9-]+)") if requested else None
        )
        if code:
            anon.get(ep.path("password_reset"), params={"reset": code})
        consumed = ctx.db.int(
            f"SELECT count(*) FROM starexec.pass_reset_request WHERE user_id={uid};"
        )
        changed = _hash_digest(ctx, uid) != old_digest
        _, old_ok = _login(ctx, "acct-prereset", email, password)
        cap.check(
            "7",
            "password reset issues, mails and redeems a single-use code",
            "POST /public/reset_password (with the page's CSRF token and matching names) writes one"
            " pass_reset_request row and mails reset=<code>; GET with the code deletes the row, replaces"
            " users.password, and the previous password stops working. The temporary password shown on"
            " temp_pass.jsp is not read.",
            {
                "requested": 1,
                "mailed": True,
                "consumed": 0,
                "hash_changed": True,
                "old_login": False,
            },
            {
                "requested": requested,
                "mailed": bool(code),
                "consumed": consumed,
                "hash_changed": changed,
                "old_login": old_ok,
                "csrf_token_found": bool(token),
            },
            requested == 1 and bool(code) and consumed == 0 and changed and not old_ok,
            [
                "password_reset",
                "password_reset_page",
                "password_reset_redeem",
                "password_reset_csrf",
            ],
        )
        # The temporary password is unknown by design; a disposable bcrypt password takes over.
        password = ctx.set_password(uid)

    # ---- U9 suspend / reinstate, including an already-open session ----
    live, ok = _login(ctx, "acct-live", email, password)
    if not ok:
        raise Blocked("could not open a session before suspension")
    admin = ctx.admin()
    admin.post(ep.path("suspend_user", userId=uid), keep=["success", "message"])
    roles = _roles(ctx, uid)
    live_after = live.get(ep.path("session_logged_in"))
    live_page = live.get("/secure/index.jsp", allow_redirects=False)
    _, fresh_ok = _login(ctx, "acct-suspended", email, password)
    cap.check(
        "8",
        "suspension denies new logins and the open session",
        "POST /services/suspend/user/{id} sets user_roles to exactly 'suspended'; a fresh login is refused and"
        " the session opened before suspension no longer reaches /secure (Users.loadAuthenticatedUserState)",
        {
            "roles": ["suspended"],
            "fresh_login": False,
            "open_session_secure_status": "not 200",
        },
        {
            "roles": roles,
            "fresh_login": fresh_ok,
            "open_session_logged_in": live_after.text.strip(),
            "open_session_secure_status": live_page.status_code,
        },
        roles == ["suspended"] and not fresh_ok and live_page.status_code != 200,
        ["suspend_user", "suspended_session_denied", "login"],
    )
    admin.post(ep.path("reinstate_user", userId=uid), keep=["success", "message"])
    roles = _roles(ctx, uid)
    user, back_ok = _login(ctx, "acct-reinstated", email, password)
    cap.check(
        "8-reinstate",
        "reinstatement restores the role and login",
        "POST /services/reinstate/user/{id} sets user_roles to exactly 'user' and login works again",
        {"roles": ["user"], "login": True},
        {"roles": roles, "login": back_ok},
        roles == ["user"] and back_ok,
        ["reinstate_user", "login"],
    )

    # The session opened before the suspension must stay dead after reinstatement: only a new login may
    # bring the user back.
    revived = live.get("/secure/index.jsp", allow_redirects=False)
    revived_flag = live.get(ep.path("session_logged_in")).text.strip()
    cap.check(
        "8-old-session",
        "the pre-suspension session does not come back after reinstatement",
        "after POST /services/reinstate/user/{id}, the cookie jar opened before the suspension still does not"
        " reach /secure/index.jsp with 200 (a fresh login is required)",
        {"secure_status": "not 200"},
        {"secure_status": revived.status_code, "logged_in": revived_flag},
        revived.status_code != 200,
        ["reinstate_user", "suspended_session_denied"],
    )

    # ---- U3 logout ----
    before = user.get(ep.path("session_logged_in")).text.strip()
    user.post(ep.path("session_logout"))
    after = user.get(ep.path("session_logged_in"), allow_redirects=False)
    body = after.text.strip()[:40]
    # /services is a secured path: once the session is invalidated the container answers the login form (or a
    # redirect to it), never "true". Asserting "false" would be asserting the container's behaviour, not StarExec's.
    cap.check(
        "9",
        "logout ends the session",
        'logged-in is "true"; after POST /services/session/logout the same cookie jar no longer gets "true" from'
        " /services/session/logged-in (the secured path falls back to the login page)",
        {"before": "true", "after": 'anything but "true"'},
        {"before": before, "after_status": after.status_code, "after_body": body},
        before == "true" and body != "true",
        ["session_logout", "session_logged_in"],
    )
