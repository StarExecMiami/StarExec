#!/usr/bin/env python3
"""Assert that the PersistentVolumeClaims the chart creates survive helm uninstall.

helm uninstall deletes every resource of the release, PersistentVolumeClaims
included, unless a resource carries the annotation helm.sh/resource-policy: keep.
Under the usual reclaim policy (Delete) the volume goes with its claim, and with
the volume the database or every uploaded solver, benchmark and job result. So
the chart annotates each claim it creates (persistence.resourcePolicy: keep).

This check renders every values profile in which the chart creates claims and
fails if one of those claims lacks the annotation. It also renders with
persistence.resourcePolicy="" and fails if any annotation is left, so it cannot
pass on an annotation that the value does not control.
"""

import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required to run this check")

CHART = "charts/starexec"
POLICY = "helm.sh/resource-policy"
# values.yaml alone names no database secret, and helm template refuses it
# without one. The check reads only PersistentVolumeClaims, so any name will do.
SECRET = ["--set", "postgres.existingSecret=starexec-postgres-credentials"]
# Profile -> (namespace, extra helm args, number of claims the chart creates).
# values-prod.yaml is absent on purpose: it names claims created outside the
# chart, so the chart creates none there.
PROFILES = {
    "values.yaml": (
        "starexec",
        SECRET + ["--set", "postgres.persistence.enabled=true"],
        5,
    ),
    "values-dev.yaml": ("starexec-dev", [], 4),
    # External database (postgres.host is not localhost): no postgres claim.
    "values-kubernetes.yaml": ("starexec", [], 4),
    "values-microk8s.yaml": ("starexec", [], 5),
}


def claims(values, namespace, extra):
    cmd = [
        "helm",
        "template",
        "starexec",
        CHART,
        "-f",
        f"{CHART}/{values}",
        "--namespace",
        namespace,
    ]
    out = subprocess.run(cmd + extra, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"{values}: helm template failed: {out.stderr.strip()}")
    return [
        d
        for d in yaml.safe_load_all(out.stdout)
        if d and d.get("kind") == "PersistentVolumeClaim"
    ]


def policy(claim):
    return ((claim.get("metadata") or {}).get("annotations") or {}).get(POLICY)


def main():
    failures = []
    checked = 0
    for values, (namespace, extra, expected) in PROFILES.items():
        found = claims(values, namespace, extra)
        if len(found) != expected:
            failures.append(
                f"{values}: rendered {len(found)} claims, expected {expected}"
            )
        for claim in found:
            checked += 1
            if policy(claim) != "keep":
                failures.append(
                    f"{values}: PersistentVolumeClaim/{claim['metadata']['name']} has"
                    f" {POLICY}={policy(claim)!r}, expected 'keep'"
                )
        # The opt-out must remove the annotation, or the value controls nothing.
        for claim in claims(
            values, namespace, extra + ["--set-string", "persistence.resourcePolicy="]
        ):
            if policy(claim) is not None:
                failures.append(
                    f'{values} with persistence.resourcePolicy="": PersistentVolumeClaim/'
                    f"{claim['metadata']['name']} still has {POLICY}={policy(claim)!r}"
                )
    if not checked:
        sys.exit("checked 0 claims - the render or the filter is wrong")
    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  {f}")
        sys.exit(1)
    print(
        f"OK: {checked} claims in {len(PROFILES)} profiles carry {POLICY}: keep, and the opt-out removes it"
    )


if __name__ == "__main__":
    main()
