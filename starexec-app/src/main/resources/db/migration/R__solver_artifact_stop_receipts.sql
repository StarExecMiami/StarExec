-- This envelope check binds conserved bytes to an execution. The trusted backend
-- observer must establish the physical stop; JSON supplied by a runner cannot.
CREATE OR REPLACE FUNCTION starexec.solver_build_stop_receipt_matches(
    _payload TEXT, _digest TEXT, _artifact BIGINT, _pair INTEGER, _attempt INTEGER, _engine TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    _receipt JSONB;
    _observed TIMESTAMPTZ;
BEGIN
    IF _payload IS NULL OR _digest IS NULL OR _engine IS NULL
        OR _digest <> encode(sha256(convert_to(_payload, 'UTF8')), 'hex') THEN RETURN FALSE; END IF;
    _receipt := _payload::jsonb;
    IF jsonb_typeof(_receipt) <> 'object' THEN RETURN FALSE; END IF;
    _observed := (_receipt->>'observed_at')::timestamptz;
    RETURN COALESCE(
        _receipt->'version' = '1'::jsonb AND jsonb_typeof(_receipt->'version') = 'number'
        AND _receipt->'artifact_id' = to_jsonb(_artifact) AND jsonb_typeof(_receipt->'artifact_id') = 'number'
        AND _receipt->'pair_id' = to_jsonb(_pair) AND jsonb_typeof(_receipt->'pair_id') = 'number'
        AND _receipt->'attempt_no' = to_jsonb(_attempt) AND jsonb_typeof(_receipt->'attempt_no') = 'number'
        AND _receipt->>'engine_identity' = _engine AND jsonb_typeof(_receipt->'engine_identity') = 'string'
        AND _receipt->>'backend' IN ('local', 'podman', 'kubernetes')
        AND jsonb_typeof(_receipt->'resource_id') = 'string' AND length(btrim(_receipt->>'resource_id')) > 0
        AND jsonb_typeof(_receipt->'observed_at') = 'string' AND _observed IS NOT NULL
        AND jsonb_typeof(_receipt->'facts') = 'object' AND _receipt->'facts' <> '{}'::jsonb,
        FALSE);
EXCEPTION WHEN invalid_text_representation OR invalid_datetime_format OR datetime_field_overflow THEN
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.record_solver_build_stop_receipt(
    _pair INTEGER, _attempt INTEGER, _token UUID, _engine TEXT, _payload TEXT, _digest TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    _artifact starexec.solver_build_artifacts%ROWTYPE;
BEGIN
    SELECT * INTO _artifact FROM starexec.solver_build_artifacts
    WHERE origin_pair_id = _pair AND attempt_no = _attempt AND execution_token = _token
        AND engine_identity = _engine FOR UPDATE;
    IF NOT FOUND OR NOT starexec.solver_build_stop_receipt_matches(
        _payload, _digest, _artifact.id, _pair, _attempt, _engine) THEN RETURN FALSE; END IF;
    IF _artifact.execution_stop_receipt_payload IS NOT NULL THEN
        RETURN _artifact.execution_stop_receipt_payload = _payload
            AND _artifact.execution_stop_receipt_sha256 = _digest;
    END IF;
    IF _artifact.state NOT IN ('PLANNED', 'STAGED', 'REJECTED') THEN RETURN FALSE; END IF;
    UPDATE starexec.solver_build_artifacts SET
        execution_stopped_at = COALESCE(execution_stopped_at, clock_timestamp()),
        execution_stop_receipt_payload = _payload, execution_stop_receipt_sha256 = _digest,
        execution_stop_receipt_recorded_at = clock_timestamp()
    WHERE id = _artifact.id;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.publish_solver_build_output(_artifact BIGINT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _artifact;
    IF NOT FOUND OR _a.execution_token IS NULL OR _a.engine_identity IS NULL
        OR _a.execution_stopped_at IS NULL OR _a.staging_path IS NULL OR _a.storage_root IS NULL
        OR _a.artifact_path <> _a.storage_root || '/solver'
        OR _a.execution_stop_receipt_recorded_at IS NULL
        OR NOT starexec.solver_build_stop_receipt_matches(_a.execution_stop_receipt_payload,
            _a.execution_stop_receipt_sha256, _a.id, _a.origin_pair_id, _a.attempt_no, _a.engine_identity)
        THEN RETURN FALSE; END IF;
    IF _a.execution_stop_receipt_payload::jsonb #>> '{facts,disposition}' = 'never_started'
        THEN RETURN FALSE; END IF;
    RETURN starexec.publish_solver_build_artifact(_artifact);
END;
$$ LANGUAGE plpgsql;
