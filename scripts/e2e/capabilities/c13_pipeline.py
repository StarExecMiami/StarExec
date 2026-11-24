"""13: two-stage pipeline with a known answer per stage: exact per-stage attributes (#179) and exact
per-stage measurements against that stage's own runsolver files (#180)."""

import io
import subprocess
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import job_id_by_name, wait_terminal, stop_watcher

WATCHER = (
    Path(__file__).resolve().parent.parent / "lib" / "watch_stage_measurements.bash"
)
EXPECTED = {
    1: {
        "starexec-result": "Success",
        "szs-status-count": "1",
        "szs-multi-status-policy": "consistent",
        "szs-observed-statuses": "Success",
        "szs-has-output": "true",
        "szs-output-balanced": "true",
        "szs-output-types": "Assurance",
    },
    2: {
        "starexec-result": "Theorem",
        "szs-status-count": "1",
        "szs-multi-status-policy": "consistent",
        "szs-observed-statuses": "Theorem",
    },
}
MARKER = [
    "% SZS status Success for theBenchmark.p",
    "% SZS output start Assurance for theBenchmark.p",
    "% SZS output end Assurance for theBenchmark.p",
]
FIELDS = [
    ("wallclock", "var", "WCTIME"),
    ("cpu", "var", "CPUTIME"),
    ("user_time", "var", "USERTIME"),
    ("system_time", "var", "SYSTEMTIME"),
    ("max_vmem", "var", "MAXVM"),
    ("max_res_set", "watcher", "RSS"),
]


def utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]


def job_xml(name, verbatim, e_config, post, queue, space, benches):
    lines = "".join(
        f'<JobLine pipe-name="known_answer" bench-id="{b}" job-space-id="{space}"/>'
        for b in benches
    )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<tns:Jobs xmlns:tns="https://www.starexec.org/starexec/public/batchJobSchema.xsd">
<SolverPipeline name="known_answer"><PipelineStage config-id="{verbatim}" primary="true"/><PipelineStage config-id="{e_config}"/></SolverPipeline>
<Job name="{name}"><JobAttributes><description value="E2E known-answer pipeline"/><queue-id value="{queue}"/><cpu-timeout value="30"/><wallclock-timeout value="45"/><mem-limit value="0.5"/><postproc-id value="{post}"/><bench-framework value="runsolver"/></JobAttributes>
<StageAttributes><stage-num value="2"/><cpu-timeout value="30"/><wallclock-timeout value="45"/><mem-limit value="0.5"/><postproc-id value="{post}"/></StageAttributes>
{lines}</Job></tns:Jobs>'''


def parse_events(text):
    events = []
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) != 6:
            continue
        t, pair, kind, named, _, values = parts
        vals = (
            dict(kv.split("=", 1) for kv in values.split() if "=" in kv)
            if kind in ("var", "watcher")
            else {}
        )
        events.append(
            {
                "t": t,
                "pair": int(pair),
                "kind": kind,
                "stage": int(named) if named.isdigit() else None,
                "values": vals,
            }
        )
    return events


def run_pipeline(cap, ctx):
    verbatim, e_config, post, space, benches = ctx.state.need(
        "config_verbatim", "config_e", "post_processor", "space", "benchmarks"
    )
    chosen = [benches[n] for n in ("AGT001+1.p", "AGT001+2.p") if n in benches]
    if len(chosen) != 2:
        raise Blocked(
            "AGT001+1.p and AGT001+2.p are needed for the known-answer pipeline"
        )
    owner = ctx.owner()
    watcher = None
    events_text = ""
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
            text=True, start_new_session=True,
        )
        time.sleep(2)
    t0 = utc()
    name = ctx.name("pipeline")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(
            "job.xml",
            job_xml(name, verbatim, e_config, post, ctx.queue(), space, chosen),
        )
    r = owner.post(
        ep.path("upload_job_xml"),
        data={"space": space},
        files={"f": ("pipeline.zip", buf.getvalue(), "application/zip")},
    )
    job = job_id_by_name(ctx, name)
    stages = ctx.db.rows(
        f"SELECT p.id, count(s.stage_number) FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s"
        f" ON s.jobpair_id=p.id WHERE p.job_id={job or 0} GROUP BY 1 ORDER BY 1;"
    )
    cap.check(
        "1",
        "job XML with a two-stage SolverPipeline creates two-stage pairs",
        "POST /secure/upload/jobXML creates the job with 2 pairs, each with exactly 2 jobpair_stage_data rows",
        {"pairs": 2, "stages_each": 2},
        {"http": r.status_code, "job": job, "stages": stages},
        bool(job) and len(stages) == 2 and all(n == "2" for _, n in stages),
        ["upload_job_xml", "upload_job_xml_params"],
    )
    if not job:
        if watcher:
            stop_watcher(watcher)
        raise Blocked("pipeline job not created")
    ctx.state.set("job_pipeline", job)
    ok, last, seconds = wait_terminal(ctx, job, 2, ctx.cfg.timeout("job", 900))
    time.sleep(3)
    t1 = utc()
    if watcher:
        stop_watcher(watcher)
        events_text = watcher.communicate(timeout=10)[0]

    for pid, _ in stages:
        pid = int(pid)
        srows = {
            int(s): (int(st), wall)
            for s, st, wall in ctx.db.rows(
                f"SELECT stage_number, status_code, coalesce(wallclock::text,'') FROM starexec.jobpair_stage_data"
                f" WHERE jobpair_id={pid} ORDER BY 1;"
            )
        }
        pst = ctx.db.int(f"SELECT status_code FROM starexec.job_pairs WHERE id={pid};")
        fixture_ok = pst == 7 and {k: v[0] for k, v in srows.items()} == {1: 7, 2: 7}
        cap.check(
            f"{pid}-status",
            f"pair {pid}: pair and both stages COMPLETE",
            "job_pairs and both stage rows end at 7",
            {"pair": 7, "stages": {1: 7, 2: 7}},
            {"pair": pst, "stages": {k: v[0] for k, v in srows.items()}},
            fixture_ok,
            ["upload_job_xml"],
        )
        out1 = owner.get(
            ep.path("pair_stdout", id=pid, stageNumber=1), params={"limit": -1}
        )
        head = [l for l in out1.text.splitlines() if l.startswith("% SZS")][:3]
        cap.check(
            f"{pid}-stage1-output",
            f"pair {pid}: stage 1 output carries the fixed assurance",
            "GET stage-1 stdout contains the three verbatim-fixture SZS lines in order",
            MARKER,
            {"http": out1.status_code, "szs_lines": head},
            head == MARKER,
            ["pair_stdout"],
        )
        out2 = owner.get(
            ep.path("pair_stdout", id=pid, stageNumber=2), params={"limit": -1}
        )
        szs2 = next((l for l in out2.text.splitlines() if "SZS status" in l), None)
        cap.check(
            f"{pid}-stage2-output",
            f"pair {pid}: stage 2 (E on the verbatim problem) proves Theorem",
            "GET stage-2 stdout has an SZS status line naming Theorem",
            "SZS status Theorem",
            {"http": out2.status_code, "szs": szs2},
            bool(szs2) and "SZS status Theorem" in szs2,
            ["pair_stdout"],
        )
        attrs = {}
        for s, k, v in ctx.db.rows(
            f"SELECT stage_number, attr_key, attr_value FROM starexec.job_attributes WHERE pair_id={pid} ORDER BY 1,2;"
        ):
            attrs.setdefault(int(s), {})[k] = v
        for s in (1, 2):
            stage_attrs = attrs.get(s, {})
            if s == 1:
                matched = stage_attrs == EXPECTED[1]
            else:
                matched = all(stage_attrs.get(k) == v for k, v in EXPECTED[2].items())
            cap.check(
                f"{pid}-attrs-{s}",
                f"pair {pid}: stage {s} attributes match SZSPostProcessor contract (#179)",
                f"job_attributes for stage {s} match the pinned #287 SZSPostProcessor output for that stage's stdout",
                EXPECTED[s],
                stage_attrs,
                matched,
                ["upload_job_xml"],
            )
        cap.check(
            f"{pid}-attrs-extra",
            f"pair {pid}: no attribute rows outside stages 1-2",
            "no job_attributes rows carry a stage other than 1 or 2",
            [],
            sorted(set(attrs) - {1, 2}),
            not (set(attrs) - {1, 2}),
            ["upload_job_xml"],
        )

        if not ctx.cfg.app_exec:
            cap.blocked(
                f"{pid}-measure",
                f"pair {pid}: exact per-stage measurements (#180)",
                "app_exec is not configured, so runsolver's var.out/watcher.out cannot be observed",
            )
            continue
        events = [
            e
            for e in parse_events(events_text)
            if e["pair"] == pid and t0 <= e["t"] <= t1
        ]
        db = {
            int(r[0]): dict(zip([f for f, _, _ in FIELDS], r[1:]))
            for r in ctx.db.rows(
                "SELECT stage_number, coalesce(wallclock::text,'NULL'), coalesce(cpu::text,'NULL'), coalesce(user_time::text,'NULL'),"
                " coalesce(system_time::text,'NULL'), coalesce(max_vmem::text,'NULL'), coalesce(max_res_set::text,'NULL')"
                f" FROM starexec.jobpair_stage_data WHERE jobpair_id={pid} ORDER BY 1;"
            )
        }
        for s in (1, 2):
            src = {"var": [], "watcher": []}
            for e in events:
                if (
                    e["stage"] == s
                    and e["kind"] in src
                    and e["values"]
                    and e["values"] not in src[e["kind"]]
                ):
                    src[e["kind"]].append(e["values"])
            if len(src["var"]) != 1 or len(src["watcher"]) != 1:
                cap.blocked(
                    f"{pid}-measure-{s}",
                    f"pair {pid} stage {s}: exact measurements",
                    f"expected exactly one var.out and one watcher.out capture, got {len(src['var'])}/{len(src['watcher'])}",
                )
                continue
            for column, kind, key in FIELDS:
                want, got = src[kind][0].get(key), db.get(s, {}).get(column)
                okv = (
                    want not in (None, "")
                    and got not in (None, "NULL")
                    and float(got) == float(want)
                )
                cap.check(
                    f"{pid}-{s}-{column}",
                    f"pair {pid} stage {s}: {column} == {kind}.{key} (#180)",
                    f"jobpair_stage_data.{column} for stage {s} equals, as a double, {key} from that stage's own"
                    f" {kind}.out captured by the read-only watcher",
                    want,
                    got,
                    okv,
                    ["upload_job_xml"],
                )
