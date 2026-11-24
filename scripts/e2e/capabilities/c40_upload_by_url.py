"""40: benchmark upload by URL (#139): the worker downloads the archive, then processes it.

The archive is served by lib.fileserver, a throwaway static server on the stack's own network, so
the download is a real HTTP fetch of a name that resolves to a private address (the stack allows
private networks for this).
"""

import posixpath
import subprocess
import time

from lib import endpoints as ep
from lib.fileserver import PORT, serving
from lib.steps import need_fixture, wait_upload

WORST_CASE = 5 * 1024 * 1024 * 1024
CREDENTIALS = "Archive URLs must not contain a user name or password"


def _post(ctx, client, space, processor, url):
    r = client.post(
        ep.path("upload_benchmarks"),
        timeout=120,
        keep=["jobId", "status"],
        data={
            "space": space,
            "upMethod": "convert",
            "benchType": processor,
            "download": "true",
            "localOrURLOrGit": "URL",
            "url": url,
            "dependency": "false",
            "linked": "false",
            "depRoot": space,
        },
        # Multipart, with the file field empty, as the browser form sends it.
        files={"benchFile": ("", b"", "application/octet-stream")},
    )
    return r, (client.json(r) or {}).get("jobId")


def _status(client, job):
    r = client.get(
        ep.path("upload_job_status", jobId=job),
        keep=["status", "errorMessage", "totalFilesProcessed"],
    )
    return client.json(r) or {}


def run_url_upload(cap, ctx):
    space, processor = ctx.state.need("space", "bench_processor")
    owner = ctx.owner()
    timeout = ctx.cfg.timeout("upload", 300)
    fixture = need_fixture(ctx, "e2e-axioms.tar")
    name, folder = (
        posixpath.basename(fixture["path"]),
        posixpath.dirname(fixture["path"]),
    )
    stack = ctx.cfg.deployment_name
    with serving(ctx, folder, name) as files:
        url = "http://%s:%d/%s" % (files, PORT, name)

        # 40.1 -- accepted at once, recorded with its address and the worst-case size.
        r, job = _post(ctx, owner, space, processor, url)
        # Only with a job: before V0119 the column does not exist and the query itself fails.
        row = (
            ctx.db.rows(
                "SELECT source_url, archive_size FROM starexec.upload_jobs WHERE id = %d;"
                % int(job)
            )
            if job
            else None
        )
        recorded = list(row[0]) if row else None
        # The #139 signature exactly (what 9.1 records): HTTP 500 and no job. Only that failure is
        # tagged known; any other failure of this check is a new one and reads FAIL.
        unfixed = r.status_code == 500 and not job
        cap.check(
            "1",
            "an upload by URL is accepted and recorded for the worker",
            "POST returns 202 with a job whose source_url is the address and whose archive_size is the"
            " 5 GiB worst case, so it does not jump the smallest-first queue",
            {"http": 202, "source_url": url, "archive_size": WORST_CASE},
            {"http": r.status_code, "jobId": job, "row": recorded},
            r.status_code == 202
            and bool(job)
            and recorded is not None
            and recorded[0] == url
            and int(recorded[1]) == WORST_CASE,
            ["upload_benchmarks"],
            known="#139" if unfixed else None,
        )
        if unfixed:
            # Nothing below can run without the fix: there is no job to follow, no download to log,
            # and the refusals are part of the fix itself.
            for cid, name in (
                ("2", "the archive is downloaded and processed like any other upload"),
                ("3", "the download is logged with its address"),
                ("4", "an address with a user name or password is refused"),
                ("5", "a download that fails fails the job with a message"),
            ):
                cap.gated(
                    cid,
                    name,
                    "the #139 fix (40.1 still reproduces #139)",
                    ["upload_benchmarks"],
                )
            return

        # 40.2 -- the worker downloads it and the upload completes with every file.
        ok, state, seconds = (
            wait_upload(ctx, owner, job, timeout) if job else (False, None, 0)
        )
        data = _status(owner, job) if job else {}
        expected = len(ctx.fx["axioms"])
        cap.check(
            "2",
            "the archive is downloaded and processed like any other upload",
            "the job reaches COMPLETED and processes exactly the archive's files",
            {"status": "COMPLETED", "totalFilesProcessed": expected},
            {
                "status": data.get("status"),
                "totalFilesProcessed": data.get("totalFilesProcessed"),
                "errorMessage": data.get("errorMessage"),
                "seconds": seconds,
            },
            data.get("status") == "COMPLETED"
            and data.get("totalFilesProcessed") == expected,
            ["upload_job_status"],
        )

        # 40.3 -- the download is logged with its host only: any part of the path can carry a
        # token, and the job id leads to the full address in upload_jobs.source_url.
        logs = subprocess.run(
            ["podman", "logs", stack + "-app"], capture_output=True, text=True
        )
        line = next(
            (
                l
                for l in (logs.stdout + logs.stderr).splitlines()
                if "Downloading the archive of upload job %s from" % job in l
            ),
            "",
        )
        origin = "http://%s:8080" % files
        cap.check(
            "3",
            "the download is logged with its host, not its path",
            "the worker logs the host it fetched from and the byte limit it applied, and no part"
            " of the path",
            "Downloading the archive of upload job %s from %s, at most" % (job, origin),
            line[-300:],
            ("from %s, at most" % origin) in line and name not in line,
        )

        # 40.4 -- an address carrying credentials is refused before any job exists.
        before = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        r, bad = _post(
            ctx,
            owner,
            space,
            processor,
            "http://user:secret@%s:8080/%s" % (files, name),
        )
        after = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        said = CREDENTIALS in (r.text or "") or CREDENTIALS in (
            r.headers.get("Set-Cookie") or ""
        ).replace("%20", " ").replace("+", " ")
        cap.check(
            "4",
            "an address with a user name or password is refused",
            "the request is rejected with the credentials message and no upload job is created",
            {"rejected": True, "new_upload_jobs": 0},
            {
                "http": r.status_code,
                "jobId": bad,
                "message_seen": said,
                "new_upload_jobs": after - before,
            },
            r.status_code >= 400 and not bad and after == before and said,
            ["upload_benchmarks"],
        )

        # 40.5 -- an unreachable address fails the job, without repeating the address.
        r, gone = _post(ctx, owner, space, processor, "http://%s:9/none.tar" % files)
        if gone:
            wait_upload(ctx, owner, gone, timeout)
        failed = _status(owner, gone) if gone else {}
        message = failed.get("errorMessage") or ""
        cap.check(
            "5",
            "a download that fails fails the job with a message",
            "the job reaches FAILED; the error says the download failed and does not repeat the address",
            {
                "status": "FAILED",
                "starts": "Could not download the archive from its URL",
            },
            {
                "http": r.status_code,
                "jobId": gone,
                "status": failed.get("status"),
                "errorMessage": message,
            },
            failed.get("status") == "FAILED"
            and message.startswith("Could not download the archive from its URL")
            and files not in message,
            ["upload_benchmarks", "upload_job_status"],
        )

        # 40.6 -- one upload by URL at a time. A stand-in row plays the running download, so the
        # check does not depend on timing a real one; the worker never claims or touches it.
        uid = ctx.state.need("owner_user")
        stand_in = ctx.db.int(
            "INSERT INTO starexec.upload_jobs (archive_path, user_id, space_id, upload_method, "
            "benchmark_type_id, priority, archive_size, status, source_url, started_at, last_heartbeat) "
            "VALUES ('/nonexistent/e2e-stand-in.tar', %d, %d, 'convert', %d, 0, %d, 'PROCESSING', "
            "'http://%s:9/stand-in.tar', now(), now()) RETURNING id;"
            % (int(uid), int(space), int(processor), WORST_CASE, files)
        )
        try:
            r, held = _post(ctx, owner, space, processor, url)
            time.sleep(
                20
            )  # the worker polls every few seconds: several chances to claim it
            waiting = _status(owner, held).get("status") if held else None
        finally:
            ctx.db.sql("DELETE FROM starexec.upload_jobs WHERE id = %d;" % stand_in)
        if held:
            wait_upload(ctx, owner, held, timeout)
        after = (_status(owner, held) if held else {}).get("status")
        cap.check(
            "6",
            "only one upload by URL runs at a time",
            "while another upload by URL is processing, a new one stays PENDING; once that one ends,"
            " the new one is claimed (its final status is recorded, not asserted: a second upload of"
            " the same archive into the same space may meet a name conflict)",
            {"while_another_runs": "PENDING", "after": "claimed (not PENDING)"},
            {
                "http": r.status_code,
                "jobId": held,
                "while_another_runs": waiting,
                "after": after,
            },
            r.status_code == 202
            and waiting == "PENDING"
            and after not in (None, "PENDING"),
            ["upload_benchmarks", "upload_job_status"],
        )
