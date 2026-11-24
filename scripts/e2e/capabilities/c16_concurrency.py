"""16: #144 recipe. FAST and SLOW marker configurations: serial controls, then submitted together.
Each pair's stdout must carry only its own marker.

The configurations come from this capability's own solver (c16-hold-solver.zip, lib/fixtures.py
C16_HOLD_CONFIGS: 3 s and 10 s), not from the shared fixture solver's 0.2 s and 1.8 s, so that two pairs
dispatched together are still running at the same time and the overlap check can see it."""

import subprocess
import time
from datetime import datetime, timezone

from lib import endpoints as ep
from capabilities.c07_solvers import configs_of, upload_solver
from capabilities.c13_pipeline import WATCHER, parse_events
from lib.fixtures import FAST_MARKER, SLOW_MARKER
from lib.report import Blocked
from lib.steps import need_fixture, pair_statuses, wait_terminal, stop_watcher
from capabilities.c10_single_stage import create_job


def _hold_configs(ctx, owner):
    """Upload capability 16's marker solver and return its configurations, {"fast": id, "slow": id}."""
    fx = need_fixture(ctx, "c16-hold-solver.zip")
    _, sid = upload_solver(
        ctx, owner, ctx.name("c16_hold"), fx["path"], "c16-hold-solver.zip"
    )
    cfgs = configs_of(ctx, sid) if sid else {}
    if set(cfgs) != {"fast", "slow"}:
        raise Blocked(f"c16 marker solver configurations not created: {sorted(cfgs)}")
    return cfgs


def _marker_check(cap, ctx, owner, cid, label, pair, own, other):
    r = owner.get(ep.path("pair_stdout", id=pair, stageNumber=1), params={"limit": -1})
    text = r.text
    has_own = f"MARKER={own}" in text and f"DONE={own}" in text
    has_other = other in text
    cap.check(
        cid,
        label,
        f"pair {pair}'s stage-1 stdout contains MARKER={own} and DONE={own} and no {other}",
        {"own": True, "other": False},
        {"http": r.status_code, "own": has_own, "other": has_other},
        r.status_code == 200 and has_own and not has_other,
        ["pair_stdout"],
    )


def run_concurrency(cap, ctx):
    benches = ctx.state.need("benchmarks")
    bench = sorted(benches.values())[0]
    owner = ctx.owner()
    cfgs = _hold_configs(ctx, owner)
    for cid, config, own, other in (
        ("1", "fast", FAST_MARKER, SLOW_MARKER),
        ("2", "slow", SLOW_MARKER, FAST_MARKER),
    ):
        r, job = create_job(
            ctx,
            owner,
            ctx.name(f"serial_{config}"),
            [cfgs[config]],
            [bench],
            cpu=30,
            wall=30,
        )
        if not job:
            raise Blocked(f"serial {config} job not created")
        ok, _, _ = wait_terminal(ctx, job, 1, ctx.cfg.timeout("job", 300))
        pair = next(iter(pair_statuses(ctx, job)))
        _marker_check(
            cap, ctx, owner, cid, f"serial control {config}", pair, own, other
        )

    watcher = None
    if ctx.cfg.app_exec:
        first_pair = ctx.db.int("SELECT coalesce(max(id),0)+1 FROM starexec.job_pairs;")
        watcher = subprocess.Popen(
            list(ctx.cfg.app_exec)
            + [
                "timeout",
                "900",
                "bash",
                "-s",
                "--",
                str(first_pair),
                ctx.cfg.app_data_root,
            ],
            stdin=open(WATCHER),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            start_new_session=True,
        )
        time.sleep(2)
    r, job = create_job(
        ctx,
        owner,
        ctx.name("concurrent"),
        [cfgs["fast"], cfgs["slow"]],
        sorted(benches.values())[:2],
        cpu=30,
        wall=30,
    )
    if not job:
        if watcher:
            stop_watcher(watcher)
        raise Blocked("concurrent job not created")
    ok, _, seconds = wait_terminal(ctx, job, 4, ctx.cfg.timeout("job", 300))
    if watcher:
        time.sleep(3)
        stop_watcher(watcher)
        events = parse_events(watcher.communicate(timeout=10)[0])
        pairs = set(pair_statuses(ctx, job))
        # Each pair's solver interval is [var.out published - WCTIME, var.out published]: runsolver writes var.out
        # when the solver ends, and WCTIME is its measured wallclock.
        intervals = {}
        for e in events:
            if e["pair"] in pairs and e["kind"] == "var" and e["values"].get("WCTIME"):
                end = (
                    datetime.strptime(e["t"], "%Y-%m-%dT%H:%M:%S.%f")
                    .replace(tzinfo=timezone.utc)
                    .timestamp()
                )
                intervals.setdefault(
                    e["pair"], (end - float(e["values"]["WCTIME"]), end)
                )
        overlaps = [
            (a, b)
            for a in intervals
            for b in intervals
            if a < b
            and intervals[a][0] < intervals[b][1]
            and intervals[b][0] < intervals[a][1]
        ]
        cap.check(
            "overlap",
            "at least two pairs of the concurrent job actually ran at the same time (#143/#144)",
            "from the read-only watcher, the solver intervals [var.out time - WCTIME, var.out time] of at least two"
            " pairs of the concurrent job intersect; without that the marker checks prove nothing about concurrency",
            "at least one overlapping pair of pairs",
            {
                "intervals": {
                    p: [round(s, 3), round(t, 3)] for p, (s, t) in intervals.items()
                },
                "overlaps": overlaps,
                # queuesub_time and start_time separate a dispatch that serialises (the second pair is not even
                # submitted until the first finishes) from a host that simply ran them back to back.
                "dispatch": ctx.db.rows(
                    "SELECT id, coalesce(queuesub_time::text,''), coalesce(start_time::text,''),"
                    " coalesce(end_time::text,'') FROM starexec.job_pairs"
                    f" WHERE job_id={int(job)} ORDER BY id;"
                ),
            },
            bool(overlaps),
            ["create_job"],
            status=None if overlaps else "BLOCKED",
        )
        dispatch = ctx.db.rows(
            "SELECT id, CASE WHEN queuesub_time IS NULL THEN 'null' ELSE 'set' END,"
            " CASE WHEN start_time IS NULL THEN 'null' ELSE 'set' END,"
            " CASE WHEN end_time IS NULL THEN 'null' ELSE 'set' END"
            f" FROM starexec.job_pairs WHERE job_id={int(job)} ORDER BY id;"
        )
        started = [r for r in dispatch if r[2] == "set"]
        # Ledger B5: PodmanBackend populates job_pairs.start_time, LocalBackend leaves it NULL on the same
        # commit. Anything deriving a queue delay or a run duration from start_time is silently wrong there,
        # so the asymmetry is asserted here rather than left as a note: PASS on Podman, EXPECTED-FAIL on Local.
        cap.check(
            "start-time",
            "every finished pair records when it started running",
            "job_pairs.start_time is set for every pair of the concurrent job, as queuesub_time and end_time are",
            {"pairs_with_start_time": len(dispatch)},
            {
                "pairs": len(dispatch),
                "with_start_time": len(started),
                "columns_set": dispatch,
            },
            bool(dispatch) and len(started) == len(dispatch),
            ["create_job"],
            known="B5" if ctx.cfg.backend == "local" else None,
        )
    else:
        cap.blocked(
            "overlap",
            "concurrent pairs actually overlapped",
            "app_exec is not configured",
        )
    rows = ctx.db.rows(
        f"SELECT p.id, s.config_id, coalesce(to_char(p.end_time,'YYYY-MM-DD\"T\"HH24:MI:SS.MS'),'')"
        f" FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
        f" WHERE p.job_id={job} ORDER BY p.id;"
    )
    ctx.run.recorder.note(
        f"concurrent job {job} end times (overlap is backend-dependent and not asserted): {rows}"
    )
    for i, (pair, config, _) in enumerate(rows, start=1):
        own, other = (
            (FAST_MARKER, SLOW_MARKER)
            if int(config) == cfgs["fast"]
            else (SLOW_MARKER, FAST_MARKER)
        )
        _marker_check(
            cap,
            ctx,
            owner,
            f"3.{i}",
            f"submitted together: pair {pair} ({own})",
            int(pair),
            own,
            other,
        )
