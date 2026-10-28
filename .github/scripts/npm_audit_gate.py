#!/usr/bin/env python3
"""Fail CI on high or critical npm advisories, apart from documented exceptions.

`npm audit` cannot set a single advisory aside. GHSA-vfj7-8cjw-p6xm (braces) has no patched
release, so failing on it blocked every pull request; and because the audit ran first in the
dependency-scan job, it also kept the OWASP scan of the Java dependencies from running at all.

An exception holds only while it still describes reality:
  * every copy of the package is reached through development dependencies alone, so it is
    build tooling and never shipped;
  * npm reports no fix for it, so an upgrade cannot replace the exception;
  * its review date has not passed.
Any other advisory at high or critical severity fails, as `npm audit --audit-level=high` did.

Usage: npm_audit_gate.py AUDIT_JSON PACKAGE_LOCK EXCEPTIONS_JSON [--today YYYY-MM-DD]
"""

import argparse
import datetime
import json
import re
import sys

FAILING_SEVERITIES = {"high", "critical"}
GHSA = re.compile(r"GHSA-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}")


def advisories(audit):
    """Each advisory npm audit attributes to a package: (package, id, severity, title, url).

    A package's `via` holds advisory objects for its own vulnerabilities and plain names for
    those it only inherits; the inherited ones are reported on the package they belong to.
    """
    found = []
    for name, vuln in sorted((audit.get("vulnerabilities") or {}).items()):
        for via in vuln.get("via") or []:
            if isinstance(via, dict):
                url = via.get("url") or ""
                match = GHSA.search(url)
                found.append(
                    (
                        via.get("name") or name,
                        match.group(0) if match else url,
                        via.get("severity"),
                        via.get("title") or "",
                        url,
                    )
                )
    return found


def shipped_nodes(audit, lock, package):
    """The installed copies of `package` that are not development-only, per package-lock."""
    packages = lock.get("packages") or {}
    nodes = (audit.get("vulnerabilities") or {}).get(package, {}).get("nodes") or []
    if not nodes:
        nodes = [
            path for path in packages if path.split("node_modules/")[-1] == package
        ]
    return [node for node in nodes if not (packages.get(node) or {}).get("dev")]


def evaluate(audit, lock, exceptions, today):
    """Returns (failures, excepted, notes): lines to print, and whether the gate passes."""
    if "error" in audit:
        return (
            ["npm audit did not complete: %s" % json.dumps(audit["error"])[:500]],
            [],
            [],
        )
    by_key = {(e["id"], e["package"]): e for e in exceptions}
    used = set()
    failures, excepted = [], []
    for package, advisory, severity, title, url in advisories(audit):
        if severity not in FAILING_SEVERITIES:
            continue
        label = "%s %s (%s): %s %s" % (package, advisory, severity, title, url)
        exception = by_key.get((advisory, package))
        if exception is None:
            failures.append(label)
            continue
        used.add((advisory, package))
        review_by = datetime.date.fromisoformat(exception["review_by"])
        shipped = shipped_nodes(audit, lock, package)
        fix = (audit.get("vulnerabilities") or {}).get(package, {}).get("fixAvailable")
        if today > review_by:
            failures.append(
                "%s -- its exception expired on %s; review it" % (label, review_by)
            )
        elif shipped:
            failures.append(
                "%s -- excepted as build tooling, but reached by shipped (non-dev) "
                "copies: %s" % (label, ", ".join(shipped))
            )
        elif fix:
            failures.append(
                "%s -- excepted for lack of a fix, but npm now reports one: upgrade "
                "instead" % label
            )
        else:
            excepted.append(
                "%s -- excepted until %s: %s" % (label, review_by, exception["reason"])
            )
    notes = [
        "exception %s for %s matches nothing in this audit; remove it" % key
        for key in sorted(set(by_key) - used)
    ]
    return failures, excepted, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("audit")
    parser.add_argument("lock")
    parser.add_argument("exceptions")
    parser.add_argument(
        "--today", type=datetime.date.fromisoformat, default=datetime.date.today()
    )
    args = parser.parse_args(argv)
    with open(args.audit) as f:
        audit = json.load(f)
    with open(args.lock) as f:
        lock = json.load(f)
    with open(args.exceptions) as f:
        exceptions = json.load(f)["exceptions"]

    failures, excepted, notes = evaluate(audit, lock, exceptions, args.today)
    for line in excepted:
        print("::notice::" + line)
    for line in notes:
        print("::warning::" + line)
    for line in failures:
        print("::error::" + line)
    if failures:
        print(
            "npm audit gate: %d failing advisor%s"
            % (len(failures), "y" if len(failures) == 1 else "ies")
        )
        return 1
    print("npm audit gate: passed (%d excepted)" % len(excepted))
    return 0


if __name__ == "__main__":
    sys.exit(main())
