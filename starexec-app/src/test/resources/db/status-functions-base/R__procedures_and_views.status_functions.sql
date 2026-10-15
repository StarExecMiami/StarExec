-- TEST FIXTURE. Verbatim copy of the six status functions as they were defined in
-- R__procedures_and_views.sql at b910c79cf (lines 9875-10004), i.e. the definitions that won on a
-- fresh install. JobStatusFunctionsSqlTest replays them to rebuild the pre-fix database state
-- before applying the current migrations on top. Do not edit.

-- ================================================================================
-- FUNCTIONS from StarFunctions.sql
-- ================================================================================

-- Gets the number of completed job pairs for a given job id
-- Counts only pairs that have genuinely completed execution, excluding
-- active/pending/paused pairs as well as pairs whose only status is
-- a pure infrastructure error.
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetCompletePairs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCompletePairs(_jobId INT)
RETURNS BIGINT AS $$
DECLARE
    completePairs BIGINT;
BEGIN
    SELECT COUNT(*) INTO completePairs
    FROM starexec.job_pairs
    WHERE job_id = _jobId
    AND status_code IN (7, 14, 15, 16, 17, 25, 26);

    RETURN completePairs;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of errored job pairs for a given job id
-- Counts only infrastructure/application errors, excluding resource-limit
-- completions (timeout/memout/file-write) which are counted as completed.
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetErrorPairs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetErrorPairs(_jobId INT)
RETURNS BIGINT AS $$
DECLARE
    errorPairs BIGINT;
BEGIN
    SELECT COUNT(*) INTO errorPairs
    FROM starexec.job_pairs
    WHERE job_id = _jobId
    AND status_code IN (8, 9, 10, 11, 12, 13, 18, 24, 25, 26);

    RETURN errorPairs;
END;
$$ LANGUAGE plpgsql;

-- Returns "complete" if the job represented by the given id had no pending job pairs,
-- and returns "incomplete" otherwise
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetJobStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobStatus(_jobId INT)
RETURNS TEXT AS $$
DECLARE
    status TEXT;
BEGIN
    SELECT CASE WHEN _jobId IN (
        SELECT job_id
        FROM starexec.job_pairs
        WHERE status_code IN (1, 2, 4, 19, 20, 22)
    ) THEN 'incomplete' ELSE 'complete' END
    INTO status;

    RETURN status;
END;
$$ LANGUAGE plpgsql;

-- Returns human readable description of this job's status
-- This Function looks intimidating, but it is just a big IF ELSE IF chain
DROP FUNCTION IF EXISTS starexec.GetJobStatusDetail CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobStatusDetail(_jobId INT)
RETURNS TEXT AS $$
DECLARE
    status TEXT;
BEGIN
    SELECT CASE
        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE deleted) THEN 'DELETED'
        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE killed) THEN 'KILLED'
        WHEN _jobId IN (SELECT id FROM starexec.jobs WHERE paused) THEN 'PAUSED'
        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code = 22) THEN 'PROCESSING'
        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code = 19) THEN 'PROCESSING_RESULTS'
        WHEN _jobId IN (SELECT job_id FROM starexec.job_pairs WHERE status_code BETWEEN 1 AND 6) THEN
            CASE
                WHEN EXISTS (SELECT 1 FROM starexec.system_flags WHERE paused = TRUE)
                     AND _jobId NOT IN (
                         SELECT j.id
                         FROM starexec.jobs j
                         JOIN users u ON j.user_id = u.id
                         JOIN user_roles ur ON ur.email = u.email
                         WHERE ur.role IN ('admin', 'developer')
                     )
                THEN 'GLOBAL_PAUSE'
                ELSE 'RUNNING'
            END
        ELSE 'COMPLETE'
    END INTO status;

    RETURN status;
END;
$$ LANGUAGE plpgsql;


-- Gets the number of pending job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetPendingPairs(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPendingPairs(_jobId INT)
RETURNS BIGINT AS $$
DECLARE
    pendingPairs BIGINT;
BEGIN
    SELECT COUNT(*) INTO pendingPairs
    FROM starexec.job_pairs
    WHERE job_id = _jobId
    AND status_code IN (1, 2, 4, 19, 20, 22);

    RETURN pendingPairs;
END;
$$ LANGUAGE plpgsql;

-- Tells you whether a space is public or not
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsPublic(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsPublic(_spaceId INT)
RETURNS BOOLEAN AS $$
DECLARE
    isPublic BOOLEAN;
BEGIN
    SELECT public_access INTO isPublic
    FROM starexec.spaces
    WHERE id = _spaceId;
    RETURN isPublic;
END;
$$ LANGUAGE plpgsql;

