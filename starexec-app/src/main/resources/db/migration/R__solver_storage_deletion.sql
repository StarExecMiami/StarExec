-- Conserved input and output require registered cleanup, not legacy path deletion.
CREATE OR REPLACE FUNCTION starexec.solver_storage_requires_retention(
    _solver INTEGER, _path TEXT) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM starexec.jobpair_stage_data WHERE solver_id=_solver) OR EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a
        WHERE (a.execution_token IS NOT NULL OR a.storage_root IS NOT NULL OR a.artifact_path IS NOT NULL)
          AND (a.state <> 'DELETED' OR EXISTS (
              SELECT 1 FROM starexec.solvers s WHERE s.build_output_artifact_id=a.id))
          AND (a.origin_solver_id=_solver OR EXISTS (
              SELECT 1 FROM unnest(ARRAY[a.source_path,a.staging_path,a.storage_root,a.artifact_path]) protected,
                            unnest(ARRAY[_path,CASE WHEN right(_path,4)='_src' THEN _path ELSE _path || '_src' END]) candidate
              WHERE protected=candidate OR starts_with(protected,candidate || '/')
                    OR starts_with(candidate,protected || '/'))));
$$ LANGUAGE SQL STABLE;

-- The tombstone retains its path and existing charge until cleanup is confirmed.
CREATE OR REPLACE FUNCTION starexec.request_solver_storage_deletion(_solver INTEGER)
RETURNS TABLE(solver_id INTEGER, owner_id INTEGER, legacy_path TEXT, charged_bytes BIGINT) AS $$
DECLARE
    _owner INTEGER;
    _s starexec.solvers%ROWTYPE;
BEGIN
    SELECT user_id INTO _owner FROM starexec.solvers WHERE id=_solver;
    IF NOT FOUND THEN RAISE EXCEPTION 'Solver % not found', _solver USING ERRCODE='P0002'; END IF;
    PERFORM id FROM starexec.users WHERE id=_owner FOR UPDATE;
    SELECT * INTO _s FROM starexec.solvers WHERE id=_solver FOR UPDATE;
    IF NOT FOUND OR _s.user_id IS DISTINCT FROM _owner THEN
        RAISE EXCEPTION 'Solver owner changed during deletion' USING ERRCODE='40001';
    END IF;
    IF _s.path IS NULL OR left(_s.path,1)<>'/' OR _s.path='/'
        OR _s.path ~ '(^|/)[.]{1,2}(/|$)' OR strpos(_s.path,'//')>0 OR right(_s.path,1)='/' THEN
        RAISE EXCEPTION 'Solver cleanup requires an absolute normalized non-root path';
    END IF;
    UPDATE starexec.solvers SET deleted=true WHERE id=_solver;
    solver_id := _solver;
    owner_id := _owner;
    charged_bytes := _s.disk_size;
    legacy_path := CASE WHEN starexec.solver_storage_requires_retention(_solver,_s.path) THEN NULL ELSE _s.path END;
    RETURN NEXT;
END;
$$ LANGUAGE plpgsql;

-- Release the captured legacy charge only after the caller confirms file absence.
CREATE OR REPLACE FUNCTION starexec.settle_legacy_solver_deletion(
    _solver INTEGER, _owner INTEGER, _path TEXT, _bytes BIGINT) RETURNS BOOLEAN AS $$
DECLARE
    _s starexec.solvers%ROWTYPE;
BEGIN
    PERFORM id FROM starexec.users WHERE id=_owner FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _s FROM starexec.solvers WHERE id=_solver FOR UPDATE;
    IF NOT FOUND OR _s.user_id IS DISTINCT FROM _owner OR _s.deleted IS NOT TRUE
        OR _s.path IS DISTINCT FROM _path OR _bytes IS NULL OR _bytes<0
        OR starexec.solver_storage_requires_retention(_solver,_s.path) THEN RETURN FALSE; END IF;
    IF _s.disk_size=0 THEN RETURN TRUE; END IF;
    IF _s.disk_size IS DISTINCT FROM _bytes THEN RETURN FALSE; END IF;
    UPDATE starexec.users SET disk_size=disk_size-_bytes WHERE id=_owner AND disk_size>=_bytes;
    IF NOT FOUND THEN RAISE EXCEPTION 'Solver deletion charge exceeds owner usage'; END IF;
    UPDATE starexec.solvers SET disk_size=0 WHERE id=_solver;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.request_recycled_solver_deletions(_user INTEGER)
RETURNS TABLE(solver_id INTEGER, owner_id INTEGER, legacy_path TEXT, charged_bytes BIGINT) AS $$
DECLARE
    _solver INTEGER;
BEGIN
    PERFORM id FROM starexec.users WHERE id=_user FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'User % not found', _user USING ERRCODE='P0002'; END IF;
    FOR _solver IN SELECT s.id FROM starexec.solvers s
        WHERE s.user_id=_user AND s.recycled IS TRUE AND (s.deleted IS NOT TRUE OR s.disk_size>0)
        ORDER BY s.id FOR UPDATE
    LOOP
        RETURN QUERY SELECT * FROM starexec.request_solver_storage_deletion(_solver);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Preserve the scalar/void SQL interfaces; filesystem cleanup uses the typed requests.
CREATE OR REPLACE FUNCTION starexec.SetSolverToDeletedById(_solverId INTEGER) RETURNS TEXT AS $$
    SELECT legacy_path FROM starexec.request_solver_storage_deletion(_solverId);
$$ LANGUAGE SQL;

CREATE OR REPLACE FUNCTION starexec.SetRecycledSolversToDeleted(_userId INTEGER) RETURNS VOID AS $$
BEGIN
    PERFORM * FROM starexec.request_recycled_solver_deletions(_userId);
END;
$$ LANGUAGE plpgsql;

-- Metadata retirement cannot discard a retained path, charge or input reference.
CREATE OR REPLACE FUNCTION starexec.remove_deleted_solver_metadata(_solver INTEGER) RETURNS BOOLEAN AS $$
DECLARE
    _owner INTEGER;
    _s starexec.solvers%ROWTYPE;
BEGIN
    SELECT user_id INTO _owner FROM starexec.solvers WHERE id=_solver;
    IF NOT FOUND THEN RETURN TRUE; END IF;
    PERFORM id FROM starexec.users WHERE id=_owner FOR UPDATE;
    SELECT * INTO _s FROM starexec.solvers WHERE id=_solver FOR UPDATE;
    IF NOT FOUND THEN RETURN TRUE; END IF;
    IF _s.user_id IS DISTINCT FROM _owner OR _s.deleted IS NOT TRUE OR _s.disk_size IS DISTINCT FROM 0
        OR starexec.solver_storage_requires_retention(_solver,_s.path)
        OR EXISTS (SELECT 1 FROM starexec.solver_assoc WHERE solver_id=_solver) THEN RETURN FALSE; END IF;
    DELETE FROM starexec.solvers WHERE id=_solver;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.RemoveSolverFromDatabase(_id INTEGER) RETURNS VOID AS $$
DECLARE
    _owner INTEGER;
    _s starexec.solvers%ROWTYPE;
BEGIN
    SELECT user_id INTO _owner FROM starexec.solvers WHERE id=_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Solver % not found', _id USING ERRCODE='P0002'; END IF;
    PERFORM id FROM starexec.users WHERE id=_owner FOR UPDATE;
    SELECT * INTO _s FROM starexec.solvers WHERE id=_id FOR UPDATE;
    IF NOT FOUND OR _s.user_id IS DISTINCT FROM _owner THEN
        RAISE EXCEPTION 'Solver owner changed during removal' USING ERRCODE='40001';
    END IF;
    IF _s.deleted IS TRUE AND _s.disk_size IS DISTINCT FROM 0
        OR starexec.solver_storage_requires_retention(_id,_s.path) THEN
        RAISE EXCEPTION 'Solver storage is still retained or unsettled' USING ERRCODE='55006';
    END IF;
    DELETE FROM starexec.solvers WHERE id=_id;
END;
$$ LANGUAGE plpgsql;
