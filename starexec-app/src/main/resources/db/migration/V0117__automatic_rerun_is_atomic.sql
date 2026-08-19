-- V0117: make the automatic rerun's reset and its allowance acknowledgment one
-- transaction, and stop a manual rerun from deciding on a stale pre-lock read.
--
-- WHY THIS EXISTS
--
-- The RERUN_FAILED_PAIRS sweep is the only automatic retry path in StarExec. It ran as
-- two independent statements (PeriodicTasks:357-358):
--
--     Jobs.rerunPair(pairId);            -- reset the pair for another execution
--     PairsRerun.markPairAsRerun(pairId) -- record that the allowance was consumed
--
-- Neither checked the other, and both can fail on their own. That leaves two committed
-- states that must not exist:
--
--   * tombstone written, reset failed -> the pair is permanently invisible to
--     GetJobPairIdsWithStatusNotRerunAfterDate, with no automatic or manual path back;
--   * reset succeeded, tombstone failed -> the allowance is never recorded, so when the
--     new attempt fails with ERROR_RUNSCRIPT it is selected again, and again. Nothing
--     bounds this: job_pair_attempts.current_attempt_no is incremented but never compared
--     against a maximum anywhere in the codebase.
--
-- THE MANUAL / AUTOMATIC BOUNDARY
--
-- pairs_rerun does not mean "this pair was rerun". markPairAsRerun has exactly one
-- production caller -- the automatic sweep -- so it means "the automatic rerun allowance
-- was consumed". RerunJobPairsBatch, by contrast, is reached from user- and
-- admin-initiated paths (RESTServices:1816, :1838, :1859, :2091) through the shared
-- Jobs.rerunPair. Folding the acknowledgment into that shared procedure would make a
-- human's manual rerun silently spend the pair's automatic retry.
--
-- So the two operations get two names. The reset itself is shared -- RerunJobPairsBatchCore
-- -- and is not duplicated; only the surrounding contract differs.
--
-- CONCURRENCY
--
-- The periodic sweep and an HTTP request can reach the same pair at the same time, and
-- may run in different processes later, so JVM-level synchronisation cannot be the
-- authority. Both entry points take a row lock on job_pairs and revalidate AFTER
-- acquiring it. Whichever loses observes the state the winner committed and does nothing:
--
--   automatic wins -> manual re-reads PENDING_SUBMIT, resets nothing, returns 0
--   manual wins    -> automatic re-reads a status that is not the expected one,
--                     returns STALE_OR_NOT_ELIGIBLE, and writes NO acknowledgment,
--                     so the automatic allowance survives a manual rerun
--
-- Revalidation was previously absent altogether: RerunJobPairsBatch reset whatever ids it
-- was handed, and the only precondition anywhere was a Java-side filter over a JobPair
-- object fetched seconds earlier (Jobs.java:4894). setPairsToPending applies none at all.

-- ---------------------------------------------------------------------------
-- 1. the shared reset, extracted verbatim from V0116
-- ---------------------------------------------------------------------------
--
-- Internal. Callers must already hold the row locks and have revalidated eligibility;
-- this function deliberately makes no decisions of its own.

CREATE OR REPLACE FUNCTION starexec.RerunJobPairsBatchCore(_pairIds INT[])
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
-- 2. manual / admin rerun -- reset only, but now locked and revalidated
-- ---------------------------------------------------------------------------
--
-- Returns the number of pairs actually reset so a caller can tell "nothing to do" from
-- "it worked". The signature changes from VOID, which CREATE OR REPLACE cannot do, so the
-- old form is dropped first -- the same shape V0025 and V0103 already use. The Java call
-- text, "SELECT starexec.RerunJobPairsBatch(?::int[])", is unchanged.
--
-- No BEGIN..EXCEPTION block: V0103 removed exactly that construct from this function to
-- eliminate subtransaction contention and delegated error handling to the Java layer.

DROP FUNCTION IF EXISTS starexec.RerunJobPairsBatch(INT[]) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RerunJobPairsBatch(_pairIds INT[])
RETURNS INT AS $$
DECLARE
    _eligible INT[];
BEGIN
    IF _pairIds IS NULL OR cardinality(_pairIds) = 0 THEN
        RETURN 0;
    END IF;

    -- Lock every target row, lowest id first. A deterministic order is what stops two
    -- overlapping batch calls from deadlocking on an overlapping id set.
    PERFORM 1
       FROM starexec.job_pairs jp
      WHERE jp.id = ANY(_pairIds)
      ORDER BY jp.id
        FOR UPDATE;

    -- Re-read INSIDE the lock. This is the authoritative eligibility decision; the Java
    -- filter on the caller's side is only an optimisation over a possibly stale snapshot.
    -- 1 = STATUS_PENDING_SUBMIT: already reset, so there is nothing to do.
    SELECT COALESCE(array_agg(jp.id ORDER BY jp.id), ARRAY[]::INT[])
      INTO _eligible
      FROM starexec.job_pairs jp
     WHERE jp.id = ANY(_pairIds)
       AND jp.status_code <> 1;

    IF cardinality(_eligible) = 0 THEN
        RETURN 0;
    END IF;

    PERFORM starexec.RerunJobPairsBatchCore(_eligible);

    -- Deliberately no pairs_rerun write. A manual rerun must not consume the pair's
    -- automatic-rerun allowance.
    RETURN cardinality(_eligible);
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 3. automatic rerun -- reset AND allowance, indivisibly
-- ---------------------------------------------------------------------------
--
-- One statement, therefore one transaction: Common.getConnection() leaves autocommit true
-- (Common.java disables it only inside beginTransaction), so both effects commit together
-- or neither does. The Java caller must not split this into two statements.
--
-- The result is returned rather than inferred, so a caller that loses its connection after
-- the commit can re-invoke and be told ALREADY_CONSUMED instead of guessing from unrelated
-- state.
--
-- Check order is load-bearing. The tombstone is tested before the status: when a manual
-- rerun won the race there is no tombstone, so the call correctly falls through to the
-- status test and reports STALE_OR_NOT_ELIGIBLE -- leaving the automatic allowance intact.

CREATE OR REPLACE FUNCTION starexec.RerunJobPairAutomatic(_pairId INT, _expectedStatus INT)
RETURNS TEXT AS $$
DECLARE
    _current SMALLINT;
BEGIN
    SELECT jp.status_code INTO _current
      FROM starexec.job_pairs jp
     WHERE jp.id = _pairId
       FOR UPDATE;

    IF NOT FOUND THEN
        -- Deleted between selection and now.
        RETURN 'STALE_OR_NOT_ELIGIBLE';
    END IF;

    IF EXISTS (SELECT 1 FROM starexec.pairs_rerun pr WHERE pr.pair_id = _pairId) THEN
        RETURN 'ALREADY_CONSUMED';
    END IF;

    IF _current::INT <> _expectedStatus THEN
        RETURN 'STALE_OR_NOT_ELIGIBLE';
    END IF;

    PERFORM starexec.RerunJobPairsBatchCore(ARRAY[_pairId]);

    -- Plain INSERT, not ON CONFLICT DO NOTHING: the EXISTS check above already ruled the
    -- row out under the lock, so a conflict here would mean the invariant is broken and
    -- must abort the whole transaction rather than be swallowed.
    INSERT INTO starexec.pairs_rerun (pair_id) VALUES (_pairId);

    RETURN 'UPDATED';
END;
$$ LANGUAGE plpgsql;
