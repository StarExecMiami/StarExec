-- Require known successful pair and build statuses before solver publication.
CREATE OR REPLACE FUNCTION starexec.publish_solver_build_artifact(_artifact BIGINT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _job INTEGER;
    _job_owner INTEGER;
    _status INTEGER;
    _attempt INTEGER;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _artifact;
    IF NOT FOUND OR _a.solver_id IS NULL OR _a.pair_id IS NULL THEN RETURN FALSE; END IF;
    SELECT job_id INTO _job FROM starexec.job_pairs WHERE id = _a.pair_id;
    SELECT user_id INTO _job_owner FROM starexec.jobs
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

    IF _s.deleted IS TRUE OR _s.recycled IS TRUE OR _job_owner <> _s.user_id
        OR _a.origin_owner_id <> _s.user_id OR _a.solver_id <> _s.id
        OR _attempt <> _a.attempt_no OR _s.build_generation <> _a.build_generation
        OR _status IS DISTINCT FROM 7 OR NOT EXISTS (
            SELECT 1 FROM starexec.jobpair_stage_data sd
            JOIN starexec.job_pairs jp ON jp.id = sd.jobpair_id
            WHERE jp.id = _a.pair_id AND sd.stage_number = jp.primary_jobpair_data
                AND sd.solver_id = _s.id)
        OR EXISTS (
            SELECT 1 FROM starexec.jobs j
            JOIN starexec.job_pairs jp ON jp.job_id = j.id
            JOIN starexec.jobpair_stage_data sd ON sd.jobpair_id = jp.id
                AND sd.stage_number = jp.primary_jobpair_data
            WHERE j.buildjob AND sd.solver_id = _s.id AND j.id > _job) THEN
        RETURN FALSE;
    END IF;
    IF _a.state = 'PUBLISHED' THEN
        IF _s.path IS DISTINCT FROM _a.artifact_path OR _s.build_status IS DISTINCT FROM 2 THEN RETURN FALSE; END IF;
        UPDATE starexec.solvers SET build_output_artifact_id = _a.id WHERE id = _s.id;
        RETURN TRUE;
    END IF;
    IF _a.state <> 'VALIDATED' OR NOT COALESCE(_s.build_status IN (0, 3), FALSE)
        OR _s.path <> _a.source_path THEN RETURN FALSE; END IF;

    UPDATE starexec.solver_build_artifacts SET state = 'RETIRED'
        WHERE origin_solver_id = _s.id AND state = 'PUBLISHED';
    UPDATE starexec.solvers SET path = _a.artifact_path, build_status = 2,
        build_source_path = _a.source_path, build_output_artifact_id = _a.id WHERE id = _s.id;
    UPDATE starexec.solver_build_artifacts SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
        WHERE id = _artifact;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
