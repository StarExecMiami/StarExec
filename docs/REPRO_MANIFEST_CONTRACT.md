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

## Digest verification

`manifestSha256` is SHA-256 over the UTF-8 bytes of the exact JSON string produced
when that row was written. It is not a hash of a client reserialization of the
`manifest` object. PostgreSQL JSONB preserves the document but changes its key
order and whitespace, so hashing the returned object is not a valid check.

The response adds two fields without changing existing digests or schema version:

- `manifestBytesAvailable`: whether digest-covered bytes are available.
- `manifestJson`: the exact string to encode as UTF-8 and hash; present only when
  the server has verified both its digest and its equality to the `manifest`
  document. JSON string escaping in the HTTP response must be decoded first.

For example, after parsing the HTTP JSON response in Python:

```python
assert response["manifestBytesAvailable"] is True
payload = response["manifestJson"]
assert hashlib.sha256(payload.encode("utf-8")).hexdigest() == response["manifestSha256"]
assert json.loads(payload) == response["manifest"]
```

New collecting, final and legacy-derived writes retain the original string in
`manifest_payload`, alongside the searchable JSONB document. Apply V0121 before
running the new application. The column is nullable for older writers and
historical rows; it does not relax the final-state immutability triggers.

For historical rows, the server may reconstruct the known version-1 producer
order, but publishes that string only when it matches the existing digest.
Unsupported representations or unrecoverable bytes return
`manifestBytesAvailable=false` and omit `manifestJson`. Neither a new digest nor
an invented payload replaces the historical record. Old application versions
can continue writing the existing columns; any stale retained bytes are checked
against the current document and digest before use.

Repeated finalization compares the document while ignoring only
`provenance.generatedAt`. Identical content succeeds without rewriting the final
row, its digest or timestamps. A changed status, limit, input or other field
still fails the immutable-content comparison.

Document comparison uses exact decimal numeric values, without conversion to
binary floating point. Adjacent integers above 2^53 remain distinct; equivalent
numeric spellings such as `2` and `2.000` compare equal. Object keys and array
order must match. This comparison never changes the retained UTF-8 payload or
the bytes covered by its digest.

This verifies byte integrity, not the truth of declared runtime metadata or a
cryptographic signature. Runtime identity and scientific provenance need their
own evidence. See [PostgreSQL JSON types](https://www.postgresql.org/docs/16/datatype-json.html)
and the [DSSE serialized-body approach](https://github.com/secure-systems-lab/dsse/blob/master/protocol.md).

