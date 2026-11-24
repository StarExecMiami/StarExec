"""29: space tree under the lifecycle user's personal space (coverage matrix S2-S4, S7-S10, S12).

Oracles are the tables, never the HTTP answer: set_assoc for the shape; closure, which must equal the
reflexive-transitive closure of set_assoc for every space in the tree (AddSpace / MoveSpace maintain it);
spaces and the default permissions row for edits; space_xml_uploads and the recreated set_assoc tree
for the XML round-trip.
"""

import io
import time
import xml.etree.ElementTree as ET
import zipfile

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked

PERMS = ["add_solver", "add_bench", "add_user", "add_space", "add_job",
         "remove_solver", "remove_bench", "remove_user", "remove_space", "remove_job"]

#: What the schema seeds error_message with when a space_xml_uploads row is created
#: (R__procedures_and_views.sql :8544). It means "nothing has gone wrong", so treating it as
#: an error message reported a failure on a round-trip that had succeeded.
NO_ERROR = "no error"


def _add(ctx, client, parent, name, desc="E2E tree"):
    client.post(ep.path("add_space"), data={
        "parent": parent, "name": name, "desc": desc, "locked": "false", "sticky": "false",
        "addBench": "on", "addSolver": "on", "addSpace": "on", "addJob": "on",
    })
    return ctx.db.int(
        "SELECT s.id FROM starexec.spaces s JOIN starexec.set_assoc sa ON sa.child_id=s.id"
        f" WHERE s.name={literal(name)} AND sa.space_id={int(parent)};"
    )


def _subtree(ctx, root):
    """{child: parent} for every space strictly below root, from set_assoc."""
    rows = ctx.db.rows(
        "WITH RECURSIVE t(id, parent) AS ("
        f" SELECT child_id, space_id FROM starexec.set_assoc WHERE space_id={int(root)}"
        " UNION SELECT sa.child_id, sa.space_id FROM starexec.set_assoc sa JOIN t ON sa.space_id=t.id)"
        " SELECT id, parent FROM t;"
    )
    return {int(c): int(p) for c, p in rows}


def _closure_matches(ctx, ids):
    """closure rows for descendants in ids == reflexive-transitive closure computed from set_assoc."""
    parent = {int(c): int(p) for c, p in ctx.db.rows("SELECT child_id, space_id FROM starexec.set_assoc;")}
    want = set()
    for sid in ids:
        want.add((sid, sid))
        node = sid
        while node in parent:
            node = parent[node]
            want.add((node, sid))
    got = {(int(a), int(d)) for a, d in ctx.db.rows(
        f"SELECT ancestor, descendant FROM starexec.closure WHERE descendant IN ({','.join(map(str, ids))});")}
    return got == want, {"missing": sorted(want - got), "extra": sorted(got - want)}


def _shape(ctx, root):
    """Nested (name, [children]) tree from set_assoc, children sorted by name."""
    names = dict(ctx.db.rows(
        "SELECT id, name FROM starexec.spaces WHERE id IN (SELECT descendant FROM starexec.closure"
        f" WHERE ancestor={int(root)});"))
    kids = {}
    for c, p in _subtree(ctx, root).items():
        kids.setdefault(p, []).append(c)

    def build(node):
        return [names[str(node)], sorted((build(k) for k in kids.get(node, [])), key=lambda x: x[0])]

    return build(int(root))


def run_space_tree(cap, ctx):
    uid = ctx.state.need("acct_user")
    user = ctx.session("acct", uid)
    personal = ctx.db.int(
        "SELECT min(ua.space_id) FROM starexec.user_assoc ua JOIN starexec.set_assoc sa ON sa.child_id=ua.space_id"
        f" JOIN starexec.permissions p ON p.id=ua.permission WHERE ua.user_id={uid} AND p.is_leader;"
    )
    if not personal:
        raise Blocked("the lifecycle user leads no space to build the tree in")

    # ---- S2 nested ----
    root = _add(ctx, user, personal, ctx.name("tree"))
    a = _add(ctx, user, root, ctx.name("tree_a")) if root else None
    b = _add(ctx, user, a, ctx.name("tree_b")) if a else None
    c = _add(ctx, user, personal, ctx.name("tree_c")) if root else None
    ids = [x for x in (root, a, b, c) if x]
    ok, diff = _closure_matches(ctx, ids) if len(ids) == 4 else (False, None)
    cap.check(
        "1",
        "nested subspaces form the declared chain and closure",
        "three nested POST /secure/add/space calls give set_assoc root→a→b (plus a sibling c), and closure for"
        " each equals the reflexive-transitive closure of set_assoc",
        {"subtree": {str(a): root, str(b): a}, "closure_diff": {"missing": [], "extra": []}},
        {"ids": ids, "subtree": _subtree(ctx, root) if root else None, "closure_diff": diff},
        len(ids) == 4 and _subtree(ctx, root) == {a: root, b: a} and ok,
        ["add_space", "add_space_params", "closure_add_space"],
    )
    if len(ids) != 4:
        raise Blocked("tree not created")
    ctx.state.set("tree_root", root)
    ctx.state.set("tree_ids", {"root": root, "a": a, "b": b, "c": c, "personal": personal})

    # ---- S3 edit ----
    new_name, new_desc = ctx.name("tree_edited"), "E2E edited description 29"
    grant = {"addBench": "true", "addJob": "false", "addSolver": "true", "addSpace": "false", "addUser": "true",
             "removeBench": "false", "removeJob": "true", "removeSolver": "false", "removeSpace": "true",
             "removeUser": "false"}
    r = user.post(ep.path("edit_space", id=root), data={"name": new_name, "description": new_desc,
                                                          "locked": "true", "sticky": "true", **grant},
                  keep=["success", "message"])
    row = ctx.db.rows(f"SELECT name, description, locked, sticky_leaders, default_permission FROM starexec.spaces WHERE id={root};")[0]
    perm = ctx.db.rows(f"SELECT {', '.join(PERMS)}, is_leader FROM starexec.permissions WHERE id={int(row[4])};")[0] if row[4] else None
    want_perm = {"add_bench": "t", "add_job": "f", "add_solver": "t", "add_space": "f", "add_user": "t",
                 "remove_bench": "f", "remove_job": "t", "remove_solver": "f", "remove_space": "t",
                 "remove_user": "f", "is_leader": "f"}
    got_perm = dict(zip(PERMS + ["is_leader"], perm)) if perm else None
    cap.check(
        "2",
        "space edit stores name, description, flags and default permissions",
        "POST /services/edit/space/{id} updates spaces.name/description/locked/sticky_leaders and the"
        " default_permission row's ten add/remove columns exactly, with is_leader false",
        {"row": [new_name, new_desc, "t", "t"], "default_permission": want_perm},
        {"row": row[:4], "default_permission": got_perm, "response": user.json(r)},
        row[:4] == [new_name, new_desc, "t", "t"] and got_perm == want_perm,
        ["edit_space"],
    )
    # Unlock again so the later moves and copies are not refused for a locked space.
    user.post(ep.path("edit_space", id=root), data={"name": new_name, "description": new_desc,
                                                     "locked": "false", "sticky": "false", **grant})

    # ---- S4 public / private ----
    def public():
        return dict(ctx.db.rows(f"SELECT id, public_access FROM starexec.spaces WHERE id IN ({root},{a},{b});"))

    user.post(ep.path("space_change_public", id=root, hierarchy="true", makePublic="true"))
    after_public = public()
    user.post(ep.path("space_change_public", id=b, hierarchy="false", makePublic="false"))
    after_one = public()
    cap.check(
        "3",
        "public/private applies to the hierarchy or to one space as requested",
        "changePublic(root, hierarchy=true, public=true) sets public_access on root, a and b; then"
        " changePublic(b, hierarchy=false, public=false) clears b only",
        {"hierarchy_public": {str(root): "t", str(a): "t", str(b): "t"},
         "b_private": {str(root): "t", str(a): "t", str(b): "f"}},
        {"hierarchy_public": after_public, "b_private": after_one},
        after_public == {str(root): "t", str(a): "t", str(b): "t"}
        and after_one == {str(root): "t", str(a): "t", str(b): "f"},
        ["space_change_public"],
    )
    user.post(ep.path("space_change_public", id=root, hierarchy="true", makePublic="false"))

    # ---- S7 move ----
    user.post(ep.path("move_space"), data={"selectedIds[]": b, "parent": c}, keep=["success", "message"])
    moved = _subtree(ctx, personal)
    ok_moved, diff_moved = _closure_matches(ctx, [root, a, b, c])
    cap.check(
        "4",
        "moving a subspace re-parents it and rebuilds closure",
        "POST /services/move/space moves b from a to c: set_assoc(c,b) replaces (a,b), and closure for every"
        " tree space again equals the closure of set_assoc (no stale (root,b)/(a,b) rows)",
        {"parent_of_b": c, "closure_diff": {"missing": [], "extra": []}},
        {"parent_of_b": moved.get(b), "closure_diff": diff_moved},
        moved.get(b) == c and ok_moved,
        ["move_space", "closure_move_space"],
    )
    user.post(ep.path("move_space"), data={"selectedIds[]": b, "parent": a})
    cyc = user.post(ep.path("move_space"), data={"selectedIds[]": root, "parent": b}, keep=["success", "message"])
    cap.check(
        "4-cycle",
        "a space cannot be moved under its own descendant",
        "moving root under b (its grandchild, after b is moved back under a) is refused and set_assoc is unchanged",
        {"parent_of_root": personal},
        {"parent_of_root": _subtree(ctx, personal).get(root), "http": cyc.status_code, "response": user.json(cyc)},
        _subtree(ctx, personal).get(root) == personal,
        ["move_space", "closure_move_space"],
    )

    # ---- S8 copy hierarchy ----
    before_ids = set(_subtree(ctx, c))
    r = user.post(ep.path("copy_space", spaceId=c),
                  data={"selectedIds[]": root, "copyHierarchy": "true", "copyPrimitives": "LINK"},
                  keep=["success", "message"])
    new_children = [s for s, p in _subtree(ctx, c).items() if p == c and s not in before_ids]
    copied_shape = _shape(ctx, new_children[0]) if len(new_children) == 1 else None
    source_shape = _shape(ctx, root)
    cap.check(
        "5",
        "copying a hierarchy recreates the same tree with new ids",
        "POST /services/spaces/{c}/copySpace (copyHierarchy=true, LINK) adds exactly one new child under c whose"
        " subtree has the same nested names as root's, and none of its ids are the source's",
        {"new_children": 1, "shape": source_shape},
        {"new_children": new_children, "shape": copied_shape, "response": user.json(r)},
        len(new_children) == 1 and copied_shape == source_shape
        and not (set(_subtree(ctx, new_children[0])) | {new_children[0]}) & {root, a, b},
        ["copy_space", "copy_primitives_option"],
    )

    # ---- S9 export ----
    r = user.get(ep.path("download"), params={"type": "spaceXML", "id": root, "includeattrs": "false"}, timeout=120)
    xml_raw = None
    try:
        z = zipfile.ZipFile(io.BytesIO(r.content))
        names = [n for n in z.namelist() if n.endswith(".xml")]
        xml_raw = z.read(names[0]) if names else None
    except zipfile.BadZipFile:
        names = None
    exported = None
    if xml_raw and b"<!DOCTYPE" not in xml_raw and b"<!ENTITY" not in xml_raw:
        def walk(el):
            kids = [k for k in el if k.tag.split("}")[-1] == "Space"]
            return [el.get("name"), sorted((walk(k) for k in kids), key=lambda x: x[0])]
        tops = [k for k in ET.fromstring(xml_raw) if k.tag.split("}")[-1] == "Space"]
        exported = walk(tops[0]) if len(tops) == 1 else None
    cap.check(
        "6",
        "space XML export describes exactly the tree",
        "GET /secure/download?type=spaceXML returns a zip with one XML whose nested Space elements carry the"
        " same names and nesting as set_assoc below root",
        source_shape,
        {"http": r.status_code, "xml_files": names, "tree": exported},
        exported == source_shape,
        ["download", "download_space_xml"],
    )

    # ---- S10 import (round-trip) ----
    # The schema seeds error_message with the sentinel 'no error' when the upload row is created
    # (R__procedures_and_views.sql :8544), so an empty string is NOT how success looks. Requiring
    # one made this check report a failure on a round-trip that had in fact succeeded -- 3 of 3
    # spaces, everything_complete, and an identical tree -- and it was carried as ledger U13,
    # which kept a FIXED defect on the open list. U13's own cause is gone: XMLUtil now uses a
    # constant SCHEMA_NAMESPACE_ROOT and the deployed batch*Schema.xsd carry a real
    # targetNamespace, both verified in the running image on 2026-09-20.
    if not xml_raw:
        cap.blocked("7", "space XML import", "no exported XML to re-import")
    else:
        target = _add(ctx, user, personal, ctx.name("tree_import"))
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as out:
            out.writestr("space.xml", xml_raw)
        start_id = ctx.db.int("SELECT coalesce(max(id),0) FROM starexec.space_xml_uploads;")
        r = user.post(ep.path("upload_space_xml"), data={"space": target},
                      files={"f": ("space.zip", buf.getvalue(), "application/zip")})
        done, status, secs = ctx.wait(
            "space XML import",
            lambda: (lambda row: (bool(row) and row[0][0] == "t", row))(ctx.db.rows(
                "SELECT everything_complete, total_spaces, completed_spaces, coalesce(error_message,'')"
                f" FROM starexec.space_xml_uploads WHERE user_id={uid} AND id>{start_id} ORDER BY id DESC LIMIT 1;")),
            ctx.cfg.timeout("space_xml", 120), interval=2,
        )
        imported = [s for s, p in _subtree(ctx, target).items() if p == target]
        shape = _shape(ctx, imported[0]) if len(imported) == 1 else None
        cap.check(
            "7",
            "the exported XML re-imports as the same tree",
            "POST /secure/upload/space with the exported XML under a new parent completes (space_xml_uploads"
            " everything_complete, no error_message) and set_assoc below the new parent has the same nested names",
            {"complete": True, "error": "", "shape": source_shape},
            {"http": r.status_code, "upload_row": status, "seconds": secs, "shape": shape},
            done and status[0][3] in ("", NO_ERROR) and shape == source_shape,
            ["upload_space_xml", "upload_space_xml_params", "download_space_xml"],
        )

    # ---- S12 space website ----
    url = "https://example.org/e2e-29"
    user.post(ep.path("website_add", type="space", id=root), data={"name": "E2E space site", "url": url})
    sites = ctx.db.rows(f"SELECT id, url FROM starexec.website WHERE space_id={root};")
    if sites:
        user.post(ep.path("website_delete", websiteId=sites[0][0]))
    left = ctx.db.int(f"SELECT count(*) FROM starexec.website WHERE space_id={root};")
    cap.check("8", "a space website is added and deleted",
              "website/add/space/{root} creates one row with space_id=root and that url; websites/delete removes it",
              {"added": [url], "left": 0}, {"added": [s[1] for s in sites], "left": left},
              [s[1] for s in sites] == [url] and left == 0, ["website_add", "website_delete"])
