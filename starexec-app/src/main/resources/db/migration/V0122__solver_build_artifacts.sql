-- Build generations fence filesystem artifacts independently of mutable solver paths.
ALTER TABLE starexec.solvers
    ADD COLUMN build_generation BIGINT NOT NULL DEFAULT 0 CHECK (build_generation >= 0),
    ADD COLUMN build_source_path TEXT;

CREATE TABLE starexec.solver_build_artifacts (
    id BIGSERIAL PRIMARY KEY,
    solver_id INTEGER,
    pair_id INTEGER,
    -- Historical identities survive deletion of the live solver, pair and user.
    origin_solver_id INTEGER NOT NULL,
    origin_pair_id INTEGER NOT NULL,
    origin_owner_id INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    build_generation BIGINT NOT NULL CHECK (build_generation >= 0),
    source_path TEXT NOT NULL CHECK (source_path <> ''),
    artifact_path TEXT UNIQUE CHECK (artifact_path <> ''),
    state VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
        CHECK (state IN ('PLANNED', 'STAGED', 'VALIDATED', 'PUBLISHED', 'RETIRED', 'REJECTED', 'DELETED')),
    byte_size BIGINT CHECK (byte_size >= 0),
    manifest_payload TEXT,
    manifest_sha256 CHAR(64) CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    CONSTRAINT solver_build_artifacts_solver_fk
        FOREIGN KEY (solver_id) REFERENCES starexec.solvers(id) ON DELETE SET NULL,
    CONSTRAINT solver_build_artifacts_pair_fk
        FOREIGN KEY (pair_id) REFERENCES starexec.job_pairs(id) ON DELETE SET NULL,
    CONSTRAINT solver_build_artifacts_origin_ck CHECK (
        (solver_id IS NULL OR solver_id = origin_solver_id)
        AND (pair_id IS NULL OR pair_id = origin_pair_id)),
    CONSTRAINT solver_build_artifacts_attempt_unique UNIQUE (origin_pair_id, attempt_no),
    CONSTRAINT solver_build_artifacts_reserved_path_ck CHECK (
        state IN ('PLANNED', 'REJECTED', 'DELETED') OR artifact_path IS NOT NULL),
    CONSTRAINT solver_build_artifacts_validated_ck CHECK (
        state NOT IN ('VALIDATED', 'PUBLISHED', 'RETIRED')
        OR (byte_size IS NOT NULL AND manifest_payload IS NOT NULL
            AND manifest_sha256 IS NOT NULL AND validated_at IS NOT NULL)),
    CONSTRAINT solver_build_artifacts_digest_ck CHECK (
        manifest_payload IS NULL AND manifest_sha256 IS NULL
        OR manifest_payload IS NOT NULL AND manifest_sha256 IS NOT NULL
            AND manifest_sha256 = encode(sha256(convert_to(manifest_payload, 'UTF8')), 'hex')),
    CONSTRAINT solver_build_artifacts_publication_time_ck CHECK (
        state <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE UNIQUE INDEX solver_build_artifacts_current
    ON starexec.solver_build_artifacts(origin_solver_id) WHERE state = 'PUBLISHED';
CREATE INDEX solver_build_artifacts_live_solver ON starexec.solver_build_artifacts(solver_id);
CREATE INDEX solver_build_artifacts_live_pair ON starexec.solver_build_artifacts(pair_id);

CREATE FUNCTION starexec.guard_solver_build_artifact() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Build artifacts must retain their cleanup records';
    END IF;
    IF (NEW.origin_solver_id, NEW.origin_pair_id, NEW.origin_owner_id, NEW.attempt_no,
        NEW.build_generation, NEW.source_path, NEW.created_at)
        IS DISTINCT FROM
       (OLD.origin_solver_id, OLD.origin_pair_id, OLD.origin_owner_id, OLD.attempt_no,
        OLD.build_generation, OLD.source_path, OLD.created_at)
        OR NEW.solver_id IS DISTINCT FROM OLD.solver_id AND NEW.solver_id IS NOT NULL
        OR NEW.pair_id IS DISTINCT FROM OLD.pair_id AND NEW.pair_id IS NOT NULL THEN
        RAISE EXCEPTION 'Build artifact origin is immutable';
    END IF;
    IF OLD.artifact_path IS NOT NULL AND NEW.artifact_path IS DISTINCT FROM OLD.artifact_path THEN
        RAISE EXCEPTION 'A reserved artifact path is immutable';
    END IF;
    IF OLD.validated_at IS NOT NULL
        AND (NEW.byte_size, NEW.manifest_payload, NEW.manifest_sha256, NEW.validated_at)
            IS DISTINCT FROM
            (OLD.byte_size, OLD.manifest_payload, OLD.manifest_sha256, OLD.validated_at) THEN
        RAISE EXCEPTION 'Validated build bytes are immutable';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'Build publication time is immutable';
    END IF;
    -- A detached catalog row remains a durable cleanup record, including after user cascades.
    IF NEW.solver_id IS NULL THEN
        IF OLD.state = 'PUBLISHED' THEN NEW.state := 'RETIRED';
        ELSIF OLD.state NOT IN ('RETIRED', 'DELETED') THEN NEW.state := 'REJECTED';
        END IF;
    END IF;
    IF NEW.state <> OLD.state AND NOT (
        OLD.state = 'PLANNED' AND NEW.state IN ('STAGED', 'REJECTED')
        OR OLD.state = 'STAGED' AND NEW.state IN ('VALIDATED', 'REJECTED')
        OR OLD.state = 'VALIDATED' AND NEW.state IN ('PUBLISHED', 'REJECTED')
        OR OLD.state = 'PUBLISHED' AND NEW.state = 'RETIRED'
        OR OLD.state IN ('RETIRED', 'REJECTED') AND NEW.state = 'DELETED') THEN
        RAISE EXCEPTION 'Invalid build artifact transition: % to %', OLD.state, NEW.state;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_artifacts_guard
    BEFORE UPDATE OR DELETE ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_build_artifact();

-- Called after the pair/attempt exists, while its parent transaction owns the pair.
CREATE FUNCTION starexec.plan_solver_build_artifact(_pair INTEGER, _attempt INTEGER)
RETURNS VOID AS $$
DECLARE
    _solver INTEGER;
    _job INTEGER;
    _owner INTEGER;
    _source TEXT;
    _generation BIGINT;
BEGIN
    SELECT sd.solver_id, jp.job_id INTO _solver, _job
    FROM starexec.job_pairs jp
    JOIN starexec.jobs j ON j.id = jp.job_id AND j.buildjob
    JOIN starexec.jobpair_stage_data sd
        ON sd.jobpair_id = jp.id AND sd.stage_number = jp.primary_jobpair_data
    WHERE jp.id = _pair;
    IF NOT FOUND OR EXISTS (SELECT 1 FROM starexec.solver_build_artifacts
        WHERE origin_pair_id = _pair AND attempt_no = _attempt) THEN RETURN; END IF;

    SELECT user_id, COALESCE(build_source_path,
        CASE WHEN right(path, 4) = '_src' THEN path ELSE path || '_src' END), build_generation
    INTO _owner, _source, _generation FROM starexec.solvers WHERE id = _solver FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    -- A delayed insertion of an older job must not invalidate its newer build.
    IF NOT EXISTS (
        SELECT 1 FROM starexec.jobs j
        JOIN starexec.job_pairs jp ON jp.job_id = j.id
        JOIN starexec.jobpair_stage_data sd ON sd.jobpair_id = jp.id
            AND sd.stage_number = jp.primary_jobpair_data
        WHERE j.buildjob AND sd.solver_id = _solver AND j.id > _job) THEN
        _generation := _generation + 1;
        UPDATE starexec.solvers SET build_generation = _generation WHERE id = _solver;
    END IF;
    INSERT INTO starexec.solver_build_artifacts
        (solver_id, pair_id, origin_solver_id, origin_pair_id, origin_owner_id,
         attempt_no, build_generation, source_path)
    VALUES (_solver, _pair, _solver, _pair, _owner, _attempt, _generation, _source);
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION starexec.plan_inserted_solver_build() RETURNS trigger AS $$
BEGIN
    PERFORM starexec.plan_solver_build_artifact(NEW.jobpair_id,
        COALESCE((SELECT current_attempt_no FROM starexec.job_pair_attempts
                  WHERE pair_id = NEW.jobpair_id), 1));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_build_primary_stage_inserted
    AFTER INSERT ON starexec.jobpair_stage_data
    FOR EACH ROW EXECUTE FUNCTION starexec.plan_inserted_solver_build();

CREATE FUNCTION starexec.plan_rerun_solver_build() RETURNS trigger AS $$
BEGIN
    IF NEW.current_attempt_no > OLD.current_attempt_no THEN
        PERFORM starexec.plan_solver_build_artifact(NEW.pair_id, NEW.current_attempt_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_build_attempt_changed
    AFTER UPDATE OF current_attempt_no ON starexec.job_pair_attempts
    FOR EACH ROW EXECUTE FUNCTION starexec.plan_rerun_solver_build();

CREATE FUNCTION starexec.publish_solver_build_artifact(_artifact BIGINT) RETURNS BOOLEAN AS $$
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
        OR _status <> 7 OR NOT EXISTS (
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
        RETURN _s.path = _a.artifact_path AND _s.build_status = 2;
    END IF;
    IF _a.state <> 'VALIDATED' OR _s.build_status NOT IN (0, 3)
        OR _s.path <> _a.source_path THEN RETURN FALSE; END IF;

    UPDATE starexec.solver_build_artifacts SET state = 'RETIRED'
        WHERE origin_solver_id = _s.id AND state = 'PUBLISHED';
    UPDATE starexec.solvers SET path = _a.artifact_path, build_status = 2,
        build_source_path = _a.source_path WHERE id = _s.id;
    UPDATE starexec.solver_build_artifacts SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
        WHERE id = _artifact;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
