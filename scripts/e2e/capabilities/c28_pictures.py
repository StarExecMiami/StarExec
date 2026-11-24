"""28: profile picture replace, delete and the default image (coverage matrix P2, P3, P4).

Bytes are compared by sha256 of what GET /secure/get/pictures serves, and, where app_exec is configured,
of the files under <app_data_root>/pictures. P3 (delete) has no endpoint at the commit under test; it is
a product-fix item and is recorded SKIPPED-GATED until one exists.
"""

import hashlib

from lib import endpoints as ep
from lib.fixtures import DEFAULT_PICTURE_SHA256
from lib.steps import need_fixture

# An id no primitive of any type has a picture for: GetPicture does not check existence, only the file.
NO_PICTURE_ID = 2147480001


def _served(client, kind, pid):
    r = client.get(ep.path("get_picture"), params={"Id": pid, "type": kind})
    return r.status_code, hashlib.sha256(r.content).hexdigest(), r.content[:2] == b"\xff\xd8", len(r.content)


def _on_disk(ctx, uid):
    """sha256 of users/Pic<uid>_org.jpg and _thn.jpg inside the app, or None when app_exec is absent."""
    if not ctx.cfg.app_exec:
        return None
    base = f"{ctx.cfg.app_data_root}/pictures/users/Pic{int(uid)}"
    out = {}
    for suffix in ("_org.jpg", "_thn.jpg"):
        code, text = ctx.app("sh", "-c", f'sha256sum "{base}{suffix}" 2>/dev/null || true')
        out[suffix] = text.split()[0] if text.strip() else None
    return out


def run_pictures(cap, ctx):
    uid = ctx.state.need("acct_user")
    user = ctx.session("acct", uid)
    a = need_fixture(ctx, "picture.png")
    b = need_fixture(ctx, "picture-b.png")

    # ---- P4: defaults ----
    served = {k: _served(user, k, pid) for k, pid in
              [("uorg", uid), ("uthn", uid), ("sorg", NO_PICTURE_ID), ("borg", NO_PICTURE_ID)]}
    disk = _on_disk(ctx, uid)
    cap.check(
        "1",
        "a user and primitives without a picture get the shipped default image",
        "before any upload, uorg/uthn for the user and sorg/borg for an id with no picture file serve bytes"
        " whose sha256 equals default-pics/*/Pic0.jpg at the commit (copied by Dockerfile:293), and no"
        " Pic<uid> file exists",
        {"sha256": DEFAULT_PICTURE_SHA256, "status": 200, "on_disk": {"_org.jpg": None, "_thn.jpg": None}},
        {"served": served, "on_disk": disk},
        all(s[0] == 200 and s[1] == DEFAULT_PICTURE_SHA256 for s in served.values())
        and (disk is None or disk == {"_org.jpg": None, "_thn.jpg": None}),
        ["get_picture", "get_picture_default", "get_picture_types", "default_pics_copy", "picture_path"],
    )

    # ---- P2: upload, then replace ----
    shas = {}
    for label, fx in (("first", a), ("replacement", b)):
        data = open(fx["path"], "rb").read()
        r = user.post(ep.path("upload_picture"), data={"Id": uid, "type": "user"},
                      files={"f": (f"{label}.png", data, "image/png")})
        org = _served(user, "uorg", uid)
        thn = _served(user, "uthn", uid)
        disk = _on_disk(ctx, uid)
        shas[label] = {"http": r.status_code, "org": org, "thn": thn, "disk": disk}
    first, repl = shas["first"], shas["replacement"]
    cap.check(
        "2",
        "the first upload replaces the default",
        "uorg serves exactly the uploaded bytes; uthn is a JPEG that is not the default image; on disk"
        " Pic<uid>_org.jpg has the uploaded sha256",
        {"org": a["sha256"], "thn_is_jpeg": True, "thn_not_default": True},
        first,
        first["org"][1] == a["sha256"] and first["thn"][2] and first["thn"][1] != DEFAULT_PICTURE_SHA256
        and (first["disk"] is None or first["disk"]["_org.jpg"] == a["sha256"]),
        ["upload_picture", "upload_picture_original_write", "upload_picture_thumbnail", "get_picture"],
    )
    cap.check(
        "3",
        "a replacement fully supersedes the previous picture",
        "after a second upload uorg serves exactly the second file, uthn changes and stays a JPEG, and the"
        " single Pic<uid>_org.jpg on disk holds the second file (the first file's bytes are no longer stored)",
        {"org": b["sha256"], "thn_changed": True, "disk_org": b["sha256"]},
        repl,
        repl["org"][1] == b["sha256"] and repl["thn"][2] and repl["thn"][1] != first["thn"][1]
        and (repl["disk"] is None or repl["disk"]["_org.jpg"] == b["sha256"]),
        ["upload_picture", "upload_picture_original_write", "upload_picture_thumbnail", "get_picture"],
    )
    if not ctx.cfg.app_exec:
        cap.blocked("3-disk", "picture files on disk", "no app_exec configured; served bytes only")

    # ---- P3: delete. Probed at runtime: a build without the endpoint answers 404 ----
    other = ctx.owner()
    r = other.post(ep.path("delete_picture", type="user", id=uid))
    if r.status_code == 404:
        cap.gated("4", "deleting a profile picture restores the default image",
                  "P3: no picture delete endpoint in this build (feat/delete-profile-picture)",
                  ["get_picture_default"])
        return
    refused = other.json(r) or {}
    after_refusal = _served(user, "uorg", uid)
    cap.check(
        "4a",
        "another user cannot delete someone's profile picture",
        "POST /services/delete/picture/user/{uid} by a different, non-admin user is refused and the picture"
        " is still served",
        {"success": False, "org": b["sha256"]},
        {"http": r.status_code, "body": refused, "org": after_refusal},
        refused.get("success") is False and after_refusal[1] == b["sha256"],
        ["delete_picture", "get_picture"],
    )
    r = user.post(ep.path("delete_picture", type="user", id=uid))
    body = user.json(r) or {}
    org, thn = _served(user, "uorg", uid), _served(user, "uthn", uid)
    disk = _on_disk(ctx, uid)
    cap.check(
        "4",
        "deleting a profile picture restores the default image",
        "the owner's delete succeeds; uorg and uthn then serve the shipped default image, and neither"
        " Pic<uid>_org.jpg nor _thn.jpg remains on disk",
        {"success": True, "sha256": DEFAULT_PICTURE_SHA256, "on_disk": {"_org.jpg": None, "_thn.jpg": None}},
        {"http": r.status_code, "body": body, "org": org, "thn": thn, "on_disk": disk},
        body.get("success") is True and org[1] == DEFAULT_PICTURE_SHA256 and thn[1] == DEFAULT_PICTURE_SHA256
        and (disk is None or disk == {"_org.jpg": None, "_thn.jpg": None}),
        ["delete_picture", "get_picture", "get_picture_default"],
    )
    r = user.post(ep.path("delete_picture", type="user", id=uid))
    again = user.json(r) or {}
    cap.check(
        "4b",
        "deleting an absent picture is harmless",
        "a second delete of the same picture succeeds and changes nothing",
        {"success": True},
        {"http": r.status_code, "body": again},
        again.get("success") is True,
        ["delete_picture"],
    )
