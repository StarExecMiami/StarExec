-- Ensure upload progress counters are monotonic so totals never regress.
-- This prevents UI progress from dropping total_files_found to a partial in-flight count.

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
    SET total_files_found = GREATEST(
            total_files_found,
            COALESCE(p_files_found, total_files_found),
            COALESCE(p_files_processed, total_files_processed)
        ),
        total_files_processed = GREATEST(
            total_files_processed,
            COALESCE(p_files_processed, total_files_processed)
        ),
        total_spaces_created = GREATEST(
            total_spaces_created,
            COALESCE(p_spaces_created, total_spaces_created)
        ),
        last_processed_path = COALESCE(p_last_path, last_processed_path),
        last_processed_index = GREATEST(
            last_processed_index,
            COALESCE(p_last_processed_index, last_processed_index)
        ),
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;
