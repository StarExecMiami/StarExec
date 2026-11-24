"""Checks, evidence and the run report.

Every check records exactly what it asserted (a sentence stating the predicate), the expected and
observed values, and the HTTP/SQL evidence gathered since the previous check. A capability's status is
its worst check: FAIL > BLOCKED > EXPECTED-FAIL > SKIPPED-GATED > PASS. A capability that records no
check is BLOCKED, never PASS.

EXPECTED-FAIL is a check whose predicate failed on a defect already in the findings ledger; it names the
ledger id and never hides a new failure. A known-defect check that passes is recorded PASS with
"reproduced": false, so the ledger entry can be re-examined. SKIPPED-GATED is a check deliberately not
run until a named gate (an unmerged PR, a missing endpoint) opens.
"""

import json
import os
import re
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path

from . import endpoints as ep


#: A tag naming the issue tracker rather than the ledger. The hash is kept because it is the
#: convention every reader and GitHub itself already uses; what was missing was not a clearer
#: prefix but any check at all, so a mistyped ledger id was indistinguishable from a tracker
#: reference. With validation below, an id written without the hash must exist in the ledger,
#: and one written with it is a tracker reference by construction.
TRACKER_TAG = re.compile(r"^#\d+$")

#: Optional findings ledger path under docs/.
LEDGER = Path(__file__).resolve().parents[3] / "docs" / "findings-ledger.md"

_ledger_ids = None


def ledger_ids():
    """The ids the findings ledger defines, read once.

    Returns an empty set when the ledger cannot be read, which makes the check below permissive.

    That branch is DELIBERATE, not an oversight, and it states a principle worth keeping: an
    instrument must not refuse to measure because a document moved. The validation exists to
    catch a mistyped tag, which is a cheap error to make and an expensive one to carry; it does
    not exist to couple a run's success to the presence of a markdown file. A harness that
    aborts a two-hour qualification run because `reports/findings-ledger.md` was renamed would
    be a worse instrument than one that occasionally lets a bad tag through.
    """
    global _ledger_ids
    if _ledger_ids is None:
        try:
            text = LEDGER.read_text()
        except OSError:
            _ledger_ids = set()
        else:
            ids = set(re.findall(r"^\|\s*\*{0,2}([A-Za-z][\w#-]*)\*{0,2}\s*\|", text, re.M))
            ids |= set(re.findall(r"^##\s+([A-Za-z#][\w#-]*)\s+\u2014", text, re.M))
            ids.discard("ID")
            _ledger_ids = ids
    return _ledger_ids


def check_tag(known):
    """Refuses a tag that names neither namespace, loudly and at the moment it is written.

    A tag that names nothing can never be closed, audited or found: it reads as "known" forever.
    One such tag (R5) sat in the harness naming a ledger entry that does not exist, while four
    OTHER entries were being carried as open defects that had in fact been fixed.
    """
    if TRACKER_TAG.match(known):
        return
    ids = ledger_ids()
    if not ids or known in ids:
        return
    raise ValueError(
        f"known={known!r} names neither namespace: it is not a tracker reference (e.g. '#123')"
        f" and not an id defined in {LEDGER.name}. Either write the tracker form with a hash,"
        f" or add the entry to the ledger. Ids the ledger defines: {', '.join(sorted(ids))}"
    )

ORDER = {"PASS": 0, "SKIPPED-GATED": 1, "EXPECTED-FAIL": 2, "BLOCKED": 3, "FAIL": 4}


def utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


class Blocked(Exception):
    """Raised inside a capability when a prerequisite is missing; recorded as a BLOCKED check."""


class Recorder:
    def __init__(self):
        self.buffer = {"http": [], "sql": [], "notes": []}

    def http(self, label, item):
        self.buffer["http"].append({"as": label, "at": utc(), **item})

    def sql(self, statement, output, error=None):
        rows = None if output is None else output.splitlines()
        entry = {"at": utc(), "query": " ".join(statement.split())[:2000]}
        if error:
            entry["error"] = error
        else:
            entry["rows"] = rows[:200]
            if rows and len(rows) > 200:
                entry["rows_truncated"] = len(rows)
        self.buffer["sql"].append(entry)

    def note(self, text):
        self.buffer["notes"].append({"at": utc(), "text": text})

    def take(self):
        out, self.buffer = self.buffer, {"http": [], "sql": [], "notes": []}
        return {k: v for k, v in out.items() if v}


class Run:
    def __init__(self, cfg, runid, state_path, argv):
        self.cfg = cfg
        self.runid = runid
        self.path = cfg.report_dir / f"e2e-{cfg.backend}-{runid}.json"
        if self.path.exists():
            raise SystemExit(f"refusing to overwrite {self.path}")
        cfg.report_dir.mkdir(parents=True, exist_ok=True)
        self.data = {
            "report": "starexec-e2e-acceptance",
            "runid": runid,
            "commitSha": cfg.commit,
            "mode": cfg.mode,
            "backend": cfg.backend,
            "imageDigest": cfg.image_digest,
            "started_at": utc(),
            "argv": argv,
            "deployment": cfg.summary(),
            "state_file": str(state_path),
            "endpoint_registry": ep.registry_meta(),
            "capabilities": [],
            "findings": [],
        }
        self.recorder = Recorder()
        self.save()

    def save(self):
        tmp = self.path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(self.data, indent=2, default=str) + "\n")
        os.replace(tmp, self.path)

    def finish(self):
        caps = self.data["capabilities"]
        self.data["finished_at"] = utc()
        self.data["summary"] = {
            s: sum(1 for c in caps if c["status"] == s) for s in ORDER
        }
        self.data["verdict"] = max(
            (c["status"] for c in caps), key=ORDER.get, default="BLOCKED"
        )
        self.save()


class Capability:
    """The per-capability context handed to capability modules."""

    def __init__(self, run, number, name, ctx):
        self.run = run
        self.number = number
        self.name = name
        self.ctx = ctx
        self.checks = []
        self.entry = {
            "number": number,
            "name": name,
            "started_at": utc(),
            "checks": self.checks,
        }
        run.data["capabilities"].append(self.entry)
        run.recorder.take()

    def check(
        self,
        cid,
        name,
        asserted,
        expected,
        observed,
        passed,
        endpoints=(),
        status=None,
        extra=None,
        known=None,
    ):
        for key in endpoints:
            ep.require(key)
        if status is None:
            status = "PASS" if passed else "FAIL"
            if known:
                # known: the findings-ledger id this predicate is expected to fail on (e.g. "L2"),
                # or a tracker reference such as "#139". Validated here so a tag naming neither
                # fails the run rather than being carried as knowledge nobody can act on.
                check_tag(known)
                extra = {**(extra or {}), "ledger": known, "reproduced": not passed}
                if not passed:
                    status = "EXPECTED-FAIL"
        item = {
            "id": f"{self.number}.{cid}",
            "name": name,
            "status": status,
            "asserted": asserted,
            "expected": expected,
            "observed": observed,
            "endpoints": [ep.cite(k) for k in endpoints],
            "evidence": self.run.recorder.take(),
            "at": utc(),
        }
        if extra:
            item["detail"] = extra
        self.checks.append(item)
        self.run.save()
        mark = {"PASS": "PASS", "FAIL": "FAIL", "BLOCKED": "BLKD", "EXPECTED-FAIL": "XFAIL",
                "SKIPPED-GATED": "GATE"}[status]
        print(f"  {mark} {item['id']} {name}", flush=True)
        return passed

    def blocked(self, cid, name, reason, endpoints=()):
        return self.check(
            cid,
            name,
            "prerequisite: " + reason,
            None,
            None,
            False,
            endpoints,
            status="BLOCKED",
        )

    def gated(self, cid, name, gate, endpoints=()):
        """A check deliberately not run until `gate` opens (e.g. "PR #221 merged")."""
        return self.check(cid, name, "gated on: " + gate, None, None, False, endpoints, status="SKIPPED-GATED")

    def finding(self, fid, title, text, evidence=None):
        self.run.data["findings"].append(
            {
                "capability": self.number,
                "id": fid,
                "title": title,
                "text": text,
                "evidence": evidence,
            }
        )
        self.run.save()

    def close(self, error=None):
        if error is not None:
            if isinstance(error, Blocked):
                self.blocked("prereq", "capability prerequisites", str(error))
            else:
                self.check(
                    "error",
                    "capability raised",
                    "the capability runs to completion without an exception",
                    None,
                    f"{type(error).__name__}: {error}",
                    False,
                    extra={"traceback": traceback.format_exception(error)[-6:]},
                )
        statuses = [c["status"] for c in self.checks] or ["BLOCKED"]
        self.entry["status"] = max(statuses, key=ORDER.get)
        self.entry["finished_at"] = utc()
        self.run.save()
        return self.entry["status"]


def new_runid():
    return time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + f"-{os.getpid()}"
