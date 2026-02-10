-- ============================================================================
-- Job 5 Recovery Script
-- ============================================================================
-- This script recovers job 5 from the stuck state by marking both job pairs
-- with a complete status code, which triggers the job completion logic.
--
-- Job ID: 5
-- Job Name: "BOO 2025-12-09 05.43"
-- Created: 2025-12-09 10:43:13.119Z
-- Status: STUCK (NULL completed timestamp)
--
-- Background: The LocalBackend failed to process job completion files
-- (status.json), leaving both pairs in incomplete states. This script
-- manually triggers job completion.
-- ============================================================================

BEGIN;

-- Step 1: Verify current job state before recovery
SELECT
  j.id, j.name, j.created, j.completed,
  COUNT(jp.id) as total_pairs,
  SUM(CASE WHEN jp.status_code >= 7 OR jp.status_code IN (20, 21) THEN 1 ELSE 0 END) as complete_pairs,
  STRING_AGG(jp.id || ':' || jp.status_code, ', ') as pair_statuses
FROM jobs j
LEFT JOIN job_pairs jp ON j.id = jp.job_id
WHERE j.id = 5
GROUP BY j.id, j.name, j.created, j.completed;

-- Step 2: Mark pair 5 as complete (STATUS_RESULTS = 10)
-- This is a "complete" status code that will trigger job completion check
CALL starexec.UpdatePairStatus(5, 10);

-- Step 3: Mark pair 6 as complete (STATUS_RESULTS = 10)
-- When this second pair is marked complete, the job completion trigger fires
-- because all pairs in the job now have complete status codes
CALL starexec.UpdatePairStatus(6, 10);

-- Step 4: Verify job is now marked as completed
SELECT
  j.id, j.name, j.created, j.completed,
  COUNT(jp.id) as total_pairs,
  SUM(CASE WHEN jp.status_code >= 7 OR jp.status_code IN (20, 21) THEN 1 ELSE 0 END) as complete_pairs,
  STRING_AGG(jp.id || ':' || jp.status_code, ', ') as pair_statuses
FROM jobs j
LEFT JOIN job_pairs jp ON j.id = jp.job_id
WHERE j.id = 5
GROUP BY j.id, j.name, j.created, j.completed;

-- Step 5: Verify job pair completion records were created
SELECT pair_id, completion_id FROM job_pair_completion
WHERE pair_id IN (5, 6)
ORDER BY pair_id;

-- Step 6: Check for any errors in error_logs around job submission time
SELECT id, time, log_level_id, message
FROM error_logs
WHERE time >= '2025-12-09 10:43:00'::TIMESTAMP
  AND time <= '2025-12-09 10:44:00'::TIMESTAMP
ORDER BY time DESC
LIMIT 10;

COMMIT;

-- ============================================================================
-- Verification Queries (run after COMMIT to verify recovery)
-- ============================================================================

-- Verify job is complete
-- SELECT id, name, created, completed FROM jobs WHERE id = 5;

-- Verify all pairs are in complete state
-- SELECT id, job_id, status_code FROM job_pairs WHERE job_id = 5;

-- Verify no other jobs are stuck with similar symptoms
-- SELECT j.id, j.name, j.created, j.completed
-- FROM jobs j
-- WHERE j.completed IS NULL
--   AND j.created < NOW() - INTERVAL '10 minutes'
--   AND EXISTS (
--     SELECT 1 FROM job_pairs jp
--     WHERE jp.job_id = j.id
--     AND (jp.status_code < 7 OR jp.status_code > 18)
--   )
-- ORDER BY j.created DESC;
