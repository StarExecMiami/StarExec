#!/usr/bin/env python3
"""Deterministically generate TestSequence ZIP fixtures from tracked plain-text sources."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_LOCK = HERE / "test-sequence-fixtures.lock.json"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "starexec-app/target/test-classes/org/starexec/test/resources"

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import verify_fixtures as vf  # noqa: E402


def build_deterministic_zip_bytes(members):
    """Build deterministic uncompressed ZIP bytes (sorted members, 1980-01-01, normalized Unix modes)."""
    buf_dir = Path(tempfile.gettempdir())
    with tempfile.NamedTemporaryFile(dir=buf_dir, suffix=".zip") as tmp:
        with zipfile.ZipFile(tmp.name, "w", compression=zipfile.ZIP_STORED) as archive:
            for member_name in sorted(members):
                vf.validate_relative_posix_path(member_name)
                data, executable = members[member_name]
                info = zipfile.ZipInfo(member_name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
                archive.writestr(info, data)
        return Path(tmp.name).read_bytes()


def write_atomic(target_path, data):
    target_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(dir=target_path.parent, delete=False) as tmp:
            tmp_path = Path(tmp.name)
            tmp.write(data)
        os.chmod(tmp_path, 0o644)
        os.replace(tmp_path, target_path)
    finally:
        if tmp_path is not None and tmp_path.exists():
            tmp_path.unlink()


def canonical_sources_digest(fixtures):
    canonical = {}
    for pkg_name in sorted(fixtures):
        members = fixtures[pkg_name]["members"]
        canonical[pkg_name] = {
            m: {
                "source": members[m]["source"],
                "sha256": members[m]["sha256"],
                "size": members[m]["size"],
                "executable": bool(members[m]["executable"]),
            }
            for m in sorted(members)
        }
    encoded = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def generate_all(lock_path, output_dir, repo_root=REPO_ROOT):
    lock, _ = vf.verify_lock_file(lock_path, repo_root=repo_root)
    fixtures = lock.get("fixtures")
    if not isinstance(fixtures, dict) or not fixtures:
        raise ValueError("Lockfile must define non-empty fixtures map")

    results = {}
    for pkg_name in sorted(fixtures):
        vf.validate_relative_posix_path(pkg_name, single_component=True)
        pkg_spec = fixtures[pkg_name]
        expected_pkg_sha = pkg_spec.get("packageSha256")
        vf.validate_sha256_hex(expected_pkg_sha, f"fixtures[{pkg_name!r}].packageSha256")

        member_data = {}
        for member_name, meta in sorted(pkg_spec["members"].items()):
            vf.validate_relative_posix_path(meta["source"])
            src_path = Path(repo_root) / meta["source"]
            actual_sha, raw = vf.verify_regular_file(src_path, expected_sha256=meta["sha256"])
            if len(raw) != meta["size"]:
                raise ValueError(f"Source size mismatch for {meta['source']}")
            member_data[member_name] = (raw, bool(meta["executable"]))

        zip_bytes = build_deterministic_zip_bytes(member_data)
        actual_pkg_sha = vf.sha256_bytes(zip_bytes)
        vf.check_not_legacy_rejected(actual_pkg_sha, f"generated {pkg_name}")
        if actual_pkg_sha != expected_pkg_sha:
            raise ValueError(
                f"Generated archive SHA-256 mismatch for {pkg_name}: "
                f"expected {expected_pkg_sha}, got {actual_pkg_sha}"
            )

        out_path = Path(output_dir) / pkg_name
        write_atomic(out_path, zip_bytes)
        results[pkg_name] = {"path": str(out_path), "sha256": actual_pkg_sha, "bytes": len(zip_bytes)}
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()
    try:
        results = generate_all(args.lock, args.output_dir, repo_root=args.repo_root)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"Fixture generation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"generated": results}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
