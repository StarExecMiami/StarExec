#!/usr/bin/env python3
"""Assert every container in the rendered chart declares quota-satisfying resources.

A namespace with a ResourceQuota that constrains requests.cpu/requests.memory/
limits.cpu/limits.memory refuses any pod containing a container that declares
none of them. Kubernetes names the offending *container*, not the workload:

    pods "starexec-..." is forbidden: failed quota: starexec-quota:
    must specify limits.cpu for: fix-permissions; ...

Quokka's `starexec` namespace enforces exactly that. Jenkins build #91 could not
create a replacement pod because the fix-permissions init container declared no
resources, and because the Deployment uses the Recreate strategy the old pod had
already been removed before the refusal landed.

This check renders each values profile and asserts the four fields on every
container and initContainer, so the next container added to the chart cannot
reintroduce the same outage.
"""
import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required to run this check")

CHART = "charts/starexec"
REQUIRED = (("requests", "cpu"), ("requests", "memory"),
            ("limits", "cpu"), ("limits", "memory"))

# Profile -> extra args. values-prod.yaml is the profile Jenkins renders for
# DEPLOY_ENV=prod (Jenkinsfile: HELM_VALUES=charts/starexec/values-prod.yaml).
# The namespace must match each profile's kubernetes.jobNamespace: the chart
# fails the render otherwise. Same mapping helm-validate.yml uses, and the same
# namespace Jenkins deploys each profile into.
PROFILES = {
    "values-prod.yaml": ("starexec", []),
    "values-dev.yaml": ("starexec-dev", []),
    "values-kubernetes.yaml": ("starexec",
                               ["--set", "postgres.allowInsecureDevCredentials=true"]),
}


def render(values, namespace, extra):
    cmd = ["helm", "template", "starexec", CHART, "-f", f"{CHART}/{values}",
           "--namespace", namespace] + extra
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"helm template failed for {values}:\n{out.stderr}")
    return out.stdout


def containers(doc):
    spec = (doc.get("spec", {}).get("template", {}).get("spec", {})) or {}
    for key in ("initContainers", "containers"):
        for c in spec.get(key) or []:
            yield key, c


def main():
    failures = []
    checked = 0
    for values, (namespace, extra) in PROFILES.items():
        for doc in yaml.safe_load_all(render(values, namespace, extra)):
            if not doc or doc.get("kind") not in ("Deployment", "StatefulSet", "Job"):
                continue
            name = doc.get("metadata", {}).get("name", "<unnamed>")
            for kind, c in containers(doc):
                checked += 1
                res = c.get("resources") or {}
                missing = [f"{a}.{b}" for a, b in REQUIRED
                           if not (res.get(a) or {}).get(b)]
                if missing:
                    failures.append(
                        f"{values} {doc['kind']}/{name} {kind}[{c.get('name')}]"
                        f" missing: {', '.join(missing)}")

    if not checked:
        sys.exit("checked 0 containers - the render or the filter is wrong")

    if failures:
        print(f"FAIL: {len(failures)} container(s) would be refused by a ResourceQuota:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)

    print(f"OK: {checked} container(s) across {len(PROFILES)} profile(s) "
          f"declare all four quota-relevant resource fields")


if __name__ == "__main__":
    main()
