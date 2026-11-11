CREATE TABLE starexec.execution_storage_claims (
    execution_token UUID PRIMARY KEY,
    resource_key TEXT NOT NULL REFERENCES starexec.execution_storage_resources(resource_key) ON DELETE RESTRICT,
    lease_kind TEXT NOT NULL CHECK (lease_kind IN ('BUILD','JOB')),
    build_artifact_id BIGINT UNIQUE REFERENCES starexec.solver_build_artifacts(id) ON DELETE RESTRICT,
    origin_pair_id INTEGER,
    attempt_no INTEGER CHECK (attempt_no>0),
    origin_owner_id INTEGER,
    prior_execution_id INTEGER,
    limit_bytes BIGINT CHECK (limit_bytes>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    use_started_at TIMESTAMPTZ CHECK (use_started_at IS NULL OR use_started_at>=created_at),
    released_at TIMESTAMPTZ CHECK (released_at IS NULL OR released_at>=created_at),
    UNIQUE (origin_pair_id,attempt_no),
    CHECK (lease_kind='BUILD' AND build_artifact_id IS NOT NULL
        AND origin_pair_id IS NULL AND attempt_no IS NULL AND origin_owner_id IS NULL
        AND prior_execution_id IS NULL AND limit_bytes IS NULL AND use_started_at IS NULL
        OR lease_kind='JOB' AND build_artifact_id IS NULL
        AND origin_pair_id IS NOT NULL AND attempt_no IS NOT NULL AND origin_owner_id IS NOT NULL
        AND limit_bytes IS NOT NULL AND execution_token<>'00000000-0000-0000-0000-000000000000'::uuid)
);

CREATE UNIQUE INDEX execution_storage_one_active_claim
    ON starexec.execution_storage_claims(resource_key) WHERE released_at IS NULL;
CREATE UNIQUE INDEX execution_storage_one_active_job_attempt
    ON starexec.execution_storage_claims(origin_pair_id)
    WHERE lease_kind='JOB' AND released_at IS NULL;
CREATE INDEX execution_storage_active_job_owner
    ON starexec.execution_storage_claims(origin_owner_id)
    WHERE lease_kind='JOB' AND released_at IS NULL;

-- Preserve every build lease, including settled history, without booking its allowance again.
INSERT INTO starexec.execution_storage_claims
    (execution_token,resource_key,lease_kind,build_artifact_id,created_at,released_at)
    SELECT execution_token,resource_key,'BUILD',artifact_id,created_at,released_at
    FROM starexec.solver_build_storage_leases;

CREATE FUNCTION starexec.mirror_build_storage_claim() RETURNS trigger AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO starexec.execution_storage_claims
            (execution_token,resource_key,lease_kind,build_artifact_id,created_at,released_at)
            VALUES (NEW.execution_token,NEW.resource_key,'BUILD',NEW.artifact_id,NEW.created_at,NEW.released_at);
    ELSIF NEW.released_at IS DISTINCT FROM OLD.released_at THEN
        UPDATE starexec.execution_storage_claims SET released_at=NEW.released_at
            WHERE execution_token=NEW.execution_token AND lease_kind='BUILD' AND build_artifact_id=NEW.artifact_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'Build storage ownership is missing'; END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_storage_claim_mirror AFTER INSERT OR UPDATE
    ON starexec.solver_build_storage_leases FOR EACH ROW
    EXECUTE FUNCTION starexec.mirror_build_storage_claim();
