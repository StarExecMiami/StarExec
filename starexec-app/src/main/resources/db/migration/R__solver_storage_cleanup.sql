CREATE OR REPLACE FUNCTION starexec.solver_cleanup_paths_unreferenced(_artifact BIGINT, _source TEXT)
RETURNS BOOLEAN AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a, starexec.solver_build_artifacts b,
            unnest(ARRAY[a.staging_path,a.storage_root,_source]) target,
            unnest(ARRAY[b.source_path,b.staging_path,b.storage_root,b.artifact_path]) protected
        WHERE a.id=_artifact AND b.id<>a.id AND b.state<>'DELETED'
          AND (protected=target OR starts_with(protected,target || '/') OR starts_with(target,protected || '/')))
    AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a, starexec.solvers s,
            unnest(ARRAY[a.staging_path,a.storage_root,_source]) target,
            unnest(ARRAY[s.path,s.build_source_path]) protected
        WHERE a.id=_artifact AND NOT (s.id=a.origin_solver_id AND s.deleted IS TRUE)
          AND (protected=target OR starts_with(protected,target || '/') OR starts_with(target,protected || '/')))
    AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a, starexec.benchmarks b,
            unnest(ARRAY[a.staging_path,a.storage_root,_source]) target
        WHERE a.id=_artifact AND
            (b.path=target OR starts_with(b.path,target || '/') OR starts_with(target,b.path || '/')));
$$ LANGUAGE SQL STABLE;

-- Commit the claim before filesystem work. Never infer physical stop from a terminal status.
CREATE OR REPLACE FUNCTION starexec.claim_solver_build_cleanup(_artifact BIGINT, _token UUID)
RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _source TEXT;
    _bytes BIGINT := 0;
BEGIN
    IF _token IS NULL THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM starexec.lock_user_solver_storage_for_deletion(_a.origin_owner_id);
    SELECT * INTO _s FROM starexec.solvers WHERE id=_a.origin_solver_id FOR UPDATE;
    IF FOUND AND _s.user_id IS DISTINCT FROM _a.origin_owner_id THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE;
    IF _a.cleanup_token IS NOT NULL AND _a.cleanup_token IS DISTINCT FROM _token THEN RETURN FALSE; END IF;
    IF _a.cleanup_finished_at IS NOT NULL THEN RETURN _a.state='DELETED'; END IF;
    IF _a.execution_token IS NULL OR _a.staging_path IS NULL OR _a.engine_identity IS NULL
        OR _a.execution_stopped_at IS NULL OR _a.execution_stop_receipt_recorded_at IS NULL
        OR NOT starexec.solver_build_stop_receipt_matches(_a.execution_stop_receipt_payload,
            _a.execution_stop_receipt_sha256,_a.id,_a.origin_pair_id,_a.attempt_no,_a.engine_identity)
        OR NOT COALESCE(_a.execution_stop_receipt_payload::jsonb->>'backend' IN ('podman','kubernetes'),FALSE)
        OR _a.engine_identity NOT LIKE (_a.execution_stop_receipt_payload::jsonb->>'backend') || ':%'
        OR _a.state='DELETED'
        OR EXISTS (SELECT 1 FROM starexec.jobpair_stage_data WHERE solver_id=_a.origin_solver_id)
        THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM starexec.solvers s WHERE s.build_output_artifact_id=_artifact
        AND (s.id<>_a.origin_solver_id OR s.deleted IS NOT TRUE)) THEN RETURN FALSE; END IF;
    IF _a.state='PUBLISHED' AND _s.deleted IS NOT TRUE THEN RETURN FALSE; END IF;

    IF _a.cleanup_token IS NOT NULL THEN
        _source := _a.cleanup_source_path;
        _bytes := _a.cleanup_solver_bytes;
        IF _source IS NOT NULL AND (_s.id IS NULL OR _s.deleted IS NOT TRUE
            OR _s.disk_size IS DISTINCT FROM _bytes) THEN RETURN FALSE; END IF;
    ELSIF _s.deleted IS TRUE AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts b WHERE b.origin_solver_id=_a.origin_solver_id
            AND b.id<>_artifact AND b.state<>'DELETED'
            AND (b.execution_token IS NOT NULL OR b.storage_root IS NOT NULL OR b.artifact_path IS NOT NULL)) THEN
        IF COALESCE(_s.build_source_path,_a.source_path) IS DISTINCT FROM _a.source_path
            OR _s.disk_size IS NULL OR _s.disk_size<0 THEN RETURN FALSE; END IF;
        _source := _a.source_path;
        _bytes := _s.disk_size;
    END IF;
    IF NOT starexec.solver_cleanup_paths_unreferenced(_artifact,_source) THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY[_a.staging_path,_a.storage_root]) target
        WHERE target=_a.source_path OR starts_with(target,_a.source_path || '/')
            OR starts_with(_a.source_path,target || '/')) THEN RETURN FALSE; END IF;

    IF _a.cleanup_token IS NOT NULL THEN RETURN TRUE; END IF;
    UPDATE starexec.solvers SET build_output_artifact_id=NULL
        WHERE id=_a.origin_solver_id AND deleted IS TRUE AND build_output_artifact_id=_artifact;
    UPDATE starexec.solver_build_artifacts SET
        state=CASE WHEN state='PUBLISHED' THEN 'RETIRED' WHEN state='RETIRED' THEN state ELSE 'REJECTED' END,
        cleanup_token=_token,cleanup_started_at=clock_timestamp(),
        cleanup_source_path=_source,cleanup_solver_bytes=_bytes WHERE id=_artifact;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Trusted callers must positively confirm every registered path absent before this settlement.
CREATE OR REPLACE FUNCTION starexec.finish_solver_build_cleanup(_artifact BIGINT, _token UUID)
RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
BEGIN
    IF NOT starexec.claim_solver_build_cleanup(_artifact,_token) THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE;
    IF _a.cleanup_finished_at IS NOT NULL THEN RETURN TRUE; END IF;
    UPDATE starexec.solver_build_artifacts SET state='DELETED',cleanup_finished_at=clock_timestamp() WHERE id=_artifact;
    IF _a.cleanup_source_path IS NOT NULL THEN
        UPDATE starexec.users SET disk_size=disk_size-_a.cleanup_solver_bytes
            WHERE id=_a.origin_owner_id AND disk_size>=_a.cleanup_solver_bytes;
        IF NOT FOUND THEN RAISE EXCEPTION 'Solver cleanup charge exceeds owner usage'; END IF;
        UPDATE starexec.solvers SET disk_size=0 WHERE id=_a.origin_solver_id
            AND user_id=_a.origin_owner_id AND deleted IS TRUE AND disk_size=_a.cleanup_solver_bytes;
        IF NOT FOUND THEN RAISE EXCEPTION 'Solver cleanup accounting identity changed'; END IF;
    END IF;
    PERFORM starexec.release_solver_build_storage_bytes(_artifact,_token);
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
