-- Read-only audit of disk-usage accounting drift.
--
-- Safe to run against production: SELECT only, no DDL, no writes, no locks beyond
-- ordinary read snapshots. Nothing here changes a single row.
--
--   psql -U starexec -d starexec -f sql/audit-disk-quota.sql
--
-- WHY THIS EXISTS
--
-- users.disk_size and jobs.disk_size are maintained incrementally by stored routines
-- rather than derived, and three separate defects let them drift:
--
--   1. The asynchronous upload path inserted benchmarks without charging for them
--      (fixed; V0113 repairs the rows it left behind).
--   2. UpdatePairRunSolverStats added the full reported figure on every call while the
--      stage row was written by overwrite, so any redelivery charged twice and the
--      refund path returned it once (fixed: the charge is now a delta).
--   3. Seven deletion routines subtract with no floor, so a total can go negative --
--      and once users.disk_size is negative the quota test (quota <= usage) is false
--      forever, which disables enforcement silently.
--
-- Fixes 1 and 2 stop new drift. Neither undoes what already accumulated. This audit
-- measures what is actually there, so the repair can be sized before it is scheduled --
-- and so nobody adds CHECK (disk_size >= 0) to a table that would immediately violate it.
--
-- SEQUENCING, WHICH MATTERS
--
-- Section B must be repaired BEFORE section A. The recompute in V0113, and
-- Users.updateAllUserDiskSizes(), both derive users.disk_size from jobs.disk_size. If a
-- job total is inflated by the double-charge, recomputing the user from it copies the
-- error into users.disk_size instead of correcting it.

\echo ''
\echo '=============================================================================='
\echo 'SECTION A -- users.disk_size versus the sum of what the user owns'
\echo '  expected = solvers + benchmarks + jobs, excluding deleted rows.'
\echo '  This is the definition V0113 and Users.updateAllUserDiskSizes() both use.'
\echo '=============================================================================='

WITH owned AS (
    SELECT u.id AS user_id,
           u.disk_size AS recorded,
           COALESCE((
               SELECT SUM(x.disk_size) FROM (
                   SELECT disk_size FROM starexec.solvers    WHERE user_id = u.id AND deleted = false
                   UNION ALL
                   SELECT disk_size FROM starexec.benchmarks WHERE user_id = u.id AND deleted = false
                   UNION ALL
                   SELECT disk_size FROM starexec.jobs       WHERE user_id = u.id AND deleted = false
               ) x
           ), 0) AS expected
    FROM starexec.users u
)
SELECT user_id,
       recorded,
       expected,
       recorded - expected AS drift,
       CASE
           WHEN recorded < 0                 THEN 'NEGATIVE - quota enforcement disabled'
           WHEN recorded - expected > 0      THEN 'over-counted'
           WHEN recorded - expected < 0      THEN 'under-counted'
           ELSE 'ok'
       END AS verdict
FROM owned
WHERE recorded <> expected OR recorded < 0
ORDER BY (recorded < 0) DESC, abs(recorded - expected) DESC
LIMIT 100;

\echo ''
\echo '-- Section A summary'
WITH owned AS (
    SELECT u.id AS user_id,
           u.disk_size AS recorded,
           COALESCE((
               SELECT SUM(x.disk_size) FROM (
                   SELECT disk_size FROM starexec.solvers    WHERE user_id = u.id AND deleted = false
                   UNION ALL
                   SELECT disk_size FROM starexec.benchmarks WHERE user_id = u.id AND deleted = false
                   UNION ALL
                   SELECT disk_size FROM starexec.jobs       WHERE user_id = u.id AND deleted = false
               ) x
           ), 0) AS expected
    FROM starexec.users u
)
SELECT count(*)                                              AS users_total,
       count(*) FILTER (WHERE recorded <> expected)          AS users_drifted,
       count(*) FILTER (WHERE recorded < 0)                  AS users_negative,
       COALESCE(sum(recorded - expected) FILTER (WHERE recorded > expected), 0) AS bytes_over,
       COALESCE(sum(expected - recorded) FILTER (WHERE recorded < expected), 0) AS bytes_under
FROM owned;

\echo ''
\echo '=============================================================================='
\echo 'SECTION B -- jobs.disk_size versus the sum of its stage rows'
\echo '  This is where the double-charge landed. Repair this BEFORE section A.'
\echo '=============================================================================='

WITH staged AS (
    SELECT j.id AS job_id,
           j.user_id,
           j.disk_size AS recorded,
           COALESCE((
               SELECT SUM(jsd.disk_size)
               FROM starexec.jobpair_stage_data jsd
               JOIN starexec.job_pairs jp ON jp.id = jsd.jobpair_id
               WHERE jp.job_id = j.id
           ), 0) AS expected
    FROM starexec.jobs j
    WHERE j.deleted = false
)
SELECT job_id,
       user_id,
       recorded,
       expected,
       recorded - expected AS drift,
       CASE
           WHEN recorded < 0            THEN 'NEGATIVE'
           WHEN recorded - expected > 0 THEN 'over-counted (redelivered stats)'
           WHEN recorded - expected < 0 THEN 'under-counted'
           ELSE 'ok'
       END AS verdict
FROM staged
WHERE recorded <> expected OR recorded < 0
ORDER BY (recorded < 0) DESC, abs(recorded - expected) DESC
LIMIT 100;

\echo ''
\echo '-- Section B summary'
WITH staged AS (
    SELECT j.id AS job_id,
           j.disk_size AS recorded,
           COALESCE((
               SELECT SUM(jsd.disk_size)
               FROM starexec.jobpair_stage_data jsd
               JOIN starexec.job_pairs jp ON jp.id = jsd.jobpair_id
               WHERE jp.job_id = j.id
           ), 0) AS expected
    FROM starexec.jobs j
    WHERE j.deleted = false
)
SELECT count(*)                                     AS jobs_total,
       count(*) FILTER (WHERE recorded <> expected) AS jobs_drifted,
       count(*) FILTER (WHERE recorded < 0)         AS jobs_negative,
       COALESCE(sum(recorded - expected) FILTER (WHERE recorded > expected), 0) AS bytes_over,
       COALESCE(sum(expected - recorded) FILTER (WHERE recorded < expected), 0) AS bytes_under
FROM staged;

\echo ''
\echo '=============================================================================='
\echo 'SECTION C -- would CHECK (disk_size >= 0) be addable today?'
\echo '=============================================================================='

SELECT 'users' AS table_name,
       count(*) FILTER (WHERE disk_size < 0) AS rows_violating,
       CASE WHEN count(*) FILTER (WHERE disk_size < 0) = 0
            THEN 'constraint could be added'
            ELSE 'constraint would FAIL - repair first' END AS verdict
FROM starexec.users
UNION ALL
SELECT 'jobs',
       count(*) FILTER (WHERE disk_size < 0),
       CASE WHEN count(*) FILTER (WHERE disk_size < 0) = 0
            THEN 'constraint could be added'
            ELSE 'constraint would FAIL - repair first' END
FROM starexec.jobs;

\echo ''
\echo 'Audit complete. Nothing was modified.'
