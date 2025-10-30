-- Migration: Convert MySQL stored functions to PostgreSQL (plpgsql)
-- File: R__functions_postgres.sql
-- Note: PostgreSQL folds unquoted identifiers to lower-case. Calls like starexec.GetCompletePairs(...) (unquoted)
-- will resolve to starexec.getcompletepairs(...).

-- GetCompletePairs
DROP FUNCTION IF EXISTS starexec.getcompletepairs(integer);
CREATE OR REPLACE FUNCTION starexec.getcompletepairs(_jobid integer)
RETURNS integer
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	completepairs integer;
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
RETURNS integer
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	errorpairs integer;
BEGIN
	SELECT COUNT(*) INTO errorpairs
	FROM job_pairs
	WHERE job_id = _jobid
	  AND (status_code BETWEEN 8 AND 17 OR status_code = 0 OR status_code BETWEEN 24 AND 26);
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
	  AND (status_code BETWEEN 8 AND 17 OR status_code = 0 OR status_code BETWEEN 24 AND 26);
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
		WHEN EXISTS (SELECT 1 FROM job_pairs WHERE job_id = _jobid AND status_code BETWEEN 1 AND 6)
		THEN 'incomplete'
		ELSE 'complete'
	END;
END;
$$;

-- GetPendingPairs
DROP FUNCTION IF EXISTS starexec.getpendingpairs(integer);
CREATE OR REPLACE FUNCTION starexec.getpendingpairs(_jobid integer)
RETURNS integer
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
AS $$
DECLARE
	pendingpairs integer;
BEGIN
	SELECT COUNT(*) INTO pendingpairs
	FROM job_pairs
	WHERE job_id = _jobid
	  AND status_code BETWEEN 1 AND 6;
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