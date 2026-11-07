CREATE TABLE starexec.solver_build_storage_charges (
    artifact_id BIGINT NOT NULL REFERENCES starexec.solver_build_artifacts(id),
    storage_kind TEXT NOT NULL CHECK (storage_kind IN ('STAGING','OUTPUT')),
    owner_id INTEGER NOT NULL,
    path TEXT NOT NULL UNIQUE CHECK (left(path,1)='/' AND path<>'/'
        AND path !~ '(^|/)[.]{1,2}(/|$)' AND strpos(path,'//')=0 AND right(path,1)<>'/'),
    limit_bytes BIGINT NOT NULL CHECK (limit_bytes>=0),
    charged_bytes BIGINT NOT NULL CHECK (charged_bytes>=0 AND charged_bytes<=limit_bytes),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    verified_bytes BIGINT,
    verified_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    PRIMARY KEY (artifact_id,storage_kind),
    CHECK (verified_at IS NULL AND verified_bytes IS NULL AND charged_bytes=limit_bytes
        OR storage_kind='OUTPUT' AND verified_at IS NOT NULL AND verified_at>=created_at
            AND verified_bytes IS NOT NULL AND verified_bytes=charged_bytes),
    CHECK (released_at IS NULL OR released_at>=created_at)
);

CREATE INDEX solver_build_storage_charge_owner ON starexec.solver_build_storage_charges(owner_id)
    WHERE released_at IS NULL;

CREATE FUNCTION starexec.guard_solver_build_storage_charge() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Build storage charge history must be retained'; END IF;
    IF (NEW.artifact_id,NEW.storage_kind,NEW.owner_id,NEW.path,NEW.limit_bytes,NEW.created_at)
        IS DISTINCT FROM (OLD.artifact_id,OLD.storage_kind,OLD.owner_id,OLD.path,OLD.limit_bytes,OLD.created_at)
        OR OLD.verified_at IS NOT NULL AND (NEW.verified_bytes,NEW.verified_at,NEW.charged_bytes)
            IS DISTINCT FROM (OLD.verified_bytes,OLD.verified_at,OLD.charged_bytes)
        OR OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Build storage charge identity is immutable';
    END IF;
    IF NEW.verified_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.storage_root=NEW.path AND a.validated_at IS NOT NULL AND a.byte_size=NEW.verified_bytes) THEN
        RAISE EXCEPTION 'Output charge requires conserved verified bytes';
    END IF;
    IF NEW.released_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.state='DELETED' AND a.cleanup_finished_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Build storage settlement requires completed cleanup';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_storage_charge_guard BEFORE UPDATE OR DELETE
    ON starexec.solver_build_storage_charges FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_solver_build_storage_charge();
