-- Migration: Convert MySQL stored functions to PostgreSQL (plpgsql)
-- File: R__functions_postgres.sql
-- Note: PostgreSQL folds unquoted identifiers to lower-case. Calls like starexec.GetCompletePairs(...) (unquoted)
-- will resolve to starexec.getcompletepairs(...).

-- GetCompletePairs
DROP FUNCTION IF EXISTS starexec.getcompletepairs(integer);
CREATE OR REPLACE FUNCTION starexec.getcompletepairs(_jobid integer)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	completepairs bigint;
BEGIN
	SELECT COUNT(*) INTO completepairs
	FROM job_pairs
	WHERE job_id = _jobid
	  AND status_code = 7;
	RETURN completepairs;
END;
$$;

-- GetErrorPairs
DROP FUNCTION IF EXISTS starexec.geterrorpairs(integer);
CREATE OR REPLACE FUNCTION starexec.geterrorpairs(_jobid integer)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	errorpairs bigint;
BEGIN
	SELECT COUNT(*) INTO errorpairs
	FROM job_pairs
	WHERE job_id = _jobid
	  AND (status_code IN (8, 9, 10, 11, 12, 13, 18, 21, 23, 24, 25, 26));
	RETURN errorpairs;
END;
$$;

-- GetJobStatusDetail (keeps same behavior as GetErrorPairs)
DROP FUNCTION IF EXISTS starexec.getjobstatusdetail(integer);
CREATE OR REPLACE FUNCTION starexec.getjobstatusdetail(_jobid integer)
RETURNS integer
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	statusdetail integer;
BEGIN
	SELECT COUNT(*) INTO statusdetail
	FROM job_pairs
	WHERE job_id = _jobid
	  AND (status_code IN (8, 9, 10, 11, 12, 13, 18, 21, 23, 24, 25, 26));
	RETURN statusdetail;
END;
$$;

-- GetJobStatus
DROP FUNCTION IF EXISTS starexec.getjobstatus(integer);
CREATE OR REPLACE FUNCTION starexec.getjobstatus(_jobid integer)
RETURNS varchar(10)
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
BEGIN
	RETURN CASE
		WHEN EXISTS (SELECT 1 FROM job_pairs WHERE job_id = _jobid AND status_code IN (1, 2, 4, 19, 20, 22))
		THEN 'incomplete'
		ELSE 'complete'
	END;
END;
$$;

-- GetPendingPairs
DROP FUNCTION IF EXISTS starexec.getpendingpairs(integer);
CREATE OR REPLACE FUNCTION starexec.getpendingpairs(_jobid integer)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	pendingpairs bigint;
BEGIN
	SELECT COUNT(*) INTO pendingpairs
	FROM job_pairs
	WHERE job_id = _jobid
	  AND status_code IN (1, 2, 4, 19, 20, 22);
	RETURN pendingpairs;
END;
$$;

-- IsPublic (space visibility)
DROP FUNCTION IF EXISTS starexec.ispublic(integer);
CREATE OR REPLACE FUNCTION starexec.ispublic(_spaceid integer)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	spacepublic boolean;
BEGIN
	SELECT public_access INTO spacepublic
	FROM spaces
	WHERE id = _spaceid;
	RETURN spacepublic;
END;
$$;