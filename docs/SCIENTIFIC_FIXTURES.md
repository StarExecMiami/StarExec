# Reproducible scientific solver fixtures

The E 3.3.3 recipe supplies a local, reviewable replacement candidate for the
mislabeled legacy `E---3.3.0.zip` described in #288. It does not replace the
qualification harness's fixtures automatically, restore the legacy CVC4 fixture
in #141, or qualify a deployed backend. Processor provenance and TPTP/SZS
validation remain separate work under #287.

## Common provenance schema

Scientific assets and test fixtures under `scripts/scientific/` follow a shared
`schemaVersion: 1` contract:

- **Lockfile (`*.lock.json`)**: pins upstream `version`, `sourceCommit` (or
  provenance source), `sourceSha256`, `copyingSha256` / license metadata, and
  execution contract metadata (`configuration`, `solverWorkers`).
- **Package manifest (`fixture-manifest.json` inside each built ZIP)**: records
  `schemaVersion`, embedded `source` lock metadata, `reportedVersion`,
  `cpuPolicy`, toolchain details, and exact `sha256`, `size`, and `executable`
  mode for every member in the archive.
- **Verifier ([`verify_fixtures.py`](../scripts/scientific/verify_fixtures.py))**:
  validates lockfile schema and paths (rejecting `..` traversal and symlinks),
  checks ZIP CRCs and member manifests, and explicitly rejects blocked legacy
  digests such as `E---3.3.0.zip`
  (`329b3d910c3215cc79450ae74589cd82993c517cc3cb536261e9ca7a33bd0965`).

## Source identity (E 3.3.3)

[`eprover-3.3.3.lock.json`](../scripts/scientific/eprover-3.3.3.lock.json) fixes:

- The official [E-3.3.3 source commit](https://github.com/eprover/eprover/tree/627ec5a90dd46da1a837232dc268d6ca2239e2fb),
  `627ec5a90dd46da1a837232dc268d6ca2239e2fb`.
- The immutable-commit GitHub API archive representation, SHA-256
  `6fe63591e36ef61478211376f3cb4021ab33a4c279566123a00aec3eccd8b167`.
- The upstream `COPYING` file, SHA-256
  `691457d152914b339ea7ea591e1a684eca15f29bdedcbe4fc979474440c3920d`.
- The expected higher-order binary version,
  `E 3.3.3-ho Countess Grey (37fde70d516b57cb64294f8fe39bc16ece8198f8)`.

The version's embedded commit is stale in upstream `PROVER/e_gitcommit.h`.
The recipe records both identities without rewriting upstream source. Preserve
`COPYING` and all upstream notices when redistributing.

## Local build

Use a Linux development environment with Python 3.12+, GCC, GNU Make, GNU awk,
GNU timeout and binutils already installed. No application dependency is added.
Obtain the archive using the `sourceUrl` in the reviewed lock and keep it locally.

From the repository root, with `target/` already present:

```bash
python3 scripts/scientific/build_eprover_fixture.py \
  --source /path/to/E-3.3.3-source.tar.gz \
  --build-dir target/eprover-build-1 \
  --output target/E-3.3.3-sequential-1.zip

python3 scripts/scientific/verify_fixtures.py \
  --archive target/E-3.3.3-sequential-1.zip \
  --lock scripts/scientific/eprover-3.3.3.lock.json
```

The build directory must be new, and its absolute path may contain only ASCII
letters, digits, `/`, `.`, `_` and `-`, because upstream makefiles use unquoted
paths. The output's parent must exist; an existing output is never replaced.

Repeat with a second fresh build directory and output path to verify
reproducibility for the recorded toolchain.

The ZIP contains:

```text
bin/eprover-ho
bin/starexec_run_E-3.3.3-sequential
COPYING
fixture-manifest.json
recipe/build_eprover_fixture.py
recipe/eprover-3.3.3.lock.json
recipe/starexec_run_E-3.3.3-sequential
source/E-3.3.3-source.tar.gz
```

## Execution contract

The wrapper accepts a benchmark path and an optional StarExec output directory.
It produces solver output on stdout, preserves the solver exit status, and
requires `STAREXEC_CPU_LIMIT` as a positive decimal integer without leading zeros
(maximum 999,999,999). Missing or invalid values return 64 before solver startup.

When `TPTP` is already set in the environment, the wrapper preserves it;
otherwise it walks ancestor directories above the benchmark file (stopping before
`/`) and exports `TPTP` for the first non-symlink `Axioms/` parent so `include(...)`
directives resolve inside uploaded TPTP hierarchies.

The solver always receives `--auto-schedule=1`, `--proof-object`,
`--print-statistics`, `--print-version`, the supplied CPU-time limit, and `-- "$1"`.
E's pinned `BASICS/clb_error.h` defines exit 0 for a proof, 1 for satisfiability,
3 for syntax errors and 6 for file errors.

For each measured result, retain the package, binary and wrapper digests,
configuration name, runner image digest, CPU policy and runtime allocation
evidence. The package manifest alone does not prove exclusive physical-core
binding (#295, #291, #286).

## Verification and remaining gates

```bash
python3 scripts/scientific/verify_fixtures.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
sh -n scripts/scientific/starexec_run_E-3.3.3-sequential
shellcheck -s sh scripts/scientific/starexec_run_E-3.3.3-sequential
```

Offline tooling checks use a stub executable to verify argument handling,
`Axioms/` fallback behavior, inherited affinity, manifest verification, and
exit codes; they do not compile E or qualify deployed backends.

Deployed E2E qualification on Local, Podman, and Kubernetes (comparing SZS
status, certificates, limits, CPU affinity, and effective solver hashes through
upload, scheduling, ingestion, and download) remains tracked under #288 and #293.
