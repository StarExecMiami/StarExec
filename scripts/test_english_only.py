#!/usr/bin/env python3
"""Tests for scripts/english_only.py. Run: python3 scripts/test_english_only.py"""

import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parent / "english_only.py"
spec = importlib.util.spec_from_file_location("english_only", SCRIPT)
eo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eo)


def findings(text, allow=frozenset(), commit_message=False):
    return eo.analyze(eo.strip_noise(text, commit_message=commit_message), set(allow))


def hook(command, cwd=None):
    payload = {"tool_name": "Bash", "tool_input": {"command": command}}
    if cwd:
        payload["cwd"] = cwd
    with (
        mock.patch("sys.stdin", io.StringIO(json.dumps(payload))),
        mock.patch("sys.stderr", io.StringIO()),
    ):
        return eo.run_claude_hook()


class AnalyzeTests(unittest.TestCase):
    def test_english_passes(self):
        self.assertEqual(
            findings("fix(jobs): fence result writes on the pair's attempt"), []
        )

    def test_spanish_with_accents_flagged(self):
        self.assertTrue(findings("fix: corrección del cálculo de cuota"))

    def test_spanish_without_accents_flagged(self):
        self.assertTrue(findings("fix: corrige el bug del login para los usuarios"))

    def test_single_ambiguous_word_passes(self):
        self.assertEqual(findings("feat: add support para"), [])

    def test_code_url_and_trailers_ignored(self):
        text = (
            "fix: keep the label\n\n"
            "```\nSELECT 'nombre de usuario' AS etiqueta\n```\n"
            "See `configuración` and https://example.com/está/aquí\n\n"
            "Co-Authored-By: José Núñez <jose@example.com>\n"
        )
        self.assertEqual(findings(text), [])

    def test_git_comment_lines_and_scissors_ignored(self):
        text = (
            "fix: keep the label\n"
            "# Please enter the commit message para los cambios\n"
            "# ------------------------ >8 ------------------------\n"
            "diff con los cambios de la función\n"
        )
        self.assertEqual(findings(text, commit_message=True), [])

    def test_allowlist_permits_proper_name(self):
        self.assertTrue(findings("thanks to José for the report"))
        self.assertEqual(findings("thanks to José for the report", {"josé"}), [])

    def test_non_latin_script_flagged(self):
        self.assertTrue(findings("fix: 修复 login"))

    def test_greek_notation_allowed(self):
        self.assertEqual(
            findings("Report the Δ between runs; sigma is σ, mean is μ"), []
        )

    def test_english_word_resolver_allowed(self):
        self.assertEqual(
            findings("fix(xml): give the schema resolver a portable namespace"), []
        )


class HookTests(unittest.TestCase):
    def test_spanish_commit_blocked(self):
        self.assertEqual(hook('git commit -m "fix: corrige el error del usuario"'), 2)

    def test_english_commit_allowed(self):
        self.assertEqual(hook('git commit -m "fix: correct the quota calculation"'), 0)

    def test_spanish_heredoc_commit_blocked(self):
        cmd = (
            "git commit -m \"$(cat <<'EOF'\n"
            'fix: corrige el cálculo\n\nAhora los usuarios ven el total.\nEOF\n)"'
        )
        self.assertEqual(hook(cmd), 2)

    def test_gh_issue_and_pr_blocked(self):
        for cmd in (
            'gh issue create --title "Fallo en el login" --body "No funciona para los usuarios"',
            'gh pr comment 12 --body "Esto también corrige el problema"',
            'gh issue edit 5 --body "El archivo no se actualiza"',
        ):
            self.assertEqual(hook(cmd), 2, cmd)

    def test_gh_english_allowed(self):
        self.assertEqual(
            hook('gh issue create --title "Login fails" --body "Users cannot sign in"'),
            0,
        )

    def test_non_publishing_commands_ignored(self):
        self.assertEqual(hook("mvn test -Dtest=PruebasDeUsuario"), 0)
        self.assertEqual(hook("gh issue list --limit 30"), 0)
        self.assertEqual(hook("git status"), 0)

    def test_body_file_content_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "body.md").write_text(
                "El archivo no se actualiza para los usuarios\n", encoding="utf-8"
            )
            self.assertEqual(
                hook("gh pr create --title Fix --body-file body.md", tmp), 2
            )
            Path(tmp, "body.md").write_text(
                "The file is not updated\n", encoding="utf-8"
            )
            self.assertEqual(
                hook("gh pr create --title Fix --body-file body.md", tmp), 0
            )

    def test_non_bash_and_bad_payload_fail_open(self):
        with mock.patch(
            "sys.stdin",
            io.StringIO(json.dumps({"tool_name": "Read", "tool_input": {}})),
        ):
            self.assertEqual(eo.run_claude_hook(), 0)
        with mock.patch("sys.stdin", io.StringIO("not json")):
            self.assertEqual(eo.run_claude_hook(), 0)


class CommitMsgHookTests(unittest.TestCase):
    """Runs the real .githooks/commit-msg script end to end."""

    HOOK = SCRIPT.parent.parent / ".githooks" / "commit-msg"

    def run_hook(self, message):
        with tempfile.NamedTemporaryFile(
            "w", suffix=".txt", delete=False, encoding="utf-8"
        ) as f:
            f.write(message)
            path = f.name
        try:
            return subprocess.run(
                ["sh", str(self.HOOK), path],
                capture_output=True,
                text=True,
                cwd=SCRIPT.parent,
            )
        finally:
            os.unlink(path)

    def test_spanish_message_rejected(self):
        result = self.run_hook("fix: corrige el error del usuario\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("English-only check failed", result.stderr)

    def test_english_message_accepted(self):
        result = self.run_hook("fix: correct the quota calculation\n")
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
