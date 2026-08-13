-- Fixes "Failed to mark upload job as completed" on partial uploads, and gives the
-- completion routine a way to say why it did nothing.
--
-- Two defects, one symptom:
--
-- 1. V0100 added 'COMPLETED_WITH_ERRORS' to upload_jobs_status_check but left the
--    column at VARCHAR(20). That value is 21 characters, so the constraint permitted a
--    value the column could never hold. Any upload where total_files_processed <
--    total_files_found took that branch and failed with SQLSTATE 22001 (value too long
--    for type character varying(20)). UploadJobQueue.completeJob caught the SQLException,
--    returned false, and UploadJobWorker reported the generic
--    "Failed to mark upload job as completed" -- after the benchmarks had already been
--    inserted successfully. The job was then marked FAILED despite the work having been
--    done. VARCHAR(25) matches the v_new_status local the function already declares.
--
-- 2. complete_upload_job RETURNS VOID, and its UPDATE was guarded on
--    status = 'PROCESSING' AND cancel_requested = FALSE. When the guard did not match,
--    the function completed silently and the caller could only infer failure by
--    re-reading the row, with no way to distinguish "already cancelled" from "job
--    vanished" from "someone else finished it". It now returns the outcome so the
--    caller can report a specific reason.

ALTER TABLE starexec.upload_jobs ALTER COLUMN status TYPE VARCHAR(25);

-- Return type changes from VOID to TEXT, so the old signature must be dropped first --
-- CREATE OR REPLACE cannot change a function's return type.
DROP FUNCTION IF EXISTS starexec.complete_upload_job(BIGINT);

CREATE FUNCTION starexec.complete_upload_job(p_job_id BIGINT)
RETURNS TEXT AS $$
DECLARE
    v_found      INT;
    v_processed  INT;
    v_status     VARCHAR(25);
    v_cancel     BOOLEAN;
    v_new_status VARCHAR(25);
BEGIN
    -- FOR UPDATE so the guard below cannot race another worker between the read and
    -- the write. The previous version folded both into a single guarded UPDATE, which
    -- was race-free but could not report which guard rejected it.
    SELECT total_files_found, total_files_processed, status, cancel_requested
    INTO v_found, v_processed, v_status, v_cancel
    FROM starexec.upload_jobs
    WHERE id = p_job_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;

    IF v_cancel THEN
        RETURN 'CANCEL_REQUESTED';
    END IF;

    IF v_status <> 'PROCESSING' THEN
        RETURN 'NOT_PROCESSING:' || v_status;
    END IF;

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
    WHERE id = p_job_id;

    RETURN v_new_status;
END;
$$ LANGUAGE plpgsql;
