"""Offline deterministic tests for TPTPBenchmarkProcessor and SZSPostProcessor."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCIENTIFIC_DIR = ROOT / "scripts/scientific"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verifier = load_module("verify_fixtures", SCIENTIFIC_DIR / "verify_fixtures.py")
tptp_builder = load_module("build_tptp_processor", SCIENTIFIC_DIR / "build_tptp_processor.py")
szs_builder = load_module("build_szs_processor", SCIENTIFIC_DIR / "build_szs_processor.py")


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def parse_properties(stdout):
    props = {}
    for line in stdout.splitlines():
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        key, value = line.split("=", 1)
        props[key] = value
    return props


def make_tar_gz(path, entries):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for member_name, content in entries.items():
            info = tarfile.TarInfo(member_name)
            if content is None:
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                tar.addfile(info)
            else:
                payload = content.encode("utf-8") if isinstance(content, str) else content
                info.size = len(payload)
                info.mode = 0o755 if member_name.endswith(".sh") else 0o644
                tar.addfile(info, io.BytesIO(payload))
    data = buf.getvalue()
    Path(path).write_bytes(data)
    return sha256_hex(data)


STUB_TPTP4X = """#!/bin/sh
set -eu
if [ "${1:-}" = "-v" ]; then
    echo "TPTP4X v8.1.0.1"
    exit 0
fi
if [ "${1:-}" = "-q2" ]; then
    shift
fi
target=${1:-}
case "$target" in
    -*)
        echo "ERROR: option-like path reached tptp4X: $target"
        exit 1
        ;;
esac
clean=$(tr -d '\\r' < "$target")
if printf '%s\\n' "$clean" | grep -q 'INVALID_SYNTAX\\|Hello world\\|(p(a) & )'; then
    echo "ERROR: Line 1 Char 1 : Invalid annotated formula type, exiting"
    exit 1
fi
# Emit normalized non-comment TPTP statements just like real tptp4X -q2.
printf '%s\\n' "$clean" | grep -E '^(%|include|fof|cnf|tff|thf|tcf|tpi)' || true
exit 0
"""


class TPTPBenchmarkProcessorTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory(prefix="tptp-proc-test-")
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.pkg_dir = self.root / "pkg"
        (self.pkg_dir / "bin").mkdir(parents=True)
        self.proc = self.pkg_dir / "process"
        shutil.copyfile(tptp_builder.SCRIPT, self.proc)
        self.proc.chmod(0o755)
        self.tptp4x = self.pkg_dir / "bin/tptp4X"
        self.tptp4x.write_text(STUB_TPTP4X, encoding="utf-8")
        self.tptp4x.chmod(0o755)

    def run_proc(self, *args):
        completed = subprocess.run(
            ["/bin/sh", "-eu", str(self.proc), *args],
            cwd=self.pkg_dir,
            env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return parse_properties(completed.stdout)

    def test_valid_fof_problem_with_headers_and_no_includes(self):
        bench = self.root / "SYN916+1.p"
        bench.write_text(
            "% Status   : Theorem\n"
            "% SPC      : FOF_THM_RFO_NEQ\n"
            "fof(ax, axiom, p(a)).\n"
            "fof(conj, conjecture, ? [X] : p(X)).\n",
            encoding="utf-8",
        )
        props = self.run_proc(str(bench))
        self.assertEqual(props["starexec-valid"], "true")
        self.assertEqual(props["Status"], "Theorem")
        self.assertEqual(props["starexec-expected-result"], "Theorem")
        self.assertEqual(props["SPC"], "FOF_THM_RFO_NEQ")
        self.assertEqual(props["starexec-dependencies"], "0")

    def test_valid_axiom_without_status_and_deduplicated_includes(self):
        bench = self.root / "SET001+1.p"
        bench.write_bytes(
            b"% Status : CounterSatisfiable\r\n"
            b"% include('Axioms/COMMENTED_OUT.ax').\r\n"
            b"include('Axioms/SET001+0.ax').\r\n"
            b"include('Axioms/SET001+0.ax', [subset]).\r\n"
            b"include('Axioms/SET002+0.ax').\r\n"
            b"cnf(c1, negated_conjecture, ~p(a)).\r\n"
        )
        props = self.run_proc(str(bench))
        self.assertEqual(props["starexec-valid"], "true")
        self.assertEqual(props["Status"], "CounterSatisfiable")
        self.assertEqual(props["starexec-expected-result"], "CounterSatisfiable")
        self.assertEqual(props["starexec-dependencies"], "2")
        self.assertEqual(props["starexec-dependency-1"], "Axioms/SET001+0.ax")
        self.assertEqual(props["starexec-dependency-2"], "Axioms/SET002+0.ax")

    def test_valid_axiom_only_file_without_status(self):
        ax = self.root / "SET001+0.ax"
        ax.write_text("tff(tp, type, p : $i > $o).\nfof(a1, axiom, p(a)).\n", encoding="utf-8")
        props = self.run_proc(str(ax))
        self.assertEqual(props["starexec-valid"], "true")
        self.assertNotIn("Status", props)
        self.assertNotIn("starexec-expected-result", props)
        self.assertEqual(props["starexec-dependencies"], "0")

    def test_leading_dash_filename_is_handled_safely(self):
        bench = self.pkg_dir / "-leading-dash.p"
        bench.write_text("fof(a, axiom, p).\n", encoding="utf-8")
        completed = subprocess.run(
            ["/bin/sh", "-eu", str(self.proc), "-leading-dash.p"],
            cwd=self.pkg_dir,
            env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        props = parse_properties(completed.stdout)
        self.assertEqual(props["starexec-valid"], "true")

    def test_rejects_invalid_text_syntax_empty_comment_only_and_nul(self):
        bad_text = self.root / "prose.p"
        bad_text.write_text("Hello world, arbitrary invalid text.\n", encoding="utf-8")
        self.assertEqual(self.run_proc(str(bad_text))["starexec-valid"], "false")

        broken = self.root / "broken.p"
        broken.write_text("fof(bad, axiom, (p(a) & )).\n", encoding="utf-8")
        props = self.run_proc(str(broken))
        self.assertEqual(props["starexec-valid"], "false")
        self.assertIn("tptp-syntax-error", props["starexec-validation-error"])

        comment_only = self.root / "comments.p"
        comment_only.write_text("% Status : Theorem\n% only comments here\n", encoding="utf-8")
        self.assertEqual(
            self.run_proc(str(comment_only))["starexec-validation-error"],
            "no-tptp-formulas-or-includes",
        )

        empty = self.root / "empty.p"
        empty.write_bytes(b"")
        self.assertEqual(self.run_proc(str(empty))["starexec-validation-error"], "empty-benchmark-file")

        nul = self.root / "nul.p"
        nul.write_bytes(b"fof(a, axiom, p).\x00\n")
        self.assertEqual(self.run_proc(str(nul))["starexec-validation-error"], "binary-nul-bytes-rejected")

    def test_rejects_unsafe_include_paths_and_missing_validator(self):
        for bad_inc in ("../secret.ax", "/etc/passwd", "Axioms\\SET001.ax", "Axioms/../etc/shadow"):
            with self.subTest(include=bad_inc):
                p = self.root / "badinc.p"
                p.write_text(f"include('{bad_inc}').\nfof(a, axiom, p).\n", encoding="utf-8")
                props = self.run_proc(str(p))
                self.assertEqual(props["starexec-valid"], "false")
                self.assertIn("unsafe-include-path", props["starexec-validation-error"])

        self.tptp4x.unlink()
        good = self.root / "good.p"
        good.write_text("fof(a, axiom, p).\n", encoding="utf-8")
        props = self.run_proc(str(good))
        self.assertEqual(props["starexec-valid"], "false")
        self.assertEqual(props["starexec-validation-error"], "missing-tptp4x-syntax-validator")

    def test_tptp_builder_determinism_and_source_verification(self):
        tptp4x_root = "TPTP4X-79a72402caf33b31b85ba043a42c47ecc2a15cb5"
        jjparser_root = "JJParser-b68947d144008b1dfcc4a28a7782b4aa358b58f2"
        tptp4x_tar = self.root / "tptp4x.tar.gz"
        jjparser_tar = self.root / "jjparser.tar.gz"

        # Synthetic source Makefile compiles a deterministic POSIX shell binary stub cleanly.
        makefile = (
            "all: tptp4X\n"
            "tptp4X:\n"
            "\t@printf '#!/bin/sh\\nif [ \"$${1:-}\" = \"-v\" ]; then echo \"TPTP4X v8.1.0.1\"; exit 0; fi\\necho \"fof(a,axiom,p).\"\\n' > tptp4X\n"
            "\t@chmod 755 tptp4X\n"
        )
        tptp4x_sha = make_tar_gz(tptp4x_tar, {
            f"{tptp4x_root}/Makefile": makefile,
            f"{tptp4x_root}/JJParser": None,
        })
        license_bytes = b"GNU GENERAL PUBLIC LICENSE Version 3\n"
        jjparser_sha = make_tar_gz(jjparser_tar, {
            f"{jjparser_root}/LICENSE": license_bytes,
        })

        base_lock = json.loads((SCIENTIFIC_DIR / "tptp-benchmark-processor.lock.json").read_text())
        patched_lock = dict(base_lock)
        patched_lock["sourceSha256"] = tptp4x_sha
        patched_lock["jjparserSha256"] = jjparser_sha
        patched_lock["copyingSha256"] = sha256_hex(license_bytes)
        lock_file = self.root / "tptp.lock.json"
        lock_file.write_text(json.dumps(patched_lock), encoding="utf-8")

        out1 = self.root / "tptp-1.zip"
        out2 = self.root / "tptp-2.zip"
        with mock.patch.object(tptp_builder, "LOCK", lock_file):
            res1 = tptp_builder.build(tptp4x_tar, jjparser_tar, self.root / "b1", out1)
            res2 = tptp_builder.build(tptp4x_tar, jjparser_tar, self.root / "b2", out2)

        self.assertEqual(res1["packageSha256"], res2["packageSha256"])
        self.assertEqual(out1.read_bytes(), out2.read_bytes())
        verified = verifier.verify_fixture_archive(out1, lock_path=lock_file, expected_sha256=res1["packageSha256"])
        self.assertEqual(verified["schemaVersion"], 1)

        # Reject tampered source archive
        tampered = self.root / "tampered.tar.gz"
        tampered.write_bytes(b"corrupt")
        with mock.patch.object(tptp_builder, "LOCK", lock_file):
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                tptp_builder.build(tampered, jjparser_tar, self.root / "b3", self.root / "tptp-3.zip")


class SZSPostProcessorTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory(prefix="szs-proc-test-")
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.proc = SCIENTIFIC_DIR / "szs_post_processor.sh"

    def run_szs(self, content):
        out_file = self.root / "solver output.txt"
        if isinstance(content, bytes):
            out_file.write_bytes(content)
        else:
            out_file.write_text(content, encoding="utf-8")
        completed = subprocess.run(
            ["/bin/sh", "-eu", str(self.proc), str(out_file)],
            cwd=self.root,
            env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return parse_properties(completed.stdout)

    def test_script_has_zero_python_or_perl_dependencies(self):
        text = self.proc.read_text(encoding="utf-8")
        # Ignore explanatory comment lines; executable body must never invoke python/perl.
        code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
        self.assertNotIn("python", code.lower())
        self.assertNotIn("perl", code.lower())

    def test_single_szs_statuses_and_output_markers(self):
        for status in (
            "Theorem", "CounterSatisfiable", "Unsatisfiable", "Satisfiable",
            "ContradictoryAxioms", "Timeout", "GaveUp", "ResourceOut", "Unknown",
        ):
            with self.subTest(status=status):
                props = self.run_szs(
                    f"% Initializing solver\n% SZS status {status} for SYN916+1\n"
                )
                self.assertEqual(props["starexec-result"], status)
                self.assertEqual(props["szs-status-count"], "1")
                self.assertEqual(props["szs-multi-status-policy"], "consistent")
                self.assertEqual(props["szs-observed-statuses"], status)
                self.assertEqual(props["szs-has-output"], "false")
                self.assertEqual(props["szs-output-balanced"], "true")

    def test_crlf_and_balanced_szs_output_block(self):
        props = self.run_szs(
            b"% SZS status Theorem for SYN916+1\r\n"
            b"% SZS output start CNFRefutation for SYN916+1\r\n"
            b"cnf(c_0_1, plain, ($false)).\r\n"
            b"% SZS output end CNFRefutation for SYN916+1\r\n"
        )
        self.assertEqual(props["starexec-result"], "Theorem")
        self.assertEqual(props["szs-has-output"], "true")
        self.assertEqual(props["szs-output-balanced"], "true")
        self.assertEqual(props["szs-output-types"], "CNFRefutation")

    def test_repeated_identical_szs_lines_stay_consistent(self):
        props = self.run_szs(
            "% SZS status Theorem for SYN916+1\n"
            "% Proof complete\n"
            "% SZS status Theorem\n"
        )
        self.assertEqual(props["starexec-result"], "Theorem")
        self.assertEqual(props["szs-status-count"], "2")
        self.assertEqual(props["szs-multi-status-policy"], "consistent")
        self.assertEqual(props["szs-observed-statuses"], "Theorem")

    def test_conflicting_szs_lines_never_silently_overwrite(self):
        props = self.run_szs(
            "% SZS status Theorem for SYN916+1\n"
            "% SZS status CounterSatisfiable for SYN916+1\n"
        )
        self.assertEqual(props["starexec-result"], "Error")
        self.assertEqual(props["szs-status-count"], "2")
        self.assertEqual(props["szs-multi-status-policy"], "conflict")
        self.assertEqual(props["szs-observed-statuses"], "Theorem,CounterSatisfiable")

    def test_missing_or_invalid_szs_status_and_unbalanced_output(self):
        silent = self.run_szs("No SZS output here.\n")
        self.assertEqual(silent["starexec-result"], "starexec-unknown")
        self.assertEqual(silent["szs-status-count"], "0")
        self.assertEqual(silent["szs-multi-status-policy"], "consistent")
        self.assertEqual(silent["szs-observed-statuses"], "none")

        bogus = self.run_szs(
            "% SZS status NotInOntology\n"
            "% SZS output start Proof\n"
        )
        self.assertEqual(bogus["starexec-result"], "Error")
        self.assertEqual(bogus["szs-multi-status-policy"], "invalid")
        self.assertEqual(bogus["szs-observed-statuses"], "NotInOntology")
        self.assertEqual(bogus["szs-has-output"], "true")
        self.assertEqual(bogus["szs-output-balanced"], "false")
        self.assertEqual(bogus["szs-output-types"], "Proof")

    def test_szs_builder_determinism_and_verified_archive_execution(self):
        out1 = self.root / "SZSPostProcessor-1.zip"
        out2 = self.root / "SZSPostProcessor-2.zip"
        res1 = szs_builder.build(out1)
        res2 = szs_builder.build(out2)
        self.assertEqual(res1["packageSha256"], res2["packageSha256"])
        self.assertEqual(out1.read_bytes(), out2.read_bytes())

        lock_path = SCIENTIFIC_DIR / "szs-post-processor.lock.json"
        verified = verifier.verify_fixture_archive(out1, lock_path=lock_path, expected_sha256=res1["packageSha256"])
        self.assertEqual(verified["memberCount"], 5)

        unpacked = self.root / "unpacked"
        unpacked.mkdir()
        with zipfile.ZipFile(out1, "r") as zf:
            for info in zf.infolist():
                dst = unpacked / info.filename
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(zf.read(info.filename))
                if (info.external_attr >> 16) & stat.S_IXUSR:
                    dst.chmod(0o755)
        solver_out = self.root / "out.txt"
        solver_out.write_text("% SZS status Unsatisfiable\n", encoding="utf-8")
        completed = subprocess.run(
            [str(unpacked / "process"), str(solver_out)],
            cwd=unpacked,
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        )
        self.assertEqual(parse_properties(completed.stdout)["starexec-result"], "Unsatisfiable")


if __name__ == "__main__":
    unittest.main()
