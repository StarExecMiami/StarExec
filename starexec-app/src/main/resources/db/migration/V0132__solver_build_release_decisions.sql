-- Retain release consumption after cancellation, physical stop and resource reuse.
CREATE TABLE starexec.solver_build_release_decisions (
    artifact_id BIGINT PRIMARY KEY REFERENCES starexec.solver_build_storage_leases(artifact_id) ON DELETE RESTRICT,
    execution_token UUID NOT NULL UNIQUE,
    request_token UUID NOT NULL UNIQUE,
    resource_key TEXT NOT NULL REFERENCES starexec.execution_storage_resources(resource_key) ON DELETE RESTRICT,
    resource_identity TEXT NOT NULL CHECK (btrim(resource_identity)<>''),
    engine_identity TEXT NOT NULL CHECK (engine_identity LIKE 'podman:%' OR engine_identity LIKE 'kubernetes:%'),
    runtime_payload TEXT NOT NULL CHECK (octet_length(runtime_payload) BETWEEN 2 AND 8192
        AND json_typeof(runtime_payload::json)='object'),
    runtime_sha256 TEXT NOT NULL CHECK (runtime_sha256 ~ '^[0-9a-f]{64}$'
        AND runtime_sha256=encode(sha256(convert_to(runtime_payload,'UTF8')),'hex')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION starexec.guard_solver_build_release_decision() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Build release consumption history is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_release_decision_guard BEFORE UPDATE OR DELETE
    ON starexec.solver_build_release_decisions FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_solver_build_release_decision();
