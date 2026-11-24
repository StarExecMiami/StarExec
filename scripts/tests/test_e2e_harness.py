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
        self.assertEqual(failed, [], f"Drifted endpoint citations: {failed}")

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


if __name__ == "__main__":
    unittest.main()
