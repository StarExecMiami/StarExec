"""42: copying more than one item at a time, as the explore page does when several are selected.

Every earlier capability copies a single id. With two or more, the copy endpoints answer with a
New_ID cookie listing the new ids. RFC 6265 does not allow a comma in a cookie value, and Tomcat
threw when the raw list was added: after the copy, so the user got an error for a copy that had
happened. Each check therefore asks for both: the copies exist, and the reply says so.

42.3 runs StarexecCommand (see capability 41 for E2E_COMMAND_CLASSES), the only reader of that
cookie, and checks it prints every new id.
"""

import io
import re
import zipfile

from lib import endpoints as ep
from lib.report import Blocked

from capabilities.c13_pipeline import job_xml
from capabilities.c41_command_client import _session

KINDS = {
    "benchmark": ("bench_assoc", "bench_id", "benchmarks"),
    "solver": ("solver_assoc", "solver_id", "solvers"),
}


def _two_of(ctx, kind, uid, dest):
    """Two live items of this kind the owner owns, from one space other than dest."""
    assoc, col, table = KINDS[kind]
    live = (
        f"JOIN starexec.{table} t ON t.id = a.{col} WHERE t.user_id = {int(uid)} "
        "AND NOT t.deleted AND NOT t.recycled"
    )
    src = ctx.db.scalar(
        f"SELECT a.space_id FROM starexec.{assoc} a {live} AND a.space_id <> {int(dest)} "
        "GROUP BY a.space_id HAVING count(*) >= 2 ORDER BY count(*) DESC, a.space_id LIMIT 1;",
        record=False,
    )
    if not src:
        return None, []
    ids = ctx.db.scalar(
        f"SELECT string_agg(id::text, ',' ORDER BY id) FROM (SELECT a.{col} AS id FROM "
        f"starexec.{assoc} a {live} AND a.space_id = {int(src)} ORDER BY a.{col} LIMIT 2) x;",
        record=False,
    )
    return int(src), [int(x) for x in ids.split(",")]


def _count(ctx, kind, space):
    assoc, _, _ = KINDS[kind]
    return ctx.db.int(
        f"SELECT count(*) FROM starexec.{assoc} WHERE space_id = {int(space)};"
    )


def _web_copy(cap, ctx, cid, kind, endpoint, extra):
    uid, dest = ctx.state.need("owner_user", "owner_personal_space")
    src, ids = _two_of(ctx, kind, uid, dest)
    if not src:
        cap.blocked(
            cid,
            f"copying two {kind}s",
            f"the owner has no space with two {kind}s of their own",
        )
        return
    owner = ctx.owner()
    before = _count(ctx, kind, dest)
    r = owner.post(
        ep.path(endpoint, spaceId=dest),
        data=dict({"selectedIds[]": ids, "copy": "true", "fromSpace": src}, **extra),
        keep=["success", "message"],
    )
    body = owner.json(r) or {}
    after = _count(ctx, kind, dest)
    cap.check(
        cid,
        f"copying two {kind}s reports the copy that happened",
        f"POST {endpoint} with two ids and copy=true: HTTP 200 with success=true, and exactly two"
        f" new {kind}s in the destination space",
        {"http": 200, "success": True, "new_in_destination": 2},
        {
            "http": r.status_code,
            "success": body.get("success"),
            "message": body.get("message") or r.text[:160],
            "new_in_destination": after - before,
            "copied_from_space": src,
            "ids": ids,
        },
        r.status_code == 200 and body.get("success") is True and after - before == 2,
        [endpoint],
    )


def _command_copy(cap, ctx):
    uid, dest = ctx.state.need("owner_user", "owner_personal_space")
    src, ids = _two_of(ctx, "benchmark", uid, dest)
    if not src:
        cap.blocked(
            "3", "StarexecCommand copies two benchmarks", "no space with two benchmarks"
        )
        return
    before = _count(ctx, "benchmark", dest)
    try:
        chunks, _ = _session(
            ctx,
            uid,
            ["returnids", f"copybench id={ids[0]},{ids[1]} from={src} to={int(dest)}"],
        )
    except Blocked as e:
        cap.blocked("3", "StarexecCommand copies two benchmarks", str(e))
        return
    copied = chunks[3] if len(chunks) > 3 else ""
    printed = [int(x) for x in re.findall(r"\bid=(\d+)", copied)]
    after = _count(ctx, "benchmark", dest)
    new = ctx.db.scalar(
        f"SELECT string_agg(bench_id::text, ',' ORDER BY bench_id) FROM starexec.bench_assoc "
        f"WHERE space_id = {int(dest)} AND bench_id = ANY(ARRAY[{','.join(str(i) for i in printed) or '0'}]);",
        record=False,
    )
    cap.check(
        "3",
        "StarexecCommand copies two benchmarks and prints both new ids",
        "copybench with two ids: the client prints two ids and no error, two new benchmarks are in"
        " the destination, and they are the ids printed",
        {"printed_ids": 2, "new_in_destination": 2, "printed_ids_in_destination": 2},
        {
            "client_output": copied[:300],
            "printed_ids": printed,
            "new_in_destination": after - before,
            "printed_ids_in_destination": new,
        },
        "ERROR" not in copied
        and len(printed) == 2
        and after - before == 2
        and new is not None
        and len(new.split(",")) == 2,
        ["copy_bench_to_space"],
    )


def _two_job_xml(cap, ctx):
    """A job XML defining two jobs: the upload answers with both new ids in the same cookie."""
    try:
        verbatim, e_config, post, space, benches = ctx.state.need(
            "config_verbatim", "config_e", "post_processor", "space", "benchmarks"
        )
    except Blocked as e:
        cap.blocked("4", "a job XML with two jobs", str(e))
        return
    bench = benches.get("AGT001+1.p") or next(iter(benches.values()), None)
    if not bench:
        cap.blocked("4", "a job XML with two jobs", "no benchmark in the state")
        return
    names = [ctx.name("xml-two-a"), ctx.name("xml-two-b")]
    one, two = (
        job_xml(n, verbatim, e_config, post, ctx.queue(), space, [bench]) for n in names
    )
    # Both are whole documents: keep the first, and add the second's <Job> element to it.
    second = two[two.index("<Job name=") : two.index("</tns:Jobs>")]
    doc = one.replace("</tns:Jobs>", second + "</tns:Jobs>")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("jobs.xml", doc)
    r = ctx.owner().post(
        ep.path("upload_job_xml"),
        data={"space": space},
        files={"f": ("two-jobs.zip", buf.getvalue(), "application/zip")},
    )
    quoted = ",".join("'" + n.replace("'", "''") + "'" for n in names)
    created = ctx.db.int(
        f"SELECT count(*) FROM starexec.jobs WHERE name IN ({quoted});"
    )
    cap.check(
        "4",
        "a job XML with two jobs reports the jobs it created",
        "POST /secure/upload/jobXML with two jobs: both jobs exist, and the reply is the redirect to"
        " the explore page (HTTP 200 once followed), not an error saying no job was created",
        {"http": 200, "jobs_created": 2},
        {
            "http": r.status_code,
            "jobs_created": created,
            "reply": "redirect followed" if r.status_code == 200 else r.text[:160],
        },
        r.status_code == 200 and created == 2,
        ["upload_job_xml", "upload_job_xml_params"],
    )


def run_multi_item_copy(cap, ctx):
    _web_copy(cap, ctx, "1", "benchmark", "copy_bench_to_space", {})
    _web_copy(
        cap, ctx, "2", "solver", "copy_solver_to_space", {"copyToSubspaces": "false"}
    )
    _command_copy(cap, ctx)
    _two_job_xml(cap, ctx)
