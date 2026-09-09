#!/usr/bin/env python3
"""Prove an application release cannot publish job-runner images.

job-runner-publish.yml triggers on `release: published` and gates its semver
tag rules on `github.event_name == 'release'`. Nothing in those rules inspects
WHICH release fired. Publishing application release v2.6.0 with a token that
retriggers workflows would therefore stamp starexec-job-runner:2.6.0 / :2.6 /
:2 from an application tag -- a job-runner image labelled with a version of a
different product.

The build job carries a guard:

    github.event_name != 'release' ||
    startsWith(github.event.release.tag_name, 'job-runner-v')

This test extracts that guard from the workflow as written and evaluates it
against four contexts. It reads the real expression rather than a restatement
of it, so deleting or weakening the guard fails here.
"""
import re
import sys

try:
    import yaml
except ImportError:
    sys.exit("check-job-runner-isolation: PyYAML is required")

WORKFLOW = ".github/workflows/job-runner-publish.yml"
JOB = "build"


class Expr:
    """A deliberately tiny evaluator for the GitHub expression subset used here.

    Supports: || && != == , startsWith(a, b), 'literals', and github.* lookups.
    Anything else raises, so a guard that grows past this subset fails loudly
    instead of being silently mis-evaluated.
    """

    def __init__(self, ctx):
        self.ctx = ctx

    def lookup(self, path):
        cur = self.ctx
        for part in path.split("."):
            if not isinstance(cur, dict) or part not in cur:
                return None
            cur = cur[part]
        return cur

    def atom(self, tok):
        tok = tok.strip()
        if tok.startswith("'") and tok.endswith("'"):
            return tok[1:-1]
        m = re.fullmatch(r"startsWith\(\s*(.+?)\s*,\s*(.+?)\s*\)", tok, re.S)
        if m:
            a, b = self.atom(m.group(1)), self.atom(m.group(2))
            return isinstance(a, str) and isinstance(b, str) and a.startswith(b)
        if tok in ("true", "false"):
            return tok == "true"
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", tok):
            return self.lookup(tok)
        raise ValueError(f"unsupported expression atom: {tok!r}")

    def eval(self, expr):
        expr = " ".join(expr.split())
        for op, fn in (("||", any), ("&&", all)):
            parts = self.split_top(expr, op)
            if len(parts) > 1:
                return fn(self.eval(p) for p in parts)
        for op in ("!=", "=="):
            parts = self.split_top(expr, op)
            if len(parts) == 2:
                a, b = self.atom(parts[0]), self.atom(parts[1])
                return a != b if op == "!=" else a == b
        v = self.atom(expr)
        return bool(v)

    @staticmethod
    def split_top(expr, op):
        """Split on `op` only at paren depth 0, so startsWith(...) stays intact."""
        out, depth, cur, i = [], 0, "", 0
        while i < len(expr):
            c = expr[i]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            if depth == 0 and expr.startswith(op, i):
                out.append(cur)
                cur = ""
                i += len(op)
                continue
            cur += c
            i += 1
        out.append(cur)
        return [p.strip() for p in out]


SCENARIOS = [
    # (label, context, must_run, why)
    ("application release v2.6.0", {
        "github": {"event_name": "release",
                   "event": {"release": {"tag_name": "v2.6.0"}}}},
     False, "an application tag must NOT publish a job-runner image"),
    ("job-runner release job-runner-v2.1.0", {
        "github": {"event_name": "release",
                   "event": {"release": {"tag_name": "job-runner-v2.1.0"}}}},
     True, "the job-runner's own release must still publish"),
    ("branch push to containerised", {
        "github": {"event_name": "push",
                   "event": {}}},
     True, "branch pushes are unaffected by the release guard"),
    ("pull request", {
        "github": {"event_name": "pull_request",
                   "event": {}}},
     True, "pull-request builds are unaffected by the release guard"),
]


def main():
    with open(WORKFLOW) as fh:
        wf = yaml.safe_load(fh)

    job = (wf.get("jobs") or {}).get(JOB)
    if job is None:
        sys.exit(f"check-job-runner-isolation: no '{JOB}' job in {WORKFLOW}")

    guard = job.get("if")
    if not guard:
        print(f"check-job-runner-isolation: ERROR: job '{JOB}' has NO `if:` guard.",
              file=sys.stderr)
        print("check-job-runner-isolation: an application release would publish "
              "job-runner images.", file=sys.stderr)
        return 1

    guard = str(guard).strip()
    if guard.startswith("${{") and guard.endswith("}}"):
        guard = guard[3:-2].strip()
    print(f"check-job-runner-isolation: guard on job '{JOB}':\n    {guard}\n")

    failures = 0
    for label, ctx, must_run, why in SCENARIOS:
        try:
            got = Expr(ctx).eval(guard)
        except ValueError as e:
            print(f"check-job-runner-isolation: ERROR evaluating guard: {e}",
                  file=sys.stderr)
            return 1
        status = "runs" if got else "skipped"
        if got == must_run:
            print(f"  OK   {label:<38} -> {status}")
        else:
            print(f"  FAIL {label:<38} -> {status}; expected "
                  f"{'runs' if must_run else 'skipped'}: {why}", file=sys.stderr)
            failures += 1

    if failures:
        print(f"check-job-runner-isolation: FAILED ({failures})", file=sys.stderr)
        return 1
    print("\ncheck-job-runner-isolation: PASSED -- "
          "application releases cannot publish job-runner images")
    return 0


if __name__ == "__main__":
    sys.exit(main())
