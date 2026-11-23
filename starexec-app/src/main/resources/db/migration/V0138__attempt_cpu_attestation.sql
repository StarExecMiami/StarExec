-- Dedicated per-attempt CPU allocation and topology attestation record (#295, #291, #286).
-- Stored separately from user/post-processor pair attributes and removed on pair deletion.
CREATE TABLE starexec.attempt_cpu_attestation (
    pair_id INTEGER NOT NULL CHECK (pair_id > 0),
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    backend_type TEXT NOT NULL CHECK (btrim(backend_type) <> ''),
    node_name TEXT,
    isolation_mode TEXT NOT NULL CHECK (btrim(isolation_mode) <> ''),
    leased_cpus TEXT,
    initial_effective_cpuset TEXT,
    terminal_effective_cpuset TEXT,
    observed_process_cpuset TEXT,
    topology_summary TEXT,
    attestation_status TEXT NOT NULL CHECK (
        attestation_status IN ('ATTESTED', 'VIOLATED', 'UNQUALIFIED', 'UNAVAILABLE')
    ),
    violation_reason TEXT,
    attested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT attempt_cpu_attestation_pk PRIMARY KEY (pair_id, attempt_no),
    CONSTRAINT attempt_cpu_attestation_pair_id_fk
        FOREIGN KEY (pair_id) REFERENCES starexec.job_pairs(id) ON DELETE CASCADE
);

CREATE INDEX idx_attempt_cpu_attestation_status_attested_at
    ON starexec.attempt_cpu_attestation (attestation_status, attested_at);
