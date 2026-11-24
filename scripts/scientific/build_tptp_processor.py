#!/usr/bin/env python3
"""Build a deterministic TPTPBenchmarkProcessor ZIP from pinned TPTP4X + JJParser archives."""

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
LOCK = HERE / "tptp-benchmark-processor.lock.json"
SCRIPT = HERE / "tptp_benchmark_processor.sh"
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_SOURCE_BYTES = 64 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_verified_archive(path, expected_hash, label):
    """Read a regular non-symlink tarball with bounded size and SHA-256 verification."""
    candidate = Path(path)
    for parent in [candidate, *candidate.parents]:
        if parent.is_symlink():
            raise ValueError(f"Refusing symlink component in {label} path: {parent}")
    descriptor = os.open(candidate, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError(f"{label} must be a regular file")
        data = source.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError(f"{label} exceeds the archive size limit")
    if sha256(data) != expected_hash:
        raise ValueError(f"{label} SHA-256 does not match the reviewed lock")
    return data


def extract_tarball_safe(archive, destination, root):
    """Validate tar members before unpacking with filter='data'."""
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        names = set()
        total = 0
        for member in members:
            name = PurePosixPath(member.name)
            if (
                name.is_absolute()
                or ".." in name.parts
                or not name.parts
                or name.parts[0] != root
                or member.name in names
                or not (member.isfile() or member.isdir())
            ):
                raise ValueError(f"Unsafe or duplicate member in {archive.name}: {member.name}")
            names.add(member.name)
            total += member.size
            if total > MAX_SOURCE_BYTES:
                raise ValueError(f"Unpacked source exceeds limit in {archive.name}")
        source.extractall(destination, members=members, filter="data")
    return destination / root


def run_checked(argv, cwd, env, log_name, seconds=300):
    with (cwd / log_name).open("xb") as log:
        subprocess.run(
            ["timeout", "--kill-after=5s", f"{seconds}s", *argv],
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
        )


def command_output(argv, cwd, env, allowed_rcs=(0,)):
    completed = subprocess.run(
        argv,
        cwd=cwd,
        env=env,
        stderr=subprocess.STDOUT,
        stdout=subprocess.PIPE,
        timeout=10,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode not in allowed_rcs:
        raise subprocess.CalledProcessError(
            completed.returncode, argv, output=completed.stdout
        )
    return completed.stdout.strip()


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


def build(tptp4x_source, jjparser_source, build_dir, output):
    if sys.version_info < (3, 12):
        raise ValueError("Python 3.12 or newer is required for safe tar extraction")
    lock_bytes = LOCK.read_bytes()
    lock = json.loads(lock_bytes)
    tptp4x_bytes = read_verified_archive(tptp4x_source, lock["sourceSha256"], "TPTP4X tarball")
    jjparser_bytes = read_verified_archive(jjparser_source, lock["jjparserSha256"], "JJParser tarball")

    output = Path(output).absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; choose a new package path")
    if not output.parent.is_dir():
        raise ValueError("Output parent directory must already exist")

    build_dir = Path(build_dir).absolute()
    if any(not (ch.isascii() and (ch.isalnum() or ch in "/._-")) for ch in str(build_dir)):
        raise ValueError("Build path must contain only ASCII letters, digits, /._-")
    for tool in ("gcc", "make", "timeout", "ld"):
        if not shutil.which(tool, path="/usr/bin:/bin"):
            raise ValueError(f"Required build tool is missing: {tool}")

    build_dir.mkdir(parents=True, exist_ok=False)
    tptp4x_tar = build_dir / "tptp4x-source.tar.gz"
    jjparser_tar = build_dir / "jjparser-source.tar.gz"
    tptp4x_tar.write_bytes(tptp4x_bytes)
    jjparser_tar.write_bytes(jjparser_bytes)

    root = extract_tarball_safe(tptp4x_tar, build_dir, lock["sourceRoot"])
    jj_dst = root / "JJParser"
    if jj_dst.exists() and jj_dst.is_dir() and not any(jj_dst.iterdir()):
        jj_dst.rmdir()
    jj_root = extract_tarball_safe(jjparser_tar, root, lock["jjparserRoot"])
    jj_root.rename(jj_dst)

    license_bytes = (jj_dst / "LICENSE").read_bytes()
    if sha256(license_bytes) != lock["copyingSha256"]:
        raise ValueError("Upstream JJParser LICENSE does not match the reviewed lock")

    env = {
        "PATH": "/usr/bin:/bin",
        "LC_ALL": "C",
        "TZ": "UTC",
        "SOURCE_DATE_EPOCH": str(lock["sourceDateEpoch"]),
    }
    run_checked(["make", "-j2", "tptp4X"], root, env, "fixture-build.log")

    binary = root / "tptp4X"
    version = command_output([str(binary), "-v"], root, env)
    if version != lock["expectedVersionOutput"]:
        raise ValueError(
            f"Built tptp4X version mismatch: expected {lock['expectedVersionOutput']!r}, got {version!r}"
        )

    files = {
        "bin/tptp4X": (binary.read_bytes(), True),
        "process": (SCRIPT.read_bytes(), True),
        "COPYING": (license_bytes, False),
        "source/TPTP4X-source.tar.gz": (tptp4x_bytes, False),
        "source/JJParser-source.tar.gz": (jjparser_bytes, False),
        "recipe/build_tptp_processor.py": (Path(__file__).read_bytes(), False),
        "recipe/tptp-benchmark-processor.lock.json": (lock_bytes, False),
        f"recipe/{SCRIPT.name}": (SCRIPT.read_bytes(), False),
    }
    manifest = {
        "schemaVersion": 1,
        "source": lock,
        "reportedVersion": version,
        "qualification": "LOCAL_BUILD_ONLY",
        "build": {
            "make": ["make", "-j2", "tptp4X"],
            "sourcePatchApplied": False,
            "gcc": command_output(["gcc", "--version"], root, env).splitlines()[0],
            "makeVersion": command_output(["make", "--version"], root, env).splitlines()[0],
            "linker": command_output(["ld", "--version"], root, env).splitlines()[0],
            "libc": list(platform.libc_ver()),
            "machine": platform.machine(),
            "system": platform.system(),
            "python": platform.python_version(),
        },
        "files": {
            name: {"sha256": sha256(data), "size": len(data), "executable": executable}
            for name, (data, executable) in sorted(files.items())
        },
    }
    files["fixture-manifest.json"] = (
        (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        False,
    )
    write_package(output, files)
    return {
        "packageSha256": sha256(output.read_bytes()),
        "binarySha256": sha256(files["bin/tptp4X"][0]),
        "sourceSha256": lock["sourceSha256"],
        "jjparserSha256": lock["jjparserSha256"],
        "reportedVersion": version,
        "qualification": manifest["qualification"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tptp4x-source", required=True, type=Path)
    parser.add_argument("--jjparser-source", required=True, type=Path)
    parser.add_argument("--build-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.tptp4x_source, args.jjparser_source, args.build_dir, args.output)
    except (OSError, ValueError, tarfile.TarError, subprocess.SubprocessError) as error:
        print(f"TPTP processor build rejected: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
