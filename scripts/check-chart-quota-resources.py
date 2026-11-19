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

It also refuses a key directly under `resources`, in any YAML file of the chart
directory, that the chart's values.yaml does not declare. values.yaml is the
chart's interface: it declares every `resources.<key>` the templates read, so a
template that starts reading a new one must declare its default there. Helm
merges an undeclared key in and renders nothing from it: values-prod.yaml and
values-dev.yaml once set `resources.requests`/`limits`, so both ran the
application at the chart defaults.
"""
import pathlib
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


def unread_resource_keys():
    """Keys under `resources` in the chart's YAML files that values.yaml does not declare, as messages."""
    chart = pathlib.Path(CHART)
    declared = (yaml.safe_load((chart / "values.yaml").read_text()) or {}).get("resources")
    if not isinstance(declared, dict) or not declared:
        sys.exit(f"{CHART}/values.yaml declares no resources.<key> - the contract is missing")
    files = sorted(f for pattern in ("*.yaml", "*.yml", "*.yaml.example") for f in chart.glob(pattern)
                   if f.name not in ("values.yaml", "Chart.yaml"))
    problems = []
    for f in files:
        doc = yaml.safe_load(f.read_text())
        if doc is None:
            continue
        if not isinstance(doc, dict):
            problems.append(f"{f.name}: the root is not a mapping")
            continue
        resources = doc.get("resources")
        if resources is None:
            continue
        if not isinstance(resources, dict):
            problems.append(f"{f.name}: resources is not a mapping")
            continue
        for key in resources:
            if key not in declared:
                problems.append(f"{f.name}: resources.{key} is not declared in values.yaml"
                                f" (it declares resources.{', resources.'.join(declared)})")
    return problems


def main():
    unread = unread_resource_keys()
    if unread:
        print(f"FAIL: {len(unread)} resources key(s) in the chart directory would be ignored:")
        for u in unread:
            print(f"  - {u}")
        sys.exit(1)

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
