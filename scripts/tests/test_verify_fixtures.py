"""Offline regressions for scripts/scientific/verify_fixtures.py."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCIENTIFIC_DIR = ROOT / "scripts/scientific"

VERIFY_SPEC = importlib.util.spec_from_file_location(
    "verify_fixtures", SCIENTIFIC_DIR / "verify_fixtures.py"
)
verifier = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(verifier)

BUILD_SPEC = importlib.util.spec_from_file_location(
    "build_eprover_fixture", SCIENTIFIC_DIR / "build_eprover_fixture.py"
)
builder = importlib.util.module_from_spec(BUILD_SPEC)
BUILD_SPEC.loader.exec_module(builder)

LEGACY_E330_SHA256 = "329b3d910c3215cc79450ae74589cd82993c517cc3cb536261e9ca7a33bd0965"


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


class VerifyFixturesTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory(prefix="verify-fixtures-test-")
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)

    def _build_valid_archive(self, archive_path):
        lock_bytes = (SCIENTIFIC_DIR / "eprover-3.3.3.lock.json").read_bytes()
        lock = json.loads(lock_bytes)
        wrapper_bytes = (SCIENTIFIC_DIR / "starexec_run_E-3.3.3-sequential").read_bytes()
        binary_bytes = b"#!/bin/sh\necho stub\n"
        files = {
            "bin/eprover-ho": (binary_bytes, True),
            "bin/starexec_run_E-3.3.3-sequential": (wrapper_bytes, True),
            "COPYING": (b"license notice\n", False),
            "recipe/eprover-3.3.3.lock.json": (lock_bytes, False),
        }
        manifest = {
            "schemaVersion": 1,
            "source": lock,
            "reportedVersion": lock["expectedVersionOutput"],
            "qualification": "LOCAL_BUILD_ONLY",
            "files": {
                name: {
                    "sha256": sha256_hex(data),
                    "size": len(data),
                    "executable": executable,
                }
                for name, (data, executable) in sorted(files.items())
            },
        }
        files["fixture-manifest.json"] = (
            (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"),
            False,
        )
        builder.write_package(archive_path, files)
        return sha256_hex(archive_path.read_bytes())

    def test_repo_lock_directory_passes(self):
        results = verifier.verify_lock_directory(SCIENTIFIC_DIR)
        by_name = {item["lock"]: item for item in results}
        self.assertIn("eprover-3.3.3.lock.json", by_name)
        self.assertEqual(by_name["eprover-3.3.3.lock.json"]["version"], "3.3.3")
        self.assertIn("test-sequence-fixtures.lock.json", by_name)
        self.assertEqual(by_name["test-sequence-fixtures.lock.json"]["version"], "1.0.0")

    def test_valid_fixture_archive_passes_against_lock(self):
        archive = self.root / "E-3.3.3-sequential.zip"
        digest = self._build_valid_archive(archive)
        summary = verifier.verify_fixture_archive(
            archive,
            lock_path=SCIENTIFIC_DIR / "eprover-3.3.3.lock.json",
            expected_sha256=digest,
        )
        self.assertEqual(summary["packageSha256"], digest)
        self.assertEqual(summary["memberCount"], 5)

    def test_rejects_legacy_e330_sha256_everywhere(self):
        # 1. Rejected via validate_sha256_hex / lockfile validation
        with self.assertRaisesRegex(ValueError, r"E---3\.3\.0\.zip"):
            verifier.validate_lock_data({
                "schemaVersion": 1,
                "version": "3.3.0",
                "sourceSha256": LEGACY_E330_SHA256,
            })

        # 2. Rejected via regular file verification and CLI --file
        dummy = self.root / "E---3.3.0.zip"
        dummy.write_bytes(b"simulated legacy archive")
        with mock.patch.object(verifier, "sha256_bytes", return_value=LEGACY_E330_SHA256):
            with self.assertRaisesRegex(ValueError, r"E---3\.3\.0\.zip"):
                verifier.verify_regular_file(dummy)

            stderr = io.StringIO()
            with mock.patch.object(sys, "argv", ["verify_fixtures.py", "--file", str(dummy)]), \
                 contextlib.redirect_stderr(stderr):
                rc = verifier.main()
            self.assertEqual(rc, 1)
            self.assertIn("E---3.3.0.zip", stderr.getvalue())

    def test_rejects_symlinks_and_path_traversal(self):
        target = self.root / "real.json"
        target.write_text('{"schemaVersion":1}\n', encoding="utf-8")
        link = self.root / "link.json"
        link.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "symlink"):
            verifier.verify_regular_file(link)

        for bad_path in ("../escape", "/abs/path", "sub\\win", "dir/./file", "dir/../out"):
            with self.subTest(bad_path=bad_path):
                with self.assertRaises(ValueError):
                    verifier.validate_relative_posix_path(bad_path)

    def test_rejects_zip_traversal_symlink_member_or_tamper(self):
        # Zip member with traversal
        bad_zip = self.root / "traversal.zip"
        with zipfile.ZipFile(bad_zip, "w") as zf:
            zf.writestr("../evil.txt", b"x")
        with self.assertRaisesRegex(ValueError, "Unsafe or non-normalized"):
            verifier.verify_fixture_archive(bad_zip)

        # Zip member with symlink bit
        sym_zip = self.root / "symlink.zip"
        with zipfile.ZipFile(sym_zip, "w") as zf:
            info = zipfile.ZipInfo("bin/link")
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            zf.writestr(info, b"/etc/passwd")
        with self.assertRaisesRegex(ValueError, "symlink"):
            verifier.verify_fixture_archive(sym_zip)

        # Undocumented extra member in otherwise valid archive
        valid = self.root / "valid.zip"
        self._build_valid_archive(valid)
        extra_zip = self.root / "extra.zip"
        with zipfile.ZipFile(valid, "r") as src, zipfile.ZipFile(extra_zip, "w") as dst:
            for item in src.infolist():
                dst.writestr(item, src.read(item.filename))
            dst.writestr("extra.txt", b"surprise")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            verifier.verify_fixture_archive(extra_zip)

        # Tampered manifest digest
        tampered_zip = self.root / "tampered.zip"
        with zipfile.ZipFile(valid, "r") as src, zipfile.ZipFile(tampered_zip, "w") as dst:
            for item in src.infolist():
                data = b"tampered\n" if item.filename == "COPYING" else src.read(item.filename)
                dst.writestr(item, data)
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            verifier.verify_fixture_archive(tampered_zip)

    def test_rejects_invalid_lock_schema_fields(self):
        for bad_lock in (
            {"schemaVersion": 2, "version": "3.3.3", "sourceSha256": "a" * 64},
            {"schemaVersion": True, "version": "3.3.3", "sourceSha256": "a" * 64},
            {"schemaVersion": 1, "version": "", "sourceSha256": "a" * 64},
            {"schemaVersion": 1, "version": "3.3.3", "sourceSha256": "not-hex"},
            {"schemaVersion": 1, "version": "3.3.3", "sourceSha256": "a" * 64, "sourceRoot": "a/b"},
            {"schemaVersion": 1, "version": "3.3.3", "sourceSha256": "a" * 64, "sourceUrl": "http://example.com"},
            {"schemaVersion": 1, "version": "3.3.3", "sourceSha256": "a" * 64, "solverWorkers": 0},
        ):
            with self.subTest(bad_lock=bad_lock):
                with self.assertRaises(ValueError):
                    verifier.validate_lock_data(bad_lock)


if __name__ == "__main__":
    unittest.main()
