-- V0116: make "a terminal job pair always carries an end_time" a real invariant,
-- and stop a rerun from inheriting the previous attempt's timestamps.
--
-- WHY THIS EXISTS
--
-- starexec.GetJobPairIdsWithStatusNotRerunAfterDate -- the query behind
-- RERUN_FAILED_PAIRS, the only automatic retry path in the system -- selects on
--
--     status_code = _status AND (end_time >= _cutoff OR end_time < '1970-01-01')
--
-- Under SQL three-valued logic a NULL end_time satisfies neither disjunct, so a pair
-- that reaches ERROR_RUNSCRIPT with end_time NULL is invisible to it. It is equally
-- invisible to startup reconciliation, which queries only STATUS_ENQUEUED and
-- STATUS_RUNNING. Such a pair is stranded permanently, with no automatic or manual
-- path back. job_pairs.end_time is "TIMESTAMP NULL DEFAULT NULL" (V0001:361), so this
-- is the natural state of any pair that never completed normally.
--
-- Patching individual routines proved insufficient. Terminal statuses are written by at
-- least four distinct paths -- UpdatePairStatusPrecise, UpdatePairStatus (the legacy
-- procedure the SGE/OAR jobscript and RunscriptError still call), UpdateJobPairStatus
-- (reached from JobPairs.killPair), and KillJob -- and nothing stopped a fifth from
-- being added. Two of them were fixed by hand and the invariant was still only
-- "usually true". Enforcing it once, in the database, makes it total: every writer,
-- present and future, including a direct UPDATE.

-- ---------------------------------------------------------------------------
-- 1. terminal => end_time, enforced for every writer
-- ---------------------------------------------------------------------------

-- IsTerminalPairStatus lives in R__procedures_and_views.sql, and Flyway runs repeatable
-- migrations AFTER every versioned one -- so on a fresh install it does not exist yet at
-- this point. Asserted here first, idempotently and with the same body, so this migration
-- does not depend on file ordering it cannot control. R__ re-asserts it immediately after.
CREATE OR REPLACE FUNCTION starexec.IsTerminalPairStatus(_status INT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN ((_status > 6 AND _status < 19) OR _status IN (21, 23, 24, 25, 26));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- The trigger itself is defined in R__procedures_and_views.sql, immediately after
-- IsTerminalPairStatus -- NOT here. It cannot live in a versioned migration: that file
-- opens with "DROP FUNCTION IF EXISTS starexec.IsTerminalPairStatus(INT) CASCADE", the
-- trigger's WHEN clause depends on that function, and Flyway runs repeatable migrations
-- after every versioned one. A trigger created here is therefore dropped by CASCADE the
-- first time R__ runs and never comes back. Verified against a real database: present
-- after V0001..V0116, absent after R__.
--
-- Defining it beside the function it depends on means the recreate always follows the
-- drop, for every future run of that file.

-- Deliberately NOT backfilling existing rows. Any pair already sitting at
-- ERROR_RUNSCRIPT with a NULL end_time would become rerun-eligible the moment it gained
-- one, and on a large instance that means a mass rerun on deploy. Those rows are
-- reported by the audit query at the end of this file; repairing them is an operator
-- decision, not a migration side effect.

-- ---------------------------------------------------------------------------
-- 2. a rerun must not inherit the previous attempt's timestamps
-- ---------------------------------------------------------------------------
--
-- RerunJobPairsBatch reset status_code to PENDING_SUBMIT but left start_time and
-- end_time untouched, so a pair awaiting a new attempt still carried the previous
-- attempt's end_time. That is wrong on its own terms -- the field means "when this
-- pair's execution finished", and it has not finished -- and it also made the new
-- trigger's IS NULL guard a no-op for rerun pairs, freezing the stale value in place.
--
-- Body is otherwise identical to V0106; only step 5 changes.

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

    -- 5. Reset pair status to PENDING_SUBMIT (1) and clear the previous attempt's
    --    execution timestamps. A pair that has not run has neither a start nor an end.
    UPDATE starexec.job_pairs
    SET status_code = 1,
        start_time  = NULL,
        end_time    = NULL
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

-- ---------------------------------------------------------------------------
-- 3. read-only audit of rows that predate this migration
-- ---------------------------------------------------------------------------
--
-- Reports, without changing anything, how many pairs are already stranded in the state
-- described at the top of this file. Matches the read-only-audit convention used by the
-- disk-quota and limit-misclassification migrations.

DO $$
DECLARE
    _stranded INT;
BEGIN
    SELECT COUNT(*) INTO _stranded
    FROM starexec.job_pairs
    WHERE end_time IS NULL
      AND starexec.IsTerminalPairStatus(status_code::INT);

    IF _stranded > 0 THEN
        RAISE WARNING
            'V0116 audit: % job pair(s) are terminal with a NULL end_time and predate this migration. Those at status 11 (ERROR_RUNSCRIPT) are invisible to RERUN_FAILED_PAIRS. Repair is an operator decision -- setting end_time makes them rerun-eligible immediately.',
            _stranded;
    ELSE
        RAISE NOTICE 'V0116 audit: no pre-existing terminal pairs with a NULL end_time.';
    END IF;
END $$;
