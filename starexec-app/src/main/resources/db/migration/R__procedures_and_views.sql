-- Repeatable migration for procedures, functions, and views
-- This file consolidates ALL procedures from sql/procedures/*.sql into a single Flyway migration
-- All procedures are idempotent and can be re-executed safely
-- Migration includes: Analytics, AnonymousLinks, Benchmarks, Cluster, Communities, ErrorLogs,
-- JobPairs, Jobs, Misc, Notifications, PairsRerun, Permissions, Pipelines, Processors,
-- Queues, Reports, Requests, RunscriptErrors, Settings, Solvers, Spaces, UploadStatus,
-- Users, Websites, and pagination procedures

-- ================================================================================
-- Analytics PROCEDURES
-- ================================================================================

-- Retrieve the `id` of a given Event
DROP FUNCTION IF EXISTS starexec.GetEventId(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetEventId(_name CHAR(32))
RETURNS TABLE(event_id INT) AS $$
	BEGIN
		RETURN QUERY SELECT ae.event_id FROM starexec.analytics_events ae WHERE ae.name = _name;
	END;
$$ LANGUAGE plpgsql;

-- If there is not yet a record of this event happening today
--   create a record and set its count to `1`
-- otherwise
--   increment the count of the existing record
DROP ROUTINE IF EXISTS starexec.RecordEvent(INT, DATE, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.RecordEvent(
		_event_id INT,
		_date_recorded DATE,
		_count INT
	)
AS $$
	BEGIN
		INSERT INTO analytics_historical (event_id, date_recorded, count)
			VALUES (_event_id, _date_recorded, _count)
		ON CONFLICT (event_id, date_recorded)
			DO UPDATE SET
				count = analytics_historical.count + _count;
	END;
$$ LANGUAGE plpgsql;

-- Record an instance of
--   a particular user triggering
--   a particular event on
--   a particular day
-- If we have already recorded this user/event/day, we can just ignore the
-- DUPLICATE KEY warning
DROP ROUTINE IF EXISTS starexec.RecordEventUser(INT, DATE, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.RecordEventUser(
		_event_id INT,
		_date_recorded DATE,
		_user_id INT
	)
AS $$
	BEGIN
		INSERT INTO analytics_users (event_id, date_recorded, user_id)
			VALUES (_event_id, _date_recorded, _user_id)
		ON CONFLICT DO NOTHING;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAnalyticsForDateRange(DATE, DATE) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAnalyticsForDateRange(
		_start DATE,
		_end DATE)
RETURNS TABLE(event_name TEXT, event_count BIGINT, user_count BIGINT) AS $$
	BEGIN
		RETURN QUERY
		SELECT
			ae.name::TEXT as event_name,
			SUM(ah.count) as event_count,
			COUNT(distinct au.user_id) as user_count
		FROM starexec.analytics_historical ah
		LEFT JOIN analytics_users au ON ah.event_id = au.event_id
			AND ah.date_recorded = au.date_recorded
		LEFT JOIN analytics_events ae ON ah.event_id = ae.event_id
		WHERE ah.date_recorded >= _start AND ah.date_recorded <= _end
		GROUP BY ah.event_id, ae.name;
	END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- AnonymousLinks PROCEDURES
-- ================================================================================

DROP FUNCTION IF EXISTS starexec.AddAnonymousPrimitiveName(VARCHAR, INT, VARCHAR, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddAnonymousPrimitiveName(
		_anonymousName VARCHAR(36),
		_primitiveId INT,
		_primitiveType VARCHAR(10),
		_jobId INT)
RETURNS VOID AS $$
	BEGIN
		INSERT INTO anonymous_primitive_names (anonymous_name, primitive_id, primitive_type, job_id)
			VALUES (_anonymousName, _primitiveId, _primitiveType, _jobId);
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.AddAnonymousLink(VARCHAR, VARCHAR, INT, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddAnonymousLink(_uniqueId VARCHAR(36), _primitiveType VARCHAR(10), _primitiveId INT,
		_primitivesToAnonymize VARCHAR(15))
RETURNS VOID AS $$
	BEGIN
		INSERT INTO anonymous_links (unique_id, primitive_type, primitive_id, primitives_to_anonymize, date_created)
			VALUES (_uniqueId, _primitiveType, _primitiveId, _primitivesToAnonymize, CURRENT_DATE);
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAnonymousNamesForJob(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAnonymousNamesForJob(_jobId INT)
RETURNS TABLE(anonymousName VARCHAR, primitiveId INT, primitiveType VARCHAR, jobId INT) AS $$
	BEGIN
		RETURN QUERY SELECT apn.anonymous_name, apn.primitive_id, apn.primitive_type, apn.job_id
			FROM starexec.anonymous_primitive_names apn WHERE apn.job_id = _jobId;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAnonymousSolverNamesAndIds(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAnonymousSolverNamesAndIds(_jobId INT)
RETURNS TABLE(anonymousName VARCHAR, primitiveId INT) AS $$
	BEGIN
		RETURN QUERY SELECT apn.anonymous_name, apn.primitive_id
			FROM starexec.anonymous_primitive_names apn
			WHERE apn.job_id = _jobId AND apn.primitive_type = 'solver';
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAnonymousLink(VARCHAR, INT, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAnonymousLink(_primitiveType VARCHAR(10), _primitiveId INT,
		_primitivesToAnonymize VARCHAR(15))
RETURNS TABLE(unique_id VARCHAR) AS $$
	BEGIN
		RETURN QUERY SELECT al.unique_id FROM starexec.anonymous_links al
			WHERE al.primitive_type = _primitiveType AND al.primitive_id = _primitiveId
			AND al.primitives_to_anonymize = _primitivesToAnonymize;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetIdOfPrimitiveAssociatedWithLink(VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetIdOfPrimitiveAssociatedWithLink(_uniqueId VARCHAR(36), _primitiveType VARCHAR(10))
RETURNS TABLE(primitive_id INT) AS $$
	BEGIN
		RETURN QUERY SELECT al.primitive_id FROM starexec.anonymous_links al
			WHERE al.unique_id = _uniqueId AND al.primitive_type = _primitiveType;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetPrimitivesToAnonymize(VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPrimitivesToAnonymize(_uniqueId VARCHAR(36), _primitiveType VARCHAR(10))
RETURNS TABLE(primitives_to_anonymize VARCHAR) AS $$
	BEGIN
		RETURN QUERY SELECT al.primitives_to_anonymize FROM starexec.anonymous_links al
			WHERE al.unique_id = _uniqueId AND al.primitive_type = _primitiveType;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.DeleteOldLinks(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteOldLinks(_ageThresholdInDays INT)
RETURNS VOID AS $$
	BEGIN
		/* Delete all the anonymous primitive names that correspond to anonymous links for jobs that are being deleted. */
		DELETE FROM starexec.anonymous_primitive_names
		WHERE job_id IN (
			SELECT primitive_id FROM starexec.anonymous_links
			WHERE CURRENT_DATE - date_created >= _ageThresholdInDays
			AND primitive_type = 'job'
		);

		DELETE FROM starexec.anonymous_links
		WHERE CURRENT_DATE - date_created >= _ageThresholdInDays;
	END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.DeleteAnonymousLink(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteAnonymousLink(_uniqueId VARCHAR(36))
RETURNS VOID AS $$
	BEGIN
		DELETE FROM starexec.anonymous_primitive_names
		WHERE job_id IN (
			SELECT primitive_id FROM starexec.anonymous_links
			WHERE unique_id = _uniqueId AND primitive_type = 'job'
		);
		DELETE FROM starexec.anonymous_links WHERE unique_id = _uniqueId;
	END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Benchmarks PROCEDURES
-- ================================================================================

-- Description: This file contains all benchmark stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a benchmark into the system and associates it with a space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddBenchmark(VARCHAR, TEXT, BOOLEAN, INT, INT, BIGINT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddBenchmark(_name VARCHAR(256), _path TEXT, _downloadable BOOLEAN, _userId INT, _typeId INT, _diskSize BIGINT, _description TEXT)
RETURNS INT AS $$
DECLARE
	_benchId INT;
BEGIN
	UPDATE users SET disk_size = disk_size + _diskSize WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
	INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, downloadable, disk_size, description)
	VALUES (_userId, _name, _typeId, CURRENT_TIMESTAMP, _path, _downloadable, _diskSize, _description)
	RETURNING id INTO _benchId;

	RETURN _benchId;
END;
$$ LANGUAGE plpgsql;

DROP ROUTINE IF EXISTS starexec.AddAndAssociateBenchmark(VARCHAR, TEXT, BOOLEAN, INT, INT, BIGINT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.AddAndAssociateBenchmark(_name VARCHAR(256), _path TEXT, _downloadable BOOLEAN, _userId INT, _typeId INT, _diskSize BIGINT, _spaceId INT, INOUT _benchId INT DEFAULT NULL)
AS $$
BEGIN
	UPDATE users SET disk_size = disk_size + _diskSize WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
	INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, downloadable, disk_size)
	VALUES (_userId, _name, _typeId, CURRENT_TIMESTAMP, _path, _downloadable, _diskSize)
	RETURNING id INTO _benchId;

	INSERT INTO bench_assoc (space_id, bench_id) VALUES (_spaceId, _benchId)
	ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Set-based sibling of AddAndAssociateBenchmark, for the asynchronous upload
-- processor (BoundedUploadProcessor), which inserts in batches of 50.
-- It encodes the same three invariants as the single-row version -- charge the
-- user's disk quota, insert the benchmark, link it to the space -- so that the
-- batch path cannot drift away from them again. Returns the new ids paired with
-- their source path; the path is the join key because RETURNING makes no
-- ordering guarantee.
DROP FUNCTION IF EXISTS starexec.AddAndAssociateBenchmarks(TEXT[], TEXT[], BIGINT[], INT, INT, BOOLEAN, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddAndAssociateBenchmarks(
	_names TEXT[],
	_paths TEXT[],
	_diskSizes BIGINT[],
	_userId INT,
	_typeId INT,
	_downloadable BOOLEAN,
	_spaceId INT
)
RETURNS TABLE(bench_id INT, bench_path TEXT) AS $$
DECLARE
	_count INT;
	_totalDiskSize BIGINT;
BEGIN
	_count := COALESCE(array_length(_names, 1), 0);
	IF _count = 0 THEN
		RETURN;
	END IF;

	-- The three arrays are positionally zipped below, so a length mismatch would
	-- silently drop benchmarks. Fail loudly instead.
	IF COALESCE(array_length(_paths, 1), 0) <> _count
			OR COALESCE(array_length(_diskSizes, 1), 0) <> _count THEN
		RAISE EXCEPTION USING
			ERRCODE = '22023',
			MESSAGE = format('Array length mismatch: names=%s paths=%s diskSizes=%s',
				_count, COALESCE(array_length(_paths, 1), 0), COALESCE(array_length(_diskSizes, 1), 0));
	END IF;

	SELECT COALESCE(SUM(s), 0) INTO _totalDiskSize FROM unnest(_diskSizes) AS s;

	-- One quota charge for the whole batch, equivalent to N single-row charges.
	UPDATE users SET disk_size = disk_size + _totalDiskSize WHERE id = _userId;
	IF NOT FOUND THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0002',
			MESSAGE = format('User %s not found', _userId);
	END IF;

	RETURN QUERY
	WITH input AS (
		SELECT n.ord, n.name, p.path, d.size
		FROM unnest(_names) WITH ORDINALITY AS n(name, ord)
		JOIN unnest(_paths) WITH ORDINALITY AS p(path, ord) ON p.ord = n.ord
		JOIN unnest(_diskSizes) WITH ORDINALITY AS d(size, ord) ON d.ord = n.ord
	),
	inserted AS (
		INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, downloadable, disk_size)
		SELECT _userId, i.name, _typeId, CURRENT_TIMESTAMP, i.path, _downloadable, i.size
		FROM input i
		RETURNING id, path
	),
	-- Data-modifying CTEs always execute to completion even when unreferenced, so
	-- this needs no RETURNING -- and must not have one: `bench_id` would resolve
	-- ambiguously against the OUT parameter of the same name.
	associated AS (
		INSERT INTO bench_assoc (space_id, bench_id)
		SELECT _spaceId, ins.id FROM inserted ins
		ON CONFLICT DO NOTHING
	)
	SELECT ins.id, ins.path FROM inserted ins;
END;
$$ LANGUAGE plpgsql;

-- Gets all benchmarks that are in a job (in job pairs in that job)
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetBenchmarksByJob(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarksByJob(_jobId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled
	FROM starexec.benchmarks b
	INNER JOIN job_pairs jp ON b.id = jp.bench_id
	WHERE jp.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

-- Adds a new attribute to a benchmark
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddBenchAttr(INT, VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddBenchAttr(_benchmarkId INT, _key VARCHAR(128), _val VARCHAR(128))
RETURNS VOID AS $$
BEGIN
	INSERT INTO bench_attributes VALUES (_benchmarkId, _key, _val)
	ON CONFLICT (bench_id, attr_key) DO UPDATE SET attr_value = _val;
END;
$$ LANGUAGE plpgsql;

-- Adds a new dependency for a benchmark
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.AddBenchDependency(INT, INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddBenchDependency(_primary_bench_id INT, _secondary_bench_id INT, _include_path TEXT)
RETURNS VOID AS $$
BEGIN
	INSERT INTO bench_dependency (primary_bench_id, secondary_bench_id, include_path)
	VALUES (_primary_bench_id, _secondary_bench_id, _include_path)
	ON CONFLICT (primary_bench_id, secondary_bench_id)
	DO UPDATE SET include_path = EXCLUDED.include_path;
END;
$$ LANGUAGE plpgsql;

-- Associates the given benchmark with the given space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AssociateBench(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AssociateBench(_benchId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
	INSERT INTO bench_assoc (space_id, bench_id) VALUES (_spaceId, _benchId)
	ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all attributes for a benchmark
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetBenchAttrs(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchAttrs(_benchmarkId INT)
RETURNS TABLE(bench_id INT, attr_key VARCHAR, attr_value VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT ba.bench_id, ba.attr_key, ba.attr_value
	FROM starexec.bench_attributes ba
	WHERE ba.bench_id = _benchmarkId
	ORDER BY ba.attr_key ASC;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetBenchByName(INT, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchByName(_id INT, _name VARCHAR(256))
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN) AS $$
BEGIN
	RETURN QUERY
	SELECT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled
	FROM starexec.benchmarks b
	WHERE b.deleted = false AND b.recycled = false AND b.id IN
		(SELECT ba.bench_id FROM starexec.bench_assoc ba WHERE ba.space_id = _id)
	AND b.name = _name;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all benchmark dependencies for a given primary benchmark id
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetBenchmarkDependencies(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkDependencies(_pBenchId INT)
RETURNS TABLE(primary_bench_id INT, secondary_bench_id INT, include_path TEXT) AS $$
BEGIN
	RETURN QUERY
	SELECT bd.primary_bench_id, bd.secondary_bench_id, bd.include_path
	FROM starexec.bench_dependency bd
	WHERE bd.primary_bench_id = _pBenchId;
END;
$$ LANGUAGE plpgsql;

-- Just get the id, name, and path for the dependencies of the benchmark with the given id
-- Author: Aaron Stump
DROP FUNCTION IF EXISTS starexec.GetPathsForBenchmarkDependencies(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPathsForBenchmarkDependencies(_pBenchId INT)
RETURNS TABLE(id INT, name VARCHAR, path TEXT, include_path TEXT) AS $$
BEGIN
	RETURN QUERY
	SELECT b.id, b.name, b.path, bd.include_path
	FROM starexec.benchmarks b JOIN bench_dependency bd ON b.id = bd.secondary_bench_id
	WHERE bd.primary_bench_id = _pBenchId;
END;
$$ LANGUAGE plpgsql;

-- Performs batch resolution of benchmark dependencies for all benchmarks in a space hierarchy.
-- Uses a true Recursive CTE to walk the space hierarchy segment-by-segment as defined in include_path.
-- Author: AI Assistant (Refactoring v3)
DROP FUNCTION IF EXISTS starexec.ResolveBenchmarkDependenciesBatch(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.ResolveBenchmarkDependenciesBatch(_root_space_id INT)
RETURNS TABLE(p_id INT, s_id INT, inc_path TEXT) AS $$
BEGIN
	RETURN QUERY
	WITH RECURSIVE
	-- 1. Extract all declared dependencies for benchmarks in the given space hierarchy
	raw_deps AS (
		SELECT b.id as bench_id, ba.attr_value::TEXT as include_path,
		       string_to_array(ba.attr_value, '/') as segments,
		       array_length(string_to_array(ba.attr_value, '/'), 1) as total_segments
		FROM starexec.benchmarks b
		JOIN starexec.bench_attributes ba ON b.id = ba.bench_id
		JOIN starexec.bench_assoc ba2 ON b.id = ba2.bench_id
		WHERE ba2.space_id IN (
			-- Include the root space and all its descendants via closure table
			SELECT descendant FROM starexec.closure WHERE ancestor = _root_space_id
		)
		  AND ba.attr_key LIKE 'starexec-dependency-%'
		  AND ba.attr_value IS NOT NULL AND ba.attr_value != ''
	),
	-- 2. Walk the path segment-by-segment using the set_assoc table
	path_walk AS (
		-- Base case: Starting segments for each dependency
		SELECT
			rd.bench_id, rd.include_path, rd.segments, rd.total_segments,
			1 as current_level,
			_root_space_id as current_space_id,
			CAST(NULL AS INT) as resolved_bench_id,
			FALSE as is_resolved
		FROM raw_deps rd

		UNION ALL

		-- Recursive step: descend into subspaces or find the target benchmark
		SELECT
			pw.bench_id, pw.include_path, pw.segments, pw.total_segments,
			pw.current_level + 1,
			CASE
				WHEN pw.current_level < pw.total_segments THEN s.id
				ELSE pw.current_space_id
			END,
			CASE
				WHEN pw.current_level = pw.total_segments THEN b.id
				ELSE NULL
			END,
			(pw.current_level = pw.total_segments AND b.id IS NOT NULL)
		FROM path_walk pw
		-- For intermediate directory segments, join with spaces via set_assoc
		LEFT JOIN starexec.set_assoc sa ON sa.space_id = pw.current_space_id
		LEFT JOIN starexec.spaces s ON s.id = sa.child_id
									AND s.name = pw.segments[pw.current_level]
									AND pw.current_level < pw.total_segments
		-- For the final segment, join with benchmarks table via bench_assoc
		LEFT JOIN starexec.bench_assoc ba_target ON ba_target.space_id = pw.current_space_id
									AND pw.current_level = pw.total_segments
		LEFT JOIN starexec.benchmarks b ON b.id = ba_target.bench_id
									AND b.name = pw.segments[pw.current_level]
		WHERE pw.current_level <= pw.total_segments
		  AND NOT pw.is_resolved
		  -- Keep walking only if we found the next segment (space or benchmark)
		  AND (s.id IS NOT NULL OR b.id IS NOT NULL)
	)
	SELECT bench_id, resolved_bench_id, include_path
	FROM path_walk
	WHERE is_resolved = TRUE;
END;
$$ LANGUAGE plpgsql;

-- Atomic batch insertion of resolved dependencies for a space hierarchy.
-- Uses ON CONFLICT to ensure idempotency.
-- Returns counts of successful and failed resolutions.
-- Author: AI Assistant (Refactoring v3)
DROP FUNCTION IF EXISTS starexec.InsertResolvedDependencies(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.InsertResolvedDependencies(_root_space_id INT)
RETURNS TABLE(inserted_count INT, total_found INT) AS $$
DECLARE
	v_inserted INT := 0;
	v_total INT := 0;
BEGIN
	-- 1. Perform batch resolution and insert into bench_dependency
	INSERT INTO starexec.bench_dependency (primary_bench_id, secondary_bench_id, include_path)
	SELECT r.p_id, r.s_id, r.inc_path
	FROM starexec.ResolveBenchmarkDependenciesBatch(_root_space_id) r
	ON CONFLICT (primary_bench_id, secondary_bench_id)
	DO UPDATE SET include_path = EXCLUDED.include_path;

	GET DIAGNOSTICS v_inserted = ROW_COUNT;

	-- 2. Count total resolutions found
	SELECT COUNT(*) INTO v_total
	FROM starexec.ResolveBenchmarkDependenciesBatch(_root_space_id);

	RETURN QUERY SELECT v_inserted, v_total;
END;
$$ LANGUAGE plpgsql;

-- Deletes a benchmark given that benchmark's id
-- Author: Todd Elvers	+ Eric Burns
DROP FUNCTION IF EXISTS starexec.SetBenchmarkToDeletedById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetBenchmarkToDeletedById(_benchmarkId INT)
RETURNS TEXT AS $$
DECLARE
	_path TEXT;
BEGIN
	UPDATE users
	SET disk_size = users.disk_size - b.disk_size
	FROM starexec.benchmarks b
	WHERE users.id = b.user_id AND b.id = _benchmarkId;

	SELECT b.path INTO _path FROM starexec.benchmarks b WHERE b.id = _benchmarkId;

	UPDATE benchmarks
	SET deleted = true, disk_size = 0
	WHERE id = _benchmarkId;

	IF NOT FOUND THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0002',
			MESSAGE = format('Benchmark %s not found', _benchmarkId);
	END IF;

	RETURN _path;
END;
$$ LANGUAGE plpgsql;

-- Gets the IDs of all the spaces associated with the given benchmark
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetAssociatedSpaceIdsByBenchmark(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAssociatedSpaceIdsByBenchmark(_benchId INT)
RETURNS TABLE(space_id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT ba.space_id
	FROM starexec.bench_assoc ba
	WHERE ba.bench_id = _benchId;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the benchmark with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetBenchmarkById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkById(_id INT)
RETURNS TABLE(bench_id INT, bench_user_id INT, bench_name VARCHAR, bench_bench_type INT, bench_uploaded TIMESTAMP, bench_path TEXT, bench_downloadable BOOLEAN, bench_disk_size BIGINT, bench_description TEXT, bench_deleted BOOLEAN, bench_recycled BOOLEAN, types_id INT, types_name VARCHAR, types_description TEXT, types_community INT, types_path TEXT, types_disk_size BIGINT, types_processor_type SMALLINT, types_time_limit SMALLINT, types_syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT b.id AS bench_id,
        b.user_id AS bench_user_id,
        b.name AS bench_name,
        b.bench_type AS bench_bench_type,
        b.uploaded AS bench_uploaded,
        b.path AS bench_path,
        b.downloadable AS bench_downloadable,
        b.disk_size AS bench_disk_size,
        b.description AS bench_description,
        b.deleted AS bench_deleted,
        b.recycled AS bench_recycled,
        p.id AS types_id,
        p.name AS types_name,
        p.description AS types_description,
        p.community AS types_community,
        p.path AS types_path,
        p.disk_size AS types_disk_size,
        p.processor_type AS types_processor_type,
        p.time_limit AS types_time_limit,
        p.syntax_id AS types_syntax_id
    FROM starexec.benchmarks b
    LEFT OUTER JOIN processors p ON b.bench_type = p.id
    WHERE b.id = _id AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetBenchmarkPathById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkPathById(_id INT)
RETURNS TABLE(name VARCHAR, path TEXT) AS $$
BEGIN
	RETURN QUERY
	SELECT b.name, b.path
	FROM starexec.benchmarks b
	WHERE b.id = _id AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the benchmark with the given id, including deleted benchmarks
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarkByIdIncludeDeletedAndRecycled(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkByIdIncludeDeletedAndRecycled(_id INT)
RETURNS TABLE(bench_id INT, bench_user_id INT, bench_name VARCHAR, bench_bench_type INT, bench_uploaded TIMESTAMP, bench_path TEXT, bench_downloadable BOOLEAN, bench_disk_size BIGINT, bench_description TEXT, bench_deleted BOOLEAN, bench_recycled BOOLEAN, types_id INT, types_name VARCHAR, types_description TEXT, types_community INT, types_path TEXT, types_disk_size BIGINT, types_processor_type SMALLINT, types_time_limit SMALLINT, types_syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT b.id AS bench_id,
        b.user_id AS bench_user_id,
        b.name AS bench_name,
        b.bench_type AS bench_bench_type,
        b.uploaded AS bench_uploaded,
        b.path AS bench_path,
        b.downloadable AS bench_downloadable,
        b.disk_size AS bench_disk_size,
        b.description AS bench_description,
        b.deleted AS bench_deleted,
        b.recycled AS bench_recycled,
        p.id AS types_id,
        p.name AS types_name,
        p.description AS types_description,
        p.community AS types_community,
        p.path AS types_path,
        p.disk_size AS types_disk_size,
        p.processor_type AS types_processor_type,
        p.time_limit AS types_time_limit,
        p.syntax_id AS types_syntax_id
    FROM starexec.benchmarks b
    LEFT OUTER JOIN processors p ON b.bench_type = p.id
    WHERE b.id = _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetXMLUploadStatusById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetXMLUploadStatusById(_id INT)
RETURNS TABLE(id INT, user_id INT, upload_time TIMESTAMP, file_upload_complete BOOLEAN, everything_complete BOOLEAN, total_spaces INT, completed_spaces INT, total_benchmarks INT, completed_benchmarks INT, total_solvers INT, completed_solvers INT, total_updates INT, completed_updates INT, error_message TEXT) AS $$
BEGIN
	RETURN QUERY
	SELECT xu.id, xu.user_id, xu.upload_time, xu.file_upload_complete, xu.everything_complete, xu.total_spaces, xu.completed_spaces, xu.total_benchmarks, xu.completed_benchmarks, xu.total_solvers, xu.completed_solvers, xu.total_updates, xu.completed_updates, xu.error_message
	FROM starexec.space_xml_uploads xu
	WHERE xu.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of benchmarks in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarkCountInSpaceWithQuery(INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkCountInSpaceWithQuery(_spaceId INT, _query TEXT)
RETURNS TABLE(benchCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) AS benchCount
	FROM starexec.bench_assoc ba
	JOIN benchmarks b ON b.id = ba.bench_id
	LEFT JOIN processors p ON b.bench_type = p.id
	WHERE ba.space_id = _spaceId AND
		(b.name LIKE '%' || _query || '%' OR
		 p.name LIKE '%' || _query || '%' OR
		 (p.name IS NULL AND 'none' LIKE '%' || _query || '%'));
END;
$$ LANGUAGE plpgsql;

-- Retrieves all benchmarks belonging to a space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSpaceBenchmarksById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpaceBenchmarksById(_id INT)
RETURNS TABLE(
    bench_id INT,
    bench_user_id INT,
    bench_name VARCHAR,
    bench_bench_type INT,
    bench_uploaded TIMESTAMP,
    bench_path TEXT,
    bench_downloadable BOOLEAN,
    bench_disk_size BIGINT,
    bench_description TEXT,
    bench_deleted BOOLEAN,
    bench_recycled BOOLEAN,
    bench_assoc_space_id INT,
    bench_assoc_order_id INT,
    types_id INT,
    types_community INT,
    types_name VARCHAR,
    types_description TEXT,
    types_path TEXT,
    types_disk_size BIGINT,
    types_processor_type SMALLINT,
    types_time_limit SMALLINT,
    types_syntax_id INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT b.id AS bench_id,
        b.user_id AS bench_user_id,
        b.name AS bench_name,
        b.bench_type AS bench_bench_type,
        b.uploaded AS bench_uploaded,
        b.path AS bench_path,
        b.downloadable AS bench_downloadable,
        b.disk_size AS bench_disk_size,
        b.description AS bench_description,
        b.deleted AS bench_deleted,
        b.recycled AS bench_recycled,
        ba.space_id AS bench_assoc_space_id,
        ba.order_id AS bench_assoc_order_id,
        p.id AS types_id,
        p.community AS types_community,
        p.name AS types_name,
        p.description AS types_description,
        p.path AS types_path,
        p.disk_size AS types_disk_size,
        p.processor_type AS types_processor_type,
        p.time_limit AS types_time_limit,
        p.syntax_id AS types_syntax_id
    FROM starexec.bench_assoc ba
    JOIN benchmarks b ON b.id = ba.bench_id
    LEFT OUTER JOIN processors p ON b.bench_type = p.id
    WHERE ba.space_id = _id AND b.deleted = false AND b.recycled = false
    ORDER BY ba.order_id ASC;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of public spaces a benchmark is in
-- Benton McCune
DROP FUNCTION IF EXISTS starexec.IsBenchPublic(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsBenchPublic(_benchId INT)
RETURNS TABLE(benchPublic BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) as benchPublic
	FROM starexec.bench_assoc ba
	WHERE ba.bench_id = _benchId
	AND IsPublic(ba.space_id);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IsBenchmarkDeleted(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsBenchmarkDeleted(_benchId INT)
RETURNS TABLE(benchDeleted BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) AS benchDeleted
	FROM starexec.benchmarks b
	WHERE b.deleted = true AND b.id = _benchId;
END;
$$ LANGUAGE plpgsql;

-- Removes the association between a benchmark and a given space;
-- Author: Todd Elvers + Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveBenchFromSpace(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveBenchFromSpace(_benchId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
	IF _spaceId >= 0 THEN
		DELETE FROM starexec.bench_assoc
		WHERE space_id = _spaceId AND bench_id = _benchId;
	END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the details associated with a given benchmark
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.UpdateBenchmarkDetails(INT, VARCHAR, TEXT, BOOLEAN, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateBenchmarkDetails(_benchmarkId INT, _name VARCHAR(256), _description TEXT, _downloadable BOOLEAN, _type INT)
RETURNS VOID AS $$
BEGIN
	UPDATE benchmarks
	SET name = _name,
		description = _description,
		downloadable = _downloadable,
		bench_type = _type
	WHERE id = _benchmarkId;
	IF NOT FOUND THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0002',
			MESSAGE = format('Benchmark %s not found', _benchmarkId);
	END IF;
END;
$$ LANGUAGE plpgsql;

-- Get the total count of the benchmarks belong to a specific user
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetBenchmarkCountByUser(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkCountByUser(_userId INT)
RETURNS TABLE(benchCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) AS benchCount
	FROM starexec.benchmarks b
	WHERE b.user_id = _userId AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of benchmarks a given user has that match the query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarkCountByUserWithQuery(INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkCountByUserWithQuery(_userId INT, _query TEXT)
RETURNS TABLE(benchCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) AS benchCount
	FROM starexec.benchmarks b
	JOIN processors p ON b.bench_type = p.id
	WHERE b.user_id = _userId AND b.deleted = false AND b.recycled = false AND
		(b.name LIKE '%' || _query || '%' OR p.name LIKE '%' || _query || '%');
END;
$$ LANGUAGE plpgsql;

-- Sets the recycled attribute to the given value for the given benchmark
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetBenchmarkRecycledValue(INT, BOOLEAN) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetBenchmarkRecycledValue(_benchId INT, _recycled BOOLEAN)
RETURNS VOID AS $$
BEGIN
	UPDATE benchmarks SET recycled = _recycled WHERE id = _benchId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark %s not found', _benchId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Checks to see whether the "recycled" flag is set for the given benchmark
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsBenchmarkRecycled(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsBenchmarkRecycled(_benchId INT)
RETURNS TABLE(recycled BOOLEAN) AS $$
BEGIN
	RETURN QUERY SELECT b.recycled FROM starexec.benchmarks b WHERE b.id = _benchId;
END;
$$ LANGUAGE plpgsql;

-- Counts how many recycled benchmarks a user has that match the given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledBenchmarkCountByUser(INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledBenchmarkCountByUser(_userId INT, _query TEXT)
RETURNS TABLE(benchCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT COUNT(*) AS benchCount
	FROM starexec.benchmarks b
	JOIN processors p ON b.bench_type = p.id
	WHERE b.recycled = true AND b.user_id = _userId AND b.deleted = false AND
		(b.name LIKE '%' || _query || '%' OR p.name LIKE '%' || _query || '%');
END;
$$ LANGUAGE plpgsql;

-- Gets the path to every recycled benchmark a user has
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledBenchmarkPaths(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledBenchmarkPaths(_userId INT)
RETURNS TABLE(path TEXT) AS $$
BEGIN
	RETURN QUERY SELECT b.path FROM starexec.benchmarks b
	WHERE b.recycled = true AND b.user_id = _userId;
END;
$$ LANGUAGE plpgsql;

-- Removes all recycled benchmarks a user has in the database
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetRecycledBenchmarksToDeleted(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetRecycledBenchmarksToDeleted(_userId INT)
RETURNS VOID AS $$
BEGIN
	UPDATE users
	SET disk_size = users.disk_size - (
		SELECT COALESCE(SUM(disk_size), 0)
		FROM starexec.benchmarks
		WHERE user_id = _userId AND recycled = true AND deleted = false
	)
	WHERE users.id = _userId;

	UPDATE benchmarks
	SET deleted = true, disk_size = 0
	WHERE user_id = _userId AND recycled = true AND deleted = false;
END;
$$ LANGUAGE plpgsql;

-- Gets all recycled benchmark ids a user has
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledBenchmarkIds(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledBenchmarkIds(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY SELECT b.id FROM starexec.benchmarks b
	WHERE b.user_id = _userId AND b.recycled = true;
END;
$$ LANGUAGE plpgsql;

-- Sets the recycled flag for a single benchmark back to false
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RestoreBenchmark(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RestoreBenchmark(_benchId INT)
RETURNS VOID AS $$
BEGIN
	UPDATE benchmarks SET recycled = false WHERE id = _benchId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark %s not found', _benchId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets rid of all the current attributes a benchmark has
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.ClearBenchAttributes(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.ClearBenchAttributes(_benchId INT)
RETURNS VOID AS $$
DECLARE
    _deleted_count INT := 0;
BEGIN
	DELETE FROM starexec.bench_attributes WHERE bench_id = _benchId;
    GET DIAGNOSTICS _deleted_count = ROW_COUNT;
    IF _deleted_count = 0 THEN
        -- Check if benchmark exists
        IF NOT EXISTS(SELECT 1 FROM starexec.benchmarks WHERE id = _benchId) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Benchmark %s not found', _benchId);
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the benchmarks owned by a given user id
-- Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarksByOwner(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarksByOwner(_userId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN, type_id INT, type_name VARCHAR, type_description TEXT, types_id INT, types_community INT, types_name VARCHAR, types_description TEXT, types_path TEXT, types_disk_size BIGINT, types_processor_type SMALLINT, types_time_limit SMALLINT, types_syntax_id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled,
           p.id as type_id, p.name as type_name, p.description as type_description,
           p.id AS types_id,
           p.community AS types_community,
           p.name AS types_name,
           p.description AS types_description,
           p.path AS types_path,
           p.disk_size AS types_disk_size,
           p.processor_type AS types_processor_type,
           p.time_limit AS types_time_limit,
           p.syntax_id AS types_syntax_id
	FROM starexec.benchmarks b
	LEFT OUTER JOIN processors p ON b.bench_type = p.id
	WHERE b.user_id = _userId AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Gets the ids of every orphaned benchmark a user owns
DROP FUNCTION IF EXISTS starexec.GetOrphanedBenchmarkIds(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetOrphanedBenchmarkIds(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT b.id FROM starexec.benchmarks b
	LEFT JOIN bench_assoc ba ON ba.bench_id = b.id
	WHERE b.user_id = _userId AND ba.space_id IS NULL;
END;
$$ LANGUAGE plpgsql;

-- Permanently removes a benchmark from the database
-- Author: Eric Burns
DROP ROUTINE IF EXISTS starexec.RemoveBenchmarkFromDatabase(INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.RemoveBenchmarkFromDatabase(_id INT)
AS $$
BEGIN
	DELETE FROM starexec.benchmarks WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all the benchmarks ids of benchmarks that are in at least one space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarksAssociatedWithSpaces() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarksAssociatedWithSpaces()
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY SELECT DISTINCT bench_id AS id FROM starexec.bench_assoc;
END;
$$ LANGUAGE plpgsql;

-- Gets the benchmarks ids of all benchmarks associated with at least one pair
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarksAssociatedWithPairs() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarksAssociatedWithPairs()
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY SELECT DISTINCT bench_id AS id FROM starexec.job_pairs;
END;
$$ LANGUAGE plpgsql;

-- Gets the benchmark ids of all deleted benchmarks
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetDeletedBenchmarks() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDeletedBenchmarks()
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN) AS $$
BEGIN
	RETURN QUERY SELECT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled
	FROM starexec.benchmarks b WHERE b.deleted = true;
END;
$$ LANGUAGE plpgsql;

-- returns every benchmark that shares a space with the given user
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetBenchmarksInSharedSpaces(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarksInSharedSpaces(_userId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN, type_name VARCHAR, type_description TEXT, types_id INT, types_community INT, types_name VARCHAR, types_description TEXT, types_path TEXT, types_disk_size BIGINT, types_processor_type SMALLINT, types_time_limit SMALLINT, types_syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled,
           p.name AS type_name, p.description AS type_description,
           p.id AS types_id,
           p.community AS types_community,
           p.name AS types_name,
           p.description AS types_description,
           p.path AS types_path,
           p.disk_size AS types_disk_size,
           p.processor_type AS types_processor_type,
           p.time_limit AS types_time_limit,
           p.syntax_id AS types_syntax_id
	FROM starexec.benchmarks b
	JOIN bench_assoc ba ON ba.bench_id = b.id
	JOIN user_assoc ua ON ua.space_id = ba.space_id
	LEFT OUTER JOIN processors p ON b.bench_type = p.id
	WHERE ua.user_id = _userId AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Gets all solvers that reside in public spaces
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetPublicBenchmarks() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPublicBenchmarks()
RETURNS TABLE(id INT, user_id INT, name VARCHAR, bench_type INT, uploaded TIMESTAMP, path TEXT, downloadable BOOLEAN, disk_size BIGINT, description TEXT, deleted BOOLEAN, recycled BOOLEAN, type_name VARCHAR, type_description TEXT, types_id INT, types_community INT, types_name VARCHAR, types_description TEXT, types_path TEXT, types_disk_size BIGINT, types_processor_type SMALLINT, types_time_limit SMALLINT, types_syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT b.id, b.user_id, b.name, b.bench_type, b.uploaded, b.path, b.downloadable, b.disk_size, b.description, b.deleted, b.recycled,
           p.name AS type_name, p.description AS type_description,
           p.id AS types_id,
           p.community AS types_community,
           p.name AS types_name,
           p.description AS types_description,
           p.path AS types_path,
           p.disk_size AS types_disk_size,
           p.processor_type AS types_processor_type,
           p.time_limit AS types_time_limit,
           p.syntax_id AS types_syntax_id
	FROM starexec.benchmarks b
	JOIN bench_assoc ba ON ba.bench_id = b.id
	JOIN spaces s ON s.id = ba.space_id
	LEFT OUTER JOIN processors p ON b.bench_type = p.id
	WHERE s.public_access = true AND b.deleted = false AND b.recycled = false;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetBrokenBenchDependencies(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBrokenBenchDependencies(_benchId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT b.id
	FROM starexec.benchmarks b JOIN bench_dependency bd ON b.id = bd.secondary_bench_id
	WHERE (b.deleted = true OR b.recycled = true) AND bd.primary_bench_id = _benchId;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Cluster PROCEDURES
-- ================================================================================

-- Description: This file contains all cluster stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a worker node to the database and ignores duplicates
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AssociateQueue(VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AssociateQueue(_queueName VARCHAR(64), _nodeName VARCHAR(64))
RETURNS VOID AS $$
BEGIN
	INSERT INTO queue_assoc
	VALUES(
		(SELECT id FROM starexec.queues WHERE name = _queueName),
		(SELECT id FROM starexec.nodes WHERE name = _nodeName))
	ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Adds a worker node to the database and ignores duplicates
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddNode(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddNode(_name VARCHAR(64))
RETURNS VOID AS $$
BEGIN
	INSERT INTO nodes (name) VALUES (_name) ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Clear all Queue Associations from the db
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.ClearQueueAssociations() CASCADE;
CREATE OR REPLACE FUNCTION starexec.ClearQueueAssociations()
RETURNS VOID AS $$
BEGIN
	TRUNCATE queue_assoc;
END;
$$ LANGUAGE plpgsql;

-- Gets the id, name and status of all nodes in the cluster that are active
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetNodesForQueue(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNodesForQueue(_id INT)
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT n.id, n.name, n.status
	FROM starexec.queue_assoc qa
	JOIN nodes n ON n.id = qa.node_id
	WHERE qa.queue_id = _id
	ORDER BY n.name;
END;
$$ LANGUAGE plpgsql;

-- Gets the id, name and status of all queues in the cluster that are active
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetAllQueues() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllQueues()
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR, global_access BOOLEAN, cpuTimeout INT, clockTimeout INT) AS $$
BEGIN
	RETURN QUERY
	SELECT q.id, q.name, q.status, q.global_access, q.cpuTimeout, q.clockTimeout
	FROM starexec.queues q
	WHERE q.status = 'ACTIVE'
	ORDER BY q.name;
END;
$$ LANGUAGE plpgsql;

-- Gets the id, name and status of all queues in the cluster
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetAllQueuesAdmin() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllQueuesAdmin()
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR, global_access BOOLEAN, cpuTimeout INT, clockTimeout INT) AS $$
BEGIN
	RETURN QUERY
	SELECT q.id, q.name, q.status, q.global_access, q.cpuTimeout, q.clockTimeout
	FROM starexec.queues q
	ORDER BY q.id;
END;
$$ LANGUAGE plpgsql;

-- Gets worker node with the given ID
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetNodeDetails(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNodeDetails(_id INT)
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR) AS $$
BEGIN
	RETURN QUERY SELECT n.id, n.name, n.status FROM starexec.nodes n WHERE n.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Gets the queue with the given ID (excluding SGE attributes)
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetQueue(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetQueue(_id INT)
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR, global_access BOOLEAN, cpuTimeout INT, clockTimeout INT) AS $$
BEGIN
	RETURN QUERY SELECT q.id, q.name, q.status, q.global_access, q.cpuTimeout, q.clockTimeout
	FROM starexec.queues q WHERE q.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Updates all queues status'
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateAllQueueStatus(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateAllQueueStatus(_status VARCHAR(32))
RETURNS VOID AS $$
BEGIN
	UPDATE queues SET status = _status;
END;
$$ LANGUAGE plpgsql;

-- Updates a specific queues status
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateQueueStatus(VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateQueueStatus(_name VARCHAR(64), _status VARCHAR(32))
RETURNS VOID AS $$
BEGIN
	UPDATE queues SET status = _status WHERE name = _name;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _name);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates all nodes status'
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateAllNodeStatus(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateAllNodeStatus(_status VARCHAR(32))
RETURNS VOID AS $$
BEGIN
	UPDATE nodes SET status = _status;
END;
$$ LANGUAGE plpgsql;

-- Updates a specific node's status
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateNodeStatus(VARCHAR, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateNodeStatus(_name VARCHAR(64), _status VARCHAR(32))
RETURNS VOID AS $$
BEGIN
	UPDATE nodes SET status = _status WHERE name = _name;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Node %s not found', _name);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all the nodes in the system that are active
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetAllNodes() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllNodes()
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR) AS $$
BEGIN
	RETURN QUERY SELECT n.id, n.name, n.status FROM starexec.nodes n WHERE n.status = 'ACTIVE';
END;
$$ LANGUAGE plpgsql;

-- Returns all the nodes in the system that are active and not associated with the queue already
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNonAttachedNodes(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNonAttachedNodes(_queueId INT)
RETURNS TABLE(node_id INT, queue_id INT, node_name VARCHAR, queue_name VARCHAR, node_status VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT n.id, q.id, n.name, q.name, n.status
	FROM starexec.nodes n
	LEFT JOIN queue_assoc qa ON n.id = qa.node_id
	LEFT JOIN queues q ON q.id = qa.queue_id
	WHERE n.status = 'ACTIVE' AND (qa.queue_id IS NULL OR qa.queue_id != _queueId);
END;
$$ LANGUAGE plpgsql;

-- Returns the jobs that are currently running on a specific queue
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetJobsRunningOnQueue(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobsRunningOnQueue(_queueId INT)
RETURNS TABLE(id INT, name VARCHAR, user_id INT, queue_id INT, created TIMESTAMP, completed TIMESTAMP, description TEXT, deleted BOOLEAN, primary_space INT, status INT, totalPairs BIGINT, completePairs BIGINT, pendingPairs BIGINT, errorPairs BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT
		j.id,
		j.name,
		j.user_id,
		j.queue_id,
		j.created,
		j.completed,
		j.description,
		j.deleted,
		j.primary_space,
		GetJobStatus(j.id) AS status,
		CAST(j.total_pairs AS BIGINT) AS totalPairs,
		GetCompletePairs(j.id) AS completePairs,
		GetPendingPairs(j.id) AS pendingPairs,
		GetErrorPairs(j.id) AS errorPairs
	FROM starexec.jobs j
	JOIN job_pairs jp ON j.id = jp.job_id
	WHERE (jp.status_code < 7 OR jp.status_code BETWEEN 19 AND 22) AND j.queue_id = _queueId;
END;
$$ LANGUAGE plpgsql;

-- Returns the Queue that a specific node is associated with
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetQueueForNode(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetQueueForNode(_nodeId INT)
RETURNS TABLE(id INT, name VARCHAR, status VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT q.id, q.name, q.status
	FROM starexec.queues q, queue_assoc qa
	WHERE q.id = qa.queue_id AND qa.node_id = _nodeId;
END;
$$ LANGUAGE plpgsql;

-- Return the node id given its name
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNodeIdByName(VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNodeIdByName(_nodeName VARCHAR(128))
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY SELECT n.id FROM starexec.nodes n WHERE n.name = _nodeName;
END;
$$ LANGUAGE plpgsql;

-- Return the node name given its id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNodeNameById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNodeNameById(_nodeId INT)
RETURNS TABLE(name VARCHAR) AS $$
BEGIN
	RETURN QUERY SELECT n.name FROM starexec.nodes n WHERE n.id = _nodeId;
END;
$$ LANGUAGE plpgsql;

-- deletes a node from the database
DROP FUNCTION IF EXISTS starexec.DeleteNode(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteNode(_id INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.nodes WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Node %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Communities PROCEDURES
-- ================================================================================

-- Description: This file contains all community stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Checks to see if the space with the given space ID is a community.
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.IsCommunity(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsCommunity(_spaceId INT)
RETURNS TABLE(space_id INT, child_id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT sa.space_id, sa.child_id
	FROM starexec.set_assoc sa
	WHERE sa.space_id = 1 AND sa.child_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Returns basic space information for the community with the given id
-- This ensures security by preventing malicious users from getting details about ANY space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetCommunityById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityById(_id INT)
RETURNS TABLE(space_id INT, child_id INT, "space.id" INT, "space.name" VARCHAR, "space.description" TEXT, "space.locked" BOOLEAN, "space.created" TIMESTAMP, "space.public_access" BOOLEAN, "space.default_permission" INT, "space.parent_space" INT) AS $$
BEGIN
    RETURN QUERY
    SELECT sa.space_id,
        sa.child_id,
        s.id        AS "space.id",
        s.name      AS "space.name",
        s.description AS "space.description",
        s.locked    AS "space.locked",
        s.created   AS "space.created",
        s.public_access   AS "space.public_access",
        s.default_permission AS "space.default_permission",
        NULL::INT AS "space.parent_space"
    FROM starexec.set_assoc sa
    JOIN spaces s ON s.id = sa.child_id
    WHERE _id = sa.child_id AND sa.space_id = 1;
END;
$$ LANGUAGE plpgsql;

-- Removes the association a user has with a given space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.LeaveSpace(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.LeaveSpace(_userId INT, _spaceId INT)
RETURNS VOID AS $$
DECLARE
	_perm_id INT;
BEGIN
	-- Remove the permission associated with this user/space
	SELECT ua.permission INTO _perm_id
	FROM starexec.user_assoc ua
	WHERE ua.user_id = _userId AND ua.space_id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not associated with space %s', _userId, _spaceId);
    END IF;

	DELETE FROM starexec.permissions WHERE id = _perm_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Permission %s for user %s in space %s not found', _perm_id, _userId, _spaceId);
    END IF;

	-- Delete the association
	DELETE FROM starexec.user_assoc
	WHERE user_id = _userId AND space_id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not associated with space %s', _userId, _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Removes every association a user has with every space in the hierarchy rooted at the given space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.LeaveHierarchy(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.LeaveHierarchy(_userId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.user_assoc
	WHERE user_id = _userId AND space_id IN (
		SELECT c.descendant FROM starexec.closure c WHERE c.ancestor = _spaceId
	);
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('No hierarchy membership found for user %s in space %s', _userId, _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetCommunityStatsUsers() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityStatsUsers()
RETURNS TABLE(comm_id INT, userCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT ca.comm_id, COUNT(DISTINCT ua.user_id) AS userCount
	FROM starexec.community_assoc ca
	JOIN user_assoc ua ON ca.space_id = ua.space_id
	GROUP BY ca.comm_id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetCommunityStatsSolvers() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityStatsSolvers()
RETURNS TABLE(comm_id INT, solverCount BIGINT, solverDiskUsage BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.comm_id,
           COUNT(DISTINCT sub.solverId) as solverCount,
           COALESCE(SUM(sub.solverDiskSize)::bigint, 0) as solverDiskUsage
	FROM (SELECT DISTINCT ca.comm_id, s.id as solverId, s.disk_size as solverDiskSize
		FROM starexec.community_assoc ca
		JOIN solver_assoc sa ON sa.space_id = ca.space_id
		JOIN solvers s ON s.id = sa.solver_id) as sub
	GROUP BY sub.comm_id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetCommunityStatsBenches() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityStatsBenches()
RETURNS TABLE(comm_id INT, benchCount BIGINT, benchDiskUsage BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.comm_id,
           COUNT(DISTINCT sub.benchId) as benchCount,
           COALESCE(SUM(sub.benchDiskSize)::bigint, 0) AS benchDiskUsage
	FROM (SELECT DISTINCT ca.comm_id, b.id as benchId, b.disk_size as benchDiskSize
		FROM starexec.community_assoc ca
		JOIN bench_assoc ba ON ba.space_id = ca.space_id
		JOIN benchmarks b ON b.id = ba.bench_id) as sub
	GROUP BY sub.comm_id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetCommunityStatsJobs() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityStatsJobs()
RETURNS TABLE(comm_id INT, jobCount BIGINT, jobPairCount BIGINT) AS $$
BEGIN
	RETURN QUERY
	SELECT ca.comm_id, COUNT(DISTINCT jp.job_id) AS jobCount, COUNT(DISTINCT jp.id) AS jobPairCount
	FROM starexec.community_assoc ca
	JOIN job_assoc ja ON ja.space_id = ca.space_id
	JOIN job_pairs jp ON jp.job_id = ja.job_id
	WHERE jp.status_code >= 7  -- finishedRunning(): any terminal code (7+)
	GROUP BY ca.comm_id;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- ErrorLogs PROCEDURES
-- ================================================================================

-- Adds an error log to the database.
DROP FUNCTION IF EXISTS starexec.AddErrorLog(TEXT, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddErrorLog(_message TEXT, _logLevel VARCHAR(32))
RETURNS INT AS $$
DECLARE
	_llid INT;
	_id INT;
BEGIN
	SELECT ll.id INTO _llid FROM starexec.log_levels ll WHERE ll.name = _logLevel;
	INSERT INTO error_logs (message, log_level_id) VALUES (_message, _llid)
	RETURNING id INTO _id;
	RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Gets an error log from the database with the given id.
DROP FUNCTION IF EXISTS starexec.GetErrorLogById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetErrorLogById(_id INT)
RETURNS TABLE(id INT, message TEXT, "time" TIMESTAMP, level VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT el.id, el.message, el.time, ll.name AS level
	FROM starexec.error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
	WHERE el.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Deletes an error log with the given id.
DROP FUNCTION IF EXISTS starexec.DeleteErrorLogWithId(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteErrorLogWithId(_id INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.error_logs WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Error log %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Deletes all error log from before the given time.
DROP FUNCTION IF EXISTS starexec.DeleteErrorLogsBefore(TIMESTAMP) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteErrorLogsBefore(_time TIMESTAMP)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.error_logs WHERE error_logs.time < _time;
END;
$$ LANGUAGE plpgsql;

-- Deletes all error log from before the given time.
DROP FUNCTION IF EXISTS starexec.GetErrorLogsBefore(TIMESTAMP) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetErrorLogsBefore(_time TIMESTAMP)
RETURNS TABLE(id INT, message TEXT, "time" TIMESTAMP, level VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT el.id, el.message, el.time, ll.name AS level
	FROM starexec.error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
	WHERE el.time < _time
	ORDER BY el.time DESC;
END;
$$ LANGUAGE plpgsql;

-- Gets all error logs since the given time (inclusive).
DROP FUNCTION IF EXISTS starexec.GetErrorLogsSince(TIMESTAMP) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetErrorLogsSince(_since TIMESTAMP)
RETURNS TABLE(id INT, message TEXT, "time" TIMESTAMP, level VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT el.id, el.message, el.time, ll.name AS level
	FROM starexec.error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
	WHERE el.time >= _since
	ORDER BY el.time DESC;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllErrorLogs() CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllErrorLogs()
RETURNS TABLE(id INT, message TEXT, "time" TIMESTAMP, level VARCHAR) AS $$
BEGIN
	RETURN QUERY
    SELECT el.id, el.message, el.time AS "time", ll.name AS level
	FROM starexec.error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
	ORDER BY el.time DESC;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- JobPairs PROCEDURES
-- ================================================================================

-- Description: This file contains all job-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

DROP FUNCTION IF EXISTS starexec.UpdateJobPairStatus(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateJobPairStatus(_pairId INT, _statusCode INT)
RETURNS VOID AS $$
BEGIN
	UPDATE job_pairs
	SET status_code = _statusCode
	WHERE id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _pairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UpdateJobSpaceId(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateJobSpaceId(_pairId INT, _jobSpaceId INT)
RETURNS VOID AS $$
BEGIN
	UPDATE job_pairs
	SET job_space_id = _jobSpaceId
	WHERE id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _pairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UpdatePairNodeId(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdatePairNodeId(_jobPairId INT, _nodeId INT)
RETURNS VOID AS $$
BEGIN
	UPDATE job_pairs SET node_id=_nodeId WHERE id=_jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _jobPairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates a job pair's statistics directly from the execution node
-- Author: Benton McCune
DROP ROUTINE IF EXISTS starexec.UpdatePairRunSolverStats(INT, VARCHAR, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, BIGINT, INT, BIGINT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.UpdatePairRunSolverStats(_jobPairId INT, _nodeName VARCHAR(64), _wallClock DOUBLE PRECISION, _cpu DOUBLE PRECISION, _userTime DOUBLE PRECISION, _systemTime DOUBLE PRECISION, _maxVmem DOUBLE PRECISION, _maxResSet BIGINT, _stageNumber INT, _diskSize BIGINT)
AS $$
DECLARE
    _nodeId INT;
    _jobId INT;
    _userId INT;
BEGIN
    SELECT id INTO _nodeId FROM starexec.nodes WHERE name = _nodeName;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Node %s not found', _nodeName);
    END IF;

    UPDATE job_pairs SET node_id = _nodeId WHERE id = _jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _jobPairId);
    END IF;

    SELECT j.id, j.user_id
    INTO _jobId, _userId
    FROM starexec.job_pairs jp
    JOIN jobs j ON j.id = jp.job_id
    WHERE jp.id = _jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job for job pair %s not found', _jobPairId);
    END IF;

    UPDATE users
    SET disk_size = disk_size + _diskSize
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s for job pair %s not found', _userId, _jobPairId);
    END IF;

    UPDATE jobpair_stage_data
    SET wallclock = _wallClock,
        cpu = _cpu,
        user_time = _userTime,
        system_time = _systemTime,
        max_vmem = _maxVmem,
        max_res_set = _maxResSet,
        disk_size = _diskSize
    WHERE jobpair_id = _jobPairId AND stage_number = _stageNumber;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Stage %s for job pair %s not found', _stageNumber, _jobPairId);
    END IF;

    UPDATE jobs SET disk_size = disk_size + _diskSize WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s for job pair %s not found', _jobId, _jobPairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates a job pairs node Id
-- Author: Wyatt
DROP ROUTINE IF EXISTS starexec.UpdateNodeId(INT, VARCHAR, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.UpdateNodeId(_jobPairId INT, _nodeName VARCHAR(128), _sandbox INT)
AS $$
DECLARE
	_nodeId INT;
BEGIN
	SELECT id FROM starexec.nodes WHERE name=_nodeName INTO _nodeId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Node %s not found', _nodeName);
    END IF;

	UPDATE job_pairs SET node_id=_nodeId WHERE id = _jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _jobPairId);
    END IF;
	UPDATE job_pairs SET sandbox_num=_sandbox WHERE id=_jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _jobPairId);
    END IF;

	-- Next lines finish a pair that is still in the "running" state despite another pair being in the same place now
	-- First, mark the end time of the pairs
	UPDATE job_pairs SET end_time=NOW() WHERE node_id = _nodeId AND status_code = 4 AND id!=_jobPairId AND sandbox_num=_sandbox;
	-- Then, update the stuck pairs to an error code
	UPDATE job_pairs SET status_code = 10 WHERE node_id = _nodeId AND status_code = 4 AND id!=_jobPairId AND sandbox_num=_sandbox;
END;
$$ LANGUAGE plpgsql;

-- Sets a pair's disk_usage to 0, updating jobpair_stage_data, jobs, and users
DROP FUNCTION IF EXISTS starexec.RemoveJobPairDiskSize(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveJobPairDiskSize(_jobPairId INT)
RETURNS VOID AS $$
DECLARE
	_sumDiskSize BIGINT;
    _jobId INT;
    _userId INT;
BEGIN
	SELECT SUM(disk_size) FROM starexec.jobpair_stage_data WHERE jobpair_id=_jobPairId INTO _sumDiskSize;
    _sumDiskSize := COALESCE(_sumDiskSize, 0);

    SELECT j.id, j.user_id
    INTO _jobId, _userId
    FROM starexec.job_pairs jp
    JOIN jobs j ON j.id = jp.job_id
    WHERE jp.id = _jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job for job pair %s not found', _jobPairId);
    END IF;

    UPDATE jobs
    SET disk_size = disk_size - _sumDiskSize
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s for job pair %s not found', _jobId, _jobPairId);
    END IF;

    UPDATE users
    SET disk_size = disk_size - _sumDiskSize
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s for job pair %s not found', _userId, _jobPairId);
    END IF;

    UPDATE jobpair_stage_data SET disk_size=0 WHERE jobpair_id=_jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Stage data for job pair %s not found', _jobPairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all the nodes that could have pairs that have been enqueued longer than
-- some given amount of time. This works by getting all the queues with pairs that
-- have been enqueued longer than _timeThreshold and then returning all the nodes
-- from that queue that have not run any pairs since the _timeThreshold (basically filtering out
-- nodes that appear to be working).
DROP FUNCTION IF EXISTS starexec.GetNodesThatMayHavePairsEnqueuedLongerThan(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNodesThatMayHavePairsEnqueuedLongerThan(_timeThreshold INT)
RETURNS TABLE(node_id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT qa.node_id
	FROM starexec.job_pairs jp JOIN jobs j ON jp.job_id=j.id
	JOIN queues q ON q.id=j.queue_id
	JOIN queue_assoc qa ON q.id=qa.queue_id
	WHERE EXTRACT(EPOCH FROM (NOW() - jp.queuesub_time))/60 > _timeThreshold
		AND jp.status_code=2
		AND qa.node_id NOT IN
			-- This subquery will get all the working nodes.
			( SELECT i_qa.node_id
			  FROM starexec.job_pairs i_jp JOIN jobs i_j ON i_jp.job_id=i_j.id
				JOIN queues i_q ON i_q.id=i_j.queue_id
				JOIN queue_assoc i_qa ON i_q.id=i_qa.queue_id
			  WHERE EXTRACT(EPOCH FROM (NOW() - i_jp.start_time))/60 <= _timeThreshold);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetPairsEnqueuedLongerThan(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPairsEnqueuedLongerThan(_timeThreshold INT)
RETURNS TABLE(pair_id INT, job_id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT DISTINCT jp.id, j.id
	FROM starexec.job_pairs jp JOIN jobs j ON jp.job_id=j.id
	JOIN queues q ON q.id=j.queue_id
	JOIN queue_assoc qa ON q.id=qa.queue_id
	WHERE EXTRACT(EPOCH FROM (NOW() - jp.queuesub_time))/60 > _timeThreshold AND jp.status_code=2;
END;
$$ LANGUAGE plpgsql;

-- Updates a job pair's status
-- Author: Tyler Jensen
DROP ROUTINE IF EXISTS starexec.UpdatePairStatus(INT, SMALLINT) CASCADE;
DROP ROUTINE IF EXISTS starexec.UpdatePairStatus(INT, INT) CASCADE;
DROP FUNCTION IF EXISTS starexec.IsTerminalPairStatus(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsTerminalPairStatus(_status INT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN ((_status > 6 AND _status < 19) OR _status IN (21, 23, 24, 25, 26));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE PROCEDURE starexec.UpdatePairStatus(_jobPairId INT, _statusCode INT)
AS $$
DECLARE
	_job_id INT;
	_current_status INT;
	_count INT;
BEGIN
	-- Initialize _job_id
	SELECT job_id, status_code INTO _job_id, _current_status FROM job_pairs WHERE id = _jobPairId;
	IF NOT FOUND THEN
	    RAISE EXCEPTION USING
	        ERRCODE = 'P0002',
	        MESSAGE = format('Job pair %s not found', _jobPairId);
	END IF;

	-- Terminal pairs must not be moved back into an earlier non-terminal state.
	IF starexec.IsTerminalPairStatus(_current_status) AND NOT starexec.IsTerminalPairStatus(_statusCode) THEN
	    RAISE EXCEPTION USING
	        ERRCODE = 'P0001',
	        MESSAGE = format(
	            'Illegal status transition for pair %s: terminal status %s cannot move to non-terminal status %s',
	            _jobPairId,
	            _current_status,
	            _statusCode
	        );
	END IF;

	UPDATE job_pairs SET status_code=_statusCode WHERE id=_jobPairId;


	-- List of terminal status codes (ones that mean the pair is finished and won't be updated further)
	-- 7-18: Normal completion, resource limits, and common errors
	-- 21: Killed
	-- 23: Not reached
	-- 24: Benchmark dependency missing
	-- 25: Pre-processor error
	-- 26: Post-processor error
	IF starexec.IsTerminalPairStatus(_statusCode) THEN
		INSERT INTO job_pair_completion (pair_id) VALUES (_jobPairId)
		ON CONFLICT (pair_id) DO NOTHING;

		-- this checks to see if the job is done and sets its completion id if so.
		-- It checks by trying to find exactly 1 pair (for efficiency) that is not yet complete
		-- A pair is "not yet complete" if its status is Pending (1), Enqueued (2), Running (4),
		-- Processing Results (19), Paused (20), or Awaiting post-processor (22).
		SELECT COUNT(*) INTO _count FROM (SELECT id FROM starexec.job_pairs WHERE job_id=_job_id AND status_code IN (1, 2, 4, 19, 20, 22) LIMIT 1) AS subq;
		IF _count = 0 THEN
			UPDATE jobs SET completed=CURRENT_TIMESTAMP WHERE id=_job_id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Job %s for job pair %s not found', _job_id, _jobPairId);
            END IF;
		END IF;
	END IF;
	IF (_statusCode = 2) THEN
		UPDATE job_pairs SET queuesub_time=NOW() WHERE id=_jobPairId;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Job pair %s not found', _jobPairId);
        END IF;
	END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the status code for the given stage of the given pair
DROP ROUTINE IF EXISTS starexec.UpdatePairStageStatus(INT, INT, SMALLINT) CASCADE;
DROP ROUTINE IF EXISTS starexec.UpdatePairStageStatus(INT, INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.UpdatePairStageStatus(_jobPairId INT, _stageNumber INT, _statusCode INT)
AS $$
BEGIN
	UPDATE jobpair_stage_data SET status_code=_statusCode WHERE jobpair_id=_jobPairId AND stage_number=_stageNumber;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Stage %s for job pair %s not found', _stageNumber, _jobPairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the status code of every stage occurring after the given stage to the given status code.
-- We do this, for example, when an early stage times out and so later stages are never run
DROP ROUTINE IF EXISTS starexec.UpdateLaterStageStatuses(INT, INT, SMALLINT) CASCADE;
DROP ROUTINE IF EXISTS starexec.UpdateLaterStageStatuses(INT, INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.UpdateLaterStageStatuses(_jobPairId INT, _stageNumber INT, _statusCode INT)
AS $$
BEGIN
	UPDATE jobpair_stage_data SET status_code=_statusCode WHERE jobpair_id=_jobPairId AND stage_number>_stageNumber;
END;
$$ LANGUAGE plpgsql;

-- Sets all run stats to 0 for stages that come after the given stage. This is used for
-- pipelines where an early stage fails, causing later stages to not run
DROP ROUTINE IF EXISTS starexec.SetRunStatsForLaterStagesToZero(INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.SetRunStatsForLaterStagesToZero(_jobPairId INT, _stageNumber INT)
AS $$
BEGIN
	UPDATE jobpair_stage_data
	SET wallclock = 0,
		cpu=0,
		user_time=0,
		system_time=0,
		max_vmem=0,
		max_res_set=0
	WHERE jobpair_id=_jobPairId AND stage_number>_stageNumber;
END;
$$ LANGUAGE plpgsql;

-- Gets all the stages for the given job pair
DROP FUNCTION IF EXISTS starexec.GetJobPairStagesById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairStagesById(_id INT)
RETURNS TABLE(jobpair_id INT, stage_id INT, stage_number INT, solver_id INT, solver_name VARCHAR, config_id INT, config_name VARCHAR, status_code SMALLINT, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, job_space_id INT, pipeline_stage_id INT, stage_name VARCHAR, stage_type VARCHAR, exit_code INT) AS $$
BEGIN
	RETURN QUERY
	SELECT jobpair_stage_data.jobpair_id, jobpair_stage_data.stage_id, jobpair_stage_data.stage_number, jobpair_stage_data.solver_id, jobpair_stage_data.solver_name, jobpair_stage_data.config_id, jobpair_stage_data.config_name, jobpair_stage_data.status_code, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, jobpair_stage_data.job_space_id, pipeline_stages.pipeline_id, NULL::VARCHAR AS stage_name, NULL::VARCHAR AS stage_type, NULL::INT AS exit_code
	FROM starexec.jobpair_stage_data
	LEFT JOIN pipeline_stages ON pipeline_stages.stage_id=jobpair_stage_data.stage_id
	WHERE jobpair_stage_data.jobpair_id=_id
	ORDER BY jobpair_stage_data.stage_id ASC;
END;
$$ LANGUAGE plpgsql;

-- Gets the job pair with the given id. Only gets the primary stage!
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetJobPairById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairById(_Id INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT, completion_id INT, job_space_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data, job_pair_completion.completion_id, jobSpace.name
	FROM starexec.job_pairs
	LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
	LEFT JOIN job_pair_completion ON job_pairs.id = job_pair_completion.pair_id
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
	WHERE job_pairs.id=_Id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all attributes for a job pair
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetPairAttrs(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPairAttrs(_pairId INT)
RETURNS TABLE(pair_id INT, attr_key VARCHAR, attr_value VARCHAR, job_id INT, stage_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_attributes.pair_id, job_attributes.attr_key, job_attributes.attr_value, job_attributes.job_id, job_attributes.stage_number
	FROM starexec.job_attributes
	WHERE job_attributes.pair_id=_pairId
	ORDER BY attr_key ASC;
END;
$$ LANGUAGE plpgsql;

-- Updates a job pair's backend ID (SGE, OAR, or so on).
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.SetBackendExecId(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetBackendExecId(_jobPairId INT, _execId INT)
RETURNS VOID AS $$
BEGIN
	UPDATE job_pairs
	SET sge_id=_execId
	WHERE id=_jobPairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _jobPairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets back only the fields of a job pair that are necessary to determine where it is stored on disk
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairFilePathInfo(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairFilePathInfo(_pairId INT)
RETURNS TABLE(job_id INT, job_space_id INT, path VARCHAR, solver_name VARCHAR, config_name VARCHAR, bench_name VARCHAR, stage_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.job_id, job_pairs.job_space_id, job_pairs.path, jobpair_stage_data.solver_name,
	jobpair_stage_data.config_name, job_pairs.bench_name, jobpair_stage_data.stage_number
	FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
	WHERE job_pairs.id=_pairId AND jobpair_stage_data.stage_number = job_pairs.primary_jobpair_data;
END;
$$ LANGUAGE plpgsql;

-- Gets every pair_id and processor_id for pairs awaiting processing
DROP FUNCTION IF EXISTS starexec.GetPairsToBeProcessed(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPairsToBeProcessed(_processingStatus INT)
RETURNS TABLE(post_processor INT, id INT, stageNumber INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_stage_params.post_processor, job_pairs.id, jobpair_stage_data.stage_number
	FROM starexec.jobpair_stage_data
	JOIN job_pairs ON job_pairs.id = jobpair_stage_data.jobpair_id
	JOIN job_stage_params ON (job_stage_params.job_id=job_pairs.job_id AND job_stage_params.stage_number=jobpair_stage_data.stage_number)
	WHERE jobpair_stage_data.status_code=_processingStatus;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.RemovePairFromCompletedTable(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemovePairFromCompletedTable(_id INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.job_pair_completion
	WHERE pair_id=_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Completion record for job pair %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the queue submission time to now (the moment this is called) for the pair with the given id
DROP ROUTINE IF EXISTS starexec.SetPairStartTime(INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.SetPairStartTime(_id INT)
AS $$
BEGIN
	UPDATE job_pairs SET start_time=NOW() WHERE id=_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Deletes a job pair from the database. The _pairSize argument is in bytes, and it is only
-- used in cases where the disk_size field is not set in the stages of the pair to be deleted.
-- This is necessary only for old pairs with no disk_size set
DROP FUNCTION IF EXISTS starexec.DeleteJobPair(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteJobPair(_pairId INT)
RETURNS VOID AS $$
DECLARE
	pair_disk_size BIGINT := 0;
    _jobId INT;
    _userId INT;
BEGIN
    SELECT jp.job_id, j.user_id
    INTO _jobId, _userId
    FROM starexec.job_pairs jp
    JOIN jobs j ON j.id = jp.job_id
    WHERE jp.id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _pairId);
    END IF;

    SELECT COALESCE(SUM(disk_size), 0)
    INTO pair_disk_size
    FROM starexec.jobpair_stage_data
    WHERE jobpair_id = _pairId;

    UPDATE users
    SET disk_size = disk_size - pair_disk_size
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s for job pair %s not found', _userId, _pairId);
    END IF;

    UPDATE jobs
    SET disk_size = disk_size - pair_disk_size,
        total_pairs = total_pairs - 1
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s for job pair %s not found', _jobId, _pairId);
    END IF;

    DELETE FROM starexec.job_pairs
    WHERE job_pairs.id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _pairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all of the job pairs in a job that contain a given benchmark.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetJobPairsInJobContainingBenchmark(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsInJobContainingBenchmark(_jobId INT, _benchmarkId INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data
	FROM starexec.job_pairs INNER JOIN jobpair_stage_data ON job_pairs.id=jobpair_stage_data.jobpair_id
	WHERE job_pairs.job_id=_jobId AND job_pairs.bench_id=_benchmarkId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobPairsInJobContainingSolver(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsInJobContainingSolver(_jobId INT, _solverId INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data
	FROM starexec.job_pairs INNER JOIN jobpair_stage_data ON job_pairs.id=jobpair_stage_data.jobpair_id
	WHERE job_pairs.job_id=_jobId AND jobpair_stage_data.solver_id=_solverId;
END;
$$ LANGUAGE plpgsql;

-- Sets the completion time to now (the moment this is called) for the pair with the given id
-- Also sets the time_delta for the pair in the jobpair_time_delta table.
DROP ROUTINE IF EXISTS starexec.SetPairEndTime(INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.SetPairEndTime(_id INT)
AS $$
DECLARE
	_user_id INT;
	_queue_id INT;
    _job_id INT;
	_time_delta_val DOUBLE PRECISION;
BEGIN
    SELECT j.id, j.user_id, j.queue_id
    INTO _job_id, _user_id, _queue_id
    FROM starexec.job_pairs jp
    JOIN jobs j ON j.id = jp.job_id
    WHERE jp.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _id);
    END IF;

    UPDATE job_pairs SET end_time = NOW() WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found', _id);
    END IF;

	-- save the diff between this pair's timeout and wallclock time to jobpair_time_delta
	INSERT INTO jobpair_time_delta(user_id, queue_id, time_delta)
    VALUES (_user_id, _queue_id, 0)
	ON CONFLICT (user_id, queue_id) DO NOTHING;

	-- Calculate time delta
	SELECT jobs.clockTimeout - CEIL(SUM(jobpair_stage_data.wallclock)+1) + COALESCE(jobpair_time_delta.time_delta, 0)
	INTO _time_delta_val
	FROM starexec.jobpair_stage_data
	JOIN job_pairs ON job_pairs.id=jobpair_stage_data.jobpair_id
	JOIN jobs ON jobs.id = job_pairs.job_id
	LEFT JOIN jobpair_time_delta ON jobpair_time_delta.user_id = jobs.user_id AND jobpair_time_delta.queue_id = jobs.queue_id
	WHERE jobpair_stage_data.jobpair_id=_id
	GROUP BY jobs.clockTimeout, jobpair_time_delta.time_delta;

	UPDATE jobpair_time_delta
	SET time_delta = _time_delta_val
    WHERE user_id = _user_id
    AND queue_id = _queue_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Jobpair time delta entry for user %s and queue %s not found', _user_id, _queue_id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Counts the number of pairs with the given status code that completed in within the given
-- number of days
DROP FUNCTION IF EXISTS starexec.CountRecentPairsByStatus(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountRecentPairsByStatus(_status INT, _days INT)
RETURNS BIGINT AS $$
DECLARE
	count_val BIGINT;
BEGIN
	SELECT count(*) INTO count_val FROM starexec.job_pairs WHERE status_code=_status AND
	end_time BETWEEN (NOW() - INTERVAL '_days days') AND NOW();
	RETURN count_val;
END;
$$ LANGUAGE plpgsql;

-- Adds a single job pair input to the database
DROP FUNCTION IF EXISTS starexec.AddJobPairInput(INT, INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJobPairInput(_pairId INT, _input INT, _benchId INT)
RETURNS VOID AS $$
BEGIN
	INSERT INTO jobpair_inputs (jobpair_id, input_number, bench_id) VALUES (_pairId, _input, _benchId);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobPairInputPaths(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairInputPaths(_pairId INT)
RETURNS TABLE(path VARCHAR, input_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT benchmarks.path, jobpair_inputs.input_number
	FROM starexec.jobpair_inputs
	JOIN benchmarks ON benchmarks.id=jobpair_inputs.bench_id
	WHERE jobpair_id=_pairId ORDER BY input_number ASC;
END;
$$ LANGUAGE plpgsql;

-- Select all data from the jobpair_time_delta table for a specific
-- queue. -1 means all queues
DROP FUNCTION IF EXISTS starexec.GetJobpairTimeDeltaData(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobpairTimeDeltaData(_qid INT)
RETURNS TABLE(user_id INT, queue_id INT, time_delta DOUBLE PRECISION) AS $$
BEGIN
	RETURN QUERY
	SELECT jobpair_time_delta.user_id, jobpair_time_delta.queue_id, CAST(jobpair_time_delta.time_delta AS DOUBLE PRECISION)
	FROM starexec.jobpair_time_delta WHERE jobpair_time_delta.queue_id=_qid OR _qid = -1;
END;
$$ LANGUAGE plpgsql;

-- Deletes all data from the jobpair_time_delta table for a specific
-- queue. -1 means all queues
DROP FUNCTION IF EXISTS starexec.ClearJobpairTimeDeltaData(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.ClearJobpairTimeDeltaData(_qid INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.jobpair_time_delta WHERE queue_id=_qid OR _qid=-1;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobPairsWithStatus(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsWithStatus(_status INT)
RETURNS TABLE(
    id INT,
    job_id INT,
    bench_id INT,
    status_code SMALLINT,
    node_id INT,
    job_space_id INT,
    path VARCHAR,
    bench_name VARCHAR,
    solver_name VARCHAR,
    config_name VARCHAR,
    solver_id INT,
    config_id INT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    cpu DOUBLE PRECISION,
    wallclock DOUBLE PRECISION,
    user_time DOUBLE PRECISION,
    system_time DOUBLE PRECISION,
    max_vmem DOUBLE PRECISION,
    max_res_set BIGINT,
    disk_size BIGINT,
    sge_id INT,
    sandbox_num INT,
    queuesub_time TIMESTAMP,
    primary_jobpair_data INT
) AS $$
BEGIN
    -- job_pairs contains pair-level metadata; stage-specific runtime fields live in jobpair_stage_data.
    -- To avoid adding many redundant columns to job_pairs, join the primary jobpair_stage_data
    -- when available to populate solver/config/runtime columns.
    RETURN QUERY
    SELECT
    jp.id,
    jp.job_id,
    jp.bench_id,
    jp.status_code,
        jp.node_id,
        jp.job_space_id,
        jp.path,
        jp.bench_name,
        COALESCE(jpsd.solver_name, '') AS solver_name,
        COALESCE(jpsd.config_name, '') AS config_name,
        jpsd.solver_id AS solver_id,
        jpsd.config_id AS config_id,
    -- job_pairs contains pair-level start/end timestamps. jobpair_stage_data
    -- does not include start_time/end_time columns, so use the values
    -- from job_pairs (jp) here. Previously references to jpsd.start_time
    -- and jpsd.end_time caused "column does not exist" errors on Postgres.
        jp.start_time AS start_time,
        jp.end_time AS end_time,
        jpsd.cpu,
        jpsd.wallclock,
        jpsd.user_time,
        jpsd.system_time,
        jpsd.max_vmem,
        COALESCE(jpsd.max_res_set::bigint, 0) AS max_res_set,
        COALESCE(jpsd.disk_size::bigint, 0) AS disk_size,
        jp.sge_id,
        jp.sandbox_num,
        jp.queuesub_time,
        jp.primary_jobpair_data
    FROM starexec.job_pairs jp
    LEFT JOIN starexec.jobpair_stage_data jpsd ON jpsd.jobpair_id = jp.primary_jobpair_data
    WHERE jp.status_code = _status;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobPairIdsWithStatusNotRerunAfterDate(INT, TIMESTAMP) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairIdsWithStatusNotRerunAfterDate(_status INT, _earliestEndTime TIMESTAMP)
RETURNS TABLE(id INT) AS $$
BEGIN
	RETURN QUERY
	SELECT jp.id FROM starexec.job_pairs jp
	WHERE jp.status_code = _status
	AND (jp.end_time >= _earliestEndTime OR jp.end_time < '1970-01-01'::timestamp)
	AND jp.id NOT IN (SELECT pair_id FROM starexec.pairs_rerun);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetBrokenPairStatus(INT, INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetBrokenPairStatus(_pairId INT, _current_status INT, _new_status INT)
RETURNS VOID AS $$
DECLARE
	_job_id INT;
    _count INT;
BEGIN
    UPDATE starexec.job_pairs
    SET status_code = _new_status
    WHERE id = _pairId AND status_code = _current_status
    RETURNING job_id INTO _job_id;

    -- The pair changed after the caller read it; leave it untouched.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE starexec.jobpair_stage_data
    SET status_code = _new_status
    WHERE jobpair_id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Stage data for job pair %s not found', _pairId);
    END IF;

    -- Terminal status codes mean the pair is finished and should appear in
    -- job_pair_completion. This mirrors UpdatePairStatus side effects.
    IF starexec.IsTerminalPairStatus(_new_status) THEN
        INSERT INTO starexec.job_pair_completion (pair_id) VALUES (_pairId)
        ON CONFLICT (pair_id) DO NOTHING;

        -- Serialize the final job-completion check with other concurrent pair
        -- completions for the same job.
        PERFORM 1 FROM starexec.jobs WHERE id = _job_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Job %s for job pair %s not found', _job_id, _pairId);
        END IF;

        SELECT COUNT(*) INTO _count FROM (
            SELECT id FROM starexec.job_pairs
            WHERE job_id = _job_id AND status_code IN (1, 2, 4, 19, 20, 22)
            LIMIT 1
        ) AS subq;
        IF _count = 0 THEN
            UPDATE starexec.jobs
            SET completed = COALESCE(completed, CURRENT_TIMESTAMP)
            WHERE id = _job_id;
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Counts the total number of job pairs that satisfy GetNextPageOfJobPairsInJobSpaceHierarchy
DROP FUNCTION IF EXISTS starexec.CountJobPairsInJobSpaceHierarchyByType(INT, INT, VARCHAR, TEXT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountJobPairsInJobSpaceHierarchyByType(_jobSpaceId INT, _configId INT, _type VARCHAR(16), _query TEXT, _stageNumber INT)
RETURNS BIGINT AS $$
DECLARE
	count_val BIGINT;
BEGIN
	SELECT COUNT(*) INTO count_val FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
	LEFT JOIN job_pair_completion ON job_pair_completion.pair_id=job_pairs.id
	LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id and job_attributes.stage_number=jobpair_stage_data.stage_number AND job_attributes.attr_key='starexec-result')
	JOIN job_space_closure ON descendant=job_pairs.job_space_id
	LEFT JOIN bench_attributes ON (job_pairs.bench_id=bench_attributes.bench_id AND bench_attributes.attr_key = 'starexec-expected-result')
	WHERE ancestor=_jobSpaceId AND jobpair_stage_data.config_id=_configId AND
	(( _stageNumber = 0 AND jobpair_stage_data.stage_number = job_pairs.primary_jobpair_data) OR jobpair_stage_data.stage_number = _stageNumber) AND
	((_type = 'all') OR
	(_type='resource' AND job_pairs.status_code BETWEEN 14 AND 17) OR
	(_type = 'incomplete' AND job_pairs.status_code NOT IN (7, 14, 15, 16, 17, 25, 26)) OR
	(_type='failed' AND job_pairs.status_code IN (8, 9, 10, 11, 12, 13, 18, 24, 25, 26)) OR
	(_type ='complete' AND job_pairs.status_code IN (7, 14, 15, 16, 17, 25, 26)) OR
	(_type = 'unknown' AND jobpair_stage_data.status_code = 7 AND (
		job_attributes.attr_value = 'starexec-unknown' OR
		bench_attributes.attr_value IS NULL OR
		bench_attributes.attr_value = 'starexec-unknown')) OR
	(_type = 'solved' AND jobpair_stage_data.status_code = 7 AND
		bench_attributes.attr_value IS NOT NULL AND
		bench_attributes.attr_value != 'starexec-unknown' AND
		job_attributes.attr_value = bench_attributes.attr_value) OR
	(_type = 'wrong' AND jobpair_stage_data.status_code = 7 AND
		bench_attributes.attr_value IS NOT NULL AND
		bench_attributes.attr_value != 'starexec-unknown' AND
		job_attributes.attr_value IS DISTINCT FROM bench_attributes.attr_value AND
		job_attributes.attr_value IS DISTINCT FROM 'starexec-unknown'))
	AND
	(bench_name LIKE CONCAT('%', _query, '%')
	OR jobpair_stage_data.config_name LIKE CONCAT('%', _query, '%')
	OR jobpair_stage_data.solver_name LIKE CONCAT('%', _query, '%')
	OR jobpair_stage_data.status_code::text LIKE CONCAT('%', _query, '%')
	OR jobpair_stage_data.wallclock::text LIKE CONCAT('%', _query, '%')
	OR jobpair_stage_data.cpu::text LIKE CONCAT('%', _query, '%')
	OR job_attributes.attr_value LIKE CONCAT('%', _query, '%'));

	RETURN count_val;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Jobs PROCEDURES
-- ================================================================================

-- Description: This file contains all job-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds an association between the given job and space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AssociateJob(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AssociateJob(_jobId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
	INSERT INTO job_assoc VALUES (_spaceId, _jobId)
	ON CONFLICT (space_id, job_id) DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of public spaces the job is in
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.JobInPublicSpace(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.JobInPublicSpace(_jobId INT)
RETURNS BIGINT AS $$
DECLARE
	space_count BIGINT;
BEGIN
	SELECT COUNT(*) INTO space_count FROM starexec.job_assoc
	INNER JOIN spaces ON spaces.id=job_assoc.space_id
	WHERE job_id=_jobId AND spaces.public_access=true;
	RETURN space_count;
END;
$$ LANGUAGE plpgsql;

-- Adds a new attribute to a job pair for the given stage
-- Author: Tyler Jensen
DROP ROUTINE IF EXISTS starexec.AddJobAttr(INT, VARCHAR, VARCHAR, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.AddJobAttr(_pairId INT, _key VARCHAR(128), _val VARCHAR(128), _stage INT)
AS $$
BEGIN
	INSERT INTO job_attributes (pair_id, attr_key, attr_value, job_id, stage_number)
	VALUES (_pairId, _key, _val, (SELECT job_id FROM starexec.job_pairs WHERE id=_pairId), _stage)
	ON CONFLICT (pair_id, attr_key, stage_number) DO UPDATE SET
	attr_value = EXCLUDED.attr_value,
	job_id = EXCLUDED.job_id;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs in a given space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetJobCountBySpace(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobCountBySpace(_spaceId INT)
RETURNS BIGINT AS $$
DECLARE
	job_count BIGINT;
BEGIN
	SELECT COUNT(*) INTO job_count
	FROM starexec.job_assoc
	WHERE _spaceId=space_id;
	RETURN job_count;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs in a given space that match a given query
-- Author: Eric burns
DROP FUNCTION IF EXISTS starexec.GetJobCountBySpaceWithQuery(INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobCountBySpaceWithQuery(_spaceId INT, _query TEXT)
RETURNS BIGINT AS $$
DECLARE
	job_count BIGINT;
BEGIN
	SELECT COUNT(*) INTO job_count
	FROM starexec.job_assoc
	JOIN jobs ON jobs.id=job_assoc.job_id
	WHERE _spaceId=job_assoc.space_id
	AND (jobs.name LIKE CONCAT('%', _query, '%')
	OR GetJobStatus(jobs.id) LIKE CONCAT('%', _query, '%'));
	RETURN job_count;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs pairs for a given job in the given job space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairCountInJobSpace(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairCountInJobSpace(_jobSpaceId INT, _stageNumber INT)
RETURNS BIGINT AS $$
DECLARE
	job_pair_count BIGINT;
BEGIN
	IF _stageNumber > 0 THEN
		SELECT COUNT(*) INTO job_pair_count
		FROM starexec.job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND jobpair_stage_data.stage_number=_stageNumber;
	ELSE
		SELECT COUNT(*) INTO job_pair_count
		FROM starexec.job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
	END IF;
	RETURN job_pair_count;
END;
$$ LANGUAGE plpgsql;

-- Counts the number of pairs in a job with a completion index <= the given
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.CountOlderPairs(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountOlderPairs(_id INT, _since INT)
RETURNS BIGINT AS $$
DECLARE
	count_val BIGINT;
BEGIN
	SELECT COUNT(*) INTO count_val
	FROM starexec.job_pairs JOIN job_pair_completion ON id=pair_id
	WHERE completion_id<=_since AND job_id=_id;
	RETURN count_val;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs pairs for a given job that match a given query for the given stage
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairCountByJobInJobSpaceWithQuery(INT, TEXT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairCountByJobInJobSpaceWithQuery(_jobSpaceId INT, _query TEXT, _stageNumber INT)
RETURNS BIGINT AS $$
DECLARE
	job_pair_count BIGINT;
BEGIN
	IF _stageNumber>0 THEN
		SELECT COUNT(*) INTO job_pair_count
		FROM starexec.job_pairs
		JOIN jobpair_stage_data ON (jobpair_stage_data.jobpair_id = job_pairs.id)
		WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND jobpair_stage_data.stage_number = _stageNumber
		AND (bench_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.config_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.solver_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.status_code::text LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.wallclock::text LIKE CONCAT('%', _query, '%'));
	ELSE
		SELECT COUNT(*) INTO job_pair_count
		FROM starexec.job_pairs
		JOIN jobpair_stage_data ON (jobpair_stage_data.jobpair_id = job_pairs.id)
		WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data
		AND (bench_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.config_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.solver_name LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.status_code::text LIKE CONCAT('%', _query, '%')
		OR jobpair_stage_data.wallclock::text LIKE CONCAT('%', _query, '%'));
	END IF;
	RETURN job_pair_count;
END;
$$ LANGUAGE plpgsql;

-- Gets attributes for every pair in a job
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobAttrs(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobAttrs(_jobId INT)
RETURNS TABLE(pair_id INT, attr_key VARCHAR, attr_value VARCHAR, stage_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT pair.id, attr.attr_key, attr.attr_value, attr.stage_number
	FROM starexec.job_pairs AS pair
	LEFT JOIN job_attributes AS attr ON attr.pair_id=pair.id
	WHERE pair.job_id=_jobId;
END;
$$ LANGUAGE plpgsql;

-- Gets the attributes for every job pair of a job completed after the given completion id
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetNewJobAttrs(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNewJobAttrs(_jobId INT, _completionId INT)
RETURNS TABLE(pair_id INT, attr_key VARCHAR, attr_value VARCHAR, stage_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT pair.id, attr.attr_key, attr.attr_value, attr.stage_number
	FROM starexec.job_pairs AS pair
	LEFT JOIN job_attributes AS attr ON attr.pair_id=pair.id
	INNER JOIN job_pair_completion AS complete ON pair.id=complete.pair_id
	WHERE pair.job_id=_jobId AND complete.completion_id>_completionId;
END;
$$ LANGUAGE plpgsql;

-- Adds a new job stats record to the database
-- Author : Eric Burns
DROP FUNCTION IF EXISTS starexec.AddJobStats(INT, INT, INT, INT, INT, INT, INT, DOUBLE PRECISION, DOUBLE PRECISION, INT, INT, INT, BOOLEAN) CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJobStats(_jobSpaceId INT, _configId INT, _complete INT, _correct INT, _incorrect INT, _failed INT, _conflicts INT, _wallclock DOUBLE PRECISION, _cpu DOUBLE PRECISION, _resource INT, _incomplete INT, _stage INT, _includeUnknown BOOLEAN)
RETURNS VOID AS $$
BEGIN
	INSERT INTO job_stats (job_space_id, config_id, complete, correct, incorrect, failed, conflicts, wallclock, cpu, resource_out, incomplete, stage_number, include_unknowns)
	VALUES (_jobSpaceId, _configId, _complete, _correct, _incorrect, _failed, _conflicts, _wallclock, _cpu, _resource, _incomplete, _stage, _includeUnknown)
	ON CONFLICT (job_space_id, config_id, stage_number) DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- this version includes deleted configs; used to construct the solver summary table in the job space view
-- Alexander Brown, 9/20
DROP FUNCTION IF EXISTS starexec.GetJobStatsInJobSpaceIncludeDeletedConfigs(INT, INT, INT, BOOLEAN) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobStatsInJobSpaceIncludeDeletedConfigs(_jobSpaceId INT, _jobId INT, _stageNumber INT, _includeUnknown BOOLEAN)
RETURNS TABLE(job_space_id INT, config_id INT, complete INT, correct INT, incorrect INT, failed INT, conflicts INT, wallclock DOUBLE PRECISION, cpu DOUBLE PRECISION, resource_out INT, incomplete INT, stage_number INT, include_unknowns BOOLEAN, solver_id INT, solver_name VARCHAR, solver_description TEXT, solver_downloadable BOOLEAN, solver_deleted BOOLEAN, solver_upload_date TIMESTAMP, solver_user_id INT, solver_build_status INT, config_name VARCHAR, config_description TEXT, config_contents TEXT, config_deleted BOOLEAN, config_upload_date TIMESTAMP, config_user_id INT, anonymous_solver_name VARCHAR, anonymous_config_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_stats.job_space_id, job_stats.config_id, job_stats.complete, job_stats.correct, job_stats.incorrect, job_stats.failed, job_stats.conflicts, job_stats.wallclock, job_stats.cpu, job_stats.resource_out, job_stats.incomplete, job_stats.stage_number, job_stats.include_unknowns, solver.id, solver.name, solver.description, solver.downloadable, solver.deleted, solver.upload_date, solver.user_id, solver.build_status, config.name, config.description, config.contents, config.deleted, config.upload_date, config.user_id, anonymous_solver_names.anonymous_name, anonymous_config_names.anonymous_name
	FROM starexec.job_stats
	JOIN configurations AS config ON config.id=job_stats.config_id
	JOIN solvers AS solver ON solver.id=config.solver_id
	LEFT JOIN anonymous_primitive_names AS anonymous_solver_names
		ON solver.id=anonymous_solver_names.primitive_id AND anonymous_solver_names.primitive_type='solver'
		AND anonymous_solver_names.job_id=_jobId
	LEFT JOIN anonymous_primitive_names AS anonymous_config_names
		ON config.id=anonymous_config_names.primitive_id AND anonymous_config_names.primitive_type='config'
		AND anonymous_config_names.job_id=_jobId
	WHERE job_stats.job_space_id = _jobSpaceId AND job_stats.stage_number=_stageNumber AND job_stats.include_unknowns=_includeUnknown;
END;
$$ LANGUAGE plpgsql;

-- Clears the entire cache of job stats
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveAllJobStats() CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveAllJobStats()
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.job_stats;
END;
$$ LANGUAGE plpgsql;

-- Removes the cached job results for the hierarchy rooted at the given job space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveJobStatsInJobSpace(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveJobStatsInJobSpace(_jobSpaceId INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.job_stats
	WHERE job_stats.job_space_id = _jobSpaceId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.RemoveJobStatsInJobSpaceForConfig(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveJobStatsInJobSpaceForConfig(_jobSpaceId INT, _configId INT)
RETURNS VOID AS $$
BEGIN
	DELETE FROM starexec.job_stats
	WHERE job_stats.job_space_id = _jobSpaceId
		AND job_stats.config_id = _configId;
END;
$$ LANGUAGE plpgsql;

-- Counts the number of pending pairs in a job
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.CountPendingPairs(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountPendingPairs(_jobId INT)
RETURNS BIGINT AS $$
DECLARE
	pending_count BIGINT;
BEGIN
	SELECT count(*) INTO pending_count FROM starexec.job_pairs
	WHERE status_code IN (1, 2, 4, 19, 20, 22) AND job_id=_jobId;
	RETURN pending_count;
END;
$$ LANGUAGE plpgsql;

-- Retrieves simple overall statistics for job pairs belonging to a job
-- Including the total number of pairs, how many are complete, pending or errored out
-- as well as how long the pairs ran
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetJobPairOverview(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairOverview(_jobId INT)
RETURNS TABLE(totalPairs INT, completePairs BIGINT, pendingPairs BIGINT, errorPairs BIGINT, runtime DOUBLE PRECISION) AS $$
DECLARE
	total_pairs_val INT;
	complete_pairs_val BIGINT;
	pending_pairs_val BIGINT;
	error_pairs_val BIGINT;
	runtime_val DOUBLE PRECISION;
BEGIN
	-- Get total pairs
	SELECT total_pairs INTO total_pairs_val FROM starexec.jobs WHERE id=_jobId;

	-- Get complete pairs (same semantics as GetCompletePairs)
	SELECT COUNT(*) INTO complete_pairs_val
	FROM starexec.job_pairs
	WHERE job_id=_jobId
	AND status_code IN (7, 14, 15, 16, 17, 25, 26);

	-- Get pending pairs
	SELECT COUNT(*) INTO pending_pairs_val
	FROM starexec.job_pairs
	WHERE job_id=_jobId
	AND status_code IN (1, 2, 4, 19, 20, 22);

	-- Get error pairs (mutually exclusive with complete pairs by excluding resource-limit completions 14-17)
	SELECT COUNT(*) INTO error_pairs_val
	FROM starexec.job_pairs
	WHERE job_id=_jobId
	AND status_code IN (8, 9, 10, 11, 12, 13, 18, 24, 25, 26);

	-- Calculate runtime (difference between earliest completed pair's start time and latest completed pair's end time)
	SELECT EXTRACT(EPOCH FROM (MAX(end_time) - MIN(start_time))) * 1000000 INTO runtime_val
	FROM starexec.job_pairs WHERE job_id=_jobId AND status_code IN (7, 14, 15, 16, 17, 25, 26);

	RETURN QUERY SELECT total_pairs_val, complete_pairs_val, pending_pairs_val, error_pairs_val, runtime_val;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about a job from the jobs table
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetJobById(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobById(_id INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR, description TEXT, queue_id INT, primary_space INT, seed BIGINT, cpuTimeout INT, clockTimeout INT, maximum_memory BIGINT, paused BOOLEAN, killed BOOLEAN, created TIMESTAMP, completed TIMESTAMP, deleted BOOLEAN, suppress_timestamp BOOLEAN, using_dependencies BOOLEAN, buildJob BOOLEAN, total_pairs INT, soft_time_limit INT, kill_delay INT, disk_size BIGINT, benchmarking_framework VARCHAR, is_high_priority BOOLEAN, output_benchmarks_directory_path TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT jobs.id, jobs.user_id, jobs.name, jobs.description, jobs.queue_id, jobs.primary_space, jobs.seed, jobs.cpuTimeout, jobs.clockTimeout, jobs.maximum_memory, jobs.paused, jobs.killed, jobs.created, jobs.completed, jobs.deleted, jobs.suppress_timestamp, jobs.using_dependencies, jobs.buildJob, jobs.total_pairs, jobs.soft_time_limit, jobs.kill_delay, jobs.disk_size, jobs.benchmarking_framework, jobs.is_high_priority, jobs.output_benchmarks_directory_path
    FROM starexec.jobs
    WHERE jobs.id = _id AND jobs.deleted = false;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetHighPriority(INT, BOOLEAN) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetHighPriority(_jobId INT, _isHighPriority BOOLEAN)
RETURNS VOID AS $$
BEGIN
	UPDATE jobs
	SET is_high_priority=_isHighPriority
	WHERE id=_jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetOutputBenchmarksPath(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetOutputBenchmarksPath(_jobId INT)
RETURNS VARCHAR AS $$
DECLARE
	output_path VARCHAR;
BEGIN
	SELECT output_benchmarks_directory_path INTO output_path
	FROM starexec.jobs
	WHERE id=_jobId;
	RETURN output_path;
END;
$$ LANGUAGE plpgsql;
DROP FUNCTION IF EXISTS starexec.SetOutputBenchmarksPath(INT, TEXT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetOutputBenchmarksPath(_jobId INT, _path TEXT)
RETURNS VOID AS $$
BEGIN
	UPDATE jobs
	SET output_benchmarks_directory_path=_path
	WHERE id=_jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about a job from the jobs table
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetJobByIdIncludeDeleted(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobByIdIncludeDeleted(_id INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR, description TEXT, queue_id INT, primary_space INT, seed BIGINT, cpuTimeout INT, clockTimeout INT, maximum_memory BIGINT, paused BOOLEAN, killed BOOLEAN, created TIMESTAMP, completed TIMESTAMP, deleted BOOLEAN, suppress_timestamp BOOLEAN, using_dependencies BOOLEAN, buildJob BOOLEAN, total_pairs INT, soft_time_limit INT, kill_delay INT, disk_size BIGINT, benchmarking_framework VARCHAR, is_high_priority BOOLEAN, output_benchmarks_directory_path TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT jobs.id, jobs.user_id, jobs.name, jobs.description, jobs.queue_id, jobs.primary_space, jobs.seed, jobs.cpuTimeout, jobs.clockTimeout, jobs.maximum_memory, jobs.paused, jobs.killed, jobs.created, jobs.completed, jobs.deleted, jobs.suppress_timestamp, jobs.using_dependencies, jobs.buildJob, jobs.total_pairs, jobs.soft_time_limit, jobs.kill_delay, jobs.disk_size, jobs.benchmarking_framework, jobs.is_high_priority, jobs.output_benchmarks_directory_path
    FROM starexec.jobs
    WHERE jobs.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about job pairs for the given job id (simple version). Gets only the primary stage
-- Author: Julio Cervantes
DROP FUNCTION IF EXISTS starexec.GetJobPairsByJobSimple(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsByJobSimple(_id INT)
RETURNS TABLE(pair_id INT, pair_job_space_id INT, path VARCHAR, solver_name VARCHAR, solver_id INT, config_name VARCHAR, config_id INT, bench_name VARCHAR, bench_id INT, pipeline_name VARCHAR, job_space_name VARCHAR, status_code SMALLINT, job_space_id_dup INT, pipeline_id INT, stage_number INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_space_id, job_pairs.path, jobpair_stage_data.solver_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_name,
	jobpair_stage_data.config_id, job_pairs.bench_name, job_pairs.bench_id, solver_pipelines.name,
	job_spaces.name, job_pairs.status_code, job_spaces.id, pipeline_stages.pipeline_id, jobpair_stage_data.stage_number
	FROM starexec.job_pairs
	JOIN job_spaces ON job_spaces.id=job_pairs.job_space_id
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
	LEFT JOIN pipeline_stages ON pipeline_stages.stage_id = jobpair_stage_data.stage_id
	LEFT JOIN solver_pipelines ON pipeline_stages.pipeline_id = solver_pipelines.id
	WHERE job_pairs.job_id=_id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about job pairs for the given job id
-- Author: Tyler Jensen
-- NOTE: `config.deleted` may still be stored as INT during the boolean migration (V0016)
-- casting it here keeps the repeated procedures compatible with both column types.
DROP FUNCTION IF EXISTS starexec.GetJobPairsPrimaryStageByJob(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsPrimaryStageByJob(_id INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT, stage_number INT, stage_id INT, node_name VARCHAR, node_status VARCHAR, job_space_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data, jobpair_stage_data.stage_number, jobpair_stage_data.stage_id, node.name, node.status, jobSpace.name
	FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	JOIN configurations AS config ON jobpair_stage_data.config_id = config.id
	LEFT JOIN nodes AS node ON job_pairs.node_id=node.id
	LEFT JOIN job_spaces AS jobSpace ON jobSpace.id=job_pairs.job_space_id
    WHERE job_pairs.job_id=_id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data
    AND (config.deleted::BOOLEAN IS FALSE)
	ORDER BY job_pairs.end_time DESC;
END;
$$ LANGUAGE plpgsql;




-- Counts the entries in the job space closure table with the given ancestor and updates their last_used time
-- Author: Eric Burns
-- Note: Returns 0 if no entries exist for the ancestor (this is a valid case, not an error)
DROP FUNCTION IF EXISTS starexec.RefreshEntriesByAncestor(INT, TIMESTAMP) CASCADE;
CREATE OR REPLACE FUNCTION starexec.RefreshEntriesByAncestor(_id INT, _time TIMESTAMP)
RETURNS BIGINT AS $$
DECLARE
	count_val BIGINT;
BEGIN
	UPDATE job_space_closure
	SET last_used=_time
	WHERE ancestor=_id;
	-- Note: NOT FOUND is acceptable - ancestor may not have entries yet

	SELECT COUNT(*) INTO count_val
	FROM starexec.job_space_closure
	WHERE ancestor=_id;

	RETURN count_val;
END;
$$ LANGUAGE plpgsql;

-- Gets all the attribute values for benchmarks in the given job
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetAttrsOfNameForJob(INT, VARCHAR) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAttrsOfNameForJob(_jobId INT, _attrName VARCHAR(128))
RETURNS TABLE(bench_id INT, attr_value VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.bench_id, bench_attributes.attr_value
	FROM starexec.job_pairs JOIN bench_attributes ON job_pairs.bench_id = bench_attributes.bench_id
	WHERE bench_attributes.attr_key=_attrName AND job_pairs.job_id=_jobId;
END;
$$ LANGUAGE plpgsql;

-- Gets all the job pairs in a job space. No stages are retrieved
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairsInJobSpace(INT, INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsInJobSpace(_jobSpaceId INT, _jobId INT, _stageNumber INT)
RETURNS TABLE(status_code SMALLINT, id INT, bench_id INT, bench_name VARCHAR, completion_id INT, solver_id INT, solver_name VARCHAR, stage_status_code SMALLINT, config_id INT, config_name VARCHAR, cpu DOUBLE PRECISION, stage_id INT, wallclock DOUBLE PRECISION, primary_jobpair_data INT, path VARCHAR, anon_solver_name VARCHAR, anon_config_name VARCHAR, anon_bench_name VARCHAR, result VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.status_code,
	job_pairs.id, job_pairs.bench_id, job_pairs.bench_name,
	job_pair_completion.completion_id, jobpair_stage_data.solver_id, jobpair_stage_data.solver_name, jobpair_stage_data.status_code,
	jobpair_stage_data.config_id, jobpair_stage_data.config_name, jobpair_stage_data.cpu, jobpair_stage_data.stage_id,
	jobpair_stage_data.wallclock, job_pairs.primary_jobpair_data, job_pairs.path,
	anonymous_solver_names.anonymous_name,
	anonymous_config_names.anonymous_name,
	anonymous_bench_names.anonymous_name,
	job_attributes.attr_value
	FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key='starexec-result')
	LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id
	LEFT JOIN anonymous_primitive_names AS anonymous_solver_names ON
		anonymous_solver_names.primitive_id=jobpair_stage_data.solver_id AND anonymous_solver_names.primitive_type='solver'
		AND anonymous_solver_names.job_id = _jobId
	LEFT JOIN anonymous_primitive_names AS anonymous_config_names ON
		anonymous_config_names.primitive_id=jobpair_stage_data.config_id AND anonymous_config_names.primitive_type='config'
		AND anonymous_config_names.job_id = _jobId
	LEFT JOIN anonymous_primitive_names AS anonymous_bench_names ON
		anonymous_bench_names.primitive_id=job_pairs.bench_id AND anonymous_bench_names.primitive_type='bench'
		AND anonymous_bench_names.job_id = _jobId
	WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND
	(jobpair_stage_data.stage_number=_stageNumber OR (_stageNumber = 0 AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number));
END;
$$ LANGUAGE plpgsql;

-- Gets all the job pairs in a job space hierarchy. No stages are retrieved
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairsInJobSpaceHierarchy(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsInJobSpaceHierarchy(_jobSpaceId INT, _since INT)
RETURNS TABLE(status_code SMALLINT, id INT, bench_id INT, bench_name VARCHAR, anon_bench_name VARCHAR, path VARCHAR, completion_id INT, primary_jobpair_data INT) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.status_code,
	job_pairs.id,
	job_pairs.bench_id,
	job_pairs.bench_name,
	anonymous_primitive_names.anonymous_name,
	job_pairs.path,
	job_pair_completion.completion_id,
	job_pair_completion.primary_jobpair_data
	FROM starexec.job_pairs
	JOIN job_spaces ON job_spaces.id = job_pairs.job_space_id
	LEFT JOIN anonymous_primitive_names ON
		anonymous_primitive_names.primitive_id=job_pairs.bench_id AND anonymous_primitive_names.primitive_type='bench'
		AND anonymous_primitive_names.job_id=job_spaces.job_id
	JOIN job_space_closure ON descendant=job_space_id
	LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id
	WHERE ancestor=_jobSpaceId AND ((_since IS NULL) OR job_pair_completion.completion_id>_since);
END;
$$ LANGUAGE plpgsql;

-- Gets all the stages of job pairs in a particular job space
DROP FUNCTION IF EXISTS starexec.GetJobPairStagesInJobSpace(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairStagesInJobSpace(_jobSpaceId INT)
RETURNS TABLE(pair_id INT, solver_id INT, solver_name VARCHAR, status_code SMALLINT, config_id INT, config_name VARCHAR, cpu DOUBLE PRECISION, stage_id INT, wallclock DOUBLE PRECISION, id_dup INT, result VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id AS pair_id, jobpair_stage_data.solver_id, jobpair_stage_data.solver_name, jobpair_stage_data.status_code,
	jobpair_stage_data.config_id, jobpair_stage_data.config_name, jobpair_stage_data.cpu, jobpair_stage_data.stage_id,
	jobpair_stage_data.wallclock, job_pairs.id,
	job_attributes.attr_value AS result
	FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key='starexec-result')
	WHERE job_space_id=_jobSpaceId;
END;
$$ LANGUAGE plpgsql;

-- Gets all the stages of job pairs in a particular job space
DROP FUNCTION IF EXISTS starexec.GetJobPairStagesInJobSpaceHierarchy(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairStagesInJobSpaceHierarchy(_jobSpaceId INT, _since INT)
RETURNS TABLE(pair_id INT, solver_id INT, solver_name VARCHAR, status_code SMALLINT, config_id INT, config_name VARCHAR, cpu DOUBLE PRECISION, stage_id INT, wallclock DOUBLE PRECISION, id_dup INT, stage_number INT, max_vmem DOUBLE PRECISION, expected VARCHAR, result VARCHAR, anon_solver_name VARCHAR, anon_config_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT
	job_pairs.id AS pair_id,
	jobpair_stage_data.solver_id,
	jobpair_stage_data.solver_name,
	jobpair_stage_data.status_code,
	jobpair_stage_data.config_id,
	jobpair_stage_data.config_name,
	jobpair_stage_data.cpu,
	jobpair_stage_data.stage_id,
	jobpair_stage_data.wallclock,
	job_pairs.id, jobpair_stage_data.stage_number,
	jobpair_stage_data.max_vmem,
	bench_attributes.attr_value AS expected,
	job_attributes.attr_value AS result,
	anonymous_solver_names.anonymous_name,
	anonymous_config_names.anonymous_name
	FROM starexec.job_pairs
	JOIN job_spaces ON job_spaces.id=job_pairs.job_space_id
	JOIN job_space_closure ON descendant=job_space_id
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	LEFT JOIN anonymous_primitive_names AS anonymous_solver_names ON
		anonymous_solver_names.primitive_id=jobpair_stage_data.solver_id AND anonymous_solver_names.primitive_type='solver'
		AND anonymous_solver_names.job_id = job_spaces.job_id
	LEFT JOIN anonymous_primitive_names AS anonymous_config_names ON
		anonymous_config_names.primitive_id=jobpair_stage_data.config_id AND anonymous_config_names.primitive_type='config'
		AND anonymous_config_names.job_id = job_spaces.job_id
	LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key='starexec-result')
	LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id
	LEFT JOIN bench_attributes ON (job_pairs.bench_id=bench_attributes.bench_id AND bench_attributes.attr_key = 'starexec-expected-result')
	WHERE ancestor=_jobSpaceId AND ((_since IS NULL) OR job_pair_completion.completion_id>_since);
END;
$$ LANGUAGE plpgsql;

-- Counts the number of pairs in a job
-- Author Eric Burns
DROP FUNCTION IF EXISTS starexec.CountPairsForJob(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountPairsForJob(_id INT)
RETURNS BIGINT AS $$
DECLARE
	count_val BIGINT;
BEGIN
	SELECT COUNT(*) INTO count_val
	FROM starexec.job_pairs
	WHERE job_id=_id;
	RETURN count_val;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllJobPairsByJob(INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllJobPairsByJob(_id INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT, stage_number INT, stage_id INT, node_name VARCHAR, node_status VARCHAR, job_space_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data, jobpair_stage_data.stage_number, jobpair_stage_data.stage_id, node.name, node.status, jobSpace.name
	FROM starexec.job_pairs
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	JOIN configurations AS config ON jobpair_stage_data.config_id = config.id
	LEFT JOIN nodes AS node ON job_pairs.node_id=node.id
	LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
    WHERE job_pairs.job_id=_id AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number
    AND (config.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about job pairs for the given job id for pairs completed after _completionId
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetNewCompletedJobPairsByJob(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNewCompletedJobPairsByJob(_id INT, _completionId INT)
RETURNS TABLE(id INT, job_id INT, bench_id INT, status_code SMALLINT, node_id INT, job_space_id INT, path VARCHAR, bench_name VARCHAR, solver_name VARCHAR, config_name VARCHAR, solver_id INT, config_id INT, start_time TIMESTAMP, end_time TIMESTAMP, cpu DOUBLE PRECISION, wallclock DOUBLE PRECISION, user_time DOUBLE PRECISION, system_time DOUBLE PRECISION, max_vmem DOUBLE PRECISION, max_res_set BIGINT, disk_size BIGINT, sge_id INT, sandbox_num INT, queuesub_time TIMESTAMP, primary_jobpair_data INT, completion_id INT, stage_number INT, stage_id INT, node_name VARCHAR, node_status VARCHAR, job_space_name VARCHAR) AS $$
BEGIN
	RETURN QUERY
	SELECT job_pairs.id, job_pairs.job_id, job_pairs.bench_id, job_pairs.status_code, job_pairs.node_id, job_pairs.job_space_id, job_pairs.path, job_pairs.bench_name, jobpair_stage_data.solver_name, jobpair_stage_data.config_name, jobpair_stage_data.solver_id, jobpair_stage_data.config_id, job_pairs.start_time, job_pairs.end_time, jobpair_stage_data.cpu, jobpair_stage_data.wallclock, jobpair_stage_data.user_time, jobpair_stage_data.system_time, jobpair_stage_data.max_vmem, CAST(jobpair_stage_data.max_res_set AS BIGINT), jobpair_stage_data.disk_size, job_pairs.sge_id, job_pairs.sandbox_num, job_pairs.queuesub_time, job_pairs.primary_jobpair_data, complete.completion_id, jobpair_stage_data.stage_number, jobpair_stage_data.stage_id, node.name, node.status, jobSpace.name
	FROM starexec.job_pairs
	JOIN job_pair_completion AS complete ON job_pairs.id=complete.pair_id
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
	JOIN configurations AS config ON jobpair_stage_data.config_id = config.id
	LEFT JOIN nodes AS node ON job_pairs.node_id=node.id
	LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
    WHERE job_pairs.job_id=_id AND complete.completion_id>_completionId AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number
    AND (config.deleted::BOOLEAN IS FALSE)
	ORDER BY job_pairs.end_time DESC;
END;
$$ LANGUAGE plpgsql;


-- Retrieves ids for job pairs with a given status in a given job
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobPairsByStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobPairsByStatus(_jobId INT, _statusCode INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.id FROM starexec.job_pairs jp
    WHERE jp.job_id=_jobId AND jp.status_code=_statusCode ORDER BY jp.id ASC;
END;
$$ LANGUAGE plpgsql;

-- Retrieves ids for job pairs in a given job where either cpu or wallclock is 0 for any stage that has the given status code
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetTimelessJobPairsByStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetTimelessJobPairsByStatus(_jobId INT, _statusCode INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT jp.id FROM starexec.job_pairs jp
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id=jp.id
    WHERE jp.job_id=_jobId AND jsd.status_code=_statusCode AND (jsd.cpu=0 OR jsd.wallclock=0);
END;
$$ LANGUAGE plpgsql;

-- Retrieves information for pending job pairs with the given job id. Returns all stages for _limit pairs.
-- Excludes any job pairs that are utilizing solvers that have still not been built
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetPendingJobPairsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPendingJobPairsByJob(_id INT, _limit INT)
RETURNS TABLE(
    id INT,
    job_id INT,
    sge_id INT,
    bench_id INT,
    bench_name VARCHAR(255),
    status_code SMALLINT,
    node_id INT,
    queuesub_time TIMESTAMP,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    job_space_id INT,
    path VARCHAR(2048),
    sandbox_num INT,
    primary_jobpair_data INT,
    stage_number INT,
    jobpair_id_stage INT,
    stage_id INT,
    cpu DOUBLE PRECISION,
    wallclock DOUBLE PRECISION,
    max_vmem DOUBLE PRECISION,
    max_res_set DOUBLE PRECISION,
    user_time DOUBLE PRECISION,
    system_time DOUBLE PRECISION,
    status_code_stage SMALLINT,
    solver_name VARCHAR(128),
    config_name VARCHAR(128),
    solver_id INT,
    config_id INT,
    job_space_id_stage INT,
    disk_size BIGINT,
    dependency_count BIGINT,
    user_id INT,
    benchmarks_id INT,
    benchmarks_user_id INT,
    benchmarks_name VARCHAR(256),
    benchmarks_uploaded TIMESTAMP,
    benchmarks_path TEXT,
    benchmarks_description TEXT,
    benchmarks_downloadable BOOLEAN,
    benchmarks_disk_size BIGINT,
    benchmarks_recycled BOOLEAN,
    benchmarks_deleted BOOLEAN,
    "solvers.id" INT,
    "solvers.name" VARCHAR(255),
    "solvers.description" TEXT,
    "solvers.disk_size" BIGINT,
    "solvers.path" TEXT,
    "solvers.downloadable" BOOLEAN,
    "solvers.uploaded" TIMESTAMP,
    "solvers.user_id" INT,
    "solvers.recycled" BOOLEAN,
    "solvers.deleted" BOOLEAN,
    executable_type INT,
    recycled BOOLEAN,
    deleted BOOLEAN,
    build_status INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        jp.id, jp.job_id, jp.sge_id, jp.bench_id, jp.bench_name, jp.status_code, jp.node_id,
        jp.queuesub_time, jp.start_time, jp.end_time, jp.job_space_id, jp.path, jp.sandbox_num, jp.primary_jobpair_data,
        jsd.stage_number, jsd.jobpair_id, jsd.stage_id, jsd.cpu, jsd.wallclock, jsd.max_vmem, jsd.max_res_set,
        jsd.user_time, jsd.system_time, jsd.status_code, jsd.solver_name, jsd.config_name, jsd.solver_id, jsd.config_id, jsd.job_space_id, jsd.disk_size,
        (SELECT count(*)::BIGINT FROM starexec.bench_dependency WHERE primary_bench_id = b.id) AS dependency_count,
        b.user_id,
        b.id AS benchmarks_id,
        b.user_id AS benchmarks_user_id,
        b.name AS benchmarks_name,
        b.uploaded AS benchmarks_uploaded,
        b.path AS benchmarks_path,
        b.description AS benchmarks_description,
        b.downloadable AS benchmarks_downloadable,
        b.disk_size AS benchmarks_disk_size,
        b.recycled AS benchmarks_recycled,
        b.deleted AS benchmarks_deleted,
        s.id AS "solvers.id",
        s.name AS "solvers.name",
        s.description AS "solvers.description",
        s.disk_size AS "solvers.disk_size",
        s.path AS "solvers.path",
        s.downloadable AS "solvers.downloadable",
        s.uploaded AS "solvers.uploaded",
        s.user_id AS "solvers.user_id",
        s.recycled AS "solvers.recycled",
        s.deleted AS "solvers.deleted",
        s.executable_type,
        s.recycled,
        s.deleted,
        s.build_status
    FROM starexec.job_pairs jp
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    LEFT JOIN benchmarks b ON b.id = jp.bench_id
    LEFT JOIN solvers s ON s.id = jsd.solver_id
    JOIN (SELECT DISTINCT jp2.id FROM starexec.job_pairs jp2
    WHERE jp2.job_id = _id AND jp2.status_code = 1
    AND NOT EXISTS (SELECT 1 FROM starexec.jobpair_stage_data jsd2
    LEFT JOIN solvers s2 ON s2.id = jsd2.solver_id
    JOIN job_pairs jp3 ON jp3.id=jsd2.jobpair_id
    JOIN jobs j ON j.id=jp3.job_id
    WHERE jsd2.jobpair_id = jp2.id AND s2.build_status=0 AND j.buildJob=false)
    ORDER BY jp2.id ASC LIMIT _limit) AS temp
    ON temp.id=jp.id;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about enqueued job pairs for the given job id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetEnqueuedJobPairsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetEnqueuedJobPairsByJob(_id INT)
RETURNS TABLE(id INT, sge_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.id, jp.sge_id
    FROM starexec.job_pairs jp
    WHERE (jp.job_id = _id AND jp.status_code = 2)
    ORDER BY jp.sge_id ASC;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about running job pairs for the given job id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetRunningJobPairsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunningJobPairsByJob(_id INT)
RETURNS TABLE(id INT, sge_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.id, jp.sge_id
    FROM starexec.job_pairs jp
    WHERE (jp.job_id = _id AND jp.status_code = 4)
    ORDER BY jp.sge_id ASC;
END;
$$ LANGUAGE plpgsql;

-- Returns true if the job in question has the deleted flag set as true
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsJobDeleted CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsJobDeleted(_jobId INT)
RETURNS TABLE(jobDeleted BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT count(*)::BIGINT AS jobDeleted
    FROM starexec.jobs j
    WHERE j.deleted=true AND j.id=_jobId;
END;
$$ LANGUAGE plpgsql;

-- Returns the paused and deleted columns for a job
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsJobPausedOrKilled CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsJobPausedOrKilled(_jobId INT)
RETURNS TABLE(paused BOOLEAN, killed BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT j.paused, j.killed
    FROM starexec.jobs j
    WHERE j.id=_jobId;
END;
$$ LANGUAGE plpgsql;


-- Sets the "deleted" property of a job to true
-- Also updates the total_pairs and disk_size columns to 0
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.DeleteJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteJob(_jobId INT)
RETURNS VOID AS $$
DECLARE
    _userId INT;
    _diskSize BIGINT;
BEGIN
    SELECT user_id, disk_size INTO _userId, _diskSize
    FROM starexec.jobs
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    UPDATE users
    SET disk_size = disk_size - _diskSize
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s for job %s not found', _userId, _jobId);
    END IF;

    UPDATE jobs SET deleted = true, total_pairs = 0, disk_size = 0
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UpdateJobDiskSize CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateJobDiskSize(_jobId INT, _diskSize BIGINT)
RETURNS VOID AS $$
DECLARE
    _userId INT;
    _oldDiskSize BIGINT;
BEGIN
    SELECT user_id, disk_size INTO _userId, _oldDiskSize
    FROM starexec.jobs
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    UPDATE users
    SET disk_size = (disk_size - _oldDiskSize) + _diskSize
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s for job %s not found', _userId, _jobId);
    END IF;

    UPDATE jobs SET disk_size = _diskSize
    WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Deletes every job pair belonging to the given job
DROP FUNCTION IF EXISTS starexec.DeleteAllJobPairsInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteAllJobPairsInJob(_jobId INT)
RETURNS VOID AS $$
BEGIN
    PERFORM 1 FROM starexec.jobs WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    DELETE FROM starexec.job_pairs WHERE job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetOrphanedJobIds CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetOrphanedJobIds(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id FROM starexec.jobs j
    LEFT JOIN job_assoc ja ON ja.job_id = j.id
    WHERE j.user_id = _userId AND ja.space_id IS NULL;
END;
$$ LANGUAGE plpgsql;

-- Sets the "paused" property of a job to true
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.PauseJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.PauseJob(_jobId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET paused = true WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
    UPDATE job_pairs SET status_code = 20 WHERE job_id = _jobId AND status_code = 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s has no pending pairs to pause', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the global paused flag to true
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.PauseAll CASCADE;
CREATE OR REPLACE FUNCTION starexec.PauseAll()
RETURNS VOID AS $$
BEGIN
    UPDATE system_flags SET paused = true;
END;
$$ LANGUAGE plpgsql;

-- Sets the "paused" property of a job to false
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.ResumeJob CASCADE;
-- Resumes a job by setting its paused flag to false and resuming any paused job pairs.
-- If the job has no paused pairs, the operation completes silently without error.
-- This behavior change was made to prevent exceptions when resuming jobs that were
-- never actually paused or have no pairs in paused state.
CREATE OR REPLACE FUNCTION starexec.ResumeJob(_jobId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET paused = false WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
    -- job_pairs first, then jobpair_stage_data. The two updates select on different
    -- columns (jp.status_code vs jsd.status_code) so neither depends on the other's
    -- effect; the order is chosen to match every other routine that locks both tables,
    -- so that none of them can deadlock against another.
    UPDATE job_pairs
    SET status_code = 1
    WHERE job_id = _jobId AND status_code = 20;
    UPDATE jobpair_stage_data jsd SET status_code = 1
    FROM starexec.job_pairs jp
    WHERE jp.id = jsd.jobpair_id AND jp.job_id = _jobId AND jsd.status_code = 20;
END;
$$ LANGUAGE plpgsql;

-- sets the global paused flag to false
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.ResumeAll CASCADE;
CREATE OR REPLACE FUNCTION starexec.ResumeAll()
RETURNS VOID AS $$
BEGIN
    UPDATE system_flags SET paused = false;
END;
$$ LANGUAGE plpgsql;

-- Sets the "killed" property of a job to true
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.KillJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.KillJob(_jobId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET killed = true WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
    UPDATE jobs SET paused = false WHERE id = _jobId;
    UPDATE job_pairs SET status_code = 21 WHERE job_id = _jobId AND (status_code = 1 OR status_code = 20);
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s has no running or paused pairs to kill', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Changes the queueid in the jobs datatable
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.ChangeQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.ChangeQueue(_jobId INT, _queueId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET queue_id = _queueId WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Adds a new job pair record to the database
-- Author: Tyler Jensen + Eric Burns
DROP FUNCTION IF EXISTS starexec.AddJobPair CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJobPair(_jobId INT, _benchId INT, _status SMALLINT, _path VARCHAR(2048), _jobSpaceId INT, _benchName VARCHAR(256), _stageNumber INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO job_pairs (job_id, bench_id, status_code, path, job_space_id, bench_name, primary_jobpair_data)
    VALUES (_jobId, _benchId, _status, _path, _jobSpaceId, _benchName, _stageNumber)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.AddJobPairStage CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJobPairStage(_pairId INT, _stageId INT, _stageNumber INT, _primary BOOLEAN, _solverId INT, _solverName VARCHAR(255), _configId INT, _configName VARCHAR(255), _jobSpace INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO jobpair_stage_data (jobpair_id, stage_id, stage_number, solver_id, solver_name, config_id, config_name, job_space_id, status_code, disk_size)
    VALUES (_pairId, _stageId, _stageNumber, _solverId, _solverName, _configId, _configName, _jobSpace, 1, 0);
END;
$$ LANGUAGE plpgsql;

-- Adds a new job record to the database
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJob(
    _userId INT,
    _name VARCHAR(64),
    _desc TEXT,
    _queueId INT,
    _spaceId INT,
    _seed BIGINT,
    _cpu INT,
    _wall INT,
    _softTimeLimit INT,
    _killDelay INT,
    _mem BIGINT,
    _suppressTimestamp BOOLEAN,
    _usingDeps BOOLEAN,
    _buildJob BOOLEAN,
    _totalPairs INT,
    _benchmarkingFramework VARCHAR(32)
)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO jobs (user_id, name, description, queue_id, primary_space,
            seed, cpuTimeout, clockTimeout, maximum_memory, paused,
            suppress_timestamp, using_dependencies, buildJob, total_pairs,
            soft_time_limit, kill_delay, disk_size, benchmarking_framework,
            completed_pairs, errored_pairs, pending_pairs, status_code,
            max_stages, job_type, suppress_output, node_queued)
    VALUES (_userId, _name, _desc, _queueId, _spaceId,
            _seed, _cpu, _wall, _mem, true,
            _suppressTimestamp, _usingDeps, _buildJob, _totalPairs,
            _softTimeLimit, _killDelay, 0, _benchmarkingFramework,
            0, 0, 0, 0, 1, 0, false, false)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all jobs belonging to a user (but not their job pairs)
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.GetUserJobsById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserJobsById(_userId INT)
RETURNS TABLE(
    id INT,
    user_id INT,
    name VARCHAR(64),
    description TEXT,
    queue_id INT,
    primary_space INT,
    created TIMESTAMP,
    completed TIMESTAMP,
    seed BIGINT,
    cpuTimeout INT,
    clockTimeout INT,
    maximum_memory BIGINT,
    paused BOOLEAN,
    killed BOOLEAN,
    suppress_timestamp BOOLEAN,
    using_dependencies BOOLEAN,
    buildJob BOOLEAN,
    total_pairs INT,
    soft_time_limit INT,
    kill_delay INT,
    disk_size BIGINT,
    deleted BOOLEAN,
    benchmarking_framework VARCHAR(32),
    is_high_priority BOOLEAN,
    output_benchmarks_directory_path TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT jobs.id, jobs.user_id, jobs.name, jobs.description, jobs.queue_id, jobs.primary_space, jobs.created, jobs.completed, jobs.seed, jobs.cputimeout, jobs.clocktimeout, jobs.maximum_memory, jobs.paused, jobs.killed, jobs.suppress_timestamp, jobs.using_dependencies, jobs.buildjob, jobs.total_pairs, jobs.soft_time_limit, jobs.kill_delay, jobs.disk_size, jobs.deleted, jobs.benchmarking_framework, jobs.is_high_priority, jobs.output_benchmarks_directory_path
    FROM starexec.jobs
    WHERE jobs.user_id = _userId AND jobs.deleted = false
    ORDER BY jobs.created DESC;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetQueueJobsById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetQueueJobsById(_queueId INT)
RETURNS TABLE(
    id INT,
    user_id INT,
    name VARCHAR(64),
    description TEXT,
    queue_id INT,
    primary_space INT,
    created TIMESTAMP,
    completed TIMESTAMP,
    seed BIGINT,
    cpuTimeout INT,
    clockTimeout INT,
    maximum_memory BIGINT,
    paused BOOLEAN,
    killed BOOLEAN,
    suppress_timestamp BOOLEAN,
    using_dependencies BOOLEAN,
    buildJob BOOLEAN,
    total_pairs INT,
    soft_time_limit INT,
    kill_delay INT,
    disk_size BIGINT,
    deleted BOOLEAN,
    benchmarking_framework VARCHAR(32),
    is_high_priority BOOLEAN,
    output_benchmarks_directory_path TEXT,
    totalPairs BIGINT,
    completePairs BIGINT,
    pendingPairs BIGINT,
    errorPairs BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id,
        j.user_id,
        j.name,
        j.description,
        j.queue_id,
        j.primary_space,
        j.created,
        j.completed,
        j.seed,
        j.cpuTimeout,
        j.clockTimeout,
        j.maximum_memory,
        j.paused,
        j.killed,
        j.suppress_timestamp,
        j.using_dependencies,
        j.buildJob,
        j.total_pairs,
        j.soft_time_limit,
        j.kill_delay,
        j.disk_size,
        j.deleted,
        j.benchmarking_framework,
        j.is_high_priority,
        j.output_benchmarks_directory_path,
        CAST(j.total_pairs AS BIGINT) AS totalPairs,
        GetCompletePairs(j.id) AS completePairs,
        GetPendingPairs(j.id) AS pendingPairs,
        GetErrorPairs(j.id) AS errorPairs
    FROM starexec.jobs j
    WHERE j.queue_id = _queueId
      AND j.id IN (SELECT DISTINCT jp.job_id FROM starexec.job_pairs jp WHERE jp.status_code IN (1, 2, 4, 19, 20, 22))
      AND NOT j.paused
      AND NOT j.killed
    ORDER BY j.created DESC;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs in the entire system
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetJobCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobCount()
RETURNS TABLE(jobCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS jobCount FROM starexec.jobs;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of running jobs in the entire system
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetRunningJobCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunningJobCount()
RETURNS TABLE(jobCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT j.id)::BIGINT AS jobCount
    FROM starexec.jobs j
    JOIN job_pairs jp ON j.id = jp.job_id
    WHERE (jp.status_code < 7 OR jp.status_code BETWEEN 19 AND 22);
END;
$$ LANGUAGE plpgsql;

-- Returns the number of paused jobs in the entire system
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetPausedJobCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPausedJobCount()
RETURNS TABLE(jobCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT j.id)::BIGINT AS jobCount
    FROM starexec.jobs j
    JOIN job_pairs jp ON j.id = jp.job_id
    WHERE jp.status_code = 20;
END;
$$ LANGUAGE plpgsql;

-- Get the total count of the jobs belong to a specific user
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.GetJobCountByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobCountByUser(_userId INT)
RETURNS TABLE(jobCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS jobCount
    FROM starexec.jobs j
    WHERE j.user_id = _userId AND j.deleted = false;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of jobs in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobCountByUserWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobCountByUserWithQuery(_userId INT, _query TEXT)
RETURNS TABLE(jobCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS jobCount
    FROM starexec.jobs j
    WHERE j.user_id = _userId AND j.deleted = false AND
        (j.name LIKE CONCAT('%', _query, '%')
        OR GetJobStatus(j.id) LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;
DROP FUNCTION IF EXISTS starexec.GetNameofJobById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNameofJobById(_jobId INT)
RETURNS TABLE(name VARCHAR(64)) AS $$
BEGIN
    RETURN QUERY
    SELECT j.name
    FROM starexec.jobs j
    WHERE j.id = _jobId AND j.deleted = false;
END;
$$ LANGUAGE plpgsql;

-- Sets the primary space of a job to a new space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.UpdatePrimarySpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdatePrimarySpace(_jobId INT, _jobSpaceId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET primary_space = _jobSpaceId WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Populates the solver_name, config_name, and bench_name columns of all pairs in the
-- job_pair table. Should only need to be run once on Starexec and Stardev to get the table
-- up to date
-- Author: Eric Burns
-- NOTE: This procedure populates denormalized columns in job_pairs from related tables
-- It uses the primary stage data (job_pairs.primary_jobpair_data = stage_number)
DROP FUNCTION IF EXISTS starexec.SetNewColumns CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetNewColumns()
RETURNS VOID AS $$
BEGIN
    UPDATE job_pairs jp
    SET bench_name = b.name,
        solver_name = jsd.solver_name,
        config_name = jsd.config_name
    FROM starexec.benchmarks b, jobpair_stage_data jsd
    WHERE b.id = jp.bench_id
      AND jsd.jobpair_id = jp.id
      AND jsd.stage_number = jp.primary_jobpair_data;
END;
$$ LANGUAGE plpgsql;

-- Gets back only the fields of a job pair that are necessary to determine where it is stored on disk
-- Gets pairs that have either completed after the given completionID or are still running
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetNewJobPairFilePathInfoByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNewJobPairFilePathInfoByJob(_jobID INT, _completionID INT)
RETURNS TABLE(
    path VARCHAR(2048),
    solver_name VARCHAR(255),
    config_name VARCHAR(255),
    bench_name VARCHAR(256),
    status_code SMALLINT,
    completion_id INT,
    id INT,
    primary_jobpair_data INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.path, jsd.solver_name, jsd.config_name, jp.bench_name, jp.status_code,
           complete.completion_id, jp.id, jp.primary_jobpair_data
    FROM starexec.job_pairs jp
    LEFT JOIN job_pair_completion complete ON jp.id = complete.pair_id
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobID AND (complete.completion_id > _completionID OR jp.status_code = 4)
    AND jp.primary_jobpair_data = jsd.stage_number;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.RemovePairsFromComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemovePairsFromComplete(_jobId INT)
RETURNS VOID AS $$
BEGIN
    PERFORM 1 FROM starexec.jobs WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    DELETE FROM starexec.job_pair_completion jpc
    USING job_pairs jp
    WHERE jp.id = jpc.pair_id AND jp.job_id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s has no completion records to remove', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets all the pairs of a given job to the given status
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetPairsToStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetPairsToStatus(_jobId INT, _statusCode INT)
RETURNS VOID AS $$
BEGIN
    PERFORM 1 FROM starexec.jobs WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    UPDATE job_pairs SET status_code = _statusCode WHERE job_id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s has no pairs to update', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets all the pairs of a given job and status to the given status
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetPairsOfStatusToStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetPairsOfStatusToStatus(_jobId INT, _newCode INT, _curCode INT)
RETURNS VOID AS $$
BEGIN
    PERFORM 1 FROM starexec.jobs WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;

    UPDATE job_pairs SET status_code = _newCode WHERE job_id = _jobId AND status_code = _curCode;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s has no pairs with status %s to update', _jobId, _curCode);
    END IF;
END;
$$ LANGUAGE plpgsql;
-- Removes all jobs in the database that are deleted and also orphaned. Runs periodically.
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetDeletedJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDeletedJobs()
RETURNS TABLE(
    id INT,
    user_id INT,
    name VARCHAR(64),
    description TEXT,
    queue_id INT,
    primary_space INT,
    created TIMESTAMP,
    completed TIMESTAMP,
    seed BIGINT,
    cpuTimeout INT,
    clockTimeout INT,
    maximum_memory BIGINT,
    paused BOOLEAN,
    killed BOOLEAN,
    suppress_timestamp BOOLEAN,
    using_dependencies BOOLEAN,
    buildJob BOOLEAN,
    total_pairs INT,
    soft_time_limit INT,
    kill_delay INT,
    disk_size BIGINT,
    benchmarking_framework VARCHAR(32),
    is_high_priority BOOLEAN,
    output_benchmarks_directory_path TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id, j.user_id, j.name, j.description, j.queue_id, j.primary_space, j.created, j.completed, j.seed, j.cpuTimeout, j.clockTimeout, j.maximum_memory, j.paused, j.killed, j.suppress_timestamp, j.using_dependencies, j.buildJob, j.total_pairs, j.soft_time_limit, j.kill_delay, j.disk_size, j.benchmarking_framework, j.is_high_priority, j.output_benchmarks_directory_path
    FROM starexec.jobs j WHERE j.deleted = true;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobsAssociatedWithSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobsAssociatedWithSpaces()
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT job_id AS id FROM starexec.job_assoc;
END;
$$ LANGUAGE plpgsql;

-- Gives back the number of pairs with the given status
DROP FUNCTION IF EXISTS starexec.CountPairsByStatusByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountPairsByStatusByJob(_jobId INT, _status INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS count
    FROM starexec.job_pairs jp
    WHERE jp.job_id = _jobId AND _status = jp.status_code;
END;
$$ LANGUAGE plpgsql;

-- Gives back the number of pairs with the given status
DROP FUNCTION IF EXISTS starexec.CountTimelessPairsByStatusByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountTimelessPairsByStatusByJob(_jobId INT, _status INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT jp.id)::BIGINT AS count
    FROM starexec.job_pairs jp
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobId AND _status = jsd.status_code AND (jsd.wallclock = 0 OR jsd.cpu = 0);
END;
$$ LANGUAGE plpgsql;

-- For a given job, sets every pair at the complete status to the processing status, and also changes the post_processor
-- of the job to the given one
-- Choosing the primary stage is not allowed here-- an actual stage number must be supplied
DROP FUNCTION IF EXISTS starexec.PrepareJobForPostProcessing CASCADE;
CREATE OR REPLACE FUNCTION starexec.PrepareJobForPostProcessing(_jobId INT, _procId INT, _completeStatus INT, _processingStatus INT, _stageNumber INT)
RETURNS VOID AS $$
DECLARE
    -- The pairs to move, resolved once. Both tables are then updated against this
    -- fixed set, so neither update depends on a status the other has already changed.
    _pairIds INT[];
BEGIN
	PERFORM 1 FROM starexec.jobs WHERE id = _jobId;
	IF NOT FOUND THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0002',
			MESSAGE = format('Job %s not found', _jobId);
	END IF;

    -- This procedure previously wrote "SET jp.status_code = ...". PostgreSQL does not
    -- accept a qualified target column in SET and rejects it with 'column "jp" of
    -- relation "job_pairs" does not exist'. PL/pgSQL plans a function body lazily, at
    -- call time, so CREATE FUNCTION accepted it and the error only ever appeared when
    -- post-processing was actually requested.
    --
    -- The two updates were also order-dependent: the first moved job_pairs off
    -- _completeStatus, and the second then required that same status, so it matched no
    -- rows and raised P0002 even once the syntax was corrected. Resolving the pair set
    -- up front removes that coupling.
    SELECT array_agg(jp.id) INTO _pairIds
    FROM starexec.job_pairs jp
    JOIN starexec.jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobId
      AND jp.status_code = _completeStatus
      AND jsd.status_code = _completeStatus
      AND jsd.stage_number = _stageNumber;

    IF _pairIds IS NULL OR array_length(_pairIds, 1) = 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('No job pairs in job %s with status %s for stage %s', _jobId, _completeStatus, _stageNumber);
    END IF;

    -- job_pairs before jobpair_stage_data. The pair set is already resolved, so the two
    -- updates are order-independent in effect and this ordering is purely about locks:
    -- every other routine that touches both tables takes job_pairs first, and taking
    -- them in the opposite order here would make this a deadlock counterparty.
    UPDATE starexec.job_pairs
    SET status_code = _processingStatus
    WHERE id = ANY(_pairIds);

    UPDATE starexec.jobpair_stage_data
    SET status_code = _processingStatus
    WHERE jobpair_id = ANY(_pairIds) AND stage_number = _stageNumber;

    -- makes sure there is actually an entry in job_stage_params for this job / stage pair.
    INSERT INTO job_stage_params (job_id, stage_number, cpuTimeout, clockTimeout, maximum_memory, space_id, post_processor, pre_processor)
    VALUES (_jobId, _stageNumber, (SELECT cpuTimeout FROM starexec.jobs WHERE jobs.id = _jobId),
            (SELECT clockTimeout FROM starexec.jobs WHERE jobs.id = _jobId),
            (SELECT maximum_memory FROM starexec.jobs WHERE jobs.id = _jobId), NULL, _procId, NULL)
    ON CONFLICT (job_id, stage_number) DO NOTHING;

    UPDATE job_stage_params SET post_processor = _procId WHERE job_id = _jobId AND stage_number = _stageNumber;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job stage params for job %s stage %s not found', _jobId, _stageNumber);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetJobStageParams CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetJobStageParams(_jobId INT, _stage INT, _cpu INT, _clock INT, _mem BIGINT,
_space INT, _postProc INT, _preProc INT, _suffix VARCHAR(64), _resultsInterval INT, _stdoutSave INT, _extraSave INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO job_stage_params (job_id, stage_number, cpuTimeout, clockTimeout, maximum_memory,
    space_id, post_processor, pre_processor, bench_suffix, results_interval, stdout_save_option, extra_output_save_option)
    VALUES (_jobId, _stage, _cpu, _clock, _mem, _space, _postProc, _preProc, _suffix, _resultsInterval, _stdoutSave, _extraSave)
    ON CONFLICT (job_id, stage_number) DO UPDATE SET
        cpuTimeout = EXCLUDED.cpuTimeout,
        clockTimeout = EXCLUDED.clockTimeout,
        maximum_memory = EXCLUDED.maximum_memory,
        space_id = EXCLUDED.space_id,
        post_processor = EXCLUDED.post_processor,
        pre_processor = EXCLUDED.pre_processor,
        bench_suffix = EXCLUDED.bench_suffix,
        results_interval = EXCLUDED.results_interval,
        stdout_save_option = EXCLUDED.stdout_save_option,
        extra_output_save_option = EXCLUDED.extra_output_save_option;
END;
$$ LANGUAGE plpgsql;

-- Gets every incomplete Job
DROP FUNCTION IF EXISTS starexec.GetIncompleteJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetIncompleteJobs()
RETURNS TABLE(
    id INT,
    user_id INT,
    name VARCHAR(64),
    description TEXT,
    queue_id INT,
    primary_space INT,
    created TIMESTAMP,
    completed TIMESTAMP,
    seed BIGINT,
    cpuTimeout INT,
    clockTimeout INT,
    maximum_memory BIGINT,
    paused BOOLEAN,
    killed BOOLEAN,
    suppress_timestamp BOOLEAN,
    using_dependencies BOOLEAN,
    buildJob BOOLEAN,
    total_pairs INT,
    soft_time_limit INT,
    kill_delay INT,
    disk_size BIGINT,
    deleted BOOLEAN,
    benchmarking_framework VARCHAR(32),
    is_high_priority BOOLEAN,
    output_benchmarks_directory_path TEXT,
    totalPairs BIGINT,
    completePairs BIGINT,
    pendingPairs BIGINT,
    errorPairs BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id,
        j.user_id,
        j.name,
        j.description,
        j.queue_id,
        j.primary_space,
        j.created,
        j.completed,
        j.seed,
        j.cpuTimeout,
        j.clockTimeout,
        j.maximum_memory,
        j.paused,
        j.killed,
        j.suppress_timestamp,
        j.using_dependencies,
        j.buildJob,
        j.total_pairs,
        j.soft_time_limit,
        j.kill_delay,
        j.disk_size,
        j.deleted,
        j.benchmarking_framework,
        j.is_high_priority,
        j.output_benchmarks_directory_path,
        CAST(j.total_pairs AS BIGINT) AS totalPairs,
        GetCompletePairs(j.id) AS completePairs,
        GetPendingPairs(j.id) AS pendingPairs,
        GetErrorPairs(j.id) AS errorPairs
    FROM starexec.jobs j
    WHERE GetJobStatus(j.id) = 'incomplete' OR j.paused = true;
END;
$$ LANGUAGE plpgsql;

-- Gets the ID of every job that is currently running (has incomplete pairs and
-- is not already paused / killed)
DROP FUNCTION IF EXISTS starexec.GetRunningJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunningJobs()
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT temp.id FROM (
        SELECT j.id, GetJobStatus(j.id) AS status
        FROM starexec.jobs j
        WHERE j.paused = false AND j.killed = false
    ) AS temp
    WHERE temp.status = 'incomplete';
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetRunningJobsByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunningJobsByUser(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT temp.id FROM (
        SELECT j.id, GetJobStatus(j.id) AS status
        FROM starexec.jobs j
        WHERE j.paused = false AND j.killed = false AND j.user_id = _userId
    ) AS temp
    WHERE temp.status = 'incomplete';
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetJobName CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetJobName(_jobId INT, _newName VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET name = _newName WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetJobDescription CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetJobDescription(_jobId INT, _newDescription TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET description = _newDescription WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Checks to see if there is a global pause on all jobs
DROP FUNCTION IF EXISTS starexec.IsSystemPaused CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsSystemPaused()
RETURNS TABLE(paused BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT sf.paused FROM starexec.system_flags sf;
END;
$$ LANGUAGE plpgsql;

-- Permanently removes a job from the database
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveJobFromDatabase CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveJobFromDatabase(_jobId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.jobs WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all entries in the job_stage_params table referencing the given job
DROP FUNCTION IF EXISTS starexec.getStageParamsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.getStageParamsByJob(_jobId INT)
RETURNS TABLE(
    job_id INT,
    stage_number INT,
    cpuTimeout INT,
    clockTimeout INT,
    maximum_memory BIGINT,
    space_id INT,
    bench_suffix VARCHAR(64),
    post_processor INT,
    pre_processor INT,
    results_interval INT,
    stdout_save_option INT,
    extra_output_save_option INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        job_stage_params.job_id,
        job_stage_params.stage_number,
        job_stage_params.cpuTimeout,
        job_stage_params.clockTimeout,
        job_stage_params.maximum_memory,
        job_stage_params.space_id,
        job_stage_params.bench_suffix,
        job_stage_params.post_processor,
        job_stage_params.pre_processor,
        job_stage_params.results_interval,
        job_stage_params.stdout_save_option,
        job_stage_params.extra_output_save_option
    FROM starexec.job_stage_params WHERE job_stage_params.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

-- Gets all benchmark inputs for all pairs in the given job
DROP FUNCTION IF EXISTS starexec.GetAllJobPairBenchmarkInputsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllJobPairBenchmarkInputsByJob(_jobId INT)
RETURNS TABLE(jobpair_id INT, bench_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT jpi.jobpair_id, jpi.bench_id
    FROM starexec.jobpair_inputs jpi
    JOIN job_pairs jp ON jp.id = jpi.jobpair_id
    WHERE jp.job_id = _jobId
    ORDER BY jpi.input_number ASC;
END;
$$ LANGUAGE plpgsql;
DROP FUNCTION IF EXISTS starexec.GetAllJobIds CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllJobIds()
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id FROM starexec.jobs j;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.CountPairsByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.CountPairsByUser(_userId INT)
RETURNS TABLE(total_pairs BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT SUM(j.total_pairs)::BIGINT AS total_pairs FROM starexec.jobs j WHERE j.user_id = _userId AND j.deleted = false;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IncrementTotalJobPairsForJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementTotalJobPairsForJob(_jobId INT, _increment INT)
RETURNS VOID AS $$
BEGIN
    UPDATE jobs SET total_pairs = total_pairs + _increment WHERE id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not found', _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.DoesJobCopyBackIncrementally CASCADE;
CREATE OR REPLACE FUNCTION starexec.DoesJobCopyBackIncrementally(_jobId INT)
RETURNS TABLE(jobCopiesBackIncrementally BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT (COUNT(*) <> 0)::BOOLEAN AS jobCopiesBackIncrementally
    FROM starexec.job_stage_params jsp
    WHERE jsp.results_interval <> 0 AND jsp.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobAttributesTableHeaders CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobAttributesTableHeaders(_jobSpaceId INT)
RETURNS TABLE(attr_value TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT ja.attr_value::TEXT
    FROM starexec.job_attributes ja
    INNER JOIN job_pairs jp ON ja.pair_id = jp.id
    WHERE ja.attr_key = 'starexec-result' AND jp.job_space_id = _jobSpaceId
    GROUP BY ja.attr_value
    ORDER BY ja.attr_value;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetJobAttributesTable CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobAttributesTable(_jobSpaceId INT)
RETURNS TABLE(
    solver_id INT,
    solver_name VARCHAR(255),
    config_id INT,
    config_name VARCHAR(255),
    attr_value TEXT,
    attr_count BIGINT,
    wallclock_sum BIGINT,
    cpu_sum BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT jsd.solver_id, jsd.solver_name, jsd.config_id, jsd.config_name, ja.attr_value::TEXT,
           COUNT(ja.attr_value)::BIGINT AS attr_count,
           SUM(jsd.wallclock)::BIGINT AS wallclock_sum,
           SUM(jsd.cpu)::BIGINT AS cpu_sum
    FROM starexec.job_attributes ja
    JOIN job_pairs jp ON ja.pair_id = jp.id
    JOIN jobpair_stage_data jsd ON jp.id = jsd.jobpair_id AND ja.stage_number = jsd.stage_number
    WHERE ja.attr_key = 'starexec-result' AND jp.job_space_id = _jobSpaceId
    GROUP BY jsd.solver_id, jsd.config_id, jsd.solver_name, jsd.config_name, ja.attr_value;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetSumOfJobAttributes CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSumOfJobAttributes(_jobSpaceId INT)
RETURNS TABLE(
    attr_value TEXT,
    attr_count BIGINT,
    wallclock BIGINT,
    cpu BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT ja.attr_value::TEXT,
           COUNT(ja.attr_value)::BIGINT AS attr_count,
           SUM(jsd.wallclock)::BIGINT AS wallclock,
           SUM(jsd.cpu)::BIGINT AS cpu
    FROM starexec.job_attributes ja
    JOIN job_pairs jp ON ja.pair_id = jp.id
    JOIN jobpair_stage_data jsd ON jp.id = jsd.jobpair_id
    WHERE ja.attr_key = 'starexec-result' AND jp.job_space_id = _jobSpaceId
    GROUP BY ja.attr_value
    ORDER BY ja.attr_value;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Misc PROCEDURES
-- ================================================================================

-- Description: This file contains all miscellaneous stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new historical record to the logins table which tracks all user logins
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.LoginRecord CASCADE;
CREATE OR REPLACE FUNCTION starexec.LoginRecord(_userId INT, _ipAddress VARCHAR(15), _agent TEXT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO logins (user_id, login_date, ip_address, browser_agent)
    VALUES (_userId, NOW(), _ipAddress, _agent);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetReadOnly CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetReadOnly(readOnly BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE system_flags SET read_only = readOnly;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'System flags not found';
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetReadOnly CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetReadOnly()
RETURNS TABLE(read_only BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT sf.read_only FROM starexec.system_flags sf;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetFreezePrimitives CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetFreezePrimitives(frozen BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE system_flags SET freeze_primitives = frozen;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'System flags not found';
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetFreezePrimitives CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetFreezePrimitives()
RETURNS TABLE(freeze_primitives BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT sf.freeze_primitives FROM starexec.system_flags sf;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetStatusMessage CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetStatusMessage(_enabled BOOLEAN, _message TEXT, _url TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE ui_status_message SET enabled = _enabled, message = _message, url = _url;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'UI status message configuration not found';
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetStatusMessage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetStatusMessage()
RETURNS TABLE(enabled BOOLEAN, message TEXT, url TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT usm.enabled, usm.message, usm.url FROM starexec.ui_status_message usm;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetPairTimes CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPairTimes(_jobID INT)
RETURNS TABLE(start_time TIMESTAMP, end_time TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.start_time, jp.end_time FROM starexec.job_pairs jp WHERE jp.job_id = _jobID;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Notifications PROCEDURES
-- ================================================================================

-- Description: This file contains stored procedures for sending notifications

-- Subscribe a User to status updates from a Job
-- Record the current status of Job so we can see when it changes
DROP FUNCTION IF EXISTS starexec.SubscribeUserToJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.SubscribeUserToJob(_userId INT, _jobId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO notifications_jobs_users (user_id, job_id, last_seen_status)
    VALUES (_userId, _jobId, GetJobStatusDetail(_jobId));
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Failed to subscribe user %s to job %s', _userId, _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;
-- Unsubscribe a User from status updates to a Job
DROP FUNCTION IF EXISTS starexec.UnsubscribeUserFromJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.UnsubscribeUserFromJob(_userId INT, _jobId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.notifications_jobs_users
    WHERE user_id = _userId AND job_id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Subscription for user %s to job %s not found', _userId, _jobId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Find all Jobs that Users have subscribed to whose current status is different
--   from last recorded status. Does NOT modify any data. Status must be updated
--   if a notification is sent.
-- Returns a list of Job ID and User emails for sending notifications
DROP FUNCTION IF EXISTS starexec.NotifyUsersOfJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.NotifyUsersOfJobs()
RETURNS TABLE(job INT, "user" INT, firstName VARCHAR(32), lastName VARCHAR(32), email VARCHAR(64), status VARCHAR(16)) AS $$
BEGIN
    RETURN QUERY
    SELECT nju.job_id AS "job", nju.user_id AS "user", u.first_name, u.last_name, u.email AS "email", GetJobStatusDetail(nju.job_id) AS "status"
    FROM starexec.notifications_jobs_users nju
    LEFT JOIN users u ON u.id = nju.user_id
    WHERE nju.last_seen_status <> GetJobStatusDetail(nju.job_id);
END;
$$ LANGUAGE plpgsql;

-- Update the last_seen_status of a Job-User notification to the current status
--   of Job, then clean up the table deleting any notifications for Jobs with an
--   immutable status
DROP FUNCTION IF EXISTS starexec.UpdateNotificationJobStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateNotificationJobStatus(_userId INT, _jobId INT, _status VARCHAR(16))
RETURNS VOID AS $$
BEGIN
    UPDATE notifications_jobs_users
    SET last_seen_status = _status
    WHERE user_id = _userId AND job_id = _jobId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Subscription for user %s to job %s not found', _userId, _jobId);
    END IF;

    DELETE FROM starexec.notifications_jobs_users
    WHERE last_seen_status = 'COMPLETE' OR last_seen_status = 'DELETED';
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Compatibility-fix: NotifyUsersOfJobs (ensure consistent return types)
-- Some code paths and callers expect the status column to be a VARCHAR of
-- limited length. GetJobStatusDetail returns TEXT; cast explicitly to avoid
-- type-mismatch comparisons when checking last_seen_status vs current status.
-- ================================================================================
DROP FUNCTION IF EXISTS starexec.NotifyUsersOfJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.NotifyUsersOfJobs()
RETURNS TABLE(job INT, "user" INT, firstName VARCHAR(32), lastName VARCHAR(32), email VARCHAR(64), status VARCHAR(20)) AS $$
BEGIN
    RETURN QUERY
    SELECT nju.job_id AS job,
           nju.user_id AS "user",
           u.first_name AS firstName,
           u.last_name AS lastName,
           u.email AS email,
           GetJobStatusDetail(nju.job_id)::VARCHAR(20) AS status
    FROM starexec.notifications_jobs_users nju
    LEFT JOIN starexec.users u ON u.id = nju.user_id
    WHERE nju.last_seen_status IS DISTINCT FROM GetJobStatusDetail(nju.job_id)::VARCHAR(20);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UserSubscribedToJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.UserSubscribedToJob(_userId INT, _jobId INT)
RETURNS TABLE(subscribed BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT (_jobId IN (SELECT job_id FROM starexec.notifications_jobs_users WHERE user_id = _userId))::BOOLEAN AS subscribed;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- PairsRerun PROCEDURES
-- ================================================================================

-- Description: This file contains all procedures related to the pairs_rerun table.

DROP FUNCTION IF EXISTS starexec.HasPairBeenRerun CASCADE;
CREATE OR REPLACE FUNCTION starexec.HasPairBeenRerun(_pairId INT)
RETURNS TABLE(pair_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT pr.pair_id FROM starexec.pairs_rerun pr WHERE pr.pair_id = _pairId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.MarkPairAsRerun CASCADE;
CREATE OR REPLACE FUNCTION starexec.MarkPairAsRerun(_pairId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO pairs_rerun (pair_id) VALUES (_pairId)
    ON CONFLICT (pair_id) DO NOTHING;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Pair %s already marked as rerun', _pairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UnmarkPairAsRerun CASCADE;
CREATE OR REPLACE FUNCTION starexec.UnmarkPairAsRerun(_pairId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.pairs_rerun WHERE pair_id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Pair %s not marked as rerun', _pairId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Permissions PROCEDURES
-- ================================================================================

-- Description: This file contains all permissions-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new permissions record with the given permissions
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddPermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddPermissions(_addSolver BOOLEAN, _addBench BOOLEAN, _addUser BOOLEAN,
_addSpace BOOLEAN, _addJob BOOLEAN, _removeSolver BOOLEAN, _removeBench BOOLEAN, _removeSpace BOOLEAN,
_removeUser BOOLEAN, _removeJob BOOLEAN, _isLeader BOOLEAN)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO permissions
        (add_solver, add_bench, add_user, add_space, add_job, remove_solver,
        remove_bench, remove_space, remove_user, remove_job, is_leader)
    VALUES
        (_addSolver, _addBench, _addUser, _addSpace, _addJob, _removeSolver,
        _removeBench, _removeSpace, _removeUser, _removeJob, _isLeader)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Returns 1 if the given user can somehow see the given solver, 0 otherwise
-- Author: Tyler Jensen + Eric Burns
DROP FUNCTION IF EXISTS starexec.CanViewSolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.CanViewSolver(_solverId INT, _userId INT)
RETURNS TABLE(verified BIGINT) AS $$
DECLARE
    space_count BIGINT;
    owner_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO space_count
    FROM starexec.solver_assoc sa
    JOIN user_assoc ua ON ua.space_id = sa.space_id
    WHERE sa.solver_id = _solverId AND ua.user_id = _userId;

    SELECT COUNT(*) INTO owner_count
    FROM starexec.solvers s
    WHERE s.id = _solverId AND s.user_id = _userId;

    IF space_count > 0 THEN
        RETURN QUERY SELECT 1::BIGINT AS verified;
    ELSE
        RETURN QUERY SELECT owner_count::BIGINT AS verified;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Author: Tyler Jensen + Eric Burns
DROP FUNCTION IF EXISTS starexec.CanViewBenchmark CASCADE;
CREATE OR REPLACE FUNCTION starexec.CanViewBenchmark(_benchId INT, _userId INT)
RETURNS TABLE(verified BIGINT) AS $$
DECLARE
    space_count BIGINT;
    owner_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO space_count
    FROM starexec.bench_assoc ba
    JOIN user_assoc ua ON ua.space_id = ba.space_id
    WHERE ba.bench_id = _benchId AND ua.user_id = _userId;

    SELECT COUNT(*) INTO owner_count
    FROM starexec.benchmarks b
    WHERE b.id = _benchId AND b.user_id = _userId;

    IF space_count > 0 THEN
        RETURN QUERY SELECT 1::BIGINT AS verified;
    ELSE
        RETURN QUERY SELECT owner_count::BIGINT AS verified;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns 1 if the given user either shares a space with the job or owns it
-- Author: Tyler Jensen	+ Eric Burns
DROP FUNCTION IF EXISTS starexec.CanViewJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.CanViewJob(_jobId INT, _userId INT)
RETURNS TABLE(verified BIGINT) AS $$
DECLARE
    space_count BIGINT;
    owner_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO space_count
    FROM starexec.job_assoc ja
    JOIN user_assoc ua ON ua.space_id = ja.space_id
    WHERE ja.job_id = _jobId AND ua.user_id = _userId;

    SELECT COUNT(*) INTO owner_count
    FROM starexec.jobs j
    WHERE j.id = _jobId AND j.user_id = _userId;

    IF space_count > 0 THEN
        RETURN QUERY SELECT 1::BIGINT AS verified;
    ELSE
        RETURN QUERY SELECT owner_count::BIGINT AS verified;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns 1 if the given user can somehow see the given space, 0 otherwise
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.CanViewSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.CanViewSpace(_spaceId INT, _userId INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT -- will return 1 if the user is in the space and 0 if they are not
    FROM starexec.user_assoc ua
    WHERE ua.space_id = _spaceId AND ua.user_id = _userId;
END;
$$ LANGUAGE plpgsql;

-- Finds the maximal set of permissions for the given user on the given space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetUserPermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserPermissions(_userId INT, _spaceId INT)
RETURNS TABLE(
    add_solver BOOLEAN,
    add_bench BOOLEAN,
    add_user BOOLEAN,
    add_space BOOLEAN,
    add_job BOOLEAN,
    remove_solver BOOLEAN,
    remove_bench BOOLEAN,
    remove_space BOOLEAN,
    remove_user BOOLEAN,
    remove_job BOOLEAN,
    is_leader BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT bool_or(p.add_solver) AS add_solver,
        bool_or(p.add_bench) AS add_bench,
        bool_or(p.add_user) AS add_user,
        bool_or(p.add_space) AS add_space,
        bool_or(p.add_job) AS add_job,
        bool_or(p.remove_solver) AS remove_solver,
        bool_or(p.remove_bench) AS remove_bench,
        bool_or(p.remove_space) AS remove_space,
        bool_or(p.remove_user) AS remove_user,
        bool_or(p.remove_job) AS remove_job,
        bool_or(p.is_leader) AS is_leader
    FROM starexec.permissions p
    JOIN user_assoc ua ON ua.permission = p.id
    WHERE ua.user_id = _userId AND ua.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Finds the default user permissions for the given space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSpacePermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpacePermissions(_spaceId INT)
RETURNS TABLE(
    id INT,
    add_solver BOOLEAN,
    add_bench BOOLEAN,
    add_user BOOLEAN,
    add_space BOOLEAN,
    add_job BOOLEAN,
    remove_solver BOOLEAN,
    remove_bench BOOLEAN,
    remove_space BOOLEAN,
    remove_user BOOLEAN,
    remove_job BOOLEAN,
    is_leader BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT p.*
    FROM starexec.permissions p
    JOIN spaces s ON s.default_permission = p.id
    WHERE s.id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Copies one set of permissions into another with a new ID
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.CopyPermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.CopyPermissions(_permId INT)
RETURNS INT AS $$
DECLARE
    _newId INT;
BEGIN
    INSERT INTO permissions (add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader)
    SELECT add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader
    FROM starexec.permissions
    WHERE id = _permId
    RETURNING id INTO _newId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Permission %s not found', _permId);
    END IF;
    RETURN _newId;
END;
$$ LANGUAGE plpgsql;

-- Sets a user's permissions for a given space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.SetUserPermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetUserPermissions(_userId INT, _spaceId INT, _addSolver BOOLEAN, _addBench BOOLEAN, _addUser BOOLEAN,
_addSpace BOOLEAN, _addJob BOOLEAN, _removeSolver BOOLEAN, _removeBench BOOLEAN, _removeSpace BOOLEAN,
_removeUser BOOLEAN, _removeJob BOOLEAN, _isLeader BOOLEAN)
RETURNS VOID AS $$
DECLARE
    _permissionId INT;
BEGIN
    -- First, ensure the user_assoc entry exists
    IF NOT EXISTS(SELECT 1 FROM starexec.user_assoc WHERE user_id = _userId AND space_id = _spaceId) THEN
        -- User is not yet associated with this space - this is an error condition
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s is not associated with space %s', _userId, _spaceId);
    END IF;

    -- Get the permission ID for this user-space combination
    SELECT ua.permission INTO _permissionId
    FROM starexec.user_assoc ua
    WHERE ua.user_id = _userId AND ua.space_id = _spaceId;

    -- Update the permission record
    UPDATE permissions p
    SET add_user = _addUser,
        add_solver = _addSolver,
        add_bench = _addBench,
        add_job = _addJob,
        add_space = _addSpace,
        remove_user = _removeUser,
        remove_solver = _removeSolver,
        remove_bench = _removeBench,
        remove_job = _removeJob,
        remove_space = _removeSpace,
        is_leader = _isLeader
    WHERE p.id = _permissionId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Permission %s for user %s in space %s not found', _permissionId, _userId, _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the permission set with the given id
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdatePermissions CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdatePermissions(_id INT, _addSolver BOOLEAN, _addBench BOOLEAN, _addUser BOOLEAN,
_addSpace BOOLEAN, _addJob BOOLEAN, _removeSolver BOOLEAN, _removeBench BOOLEAN, _removeSpace BOOLEAN,
_removeUser BOOLEAN, _removeJob BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE permissions
    SET add_user = _addUser,
        add_solver = _addSolver,
        add_bench = _addBench,
        add_job = _addJob,
        add_space = _addSpace,
        remove_user = _removeUser,
        remove_solver = _removeSolver,
        remove_bench = _removeBench,
        remove_job = _removeJob,
        remove_space = _removeSpace
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Permission %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets a user's permissions for a given space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.SetUserPermissions2 CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetUserPermissions2(_userId INT, _spaceId INT, _permissionId INT)
RETURNS VOID AS $$
BEGIN
    -- Use INSERT...ON CONFLICT to handle both update and insert cases
    INSERT INTO user_assoc (user_id, space_id, permission)
    VALUES (_userId, _spaceId, _permissionId)
    ON CONFLICT (user_id, space_id) DO UPDATE
    SET permission = _permissionId;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Pipelines PROCEDURES
-- ================================================================================

-- Gets data from the solver_pipelines table for the given id
DROP FUNCTION IF EXISTS starexec.GetPipelineById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPipelineById(_id INT)
RETURNS TABLE(
    id INT,
    userId INT,
    name VARCHAR(64),
    uploaded TIMESTAMP,
    description TEXT,
    primaryStageId INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT solver_pipelines.id, user_id AS userId, name, uploaded, description, primary_stage_id AS primaryStageId
    FROM starexec.solver_pipelines WHERE id = _id;
END;
$$ LANGUAGE plpgsql;

-- Gets all the stage information from the pipeline_stages table for the given pipeline
DROP FUNCTION IF EXISTS starexec.GetStagesByPipelineId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetStagesByPipelineId(_id INT)
RETURNS TABLE(
    stage_id INT,
    pipeline_id INT,
    config_id INT,
    is_noop BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM starexec.pipeline_stages WHERE pipeline_stages.pipeline_id = _id;
END;
$$ LANGUAGE plpgsql;

-- Given a stage ID, gets all the dependencies for the stage
DROP FUNCTION IF EXISTS starexec.GetDependenciesForPipelineStage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDependenciesForPipelineStage(_id INT)
RETURNS TABLE(
    stage_id INT,
    input_type SMALLINT,
    input_id SMALLINT,
    input_number SMALLINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM starexec.pipeline_dependencies WHERE pipeline_dependencies.stage_id = _id ORDER BY input_number;
END;
$$ LANGUAGE plpgsql;

-- Given a stage ID, gets all the dependencies for the stage
DROP FUNCTION IF EXISTS starexec.GetDependenciesForJobPair CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDependenciesForJobPair(_pairId INT)
RETURNS TABLE(
    stage_id INT,
    input_type SMALLINT,
    input_id SMALLINT,
    input_number SMALLINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT pd.stage_id, pd.input_type, pd.input_id, pd.input_number
    FROM starexec.jobpair_stage_data jsd
    JOIN pipeline_dependencies pd ON pd.stage_id = jsd.stage_id
    WHERE jsd.jobpair_id = _pairId
    ORDER BY pd.input_number;
END;
$$ LANGUAGE plpgsql;

-- Adds a solver pipeline to the database
DROP FUNCTION IF EXISTS starexec.AddPipeline CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddPipeline(_uid INT, _name VARCHAR(128))
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO solver_pipelines (user_id, name, uploaded) VALUES (_uid, _name, NOW())
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- adds a solver pipeline stage for an existing pipeline to the database.
-- pipelines must be added to the database in the order that they are to be used in the pipeline
-- to ensure that the AUTO_INCREMENT IDs are ordered
DROP FUNCTION IF EXISTS starexec.AddPipelineStage CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddPipelineStage(_pid INT, _cid INT, _primary INT, _noop BOOLEAN)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO pipeline_stages (pipeline_id, config_id, is_noop)
    VALUES (_pid, _cid, _noop)
    RETURNING stage_id INTO _id;

    IF _primary = 1 THEN
        UPDATE solver_pipelines SET primary_stage_id = _id WHERE id = _pid;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Pipeline %s not found when setting primary stage', _pid);
        END IF;
    END IF;

    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Adds a dependency for an existing stage.
DROP FUNCTION IF EXISTS starexec.AddPipelineDependency CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddPipelineDependency(_sid INT, _iid INT, _type INT, _num INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO pipeline_dependencies (stage_id, input_id, input_type, input_number)
    VALUES (_sid, _iid, _type, _num);
END;
$$ LANGUAGE plpgsql;

-- deletes a pipeline from the database. This will also delete all of its dependencies and stages
DROP FUNCTION IF EXISTS starexec.DeletePipeline CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeletePipeline(_pid INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.solver_pipelines WHERE id = _pid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Pipeline %s not found', _pid);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all the pipeline IDs of pipelines referenced by the given job
DROP FUNCTION IF EXISTS starexec.GetPipelineIdsByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPipelineIdsByJob(_jid INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT sp.id
    FROM starexec.job_pairs jp
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    JOIN pipeline_stages ps ON ps.stage_id = jsd.stage_id
    JOIN solver_pipelines sp ON sp.id = ps.pipeline_id
    WHERE jp.job_id = _jid;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Processors PROCEDURES
-- ================================================================================

-- Description: This file contains all processor-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new processor with the given information
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddProcessor CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddProcessor(_name VARCHAR(64), _desc TEXT, _path TEXT, _comId INT, _type SMALLINT, _diskSize BIGINT, _time_limit SMALLINT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO processors (name, description, path, community, processor_type, disk_size, time_limit)
    VALUES (_name, _desc, _path, _comId, _type, _diskSize, _time_limit)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Removes the association between a processor and a given space,
-- and inserts the processor_path into _path, so the physical file(s) can
-- be removed from disk
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.DeleteProcessor CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteProcessor(_id INT)
RETURNS TEXT AS $$
DECLARE
    _path TEXT;
BEGIN
    SELECT path INTO _path FROM starexec.processors WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
    DELETE FROM starexec.processors
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
    RETURN _path;
END;
$$ LANGUAGE plpgsql;

-- Gets all processors of a given type
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetAllProcessors CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllProcessors(_type SMALLINT)
RETURNS TABLE(id INT, name VARCHAR(64), description TEXT, path TEXT, community INT, processor_type SMALLINT, disk_size BIGINT, time_limit SMALLINT, syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT p.id, p.name, p.description, p.path, p.community, p.processor_type, p.disk_size, p.time_limit, p.syntax_id
    FROM starexec.processors p
    WHERE p.processor_type = _type
    ORDER BY p.name;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all processor belonging to a community
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetProcessorsByCommunity CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetProcessorsByCommunity(_id INT, _type SMALLINT)
RETURNS TABLE(id INT, name VARCHAR(64), description TEXT, path TEXT, community INT, processor_type SMALLINT, disk_size BIGINT, time_limit SMALLINT, syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT p.id, p.name, p.description, p.path, p.community, p.processor_type, p.disk_size, p.time_limit, p.syntax_id
    FROM starexec.processors p
    WHERE p.community = _id AND p.processor_type = _type
    ORDER BY p.name;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all processors in all communities a user is a part of
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetProcessorsByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetProcessorsByUser(_userId INT, _type SMALLINT)
RETURNS TABLE(id INT, name VARCHAR(64), description TEXT, path TEXT, community INT, processor_type SMALLINT, disk_size BIGINT, time_limit SMALLINT, syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT p.id, p.name, p.description, p.path, p.community, p.processor_type, p.disk_size, p.time_limit, p.syntax_id
    FROM starexec.processors p
    WHERE p.processor_type = _type
    AND p.community IN (
        SELECT c.ancestor
        FROM starexec.closure c
        JOIN user_assoc ua ON ua.space_id = c.descendant
        WHERE ua.user_id = _userId
    );
END;
$$ LANGUAGE plpgsql;

-- Gets the processor with the given ID
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetProcessorById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetProcessorById(_id INT)
RETURNS TABLE(id INT, name VARCHAR(64), description TEXT, path TEXT, community INT, processor_type SMALLINT, disk_size BIGINT, time_limit SMALLINT, syntax_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT p.id, p.name, p.description, p.path, p.community, p.processor_type, p.disk_size, p.time_limit, p.syntax_id
    FROM starexec.processors p
    WHERE p.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Updates a processor's description
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateProcessorDescription CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorDescription(_id INT, _desc TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET description = _desc
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates a processor's file path
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.UpdateProcessorFilePath CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorFilePath(_id INT, _path TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET path = _path
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates a processor's name
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateProcessorName CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorName(_id INT, _name VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET name = _name
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates a processor's processor path
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateProcessorPath CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorPath(_id INT, _path TEXT, _diskSize BIGINT)
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET path = _path,
        disk_size = _diskSize
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UpdateProcessorTimeLimit CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorTimeLimit(_id INT, _timeLimit SMALLINT)
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET time_limit = _timeLimit
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UpdateProcessorSyntax CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateProcessorSyntax(_id INT, _syntax INT)
RETURNS VOID AS $$
BEGIN
    UPDATE processors
    SET syntax_id = _syntax
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Processor %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllSyntaxes CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllSyntaxes()
RETURNS TABLE(id INT, name CHAR(32), class CHAR(32), js CHAR(32)) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.name, s.class, s.js FROM starexec.syntax s;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Queues PROCEDURES
-- ================================================================================

-- Adds a new queue given a name
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddQueue(_name VARCHAR(128), _wall INT, _cpu INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO queues (name, clockTimeout, cpuTimeout, status)
    VALUES (_name, _wall, _cpu, 'INACTIVE')
    ON CONFLICT (name) DO NOTHING
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Remove a queue given its id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.RemoveQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveQueue(_queueId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.queues
    WHERE id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _queueId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the id of a queue given its name
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetIdByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetIdByName(_queueName VARCHAR(64))
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT q.id
    FROM starexec.queues q
    WHERE q.name = _queueName;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all jobs with pending job pairs for the given queue
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetPendingJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPendingJobs(_queueId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(64), description TEXT, queue_id INT, primary_space INT, created TIMESTAMP, completed TIMESTAMP, seed BIGINT, cpuTimeout INT, clockTimeout INT, maximum_memory BIGINT, paused BOOLEAN, killed BOOLEAN, suppress_timestamp BOOLEAN, using_dependencies BOOLEAN, buildJob BOOLEAN, total_pairs INT, soft_time_limit INT, kill_delay INT, disk_size BIGINT, benchmarking_framework VARCHAR, is_high_priority BOOLEAN, output_benchmarks_directory_path TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT j.id, j.user_id, j.name, j.description, j.queue_id, j.primary_space, j.created, j.completed, j.seed, j.cpuTimeout, j.clockTimeout, j.maximum_memory, j.paused, j.killed, j.suppress_timestamp, j.using_dependencies, j.buildJob, j.total_pairs, j.soft_time_limit, j.kill_delay, j.disk_size, j.benchmarking_framework, j.is_high_priority, j.output_benchmarks_directory_path
    FROM starexec.jobs j
    WHERE j.queue_id = _queueId
    AND EXISTS (SELECT 1 FROM starexec.job_pairs jp WHERE jp.status_code = 1 AND jp.job_id = j.id);
END;
$$ LANGUAGE plpgsql;

-- Retrieves all pending job pairs for a give queue owned by a developer
DROP FUNCTION IF EXISTS starexec.GetPendingDeveloperJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPendingDeveloperJobs(_queueId INT)
RETURNS TABLE(id INT, userId INT, name VARCHAR(64), description TEXT, queueId INT, primarySpace INT, created TIMESTAMP, completed TIMESTAMP, seed BIGINT, cpuTimeout INT, clockTimeout INT, maximumMemory BIGINT, paused BOOLEAN, killed BOOLEAN, suppressTimestamp BOOLEAN, usingDependencies BOOLEAN, buildJob BOOLEAN, totalPairs INT, softTimeLimit INT, killDelay INT, diskSize BIGINT, benchmarkingFramework VARCHAR, isHighPriority BOOLEAN, outputBenchmarksDirectoryPath TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT j.id, j.user_id, j.name, j.description, j.queue_id, j.primary_space, j.created, j.completed, j.seed, j.cpuTimeout, j.clockTimeout, j.maximum_memory, j.paused, j.killed, j.suppress_timestamp, j.using_dependencies, j.buildJob, j.total_pairs, j.soft_time_limit, j.kill_delay, j.disk_size, j.benchmarking_framework, j.is_high_priority, j.output_benchmarks_directory_path
    FROM starexec.users u
    INNER JOIN user_roles ur ON u.email = ur.email
    INNER JOIN jobs j ON j.user_id = u.id
    WHERE (ur.role = 'developer' OR ur.role = 'admin') AND j.queue_id = _queueId
    AND EXISTS (SELECT 1 FROM starexec.job_pairs jp WHERE jp.status_code = 1 AND jp.job_id = j.id);
END;
$$ LANGUAGE plpgsql;

-- Retrieves the number of enqueued job pairs for the given queue
-- Author: Benton McCune and Aaron Stump
DROP FUNCTION IF EXISTS starexec.GetNumEnqueuedJobs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNumEnqueuedJobs(_queueId INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS count FROM starexec.job_pairs jp JOIN jobs j ON jp.job_id = j.id
    WHERE jp.status_code = 2 AND j.queue_id = _queueId;
END;
$$ LANGUAGE plpgsql;

-- Gets the sum of wallclock timeouts for all
DROP FUNCTION IF EXISTS starexec.GetUserLoadOnQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserLoadOnQueue(_queueId INT, _user INT)
RETURNS TABLE(queue_load BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT SUM(j.clockTimeout) AS queue_load FROM starexec.job_pairs jp JOIN jobs j ON jp.job_id = j.id
    WHERE (jp.status_code = 4 OR jp.status_code = 2)
    AND j.queue_id = _queueId AND j.user_id = _user;
END;
$$ LANGUAGE plpgsql;

-- Retrieves basic info about enqueued job pairs for the given queue id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetCountOfEnqueuedJobPairsByQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCountOfEnqueuedJobPairsByQueue(_id INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS count
    FROM starexec.job_pairs jp
    INNER JOIN jobs j ON jp.job_id = j.id
    WHERE j.queue_id = _id AND jp.status_code = 2;
END;
$$ LANGUAGE plpgsql;

-- Get the name of a queue given its id
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNameById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNameById(_queueId INT)
RETURNS TABLE(name VARCHAR(128)) AS $$
BEGIN
    RETURN QUERY
    SELECT q.name
    FROM starexec.queues q
    WHERE q.id = _queueId;
END;
$$ LANGUAGE plpgsql;

-- Updates the max wallclock timeout for a queue
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.UpdateQueueClockTimeout CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateQueueClockTimeout(_queueId INT, _timeout INT)
RETURNS VOID AS $$
BEGIN
    UPDATE queues
    SET clockTimeout = _timeout
    WHERE id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _queueId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the max cpu timeout for a queue
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.UpdateQueueCpuTimeout CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateQueueCpuTimeout(_queueId INT, _timeout INT)
RETURNS VOID AS $$
BEGIN
    UPDATE queues
    SET cpuTimeout = _timeout
    WHERE id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _queueId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Determines if the queue has global access
-- Author: Wyatt kaiser
DROP FUNCTION IF EXISTS starexec.IsQueueGlobal CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsQueueGlobal(_queueId INT)
RETURNS TABLE(global_access BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT q.global_access
    FROM starexec.queues q
    WHERE q.id = _queueId;
END;
$$ LANGUAGE plpgsql;

-- Removes a queue's association with a space
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.RemoveQueueAssociation CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveQueueAssociation(_queueId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.comm_queue
    WHERE queue_id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s association not found', _queueId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Make a queue have global access
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.MakeQueueGlobal CASCADE;
CREATE OR REPLACE FUNCTION starexec.MakeQueueGlobal(_queueId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE queues
    SET global_access = true
    WHERE id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _queueId);
    END IF;

    DELETE FROM starexec.comm_queue
    WHERE queue_id = _queueId;
END;
$$ LANGUAGE plpgsql;

-- remove global access from a queue
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.RemoveQueueGlobal CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveQueueGlobal(_queueId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE queues
    SET global_access = false
    WHERE id = _queueId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _queueId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the test queue in the database to a new value
DROP FUNCTION IF EXISTS starexec.SetTestQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetTestQueue(_qid INT)
RETURNS VOID AS $$
BEGIN
    UPDATE system_flags SET test_queue = _qid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'System flags row not found when setting test queue';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets the ID of the queue for running test jobs on solver uploads
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetTestQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetTestQueue()
RETURNS TABLE(test_queue INT) AS $$
BEGIN
    RETURN QUERY
    SELECT sf.test_queue FROM starexec.system_flags sf;
END;
$$ LANGUAGE plpgsql;

-- Give the community (leaders) Access to a queue
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.SetQueueCommunityAccess CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetQueueCommunityAccess(_communityId INT, _queueId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO comm_queue
    VALUES (_communityId, _queueId)
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetPairsRunningOnNode CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPairsRunningOnNode(_nodeId INT)
RETURNS TABLE(id INT, path TEXT, primaryJobpairData INT, jobId INT, benchId INT, benchName VARCHAR(256), queuesubTime TIMESTAMP, solverId INT, solverName VARCHAR(128), configId INT, configName VARCHAR(128), jobIdDup INT, jobName VARCHAR(128), userId INT, firstName VARCHAR(32), lastName VARCHAR(32)) AS $$
BEGIN
    RETURN QUERY
    SELECT jp.id,
           jp.path::TEXT,
           jp.primary_jobpair_data,
           jp.job_id,
           jp.bench_id,
           jp.bench_name,
           jp.queuesub_time,
           jsd.solver_id,
           jsd.solver_name,
           jsd.config_id,
           jsd.config_name,
           j.id,
           j.name,
           u.id,
           u.first_name,
           u.last_name
    FROM starexec.job_pairs jp
    JOIN jobs j ON j.id = jp.job_id
    JOIN users u ON u.id = j.user_id
    JOIN jobpair_stage_data jsd ON jsd.jobpair_id = jp.id
    WHERE jp.node_id = _nodeId AND jp.status_code = 4 AND jsd.stage_number = jp.primary_jobpair_data;
END;
$$ LANGUAGE plpgsql;

-- Gets all of the queues that the given user is allowed to use
DROP FUNCTION IF EXISTS starexec.GetQueuesForUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetQueuesForUser(_userID INT)
RETURNS TABLE(id INT, name VARCHAR(128), status VARCHAR(32), global_access BOOLEAN, cpuTimeout INT, clockTimeout INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT q.id, q.name, q.status, q.global_access, q.cpuTimeout, q.clockTimeout
    FROM starexec.queues q
    LEFT JOIN comm_queue cq ON q.id = cq.queue_id
    WHERE q.status = 'ACTIVE'
    AND (
        (SELECT COUNT(*) > 0 FROM starexec.IsLeader(cq.space_id, _userID))  -- Either you are the leader of the community it was given access to
        OR
        q.global_access                         -- or it is a global queue
    );
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetDescForQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDescForQueue(_qID INT)
RETURNS TABLE(description TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT q.description::TEXT
    FROM starexec.queues q
    WHERE q.id = _qID;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetDescForQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetDescForQueue(_qID INT, _desc VARCHAR(200))
RETURNS VOID AS $$
BEGIN
    UPDATE queues
    SET description = _desc
    WHERE id = _qID;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Queue %s not found', _qID);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ================================================================================
-- Reports PROCEDURES
-- ================================================================================

-- Description: This file contains all weekly-report-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Set the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.SetEventOccurrencesNotRelatedToQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetEventOccurrencesNotRelatedToQueue(_eventName VARCHAR(64), _eventOccurrences INT)
RETURNS VOID AS $$
BEGIN
    UPDATE report_data
    SET occurrences = _eventOccurrences
    WHERE event_name = _eventName AND queue_name IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Report event %s not found (no queue)', _eventName);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Set the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.SetEventOccurrencesForQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetEventOccurrencesForQueue(_eventName VARCHAR(64), _eventOccurrences INT, _queueName VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    -- check if the event already exists for this queue and set it if it does
    IF EXISTS (SELECT 1 FROM starexec.report_data WHERE queue_name = _queueName AND event_name = _eventName) THEN
        UPDATE report_data
        SET occurrences = _eventOccurrences
        WHERE event_name = _eventName AND queue_name = _queueName;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Report event %s for queue %s not found', _eventName, _queueName);
        END IF;
    -- otherwise create the event with the given number of occurrences
    ELSE
        INSERT INTO report_data (event_name, queue_name, occurrences)
        VALUES (_eventName, _queueName, _eventOccurrences);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Adds to the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP ROUTINE IF EXISTS starexec.AddToEventOccurrencesNotRelatedToQueue(VARCHAR, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.AddToEventOccurrencesNotRelatedToQueue(_eventName VARCHAR(64), _eventOccurrences INT)
AS $$
BEGIN
    UPDATE report_data
    SET occurrences = occurrences + _eventOccurrences
    WHERE event_name = _eventName AND queue_name IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Report event %s not found (no queue)', _eventName);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Add to the value of an event's occurrences for a specific queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.AddToEventOccurrencesForQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddToEventOccurrencesForQueue(_eventName VARCHAR(64), _eventOccurrences INT, _queueName VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    INSERT INTO report_data (event_name, queue_name, occurrences) VALUES (_eventName, _queueName, 0)
    ON CONFLICT (event_name, queue_name) DO NOTHING;

    UPDATE report_data
    SET occurrences = occurrences + _eventOccurrences
    WHERE event_name = _eventName AND queue_name = _queueName;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Report event %s for queue %s not found', _eventName, _queueName);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Add to the value of an event's occurrences for a specific queue related to a specific job pair.
-- Author: Albert Giegerich
DROP ROUTINE IF EXISTS starexec.AddToEventOccurrencesForJobPairsQueue(VARCHAR, INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.AddToEventOccurrencesForJobPairsQueue(_eventName VARCHAR(64), _eventOccurrences INT, _pairId INT)
AS $$
DECLARE
    _queueId INT;
    _queueName VARCHAR(128);
BEGIN
    SELECT j.queue_id INTO _queueId
    FROM starexec.job_pairs jp
    INNER JOIN jobs j ON jp.job_id = j.id
    WHERE jp.id = _pairId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job pair %s not found when updating report event %s', _pairId, _eventName);
    END IF;

    IF _queueId IS NOT NULL THEN
        SELECT q.name INTO _queueName FROM starexec.queues q WHERE q.id = _queueId;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Queue %s for job pair %s not found when updating report event %s', _queueId, _pairId, _eventName);
        END IF;

        INSERT INTO report_data (event_name, occurrences, queue_name) VALUES (_eventName, 0, _queueName)
        ON CONFLICT (event_name, queue_name) DO NOTHING;

        PERFORM AddToEventOccurrencesForQueue(_eventName, _eventOccurrences, _queueName);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all event names and occurrences for all events not related to a queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetAllEventsAndOccurrencesNotRelatedToQueues CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllEventsAndOccurrencesNotRelatedToQueues()
RETURNS TABLE(event_name VARCHAR(64), occurrences INT) AS $$
BEGIN
    RETURN QUERY
    SELECT rd.event_name, rd.occurrences
    FROM starexec.report_data rd
    WHERE rd.queue_name IS NULL;
END;
$$ LANGUAGE plpgsql;

-- Gets all event names and occurrences for every queue
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetAllEventsAndOccurrencesForAllQueues CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllEventsAndOccurrencesForAllQueues()
RETURNS TABLE(event_name VARCHAR(64), occurrences INT, queue_name VARCHAR(128)) AS $$
BEGIN
    RETURN QUERY
    SELECT rd.event_name, rd.occurrences, rd.queue_name
    FROM starexec.report_data rd
    WHERE rd.queue_name IS NOT NULL
    ORDER BY rd.queue_name, rd.event_name;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of occurrences for an event not related to a queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetEventOccurrencesNotRelatedToQueues CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetEventOccurrencesNotRelatedToQueues(_eventName VARCHAR(64))
RETURNS TABLE(occurrences INT) AS $$
BEGIN
    RETURN QUERY
    SELECT rd.occurrences
    FROM starexec.report_data rd
    WHERE rd.event_name = _eventName AND rd.queue_name IS NULL;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of an event's occurrences for a specific queue.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetEventOccurrencesForQueue CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetEventOccurrencesForQueue(_eventName VARCHAR(64), _queueName VARCHAR(64))
RETURNS TABLE(occurrences INT) AS $$
BEGIN
    RETURN QUERY
    SELECT rd.occurrences
    FROM starexec.report_data rd
    WHERE rd.event_name = _eventName AND rd.queue_name = _queueName;
END;
$$ LANGUAGE plpgsql;

-- Resets all report data by setting all occurrences to 0 and deleting queue related rows
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.ResetReports CASCADE;
CREATE OR REPLACE FUNCTION starexec.ResetReports()
RETURNS VOID AS $$
BEGIN
    UPDATE report_data
    SET occurrences = 0
    WHERE queue_name IS NULL;
    DELETE FROM starexec.report_data
    WHERE queue_name IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of unique user logins in the logins table.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetNumberOfUniqueLogins CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNumberOfUniqueLogins()
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) FROM (SELECT DISTINCT l.user_id FROM starexec.logins l) AS t;
END;
$$ LANGUAGE plpgsql;

-- Delete all information in the logins table.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.ResetLogins CASCADE;
CREATE OR REPLACE FUNCTION starexec.ResetLogins()
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.logins;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Requests PROCEDURES
-- ================================================================================

-- Description: This file contains all stored procedures used for requesting membership in a community, registering, and the resetting of passwords
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds an activation code for a specific user
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.AddCode CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddCode(_id INT, _code VARCHAR(36))
RETURNS VOID AS $$
BEGIN
    INSERT INTO verify(user_id, code, created)
    VALUES (_id, _code, NOW());
END;
$$ LANGUAGE plpgsql;

-- Adds a request to join a community, provided the user isn't already a part of that community
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.AddCommunityRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddCommunityRequest(_id INT, _community INT, _code VARCHAR(36), _message TEXT)
RETURNS VOID AS $$
BEGIN
    IF NOT EXISTS(SELECT * FROM starexec.user_assoc WHERE user_id = _id AND space_id = _community) THEN
        INSERT INTO community_requests(user_id, community, code, message, created)
        VALUES (_id, _community, _code, _message, NOW());
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Adds a user to USER_ASSOC, deletes their entry in INVITES, and makes their
-- role 'user' if not so already
-- Author: Todd Elvers & Skylar Stark
DROP FUNCTION IF EXISTS starexec.ApproveCommunityRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.ApproveCommunityRequest(_id INT, _community INT)
RETURNS VOID AS $$
DECLARE
    _newPermId INT;
    _pid INT;
BEGIN
    IF EXISTS(SELECT * FROM starexec.community_requests WHERE user_id = _id AND community = _community) THEN
        DELETE FROM starexec.community_requests
        WHERE user_id = _id AND community = _community;

        -- Copy the default permission for the community
        SELECT s.default_permission INTO _pid FROM starexec.spaces s WHERE s.id = _community;
        SELECT CopyPermissions(_pid) INTO _newPermId;

        INSERT INTO user_assoc(user_id, space_id, permission)
        VALUES(_id, _community, _newPermId);

        -- make the user a 'user' if they are currently 'unauthorized'
        IF EXISTS(SELECT ur.email FROM starexec.user_roles ur WHERE ur.email = (SELECT u.email FROM starexec.users u WHERE u.id = _id) AND ur.role = 'unauthorized') THEN
            UPDATE user_roles
            SET role = 'user'
            FROM starexec.users u
            WHERE user_roles.email = u.email AND u.id = _id;
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Adds a new entry to pass_reset_request for a given user (also deletes previous
-- entries for the same user)
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.AddPassResetRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddPassResetRequest(_id INT, _code VARCHAR(36))
RETURNS VOID AS $$
BEGIN
    IF EXISTS(SELECT * FROM starexec.pass_reset_request WHERE user_id = _id) THEN
        DELETE FROM starexec.pass_reset_request
        WHERE user_id = _id;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Password reset request for user %s not found', _id);
        END IF;
    END IF;
    INSERT INTO pass_reset_request(user_id, code, created)
    VALUES(_id, _code, NOW());
END;
$$ LANGUAGE plpgsql;

-- Deletes a user's entry in INVITES, and if the user is unregistered
-- (i.e. has a role of 'unauthorized') then they are completely
-- deleted from the system
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.DeclineCommunityRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeclineCommunityRequest(_id INT, _community INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.community_requests
    WHERE user_id = _id AND community = _community;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Community request for user %s in community %s not found', _id, _community);
    END IF;

    DELETE FROM starexec.users
    USING user_roles
    WHERE users.email = user_roles.email
    AND users.id = _id
    AND user_roles.role = 'unauthorized';
END;
$$ LANGUAGE plpgsql;

-- Returns the community request associated with given user id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetCommunityRequestById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityRequestById(_id INT)
RETURNS TABLE(user_id INT, community INT, code VARCHAR(36), message TEXT, created TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT cr.user_id, cr.community, cr.code, cr.message, cr.created
    FROM starexec.community_requests cr
    WHERE cr.user_id = _id;
END;
$$ LANGUAGE plpgsql;

-- See if a request already exists from this user to this community
DROP FUNCTION IF EXISTS starexec.GetCommunityRequestForUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityRequestForUser(_user INT, _community INT)
RETURNS TABLE(result INT) AS $$
BEGIN
    RETURN QUERY
    SELECT 1
    FROM starexec.community_requests cr
    WHERE cr.community = _community
      AND cr.user_id = _user;
END;
$$ LANGUAGE plpgsql;

-- Returns the community request associated with the given activation code
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetCommunityRequestByCode CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityRequestByCode(_code VARCHAR(36))
RETURNS TABLE(user_id INT, community INT, code VARCHAR(36), message TEXT, created TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT cr.user_id, cr.community, cr.code, cr.message, cr.created
    FROM starexec.community_requests cr
    WHERE cr.code = _code;
END;
$$ LANGUAGE plpgsql;

-- Looks for an activation code, and if successful, removes it from VERIFY,
-- then adds an entry to USER_ROLES
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.RedeemActivationCode CASCADE;
CREATE OR REPLACE FUNCTION starexec.RedeemActivationCode(_code VARCHAR(36))
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    IF EXISTS(SELECT v.code FROM starexec.verify v WHERE v.code = _code) THEN
        SELECT v.user_id INTO _id
        FROM starexec.verify v
        WHERE v.code = _code;

        DELETE FROM starexec.verify
        WHERE code = _code;
    END IF;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Redeems a given password reset code by deleting the corresponding entry
-- in pass_reset_request and returning the user_id of that deleted entry
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.RedeemPassResetRequestByCode CASCADE;
CREATE OR REPLACE FUNCTION starexec.RedeemPassResetRequestByCode(_code VARCHAR(36))
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    SELECT prr.user_id INTO _id
    FROM starexec.pass_reset_request prr
    WHERE prr.code = _code;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Password reset request with code %s not found', _code);
    END IF;
    DELETE FROM starexec.pass_reset_request
    WHERE code = _code;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Password reset request with code %s not found', _code);
    END IF;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of community requests waiting approval
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetCommunityRequestCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityRequestCount()
RETURNS TABLE(requestCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS requestCount
    FROM starexec.community_requests;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of community requests waiting approval for the specified community.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetCommunityRequestCountForCommunity CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityRequestCountForCommunity(_communityId INT)
RETURNS TABLE(requestCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS requestCount
    FROM starexec.community_requests cr
    WHERE cr.community = _communityId;
END;
$$ LANGUAGE plpgsql;

-- Creates a change email request for user with _userId.
-- The email the the user is requesting to change to is _newEmail.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.AddChangeEmailRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddChangeEmailRequest(_userId INT, _newEmail VARCHAR(64), _code VARCHAR(36))
RETURNS VOID AS $$
BEGIN
    INSERT INTO change_email_requests (user_id, new_email, code, requested_at)
    VALUES (_userId, _newEmail, _code, NOW())
    ON CONFLICT (user_id) DO UPDATE SET
        new_email = EXCLUDED.new_email,
        code = EXCLUDED.code,
        requested_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- Gets a change email request for user with id _userId
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetChangeEmailRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetChangeEmailRequest(_userId INT)
RETURNS TABLE(user_id INT, new_email VARCHAR(64), code VARCHAR(36)) AS $$
BEGIN
    RETURN QUERY
    SELECT cer.user_id, cer.new_email, cer.code FROM starexec.change_email_requests cer
    WHERE cer.user_id = _userId;
END;
$$ LANGUAGE plpgsql;

-- Deletes the change email request associated with the user with id _userId.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.DeleteChangeEmailRequest CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteChangeEmailRequest(_userId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.change_email_requests
    WHERE user_id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Change email request for user %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- RunscriptErrors PROCEDURES
-- ================================================================================

-- Description: This file contains all Runscript Error procedures

DROP ROUTINE IF EXISTS starexec.RunscriptError(VARCHAR, INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.RunscriptError(node VARCHAR(32), jobPairId INT, stage INT)
AS $$
DECLARE
    _node_id INT;
BEGIN
    SELECT n.id INTO _node_id
    FROM starexec.nodes n
    WHERE n.name = node;

    INSERT INTO runscript_errors (node_id, job_pair_id)
    VALUES (_node_id, jobPairId);

    CALL UpdatePairStatus(jobPairId, 11);
    CALL UpdateLaterStageStatuses(jobPairId, stage, 11);
    CALL SetRunStatsForLaterStagesToZero(jobPairId, stage);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetRunscriptErrorsCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunscriptErrorsCount(_begin TIMESTAMP, _end TIMESTAMP)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS count
    FROM starexec.runscript_errors re
    WHERE re.time >= _begin
      AND re.time <= _end;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetRunscriptErrors CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRunscriptErrors(_begin TIMESTAMP, _end TIMESTAMP)
RETURNS TABLE(node VARCHAR(32), job_pair_id INT, "time" TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT n.name AS node, re.job_pair_id, re.time AS "time"
    FROM starexec.runscript_errors re
    JOIN nodes n ON n.id = re.node_id
    WHERE re.time >= _begin
      AND re.time <= _end;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Settings PROCEDURES
-- ================================================================================

-- This file contains procedures for DefaultSettings functionality

-- Gets a settings profile given its id
-- NOTE: Column aliases must stay in sync with org.starexec.data.database.Settings.resultsToSettings
DROP FUNCTION IF EXISTS starexec.getProfileById CASCADE;
CREATE OR REPLACE FUNCTION starexec.getProfileById(_id INT)
RETURNS TABLE(id INT, primId INT, post_processor INT, cpu_timeout INT, clock_timeout INT, dependencies_enabled BOOLEAN, maximumMemory BIGINT, defaultSolver INT, benchProcessor INT, pre_processor INT, settingType INT, name VARCHAR(32), benchmarkingFramework VARCHAR(16)) AS $$
BEGIN
    RETURN QUERY
    SELECT ds.id,
           ds.prim_id AS primId,
           ds.post_processor,
           ds.cpu_timeout,
           ds.clock_timeout,
           ds.dependencies_enabled,
           ds.maximum_memory AS maximumMemory,
           ds.default_solver AS defaultSolver,
           ds.bench_processor AS benchProcessor,
           ds.pre_processor,
           ds.setting_type AS settingType,
           ds.name,
           ds.benchmarking_framework AS benchmarkingFramework
    FROM starexec.default_settings ds WHERE ds.id = _id;
END;
$$ LANGUAGE plpgsql;

-- NOTE: Column aliases must stay in sync with org.starexec.data.database.Settings.resultsToSettings
DROP FUNCTION IF EXISTS starexec.GetDefaultSettingsByIdAndType CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDefaultSettingsByIdAndType(_prim_id INT, _type INT)
RETURNS TABLE(id INT, primId INT, post_processor INT, cpu_timeout INT, clock_timeout INT, dependencies_enabled BOOLEAN, maximumMemory BIGINT, defaultSolver INT, benchProcessor INT, pre_processor INT, settingType INT, name VARCHAR(32), benchmarkingFramework VARCHAR(16)) AS $$
BEGIN
    RETURN QUERY
    SELECT ds.id,
           ds.prim_id AS primId,
           ds.post_processor,
           ds.cpu_timeout,
           ds.clock_timeout,
           ds.dependencies_enabled,
           ds.maximum_memory AS maximumMemory,
           ds.default_solver AS defaultSolver,
           ds.bench_processor AS benchProcessor,
           ds.pre_processor,
           ds.setting_type AS settingType,
           ds.name,
           ds.benchmarking_framework AS benchmarkingFramework
    FROM starexec.default_settings ds WHERE ds.prim_id = _prim_id AND ds.setting_type = _type;
END;
$$ LANGUAGE plpgsql;

-- Checks to see whether the given benchmark is a community default for any community
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsBenchACommunityDefault CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsBenchACommunityDefault(_benchId INT)
RETURNS TABLE(benchDefault BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS benchDefault
    FROM starexec.default_settings ds
    WHERE ds.default_benchmark = _benchId AND ds.setting_type = 1;
END;
$$ LANGUAGE plpgsql;

-- Checks to see whether the given solver is a community default for any community
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsSolverACommunityDefault CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsSolverACommunityDefault(_solverId INT)
RETURNS TABLE(solverDefault BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverDefault
    FROM starexec.default_settings ds
    WHERE ds.default_solver = _solverId AND ds.setting_type = 1;
END;
$$ LANGUAGE plpgsql;

-- Updates the maximum memory setting for a default_settings tuple
DROP FUNCTION IF EXISTS starexec.SetMaximumMemorySetting CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetMaximumMemorySetting(_id INT, _bytes BIGINT)
RETURNS VOID AS $$
BEGIN
    UPDATE default_settings
    SET maximum_memory = _bytes
    WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Maximum memory setting for %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the default settings object with the given id
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.SetDefaultSettingsById CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetDefaultSettingsById(_id INT, _num INT, _setting INT)
RETURNS VOID AS $$
BEGIN
    CASE _num
        WHEN 1 THEN
            UPDATE default_settings
            SET post_processor = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 2 THEN
            UPDATE default_settings
            SET cpu_timeout = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 3 THEN
            UPDATE default_settings
            SET clock_timeout = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 4 THEN
            UPDATE default_settings
            SET dependencies_enabled = (_setting = 1)
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 5 THEN
            UPDATE default_settings
            SET default_benchmark = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 6 THEN
            UPDATE default_settings
            SET pre_processor = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 7 THEN
            UPDATE default_settings
            SET default_solver = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
        WHEN 8 THEN
            UPDATE default_settings
            SET bench_processor = _setting
            WHERE id = _id;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'P0002',
                    MESSAGE = format('Default settings %s not found', _id);
            END IF;
    END CASE;
END;
$$ LANGUAGE plpgsql;

-- Insert a default setting of a space given by id when it's initiated.
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.CreateDefaultSettings CASCADE;
CREATE OR REPLACE FUNCTION starexec.CreateDefaultSettings(_prim_id INT, _pp INT, _cto INT, _clto INT, _dp BOOLEAN, _dm BIGINT, _defaultSolver INT, _benchProc INT, _preProc INT, _type INT, _name VARCHAR(32), _benchmarkingFramework VARCHAR(16))
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO default_settings (prim_id, post_processor, cpu_timeout, clock_timeout, dependencies_enabled, maximum_memory, default_solver, bench_processor, pre_processor, setting_type, name, benchmarking_framework)
    VALUES (_prim_id, _pp, _cto, _clto, _dp, _dm, _defaultSolver, _benchProc, _preProc, _type, _name, _benchmarkingFramework)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Insert a default setting of a space given by id when it's initiated.
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.UpdateDefaultSettings CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateDefaultSettings(_pp INT, _cto INT, _clto INT, _dp BOOLEAN, _dm BIGINT, _defaultSolver INT, _benchProc INT, _preProc INT, _benchmarkingFramework VARCHAR(16), _id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE default_settings SET
        post_processor = _pp,
        cpu_timeout = _cto,
        clock_timeout = _clto,
        dependencies_enabled = _dp,
        maximum_memory = _dm,
        default_solver = _defaultSolver,
        bench_processor = _benchProc,
        pre_processor = _preProc,
        benchmarking_framework = _benchmarkingFramework
    WHERE id = _id;
END;
$$ LANGUAGE plpgsql;

-- deletes a DefaultSettings profile
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.DeleteDefaultSettings CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteDefaultSettings(_id INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.default_settings WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Default settings %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.DeleteAllDefaultBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteAllDefaultBenchmarks(_settingId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.default_bench_assoc WHERE setting_id = _settingId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetDefaultProfileForUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetDefaultProfileForUser(_uid INT, _sid INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users SET default_settings_profile = _sid WHERE id = _uid;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Default profile setting for user %s not found', _uid);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetDefaultProfileForUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDefaultProfileForUser(_uid INT)
RETURNS TABLE(default_settings_profile INT) AS $$
BEGIN
    RETURN QUERY
    SELECT u.default_settings_profile FROM starexec.users u WHERE u.id = _uid;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.AddDefaultBenchmark CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddDefaultBenchmark(_settingId INT, _benchId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO default_bench_assoc (setting_id, bench_id)
    VALUES (_settingId, _benchId);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetDefaultBenchmarksForSetting CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDefaultBenchmarksForSetting(_settingId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(256), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256)) AS $$
BEGIN
    RETURN QUERY
    SELECT b.id, b.user_id, b.name, b.uploaded, b.path, b.description, b.downloadable, b.disk_size, b.deleted, b.recycled, b.recycled_original_name
    FROM starexec.benchmarks b JOIN default_bench_assoc dba ON b.id = dba.bench_id
        JOIN default_settings ds ON dba.setting_id = ds.id
    WHERE _settingId = ds.id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetDefaultBenchmarkIdsForSetting CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDefaultBenchmarkIdsForSetting(_settingId INT)
RETURNS TABLE(default_bench_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT b.id AS default_bench_id
    FROM starexec.benchmarks b JOIN default_bench_assoc dba ON b.id = dba.bench_id
      JOIN default_settings ds ON dba.setting_id = ds.id
    WHERE _settingId = ds.id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.DeleteDefaultBenchmark CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteDefaultBenchmark(_settingId INT, _benchId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.default_bench_assoc
    WHERE setting_id = _settingId AND bench_id = _benchId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Default benchmark %s for setting %s not found', _benchId, _settingId);
    END IF;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Solvers PROCEDURES
-- ================================================================================

-- Description: This file contains all solver-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a solver and returns the solver ID
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.AddSolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddSolver(_userId INT, _name VARCHAR(128), _downloadable BOOLEAN, _path TEXT, _description TEXT, _diskSize BIGINT, _type INT, _build_status INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    UPDATE users SET disk_size = disk_size + _diskSize WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found when adding solver', _userId);
    END IF;
    INSERT INTO solvers (user_id, name, uploaded, path, description, downloadable, disk_size, executable_type, build_status)
    VALUES (_userId, _name, NOW(), _path, _description, _downloadable, _diskSize, _type, _build_status)
    RETURNING id INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Gets all solvers that reside in public spaces
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetPublicSolvers CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPublicSolvers()
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT s.id AS solver_id,
                   s.user_id AS solver_user_id,
                   s.name AS solver_name,
                   s.uploaded AS solver_uploaded,
                   s.path AS solver_path,
                   s.description AS solver_description,
                   s.downloadable AS solver_downloadable,
                   s.disk_size AS solver_disk_size,
                   s.deleted AS solver_deleted,
                   s.recycled AS solver_recycled,
                   s.recycled_original_name AS solver_recycled_original_name,
                   s.executable_type AS solver_executable_type,
                   s.build_status AS solver_build_status
    FROM starexec.solvers s
    JOIN solver_assoc sa ON sa.solver_id = s.id
    JOIN spaces sp ON sp.id = sa.space_id
    WHERE sp.public_access = true AND s.deleted = false AND s.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Gets the number of conflicting benchmarks a given config was run against for a stage.
-- A conflicting benchmark is a benchmark for which two solvers gave different results.
DROP FUNCTION IF EXISTS starexec.GetConflictsForConfigInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetConflictsForConfigInJob(_jobId INT, _configId INT, _stageNumber INT)
RETURNS TABLE(conflicting_benchmarks BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT jp_o.bench_id) AS conflicting_benchmarks
    FROM starexec.jobs j_o JOIN job_pairs jp_o ON j_o.id = jp_o.job_id
        JOIN jobpair_stage_data jpsd_o ON jpsd_o.jobpair_id = jp_o.id
        JOIN job_attributes ja_o ON ja_o.pair_id = jp_o.id
        JOIN
            (SELECT jp.bench_id
            FROM starexec.jobs j join job_pairs jp ON j.id = jp.job_id
                JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id = jp.id
                JOIN job_attributes ja ON ja.pair_id = jp.id
            WHERE j.id = _jobId
                AND ja.stage_number = _stageNumber
                AND ja.attr_key = 'starexec-result'
                AND ja.attr_value != 'starexec-unknown'
            GROUP BY jp.bench_id
            HAVING COUNT(DISTINCT ja.attr_value) > 1) AS conflicting
        ON jp_o.bench_id = conflicting.bench_id
    WHERE jpsd_o.config_id = _configId
        AND ja_o.attr_key = 'starexec-result'
        AND ja_o.attr_value != 'starexec-unknown';
END;
$$ LANGUAGE plpgsql;

-- Gets the data for conflicting benchmarks in the job.
-- A conflicting benchmark is a benchmark for which two solvers gave different results.
DROP FUNCTION IF EXISTS starexec.GetConflictingBenchmarksForConfigInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetConflictingBenchmarksForConfigInJob(_jobId INT, _configId INT, _stageNumber INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(256), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256)) AS $$
BEGIN
    RETURN QUERY
    SELECT b_o.id, b_o.user_id, b_o.name, b_o.uploaded, b_o.path, b_o.description, b_o.downloadable, b_o.disk_size, b_o.deleted, b_o.recycled, b_o.recycled_original_name
    FROM starexec.jobs j_o JOIN job_pairs jp_o ON j_o.id = jp_o.job_id
        JOIN jobpair_stage_data jpsd_o ON jpsd_o.jobpair_id = jp_o.id
        JOIN job_attributes ja_o ON ja_o.pair_id = jp_o.id
        JOIN benchmarks b_o ON b_o.id = jp_o.bench_id
        JOIN
        (SELECT jp.bench_id
         FROM starexec.jobs j join job_pairs jp ON j.id = jp.job_id
             JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id = jp.id
             JOIN job_attributes ja ON ja.pair_id = jp.id
         WHERE j.id = _jobId
                     AND ja.stage_number = _stageNumber
                     AND ja.attr_key = 'starexec-result'
                     AND ja.attr_value != 'starexec-unknown'
         GROUP BY jp.bench_id
         HAVING COUNT(DISTINCT ja.attr_value) > 1) AS conflicting
            ON jp_o.bench_id = conflicting.bench_id
    WHERE jpsd_o.config_id = _configId
                AND ja_o.attr_key = 'starexec-result'
                AND ja_o.attr_value != 'starexec-unknown'
    GROUP BY b_o.id, b_o.user_id, b_o.name, b_o.uploaded, b_o.path, b_o.description, b_o.downloadable, b_o.disk_size, b_o.deleted, b_o.recycled, b_o.recycled_original_name;
END;
$$ LANGUAGE plpgsql;

-- Gets the all of the solvers, configs, and results run on a benchmark in a job.
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetSolverConfigResultsForBenchmarkInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverConfigResultsForBenchmarkInJob(_jobId INT, _benchId INT, _stageNum INT)
RETURNS TABLE(
    "s.id" INT,
    "s.user_id" INT,
    "s.name" VARCHAR(255),
    "s.uploaded" TIMESTAMP,
    "s.path" TEXT,
    "s.description" TEXT,
    "s.downloadable" BOOLEAN,
    "s.disk_size" BIGINT,
    executable_type INT,
    recycled BOOLEAN,
    deleted BOOLEAN,
    "s.build_status" INT,
    "c.id" INT,
    "c.description" TEXT,
    "c.name" VARCHAR(128),
    "c.solver_id" INT,
    attr_value VARCHAR(128)
) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.user_id, s.name, s.uploaded, s.path, s.description,
            s.downloadable, s.disk_size, s.executable_type, s.recycled, s.deleted,
            s.build_status,
            c.id, c.description, c.name, c.solver_id,
            ja.attr_value
    FROM starexec.jobs j JOIN job_pairs jp ON j.id = jp.job_id
            JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id = jp.id
            JOIN solvers s ON jpsd.solver_id = s.id
            JOIN configurations c ON jpsd.config_id = c.id
            JOIN job_attributes ja ON ja.pair_id = jp.id
    WHERE j.id = _jobId
            AND jp.bench_id = _benchId
            AND ja.attr_key = 'starexec-result'
            AND ja.attr_value != 'starexec-unknown'
            AND jpsd.stage_number = _stageNum
            AND (c.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;


-- Adds a Space/Solver association
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.AddSolverAssociation CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddSolverAssociation(_spaceId INT, _solverId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO solver_assoc VALUES (_spaceId, _solverId)
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Adds a run configuration to the specified solver
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.AddConfiguration CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddConfiguration(_solverId INT, _name VARCHAR(128), _description TEXT, _time TIMESTAMP)
RETURNS INT AS $$
DECLARE
    configId INT;
BEGIN
    INSERT INTO configurations (solver_id, name, description, updated)
    VALUES (_solverId, _name, _description, _time)
    RETURNING id INTO configId;
    RETURN configId;
END;
$$ LANGUAGE plpgsql;

-- Deletes a configuration given that configuration's id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.DeleteConfigurationById CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteConfigurationById(_configId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE configurations SET deleted = true
    WHERE id = _configId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Configuration %s not found', _configId);
    END IF;
    PERFORM UpdateConfigDeletedInSolvers(_configId, true);
END;
$$ LANGUAGE plpgsql;

-- Updates the solvers table to properly reflect that the corresponding configuration has been deleted
-- Author: Alexander Brown
DROP FUNCTION IF EXISTS starexec.UpdateConfigDeletedInSolvers CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateConfigDeletedInSolvers(_configId INT, _configDeleted BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE solvers
    SET config_deleted = _configDeleted
    WHERE id IN (
        SELECT solver_id FROM starexec.configurations WHERE id = _configId
    );
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver for configuration %s not found', _configId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Deletes a solver given that solver's id
-- Author: Todd Elvers + Eric Burns
DROP FUNCTION IF EXISTS starexec.SetSolverToDeletedById CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetSolverToDeletedById(_solverId INT)
RETURNS TEXT AS $$
DECLARE
    _path TEXT;
BEGIN
        -- Postgres doesn't support UPDATE ... JOIN syntax; use UPDATE ... FROM starexec.... WHERE
        UPDATE users
        SET disk_size = users.disk_size - solvers.disk_size
        FROM starexec.solvers
        WHERE solvers.user_id = users.id
            AND solvers.id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found when updating owner disk usage', _solverId);
    END IF;

    SELECT s.path INTO _path FROM starexec.solvers s WHERE s.id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
    UPDATE solvers
    SET deleted = true, disk_size = 0
    WHERE id = _solverId;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
    RETURN _path;
END;
$$ LANGUAGE plpgsql;

-- Gets the IDs of all the spaces associated with the given solver
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetAssociatedSpaceIdsBySolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAssociatedSpaceIdsBySolver(_solverId INT)
RETURNS TABLE(space_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT sa.space_id
    FROM starexec.solver_assoc sa
    WHERE sa.solver_id = _solverId;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the configurations with the given id
-- Author: Tyler Jensen
-- NOTE: only retrieves configurations that have _not_ been marked as deleted (Alexander Brown)
DROP FUNCTION IF EXISTS starexec.GetConfiguration CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetConfiguration(_id INT)
RETURNS TABLE(id INT, solver_id INT, name VARCHAR(128), description TEXT, updated TIMESTAMP, deleted BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT c.id, c.solver_id, c.name, c.description, c.updated, (c.deleted::BOOLEAN) AS deleted
    FROM starexec.configurations c
    WHERE c.id = _id AND (c.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;

-- Retrieves the configurations with the given id, including deleted configs
-- Author: Alexander Brown
DROP FUNCTION IF EXISTS starexec.GetConfigurationIncludeDeleted CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetConfigurationIncludeDeleted(_id INT)
RETURNS TABLE(id INT, solver_id INT, name VARCHAR(128), description TEXT, updated TIMESTAMP, deleted BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT c.id, c.solver_id, c.name, c.description, c.updated, c.deleted
    FROM starexec.configurations c
    WHERE c.id = _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllSolversInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllSolversInJob(_jobId INT)
RETURNS TABLE(solver_id INT, solver_name VARCHAR(128)) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT jpsd.solver_id, jpsd.solver_name
    FROM starexec.jobpair_stage_data jpsd
    INNER JOIN job_pairs jp ON jpsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllConfigsInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllConfigsInJob(_jobId INT)
RETURNS TABLE(config_id INT, config_name VARCHAR(128)) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT jpsd.config_id, jpsd.config_name
    FROM starexec.jobpair_stage_data jpsd
    INNER JOIN job_pairs jp ON jpsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllConfigIdsInJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllConfigIdsInJob(_jobId INT)
RETURNS TABLE(config_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT jpsd.config_id
    FROM starexec.jobpair_stage_data jpsd
    INNER JOIN job_pairs jp ON jpsd.jobpair_id = jp.id
    WHERE jp.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the configurations that belong to a solver with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetConfigsForSolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetConfigsForSolver(_id INT)
RETURNS TABLE(id INT, solver_id INT, name VARCHAR(128), description TEXT, updated TIMESTAMP, deleted BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT c.id, c.solver_id, c.name, c.description, c.updated, c.deleted
    FROM starexec.configurations c
    WHERE c.solver_id = _id AND (c.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;


-- Retrieves all solvers belonging to a space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSpaceSolversById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpaceSolversById(_id INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id AS solver_id,
           s.user_id AS solver_user_id,
           s.name AS solver_name,
           s.uploaded AS solver_uploaded,
           s.path AS solver_path,
           s.description AS solver_description,
           s.downloadable AS solver_downloadable,
           s.disk_size AS solver_disk_size,
           s.deleted AS solver_deleted,
           s.recycled AS solver_recycled,
           s.recycled_original_name AS solver_recycled_original_name,
           s.executable_type AS solver_executable_type,
           s.build_status AS solver_build_status
    FROM starexec.solvers s
    JOIN solver_assoc sa ON sa.solver_id = s.id
    WHERE s.deleted = false AND s.recycled = false AND sa.space_id = _id;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the solver associated with the configuration with the given id
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.GetSolverIdByConfigId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverIdByConfigId(_id INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT c.solver_id AS id
    FROM starexec.configurations c
    WHERE c.id = _id AND (c.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;

-- Retrieves the solver with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSolverById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverById(_id INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id AS solver_id,
           s.user_id AS solver_user_id,
           s.name AS solver_name,
           s.uploaded AS solver_uploaded,
           s.path AS solver_path,
           s.description AS solver_description,
           s.downloadable AS solver_downloadable,
           s.disk_size AS solver_disk_size,
           s.deleted AS solver_deleted,
           s.recycled AS solver_recycled,
           s.recycled_original_name AS solver_recycled_original_name,
           s.executable_type AS solver_executable_type,
           s.build_status AS solver_build_status
    FROM starexec.solvers s
    WHERE s.id = _id AND s.deleted = false AND s.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the solver with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSolverByIdIncludeDeleted CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverByIdIncludeDeleted(_id INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id AS solver_id,
           s.user_id AS solver_user_id,
           s.name AS solver_name,
           s.uploaded AS solver_uploaded,
           s.path AS solver_path,
           s.description AS solver_description,
           s.downloadable AS solver_downloadable,
           s.disk_size AS solver_disk_size,
           s.deleted AS solver_deleted,
           s.recycled AS solver_recycled,
           s.recycled_original_name AS solver_recycled_original_name,
           s.executable_type AS solver_executable_type,
           s.build_status AS solver_build_status
    FROM starexec.solvers s
    WHERE s.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSolverCountInSpaceWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverCountInSpaceWithQuery(_spaceId INT, _query TEXT)
RETURNS TABLE(solverCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverCount
    FROM starexec.solver_assoc sa
        JOIN solvers s ON s.id = sa.solver_id
    WHERE _spaceId = sa.space_id AND
            (s.name LIKE CONCAT('%', _query, '%')
            OR s.description LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;

-- Retrieves the solvers owned by a given user id
-- Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetSolversByOwner CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolversByOwner(_userId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id AS solver_id,
           s.user_id AS solver_user_id,
           s.name AS solver_name,
           s.uploaded AS solver_uploaded,
           s.path AS solver_path,
           s.description AS solver_description,
           s.downloadable AS solver_downloadable,
           s.disk_size AS solver_disk_size,
           s.deleted AS solver_deleted,
           s.recycled AS solver_recycled,
           s.recycled_original_name AS solver_recycled_original_name,
           s.executable_type AS solver_executable_type,
           s.build_status AS solver_build_status
    FROM starexec.solvers s
    WHERE s.user_id = _userId AND s.deleted = false AND s.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of public spaces a solver is in
-- Benton McCune
DROP FUNCTION IF EXISTS starexec.IsSolverPublic CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsSolverPublic(_solverId INT)
RETURNS TABLE(solverPublic BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverPublic
    FROM starexec.solver_assoc sa
    WHERE sa.solver_id = _solverId
    AND (SELECT COUNT(*) > 0 FROM starexec.IsPublic(sa.space_id));
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IsSolverDeleted CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsSolverDeleted(_solverId INT)
RETURNS TABLE(solverDeleted BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverDeleted
    FROM starexec.solvers s
    WHERE s.deleted = true AND s.id = _solverId;
END;
$$ LANGUAGE plpgsql;

-- Removes the association between a solver and a given space;
-- Author: Todd Elvers + Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveSolverFromSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveSolverFromSpace(_solverId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
    IF _spaceId >= 0 THEN
        DELETE FROM starexec.solver_assoc
        WHERE solver_id = _solverId
        AND space_id = _spaceId;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Solver %s association with space %s not found', _solverId, _spaceId);
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the disk_size attribute of a given solver
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.UpdateSolverDiskSize CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateSolverDiskSize(_solverId INT, _newDiskSize BIGINT)
RETURNS VOID AS $$
BEGIN
        -- Postgres doesn't support UPDATE ... JOIN syntax; use UPDATE ... FROM starexec.... WHERE
        UPDATE users
        SET disk_size = (users.disk_size - solvers.disk_size) + _newDiskSize
        FROM starexec.solvers
        WHERE solvers.user_id = users.id
            AND solvers.id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found when updating owner disk usage', _solverId);
    END IF;
    UPDATE solvers
    SET disk_size = _newDiskSize
    WHERE id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the details associated with a given configuration
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.UpdateConfigurationDetails CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateConfigurationDetails(_configId INT, _name VARCHAR(128), _description TEXT, _time TIMESTAMP)
RETURNS VOID AS $$
BEGIN
    UPDATE configurations
    SET name = _name,
        description = _description,
        updated = _time
    WHERE id = _configId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Configuration %s not found', _configId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the details associated with a given solver
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.UpdateSolverDetails CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateSolverDetails(_solverId INT, _name VARCHAR(128), _description TEXT, _downloadable BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE solvers
    SET name = _name,
        description = _description,
        downloadable = _downloadable
    WHERE id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Get the total count of the solvers belong to a specific user
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetSolverCountByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverCountByUser(_userId INT)
RETURNS TABLE(solverCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverCount
    FROM starexec.solvers s
    WHERE s.user_id = _userId AND s.deleted = false AND s.recycled = false;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSolverCountByUserWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolverCountByUserWithQuery(_userId INT, _query TEXT)
RETURNS TABLE(solverCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverCount
    FROM starexec.solvers s
    WHERE s.user_id = _userId AND s.deleted = false AND s.recycled = false AND
            (s.name LIKE CONCAT('%', _query, '%')
            OR s.description LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;

-- Sets the recycled attribute to the given value for the given solver
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetSolverRecycledValue CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetSolverRecycledValue(_solverId INT, _recycled BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE solvers
    SET recycled = _recycled
    WHERE id = _solverId;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Checks to see whether the "recycled" flag is set for the given solver
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsSolverRecycled CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsSolverRecycled(_solverId INT)
RETURNS TABLE(recycled BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT s.recycled FROM starexec.solvers s
    WHERE s.id = _solverId;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledSolverCountByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledSolverCountByUser(_userId INT, _query TEXT)
RETURNS TABLE(solverCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS solverCount
    FROM starexec.solvers s
    WHERE s.user_id = _userId AND s.recycled = true AND s.deleted = false AND
            (s.name LIKE CONCAT('%', _query, '%')
            OR s.description LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;

-- Gets the path to every recycled solver a user has
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledSolverPaths CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledSolverPaths(_userId INT)
RETURNS TABLE(path TEXT, id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.path, s.id FROM starexec.solvers s
    WHERE s.recycled = true AND s.user_id = _userId AND s.deleted = false;
END;
$$ LANGUAGE plpgsql;

-- Sets all the solvers the user has in the database to "deleted"
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetRecycledSolversToDeleted CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetRecycledSolversToDeleted(_userId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET disk_size = users.disk_size - (SELECT COALESCE(SUM(s.disk_size), 0) FROM starexec.solvers s WHERE s.user_id = _userId AND s.recycled = true AND s.deleted = false)
    WHERE users.id = _userId;
    UPDATE solvers
    SET deleted = true, disk_size = 0
    WHERE user_id = _userId AND recycled = true AND deleted = false;
END;
$$ LANGUAGE plpgsql;

-- Gets all recycled solver ids a user has in the database
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetRecycledSolverIds CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetRecycledSolverIds(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id FROM starexec.solvers s
    WHERE s.user_id = _userId AND s.recycled = true;
END;
$$ LANGUAGE plpgsql;

-- Permanently removes a solver from the database
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveSolverFromDatabase CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveSolverFromDatabase(_id INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.solvers
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets all the solver ids of solvers that are in at least one space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSolversAssociatedWithSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolversAssociatedWithSpaces()
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT sa.solver_id AS id FROM starexec.solver_assoc sa;
END;
$$ LANGUAGE plpgsql;

-- Gets the solver ids of all solvers associated with at least one pair
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSolversAssociatedWithPairs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolversAssociatedWithPairs()
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT jpsd.solver_id AS id FROM starexec.jobpair_stage_data jpsd;
END;
$$ LANGUAGE plpgsql;

-- Gets the solver ids of all deleted solvers
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetDeletedSolvers CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDeletedSolvers()
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.user_id, s.name, s.uploaded, s.path, s.description, s.downloadable, s.disk_size, s.deleted, s.recycled, s.recycled_original_name, s.executable_type, s.build_status
    FROM starexec.solvers s WHERE s.deleted = true;
END;
$$ LANGUAGE plpgsql;

-- Sets the recycled flag for a single solver back to false
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RestoreSolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.RestoreSolver(_solverId INT)
RETURNS VOID AS $$
BEGIN
    UPDATE solvers
    SET recycled = false
    WHERE id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets the timestamp of the configuration that was most recently added or updated
-- on this solver
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetMaxConfigTimestamp CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetMaxConfigTimestamp(_solverId INT)
RETURNS TABLE(recent TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT MAX(c.updated) AS recent
    FROM starexec.configurations c
    WHERE c.solver_id = _solverId AND (c.deleted::BOOLEAN IS FALSE);
END;
$$ LANGUAGE plpgsql;

-- Gets the ids of every orphaned solver a user owns (orphaned meaning the solver is in no spaces
DROP FUNCTION IF EXISTS starexec.GetOrphanedSolverIds CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetOrphanedSolverIds(_userId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id FROM starexec.solvers s
    LEFT JOIN solver_assoc sa ON sa.solver_id = s.id
    WHERE s.user_id = _userId AND sa.space_id IS NULL;
END;
$$ LANGUAGE plpgsql;

-- returns every solver that shares a space with the given user
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSolversInSharedSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSolversInSharedSpaces(_userId INT)
RETURNS TABLE(id INT, user_id INT, name VARCHAR(128), uploaded TIMESTAMP, path TEXT, description TEXT, downloadable BOOLEAN, disk_size BIGINT, deleted BOOLEAN, recycled BOOLEAN, recycled_original_name VARCHAR(256), executable_type INT, build_status INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT s.id, s.user_id, s.name, s.uploaded, s.path, s.description, s.downloadable, s.disk_size, s.deleted, s.recycled, s.recycled_original_name, s.executable_type, s.build_status
    FROM starexec.solvers s
    JOIN solver_assoc sa ON sa.solver_id = s.id
    JOIN user_assoc ua ON ua.space_id = sa.space_id
    WHERE ua.user_id = _userId;
END;
$$ LANGUAGE plpgsql;

-- Sets the build_status status code of the solver
-- Author: Andrew Lubinus
DROP ROUTINE IF EXISTS starexec.SetSolverBuildStatus(INT, INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.SetSolverBuildStatus(_solverId INT, _build_status INT)
AS $$
BEGIN
    UPDATE solvers
    SET build_status = _build_status
    WHERE id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates path to solver
-- Author: Andrew Lubinus
DROP ROUTINE IF EXISTS starexec.SetSolverPath(INT, TEXT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.SetSolverPath(_solverId INT, _path TEXT)
AS $$
BEGIN
    UPDATE solvers
    SET path = _path
    WHERE id = _solverId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- This deletes the dummy config from a solver built on Starexec
DROP ROUTINE IF EXISTS starexec.DeleteBuildConfig(INT) CASCADE;
CREATE OR REPLACE PROCEDURE starexec.DeleteBuildConfig(_solverId INT)
AS $$
BEGIN
    DELETE FROM starexec.configurations -- dummy configs are deleted but not other configs
    WHERE solver_id = _solverId AND name = 'starexec_build';
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Build configuration for solver %s not found', _solverId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Pauses all JobPairs containing Solver and rebuilds Solver
DROP FUNCTION IF EXISTS starexec.RebuildSolver CASCADE;
CREATE OR REPLACE FUNCTION starexec.RebuildSolver(_solverId INT)
RETURNS VOID AS $$
BEGIN
    -- Pause all jobs containing solver
    UPDATE jobs
    SET paused = TRUE
    WHERE killed = FALSE
    AND deleted = FALSE
    AND buildJob = FALSE
    AND id IN (
        SELECT job_id FROM (
            SELECT jp.job_id, jp.id
            FROM starexec.job_pairs jp
            WHERE jp.id IN (
                SELECT jpsd.jobpair_id
                FROM starexec.jobpair_stage_data jpsd
                WHERE jpsd.solver_id = _solverId
            )
        ) AS jobPairsWithSolver
    );
    -- Pause all jobpairs containing solver
    UPDATE job_pairs
    SET status_code = 20
    WHERE status_code = 1
    AND id IN (
        SELECT jpsd.jobpair_id
        FROM starexec.jobpair_stage_data jpsd
        WHERE jpsd.solver_id = _solverId
    );
    -- Set Solver status to Unbuilt
    UPDATE solvers
        SET build_status = 0, -- 0 = Unbuilt : SolverBuildStatus.java
            path = CONCAT(path, '_src')
        WHERE id = _solverId
        AND build_status = 2;  -- 2 = Built by StarExec
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Spaces PROCEDURES
-- ================================================================================

-- Description: This file contains all space-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new space with the given information
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddSpace(_name VARCHAR(255), _desc TEXT, _locked BOOLEAN, _permission INT, _parent INT, _sticky BOOLEAN)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO spaces (name, created, description, locked, default_permission, sticky_leaders)
    VALUES (_name, NOW(), _desc, _locked, _permission, _sticky);
    SELECT currval('spaces_id_seq') INTO _id;
    -- Update closure table
    INSERT INTO closure (ancestor, descendant)
        SELECT ancestor, _id FROM starexec.closure
        WHERE descendant = _parent
        UNION ALL SELECT _parent, _id UNION SELECT _id, _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Adds a new job space with the given information
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.AddJobSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddJobSpace(_name VARCHAR(255), _job_id INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO job_spaces (name, job_id)
    VALUES (_name, _job_id);
    SELECT currval('job_spaces_id_seq') INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetJobSpaceMaxStages CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetJobSpaceMaxStages(_id INT, _max INT)
RETURNS VOID AS $$
BEGIN
    UPDATE job_spaces SET max_stages=_max WHERE id=_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job space %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Clears entries from the job_space_closure table that are older than the given time
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.ClearOldJobClosureEntries CASCADE;
CREATE OR REPLACE FUNCTION starexec.ClearOldJobClosureEntries(_cutoff TIMESTAMP)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.job_space_closure WHERE last_used < _cutoff;
END;
$$ LANGUAGE plpgsql;

-- Insets a new ancestor/descendant pair into the job space closure table
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.InsertIntoJobSpaceClosure CASCADE;
CREATE OR REPLACE FUNCTION starexec.InsertIntoJobSpaceClosure(_ancestor INT, _descendant INT, _time TIMESTAMP)
RETURNS VOID AS $$
BEGIN
    INSERT INTO job_space_closure (ancestor, descendant, last_used)
    VALUES (_ancestor, _descendant, _time)
    ON CONFLICT (ancestor, descendant) DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Adds an association between two spaces
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AssociateSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.AssociateSpaces(_parentId INT, _childId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO set_assoc
    VALUES (_parentId, _childId)
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Moves an existing space to the new parent
-- Note: The order of arguments is (Destination, Source) to match
--       AssociateSpaces, which was apparently written by Intel engineers
DROP FUNCTION IF EXISTS starexec.MoveSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.MoveSpace(_parentId INT, _childId INT)
RETURNS VOID AS $$
BEGIN
    -- remove all existing closures for this child space
    DELETE FROM starexec.closure WHERE descendant = _childId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Closure entries for space %s not found', _childId);
    END IF;
    UPDATE set_assoc SET space_id = _parentId WHERE child_id = _childId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space association for child %s not found', _childId);
    END IF;
    -- insert as ancestors of parent space
    INSERT INTO closure (ancestor, descendant)
        SELECT ancestor, _childId AS descendant -- all ancestors of parent space
        FROM starexec.closure
        WHERE descendant = _parentId
        UNION ALL SELECT _parentId, _childId    -- parent space
        UNION SELECT _childId, _childId;        -- child space
END;
$$ LANGUAGE plpgsql;

-- Rebuild closure entries for a space, assuming its parent has fully correct
-- closure entries. When moving a space, we must call this for each child space,
-- using a pre-order traversal of the tree.
DROP FUNCTION IF EXISTS starexec.RebuildSpaceClosures CASCADE;
CREATE OR REPLACE FUNCTION starexec.RebuildSpaceClosures(_childId INT)
RETURNS VOID AS $$
DECLARE
    _parentId INT;
BEGIN
    SELECT space_id INTO _parentId FROM starexec.set_assoc WHERE child_id = _childId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Parent space for child %s not found', _childId);
    END IF;
    -- remove all existing closures for this child space
    DELETE FROM starexec.closure WHERE descendant = _childId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Closure entries for space %s not found', _childId);
    END IF;
    -- insert as ancestors of parent space
    INSERT INTO closure (ancestor, descendant)
        SELECT ancestor, _childId AS descendant -- all ancestors of parent space
        FROM starexec.closure
        WHERE descendant = _parentId
        UNION ALL SELECT _parentId, _childId    -- parent space
        UNION SELECT _childId, _childId;        -- this space
END;
$$ LANGUAGE plpgsql;

-- Adds an association between two job spaces
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.AssociateJobSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.AssociateJobSpaces(_parentId INT, _childId INT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO job_space_assoc
    VALUES (_parentId, _childId)
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Gets all the descendants of a space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetDescendantsOfSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDescendantsOfSpace(_spaceId INT)
RETURNS TABLE(descendant INT) AS $$
BEGIN
    RETURN QUERY
    SELECT c.descendant
    FROM starexec.closure c
    WHERE c.ancestor = _spaceId AND NOT c.descendant = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Gets all the leaders of a space
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetLeadersBySpaceId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetLeadersBySpaceId(_id INT)
RETURNS TABLE(email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), id INT, created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT u.email, u.first_name, u.last_name, u.institution, u.id, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota
    FROM starexec.users u
    JOIN user_assoc ua ON ua.user_id = u.id
    JOIN spaces s ON s.id = ua.space_id
    JOIN permissions p ON p.id = ua.permission
    WHERE s.id = _id AND p.is_leader = TRUE;
END;
$$ LANGUAGE plpgsql;

-- Returns basic space information for the space with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSpaceById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpaceById(_id INT)
RETURNS TABLE(id INT, name VARCHAR(255), created TIMESTAMP, description TEXT, locked BOOLEAN, default_permission INT, public_access BOOLEAN, sticky_leaders BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
    FROM starexec.spaces s
    WHERE s.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Returns basic space information for the space with the given id
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobSpaceById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobSpaceById(_id INT)
RETURNS TABLE(id INT, name VARCHAR(255), job_id INT, max_stages INT) AS $$
BEGIN
    RETURN QUERY
    SELECT js.id, js.name, js.job_id, js.max_stages
    FROM starexec.job_spaces js
    WHERE js.id = _id;
END;
$$ LANGUAGE plpgsql;


-- Gets all the spaces that a user has access to
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetSpacesByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpacesByUser(_userId INT)
RETURNS TABLE(name VARCHAR(255), id INT, locked BOOLEAN, description TEXT, sticky_leaders BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT space.name, space.id, space.locked, space.description, space.sticky_leaders
    FROM starexec.user_assoc ua
    JOIN spaces space ON space.id = ua.space_id
    WHERE ua.user_id = _userId;
END;
$$ LANGUAGE plpgsql;

-- Gets all the spaces
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetAllSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllSpaces()
RETURNS TABLE(name VARCHAR(255), id INT, locked BOOLEAN, description TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.name, s.id, s.locked, s.description
    FROM starexec.spaces s;
END;
$$ LANGUAGE plpgsql;

-- Returns all spaces a user can see in the hierarchy rooted at the given space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubSpaceHierarchyById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpaceHierarchyById(_spaceId INT, _userId INT)
RETURNS TABLE(name VARCHAR(255), description TEXT, locked BOOLEAN, id INT) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- If we get an invalid ID, return the root space (the space with the minimum ID)
        RETURN QUERY
        SELECT s.name, s.description, s.locked, s.id
        FROM starexec.spaces s
        WHERE s.id = (SELECT MIN(spaces.id) FROM starexec.spaces);
    ELSE
        -- Else find all children spaces that are an ancestor of a space the user is apart of
        RETURN QUERY
        SELECT DISTINCT s.name, s.description, s.locked, s.id
        FROM starexec.closure c
        JOIN spaces s ON s.id = c.descendant
        JOIN user_assoc ua ON ((ua.user_id = _userId OR s.public_access) AND ua.space_id = c.descendant)
        WHERE c.ancestor = _spaceId AND c.ancestor != c.descendant;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all spaces belonging to the space with the given id.
-- Author: Tyler Jensen & Benton McCune & Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubSpacesById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpacesById(_spaceId INT, _userId INT)
RETURNS TABLE(name VARCHAR(255), description TEXT, locked BOOLEAN, id INT) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- If we get an invalid ID, return the root space (the space with the minimum ID)
        RETURN QUERY
        SELECT s.name, s.description, s.locked, s.id
        FROM starexec.spaces s
        WHERE s.id = (SELECT MIN(sp.id) FROM starexec.spaces sp);
    ELSE
        -- Else find all children spaces that are an ancestor of a space the user is apart of
        RETURN QUERY
        SELECT DISTINCT s.name, s.description, s.locked, s.id
        FROM starexec.set_assoc sa
        JOIN closure c ON sa.child_id = c.ancestor
        JOIN spaces s ON s.id = sa.child_id
        JOIN spaces s2 ON s2.id = c.descendant
        LEFT JOIN user_assoc ua ON ua.space_id = c.descendant
        WHERE sa.space_id = _spaceId AND (s2.public_access OR ua.user_id = _userId)
        ORDER BY s.name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all the spaces in the hierarchy rooted at the given space (doesn't require user to be in user_assoc)
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetSubSpacesAdmin CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpacesAdmin(_spaceId INT)
-- Use a different internal OUT parameter name to avoid collision with the column name 'id'
RETURNS TABLE(name VARCHAR(255), description TEXT, locked BOOLEAN, out_id INT) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- If we get an invalid ID, return the root space (the space with the minimum ID)
        RETURN QUERY
        SELECT s.name, s.description, s.locked, s.id
        FROM starexec.spaces s
        WHERE s.id = (SELECT MIN(spaces.id) FROM starexec.spaces);
    ELSE
    RETURN QUERY
    -- Explicitly alias the selected id column as "id" so callers continue to see column name 'id'
    SELECT DISTINCT s.name, s.description, s.locked, s.id AS id
    FROM starexec.set_assoc sa
    JOIN spaces s ON s.id = sa.child_id
    WHERE sa.space_id = _spaceId
    ORDER BY s.name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all the spaces in the hierarchy rooted at the given space (doesn't require user to be in user_assoc)
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubSpaceHierarchyAdmin CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpaceHierarchyAdmin(_spaceId INT)
RETURNS TABLE(name VARCHAR(255), description TEXT, locked BOOLEAN, id INT) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- If we get an invalid ID, return the root space (the space with the minimum ID)
        RETURN QUERY
        SELECT s.name, s.description, s.locked, s.id
        FROM starexec.spaces s
        WHERE s.id = (SELECT MIN(spaces.id) FROM starexec.spaces);
    ELSE
        RETURN QUERY
        SELECT DISTINCT s.name, s.description, s.locked, s.id
        FROM starexec.closure c
        JOIN spaces s ON s.id = c.descendant
        WHERE c.ancestor = _spaceId AND c.ancestor != c.descendant;
    END IF;
END;
$$ LANGUAGE plpgsql;


-- Returns the parent space of a given space ID
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetParentSpaceById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetParentSpaceById(_spaceId INT)
RETURNS TABLE(space_id INT, name VARCHAR(255), created TIMESTAMP, description TEXT, locked BOOLEAN, default_permission INT, public_access BOOLEAN, sticky_leaders BOOLEAN) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- Invalid ID => return root space
        RETURN QUERY
        SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
        FROM starexec.spaces s
        WHERE s.id = (SELECT MIN(spaces.id) FROM starexec.spaces);
    ELSE
        RETURN QUERY
        SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
        FROM starexec.spaces s
        JOIN set_assoc sa ON sa.space_id = s.id
        WHERE sa.child_id = _spaceId;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all subsspaces of a given name belonging to the space with the given id.
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetSubSpaceByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpaceByName(_spaceId INT, _userId INT, _name VARCHAR(255))
RETURNS TABLE(id INT, name VARCHAR(255), created TIMESTAMP, description TEXT, locked BOOLEAN, default_permission INT, public_access BOOLEAN, sticky_leaders BOOLEAN) AS $$
BEGIN
    IF _spaceId <= 0 THEN
        -- If we get an invalid ID, return the root space (the space with the minimum ID)
        RETURN QUERY
        SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
        FROM starexec.spaces s
        ORDER BY s.id LIMIT 1;
    ELSE
        IF _userId > 0 THEN
            RETURN QUERY
            SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
            FROM starexec.spaces s
            WHERE s.id IN (
                SELECT sa.child_id
                FROM starexec.set_assoc sa
                JOIN closure c ON sa.child_id = c.ancestor
                JOIN user_assoc ua ON (ua.user_id = _userId AND ua.space_id = c.descendant)
                WHERE sa.space_id = _spaceId
            ) AND s.name = _name;
        ELSE
            RETURN QUERY
            SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
            FROM starexec.spaces s
            JOIN set_assoc sa ON sa.child_id = s.id
            WHERE sa.space_id = _spaceId AND s.name = _name;
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all spaces that are a subspace of the root
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetSubSpacesOfRoot CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpacesOfRoot()
RETURNS TABLE(id INT, name VARCHAR(255), created TIMESTAMP, description TEXT, locked BOOLEAN, default_permission INT, public_access BOOLEAN, sticky_leaders BOOLEAN) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.name, s.created, s.description, s.locked, s.default_permission, s.public_access, s.sticky_leaders
    FROM starexec.spaces s
    WHERE s.id IN (
        SELECT sa.child_id
        FROM starexec.set_assoc sa
        WHERE sa.space_id = 1
    );
END;
$$ LANGUAGE plpgsql;

-- Gets all the subspaces of a given space needed for a given job (non-recursive)
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetJobSubSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetJobSubSpaces(_spaceId INT)
RETURNS TABLE(id INT, name VARCHAR(255), job_id INT, max_stages INT) AS $$
BEGIN
    RETURN QUERY
    SELECT js.id, js.name, js.job_id, js.max_stages
    FROM starexec.job_spaces js
    JOIN job_space_assoc jsa ON jsa.child_id = js.id
    WHERE jsa.space_id = _spaceId
    ORDER BY js.name ASC;
END;
$$ LANGUAGE plpgsql;

-- Gets the ids of the first level of subspaces of a given space (not recursive)
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubSpaceIds CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubSpaceIds(_spaceId INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT sa.child_id AS id
    FROM starexec.set_assoc sa
    WHERE sa.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Returns the recursive number of subspaces a user can see in a given space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubspaceCountBySpaceIdInHierarchy CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubspaceCountBySpaceIdInHierarchy(_spaceId INT, _userId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS spaceCount
    FROM starexec.closure c
    JOIN spaces s ON s.id = c.descendant
    JOIN user_assoc ua ON ((ua.user_id = _userId OR s.public_access) AND ua.space_id = c.descendant)
    WHERE c.ancestor = _spaceId AND c.ancestor != c.descendant;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetTotalSubspaceCountBySpaceIdInHierarchy CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetTotalSubspaceCountBySpaceIdInHierarchy(_spaceId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS spaceCount
    FROM starexec.closure c
    WHERE c.ancestor = _spaceId AND c.ancestor != c.descendant;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of subspaces in a given space without checking membership
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubspaceCountBySpaceIdAdmin CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubspaceCountBySpaceIdAdmin(_spaceId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS spaceCount
    FROM starexec.set_assoc sa
    WHERE sa.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of subspaces in a given space
-- Author: Todd Elvers + Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubspaceCountBySpaceId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubspaceCountBySpaceId(_spaceId INT, _userId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT sa.child_id)::BIGINT AS spaceCount
    FROM starexec.set_assoc sa
    JOIN user_assoc ua ON sa.child_id = ua.space_id
    JOIN spaces s ON s.id = sa.child_id
    WHERE sa.space_id = _spaceId
    AND (ua.user_id = _userId OR s.public_access);
END;
$$ LANGUAGE plpgsql;

-- Returns the number of subspaces in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubspaceCountBySpaceIdWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubspaceCountBySpaceIdWithQuery(_spaceId INT, _userId INT, _query TEXT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(DISTINCT sa.child_id)::BIGINT AS spaceCount
    FROM starexec.set_assoc sa
    JOIN spaces s ON s.id = sa.child_id
    JOIN user_assoc ua ON sa.child_id = ua.space_id
    JOIN users u ON u.id = _userId
    JOIN user_roles ur ON ur.email = u.email
    WHERE sa.space_id = _spaceId AND (ua.user_id = _userId OR s.public_access OR ur.role = 'admin' OR ur.role = 'developer')
    AND (s.name LIKE CONCAT('%', _query, '%') OR s.description LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;

-- Returns the number of subspaces in a given job space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetSubspaceCountByJobSpaceId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSubspaceCountByJobSpaceId(_spaceId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS spaceCount
    FROM starexec.job_space_assoc jsa
    WHERE jsa.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;


-- Removes the association between a space and a subspace and deletes the subspace
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.RemoveSubspace CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveSubspace(_subspaceId INT)
RETURNS VOID AS $$
DECLARE
    _permId INT;
BEGIN
    -- Remove that space's default permission
    SELECT s.default_permission INTO _permId FROM starexec.spaces s WHERE s.id = _subspaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _subspaceId);
    END IF;

    -- Only attempt to delete permission if it's not NULL
    -- (permissions can be NULL for some spaces)
    IF _permId IS NOT NULL THEN
        DELETE FROM starexec.permissions WHERE id = _permId;
        -- Don't raise on NOT FOUND - permission may already be deleted or cascade-deleted
    END IF;

    -- Remove the space
    DELETE FROM starexec.spaces WHERE id = _subspaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _subspaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the name of the space with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateSpaceName CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateSpaceName(_id INT, _name VARCHAR(255))
RETURNS VOID AS $$
BEGIN
    UPDATE spaces SET name = _name WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the name of the space with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.UpdateSpaceDescription CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateSpaceDescription(_id INT, _desc TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE spaces SET description = _desc WHERE id = _id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates all details of the space with the given id, and returns the permission id to
-- help update default permissions.
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdateSpaceDetails CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateSpaceDetails(_spaceId INT, _name VARCHAR(255), _desc TEXT, _locked BOOLEAN, _sticky BOOLEAN)
RETURNS TABLE(perm INT) AS $$
DECLARE
    _permId INT;
BEGIN
    UPDATE spaces
    SET name = _name, description = _desc, locked = _locked, sticky_leaders = _sticky
    WHERE id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _spaceId);
    END IF;

    SELECT s.default_permission INTO _permId FROM starexec.spaces s WHERE s.id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _spaceId);
    END IF;
    RETURN QUERY SELECT _permId;
END;
$$ LANGUAGE plpgsql;


-- Get the id of the community where the space belongs to
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.GetCommunityOfSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetCommunityOfSpace(_id INT)
RETURNS TABLE(community BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT MIN(c.ancestor)::BIGINT AS community
    FROM starexec.closure c
    WHERE c.descendant = _id AND c.ancestor != 1;
END;
$$ LANGUAGE plpgsql;

-- Query if a space is a public space
-- Author: Ruoyu Zhang, edited by Benton McCune + Eric Burns
DROP FUNCTION IF EXISTS starexec.IsPublicSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsPublicSpace(_spaceId INT)
-- Return integer (1 = public, 0 = private) to match callers that expect numeric values
RETURNS TABLE(public INT) AS $$
BEGIN
    RETURN QUERY
    SELECT CASE WHEN s.public_access THEN 1 ELSE 0 END AS public
    FROM starexec.spaces s
    WHERE s.id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Determines whether a hierarchy is public, meaning every space rooted at the given one
-- (including the given one) is public
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsPublicHierarchy CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsPublicHierarchy(_spaceId INT)
RETURNS TABLE(public_hierarchy INT) AS $$
DECLARE
    _count INT;
BEGIN
    SELECT COUNT(*) INTO _count
    FROM starexec.closure c
    JOIN spaces s ON s.id = c.descendant
    WHERE c.ancestor = _spaceId AND s.public_access = FALSE;

    IF _count = 0 THEN
        RETURN QUERY SELECT 1;
    ELSE
        RETURN QUERY SELECT 0;
    END IF;
END;
$$ LANGUAGE plpgsql;


-- Change a space to a public space or a private one
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.setPublicSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.setPublicSpace(_spaceId INT, _pbc BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE spaces
    SET public_access = _pbc
    WHERE id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Count the number of solvers in a specific space
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.countSpaceSolversByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.countSpaceSolversByName(_name VARCHAR(255), _spaceId INT)
RETURNS TABLE(solver_count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT
    FROM starexec.solvers s
    JOIN solver_assoc sa ON s.id = sa.solver_id
    WHERE s.name = _name AND sa.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Count the number of benchmarks in a specific space
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.countSpaceBenchmarksByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.countSpaceBenchmarksByName(_name VARCHAR(256), _spaceId INT)
RETURNS TABLE(benchmark_count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT
    FROM starexec.benchmarks b
    JOIN bench_assoc ba ON b.id = ba.bench_id
    WHERE b.name = _name AND ba.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Count the number of jobs in a specific space
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.countSpaceJobsByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.countSpaceJobsByName(_name VARCHAR(255), _spaceId INT)
RETURNS TABLE(job_count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT
    FROM starexec.jobs j
    JOIN job_assoc ja ON j.id = ja.job_id
    WHERE j.name = _name AND ja.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Count the number of subspaces in a specific space
-- Author: Ruoyu Zhang
DROP FUNCTION IF EXISTS starexec.countSubspacesByName CASCADE;
CREATE OR REPLACE FUNCTION starexec.countSubspacesByName(_name VARCHAR(255), _spaceId INT)
RETURNS TABLE(subspace_count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT
    FROM starexec.spaces parent
    JOIN set_assoc sa ON parent.id = sa.space_id
    JOIN spaces child ON sa.child_id = child.id
    WHERE parent.id = _spaceId AND child.name = _name;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetSpacesByJob CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpacesByJob(_jobId INT)
RETURNS TABLE(space_id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT ja.space_id
    FROM starexec.job_assoc ja
    WHERE ja.job_id = _jobId;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all jobs belonging to a space (but not their job pairs)
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSpaceJobsById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpaceJobsById(_spaceId INT)
RETURNS TABLE(id INT, name VARCHAR(255), user_id INT, created TIMESTAMP, description TEXT, deleted BOOLEAN, paused BOOLEAN, killed BOOLEAN, buildJob BOOLEAN, disk_size BIGINT, total_pairs INT, completed_pairs INT, errored_pairs INT, pending_pairs INT, status_code INT, max_stages INT, job_type INT, timeout INT, seed BIGINT, suppress_output BOOLEAN, node_queued BOOLEAN, primary_space INT, completed TIMESTAMP, cpuTimeout INT, clockTimeout INT, maximum_memory BIGINT, suppress_timestamp BOOLEAN, using_dependencies BOOLEAN, soft_time_limit INT, kill_delay INT, benchmarking_framework VARCHAR, is_high_priority BOOLEAN, output_benchmarks_directory_path TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT
        j.id, j.name, j.user_id, j.created, j.description, j.deleted, j.paused, j.killed, j.buildJob, j.disk_size, j.total_pairs,
        starexec.getcompletepairs(j.id)::INT as completed_pairs,
        starexec.geterrorpairs(j.id)::INT as errored_pairs,
        (j.total_pairs - starexec.getcompletepairs(j.id) - starexec.geterrorpairs(j.id))::INT as pending_pairs,
        j.status_code, j.max_stages, j.job_type, j.timeout, j.seed, j.suppress_output, j.node_queued,
        j.primary_space, j.completed, j.cpuTimeout, j.clockTimeout, j.maximum_memory, j.suppress_timestamp, j.using_dependencies, j.soft_time_limit, j.kill_delay, j.benchmarking_framework, j.is_high_priority, j.output_benchmarks_directory_path
    FROM starexec.jobs j
    WHERE j.id IN (
        SELECT ja.job_id
        FROM starexec.job_assoc ja
        WHERE ja.space_id = _spaceId
    )
    ORDER BY j.created DESC;
END;
$$ LANGUAGE plpgsql;

-- Removes the association between a job and a given space
-- Author: Todd Elvers + Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveJobFromSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveJobFromSpace(_jobId INT, _spaceId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.job_assoc
    WHERE job_id = _jobId AND space_id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Job %s not associated with space %s', _jobId, _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;


-- Sets the "sticky_leader" flag for a given space
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetStickyLeader CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetStickyLeader(_spaceID INT, _val BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE spaces SET sticky_leaders = _val WHERE id = _spaceID;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _spaceID);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Get all the communities that are not already attached to a queue
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNonAttachedCommunities CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNonAttachedCommunities(_queueId INT)
RETURNS TABLE(id INT, name VARCHAR(255)) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.name
    FROM starexec.spaces s
    JOIN set_assoc sa ON s.id = sa.child_id
    WHERE sa.space_id = 1
    AND s.id NOT IN (
        SELECT space_id FROM starexec.comm_queue WHERE queue_id = _queueId
    );
END;
$$ LANGUAGE plpgsql;

-- Get the "Users" subspace for a given Community
DROP FUNCTION IF EXISTS starexec.GetUsersSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUsersSpace(_id INT)
RETURNS TABLE(id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT s.id
    FROM starexec.spaces s
    WHERE s.name = 'Users'
    AND s.id IN (
        SELECT sa.child_id FROM starexec.set_assoc sa WHERE sa.space_id = _id
    );
END;
$$ LANGUAGE plpgsql;

-- Create the "Users" subspace for a given Community
DROP FUNCTION IF EXISTS starexec.CreateUsersSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.CreateUsersSpace(_communityId INT)
RETURNS VOID AS $$
DECLARE
    _name VARCHAR(255) DEFAULT 'Users';
    _permission INT;
    _locked BOOLEAN DEFAULT FALSE;
    _sticky BOOLEAN DEFAULT FALSE;
    _description TEXT DEFAULT 'Holding personal spaces for users';
    _newSpaceId INT;
BEGIN
    -- Prefer the parent community's default permission when available
    SELECT default_permission INTO _permission
    FROM spaces
    WHERE id = _communityId
    LIMIT 1;

    -- Fall back to the root space default if parent does not define one
    IF _permission IS NULL THEN
        SELECT default_permission INTO _permission
        FROM spaces
        WHERE name = 'root'
        ORDER BY id
        LIMIT 1;
    END IF;

    -- Fall back to a fully privileged non-leader permission if still null
    IF _permission IS NULL THEN
        SELECT id INTO _permission
        FROM permissions
        WHERE add_solver = TRUE
          AND add_bench = TRUE
          AND add_user = TRUE
          AND add_space = TRUE
          AND add_job = TRUE
          AND remove_solver = TRUE
          AND remove_bench = TRUE
          AND remove_user = TRUE
          AND remove_space = TRUE
          AND remove_job = TRUE
          AND is_leader = FALSE
        ORDER BY id
        LIMIT 1;
    END IF;

    -- Final safety: fall back to the oldest permission record if no match above
    IF _permission IS NULL THEN
        SELECT id INTO _permission
        FROM permissions
        ORDER BY id
        LIMIT 1;
    END IF;

    -- Ensure the derived permission exists before inserting the space
    PERFORM 1 FROM permissions WHERE id = _permission;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Unable to determine a valid default permission for Users subspace (communityId=%s)', _communityId);
    END IF;

    SELECT AddSpace(_name, _description, _locked, _permission, _communityId, _sticky) INTO _newSpaceId;
    PERFORM AssociateSpaces(_communityId, _newSpaceId);
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- UploadStatus PROCEDURES
-- ================================================================================

-- Creates a new UpdateStatus entry when user uploads a benchmark
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.CreateBenchmarkUploadStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.CreateBenchmarkUploadStatus(_spaceId INT, _userId INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO benchmark_uploads (space_id, user_id, upload_time, error_message)
    VALUES (_spaceId, _userId, NOW(), 'no error');
    SELECT currval('benchmark_uploads_id_seq') INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Creates a new upload status entry for a space XML upload
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.CreateSpaceXMLUploadStatus CASCADE;
CREATE OR REPLACE FUNCTION starexec.CreateSpaceXMLUploadStatus(_userId INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO space_xml_uploads (user_id, upload_time, error_message)
    VALUES (_userId, NOW(), 'no error');
    SELECT currval('space_xml_uploads_id_seq') INTO _id;
    RETURN _id;
END;
$$ LANGUAGE plpgsql;

-- Updates status when file upload is complete
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.XMLFileUploadComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.XMLFileUploadComplete(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET file_upload_complete = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when file upload is complete
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.BenchmarkFileUploadComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.BenchmarkFileUploadComplete(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET file_upload_complete = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when file extraction is complete
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.FileExtractComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.FileExtractComplete(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET file_extraction_complete = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when java object is created and processing/entering of benchmarks in db has begun
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.ProcessingBegun CASCADE;
CREATE OR REPLACE FUNCTION starexec.ProcessingBegun(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET processing_begun = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when the entire upload benchmark process has completed
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.XMLEverythingComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.XMLEverythingComplete(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET everything_complete = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.BenchmarkEverythingComplete CASCADE;
CREATE OR REPLACE FUNCTION starexec.BenchmarkEverythingComplete(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET everything_complete = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when a directory is encountered when traversing extracted file
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementTotalSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementTotalSpaces(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET total_spaces = total_spaces + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when a file is encountered when traversing extracted file
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementTotalBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementTotalBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET total_benchmarks = total_benchmarks + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Indicates a space is completely added to the db.
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementCompletedSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementCompletedSpaces(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET completed_spaces = completed_spaces + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when a benchmark is completed and entered into the db
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementCompletedBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementCompletedBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET completed_benchmarks = completed_benchmarks + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when a benchmark is validated
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementValidatedBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementValidatedBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET validated_benchmarks = validated_benchmarks + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when a benchmark fails validation
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IncrementFailedBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementFailedBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET failed_benchmarks = failed_benchmarks + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;


DROP FUNCTION IF EXISTS starexec.SetXMLErrorMessage CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetXMLErrorMessage(_id INT, _message TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET error_message = _message
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates status when an error occurs
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.SetBenchmarkErrorMessage CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetBenchmarkErrorMessage(_id INT, _message TEXT)
RETURNS VOID AS $$
BEGIN
    UPDATE benchmark_uploads
    SET error_message = _message
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Benchmark upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Retrieves the upload status with the given id
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetBenchmarkUploadStatusById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetBenchmarkUploadStatusById(_id INT)
RETURNS TABLE(id INT, space_id INT, user_id INT, upload_time TIMESTAMP, error_message TEXT, file_upload_complete BOOLEAN, file_extraction_complete BOOLEAN, processing_begun BOOLEAN, everything_complete BOOLEAN, total_spaces INT, total_benchmarks INT, completed_spaces INT, completed_benchmarks INT, validated_benchmarks INT, failed_benchmarks INT) AS $$
BEGIN
    RETURN QUERY
    SELECT bu.id, bu.space_id, bu.user_id, bu.upload_time, bu.error_message, bu.file_upload_complete, bu.file_extraction_complete, bu.processing_begun, bu.everything_complete, bu.total_spaces, bu.total_benchmarks, bu.completed_spaces, bu.completed_benchmarks, bu.validated_benchmarks, bu.failed_benchmarks
    FROM starexec.benchmark_uploads bu
    WHERE bu.id = _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetUploadStatusForInvalidBenchmarkId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUploadStatusForInvalidBenchmarkId(_id INT)
RETURNS TABLE(id INT, space_id INT, user_id INT, upload_time TIMESTAMP, error_message TEXT, file_upload_complete BOOLEAN, file_extraction_complete BOOLEAN, processing_begun BOOLEAN, everything_complete BOOLEAN, total_spaces INT, total_benchmarks INT, completed_spaces INT, completed_benchmarks INT, validated_benchmarks INT, failed_benchmarks INT) AS $$
BEGIN
    RETURN QUERY
    SELECT bu.id, bu.space_id, bu.user_id, bu.upload_time, bu.error_message, bu.file_upload_complete, bu.file_extraction_complete, bu.processing_begun, bu.everything_complete, bu.total_spaces, bu.total_benchmarks, bu.completed_spaces, bu.completed_benchmarks, bu.validated_benchmarks, bu.failed_benchmarks
    FROM starexec.benchmark_uploads bu
    JOIN unvalidated_benchmarks ub ON bu.id = ub.status_id
    WHERE ub.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Updates status when  benchmark fails validation
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.AddUnvalidatedBenchmark CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUnvalidatedBenchmark(_id INT, _name VARCHAR(256), _error TEXT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO unvalidated_benchmarks (status_id, bench_name, error_message)
    VALUES (_id, _name, _error);
END;
$$ LANGUAGE plpgsql;

-- Gets direct count of unvalidated benchmarks if there are no more than maximum
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.UnvalidatedBenchmarkCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.UnvalidatedBenchmarkCount(_status_id INT)
RETURNS TABLE(count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT
    FROM starexec.unvalidated_benchmarks
    WHERE status_id = _status_id;
END;
$$ LANGUAGE plpgsql;

-- Gets unvalidated benchmark names
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.GetUnvalidatedBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUnvalidatedBenchmarks(_status_id INT)
RETURNS TABLE(bench_name VARCHAR(256), id INT) AS $$
BEGIN
    RETURN QUERY
    SELECT ub.bench_name, ub.id
    FROM starexec.unvalidated_benchmarks ub
    WHERE ub.status_id = _status_id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetXMLTotalSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetXMLTotalSpaces(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET total_spaces = _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetXMLTotalSolvers CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetXMLTotalSolvers(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET total_solvers = _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetXMLTotalBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetXMLTotalBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET total_benchmarks = _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SetXMLTotalUpdates CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetXMLTotalUpdates(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET total_updates = _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IncrementXMLCompletedUpdates CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementXMLCompletedUpdates(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET completed_updates = completed_updates + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IncrementXMLCompletedSolvers CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementXMLCompletedSolvers(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET completed_solvers = completed_solvers + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IncrementXMLCompletedBenchmarks CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementXMLCompletedBenchmarks(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET completed_benchmarks = completed_benchmarks + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.IncrementXMLCompletedSpaces CASCADE;
CREATE OR REPLACE FUNCTION starexec.IncrementXMLCompletedSpaces(_id INT, _num INT)
RETURNS VOID AS $$
BEGIN
    UPDATE space_xml_uploads
    SET completed_spaces = completed_spaces + _num
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space XML upload %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets the error message for a particular row in the unvalidated benchmarks table
DROP FUNCTION IF EXISTS starexec.GetInvalidBenchmarkMessage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetInvalidBenchmarkMessage(_id INT)
RETURNS TABLE(error_message TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT ub.error_message
    FROM starexec.unvalidated_benchmarks ub
    WHERE ub.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Gets the total count of the Uploads that belong to a specific user
DROP FUNCTION IF EXISTS starexec.GetUploadCountByUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUploadCountByUser(_userId INT)
RETURNS TABLE(uploadCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS uploadCount
    FROM starexec.benchmark_uploads
    WHERE user_id = _userId;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetUploadCountByUserWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUploadCountByUserWithQuery(_userId INT, _query TEXT)
RETURNS TABLE(uploadCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS uploadCount
    FROM starexec.benchmark_uploads
    WHERE user_id = _userId AND
    (upload_time::TEXT LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Users PROCEDURES
-- ================================================================================

-- Description: This file contains all user-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Begins the registration process by adding a user to the USERS table
-- Makes their role "unauthorized"
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.AddUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUser(_firstName VARCHAR(32), _lastName VARCHAR(32), _email VARCHAR(64), _institute VARCHAR(64), _password VARCHAR(128), _diskQuota BIGINT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO users(email, first_name, last_name, institution, created, password, disk_quota)
    VALUES (_email, _firstName, _lastName, _institute, NOW(), _password, _diskQuota);

    SELECT currval('users_id_seq') INTO _id;

    INSERT INTO user_roles(email, role)
    VALUES (_email, 'unauthorized');

    RETURN _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.AddUserAuthorized CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUserAuthorized(_firstName VARCHAR(32), _lastName VARCHAR(32), _email VARCHAR(64), _institute VARCHAR(64), _password VARCHAR(128), _diskQuota BIGINT, _role VARCHAR(24), _pairQuota INT)
RETURNS INT AS $$
DECLARE
    _id INT;
BEGIN
    INSERT INTO users(email, first_name, last_name, institution, created, password, disk_quota, job_pair_quota)
    VALUES (_email, _firstName, _lastName, _institute, NOW(), _password, _diskQuota, _pairQuota);
    SELECT currval('users_id_seq') INTO _id;

    INSERT INTO user_roles(email, role)
    VALUES (_email, _role);

    RETURN _id;
END;
$$ LANGUAGE plpgsql;



-- Removes the user given by _userId from every space in the hierarchy rooted at _spaceId that _requestUserId can see
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.RemoveUserFromSpaceHierarchy CASCADE;
CREATE OR REPLACE FUNCTION starexec.RemoveUserFromSpaceHierarchy(_userId INT, _spaceId INT, _requestUserId INT)
RETURNS VOID AS $$
DECLARE
    _deleted_permissions INT := 0;
    _deleted_associations INT := 0;
BEGIN
    -- Remove the permission associated with this user/community
    DELETE FROM starexec.permissions
    WHERE id IN (
        SELECT ua.permission
        FROM starexec.user_assoc ua
        JOIN closure c ON c.descendant = _spaceId
        JOIN user_assoc ua2 ON (ua2.user_id = _requestUserId OR s.public_access) AND ua2.space_id = c.descendant
        WHERE ua.user_id = _userId
    );
    GET DIAGNOSTICS _deleted_permissions = ROW_COUNT;

    DELETE FROM starexec.user_assoc
    WHERE user_id = _userId AND space_id IN (
        SELECT c.descendant
        FROM starexec.closure c
        JOIN spaces s ON c.descendant = s.id
        JOIN user_assoc ua ON (ua.user_id = _requestUserId OR s.public_access) AND ua.space_id = c.descendant
        WHERE c.ancestor = _spaceId
    );
    GET DIAGNOSTICS _deleted_associations = ROW_COUNT;

    IF _deleted_permissions + _deleted_associations = 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found in accessible hierarchy rooted at space %s', _userId, _spaceId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Adds the user given by _userId to every space in the hierarchy rooted at _spaceId that _requestUserId can see
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.AddUserToSpaceHierarchy CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUserToSpaceHierarchy(_userId INT, _spaceId INT, _requestUserId INT)
RETURNS VOID AS $$
DECLARE
    _newPermId INT;
    _pid INT;
BEGIN
    -- Copy the default permission for the community
    SELECT s.default_permission INTO _pid FROM starexec.spaces s WHERE s.id = _spaceId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Space %s not found', _spaceId);
    END IF;

    SELECT CopyPermissions(_pid) INTO _newPermId;
    IF NOT FOUND OR _newPermId IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Permission %s not found', _pid);
    END IF;

    INSERT INTO user_assoc (user_id, space_id, permission)
    SELECT _userId, c.descendant, _newPermId
    FROM starexec.closure c
    JOIN spaces s ON c.descendant = s.id
    JOIN user_assoc ua ON (ua.user_id = _requestUserId OR s.public_access) AND ua.space_id = c.descendant
    WHERE c.ancestor = _spaceId
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- Adds an association between a user and a space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddUserToSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUserToSpace(_userId INT, _spaceId INT)
RETURNS VOID AS $$
DECLARE
    _newPermId INT;
    _pid INT;
BEGIN
    IF NOT EXISTS(SELECT * FROM starexec.user_assoc WHERE user_id = _userId AND space_id = _spaceId) THEN
        -- Copy the default permission for the community
        SELECT s.default_permission INTO _pid FROM starexec.spaces s WHERE s.id = _spaceId;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Space %s not found', _spaceId);
        END IF;

        SELECT CopyPermissions(_pid) INTO _newPermId;
        IF NOT FOUND OR _newPermId IS NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Permission %s not found', _pid);
        END IF;

        INSERT INTO user_assoc (user_id, space_id, permission)
        VALUES (_userId, _spaceId, _newPermId);
    END IF;
END;
$$ LANGUAGE plpgsql;



-- Returns the (hashed) password of the user with the given user id
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.GetPasswordById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetPasswordById(_id INT)
RETURNS TABLE(password VARCHAR(128)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.password
    FROM starexec.users u
    WHERE u.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Returns unregistered user corresponding to the given id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.GetUnregisteredUserById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUnregisteredUserById(_id INT)
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    JOIN user_roles ur ON u.email = ur.email
    WHERE u.id = _id AND ur.role = 'unauthorized';
END;
$$ LANGUAGE plpgsql;

-- Returns the number of users in the entire system
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetUserCount CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserCount()
RETURNS TABLE(userCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS userCount
    FROM starexec.users;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of users in a given space that match a given query
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetUserCountInSpaceWithQuery CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserCountInSpaceWithQuery(_spaceId INT, _query TEXT)
RETURNS TABLE(userCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*)::BIGINT AS userCount
    FROM starexec.user_assoc ua
    JOIN users u ON u.id = ua.user_id
    WHERE ua.space_id = _spaceId AND
    (CONCAT(u.first_name, ' ', u.last_name) LIKE CONCAT('%', _query, '%')
    OR u.institution LIKE CONCAT('%', _query, '%')
    OR u.email LIKE CONCAT('%', _query, '%'));
END;
$$ LANGUAGE plpgsql;

-- Returns the user record with the given email address
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetUserByEmail CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserByEmail(_email VARCHAR(64))
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    NATURAL JOIN user_roles ur
    WHERE u.email = _email;
END;
$$ LANGUAGE plpgsql;

-- Returns the user record with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetUserById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserById(_id INT)
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    NATURAL JOIN user_roles ur
    WHERE u.id = _id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.UnsubscribeUserFromErrorLogs CASCADE;
CREATE OR REPLACE FUNCTION starexec.UnsubscribeUserFromErrorLogs(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET subscribed_to_error_logs = FALSE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.SubscribeUserToErrorLogs CASCADE;
CREATE OR REPLACE FUNCTION starexec.SubscribeUserToErrorLogs(_id INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET subscribed_to_error_logs = TRUE
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS starexec.GetAllUsersSubscribedToErrorLogs CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllUsersSubscribedToErrorLogs()
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    NATURAL JOIN user_roles ur
    WHERE u.subscribed_to_error_logs = TRUE;
END;
$$ LANGUAGE plpgsql;

-- Retrieves all users belonging to a space
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetSpaceUsersById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetSpaceUsersById(_id INT)
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota
    FROM starexec.user_assoc ua
    JOIN users u ON u.id = ua.user_id
    WHERE ua.space_id = _id
    ORDER BY u.first_name;
END;
$$ LANGUAGE plpgsql;




-- Updates the email address of the user with the given user id to the
-- given email address. The email address should already be validated
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdateEmail CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateEmail(_id INT, _email VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET email = _email
    WHERE users.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the first name of the user with the given user id to the
-- given first name. The first name should already be validated.
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdateFirstName CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateFirstName(_id INT, _firstname VARCHAR(32))
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET first_name = _firstname
    WHERE users.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the last name of the user with the given user id to the
-- given last name. The last name should already be validated
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdateLastName CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateLastName(_id INT, _lastname VARCHAR(32))
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET last_name = _lastname
    WHERE users.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the institution of the user with the given user id to the
-- given institution. The institution should already be validated
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdateInstitution CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateInstitution(_id INT, _institution VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET institution = _institution
    WHERE users.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Updates the password of the user with the given user id to the
-- given (already hashed and validated) password.
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.UpdatePassword CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdatePassword(_id INT, _password VARCHAR(128))
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET password = _password
    WHERE users.id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets the default page size for a given user
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetDefaultPageSize CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetDefaultPageSize(_id INT)
RETURNS TABLE(pageSize INT) AS $$
BEGIN
    RETURN QUERY
    SELECT u.default_page_size AS pageSize
    FROM starexec.users u
    WHERE u.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Sets the default page size for a user, which is the number of rows per datatable they see by default
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.SetDefaultPageSize CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetDefaultPageSize(_id INT, _size INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET default_page_size = _size
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the user disk quota limit to the value of _newBytes
-- for the given user
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.UpdateUserDiskQuota CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateUserDiskQuota(_userId INT, _newQuota BIGINT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET disk_quota = _newQuota
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the user pair quota limit for the given user
DROP FUNCTION IF EXISTS starexec.UpdateUserPairQuota CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateUserPairQuota(_userId INT, _newQuota INT)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET job_pair_quota = _newQuota
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Gets the total disk usage for a given user.
DROP FUNCTION IF EXISTS starexec.GetUserDiskUsage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserDiskUsage(_userID INT)
RETURNS TABLE(disk_size BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT u.disk_size
    FROM starexec.users u
    WHERE u.id = _userID;
END;
$$ LANGUAGE plpgsql;

-- Sums up the disk_size columns of solvers, benchmarks, and jobs and places that value in the
-- the user disk_size column. Returns the difference between the old and new values
DROP FUNCTION IF EXISTS starexec.UpdateUserDiskUsage CASCADE;
CREATE OR REPLACE FUNCTION starexec.UpdateUserDiskUsage(_userID INT)
RETURNS BIGINT AS $$
DECLARE
    _sumDiskSize BIGINT;
    _userDiskSize BIGINT;
    _sizeDelta BIGINT;
BEGIN
    SELECT COALESCE(SUM(disk_size), 0) INTO _sumDiskSize FROM
    (SELECT disk_size FROM starexec.solvers WHERE user_id = _userID AND deleted = false
     UNION ALL
     SELECT disk_size FROM starexec.benchmarks WHERE user_id = _userID AND deleted = false
     UNION ALL
     SELECT disk_size FROM starexec.jobs WHERE user_id = _userID AND deleted = false) AS tmp;

    SELECT disk_size INTO _userDiskSize FROM starexec.users WHERE id = _userID;

    _sizeDelta := _userDiskSize - _sumDiskSize;

    UPDATE users SET disk_size = _sumDiskSize WHERE id = _userID;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userID);
    END IF;

    RETURN _sizeDelta;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of bytes a given user's benchmarks is consuming on disk
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetUserBenchmarkDiskUsage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserBenchmarkDiskUsage(_userID INT)
RETURNS TABLE(disk_usage BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COALESCE(SUM(benchmarks.disk_size), 0) AS disk_usage
    FROM starexec.benchmarks
    WHERE benchmarks.user_id = _userID;
END;
$$ LANGUAGE plpgsql;

-- Returns the number of bytes a given user's solvers is consuming on disk
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.GetUserSolverDiskUsage CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetUserSolverDiskUsage(_userID INT)
RETURNS TABLE(disk_usage BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COALESCE(SUM(solvers.disk_size), 0) AS disk_usage
    FROM starexec.solvers
    WHERE solvers.user_id = _userID;
END;
$$ LANGUAGE plpgsql;



-- Returns one record if a given user is a member of a particular space
-- otherwise it returns an empty set
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS starexec.IsMemberOfSpace CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsMemberOfSpace(_userId INT, _spaceId INT)
RETURNS TABLE(isMember BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) as isMember
    FROM starexec.user_assoc ua
    WHERE ua.user_id = _userId AND ua.space_id = _spaceId;
END;
$$ LANGUAGE plpgsql;

-- Gets every user subscribed to the weekly reports
-- Author: Albert Giegerich
DROP FUNCTION IF EXISTS starexec.GetAllUsersSubscribedToReports CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAllUsersSubscribedToReports()
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    INNER JOIN user_roles ur ON u.email = ur.email
    WHERE u.subscribed_to_reports = TRUE;
END;
$$ LANGUAGE plpgsql;

-- Gets every user whose role is 'admin'
DROP FUNCTION IF EXISTS starexec.GetAdmins CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetAdmins()
RETURNS TABLE(id INT, email VARCHAR(64), first_name VARCHAR(32), last_name VARCHAR(32), institution VARCHAR(64), created TIMESTAMP, password VARCHAR(128), disk_quota BIGINT, disk_size BIGINT, subscribed_to_reports BOOLEAN, subscribed_to_error_logs BOOLEAN, default_page_size INT, job_pair_quota INT, role VARCHAR(24)) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.email, u.first_name, u.last_name, u.institution, u.created, u.password, u.disk_quota, u.disk_size, u.subscribed_to_reports, u.subscribed_to_error_logs, u.default_page_size, u.job_pair_quota, ur.role
    FROM starexec.users u
    INNER JOIN user_roles ur ON u.email = ur.email
    WHERE ur.role = 'admin';
END;
$$ LANGUAGE plpgsql;

-- Checks to see whether the given user is a member of the given community
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.IsMemberOfCommunity CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsMemberOfCommunity(_userId INT, _communityId INT)
RETURNS TABLE(spaceCount BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT COUNT(*) AS spaceCount FROM starexec.closure
    JOIN user_assoc AS assoc ON assoc.space_id = descendant
    WHERE assoc.user_id = _userId AND ancestor = _communityId;
END;
$$ LANGUAGE plpgsql;

-- Deletes a user from the database. Right now, this is only used to get rid of temporary test users
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.DeleteUser CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteUser(_userId INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.logins WHERE user_id = _userId;
    DELETE FROM starexec.users WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the role of the given user to the given value
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.ChangeUserRole CASCADE;
CREATE OR REPLACE FUNCTION starexec.ChangeUserRole(_userId INT, _role VARCHAR(24))
RETURNS VOID AS $$
BEGIN
    UPDATE user_roles
    SET role = _role
    FROM starexec.users
    WHERE users.email = user_roles.email AND users.id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Sets the report subscription for a user
DROP FUNCTION IF EXISTS starexec.SetUserReportSubscription CASCADE;
CREATE OR REPLACE FUNCTION starexec.SetUserReportSubscription(_userId INT, _willBeSubscribed BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET subscribed_to_reports = _willBeSubscribed
    WHERE id = _userId;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('User %s not found', _userId);
    END IF;
END;
$$ LANGUAGE plpgsql;


-- ================================================================================
-- Websites PROCEDURES
-- ================================================================================

-- Description: This file contains all website-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a website that is associated with a user
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.AddUserWebsite CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddUserWebsite(_userId INT, _url TEXT, _name VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    INSERT INTO website(user_id, url, name)
    VALUES(_userId, _url, _name);
END;
$$ LANGUAGE plpgsql;

-- Adds a website that is associated with a solver
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddSolverWebsite CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddSolverWebsite(_solverId INT, _url TEXT, _name VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    INSERT INTO website(solver_id, url, name)
    VALUES(_solverId, _url, _name);
END;
$$ LANGUAGE plpgsql;

-- Adds a website that is associated with a space (community)
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.AddSpaceWebsite CASCADE;
CREATE OR REPLACE FUNCTION starexec.AddSpaceWebsite(_spaceId INT, _url TEXT, _name VARCHAR(64))
RETURNS VOID AS $$
BEGIN
    INSERT INTO website(space_id, url, name)
    VALUES(_spaceId, _url, _name);
END;
$$ LANGUAGE plpgsql;

-- Deletes the website with the given website id
-- Author: Eric Burns
DROP FUNCTION IF EXISTS starexec.DeleteWebsite CASCADE;
CREATE OR REPLACE FUNCTION starexec.DeleteWebsite(_id INT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM starexec.website
    WHERE id = _id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Website %s not found', _id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Returns all websites associated with the user with the given user id
-- Author: Skylar Stark
DROP FUNCTION IF EXISTS starexec.GetWebsitesByUserId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetWebsitesByUserId(_userid INT)
RETURNS TABLE(id INT, user_id INT, solver_id INT, space_id INT, url TEXT, name VARCHAR(64)) AS $$
BEGIN
    RETURN QUERY
    SELECT w.id, w.user_id, w.solver_id, w.space_id, w.url, w.name
    FROM starexec.website w
    WHERE w.user_id = _userid
    ORDER BY name;
END;
$$ LANGUAGE plpgsql;

-- Gets all websites that are associated with the solver with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetWebsitesBySolverId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetWebsitesBySolverId(_id INT)
RETURNS TABLE(id INT, user_id INT, solver_id INT, space_id INT, url TEXT, name VARCHAR(64)) AS $$
BEGIN
    RETURN QUERY
    SELECT w.id, w.user_id, w.solver_id, w.space_id, w.url, w.name
    FROM starexec.website w
    WHERE w.solver_id = _id
    ORDER BY name;
END;
$$ LANGUAGE plpgsql;

-- Gets all websites that are associated with the space with the given id
-- Author: Tyler Jensen
DROP FUNCTION IF EXISTS starexec.GetWebsitesBySpaceId CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetWebsitesBySpaceId(_id INT)
RETURNS TABLE(id INT, user_id INT, solver_id INT, space_id INT, url TEXT, name VARCHAR(64)) AS $$
BEGIN
    RETURN QUERY
    SELECT w.id, w.user_id, w.solver_id, w.space_id, w.url, w.name
    FROM starexec.website w
    WHERE w.space_id = _id
    ORDER BY name;
END;
$$ LANGUAGE plpgsql;

-- Gets a website by its ID
DROP FUNCTION IF EXISTS starexec.GetWebsiteById CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetWebsiteById(_id INT)
RETURNS TABLE(id INT, user_id INT, solver_id INT, space_id INT, url TEXT, name VARCHAR(64)) AS $$
BEGIN
    RETURN QUERY
    SELECT w.id, w.user_id, w.solver_id, w.space_id, w.url, w.name
    FROM starexec.website w
    WHERE w.id = _id;
END;
$$ LANGUAGE plpgsql;

-- Gets the next page of data table for community requests
-- Author: Wyatt Kaiser
DROP FUNCTION IF EXISTS starexec.GetNextPageOfPendingCommunityRequests CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNextPageOfPendingCommunityRequests(_startingRecord INT, _recordsPerPage INT)
RETURNS TABLE(user_id INT, community INT, code VARCHAR(32), message TEXT, created TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT cr.user_id, cr.community, cr.code, cr.message, cr.created
    FROM starexec.community_requests cr
    ORDER BY cr.created ASC
    LIMIT _recordsPerPage OFFSET _startingRecord;
END;
$$ LANGUAGE plpgsql;

-- Gets the next page of data table for community requests for a specific community
DROP FUNCTION IF EXISTS starexec.GetNextPageOfPendingCommunityRequestsForCommunity CASCADE;
CREATE OR REPLACE FUNCTION starexec.GetNextPageOfPendingCommunityRequestsForCommunity(_startingRecord INT, _recordsPerPage INT, _communityId INT)
RETURNS TABLE(user_id INT, community INT, code VARCHAR(32), message TEXT, created TIMESTAMP) AS $$
BEGIN
    RETURN QUERY
    SELECT cr.user_id, cr.community, cr.code, cr.message, cr.created
    FROM starexec.community_requests cr
    WHERE cr.community = _communityId
    ORDER BY cr.created ASC
    LIMIT _recordsPerPage OFFSET _startingRecord;
END;
$$ LANGUAGE plpgsql;

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

-- Atomically sets the status of a job pair and its stages with precision:
-- - Sets job_pairs.status_code = terminalStatus for the pair
-- - Sets jobpair_stage_data.status_code = terminalStatus for the terminal stage (stageNumber)
-- - Sets jobpair_stage_data.status_code = notReachedStatus for all stages after stageNumber
-- - Fires the job_pair_completion side-effects (insertion + job completion check) if terminalStatus is terminal
-- This replaces the non-atomic two-call sequence of UpdatePairStatus + UpdateLaterStageStatuses.
DROP ROUTINE IF EXISTS starexec.UpdatePairStatusPrecise(INT, INT, INT, INT) CASCADE;
DROP ROUTINE IF EXISTS starexec.UpdatePairStatusPrecise(INT, INT, INT, INT, BOOLEAN) CASCADE;
-- Returns TRUE when the pair now holds _terminalStatus, FALSE when another writer had
-- already recorded a different terminal result and _forceOverride was not given.
--
-- Three outcomes, not two. Rejecting every terminal-to-terminal write would break
-- at-least-once delivery: KubernetesNativeBackend retries whenever this reports false,
-- so a duplicate completion event for a pair already at the right status would retry
-- forever. A duplicate is therefore idempotent success; only a CONFLICTING terminal
-- status is refused.
CREATE OR REPLACE FUNCTION starexec.UpdatePairStatusPrecise(
	_pairId INT,
	_stageNumber INT,
	_terminalStatus INT,
	_notReachedStatus INT,
	_forceOverride BOOLEAN DEFAULT FALSE
)
RETURNS BOOLEAN AS $$
DECLARE
	_job_id INT;
	_current_status INT;
	_count INT;
	_duplicate BOOLEAN;
BEGIN
	-- FOR UPDATE, and on job_pairs before jobpair_stage_data: every routine touching
	-- both tables takes them in that order, so none can deadlock against another.
	-- Without this lock the read below is a check-then-act -- the caller in
	-- JobPairs.tryMarkRunningAsFailed says as much, updating "outside the lock
	-- transaction" -- which let reconciliation overwrite a result recorded in between.
	SELECT job_id, status_code INTO _job_id, _current_status
	FROM starexec.job_pairs WHERE id = _pairId
	FOR UPDATE;
	IF NOT FOUND THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0002',
			MESSAGE = format('Job pair %s not found', _pairId);
	END IF;

	-- Terminal pairs must not be moved back into an earlier non-terminal state. This
	-- stays an exception rather than a FALSE return: no caller does it legitimately, so
	-- it is a programming error, and reporting it as a lost race would hide that.
	IF starexec.IsTerminalPairStatus(_current_status) AND NOT starexec.IsTerminalPairStatus(_terminalStatus) THEN
		RAISE EXCEPTION USING
			ERRCODE = 'P0001',
			MESSAGE = format(
				'Illegal status transition for pair %s: terminal status %s cannot move to non-terminal status %s',
				_pairId,
				_current_status,
				_terminalStatus
			);
	END IF;

	_duplicate := starexec.IsTerminalPairStatus(_current_status) AND _current_status = _terminalStatus;

	-- A different terminal status means someone already recorded a result for this
	-- pair. Refuse, and let the caller decide; only an explicit override may replace it.
	IF starexec.IsTerminalPairStatus(_current_status) AND NOT _duplicate AND NOT _forceOverride THEN
		RETURN FALSE;
	END IF;

	-- Skipped for a duplicate, whose statuses are already correct. The side effects
	-- below still run: they are idempotent, and running them repairs a pair whose
	-- earlier attempt set the status but died before completion was recorded.
	IF NOT _duplicate THEN
		-- Set the pair-level status
		UPDATE starexec.job_pairs SET status_code = _terminalStatus WHERE id = _pairId;

		-- Set the terminal stage to terminalStatus
		UPDATE starexec.jobpair_stage_data SET status_code = _terminalStatus
		WHERE jobpair_id = _pairId AND stage_number = _stageNumber;

		-- Set all stages after the terminal stage to notReachedStatus
		UPDATE starexec.jobpair_stage_data SET status_code = _notReachedStatus
		WHERE jobpair_id = _pairId AND stage_number > _stageNumber;
	END IF;

	-- Fire job_pair_completion side-effects if terminalStatus is a terminal status code.
	-- Terminal codes: 7-18 (normal completion, resource limits, common errors), 21 (killed),
	-- 23 (not reached), 24 (benchmark dependency missing), 25 (pre-processor error), 26 (post-processor error)
	IF starexec.IsTerminalPairStatus(_terminalStatus) THEN
		INSERT INTO job_pair_completion (pair_id) VALUES (_pairId)
		ON CONFLICT (pair_id) DO NOTHING;

		-- Check if all pairs in the job are now complete; if so, stamp jobs.completed
		SELECT COUNT(*) INTO _count FROM (
			SELECT id FROM starexec.job_pairs
			WHERE job_id = _job_id AND status_code IN (1, 2, 4, 19, 20, 22)
			LIMIT 1
		) AS subq;
		IF _count = 0 THEN
			UPDATE jobs SET completed = CURRENT_TIMESTAMP WHERE id = _job_id;
			IF NOT FOUND THEN
				RAISE EXCEPTION USING
					ERRCODE = 'P0002',
					MESSAGE = format('Job %s for job pair %s not found', _job_id, _pairId);
			END IF;
		END IF;
	END IF;

	RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Determines if User is Leader of Space
-- Author: Benton McCune
DROP FUNCTION IF EXISTS starexec.IsLeader(INT, INT) CASCADE;
CREATE OR REPLACE FUNCTION starexec.IsLeader(_spaceId INT, _userId INT)
RETURNS BOOLEAN AS $$
DECLARE
    isLeader BOOLEAN;
BEGIN
    SELECT p.is_Leader INTO isLeader
    FROM starexec.permissions p
    WHERE p.id = (SELECT ua.permission FROM starexec.user_assoc ua WHERE ua.space_id = _spaceId AND ua.user_id = _userId LIMIT 1);
    RETURN isLeader;
END;
$$ LANGUAGE plpgsql;
