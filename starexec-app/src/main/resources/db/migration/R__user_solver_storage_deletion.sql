-- Acquire build identities only after the caller has completed its job-stop sweep.
-- Refuse contention rather than wait while holding a different cascade lock order.
CREATE OR REPLACE FUNCTION starexec.lock_user_solver_storage_for_deletion(_user INTEGER)
RETURNS VOID AS $$
BEGIN
    PERFORM starexec.LockUserJobLifecycle(_user);
    PERFORM id FROM starexec.jobs WHERE user_id=_user ORDER BY id FOR UPDATE NOWAIT;
    PERFORM jp.id FROM starexec.job_pairs jp JOIN starexec.jobs j ON j.id=jp.job_id
        WHERE j.user_id=_user ORDER BY jp.id FOR UPDATE OF jp NOWAIT;
    PERFORM a.pair_id FROM starexec.job_pair_attempts a
        JOIN starexec.job_pairs jp ON jp.id=a.pair_id JOIN starexec.jobs j ON j.id=jp.job_id
        WHERE j.user_id=_user ORDER BY a.pair_id FOR UPDATE OF a NOWAIT;
    PERFORM id FROM starexec.users WHERE id=_user FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'User % not found', _user USING ERRCODE='P0002'; END IF;
    PERFORM id FROM starexec.solvers WHERE user_id=_user ORDER BY id FOR UPDATE NOWAIT;
END;
$$ LANGUAGE plpgsql;

-- Account deletion must not orphan retained bytes, charges or surviving inputs.
CREATE OR REPLACE FUNCTION starexec.assert_user_solver_storage_deletable(
    _user INTEGER, _roots TEXT[] DEFAULT ARRAY[]::TEXT[]) RETURNS VOID AS $$
DECLARE
    _paths TEXT[];
BEGIN
    PERFORM starexec.lock_user_solver_storage_for_deletion(_user);
    IF EXISTS (SELECT 1 FROM starexec.solver_build_helpers h WHERE h.owner_id=_user AND h.cleanup_finished_at IS NULL
        AND EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a
            WHERE a.helper_benchmark_id=h.benchmark_id AND a.execution_token IS NOT NULL)) THEN
        RAISE EXCEPTION 'Managed build helper cleanup is unsettled' USING ERRCODE='55006';
    END IF;
    _paths := COALESCE(_roots,ARRAY[]::TEXT[]) || ARRAY(
        SELECT p FROM starexec.solvers s,
            unnest(ARRAY[s.path,s.build_source_path,
                CASE WHEN right(s.path,4)='_src' THEN s.path ELSE s.path || '_src' END]) p
        WHERE s.user_id=_user AND p IS NOT NULL);
    IF EXISTS (SELECT 1 FROM unnest(_paths) p WHERE p IS NULL OR left(p,1)<>'/' OR p='/'
        OR p ~ '(^|/)[.]{1,2}(/|$)' OR strpos(p,'//')>0 OR right(p,1)='/') THEN
        RAISE EXCEPTION 'User cleanup requires absolute normalized non-root paths' USING ERRCODE='55006';
    END IF;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_helpers h WHERE h.cleanup_finished_at IS NULL
        AND EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a
            WHERE a.helper_benchmark_id=h.benchmark_id AND a.execution_token IS NOT NULL)
        AND EXISTS (SELECT 1 FROM unnest(_paths) candidate WHERE h.path=candidate
            OR starts_with(h.path,candidate || '/') OR starts_with(candidate,h.path || '/'))) THEN
        RAISE EXCEPTION 'Managed build helper cleanup is unsettled' USING ERRCODE='55006';
    END IF;
    IF EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a
        WHERE (a.execution_token IS NOT NULL OR a.storage_root IS NOT NULL OR a.artifact_path IS NOT NULL)
          AND (a.state<>'DELETED' OR EXISTS (
              SELECT 1 FROM starexec.solvers s WHERE s.build_output_artifact_id=a.id))
          AND (a.origin_owner_id=_user OR EXISTS (
              SELECT 1 FROM starexec.solvers s WHERE s.user_id=_user AND s.id=a.origin_solver_id)
            OR EXISTS (
              SELECT 1 FROM unnest(ARRAY[a.source_path,a.staging_path,a.storage_root,a.artifact_path]) protected,
                            unnest(_paths) candidate
              WHERE protected=candidate OR starts_with(protected,candidate || '/')
                    OR starts_with(candidate,protected || '/'))))
        OR EXISTS (
            SELECT 1 FROM starexec.jobpair_stage_data sd
            JOIN starexec.solvers s ON s.id=sd.solver_id
            JOIN starexec.job_pairs jp ON jp.id=sd.jobpair_id JOIN starexec.jobs j ON j.id=jp.job_id
            WHERE s.user_id=_user AND j.user_id IS DISTINCT FROM _user)
        OR EXISTS (
            SELECT 1 FROM starexec.solvers s,
                unnest(ARRAY[s.path,s.build_source_path,
                    CASE WHEN right(s.path,4)='_src' THEN s.path ELSE s.path || '_src' END]) protected,
                unnest(_paths) candidate
            WHERE s.user_id IS DISTINCT FROM _user AND
                (protected=candidate OR starts_with(protected,candidate || '/')
                    OR starts_with(candidate,protected || '/'))) THEN
        RAISE EXCEPTION 'Solver storage is still retained or referenced' USING ERRCODE='55006';
    END IF;
END;
$$ LANGUAGE plpgsql;
