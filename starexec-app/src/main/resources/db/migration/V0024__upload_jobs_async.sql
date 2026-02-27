-- V0024__upload_jobs_async.sql
-- Async upload job queue for benchmark uploads
-- Migration to support the new asynchronous upload system with progress tracking

-- ============================================================================
-- Table: upload_jobs
-- ============================================================================
CREATE TABLE upload_jobs (
    id BIGSERIAL PRIMARY KEY,
    archive_path TEXT NOT NULL,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    space_id INT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    upload_method VARCHAR(20) NOT NULL CHECK (upload_method IN ('convert', 'dump')),
    benchmark_type_id INT NOT NULL,
    downloadable BOOLEAN DEFAULT false,
    priority INT DEFAULT 0,
    archive_size BIGINT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    total_files_found INT DEFAULT 0,
    total_files_processed INT DEFAULT 0,
    total_spaces_created INT DEFAULT 0,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    last_heartbeat TIMESTAMP,
    scheduled_after TIMESTAMP,
    last_processed_path TEXT,
    last_processed_index INT DEFAULT 0,
    extract_path TEXT
);

-- ============================================================================
-- Indexes for efficient polling and querying
-- ============================================================================

-- Partial index for job claiming: Only index PENDING jobs, sorted by priority/size/date
-- This is the critical index for the background worker polling loop
-- O(1) or O(log n) lookup to find the next job to process
CREATE INDEX idx_upload_jobs_polling 
    ON upload_jobs (priority DESC, archive_size ASC, created_at ASC) 
    WHERE status = 'PENDING';

-- Index for user-specific job listings (most common query)
CREATE INDEX idx_upload_jobs_user_status 
    ON upload_jobs (user_id, status, created_at DESC);

-- Index for space-specific cleanup queries
CREATE INDEX idx_upload_jobs_space 
    ON upload_jobs (space_id, status);

-- Index for heartbeat-based stuck job detection
CREATE INDEX idx_upload_jobs_heartbeat 
    ON upload_jobs (last_heartbeat) 
    WHERE status = 'PROCESSING';

-- Index for cleanup of old completed jobs
CREATE INDEX idx_upload_jobs_completed_at 
    ON upload_jobs (completed_at) 
    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- ============================================================================
-- Comments for documentation
-- ============================================================================
COMMENT ON TABLE upload_jobs IS 'Queue for asynchronous benchmark upload processing';
COMMENT ON COLUMN upload_jobs.status IS 'Job state: PENDING → PROCESSING → COMPLETED/FAILED/CANCELLED';
COMMENT ON COLUMN upload_jobs.total_files_found IS 'Total benchmarks discovered in archive (set after Phase 1: discovery)';
COMMENT ON COLUMN upload_jobs.total_files_processed IS 'Benchmarks successfully inserted into database (increments during Phase 2)';
COMMENT ON COLUMN upload_jobs.total_spaces_created IS 'Number of subspaces created (for convert mode)';
COMMENT ON COLUMN upload_jobs.last_processed_path IS 'Idempotency: path of last processed file for resume support';
COMMENT ON COLUMN upload_jobs.extract_path IS 'Path where archive was extracted (for retry after failure)';

-- ============================================================================
-- Initial data: No seed data needed
-- ============================================================================

-- ============================================================================
-- Function: claim_upload_job()
-- Atomically claims a PENDING job using FOR UPDATE SKIP LOCKED
-- Returns the full job record as a result set for Java JDBC consumption
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.claim_upload_job()
RETURNS SETOF upload_jobs AS $$
BEGIN
    -- Atomic claim using FOR UPDATE SKIP LOCKED
    -- This ensures multiple workers don't claim the same job
    RETURN QUERY
    UPDATE upload_jobs 
    SET status = 'PROCESSING', 
        started_at = CURRENT_TIMESTAMP,
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = (
        SELECT id 
        FROM upload_jobs 
        WHERE status = 'PENDING' 
          AND (scheduled_after IS NULL OR scheduled_after <= CURRENT_TIMESTAMP)
        ORDER BY priority DESC, archive_size ASC, created_at ASC 
        LIMIT 1 
        FOR UPDATE SKIP LOCKED
    )
    RETURNING *;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Function: update_upload_job_progress(job_id, files_found, files_processed, spaces, last_path, heartbeat)
-- Updates progress counters for a job
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.update_upload_job_progress(
    p_job_id BIGINT,
    p_files_found INT DEFAULT NULL,
    p_files_processed INT DEFAULT NULL,
    p_spaces_created INT DEFAULT NULL,
    p_last_path TEXT DEFAULT NULL,
    p_heartbeat_interval INT DEFAULT 300
)
RETURNS VOID AS $$
BEGIN
    UPDATE upload_jobs 
    SET 
        total_files_found = COALESCE(p_files_found, total_files_found),
        total_files_processed = COALESCE(p_files_processed, total_files_processed),
        total_spaces_created = COALESCE(p_spaces_created, total_spaces_created),
        last_processed_path = COALESCE(p_last_path, last_processed_path),
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id 
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Function: complete_upload_job(job_id)
-- Marks a job as COMPLETED
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.complete_upload_job(p_job_id BIGINT)
RETURNS VOID AS $$
BEGIN
    UPDATE upload_jobs 
    SET status = 'COMPLETED',
        completed_at = CURRENT_TIMESTAMP,
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id 
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Function: fail_upload_job(job_id, error_message)
-- Marks a job as FAILED with error message
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.fail_upload_job(p_job_id BIGINT, p_error_message TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE upload_jobs 
    SET status = 'FAILED',
        error_message = p_error_message,
        completed_at = CURRENT_TIMESTAMP,
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id 
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Function: retry_upload_job(job_id)
-- Increments retry count and resets job to PENDING for reprocessing
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.retry_upload_job(p_job_id BIGINT)
RETURNS BOOLEAN AS $$
DECLARE
    can_retry BOOLEAN := FALSE;
    current_retry_count INT;
    current_max_retries INT;
BEGIN
    -- Get current retry state
    SELECT retry_count, max_retries INTO current_retry_count, current_max_retries
    FROM upload_jobs 
    WHERE id = p_job_id;
    
    -- Check if we can retry
    IF current_retry_count < current_max_retries THEN
        UPDATE upload_jobs 
        SET status = 'PENDING',
            retry_count = retry_count + 1,
            started_at = NULL,
            completed_at = NULL,
            last_heartbeat = NULL
        WHERE id = p_job_id 
          AND status IN ('FAILED', 'CANCELLED');
        
        GET DIAGNOSTICS can_retry = ROW_COUNT;
    END IF;
    
    RETURN can_retry > 0;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Function: touch_upload_job(job_id)
-- Updates heartbeat to prevent stuck job detection
-- ============================================================================
CREATE OR REPLACE FUNCTION starexec.touch_upload_job(p_job_id BIGINT)
RETURNS VOID AS $$
BEGIN
    UPDATE upload_jobs 
    SET last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id 
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;
