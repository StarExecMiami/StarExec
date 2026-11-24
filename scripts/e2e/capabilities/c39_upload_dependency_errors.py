"""39: upload dependency errors (2026-10-15 fixes).

A refused upload names the file, the include and the dependency root it searched (Geoff's
uploads 31 and 32 on Quokka said only "could not be resolved"); the status page escapes that
text; and a dependency root the uploader cannot see is refused before any upload job exists.
"""

import io
import tarfile

from lib import endpoints as ep
from lib.steps import need_fixture, wait_upload

HIDDEN_ROOT = 99039
PROBE = "PRB039+1.p"
DENIED = "You do not have permission to see the dependency root space"


def _problem(ctx, include):
    """A fixture problem the TPTP processor accepts, with its includes replaced by one."""
    fixture = need_fixture(ctx, "e2e-problems.tar")
    with tarfile.open(fixture["path"]) as t:
        member = next(
            m for m in t.getmembers() if m.isfile() and m.name.endswith("AGT001+1.p")
        )
        lines = t.extractfile(member).read().decode().splitlines()
    out, placed = [], False
    for line in lines:
        if line.startswith("include("):
            if not placed:
                out.append("include('%s')." % include)
                placed = True
            continue
        out.append(line)
    data = ("\n".join(out) + "\n").encode()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as t:
        info = tarfile.TarInfo(PROBE)
        info.size = len(data)
        t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def _upload(ctx, client, space, processor, archive, dependency, dep_root):
    r = client.post(
        ep.path("upload_benchmarks"),
        timeout=120,
        keep=["jobId", "status"],
        data={
            "space": space,
            "upMethod": "dump",
            "benchType": processor,
            "download": "true",
            "localOrURLOrGit": "local",
            "dependency": str(dependency).lower(),
            "linked": "false",
            "depRoot": dep_root,
        },
        files={"benchFile": ("probe039.tar", archive, "application/x-tar")},
    )
    body = client.json(r) or {}
    return r, body.get("jobId")


def _status(ctx, client, job):
    r = client.get(
        ep.path("upload_job_status", jobId=job), keep=["status", "errorMessage"]
    )
    return client.json(r) or {}


def run_dependency_errors(cap, ctx):
    space, processor = ctx.state.need("space", "bench_processor")
    owner = ctx.owner()
    timeout = ctx.cfg.timeout("upload", 300)

    # 39.1 -- Geoff's case: one include the root does not hold.
    missing = "Axioms/NOPE039+0.ax"
    # Counted around this upload, not absolutely: 39.4 adds a benchmark of the same name, and a
    # re-run against the same state would otherwise see it.
    count_probe = "SELECT count(*) FROM starexec.benchmarks WHERE name = '%s' AND NOT deleted;" % PROBE
    before = ctx.db.int(count_probe)
    r, job = _upload(ctx, owner, space, processor, _problem(ctx, missing), True, space)
    ok, state, _ = wait_upload(ctx, owner, job, timeout) if job else (False, None, 0)
    data = _status(ctx, owner, job) if job else {}
    want = (
        "%s includes %s, which was not found under the dependency root space (id %s)"
        % (PROBE, missing, space)
    )
    message = data.get("errorMessage") or ""
    cap.check(
        "1",
        "a missing include is named with the file and the root searched",
        "the upload job fails and its error names the file, the include and the dependency root id",
        want,
        {
            "http": r.status_code,
            "jobId": job,
            "status": data.get("status"),
            "errorMessage": message,
        },
        r.status_code == 202 and data.get("status") == "FAILED" and want in message,
        ["upload_benchmarks", "upload_job_status"],
    )
    added = ctx.db.int(count_probe) - before
    cap.check(
        "1b",
        "the refused batch adds no benchmark",
        "the refused upload adds no benchmarks row for the probe file",
        0,
        added,
        added == 0,
    )

    # 39.2 -- the page shows that text escaped.
    amp = "Axioms/A&B039+0.ax"
    r, job = _upload(ctx, owner, space, processor, _problem(ctx, amp), True, space)
    if job:
        wait_upload(ctx, owner, job, timeout)
    message = (_status(ctx, owner, job) if job else {}).get("errorMessage") or ""
    if amp not in message:
        cap.check(
            "2",
            "the status page escapes upload text",
            "prerequisite: the error message carries the include, so the page can be checked for escaping",
            "error message containing %s" % amp,
            {"jobId": job, "errorMessage": message},
            False,
            status="BLOCKED",
            extra={
                "why": "the processor did not carry this include into the error; escaping is "
                "covered by UploadStatusPagesEscapeTest"
            },
        )
    else:
        page = owner.get("/secure/details/uploadStatus.jsp", params={"id": job})
        html = page.text
        cell = (
            html.split('id="errorMessageValue">', 1)[1].split("</td>", 1)[0]
            if 'id="errorMessageValue">' in html
            else ""
        )
        cap.check(
            "2",
            "the status page escapes upload text",
            "the error cell shows '&' as '&amp;' and never raw",
            "A&amp;B039+0.ax in the cell, no raw A&B039+0.ax",
            {"http": page.status_code, "cell": cell[:300]},
            page.status_code == 200
            and "A&amp;B039+0.ax" in cell
            and "A&B039+0.ax" not in cell,
        )

    # 39.3 -- a root the uploader cannot see: no member, not public.
    ctx.db.sql(
        "INSERT INTO starexec.spaces (id,name,created,locked,public_access) VALUES "
        "(%d,'e2e-hidden-root',NOW(),false,false) ON CONFLICT (id) DO NOTHING;"
        % HIDDEN_ROOT
    )
    try:
        before = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        r, job = _upload(
            ctx, owner, space, processor, _problem(ctx, missing), True, HIDDEN_ROOT
        )
        after = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        said = DENIED in (r.text or "") or DENIED in (
            r.headers.get("Set-Cookie") or ""
        ).replace("%20", " ").replace("+", " ")
        cap.check(
            "3",
            "a dependency root the uploader cannot see is refused",
            "the request is rejected with the permission message and no upload job is created",
            {"rejected": True, "message": DENIED, "new_upload_jobs": 0},
            {
                "http": r.status_code,
                "jobId": job,
                "message_seen": said,
                "new_upload_jobs": after - before,
            },
            r.status_code >= 400 and not job and after == before and said,
            ["upload_benchmarks"],
        )
        # 39.4 -- the root is not consulted when dependencies are off.
        r, job = _upload(
            ctx, owner, space, processor, _problem(ctx, missing), False, HIDDEN_ROOT
        )
        cap.check(
            "4",
            "without dependencies the root is not checked",
            "the same upload with dependencies off is accepted",
            202,
            {"http": r.status_code, "jobId": job},
            r.status_code == 202 and bool(job),
            ["upload_benchmarks"],
        )
        if job:
            wait_upload(ctx, owner, job, timeout)
    finally:
        ctx.db.sql("DELETE FROM starexec.spaces WHERE id = %d;" % HIDDEN_ROOT)
