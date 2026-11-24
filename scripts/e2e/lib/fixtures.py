"""Reproducible fixture resolution backed by scripts/scientific/ assets.

Zero local user paths and zero blocked legacy archives (explicitly rejecting
LEGACY_REJECTED_SHA256 from scripts/scientific/verify_fixtures.py).
Supports two deterministic modes:
  - reproducible-offline (default): builds SZSPostProcessor.zip from
    scripts/scientific/build_szs_processor.py, #141 ZIPs from
    generate_test_sequence_fixtures.py, and self-contained POSIX E2E
    TPTP/solver/problem fixtures matching the exact same contract without
    external tarball downloads.
  - scientific: compiles real E 3.3.3 and tptp4X from pinned upstream source
    tarballs + extracts curated v9.3.0 TPTP subsets, verifying every lockfile
    and archive via scripts/scientific/verify_fixtures.py.
"""

import hashlib
import importlib.util
import io
import json
import os
import re
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
E2E = HERE.parent
REPO_ROOT = E2E.parents[1]
SCIENTIFIC = REPO_ROOT / "scripts" / "scientific"
FIXDIR = E2E / "fixtures"
MANIFEST = FIXDIR / "manifest.json"
DEFAULT_PICTURE_PATH = (
    REPO_ROOT
    / "starexec-app"
    / "src"
    / "main"
    / "resources"
    / "static"
    / "default-pics"
    / "default-pics"
    / "users"
    / "Pic0.jpg"
)
DEFAULT_PICTURE_SHA256 = (
    "20e83222657ab3904be4ec6b3ff5d170c1ef96cc352b9b6b97b15d4933b914fa"
)

DATASET_SHA256 = {
    "Axioms-v9.3.0.tgz": "9ef819d304ecc58f75f13ce11b9a3b26805b27b38d0705f5b1802c35cb82859a",
    "Problems-v9.3.0.tgz": "8a98d127861d0af5a3cca2047e701e0202e3cf300370ad107ec004b6eca5bbea",
}
THEOREMS = [
    "Problems/AGT/AGT001+1.p",
    "Problems/AGT/AGT001+2.p",
    "Problems/AGT/AGT002+1.p",
    "Problems/AGT/AGT003+1.p",
]
CSA_WANTED = 2
PRESCREEN_IMAGE = (
    "ghcr.io/starexecmiami/starexec:sha-207f6f50c792cf3e381bcd0fe550be8301f96b58"
)
PRESCREEN_CPU_LIMIT = 5
PRESCREEN_MAX_CANDIDATES = 16
FIXED_DATE = (2026, 9, 15, 0, 0, 0)

DESC_VALID = b"C++ solver - https://my-tool.example.com/2017-05-22"  # #101
DESC_INVALID = b"C++ solver <script> - https://my-tool.example.com/2017-05-22"
FAST_MARKER, SLOW_MARKER = "E2E_FAST", "E2E_SLOW"
WRONG_ANSWER = "CounterSatisfiable"
SLEEP_SECONDS = 60

VERBATIM_RUN_SCRIPT = b"""#!/bin/sh
# StarExec qualification fixture: verbatim copy stage with a fixed SZS assurance.
set -eu
name=$(basename "$1")
printf '%% SZS status Success for %s\\n' "$name"
printf '%% SZS output start Assurance for %s\\n' "$name"
printf '%% SZS output end Assurance for %s\\n' "$name"
cat "$1"
head -c 300000000 /dev/zero | sha256sum > /dev/null
"""

FIXTURE_CONFIGS = {
    "fast": (
        f"#!/bin/sh\necho MARKER={FAST_MARKER}\nsleep 0.2\necho DONE={FAST_MARKER}\n",
        "marker",
    ),
    "slow": (
        f"#!/bin/sh\necho MARKER={SLOW_MARKER}\nsleep 1.8\necho DONE={SLOW_MARKER}\n",
        "marker",
    ),
    "wrong": (
        f"#!/bin/sh\necho '% SZS status {WRONG_ANSWER} for fixture'\n",
        "always " + WRONG_ANSWER,
    ),
    "silent": ("#!/bin/sh\necho 'no verdict printed'\n", "starexec-unknown"),
    "sleep": (
        f"#!/bin/sh\necho 'sleeping past the wallclock limit'\nsleep {SLEEP_SECONDS}\n",
        "wallclock limit breach",
    ),
}

C16_HOLD_CONFIGS = {
    "fast": f"#!/bin/sh\necho MARKER={FAST_MARKER}\nsleep 3\necho DONE={FAST_MARKER}\n",
    "slow": f"#!/bin/sh\necho MARKER={SLOW_MARKER}\nsleep 10\necho DONE={SLOW_MARKER}\n",
}

OFFLINE_TPTP_VALIDATOR = b"""#!/bin/sh
# Offline reproducible TPTP benchmark processor matching #287 output keys.
set -eu
LC_ALL=C
export LC_ALL

emit_invalid() {
    printf 'starexec-valid=false\\n'
    printf 'starexec-dependencies=0\\n'
    printf 'starexec-validation-error=%s\\n' "$1"
    exit 0
}

if [ "$#" -ne 1 ] || [ ! -f "$1" ] || [ ! -r "$1" ]; then
    emit_invalid "unreadable-or-missing-benchmark-file"
fi
BENCH="$1"
RAW_BYTES=$(wc -c < "$BENCH" | tr -d '[:space:]')
if [ "$RAW_BYTES" -eq 0 ]; then
    emit_invalid "empty-benchmark-file"
fi
CLEAN_BYTES=$(tr -d '\\000' < "$BENCH" | wc -c | tr -d '[:space:]')
if [ "$RAW_BYTES" -ne "$CLEAN_BYTES" ]; then
    emit_invalid "binary-nul-bytes-rejected"
fi
if ! grep -Eq '^[[:space:]]*(include|fof|cnf|tff|thf|tcf|tpi)\\(' "$BENCH"; then
    emit_invalid "no-tptp-formulas-or-includes"
fi
if grep -Eq '^[[:space:]]*syntax_error' "$BENCH"; then
    emit_invalid "tptp-syntax-error"
fi

STATUS_VAL=$(tr -d '\\r' < "$BENCH" | sed -n 's/^[[:space:]]*%[[:space:]]*Status[[:space:]]*:[[:space:]]*\\([^[:space:]]\\{1,\\}\\).*$/\\1/p' | head -n 1)
SPC_VAL=$(tr -d '\\r' < "$BENCH" | sed -n 's/^[[:space:]]*%[[:space:]]*SPC[[:space:]]*:[[:space:]]*\\([^[:space:]]\\{1,\\}\\).*$/\\1/p' | head -n 1)
INCLUDES=$(sed -n -e "s/^[[:space:]]*include('\\([^']*\\)').*$/\\1/p" -e 's/^[[:space:]]*include("\\([^"]*\\)").*$/\\1/p' "$BENCH")

DEP_COUNT=0
DEP_LINES=""
if [ -n "$INCLUDES" ]; then
    old_ifs=$IFS
    IFS='
'
    for inc in $INCLUDES; do
        case "$inc" in
            ""|/*|*\\\\*|*" "*|*".."*)
                IFS=$old_ifs
                emit_invalid "unsafe-include-path: $inc"
                ;;
        esac
        DEP_COUNT=$((DEP_COUNT + 1))
        if [ -z "$DEP_LINES" ]; then
            DEP_LINES="$inc"
        else
            DEP_LINES="${DEP_LINES}
${inc}"
        fi
    done
    IFS=$old_ifs
fi

printf 'starexec-valid=true\\n'
if [ -n "$STATUS_VAL" ]; then
    printf 'Status=%s\\n' "$STATUS_VAL"
    printf 'starexec-expected-result=%s\\n' "$STATUS_VAL"
fi
if [ -n "$SPC_VAL" ]; then
    printf 'SPC=%s\\n' "$SPC_VAL"
fi
printf 'starexec-dependencies=%s\\n' "$DEP_COUNT"
idx=1
if [ -n "$DEP_LINES" ]; then
    old_ifs=$IFS
    IFS='
'
    for inc in $DEP_LINES; do
        printf 'starexec-dependency-%s=%s\\n' "$idx" "$inc"
        idx=$((idx + 1))
    done
    IFS=$old_ifs
fi
"""

OFFLINE_EPROVER_RUN_SCRIPT = b"""#!/bin/sh
# Offline reproducible E-3.3.3-sequential-compatible solver fixture.
set -eu
limit="${STAREXEC_CPU_LIMIT:-30}"
case "$limit" in
    ''|0*|*[!0-9]*)
        echo "Invalid STAREXEC_CPU_LIMIT: $limit" >&2
        exit 64
        ;;
esac
bench="$1"
name=$(basename "$bench")
status=$(tr -d '\\r' < "$bench" | sed -n 's/^[[:space:]]*%[[:space:]]*Status[[:space:]]*:[[:space:]]*\\([^[:space:]]\\{1,\\}\\).*$/\\1/p' | head -n 1)
case "$status" in
    CounterSatisfiable)
        printf '%% SZS status CounterSatisfiable for %s\\n' "$name"
        exit 1
        ;;
    Theorem|"")
        printf '%% SZS status Theorem for %s\\n' "$name"
        exit 0
        ;;
    *)
        printf '%% SZS status %s for %s\\n' "$status" "$name"
        exit 0
        ;;
esac
"""


class FixtureError(Exception):
    pass


def _load_verify_module():
    spec = importlib.util.spec_from_file_location(
        "starexec_verify_fixtures", SCIENTIFIC / "verify_fixtures.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_not_legacy_rejected(digest, label="artifact"):
    vf = _load_verify_module()
    try:
        vf.check_not_legacy_rejected(digest, label=label)
    except ValueError as exc:
        raise FixtureError(str(exc)) from exc


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    digest = h.hexdigest()
    check_not_legacy_rejected(digest, label=Path(path).name)
    return digest


def write_deterministic(path, data):
    check_not_legacy_rejected(hashlib.sha256(data).hexdigest(), label=path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.read_bytes() != data:
        path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def _zip(entries, date_time=FIXED_DATE):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data, mode in entries:
            info = zipfile.ZipInfo(name, date_time=date_time)
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.create_system = 3
            z.writestr(info, data)
    return buf.getvalue()


def _tar(files):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for name, data in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def _png(width=32, height=32, rgb=(40, 100, 180)):
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def header_status(data):
    m = re.search(rb"^%\s*Status\s*:\s*(\S+)", data, re.M)
    return m.group(1).decode() if m else None


def header_spc(data):
    m = re.search(rb"^%\s*SPC\s*:\s*(\S+)", data, re.M)
    return m.group(1).decode() if m else None


def includes(data):
    return [
        n.decode() for n in re.findall(rb"^\s*include\s*\(\s*'([^']+)'", data, re.M)
    ]


def _axiom_closure(problems, axioms_tar):
    with tarfile.open(axioms_tar) as archive:
        index = {m.name: m for m in archive.getmembers() if m.isfile()}
        closure = {}
        pending = list(problems.values())
        while pending:
            for name in includes(pending.pop()):
                if name not in closure:
                    if (
                        not name.startswith("Axioms/")
                        or ".." in Path(name).parts
                        or name not in index
                    ):
                        raise FixtureError(f"unresolvable or unsafe include {name!r}")
                    closure[name] = archive.extractfile(index[name]).read()
                    pending.append(closure[name])
    return closure


def _offline_tptp_dataset():
    axioms = {
        "Axioms/AGT001+0.ax": b"% Minimal reproducible TPTP axiom\nfof(agt_ax_1, axiom, (p => p)).\n"
    }
    problems = {}
    for rel in THEOREMS:
        base = rel.rsplit("/", 1)[-1]
        problems[rel] = (
            f"% File     : {base}\n"
            f"% Status   : Theorem\n"
            f"% SPC      : FOF_THM_RFO_SEQ\n"
            f"include('Axioms/AGT001+0.ax').\n"
            f"fof(thm_goal, conjecture, (p => p)).\n"
        ).encode()
    for idx in range(1, CSA_WANTED + 1):
        name = f"Problems/SYN/SYN00{idx}+1.p"
        problems[name] = (
            f"% File     : SYN00{idx}+1.p\n"
            f"% Status   : CounterSatisfiable\n"
            f"% SPC      : FOF_CSA_RFO_SEQ\n"
            f"include('Axioms/AGT001+0.ax').\n"
            f"fof(csa_goal, conjecture, (p & ~p)).\n"
        ).encode()
    return problems, axioms


def _run_scientific_builder(cmd):
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise FixtureError(
            f"scientific builder failed ({' '.join(cmd)}): {proc.stderr or proc.stdout}"
        )


def _build_reproducible_software(
    mode,
    eprover_source=None,
    tptp4x_source=None,
    jjparser_source=None,
):
    vf = _load_verify_module()
    built = {}

    # 1. SZSPostProcessor.zip (#287): always built offline and verified against lockfile.
    szs_dest = FIXDIR / "SZSPostProcessor.zip"
    if szs_dest.exists():
        szs_dest.unlink()
    szs_lock = SCIENTIFIC / "szs-post-processor.lock.json"
    _run_scientific_builder(
        [
            sys.executable,
            str(SCIENTIFIC / "build_szs_processor.py"),
            "--output",
            str(szs_dest),
            "--lock",
            str(szs_lock),
        ]
    )
    vf.verify_fixture_archive(szs_dest, lock_path=szs_lock)
    built["SZSPostProcessor.zip"] = szs_dest.read_bytes()

    # 2. #141 TestSequence fixtures (generated + verified against test-sequence-fixtures.lock.json).
    ts_dir = FIXDIR / "test-sequence"
    ts_lock = SCIENTIFIC / "test-sequence-fixtures.lock.json"
    _run_scientific_builder(
        [
            sys.executable,
            str(SCIENTIFIC / "generate_test_sequence_fixtures.py"),
            "--lock",
            str(ts_lock),
            "--output-dir",
            str(ts_dir),
            "--repo-root",
            str(REPO_ROOT),
        ]
    )
    ts_report = vf.verify_generated_fixtures(ts_lock, ts_dir, repo_root=REPO_ROOT)

    # 3. TPTPBenchmarkProcessor.zip (#287).
    tptp_dest = FIXDIR / "TPTPBenchmarkProcessor.zip"
    tptp_lock = SCIENTIFIC / "tptp-benchmark-processor.lock.json"
    if tptp4x_source and jjparser_source:
        if tptp_dest.exists():
            tptp_dest.unlink()
        with tempfile.TemporaryDirectory(prefix="e2e-tptp-build-") as tmp:
            bdir = Path(tmp) / "build"
            _run_scientific_builder(
                [
                    sys.executable,
                    str(SCIENTIFIC / "build_tptp_processor.py"),
                    "--tptp4x-source",
                    str(tptp4x_source),
                    "--jjparser-source",
                    str(jjparser_source),
                    "--build-dir",
                    str(bdir),
                    "--output",
                    str(tptp_dest),
                ]
            )
        vf.verify_fixture_archive(tptp_dest, lock_path=tptp_lock)
        built["TPTPBenchmarkProcessor.zip"] = tptp_dest.read_bytes()
    elif mode == "scientific":
        raise FixtureError(
            "scientific fixture mode requires --tptp4x-source and --jjparser-source"
        )
    else:
        built["TPTPBenchmarkProcessor.zip"] = _zip(
            [("process", OFFLINE_TPTP_VALIDATOR, 0o755)]
        )

    # 4. E-3.3.3-sequential.zip (#288): reject blocked legacy digests via sha256_file().
    e_dest = FIXDIR / "E-3.3.3-sequential.zip"
    e_lock = SCIENTIFIC / "eprover-3.3.3.lock.json"
    if eprover_source:
        sha256_file(eprover_source)
        if e_dest.exists():
            e_dest.unlink()
        with tempfile.TemporaryDirectory(prefix="e2e-eprover-build-") as tmp:
            bdir = Path(tmp) / "build"
            _run_scientific_builder(
                [
                    sys.executable,
                    str(SCIENTIFIC / "build_eprover_fixture.py"),
                    "--source",
                    str(eprover_source),
                    "--build-dir",
                    str(bdir),
                    "--output",
                    str(e_dest),
                ]
            )
        vf.verify_fixture_archive(e_dest, lock_path=e_lock)
        built["E-3.3.3-sequential.zip"] = e_dest.read_bytes()
    elif mode == "scientific":
        raise FixtureError("scientific fixture mode requires --eprover-source")
    else:
        built["E-3.3.3-sequential.zip"] = _zip(
            [("bin/starexec_run_E-3.3.3-sequential", OFFLINE_EPROVER_RUN_SCRIPT, 0o755)]
        )

    return built, ts_report


def verify_default_picture():
    if not DEFAULT_PICTURE_PATH.is_file():
        raise FixtureError(f"Default picture not found: {DEFAULT_PICTURE_PATH}")
    digest = sha256_file(DEFAULT_PICTURE_PATH)
    if digest != DEFAULT_PICTURE_SHA256:
        raise FixtureError(
            f"Default picture hash mismatch: {digest} != {DEFAULT_PICTURE_SHA256}"
        )
    return digest


def build(
    mode="reproducible-offline",
    run_prescreen=False,
    eprover_source=None,
    tptp4x_source=None,
    jjparser_source=None,
    datasets_dir=None,
    log=print,
):
    if mode not in ("reproducible-offline", "scientific"):
        raise FixtureError(f"Unsupported fixture mode: {mode!r}")

    eprover_source = eprover_source or os.environ.get("STAREXEC_EPROVER_SOURCE")
    tptp4x_source = tptp4x_source or os.environ.get("STAREXEC_TPTP4X_SOURCE")
    jjparser_source = jjparser_source or os.environ.get("STAREXEC_JJPARSER_SOURCE")
    datasets_dir = Path(datasets_dir or os.environ.get("STAREXEC_TPTP_DATASETS_DIR")) if (
        datasets_dir or os.environ.get("STAREXEC_TPTP_DATASETS_DIR")
    ) else None

    FIXDIR.mkdir(parents=True, exist_ok=True)
    pic_sha = verify_default_picture()
    vf = _load_verify_module()
    lock_results = vf.verify_lock_directory(SCIENTIFIC, repo_root=REPO_ROOT)

    built_software, ts_report = _build_reproducible_software(
        mode,
        eprover_source=eprover_source,
        tptp4x_source=tptp4x_source,
        jjparser_source=jjparser_source,
    )

    archives_report = {
        info["lock"]: {
            "path": str(SCIENTIFIC / info["lock"]),
            "sha256": info["sha256"],
            "ok": True,
        }
        for info in lock_results
    }
    archives_report["default_picture"] = {
        "path": str(DEFAULT_PICTURE_PATH.relative_to(REPO_ROOT)),
        "sha256": pic_sha,
        "ok": True,
    }

    if datasets_dir is not None:
        for name, want in DATASET_SHA256.items():
            p = datasets_dir / name
            got = sha256_file(p) if p.exists() else None
            archives_report[name] = {
                "path": str(p),
                "expected": want,
                "actual": got,
                "ok": got == want,
            }
        bad = [n for n, v in archives_report.items() if not v["ok"]]
        if bad:
            return {
                "schemaVersion": 1,
                "fixture_mode": mode,
                "archives": archives_report,
                "error": f"archive hash mismatch or missing: {bad}",
            }
        with tarfile.open(datasets_dir / "Problems-v9.3.0.tgz") as t:
            problems = {n: t.extractfile(n).read() for n in THEOREMS}
        axioms = _axiom_closure(problems, datasets_dir / "Axioms-v9.3.0.tgz")
        csa_count = 0
    elif mode == "scientific":
        raise FixtureError(
            "scientific fixture mode requires --datasets-dir with Axioms-v9.3.0.tgz and Problems-v9.3.0.tgz"
        )
    else:
        problems, axioms = _offline_tptp_dataset()
        csa_count = CSA_WANTED

    files = dict(built_software)
    files["e2e-axioms.tar"] = _tar(axioms)
    files["e2e-problems.tar"] = _tar(problems)
    files["verbatim-szs.zip"] = _zip(
        [("bin/starexec_run_verbatim", VERBATIM_RUN_SCRIPT, 0o755)],
        date_time=(2026, 9, 14, 0, 0, 0),
    )
    files["fixture-solver.zip"] = _zip(
        [
            (f"bin/starexec_run_{n}", s.encode(), 0o755)
            for n, (s, _) in sorted(FIXTURE_CONFIGS.items())
        ]
    )
    files["desc-valid-solver.zip"] = _zip(
        [
            ("starexec_description.txt", DESC_VALID, 0o644),
            ("bin/starexec_run_default", b"#!/bin/sh\necho ok\n", 0o755),
        ]
    )
    files["desc-invalid-solver.zip"] = _zip(
        [
            ("starexec_description.txt", DESC_INVALID, 0o644),
            ("bin/starexec_run_default", b"#!/bin/sh\necho ok\n", 0o755),
        ]
    )
    files["picture.png"] = _png()
    files["picture-b.png"] = _png(width=48, height=24, rgb=(200, 60, 30))
    for version in ("v1", "v2"):
        files[f"c31-bench-proc-{version}.zip"] = _zip(
            [
                (
                    "process",
                    (
                        f"#!/bin/sh\necho starexec-valid=true\necho e2e-proc={version}\n"
                        'echo e2e-lines=$(wc -l < "$1" | tr -d " ")\n'
                    ).encode(),
                    0o755,
                )
            ]
        )
        files[f"c31-post-{version}.zip"] = _zip(
            [
                (
                    "process",
                    (
                        f"#!/bin/sh\necho starexec-result=E2E31{version.upper()}\necho e2e-post={version}\n"
                        'echo e2e-pre-seen=$(grep -c E2E_PRE_31 "$1")\n'
                    ).encode(),
                    0o755,
                )
            ]
        )
    files["c31-pre.zip"] = _zip(
        [("process", b'#!/bin/sh\nprintf "E2E_PRE_31\\n"\ncat "$1"\n', 0o755)]
    )
    files["c31-cat-solver.zip"] = _zip(
        [("bin/starexec_run_cat", b'#!/bin/sh\ncat "$1"\n', 0o755)]
    )
    files["c16-hold-solver.zip"] = _zip(
        [
            (f"bin/starexec_run_{n}", s.encode(), 0o755)
            for n, s in sorted(C16_HOLD_CONFIGS.items())
        ]
    )
    files["c31-bench.tar"] = _tar(
        {
            "c31/one.p": b"% E2E c31 benchmark one\nfof(a,axiom,p).\n",
            "c31/two.p": b"% E2E c31 benchmark two\nfof(b,axiom,q).\nfof(c,conjecture,q).\n",
        }
    )
    files["pre-processor.zip"] = _zip([("process", b'#!/bin/sh\ncat "$1"\n', 0o755)])
    files["fail-solver.zip"] = _zip(
        [
            (
                "bin/starexec_run_failvar",
                b"#!/bin/sh\necho 'failvar fixture: removing runsolver files and failing'\n"
                b'rm -f "$2/../var.out" "$2/../watcher.out"\nexit 3\n',
                0o755,
            )
        ]
    )
    files["conflict-solver.zip"] = _zip(
        [
            (
                "bin/starexec_run_theorem",
                b"#!/bin/sh\necho '% SZS status Theorem for conflict fixture'\n",
                0o755,
            ),
            (
                "bin/starexec_run_csa",
                b"#!/bin/sh\necho '% SZS status CounterSatisfiable for conflict fixture'\n",
                0o755,
            ),
            ("bin/starexec_run_silent", b"#!/bin/sh\necho 'no verdict'\n", 0o755),
            (
                "bin/starexec_run_jobvariant",
                b'#!/bin/sh\nif [ "${STAREXEC_WALLCLOCK_LIMIT:-}" = "29" ]; then\n'
                b"  echo '% SZS status Theorem for conflict fixture'\nelse\n  echo 'no verdict'\nfi\n",
                0o755,
            ),
        ]
    )
    files["delete-bench.tar"] = _tar(
        {
            "DeleteMe/e2e_delete_me.p": (
                b"% E2E deletion fixture: no include(), used by nothing.\n"
                b"fof(ax, axiom, p).\nfof(c, conjecture, p).\n"
            )
        }
    )

    def stored_name(n, d):
        if n in ("e2e-axioms.tar", "e2e-problems.tar"):
            return f"{n[:-4]}-{hashlib.sha256(d).hexdigest()[:12]}.tar"
        return n

    manifest = {
        "schemaVersion": 1,
        "fixture_mode": mode,
        "eprover_config": "E-3.3.3-sequential",
        "archives": archives_report,
        "test_sequence_fixtures": ts_report,
        "prescreen": {"not_run": not run_prescreen, "chosen": sorted(k for k in problems if k not in THEOREMS)},
        "files": {
            n: {
                "path": str(FIXDIR / stored_name(n, d)),
                "sha256": write_deterministic(FIXDIR / stored_name(n, d), d),
                "bytes": len(d),
            }
            for n, d in sorted(files.items())
        },
        "problems": {
            n: {
                "sha256": hashlib.sha256(d).hexdigest(),
                "expected_status": header_status(d),
                "spc": header_spc(d),
                "includes": includes(d),
            }
            for n, d in sorted(problems.items())
        },
        "axioms": {n: hashlib.sha256(d).hexdigest() for n, d in sorted(axioms.items())},
        "fixture_configs": {n: e for n, (_, e) in sorted(FIXTURE_CONFIGS.items())},
        "descriptions": {
            "valid": DESC_VALID.decode(),
            "invalid": DESC_INVALID.decode(),
        },
        "csa_count": csa_count,
    }
    tmp = MANIFEST.with_suffix(".tmp")
    tmp.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    tmp.replace(MANIFEST)
    return manifest


def load():
    if not MANIFEST.exists():
        raise FixtureError("fixtures not built; run scripts/e2e/run.py --fixtures-only")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for name, f in manifest.get("files", {}).items():
        fpath = Path(f["path"])
        if not fpath.exists():
            raise FixtureError(f"fixture {name} missing at {fpath}")
        if sha256_file(fpath) != f["sha256"]:
            raise FixtureError(f"fixture {name} changed since manifest was written")
    return manifest


def ensure_loaded(
    mode="reproducible-offline",
    eprover_source=None,
    tptp4x_source=None,
    jjparser_source=None,
    datasets_dir=None,
):
    if MANIFEST.exists():
        try:
            manifest = load()
            if manifest.get("fixture_mode") == mode:
                return manifest
        except FixtureError:
            pass
    manifest = build(
        mode=mode,
        eprover_source=eprover_source,
        tptp4x_source=tptp4x_source,
        jjparser_source=jjparser_source,
        datasets_dir=datasets_dir,
    )
    if manifest.get("error"):
        raise FixtureError(manifest["error"])
    return load()
