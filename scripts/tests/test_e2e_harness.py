"""Offline unit tests for the sanitized 63-cell E2E harness under scripts/e2e/."""

import ast
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - stdlib-only minimal runners
    yaml = None

REPO_ROOT = Path(__file__).resolve().parents[2]
E2E_DIR = REPO_ROOT / "scripts" / "e2e"
SCIENTIFIC_DIR = REPO_ROOT / "scripts" / "scientific"

sys.path.insert(0, str(E2E_DIR))
from lib import config as e2e_config  # noqa: E402
from lib import endpoints as ep  # noqa: E2E, E402
from lib import fixtures as e2e_fixtures  # noqa: E402
from lib import matrix as e2e_matrix  # noqa: E402

ENDPOINT_ARG = {"check": 6, "blocked": 3, "gated": 3}


def _named_endpoint_keys(tree):
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        attr, owner = node.func.attr, node.func.value
        if isinstance(owner, ast.Name) and owner.id == "ep" and attr in ("path", "require", "cite"):
            if node.args and isinstance(node.args[0], ast.Constant):
                yield node.args[0].value, node.lineno
        if attr in ENDPOINT_ARG:
            arg = node.args[ENDPOINT_ARG[attr]] if len(node.args) > ENDPOINT_ARG[attr] else None
            for kw in node.keywords:
                if kw.arg == "endpoints":
                    arg = kw.value
            if isinstance(arg, (ast.List, ast.Tuple)):
                for elt in arg.elts:
                    if isinstance(elt, ast.Constant) and isinstance(elt.value, str):
                        yield elt.value, node.lineno


def _load_verify_module():
    spec = importlib.util.spec_from_file_location(
        "starexec_verify_fixtures_test", SCIENTIFIC_DIR / "verify_fixtures.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class E2EHarnessTest(unittest.TestCase):
    def test_no_host_paths_or_legacy_binary_references_in_scripts_e2e(self):
        legacy_sha = next(iter(_load_verify_module().LEGACY_REJECTED_SHA256))
        for path in sorted(E2E_DIR.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(E2E_DIR)
            if rel.parts[0] in ("fixtures", "__pycache__", ".pytest_cache", ".ruff_cache"):
                continue
            if path.suffix not in (".py", ".json", ".bash", ".xml"):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            self.assertNotIn("/home/", text, f"Host path leaked into {rel}")
            self.assertNotIn("E---3.3.0", text, f"Legacy archive name leaked into {rel}")
            self.assertNotIn(legacy_sha, text, f"Legacy SHA-256 leaked into {rel}")

    def test_every_capability_endpoint_key_is_registered_and_verified(self):
        known = dict(ep.items())
        missing = []
        refs = 0
        for path in sorted((E2E_DIR / "capabilities").glob("c*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for key, lineno in _named_endpoint_keys(tree):
                refs += 1
                if key not in known:
                    missing.append(f"{path.name}:{lineno} -> {key}")
        self.assertGreater(refs, 100)
        self.assertEqual(missing, [])

        verify_results = ep.verify(None, REPO_ROOT)
        failed = [r for r in verify_results if not r["ok"]]
        self.assertEqual(failed, [], f"Drifted endpoint citations at pinned commit: {failed}")

        head_results = ep.verify("HEAD", REPO_ROOT)
        head_failed = [r for r in head_results if not r["ok"]]
        self.assertEqual(head_failed, [], f"Drifted endpoint citations against HEAD: {head_failed}")

    def test_verify_endpoints_falls_back_to_head_when_pinned_commit_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.com"],
                cwd=repo,
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test User"],
                cwd=repo,
                check=True,
                capture_output=True,
            )
            sample = repo / "sample.txt"
            sample.write_text("first line\nENDPOINT_NEEDLE_HERE\n", encoding="utf-8")
            subprocess.run(["git", "add", "sample.txt"], cwd=repo, check=True, capture_output=True)
            subprocess.run(
                ["git", "commit", "-m", "init"],
                cwd=repo,
                check=True,
                capture_output=True,
            )

            missing_sha = "0" * 40
            fake_registry = {
                "resolved_at_commit": missing_sha,
                "endpoints": {
                    "sample_ep": {
                        "method": "GET",
                        "path": "/sample",
                        "file": "sample.txt",
                        "line": 2,
                        "needle": "ENDPOINT_NEEDLE_HERE",
                    }
                },
            }
            orig_registry = ep._registry
            try:
                ep._registry = fake_registry
                fallback_results = ep.verify(None, repo)
                self.assertEqual(len(fallback_results), 1)
                self.assertTrue(fallback_results[0]["ok"], fallback_results)
                self.assertEqual(fallback_results[0]["commit"], "HEAD")

                explicit_results = ep.verify(missing_sha, repo)
                self.assertEqual(len(explicit_results), 1)
                self.assertFalse(explicit_results[0]["ok"], explicit_results)
            finally:
                ep._registry = orig_registry

    def test_matrix_accounting_is_63_cells_and_never_promotes_blocked_or_cross_mode(self):
        self.assertEqual(len(e2e_matrix.CAPABILITY_ROWS), 21)
        self.assertEqual(len(e2e_matrix.BACKENDS), 3)

        fake_run = {
            "runid": "20261124T000000Z-1",
            "deployment": {
                "backend": "local",
                "mode": "fresh",
                "commit": "3afdd5806eac7c21450e67322024859f5e7bcdc4",
                "image_digest": "sha256:abcdef0123456789",
            },
            "capabilities": [
                {"number": 1, "status": "PASS"},
                {"number": 27, "status": "PASS"},
                {"number": 30, "status": "EXPECTED-FAIL"},
                {"number": 3, "status": "FAIL"},
                {"number": 29, "status": "PASS"},
                {"number": 28, "status": "BLOCKED"},
                {"number": 7, "status": "PASS"},
                {"number": 33, "status": "SKIPPED-GATED"},
            ],
        }
        report = e2e_matrix.build_matrix_report(
            [fake_run],
            commit_sha="3afdd5806eac7c21450e67322024859f5e7bcdc4",
            active_mode="fresh",
            blocked_backends={("fresh", "podman"): "rootless socket unavailable"},
        )

        self.assertEqual(report["matrixDenominator"], 63)
        self.assertEqual(report["backendDenominator"], 21)
        fresh = report["modes"]["fresh"]
        upgrade = report["modes"]["upgrade"]

        by_id = {c["cellId"]: c for c in fresh["cells"]}
        self.assertEqual(by_id["C01:local:fresh"]["status"], "PASS")
        self.assertEqual(by_id["C02:local:fresh"]["status"], "BLOCKED")
        self.assertEqual(by_id["C03:local:fresh"]["status"], "FAIL")
        self.assertEqual(by_id["C05:local:fresh"]["status"], "BLOCKED")
        self.assertEqual(by_id["C07:local:fresh"]["status"], "SKIPPED")
        self.assertEqual(by_id["C01:podman:fresh"]["status"], "BLOCKED")
        self.assertEqual(by_id["C01:kubernetes:fresh"]["status"], "SKIPPED")

        # Exact sum check: PASS + FAIL + BLOCKED + SKIPPED == 63 per mode
        self.assertEqual(sum(fresh["countsByStatus"].values()), 63)
        self.assertEqual(fresh["countsByStatus"]["PASS"], 1)
        self.assertEqual(fresh["countsByStatus"]["BLOCKED"], 23)  # 21 podman + C02,C05 local
        self.assertEqual(fresh["countsByStatus"]["FAIL"], 2)  # C03,C04 (both include cap 3)
        self.assertEqual(sum(upgrade["countsByStatus"].values()), 63)
        self.assertEqual(upgrade["countsByStatus"]["PASS"], 0)
        self.assertEqual(upgrade["countsByStatus"]["SKIPPED"], 63)

        with tempfile.TemporaryDirectory() as tmp:
            paths = e2e_matrix.write_matrix_artifacts(report, tmp)
            loaded = json.loads(paths["json"].read_text(encoding="utf-8"))
            self.assertEqual(loaded["commitSha"], "3afdd5806eac7c21450e67322024859f5e7bcdc4")
            self.assertEqual(loaded["imageDigestsByBackend"]["local"], "sha256:abcdef0123456789")
            csv_lines = paths["csv"].read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(csv_lines), 1 + 63 * 2)  # header + fresh(63) + upgrade(63)
            md_text = paths["markdown"].read_text(encoding="utf-8")
            self.assertIn("Mode: `fresh`", md_text)
            self.assertIn("Mode: `upgrade`", md_text)

    def test_reproducible_offline_fixtures_connect_scientific_locks_and_reject_legacy(self):
        vf = _load_verify_module()
        legacy_sha = next(iter(vf.LEGACY_REJECTED_SHA256))
        with self.assertRaises(e2e_fixtures.FixtureError):
            e2e_fixtures.check_not_legacy_rejected(legacy_sha, "legacy")

        manifest = e2e_fixtures.build(mode="reproducible-offline")
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["fixture_mode"], "reproducible-offline")
        self.assertEqual(manifest["eprover_config"], "E-3.3.3-sequential")
        self.assertTrue(all(v["ok"] for v in manifest["archives"].values()))
        self.assertEqual(
            sorted(manifest["test_sequence_fixtures"].keys()),
            ["CVC4.zip", "benchmarks.zip", "benchproc.zip", "postproc.zip", "smallsolver.zip"],
        )

        # Verify built SZSPostProcessor.zip matches #287 lockfile
        szs_path = Path(manifest["files"]["SZSPostProcessor.zip"]["path"])
        vf.verify_fixture_archive(
            szs_path, lock_path=SCIENTIFIC_DIR / "szs-post-processor.lock.json"
        )

        # Verify offline E-3.3.3-sequential.zip and TPTPBenchmarkProcessor.zip scripts behave properly
        with tempfile.TemporaryDirectory() as tmp:
            tdir = Path(tmp)
            e_zip = Path(manifest["files"]["E-3.3.3-sequential.zip"]["path"])
            with zipfile.ZipFile(e_zip, "r") as zf:
                e_script = Path(zf.extract("bin/starexec_run_E-3.3.3-sequential", tdir))
            e_script.chmod(0o755)
            prob = tdir / "sample.p"
            prob.write_text("% Status : CounterSatisfiable\nfof(c,conjecture,p).\n", encoding="utf-8")
            res = subprocess.run(
                [str(e_script), str(prob)],
                env={"PATH": "/usr/bin:/bin", "STAREXEC_CPU_LIMIT": "10"},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(res.returncode, 1)
            self.assertIn("% SZS status CounterSatisfiable", res.stdout)

            tptp_zip = Path(manifest["files"]["TPTPBenchmarkProcessor.zip"]["path"])
            with zipfile.ZipFile(tptp_zip, "r") as zf:
                t_script = Path(zf.extract("process", tdir))
            t_script.chmod(0o755)
            valid_out = subprocess.run(
                [str(t_script), str(prob)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(valid_out.returncode, 0)
            self.assertIn("starexec-valid=true", valid_out.stdout)
            self.assertIn("starexec-expected-result=CounterSatisfiable", valid_out.stdout)

            bad_file = tdir / "garbage.p"
            bad_file.write_text("not valid tptp content at all\n", encoding="utf-8")
            bad_out = subprocess.run(
                [str(t_script), str(bad_file)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(bad_out.returncode, 0)
            self.assertIn("starexec-valid=false", bad_out.stdout)

    def test_config_rejects_production_hosts_and_unsafe_namespaces(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg_path = Path(tmp) / "bad.json"
            cfg_path.write_text(
                json.dumps(
                    {
                        "backend": "kubernetes",
                        "base_url": "http://127.0.0.1:8080/starexec",
                        "deployment_name": "sq-deadbeef-k8s",
                        "db_exec": ["kubectl", "-n", "starexec", "exec", "pod/db"],
                        "commit": "3afdd580",
                        "image": "ghcr.io/starexecmiami/starexec:sha-3afdd580",
                        "report_dir": tmp,
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(e2e_config.ConfigError):
                e2e_config.load(cfg_path)

    def test_cli_help_and_ci_automation_files_are_valid(self):
        proc = subprocess.run(
            [sys.executable, str(E2E_DIR / "run.py"), "--help"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--verify-endpoints", proc.stdout)
        self.assertIn("--record-blocked", proc.stdout)

        workflow_text = (
            REPO_ROOT / ".github" / "workflows" / "integration-test.yml"
        ).read_text(encoding="utf-8")
        if yaml is not None:
            workflow = yaml.safe_load(workflow_text)
            jobs = workflow["jobs"]
            self.assertIn("integration-test", jobs)
            self.assertIn("podman-e2e", jobs)
            self.assertIn("test-summary", jobs)
        else:
            self.assertIn("podman-e2e:", workflow_text)
            self.assertIn("integration-test:", workflow_text)
            self.assertIn("test-summary:", workflow_text)

        jenkins = (REPO_ROOT / "Jenkinsfile").read_text(encoding="utf-8")
        self.assertIn("Deployed E2E Matrix (Kubernetes)", jenkins)
        self.assertIn("--backend kubernetes", jenkins)

    @staticmethod
    def _render_groovy_gstring(body):
        simple_escapes = {
            "n": "\n",
            "t": "\t",
            "r": "\r",
            "b": "\b",
            "f": "\f",
            "\\": "\\",
            "$": "$",
            '"': '"',
            "'": "'",
        }
        out = []
        i = 0
        n = len(body)
        while i < n:
            ch = body[i]
            if ch == "\\" and i + 1 < n:
                nxt = body[i + 1]
                if nxt == "\n":
                    i += 2
                    continue
                if nxt in simple_escapes:
                    out.append(simple_escapes[nxt])
                    i += 2
                    continue
                raise AssertionError(f"Unsupported Groovy backslash escape \\{nxt!r} in Jenkinsfile GString")
            if ch == "$" and i + 1 < n and body[i + 1] == "{":
                end = body.find("}", i + 2)
                if end != -1:
                    out.append("stub")
                    i = end + 1
                    continue
            out.append(ch)
            i += 1
        return "".join(out)

    def test_jenkinsfile_shell_blocks_have_no_indented_heredoc_delimiters(self):
        import re

        jenkins = (REPO_ROOT / "Jenkinsfile").read_text(encoding="utf-8")
        sh_blocks = re.findall(
            r"""\bsh\s*(?:\(\s*script\s*:\s*)?('''|\"\"\")(.*?)\1""",
            jenkins,
            flags=re.DOTALL,
        )
        self.assertGreaterEqual(len(sh_blocks), 5)

        for quote, body in sh_blocks:
            rendered = self._render_groovy_gstring(body) if quote == '"""' else body

            lines = rendered.splitlines()
            idx = 0
            while idx < len(lines):
                match = re.search(
                    r"<<(-?)\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))",
                    lines[idx],
                )
                if not match:
                    idx += 1
                    continue
                strip_tabs = bool(match.group(1))
                delimiter = match.group(2) or match.group(3) or match.group(4)
                idx += 1
                closed = False
                while idx < len(lines):
                    candidate = lines[idx].lstrip("\t") if strip_tabs else lines[idx]
                    self.assertFalse(
                        lines[idx].lstrip(" \t") == delimiter and candidate != delimiter,
                        f"Heredoc closing delimiter {delimiter!r} is space-indented in Jenkinsfile: {lines[idx]!r}",
                    )
                    if candidate == delimiter:
                        closed = True
                        break
                    idx += 1
                self.assertTrue(closed, f"Unterminated heredoc {delimiter!r} in Jenkinsfile sh block")
                idx += 1

    def test_jenkinsfile_k8s_e2e_stage_valid_sh_and_enforces_matrix_reports(self):
        import os
        import re

        jenkins = (REPO_ROOT / "Jenkinsfile").read_text(encoding="utf-8")
        stage_match = re.search(
            r"stage\('Deployed E2E Matrix \(Kubernetes\)'\)\s*\{(?P<body>.*?)^\s*\}\s*// end stages",
            jenkins,
            flags=re.DOTALL | re.MULTILINE,
        )
        self.assertIsNotNone(stage_match)
        stage_body = stage_match.group("body")

        sh_match = re.search(r'sh\s*"""(.*?)"""', stage_body, flags=re.DOTALL)
        self.assertIsNotNone(sh_match)
        rendered = self._render_groovy_gstring(sh_match.group(1))

        proc = subprocess.run(
            ["sh", "-n"],
            input=rendered,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertRegex(
            rendered,
            r'(?m)^\s*"\$\{TMP_E2E\}/venv/bin/python"\s+scripts/e2e/run\.py\s+\\\n\s+--config\s+',
        )
        py_snippets = re.findall(
            r'"\$\{TMP_E2E\}/venv/bin/python"\s+-c\s+\'(.*?)\'',
            rendered,
            flags=re.DOTALL,
        )
        self.assertEqual(len(py_snippets), 2)
        for py_src in py_snippets:
            ast.parse(py_src)

        with tempfile.TemporaryDirectory() as tmp:
            cfg_path = Path(tmp) / "k8s-e2e-config.json"
            gen_env = dict(os.environ)
            gen_env.update(
                {
                    "E2E_CFG_PATH": str(cfg_path),
                    "E2E_MATRIX_MODE": "fresh",
                    "E2E_APP_URL": "http://localhost:30081/starexec",
                    "E2E_SHORT_SHA": "86a325c7",
                    "E2E_K8S_NAMESPACE": "starexec-dev",
                    "E2E_HELM_RELEASE": "starexec-dev",
                    "E2E_GIT_SHA": "86a325c7eb952ff6d8eed9f7506fe863832df4e4",
                    "E2E_PUBLISHED_IMAGE": "ghcr.io/starexecmiami/starexec:sha-86a325c7eb952ff6d8eed9f7506fe863832df4e4",
                    "E2E_DEPLOY_IMAGE_DIGEST": "sha256:" + "a" * 64,
                    "E2E_REPORT_DIR": tmp,
                }
            )
            gen = subprocess.run(
                [sys.executable, "-c", py_snippets[0]],
                env=gen_env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(gen.returncode, 0, gen.stderr)
            loaded = e2e_config.load(cfg_path)
            self.assertEqual(loaded.backend, "kubernetes")
            self.assertEqual(loaded.mode, "fresh")
            self.assertEqual(loaded.base_url, "http://localhost:30081/starexec")
            self.assertEqual(loaded.deployment_name, "sq-86a325c7-k8s")
            self.assertEqual(loaded.queue_id, "auto")
            self.assertEqual(loaded.allowed_hosts, [])
            self.assertTrue(e2e_config.is_disposable(loaded))

        for artifact in (
            "target/e2e-reports/kubernetes/matrix-report.json",
            "target/e2e-reports/kubernetes/matrix-report.csv",
            "target/e2e-reports/kubernetes/matrix-summary.md",
        ):
            self.assertIn(artifact, rendered)
        self.assertIn('[ ! -s "${report_file}" ]', rendered)
        self.assertIn("allowEmptyArchive: false", stage_body)

    def test_jenkinsfile_resolve_published_image_and_unit_tests_fail_fast_jq(self):
        import json
        import re
        import shutil

        jenkins = (REPO_ROOT / "Jenkinsfile").read_text(encoding="utf-8")
        stage_match = re.search(
            r"stage\('Resolve Image & Verify Tests'\)\s*\{(?P<body>.*?)stage\('Pre-deploy Validation'\)",
            jenkins,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(stage_match)
        stage_body = stage_match.group("body")

        self.assertIn("failFast true", stage_body)
        self.assertIn("container-publish.yml/runs?per_page=100", stage_body)
        self.assertIn("maven-test.yml/runs?per_page=100", stage_body)
        self.assertNotIn("status=completed", stage_body)
        self.assertNotIn("head_sha=", stage_body)

        sh_blocks = re.findall(r'sh\s*\(\s*script\s*:\s*"""(.*?)"""', stage_body, flags=re.DOTALL)
        self.assertEqual(len(sh_blocks), 2)
        rendered_blocks = []
        for body in sh_blocks:
            rendered = self._render_groovy_gstring(body)
            rendered_blocks.append(rendered)
            proc = subprocess.run(["sh", "-n"], input=rendered, text=True, capture_output=True, check=False)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            bash_proc = subprocess.run(["bash", "-n"], input=rendered, text=True, capture_output=True, check=False)
            self.assertEqual(bash_proc.returncode, 0, bash_proc.stderr)

        if shutil.which("jq") is None:
            self.skipTest("jq binary not available")

        target_sha = "93e4de964fda675f8e1e7e3da48d52c3129f209b"
        other_sha = "16be8b7c2b9cc4fd8eaae5d1cadfa9f9fca2c382"

        def eval_jq(expr, payload):
            proc = subprocess.run(
                ["jq", "-r", "--arg", "sha", target_sha, expr],
                input=json.dumps(payload),
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            return proc.stdout.strip()

        resolve_jq_match = re.search(r"jq -r --arg sha '[^']+'\s+'(.*?)'\)", rendered_blocks[0], flags=re.DOTALL)
        self.assertIsNotNone(resolve_jq_match)
        resolve_jq = resolve_jq_match.group(1)

        # 1. Success state (even alongside an earlier failed run for the same SHA)
        self.assertEqual(
            eval_jq(resolve_jq, {
                "total_count": 2,
                "workflow_runs": [
                    {"id": 100, "head_sha": target_sha, "status": "completed", "conclusion": "failure", "updated_at": "2026-11-27T04:38:01Z", "created_at": "2026-11-27T04:30:00Z", "html_url": "https://example/runs/100"},
                    {"id": 101, "head_sha": target_sha, "status": "completed", "conclusion": "success", "updated_at": "2026-11-27T04:45:00Z", "created_at": "2026-11-27T04:40:00Z", "html_url": "https://example/runs/101"},
                ],
            }),
            "success|101|success|https://example/runs/101",
        )

        # 2. Active/in-progress state (keeps polling while rerun or initial run executes)
        for active_status in ("queued", "in_progress", "waiting", "pending", "requested"):
            self.assertEqual(
                eval_jq(resolve_jq, {
                    "total_count": 2,
                    "workflow_runs": [
                        {"id": 100, "head_sha": target_sha, "status": "completed", "conclusion": "failure", "updated_at": "2026-11-27T04:38:01Z", "created_at": "2026-11-27T04:30:00Z", "html_url": "https://example/runs/100"},
                        {"id": 102, "head_sha": target_sha, "status": active_status, "conclusion": None, "updated_at": "2026-11-27T04:42:00Z", "created_at": "2026-11-27T04:41:00Z", "html_url": "https://example/runs/102"},
                    ],
                }),
                f"pending|102|{active_status}|https://example/runs/102",
            )

        # 3. Fail-fast state: all matching runs completed without success
        for fail_conclusion in ("failure", "cancelled", "timed_out", "action_required", "startup_failure"):
            self.assertEqual(
                eval_jq(resolve_jq, {
                    "total_count": 1,
                    "workflow_runs": [
                        {"id": 103, "head_sha": target_sha, "status": "completed", "conclusion": fail_conclusion, "updated_at": "2026-11-27T04:38:01Z", "created_at": "2026-11-27T04:30:00Z", "html_url": "https://example/runs/103"},
                    ],
                }),
                f"failed|103|{fail_conclusion}|https://example/runs/103",
            )

        # 4. Not yet registered state (0 runs for target SHA): waits instead of failing
        self.assertEqual(
            eval_jq(resolve_jq, {
                "total_count": 1,
                "workflow_runs": [
                    {"id": 99, "head_sha": other_sha, "status": "completed", "conclusion": "success", "updated_at": "2026-11-27T00:48:08Z", "created_at": "2026-11-27T00:40:00Z", "html_url": "https://example/runs/99"},
                ],
            }),
            f"none|||total_count=1, page_runs=1, matching_sha=0, latest_shas={other_sha[:12]}(success)",
        )

        # Unit Tests jq preserves fallback on empty match and handles active/failed/success consistently
        unit_jq_match = re.search(r"jq -r --arg sha '[^']+'\s+'(.*?)'\)", rendered_blocks[1], flags=re.DOTALL)
        self.assertIsNotNone(unit_jq_match)
        unit_jq = unit_jq_match.group(1)
        self.assertEqual(eval_jq(unit_jq, {"total_count": 1, "workflow_runs": [{"id": 99, "head_sha": other_sha, "status": "completed", "conclusion": "success"}]}), "")
        self.assertEqual(
            eval_jq(unit_jq, {
                "workflow_runs": [
                    {"id": 200, "head_sha": target_sha, "status": "completed", "conclusion": "failure", "updated_at": "2026-11-27T04:30:00Z", "created_at": "2026-11-27T04:25:00Z", "html_url": "https://example/runs/200"},
                    {"id": 201, "head_sha": target_sha, "status": "in_progress", "conclusion": "", "updated_at": "2026-11-27T04:31:00Z", "created_at": "2026-11-27T04:31:00Z", "html_url": "https://example/runs/201"},
                ],
            }),
            "201|in_progress||https://example/runs/201",
        )
        self.assertEqual(
            eval_jq(unit_jq, {
                "workflow_runs": [
                    {"id": 202, "head_sha": target_sha, "status": "completed", "conclusion": "failure", "updated_at": "2026-11-27T04:32:00Z", "created_at": "2026-11-27T04:31:00Z", "html_url": "https://example/runs/202"},
                ],
            }),
            "202|completed|failure|https://example/runs/202",
        )

    def test_ci_only_selections_close_complete_matrix_rows(self):
        import re
        from capabilities import BY_NUMBER

        workflow_text = (
            REPO_ROOT / ".github" / "workflows" / "integration-test.yml"
        ).read_text(encoding="utf-8")
        selections = re.findall(r"--only\s+([0-9,]+)", workflow_text)
        self.assertGreaterEqual(len(selections), 2, selections)

        for sel in selections:
            caps = [int(x) for x in sel.split(",")]
            available_state = set()
            for num in caps:
                self.assertIn(num, BY_NUMBER)
                _, _, _, needs, creates = BY_NUMBER[num]
                missing_needs = [k for k in needs if k not in available_state]
                self.assertEqual(
                    missing_needs,
                    [],
                    f"--only {sel} runs cap {num} before prerequisites {missing_needs}",
                )
                available_state.update(creates)

            fake_run = {
                "runid": "ci-only-check",
                "deployment": {
                    "backend": "local",
                    "mode": "fresh",
                    "commit": "0" * 40,
                    "image_digest": "sha256:0000",
                },
                "capabilities": [{"number": n, "status": "PASS"} for n in caps],
            }
            report = e2e_matrix.build_matrix_report(
                [fake_run], commit_sha="0" * 40, active_mode="fresh"
            )
            passed = [
                c["capabilityId"]
                for c in report["modes"]["fresh"]["cells"]
                if c["backend"] == "local" and c["status"] == "PASS"
            ]
            self.assertGreater(
                len(passed),
                0,
                f"--only {sel} failed to close any complete CAPABILITY_ROWS row",
            )
            self.assertEqual(passed, ["C11", "C17", "C18", "C20"])


class E2EDAGExecutorTest(unittest.TestCase):
    def _dummy_run(self, tmp, state, selected):
        from lib import endpoints as ep
        from lib.report import Run

        cfg = e2e_config.Config(
            backend="kubernetes",
            mode="fresh",
            fixture_mode="reproducible-offline",
            base_url="http://localhost:30081/starexec",
            deployment_name="sq-testdag-k8s",
            db_exec=["true"],
            app_exec=[],
            app_logs=[],
            app_data_root="/app/data",
            queue_id="1",
            commit="0" * 40,
            image="example/starexec:test",
            image_digest="sha256:" + "0" * 64,
            report_dir=Path(tmp) / "reports",
            allowed_hosts=["localhost"],
            admin_user_id=1,
            enable_browser=False,
            timeouts={"upload": 10, "job": 10, "pause": 10, "kill": 10, "dispatch": 10, "smtp": 10},
        )
        run = Run(cfg, "testdag-1", state.path, ["--workers", "4"])
        run.data["selected"] = list(selected)
        return cfg, run

    def test_real_capabilities_dag_valid_and_enforce_explicit_edges(self):
        from capabilities import CAPABILITIES, after_for, bucket_for
        from lib.dag import build_dag

        dag = build_dag(CAPABILITIES, buckets=bucket_for, after_map=after_for)
        self.assertEqual(len(dag.order), 45)
        self.assertIn(30, dag.preds[35])
        self.assertTrue({11, 12, 15, 20}.issubset(dag.preds[14]))
        self.assertIn(25, dag.preds[24])
        self.assertIn(10, dag.preds[15])
        self.assertIn(10, dag.preds[20])
        for n in dag.order:
            if n != 22:
                self.assertIn(n, dag.preds[22])

        cli = subprocess.run(
            [sys.executable, str(E2E_DIR / "run.py"), "--validate-dag"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(cli.returncode, 0, cli.stderr)
        payload = json.loads(cli.stdout)
        self.assertTrue(payload["valid"])
        self.assertEqual(payload["capabilities"], 45)

    def test_dag_validator_detects_cycles_orphan_needs_and_duplicates(self):
        from lib.dag import DAGValidationError, build_dag

        noop = lambda cap, ctx: None
        with self.assertRaises(DAGValidationError) as ctx_cycle:
            build_dag(
                [(1, "a", noop, ["k2"], ["k1"]), (2, "b", noop, ["k1"], ["k2"])],
                buckets={1: "parallel", 2: "parallel"},
            )
        self.assertIn("cycle", str(ctx_cycle.exception).lower())

        with self.assertRaises(DAGValidationError):
            build_dag([(1, "a", noop, ["missing_key"], [])], buckets={1: "parallel"})

        seeded = build_dag(
            [(1, "a", noop, ["seeded_key"], [])],
            seeded_keys={"seeded_key"},
            buckets={1: "parallel"},
        )
        self.assertEqual(seeded.order, [1])

        with self.assertRaises(DAGValidationError):
            build_dag(
                [(1, "a", noop, [], ["dup"]), (2, "b", noop, [], ["dup"])],
                buckets={1: "parallel", 2: "parallel"},
            )

        partial = build_dag(
            [(1, "a", noop, [], []), (2, "b", noop, [], [])],
            selected=[2],
            buckets={1: "parallel", 2: "parallel"},
            after_map={2: (1,)},
        )
        self.assertEqual(len(partial.warnings), 1)

    def test_parallel_workers_speedup_state_and_context_isolation(self):
        import threading
        import time
        from lib.context import Context
        from lib.dag import build_dag, run_dag
        from lib.state import State, StateWriteRefused

        order_seen = []
        kept_refs = []
        client_objs = []
        lock = threading.Lock()

        def make_worker(key_name, sleep_s=0.14):
            def _fn(cap, ctx):
                client = ctx.run.recorder  # Worker _RunView points to isolated Recorder
                with lock:
                    kept_refs.append((cap.number, ctx._sessions, client))
                    order_seen.append(cap.number)
                ctx.recorder.note(f"worker-{cap.number}-private-note")
                ctx.state.set(key_name, cap.number)
                time.sleep(sleep_s)
                cap.check("1", "ok", "worker isolated", key_name, key_name, True)

            return _fn

        def bad_writer(cap, ctx):
            with lock:
                kept_refs.append((cap.number, ctx._sessions, ctx.run.recorder))
            with self.assertRaises(StateWriteRefused):
                ctx.state.set("owned_a", 999)
            cap.check("1", "guarded", "undeclared write refused", True, True, True)

        caps = [
            (1, "p1", make_worker("owned_a"), [], ["owned_a"]),
            (2, "p2", make_worker("owned_b"), [], ["owned_b"]),
            (3, "p3", make_worker("owned_c"), [], ["owned_c"]),
            (4, "bad", bad_writer, [], []),
        ]
        buckets = {n: "parallel" for n, *_ in caps}
        with tempfile.TemporaryDirectory() as tmp:
            owners = {k: (n,) for n, _, _, _, cr in caps for k in cr}
            state = State(Path(tmp) / "state.json", owners=owners)
            cfg, run = self._dummy_run(tmp, state, [1, 2, 3, 4])
            ctx = Context(cfg, run, state, {})
            # Track real HTTP session objects closed at worker teardown
            from lib.http import Client

            def tracked_session(cap_num):
                cli = Client(cfg.base_url, ctx.recorder, f"w{cap_num}")
                with lock:
                    client_objs.append(cli)
                return cli

            orig_fork = ctx.fork_for_worker

            def wrapped_fork(scoped_state, worker_recorder=None):
                w = orig_fork(scoped_state, worker_recorder)
                w._sessions["synthetic"] = tracked_session(scoped_state.number)
                return w

            ctx.fork_for_worker = wrapped_fork
            dag = build_dag(caps, buckets=buckets)
            stats = run_dag(dag, ctx, workers=4, k8s_job_slots=2)
            run.finish(order_numbers=[1, 2, 3, 4])

            self.assertLess(stats["wall_clock_s"], 0.36)
            self.assertEqual(len(kept_refs), 4)
            self.assertEqual(len({id(sess) for _, sess, _ in kept_refs}), 4)
            self.assertEqual(len({id(rec) for _, _, rec in kept_refs}), 4)
            self.assertTrue(all(sess is not ctx._sessions for _, sess, _ in kept_refs))
            self.assertTrue(all(len(sess) == 0 for _, sess, _ in kept_refs))
            self.assertEqual(state.get("owned_a"), 1)
            self.assertEqual(state.get("owned_b"), 2)
            self.assertEqual(state.get("owned_c"), 3)
            for cap_entry in run.data["capabilities"]:
                num = cap_entry["number"]
                if num in (1, 2, 3):
                    notes = [n["text"] for n in cap_entry["checks"][0]["evidence"].get("notes", [])]
                    self.assertEqual(notes, [f"worker-{num}-private-note"])

    def test_k8s_semaphore_and_exclusive_bucket_d_mutual_exclusion(self):
        import threading
        import time
        from lib.context import Context
        from lib.dag import build_dag, run_dag
        from lib.state import State

        state_lock = threading.Lock()
        active_total = 0
        active_k8s = 0
        max_k8s = 0
        max_during_exclusive = 0

        def runner(bucket):
            def _fn(cap, ctx):
                nonlocal active_total, active_k8s, max_k8s, max_during_exclusive
                with state_lock:
                    active_total += 1
                    if bucket == "k8s":
                        active_k8s += 1
                        max_k8s = max(max_k8s, active_k8s)
                    if bucket == "exclusive":
                        max_during_exclusive = max(max_during_exclusive, active_total)
                time.sleep(0.06)
                with state_lock:
                    if bucket == "exclusive":
                        max_during_exclusive = max(max_during_exclusive, active_total)
                    if bucket == "k8s":
                        active_k8s -= 1
                    active_total -= 1
                cap.check("1", "done", "bucket rule respected", True, True, True)

            return _fn

        caps = [
            (10, "k1", runner("k8s"), [], []),
            (11, "k2", runner("k8s"), [], []),
            (12, "k3", runner("k8s"), [], []),
            (13, "k4", runner("k8s"), [], []),
            (19, "k5", runner("k8s"), [], []),
            (27, "p1", runner("parallel"), [], []),
            (28, "p2", runner("parallel"), [], []),
            (34, "excl", runner("exclusive"), [], []),
        ]
        buckets = {10: "k8s", 11: "k8s", 12: "k8s", 13: "k8s", 19: "k8s", 27: "parallel", 28: "parallel", 34: "exclusive"}
        with tempfile.TemporaryDirectory() as tmp:
            state = State(Path(tmp) / "state.json", owners={})
            cfg, run = self._dummy_run(tmp, state, [c[0] for c in caps])
            ctx = Context(cfg, run, state, {})
            dag = build_dag(caps, buckets=buckets)
            run_dag(dag, ctx, workers=4, k8s_job_slots=2)
            run.finish(order_numbers=[c[0] for c in caps])
            self.assertLessEqual(max_k8s, 2)
            self.assertEqual(max_k8s, 2)
            self.assertEqual(max_during_exclusive, 1)

    def test_failed_producer_unblocks_dependents_and_matches_serial_report(self):
        import time
        from lib.context import Context
        from lib.dag import build_dag, run_dag
        from lib.report import Capability
        from lib.state import State

        def producer_fail(cap, ctx):
            raise RuntimeError("boom")

        def consumer_needs(cap, ctx):
            ctx.state.need("prod_key")
            cap.check("1", "unreachable", "should be blocked", True, True, True)

        def indep_ok(cap, ctx):
            time.sleep(0.02)
            cap.check("1", "ok", "independent passes", True, True, True)

        caps = [
            (1, "prod", producer_fail, [], ["prod_key"]),
            (2, "cons", consumer_needs, ["prod_key"], []),
            (3, "indep", indep_ok, [], []),
        ]
        buckets = {1: "setup", 2: "parallel", 3: "parallel"}
        selected = [1, 2, 3]

        reports = {}
        for mode_workers in (1, 4):
            with tempfile.TemporaryDirectory() as tmp:
                state = State(Path(tmp) / "state.json", owners={"prod_key": (1,)})
                cfg, run = self._dummy_run(tmp, state, selected)
                ctx = Context(cfg, run, state, {})
                if mode_workers == 1:
                    for num, name, fn, _, cr in caps:
                        cap = Capability(run, num, name, ctx)
                        state.act_as(num, cr)
                        err = None
                        try:
                            fn(cap, ctx)
                        except Exception as exc:
                            err = exc
                        cap.close(err)
                    run.finish()
                else:
                    dag = build_dag(caps, buckets=buckets)
                    run_dag(dag, ctx, workers=4, k8s_job_slots=2)
                    run.finish(order_numbers=selected)
                reports[mode_workers] = (
                    [c["number"] for c in run.data["capabilities"]],
                    [c["status"] for c in run.data["capabilities"]],
                    run.data["summary"],
                    run.data["verdict"],
                )

        self.assertEqual(reports[1], reports[4])
        self.assertEqual(reports[4][0], [1, 2, 3])
        self.assertEqual(reports[4][1], ["FAIL", "BLOCKED", "PASS"])


if __name__ == "__main__":
    unittest.main()
