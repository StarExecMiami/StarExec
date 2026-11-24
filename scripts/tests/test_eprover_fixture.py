"""Offline fixture regressions; no download, compiler or real solver required."""

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
SPEC = importlib.util.spec_from_file_location(
    "fixture_builder", ROOT / "scripts/scientific/build_eprover_fixture.py"
)
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class WrapperTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="e-fixture-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin_dir = self.root / "solver with spaces" / "bin"
        self.bin_dir.mkdir(parents=True)
        self.wrapper = self.bin_dir / builder.WRAPPER.name
        shutil.copyfile(builder.WRAPPER, self.wrapper)
        self.wrapper.chmod(0o755)
        self.solver = self.bin_dir / "eprover-ho"
        self.solver.write_text(
            "#!/usr/bin/python3\n"
            "import json, os, sys\n"
            "print(json.dumps({'args': sys.argv[1:], "
            "'tptp': os.environ.get('TPTP'), "
            "'affinity': sorted(os.sched_getaffinity(0))}))\n"
            "sys.exit(int(os.environ.get('FIXTURE_TEST_EXIT', '0')))\n"
        )
        self.solver.chmod(0o755)
        self.benchmark = self.root / "problem $literal;`text`.p"
        self.benchmark.write_text("fof(a, axiom, p).\n")

    def run_wrapper(self, args=None, cpu="7", extra=None, affinity=None):
        env = {"PATH": "/usr/bin:/bin", "STAREXEC_WALLCLOCK_LIMIT": "99"}
        if cpu is not None:
            env["STAREXEC_CPU_LIMIT"] = cpu
        env.update(extra or {})
        return subprocess.run(
            ["/bin/sh", "-eu", str(self.wrapper), *(args or [str(self.benchmark)])],
            cwd=self.root, env=env, text=True, capture_output=True, timeout=5,
            preexec_fn=(lambda: os.sched_setaffinity(0, affinity)) if affinity else None,
        )

    def test_exact_sequential_arguments_and_cpu_time(self):
        result = self.run_wrapper(args=[str(self.benchmark), str(self.root / "output")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"], [
            "--auto-schedule=1", "--proof-object", "--print-statistics",
            "--print-version", "--cpu-limit=7", "--", str(self.benchmark),
        ])

    def test_missing_or_invalid_cpu_limit_rejected_before_solver(self):
        for cpu in (None, "", "0", "00", "01", "-1", "+1", "1.5", " 2", "2\n",
                    "1000000000", "Auto", "--help", "١"):
            with self.subTest(cpu=cpu):
                result = self.run_wrapper(cpu=cpu)
                self.assertEqual(result.returncode, 64)
                self.assertEqual(result.stdout, "")
                self.assertIn("STAREXEC_CPU_LIMIT", result.stderr)

    def test_supported_cpu_limits(self):
        for cpu in ("1", "999999999"):
            with self.subTest(cpu=cpu):
                result = self.run_wrapper(cpu=cpu)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"--cpu-limit={cpu}", json.loads(result.stdout)["args"])

    def test_option_like_benchmark_is_not_an_option(self):
        (self.root / "--help").write_text("fof(a, axiom, p).\n")
        result = self.run_wrapper(args=["--help"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"][-2:], ["--", "--help"])

    def test_invalid_argument_count_and_missing_benchmark(self):
        for args in (["missing"], [str(self.root)], [str(self.benchmark), "out", "extra"]):
            with self.subTest(args=args):
                result = self.run_wrapper(args=args)
                self.assertEqual(result.returncode, 64)
                self.assertEqual(result.stdout, "")
        result = subprocess.run(["/bin/sh", "-eu", str(self.wrapper)],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 64)

    def test_solver_exit_code_propagates(self):
        result = self.run_wrapper(extra={"FIXTURE_TEST_EXIT": "17"})
        self.assertEqual(result.returncode, 17)

    def test_missing_solver_fails(self):
        self.solver.unlink()
        self.assertEqual(self.run_wrapper().returncode, 127)

    def test_inherited_affinity_is_not_broadened(self):
        allowed = {min(os.sched_getaffinity(0))}
        result = self.run_wrapper(affinity=allowed)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["affinity"], sorted(allowed))

    def test_tptp_ancestor_axioms_fallback_and_preservation(self):
        tptp_root = self.root / "tptp-tree"
        (tptp_root / "Axioms").mkdir(parents=True)
        nested = tptp_root / "Problems" / "SET"
        nested.mkdir(parents=True)
        problem = nested / "SET014^4.p"
        problem.write_text("fof(a, axiom, p).\n")

        # 1. Discovers ancestor Axioms/ directory when TPTP is unset.
        discovered = self.run_wrapper(args=[str(problem)])
        self.assertEqual(discovered.returncode, 0, discovered.stderr)
        self.assertEqual(json.loads(discovered.stdout)["tptp"], str(tptp_root.resolve()))

        # 2. Preserves an explicit TPTP environment variable verbatim.
        preserved = self.run_wrapper(args=[str(problem)], extra={"TPTP": "/explicit/tptp"})
        self.assertEqual(preserved.returncode, 0, preserved.stderr)
        self.assertEqual(json.loads(preserved.stdout)["tptp"], "/explicit/tptp")

    def test_tptp_fallback_ignores_symlinked_axioms_directory(self):
        outside = self.root / "outside-axioms"
        outside.mkdir()
        parent = self.root / "hierarchy"
        child = parent / "Problems"
        child.mkdir(parents=True)
        (child / "Axioms").symlink_to(outside)
        problem = child / "SYN001+1.p"
        problem.write_text("fof(a, axiom, p).\n")

        # Symlinked Axioms under child must be skipped; no ancestor Axioms exists.
        without_real = self.run_wrapper(args=[str(problem)])
        self.assertEqual(without_real.returncode, 0, without_real.stderr)
        self.assertIsNone(json.loads(without_real.stdout)["tptp"])

        # Real non-symlink Axioms under parent is selected once present.
        (parent / "Axioms").mkdir()
        with_real = self.run_wrapper(args=[str(problem)])
        self.assertEqual(with_real.returncode, 0, with_real.stderr)
        self.assertEqual(json.loads(with_real.stdout)["tptp"], str(parent.resolve()))


class SourceAndPackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="e-fixture-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source.tar.gz"

    def test_exact_source_bytes_verified(self):
        data = b"fixture source bytes"
        self.source.write_bytes(data)
        self.assertEqual(builder.read_source(self.source, hashlib.sha256(data).hexdigest()), data)

    def test_wrong_source_fails_before_creating_build_or_output(self):
        self.source.write_bytes(b"not the pinned source")
        build_dir = self.root / "build"
        output = self.root / "fixture.zip"
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            builder.build(self.source, build_dir, output)
        self.assertFalse(build_dir.exists())
        self.assertFalse(output.exists())

    def test_source_size_is_bounded(self):
        self.source.write_bytes(b"x" * 9)
        with mock.patch.object(builder, "MAX_ARCHIVE_BYTES", 8):
            with self.assertRaisesRegex(ValueError, "size limit"):
                builder.read_source(self.source, "unused")

    def test_source_fifo_and_symlink_rejected_without_waiting(self):
        os.mkfifo(self.source)
        with self.assertRaisesRegex(ValueError, "regular file"):
            builder.read_source(self.source, "unused")
        self.source.unlink()
        target = self.root / "target"
        target.write_bytes(b"x")
        self.source.symlink_to(target)
        with self.assertRaises(OSError):
            builder.read_source(self.source, hashlib.sha256(b"x").hexdigest())

    def archive(self, entries):
        with tarfile.open(self.source, "w:gz") as archive:
            for name, kind in entries:
                member = tarfile.TarInfo(name)
                member.type = kind
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    member.linkname = "../../outside"
                member.size = 1 if kind == tarfile.REGTYPE else 0
                archive.addfile(member, io.BytesIO(b"x") if member.size else None)

    def test_unsafe_archive_is_rejected_before_extraction(self):
        for entries in (
            [("root/../outside", tarfile.REGTYPE)],
            [("/outside", tarfile.REGTYPE)],
            [("other/file", tarfile.REGTYPE)],
            [("root/link", tarfile.SYMTYPE)],
            [("root/link", tarfile.LNKTYPE)],
            [("root/pipe", tarfile.FIFOTYPE)],
            [("root/file", tarfile.REGTYPE), ("root/file", tarfile.REGTYPE)],
        ):
            with self.subTest(entries=entries):
                self.archive(entries)
                destination = self.root / "unpacked"
                with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
                    builder.extract_source(self.source, destination, "root")
                self.assertFalse(destination.exists())

    def test_unpacked_size_is_bounded(self):
        self.archive([("root/file", tarfile.REGTYPE)])
        with mock.patch.object(builder, "MAX_SOURCE_BYTES", 0):
            with self.assertRaisesRegex(ValueError, "Unpacked source"):
                builder.extract_source(self.source, self.root / "unpacked", "root")

    def test_regular_source_extracts(self):
        self.archive([("root", tarfile.DIRTYPE), ("root/file", tarfile.REGTYPE)])
        extracted = builder.extract_source(self.source, self.root / "unpacked", "root")
        self.assertEqual((extracted / "file").read_bytes(), b"x")

    def test_zip_is_deterministic_and_preserves_permissions(self):
        files = {"bin/solver": (b"binary", True), "COPYING": (b"license", False)}
        first = self.root / "a.zip"
        second = self.root / "b.zip"
        builder.write_package(first, files)
        builder.write_package(second, dict(reversed(list(files.items()))))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            self.assertEqual(archive.namelist(), sorted(files))
            for item in archive.infolist():
                self.assertEqual(item.date_time, (1980, 1, 1, 0, 0, 0))
                mode = item.external_attr >> 16
                self.assertTrue(stat.S_ISREG(mode))
                self.assertEqual(stat.S_IMODE(mode), 0o755 if files[item.filename][1] else 0o644)
                self.assertEqual(archive.read(item), files[item.filename][0])

    def test_existing_output_is_never_overwritten(self):
        output = self.root / "existing.zip"
        output.write_bytes(b"keep")
        with self.assertRaises(FileExistsError):
            builder.write_package(output, {"COPYING": (b"new", False)})
        self.assertEqual(output.read_bytes(), b"keep")
        self.assertEqual(list(self.root.iterdir()), [output])

    def test_failed_zip_write_does_not_publish_partial_output(self):
        output = self.root / "fixture.zip"
        with mock.patch.object(zipfile.ZipFile, "writestr", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(OSError, "disk full"):
                builder.write_package(output, {"COPYING": (b"license", False)})
        self.assertFalse(output.exists())
        self.assertEqual(list(self.root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
