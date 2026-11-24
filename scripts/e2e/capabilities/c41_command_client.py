"""41: StarexecCommand, the command-line client users download, against this server.

The client under test is a StarexecCommand build from a source tree: its compiled classes plus its
runtime classpath. E2E_COMMAND_CLASSES names the classes directory and E2E_COMMAND_CLASSPATH a file
holding the ':'-joined runtime classpath, as `mvn dependency:build-classpath` writes it.

The client runs as a subprocess and reads its commands on stdin. That keeps the owner's disposable
password off its command line, out of the process list and off the disk. It is also removed from
everything recorded. What the client prints is evidence of what it saw; what the server did is
checked separately, in the database and through the web API.
"""

import os
import re
import subprocess

from lib.report import Blocked
from lib.steps import need_fixture, wait_upload

PROMPT = "StarCom> "


def _command(ctx):
    classes = os.environ.get("E2E_COMMAND_CLASSES")
    cpfile = os.environ.get("E2E_COMMAND_CLASSPATH")
    if not classes or not cpfile:
        raise Blocked(
            "set E2E_COMMAND_CLASSES and E2E_COMMAND_CLASSPATH to a StarexecCommand build"
        )
    if not os.path.isfile(os.path.join(classes, "org/starexec/command/Shell.class")):
        raise Blocked(f"no StarexecCommand Shell.class under {classes}")
    with open(cpfile) as f:
        cp = f.read().strip()
    # The server's runtime classpath carries logback and the server's logback.xml; both would write
    # into the middle of the shell's stdout.
    return [
        "java",
        "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
        "-Dlogback.configurationFile="
        + os.path.join(os.path.dirname(os.path.abspath(__file__)), "c41_logback.xml"),
        "-cp",
        classes + os.pathsep + cp,
        "org.starexec.command.Shell",
    ]


LOGBACK_STATUS = re.compile(r"^\d\d:\d\d:\d\d,\d{3} \|-")


def _session(ctx, uid, commands, timeout=300):
    """Logs the client in as user uid, runs commands, returns one output chunk per command.

    The shell prints its prompt before reading each line, so splitting the output on the prompt
    gives the banner, then exactly one chunk for each line it read.
    """
    email = ctx.db.scalar(
        f"SELECT email FROM starexec.users WHERE id={int(uid)};", record=False
    )
    password = ctx.set_password(uid)
    lines = (
        [f"login u={email} p={password} addr={ctx.cfg.base_url}/"] + commands + ["exit"]
    )
    r = subprocess.run(
        _command(ctx),
        input="\n".join(lines) + "\n",
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    out = r.stdout.replace(password, "<password>")
    err = r.stderr.replace(password, "<password>")
    chunks = [
        "\n".join(
            line for line in c.splitlines() if not LOGBACK_STATUS.match(line)
        ).strip()
        for c in out.split(PROMPT)
    ]
    # chunks[0] is the banner; chunks[1] answers the login line, then one per command. stderr is
    # kept apart so it cannot shift that alignment.
    return chunks, err[-1500:]


def _upload_jobs(ctx, uid):
    return ctx.db.int(
        f"SELECT count(*) FROM starexec.upload_jobs WHERE user_id = {int(uid)};"
    )


def run_command_client(cap, ctx):
    uid, space, processor = ctx.state.need("owner_user", "space", "bench_processor")
    fixture = need_fixture(ctx, "e2e-axioms.tar")
    before = _upload_jobs(ctx, uid)
    push = (
        f"pushbenchmarks f={fixture['path']} id={int(space)} bt={int(processor)} hier="
    )
    chunks, out = _session(ctx, uid, ["returnids", push])
    login = chunks[1] if len(chunks) > 1 else ""
    pushed = chunks[3] if len(chunks) > 3 else ""
    cap.check(
        "1",
        "StarexecCommand logs in",
        "the login line prints no error",
        {"login_output": "no ERROR"},
        {"login_output": login[:300]},
        len(chunks) > 3 and "ERROR" not in login,
        [],
    )

    m = re.search(r"\bid=(\d+)", pushed)
    returned = int(m.group(1)) if m else None
    after = _upload_jobs(ctx, uid)
    job = (
        ctx.db.int(
            f"SELECT max(id) FROM starexec.upload_jobs WHERE user_id = {int(uid)} "
            f"AND space_id = {int(space)};"
        )
        if after > before
        else None
    )
    owner = ctx.owner()
    done, state = False, {}
    if job:
        done, state, _ = wait_upload(ctx, owner, job, ctx.cfg.timeout("upload", 300))
    cap.check(
        "2",
        "pushbenchmarks uploads a local archive",
        "the client prints the new upload's id and no error; the server records exactly one new"
        " upload job for the owner, it is the id the client printed, and it completes with all"
        " five benchmarks",
        {
            "client": "id=<new upload job>",
            "new_upload_jobs": 1,
            "status": "COMPLETED",
            "files_processed": 5,
        },
        {
            "client_output": pushed[:400],
            "returned_id": returned,
            "new_upload_jobs": after - before,
            "upload_job": job,
            "status": state.get("status"),
            "files_processed": state.get("totalFilesProcessed"),
            "error": state.get("errorMessage"),
        },
        "ERROR" not in pushed
        and returned is not None
        and after - before == 1
        and returned == job
        and done
        and state.get("totalFilesProcessed") == 5,
        ["upload_benchmarks", "upload_job_status"],
    )

    # Without an id from pushbenchmarks (a client that cannot upload), ask about the owner's newest
    # completed upload job instead: what the client says about an upload the server did make.
    asked = returned or ctx.db.int(
        f"SELECT coalesce(max(id), 0) FROM starexec.upload_jobs WHERE user_id = {int(uid)} "
        "AND status = 'COMPLETED' AND total_files_processed = 5;"
    )
    if not asked:
        cap.blocked(
            "3",
            "viewuploadstatus reports the upload",
            "no completed upload job to ask about",
        )
        return
    status_chunks, _ = _session(ctx, uid, [f"viewuploadstatus id={int(asked)}"])
    shown = status_chunks[2] if len(status_chunks) > 2 else ""
    cap.check(
        "3",
        "viewuploadstatus reports the upload",
        "viewuploadstatus with the id pushbenchmarks returned (or, if it returned none, the owner's"
        " newest completed upload job) prints the upload job's status, its benchmarks processed out"
        " of those found, and no error",
        {"client_output": "status: COMPLETED | benchmarks: 5 / 5 | ..."},
        {"asked_about": asked, "client_output": shown[:300]},
        "ERROR" not in shown and "status: COMPLETED | benchmarks: 5 / 5" in shown,
        ["upload_job_status"],
    )

    # No upload job has this id. The server answers its route with 200 and "not available", and
    # the client must say so rather than describe an older upload status with the same number.
    missing = ctx.db.int(
        "SELECT coalesce(max(id), 0) + 1000 FROM starexec.upload_jobs;"
    )
    gone_chunks, _ = _session(ctx, uid, [f"viewuploadstatus id={int(missing)}"])
    gone = gone_chunks[2] if len(gone_chunks) > 2 else ""
    cap.check(
        "4",
        "viewuploadstatus of an upload job that does not exist says so",
        "viewuploadstatus for an id no upload job has prints that no such upload job is visible,"
        " not another upload's status",
        {"client_output": f"ERROR: No upload job {missing} is visible to you"},
        {"asked_about": missing, "client_output": gone[:300]},
        f"No upload job {missing} is visible to you" in gone,
        ["upload_job_status"],
    )
