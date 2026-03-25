CREATE TABLE starexec.job_pair_attempts (
    pair_id INTEGER PRIMARY KEY,
    current_attempt_no INTEGER NOT NULL DEFAULT 1 CHECK (current_attempt_no >= 1),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT job_pair_attempts_pair_id_fk
        FOREIGN KEY (pair_id) REFERENCES starexec.job_pairs(id) ON DELETE CASCADE
);

CREATE TABLE starexec.job_pair_repro_manifests (
    pair_id INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    state SMALLINT NOT NULL,
    provenance SMALLINT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    manifest_json JSONB,
    manifest_sha256 CHAR(64),
    source_status_code SMALLINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    finalized_at TIMESTAMP,
    CONSTRAINT job_pair_repro_manifests_pk PRIMARY KEY (pair_id, attempt_no),
    CONSTRAINT job_pair_repro_manifests_pair_id_fk
        FOREIGN KEY (pair_id) REFERENCES starexec.job_pairs(id) ON DELETE CASCADE,
    CONSTRAINT job_pair_repro_manifest_state_payload_ck CHECK (
        (state IN (2, 3) AND manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND finalized_at IS NOT NULL)
        OR (state IN (0, 1, 4))
    )
);

CREATE INDEX idx_job_pair_repro_manifests_pair_attempt_state
    ON starexec.job_pair_repro_manifests (pair_id, attempt_no, state);

CREATE OR REPLACE FUNCTION starexec.ensure_pair_attempt_row()
RETURNS trigger AS $$
BEGIN
    INSERT INTO starexec.job_pair_attempts (pair_id, current_attempt_no, updated_at)
    VALUES (NEW.id, 1, NOW())
    ON CONFLICT (pair_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ensure_pair_attempt_row
AFTER INSERT ON starexec.job_pairs
FOR EACH ROW
EXECUTE FUNCTION starexec.ensure_pair_attempt_row();

CREATE OR REPLACE FUNCTION starexec.prevent_final_manifest_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.state IN (2, 3) THEN
        RAISE EXCEPTION 'final reproducibility manifest is immutable for pair %, attempt %', OLD.pair_id, OLD.attempt_no;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_final_manifest_update
BEFORE UPDATE ON starexec.job_pair_repro_manifests
FOR EACH ROW
EXECUTE FUNCTION starexec.prevent_final_manifest_mutation();

CREATE OR REPLACE FUNCTION starexec.prevent_final_manifest_delete()
RETURNS trigger AS $$
BEGIN
    -- Allow deletes that occur as part of FK cascade chains.
    IF pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;

    IF OLD.state IN (2, 3) THEN
        RAISE EXCEPTION 'final reproducibility manifest cannot be deleted for pair %, attempt %', OLD.pair_id, OLD.attempt_no;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_final_manifest_delete
BEFORE DELETE ON starexec.job_pair_repro_manifests
FOR EACH ROW
EXECUTE FUNCTION starexec.prevent_final_manifest_delete();

INSERT INTO starexec.job_pair_attempts (pair_id, current_attempt_no)
SELECT id, 1
FROM starexec.job_pairs
ON CONFLICT (pair_id) DO NOTHING;
