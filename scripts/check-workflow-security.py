#!/usr/bin/env python3
"""Structural guards for the release workflows.

Three properties that are easy to regress in a one-line edit and impossible to
notice in review once the file is a few hundred lines long:

  1. REFS ARE DATA. github.ref_name and friends must never be interpolated into
     an executable context. `${{ }}` substitution happens before the shell or
     the JavaScript engine sees the text, so a crafted ref becomes code. The
     ref namespace accepts characters `git tag` refuses, so "git won't let you
     name a tag that" is not a defence.

  2. PERMISSIONS ARE SCOPED. The workflow default is read-only; only the job
     that publishes holds contents: write; only the notifier holds issues:
     write; nothing reachable from a pull_request holds any write scope.

  3. THE PREFLIGHT STILL CHECKS EVERYTHING. A guard that is deleted from the
     preflight workflow stops running silently -- the workflow still passes,
     just with less in it. Each required invocation is asserted by name.

This is a structural test. It supplements the real GitHub run; it does not
replace it.
"""
import re
import sys

try:
    import yaml
except ImportError:
    sys.exit("check-workflow-security: PyYAML is required")

RELEASE_WF = ".github/workflows/release.yml"
PREFLIGHT_WF = ".github/workflows/release-preflight.yml"

# Contexts that carry attacker-influenced text.
UNTRUSTED = [
    "github.ref_name", "github.ref", "github.head_ref",
    "github.event.release.tag_name",
    "github.event.pull_request.title", "github.event.pull_request.body",
    "github.event.head_commit.message",
]

# Every release-specific guard the preflight must still invoke.
REQUIRED_PREFLIGHT = [
    ("version alignment",        "check-release-version.sh"),
    ("strict tag validation",    "validate-release-tag.sh"),
    ("ref-as-data behaviour",    "check-release-tag-safety.sh"),
    ("cumulative release notes", "check-release-notes.sh"),
    ("release artifacts",        "check-release-assets.sh"),
    ("image metadata harness",   "check-image-metadata.py"),
    ("job-runner isolation",     "check-job-runner-isolation.py"),
    ("helm retention",           "check-helm-retention.py"),
    ("helm fetch failure modes", "test-helm-repo-fetch.py"),
    ("chart image reference",    "check-chart-image-reference.py"),
    ("workflow security",        "check-workflow-security.py"),
]

failures = []


def fail(msg):
    failures.append(msg)
    print(f"check-workflow-security: ERROR: {msg}", file=sys.stderr)


def ok(msg):
    print(f"check-workflow-security: OK {msg}")


def load(path):
    with open(path) as fh:
        return yaml.safe_load(fh)


def executable_bodies(wf):
    """(location, text) for every shell and github-script body in a workflow."""
    for jname, job in (wf.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            name = step.get("name") or f"step[{i}]"
            if isinstance(step.get("run"), str):
                yield f"{jname}/{name}/run", step["run"]
            uses = step.get("uses") or ""
            if uses.startswith("actions/github-script@"):
                script = (step.get("with") or {}).get("script")
                if isinstance(script, str):
                    yield f"{jname}/{name}/script", script


def check_refs_are_data():
    wf = load(RELEASE_WF)
    hits = 0
    for where, body in executable_bodies(wf):
        for ctx in UNTRUSTED:
            if re.search(r"\$\{\{\s*" + re.escape(ctx) + r"\s*\}\}", body):
                fail(f"{RELEASE_WF}: {where} interpolates {ctx} directly into an "
                     "executable body; pass it through env: and read it as a variable")
                hits += 1
    if not hits:
        ok(f"{RELEASE_WF}: no untrusted context is interpolated into a run/script body")


def check_permissions():
    rel = load(RELEASE_WF)
    top = rel.get("permissions")
    if top != {"contents": "read"}:
        fail(f"{RELEASE_WF}: top-level permissions must be exactly "
             f"{{contents: read}}, found {top!r}")
    else:
        ok(f"{RELEASE_WF}: top-level permissions are read-only")

    jobs = rel.get("jobs") or {}
    relperm = (jobs.get("release") or {}).get("permissions")
    if relperm != {"contents": "write"}:
        fail(f"{RELEASE_WF}: job 'release' must hold exactly {{contents: write}}, "
             f"found {relperm!r}")
    else:
        ok(f"{RELEASE_WF}: job 'release' holds contents: write and nothing more")

    notif = jobs.get("notify-failure") or {}
    nperm = notif.get("permissions") or {}
    if nperm.get("issues") != "write":
        fail(f"{RELEASE_WF}: job 'notify-failure' must hold issues: write, found {nperm!r}")
    elif nperm.get("contents") not in (None, "read"):
        fail(f"{RELEASE_WF}: job 'notify-failure' must not hold contents write")
    else:
        ok(f"{RELEASE_WF}: issues: write is isolated in 'notify-failure'")

    if notif.get("needs") != "release":
        fail(f"{RELEASE_WF}: 'notify-failure' must depend on 'release'")
    elif "failure()" not in str(notif.get("if") or ""):
        fail(f"{RELEASE_WF}: 'notify-failure' must be guarded by failure()")
    else:
        ok(f"{RELEASE_WF}: 'notify-failure' runs only after the release job fails")

    for jname, job in jobs.items():
        if jname == "release":
            continue
        for scope, level in (job.get("permissions") or {}).items():
            if level == "write" and scope not in ("issues",):
                fail(f"{RELEASE_WF}: job '{jname}' holds unexpected {scope}: write")

    # The PR-triggered preflight must be read-only in every job.
    pre = load(PREFLIGHT_WF)
    if pre.get("permissions") != {"contents": "read"}:
        fail(f"{PREFLIGHT_WF}: top-level permissions must be exactly {{contents: read}}, "
             f"found {pre.get('permissions')!r}")
    else:
        ok(f"{PREFLIGHT_WF}: top-level permissions are read-only")

    pr_write = False
    for jname, job in (pre.get("jobs") or {}).items():
        bad = {k: v for k, v in (job.get("permissions") or {}).items() if v == "write"}
        if bad:
            fail(f"{PREFLIGHT_WF}: PR job '{jname}' must hold no write scope, found {bad!r}")
            pr_write = True
    if not pr_write:
        ok(f"{PREFLIGHT_WF}: no pull-request job holds a write scope")


def check_preflight_coverage():
    pre = load(PREFLIGHT_WF)
    text = "\n".join(body for _, body in executable_bodies(pre))
    missing = [(label, cmd) for label, cmd in REQUIRED_PREFLIGHT if cmd not in text]
    for label, cmd in missing:
        fail(f"{PREFLIGHT_WF}: required guard not invoked: {label} ({cmd}). "
             "Removing a guard makes the preflight pass while checking less.")
    if not missing:
        ok(f"{PREFLIGHT_WF}: all {len(REQUIRED_PREFLIGHT)} required guards are invoked")

    # A preflight that could publish is not a preflight.
    forbidden = ["softprops/action-gh-release", "peaceiris/actions-gh-pages",
                 "docker/build-push-action", "git push", "gh release create"]
    for f in forbidden:
        if f in text or any(f in (s.get("uses") or "")
                            for j in (pre.get("jobs") or {}).values()
                            for s in (j.get("steps") or [])):
            fail(f"{PREFLIGHT_WF}: must never publish; found {f!r}")
    ok(f"{PREFLIGHT_WF}: contains no publishing action")


def main():
    check_refs_are_data()
    check_permissions()
    check_preflight_coverage()
    if failures:
        print(f"\ncheck-workflow-security: FAILED ({len(failures)} problem(s))",
              file=sys.stderr)
        return 1
    print("\ncheck-workflow-security: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
