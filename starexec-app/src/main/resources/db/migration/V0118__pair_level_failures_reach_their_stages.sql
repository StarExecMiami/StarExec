-- V0118: give pairs that failed outside any stage their real status on their stages (#324).
--
-- WHY THIS EXISTS
--
-- A pair that fails before its first stage runs (a missing benchmark dependency, a
-- workspace or sandbox the job script could not set up) reports the failure with stage
-- number 0. Until this release every backend recorded that by giving the pair its error and
-- marking every stage STATUS_NOT_REACHED (23).
--
-- Every pair listing, the complete/incomplete/failed filters and the solver statistics read
-- the stage, not the pair. Such a pair was therefore shown as "stage not reached (23)",
-- counted as INCOMPLETE rather than failed, and its real error appeared nowhere in the
-- interface. The backends now give the unfinished stages the pair's own status, as the
-- legacy job script always did (sendStatusToLaterStages "$ERROR_RUNSCRIPT" 0); this
-- migration repairs the rows written the old way.
--
-- WHAT IT TOUCHES, AND WHY ONLY THAT
--
-- Only pairs whose status is a failure (the 'failed' bucket of the listings: 8-13, 18,
-- 24-26) and whose stages are ALL NOT_REACHED. No other path produces that shape: a stage
-- that fails records its own status on itself, so a stage-level failure always leaves at
-- least one stage carrying an error. A pair that failed between two stages (some stages
-- finished, the rest NOT_REACHED) is deliberately left alone, because from the rows alone
-- it cannot be told apart from a stage-level failure whose failing stage was recorded
-- differently, and a repair that guesses is worse than one that is incomplete.
--
-- Idempotent: once repaired, a pair has no NOT_REACHED stage and no longer matches. On a
-- fresh install it updates nothing.

UPDATE starexec.jobpair_stage_data AS stage
SET status_code = pair.status_code
FROM starexec.job_pairs AS pair
WHERE stage.jobpair_id = pair.id
  AND stage.status_code = 23
  AND (pair.status_code BETWEEN 8 AND 13 OR pair.status_code = 18 OR pair.status_code BETWEEN 24 AND 26)
  AND NOT EXISTS (
      SELECT 1
      FROM starexec.jobpair_stage_data AS other
      WHERE other.jobpair_id = pair.id
        AND other.status_code IS DISTINCT FROM 23
  );
