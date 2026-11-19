#!/usr/bin/env python3
"""Execute the pinned docker/metadata-action against three synthetic events.

PR #107 fixed a rule that rendered an invalid `:-<sha>` tag. The rule set has
since grown `flavor: latest=false` and two `refs/tags/v` guards, none of which
any test exercises -- and the failure mode is silent: buildx rejects the invalid
reference only at push time, on a tag, which is the worst possible moment to
discover it.

This runs the ACTION ITSELF, at the SHA container-publish.yml pins, so the
assertions are about what will really be produced rather than about a
restatement of the rules. The `enable=${{ ... }}` guards are evaluated by GitHub
before the action ever sees them, so the harness resolves them per event
exactly as GitHub would.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

try:
    import yaml
except ImportError:
    sys.exit("check-image-metadata: PyYAML is required")

WORKFLOW = ".github/workflows/container-publish.yml"
IMAGE = "ghcr.io/starexecmiami/starexec"
SHA40 = "c45dbed4fdab535851ff8112c2e856ee249ee0e3"

EVENTS = [
    {
        "label": "push refs/heads/containerised",
        "event_name": "push",
        "ref": "refs/heads/containerised",
        "sha": SHA40,
        "payload": {"repository": {"default_branch": "containerised"}},
        "expect": {"containerised", "latest", f"sha-{SHA40}", f"containerised-{SHA40[:7]}"},
    },
    {
        "label": "pull_request #110",
        "event_name": "pull_request",
        "ref": "refs/pull/110/merge",
        "sha": SHA40,
        "payload": {"number": 110,
                    "pull_request": {"number": 110, "head": {"sha": SHA40}},
                    "repository": {"default_branch": "containerised"}},
        "expect": {"pr-110", f"pr-110-{SHA40[:7]}", f"sha-{SHA40}"},
    },
    {
        "label": "push refs/tags/v2.6.0",
        "event_name": "push",
        "ref": "refs/tags/v2.6.0",
        "sha": SHA40,
        "payload": {"repository": {"default_branch": "containerised"}},
        "expect": {"2.6.0", "2.6", f"sha-{SHA40}"},
    },
]


def meta_step(wf):
    for job in (wf.get("jobs") or {}).values():
        for step in job.get("steps") or []:
            uses = step.get("uses") or ""
            if uses.startswith("docker/metadata-action@"):
                return uses.split("@", 1)[1].split()[0], step.get("with") or {}
    sys.exit("check-image-metadata: no docker/metadata-action step found")


def resolve_guards(tags_text, ref, event_name):
    """Evaluate the ${{ }} guards GitHub would have substituted before dispatch."""
    def repl(m):
        e = " ".join(m.group(1).split())
        if e == "startsWith(github.ref, 'refs/heads/')":
            return str(ref.startswith("refs/heads/")).lower()
        if e == "startsWith(github.ref, 'refs/tags/v')":
            return str(ref.startswith("refs/tags/v")).lower()
        if e == "github.ref == 'refs/heads/containerised'":
            return str(ref == "refs/heads/containerised").lower()
        if e == "github.event_name != 'pull_request'":
            return str(event_name != "pull_request").lower()
        sys.exit(f"check-image-metadata: unhandled guard expression: {e!r}")
    return re.sub(r"\$\{\{(.+?)\}\}", repl, tags_text, flags=re.S)


def github_token():
    """A read-only token for the action's commit-metadata lookup.

    metadata-action resolves the commit date through the API and fails hard on
    bad credentials. In Actions the job token is already present; locally we
    borrow the operator's gh credential. The value is only ever passed through
    the environment and never printed.
    """
    for var in ("GITHUB_TOKEN", "GH_TOKEN"):
        tok = os.environ.get(var)
        if tok:
            return tok
    if shutil.which("gh"):
        r = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
        if r.returncode == 0 and r.stdout.strip():
            return r.stdout.strip()
    return ""


def fetch_action(ref, dest):
    url = f"https://github.com/docker/metadata-action/archive/{ref}.tar.gz"
    print(f"check-image-metadata: fetching pinned action {ref[:12]}...")
    tgz = os.path.join(dest, "a.tgz")
    urllib.request.urlretrieve(url, tgz)
    with tarfile.open(tgz) as tf:
        tf.extractall(dest)
    for name in os.listdir(dest):
        p = os.path.join(dest, name)
        if os.path.isdir(p) and os.path.isfile(os.path.join(p, "dist", "index.js")):
            return os.path.join(p, "dist", "index.js")
    sys.exit("check-image-metadata: dist/index.js not found in the pinned action")


def run_event(index_js, ev, tags_text, flavor_text, work):
    payload = os.path.join(work, "event.json")
    with open(payload, "w") as fh:
        json.dump(ev["payload"], fh)
    out = os.path.join(work, "out.txt")
    state = os.path.join(work, "state.txt")
    # @actions/core appends to both and refuses to create either.
    open(out, "w").close()
    open(state, "w").close()

    env = dict(os.environ)
    env.update({
        "INPUT_GITHUB-TOKEN": github_token(),
        "INPUT_IMAGES": IMAGE,
        "INPUT_TAGS": resolve_guards(tags_text, ev["ref"], ev["event_name"]),
        "INPUT_FLAVOR": flavor_text,
        "GITHUB_EVENT_NAME": ev["event_name"],
        "GITHUB_REF": ev["ref"],
        "GITHUB_SHA": ev["sha"],
        "GITHUB_EVENT_PATH": payload,
        "GITHUB_REPOSITORY": "StarExecMiami/StarExec",
        "GITHUB_REPOSITORY_OWNER": "StarExecMiami",
        "GITHUB_SERVER_URL": "https://github.com",
        "GITHUB_API_URL": "https://api.github.com",
        "GITHUB_WORKSPACE": work,
        "GITHUB_OUTPUT": out,
        "GITHUB_STATE": state,
        "RUNNER_TEMP": work,
    })
    r = subprocess.run(["node", index_js], env=env, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:], file=sys.stderr)
        print(r.stderr[-2000:], file=sys.stderr)
        sys.exit(f"check-image-metadata: action failed for {ev['label']}")

    text = open(out).read()
    m = re.search(r"^tags<<(\S+)$(.*?)^\1$", text, re.M | re.S)
    raw = m.group(2) if m else ""
    return [t.split(":", 1)[1] for t in raw.strip().splitlines() if ":" in t]


def main():
    if not shutil.which("node"):
        sys.exit("check-image-metadata: node is required")
    with open(WORKFLOW) as fh:
        wf = yaml.safe_load(fh)
    ref, inputs = meta_step(wf)
    tags_text = inputs.get("tags", "")
    flavor_text = inputs.get("flavor", "")
    if "latest=false" not in flavor_text:
        print("check-image-metadata: ERROR: flavor must pin latest=false; metadata-action "
              "defaults to latest=auto and would add :latest to any semver tag.",
              file=sys.stderr)
        return 1
    print("check-image-metadata: flavor pins latest=false")

    work = tempfile.mkdtemp(prefix="meta-")
    try:
        index_js = fetch_action(ref, work)
        failures = 0
        for ev in EVENTS:
            sub = tempfile.mkdtemp(dir=work)
            tags = set(run_event(index_js, ev, tags_text, flavor_text, sub))
            print(f"\ncheck-image-metadata: {ev['label']}")
            for t in sorted(tags):
                print(f"    {t}")

            missing = ev["expect"] - tags
            extra = tags - ev["expect"]
            if missing:
                print(f"  ERROR missing expected tags: {sorted(missing)}", file=sys.stderr)
                failures += 1
            if extra:
                print(f"  ERROR unexpected tags: {sorted(extra)}", file=sys.stderr)
                failures += 1

            # Forbidden shapes, each one a real defect rather than a style rule.
            for t in tags:
                if t.startswith("-"):
                    print(f"  ERROR empty-prefix tag {t!r}: {{{{branch}}}} is empty off a "
                          "branch ref and buildx rejects this reference", file=sys.stderr)
                    failures += 1
                if t == "latest" and ev["ref"].startswith("refs/tags/"):
                    print("  ERROR :latest was applied to a version tag; latest must keep "
                          "tracking containerised HEAD", file=sys.stderr)
                    failures += 1
            if not missing and not extra:
                print("  OK exact expected tag set")

        if failures:
            print(f"\ncheck-image-metadata: FAILED ({failures})", file=sys.stderr)
            return 1
        print("\ncheck-image-metadata: PASSED -- all three event shapes produce the "
              "intended tags and no invalid reference")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
