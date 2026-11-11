-- A consumed create request survives an uncertain response, cancellation and storage reuse.
CREATE TABLE starexec.solver_build_creation_requests (
    artifact_id BIGINT PRIMARY KEY CONSTRAINT solver_build_creation_lease_fk
        REFERENCES starexec.solver_build_storage_leases(artifact_id) ON DELETE RESTRICT,
    execution_token UUID NOT NULL UNIQUE,
    resource_key TEXT NOT NULL CONSTRAINT solver_build_creation_resource_fk
        REFERENCES starexec.execution_storage_resources(resource_key) ON DELETE RESTRICT,
    resource_identity TEXT NOT NULL CHECK (btrim(resource_identity)<>''),
    execution_id INTEGER NOT NULL CHECK (execution_id>0),
    container_name TEXT NOT NULL UNIQUE CHECK (container_name='starexec-build-' || artifact_id::text),
    image_identity TEXT NOT NULL CHECK (image_identity ~ '^sha256:[0-9a-f]{64}$'),
    transport TEXT NOT NULL CHECK (transport IN ('java','curl')),
    request_payload TEXT NOT NULL CHECK (octet_length(request_payload) BETWEEN 2 AND 65536
        AND jsonb_typeof(request_payload::jsonb)='object'),
    request_sha256 TEXT NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'
        AND request_sha256=encode(sha256(convert_to(request_payload,'UTF8')),'hex')),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION starexec.guard_solver_build_creation_request() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='UPDATE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Build creation consumption history is immutable';
    END IF;
    IF TG_OP='INSERT' AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a
        JOIN starexec.solver_build_storage_leases l ON l.artifact_id=a.id
        JOIN starexec.execution_storage_resources r ON r.resource_key=l.resource_key
        JOIN starexec.solver_build_storage_charges c ON c.artifact_id=a.id AND c.storage_kind='STAGING'
        WHERE a.id=NEW.artifact_id AND a.execution_token=NEW.execution_token
            AND a.state IN ('PLANNED','STAGED') AND a.engine_identity IS NULL
            AND a.execution_stopped_at IS NULL AND a.cleanup_token IS NULL
            AND l.execution_token=a.execution_token AND l.released_at IS NULL AND l.cleanup_token IS NULL
            AND r.resource_key=NEW.resource_key AND r.observed_identity=NEW.resource_identity
            AND c.owner_id=a.origin_owner_id AND c.path=a.staging_path AND c.limit_bytes>=r.capacity_bytes
            AND c.charged_bytes=c.limit_bytes AND c.verified_at IS NULL AND c.released_at IS NULL
            AND NEW.request_payload::jsonb->>'Image'=NEW.image_identity
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.managed'='true'
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.label.version'='2'
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.kind'='job-pair'
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.build.layout'='private-v1'
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.build.artifact'=a.id::text
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.pair.id'=a.origin_pair_id::text
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.attempt'=a.attempt_no::text
            AND NEW.request_payload::jsonb->'Labels'->>'starexec.exec.id'=NEW.execution_id::text
    ) THEN
        RAISE EXCEPTION 'Build creation requires a matching active lease and exact request ownership';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_creation_request_guard BEFORE INSERT OR UPDATE OR DELETE
    ON starexec.solver_build_creation_requests FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_solver_build_creation_request();
