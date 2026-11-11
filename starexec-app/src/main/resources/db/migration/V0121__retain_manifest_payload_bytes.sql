-- Retain the exact representation hashed by Java before JSONB normalizes it.
-- Existing immutable final rows remain untouched. Older application versions
-- may omit this column; readers must verify any available bytes against the
-- existing digest before publishing them as its payload.
ALTER TABLE starexec.job_pair_repro_manifests
    ADD COLUMN manifest_payload TEXT;

COMMENT ON COLUMN starexec.job_pair_repro_manifests.manifest_payload IS
    'Exact JSON string whose UTF-8 bytes are covered by manifest_sha256; nullable for historical rows and older writers.';
