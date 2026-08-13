-- Lets an upload job record the authoritative count of benchmark files it will process,
-- instead of being stuck with the extraction-time estimate.
--
-- total_files_found is written from two different kinds of source:
--
--   1. an ESTIMATE during extraction -- the number of files pulled out of the archive,
--      which includes everything the benchmark traversal later discards (ignored files,
--      names that fail Validator.isValidBenchName); and
--   2. the AUTHORITATIVE count, known once the traversal has finished walking the
--      extracted directory.
--
-- update_upload_job_progress merges with GREATEST (V0109, to stop the UI total regressing
-- to a partial in-flight count), so the estimate always wins and the authoritative count
-- is discarded. Any archive containing a non-benchmark file therefore ends with
-- total_files_processed < total_files_found, which complete_upload_job reads as
-- COMPLETED_WITH_ERRORS even though every benchmark was added successfully.
--
-- This function replaces the total rather than merging it, which is safe precisely
-- because it is only called once the real count is known. V0109's monotonic merge is
-- left untouched for the streaming progress updates it was written to protect.

CREATE OR REPLACE FUNCTION starexec.set_upload_job_total_files(p_job_id BIGINT, p_total INT)
RETURNS VOID AS $$
BEGIN
    UPDATE starexec.upload_jobs
    -- Never below what has already been processed: a resumed job carries a non-zero
    -- total_files_processed, and found < processed would invert the completion test.
    SET total_files_found = GREATEST(p_total, total_files_processed),
        last_heartbeat = CURRENT_TIMESTAMP
    WHERE id = p_job_id
      AND status = 'PROCESSING';
END;
$$ LANGUAGE plpgsql;
