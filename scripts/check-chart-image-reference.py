#!/usr/bin/env python3
"""Prove the chart's appVersion does not silently choose the container image.

Chart.yaml now carries `appVersion: 2.6.0`, and helm-repo.yml publishes on a
push to containerised -- which is BEFORE the v2.6.0 tag exists and therefore
before any 2.6.0 image has been published. If the templates fell back to
`.Chart.AppVersion` for the image tag (a common chart idiom, and Helm's own
default in `helm create`), publishing chart 0.2.0 at merge time would hand
every operator a reference to an image that does not exist yet.

This asserts the property that makes the early publication harmless: the image
reference is resolved from values only. It is a metadata-only bump.
"""
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("check-chart-image-reference: PyYAML is required")

CHART = "charts/starexec"
TEMPLATES = f"{CHART}/templates"


def render(extra=None):
    cmd = ["helm", "template", "starexec", CHART,
           "-f", f"{CHART}/values-kubernetes.yaml",
           "--set", "postgres.allowInsecureDevCredentials=true"]
    cmd += extra or []
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-1500:], file=sys.stderr)
        sys.exit("check-chart-image-reference: helm template failed")
    return r.stdout


def app_images(rendered):
    out = []
    for doc in yaml.safe_load_all(rendered):
        if not doc or doc.get("kind") != "Deployment":
            continue
        spec = doc["spec"]["template"]["spec"]
        for c in spec.get("containers", []) + (spec.get("initContainers") or []):
            out.append((doc["metadata"]["name"], c["name"], c.get("image", "")))
    return out


def main():
    failures = 0
    chart_meta = yaml.safe_load(open(f"{CHART}/Chart.yaml"))
    app_version = str(chart_meta.get("appVersion") or "").strip()
    print(f"check-chart-image-reference: Chart.yaml appVersion = {app_version!r}")

    # 1. No template may reference .Chart.AppVersion in an image position.
    import os
    offenders = []
    for root, _, files in os.walk(TEMPLATES):
        for fn in files:
            if not fn.endswith((".yaml", ".yml", ".tpl")):
                continue
            path = os.path.join(root, fn)
            for i, line in enumerate(open(path), 1):
                if ".Chart.AppVersion" in line and re.search(r"image|tag", line, re.I):
                    offenders.append(f"{path}:{i}: {line.strip()}")
    if offenders:
        print("check-chart-image-reference: ERROR: appVersion used in an image position:",
              file=sys.stderr)
        for o in offenders:
            print(f"    {o}", file=sys.stderr)
        failures += 1
    else:
        print("check-chart-image-reference: OK no template derives an image tag "
              "from .Chart.AppVersion")

    # 2. The rendered default must not be the appVersion.
    rendered = render()
    images = app_images(rendered)
    if not images:
        sys.exit("check-chart-image-reference: rendered no Deployment containers")
    print("check-chart-image-reference: rendered image references:")
    for dep, cname, image in images:
        print(f"    {dep}/{cname}: {image}")

    for dep, cname, image in images:
        tag = image.rsplit(":", 1)[-1] if ":" in image.rsplit("/", 1)[-1] else ""
        if "@" in image:
            continue  # digest-pinned; appVersion is irrelevant
        if app_version and tag == app_version:
            print(f"check-chart-image-reference: ERROR: {dep}/{cname} defaults to tag "
                  f"{tag!r}, which equals appVersion. Chart 0.2.0 publishes at merge, "
                  "before that image exists.", file=sys.stderr)
            failures += 1
    if not failures:
        print("check-chart-image-reference: OK no rendered image defaults to the appVersion")

    # 3. An explicit tag must still win, and a digest must still win over a tag.
    tagged = app_images(render(["--set", "image.tag=sha-deadbeef"]))
    if not any("sha-deadbeef" in img for _, _, img in tagged):
        print("check-chart-image-reference: ERROR: image.tag did not reach the rendered "
              "image reference", file=sys.stderr)
        failures += 1
    else:
        print("check-chart-image-reference: OK image.tag controls the reference")

    digested = app_images(render(["--set", "image.digest=sha256:" + "a" * 64]))
    if not any("@sha256:" + "a" * 64 in img for _, _, img in digested):
        print("check-chart-image-reference: ERROR: image.digest did not reach the rendered "
              "image reference", file=sys.stderr)
        failures += 1
    else:
        print("check-chart-image-reference: OK image.digest controls the reference")

    if failures:
        print(f"check-chart-image-reference: FAILED ({failures})", file=sys.stderr)
        return 1
    print("check-chart-image-reference: PASSED -- chart 0.2.0 is a metadata-only bump; "
          "publishing it before the tag cannot point anyone at a missing image")
    return 0


if __name__ == "__main__":
    sys.exit(main())
