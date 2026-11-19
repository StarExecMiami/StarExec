-- Execution cleanup may finish after its solver is deleted. Preserve that
-- observation without allowing a rejected artifact to become publishable again.
CREATE OR REPLACE FUNCTION starexec.guard_solver_artifact_storage() RETURNS trigger AS $$
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
       (NEW.staging_path, NEW.storage_root, NEW.execution_token)
           IS DISTINCT FROM (OLD.staging_path, OLD.storage_root, OLD.execution_token) THEN
        RAISE EXCEPTION 'A validated artifact cannot acquire another storage binding';
    END IF;
    IF OLD.state NOT IN ('PLANNED', 'STAGED', 'REJECTED') AND
       (NEW.engine_identity, NEW.execution_stopped_at)
           IS DISTINCT FROM (OLD.engine_identity, OLD.execution_stopped_at) THEN
        RAISE EXCEPTION 'A validated artifact cannot acquire another execution observation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.bind_solver_build_execution(
    _pair INTEGER, _attempt INTEGER, _token UUID, _engine TEXT) RETURNS BOOLEAN AS $$
BEGIN
    IF _engine IS NULL OR _engine = '' THEN RETURN FALSE; END IF;
    UPDATE starexec.solver_build_artifacts SET engine_identity = _engine
    WHERE origin_pair_id = _pair AND attempt_no = _attempt AND execution_token = _token
        AND (state IN ('PLANNED', 'STAGED', 'REJECTED') OR engine_identity = _engine)
        AND (engine_identity IS NULL OR engine_identity = _engine);
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.record_solver_build_execution_stop(
    _pair INTEGER, _attempt INTEGER, _token UUID, _engine TEXT) RETURNS BOOLEAN AS $$
BEGIN
    UPDATE starexec.solver_build_artifacts
    SET execution_stopped_at = COALESCE(execution_stopped_at, clock_timestamp())
    WHERE origin_pair_id = _pair AND attempt_no = _attempt AND execution_token = _token
        AND engine_identity = _engine
        AND (state IN ('PLANNED', 'STAGED', 'REJECTED') OR execution_stopped_at IS NOT NULL);
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;
