#!/usr/bin/env python3
"""Build a deterministic SZSPostProcessor ZIP from tracked repository sources."""

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
LOCK = HERE / "szs-post-processor.lock.json"
SCRIPT = HERE / "szs_post_processor.sh"

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import verify_fixtures as vf  # noqa: E402


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical_sources_digest(sources):
    canonical = {
        name: {
            "source": sources[name]["source"],
            "sha256": sources[name]["sha256"],
            "size": sources[name]["size"],
            "executable": bool(sources[name]["executable"]),
        }
        for name in sorted(sources)
    }
    return sha256(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def write_package(output, files):
    """Write deterministic uncompressed ZIP without replacing an existing path."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=Path(output).parent, delete=False) as destination:
            temporary = Path(destination.name)
            with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as archive:
                for name, (data, executable) in sorted(files.items()):
                    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    info.create_system = 3
                    info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
                    archive.writestr(info, data)
        os.link(temporary, output)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def build(output, lock_path=LOCK, repo_root=REPO_ROOT):
    lock, _ = vf.verify_lock_file(lock_path, repo_root=repo_root)
    lock_bytes = Path(lock_path).read_bytes()
    sources = lock.get("sources")
    if not isinstance(sources, dict) or not sources:
        raise ValueError("szs-post-processor.lock.json must define a non-empty sources map")

    files = {}
    for member_name, meta in sorted(sources.items()):
        vf.validate_relative_posix_path(member_name)
        vf.validate_relative_posix_path(meta["source"])
        src_path = Path(repo_root) / meta["source"]
        _, data = vf.verify_regular_file(src_path, expected_sha256=meta["sha256"])
        if len(data) != meta["size"]:
            raise ValueError(f"Source size mismatch for {meta['source']}")
        files[member_name] = (data, bool(meta["executable"]))

    files["recipe/szs-post-processor.lock.json"] = (lock_bytes, False)

    manifest = {
        "schemaVersion": 1,
        "source": lock,
        "reportedVersion": lock["expectedVersionOutput"],
        "qualification": "LOCAL_BUILD_ONLY",
        "files": {
            name: {"sha256": sha256(data), "size": len(data), "executable": executable}
            for name, (data, executable) in sorted(files.items())
        },
    }
    files["fixture-manifest.json"] = (
        (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        False,
    )

    output = Path(output).absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; choose a new package path")
    if not output.parent.is_dir():
        raise ValueError("Output parent directory must already exist")

    write_package(output, files)
    pkg_bytes = output.read_bytes()
    return {
        "packageSha256": sha256(pkg_bytes),
        "sourceSha256": lock["sourceSha256"],
        "reportedVersion": lock["expectedVersionOutput"],
        "qualification": manifest["qualification"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=LOCK)
    args = parser.parse_args()
    try:
        result = build(args.output, lock_path=args.lock)
    except (OSError, ValueError, KeyError) as error:
        print(f"SZS processor build rejected: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
