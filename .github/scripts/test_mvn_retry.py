#!/usr/bin/env python3
"""Unit tests for .github/scripts/mvn_retry.py."""

from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvn_retry  # noqa: E402


class MvnRetryClassificationTests(unittest.TestCase):
    def test_retries_central_plugin_resolution_404(self) -> None:
        log = (
            "[WARNING] The POM for org.flywaydb:flyway-maven-plugin:jar:11.13.1 is missing\n"
            "[ERROR] Plugin org.flywaydb:flyway-maven-plugin:11.13.1 or one of its dependencies "
            "could not be resolved:\n"
            "[ERROR] Could not find artifact org.flywaydb:flyway-maven-plugin:jar:11.13.1 "
            "in central (https://repo.maven.apache.org/maven2)\n"
        )
        self.assertTrue(mvn_retry.is_transient_maven_error(log))

    def test_retries_artifact_transfer_error(self) -> None:
        log = "[ERROR] Could not transfer artifact org.example:foo:pom:1.0 from/to central: Connection reset"
        self.assertTrue(mvn_retry.is_transient_maven_error(log))

    def test_does_not_retry_test_failure(self) -> None:
        log = (
            "[ERROR] Failures: 1\n"
            "[ERROR] There are test failures.\n"
            "Could not find artifact foo:bar:jar:1.0 in central\n"
        )
        self.assertFalse(mvn_retry.is_transient_maven_error(log))

    def test_does_not_retry_compilation_error(self) -> None:
        log = "[ERROR] COMPILATION ERROR :\n[ERROR] Foo.java:[10,5] cannot find symbol"
        self.assertFalse(mvn_retry.is_transient_maven_error(log))

    def test_does_not_retry_owasp_cvss_failure(self) -> None:
        log = "[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score >= 7.0"
        self.assertFalse(mvn_retry.is_transient_maven_error(log))


class MvnRetryExecutionTests(unittest.TestCase):
    def test_clears_last_updated_markers_and_succeeds_on_retry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            nested = repo / "org" / "flywaydb" / "flyway-maven-plugin" / "11.13.1"
            nested.mkdir(parents=True)
            marker = nested / "flyway-maven-plugin-11.13.1.pom.lastUpdated"
            marker.write_text("404", encoding="utf-8")
            jar = nested / "other.jar"
            jar.write_text("keep", encoding="utf-8")

            calls: list[list[str]] = []
            sleeps: list[float] = []

            def fake_runner(cmd):
                calls.append(list(cmd))
                if len(calls) == 1:
                    return (
                        1,
                        "[ERROR] Could not find artifact org.flywaydb:flyway-maven-plugin:jar:11.13.1 in central",
                    )
                return (0, "[INFO] BUILD SUCCESS")

            rc = mvn_retry.run_with_retries(
                ["mvn", "-B", "flyway:migrate"],
                max_attempts=3,
                backoff_seconds=(5.0, 15.0),
                repo_dir=repo,
                runner=fake_runner,
                sleeper=sleeps.append,
            )

            self.assertEqual(rc, 0)
            self.assertEqual(len(calls), 2)
            self.assertEqual(sleeps, [5.0])
            self.assertFalse(marker.exists())
            self.assertTrue(jar.exists())

    def test_does_not_retry_non_transient_failure(self) -> None:
        calls = 0
        sleeps: list[float] = []

        def fake_runner(cmd):
            nonlocal calls
            calls += 1
            return (1, "[ERROR] There are test failures.")

        with tempfile.TemporaryDirectory() as tmp:
            rc = mvn_retry.run_with_retries(
                ["mvn", "test"],
                max_attempts=3,
                backoff_seconds=(5.0, 15.0),
                repo_dir=Path(tmp),
                runner=fake_runner,
                sleeper=sleeps.append,
            )

        self.assertEqual(rc, 1)
        self.assertEqual(calls, 1)
        self.assertEqual(sleeps, [])

    def test_stops_after_max_attempts(self) -> None:
        calls = 0
        sleeps: list[float] = []

        def fake_runner(cmd):
            nonlocal calls
            calls += 1
            return (1, "[ERROR] Could not transfer metadata org.owasp/maven-metadata.xml")

        with tempfile.TemporaryDirectory() as tmp:
            rc = mvn_retry.run_with_retries(
                ["mvn", "dependency-check:update-only"],
                max_attempts=3,
                backoff_seconds=(2.0, 4.0),
                repo_dir=Path(tmp),
                runner=fake_runner,
                sleeper=sleeps.append,
            )

        self.assertEqual(rc, 1)
        self.assertEqual(calls, 3)
        self.assertEqual(sleeps, [2.0, 4.0])


if __name__ == "__main__":
    unittest.main()
