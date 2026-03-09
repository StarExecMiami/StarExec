-- V0103: Batch rerun function fix
-- Replaces the BEGIN...EXCEPTION loop with a single set-based operation
-- to completely eliminate subtransactions and SubtransControlLock contention.
-- Error handling (e.g., corrupt pairs) is delegated to the Java layer.

DROP FUNCTION IF EXISTS starexec.RerunJobPairsBatch(INT[]) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RerunJobPairsBatch(_pairIds INT[])
RETURNS VOID AS $$
BEGIN
    -- SURGICAL FIX: Single, set-based operations. Zero subtransactions generated.
    -- If a constraint violation occurs, the entire statement aborts and throws an exception to Java.

    -- 1. Identify pairs and reclaim disk size from jobs
    UPDATE starexec.jobs j
    SET disk_size = GREATEST(j.disk_size - sub.reclaim, 0)
    FROM (
        SELECT jp.job_id, COALESCE(SUM(jsd.disk_size), 0) as reclaim
        FROM starexec.job_pairs jp
        JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
        WHERE jp.id = ANY(_pairIds)
        GROUP BY jp.job_id
    ) sub
    WHERE j.id = sub.job_id AND sub.reclaim > 0;

    -- 2. Reclaim disk size from users
    UPDATE starexec.users u
    SET disk_size = GREATEST(u.disk_size - sub.reclaim, 0)
    FROM (
        SELECT j.user_id, COALESCE(SUM(jsd.disk_size), 0) as reclaim
        FROM starexec.job_pairs jp
        JOIN starexec.jobs j ON j.id = jp.job_id
        JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
        WHERE jp.id = ANY(_pairIds)
        GROUP BY j.user_id
    ) sub
    WHERE u.id = sub.user_id AND sub.reclaim > 0;

    -- 3. Zero out the disk_size and reset stage statuses to PENDING_SUBMIT (1)
    UPDATE starexec.jobpair_stage_data
    SET disk_size = 0,
        status_code = 1
    WHERE jobpair_id = ANY(_pairIds);

    -- 4. Remove completion records
    DELETE FROM starexec.job_pair_completion WHERE pair_id = ANY(_pairIds);

    -- 5. Reset the pair status to PENDING_SUBMIT (1)
    UPDATE starexec.job_pairs
    SET status_code = 1
    WHERE id = ANY(_pairIds);

END;
$$ LANGUAGE plpgsql;
