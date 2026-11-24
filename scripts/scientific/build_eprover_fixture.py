#!/usr/bin/env python3
"""Build a pinned E source archive locally; never download or install software."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile


HERE = Path(__file__).resolve().parent
LOCK = HERE / "eprover-3.3.3.lock.json"
WRAPPER = HERE / "starexec_run_E-3.3.3-sequential"
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_SOURCE_BYTES = 128 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_source(path, expected_hash):
    """Verify the exact bytes that will be extracted and included in the ZIP."""
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError("Source archive must be a regular file")
        data = source.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Source archive exceeds the size limit")
    if sha256(data) != expected_hash:
        raise ValueError("Source archive SHA-256 does not match the reviewed lock")
    return data


def extract_source(archive, destination, root):
    """Reject unsafe members before writing any source files."""
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        names = set()
        total = 0
        for member in members:
            name = PurePosixPath(member.name)
            if (name.is_absolute() or ".." in name.parts or not name.parts
                    or name.parts[0] != root or member.name in names
                    or not (member.isfile() or member.isdir())):
                raise ValueError("Unsafe or duplicate source archive member")
            names.add(member.name)
            total += member.size
            if total > MAX_SOURCE_BYTES:
                raise ValueError("Unpacked source exceeds the size limit")
        source.extractall(destination, members=members, filter="data")
    return destination / root


def run_checked(argv, cwd, env, log_name, seconds=900):
    # GNU timeout bounds the whole process group, including compiler children.
    with (cwd / log_name).open("xb") as log:
        subprocess.run(
            ["timeout", "--kill-after=5s", f"{seconds}s", *argv],
            cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True,
        )


def command_output(argv, cwd, env):
    return subprocess.check_output(
        argv, cwd=cwd, env=env, stderr=subprocess.STDOUT, timeout=10,
        text=True, encoding="utf-8",
    ).strip()


def write_package(output, files):
    """Use stable ordering, permissions and timestamps; refuse to replace files."""
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
        # Publish only the complete ZIP, atomically and without replacing a path.
        os.link(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink()


def build(source_archive, build_dir, output):
    if sys.version_info < (3, 12):
        raise ValueError("Python 3.12 or newer is required for safe tar extraction")
    lock_bytes = LOCK.read_bytes()
    lock = json.loads(lock_bytes)
    archive_bytes = read_source(source_archive, lock["sourceSha256"])
    output = Path(output).absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; choose a new package path")
    if not output.parent.is_dir():
        raise ValueError("Output parent directory must already exist")
    build_dir = Path(build_dir).absolute()
    # Upstream makefiles use unquoted paths. Fail before invoking them.
    if any(not (char.isascii() and (char.isalnum() or char in "/._-"))
           for char in str(build_dir)):
        raise ValueError("Build path must contain only ASCII letters, digits, /._-")
    for tool in ("gcc", "make", "gawk", "timeout", "ld"):
        if not shutil.which(tool, path="/usr/bin:/bin"):
            raise ValueError(f"Required build tool is missing: {tool}")
    build_dir.mkdir(parents=True, exist_ok=False)
    verified_archive = build_dir / "E-3.3.3-source.tar.gz"
    verified_archive.write_bytes(archive_bytes)
    root = extract_source(verified_archive, build_dir, lock["sourceRoot"])
    copying = (root / "COPYING").read_bytes()
    if sha256(copying) != lock["copyingSha256"]:
        raise ValueError("Upstream COPYING does not match the reviewed lock")
    embedded = (root / "PROVER/e_gitcommit.h").read_text().strip()
    if embedded != f'#define ECOMMITID "{lock["upstreamEmbeddedCommit"]}"':
        raise ValueError("Unexpected upstream embedded revision")
    env = {
        "PATH": "/usr/bin:/bin", "LC_ALL": "C", "TZ": "UTC",
        "SOURCE_DATE_EPOCH": str(lock["sourceDateEpoch"]),
        "GIT_CEILING_DIRECTORIES": str(build_dir),
    }
    run_checked(["./configure", "--enable-ho"], root, env, "fixture-configure.log")
    run_checked(["make", "-j2", "rebuild"], root, env, "fixture-build.log")
    binary = root / "PROVER/eprover-ho"
    version = command_output([str(binary), "--version"], root, env)
    if version != lock["expectedVersionOutput"]:
        raise ValueError("Built solver version does not match the reviewed lock")
    if (root / "PROVER/e_gitcommit.h").read_text().strip() != embedded:
        raise ValueError("Build changed the upstream embedded revision")
    files = {
        "bin/eprover-ho": (binary.read_bytes(), True),
        f'bin/starexec_run_{lock["configuration"]}': (WRAPPER.read_bytes(), True),
        "COPYING": (copying, False),
        "source/E-3.3.3-source.tar.gz": (archive_bytes, False),
        "recipe/build_eprover_fixture.py": (Path(__file__).read_bytes(), False),
        "recipe/eprover-3.3.3.lock.json": (lock_bytes, False),
        f"recipe/{WRAPPER.name}": (WRAPPER.read_bytes(), False),
    }
    manifest = {
        "schemaVersion": 1, "source": lock, "reportedVersion": version,
        "qualification": "LOCAL_BUILD_ONLY",
        "cpuPolicy": {
            "solverWorkers": 1, "cpuLimitVariable": "STAREXEC_CPU_LIMIT",
            "affinity": "inherited", "exclusivePhysicalCore": "UNVERIFIED",
        },
        "build": {
            "configure": ["./configure", "--enable-ho"],
            "make": ["make", "-j2", "rebuild"],
            "sourcePatchApplied": False,
            "gcc": command_output(["gcc", "--version"], root, env).splitlines()[0],
            "makeVersion": command_output(["make", "--version"], root, env).splitlines()[0],
            "linker": command_output(["ld", "--version"], root, env).splitlines()[0],
            "libc": list(platform.libc_ver()), "machine": platform.machine(),
            "system": platform.system(), "python": platform.python_version(),
        },
        "files": {name: {"sha256": sha256(data), "size": len(data),
                          "executable": executable}
                  for name, (data, executable) in sorted(files.items())},
    }
    files["fixture-manifest.json"] = (
        (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode(), False,
    )
    write_package(output, files)
    return {"packageSha256": sha256(output.read_bytes()),
            "binarySha256": sha256(files["bin/eprover-ho"][0]),
            "sourceSha256": lock["sourceSha256"], "reportedVersion": version,
            "qualification": manifest["qualification"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--build-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.source, args.build_dir, args.output)
    except (OSError, ValueError, tarfile.TarError, subprocess.SubprocessError) as error:
        print(f"Fixture build rejected: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
