"""The endpoint registry: every REST/form endpoint and source fact a check depends on, cited file:line.

Lines are resolved at the commit named in endpoints.json. `verify(commit)` re-reads each cited file at a
commit and confirms the needle is still on the cited line, so a citation cannot drift silently. When
the needle has only moved, the new line is reported; the registry is never rewritten automatically.
"""

import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
REGISTRY_FILE = HERE / "endpoints.json"
_registry = json.loads(REGISTRY_FILE.read_text())


def registry_meta():
    return {
        "file": str(REGISTRY_FILE),
        "resolved_at_commit": _registry["resolved_at_commit"],
        "entries": len(_registry["endpoints"]),
    }


def require(key):
    if key not in _registry["endpoints"]:
        raise KeyError(f"endpoint {key!r} is not in the registry")


def source_commit(entry):
    return entry.get("source_commit", _registry["resolved_at_commit"])


def cite(key):
    e = _registry["endpoints"][key]
    return {
        "key": key,
        "method": e["method"],
        "path": e["path"],
        "source": f"{e['file']}:{e['line']}",
        "commit": source_commit(e)[:9],
    }


def path(key, **params):
    p = _registry["endpoints"][key]["path"]
    for k, v in params.items():
        p = p.replace("{" + k + "}", str(v))
    if "{" in p:
        raise ValueError(f"unfilled parameter in {p}")
    return p


def items():
    return _registry["endpoints"].items()


def verify(commit, repo):
    """Verify citations at an explicit target commit, or their registry source commits by default."""
    results = []
    cache = {}
    for key, e in items():
        target_commit = commit if commit is not None else source_commit(e)
        f = e["file"]
        cache_key = (target_commit, f)
        if cache_key not in cache:
            p = subprocess.run(
                ["git", "-C", str(repo), "show", f"{target_commit}:{f}"],
                capture_output=True,
                text=True,
            )
            cache[cache_key] = p.stdout.splitlines() if p.returncode == 0 else None
        lines = cache[cache_key]
        if lines is None:
            results.append(
                {
                    "key": key,
                    "ok": False,
                    "commit": target_commit[:9],
                    "reason": f"{f} not present at {target_commit}",
                }
            )
            continue
        at_line = 0 < e["line"] <= len(lines) and e["needle"] in lines[e["line"] - 1]
        found = [i + 1 for i, l in enumerate(lines) if e["needle"] in l]
        results.append(
            {
                "key": key,
                "ok": at_line,
                "commit": target_commit[:9],
                "cited": f"{f}:{e['line']}",
                "found_at": found if not at_line else None,
            }
        )
    return results
