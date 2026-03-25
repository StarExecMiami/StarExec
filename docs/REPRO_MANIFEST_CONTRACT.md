# Reproducibility Manifest Contract

This document freezes the behavior contract for pair-level reproducibility
manifests.

## State machine

States:

- `COLLECTING` (0)
- `FINALIZING` (1)
- `FINAL` (2)
- `FINAL_DERIVED` (3)
- `FAILED` (4)

Allowed transitions:

- `COLLECTING -> FINAL`
- `COLLECTING -> FINAL_DERIVED` (legacy derivation only)

`FINALIZING` and `FAILED` are reserved states for future expansion and are not
currently emitted by runtime write paths.

Terminal immutable states:

- `FINAL`
- `FINAL_DERIVED`

## HTTP status precedence

For `GET /services/jobs/pairs/{id}/reproducibility-manifest`:

1. Resolve pair existence.
   - If pair does not exist: `404 not available`
2. Apply authorization (`JobSecurity.canUserSeeJobWithPair`).
   - If pair exists but user cannot access: `403 not available`
3. Resolve attempt (if provided) and manifest availability.
   - Missing attempt/manifest: `404 not available`
4. Conflict on immutable mismatch or illegal transition (write path): `409`

## Allowlist policy

Allowed high-level classes:

- pair/job ids and attempt metadata
- backend type and benchmarking framework
- declared runtime limits (cpu/wallclock/memory)
- pair status code and finalization metadata
- solver/config/benchmark identifiers and names

Excluded data classes (never emit):

- secrets/tokens/passwords/cookies/session values
- raw environment variable dumps
- private hostnames/internal IPs
- absolute filesystem paths
- user PII fields (email/name/profile)
- raw solver output/log bodies

## Legacy behavior

If manifest row is missing for a completed pair, create one derived best-effort
record:

- state: `FINAL_DERIVED`
- provenance: `DERIVED_LEGACY`
- include warning: `legacy-derived-manifest`

Derived manifests are immutable once persisted.
