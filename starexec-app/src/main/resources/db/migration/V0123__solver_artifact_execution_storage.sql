ALTER TABLE starexec.solver_build_artifacts
    ADD COLUMN staging_path TEXT UNIQUE CHECK (staging_path <> ''),
    ADD COLUMN storage_root TEXT UNIQUE CHECK (storage_root <> ''),
    ADD COLUMN execution_token UUID,
    ADD COLUMN engine_identity TEXT CHECK (engine_identity <> ''),
    ADD COLUMN execution_stopped_at TIMESTAMPTZ,
    ADD CONSTRAINT solver_artifact_storage_layout_ck CHECK (
        storage_root IS NULL OR artifact_path IS NOT NULL AND artifact_path = storage_root || '/solver'),
    ADD CONSTRAINT solver_artifact_execution_binding_ck CHECK (
        execution_token IS NULL AND staging_path IS NULL AND engine_identity IS NULL AND execution_stopped_at IS NULL
        OR execution_token IS NOT NULL AND staging_path IS NOT NULL),
    ADD CONSTRAINT solver_artifact_stopped_binding_ck CHECK (
        execution_stopped_at IS NULL OR engine_identity IS NOT NULL);

CREATE FUNCTION starexec.guard_solver_artifact_storage() RETURNS trigger AS $$
BEGIN
    IF OLD.staging_path IS NOT NULL AND NEW.staging_path IS DISTINCT FROM OLD.staging_path
        OR OLD.storage_root IS NOT NULL AND NEW.storage_root IS DISTINCT FROM OLD.storage_root
        OR OLD.execution_token IS NOT NULL AND NEW.execution_token IS DISTINCT FROM OLD.execution_token
        OR OLD.engine_identity IS NOT NULL AND NEW.engine_identity IS DISTINCT FROM OLD.engine_identity
        OR OLD.execution_stopped_at IS NOT NULL
            AND NEW.execution_stopped_at IS DISTINCT FROM OLD.execution_stopped_at THEN
        RAISE EXCEPTION 'Build execution storage binding is immutable';
    END IF;
    IF OLD.state NOT IN ('PLANNED', 'STAGED') AND
       (NEW.staging_path, NEW.storage_root, NEW.execution_token, NEW.engine_identity, NEW.execution_stopped_at)
           IS DISTINCT FROM
       (OLD.staging_path, OLD.storage_root, OLD.execution_token, OLD.engine_identity, OLD.execution_stopped_at) THEN
        RAISE EXCEPTION 'A validated artifact cannot acquire another execution binding';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_artifact_storage_guard BEFORE UPDATE ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_artifact_storage();

CREATE FUNCTION starexec.prepare_solver_build_storage(
    _pair INTEGER, _attempt INTEGER, _token UUID, _staging TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _job INTEGER;
    _owner INTEGER;
    _a starexec.solver_build_artifacts%ROWTYPE;
    _s starexec.solvers%ROWTYPE;
    _current INTEGER;
BEGIN
    IF _token IS NULL OR _staging IS NULL OR _staging = '' THEN RETURN FALSE; END IF;
    SELECT job_id INTO _job FROM starexec.job_pairs WHERE id = _pair;
    SELECT user_id INTO _owner FROM starexec.jobs WHERE id = _job AND buildjob
        AND deleted IS NOT TRUE AND killed IS NOT TRUE FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM 1 FROM starexec.job_pairs WHERE id = _pair AND job_id = _job FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT current_attempt_no INTO _current FROM starexec.job_pair_attempts WHERE pair_id = _pair FOR UPDATE;
    IF NOT FOUND OR _current <> _attempt THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE pair_id = _pair AND attempt_no = _attempt;
    IF NOT FOUND OR _a.solver_id IS NULL THEN RETURN FALSE; END IF;
    SELECT * INTO _s FROM starexec.solvers WHERE id = _a.solver_id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _a.id FOR UPDATE;
    IF _a.state NOT IN ('PLANNED', 'STAGED') OR _s.deleted IS TRUE OR _s.recycled IS TRUE
        OR _owner <> _s.user_id OR _a.origin_owner_id <> _s.user_id
        OR _a.build_generation <> _s.build_generation OR _s.path <> _a.source_path
        OR _s.build_status NOT IN (0, 3) OR EXISTS (
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

-- Engine callbacks bind their observed resource identity; solver output cannot do so.
CREATE FUNCTION starexec.bind_solver_build_execution(
    _pair INTEGER, _attempt INTEGER, _token UUID, _engine TEXT) RETURNS BOOLEAN AS $$
BEGIN
    IF _engine IS NULL OR _engine = '' THEN RETURN FALSE; END IF;
    UPDATE starexec.solver_build_artifacts SET engine_identity = _engine
    WHERE origin_pair_id = _pair AND attempt_no = _attempt AND execution_token = _token
        AND state IN ('PLANNED', 'STAGED') AND (engine_identity IS NULL OR engine_identity = _engine);
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION starexec.record_solver_build_execution_stop(
    _pair INTEGER, _attempt INTEGER, _token UUID, _engine TEXT) RETURNS BOOLEAN AS $$
BEGIN
    UPDATE starexec.solver_build_artifacts
    SET execution_stopped_at = COALESCE(execution_stopped_at, clock_timestamp())
    WHERE origin_pair_id = _pair AND attempt_no = _attempt AND execution_token = _token
        AND engine_identity = _engine AND state IN ('PLANNED', 'STAGED');
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

-- The application publication entry point additionally requires stopped execution
-- ownership and a manifest root containing separate solver and log directories.
CREATE FUNCTION starexec.publish_solver_build_output(_artifact BIGINT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id = _artifact;
    IF NOT FOUND OR _a.execution_token IS NULL OR _a.engine_identity IS NULL
        OR _a.execution_stopped_at IS NULL OR _a.staging_path IS NULL OR _a.storage_root IS NULL
        OR _a.artifact_path <> _a.storage_root || '/solver' THEN RETURN FALSE; END IF;
    RETURN starexec.publish_solver_build_artifact(_artifact);
END;
$$ LANGUAGE plpgsql;
