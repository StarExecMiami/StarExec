-- Extend per-attempt CPU attestation with physical-exclusivity profile and topology metadata (#291).
-- Additive defaults preserve backwards compatibility with rows written under V0138 (#295).
ALTER TABLE starexec.attempt_cpu_attestation
    ADD COLUMN isolation_mechanism TEXT NOT NULL DEFAULT 'taskset'
        CHECK (btrim(isolation_mechanism) <> ''),
    ADD COLUMN physical_exclusivity_attested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reserved_sibling_cpus TEXT,
    ADD COLUMN topology_core_id INTEGER,
    ADD COLUMN topology_package_id INTEGER,
    ADD COLUMN topology_thread_siblings TEXT;
