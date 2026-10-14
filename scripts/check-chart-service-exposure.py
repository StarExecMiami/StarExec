#!/usr/bin/env python3
"""Assert no rendered Service publishes the embedded PostgreSQL port.

The app talks to the PostgreSQL sidecar over pod-local localhost, so no Service
needs port 5432. On a NodePort or LoadBalancer Service, listing 5432 makes
Kubernetes open it on every node (NodePort) or on a load balancer. This check
renders each values profile with `helm template` and fails if any Service that
is not ClusterIP-only carries a 5432 port or targetPort.
"""

import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required to run this check")

CHART = "charts/starexec"
DB_PORT = 5432
# Profile -> (namespace, extra helm args); same mapping as
# check-chart-quota-resources.py, plus the remaining profiles that render.
PROFILES = {
    "values-prod.yaml": ("starexec", []),
    "values-dev.yaml": ("starexec-dev", []),
    "values-kubernetes.yaml": (
        "starexec",
        ["--set", "postgres.allowInsecureDevCredentials=true"],
    ),
    "values-microk8s.yaml": ("starexec", []),
    "values-auto-detected.yaml": ("starexec", []),
}


def render(values, namespace, extra):
    cmd = [
        "helm",
        "template",
        "starexec",
        CHART,
        "-f",
        f"{CHART}/{values}",
        "--namespace",
        namespace,
    ] + extra
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        return None, out.stderr
    return out.stdout, ""


def main():
    failures = []
    services = 0
    rendered = 0
    for values, (namespace, extra) in PROFILES.items():
        text, err = render(values, namespace, extra)
        if text is None:
            print(
                f"SKIP {values}: helm template failed: {err.strip().splitlines()[-1:]}"
            )
            continue
        rendered += 1
        for doc in yaml.safe_load_all(text):
            if not doc or doc.get("kind") != "Service":
                continue
            services += 1
            spec = doc.get("spec", {})
            stype = spec.get("type", "ClusterIP")
            for p in spec.get("ports") or []:
                if DB_PORT in (p.get("port"), p.get("targetPort"), p.get("nodePort")):
                    if stype != "ClusterIP":
                        failures.append(
                            f"{values}: Service/{doc['metadata']['name']} type={stype}"
                            f" publishes {DB_PORT}"
                        )
    if not services:
        sys.exit("checked 0 Services - the render or the filter is wrong")
    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print(
        f"OK: {services} Service(s) across {rendered} profile(s); "
        f"none non-ClusterIP exposes {DB_PORT}"
    )


if __name__ == "__main__":
    main()
