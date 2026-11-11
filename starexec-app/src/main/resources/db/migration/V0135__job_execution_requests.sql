-- Exact creation consumption remains after cancellation, live-row deletion and storage reuse.
CREATE TABLE starexec.job_execution_requests (
    execution_token UUID PRIMARY KEY CONSTRAINT job_execution_request_claim_fk
        REFERENCES starexec.execution_storage_claims(execution_token) ON DELETE RESTRICT,
    -- Historical identities deliberately survive deletion of the current job, pair and owner.
    pair_id INTEGER NOT NULL CHECK (pair_id>0),
    attempt_no INTEGER NOT NULL CHECK (attempt_no>0),
    owner_id INTEGER NOT NULL CHECK (owner_id>0),
    resource_key TEXT NOT NULL CHECK (btrim(resource_key)<>''),
    resource_identity TEXT NOT NULL CHECK (btrim(resource_identity)<>''),
    execution_id INTEGER NOT NULL CHECK (execution_id>0),
    container_name TEXT NOT NULL UNIQUE
        CHECK (container_name='starexec-pair-' || pair_id::text || '-attempt-' || attempt_no::text),
    image_identity TEXT NOT NULL CHECK (image_identity ~ '^sha256:[0-9a-f]{64}$'),
    preparation_sha256 TEXT NOT NULL CHECK (preparation_sha256 ~ '^[0-9a-f]{64}$'),
    transport TEXT NOT NULL CHECK (transport IN ('java','curl')),
    request_payload TEXT NOT NULL CHECK (octet_length(request_payload) BETWEEN 2 AND 16777216
        AND json_typeof(request_payload::json)='object'),
    request_sha256 TEXT NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'
        AND request_sha256=encode(sha256(convert_to(request_payload,'UTF8')),'hex')),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (pair_id,attempt_no)
);

CREATE FUNCTION starexec.retain_job_execution_request() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='TRUNCATE' OR NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Ordinary creation consumption history is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER job_execution_request_history BEFORE UPDATE OR DELETE
    ON starexec.job_execution_requests FOR EACH ROW
    EXECUTE FUNCTION starexec.retain_job_execution_request();
CREATE TRIGGER job_execution_request_truncate BEFORE TRUNCATE
    ON starexec.job_execution_requests FOR EACH STATEMENT
    EXECUTE FUNCTION starexec.retain_job_execution_request();

