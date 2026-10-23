#!/usr/bin/env python3
"""Assert the chart keeps the embedded PostgreSQL database on a claim by default.

With postgres.host set to localhost the chart runs PostgreSQL as a sidecar in the
app pod. Its postgres-data volume is a PersistentVolumeClaim when
postgres.persistence.enabled is true and an emptyDir otherwise, and an emptyDir
starts empty whenever the pod is recreated: an upgrade, a node drain or an
eviction then loses every account, solver, benchmark and job result. A release
installed from the base values must therefore keep its database, and a profile
that wants a throwaway database (values-ci.yaml) must still be able to say so.
"""

import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required to run this check")

CHART = "charts/starexec"
NAMESPACE = "starexec"
SECRET = ["--set", "postgres.existingSecret=starexec-postgres-credentials"]


def render(extra):
    cmd = (
        ["helm", "template", "starexec", CHART, "--namespace", NAMESPACE]
        + SECRET
        + extra
    )
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(
            f"helm template {' '.join(extra) or '(base values)'} failed:\n{out.stderr}"
        )
    return [doc for doc in yaml.safe_load_all(out.stdout) if doc]


def database_volume(docs):
    """Return (kind, claim name) of the app Deployment's postgres-data volume."""
    for doc in docs:
        if doc.get("kind") != "Deployment":
            continue
        for volume in doc["spec"]["template"]["spec"].get("volumes") or []:
            if volume.get("name") != "postgres-data":
                continue
            if "persistentVolumeClaim" in volume:
                return "persistentVolumeClaim", volume["persistentVolumeClaim"][
                    "claimName"
                ]
            if "emptyDir" in volume:
                return "emptyDir", None
            return next(iter(k for k in volume if k != "name"), "none"), None
    sys.exit(
        "no Deployment with a postgres-data volume was rendered - the render or the filter is wrong"
    )


def main():
    failures = []

    docs = render([])
    kind, claim = database_volume(docs)
    claims = {
        d["metadata"]["name"] for d in docs if d.get("kind") == "PersistentVolumeClaim"
    }
    if kind != "persistentVolumeClaim":
        failures.append(
            f"base values: the database volume is {kind}, so the database is lost whenever the pod is recreated"
        )
    elif claim not in claims:
        failures.append(
            f"base values: the database volume names claim {claim}, which the chart does not render"
        )
    else:
        print(f"OK base values: the database lives on claim {claim}")

    kind, _ = database_volume(render(["--set", "postgres.persistence.enabled=false"]))
    if kind != "emptyDir":
        failures.append(
            f"postgres.persistence.enabled=false: the database volume is {kind}, not the emptyDir a throwaway profile asks for"
        )
    else:
        print("OK postgres.persistence.enabled=false: the database is an emptyDir")

    if failures:
        print("FAIL:")
        for failure in failures:
            print(f"  {failure}")
        sys.exit(1)


if __name__ == "__main__":
    main()
