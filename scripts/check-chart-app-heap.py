#!/usr/bin/env python3
"""Assert the application's JVM heap fits inside its memory limit in every values profile.

The image starts Tomcat with an explicit -Xmx (JAVA_OPTS in the Dockerfile), and the
JVM keeps an explicit -Xmx whatever the container's memory limit is. Under rootless
Podman it cannot even read that limit: /sys/fs/cgroup is not readable by the image's
user, and `java -XshowSettings:system` prints "Memory Limit: Unlimited". When the heap
may grow to the limit or past it, the kernel kills Tomcat outright (exit 137,
OOMKilled) once the heap and the JVM's other memory reach the limit, instead of the
JVM raising an OutOfMemoryError that the application can log.

On 2026-10-23 the chart's values.yaml (2Gi, which values-podman.yaml.example
inherits), values-local-dev.yaml (1Gi, the profile `make start` renders) and
values-ci.yaml (512Mi) all ran the image's -Xmx2048m. Under the 1Gi limit the kernel
killed a JVM that held 896 MiB of heap.

For every values file in the chart directory this check renders the chart, reads the
app container's memory limit and its JAVA_OPTS (the rendered env value, else the
Dockerfile's), and fails when the heap ceiling is above 75% of the limit. The sizing
example in docs/CONFIGURATION.md (-Xmx4g under a 6Gi limit) is at 67%. A values file
the check does not list fails it too, so a new profile cannot skip it.
"""

import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required to run this check")

CHART = pathlib.Path("charts/starexec")
DOCKERFILE = pathlib.Path("Dockerfile")
MAX_HEAP_SHARE = 0.75

# Values file -> (namespace, extra helm args), as the other chart checks render them.
# "values.yaml" stands for the chart's defaults alone, rendered without -f; they
# render only with an existing database secret.
PROFILES = {
    "values.yaml": ("starexec", ["--set", "postgres.existingSecret=heap-check"]),
    "values-auto-detected.yaml": ("starexec", []),
    "values-ci.yaml": ("starexec", []),
    "values-dev.yaml": ("starexec-dev", []),
    "values-kubernetes.yaml": (
        "starexec",
        ["--set", "postgres.allowInsecureDevCredentials=true"],
    ),
    "values-local-dev.yaml": ("starexec", []),
    "values-microk8s.yaml": ("starexec", []),
    "values-podman.yaml.example": ("starexec", []),
    "values-prod.yaml": ("starexec", []),
}

# Kubernetes memory quantities: binary and decimal suffixes ("m" is milli).
QUANTITY = {
    "Ki": 2**10,
    "Mi": 2**20,
    "Gi": 2**30,
    "Ti": 2**40,
    "Pi": 2**50,
    "Ei": 2**60,
    "k": 10**3,
    "M": 10**6,
    "G": 10**9,
    "T": 10**12,
    "P": 10**15,
    "E": 10**18,
    "m": 10**-3,
}
# JVM sizes, as in -Xmx2048m or -XX:MaxHeapSize=2g.
JVM_SIZE = {"": 1, "k": 2**10, "m": 2**20, "g": 2**30, "t": 2**40}
HEAP_FLAG = re.compile(r"(?:^|\s)(?:-Xmx|-XX:MaxHeapSize=)(\d+)([kKmMgGtT]?)(?=\s|$)")
VALUES_FILE = re.compile(r"values.*\.yaml(\.example)?")


def quantity_bytes(value):
    """Bytes in a Kubernetes memory quantity such as 512Mi, 3Gi or 2147483648."""
    text = str(value).strip()
    for suffix in sorted(QUANTITY, key=len, reverse=True):
        if text.endswith(suffix):
            return float(text[: -len(suffix)]) * QUANTITY[suffix]
    return float(text)


def heap_ceiling(java_opts):
    """Bytes of the last -Xmx or -XX:MaxHeapSize in java_opts (the JVM takes the last), or None."""
    found = HEAP_FLAG.findall(java_opts)
    if not found:
        return None
    number, unit = found[-1]
    return int(number) * JVM_SIZE[unit.lower()]


def image_java_opts():
    """JAVA_OPTS as the Dockerfile sets it for the runtime image."""
    found = re.findall(r'JAVA_OPTS="([^"]*)"', DOCKERFILE.read_text())
    if len(found) != 1:
        sys.exit(f'expected one JAVA_OPTS="..." in {DOCKERFILE}, found {len(found)}')
    return found[0]


def render(values):
    namespace, extra = PROFILES[values]
    cmd = ["helm", "template", "starexec", str(CHART), "--namespace", namespace] + extra
    if values != "values.yaml":
        cmd += ["-f", str(CHART / values)]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"helm template failed for {values}:\n{out.stderr}")
    return out.stdout


def app_container(manifest, values):
    found = [
        c
        for doc in yaml.safe_load_all(manifest)
        if doc and doc.get("kind") == "Deployment"
        for c in doc["spec"]["template"]["spec"]["containers"]
        if c.get("name") == "app"
    ]
    if len(found) != 1:
        sys.exit(
            f"{values}: expected one Deployment container named app, found {len(found)}"
        )
    return found[0]


def check(values, default_opts):
    """One failure message for this profile, or None after printing its OK line."""
    app = app_container(render(values), values)
    env = {e["name"]: e.get("value") for e in app.get("env") or []}
    if env.get("JAVA_OPTS"):
        java_opts, source = env["JAVA_OPTS"], "env.JAVA_OPTS"
    else:
        java_opts, source = default_opts, "the Dockerfile's JAVA_OPTS"
    limit = ((app.get("resources") or {}).get("limits") or {}).get("memory")
    if limit is None:
        return f"{values}: the app container has no memory limit"
    limit_bytes = quantity_bytes(limit)
    if limit_bytes <= 0:
        return f"{values}: the app memory limit {limit} is not a positive size"
    heap = heap_ceiling(java_opts)
    if heap is None:
        return (
            f"{values}: {source} sets no -Xmx, so the JVM sizes its heap from the memory it "
            "detects, which under rootless Podman is the host's, not the container's"
        )
    share = heap / limit_bytes
    line = f"{values}: heap {heap >> 20} MiB ({source}) is {share:.0%} of the {limit} limit"
    if share > MAX_HEAP_SHARE:
        return f"{line}, above {MAX_HEAP_SHARE:.0%}"
    print(f"OK  {line}")
    return None


def main():
    on_disk = {p.name for p in CHART.iterdir() if VALUES_FILE.fullmatch(p.name)}
    failures = [
        f"{name}: not in PROFILES; add it so its app heap is checked"
        for name in sorted(on_disk - PROFILES.keys())
    ]
    failures += [
        f"{name}: in PROFILES but not in {CHART}"
        for name in sorted(PROFILES.keys() - on_disk)
    ]
    default_opts = image_java_opts()
    checked = 0
    for values in sorted(PROFILES.keys() & on_disk):
        checked += 1
        failure = check(values, default_opts)
        if failure:
            failures.append(failure)
    if failures:
        print("FAIL:")
        for failure in failures:
            print(f"  {failure}")
        sys.exit(1)
    print(
        f"checked {checked} profiles: each app heap is at most "
        f"{MAX_HEAP_SHARE:.0%} of the app's memory limit"
    )


if __name__ == "__main__":
    main()
