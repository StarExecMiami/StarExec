-- Description: This file contains all stored functions for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!
-- Author: Todd Elvers

-- SQL Functions Migration
-- Uses CREATE so they are created if missing (or refreshed if changed)
-- Requires MySQL 8.0.29+ for CREATE OR REPLACE FUNCTION

DELIMITER //

-- Gets the number of completed job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetCompletePairs //
CREATE FUNCTION starexec.GetCompletePairs(_jobId INT)
RETURNS INT
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
	DECLARE completePairs INT;
	SELECT COUNT(*) INTO completePairs
	FROM job_pairs
	WHERE job_id=_jobId
	AND status_code=7;
	RETURN completePairs;
END //

DROP FUNCTION IF EXISTS starexec.GetErrorPairs //
CREATE FUNCTION starexec.GetErrorPairs(_jobId INT)
RETURNS INT
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
	DECLARE errorPairs INT;
	SELECT COUNT(*) INTO errorPairs
	FROM job_pairs
	WHERE job_id=_jobId
	AND (status_code BETWEEN 8 AND 17 OR status_code=0 OR status_code BETWEEN 24 AND 26);
	RETURN errorPairs;
END //

DROP FUNCTION IF EXISTS starexec.GetJobStatusDetail //
CREATE FUNCTION starexec.GetJobStatusDetail(_jobId INT)
RETURNS INT
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
	DECLARE statusDetail INT;
	SELECT COUNT(*) INTO statusDetail
	FROM job_pairs
	WHERE job_id=_jobId
		AND (status_code BETWEEN 8 AND 17 OR status_code=0 OR status_code BETWEEN 24 AND 26);
	RETURN statusDetail;
END //

DROP FUNCTION IF EXISTS starexec.GetJobStatus //
CREATE FUNCTION starexec.GetJobStatus(_jobId INT)
RETURNS VARCHAR(10)
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
RETURN (
	SELECT IF (
		EXISTS (SELECT 1 FROM job_pairs WHERE job_id=_jobId AND status_code BETWEEN 1 AND 6),
		'incomplete',
		'complete'
	)
); //

-- Gets the number of pending job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetPendingPairs //
CREATE FUNCTION starexec.GetPendingPairs(_jobId INT)
RETURNS INT
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
	DECLARE pendingPairs INT;
	SELECT COUNT(*) INTO pendingPairs
	FROM job_pairs
	WHERE job_id=_jobId
	AND (status_code BETWEEN 1 AND 6);
	RETURN pendingPairs;
END //

DELIMITER ;

-- Additional functions appended by migration automation (IsPublic for space visibility)
DELIMITER //
DROP FUNCTION IF EXISTS starexec.IsPublic //
CREATE FUNCTION starexec.IsPublic(_spaceId INT)
RETURNS BOOLEAN
DETERMINISTIC
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
	DECLARE spacePublic BOOLEAN;
	SELECT public_access INTO spacePublic
	FROM spaces
	WHERE id=_spaceId;
	RETURN spacePublic;
END //
DELIMITER ;