"""Offline tests for deterministic TestSequence fixture generation and provenance verification."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCIENTIFIC_DIR = ROOT / "scripts/scientific"
LOCK_FILE = SCIENTIFIC_DIR / "test-sequence-fixtures.lock.json"
SRC_BASE = ROOT / "starexec-app/src/test/resources/org/starexec/test/resources"

GEN_SPEC = importlib.util.spec_from_file_location(
    "generate_test_sequence_fixtures",
    SCIENTIFIC_DIR / "generate_test_sequence_fixtures.py",
)
generator = importlib.util.module_from_spec(GEN_SPEC)
GEN_SPEC.loader.exec_module(generator)

VERIFY_SPEC = importlib.util.spec_from_file_location(
    "verify_fixtures", SCIENTIFIC_DIR / "verify_fixtures.py"
)
verifier = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(verifier)


class TestSequenceFixturesTests(unittest.TestCase):
    def test_generation_is_bit_for_bit_reproducible_and_matches_lockfile(self):
        with tempfile.TemporaryDirectory(prefix="ts-fx-a-") as dir_a, \
             tempfile.TemporaryDirectory(prefix="ts-fx-b-") as dir_b:
            res_a = generator.generate_all(LOCK_FILE, Path(dir_a), repo_root=ROOT)
            res_b = generator.generate_all(LOCK_FILE, Path(dir_b), repo_root=ROOT)

            self.assertEqual(sorted(res_a), sorted(res_b))
            self.assertEqual(
                set(res_a),
                {"CVC4.zip", "benchmarks.zip", "benchproc.zip", "postproc.zip", "smallsolver.zip"},
            )
            for name in res_a:
                bytes_a = (Path(dir_a) / name).read_bytes()
                bytes_b = (Path(dir_b) / name).read_bytes()
                self.assertEqual(bytes_a, bytes_b, f"Archive {name} is not bit-for-bit reproducible")
                self.assertEqual(res_a[name]["sha256"], res_b[name]["sha256"])

            verified = verifier.verify_generated_fixtures(LOCK_FILE, Path(dir_a), repo_root=ROOT)
            self.assertEqual(set(verified), set(res_a))

    def test_verify_fixtures_detects_tampered_generated_archive(self):
        with tempfile.TemporaryDirectory(prefix="ts-fx-tamper-") as out_dir:
            generator.generate_all(LOCK_FILE, Path(out_dir), repo_root=ROOT)
            target = Path(out_dir) / "benchproc.zip"
            with zipfile.ZipFile(target, "w") as zf:
                zf.writestr("process", b"#!/bin/bash\necho evil\n")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                verifier.verify_generated_fixtures(LOCK_FILE, Path(out_dir), repo_root=ROOT)

    def test_verify_lock_directory_includes_test_sequence_fixtures(self):
        locks = verifier.verify_lock_directory(SCIENTIFIC_DIR, repo_root=ROOT)
        names = [entry["lock"] for entry in locks]
        self.assertIn("test-sequence-fixtures.lock.json", names)
        self.assertIn("eprover-3.3.3.lock.json", names)

    def test_synthetic_solver_and_processors_emit_expected_results(self):
        bench_proc = SRC_BASE / "fixtures-src/benchproc/process"
        bp = subprocess.run([str(bench_proc), "/dev/null"], text=True, capture_output=True, check=True)
        self.assertIn("starexec-valid=true", bp.stdout)
        self.assertIn("test-attribute=test", bp.stdout)

        solver = SRC_BASE / "fixtures-src/CVC4/bin/starexec_run_default"
        postproc = SRC_BASE / "postproc.sh"
        with tempfile.TemporaryDirectory(prefix="ts-fx-run-") as tmp:
            for expected in ("sat", "unsat", "unknown"):
                bench = SRC_BASE / f"fixtures-src/benchmarks/test/{expected}.smt2"
                solver_run = subprocess.run([str(solver), str(bench)], text=True, capture_output=True, check=True)
                self.assertEqual(solver_run.stdout.strip(), expected)

                out_file = Path(tmp) / f"{expected}.out"
                out_file.write_text(solver_run.stdout, encoding="utf-8")
                post_run = subprocess.run([str(postproc), str(out_file)], text=True, capture_output=True, check=True)
                self.assertEqual(post_run.stdout.strip(), f"starexec-result={expected}")


if __name__ == "__main__":
    unittest.main()
