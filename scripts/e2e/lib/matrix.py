"""63-cell (21 capabilities x 3 backends) E2E matrix accounting and reporting.

Enforces strict separation of database deployment modes (`fresh` vs `upgrade`) and
strict status accounting (`PASS`, `FAIL`, `BLOCKED`, `SKIPPED`). Blocked, omitted,
or expected-fail items are never promoted into `PASS`.
"""

import csv
from datetime import datetime, timezone
import json
from pathlib import Path

BACKENDS = ("local", "podman", "kubernetes")
MODES = ("fresh", "upgrade")
CELL_STATUSES = ("PASS", "FAIL", "BLOCKED", "SKIPPED")

# Canonical 21 functional rows from issue #293, mapped to implemented E2E capability numbers.
CAPABILITY_ROWS = (
    ("C01", "Users", (1, 27)),
    ("C02", "Governance", (30,)),
    ("C03", "Spaces", (3, 29)),
    ("C04", "Subspaces", (3, 29, 43)),
    ("C05", "Images", (28,)),
    ("C06", "Solvers", (7, 18, 21, 33, 38)),
    ("C07", "Configs", (7, 33)),
    ("C08", "Benchmarks", (8, 9, 40, 41, 42, 43, 44)),
    ("C09", "Problems", (8, 32)),
    ("C10", "Axioms", (32, 39)),
    ("C11", "Processors", (4, 5, 31)),
    ("C12", "Settings", (6, 35)),
    ("C13", "Jobs", (10, 11, 12, 13, 15, 26, 36)),
    ("C14", "Pairs", (11, 14, 19, 23, 24, 25, 26, 36)),
    ("C15", "Pause", (14,)),
    ("C16", "Resume", (14, 16)),
    ("C17", "Link", (34,)),
    ("C18", "Unlink", (34,)),
    ("C19", "Delete", (17, 34, 37)),
    ("C20", "Outputs", (15,)),
    ("C21", "Manifests", (13, 24)),
)


def _utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _cell_status(required_caps, cap_results, stopped_info, backend_blocked_reason):
    if backend_blocked_reason:
        return "BLOCKED", f"backend blocked: {backend_blocked_reason}"

    if not cap_results:
        return "SKIPPED", "not executed in this run"

    executed = {}
    for entry in cap_results:
        num = entry.get("number")
        if num is not None:
            executed[int(num)] = entry.get("status", "BLOCKED")

    present = [n for n in required_caps if n in executed]
    missing = [n for n in required_caps if n not in executed]

    statuses = [executed[n] for n in present]
    if "FAIL" in statuses:
        failing = [n for n in present if executed[n] == "FAIL"]
        return "FAIL", f"capability failure in {failing}"
    if "BLOCKED" in statuses or "EXPECTED-FAIL" in statuses:
        blocked = [n for n in present if executed[n] in ("BLOCKED", "EXPECTED-FAIL")]
        return "BLOCKED", f"blocked or expected-fail in {blocked}"
    if missing:
        if stopped_info is not None:
            return "BLOCKED", f"run stopped before capabilities {missing}"
        return "SKIPPED", f"capabilities not selected in run: {missing}"
    if "SKIPPED-GATED" in statuses:
        gated = [n for n in present if executed[n] == "SKIPPED-GATED"]
        return "SKIPPED", f"gated checks not executed in {gated}"
    if present and all(executed[n] == "PASS" for n in required_caps):
        return "PASS", f"all mapped capabilities passed: {list(required_caps)}"
    return "SKIPPED", "no mapped capabilities passed"


def _evaluate_mode(mode, runs_by_backend, blocked_backends):
    cells = []
    by_backend = {}
    for backend in BACKENDS:
        run_data = runs_by_backend.get((mode, backend))
        blocked_reason = blocked_backends.get((mode, backend))
        cap_results = run_data.get("capabilities", []) if run_data else []
        stopped_info = run_data.get("stopped") if run_data else None
        backend_counts = {s: 0 for s in CELL_STATUSES}
        for cap_id, cap_name, req_caps in CAPABILITY_ROWS:
            status, detail = _cell_status(req_caps, cap_results, stopped_info, blocked_reason)
            backend_counts[status] += 1
            cells.append(
                {
                    "cellId": f"{cap_id}:{backend}:{mode}",
                    "capabilityId": cap_id,
                    "capabilityName": cap_name,
                    "backend": backend,
                    "mode": mode,
                    "requiredCapabilities": list(req_caps),
                    "status": status,
                    "detail": detail,
                }
            )
        by_backend[backend] = {
            "denominator": len(CAPABILITY_ROWS),
            "countsByStatus": backend_counts,
            "runId": run_data.get("runid") if run_data else None,
            "imageDigest": (run_data.get("deployment") or {}).get("image_digest") if run_data else None,
        }

    total_counts = {s: sum(1 for c in cells if c["status"] == s) for s in CELL_STATUSES}
    return {
        "mode": mode,
        "matrixDenominator": len(CAPABILITY_ROWS) * len(BACKENDS),
        "backendDenominator": len(CAPABILITY_ROWS),
        "countsByStatus": total_counts,
        "byBackend": by_backend,
        "cells": cells,
    }


def build_matrix_report(
    runs,
    commit_sha=None,
    active_mode="fresh",
    fixture_mode="reproducible-offline",
    blocked_backends=None,
):
    if active_mode not in MODES:
        raise ValueError(f"Unsupported matrix mode {active_mode!r}; expected one of {MODES}")

    runs_by_backend = {}
    image_digests = {}
    backends_present = []
    for run_data in runs:
        dep = run_data.get("deployment") or {}
        backend = dep.get("backend")
        mode = dep.get("mode", active_mode)
        if backend not in BACKENDS:
            raise ValueError(f"Unsupported backend {backend!r} in run data")
        if mode not in MODES:
            raise ValueError(f"Unsupported mode {mode!r} in run data")
        runs_by_backend[(mode, backend)] = run_data
        if backend not in backends_present:
            backends_present.append(backend)
        if dep.get("image_digest"):
            image_digests[backend] = dep["image_digest"]
        if commit_sha is None and dep.get("commit"):
            commit_sha = dep["commit"]

    blocked = dict(blocked_backends or {})
    modes_payload = {
        mode: _evaluate_mode(mode, runs_by_backend, blocked) for mode in MODES
    }
    primary = modes_payload[active_mode]
    primary_backend = backends_present[0] if len(backends_present) == 1 else "multi"

    return {
        "schemaVersion": 1,
        "generatedAt": _utc_now(),
        "commitSha": commit_sha or "unknown",
        "mode": active_mode,
        "backend": primary_backend,
        "fixtureMode": fixture_mode,
        "imageDigest": image_digests.get(primary_backend) if primary_backend != "multi" else None,
        "imageDigestsByBackend": image_digests,
        "matrixDenominator": len(CAPABILITY_ROWS) * len(BACKENDS),
        "backendDenominator": len(CAPABILITY_ROWS),
        "countsByStatus": primary["countsByStatus"],
        "byBackend": primary["byBackend"],
        "cells": primary["cells"],
        "modes": modes_payload,
    }


def write_matrix_artifacts(report, output_dir):
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    json_path = out / "matrix-report.json"
    csv_path = out / "matrix-report.csv"
    md_path = out / "matrix-summary.md"

    json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "commit_sha",
                "mode",
                "backend",
                "capability_id",
                "capability_name",
                "status",
                "required_capabilities",
                "detail",
            ]
        )
        for mode in MODES:
            for cell in report["modes"][mode]["cells"]:
                writer.writerow(
                    [
                        report["commitSha"],
                        cell["mode"],
                        cell["backend"],
                        cell["capabilityId"],
                        cell["capabilityName"],
                        cell["status"],
                        ";".join(str(x) for x in cell["requiredCapabilities"]),
                        cell["detail"],
                    ]
                )

    lines = [
        "# StarExec 63-Cell Deployed E2E Acceptance Matrix",
        "",
        f"- **Commit SHA:** `{report['commitSha']}`",
        f"- **Active Mode:** `{report['mode']}`",
        f"- **Active Backend:** `{report['backend']}`",
        f"- **Fixture Mode:** `{report['fixtureMode']}`",
        f"- **Matrix Denominator:** `{report['matrixDenominator']}` (`21 x 3` per mode)",
        "",
    ]
    for mode in MODES:
        m = report["modes"][mode]
        counts = m["countsByStatus"]
        lines.append(f"## Mode: `{mode}`")
        lines.append("")
        lines.append(
            f"- **Totals (`{mode}`):** PASS=`{counts['PASS']}` | FAIL=`{counts['FAIL']}` | BLOCKED=`{counts['BLOCKED']}` | SKIPPED=`{counts['SKIPPED']}` / `{m['matrixDenominator']}`"
        )
        lines.append("")
        lines.append("| Backend | PASS | FAIL | BLOCKED | SKIPPED | Denominator | Image Digest |")
        lines.append("|---|---:|---:|---:|---:|---:|---|")
        for backend in BACKENDS:
            b = m["byBackend"][backend]
            bc = b["countsByStatus"]
            digest = b["imageDigest"] or "n/a"
            lines.append(
                f"| `{backend}` | {bc['PASS']} | {bc['FAIL']} | {bc['BLOCKED']} | {bc['SKIPPED']} | {b['denominator']} | `{digest}` |"
            )
        lines.append("")

    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {"json": json_path, "csv": csv_path, "markdown": md_path}
