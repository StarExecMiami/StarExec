#!/usr/bin/env python3
"""Contract tests for the StarExec runtime Dockerfile."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = ROOT / "Dockerfile"
JOB_RUNNER_DOCKERFILE = ROOT / "docker" / "job-runner.Dockerfile"


class DockerfileRuntimeTests(unittest.TestCase):
    def test_runtime_stage_uses_glibc_jre_for_secure_directory_stream(self):
        content = DOCKERFILE.read_text(encoding="utf-8")
        from_lines = re.findall(r"^FROM\s+(\S+)(?:\s+AS\s+(\S+))?", content, re.MULTILINE)
        self.assertGreaterEqual(len(from_lines), 5)
        runtime_image, runtime_alias = from_lines[-1]
        self.assertEqual(runtime_alias, "")
        self.assertNotIn("alpine", runtime_image.lower())
        self.assertIn("eclipse-temurin:17-jre-noble", runtime_image)
        self.assertIn('ENTRYPOINT ["/usr/bin/tini", "--"]', content)
        self.assertIn("groupadd -g 1000 starexec", content)

    def test_runsolver_builder_matches_job_runner_alpine_base(self):
        app_df = DOCKERFILE.read_text(encoding="utf-8")
        runner_df = JOB_RUNNER_DOCKERFILE.read_text(encoding="utf-8")
        app_match = re.search(r"^FROM\s+(\S+)\s+AS\s+runsolver-builder", app_df, re.MULTILINE)
        runner_match = re.search(r"^FROM\s+(\S+)\s+AS\s+runsolver-builder", runner_df, re.MULTILINE)
        self.assertIsNotNone(app_match)
        self.assertIsNotNone(runner_match)
        self.assertEqual(app_match.group(1), runner_match.group(1))

    def test_tomcat_connector_hardens_large_upload_timeouts_and_swallow_size(self):
        content = DOCKERFILE.read_text(encoding="utf-8")
        self.assertIn('disableUploadTimeout="false"', content)
        self.assertIn('connectionUploadTimeout="600000"', content)
        self.assertIn('maxSwallowSize="52428800"', content)


if __name__ == "__main__":
    unittest.main()
