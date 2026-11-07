CREATE TABLE starexec.execution_storage_policy (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO starexec.execution_storage_policy(singleton) VALUES (TRUE);

ALTER TABLE starexec.solver_build_storage_leases
    ADD COLUMN cleanup_token UUID,
    ADD COLUMN cleanup_staging_identity TEXT,
    ADD COLUMN cleanup_started_at TIMESTAMPTZ,
    ADD COLUMN cleanup_finished_at TIMESTAMPTZ,
    ADD CONSTRAINT solver_build_staging_cleanup_identity_ck CHECK (
        cleanup_token IS NULL AND cleanup_staging_identity IS NULL AND cleanup_started_at IS NULL AND cleanup_finished_at IS NULL
        OR cleanup_token IS NOT NULL AND cleanup_staging_identity IS NOT NULL AND btrim(cleanup_staging_identity)<>''
            AND cleanup_started_at IS NOT NULL
            AND cleanup_started_at>=created_at
            AND (cleanup_finished_at IS NULL OR cleanup_finished_at>=cleanup_started_at
                AND released_at IS NOT NULL AND released_at>=cleanup_finished_at));

CREATE UNIQUE INDEX solver_build_staging_cleanup_token
    ON starexec.solver_build_storage_leases(cleanup_token) WHERE cleanup_token IS NOT NULL;
