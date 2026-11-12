"""Check Trivy scan coverage, runner identity and publication boundaries.

Run with the existing PyYAML dependency:
python3 -m unittest discover -s scripts -p 'test_runner_sarif_workflows.py'
"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/job-runner-publish.yml"
UPLOAD_ACTION = "github/codeql-action/upload-sarif@7188fc363630916deb702c7fdcf4e481b751f97a"
SCAN_ACTION = "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25"
TOKEN = re.compile(r"\s*(\|\||&&|==|!=|[()!]|'(?:[^']|'')*'|[A-Za-z_][A-Za-z_0-9.]*)")


def truth(value):
    return value not in (False, None, "", 0)


def expression(source, context):
    """Evaluate the boolean expression subset used by the runner's gates."""
    if isinstance(source, bool):
        return source
    source = str(source).strip()
    if source.startswith("${{") and source.endswith("}}"):
        source = source[3:-2].strip()
    tokens = []
    while source:
        match = TOKEN.match(source)
        if not match:
            raise AssertionError("Unsupported workflow expression: " + source)
        tokens.append(match.group(1))
        source = source[match.end():].lstrip()
    index = 0

    def atom():
        nonlocal index
        token = tokens[index]
        index += 1
        if token == "!":
            return not truth(atom())
        if token == "(":
            value = disjunction()
            if tokens[index] != ")":
                raise AssertionError("Unbalanced expression")
            index += 1
            return value
        if token.startswith("'"):
            return token[1:-1].replace("''", "'")
        if token in ("true", "false"):
            return token == "true"
        value = context
        for part in token.split("."):
            value = value.get(part, "") if isinstance(value, dict) else ""
        return value

    def comparison():
        nonlocal index
        value = atom()
        if index < len(tokens) and tokens[index] in ("==", "!="):
            operator = tokens[index]
            index += 1
            right = atom()
            if isinstance(value, str) and isinstance(right, str):
                same = value.casefold() == right.casefold()
            else:
                same = value == right
            value = same if operator == "==" else not same
        return value

    def conjunction():
        nonlocal index
        value = comparison()
        while index < len(tokens) and tokens[index] == "&&":
            index += 1
            right = comparison()
            value = right if truth(value) else value
        return value

    def disjunction():
        nonlocal index
        value = conjunction()
        while index < len(tokens) and tokens[index] == "||":
            index += 1
            right = conjunction()
            value = value if truth(value) else right
        return value

    result = disjunction()
    if index != len(tokens):
        raise AssertionError("Unconsumed workflow expression")
    return result


def event(name="pull_request", head="owner/project", fork=True, push="", run="one"):
    payload = {"inputs": {"push": push}}
    if name == "pull_request":
        payload["pull_request"] = {
            "number": 42, "head": {"repo": {"full_name": head, "fork": fork}}
        }
    return {"github": {
        "event_name": name, "repository": "owner/project", "run_id": run, "event": payload
    }}


def active(step, context):
    return truth(expression(step.get("if", "true"), context))


class RunnerSarifWorkflowsTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
        self.job = self.workflow["jobs"]["build"]

    def step(self, name):
        matches = [step for step in self.job["steps"] if step.get("name") == name]
        self.assertEqual(len(matches), 1, "Expected one step: " + name)
        return matches[0]

    def test_runner_uploads_keep_the_established_workflow_and_job_identity(self):
        identities = set()
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            workflow = yaml.load(path.read_text(), Loader=yaml.BaseLoader)
            for job, configuration in workflow.get("jobs", {}).items():
                for step in configuration.get("steps", []):
                    if step.get("with", {}).get("category") == "trivy-job-runner":
                        self.assertEqual(step["uses"], UPLOAD_ACTION)
                        self.assertNotIn("matrix", step["with"])
                        self.assertNotIn("matrix", configuration.get("strategy", {}))
                        identities.add(path.relative_to(ROOT).as_posix() + ":" + job)
        self.assertEqual(identities, {".github/workflows/job-runner-publish.yml:build"})

    def test_same_repository_pr_uploads_even_when_the_repository_is_a_fork(self):
        upload = self.step("Upload Trivy scan results")
        for fork in (True, False):
            with self.subTest(fork=fork):
                self.assertTrue(active(upload, event(fork=fork)))
                self.assertFalse(truth(expression(
                    upload.get("continue-on-error", "false"), event(fork=fork)
                )))

    def test_pr_checkout_retains_no_credentials_or_additional_permissions(self):
        checkout = self.step("Checkout code")
        self.assertEqual(checkout["with"]["persist-credentials"], "false")
        self.assertEqual(self.workflow["permissions"], {
            "contents": "read", "packages": "write",
            "security-events": "write", "issues": "write",
        })

    def test_external_sources_never_upload_runner_results(self):
        for fork in (True, False):
            context = event(head="outside/project", fork=fork)
            for path in (ROOT / ".github/workflows").glob("*.yml"):
                workflow = yaml.load(path.read_text(), Loader=yaml.BaseLoader)
                for job in workflow.get("jobs", {}).values():
                    for step in job.get("steps", []):
                        if step.get("with", {}).get("category") == "trivy-job-runner":
                            with self.subTest(path=path.name, fork=fork):
                                self.assertFalse(active(step, context))

    def test_every_target_branch_pr_gets_runner_validation(self):
        trigger = self.workflow["on"]["pull_request"]
        self.assertEqual(set(trigger["branches"]), {"UMprod", "containerised", "main"})
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)
        self.assertNotIn("pull_request_target", self.workflow["on"])

    def test_both_trivy_configurations_cover_every_container_target_pr(self):
        container_path = ROOT / ".github/workflows/container-publish.yml"
        container = yaml.load(container_path.read_text(), Loader=yaml.BaseLoader)
        targets = set(container["on"]["pull_request"]["branches"])
        self.assertEqual(targets, {"UMprod", "containerised"})
        self.assertTrue(targets.issubset(self.workflow["on"]["pull_request"]["branches"]))
        configurations = set()
        for path, workflow in ((WORKFLOW, self.workflow), (container_path, container)):
            with self.subTest(workflow=path.name):
                trigger = workflow["on"]["pull_request"]
                self.assertNotIn("paths", trigger, "CI-only and documentation PRs need both scans")
                self.assertNotIn("paths-ignore", trigger)
            for job in workflow["jobs"].values():
                for step in job.get("steps", []):
                    if step.get("uses", "").startswith("github/codeql-action/upload-sarif@"):
                        configurations.add(step["with"]["category"])
        self.assertEqual(configurations, {"trivy-container", "trivy-job-runner"})

    def test_container_publication_remains_excluded_from_prs(self):
        container = yaml.load(
            (ROOT / ".github/workflows/container-publish.yml").read_text(),
            Loader=yaml.BaseLoader,
        )
        publish = container["jobs"]["publish"]
        for name, result, expected in (
            ("pull_request", "success", False), ("pull_request", "failure", False),
            ("push", "success", True), ("push", "failure", False),
            ("workflow_dispatch", "success", True), ("workflow_dispatch", "cancelled", False),
        ):
            context = event(name=name)
            context["needs"] = {"validate": {"result": result}}
            with self.subTest(event=name, validation=result):
                self.assertEqual(active(publish, context), expected)
        build = next(step for step in container["jobs"]["validate"]["steps"]
                     if step.get("id") == "build")
        self.assertEqual(build["with"]["push"], "false")

    def test_pr_build_and_scan_use_the_local_image_without_publication(self):
        context = event()
        build = self.step("Build pull request image")
        scan = self.step("Scan pull request image for vulnerabilities")
        self.assertTrue(active(build, context))
        self.assertTrue(active(scan, context))
        self.assertEqual(build["run"].strip(),
                         'docker build --file docker/job-runner.Dockerfile --tag "${LOCAL_IMAGE_TAG}" .')
        self.assertEqual(scan["with"]["image-ref"], "${{ env.LOCAL_IMAGE_TAG }}")
        self.assertIn("LOCAL_IMAGE_TAG", self.job["env"])
        for name in (
            "Log in to GitHub Container Registry", "Extract metadata for Docker",
            "Build and push image", "Set up QEMU", "Set up Docker Buildx",
        ):
            with self.subTest(name=name):
                self.assertFalse(active(self.step(name), context))

    def test_both_scans_preserve_the_pinned_tool_and_security_inputs(self):
        for name in ("Scan pull request image for vulnerabilities", "Scan image for vulnerabilities"):
            scan = self.step(name)
            self.assertEqual(scan["uses"], SCAN_ACTION)
            self.assertEqual(scan["with"]["format"], "sarif")
            self.assertEqual(scan["with"]["output"], "trivy-job-runner.sarif")
            self.assertEqual(scan["with"]["severity"], "CRITICAL,HIGH")
            self.assertNotIn("exit-code", scan["with"])
            self.assertNotIn("ignore-unfixed", scan["with"])
            self.assertNotIn("continue-on-error", scan)

    def test_push_and_release_and_manual_runs_keep_the_publisher_and_scan(self):
        upload = self.step("Upload Trivy scan results")
        for name, push in (
            ("push", ""), ("release", ""),
            ("workflow_dispatch", "true"), ("workflow_dispatch", "false"),
        ):
            context = event(name=name, push=push)
            with self.subTest(event=name, push=push):
                self.assertTrue(active(self.step("Build and push image"), context))
                self.assertEqual(truth(expression(
                    self.step("Build and push image")["with"]["push"], context
                )), push != "false")
                self.assertTrue(active(self.step("Scan image for vulnerabilities"), context))
                self.assertTrue(active(upload, context))
                self.assertTrue(truth(expression(upload.get("continue-on-error", "false"), context)))
                self.assertFalse(active(self.step("Build pull request image"), context))
                self.assertFalse(active(self.step("Scan pull request image for vulnerabilities"), context))

    def test_publisher_build_arguments_and_outputs_remain_compatible(self):
        arguments = self.step("Build and push image")["with"]
        expected = {
            "context": ".", "file": "docker/job-runner.Dockerfile",
            "platforms": "linux/amd64,linux/arm64",
            "tags": "${{ steps.meta.outputs.tags }}", "labels": "${{ steps.meta.outputs.labels }}",
            "cache-from": "type=gha", "cache-to": "type=gha,mode=max",
            "provenance": "true", "sbom": "true",
        }
        self.assertEqual({name: arguments[name] for name in expected}, expected)
        self.assertEqual(self.job["outputs"], {
            "image-tag": "${{ steps.meta.outputs.version }}",
            "image-digest": "${{ steps.build.outputs.digest }}",
        })
        self.assertEqual(self.workflow["on"]["release"]["types"], ["published"])
        self.assertEqual(self.workflow["on"]["push"]["paths"], [
            "docker/job-runner.Dockerfile", "docker/job-entrypoint.sh",
            ".github/workflows/job-runner-publish.yml",
        ])

    def test_only_prs_share_a_cancellation_group(self):
        concurrency = self.workflow.get("concurrency", {})
        self.assertIn("group", concurrency)
        group = concurrency["group"]

        def rendered(context):
            return re.sub(r"\$\{\{(.*?)\}\}", lambda match: str(expression(match.group(1), context)), group)

        self.assertEqual(rendered(event(run="one")), rendered(event(run="two")))
        self.assertNotEqual(rendered(event(name="push", run="one")),
                            rendered(event(name="push", run="two")))

    def test_runner_contract_regressions_execute_before_building_images(self):
        step = self.step("Check runner workflow contracts")
        self.assertIn(
            "python3 -m unittest discover -s scripts -p 'test_runner_sarif_workflows.py'",
            step["run"],
        )
        self.assertLess(self.job["steps"].index(step),
                        self.job["steps"].index(self.step("Build pull request image")))

    def test_summary_reports_the_actual_upload_outcome(self):
        script = self.step("Generate build summary")["run"]
        for outcome in ("success", "skipped", "failure"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as directory:
                directory = Path(directory)
                (directory / "trivy-job-runner.sarif").write_text('{"runs": []}')
                context = event()
                context["env"] = {"REGISTRY": "ghcr.io", "IMAGE_NAME": "owner/runner"}
                context["steps"] = {
                    "sarif": {"outcome": outcome},
                    "meta": {"outputs": {"tags": "owner/runner:pr"}},
                }
                rendered = re.sub(
                    r"\$\{\{(.*?)\}\}",
                    lambda match: str(expression(match.group(1), context)), script,
                )
                summary = directory / "summary.md"
                result = subprocess.run(
                    ["bash", "-eu", "-c", rendered], cwd=directory,
                    env={"PATH": os.defpath, "GITHUB_STEP_SUMMARY": str(summary)},
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                text = summary.read_text()
                self.assertIn("| Platforms | `linux/amd64` |", text)
                if outcome == "success":
                    self.assertIn("results uploaded", text)
                else:
                    self.assertNotIn("results uploaded", text)
                    self.assertIn(outcome, text)


if __name__ == "__main__":
    unittest.main()
