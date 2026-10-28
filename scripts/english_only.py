#!/usr/bin/env python3
"""English-only guard for text that lands in the repository's history.

The project language for commits, issues, pull requests and their comments is
English. This script is the single implementation behind three enforcement
points:

  commit-msg FILE   git commit-msg hook (see .githooks/commit-msg)
  claude-hook       Claude Code PreToolUse hook for Bash (reads JSON on stdin)
  check [FILE|-]    ad-hoc check of a file or stdin, usable from CI

Detection is a heuristic, not a language identifier. It flags:
  * letters outside ASCII (accented vowels, n-tilde, non-Latin scripts), and
  * text with enough unambiguous Spanish words to score >= SCORE_THRESHOLD.
Code blocks, inline code, URLs and git trailers are ignored. Legitimate
exceptions (proper names, quoted terms) go in .github/language-allowlist.txt.

Exit codes: 0 = ok, 1 = violation (commit-msg, check), 2 = violation that
blocks the tool call (claude-hook). Standard library only.
"""

from __future__ import annotations

import json
import re
import shlex
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ALLOWLIST_FILE = REPO_ROOT / ".github" / "language-allowlist.txt"
MAX_FILE_BYTES = 256 * 1024
SCORE_THRESHOLD = 2

# Spanish function words that are not ordinary English words. Weight 1 each.
COMMON_WORDS = frozenset(
    """
    para pero porque cuando donde desde entre sobre segun según tambien también
    aunque mientras ademas además hacia hasta cada todos todas ellos nosotros
    esto esta estos estas ese eso aqui aquí nuestro nuestra del los una unos
    unas con que por fue fueron sido estan están tiene tienen puede pueden
    debe deben muy como el ahora
    """.split()
)

# Spanish vocabulary that is common in commit messages and issues. Weight 2.
STRONG_WORDS = frozenset(
    """
    agrega agregar agregado añade añadir corrige corregir corregido arregla
    arreglar elimina eliminar actualiza actualizar cambia cambiar implementa
    implementar soluciona solucionar mejora mejorar prueba pruebas usuario
    usuarios archivo archivos contraseña configuración configuracion
    validación validacion función funcion funcionalidad problema resuelve
    corrección correccion nuevo nueva nuevos nuevas revisa revisar
    """.split()
)

TRAILER = re.compile(
    r"^(?:co-authored-by|signed-off-by|reviewed-by|acked-by|reported-by|"
    r"suggested-by|tested-by):",
    re.IGNORECASE,
)
SCISSORS = re.compile(r"^# -+ >8 -+\s*$")

# Commands whose text ends up in the repository or on GitHub.
TRIGGER = re.compile(
    r"\bgit\b[^|;&\n]*?\bcommit\b"
    r"|\bgh\s+(?:issue|pr)\s+(?:create|edit|comment|review|close|reopen|merge)\b"
    r"|\bgh\s+api\b[^\n]*(?:issues|pulls|comments)",
    re.DOTALL,
)
FILE_FLAGS = {"-F", "--file", "--body-file"}


def load_allowlist() -> set[str]:
    try:
        lines = ALLOWLIST_FILE.read_text(encoding="utf-8").splitlines()
    except OSError:
        return set()
    return {
        line.strip().lower()
        for line in lines
        if line.strip() and not line.lstrip().startswith("#")
    }


def strip_noise(text: str, commit_message: bool = False) -> str:
    """Drop the parts of a message that are not prose."""
    text = re.sub(r"```.*?```", " ", text, flags=re.DOTALL)
    text = re.sub(r"`[^`\n]*`", " ", text)
    text = re.sub(r"https?://\S+", " ", text)
    kept = []
    for line in text.splitlines():
        if commit_message:
            if SCISSORS.match(line):
                break
            if line.startswith("#"):
                continue
        if TRAILER.match(line):
            continue
        kept.append(line)
    return "\n".join(kept)


def _is_foreign_letter(char: str) -> bool:
    """Non-ASCII letter that is not Greek (Greek is technical notation: delta, mu, sigma)."""
    return ord(char) > 127 and not unicodedata.name(char, "").startswith("GREEK")


def analyze(text: str, allow: set[str]) -> list[str]:
    """Return human-readable findings; an empty list means the text passes."""
    tokens = re.findall(r"[^\W\d_]+", text)
    non_ascii = sorted(
        {
            t
            for t in tokens
            if any(_is_foreign_letter(c) for c in t) and t.lower() not in allow
        }
    )
    score = 0
    hits: set[str] = set()
    for token in tokens:
        word = token.lower()
        if word in allow:
            continue
        if word in STRONG_WORDS:
            score += 2
            hits.add(word)
        elif word in COMMON_WORDS:
            score += 1
            hits.add(word)

    findings = []
    if non_ascii:
        findings.append("non-ASCII letters in: " + ", ".join(non_ascii[:8]))
    if score >= SCORE_THRESHOLD:
        findings.append("Spanish words detected: " + ", ".join(sorted(hits)[:8]))
    return findings


def report(context: str, findings: list[str]) -> str:
    lines = [f"English-only check failed ({context}):"]
    lines += [f"  - {finding}" for finding in findings]
    lines += [
        "Commits, issues, pull requests and their comments must be written in",
        "English, even when the conversation is in another language. Rewrite the",
        "text in English and retry. If a flagged word is a proper name or a",
        "quoted term, add it to .github/language-allowlist.txt.",
    ]
    return "\n".join(lines)


def _read_text_file(path: Path) -> str:
    try:
        with path.open("rb") as handle:
            return handle.read(MAX_FILE_BYTES).decode("utf-8", errors="replace")
    except OSError:
        return ""


def command_text(command: str, cwd: str | None) -> str | None:
    """Text to check for a Bash command, or None if it is not a publishing command."""
    if not TRIGGER.search(command):
        return None
    parts = [command]
    try:
        tokens = shlex.split(command)
    except ValueError:
        tokens = []
    base = Path(cwd) if cwd else Path.cwd()
    for i, token in enumerate(tokens):
        target = None
        if token in FILE_FLAGS and i + 1 < len(tokens):
            target = tokens[i + 1]
        elif token.startswith(("--file=", "--body-file=")):
            target = token.split("=", 1)[1]
        if target and target != "-":
            path = Path(target)
            parts.append(_read_text_file(path if path.is_absolute() else base / path))
    return "\n".join(parts)


def run_commit_msg(path: str) -> int:
    text = strip_noise(_read_text_file(Path(path)), commit_message=True)
    findings = analyze(text, load_allowlist())
    if findings:
        print(report("commit message", findings), file=sys.stderr)
        return 1
    return 0


def run_claude_hook() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0  # fail open: never block on a malformed hook payload
    if payload.get("tool_name") != "Bash":
        return 0
    command = (payload.get("tool_input") or {}).get("command", "")
    text = command_text(command, payload.get("cwd"))
    if text is None:
        return 0
    findings = analyze(strip_noise(text), load_allowlist())
    if findings:
        print(report("blocked command", findings), file=sys.stderr)
        return 2
    return 0


def run_check(source: str) -> int:
    text = sys.stdin.read() if source == "-" else _read_text_file(Path(source))
    findings = analyze(strip_noise(text), load_allowlist())
    if findings:
        print(report(source, findings), file=sys.stderr)
        return 1
    return 0


def main(argv: list[str]) -> int:
    usage = "usage: english_only.py (commit-msg FILE | claude-hook | check [FILE|-])"
    if len(argv) < 2:
        print(usage, file=sys.stderr)
        return 64
    mode = argv[1]
    if mode == "commit-msg" and len(argv) == 3:
        return run_commit_msg(argv[2])
    if mode == "claude-hook" and len(argv) == 2:
        return run_claude_hook()
    if mode == "check" and len(argv) <= 3:
        return run_check(argv[2] if len(argv) == 3 else "-")
    print(usage, file=sys.stderr)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv))
