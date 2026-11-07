-- Book the entire bounded write before filesystem allocation. Refuse lock-order contention.
CREATE OR REPLACE FUNCTION starexec.reserve_solver_build_storage_bytes(
    _artifact BIGINT, _token UUID, _kind TEXT, _path TEXT, _limit BIGINT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _u starexec.users%ROWTYPE;
    _r starexec.solver_build_storage_charges%ROWTYPE;
BEGIN
    IF _token IS NULL OR _kind IS NULL OR _kind NOT IN ('STAGING','OUTPUT') OR _limit IS NULL OR _limit<0
        OR _path IS NULL OR left(_path,1)<>'/' OR _path='/' OR _path ~ '(^|/)[.]{1,2}(/|$)'
        OR strpos(_path,'//')>0 OR right(_path,1)='/' THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _u FROM starexec.users WHERE id=_a.origin_owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE NOWAIT;
    IF _a.execution_token IS DISTINCT FROM _token OR _a.cleanup_token IS NOT NULL
        OR _a.state NOT IN ('PLANNED','STAGED') OR _a.solver_id IS NULL THEN RETURN FALSE; END IF;
    IF _kind='STAGING' THEN
        IF _a.staging_path IS DISTINCT FROM _path OR _a.engine_identity IS NOT NULL
            OR _a.execution_stopped_at IS NOT NULL THEN RETURN FALSE; END IF;
    ELSE
        IF _a.engine_identity IS NULL OR _a.execution_stopped_at IS NULL
            OR NOT starexec.solver_build_stop_receipt_matches(_a.execution_stop_receipt_payload,
                _a.execution_stop_receipt_sha256,_a.id,_a.origin_pair_id,_a.attempt_no,_a.engine_identity)
            OR NOT COALESCE(_a.execution_stop_receipt_payload::jsonb->>'backend' IN ('podman','kubernetes'),FALSE)
            OR _a.execution_stop_receipt_payload::jsonb ? 'disposition'
            OR _a.engine_identity NOT LIKE (_a.execution_stop_receipt_payload::jsonb->>'backend') || ':%'
            OR _a.storage_root IS NOT NULL AND _a.storage_root IS DISTINCT FROM _path
            OR EXISTS (SELECT 1 FROM unnest(ARRAY[_a.source_path,_a.staging_path]) p
                WHERE _path=p OR starts_with(_path,p || '/') OR starts_with(p,_path || '/')) THEN RETURN FALSE; END IF;
    END IF;
    SELECT * INTO _r FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact AND storage_kind=_kind FOR UPDATE;
    IF FOUND THEN RETURN _r.owner_id=_a.origin_owner_id AND _r.path=_path
        AND _r.limit_bytes=_limit AND _r.released_at IS NULL; END IF;
    IF _u.disk_size IS NULL OR _u.disk_quota IS NULL OR _u.disk_size<0 OR _u.disk_quota<0
        OR _u.disk_size>_u.disk_quota THEN RETURN FALSE; END IF;
    IF _limit>_u.disk_quota-_u.disk_size THEN RETURN FALSE; END IF;
    UPDATE starexec.users SET disk_size=disk_size+_limit WHERE id=_u.id;
    INSERT INTO starexec.solver_build_storage_charges
        (artifact_id,storage_kind,owner_id,path,limit_bytes,charged_bytes)
        VALUES (_artifact,_kind,_u.id,_path,_limit,_limit);
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Release only unused copy capacity after exact output bytes have been conserved.
CREATE OR REPLACE FUNCTION starexec.reconcile_solver_build_output_bytes(_artifact BIGINT)
RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _r starexec.solver_build_storage_charges%ROWTYPE;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM id FROM starexec.users WHERE id=_a.origin_owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE NOWAIT;
    SELECT * INTO _r FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact AND storage_kind='OUTPUT' FOR UPDATE;
    IF NOT FOUND OR _r.released_at IS NOT NULL OR _a.cleanup_token IS NOT NULL
        OR _r.owner_id<>_a.origin_owner_id OR _r.path IS DISTINCT FROM _a.storage_root
        OR _a.validated_at IS NULL OR _a.byte_size IS NULL OR _a.byte_size>_r.limit_bytes THEN RETURN FALSE; END IF;
    IF _r.verified_at IS NOT NULL THEN RETURN _r.verified_bytes=_a.byte_size; END IF;
    UPDATE starexec.users SET disk_size=disk_size-(_r.charged_bytes-_a.byte_size)
        WHERE id=_r.owner_id AND disk_size>=_r.charged_bytes;
    IF NOT FOUND THEN RAISE EXCEPTION 'Build copy charge exceeds owner usage'; END IF;
    UPDATE starexec.solver_build_storage_charges SET charged_bytes=_a.byte_size,
        verified_bytes=_a.byte_size,verified_at=clock_timestamp()
        WHERE artifact_id=_artifact AND storage_kind='OUTPUT';
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Called only inside the registered cleanup transaction after positive path absence.
CREATE OR REPLACE FUNCTION starexec.release_solver_build_storage_bytes(_artifact BIGINT, _token UUID)
RETURNS VOID AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _bytes BIGINT;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND OR _token IS NULL OR _a.cleanup_token IS DISTINCT FROM _token
        OR _a.state<>'DELETED' OR _a.cleanup_finished_at IS NULL THEN
        RAISE EXCEPTION 'Build storage settlement requires matching completed cleanup';
    END IF;
    PERFORM id FROM starexec.users WHERE id=_a.origin_owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'Build storage accounting owner is missing'; END IF;
    PERFORM storage_kind FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact ORDER BY storage_kind FOR UPDATE;
    SELECT COALESCE(sum(charged_bytes),0) INTO _bytes FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact AND released_at IS NULL;
    UPDATE starexec.users SET disk_size=disk_size-_bytes WHERE id=_a.origin_owner_id AND disk_size>=_bytes;
    IF NOT FOUND THEN RAISE EXCEPTION 'Build storage settlement exceeds owner usage'; END IF;
    UPDATE starexec.solver_build_storage_charges SET released_at=clock_timestamp()
        WHERE artifact_id=_artifact AND released_at IS NULL;
END;
$$ LANGUAGE plpgsql;
