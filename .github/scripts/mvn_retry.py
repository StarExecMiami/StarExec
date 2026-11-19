#!/usr/bin/env python3
"""Run a Maven command and retry only on transient repository resolution errors.

GitHub Actions runners occasionally receive transient 404/5xx or transport errors
from Maven Central CDN edges when resolving plugins or artifacts. Maven records
negative lookups in ``*.lastUpdated`` marker files under ``~/.m2/repository``, so
a naive rerun without clearing those markers can fail again immediately.

This wrapper streams Maven output live, detects narrow repository-resolution
failures, removes ``*.lastUpdated`` files, and retries with backoff. Compilation
errors, test failures, and policy/security gate failures are never retried.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Callable, Iterable, Sequence

DEFAULT_MAX_ATTEMPTS = 3
DEFAULT_BACKOFF_SECONDS = (5.0, 15.0)

NON_RETRYABLE_PATTERNS = (
    re.compile(r"COMPILATION ERROR", re.IGNORECASE),
    re.compile(r"There are test failures", re.IGNORECASE),
    re.compile(r"Tests in error:", re.IGNORECASE),
    re.compile(r"One or more dependencies were identified with vulnerabilities", re.IGNORECASE),
)

TRANSIENT_RESOLUTION_PATTERNS = (
    re.compile(r"Plugin\s+\S+\s+or one of its dependencies could not be resolved"),
    re.compile(r"Could not find artifact\s+\S+\s+in\s+central"),
    re.compile(r"Could not transfer (?:artifact|metadata)\s+\S+"),
    re.compile(r"Non-resolvable parent POM"),
    re.compile(r"Could not resolve dependencies for project.*(?:transfer|timed out|Connection reset|status code:\s*(?:429|50\d))", re.DOTALL),
)


def is_transient_maven_error(output: str) -> bool:
    """Return True only when Maven output indicates a transient repository error."""
    if any(pattern.search(output) for pattern in NON_RETRYABLE_PATTERNS):
        return False
    return any(pattern.search(output) for pattern in TRANSIENT_RESOLUTION_PATTERNS)


def clear_last_updated_markers(repo_dir: Path) -> int:
    """Delete Maven ``*.lastUpdated`` negative-cache files under ``repo_dir``."""
    if not repo_dir.is_dir():
        return 0
    removed = 0
    for marker in repo_dir.rglob("*.lastUpdated"):
        if marker.is_file():
            try:
                marker.unlink()
                removed += 1
            except OSError:
                pass
    return removed


def _default_runner(cmd: Sequence[str]) -> tuple[int, str]:
    proc = subprocess.Popen(
        list(cmd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert proc.stdout is not None
    chunks: list[str] = []
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        chunks.append(line)
    rc = proc.wait()
    return rc, "".join(chunks)


def run_with_retries(
    cmd: Sequence[str],
    *,
    max_attempts: int = DEFAULT_MAX_ATTEMPTS,
    backoff_seconds: Sequence[float] = DEFAULT_BACKOFF_SECONDS,
    repo_dir: Path | None = None,
    runner: Callable[[Sequence[str]], tuple[int, str]] = _default_runner,
    sleeper: Callable[[float], None] = time.sleep,
) -> int:
    """Execute ``cmd`` and retry up to ``max_attempts`` on transient Maven errors."""
    if not cmd:
        raise ValueError("Command must not be empty")
    if max_attempts < 1:
        raise ValueError("max_attempts must be >= 1")

    m2_repo = repo_dir if repo_dir is not None else Path.home() / ".m2" / "repository"

    for attempt in range(1, max_attempts + 1):
        rc, output = runner(cmd)
        if rc == 0:
            return 0
        if attempt >= max_attempts or not is_transient_maven_error(output):
            return rc

        removed = clear_last_updated_markers(m2_repo)
        delay = backoff_seconds[min(attempt - 1, len(backoff_seconds) - 1)] if backoff_seconds else 0.0
        print(
            f"::warning::Transient Maven repository resolution failure on attempt "
            f"{attempt}/{max_attempts} (removed {removed} *.lastUpdated markers); "
            f"retrying in {delay:g}s...",
            flush=True,
        )
        if delay > 0:
            sleeper(delay)

    return 1


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Retry a Maven command on transient repository resolution errors."
    )
    parser.add_argument(
        "--max-attempts",
        type=int,
        default=int(os.environ.get("MVN_RETRY_MAX_ATTEMPTS", DEFAULT_MAX_ATTEMPTS)),
        help=f"Maximum number of attempts (default: {DEFAULT_MAX_ATTEMPTS})",
    )
    parser.add_argument(
        "command",
        nargs=argparse.REMAINDER,
        help="Maven command to execute (prefix with --)",
    )
    args = parser.parse_args(list(argv))
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    if not args.command:
        parser.error("Expected a Maven command after --")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    return run_with_retries(args.command, max_attempts=args.max_attempts)


if __name__ == "__main__":
    raise SystemExit(main())
