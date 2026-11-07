CREATE OR REPLACE FUNCTION starexec.claim_solver_build_helper_cleanup(_helper INTEGER, _token UUID)
RETURNS BOOLEAN AS $$
DECLARE
    _h starexec.solver_build_helpers%ROWTYPE;
    _b starexec.benchmarks%ROWTYPE;
BEGIN
    IF _token IS NULL THEN RETURN FALSE; END IF;
    SELECT * INTO _h FROM starexec.solver_build_helpers WHERE benchmark_id=_helper;
    IF NOT FOUND OR _h.cleanup_token IS NOT NULL AND _h.cleanup_token<>_token THEN RETURN FALSE; END IF;
    IF _h.cleanup_finished_at IS NOT NULL THEN RETURN TRUE; END IF;
    PERFORM starexec.lock_user_solver_storage_for_deletion(_h.owner_id);
    PERFORM id FROM starexec.solver_build_artifacts WHERE helper_benchmark_id=_helper ORDER BY id FOR UPDATE NOWAIT;
    SELECT * INTO _b FROM starexec.benchmarks WHERE id=_helper FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _h FROM starexec.solver_build_helpers WHERE benchmark_id=_helper FOR UPDATE;
    IF _h.cleanup_token IS NOT NULL AND _h.cleanup_token<>_token THEN RETURN FALSE; END IF;
    IF _h.cleanup_finished_at IS NOT NULL THEN RETURN TRUE; END IF;
    IF (_h.owner_id,_h.path,_h.byte_size) IS DISTINCT FROM (_b.user_id,_b.path,_b.disk_size)
        OR _b.name IS DISTINCT FROM 'starexec_build' OR _b.deleted IS TRUE
        OR NOT EXISTS (SELECT 1 FROM starexec.solver_build_artifacts WHERE helper_benchmark_id=_helper AND execution_token IS NOT NULL)
        OR EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.helper_benchmark_id=_helper AND a.execution_token IS NOT NULL
            AND (a.origin_owner_id<>_h.owner_id OR a.execution_stopped_at IS NULL OR a.execution_stop_receipt_recorded_at IS NULL
                OR NOT starexec.solver_build_stop_receipt_matches(a.execution_stop_receipt_payload,a.execution_stop_receipt_sha256,
                    a.id,a.origin_pair_id,a.attempt_no,a.engine_identity)
                OR NOT COALESCE(a.execution_stop_receipt_payload::jsonb->>'backend' IN ('podman','kubernetes'),FALSE)
                OR a.engine_identity NOT LIKE (a.execution_stop_receipt_payload::jsonb->>'backend') || ':%'))
        OR EXISTS (SELECT 1 FROM starexec.job_pairs jp WHERE jp.bench_id=_helper AND
            (NOT COALESCE(starexec.IsTerminalPairStatus(jp.status_code),FALSE) OR NOT EXISTS (
                SELECT 1 FROM starexec.solver_build_artifacts a JOIN starexec.job_pair_attempts t ON t.pair_id=jp.id
                WHERE a.helper_benchmark_id=_helper AND a.origin_pair_id=jp.id AND a.attempt_no=t.current_attempt_no
                    AND a.execution_token IS NOT NULL))) THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM starexec.benchmarks b WHERE b.id<>_helper AND
        (b.path=_h.path OR starts_with(b.path,_h.path || '/') OR starts_with(_h.path,b.path || '/')))
        OR EXISTS (SELECT 1 FROM starexec.solvers s, unnest(ARRAY[s.path,s.build_source_path]) p
            WHERE p=_h.path OR starts_with(p,_h.path || '/') OR starts_with(_h.path,p || '/'))
        OR EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a,
            unnest(ARRAY[a.source_path,a.staging_path,a.storage_root,a.artifact_path]) p
            WHERE a.state<>'DELETED' AND (p=_h.path OR starts_with(p,_h.path || '/') OR starts_with(_h.path,p || '/'))) THEN RETURN FALSE; END IF;
    IF _h.cleanup_token IS NULL THEN
        UPDATE starexec.solver_build_helpers SET cleanup_token=_token,cleanup_started_at=clock_timestamp() WHERE benchmark_id=_helper;
    END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Only the trusted coordinator may attest file absence and request exact settlement.
CREATE OR REPLACE FUNCTION starexec.finish_solver_build_helper_cleanup(_helper INTEGER, _token UUID)
RETURNS BOOLEAN AS $$
DECLARE
    _h starexec.solver_build_helpers%ROWTYPE;
BEGIN
    IF NOT starexec.claim_solver_build_helper_cleanup(_helper,_token) THEN RETURN FALSE; END IF;
    SELECT * INTO _h FROM starexec.solver_build_helpers WHERE benchmark_id=_helper FOR UPDATE;
    IF _h.cleanup_finished_at IS NOT NULL THEN RETURN TRUE; END IF;
    UPDATE starexec.solver_build_helpers SET cleanup_finished_at=clock_timestamp() WHERE benchmark_id=_helper;
    UPDATE starexec.users SET disk_size=disk_size-_h.byte_size WHERE id=_h.owner_id AND disk_size>=_h.byte_size;
    IF NOT FOUND THEN RAISE EXCEPTION 'Build helper cleanup charge exceeds owner usage'; END IF;
    UPDATE starexec.benchmarks SET deleted=true,disk_size=0 WHERE id=_helper
        AND user_id=_h.owner_id AND path=_h.path AND disk_size=_h.byte_size;
    IF NOT FOUND THEN RAISE EXCEPTION 'Build helper accounting identity changed'; END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Ordinary benchmarks retain their existing insertion behavior. Protect named
-- helper input before its first catalogue entry as well as registered helpers.
CREATE OR REPLACE FUNCTION starexec.guard_build_helper_consumer() RETURNS trigger AS $$
BEGIN
    IF NEW.bench_id IS NULL OR TG_OP='UPDATE' AND NEW.bench_id IS NOT DISTINCT FROM OLD.bench_id THEN RETURN NEW; END IF;
    IF NOT EXISTS (SELECT 1 FROM starexec.solver_build_helpers WHERE benchmark_id=NEW.bench_id)
        AND NOT EXISTS (SELECT 1 FROM starexec.benchmarks WHERE id=NEW.bench_id AND name='starexec_build') THEN RETURN NEW; END IF;
    PERFORM id FROM starexec.benchmarks WHERE id=NEW.bench_id FOR KEY SHARE;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_helpers WHERE benchmark_id=NEW.bench_id AND cleanup_token IS NOT NULL) THEN
        RAISE EXCEPTION 'Build helper is being removed or is no longer available' USING ERRCODE='55006';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
