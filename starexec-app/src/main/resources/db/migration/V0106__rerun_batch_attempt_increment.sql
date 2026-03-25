-- V0106: Ensure rerun attempt increment is atomic with pair reset
-- Replaces RerunJobPairsBatch with an implementation that increments
-- job_pair_attempts in the same transaction as status reset.

CREATE OR REPLACE FUNCTION starexec.RerunJobPairsBatch(_pairIds INT[])
RETURNS VOID AS $$
BEGIN
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

    -- 3. Zero out disk size and reset stage statuses to PENDING_SUBMIT (1)
    UPDATE starexec.jobpair_stage_data
    SET disk_size = 0,
        status_code = 1
    WHERE jobpair_id = ANY(_pairIds);

    -- 4. Remove completion records
    DELETE FROM starexec.job_pair_completion WHERE pair_id = ANY(_pairIds);

    -- 5. Reset pair status to PENDING_SUBMIT (1)
    UPDATE starexec.job_pairs
    SET status_code = 1
    WHERE id = ANY(_pairIds);

    -- 6. Ensure attempt rows exist and increment attempt atomically.
    INSERT INTO starexec.job_pair_attempts (pair_id, current_attempt_no, updated_at)
    SELECT unnest(_pairIds), 1, NOW()
    ON CONFLICT (pair_id) DO NOTHING;

    UPDATE starexec.job_pair_attempts
    SET current_attempt_no = current_attempt_no + 1,
        updated_at = NOW()
    WHERE pair_id = ANY(_pairIds);

END;
$$ LANGUAGE plpgsql;
