-- V0100__improve_upload_job_error_reporting.sql
-- Improve error reporting for asynchronous upload jobs

-- 1. Update the status check constraint to include 'COMPLETED_WITH_ERRORS'
ALTER TABLE starexec.upload_jobs DROP CONSTRAINT upload_jobs_status_check;
ALTER TABLE starexec.upload_jobs ADD CONSTRAINT upload_jobs_status_check 
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'));

-- 2. Update the complete_upload_job function to handle partial success
CREATE OR REPLACE FUNCTION starexec.complete_upload_job(p_job_id BIGINT)
RETURNS VOID AS $$
DECLARE
    v_found INT;
    v_processed INT;
    v_new_status VARCHAR(25);
BEGIN
    -- Get current counts
    SELECT total_files_found, total_files_processed 
    INTO v_found, v_processed
    FROM upload_jobs 
    WHERE id = p_job_id;

    -- Determine status based on discrepancy
    IF v_processed < v_found THEN
        v_new_status := 'COMPLETED_WITH_ERRORS';
    ELSE
        v_new_status := 'COMPLETED';
    END IF;

    UPDATE upload_jobs 
    SET status = v_new_status,
        completed_at = CURRENT_TIMESTAMP
    WHERE id = p_job_id;
END;
$$ LANGUAGE plpgsql;

-- 3. Update the fail_upload_job function to ensure we don't overwrite existing errors 
-- if we're just appending more context
CREATE OR REPLACE FUNCTION starexec.append_upload_job_error(p_job_id BIGINT, p_error TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE upload_jobs 
    SET error_message = CASE 
            WHEN error_message IS NULL THEN p_error 
            ELSE error_message || E'
' || p_error 
        END
    WHERE id = p_job_id;
END;
$$ LANGUAGE plpgsql;
