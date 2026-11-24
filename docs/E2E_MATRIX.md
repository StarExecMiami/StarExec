# Deployed 63-Cell E2E Acceptance Matrix (#293)

The StarExec E2E acceptance harness under [`scripts/e2e/`](../scripts/e2e/run.py)
evaluates deployed StarExec installations through public HTTP/REST/form endpoints,
PostgreSQL state verification, and storage checks across the **21×3 (63-cell)**
qualification matrix (`21` functional capabilities × `3` supported execution backends:
`local`, `podman`, `kubernetes`).

## Functional Denominator (`C01`–`C21`)

Each row (`C01`–`C21`) in [`scripts/e2e/lib/matrix.py`](../scripts/e2e/lib/matrix.py)
maps to one or more numbered capabilities in `scripts/e2e/capabilities/`:

| ID | Capability | Mapped Harness Capabilities |
|---|---|---|
| `C01` | Users | `1`, `27` |
| `C02` | Governance | `30` |
| `C03` | Spaces | `3`, `29` |
| `C04` | Subspaces | `3`, `29`, `43` |
| `C05` | Images | `28` |
| `C06` | Solvers | `7`, `18`, `21`, `33`, `38` |
| `C07` | Configs | `7`, `33` |
| `C08` | Benchmarks | `8`, `9`, `40`, `41`, `42`, `43`, `44` |
| `C09` | Problems | `8`, `32` |
| `C10` | Axioms | `32`, `39` |
| `C11` | Processors | `4`, `5`, `31` |
| `C12` | Settings | `6`, `35` |
| `C13` | Jobs | `10`, `11`, `12`, `13`, `15`, `26`, `36` |
| `C14` | Pairs | `11`, `14`, `19`, `23`, `24`, `25`, `26`, `36` |
| `C15` | Pause | `14` |
| `C16` | Resume | `14`, `16` |
| `C17` | Link | `34` |
| `C18` | Unlink | `34` |
| `C19` | Delete | `17`, `34`, `37` |
| `C20` | Outputs | `15` |
| `C21` | Manifests | `13`, `24` |

## Strict Matrix Accounting & Mode Separation

- **`fresh` vs. `upgrade` separation:** `matrix-report.json`, `matrix-report.csv`,
  and `matrix-summary.md` compute independent 63-cell summaries under `modes.fresh`
  and `modes.upgrade`. Passing in one mode never increases the numerator of the other.
- **No synthetic `PASS` inflation:**
  - `PASS`: every capability mapped to that cell executed against the target backend
    in the selected mode and returned `PASS`.
  - `FAIL`: any mapped capability returned `FAIL`.
  - `BLOCKED`: backend prerequisites failed, the run aborted early, or a mapped
    capability recorded `BLOCKED` or `EXPECTED-FAIL`.
  - `SKIPPED`: the backend or mapped capability subset (e.g. partial `--only` CI
    selection or gated checks) was not executed.
- **SHA & image digest binding:** reports embed `commitSha`, `mode`, `backend`,
  `fixtureMode`, and `imageDigestsByBackend`.

## Reproducible Fixtures (`#287`, `#288`, `#141`)

[`scripts/e2e/lib/fixtures.py`](../scripts/e2e/lib/fixtures.py) integrates the
reproducible builders in [`scripts/scientific/`](../scripts/scientific/) and reuses
[`verify_fixtures.py`](../scripts/scientific/verify_fixtures.py) to reject blocked
legacy archives:

- **`reproducible-offline` (default):**
  - Builds `SZSPostProcessor.zip` via `build_szs_processor.py` (`szs-post-processor.lock.json`).
  - Generates `#141` `TestSequence` archives (`CVC4.zip`, `benchmarks.zip`, `benchproc.zip`, `postproc.zip`, `smallsolver.zip`) via `generate_test_sequence_fixtures.py`.
  - Emits self-contained POSIX `TPTPBenchmarkProcessor.zip`, `E-3.3.3-sequential.zip`, and minimal TPTP problem/axiom TARs (`Problems/AGT/...`, `Problems/SYN/...`, `Axioms/AGT001+0.ax`) matching the `#287`/`#288` interfaces with zero network access or local host paths.
- **`scientific`:**
  - Compiles real `E-3.3.3-sequential.zip` (`build_eprover_fixture.py`), real `tptp4X`-backed `TPTPBenchmarkProcessor.zip` (`build_tptp_processor.py`), and extracts curated v9.3.0 TPTP problems from supplied upstream tarballs (`--eprover-source`, `--tptp4x-source`, `--jjparser-source`, `--datasets-dir`), verifying every lockfile and archive checksum.

## Local CLI Usage

```bash
# Verify endpoint citations against HEAD
python3 scripts/e2e/run.py --verify-endpoints --commit "$(git rev-parse HEAD)"

# Materialize and verify offline reproducible fixtures under scripts/e2e/fixtures/
python3 scripts/e2e/run.py --fixtures-only --fixture-mode reproducible-offline

# Execute against a disposable sq-* deployment
python3 scripts/e2e/run.py --config path/to/config.json --mode fresh --matrix-out target/e2e-reports/local
```

## CI & Post-Deploy Automation

- **GitHub Actions ([`.github/workflows/integration-test.yml`](../.github/workflows/integration-test.yml)):**
  - `Docker Compose Integration Tests`: runs `--verify-endpoints`, `--fixtures-only`,
    and `LocalBackend` (`--mode fresh`) against the live Docker Compose stack and
    uploads `e2e-matrix-local` (`matrix-report.json`, `matrix-report.csv`,
    `matrix-summary.md`, harness run reports).
  - `PodmanBackend E2E Matrix`: verifies rootless Podman/socket/job-runner
    prerequisites and runs the `PodmanBackend` column when supported, or records an
    honest `BLOCKED` report (`PASS=0`) via `--record-blocked` and uploads
    `e2e-matrix-podman`.
- **Jenkins Post-Deploy ([`Jenkinsfile`](../Jenkinsfile)):**
  - `Deployed E2E Matrix (Kubernetes)`: executes after `Smoke Test` against
    disposable non-production Kubernetes deployments (`starexec-dev`), binding
    `GIT_SHA` and `DEPLOY_IMAGE_DIGEST` and archiving `target/e2e-reports/kubernetes/**`.
    Production namespaces (`starexec`) record `BLOCKED` rather than mutating accounts.
