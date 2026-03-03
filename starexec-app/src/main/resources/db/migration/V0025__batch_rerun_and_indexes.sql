-- V0025: Batch rerun function to replace N+1 loop in Jobs.setAllPairsToPending
-- The CREATE INDEX CONCURRENTLY statements have been intentionally moved to
-- V0026__add_missing_indexes.sql because PostgreSQL forbids CONCURRENTLY inside
-- a transaction block, and Flyway wraps every migration in a transaction by
-- default.  V0026 is annotated with -- flyway:executeInTransaction=false.

-- =============================================================================
-- PART 1: Batch rerun function
-- =============================================================================

-- Resets an array of job pairs back to PENDING_SUBMIT (status_code = 1) in a
-- single database round-trip.  This replaces the N+1 pattern in Java where
-- Jobs.rerunPair() was called once per pair inside a loop.
--
-- For each pair this function:
--   1. Zeroes out its disk size (updating jobs and users accounting tables).
--   2. Removes its entry from job_pair_completion (if one exists).
--   3. Sets the pair status_code to 1 (PENDING_SUBMIT).
--   4. Sets every stage of the pair to status_code 1.
--
-- The cache invalidation (job_stats) and backend kill operations are left to
-- the Java layer because they require application logic (job_space traversal
-- for stats, and HPC backend calls for kill).
--
-- Note: Unlike RemovePairFromCompletedTable(), this function does NOT raise an
-- error when a completion record is absent — that is intentional because pairs
-- that were never completed have no such record.

DROP FUNCTION IF EXISTS starexec.RerunJobPairsBatch(INT[]) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RerunJobPairsBatch(_pairIds INT[])
RETURNS VOID AS $$
DECLARE
    _pairId       INT;
    _sumDiskSize  BIGINT;
    _jobId        INT;
    _userId       INT;
BEGIN
    FOREACH _pairId IN ARRAY _pairIds LOOP
        -- Each pair is processed independently.  If an unexpected error occurs
        -- (FK violation, lock timeout, deadlock) the pair is skipped with a
        -- WARNING rather than aborting the entire batch ("poison pill" isolation).
        BEGIN

            -- 1. Calculate disk size to reclaim for this pair
            SELECT COALESCE(SUM(disk_size), 0)
            INTO _sumDiskSize
            FROM starexec.jobpair_stage_data
            WHERE jobpair_id = _pairId;

            -- 2. Identify the owning job and user
            SELECT j.id, j.user_id
            INTO _jobId, _userId
            FROM starexec.job_pairs jp
            JOIN starexec.jobs j ON j.id = jp.job_id
            WHERE jp.id = _pairId;

            IF NOT FOUND THEN
                RAISE WARNING 'RerunJobPairsBatch: job pair % not found, skipping', _pairId;
                CONTINUE;
            END IF;

            -- 3. Zero out the disk_size on all stages of this pair
            UPDATE starexec.jobpair_stage_data
            SET disk_size = 0
            WHERE jobpair_id = _pairId;

            -- 4. Reclaim disk size from the job and user accounting rows
            IF _sumDiskSize > 0 THEN
                UPDATE starexec.jobs
                SET disk_size = GREATEST(disk_size - _sumDiskSize, 0)
                WHERE id = _jobId;

                UPDATE starexec.users
                SET disk_size = GREATEST(disk_size - _sumDiskSize, 0)
                WHERE id = _userId;
            END IF;

            -- 5. Remove completion record (no error if absent — pair may never have completed)
            DELETE FROM starexec.job_pair_completion WHERE pair_id = _pairId;

            -- 6. Reset the pair status to PENDING_SUBMIT (1)
            UPDATE starexec.job_pairs
            SET status_code = 1
            WHERE id = _pairId;

            -- 7. Reset all stage statuses to PENDING_SUBMIT (1)
            UPDATE starexec.jobpair_stage_data
            SET status_code = 1
            WHERE jobpair_id = _pairId;

        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'RerunJobPairsBatch: unexpected error on pair %, skipping: %',
                _pairId, SQLERRM;
        END;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
