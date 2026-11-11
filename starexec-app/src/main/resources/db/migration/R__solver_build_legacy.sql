-- Legacy results cannot consume an execution-bound build or retire its input.
CREATE OR REPLACE FUNCTION starexec.finish_legacy_solver_build(
    _job INTEGER, _pair INTEGER, _benchmark INTEGER, _solver INTEGER,
    _source TEXT, _build_status INTEGER, _pair_status INTEGER, _built_path TEXT,
    _helper_name TEXT, _apply BOOLEAN) RETURNS TEXT AS $$
DECLARE
    _owner INTEGER;
    _p starexec.job_pairs%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _a starexec.solver_build_artifacts%ROWTYPE;
    _b starexec.benchmarks%ROWTYPE;
    _attempt INTEGER;
BEGIN
    IF _apply IS NULL THEN RETURN NULL; END IF;
    SELECT user_id INTO _owner FROM starexec.jobs WHERE id=_job AND buildjob FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO _p FROM starexec.job_pairs WHERE id=_pair AND job_id=_job AND bench_id=_benchmark FOR UPDATE;
    IF NOT FOUND OR NOT COALESCE(starexec.IsTerminalPairStatus(_p.status_code), FALSE)
        OR _p.status_code IS DISTINCT FROM _pair_status THEN RETURN NULL; END IF;
    SELECT current_attempt_no INTO _attempt FROM starexec.job_pair_attempts WHERE pair_id=_pair FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    -- Deletion and quota settlement lock the accounting owner before the solver.
    IF NOT _apply THEN PERFORM id FROM starexec.users WHERE id=_owner FOR UPDATE; END IF;
    SELECT * INTO _s FROM starexec.solvers WHERE id=_solver FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM starexec.jobpair_stage_data
        WHERE jobpair_id=_pair AND stage_number=_p.primary_jobpair_data AND solver_id=_solver) THEN RETURN NULL; END IF;
    PERFORM id FROM starexec.solver_build_artifacts WHERE origin_pair_id=_pair ORDER BY attempt_no FOR UPDATE;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_artifacts WHERE origin_pair_id=_pair AND execution_token IS NOT NULL)
        THEN RETURN NULL; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE origin_pair_id=_pair AND attempt_no=_attempt;
    IF NOT FOUND OR _a.solver_id IS DISTINCT FROM _solver OR _a.pair_id IS DISTINCT FROM _pair
        OR _a.origin_owner_id IS DISTINCT FROM _owner OR _a.state NOT IN ('PLANNED','REJECTED')
        OR _a.staging_path IS NOT NULL OR _a.storage_root IS NOT NULL OR _a.manifest_payload IS NOT NULL
        THEN RETURN NULL; END IF;
    SELECT * INTO _b FROM starexec.benchmarks WHERE id=_benchmark FOR UPDATE;
    IF NOT FOUND OR _b.user_id IS DISTINCT FROM _owner OR _b.name IS DISTINCT FROM _helper_name
        OR _b.path IS NULL OR length(_b.path)=0 OR _b.deleted IS TRUE
        OR EXISTS (SELECT 1 FROM starexec.job_pairs WHERE bench_id=_benchmark AND id<>_pair)
        THEN RETURN NULL; END IF;

    IF _apply THEN
        IF _s.user_id IS DISTINCT FROM _owner OR _s.deleted IS TRUE OR _s.recycled IS TRUE
            OR _s.path IS DISTINCT FROM _source OR _a.source_path IS DISTINCT FROM _source
            OR _s.build_generation IS DISTINCT FROM _a.build_generation
            OR _s.build_status IS DISTINCT FROM _build_status OR NOT COALESCE(_build_status IN (0,3), FALSE)
            OR right(_source,4) IS DISTINCT FROM '_src' OR length(_source)<=4
            OR _built_path IS NOT NULL AND (_p.status_code<>7 OR _built_path<>left(_source,length(_source)-4))
            OR EXISTS (SELECT 1 FROM starexec.jobs j JOIN starexec.job_pairs jp ON jp.job_id=j.id
                JOIN starexec.jobpair_stage_data sd ON sd.jobpair_id=jp.id AND sd.stage_number=jp.primary_jobpair_data
                WHERE j.buildjob AND sd.solver_id=_solver AND j.id>_job) THEN RETURN NULL; END IF;
        UPDATE starexec.solvers SET build_status=CASE WHEN _built_path IS NULL THEN 3 ELSE 2 END,
            path=COALESCE(_built_path,_source), build_output_artifact_id=_a.id WHERE id=_solver;
    END IF;
    UPDATE starexec.solver_build_artifacts SET state='REJECTED' WHERE id=_a.id;
    IF _apply THEN RETURN _b.path; END IF;
    RETURN starexec.SetBenchmarkToDeletedById(_benchmark);
END;
$$ LANGUAGE plpgsql;

-- Preparing execution storage and retiring a legacy input serialize on the same helper.
CREATE OR REPLACE FUNCTION starexec.prepare_solver_build_storage(
    _pair INTEGER, _attempt INTEGER, _token UUID, _staging TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _job INTEGER;
    _owner INTEGER;
    _a starexec.solver_build_artifacts%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _current INTEGER;
    _benchmark INTEGER;
    _helper_deleted BOOLEAN;
BEGIN
    IF _token IS NULL OR _staging IS NULL OR _staging = '' THEN RETURN FALSE; END IF;
    SELECT job_id INTO _job FROM starexec.job_pairs WHERE id = _pair;
    SELECT user_id INTO _owner FROM starexec.jobs WHERE id = _job AND buildjob
        AND deleted IS NOT TRUE AND killed IS NOT TRUE FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT bench_id INTO _benchmark FROM starexec.job_pairs WHERE id = _pair AND job_id = _job FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT current_attempt_no INTO _current FROM starexec.job_pair_attempts WHERE pair_id = _pair FOR UPDATE;
    IF NOT FOUND OR _current <> _attempt THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE pair_id = _pair AND attempt_no = _attempt;
    IF NOT FOUND OR _a.solver_id IS NULL THEN RETURN FALSE; END IF;
    SELECT * INTO _s FROM starexec.solvers WHERE id = _a.solver_id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _a.id FOR UPDATE;
    IF _benchmark IS NOT NULL THEN
        SELECT deleted INTO _helper_deleted FROM starexec.benchmarks WHERE id=_benchmark FOR SHARE;
        IF NOT FOUND OR _helper_deleted IS TRUE THEN RETURN FALSE; END IF;
        IF _a.helper_benchmark_id IS DISTINCT FROM _benchmark OR NOT EXISTS (
            SELECT 1 FROM starexec.solver_build_helpers h JOIN starexec.benchmarks b ON b.id=h.benchmark_id
            WHERE h.benchmark_id=_benchmark AND h.owner_id=_owner AND h.path=b.path AND h.byte_size=b.disk_size
                AND b.user_id=_owner AND b.name='starexec_build' AND h.cleanup_token IS NULL) THEN RETURN FALSE; END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_artifacts retired
        WHERE retired.execution_token IS NULL AND retired.state IN ('REJECTED','DELETED')
            AND (retired.origin_pair_id=_pair OR EXISTS (SELECT 1 FROM starexec.job_pairs old_pair
                WHERE old_pair.id=retired.origin_pair_id AND old_pair.bench_id=_benchmark))) THEN RETURN FALSE; END IF;
    IF _a.state NOT IN ('PLANNED', 'STAGED') OR _s.deleted IS TRUE OR _s.recycled IS TRUE
        OR _owner <> _s.user_id OR _a.origin_owner_id <> _s.user_id
        OR _a.build_generation <> _s.build_generation OR _s.path <> _a.source_path
        OR NOT COALESCE(_s.build_status IN (0, 3), FALSE) OR EXISTS (
            SELECT 1 FROM starexec.jobs j JOIN starexec.job_pairs jp ON jp.job_id = j.id
            JOIN starexec.jobpair_stage_data sd ON sd.jobpair_id = jp.id
                AND sd.stage_number = jp.primary_jobpair_data
            WHERE j.buildjob AND sd.solver_id = _s.id AND j.id > _job) THEN RETURN FALSE; END IF;
    IF _a.execution_token IS NOT NULL THEN
        RETURN _a.execution_token = _token AND _a.staging_path = _staging;
    END IF;
    UPDATE starexec.solver_build_artifacts SET execution_token = _token, staging_path = _staging WHERE id = _a.id;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
