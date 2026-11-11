-- Phase history references conserved ownership rather than deletable live job rows.
CREATE TABLE starexec.job_execution_receipts (
    execution_token UUID NOT NULL CONSTRAINT job_execution_receipt_claim_fk
        REFERENCES starexec.execution_storage_claims(execution_token) ON DELETE RESTRICT,
    receipt_kind TEXT NOT NULL CHECK (receipt_kind IN ('CREATED','RELEASE','STOP','OUTPUT','CLEANUP_REQUEST','CLEANUP')),
    operation_token UUID NOT NULL CHECK (operation_token<>'00000000-0000-0000-0000-000000000000'::uuid),
    engine_identity TEXT CHECK (engine_identity IS NULL OR engine_identity LIKE 'podman:%' OR engine_identity LIKE 'kubernetes:%'),
    receipt_payload TEXT NOT NULL CHECK (octet_length(receipt_payload) BETWEEN 2 AND 16777216
        AND json_typeof(receipt_payload::json)='object'),
    receipt_sha256 TEXT NOT NULL CHECK (receipt_sha256 ~ '^[0-9a-f]{64}$'
        AND receipt_sha256=encode(sha256(convert_to(receipt_payload,'UTF8')),'hex')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (execution_token,receipt_kind),
    UNIQUE (operation_token,receipt_kind)
);

CREATE FUNCTION starexec.retain_job_execution_receipt() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='TRUNCATE' OR NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Ordinary execution phase history is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER job_execution_receipt_history BEFORE UPDATE OR DELETE
    ON starexec.job_execution_receipts FOR EACH ROW EXECUTE FUNCTION starexec.retain_job_execution_receipt();
CREATE TRIGGER job_execution_receipt_truncate BEFORE TRUNCATE
    ON starexec.job_execution_receipts FOR EACH STATEMENT EXECUTE FUNCTION starexec.retain_job_execution_receipt();

