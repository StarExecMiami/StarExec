-- Read-only diagnostic for job pairs that may have hit a resource limit but were
-- recorded as having completed successfully.
--
--   psql -U starexec -d starexec -f sql/audit-misclassified-limits.sql
--
-- SELECT only. No DDL, no writes. Safe against production without a window.
--
--
-- WHAT THIS IS LOOKING FOR
--
-- In container mode the monitor read stats.json and returned before reading
-- watcher.out. stats.json carries timings only; the wallclockExceeded, cpuExceeded
-- and memoryExceeded flags come solely from watcher.out. With all three left false
-- and a zero container exit code, determineStatus returned STATUS_COMPLETE. A
-- solver that exhausted its limit was therefore recorded as having finished.
--
-- That is fixed going forward. This finds the pairs already recorded that way.
--
--
-- WHAT THIS CANNOT DO -- read this before acting on the output
--
-- 1. It is suggestive, not conclusive. A solver may legitimately finish at or just
--    under its limit. Reaching the limit is strong evidence of having been killed,
--    not proof of it.
-- 2. It cannot tell which backend ran a pair. Only container-mode runs were
--    affected; pairs run under SGE or the local backend read watcher.out normally
--    and are not suspect, but the database does not record which path was taken.
--    Expect false positives from non-container runs.
-- 3. Memory is compared last and separately because max_vmem and maximum_memory
--    are not certain to share a unit. Treat section 3 as a weaker signal than
--    sections 1 and 2, which compare seconds against seconds.
--
-- Section 4 is the most informative part. If a deployment shows many at-limit
-- COMPLETE pairs and almost no EXCEED_* pairs, limit detection was not working.
-- If both are present in proportion, the at-limit COMPLETE rows are more likely to
-- be genuine finishes.

\set ON_ERROR_STOP on

\echo ''
\echo '=============================================================================='
\echo 'SECTION 1 -- COMPLETE pairs whose wallclock reached the wallclock limit'
\echo '=============================================================================='

SELECT jp.id            AS pair_id,
       jp.job_id,
       jsd.stage_number,
       round(jsd.wallclock::numeric, 2) AS wallclock_used,
       COALESCE(jsp.clockTimeout, j.clockTimeout) AS wallclock_limit,
       j.name           AS job_name
FROM starexec.job_pairs jp
JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
JOIN starexec.jobs j ON j.id = jp.job_id
LEFT JOIN starexec.job_stage_params jsp
       ON jsp.job_id = jp.job_id AND jsp.stage_number = jsd.stage_number
WHERE jp.status_code = 7                                   -- STATUS_COMPLETE
  AND COALESCE(jsp.clockTimeout, j.clockTimeout) > 0
  AND jsd.wallclock >= COALESCE(jsp.clockTimeout, j.clockTimeout)
ORDER BY jsd.wallclock - COALESCE(jsp.clockTimeout, j.clockTimeout) DESC
LIMIT 100;

\echo ''
\echo '=============================================================================='
\echo 'SECTION 2 -- COMPLETE pairs whose CPU time reached the CPU limit'
\echo '=============================================================================='

SELECT jp.id            AS pair_id,
       jp.job_id,
       jsd.stage_number,
       round(jsd.cpu::numeric, 2) AS cpu_used,
       COALESCE(jsp.cpuTimeout, j.cpuTimeout) AS cpu_limit,
       j.name           AS job_name
FROM starexec.job_pairs jp
JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
JOIN starexec.jobs j ON j.id = jp.job_id
LEFT JOIN starexec.job_stage_params jsp
       ON jsp.job_id = jp.job_id AND jsp.stage_number = jsd.stage_number
WHERE jp.status_code = 7
  AND COALESCE(jsp.cpuTimeout, j.cpuTimeout) > 0
  AND jsd.cpu >= COALESCE(jsp.cpuTimeout, j.cpuTimeout)
ORDER BY jsd.cpu - COALESCE(jsp.cpuTimeout, j.cpuTimeout) DESC
LIMIT 100;

\echo ''
\echo '=============================================================================='
\echo 'SECTION 3 -- COMPLETE pairs whose peak memory reached the memory limit'
\echo '  WEAKER SIGNAL: max_vmem and maximum_memory may not share a unit.'
\echo '  Confirm the units for this deployment before drawing any conclusion.'
\echo '=============================================================================='

SELECT jp.id            AS pair_id,
       jp.job_id,
       jsd.stage_number,
       jsd.max_vmem     AS memory_used,
       COALESCE(jsp.maximum_memory, j.maximum_memory) AS memory_limit
FROM starexec.job_pairs jp
JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
JOIN starexec.jobs j ON j.id = jp.job_id
LEFT JOIN starexec.job_stage_params jsp
       ON jsp.job_id = jp.job_id AND jsp.stage_number = jsd.stage_number
WHERE jp.status_code = 7
  AND COALESCE(jsp.maximum_memory, j.maximum_memory) > 0
  AND jsd.max_vmem >= COALESCE(jsp.maximum_memory, j.maximum_memory)
ORDER BY jsd.max_vmem DESC
LIMIT 50;

\echo ''
\echo '=============================================================================='
\echo 'SECTION 4 -- the comparison that actually tells you something'
\echo '  How many pairs were recorded as having exceeded a limit, against how many'
\echo '  were recorded COMPLETE while sitting at one. A deployment where limit'
\echo '  detection worked shows plenty of the former.'
\echo '=============================================================================='

WITH effective AS (
    SELECT jp.id AS pair_id,
           jp.status_code,
           jsd.wallclock,
           jsd.cpu,
           COALESCE(jsp.clockTimeout, j.clockTimeout) AS clock_limit,
           COALESCE(jsp.cpuTimeout,  j.cpuTimeout)   AS cpu_limit
    FROM starexec.job_pairs jp
    JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    JOIN starexec.jobs j ON j.id = jp.job_id
    LEFT JOIN starexec.job_stage_params jsp
           ON jsp.job_id = jp.job_id AND jsp.stage_number = jsd.stage_number
)
SELECT
    (SELECT count(*) FROM starexec.job_pairs WHERE status_code = 14) AS recorded_exceed_runtime,
    (SELECT count(*) FROM starexec.job_pairs WHERE status_code = 15) AS recorded_exceed_cpu,
    (SELECT count(*) FROM starexec.job_pairs WHERE status_code = 17) AS recorded_exceed_mem,
    count(*) FILTER (
        WHERE status_code = 7
          AND ((clock_limit > 0 AND wallclock >= clock_limit)
            OR (cpu_limit   > 0 AND cpu       >= cpu_limit))
    ) AS complete_but_at_a_limit,
    (SELECT count(*) FROM starexec.job_pairs WHERE status_code = 7) AS recorded_complete_total
FROM effective;

\echo ''
\echo 'Audit complete. Nothing was modified.'
\echo ''
\echo 'These are candidates, not verdicts. Before changing any recorded result,'
\echo 'confirm against the pair output still on disk -- watcher.out names the limit'
\echo 'that fired -- and against which backend actually ran the job. Rewriting a'
\echo 'competition result on the strength of a heuristic would be worse than the'
\echo 'defect this looks for.'
