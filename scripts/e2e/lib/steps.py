import signal
import os
"""Reusable steps shared by several capabilities."""

from . import endpoints as ep
from .db import literal
from .report import Blocked

# StatusCode.isTerminalExecutionResult at 207f6f50c: 7-18 and 21, 23-26 (not 19/20/22).
TERMINAL = set(range(7, 19)) | {21, 23, 24, 25, 26}


def job_id_by_name(ctx, name):
    return ctx.db.int(
        f"SELECT id FROM starexec.jobs WHERE name={literal(name)} ORDER BY id DESC LIMIT 1;"
    )


def root_job_space(ctx, job_id):
    return ctx.db.int(
        f"SELECT primary_space FROM starexec.jobs WHERE id={int(job_id)};"
    )


def pair_statuses(ctx, job_id):
    return {
        int(p): int(s)
        for p, s in ctx.db.rows(
            f"SELECT id, status_code FROM starexec.job_pairs WHERE job_id={int(job_id)} ORDER BY id;"
        )
    }


def wait_terminal(ctx, job_id, expected_pairs, timeout):
    """True only when `expected_pairs` pairs exist and every one is terminal.

    expected_pairs must be positive: waiting for zero pairs is satisfied by a job that has none, which would
    report success without observing anything (the vacuous-pass shape).
    """
    if expected_pairs < 1:
        raise Blocked(f"job {job_id}: wait_terminal needs at least one expected pair, got {expected_pairs}")

    def done():
        st = pair_statuses(ctx, job_id)
        return len(st) == expected_pairs and all(
            s in TERMINAL for s in st.values()
        ), sorted(st.items())

    return ctx.wait(f"job {job_id} terminal", done, timeout, interval=2.0)


def wait_upload(ctx, client, upload_job, timeout):
    state = {}

    def poll():
        r = client.get(
            ep.path("upload_job_status", jobId=upload_job),
            keep=["status", "totalFilesFound", "totalFilesProcessed", "errorMessage"],
        )
        data = client.json(r) or {}
        state.update(data)
        return data.get("status") not in (None, "PENDING", "PROCESSING"), data.get(
            "status"
        )

    ok, status, seconds = ctx.wait(
        f"upload job {upload_job}", poll, timeout, interval=2.0
    )
    # "Stopped moving" is not success: FAILED leaves PENDING/PROCESSING too. Only COMPLETED is an upload that
    # happened, so callers cannot mistake a failed upload for a finished one.
    return bool(ok) and state.get("status") == "COMPLETED", state, seconds


def job_stats(ctx, client, job_space, stage, include_unknown=False, wallclock=True):
    r = client.post(
        ep.path(
            "job_stats",
            jobSpaceId=job_space,
            shortFormat="false",
            wallclock=str(wallclock).lower(),
            stageNum=stage,
            includeUnknown=str(include_unknown).lower(),
        ),
        data=client.datatables(),
        keep=["iTotalRecords"],
    )
    data = client.json(r)
    if not isinstance(data, dict) or "aaData" not in data:
        return r, None
    return r, data["aaData"]


def pairs_by_config(ctx, client, job_space, config, kind, stage, wallclock=True):
    r = client.post(
        ep.path(
            "pairs_by_config",
            jobSpaceId=job_space,
            configId=config,
            type=kind,
            wallclock=str(wallclock).lower(),
            stageNumber=stage,
        ),
        data=client.datatables(),
        keep=["iTotalRecords", "iTotalDisplayRecords"],
    )
    data = client.json(r)
    return r, data


def need_fixture(ctx, name):
    f = ctx.fx.get("files", {}).get(name)
    if not f:
        raise Blocked(f"fixture {name} missing from the manifest")
    return f


def stop_watcher(proc):
    """Kill a watcher started with start_new_session=True, and everything it started.

    A wrapper such as the microk8s launcher runs the real kubectl as a grandchild. Killing only
    the direct child leaves kubectl holding the stdout pipe, so draining it timed out on
    Kubernetes while podman exec (no wrapper) closed at once. Killing the process group
    closes it everywhere.
    """
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
