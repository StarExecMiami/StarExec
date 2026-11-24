"""38: a solver can keep state under $HOME, as solvers with caches do (~/.cache, ~/.elan, Java prefs).

On Kubernetes #249 made the job container's root filesystem read-only, and the runAsUser's passwd
home lies on it; fix 8fe18b598 mounts an emptyDir and sets HOME to it. The probe solver writes a file
under $HOME/.cache, reads it back, and only then prints a verdict, so a pair that reaches COMPLETE with
the marker in its output has shown HOME was writable where it ran. Backend-agnostic: Local and Podman
must pass it too.
"""

import io
import os
import tempfile
import zipfile

from capabilities.c07_solvers import configs_of, upload_solver
from capabilities.c10_single_stage import create_job
from lib import endpoints as ep
from lib.report import Blocked
from lib.steps import pair_statuses, wait_terminal

COMPLETE = 7
MARKER = "C38-HOME-WRITABLE"

PROBE = b"""#!/bin/sh
# Keeps state under $HOME the way solvers with caches do, then answers.
d="$HOME/.cache/starexec-e2e-c38"
if mkdir -p "$d" && echo c38-probe > "$d/probe" && [ "$(cat "$d/probe")" = c38-probe ]; then
    echo "C38-HOME-WRITABLE home=$HOME"
    echo "% SZS status Theorem for c38"
else
    echo "C38-HOME-NOT-WRITABLE home=$HOME"
    exit 1
fi
"""


def _probe_zip():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        info = zipfile.ZipInfo("bin/starexec_run_home", date_time=(2026, 1, 1, 0, 0, 0))
        info.external_attr = 0o755 << 16
        z.writestr(info, PROBE)
    return buf.getvalue()


def run_home_writable(cap, ctx):
    benches = ctx.state.need("benchmarks")
    owner = ctx.owner()
    name = ctx.name("homeprobe")

    fd, path = tempfile.mkstemp(suffix=".zip")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(_probe_zip())
        r, sid = upload_solver(ctx, owner, name, path, "c38-home-probe.zip")
    finally:
        os.unlink(path)
    config = configs_of(ctx, sid).get("home") if sid else None
    cap.check(
        "1",
        "the HOME probe solver uploads with its configuration",
        "POST /secure/upload/solvers stores the solver and its starexec_run_home configuration",
        "solver id and configuration 'home'",
        {"http": r.status_code, "solver": sid, "config": config},
        bool(sid) and bool(config),
        ["upload_solver"],
    )
    if not config:
        raise Blocked("probe solver not uploaded")

    bench = next(iter(benches.values()))
    r, job = create_job(ctx, owner, ctx.name("homejob"), [config], [bench])
    if not job:
        raise Blocked(f"probe job not created (http {r.status_code})")
    finished, _, waited = wait_terminal(ctx, job, 1, ctx.cfg.timeout("job", 1200))
    statuses = pair_statuses(ctx, job)
    ((pid, status),) = statuses.items()
    cap.check(
        "2",
        "the probe pair completes",
        "the only pair of the probe job ends at COMPLETE (7), not ERROR_RUNSCRIPT (11) or an error status",
        COMPLETE,
        {"finished": finished, "waited_s": waited, "status": status, "pair": pid},
        finished and status == COMPLETE,
    )

    out = owner.get(ep.path("pair_stdout", id=pid, stageNumber=1), params={"limit": -1})
    lines = [l for l in out.text.splitlines() if "C38-HOME" in l]
    cap.check(
        "3",
        "the solver wrote and read back a file under $HOME/.cache",
        "stage-1 stdout carries the probe's success marker, printed only after the write and read-back",
        MARKER,
        {"http": out.status_code, "lines": lines},
        any(l.startswith(MARKER) for l in lines),
        ["pair_stdout"],
    )
