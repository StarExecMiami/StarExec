-- Resumable benchmark upload sessions and cooperative async job controls

CREATE TABLE starexec.upload_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    space_id INT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    file_name VARCHAR(1024) NOT NULL,
    staging_path TEXT NOT NULL,
    total_bytes BIGINT NOT NULL CHECK (total_bytes > 0),
    bytes_received BIGINT NOT NULL DEFAULT 0 CHECK (bytes_received >= 0 AND bytes_received <= total_bytes),
    chunk_size INT NOT NULL CHECK (chunk_size > 0),
    next_chunk_index INT NOT NULL DEFAULT 0 CHECK (next_chunk_index >= 0),
    total_chunks INT NOT NULL CHECK (total_chunks > 0),
    upload_method VARCHAR(20) NOT NULL CHECK (upload_method IN ('convert', 'dump')),
    benchmark_type_id INT NOT NULL,
    downloadable BOOLEAN NOT NULL DEFAULT FALSE,
    has_dependencies BOOLEAN NOT NULL DEFAULT FALSE,
    dep_root_space_id INT REFERENCES spaces(id) ON DELETE SET NULL,
    linked BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'READY', 'FINALIZING', 'COMPLETE', 'ABORTED', 'EXPIRED', 'FAILED')),
    job_id BIGINT UNIQUE REFERENCES starexec.upload_jobs(id) ON DELETE SET NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    CHECK (next_chunk_index <= total_chunks)
);

CREATE INDEX idx_upload_sessions_user_status
    ON starexec.upload_sessions (user_id, status, created_at DESC);

CREATE INDEX idx_upload_sessions_expires_at
    ON starexec.upload_sessions (expires_at);

ALTER TABLE starexec.upload_jobs
    ADD COLUMN IF NOT EXISTS upload_session_id BIGINT REFERENCES starexec.upload_sessions(id) ON DELETE SET NULL;

ALTER TABLE starexec.upload_jobs
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_upload_jobs_cancel_requested
    ON starexec.upload_jobs (cancel_requested)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE starexec.upload_sessions IS 'Tracks resumable browser-side benchmark archive uploads prior to async processing';
COMMENT ON COLUMN starexec.upload_sessions.staging_path IS 'Path to the partially or fully assembled archive on disk';
COMMENT ON COLUMN starexec.upload_sessions.next_chunk_index IS 'Next sequential chunk index expected from the browser';
COMMENT ON COLUMN starexec.upload_jobs.cancel_requested IS 'True when a user has requested cooperative cancellation of a processing upload job';
COMMENT ON COLUMN starexec.upload_jobs.upload_session_id IS 'Originating resumable upload session for this async upload job';

DROP FUNCTION IF EXISTS starexec.update_upload_job_progress(BIGINT, INT, INT, INT, TEXT, INT);
CREATE OR REPLACE FUNCTION starexec.update_upload_job_progress(
    p_job_id BIGINT,
    p_files_found INT DEFAULT NULL,
    p_files_processed INT DEFAULT NULL,
    p_spaces_created INT DEFAULT NULL,
    p_last_path TEXT DEFAULT NULL,
    p_last_processed_index INT DEFAULT NULL
)
RETURNS VOID AS $$
BEGIN
    UPDATE starexec.upload_jobs
    SET total_files_found = COALESCE(p_files_found, total_files_found),
        total_files_processed = COALESCE(p_files_processed, total_files_processed),
        total_spaces_created = COALESCE(p_spaces_created, total_spaces_created),
        last_processed_path = COALESCE(p_last_path, last_processed_path),
        last_processed_index = COALESCE(p_last_processed_index, last_processed_index),
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.complete_upload_job(p_job_id BIGINT)
RETURNS VOID AS $$
DECLARE
    v_found INT;
    v_processed INT;
    v_new_status VARCHAR(25);
BEGIN
    SELECT total_files_found, total_files_processed
    INTO v_found, v_processed
    FROM starexec.upload_jobs
    WHERE id = p_job_id;

    IF v_processed < v_found THEN
        v_new_status := 'COMPLETED_WITH_ERRORS';
    ELSE
        v_new_status := 'COMPLETED';
    END IF;

    UPDATE starexec.upload_jobs
    SET status = v_new_status,
        cancel_requested = FALSE,
        completed_at = CURRENT_TIMESTAMP,
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id
      AND status = 'PROCESSING'
      AND cancel_requested = FALSE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.fail_upload_job(p_job_id BIGINT, p_error_message TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE starexec.upload_jobs
    SET status = 'FAILED',
        error_message = p_error_message,
        cancel_requested = FALSE,
        completed_at = CURRENT_TIMESTAMP,
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id
      AND status <> 'CANCELLED';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.retry_upload_job(p_job_id BIGINT)
RETURNS BOOLEAN AS $$
DECLARE
    current_retry_count INT;
    current_max_retries INT;
    affected_rows INT := 0;
BEGIN
    SELECT retry_count, max_retries
    INTO current_retry_count, current_max_retries
    FROM starexec.upload_jobs
    WHERE id = p_job_id;

    IF current_retry_count < current_max_retries THEN
        UPDATE starexec.upload_jobs
        SET status = 'PENDING',
            retry_count = retry_count + 1,
            cancel_requested = FALSE,
            started_at = NULL,
            completed_at = NULL,
            last_heartbeat = NULL,
            error_message = NULL
        WHERE id = p_job_id
          AND status IN ('FAILED', 'CANCELLED');

        GET DIAGNOSTICS affected_rows = ROW_COUNT;
    END IF;

    RETURN affected_rows > 0;
END;
$$ LANGUAGE plpgsql;
