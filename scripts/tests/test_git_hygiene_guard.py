#!/usr/bin/env python3
"""Unit tests for scripts/git_hygiene_guard.py."""

from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import git_hygiene_guard  # noqa: E402


class StagedPathGuardTests(unittest.TestCase):
    def test_allows_normal_repository_paths(self) -> None:
        allowed = [
            "starexec-app/src/main/java/org/starexec/util/Util.java",
            "charts/starexec/templates/secret.yaml",
            ".github/workflows/maven-test.yml",
            ".githooks/pre-commit",
            ".env.example",
            ".env.template",
            "docs/SECURITY.md",
            "id_rsa.pub",
        ]
        self.assertEqual(git_hygiene_guard.find_staged_violations(allowed), [])

    def test_blocks_internal_agent_and_worktree_directories(self) -> None:
        blocked = [
            ".claude/RESUME.md",
            ".codex/session.json",
            ".worktrees/demo/file.txt",
            "nested/handoff/report.md",
        ]
        violations = git_hygiene_guard.find_staged_violations(blocked)
        self.assertEqual([path for path, _ in violations], blocked)

    def test_blocks_dotenv_and_private_keys(self) -> None:
        blocked = [
            ".env",
            ".env.local",
            "config/.env.production",
            "certs/server.pem",
            "certs/private.key",
            "keys/id_ed25519",
            "keys/id_rsa.bak",
        ]
        violations = git_hygiene_guard.find_staged_violations(blocked)
        self.assertEqual([path for path, _ in violations], blocked)


class CommitMessageGuardTests(unittest.TestCase):
    def test_allows_clean_conventional_commit_message(self) -> None:
        msg = (
            "ci(hooks): add pre-commit and commit-msg hygiene guards\n\n"
            "Reject staged credential files, local worktree paths, and internal "
            "bookkeeping filenames before commits are created.\n"
            "# On branch chore/git-hook-leak-guards\n"
            "# Changes to be committed:\n"
            "#\tnew file:   .githooks/pre-commit\n"
        )
        self.assertEqual(git_hygiene_guard.find_commit_message_violations(msg), [])

    def test_ignores_content_after_git_scissor_line(self) -> None:
        msg = (
            "fix(util): validate path inputs\n"
            "# ------------------------ >8 ------------------------\n"
            "+ /home/alice/dev/repo/.claude/CURRENT.json\n"
        )
        self.assertEqual(git_hygiene_guard.find_commit_message_violations(msg), [])

    def test_blocks_machine_local_paths_and_internal_artifacts(self) -> None:
        cases = [
            "fix(ci): verified against /home/alice/StarExec/logs",
            "chore(test): see /mnt/data/run.log for details",
            "chore(docs): copied from .claude/handoff/notes.md",
            "fix(build): matches SHA256SUMS and CURRENT.json",
        ]
        for msg in cases:
            with self.subTest(msg=msg):
                self.assertTrue(git_hygiene_guard.find_commit_message_violations(msg))


if __name__ == "__main__":
    unittest.main()
