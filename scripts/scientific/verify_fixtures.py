#!/usr/bin/env python3
"""Verify scientific fixture lockfiles, manifests, SHA-256 digests, and ZIP safety."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile


HERE = Path(__file__).resolve().parent
SCHEMA_VERSION = 1
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_JSON_BYTES = 1024 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

LEGACY_REJECTED_SHA256 = {
    "329b3d910c3215cc79450ae74589cd82993c517cc3cb536261e9ca7a33bd0965": (
        "Legacy E---3.3.0.zip (contains mislabeled E 3.0.0 binaries with "
        "--auto-schedule=8 and no verifiable provenance; see issue #288)"
    ),
}


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def check_not_legacy_rejected(digest, label="artifact"):
    reason = LEGACY_REJECTED_SHA256.get(digest)
    if reason is not None:
        raise ValueError(f"Rejected legacy {label} ({digest}): {reason}")


def validate_sha256_hex(value, field):
    if not isinstance(value, str) or not SHA256_RE.match(value):
        raise ValueError(f"{field} must be a 64-character lowercase hex SHA-256 digest")
    check_not_legacy_rejected(value, field)


def validate_relative_posix_path(raw_path, single_component=False):
    if not isinstance(raw_path, str) or not raw_path or "\x00" in raw_path:
        raise ValueError(f"Invalid path value: {raw_path!r}")
    if "\\" in raw_path or raw_path.startswith("/"):
        raise ValueError(f"Path must be relative POSIX without leading slash: {raw_path!r}")
    parts = raw_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"Unsafe or non-normalized path component in {raw_path!r}")
    if single_component and len(parts) != 1:
        raise ValueError(f"Expected single path component, got {raw_path!r}")
    return parts


def read_regular_file(path, max_bytes=MAX_FILE_BYTES):
    """Read a regular non-symlink file using O_NOFOLLOW."""
    candidate = Path(path)
    for parent in [candidate, *candidate.parents]:
        if parent.is_symlink():
            raise ValueError(f"Refusing symlink component in path: {parent}")
    descriptor = os.open(candidate, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as handle:
        mode = os.fstat(handle.fileno()).st_mode
        if not stat.S_ISREG(mode):
            raise ValueError(f"Path must be a regular file: {candidate}")
        data = handle.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError(f"File exceeds size limit ({max_bytes} bytes): {candidate}")
    return data


def verify_regular_file(path, expected_sha256=None):
    data = read_regular_file(path)
    digest = sha256_bytes(data)
    check_not_legacy_rejected(digest, f"file {Path(path).name}")
    if expected_sha256 is not None:
        validate_sha256_hex(expected_sha256, "expectedSha256")
        if digest != expected_sha256:
            raise ValueError(f"SHA-256 mismatch for {path}: expected {expected_sha256}, got {digest}")
    return digest, data


def validate_lock_data(lock):
    if not isinstance(lock, dict):
        raise ValueError("Lockfile root must be a JSON object")
    schema_version = lock.get("schemaVersion")
    if isinstance(schema_version, bool) or schema_version != SCHEMA_VERSION:
        raise ValueError(f"Unsupported lock schemaVersion: {schema_version!r}")
    version = lock.get("version")
    if not isinstance(version, str) or not version.strip():
        raise ValueError("Lockfile must define a non-empty version")
    validate_sha256_hex(lock.get("sourceSha256"), "sourceSha256")
    if "copyingSha256" in lock:
        validate_sha256_hex(lock.get("copyingSha256"), "copyingSha256")
    if "sourceRoot" in lock:
        validate_relative_posix_path(lock["sourceRoot"], single_component=True)
    if "configuration" in lock:
        validate_relative_posix_path(lock["configuration"], single_component=True)
    if "solverWorkers" in lock:
        workers = lock["solverWorkers"]
        if isinstance(workers, bool) or not isinstance(workers, int) or workers < 1:
            raise ValueError("solverWorkers must be a positive integer")
    if "sourceUrl" in lock:
        url = lock["sourceUrl"]
        if not isinstance(url, str) or not url.startswith("https://"):
            raise ValueError("sourceUrl must use https://")
    return lock


def verify_lock_file(lock_path):
    raw = read_regular_file(lock_path, max_bytes=MAX_JSON_BYTES)
    lock = json.loads(raw.decode("utf-8"))
    validate_lock_data(lock)
    return lock, sha256_bytes(raw)


def verify_fixture_archive(archive_path, lock_path=None, expected_sha256=None):
    archive_digest, _ = verify_regular_file(archive_path, expected_sha256=expected_sha256)
    expected_lock = None
    if lock_path is not None:
        expected_lock, _ = verify_lock_file(lock_path)

    with zipfile.ZipFile(archive_path, "r") as zf:
        bad_member = zf.testzip()
        if bad_member is not None:
            raise ValueError(f"Corrupt ZIP CRC for member {bad_member}")

        infos = zf.infolist()
        seen = set()
        for info in infos:
            validate_relative_posix_path(info.filename)
            if info.filename in seen:
                raise ValueError(f"Duplicate ZIP member: {info.filename}")
            seen.add(info.filename)
            unix_mode = info.external_attr >> 16
            if stat.S_ISLNK(unix_mode):
                raise ValueError(f"Refusing symlink member in ZIP: {info.filename}")
            file_type = stat.S_IFMT(unix_mode)
            if file_type not in (0, stat.S_IFREG):
                raise ValueError(f"Unsupported non-regular member mode in ZIP: {info.filename}")

        if "fixture-manifest.json" not in seen:
            raise ValueError("Missing required fixture-manifest.json in archive")

        manifest = json.loads(zf.read("fixture-manifest.json").decode("utf-8"))
        if not isinstance(manifest, dict) or manifest.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError("Unsupported or missing schemaVersion in fixture-manifest.json")

        manifest_files = manifest.get("files")
        if not isinstance(manifest_files, dict) or not manifest_files:
            raise ValueError("fixture-manifest.json must declare a non-empty files map")

        declared = set(manifest_files.keys()) | {"fixture-manifest.json"}
        if declared != seen:
            missing = sorted(declared - seen)
            extra = sorted(seen - declared)
            raise ValueError(f"Manifest/archive member mismatch (missing={missing}, extra={extra})")

        for member_name, meta in manifest_files.items():
            validate_relative_posix_path(member_name)
            if not isinstance(meta, dict):
                raise ValueError(f"Invalid manifest entry for {member_name}")
            validate_sha256_hex(meta.get("sha256"), f"files[{member_name!r}].sha256")
            content = zf.read(member_name)
            actual_digest = sha256_bytes(content)
            check_not_legacy_rejected(actual_digest, f"ZIP member {member_name}")
            if actual_digest != meta["sha256"]:
                raise ValueError(f"Member SHA-256 mismatch for {member_name}")
            if len(content) != meta.get("size"):
                raise ValueError(f"Member size mismatch for {member_name}")
            info = zf.getinfo(member_name)
            actual_exec = bool((info.external_attr >> 16) & 0o111)
            if actual_exec != bool(meta.get("executable")):
                raise ValueError(f"Member executable permission mismatch for {member_name}")

        manifest_source = manifest.get("source")
        if manifest_source is not None:
            validate_lock_data(manifest_source)
        if expected_lock is not None:
            if manifest_source != expected_lock:
                raise ValueError("Archive fixture-manifest.json source does not match lockfile")
            expected_ver = expected_lock.get("expectedVersionOutput")
            if expected_ver is not None and manifest.get("reportedVersion") != expected_ver:
                raise ValueError("Archive reportedVersion does not match lockfile expectedVersionOutput")

    return {
        "archive": str(archive_path),
        "packageSha256": archive_digest,
        "memberCount": len(seen),
        "schemaVersion": SCHEMA_VERSION,
    }


def verify_lock_directory(directory=HERE):
    locks = sorted(Path(directory).glob("*.lock.json"))
    if not locks:
        raise ValueError(f"No *.lock.json files found under {directory}")
    results = []
    for lock_path in locks:
        lock, digest = verify_lock_file(lock_path)
        config = lock.get("configuration")
        if config:
            wrapper = Path(directory) / f"starexec_run_{config}"
            if wrapper.exists() or wrapper.is_symlink():
                verify_regular_file(wrapper)
        results.append({
            "lock": lock_path.name,
            "sha256": digest,
            "version": lock["version"],
            "sourceSha256": lock["sourceSha256"],
        })
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Verify a built fixture ZIP against its manifest.")
    parser.add_argument("--file", type=Path, help="Verify a regular file and reject legacy blocked hashes.")
    parser.add_argument("--lock", type=Path, help="Optional *.lock.json to check individually or against --archive.")
    parser.add_argument("--expected-sha256", help="Optional expected SHA-256 for --archive or --file.")
    parser.add_argument("--locks-dir", type=Path, default=HERE, help="Directory of *.lock.json files to validate.")
    args = parser.parse_args()

    try:
        if args.file is not None:
            digest, data = verify_regular_file(args.file, expected_sha256=args.expected_sha256)
            output = {"file": str(args.file), "sha256": digest, "bytes": len(data)}
        elif args.archive is not None:
            output = verify_fixture_archive(args.archive, lock_path=args.lock, expected_sha256=args.expected_sha256)
        elif args.lock is not None:
            lock, digest = verify_lock_file(args.lock)
            output = {"lock": str(args.lock), "sha256": digest, "version": lock["version"]}
        else:
            output = {"locks": verify_lock_directory(args.locks_dir)}
    except (OSError, ValueError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as error:
        print(f"Fixture verification failed: {error}", file=sys.stderr)
        return 1

    print(json.dumps(output, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
