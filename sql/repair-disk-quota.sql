-- Two-phase repair of historical disk-usage drift.
--
--   DRY RUN (default -- reports what would change, then rolls back):
--     psql -U starexec -d starexec -f sql/repair-disk-quota.sql
--
--   APPLY:
--     psql -U starexec -d starexec -v apply=1 -f sql/repair-disk-quota.sql
--
-- Run sql/audit-disk-quota.sql first. This script assumes you have seen the
-- numbers it reports and decided to correct them.
--
--
-- WHY THIS IS A SCRIPT AND NOT A MIGRATION
--
-- A recompute is only correct while nothing else is writing the totals it derives.
-- A Flyway migration runs during deployment -- exactly when the application is
-- starting and workers may still be draining pairs -- and Flyway records it as
-- applied, so a total computed at a bad moment is both wrong and permanent. A
-- recompute is naturally re-runnable; making it a migration discards that for no
-- gain. Run this deliberately, in a quiesced window, as many times as needed.
--
-- V0113 contains a scoped recompute and sets the opposite precedent. It was
-- limited to users with dump-path upload jobs, which bounded the damage of getting
-- it wrong. This one is fleet-wide, so the same reasoning does not carry over.
--
--
-- WHY THE ORDER MATTERS
--
-- Phase 2 derives users.disk_size from jobs.disk_size, the same definition used by
-- V0113 and Users.updateAllUserDiskSizes(). Running phase 2 first would copy the
-- job-level over-count straight into the user total and call it repaired. Phase 1
-- must land first, in the same transaction.
--
--
-- WHY jobpair_stage_data IS TRUSTED
--
-- UpdatePairRunSolverStats writes the stage row by overwrite, not by accumulation,
-- so a stage row holds the last figure reported for that stage -- which is the
-- correct one. Only the aggregates above it accumulated the surplus. That is what
-- makes this repair possible at all.

\set ON_ERROR_STOP on
\timing off

BEGIN;

\echo ''
\echo '=============================================================================='
\echo 'PRECONDITION -- how quiesced is this database?'
\echo '=============================================================================='
\echo 'Pairs in a non-terminal state are still being written to. A nonzero count here'
\echo 'means some totals may move again the moment this finishes. It is not fatal --'
\echo 'the accounting fix makes each later write apply a correct delta on top of what'
\echo 'this script sets -- but the closer to zero, the less residual error remains.'
\echo ''

SELECT count(*) AS pairs_still_active,
       CASE WHEN count(*) = 0 THEN 'quiesced'
            ELSE 'ACTIVE WRITERS PRESENT - consider stopping workers first' END AS verdict
FROM starexec.job_pairs
WHERE NOT starexec.IsTerminalPairStatus(status_code);

-- Snapshot the current values so the report can show real before/after figures
-- rather than asserting that something was fixed.
CREATE TEMP TABLE _before_jobs ON COMMIT DROP AS
SELECT id, disk_size FROM starexec.jobs WHERE deleted = false;

CREATE TEMP TABLE _before_users ON COMMIT DROP AS
SELECT id, disk_size FROM starexec.users;

\echo ''
\echo '=============================================================================='
\echo 'PHASE 1 -- jobs.disk_size = sum of its stage rows'
\echo '=============================================================================='

UPDATE starexec.jobs j
SET disk_size = COALESCE((
        SELECT SUM(jsd.disk_size)
        FROM starexec.jobpair_stage_data jsd
        JOIN starexec.job_pairs jp ON jp.id = jsd.jobpair_id
        WHERE jp.job_id = j.id
    ), 0)
WHERE j.deleted = false;

SELECT count(*)                                        AS jobs_examined,
       count(*) FILTER (WHERE b.disk_size <> a.disk_size) AS jobs_corrected,
       COALESCE(sum(b.disk_size - a.disk_size) FILTER (WHERE b.disk_size > a.disk_size), 0) AS bytes_removed,
       COALESCE(sum(a.disk_size - b.disk_size) FILTER (WHERE b.disk_size < a.disk_size), 0) AS bytes_added
FROM _before_jobs b
JOIN starexec.jobs a ON a.id = b.id;

\echo ''
\echo '=============================================================================='
\echo 'PHASE 2 -- users.disk_size = solvers + benchmarks + jobs (corrected)'
\echo '=============================================================================='

UPDATE starexec.users u
SET disk_size = COALESCE((
        SELECT SUM(x.disk_size) FROM (
            SELECT disk_size FROM starexec.solvers    WHERE user_id = u.id AND deleted = false
            UNION ALL
            SELECT disk_size FROM starexec.benchmarks WHERE user_id = u.id AND deleted = false
            UNION ALL
            SELECT disk_size FROM starexec.jobs       WHERE user_id = u.id AND deleted = false
        ) x
    ), 0);

SELECT count(*)                                        AS users_examined,
       count(*) FILTER (WHERE b.disk_size <> a.disk_size) AS users_corrected,
       count(*) FILTER (WHERE b.disk_size < 0)          AS users_were_negative,
       COALESCE(sum(b.disk_size - a.disk_size) FILTER (WHERE b.disk_size > a.disk_size), 0) AS bytes_removed,
       COALESCE(sum(a.disk_size - b.disk_size) FILTER (WHERE b.disk_size < a.disk_size), 0) AS bytes_added
FROM _before_users b
JOIN starexec.users a ON a.id = b.id;

\echo ''
\echo '-- Largest 20 user corrections'
SELECT b.id AS user_id,
       b.disk_size AS before,
       a.disk_size AS after,
       a.disk_size - b.disk_size AS change
FROM _before_users b
JOIN starexec.users a ON a.id = b.id
WHERE b.disk_size <> a.disk_size
ORDER BY abs(a.disk_size - b.disk_size) DESC
LIMIT 20;

\echo ''
\echo '=============================================================================='
\echo 'POSTCONDITION -- could CHECK (disk_size >= 0) be added after this?'
\echo '=============================================================================='

SELECT 'users' AS table_name, count(*) FILTER (WHERE disk_size < 0) AS rows_violating FROM starexec.users
UNION ALL
SELECT 'jobs', count(*) FILTER (WHERE disk_size < 0) FROM starexec.jobs WHERE deleted = false;

\echo ''
\echo 'A nonzero count above means a total is still negative after recomputation.'
\echo 'That is not drift -- it means an underlying row carries a negative disk_size,'
\echo 'which recomputation faithfully reproduces. Investigate those rows directly'
\echo 'rather than clamping the total, and do not add the constraint until they are'
\echo 'resolved.'

\if :{?apply}
    \echo ''
    \echo '>>> apply was set: COMMITTING'
    COMMIT;
\else
    \echo ''
    \echo '>>> DRY RUN: rolling back. Nothing was changed.'
    \echo '>>> Re-run with  -v apply=1  to keep these corrections.'
    ROLLBACK;
\endif
