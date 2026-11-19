#!/usr/bin/env python3
"""Local git hook guard against staging internal/secret files or leaking local paths.

Invoked by ``.githooks/pre-commit`` and ``.githooks/commit-msg``:
  - ``pre-commit`` inspects staged file paths and rejects local agent/worktree
    directories, unencrypted private keys, and dotenv files (except templates).
  - ``commit-msg <path>`` inspects the commit message body (ignoring Git comment
    lines and scissor diffs) and rejects machine-local filesystem paths or
    internal handoff bookkeeping references.
"""

from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from typing import Iterable, Sequence

BLOCKED_PATH_SEGMENTS = frozenset(
    {
        ".agent",
        ".antigravity",
        ".claude",
        ".codex",
        ".gentle",
        ".opencode",
        ".pi",
        ".worktrees",
        "handoff",
    }
)

ALLOWED_DOTENV_NAMES = frozenset(
    {
        ".env.example",
        ".env.sample",
        ".env.template",
    }
)

BLOCKED_SECRET_SUFFIXES = (".pem", ".key", ".p12", ".pfx")

BLOCKED_EXACT_FILENAMES = frozenset(
    {
        "id_rsa",
        "id_ed25519",
        "id_ecdsa",
        "id_dsa",
    }
)

COMMIT_MSG_LEAK_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("machine-local home path", re.compile(r"(?:^|[\s`'\"(])(?:/home/[^/\s]+/|/Users/[^/\s]+/|/mnt/[^/\s]+/|[A-Za-z]:\\Users\\[^\\\s]+\\)")),
    ("local agent or worktree directory", re.compile(r"(?:^|[\s`'\"(/])(?:\.(?:agent|antigravity|claude|codex|gentle|opencode|pi|worktrees)/)")),
    ("internal handoff artifact", re.compile(r"\b(?:CURRENT\.json|EXECUTION_QUEUE\.json|MODEL_HANDOFF\.md|SHA256SUMS)\b")),
)

GIT_SCISSOR_LINE = "# ------------------------ >8 ------------------------"


def check_staged_path(rel_path: str) -> str | None:
    """Return a human-readable violation reason if ``rel_path`` must not be staged."""
    normalized = rel_path.replace("\\", "/").strip()
    if not normalized:
        return None
    posix = PurePosixPath(normalized)
    parts = [part for part in posix.parts if part not in ("", ".")]
    lower_parts = [part.lower() for part in parts]

    for part in lower_parts:
        if part in BLOCKED_PATH_SEGMENTS:
            return f"internal directory segment '{part}'"

    name = posix.name
    lower_name = name.lower()

    if lower_name == ".env" or (lower_name.startswith(".env.") and lower_name not in ALLOWED_DOTENV_NAMES):
        return f"environment secret file '{name}'"

    if lower_name in BLOCKED_EXACT_FILENAMES or any(
        lower_name.startswith(f"{prefix}.") and not lower_name.endswith(".pub")
        for prefix in BLOCKED_EXACT_FILENAMES
    ):
        return f"private SSH key file '{name}'"

    if any(lower_name.endswith(suffix) for suffix in BLOCKED_SECRET_SUFFIXES):
        return f"key or certificate material '{name}'"

    return None


def find_staged_violations(paths: Iterable[str]) -> list[tuple[str, str]]:
    """Return ``(path, reason)`` pairs for every disallowed staged path."""
    violations: list[tuple[str, str]] = []
    for path in paths:
        reason = check_staged_path(path)
        if reason is not None:
            violations.append((path, reason))
    return violations


def strip_git_comments(raw_message: str) -> str:
    """Strip Git scissor diff blocks and ``#`` comment lines from a commit message."""
    lines: list[str] = []
    for line in raw_message.splitlines():
        if line.strip() == GIT_SCISSOR_LINE:
            break
        if line.lstrip().startswith("#"):
            continue
        lines.append(line)
    return "\n".join(lines)


def find_commit_message_violations(raw_message: str) -> list[str]:
    """Return human-readable violation descriptions for leaked local/internal tokens."""
    body = strip_git_comments(raw_message)
    violations: list[str] = []
    for label, pattern in COMMIT_MSG_LEAK_PATTERNS:
        match = pattern.search(body)
        if match:
            token = match.group(0).strip()
            violations.append(f"{label}: {token}")
    return violations


def get_staged_paths() -> list[str]:
    """Return paths currently staged for addition, copy, modification, or rename."""
    proc = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
        check=True,
        capture_output=True,
    )
    raw = proc.stdout.decode("utf-8", errors="surrogateescape")
    return [entry for entry in raw.split("\0") if entry]


def run_pre_commit() -> int:
    violations = find_staged_violations(get_staged_paths())
    if not violations:
        return 0
    print("error: pre-commit guard blocked disallowed staged files:", file=sys.stderr)
    for path, reason in violations:
        print(f"  - {path} ({reason})", file=sys.stderr)
    print(
        "Unstage these files before committing (keep local agent state and secrets outside Git).",
        file=sys.stderr,
    )
    return 1


def run_commit_msg(message_file: Path) -> int:
    text = message_file.read_text(encoding="utf-8", errors="replace")
    violations = find_commit_message_violations(text)
    if not violations:
        return 0
    print("error: commit-msg guard detected local or internal state references:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    print(
        "Rewrite the commit message using only repository-relative paths and domain terms.",
        file=sys.stderr,
    )
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="StarExec local git hygiene guard.")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("pre-commit", help="Validate staged file paths.")
    cm = sub.add_parser("commit-msg", help="Validate a commit message file.")
    cm.add_argument("message_file", type=Path, help="Path to the commit message file.")

    args = parser.parse_args(list(argv) if argv is not None else None)
    if args.command == "pre-commit":
        return run_pre_commit()
    return run_commit_msg(args.message_file)


if __name__ == "__main__":
    raise SystemExit(main())
