#!/usr/bin/env python3
"""StarExec backend-agnostic 63-cell E2E acceptance harness.

  run.py --list
  run.py --verify-endpoints [--commit SHA]
  run.py --fixtures-only [--fixture-mode reproducible-offline|scientific] [--prescreen]
  run.py --config CFG.json [--mode fresh|upgrade] [--only 0,1,3] [--state state.json]
  run.py --record-blocked --backend podman --mode fresh --commit SHA --reason "..." --matrix-out DIR

Exit codes:
  0: all selected checks passed
  1: any capability check failed
  2: blocked, configuration error, or fixture error
  3: clean except known ledger defects or gated checks
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from capabilities import BY_NUMBER, CAPABILITIES, after_for, bucket_for  # noqa: E402
from capabilities.c00_deployment import readiness  # noqa: E402
from lib import config as config_mod  # noqa: E402
from lib import endpoints, fixtures, matrix  # noqa: E402
from lib.context import Context  # noqa: E402
from lib.dag import DAGValidationError, build_dag, run_dag  # noqa: E402
from lib.report import Capability, Run, new_runid  # noqa: E402
from lib.state import State  # noqa: E402

REPO = HERE.parents[1]  # scripts/e2e -> repository root


def cmd_list():
    for number, name, _fn, needs, creates in CAPABILITIES:
        print(f"{number:>3}  {name}")
        print(f"       needs: {', '.join(needs) or '-'}")
        print(f"     creates: {', '.join(creates) or '-'}")
    return 0


def cmd_verify(commit=None):
    results = endpoints.verify(commit, REPO)
    bad = [r for r in results if not r["ok"]]
    for r in results:
        print(
            f"{'ok  ' if r['ok'] else 'FAIL'} {r['key']:<32} "
            f"{r.get('cited') or r.get('reason')} @ {r['commit']}"
            + (f"  (needle now at {r['found_at']})" if r.get("found_at") else "")
        )
    print(
        json.dumps(
            {
                "commit": (
                    commit if commit is not None else "per-entry registry source"
                ),
                "entries": len(results),
                "ok": len(results) - len(bad),
                "failed": len(bad),
            }
        )
    )
    return 0 if not bad else 1


def _build_or_load_fixtures(args, fixture_mode):
    return fixtures.build(
        mode=fixture_mode,
        run_prescreen=args.prescreen,
        eprover_source=args.eprover_source,
        tptp4x_source=args.tptp4x_source,
        jjparser_source=args.jjparser_source,
        datasets_dir=args.datasets_dir,
    )


def cmd_fixtures(args):
    manifest = _build_or_load_fixtures(args, args.fixture_mode or "reproducible-offline")
    summary = {
        "fixture_mode": manifest.get("fixture_mode"),
        "archives_ok": all(v["ok"] for v in manifest.get("archives", {}).values()),
        "error": manifest.get("error"),
        "problems": {
            n: v["expected_status"] for n, v in manifest.get("problems", {}).items()
        },
        "axioms": len(manifest.get("axioms", {})),
        "files": {n: v["sha256"][:16] for n, v in manifest.get("files", {}).items()},
        "test_sequence_fixtures": sorted(manifest.get("test_sequence_fixtures", {})),
        "csa_count": manifest.get("csa_count"),
        "manifest": str(fixtures.MANIFEST),
    }
    print(json.dumps(summary, indent=2))
    return 0 if summary["archives_ok"] and not summary["error"] else 2


def cmd_record_blocked(args):
    if args.backend not in matrix.BACKENDS:
        raise SystemExit(f"unknown backend {args.backend!r}")
    if args.mode not in matrix.MODES:
        raise SystemExit(f"unknown mode {args.mode!r}")
    out_dir = Path(args.matrix_out)
    report = matrix.build_matrix_report(
        [],
        commit_sha=args.commit,
        active_mode=args.mode,
        fixture_mode=args.fixture_mode or "reproducible-offline",
        blocked_backends={(args.mode, args.backend): args.reason},
    )
    paths = matrix.write_matrix_artifacts(report, out_dir)
    print(
        json.dumps(
            {
                "commitSha": report["commitSha"],
                "mode": report["mode"],
                "backend": args.backend,
                "countsByStatus": report["countsByStatus"],
                "matrix_json": str(paths["json"]),
                "matrix_csv": str(paths["csv"]),
                "matrix_summary": str(paths["markdown"]),
            }
        )
    )
    return 2


def _parse_only(only_arg):
    selected = [c[0] for c in CAPABILITIES]
    if not only_arg:
        return selected
    wanted = [int(x) for x in only_arg.split(",") if x.strip()]
    unknown = [n for n in wanted if n not in BY_NUMBER]
    if unknown:
        raise SystemExit(f"unknown capability numbers {unknown}")
    return [n for n in selected if n in wanted]


def cmd_validate_dag(args):
    selected = _parse_only(args.only)
    seeded_keys = set()
    if args.state:
        prior = json.loads(Path(args.state).read_text())
        seeded_keys = set(prior.get("ids", {}).keys()) | set(prior.get("facts", {}).keys())
    try:
        dag = build_dag(
            CAPABILITIES,
            selected=selected,
            seeded_keys=seeded_keys,
            buckets=bucket_for,
            after_map=after_for,
        )
    except DAGValidationError as exc:
        print(f"DAG validation failed: {exc}", file=sys.stderr)
        return 1
    for warning in dag.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    bucket_counts = {}
    for num in dag.order:
        b = dag.buckets[num]
        bucket_counts[b] = bucket_counts.get(b, 0) + 1
    print(
        json.dumps(
            {
                "valid": True,
                "capabilities": len(dag.order),
                "edges": sum(len(v) for v in dag.succs.values()),
                "buckets": bucket_counts,
                "warnings": dag.warnings,
            }
        )
    )
    return 0


def cmd_run(args):
    cfg = config_mod.load(args.config)
    if args.mode:
        cfg.mode = args.mode
    if args.image_digest:
        cfg.image_digest = args.image_digest
    if args.fixture_mode:
        cfg.fixture_mode = args.fixture_mode
    if args.backend and args.backend != cfg.backend:
        raise config_mod.ConfigError(
            f"CLI --backend {args.backend!r} conflicts with config backend {cfg.backend!r}"
        )

    fx = fixtures.ensure_loaded(
        mode=cfg.fixture_mode,
        eprover_source=args.eprover_source,
        tptp4x_source=args.tptp4x_source,
        jjparser_source=args.jjparser_source,
        datasets_dir=args.datasets_dir,
    )
    selected = _parse_only(args.only)
    seeded_keys = set()
    if args.state:
        prior = json.loads(Path(args.state).read_text())
        seeded_keys = set(prior.get("ids", {}).keys()) | set(
            prior.get("facts", {}).keys()
        )
    try:
        dag = build_dag(
            CAPABILITIES,
            selected=selected,
            seeded_keys=seeded_keys,
            buckets=bucket_for,
            after_map=after_for,
        )
    except DAGValidationError as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2

    runid = new_runid()
    owners = {}
    for n, _, _, _, creates in CAPABILITIES:
        for key in creates:
            owners[key] = owners.get(key, ()) + (n,)
    state = State(
        cfg.report_dir / f"state-{cfg.backend}-{runid}.json",
        seed=args.state,
        owners=owners,
    )

    run = Run(cfg, runid, state.path, sys.argv[1:])
    run.data["fixtures_manifest"] = str(fixtures.MANIFEST)
    run.data["fixture_mode"] = cfg.fixture_mode
    run.data["selected"] = selected
    ctx = Context(cfg, run, state, fx)
    parallel_stats = None
    try:
        if args.workers == 1:
            for warning in dag.warnings:
                print(f"warning: {warning}", file=sys.stderr, flush=True)
            for number in selected:
                _, name, fn, needs, creates = BY_NUMBER[number]
                ready = readiness(ctx)
                if ready != 200:
                    run.data["stopped"] = {"before_capability": number, "readiness": ready}
                    print(
                        f"[{number}] not started: readiness returned {ready}; stopping the run",
                        flush=True,
                    )
                    break
                print(f"[{number}] {name}", flush=True)
                cap = Capability(run, number, name, ctx)
                state.act_as(number, creates)
                error = None
                try:
                    fn(cap, ctx)
                except Exception as exc:
                    error = exc
                status = cap.close(error)
                print(f"[{number}] {status}", flush=True)
            run.finish()
        else:
            parallel_stats = run_dag(
                dag,
                ctx,
                workers=args.workers,
                k8s_job_slots=args.k8s_job_slots,
                readiness_fn=readiness,
            )
            run.finish(order_numbers=selected)
            print(
                f"DAG execution complete: workers={parallel_stats['workers']}, "
                f"k8s_job_slots={parallel_stats['k8s_job_slots']}, "
                f"wall_clock_s={parallel_stats['wall_clock_s']}, "
                f"sum_capability_s={parallel_stats['sum_capability_s']}",
                flush=True,
            )
    finally:
        ctx.close()

    matrix_dir = Path(args.matrix_out) if args.matrix_out else cfg.report_dir
    matrix_report = matrix.build_matrix_report(
        [run.data],
        commit_sha=cfg.commit,
        active_mode=cfg.mode,
        fixture_mode=cfg.fixture_mode,
    )
    matrix_paths = matrix.write_matrix_artifacts(matrix_report, matrix_dir)

    known = [
        {"check": c["id"], "ledger": c.get("detail", {}).get("ledger")}
        for cap_entry in run.data["capabilities"]
        for c in cap_entry["checks"]
        if c["status"] == "EXPECTED-FAIL"
    ]
    for item in known:
        print(f"EXPECTED-FAIL {item['check']} ledger {item['ledger']}", flush=True)
    no_longer = [
        {"check": c["id"], "ledger": c.get("detail", {}).get("ledger")}
        for cap_entry in run.data["capabilities"]
        for c in cap_entry["checks"]
        if c["status"] == "PASS" and c.get("detail", {}).get("reproduced") is False
    ]
    for item in no_longer:
        print(
            f"NO-LONGER-REPRODUCES {item['check']} ledger {item['ledger']}"
            " -- verify the cause and close the entry",
            flush=True,
        )
    print(
        json.dumps(
            {
                "verdict": run.data["verdict"],
                "summary": run.data["summary"],
                "matrix_counts": matrix_report["countsByStatus"],
                "expected_fail": known,
                "no_longer_reproduces": no_longer,
                "report": str(run.path),
                "matrix_json": str(matrix_paths["json"]),
                "matrix_csv": str(matrix_paths["csv"]),
                "matrix_summary": str(matrix_paths["markdown"]),
                "state": str(state.path),
            }
        )
    )
    return {"PASS": 0, "SKIPPED-GATED": 3, "EXPECTED-FAIL": 3, "FAIL": 1}.get(
        run.data["verdict"], 2
    )


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--list", action="store_true")
    p.add_argument("--verify-endpoints", action="store_true")
    p.add_argument(
        "--commit",
        default=None,
        help="verify every citation at SHA (or bind blocked matrix report to SHA)",
    )
    p.add_argument("--fixtures-only", action="store_true")
    p.add_argument(
        "--fixture-mode",
        choices=["reproducible-offline", "scientific"],
        default=None,
        help="fixture resolution mode (default: reproducible-offline)",
    )
    p.add_argument("--eprover-source", type=Path, default=None)
    p.add_argument("--tptp4x-source", type=Path, default=None)
    p.add_argument("--jjparser-source", type=Path, default=None)
    p.add_argument("--datasets-dir", type=Path, default=None)
    p.add_argument(
        "--prescreen",
        action="store_true",
        help="with --fixtures-only: select CSA problems with E in a container",
    )
    p.add_argument("--config")
    p.add_argument("--backend", choices=["local", "podman", "kubernetes"], default=None)
    p.add_argument("--mode", choices=["fresh", "upgrade"], default=None)
    p.add_argument("--image-digest", default=None)
    p.add_argument("--matrix-out", default=None)
    p.add_argument("--only")
    p.add_argument("--state")
    p.add_argument("--validate-dag", action="store_true", help="validate capability DAG and exit")
    p.add_argument("--workers", type=int, default=1, help="concurrent DAG workers (default: 1, serial)")
    p.add_argument(
        "--k8s-job-slots",
        type=int,
        default=2,
        help="max concurrent Bucket C (k8s) capabilities when --workers > 1 (default: 2)",
    )
    p.add_argument("--record-blocked", action="store_true")
    p.add_argument("--reason", default="prerequisite check failed")
    args = p.parse_args()
    if args.workers < 1:
        p.error("--workers must be >= 1")
    if args.k8s_job_slots < 1:
        p.error("--k8s-job-slots must be >= 1")
    if args.list:
        return cmd_list()
    if args.verify_endpoints:
        return cmd_verify(args.commit)
    if args.validate_dag:
        return cmd_validate_dag(args)
    if args.fixtures_only:
        return cmd_fixtures(args)
    if args.record_blocked:
        if not (args.backend and args.mode and args.commit and args.matrix_out):
            p.error("--record-blocked requires --backend, --mode, --commit, and --matrix-out")
        return cmd_record_blocked(args)
    if not args.config:
        p.error("--config is required for a run")
    try:
        return cmd_run(args)
    except (config_mod.ConfigError, fixtures.FixtureError) as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
