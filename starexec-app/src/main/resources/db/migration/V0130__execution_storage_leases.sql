CREATE TABLE starexec.execution_storage_resources (
    resource_key TEXT PRIMARY KEY CHECK (resource_key ~ '^[0-9a-f]+$' AND resource_key !~ '^0+$'),
    root_path TEXT NOT NULL UNIQUE CHECK (left(root_path,1)='/' AND root_path<>'/'
        AND root_path !~ '(^|/)[.]{1,2}(/|$)' AND strpos(root_path,'//')=0 AND right(root_path,1)<>'/'),
    observed_identity TEXT NOT NULL CHECK (btrim(observed_identity)<>''),
    capacity_bytes BIGINT NOT NULL CHECK (capacity_bytes>0),
    inode_limit BIGINT NOT NULL CHECK (inode_limit>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE starexec.solver_build_storage_leases (
    artifact_id BIGINT PRIMARY KEY REFERENCES starexec.solver_build_artifacts(id) ON DELETE RESTRICT,
    execution_token UUID NOT NULL UNIQUE,
    resource_key TEXT NOT NULL REFERENCES starexec.execution_storage_resources(resource_key) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    released_at TIMESTAMPTZ CHECK (released_at IS NULL OR released_at>=created_at)
);

CREATE UNIQUE INDEX solver_build_storage_one_active_lease
    ON starexec.solver_build_storage_leases(resource_key) WHERE released_at IS NULL;

CREATE FUNCTION starexec.guard_execution_storage_resource() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Execution storage resource history is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER execution_storage_resource_guard BEFORE UPDATE OR DELETE
    ON starexec.execution_storage_resources FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_execution_storage_resource();

CREATE FUNCTION starexec.guard_solver_build_storage_lease() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Build storage lease history must be retained'; END IF;
    IF (NEW.artifact_id,NEW.execution_token,NEW.resource_key,NEW.created_at)
        IS DISTINCT FROM (OLD.artifact_id,OLD.execution_token,OLD.resource_key,OLD.created_at)
        OR OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Build storage lease identity is immutable';
    END IF;
    IF NEW.released_at IS NOT NULL AND (
        NOT EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.execution_token=NEW.execution_token AND a.state='DELETED' AND a.cleanup_finished_at IS NOT NULL)
        OR EXISTS (SELECT 1 FROM starexec.solver_build_storage_charges c
            WHERE c.artifact_id=NEW.artifact_id AND c.released_at IS NULL)) THEN
        RAISE EXCEPTION 'Build storage lease release requires completed cleanup and settlement';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_storage_lease_guard BEFORE UPDATE OR DELETE
    ON starexec.solver_build_storage_leases FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_solver_build_storage_lease();
