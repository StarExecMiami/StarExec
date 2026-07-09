-- Track upload-owned filesystem artifacts separately from upload business state.
-- The cleanup worker deletes only artifact rows whose owner is terminal, whose
-- retention window has elapsed, and whose path passes Java-side safety checks.

CREATE TABLE starexec.upload_artifacts (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NULL,
    job_id BIGINT NULL,
    artifact_role VARCHAR(32) NOT NULL,
    path_kind VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    cleanup_state VARCHAR(32) NOT NULL DEFAULT 'RETAINED',
    retention_until TIMESTAMPTZ NULL,
    owner_terminal_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL,
    delete_attempt_count INT NOT NULL DEFAULT 0,
    last_delete_attempt_at TIMESTAMPTZ NULL,
    last_delete_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT upload_artifacts_owner_check CHECK (session_id IS NOT NULL OR job_id IS NOT NULL),
    CONSTRAINT upload_artifacts_role_check CHECK (artifact_role IN (
        'SOURCE_ARCHIVE', 'CHUNK_DIRECTORY', 'ASSEMBLING_FILE', 'EXTRACT_DIRECTORY'
    )),
    CONSTRAINT upload_artifacts_path_kind_check CHECK (path_kind IN ('FILE', 'DIRECTORY')),
    CONSTRAINT upload_artifacts_cleanup_state_check CHECK (cleanup_state IN (
        'RETAINED', 'READY', 'DELETING', 'DELETED', 'MISSING', 'BLOCKED_REFERENCED', 'DELETE_FAILED', 'INVALID_PATH'
    )),
    CONSTRAINT upload_artifacts_session_fk FOREIGN KEY (session_id)
        REFERENCES starexec.upload_sessions(id) ON DELETE SET NULL,
    CONSTRAINT upload_artifacts_job_fk FOREIGN KEY (job_id)
        REFERENCES starexec.upload_jobs(id) ON DELETE CASCADE
);

CREATE INDEX upload_artifacts_cleanup_due_idx
    ON starexec.upload_artifacts (retention_until, id)
    WHERE deleted_at IS NULL
      AND retention_until IS NOT NULL
      AND cleanup_state IN ('RETAINED', 'READY', 'DELETE_FAILED', 'MISSING', 'DELETING', 'BLOCKED_REFERENCED');

CREATE INDEX upload_artifacts_job_role_idx
    ON starexec.upload_artifacts (job_id, artifact_role)
    WHERE job_id IS NOT NULL;

CREATE UNIQUE INDEX upload_artifacts_source_archive_job_uidx
    ON starexec.upload_artifacts (job_id)
    WHERE job_id IS NOT NULL AND artifact_role = 'SOURCE_ARCHIVE';

CREATE INDEX upload_artifacts_session_role_idx
    ON starexec.upload_artifacts (session_id, artifact_role)
    WHERE session_id IS NOT NULL;

CREATE INDEX upload_artifacts_path_idx
    ON starexec.upload_artifacts (path);

-- Conservative backfill: only source archives are created for existing jobs.
-- Extract directories are not backfilled because successful imports may have
-- durable benchmarks under them and require separate provenance work.
INSERT INTO starexec.upload_artifacts (
    session_id,
    job_id,
    artifact_role,
    path_kind,
    path,
    cleanup_state,
    retention_until,
    owner_terminal_at
)
SELECT
    upload_session_id,
    id,
    'SOURCE_ARCHIVE',
    'FILE',
    archive_path,
    'RETAINED',
    CASE
        WHEN status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
        THEN COALESCE(completed_at, CURRENT_TIMESTAMP) + INTERVAL '7 days'
        ELSE NULL
    END,
    completed_at
FROM starexec.upload_jobs
WHERE archive_path IS NOT NULL
  AND archive_path <> '';
