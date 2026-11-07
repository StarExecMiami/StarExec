-- Failed build logs never become executable solver artifacts. Recheck the same
-- current-owner fences as publication before deciding a solver's failure status.
CREATE OR REPLACE FUNCTION starexec.record_failed_solver_build_output(
    _artifact BIGINT, _token UUID, _engine TEXT, _receipt_digest TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _job INTEGER;
    _owner INTEGER;
    _status INTEGER;
    _attempt INTEGER;
    _manifest JSONB;
    _backend TEXT;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _artifact;
    IF NOT FOUND OR _a.solver_id IS NULL OR _a.pair_id IS NULL THEN RETURN FALSE; END IF;
    SELECT job_id INTO _job FROM starexec.job_pairs WHERE id = _a.pair_id;
    SELECT user_id INTO _owner FROM starexec.jobs
        WHERE id = _job AND buildjob AND deleted IS NOT TRUE AND killed IS NOT TRUE FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT status_code INTO _status FROM starexec.job_pairs
        WHERE id = _a.pair_id AND job_id = _job FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT current_attempt_no INTO _attempt FROM starexec.job_pair_attempts
        WHERE pair_id = _a.pair_id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _s FROM starexec.solvers WHERE id = _a.solver_id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _artifact FOR UPDATE;

    IF _s.deleted IS TRUE OR _s.recycled IS TRUE OR _owner IS DISTINCT FROM _s.user_id
        OR _a.origin_owner_id IS DISTINCT FROM _s.user_id OR _a.solver_id IS DISTINCT FROM _s.id
        OR _a.pair_id IS NULL OR _attempt IS DISTINCT FROM _a.attempt_no
        OR _s.build_generation IS DISTINCT FROM _a.build_generation
        OR _s.path IS DISTINCT FROM _a.source_path OR NOT COALESCE(_s.build_status IN (0, 3), FALSE)
        OR NOT COALESCE(starexec.IsTerminalPairStatus(_status) AND _status NOT IN (7, 21), FALSE)
        OR NOT EXISTS (
            SELECT 1 FROM starexec.jobpair_stage_data sd
            JOIN starexec.job_pairs jp ON jp.id = sd.jobpair_id
            WHERE jp.id = _a.pair_id AND sd.stage_number = jp.primary_jobpair_data
                AND sd.solver_id = _s.id)
        OR EXISTS (
            SELECT 1 FROM starexec.jobs j JOIN starexec.job_pairs jp ON jp.job_id = j.id
            JOIN starexec.jobpair_stage_data sd ON sd.jobpair_id = jp.id
                AND sd.stage_number = jp.primary_jobpair_data
            WHERE j.buildjob AND sd.solver_id = _s.id AND j.id > _job) THEN RETURN FALSE; END IF;

    IF _a.state NOT IN ('STAGED', 'REJECTED') OR _a.execution_token IS DISTINCT FROM _token OR _token IS NULL
        OR _engine IS NULL OR _a.engine_identity IS DISTINCT FROM _engine OR _a.execution_stopped_at IS NULL
        OR _a.execution_stop_receipt_recorded_at IS NULL OR _a.execution_stop_receipt_sha256 IS DISTINCT FROM _receipt_digest
        OR _a.staging_path IS NULL OR _a.storage_root IS NULL
        OR _a.artifact_path IS DISTINCT FROM _a.storage_root || '/solver'
        OR _a.validated_at IS NULL OR _a.byte_size IS NULL OR _a.manifest_payload IS NULL OR _a.manifest_sha256 IS NULL
        OR NOT starexec.solver_build_stop_receipt_matches(_a.execution_stop_receipt_payload,
            _receipt_digest, _a.id, _a.origin_pair_id, _a.attempt_no, _engine) THEN RETURN FALSE; END IF;
    _backend := _a.execution_stop_receipt_payload::jsonb->>'backend';
    IF _backend NOT IN ('podman', 'kubernetes') OR _engine NOT LIKE _backend || ':%'
        OR (_a.execution_stop_receipt_payload::jsonb->'facts') ? 'disposition' THEN RETURN FALSE; END IF;
    _manifest := _a.manifest_payload::jsonb;
    IF _manifest->'version' IS DISTINCT FROM '1'::jsonb
        OR jsonb_typeof(_manifest->'entries') IS DISTINCT FROM 'array' THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(_manifest->'entries') e WHERE NOT COALESCE(
        e->>'path' = 'logs' AND e->>'kind' = 'directory'
        OR e->>'path' LIKE 'logs/%' AND e->>'kind' IN ('directory', 'file', 'link'), FALSE)) THEN RETURN FALSE; END IF;

    IF _a.state = 'REJECTED' THEN RETURN _s.build_status = 3; END IF;
    UPDATE starexec.solvers SET build_status = 3 WHERE id = _s.id;
    UPDATE starexec.solver_build_artifacts SET state = 'REJECTED' WHERE id = _artifact;
    RETURN TRUE;
EXCEPTION WHEN invalid_text_representation THEN
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;
