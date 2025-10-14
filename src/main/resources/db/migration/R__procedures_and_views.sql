-- Repeatable migration for procedures, functions, and views
-- This file consolidates ALL procedures from sql/procedures/*.sql into a single Flyway migration
-- All procedures are idempotent and can be re-executed safely
-- Migration includes: Analytics, AnonymousLinks, Benchmarks, Cluster, Communities, ErrorLogs,
-- JobPairs, Jobs, Misc, Notifications, PairsRerun, Permissions, Pipelines, Processors,
-- Queues, Reports, Requests, RunscriptErrors, Settings, Solvers, Spaces, UploadStatus,
-- Users, Websites, and pagination procedures

DELIMITER //

-- ================================================================================
-- Analytics PROCEDURES
-- ================================================================================

-- Retrieve the `id` of a given Event
DROP PROCEDURE IF EXISTS GetEventId //
CREATE PROCEDURE GetEventId( IN _name CHAR(32) )
	BEGIN
		SELECT event_id FROM analytics_events WHERE name=_name;
	END //

-- If there is not yet a record of this event happening today
--   create a record and set its count to `1`
-- otherwise
--   increment the count of the existing record
DROP PROCEDURE IF EXISTS RecordEvent //
CREATE PROCEDURE RecordEvent(
		IN _event_id INT,
		IN _date_recorded DATE,
		IN _count INT
	)
	BEGIN
		INSERT INTO analytics_historical (event_id, date_recorded, count)
			VALUES (_event_id, _date_recorded, _count)
		ON DUPLICATE KEY
			UPDATE
				count = count + _count;
	END //

-- Record an instance of
--   a particular user triggering
--   a particular event on
--   a particular day
-- If we have already recorded this user/event/day, we can just ignore the
-- DUPLICATE KEY warning
DROP PROCEDURE IF EXISTS RecordEventUser //
CREATE PROCEDURE RecordEventUser(
		IN _event_id INT,
		IN _date_recorded DATE,
		IN _user_id INT
	)
	BEGIN
		INSERT IGNORE
			INTO analytics_users (event_id, date_recorded, user_id)
			VALUES (_event_id, _date_recorded, _user_id)
		;
	END //

DROP PROCEDURE IF EXISTS GetAnalyticsForDateRange //
CREATE PROCEDURE GetAnalyticsForDateRange(
		IN _start DATE,
		IN _end DATE)
	BEGIN
		SELECT
			name as "event",
			SUM(count) as "count",
			COUNT(distinct user_id) as "users"
		FROM analytics_historical
		LEFT JOIN analytics_users  ON analytics_historical.event_id = analytics_users.event_id
		LEFT JOIN analytics_events ON analytics_historical.event_id = analytics_events.event_id
		WHERE analytics_historical.date_recorded >= _start AND analytics_historical.date_recorded <= _end
		GROUP BY analytics_historical.event_id
		;
	END //


-- ================================================================================
-- AnonymousLinks PROCEDURES
-- ================================================================================

DROP PROCEDURE IF EXISTS AddAnonymousPrimitiveName //
CREATE PROCEDURE AddAnonymousPrimitiveName(
		IN _anonymousName VARCHAR(36),
		IN _primitiveId INT,
		IN _primitiveType ENUM('solver', 'bench', 'job', 'config'),
		IN _jobId INT)
	BEGIN
		INSERT INTO anonymous_primitive_names ( anonymous_name, primitive_id, primitive_type, job_id)
			VALUES ( _anonymousName, _primitiveId, _primitiveType, _jobId);
	END //

DROP PROCEDURE IF EXISTS AddAnonymousLink //
CREATE PROCEDURE AddAnonymousLink(IN _uniqueId VARCHAR(36), IN _primitiveType ENUM('solver', 'bench', 'job'), IN _primitiveId INT,
		IN _primitivesToAnonymize ENUM('all', 'allButBench', 'none'))
	BEGIN
		INSERT INTO anonymous_links ( unique_id, primitive_type, primitive_id, primitives_to_anonymize, date_created )
			VALUES ( _uniqueId, _primitiveType, _primitiveId, _primitivesToAnonymize, CURDATE() );
	END //

DROP PROCEDURE IF EXISTS GetAnonymousNamesForJob //
CREATE PROCEDURE GetAnonymousNamesForJob( IN _jobId INT )
	BEGIN
		SELECT * FROM anonymous_primitive_names WHERE job_id=_jobId;
	END //

DROP PROCEDURE IF EXISTS GetAnonymousSolverNamesAndIds //
CREATE PROCEDURE GetAnonymousSolverNamesAndIds( IN _jobId INT )
	BEGIN
		SELECT anonymous_name, primitive_id FROM anonymous_primitive_names WHERE job_id=_jobId AND primitive_type="solver";
	END //

DROP PROCEDURE IF EXISTS GetAnonymousLink //
CREATE PROCEDURE GetAnonymousLink( IN _primitiveType ENUM('solver', 'bench', 'job'), IN _primitiveId INT,
		IN _primitivesToAnonymize ENUM('all', 'allButBench', 'none') )
	BEGIN
		SELECT unique_id FROM anonymous_links
			WHERE primitive_type = _primitiveType AND primitive_id = _primitiveId AND primitives_to_anonymize = _primitivesToAnonymize;
	END //

DROP PROCEDURE IF EXISTS GetIdOfPrimitiveAssociatedWithLink //
CREATE PROCEDURE GetIdOfPrimitiveAssociatedWithLink( IN _uniqueId VARCHAR(36), IN _primitiveType ENUM('solver', 'bench', 'job') )
	BEGIN
		SELECT primitive_id FROM anonymous_links WHERE unique_id = _uniqueId AND primitive_type = _primitiveType;
	END //

DROP PROCEDURE IF EXISTS GetPrimitivesToAnonymize //
CREATE PROCEDURE GetPrimitivesToAnonymize( IN _uniqueId VARCHAR(36), IN _primitiveType ENUM('solver', 'bench', 'job'))
	BEGIN
		SELECT primitives_to_anonymize FROM anonymous_links WHERE _uniqueId = unique_id AND primitive_type = _primitiveType;
	END //

DROP PROCEDURE IF EXISTS DeleteOldLinks //
CREATE PROCEDURE DeleteOldLinks( IN _ageThresholdInDays INT )
	BEGIN
		/* Delete all the anonymous primitive names that correspond to anonymous links for jobs that are being deleted. */
		DELETE FROM anonymous_primitive_names WHERE job_id IN (SELECT primitive_id FROM anonymous_links
				WHERE DATEDIFF( CURDATE(), date_created ) >= _ageThresholdInDays AND primitive_type="job");

		DELETE FROM anonymous_links WHERE DATEDIFF( CURDATE(), date_created ) >= _ageThresholdInDays;
	END //

DROP PROCEDURE IF EXISTS DeleteAnonymousLink //
CREATE PROCEDURE DeleteAnonymousLink( IN _uniqueId VARCHAR(36) )
	BEGIN
		DELETE FROM anonymous_primitive_names WHERE anonymous_primitive_names.job_id IN
				(SELECT anonymous_links.primitive_id FROM anonymous_links WHERE anonymous_links.unique_id=_uniqueId AND anonymous_links.primitive_type="job");
		DELETE FROM anonymous_links WHERE unique_id = _uniqueId;
	END //


-- ================================================================================
-- Benchmarks PROCEDURES
-- ================================================================================

-- Description: This file contains all benchmark stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a benchmark into the system and associates it with a space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddBenchmark //
CREATE PROCEDURE AddBenchmark(IN _name VARCHAR(256), IN _path TEXT, IN _downloadable TINYINT(1), IN _userId INT, IN _typeId INT, IN _diskSize BIGINT, IN _description TEXT, OUT _benchId INT)
	BEGIN
		UPDATE users SET disk_size=disk_size+_diskSize WHERE id = _userId;
		INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, downloadable, disk_size, description)
		VALUES (_userId, _name, _typeId, SYSDATE(), _path, _downloadable, _diskSize, _description);

		SELECT LAST_INSERT_ID() INTO _benchId;
	END //

DROP PROCEDURE IF EXISTS AddAndAssociateBenchmark //
CREATE PROCEDURE AddAndAssociateBenchmark(IN _name VARCHAR(256), IN _path TEXT, IN _downloadable TINYINT(1), IN _userId INT, IN _typeId INT, IN _diskSize BIGINT, IN _spaceId INT, OUT _benchId INT)
	BEGIN
		UPDATE users SET disk_size=disk_size+_diskSize WHERE id = _userId;
		INSERT INTO benchmarks (user_id, name, bench_type, uploaded, path, downloadable, disk_size)
		VALUES (_userId, _name, _typeId, SYSDATE(), _path, _downloadable, _diskSize);

		SELECT LAST_INSERT_ID() INTO _benchId;

		INSERT IGNORE INTO bench_assoc (space_id, bench_id) VALUES (_spaceId, _benchId);

	END //

-- Gets all benchmarks that are in a job (in job pairs in that job)
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetBenchmarksByJob //
CREATE PROCEDURE GetBenchmarksByJob(IN _jobId INT)
	BEGIN
		SELECT DISTINCT benchmarks.*
		FROM benchmarks
			INNER JOIN job_pairs
			ON benchmarks.id=job_pairs.bench_id
		WHERE job_id=_jobId;
	END //

-- Adds a new attribute to a benchmark
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddBenchAttr //
CREATE PROCEDURE AddBenchAttr(IN _benchmarkId INT, IN _key VARCHAR(128), IN _val VARCHAR(128))
	BEGIN
		REPLACE INTO bench_attributes VALUES (_benchmarkId, _key, _val);
	END //


-- Adds a new dependency for a benchmark
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS AddBenchDependency //
CREATE PROCEDURE AddBenchDependency(IN _primary_bench_id INT, IN _secondary_benchId INT, IN _include_path TEXT)
	BEGIN
		INSERT INTO bench_dependency (primary_bench_id, secondary_bench_id, include_path) VALUES (_primary_bench_id, _secondary_benchId, _include_path);
	END //

-- Associates the given benchmark with the given space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AssociateBench //
CREATE PROCEDURE AssociateBench(IN _benchId INT, IN _spaceId INT)
	BEGIN
		INSERT IGNORE INTO bench_assoc (space_id, bench_id) VALUES (_spaceId, _benchId);
	END //

-- Retrieves all attributes for a benchmark
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetBenchAttrs //
CREATE PROCEDURE GetBenchAttrs(IN _benchmarkId INT)
	BEGIN
		SELECT *
		FROM bench_attributes
		WHERE bench_id=_benchmarkId
		ORDER BY attr_key ASC;
	END //

DROP PROCEDURE IF EXISTS GetBenchByName //
CREATE PROCEDURE GetBenchByName(IN _id INT, IN _name VARCHAR(256))
	BEGIN
		SELECT *
		FROM benchmarks AS bench
		WHERE deleted=false AND recycled=false and bench.id IN
				(SELECT bench_id
				FROM bench_assoc
				WHERE space_id = _id)
		AND bench.name = _name;
	END //

-- Retrieves all benchmark dependencies for a given primary benchmark id
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetBenchmarkDependencies //
CREATE PROCEDURE GetBenchmarkDependencies(IN _pBenchId INT)
	BEGIN
		SELECT *
		FROM bench_dependency
		WHERE primary_bench_id = _pBenchId;
	END //

-- Just get the id, name, and path for the dependencies of the benchmark with the given id
-- Author: Aaron Stump
DROP PROCEDURE IF EXISTS GetPathsForBenchmarkDependencies //
CREATE PROCEDURE GetPathsForBenchmarkDependencies(IN _pBenchId INT)
	BEGIN
                SELECT benchmarks.id , benchmarks.name , benchmarks.path , bench_dependency.include_path
		FROM benchmarks JOIN bench_dependency
		ON benchmarks.id = bench_dependency.secondary_bench_id 
                WHERE primary_bench_id = _pBenchId;
	END //

-- Deletes a benchmark given that benchmark's id
-- Author: Todd Elvers	+ Eric Burns
DROP PROCEDURE IF EXISTS SetBenchmarkToDeletedById //
CREATE PROCEDURE SetBenchmarkToDeletedById(IN _benchmarkId INT, OUT _path TEXT)
	BEGIN
		UPDATE users JOIN benchmarks on benchmarks.user_id=users.id
		SET users.disk_size=users.disk_size-benchmarks.disk_size
		WHERE benchmarks.id = _benchmarkId;
		SELECT path INTO _path FROM benchmarks WHERE id = _benchmarkId;
		UPDATE benchmarks
		SET deleted=true, disk_size=0
		WHERE id = _benchmarkId;

	END //
-- Gets the IDs of all the spaces associated with the given benchmark
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetAssociatedSpaceIdsByBenchmark //
CREATE PROCEDURE GetAssociatedSpaceIdsByBenchmark(IN _benchId INT)
	BEGIN
		SELECT space_id
		FROM bench_assoc
		WHERE bench_id=_benchId;
	END //

-- Retrieves the benchmark with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetBenchmarkById //
CREATE PROCEDURE GetBenchmarkById(IN _id INT)
	BEGIN
		SELECT *
		FROM benchmarks AS bench
			LEFT OUTER JOIN processors AS types
			ON bench.bench_type=types.id
		WHERE bench.id = _id and deleted=false AND recycled=false;
	END //

DROP PROCEDURE IF EXISTS GetBenchmarkPathById //
CREATE PROCEDURE GetBenchmarkPathById(IN _id INT)
	BEGIN
		SELECT name , path
		FROM benchmarks 
		WHERE id = _id and deleted=false AND recycled=false;
	END //

-- Retrieves the benchmark with the given id, including deleted benchmarks
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarkByIdIncludeDeletedAndRecycled //
CREATE PROCEDURE GetBenchmarkByIdIncludeDeletedAndRecycled(IN _id INT)
	BEGIN
		SELECT *
		FROM benchmarks AS bench
			LEFT OUTER JOIN processors AS types
			ON bench.bench_type=types.id
		WHERE bench.id = _id;
	END //

DROP PROCEDURE IF EXISTS GetXMLUploadStatusById //
CREATE PROCEDURE GetXMLUploadStatusById(IN _id INT)
	BEGIN
		SELECT *
		FROM space_xml_uploads
		WHERE id = _id;
	END //

-- Returns the number of benchmarks in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarkCountInSpaceWithQuery //
CREATE PROCEDURE GetBenchmarkCountInSpaceWithQuery(IN _spaceId INT, IN _query TEXT)
	BEGIN
		SELECT 	COUNT(*) AS benchCount
		FROM 	bench_assoc
			JOIN	benchmarks AS benchmarks ON benchmarks.id = bench_assoc.bench_id
			LEFT JOIN	processors  AS benchType ON benchmarks.bench_type=benchType.id
		WHERE 	_spaceId=space_id AND
				(benchmarks.name LIKE	CONCAT('%', _query, '%')
				OR		(benchType.name	LIKE 	CONCAT('%', _query, '%')
				OR (benchType.name is null AND 'none' LIKE CONCAT('%', _query, '%'))));
	END //
-- Retrieves all benchmarks belonging to a space
-- Author: Eric Burns

DROP PROCEDURE IF EXISTS GetSpaceBenchmarksById //
CREATE PROCEDURE GetSpaceBenchmarksById(IN _id INT)
	BEGIN
		SELECT *
		FROM bench_assoc
		JOIN benchmarks AS bench ON bench.id=bench_assoc.bench_id
		LEFT OUTER JOIN processors AS types ON bench.bench_type=types.id
		WHERE bench_assoc.space_id=_id and bench.deleted=false and bench.recycled=false
		ORDER BY order_id ASC;
	END //

-- Returns the number of public spaces a benchmark is in
-- Benton McCune
DROP PROCEDURE IF EXISTS IsBenchPublic //
CREATE PROCEDURE IsBenchPublic(IN _benchId INT)
	BEGIN
		SELECT count(*) as benchPublic
		FROM bench_assoc
		WHERE bench_id = _benchId
		AND IsPublic(space_id);
	END //

DROP PROCEDURE IF EXISTS IsBenchmarkDeleted //
CREATE PROCEDURE IsBenchmarkDeleted(IN _benchId INT)
	BEGIN
		SELECT count(*) AS benchDeleted
		FROM benchmarks
		WHERE deleted=true AND id=_benchId;
	END //

-- Removes the association between a benchmark and a given space;
-- Author: Todd Elvers + Eric Burns
DROP PROCEDURE IF EXISTS RemoveBenchFromSpace //
CREATE PROCEDURE RemoveBenchFromSpace(IN _benchId INT, IN _spaceId INT)
	BEGIN
		IF _spaceId >= 0 THEN
			DELETE FROM bench_assoc
			WHERE space_id = _spaceId
			AND bench_id = _benchId;
		END IF;

	END //


-- Updates the details associated with a given benchmark
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS UpdateBenchmarkDetails //
CREATE PROCEDURE UpdateBenchmarkDetails(IN _benchmarkId INT, IN _name VARCHAR(256), IN _description TEXT, IN _downloadable BOOLEAN, IN _type INT)
	BEGIN
		UPDATE benchmarks
		SET name = _name,
		description = _description,
		downloadable = _downloadable,
		bench_type = _type
		WHERE id = _benchmarkId;
	END //

-- Get the total count of the benchmarks belong to a specific user
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetBenchmarkCountByUser //
CREATE PROCEDURE GetBenchmarkCountByUser(IN _userId INT)
	BEGIN
		SELECT COUNT(*) AS benchCount
		FROM benchmarks
		WHERE user_id = _userId AND deleted=false AND recycled=false;
	END //
-- Returns the number of benchmarks a given user has that match the query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarkCountByUserWithQuery //
CREATE PROCEDURE GetBenchmarkCountByUserWithQuery(IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT 	COUNT(*) AS benchCount
		FROM 	benchmarks
			JOIN	processors  AS benchType ON benchmarks.bench_type=benchType.id
		WHERE 	benchmarks.user_id=_userId AND deleted=false AND recycled=false AND
				(benchmarks.name LIKE	CONCAT('%', _query, '%')
				OR		benchType.name	LIKE 	CONCAT('%', _query, '%'));
	END //

-- Sets the recycled attribute to the given value for the given benchmark
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetBenchmarkRecycledValue //
CREATE PROCEDURE SetBenchmarkRecycledValue(IN _benchId INT, IN _recycled BOOLEAN)
	BEGIN
		UPDATE benchmarks
		SET recycled=_recycled
		WHERE id=_benchId;
	END //

-- Checks to see whether the "recycled" flag is set for the given benchmark
-- Author: Eric BUrns
DROP PROCEDURE IF EXISTS IsBenchmarkRecycled //
CREATE PROCEDURE IsBenchmarkRecycled(IN _benchId INT)
	BEGIN
		SELECT recycled FROM benchmarks
		WHERE id=_benchId;
	END //

-- Counts how many recycled benchmarks a user has that match the given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledBenchmarkCountByUser //
CREATE PROCEDURE GetRecycledBenchmarkCountByUser(IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT 	COUNT(*) AS benchCount
		FROM 	benchmarks
			JOIN	processors  AS benchType ON benchmarks.bench_type=benchType.id
		WHERE 	benchmarks.recycled=true AND benchmarks.user_id=_userId AND deleted=false AND
				(benchmarks.name LIKE	CONCAT('%', _query, '%')
				OR		benchType.name	LIKE 	CONCAT('%', _query, '%'));
	END //

-- Gets the path to every recycled benchmark a user has
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledBenchmarkPaths //
CREATE PROCEDURE GetRecycledBenchmarkPaths(IN _userId INT)
	BEGIN
		SELECT path FROM benchmarks
		WHERE recycled=true AND user_id=_userId;
	END //

-- Removes all recycled benchmarks a user has in the database
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetRecycledBenchmarksToDeleted //
CREATE PROCEDURE SetRecycledBenchmarksToDeleted(IN _userId INT)
	BEGIN
		UPDATE users
		SET users.disk_size=users.disk_size-(SELECT COALESCE(SUM(disk_size),0) FROM benchmarks WHERE user_id=_userId AND recycled=true AND deleted=false)
		WHERE users.id=_userId;
		UPDATE benchmarks
		SET deleted=true, disk_size=0
		WHERE user_id = _userId AND recycled=true AND deleted=false;
	END //

-- Gets all recycled benchmark ids a user has
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledBenchmarkIds //
CREATE PROCEDURE GetRecycledBenchmarkIds(IN _userId INT)
	BEGIN
		SELECT id from benchmarks
		WHERE user_id=_userId AND recycled=true;
	END //


-- Sets the recycled flag for a single benchmark back to false
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RestoreBenchmark //
CREATE PROCEDURE RestoreBenchmark(IN _benchId INT)
	BEGIN
		UPDATE benchmarks
		SET recycled=false
		WHERE _benchId=id;
	END //
-- Gets rid of all the current attributes a benchmark has
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS ClearBenchAttributes //
CREATE PROCEDURE ClearBenchAttributes(IN _benchId INT)
	BEGIN
		DELETE FROM bench_attributes
		WHERE _benchId=bench_id;
	END //

-- Retrieves the benchmarks owned by a given user id
-- Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarksByOwner //
CREATE PROCEDURE GetBenchmarksByOwner(IN _userId INT)
	BEGIN
		SELECT *
		FROM benchmarks
		LEFT OUTER JOIN processors AS types
			ON benchmarks.bench_type=types.id
		WHERE user_id = _userId and deleted=false AND recycled=false;
	END //

-- Gets the ids of every orphaned benchmark a user owns
DROP PROCEDURE IF EXISTS GetOrphanedBenchmarkIds //
CREATE PROCEDURE GetOrphanedBenchmarkIds(IN _userId INT)
	BEGIN
		SELECT benchmarks.id FROM benchmarks
		LEFT JOIN bench_assoc ON bench_assoc.bench_id=benchmarks.id
		WHERE benchmarks.user_id=_userId AND bench_assoc.space_id IS NULL;
	END //

-- Permanently removes a benchmark from the database
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RemoveBenchmarkFromDatabase //
CREATE PROCEDURE RemoveBenchmarkFromDatabase(IN _id INT)
	BEGIN
		DELETE FROM benchmarks
		WHERE id=_id;
	END //


-- Gets all the benchmarks ids of benchmarks that are in at least one space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarksAssociatedWithSpaces //
CREATE PROCEDURE GetBenchmarksAssociatedWithSpaces()
	BEGIN
		SELECT DISTINCT bench_id AS id FROM bench_assoc;
	END //

-- Gets the benchmarks ids of all benchmarks associated with at least one pair
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarksAssociatedWithPairs //
CREATE PROCEDURE GetBenchmarksAssociatedWithPairs()
	BEGIN
		SELECT DISTINCT bench_id AS id from job_pairs;
	END //

-- Gets the benchmark ids of all deleted benchmarks
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetDeletedBenchmarks //
CREATE PROCEDURE GetDeletedBenchmarks()
	BEGIN
		SELECT * FROM benchmarks WHERE deleted=true;
	END //


-- returns every benchmark that shares a space with the given user
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetBenchmarksInSharedSpaces //
CREATE PROCEDURE GetBenchmarksInSharedSpaces(IN _userId INT)
	BEGIN
		SELECT DISTINCT benchmarks.*, types.name AS type_name, types.description AS type_description
		FROM benchmarks
		JOIN bench_assoc ON bench_assoc.bench_id = benchmarks.id
		JOIN user_assoc ON user_assoc.space_id = bench_assoc.space_id
		LEFT OUTER JOIN processors AS types
			ON benchmarks.bench_type=types.id
		WHERE user_assoc.user_id=_userId AND deleted=false AND recycled=false;
	END //

-- Gets all solvers that reside in public spaces
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetPublicBenchmarks //
CREATE PROCEDURE GetPublicBenchmarks()
	BEGIN
		SELECT DISTINCT benchmarks.*, types.name AS type_name, types.description AS type_description
		FROM benchmarks
		JOIN bench_assoc ON bench_assoc.bench_id=benchmarks.id
		JOIN spaces ON spaces.id=bench_assoc.space_id
		LEFT OUTER JOIN processors AS types
			ON benchmarks.bench_type=types.id
		WHERE public_access=1 AND deleted=false AND recycled=false;
	END //

DROP PROCEDURE IF EXISTS GetBrokenBenchDependencies //
CREATE PROCEDURE GetBrokenBenchDependencies(IN _benchId INT)
    BEGIN
        SELECT DISTINCT benchmarks.id
        FROM benchmarks join bench_dependency
            ON benchmarks.id=bench_dependency.secondary_bench_id
        WHERE
            (benchmarks.deleted = 1
            OR benchmarks.recycled = 1)
            AND primary_bench_id = _benchId;
    END //


-- ================================================================================
-- Cluster PROCEDURES
-- ================================================================================

-- Description: This file contains all cluster stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a worker node to the database and ignores duplicates
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AssociateQueue //
CREATE PROCEDURE AssociateQueue(IN _queueName VARCHAR(64), IN _nodeName VARCHAR(64))
	BEGIN
		INSERT IGNORE INTO queue_assoc
		VALUES(
			(SELECT id FROM queues WHERE name=_queueName),
			(SELECT id FROM nodes WHERE name=_nodeName));
	END //

-- Adds a worker node to the database and ignores duplicates
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddNode //
CREATE PROCEDURE AddNode(IN _name VARCHAR(64))
	BEGIN
		INSERT IGNORE INTO nodes (name)
		VALUES (_name);
	END //

-- Clear all Queue Associations from the db
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS ClearQueueAssociations //
CREATE PROCEDURE ClearQueueAssociations()
	BEGIN
		TRUNCATE queue_assoc;
	END //

-- Gets the id, name and status of all nodes in the cluster that are active
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetNodesForQueue //
CREATE PROCEDURE GetNodesForQueue(IN _id INT)
	BEGIN
		SELECT node.id, node.name, node.status
		FROM queue_assoc
			JOIN nodes AS node ON node.id=queue_assoc.node_id
		WHERE _id=queue_assoc.queue_id
		ORDER BY name;
	END //

-- Gets the id, name and status of all queues in the cluster that are active
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetAllQueues //
CREATE PROCEDURE GetAllQueues()
	BEGIN
		SELECT id, name, status,global_access, cpuTimeout,clockTimeout
		FROM queues
		WHERE status="ACTIVE"
		ORDER BY name;
	END //

-- Gets the id, name and status of all queues in the cluster
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetAllQueuesAdmin //
CREATE PROCEDURE GetAllQueuesAdmin()
	BEGIN
		SELECT id, name, status,global_access, cpuTimeout, clockTimeout
		FROM queues
		ORDER BY id;
	END //

-- Gets worker node with the given ID
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetNodeDetails //
CREATE PROCEDURE GetNodeDetails(IN _id INT)
	BEGIN
		SELECT *
		FROM nodes
		WHERE id=_id;
	END //

-- Gets the queue with the given ID (excluding SGE attributes)
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetQueue //
CREATE PROCEDURE GetQueue(IN _id INT)
	BEGIN
		SELECT *
		FROM queues
		WHERE id=_id;
	END //

-- Updates all queues status'
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateAllQueueStatus //
CREATE PROCEDURE UpdateAllQueueStatus(IN _status VARCHAR(32))
	BEGIN
		UPDATE queues
		SET status=_status;
	END //

-- Updates a specific queues status
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateQueueStatus //
CREATE PROCEDURE UpdateQueueStatus(IN _name VARCHAR(64), IN _status VARCHAR(32))
	BEGIN
		UPDATE queues
		SET status=_status
		WHERE name=_name;
	END //

-- Updates all nodes status'
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateAllNodeStatus //
CREATE PROCEDURE UpdateAllNodeStatus(IN _status VARCHAR(32))
	BEGIN
		UPDATE nodes
		SET status=_status;
	END //

-- Updates a specific node's status
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateNodeStatus //
CREATE PROCEDURE UpdateNodeStatus(IN _name VARCHAR(64), IN _status VARCHAR(32))
	BEGIN
		UPDATE nodes
		SET status=_status
		WHERE name=_name;
	END //

-- Returns all the nodes in the system that are active
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetAllNodes //
CREATE PROCEDURE GetAllNodes ()
	BEGIN
		SELECT *
		FROM nodes
		WHERE status = "ACTIVE";
	END //

-- Returns all the nodes in the system that are active and not associated with the queue already
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNonAttachedNodes //
CREATE PROCEDURE GetNonAttachedNodes(IN _queueId INT)
	BEGIN
		SELECT DISTINCT nodes.id, queues.id, nodes.name, queues.name, nodes.status
		FROM nodes LEFT JOIN queue_assoc on nodes.id = queue_assoc.node_id
		LEFT JOIN queues ON queues.id=queue_assoc.queue_id
		WHERE nodes.status = "ACTIVE" AND (queue_assoc.queue_id IS NULL OR queue_assoc.queue_id != _queueId);
	END //

-- Returns the jobs that are currently running on a specific queue
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetJobsRunningOnQueue //
CREATE PROCEDURE GetJobsRunningOnQueue(IN _queueId INT)
	BEGIN
		SELECT DISTINCT
			jobs.id,
			jobs.name,
			jobs.user_id,
			jobs.queue_id,
			jobs.created,
			jobs.completed,
			jobs.description,
			jobs.deleted,
			jobs.primary_space,
			GetJobStatus(jobs.id)		AS status,
			jobs.total_pairs	 		AS totalPairs,
			GetCompletePairs(jobs.id) 	AS completePairs,
			GetPendingPairs(jobs.id) 	AS pendingPairs,
			GetErrorPairs(jobs.id) 		AS errorPairs

		FROM	jobs
		JOIN    job_pairs ON jobs.id = job_pairs.job_id
		WHERE 	job_pairs.status_code < 7 AND jobs.queue_id = _queueId;
	END //

-- Returns the Queue that a specific node is associated with
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetQueueForNode //
CREATE PROCEDURE GetQueueForNode(IN _nodeId INT)
	BEGIN
		SELECT queues.id, queues.name, queues.status
		FROM queues, queue_assoc
		WHERE queues.id = queue_assoc.queue_id AND queue_assoc.node_id = _nodeId;
	END //

-- Return the node id given its name
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNodeIdByName //
CREATE PROCEDURE GetNodeIdByName(IN _nodeName VARCHAR(128))
	BEGIN
		SELECT id
		FROM nodes
		WHERE name = _nodeName;
	END //

-- Return the node name given its id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNodeNameById //
CREATE PROCEDURE GetNodeNameById(IN _nodeId INT)
	BEGIN
		SELECT name
		FROM nodes
		WHERE id = _nodeId;
	END //

-- deletes a node from the database
DROP PROCEDURE IF EXISTS DeleteNode //
CREATE PROCEDURE DeleteNode(IN _id INT)
	BEGIN
		DELETE FROM nodes WHERE id=_id;
	END //


-- ================================================================================
-- Communities PROCEDURES
-- ================================================================================

-- Description: This file contains all community stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Checks to see if the space with the given space ID is a community.
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS IsCommunity //
CREATE PROCEDURE IsCommunity(IN _spaceId INT)
	BEGIN
		SELECT *
		FROM set_assoc
		WHERE space_id = 1 AND child_id = _spaceId;
	END //

-- Returns basic space information for the community with the given id
-- This ensures security by preventing malicious users from getting details about ANY space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetCommunityById //
CREATE PROCEDURE GetCommunityById(IN _id INT)
	BEGIN
		SELECT *
		FROM set_assoc
			JOIN spaces AS space ON space.id=set_assoc.child_id
		WHERE _id=child_id AND space_id=1;
	END //

-- Removes the association a user has with a given space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS LeaveSpace //
CREATE PROCEDURE LeaveSpace(IN _userId INT, IN _spaceId INT)
	BEGIN
		-- Remove the permission associated with this user/space
		DELETE FROM permissions
			WHERE id=(SELECT permission FROM user_assoc WHERE user_id = _userId	AND space_id = _spaceId);

		-- Delete the association
		DELETE FROM user_assoc
		WHERE user_id = _userId
		AND space_id = _spaceId;
	END //

-- Removes every association a user has with every space in the hierarchy rooted at the given space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS LeaveHierarchy //
CREATE PROCEDURE LeaveHierarchy(IN _userId INT, IN _spaceId INT)
	BEGIN
		DELETE user_assoc FROM user_assoc
		JOIN closure ON closure.descendant=user_assoc.space_id
		WHERE closure.ancestor=_spaceId AND user_id=_userId;
	END //

DROP PROCEDURE IF EXISTS GetCommunityStatsUsers //
CREATE PROCEDURE GetCommunityStatsUsers()
	BEGIN
		SELECT community_assoc.comm_id, COUNT(DISTINCT user_assoc.user_id) AS userCount
		FROM community_assoc
		JOIN user_assoc
		ON community_assoc.space_id=user_assoc.space_id
		GROUP BY community_assoc.comm_id;

	END //

DROP PROCEDURE IF EXISTS GetCommunityStatsSolvers //
CREATE PROCEDURE GetCommunityStatsSolvers()
	BEGIN
		SELECT comm_id, COUNT(DISTINCT solverId) as solverCount, SUM(solverDiskSize) as solverDiskUsage
		FROM (SELECT DISTINCT community_assoc.comm_id,solvers.id as solverId, solvers.disk_size as solverDiskSize
		FROM community_assoc JOIN solver_assoc On solver_assoc.space_id=community_assoc.space_id JOIN solvers ON solvers.id=solver_assoc.solver_id) as commStatSolver
		GROUP BY comm_id;
	END //

DROP PROCEDURE IF EXISTS GetCommunityStatsBenches //
CREATE PROCEDURE GetCommunityStatsBenches()
	BEGIN
		SELECT comm_id, COUNT(DISTINCT benchId) as benchCount, SUM(benchDiskSize) AS benchDiskUsage
		FROM (SELECT DISTINCT comm_id,benchmarks.id as benchId, benchmarks.disk_size as benchDiskSize
		FROM community_assoc JOIN bench_assoc On bench_assoc.space_id=community_assoc.space_id JOIN benchmarks ON benchmarks.id=bench_assoc.bench_id) as commStatBench
		GROUP BY comm_id;
	END //

DROP PROCEDURE IF EXISTS GetCommunityStatsJobs //
CREATE PROCEDURE GetCommunityStatsJobs()
	BEGIN
		SELECT community_assoc.comm_id, COUNT(DISTINCT job_pairs.job_id) AS jobCount, COUNT(DISTINCT job_pairs.id) AS jobPairCount
		FROM community_assoc JOIN job_assoc ON job_assoc.space_id=community_assoc.space_id JOIN job_pairs ON job_pairs.job_id=job_assoc.job_id
		WHERE job_pairs.status_code IN (7,14,15,16,17)
		GROUP BY community_assoc.comm_id;
	END //


-- ================================================================================
-- ErrorLogs PROCEDURES
-- ================================================================================

-- Adds an error log to the database.
DROP PROCEDURE IF EXISTS AddErrorLog //
CREATE PROCEDURE AddErrorLog(IN _message TEXT, _logLevel VARCHAR(32), OUT _id INT)
  BEGIN
    SET @llid := (SELECT id FROM log_levels WHERE _logLevel = name);
    INSERT INTO error_logs (message, log_level_id) VALUES (_message, @llid);
    SELECT LAST_INSERT_ID() INTO _id;
  END //

-- Gets an error log from the database with the given id.
DROP PROCEDURE IF EXISTS GetErrorLogById //
CREATE PROCEDURE GetErrorLogById(IN _id INT)
  BEGIN
    SELECT el.id AS id, el.message AS message, el.time AS time, ll.name AS level
    FROM error_logs el JOIN log_levels ll ON el.log_level_id=ll.id
    WHERE el.id=_id;
  END //

-- Deletes an error log with the given id.
DROP PROCEDURE IF EXISTS DeleteErrorLogWithId //
CREATE PROCEDURE DeleteErrorLogWithId(IN _id INT)
  BEGIN
    DELETE FROM error_logs
    WHERE id=_id;
  END //

-- Deletes all error log from before the given time.
DROP PROCEDURE IF EXISTS DeleteErrorLogsBefore //
CREATE PROCEDURE DeleteErrorLogsBefore(IN _time TIMESTAMP)
  BEGIN
    DELETE FROM error_logs
    WHERE error_logs.time < _time;
  END //

-- Deletes all error log from before the given time.
DROP PROCEDURE IF EXISTS GetErrorLogsBefore //
CREATE PROCEDURE GetErrorLogsBefore(IN _time TIMESTAMP)
  BEGIN
    SELECT el.id AS id, el.message AS message, el.time AS time, ll.name AS level
    FROM error_logs el JOIN log_levels ll ON el.log_level_id=ll.id
    WHERE el.time < _time
    ORDER BY el.time DESC;
  END //

-- Gets all error logs since the given time (inclusive).
DROP PROCEDURE IF EXISTS GetErrorLogsSince //
CREATE PROCEDURE GetErrorLogsSince(IN _since TIMESTAMP)
  BEGIN
    SELECT el.id AS id, el.message AS message, el.time AS time, ll.name AS level
    FROM error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
    WHERE el.time >= _since
    ORDER BY el.time DESC;
  END //

DROP PROCEDURE IF EXISTS GetAllErrorLogs //
CREATE PROCEDURE GetAllErrorLogs()
  BEGIN
    SELECT el.id AS id, el.message AS message, el.time AS time, ll.name AS level
    FROM error_logs el JOIN log_levels ll ON el.log_level_id = ll.id
    ORDER BY el.time DESC;
  END //


-- ================================================================================
-- JobPairs PROCEDURES
-- ================================================================================

-- Description: This file contains all job-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

DROP PROCEDURE IF EXISTS UpdateJobPairStatus //
CREATE PROCEDURE UpdateJobPairStatus(IN _pairId INT, IN _statusCode INT)
	BEGIN
		UPDATE job_pairs
		SET status_code = _statusCode
		WHERE id = _pairId;
	END //

DROP PROCEDURE IF EXISTS UpdateJobSpaceId //
CREATE PROCEDURE UpdateJobSpaceId(IN _pairId INT, IN _jobSpaceId INT)
	BEGIN
		UPDATE job_pairs
		SET job_space_id = _jobSpaceId
		WHERE id = _pairId;
	END //

DROP PROCEDURE IF EXISTS UpdatePairNodeId //
CREATE PROCEDURE UpdatePairNodeId(IN _jobPairId INT, IN _nodeId INT)
	BEGIN
		UPDATE job_pairs SET node_id=_nodeId WHERE id=_jobPairId;
	END  //

-- Updates a job pair's statistics directly from the execution node
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS UpdatePairRunSolverStats //
CREATE PROCEDURE UpdatePairRunSolverStats(IN _jobPairId INT, IN _nodeName VARCHAR(64), IN _wallClock DOUBLE, IN _cpu DOUBLE, IN _userTime DOUBLE, IN _systemTime DOUBLE, IN _maxVmem DOUBLE, IN _maxResSet BIGINT, IN _stageNumber INT, IN _diskSize BIGINT)
	BEGIN
		UPDATE job_pairs SET node_id=(SELECT id FROM nodes WHERE name=_nodeName) WHERE id=_jobPairId;
		UPDATE users SET users.disk_size=users.disk_size+_diskSize
		WHERE id = (SELECT user_id FROM job_pairs JOIN jobs ON jobs.id = job_pairs.job_id WHERE job_pairs.id=_jobPairId);
		UPDATE jobpair_stage_data
		SET wallclock = _wallClock,
			cpu=_cpu,
			user_time=_userTime,
			system_time=_systemTime,
			max_vmem=_maxVmem,
			max_res_set=_maxResSet,
			disk_size=_diskSize
		WHERE jobpair_id=_jobPairId AND stage_number=_stageNumber;
		UPDATE jobs SET disk_size=disk_size+_diskSize WHERE id=(SELECT job_id FROM job_pairs WHERE id=_jobPairId);
	END //

-- Updates a job pairs node Id
-- Author: Wyatt
DROP PROCEDURE IF EXISTS UpdateNodeId //
CREATE PROCEDURE UpdateNodeId(IN _jobPairId INT, IN _nodeName VARCHAR(128), IN _sandbox INT)
	BEGIN
		DECLARE _nodeId INT;

		SELECT id FROM nodes WHERE name=_nodeName INTO _nodeId;

		UPDATE job_pairs SET node_id=_nodeId WHERE id = _jobPairId;
		UPDATE job_pairs SET sandbox_num=_sandbox WHERE id=_jobPairId;

		-- Next lines finish a pair that is still in the "running" state despite another pair being in the same place now
		-- First, mark the end time of the pairs
		UPDATE job_pairs SET end_time=NOW() WHERE node_id = _nodeID AND status_code = 4 AND id!=_jobPairId AND sandbox_num=_sandbox;
		-- Then, update the stuck pairs to an error code
		UPDATE job_pairs SET status_code = 10 WHERE node_id = _nodeID AND status_code = 4 AND id!=_jobPairId AND sandbox_num=_sandbox;
	END //

-- Sets a pair's disk_usage to 0, updating jobpair_stage_data, jobs, and users
DROP PROCEDURE IF EXISTS RemoveJobPairDiskSize //
CREATE PROCEDURE RemoveJobPairDiskSize(IN _jobPairId INT)
	BEGIN
		DECLARE _sumDiskSize BIGINT;
		SELECT SUM(disk_size) FROM jobpair_stage_data WHERE jobpair_id=_jobPairId INTO _sumDiskSize;
		UPDATE jobs SET jobs.disk_size=jobs.disk_size - (_sumDiskSize) WHERE jobs.id=(SELECT job_id FROM job_pairs WHERE job_pairs.id=_jobPairId);
		UPDATE users SET users.disk_size=users.disk_size - (_sumDiskSize)
		WHERE users.id=(SELECT user_id FROM jobs JOIN job_pairs ON job_pairs.job_id=jobs.id WHERE job_pairs.id=_jobPairId);
		UPDATE jobpair_stage_data SET disk_size=0 WHERE jobpair_id=_jobPairId;
	END //


-- Gets all the nodes that could have pairs that have been enqueued longer than
-- some given amount of time. This works by getting all the queues with pairs that
-- have been enqueued longer than _timeThreshold and then returning all the nodes
-- from that queue that have not run any pairs since the _timeThreshold (basically filtering out
-- nodes that appear to be working).
DROP PROCEDURE IF EXISTS GetNodesThatMayHavePairsEnqueuedLongerThan //
CREATE PROCEDURE GetNodesThatMayHavePairsEnqueuedLongerThan(IN _timeThreshold INT)
  BEGIN
	SELECT DISTINCT qa.node_id as node_id
	FROM job_pairs jp JOIN jobs j ON jp.job_id=j.id
	JOIN queues q ON q.id=j.queue_id
	JOIN queue_assoc qa ON q.id=qa.queue_id
	WHERE MINUTE(TIMEDIFF(NOW(), jp.queuesub_time)) > _timeThreshold
		AND jp.status_code=2
		AND qa.node_id NOT IN
			-- This subquery will get all the working nodes.
			( SELECT i_qa.node_id
			  FROM job_pairs i_jp JOIN jobs i_j ON i_jp.job_id=i_j.id
				JOIN queues i_q ON i_q.id=i_j.queue_id
				JOIN queue_assoc i_qa ON i_q.id=i_qa.queue_id
			  WHERE MINUTE(TIMEDIFF(NOW(), i_jp.start_time)) <= _timeThreshold);
  END //

DROP PROCEDURE IF EXISTS GetPairsEnqueuedLongerThan //
CREATE PROCEDURE GetPairsEnqueuedLongerThan(IN _timeThreshold INT)
  BEGIN
	SELECT DISTINCT jp.id AS pair_id, j.id AS job_id
	FROM job_pairs jp JOIN jobs j ON jp.job_id=j.id
	JOIN queues q ON q.id=j.queue_id
	JOIN queue_assoc qa ON q.id=qa.queue_id
	WHERE MINUTE(TIMEDIFF(NOW(), jp.queuesub_time)) > _timeThreshold AND jp.status_code=2;
  END //

-- Updates a job pair's status
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdatePairStatus //
CREATE PROCEDURE UpdatePairStatus(IN _jobPairId INT, IN _statusCode TINYINT)
	BEGIN
		UPDATE job_pairs SET status_code=_statusCode WHERE id=_jobPairId ;
		IF (_statusCode>6 AND _statusCode<19) THEN
			REPLACE INTO job_pair_completion (pair_id) VALUES (_jobPairId);

			-- this checks to see if the job is done and sets its completion id if so.
			-- It checks by trying to find exactly 1 pair (for efficiency) that is not yet complete
			IF (SELECT COUNT(*) FROM (select id from job_pairs WHERE job_id=(SELECT job_id FROM job_pairs WHERE job_pairs.id=_jobPairId) AND (status_code<7 || status_code>18) LIMIT 1) as theCount)=0 THEN
				UPDATE jobs SET completed=CURRENT_TIMESTAMP WHERE id=(SELECT job_id FROM job_pairs WHERE job_pairs.id=_jobPairId);
			END IF;
		END IF;
		IF (_statusCode = 2) THEN
			UPDATE job_pairs SET queuesub_time=NOW(3) WHERE id=_jobPairId;
		END IF;
	END //

-- Sets the status code for the given stage of the given pair
DROP PROCEDURE IF EXISTS UpdatePairStageStatus //
CREATE PROCEDURE UpdatePairStageStatus(IN _jobPairId INT,IN _stageNumber INT, IN _statusCode TINYINT)
	BEGIN
		UPDATE jobpair_stage_data SET status_code=_statusCode WHERE jobpair_id=_jobPairId AND stage_number=_stageNumber;
	END //

-- Sets the status code of every stage occurring after the given stage to the given status code.
-- We do this, for example, when an early stage times out and so later stages are never run
DROP PROCEDURE IF EXISTS UpdateLaterStageStatuses //
CREATE PROCEDURE UpdateLaterStageStatuses(IN _jobPairId INT, IN _stageNumber INT, IN _statusCode TINYINT)
	BEGIN
		UPDATE jobpair_stage_data SET status_code=_statusCode WHERE jobpair_id=_jobPairId AND stage_number>_stageNumber;
	END //

-- Sets all run stats to 0 for stages that come after the given stage. This is used for
-- pipelines where an early stage fails, causing later stages to not run
DROP PROCEDURE IF EXISTS SetRunStatsForLaterStagesToZero //
CREATE PROCEDURE SetRunStatsForLaterStagesToZero(IN _jobPairId INT, IN _stageNumber INT)
	BEGIN
		UPDATE jobpair_stage_data
		SET wallclock = 0,
			cpu=0,
			user_time=0,
			system_time=0,
			max_vmem=0,
			max_res_set=0
		WHERE jobpair_id=_jobPairId AND stage_number>_stageNumber;
	END //

-- Gets all the stages for the given job pair
DROP PROCEDURE IF EXISTS GetJobPairStagesById //
CREATE PROCEDURE GetJobPairStagesById( IN _id INT)
	BEGIN
		SELECT *
		FROM jobpair_stage_data
		LEFT JOIN pipeline_stages ON pipeline_stages.stage_id=jobpair_stage_data.stage_id
		WHERE jobpair_id=_id
		ORDER BY jobpair_stage_data.stage_id ASC;
	END //
-- Gets the job pair with the given id. Only gets the primary stage!
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetJobPairById //
CREATE PROCEDURE GetJobPairById(IN _Id INT)
	BEGIN
		SELECT *
		FROM job_pairs
		LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
		LEFT JOIN job_pair_completion ON job_pairs.id = job_pair_completion.pair_id
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
		WHERE job_pairs.id=_Id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
	END //

-- Retrieves all attributes for a job pair
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetPairAttrs //
CREATE PROCEDURE GetPairAttrs(IN _pairId INT)
	BEGIN
		SELECT *
		FROM job_attributes
		WHERE pair_id=_pairId
		ORDER BY attr_key ASC;
	END //

-- Updates a job pair's backend ID (SGE, OAR, or so on).
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS SetBackendExecId //
CREATE PROCEDURE SetBackendExecId(IN _jobPairId INT, IN _execId INT)
	BEGIN
		UPDATE job_pairs
		SET sge_id=_execId
		WHERE id=_jobPairId;
	END //

-- Gets back only the fields of a job pair that are necessary to determine where it is stored on disk
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairFilePathInfo //
CREATE PROCEDURE GetJobPairFilePathInfo(IN _pairId INT)
	BEGIN
		SELECT job_id,job_pairs.job_space_id,path,jobpair_stage_data.solver_name,
		jobpair_stage_data.config_name,bench_name,jobpair_stage_data.stage_number FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
		WHERE job_pairs.id=_pairId and jobpair_stage_data.stage_number = job_pairs.primary_jobpair_data;
	END //

-- Gets every pair_id and processor_id for pairs awaiting processing
DROP PROCEDURE IF EXISTS GetPairsToBeProcessed //
CREATE PROCEDURE GetPairsToBeProcessed(IN _processingStatus INT)
	BEGIN
		SELECT post_processor ,job_pairs.id, jobpair_stage_data.stage_number AS stageNumber
		FROM jobpair_stage_data
		JOIN job_pairs ON job_pairs.id = jobpair_stage_data.jobpair_id
		JOIN job_stage_params ON (job_stage_params.job_id=job_pairs.job_id AND job_stage_params.stage_number=jobpair_stage_data.stage_number)
		WHERE jobpair_stage_data.status_code=_processingStatus;
	END //

DROP PROCEDURE IF EXISTS RemovePairFromCompletedTable //
CREATE PROCEDURE RemovePairFromCompletedTable(IN _id INT)
	BEGIN
		DELETE FROM job_pair_completion
		WHERE pair_id=_id;
	END //

-- Sets the queue submission time to now (the moment this is called) for the pair with the given id
DROP PROCEDURE IF EXISTS SetPairStartTime //
CREATE PROCEDURE SetPairStartTime(IN _id INT)
	BEGIN
		UPDATE job_pairs SET start_time=NOW() WHERE id=_id;
	END //

-- Deletes a job pair from the database. The _pairSize argument is in bytes, and it is only
-- used in cases where the disk_size field is not set in the stages of the pair to be deleted.
-- This is necessary only for old pairs with no disk_size set
DROP PROCEDURE IF EXISTS DeleteJobPair //
CREATE PROCEDURE DeleteJobPair( IN _pairId INT)
	BEGIN
		DECLARE pair_disk_size BIGINT DEFAULT 0;
		SELECT sum(jobpair_stage_data.disk_size) INTO pair_disk_size
		FROM job_pairs JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.id=_pairId;

		UPDATE users
		SET users.disk_size=users.disk_size-pair_disk_size
		WHERE id = (SELECT user_id FROM jobs JOIN job_pairs ON jobs.id=job_pairs.job_id WHERE job_pairs.id=_pairId);

		UPDATE jobs
		SET jobs.disk_size=jobs.disk_size-pair_disk_size,
		total_pairs=total_pairs-1
		WHERE id=(SELECT job_id FROM job_pairs WHERE id=_pairId);

		DELETE FROM job_pairs
		WHERE job_pairs.id = _pairId;
	END //

-- Gets all of the job pairs in a job that contain a given benchmark.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetJobPairsInJobContainingBenchmark //
CREATE PROCEDURE GetJobPairsInJobContainingBenchmark(IN _jobId INT, IN _benchmarkId INT )
	BEGIN
		SELECT job_pairs.*
		FROM job_pairs
		WHERE job_id=_jobId AND bench_id=_benchmarkId;
	END //

DROP PROCEDURE IF EXISTS GetJobPairsInJobContainingSolver //
CREATE PROCEDURE GetJobPairsInJobContainingSolver(IN _jobId INT, IN _solverId INT)
  BEGIN
    SELECT job_pairs.*
    FROM job_pairs INNER JOIN jobpair_stage_data ON job_pairs.id=jobpair_stage_data.jobpair_id
	WHERE job_id=_jobId AND solver_id=_solverId;
  END //

-- Sets the completion time to now (the moment this is called) for the pair with the given id
-- Also sets the time_delta for the pair in the jobpair_time_delta table.
DROP PROCEDURE IF EXISTS SetPairEndTime //
CREATE PROCEDURE SetPairEndTime(IN _id INT)
	BEGIN
		UPDATE job_pairs SET end_time=NOW() WHERE id=_id;

		-- save the diff between this pair's timeout and wallclock time to jobpair_time_delta
		INSERT IGNORE INTO jobpair_time_delta(user_id, queue_id, time_delta)
		VALUES ((SELECT user_id FROM jobs JOIN job_pairs ON jobs.id=job_pairs.job_id WHERE job_pairs.id=_id),
		(SELECT queue_id FROM jobs JOIN job_pairs ON jobs.id=job_pairs.job_id WHERE job_pairs.id=_id), 0);

		UPDATE jobpair_time_delta
		SET time_delta = (SELECT jobs.clockTimeout - CEIL(SUM(jobpair_stage_data.wallclock)+1)+time_delta
						  FROM jobpair_stage_data JOIN job_pairs ON job_pairs.id=jobpair_stage_data.jobpair_id
						  JOIN jobs ON jobs.id = job_pairs.job_id WHERE jobpair_stage_data.jobpair_id=_id)
		WHERE user_id=(SELECT user_id FROM jobs JOIN job_pairs ON jobs.id=job_pairs.job_id WHERE job_pairs.id=_id)
		AND queue_id=(SELECT queue_id FROM jobs JOIN job_pairs ON jobs.id=job_pairs.job_id WHERE job_pairs.id=_id);

	END //

-- Counts the number of pairs with the given status code that completed in within the given
-- number of days
DROP PROCEDURE IF EXISTS CountRecentPairsByStatus //
CREATE PROCEDURE CountRecentPairsByStatus(IN _status INT, IN _days INT)
	BEGIN
		SELECT count(*) FROM job_pairs WHERE status_code=_status AND

		status_code=_status;-- end_time BETWEEN DATE_SUB(NOW(), INTERVAL _days DAY) AND NOW();
	END //

-- Adds a single job pair input to the database
DROP PROCEDURE IF EXISTS AddJobPairInput //
CREATE PROCEDURE AddJobPairInput(IN _pairId INT, IN _input INT, IN _benchId INT)
	BEGIN
		INSERT INTO jobpair_inputs (jobpair_id, input_number,bench_id) VALUES (_pairId,_input,_benchId);
	END //

DROP PROCEDURE IF EXISTS GetJobPairInputPaths //
CREATE PROCEDURE GetJobPairInputPaths(IN _pairId INT)
	BEGIN
		SELECT path,input_number FROM jobpair_inputs
		JOIN benchmarks ON benchmarks.id=jobpair_inputs.bench_id
		WHERE jobpair_id=_pairId ORDER BY input_number ASC;
	END //

-- Select all data from the jobpair_time_delta table for a specific
-- queue. -1 means all queues
DROP PROCEDURE IF EXISTS GetJobpairTimeDeltaData //
CREATE PROCEDURE GetJobpairTimeDeltaData(IN _qid INT)
	BEGIN
		SELECT * FROM jobpair_time_delta WHERE queue_id=_qid OR _qid = -1;
	END //


-- Deletes all data from the jobpair_time_delta table for a specific
-- queue. -1 means all queues
DROP PROCEDURE IF EXISTS ClearJobpairTimeDeltaData //
CREATE PROCEDURE ClearJobpairTimeDeltaData(IN _qid INT)
	BEGIN
		DELETE FROM jobpair_time_delta WHERE queue_id=_qid OR _qid=-1;
	END //

DROP PROCEDURE IF EXISTS GetJobPairsWithStatus //
CREATE PROCEDURE GetJobPairsWithStatus(IN _status INT)
	BEGIN
		SELECT * FROM job_pairs
		WHERE status_code = _status;
	END //

DROP PROCEDURE IF EXISTS GetJobPairIdsWithStatusNotRerunAfterDate //
CREATE PROCEDURE GetJobPairIdsWithStatusNotRerunAfterDate(IN _status INT, IN _earliestEndTime DATETIME)
	BEGIN
		SELECT id FROM job_pairs
		WHERE status_code = _status
		AND (job_pairs.end_time >= _earliestEndTime OR job_pairs.end_time < "1970-01-01")
		AND id NOT IN (SELECT pair_id FROM pairs_rerun);
	END //

DROP PROCEDURE IF EXISTS SetBrokenPairStatus //
CREATE PROCEDURE SetBrokenPairStatus(IN _pairId INT, IN _current_status INT, IN _new_status INT)
	BEGIN
		UPDATE jobpair_stage_data
		JOIN job_pairs ON jobpair_stage_data.jobpair_id = job_pairs.id
		SET jobpair_stage_data.status_code = _new_status
		WHERE jobpair_id=_pairId AND job_pairs.status_code=_current_status;

		UPDATE job_pairs
		SET status_code = _new_status
		WHERE id = _pairId AND status_code = _current_status;
	END //


-- Counts the total number of job pairs that satisfy GetNextPageOfJobPairsInJobSpaceHierarchy
DROP PROCEDURE IF EXISTS CountJobPairsInJobSpaceHierarchyByType //
CREATE PROCEDURE CountJobPairsInJobSpaceHierarchyByType(IN _jobSpaceId INT,IN _configId INT, IN _type VARCHAR(16), IN _query TEXT, IN _stageNumber INT)

	BEGIN
		SELECT COUNT(*) as count FROM job_pairs

		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
		LEFT JOIN job_pair_completion ON job_pair_completion.pair_id=job_pairs.id

		LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id and job_attributes.stage_number=jobpair_stage_data.stage_number AND job_attributes.attr_key="starexec-result")
		JOIN job_space_closure ON descendant=job_pairs.job_space_id
		LEFT JOIN bench_attributes ON (job_pairs.bench_id=bench_attributes.bench_id AND bench_attributes.attr_key = "starexec-expected-result")

		WHERE ancestor=_jobSpaceId AND jobpair_stage_data.config_id=_configId AND jobpair_stage_data.stage_number = _stageNumber AND
				((_type = "all") OR
				(_type="resource" AND job_pairs.status_code>=14 AND job_pairs.status_code<=17) OR
				(_type = "incomplete" AND job_pairs.status_code!=7 AND !(job_pairs.status_code>=14 AND job_pairs.status_code<=17)) OR
				(_type="failed" AND ((job_pairs.status_code>=8 AND job_pairs.status_code<=13) OR job_pairs.status_code=18)) OR
				(_type ="complete" AND (job_pairs.status_code=7 OR (job_pairs.status_code<=14 ANd job_pairs.status_code<=17))) OR
				(_type= "unknown" AND job_pairs.status_code=7 AND job_attributes.attr_value="starexec-unknown") OR
				(_type = "solved" AND job_pairs.status_code=7 AND (job_attributes.attr_value=bench_attributes.attr_value OR bench_attributes.attr_value is null)) OR
				(_type = "wrong" AND job_pairs.status_code=7 AND (bench_attributes.attr_value is not null) and (job_attributes.attr_value!=bench_attributes.attr_value)))

				AND

				(bench_name 		LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.config_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.solver_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.status_code 	LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.wallclock				LIKE	CONCAT('%', _query, '%')
				OR		cpu				LIKE	CONCAT('%', _query, '%')
				OR      job_attributes.attr_value 			LIKE 	CONCAT('%', _query, '%'));
	END //


-- ================================================================================
-- Jobs PROCEDURES
-- ================================================================================

-- Description: This file contains all job-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds an association between the given job and space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AssociateJob //
CREATE PROCEDURE AssociateJob(IN _jobId INT, IN _spaceId INT)
	BEGIN
		INSERT IGNORE INTO job_assoc VALUES (_spaceId, _jobId);
	END //

-- 	Returns the number of public spaces the job is in
--  Author: Benton McCune
DROP PROCEDURE IF EXISTS JobInPublicSpace //
CREATE PROCEDURE JobInPublicSpace(IN _jobId INT)
	BEGIN
		SELECT COUNT(*) AS spaceCount FROM job_assoc
			INNER JOIN spaces ON spaces.id=job_assoc.space_id
		WHERE job_id=_jobId AND spaces.public_access=1;
	END //

-- Adds a new attribute to a job pair for the given stage
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddJobAttr //
CREATE PROCEDURE AddJobAttr(IN _pairId INT, IN _key VARCHAR(128), IN _val VARCHAR(128), IN _stage INT)
	BEGIN
		REPLACE INTO job_attributes (pair_id,attr_key,attr_value,job_id,stage_number) VALUES (_pairId, _key, _val, (select job_id from job_pairs where id=_pairId),_stage);
	END //

-- Returns the number of jobs in a given space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetJobCountBySpace //
CREATE PROCEDURE GetJobCountBySpace(IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) AS jobCount
		FROM job_assoc
		WHERE _spaceId=space_id;
	END //
-- Returns the number of jobs in a given space that match a given query
-- Author: Eric burns
DROP PROCEDURE IF EXISTS GetJobCountBySpaceWithQuery //
CREATE PROCEDURE GetJobCountBySpaceWithQuery(IN _spaceId INT, IN _query TEXT)
	BEGIN
		SELECT COUNT(*) AS jobCount
		FROM job_assoc
			JOIN jobs AS jobs ON jobs.id=job_assoc.job_id
		WHERE _spaceId=job_assoc.space_id
		AND (jobs.name				LIKE	CONCAT('%', _query, '%')
				OR		GetJobStatus(jobs.id)	LIKE	CONCAT('%', _query, '%'));
	END //

-- Returns the number of jobs pairs for a given job in the given job space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairCountInJobSpace //
CREATE PROCEDURE GetJobPairCountInJobSpace(IN _jobSpaceId INT, IN _stageNumber INT)
	BEGIN
		IF _stageNumber > 0 THEN
			SELECT COUNT(*) AS jobPairCount
			FROM job_pairs
			JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
			WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND stage_number=_stageNumber;
		ELSE
			SELECT COUNT(*) AS jobPairCount
			FROM job_pairs
			JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
			WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND stage_number=job_pairs.primary_jobpair_data;
		END IF;

	END //



-- Counts the number of pairs in a job with a completion index <= the given
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS CountOlderPairs //
CREATE PROCEDURE CountOlderPairs(IN _id INT, IN _since INT)
	BEGIN
		SELECT COUNT(*) AS count
		FROM job_pairs JOIN job_pair_completion ON id=pair_id
		WHERE completion_id<=_since and job_id=_id;
	END //

-- Returns the number of jobs pairs for a given job that match a given query for the given stage
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairCountByJobInJobSpaceWithQuery //
CREATE PROCEDURE GetJobPairCountByJobInJobSpaceWithQuery(IN _jobSpaceId INT, IN _query TEXT, IN _stageNumber INT)
	BEGIN
		IF _stageNumber>0 THEN
			SELECT COUNT(*) AS jobPairCount
			FROM job_pairs
			JOIN jobpair_stage_data ON (jobpair_stage_data.jobpair_id = job_pairs.id)
			WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND stage_number = _stageNumber
			AND		(bench_name 		LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.config_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.solver_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.status_code		LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.wallclock				LIKE	CONCAT('%', _query, '%'));
		ELSE
			SELECT COUNT(*) AS jobPairCount
			FROM job_pairs
			JOIN jobpair_stage_data ON (jobpair_stage_data.jobpair_id = job_pairs.id)
			WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data
			AND		(bench_name 		LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.config_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.solver_name		LIKE	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.status_code		LIKE 	CONCAT('%', _query, '%')
				OR		jobpair_stage_data.wallclock				LIKE	CONCAT('%', _query, '%'));

		END IF;

	END //


-- Gets attributes for every pair in a job
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobAttrs //
CREATE PROCEDURE GetJobAttrs(IN _jobId INT)
	BEGIN
		SELECT pair.id, attr.attr_key, attr.attr_value, attr.stage_number
		FROM job_pairs AS pair
			LEFT JOIN job_attributes AS attr ON attr.pair_id=pair.id
			WHERE pair.job_id=_jobId;
	END //

-- Gets the attributes for every job pair of a job completed after the given completion id
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetNewJobAttrs //
CREATE PROCEDURE GetNewJobAttrs(IN _jobId INT, IN _completionId INT)
	BEGIN
		SELECT pair.id, attr.attr_key, attr.attr_value, attr.stage_number
		FROM job_pairs AS pair
			LEFT JOIN job_attributes AS attr ON attr.pair_id=pair.id
			INNER JOIN job_pair_completion AS complete ON pair.id=complete.pair_id
			WHERE pair.job_id=_jobId AND complete.completion_id>_completionId;

	END //

-- Adds a new job stats record to the database
-- Author : Eric Burns
DROP PROCEDURE IF EXISTS AddJobStats //
CREATE PROCEDURE AddJobStats(IN _jobSpaceId INT, IN _configId INT, IN _complete INT, IN _correct INT, IN _incorrect INT, IN _failed INT, IN _conflicts INT, IN _wallclock DOUBLE, IN _cpu DOUBLE, IN _resource INT, IN _incomplete INT, IN _stage INT, IN _includeUnknown BOOLEAN)
	BEGIN
		INSERT IGNORE INTO job_stats (job_space_id, config_id, complete, correct, incorrect, failed, conflicts, wallclock,cpu,resource_out, incomplete, stage_number, include_unknowns)
		VALUES (_jobSpaceId, _configId, _complete, _correct, _incorrect, _failed, _conflicts, _wallclock, _cpu,_resource, _incomplete, _stage, _includeUnknown);
	END //

-- this version includes deleted configs; used to construct the solver summary table in the job space view
-- Alexander Brown, 9/20
DROP PROCEDURE IF EXISTS GetJobStatsInJobSpaceIncludeDeletedConfigs //
CREATE PROCEDURE GetJobStatsInJobSpaceIncludeDeletedConfigs(IN _jobSpaceId INT, IN _jobId INT, IN _stageNumber INT, IN _includeUnknown BOOLEAN)
BEGIN
SELECT *
FROM job_stats
JOIN configurations AS config ON config.id=job_stats.config_id
JOIN solvers AS solver ON solver.id=config.solver_id
LEFT JOIN anonymous_primitive_names AS anonymous_solver_names
	ON solver.id=anonymous_solver_names.primitive_id AND anonymous_solver_names.primitive_type="solver"
AND anonymous_solver_names.job_id=_jobId
LEFT JOIN anonymous_primitive_names AS anonymous_config_names
	ON config.id=anonymous_config_names.primitive_id AND anonymous_config_names.primitive_type="config"
AND anonymous_config_names.job_id=_jobId
WHERE job_stats.job_space_id = _jobSpaceId AND stage_number=_stageNumber AND include_unknowns=_includeUnknown;
END //

-- Clears the entire cache of job stats
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RemoveAllJobStats //
CREATE PROCEDURE RemoveAllJobStats()
	BEGIN
		DELETE FROM job_stats;
	END //

-- Removes the cached job results for the hierarchy rooted at the given job space
-- Author: Eric Burns

DROP PROCEDURE IF EXISTS RemoveJobStatsInJobSpace //
CREATE PROCEDURE RemoveJobStatsInJobSpace(IN _jobSpaceId INT)
	BEGIN
		DELETE FROM job_stats
		WHERE job_stats.job_space_id = _jobSpaceId;
	END //

DROP PROCEDURE IF EXISTS RemoveJobStatsInJobSpaceForConfig //
CREATE PROCEDURE RemoveJobStatsInJobSpaceForConfig( IN _jobSpaceId INT, IN _configId INT )
	BEGIN
		DELETE FROM job_stats
		WHERE job_stats.job_space_id = _jobSpaceId
			AND job_stats.config_id = _configId;
	END //

-- Counts the number of pending pairs in a job
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS CountPendingPairs //
CREATE PROCEDURE CountPendingPairs(IN _jobId INT)
	BEGIN
		SELECT count(*) AS pending FROM job_pairs
		WHERE status_code BETWEEN 1 AND 6 AND job_id=_jobId;
	END //
-- Retrieves simple overall statistics for job pairs belonging to a job
-- Including the total number of pairs, how many are complete, pending or errored out
-- as well as how long the pairs ran
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetJobPairOverview //
CREATE PROCEDURE GetJobPairOverview(IN _jobId INT)
	BEGIN
		-- This is messy in order to get back pretty column names.
		-- Derived tables must have identifiers which is why a, b, c, d and e exist but aren't used
		SELECT * FROM (
			(SELECT total_pairs AS totalPairs FROM jobs WHERE id=_jobId) AS a, -- Gets the total number of pairs
			(SELECT COUNT(*) AS completePairs FROM job_pairs WHERE job_id=_jobId AND status_code=7) AS b, -- Gets number of pairs with COMPLETE status codes
			(SELECT COUNT(*) AS pendingPairs FROM job_pairs WHERE job_id=_jobId AND (status_code BETWEEN 1 AND 6 OR status_code=22)) AS c, -- Gets number of pairs with non complete and non error status codes
			(SELECT COUNT(*) AS errorPairs FROM job_pairs WHERE job_id=_jobId AND (status_code BETWEEN 8 AND 17 OR status_code=0)) AS d, -- Gets number of UNKNOWN or ERROR status code pairs
			(SELECT TIMESTAMPDIFF( -- Gets time difference between earliest completed pair's start time and latest completed pair's end time
				MICROSECOND,
				(SELECT MIN(start_time) FROM job_pairs WHERE job_id=_jobId AND status_code=7),
				(SELECT MAX(end_time) FROM job_pairs WHERE job_id=_jobId AND status_code=7)) AS runtime) AS e);
	END //

-- Retrieves basic info about a job from the jobs table
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetJobById //
CREATE PROCEDURE GetJobById(IN _id INT)
	BEGIN
		SELECT *
		FROM jobs
		WHERE id = _id and deleted=false;
	END //

DROP PROCEDURE IF EXISTS SetHighPriority //
CREATE PROCEDURE SetHighPriority(IN _jobId INT, IN _isHighPriority BOOLEAN)
	BEGIN
		UPDATE jobs
		SET is_high_priority=_isHighPriority
		WHERE id=_jobId;
	END //


DROP PROCEDURE IF EXISTS GetOutputBenchmarksPath //
CREATE PROCEDURE GetOutputBenchmarksPath(IN _jobId INT)
	BEGIN
		SELECT output_benchmarks_directory_path
		FROM jobs
		WHERE id=_jobId;
	END //

DROP PROCEDURE IF EXISTS SetOutputBenchmarksPath //
CREATE PROCEDURE SetOutputBenchmarksPath(IN _jobId INT, IN _path TEXT)
	BEGIN
		UPDATE jobs
		SET output_benchmarks_directory_path=_path
		WHERE id=_jobId;
	END //

-- Retrieves basic info about a job from the jobs table
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetJobByIdIncludeDeleted //
CREATE PROCEDURE GetJobByIdIncludeDeleted(IN _id INT)
	BEGIN
		SELECT *
		FROM jobs
		WHERE id = _id;
	END //

-- Retrieves basic info about job pairs for the given job id (simple version). Gets only the primary stage
-- Author: Julio Cervantes
DROP PROCEDURE IF EXISTS GetJobPairsByJobSimple //
CREATE PROCEDURE GetJobPairsByJobSimple(IN _id INT)
	BEGIN
		SELECT job_pairs.id, job_pairs.job_space_id, path, jobpair_stage_data.solver_name,jobpair_stage_data.solver_id,jobpair_stage_data.config_name,
		jobpair_stage_data.config_id,bench_name,bench_id,solver_pipelines.name,
		job_spaces.name,job_pairs.status_code,job_spaces.id, pipeline_stages.pipeline_id, jobpair_stage_data.stage_number
		FROM job_pairs
		JOIN job_spaces ON job_spaces.id=job_space_id
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
		LEFT JOIN pipeline_stages ON pipeline_stages.stage_id = jobpair_stage_data.stage_id
		LEFT JOIN solver_pipelines ON pipeline_stages.pipeline_id = solver_pipelines.id
		WHERE job_pairs.job_id=_id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
	END //

-- Retrieves basic info about job pairs for the given job id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetJobPairsPrimaryStageByJob //
CREATE PROCEDURE GetJobPairsPrimaryStageByJob(IN _id INT)
	BEGIN
		SELECT *
		FROM job_pairs

									JOIN 	jobpair_stage_data AS jobpair_stage_data  ON jobpair_stage_data.jobpair_id=job_pairs.id
									JOIN	configurations	AS	config	ON	jobpair_stage_data.config_id = config.id
									JOIN	benchmarks		AS	bench	ON	job_pairs.bench_id = bench.id
									JOIN	solvers			AS	solver	ON	config.solver_id = solver.id
									LEFT JOIN	nodes 			AS node 	ON  job_pairs.node_id=node.id
									LEFT JOIN	job_spaces 		AS  jobSpace ON jobSpace.id=job_pairs.job_space_id

		WHERE job_pairs.job_id=_id AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data
		AND config.deleted = 0
		-- configs are no longer removed from the table, so only non-deleted ones should be selected
		ORDER BY job_pairs.end_time DESC;
	END //




-- Counts the entries in the job space closure table with the given ancestor and updates their last_used time
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RefreshEntriesByAncestor //
CREATE PROCEDURE RefreshEntriesByAncestor(IN _id INT, IN _time TIMESTAMP)
	BEGIN
		UPDATE job_space_closure
		SET last_used=_time
		WHERE ancestor=_id;

		SELECT COUNT(*) AS count
		FROM job_space_closure
		WHERE ancestor=_id;
	END //



-- Gets all the attribute values for benchmarks in the given job
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetAttrsOfNameForJob //
CREATE PROCEDURE GetAttrsOfNameForJob(IN _jobId INT, IN _attrName VARCHAR(128))
	BEGIN
		SELECT job_pairs.bench_id, attr_value
		FROM job_pairs JOIN bench_attributes ON job_pairs.bench_id = bench_attributes.bench_id

		WHERE attr_key=_attrName AND job_id=_jobId;
	END  //

-- Gets all the job pairs in a job space. No stages are retrieved
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairsInJobSpace //
CREATE PROCEDURE GetJobPairsInJobSpace(IN _jobSpaceId INT, IN _jobId INT, IN _stageNumber INT)
	BEGIN
			SELECT job_pairs.status_code,
			job_pairs.id, job_pairs.bench_id, job_pairs.bench_name,
			completion_id, jobpair_stage_data.solver_id,jobpair_stage_data.solver_name, jobpair_stage_data.status_code,
			jobpair_stage_data.config_id,jobpair_stage_data.config_name,jobpair_stage_data.cpu,jobpair_stage_data.stage_id,
			jobpair_stage_data.wallclock, primary_jobpair_data, job_pairs.path,
			anonymous_solver_names.anonymous_name AS anon_solver_name,
			anonymous_config_names.anonymous_name AS anon_config_name,
			anonymous_bench_names.anonymous_name AS anon_bench_name,
			job_attributes.attr_value AS result
			FROM job_pairs
			JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
			LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key="starexec-result")
			LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id
			/* SPAGETT */
			LEFT JOIN anonymous_primitive_names AS anonymous_solver_names ON
						anonymous_solver_names.primitive_id=jobpair_stage_data.solver_id AND anonymous_solver_names.primitive_type="solver"
						AND anonymous_solver_names.job_id = _jobId
			LEFT JOIN anonymous_primitive_names AS anonymous_config_names ON
						anonymous_config_names.primitive_id=jobpair_stage_data.config_id AND anonymous_config_names.primitive_type="config"
						AND anonymous_config_names.job_id = _jobId
			LEFT JOIN anonymous_primitive_names AS anonymous_bench_names ON
						anonymous_bench_names.primitive_id=job_pairs.bench_id AND anonymous_bench_names.primitive_type="bench"
						AND anonymous_bench_names.job_id = _jobId
			WHERE jobpair_stage_data.job_space_id=_jobSpaceId AND
			(jobpair_stage_data.stage_number=_stageNumber OR (_stageNumber = 0 AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number));

	END //

-- Gets all the job pairs in a job space hierarchy. No stages are retrieved
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairsInJobSpaceHierarchy //
CREATE PROCEDURE GetJobPairsInJobSpaceHierarchy(IN _jobSpaceId INT, IN _since INT)
	BEGIN
		SELECT
		status_code,
		job_pairs.id,
		job_pairs.bench_id,
		job_pairs.bench_name,
		anonymous_primitive_names.anonymous_name AS anon_bench_name,
		job_pairs.path,
		completion_id,
		primary_jobpair_data
			FROM job_pairs
			JOIN job_spaces ON job_spaces.id = job_pairs.job_space_id
			LEFT JOIN anonymous_primitive_names ON
				anonymous_primitive_names.primitive_id=job_pairs.bench_id AND anonymous_primitive_names.primitive_type="bench"
						AND anonymous_primitive_names.job_id=job_spaces.job_id
			JOIN job_space_closure ON descendant=job_space_id
			LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id
			WHERE ancestor=_jobSpaceId AND ((_since is null) OR job_pair_completion.completion_id>_since);
	END //

-- Gets all the stages of job pairs in a particular job space
DROP PROCEDURE IF EXISTS GetJobPairStagesInJobSpace //
CREATE PROCEDURE GetJobPairStagesInJobSpace(IN _jobSpaceId INT)
	BEGIN
		SELECT job_pairs.id AS pair_id,jobpair_stage_data.solver_id,jobpair_stage_data.solver_name, jobpair_stage_data.status_code,
		jobpair_stage_data.config_id,jobpair_stage_data.config_name,jobpair_stage_data.cpu,jobpair_stage_data.stage_id,
		jobpair_stage_data.wallclock AS wallclock,job_pairs.id,
		job_attributes.attr_value AS result
		FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key="starexec-result")
		WHERE job_space_id=_jobSpaceId;
	END //

-- Gets all the stages of job pairs in a particular job space
DROP PROCEDURE IF EXISTS GetJobPairStagesInJobSpaceHierarchy //
CREATE PROCEDURE GetJobPairStagesInJobSpaceHierarchy(IN _jobSpaceId INT, IN _since INT)
	BEGIN
		SELECT
		job_pairs.id AS pair_id,
		jobpair_stage_data.solver_id,
		jobpair_stage_data.solver_name,
		jobpair_stage_data.status_code,
		jobpair_stage_data.config_id,
		jobpair_stage_data.config_name,
		jobpair_stage_data.cpu,
		jobpair_stage_data.stage_id,
		jobpair_stage_data.wallclock AS wallclock,
		job_pairs.id, jobpair_stage_data.stage_number,
		jobpair_stage_data.max_vmem,
		bench_attributes.attr_value AS expected,
		job_attributes.attr_value AS result,
		anonymous_solver_names.anonymous_name AS anon_solver_name,
		anonymous_config_names.anonymous_name AS anon_config_name
			FROM job_pairs
			JOIN job_spaces ON job_spaces.id=job_pairs.job_space_id
			JOIN job_space_closure ON descendant=job_space_id
			JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
			LEFT JOIN anonymous_primitive_names AS anonymous_solver_names ON
						anonymous_solver_names.primitive_id=jobpair_stage_data.solver_id AND anonymous_solver_names.primitive_type="solver"
						AND anonymous_solver_names.job_id = job_spaces.job_id
			LEFT JOIN anonymous_primitive_names AS anonymous_config_names ON
						anonymous_config_names.primitive_id=jobpair_stage_data.config_id AND anonymous_config_names.primitive_type="config"
						AND anonymous_config_names.job_id = job_spaces.job_id
			LEFT JOIN job_attributes on (job_attributes.pair_id=job_pairs.id AND job_attributes.stage_number=jobpair_stage_data.stage_number and job_attributes.attr_key="starexec-result")
			LEFT JOIN job_pair_completion ON job_pairs.id=job_pair_completion.pair_id

			LEFT JOIN bench_attributes ON (job_pairs.bench_id=bench_attributes.bench_id AND bench_attributes.attr_key = "starexec-expected-result")
			WHERE ancestor=_jobSpaceId AND ((_since is null) OR job_pair_completion.completion_id>_since);
	END //

-- Counts the number of pairs in a job
-- Author Eric Burns
DROP PROCEDURE IF EXISTS countPairsForJob //
CREATE PROCEDURE countPairsForJob(IN _id INT)
	BEGIN
		SELECT COUNT(*) AS count
		FROM job_pairs
		WHERE job_id=_id;
	END //

DROP PROCEDURE IF EXISTS GetAllJobPairsByJob //
CREATE PROCEDURE GetAllJobPairsByJob(IN _id INT)
	BEGIN
		SELECT *
		FROM job_pairs
						JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
						JOIN	configurations	AS	config	ON	jobpair_stage_data.config_id = config.id
						JOIN	benchmarks		AS	bench	ON	job_pairs.bench_id = bench.id
						JOIN	solvers			AS	solver	ON	config.solver_id = solver.id
						LEFT JOIN	nodes 			AS node 	ON  job_pairs.node_id=node.id
					    LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
		WHERE job_pairs.job_id=_id AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number
		AND config.deleted = 0;
		-- configs are no longer removed from the table, so only non-deleted ones should be selected
	END //

-- Retrieves basic info about job pairs for the given job id for pairs completed after _completionId
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetNewCompletedJobPairsByJob //
CREATE PROCEDURE GetNewCompletedJobPairsByJob(IN _id INT, IN _completionId INT)
	BEGIN
		SELECT *
		FROM job_pairs
						JOIN job_pair_completion AS complete ON job_pairs.id=complete.pair_id
						JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
						JOIN	configurations	AS	config	ON	jobpair_stage_data.config_id = config.id
						JOIN	benchmarks		AS	bench	ON	job_pairs.bench_id = bench.id
						JOIN	solvers			AS	solver	ON	config.solver_id = solver.id
						LEFT JOIN	nodes 			AS node 	ON  job_pairs.node_id=node.id
					    LEFT JOIN job_spaces AS jobSpace ON job_pairs.job_space_id=jobSpace.id
		WHERE job_pairs.job_id=_id AND complete.completion_id>_completionId AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number
		AND config.deleted = 0
		-- configs are no longer removed from the table, so only non-deleted ones should be selected
		ORDER BY job_pairs.end_time DESC;
	END //


-- Retrieves ids for job pairs with a given status in a given job
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobPairsByStatus //
CREATE PROCEDURE GetJobPairsByStatus(IN _jobId INT, IN _statusCode INT)
	BEGIN
		SELECT id FROM job_pairs
		WHERE job_id=_jobId AND status_code=_statusCode ORDER BY id ASC;
	END //

-- Retrieves ids for job pairs in a given job where either cpu or wallclock is 0 for any stage that has the given status code
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetTimelessJobPairsByStatus //
CREATE PROCEDURE GetTimelessJobPairsByStatus(IN _jobId INT, IN _statusCode INT)
	BEGIN
		SELECT DISTINCT job_pairs.id FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_id=_jobId AND jobpair_stage_data.status_code=_statusCode AND (jobpair_stage_data.cpu=0 OR jobpair_stage_data.wallclock=0);
	END //

-- Retrieves information for pending job pairs with the given job id. Returns all stages for _limit pairs.
-- Excludes any job pairs that are utilizing solvers that have still not been built
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetPendingJobPairsByJob //
CREATE PROCEDURE GetPendingJobPairsByJob(IN _id INT, IN _limit INT)
	BEGIN
		SELECT *,
		(SELECT count(*) FROM bench_dependency WHERE primary_bench_id = benchmarks.id) AS dependency_count
		FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
		LEFT JOIN benchmarks ON benchmarks.id = job_pairs.bench_id
		LEFT JOIN solvers ON solvers.id = jobpair_stage_data.solver_id
		JOIN (SELECT DISTINCT job_pairs.id FROM job_pairs FORCE INDEX (job_id_2)
		WHERE job_id = _id AND job_pairs.status_code = 1
		AND NOT EXISTS (SELECT 1 FROM jobpair_stage_data
		LEFT JOIN solvers ON solvers.id = jobpair_stage_data.solver_id
		JOIN job_pairs AS jp ON jp.id=jobpair_id
		JOIN jobs ON jobs.id=jp.job_id
		WHERE jobpair_stage_data.jobpair_id = job_pairs.id AND solvers.build_status=0 AND buildJob=false)
		ORDER BY job_pairs.id ASC LIMIT _limit) AS temp
		ON temp.id=job_pairs.id;
	END //

-- Retrieves basic info about enqueued job pairs for the given job id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetEnqueuedJobPairsByJob //
CREATE PROCEDURE GetEnqueuedJobPairsByJob(IN _id INT)
	BEGIN
		SELECT job_pairs.id,job_pairs.sge_id
		FROM job_pairs
		WHERE (job_id = _id AND status_code = 2)
		ORDER BY sge_id ASC;
	END //

-- Retrieves basic info about running job pairs for the given job id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetRunningJobPairsByJob //
CREATE PROCEDURE GetRunningJobPairsByJob(IN _id INT)
	BEGIN
		SELECT job_pairs.id, job_pairs.sge_id
		FROM job_pairs
		WHERE (job_id = _id AND status_code = 4)
		ORDER BY sge_id ASC;
	END //

-- Returns true if the job in question has the deleted flag set as true
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsJobDeleted //
CREATE PROCEDURE IsJobDeleted(IN _jobId INT)
	BEGIN
		SELECT count(*) AS jobDeleted
		FROM jobs
		WHERE deleted=true AND id=_jobId;
	END //



-- Returns the paused and deleted columns for a job
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsJobPausedOrKilled //
CREATE PROCEDURE IsJobPausedOrKilled(IN _jobId INT)
	BEGIN
		SELECT paused,killed
		FROM jobs
		WHERE id=_jobId;
	END //


-- Sets the "deleted" property of a job to true
-- Also updates the total_pairs and disk_size columns to 0
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS DeleteJob //
CREATE PROCEDURE DeleteJob(IN _jobId INT)
	BEGIN
		UPDATE users JOIN jobs ON jobs.user_id=users.id
		SET users.disk_size=users.disk_size-jobs.disk_size
		WHERE jobs.id=_jobId;

		UPDATE jobs
		SET deleted=true, total_pairs=0, disk_size=0
		WHERE id = _jobId;
	END //

DROP PROCEDURE IF EXISTS UpdateJobDiskSize //
CREATE PROCEDURE UpdateJobDiskSize(IN _jobId INT, IN _diskSize BIGINT)
	BEGIN
		UPDATE users JOIN jobs ON jobs.user_id=users.id
		SET users.disk_size=(users.disk_size-jobs.disk_size)+_diskSize
		WHERE jobs.id=_jobId;


		UPDATE jobs
		SET disk_size=_diskSize
		WHERE id=_jobId;
	END //

-- Deletes every job pair belonging to the given job
DROP PROCEDURE IF EXISTS DeleteAllJobPairsInJob //
CREATE PROCEDURE DeleteAllJobPairsInJob(IN _jobId INT)
	BEGIN
		DELETE FROM job_pairs
		WHERE job_id=_jobId;
	END //

DROP PROCEDURE IF EXISTS GetOrphanedJobIds //
CREATE PROCEDURE GetOrphanedJobIds(IN _userId INT)
	BEGIN
		SELECT jobs.id FROM jobs
		LEFT JOIN job_assoc ON job_assoc.job_id=jobs.id
		WHERE jobs.user_id=_userId AND job_assoc.space_id IS NULL;
	END //

-- Sets the "paused" property of a job to true
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS PauseJob //
CREATE PROCEDURE PauseJob(IN _jobId INT)
	BEGIN
		UPDATE jobs
		SET paused=true
		WHERE id = _jobId;

		UPDATE job_pairs
		SET status_code = 20
		WHERE job_id = _jobId AND status_code = 1;
	END //

-- Sets the global paused flag to true
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS PauseAll //
CREATE PROCEDURE PauseAll()
	BEGIN
		UPDATE system_flags SET paused = true;
	END //

-- Sets the "paused" property of a job to false
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS ResumeJob //
CREATE PROCEDURE ResumeJob(IN _jobId INT)
	BEGIN
		UPDATE jobs
		SET paused=false
		WHERE id = _jobId;

		UPDATE job_pairs
		JOIN jobpair_stage_data on job_pairs.id=jobpair_stage_data.jobpair_id
		SET job_pairs.status_code = 1, jobpair_stage_data.status_code=1
		WHERE job_id = _jobId AND job_pairs.status_code = 20;
	END //

-- sets the global paused flag to false
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS ResumeAll //
CREATE PROCEDURE ResumeAll()
	BEGIN
		UPDATE system_flags SET paused = false;
	END //

-- Sets the "killed" property of a job to true
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS KillJob //
CREATE PROCEDURE KillJob(IN _jobId INT)
	BEGIN
		UPDATE jobs
		SET killed=true
		WHERE id = _jobId;

		UPDATE jobs
		SET paused=false
		WHERE id = _jobId;

		UPDATE job_pairs
		SET status_code = 21
		WHERE job_id = _jobId AND (status_code = 1 OR status_code = 20);

	END //

-- Changes the queueid in the jobs datatable
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS ChangeQueue //
CREATE PROCEDURE ChangeQueue(IN _jobId INT, IN _queueId INT)
	BEGIN
		UPDATE jobs
		SET queue_id = _queueId
		WHERE id = _jobId;
	END //

-- Adds a new job pair record to the database
-- Author: Tyler Jensen + Eric Burns
DROP PROCEDURE IF EXISTS AddJobPair //
CREATE PROCEDURE AddJobPair(IN _jobId INT, IN _benchId INT, IN _status TINYINT, IN _path VARCHAR(2048),IN _jobSpaceId INT, IN _benchName VARCHAR(256), IN _stageNumber INT, OUT _id INT)
	BEGIN
		INSERT INTO job_pairs (job_id, bench_id, status_code, path,job_space_id,bench_name,primary_jobpair_data)
		VALUES (_jobId, _benchId, _status, _path, _jobSpaceId,  _benchName,_stageNumber);
		SELECT LAST_INSERT_ID() INTO _id;
	END //

DROP PROCEDURE IF EXISTS AddJobPairStage //
CREATE PROCEDURE AddJobPairStage(IN _pairId INT, IN _stageId INT,IN _stageNumber INT, IN _primary BOOLEAN, IN _solverId INT, IN _solverName VARCHAR(255), IN _configId INT, IN _configName VARCHAR (255), IN _jobSpace INT)
	BEGIN
		INSERT INTO jobpair_stage_data (jobpair_id, stage_id,stage_number,solver_id,solver_name,config_id,config_name,job_space_id,status_code, disk_size)
		VALUES (_pairId, _stageId,_stageNumber,_solverId,_solverName,_configId,_configName, _jobSpace,1,0);
	END //

-- Adds a new job record to the database
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddJob //
CREATE PROCEDURE AddJob(
		IN _userId INT,
		IN _name VARCHAR(64),
		IN _desc TEXT,
		IN _queueId INT,
		IN _spaceId INT,
		IN _seed BIGINT,
		IN _cpu INT,
		IN _wall INT,
		IN _softTimeLimit INT,
		in _killDelay INT,
		IN _mem BIGINT,
		IN _suppressTimestamp BOOLEAN,
		IN _usingDeps INT,
		IN _buildJob BOOLEAN,
		IN _totalPairs INT,
		IN _benchmarkingFramework ENUM('BENCHEXEC', 'RUNSOLVER'),
		OUT _id INT)
	BEGIN
		INSERT INTO jobs (user_id, name, description, queue_id, primary_space,
				seed, cpuTimeout, clockTimeout, maximum_memory, paused,
				suppress_timestamp, using_dependencies, buildJob, total_pairs,
				soft_time_limit, kill_delay, disk_size, benchmarking_framework)
		VALUES (_userId, _name, _desc, _queueId, _spaceId,
				_seed, _cpu, _wall, _mem, true,
				_suppressTimestamp, _usingDeps, _buildJob, _totalPairs,
				_softTimeLimit, _killDelay, 0, _benchmarkingFramework);
		SELECT LAST_INSERT_ID() INTO _id;
	END //

-- Retrieves all jobs belonging to a user (but not their job pairs)
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS GetUserJobsById //
CREATE PROCEDURE GetUserJobsById(IN _userId INT)
	BEGIN
		SELECT *
		FROM jobs
		WHERE user_id=_userId and deleted=false
		ORDER BY created DESC;
	END //

DROP PROCEDURE IF EXISTS GetQueueJobsById //
CREATE PROCEDURE GetQueueJobsById(IN _queueId INT)
	BEGIN
		SELECT *,
			total_pairs AS totalPairs,
			GetCompletePairs(id) AS completePairs,
			GetPendingPairs(id)  AS pendingPairs,
			GetErrorPairs(id)    AS errorPairs
		FROM jobs
		WHERE queue_id=_queueId
		  AND id IN
			(SELECT distinct job_id FROM job_pairs WHERE status_code BETWEEN 1 AND 6)
		  AND NOT paused
		  AND NOT killed
		ORDER BY created DESC;
	END //

-- Returns the number of jobs in the entire system
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetJobCount //
CREATE PROCEDURE GetJobCount()
	BEGIN
		SELECT COUNT(*) as jobCount
		FROM jobs;
	END //

-- Returns the number of running jobs in the entire system
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetRunningJobCount //
CREATE PROCEDURE GetRunningJobCount()
	BEGIN
		SELECT COUNT(distinct jobs.id) as jobCount
		FROM jobs
		JOIN    job_pairs ON jobs.id = job_pairs.job_id
		WHERE 	job_pairs.status_code < 7;
	END //

-- Returns the number of paused jobs in the entire system
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetPausedJobCount //
CREATE PROCEDURE GetPausedJobCount()
	BEGIN
		SELECT COUNT(distinct jobs.id) as jobCount
		FROM jobs
		JOIN    job_pairs ON jobs.id = job_pairs.job_id
		WHERE 	job_pairs.status_code = 20;
	END //

-- Get the total count of the jobs belong to a specific user
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS GetJobCountByUser //
CREATE PROCEDURE GetJobCountByUser(IN _userId INT)
	BEGIN
		SELECT COUNT(*) AS jobCount
		FROM jobs
		WHERE user_id = _userId and deleted=false;
	END //

-- Returns the number of jobs in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobCountByUserWithQuery //
CREATE PROCEDURE GetJobCountByUserWithQuery(IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT COUNT(*) AS jobCount
		FROM jobs
		WHERE user_id=_userId AND deleted=false AND
				(name				LIKE	CONCAT('%', _query, '%')
				OR		GetJobStatus(id)	LIKE	CONCAT('%', _query, '%'));
	END //
DROP PROCEDURE IF EXISTS GetNameofJobById //
CREATE PROCEDURE GetNameofJobById(IN _jobId INT)
	BEGIN
		SELECT name
		FROM jobs
		where id = _jobId and deleted=false;
	END //

-- Sets the primary space of a job to a new space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdatePrimarySpace //
CREATE PROCEDURE UpdatePrimarySpace(IN _jobId INT, IN _jobSpaceId INT)
	BEGIN
		UPDATE jobs
		SET primary_space=_jobSpaceId
		WHERE id = _jobId;
	END //
-- Populates the solver_name, config_name, and bench_name columns of all pairs in the
-- job_pair table. Should only need to be run once on Starexec and Stardev to get the table
-- up to date
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetNewColumns //
CREATE PROCEDURE SetNewColumns()
	BEGIN
		UPDATE job_pairs
			JOIN benchmarks AS bench ON bench.id=bench_id
			JOIN configurations AS config ON config.id=config_id
			JOIN solvers AS solve ON solve.id=config.solver_id
			SET bench_name=bench.name, solver_name=solve.name, config_name=config.name, job_pairs.solver_id=solve.id;
	END //

-- Gets back only the fields of a job pair that are necessary to determine where it is stored on disk
-- Gets pairs that have either completed after the given completionID or are still running
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetNewJobPairFilePathInfoByJob //
CREATE PROCEDURE GetNewJobPairFilePathInfoByJob(IN _jobID INT, IN _completionID INT)
	BEGIN
		SELECT path,solver_name,config_name,bench_name,job_pairs.status_code,complete.completion_id, id, primary_jobpair_data FROM job_pairs
			LEFT JOIN job_pair_completion AS complete ON job_pairs.id=complete.pair_id
			JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.job_id=_jobID AND (complete.completion_id>_completionId OR job_pairs.status_code=4)
		AND job_pairs.primary_jobpair_data=jobpair_stage_data.stage_number;
	END //



DROP PROCEDURE IF EXISTS RemovePairsFromComplete //
CREATE PROCEDURE RemovePairsFromComplete(IN _jobId INT)
	BEGIN
		DELETE job_pair_completion FROM job_pair_completion
		JOIN job_pairs ON job_pairs.id=job_pair_completion.pair_id
		WHERE job_id=_jobId;
	END //

-- Sets all the pairs of a given job to the given status
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetPairsToStatus //
CREATE PROCEDURE SetPairsToStatus(IN _jobId INT, In _statusCode INT)
	BEGIN
		UPDATE job_pairs
		SET status_code = _statusCode
		WHERE job_id = _jobId;
	END //


-- Sets all the pairs of a given job and status to the given status
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetPairsOfStatusToStatus //
CREATE PROCEDURE SetPairsOfStatusToStatus(IN _jobId INT, IN _newCode INT, IN _curCode INT)
	BEGIN
		UPDATE job_pairs
		SET status_code = _newCode
		WHERE job_id = _jobId AND status_code=_curCode;
	END //

-- Removes all jobs in the database that are deleted and also orphaned. Runs periodically.
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetDeletedJobs //
CREATE PROCEDURE GetDeletedJobs()
	BEGIN
		SELECT * FROM jobs WHERE deleted = true;
	END //

DROP PROCEDURE IF EXISTS GetJobsAssociatedWithSpaces //
CREATE PROCEDURE GetJobsAssociatedWithSpaces()
	BEGIN
		SELECT DISTINCT job_id AS ID FROM job_assoc;
	END //

-- Gives back the number of pairs with the given status
DROP PROCEDURE IF EXISTS CountPairsByStatusByJob //
CREATE PROCEDURE CountPairsByStatusByJob(IN _jobId INT, IN _status INT)
	BEGIN
		SELECT COUNT(*) AS count
		FROM job_pairs
		WHERE job_pairs.job_id=_jobId and _status=status_code;
	END //


-- Gives back the number of pairs with the given status
DROP PROCEDURE IF EXISTS CountTimelessPairsByStatusByJob //
CREATE PROCEDURE CountTimelessPairsByStatusByJob(IN _jobId INT, IN _status INT)
	BEGIN
		SELECT COUNT(distinct job_pairs.id) AS count
		FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.job_id=_jobId and _status=jobpair_stage_data.status_code AND (jobpair_stage_data.wallclock=0 OR jobpair_stage_data.cpu=0);
	END //

-- For a given job, sets every pair at the complete status to the processing status, and also changes the post_processor
-- of the job to the given one
-- Choosing the primary stage is not allowed here-- an actual stage number must be supplied
DROP PROCEDURE IF EXISTS PrepareJobForPostProcessing //
CREATE PROCEDURE PrepareJobForPostProcessing(IN _jobId INT, IN _procId INT, IN _completeStatus INT, IN _processingStatus INT, IN _stageNumber INT)
	BEGIN


		UPDATE job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		SET job_pairs.status_code=_processingStatus,
		jobpair_stage_data.status_code=_processingStatus
		WHERE job_id=_jobId AND job_pairs.status_code=_completeStatus
		AND jobpair_stage_data.status_code=_completeStatus AND
		(jobpair_stage_data.stage_number=_stageNumber);

	-- makes sure there is actually an entry in job_stage_params for this job / stage pair.
	INSERT IGNORE INTO job_stage_params (job_id,stage_number,cpuTimeout,clockTimeout,maximum_memory,space_id,post_processor,pre_processor)
	VALUES (_jobId, _stageNumber,(select cpuTimeout from jobs where jobs.id=_jobId),(select clockTimeout from jobs where jobs.id=_jobId),
	(select maximum_memory from jobs where jobs.id=_jobId), null, _procId,null);

	UPDATE job_stage_params SET post_processor = _procId WHERE job_id=_jobId AND stage_number=_stageNumber;

	END //

DROP PROCEDURE IF EXISTS SetJobStageParams //
CREATE PROCEDURE SetJobStageParams(IN _jobId INT, IN _stage INT, IN _cpu INT, IN _clock INT, IN _mem BIGINT,
IN _space INT, IN _postProc INT, IN _preProc INT, IN _suffix VARCHAR(64), IN _resultsInterval INT, IN _stdoutSave INT, IN _extraSave INT)
	BEGIN
		INSERT INTO job_stage_params (job_id, stage_number,cpuTimeout,clockTimeout,maximum_memory,
		space_id, post_processor, pre_processor, bench_suffix, results_interval, stdout_save_option, extra_output_save_option)
		VALUES (_jobId, _stage,_cpu,_clock,_mem,_space,_postProc,_preProc, _suffix, _resultsInterval, _stdoutSave, _extraSave);
	END //

-- Gets every incomplete Job
DROP PROCEDURE IF EXISTS GetIncompleteJobs //
CREATE PROCEDURE GetIncompleteJobs()
	BEGIN
		SELECT *,
			total_pairs AS totalPairs,
			GetCompletePairs(id) AS completePairs,
			GetPendingPairs(id)  AS pendingPairs,
			GetErrorPairs(id)    AS errorPairs
		FROM jobs
		WHERE GetJobStatus(id)="incomplete" OR paused=true
	;
	END //

-- Gets the ID of every job that is currently running (has incomplete pairs and
-- is not already paused / killed)
DROP PROCEDURE IF EXISTS GetRunningJobs //
CREATE PROCEDURE GetRunningJobs()
	BEGIN
		SELECT id FROM (
		SELECT id, GetJobStatus(id) AS status
		FROM jobs
		WHERE paused=false AND killed=false) AS temp
		WHERE status="incomplete";
	END //

DROP PROCEDURE IF EXISTS GetRunningJobsByUser //
CREATE PROCEDURE GetRunningJobsByUser(IN _userId INT)
	BEGIN
		SELECT id FROM (
		SELECT id, GetJobStatus(id) AS status
		FROM jobs
		WHERE paused=false AND killed=false AND user_id=_userId) AS temp
		WHERE status="incomplete";
	END //

DROP PROCEDURE IF EXISTS SetJobName //
CREATE PROCEDURE SetJobName(IN _jobId INT, IN _newName VARCHAR(64))
	BEGIN
		UPDATE jobs
		SET name = _newName
		WHERE id = _jobId;
	END //

DROP PROCEDURE IF EXISTS SetJobDescription //
CREATE PROCEDURE SetJobDescription(IN _jobId INT, IN _newDescription TEXT)
	BEGIN
		UPDATE jobs
		SET description = _newDescription
		WHERE id = _jobId;
	END //

-- Checks to see if there is a global pause on all jobs
DROP PROCEDURE IF EXISTS IsSystemPaused //
CREATE PROCEDURE IsSystemPaused()
	BEGIN
		SELECT paused
		FROM system_flags;
	END //

-- Permanently removes a job from the database
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RemoveJobFromDatabase //
CREATE PROCEDURE RemoveJobFromDatabase(IN _jobId INT)
	BEGIN
		DELETE FROM jobs WHERE id=_jobId;
	END //

-- Gets all entries in the job_stage_params table referencing the given job
DROP PROCEDURE IF EXISTS getStageParamsByJob //
CREATE PROCEDURE getStageParamsByJob(IN _jobId INT)
	BEGIN
		SELECT * FROM job_stage_params WHERE job_id=_jobId;
	END //

-- Gets all benchmark inputs for all pairs in the given job
DROP PROCEDURE IF EXISTS GetAllJobPairBenchmarkInputsByJob //
CREATE PROCEDURE GetAllJobPairBenchmarkInputsByJob(IN _jobId INT)
	BEGIN
		SELECT jobpair_inputs.jobpair_id,jobpair_inputs.bench_id
		FROM jobpair_inputs JOIN job_pairs ON job_pairs.id=jobpair_inputs.jobpair_id
		WHERE job_pairs.job_id=_jobId ORDER BY input_number ASC;
	END //

DROP PROCEDURE IF EXISTS GetAllJobIds //
CREATE PROCEDURE GetAllJobIds()
	BEGIN
		SELECT id FROM jobs;
	END //

DROP PROCEDURE IF EXISTS CountPairsByUser //
CREATE PROCEDURE CountPairsByUser(IN _userId INT)
	BEGIN
		SELECT SUM(total_pairs) AS total_pairs FROM jobs WHERE user_id=_userId AND deleted=false;
	END //

DROP PROCEDURE IF EXISTS IncrementTotalJobPairsForJob //
CREATE PROCEDURE IncrementTotalJobPairsForJob(IN _jobId INT, IN _increment INT)
	BEGIN
		UPDATE jobs SET total_pairs=total_pairs+_increment WHERE id=_jobId;
	END //

DROP PROCEDURE IF EXISTS DoesJobCopyBackIncrementally //
CREATE PROCEDURE DoesJobCopyBackIncrementally(IN _jobId INT, OUT _jobCopiesBackIncrementally BOOLEAN)
    BEGIN
        SELECT (COUNT(*) <> 0) INTO _jobCopiesBackIncrementally
        FROM job_stage_params
        WHERE results_interval <> 0 and _jobId = job_id;
    END //

DROP PROCEDURE IF EXISTS GetJobAttributesTableHeaders //
CREATE PROCEDURE GetJobAttributesTableHeaders(IN _jobSpaceId INT)
    BEGIN
        SELECT ja.attr_value
        FROM job_attributes ja INNER JOIN job_pairs jp
            ON ja.pair_id=jp.id
        WHERE ja.attr_key = "starexec-result" AND jp.job_space_id=_jobSpaceId
        GROUP BY attr_value
				ORDER BY attr_value;
    END //

DROP PROCEDURE IF EXISTS GetJobAttributesTable //
CREATE PROCEDURE GetJobAttributesTable(IN _jobSpaceId INT)
    BEGIN
        SELECT solver_id, solver_name, config_id, config_name, attr_value, COUNT(attr_value) attr_count,
					SUM(wallclock) wallclock_sum, SUM(cpu) cpu_sum
        FROM job_attributes ja JOIN job_pairs jp ON ja.pair_id=jp.id
            JOIN jobpair_stage_data jsd ON jp.id = jsd.jobpair_id
        WHERE ja.attr_key = 'starexec-result' AND jp.job_space_id=_jobSpaceId
        GROUP BY solver_id, config_id, attr_value;
    END //

DROP PROCEDURE IF EXISTS GetSumOfJobAttributes //
CREATE PROCEDURE GetSumOfJobAttributes(IN _jobSpaceId INT)
    BEGIN
        SELECT attr_value, COUNT(attr_value) attr_count, SUM(wallclock) wallclock, SUM(cpu) cpu
        FROM job_attributes ja JOIN job_pairs jp ON ja.pair_id=jp.id
            JOIN jobpair_stage_data jsd ON jp.id=jsd.jobpair_id
        WHERE ja.attr_key='starexec-result' AND jp.job_space_id=_jobSpaceId
        GROUP BY attr_value
				ORDER BY attr_value;
    END //


-- ================================================================================
-- Misc PROCEDURES
-- ================================================================================

-- Description: This file contains all miscellaneous stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new historical record to the logins table which tracks all user logins
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS LoginRecord //
CREATE PROCEDURE LoginRecord(IN _userId INT, IN _ipAddress VARCHAR(15), IN _agent TEXT)
	BEGIN
		INSERT INTO logins (user_id, login_date, ip_address, browser_agent)
		VALUES (_userId, SYSDATE(), _ipAddress, _agent);
	END //

DROP PROCEDURE IF EXISTS SetReadOnly //
CREATE PROCEDURE SetReadOnly(IN readOnly BOOLEAN)
	BEGIN
		UPDATE system_flags SET read_only=readOnly;
	END //

DROP PROCEDURE IF EXISTS GetReadOnly //
CREATE PROCEDURE GetReadOnly()
	BEGIN
		SELECT read_only FROM system_flags;
	END //


DROP PROCEDURE IF EXISTS SetFreezePrimitives //
CREATE PROCEDURE SetFreezePrimitives(IN frozen BOOLEAN)
	BEGIN
		UPDATE system_flags SET freeze_primitives=frozen;
	END //

DROP PROCEDURE IF EXISTS GetFreezePrimitives //
CREATE PROCEDURE GetFreezePrimitives()
	BEGIN
		SELECT freeze_primitives FROM system_flags;
	END //

DROP PROCEDURE IF EXISTS SetStatusMessage //
CREATE PROCEDURE SetStatusMessage(IN _enabled BOOLEAN, IN _message TEXT, IN _url TEXT)
	BEGIN
		UPDATE ui_status_message SET enabled=_enabled, message=_message, url=_url;
	END //

DROP PROCEDURE IF EXISTS GetStatusMessage //
CREATE PROCEDURE GetStatusMessage()
	BEGIN
		SELECT enabled, message, url FROM ui_status_message;
	END //

DROP PROCEDURE IF EXISTS GetPairTimes //
CREATE PROCEDURE GetPairTimes(IN _jobID INT)
	BEGIN
		SELECT start_time, end_time FROM job_pairs WHERE job_id = _jobID;
	END //

-- ================================================================================
-- Notifications PROCEDURES
-- ================================================================================

-- Description: This file contains stored procedures for sending notifications

-- Subscribe a User to status updates from a Job
-- Record the current status of Job so we can see when it changes
DROP PROCEDURE IF EXISTS SubscribeUserToJob //
CREATE PROCEDURE SubscribeUserToJob(IN _userId INT, IN _jobId INT)
	BEGIN
		INSERT
			INTO notifications_jobs_users (user_id, job_id, last_seen_status)
			VALUES (_userId, _jobId, GetJobStatusDetail(_jobId))
		;
	END //

-- Unsubscribe a User from status updates to a Job
DROP PROCEDURE IF EXISTS UnsubscribeUserFromJob //
CREATE PROCEDURE UnsubscribeUserFromJob(IN _userId INT, IN _jobId INT)
	BEGIN
		DELETE
			FROM notifications_jobs_users
			WHERE user_id=_userId
			  AND job_id=_jobId
		;
	END //

-- Find all Jobs that Users have subscribed to whose current status is different
--   from last recorded status. Does NOT modify any data. Status must be updated
--   if a notification is sent.
-- Returns a list of Job ID and User emails for sending notifications
DROP PROCEDURE IF EXISTS NotifyUsersOfJobs //
CREATE PROCEDURE NotifyUsersOfJobs()
	BEGIN
		SELECT job_id AS "job", user_id as "user", first_name, last_name, users.email AS "email", GetJobStatusDetail(job_id) AS "status"
			FROM notifications_jobs_users
			LEFT JOIN users ON users.id=user_id
			WHERE last_seen_status <> GetJobStatusDetail(job_id)
		;
	END //

-- Update the last_seen_status of a Job-User notification to the current status
--   of Job, then clean up the table deleting any notifications for Jobs with an
--   immutable status
DROP PROCEDURE IF EXISTS UpdateNotificationJobStatus //
CREATE PROCEDURE UpdateNotificationJobStatus(IN _userId INT, IN _jobId INT, IN _status CHAR(16))
	BEGIN
		UPDATE notifications_jobs_users
			SET last_seen_status=_status
			WHERE user_id=_userId
			  AND job_id=_jobId
		;
		DELETE
			FROM notifications_jobs_users
			WHERE last_seen_status="COMPLETE"
			   OR last_seen_status="DELETED"
		;
	END //

DROP PROCEDURE IF EXISTS UserSubscribedToJob //
CREATE PROCEDURE UserSubscribedToJob(IN _userId INT, IN _jobId INT)
	BEGIN
		SELECT _jobId IN (
			SELECT job_id
			FROM notifications_jobs_users
			WHERE user_id=_userId
		);
	END //


-- ================================================================================
-- PairsRerun PROCEDURES
-- ================================================================================

-- Description: This file contains all procedures related to the pairs_rerun table.

DROP PROCEDURE IF EXISTS HasPairBeenRerun //
CREATE PROCEDURE HasPairBeenRerun(IN _pairId INT)
	BEGIN
		SELECT *
		FROM pairs_rerun
		WHERE pair_id=_pairId;
	END //

DROP PROCEDURE IF EXISTS MarkPairAsRerun //
CREATE PROCEDURE MarkPairAsRerun(IN _pairId INT)
	BEGIN
		INSERT INTO pairs_rerun (pair_id)
		VALUES (_pairId);
	END //

DROP PROCEDURE IF EXISTS UnmarkPairAsRerun //
CREATE PROCEDURE UnmarkPairAsRerun(IN _pairId INT)
  BEGIN
    DELETE FROM pairs_rerun
    WHERE pair_id=_pairId;
  END //


-- ================================================================================
-- Permissions PROCEDURES
-- ================================================================================

-- Description: This file contains all permissions-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new permissions record with the given permissions
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddPermissions //
CREATE PROCEDURE AddPermissions(IN _addSolver TINYINT(1), IN _addBench TINYINT(1), IN _addUser TINYINT(1),
IN _addSpace TINYINT(1), IN _addJob TINYINT(1), IN _removeSolver TINYINT(1), IN _removeBench TINYINT(1), IN _removeSpace TINYINT(1),
IN _removeUser TINYINT(1), IN _removeJob TINYINT(1), IN _isLeader TINYINT(1), OUT id INT)
	BEGIN
		INSERT INTO permissions
			(add_solver, add_bench, add_user, add_space, add_job, remove_solver,
			remove_bench, remove_space, remove_user, remove_job, is_leader)
		VALUES
			(_addSolver, _addBench, _addUser, _addSpace, _addJob, _removeSolver,
			_removeBench, _removeSpace, _removeUser, _removeJob, _isLeader);
		SELECT LAST_INSERT_ID() INTO id;
	END //

-- Returns 1 if the given user can somehow see the given solver, 0 otherwise
-- Author: Tyler Jensen + Eric Burns
DROP PROCEDURE IF EXISTS CanViewSolver //
CREATE PROCEDURE CanViewSolver(IN _solverId INT, IN _userId INT)
	BEGIN
		SELECT IF((
			SELECT COUNT(*)
			FROM solver_assoc
			JOIN user_assoc ON user_assoc.space_id=solver_assoc.space_id					-- Join on user_assoc to get all the users that belong to those spaces
			WHERE solver_assoc.solver_id=_solverId AND user_assoc.user_id=_userId)			-- But only count those for the solver and user we're looking for
		> 0, 1, (SELECT COUNT(*) FROM solvers WHERE solvers.id=_solverId AND solvers.user_id=_userId)) -- If there were more than 0 results, return 1, else check to see if the user owns the solver, and return under the name 'verified'
		 AS verified;
	END //

-- Author: Tyler Jensen + Eric Burns
DROP PROCEDURE IF EXISTS CanViewBenchmark //
CREATE PROCEDURE CanViewBenchmark(IN _benchId INT, IN _userId INT)
	BEGIN
		SELECT IF((
			SELECT COUNT(*)
			FROM bench_assoc
			JOIN user_assoc ON user_assoc.space_id=bench_assoc.space_id             -- Join on user_assoc to get all the users that belong to those spaces
			WHERE bench_assoc.bench_id=_benchId AND user_assoc.user_id=_userId)            -- But only count those for the benchmark and user we're looking for
		> 0, 1, (SELECT COUNT(*) FROM benchmarks WHERE benchmarks.id=_benchId AND benchmarks.user_id=_userId)) AS verified; 												    -- If there were more than 0 results, return 1, else return 0, and return under the name 'verified'
	END //

-- Returns 1 if the given user either shares a space with the job or owns it
-- Author: Tyler Jensen	+ Eric Burns
DROP PROCEDURE IF EXISTS CanViewJob //
CREATE PROCEDURE CanViewJob(IN _jobId INT, IN _userId INT)
	BEGIN
		SELECT IF((
			SELECT COUNT(*)
			FROM job_assoc
			JOIN user_assoc ON user_assoc.space_id=job_assoc.space_id -- Join on user_assoc to get all the users that belong to those spaces
			WHERE job_assoc.job_id=_jobId AND user_assoc.user_id=_userId)      -- But only count those for the job and user we're looking for
		> 0, 1, (SELECT COUNT(*) FROM jobs WHERE jobs.id=_jobId AND jobs.user_id=_userId )) AS verified;
	END //

-- Returns 1 if the given user can somehow see the given space, 0 otherwise
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS CanViewSpace //
CREATE PROCEDURE CanViewSpace(IN _spaceId INT, IN _userId INT)
	BEGIN
		SELECT COUNT(*) -- will return 1 if the user is in the space and 0 if they are not
		FROM user_assoc
		WHERE space_id=_spaceId AND user_id=_userId;
	END //


-- Finds the maximal set of permissions for the given user on the given space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetUserPermissions //
CREATE PROCEDURE GetUserPermissions(IN _userId INT, IN _spaceId INT)
	BEGIN
		SELECT MAX(add_solver) AS add_solver,
			MAX(add_bench) AS add_bench,
			MAX(add_user) AS add_user,
			MAX(add_space) AS add_space,
			MAX(add_job) AS add_job,
			MAX(remove_solver) AS remove_solver,
			MAX(remove_bench) AS remove_bench,
			MAX(remove_space) AS remove_space,
			MAX(remove_user) AS remove_user,
			MAX(remove_job) AS remove_job,
			MAX(is_leader) AS is_leader
		FROM permissions JOIN user_assoc ON user_assoc.permission=permissions.id
		WHERE user_assoc.user_id=_userId AND user_assoc.space_id=_spaceId;
	END //

-- Finds the default user permissions for the given space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSpacePermissions //
CREATE PROCEDURE GetSpacePermissions(IN _spaceId INT)
	BEGIN
		SELECT permissions.*
		FROM permissions JOIN spaces ON spaces.default_permission=permissions.id
		WHERE spaces.id=_spaceId;
	END //

-- Copies one set of permissions into another with a new ID
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS CopyPermissions //
CREATE PROCEDURE CopyPermissions(IN _permId INT, OUT _newId INT)
	BEGIN
		INSERT INTO permissions (add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader)
		(SELECT add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader
		FROM permissions
		WHERE id=_permId);
		SELECT LAST_INSERT_ID() INTO _newId;
	END //


-- Sets a user's permissions for a given space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS SetUserPermissions //
CREATE PROCEDURE SetUserPermissions(IN _userId INT, IN _spaceId INT,IN _addSolver TINYINT(1), IN _addBench TINYINT(1), IN _addUser TINYINT(1),
IN _addSpace TINYINT(1), IN _addJob TINYINT(1), IN _removeSolver TINYINT(1), IN _removeBench TINYINT(1), IN _removeSpace TINYINT(1),
IN _removeUser TINYINT(1), IN _removeJob TINYINT(1), IN _isLeader TINYINT(1))
	BEGIN
		UPDATE permissions JOIN user_assoc ON permissions.id=user_assoc.permission
		SET add_user      = _addUser,
			add_solver    = _addSolver,
			add_bench     = _addBench,
			add_job       = _addJob,
			add_space     = _addSpace,
			remove_user   = _removeUser,
			remove_solver = _removeSolver,
			remove_bench  = _removeBench,
			remove_job    = _removeJob,
			remove_space  = _removeSpace,
			is_leader     = _isLeader
		WHERE user_id = _userId
		AND space_id = _spaceId;
	END //

-- Updates the permission set with the given id
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdatePermissions //
CREATE PROCEDURE UpdatePermissions(IN _id INT, IN _addSolver BOOLEAN, IN _addBench BOOLEAN, IN _addUser BOOLEAN,
IN _addSpace BOOLEAN, IN _addJob BOOLEAN, IN _removeSolver BOOLEAN, IN _removeBench BOOLEAN, IN _removeSpace BOOLEAN,
IN _removeUser BOOLEAN, IN _removeJob BOOLEAN)
	BEGIN
		UPDATE permissions
		SET add_user      = _addUser,
			add_solver    = _addSolver,
			add_bench     = _addBench,
			add_job       = _addJob,
			add_space     = _addSpace,
			remove_user   = _removeUser,
			remove_solver = _removeSolver,
			remove_bench  = _removeBench,
			remove_job    = _removeJob,
			remove_space  = _removeSpace
		WHERE id = _id;
	END //


-- Sets a user's permissions for a given space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS SetUserPermissions2 //
CREATE PROCEDURE SetUserPermissions2(IN _userId INT, IN _spaceId INT,IN _permissionId INT)
	BEGIN
		UPDATE user_assoc
		SET	permission	= _permissionId
		WHERE user_id = _userId && space_id = _spaceId;
	END //


-- ================================================================================
-- Pipelines PROCEDURES
-- ================================================================================

-- Gets data from the solver_pipelines table for the given id
DROP PROCEDURE IF EXISTS GetPipelineById //
CREATE PROCEDURE GetPipelineById(IN _id INT)
	BEGIN
		SELECT * FROM solver_pipelines WHERE id=_id;
	END //

-- Gets all the stage information from the pipeline_stages table for the given pipeline
DROP PROCEDURE IF EXISTS GetStagesByPipelineId //
CREATE PROCEDURE GetStagesByPipelineId(IN _id INT)
	BEGIN
		SELECT * FROM pipeline_stages WHERE pipeline_id=_id;
	END //

-- Given a stage ID, gets all the dependencies for the stage
DROP PROCEDURE IF EXISTS GetDependenciesForPipelineStage //
CREATE PROCEDURE GetDependenciesForPipelineStage(IN _id INT)
	BEGIN
		SELECT * FROM pipeline_dependencies WHERE stage_id=_id ORDER BY input_number;
	END //

-- Given a stage ID, gets all the dependencies for the stage
DROP PROCEDURE IF EXISTS GetDependenciesForJobPair //
CREATE PROCEDURE GetDependenciesForJobPair(IN _pairId INT)
	BEGIN
		SELECT pipeline_dependencies.stage_id, pipeline_dependencies.input_type,pipeline_dependencies.input_id,
		pipeline_dependencies.input_number
		FROM jobpair_stage_data
		JOIN pipeline_dependencies ON pipeline_dependencies.stage_id=jobpair_stage_data.stage_id
		WHERE jobpair_id=_pairId ORDER BY input_number;
	END //

-- Adds a solver pipeline to the database
DROP PROCEDURE IF EXISTS AddPipeline //
CREATE PROCEDURE AddPipeline(IN _uid INT, IN _name VARCHAR(128), OUT _id INT)
	BEGIN
		INSERT INTO solver_pipelines (user_id, name, uploaded) VALUES (_uid, _name, NOW());

		SELECT LAST_INSERT_ID() INTO _id;

	END //

-- adds a solver pipeline stage for an existing pipeline to the database.
-- pipelines must be added to the database in the order that they are to be used in the pipeline
-- to ensure that the AUTO_INCREMENT IDs are ordered
DROP PROCEDURE IF EXISTS AddPipelineStage //
CREATE PROCEDURE AddPipelineStage(IN _pid INT, IN _cid INT, IN _primary INT,IN _noop BOOLEAN, OUT _id INT)
	BEGIN
		INSERT INTO pipeline_stages (pipeline_id, config_id,is_noop)
		VALUES (_pid, _cid,_noop);

		SELECT LAST_INSERT_ID() INTO _id;
		IF _primary THEN
			UPDATE solver_pipelines SET primary_stage_id = _id WHERE solver_pipelines.id = _pid;
		END IF;
	END //

-- Adds a dependency for an existing stage.
DROP PROCEDURE IF EXISTS AddPipelineDependency //
CREATE PROCEDURE AddPipelineDependency(IN _sid INT, IN _iid INT, IN _type INT, IN _num INT)
	BEGIN
		INSERT INTO pipeline_dependencies (stage_id, input_id, input_type, input_number) VALUES (_sid, _iid,_type, _num);

	END //

-- deletes a pipeline from the database. This will also delete all of its dependencies and stages
DROP PROCEDURE IF EXISTS DeletePipeline //
CREATE PROCEDURE DeletePipeline(IN _pid INT)
	BEGIN
		DELETE FROM solver_pipelines WHERE id=_pid;
	END //

-- Gets all the pipeline IDs of pipelines referenced by the given job
DROP PROCEDURE IF EXISTS GetPipelineIdsByJob //
CREATE PROCEDURE GetPipelineIdsByJob(IN _jid INT)
	BEGIN
		SELECT DISTINCT solver_pipelines.id
		FROM job_pairs
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id=job_pairs.id
		JOIN pipeline_stages ON pipeline_stages.stage_id = jobpair_stage_data.stage_id
		JOIN solver_pipelines ON solver_pipelines.id = pipeline_stages.pipeline_id
		WHERE job_id=_jid;
	END //


-- ================================================================================
-- Processors PROCEDURES
-- ================================================================================

-- Description: This file contains all processor-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new processor with the given information
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddProcessor //
CREATE PROCEDURE AddProcessor(IN _name VARCHAR(64), IN _desc TEXT, IN _path TEXT, IN _comId INT, IN _type TINYINT, IN _diskSize BIGINT, IN _time_limit TINYINT, OUT _id INT)
	BEGIN
		INSERT INTO processors (name, description, path, community, processor_type, disk_size, time_limit)
		VALUES (_name, _desc, _path, _comId, _type, _diskSize, _time_limit);

		SELECT LAST_INSERT_ID() INTO _id;
	END //

-- Removes the association between a processor and a given space,
-- and inserts the processor_path into _path, so the physical file(s) can
-- be removed from disk
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS DeleteProcessor //
CREATE PROCEDURE DeleteProcessor(IN _id INT, OUT _path TEXT)
	BEGIN
		SELECT path INTO _path FROM processors WHERE id = _id;
		DELETE FROM processors
		WHERE id = _id;
	END //

-- Gets all processors of a given type
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetAllProcessors //
CREATE PROCEDURE GetAllProcessors(IN _type TINYINT)
	BEGIN
		SELECT *
		FROM processors
		WHERE processor_type=_type
		ORDER BY name;
	END //

-- Retrieves all processor belonging to a community
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetProcessorsByCommunity //
CREATE PROCEDURE GetProcessorsByCommunity(IN _id INT, IN _type TINYINT)
	BEGIN
		SELECT *
		FROM processors
		WHERE community=_id AND processor_type=_type
		ORDER BY name;
	END //

-- Retrieves all processors in all communities a user is a part of
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetProcessorsByUser //
CREATE PROCEDURE GetProcessorsByUser(IN _userId INT, IN _type TINYINT)
	BEGIN
		SELECT *
		FROM processors
		WHERE processor_type=_type
		AND community IN (
			SELECT ancestor
			FROM closure
			JOIN user_assoc ON user_assoc.space_id=closure.descendant
			WHERE user_assoc.user_id=_userId
		);
	END //


-- Gets the processor with the given ID
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetProcessorById //
CREATE PROCEDURE GetProcessorById(IN _id INT)
	BEGIN
		SELECT *
		FROM processors
		WHERE id=_id;
	END //

-- Updates a processor's description
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateProcessorDescription //
CREATE PROCEDURE UpdateProcessorDescription(IN _id INT, IN _desc TEXT)
	BEGIN
		UPDATE processors
		SET description=_desc
		WHERE id=_id;
	END //

-- Updates a processor's file path
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdateProcessorFilePath //
CREATE PROCEDURE UpdateProcessorFilePath(IN _id INT, IN _path TEXT)
	BEGIN
		UPDATE processors
		SET path=_path
		WHERE id=_id;
	END //

-- Updates a processor's name
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateProcessorName //
CREATE PROCEDURE UpdateProcessorName(IN _id INT, IN _name VARCHAR(64))
	BEGIN
		UPDATE processors
		SET name=_name
		WHERE id=_id;
	END //

-- Updates a processor's processor path
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateProcessorPath //
CREATE PROCEDURE UpdateProcessorPath(IN _id INT, IN _path TEXT, IN _diskSize BIGINT)
	BEGIN
		UPDATE processors
		SET path=_path,
			disk_size=_diskSize
		WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS UpdateProcessorTimeLimit //
CREATE PROCEDURE UpdateProcessorTimeLimit(IN _id INT, IN _timeLimit TINYINT)
	BEGIN
		UPDATE processors
		SET time_limit=_timeLimit
		WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS UpdateProcessorSyntax //
CREATE PROCEDURE UpdateProcessorSyntax(IN _id INT, IN _syntax INT)
	BEGIN
		UPDATE processors
		SET syntax_id=_syntax
		WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS GetAllSyntaxes //
CREATE PROCEDURE GetAllSyntaxes()
	BEGIN
		SELECT * FROM syntax;
	END //


-- ================================================================================
-- Queues PROCEDURES
-- ================================================================================

-- Adds a new queue given a name
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddQueue //
CREATE PROCEDURE AddQueue(IN _name VARCHAR(128),IN _wall INT, IN _cpu INT, OUT id INT)
	BEGIN
		INSERT IGNORE INTO queues (name,clockTimeout,cpuTimeout, status)
		VALUES (_name,_wall,_cpu, "INACTIVE");
		SELECT LAST_INSERT_ID() INTO id;
	END //

-- Remove a queue given its id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueue //
CREATE PROCEDURE RemoveQueue(IN _queueId INT)
	BEGIN
		DELETE FROM queues
		WHERE id = _queueId;
	END //

-- Retrieves the id of a queue given its name
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetIdByName //
CREATE PROCEDURE GetIdByName(IN _queueName VARCHAR(64))
	BEGIN
		SELECT id
		FROM queues
		WHERE name = _queueName;
	END //


-- Retrieves all jobs with pending job pairs for the given queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetPendingJobs //
CREATE PROCEDURE GetPendingJobs(IN _queueId INT)
	BEGIN
		SELECT distinct jobs.*
		FROM jobs WHERE queue_id = _queueId
		AND EXISTS (select 1 from job_pairs FORCE INDEX (job_id_2) WHERE status_code=1 and job_id=jobs.id);
	END //

-- Retrieves all pending job pairs for a give queue owned by a developer
DROP PROCEDURE IF EXISTS GetPendingDeveloperJobs //
CREATE PROCEDURE GetPendingDeveloperJobs(IN _queueId INT)
    BEGIN
        SELECT DISTINCT jobs.*
        FROM users u
        INNER JOIN user_roles ur
            ON u.email = ur.email
        INNER JOIN jobs
            ON jobs.user_id = u.id
        WHERE ur.role = 'developer' OR ur.role = 'admin' AND queue_id = _queueId
        AND EXISTS (select 1 from job_pairs FORCE INDEX (job_id_2) WHERE status_code=1 and job_id=jobs.id);
    END //

-- Retrieves the number of enqueued job pairs for the given queue
-- Author: Benton McCune and Aaron Stump
DROP PROCEDURE IF EXISTS GetNumEnqueuedJobs //
CREATE PROCEDURE GetNumEnqueuedJobs(IN _queueId INT)
	BEGIN
		SELECT COUNT(*) AS count FROM job_pairs JOIN jobs ON job_pairs.job_id = jobs.id
                WHERE job_pairs.status_code=2 AND jobs.queue_id = _queueId;
	END //


-- Gets the sum of wallclock timeouts for all
DROP PROCEDURE IF EXISTS GetUserLoadOnQueue //
CREATE PROCEDURE GetUserLoadOnQueue(IN _queueId INT, IN _user INT)
	BEGIN
		SELECT SUM(jobs.clockTimeout) AS queue_load FROM job_pairs JOIN jobs ON job_pairs.job_id = jobs.id
                WHERE (job_pairs.status_code=4 OR job_pairs.status_code=2)
                AND jobs.queue_id = _queueId AND jobs.user_id=_user;
	END //

-- Retrieves basic info about enqueued job pairs for the given queue id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetCountOfEnqueuedJobPairsByQueue //
CREATE PROCEDURE GetCountOfEnqueuedJobPairsByQueue(IN _id INT)
	BEGIN
		SELECT count(*) AS count
		FROM job_pairs
			-- Where the job_pair is running on the input Queue
			INNER JOIN jobs AS enqueued ON job_pairs.job_id = enqueued.id
		WHERE enqueued.queue_id = _id AND job_pairs.status_code = 2;
	END //

-- Get the name of a queue given its id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNameById //
CREATE PROCEDURE GetNameById(IN _queueId INT)
	BEGIN
		SELECT name
		FROM queues
		WHERE id = _queueId;
	END //

-- Updates the max wallclock timeout for a queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdateQueueClockTimeout //
CREATE PROCEDURE UpdateQueueClockTimeout(IN _queueId INT, IN _timeout INT)
	BEGIN
		UPDATE queues
		SET clockTimeout=_timeout
		WHERE id=_queueId;
	END //

-- Updates the max cpu timeout for a queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdateQueueCpuTimeout //
CREATE PROCEDURE UpdateQueueCpuTimeout(IN _queueId INT, IN _timeout INT)
	BEGIN
		UPDATE queues
		SET cpuTimeout=_timeout
		WHERE id=_queueId;
	END //

-- Determines if the queue has global access
-- Author: Wyatt kaiser
DROP PROCEDURE IF EXISTS IsQueueGlobal //
CREATE PROCEDURE IsQueueGlobal (IN _queueId INT)
	BEGIN
		SELECT global_access
		FROM queues
		WHERE id = _queueId;
	END //

-- Removes a queue's association with a space
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueueAssociation //
CREATE PROCEDURE RemoveQueueAssociation(IN _queueId INT)
	BEGIN
		DELETE FROM comm_queue
		WHERE queue_id = _queueId;
	END //

-- Make a queue have global access
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS MakeQueueGlobal //
CREATE PROCEDURE MakeQueueGlobal(IN _queueId INT)
	BEGIN
		UPDATE queues
		SET global_access = true
		WHERE id = _queueId;

		DELETE FROM comm_queue
		WHERE queue_id = _queueId;
	END //

-- remove global access from a queue
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueueGlobal //
CREATE PROCEDURE RemoveQueueGlobal(IN _queueId INT)
	BEGIN
		UPDATE queues
		SET global_access = false
		WHERE id = _queueId;
	END //

-- Sets the test queue in the database to a new value
DROP PROCEDURE IF EXISTS SetTestQueue //
CREATE PROCEDURE SetTestQueue(IN _qid INT)
	BEGIN
		UPDATE system_flags SET test_queue=_qid;
	END //
-- Gets the ID of the queue for running test jobs on solver uploads
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetTestQueue //
CREATE PROCEDURE GetTestQueue()
	BEGIN
		SELECT test_queue FROM system_flags;
	END //

-- Give the community (leaders) Access to a queue
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS SetQueueCommunityAccess //
CREATE PROCEDURE SetQueueCommunityAccess(IN _communityId INT, IN _queueId INT)
	BEGIN
		INSERT INTO comm_queue
		VALUES (_communityId, _queueId);
	END //


DROP PROCEDURE IF EXISTS GetPairsRunningOnNode //
CREATE PROCEDURE GetPairsRunningOnNode(IN _nodeId INT)
	BEGIN
		SELECT job_pairs.id,
			   job_pairs.path,
			   job_pairs.primary_jobpair_data,
			   job_pairs.job_id,
			   job_pairs.bench_id,
			   job_pairs.bench_name,
			   job_pairs.queuesub_time,
			   jobpair_stage_data.solver_id,
			   jobpair_stage_data.solver_name,
			   jobpair_stage_data.config_id,
			   jobpair_stage_data.config_name,
			   jobs.id,
			   jobs.name,
			   users.id,
			   users.first_name,
			   users.last_name
		FROM job_pairs
		JOIN jobs ON jobs.id = job_pairs.job_id
		JOIN users ON users.id = jobs.user_id
		JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id

		WHERE node_id = _nodeId AND (job_pairs.status_code = 4 OR job_pairs.status_code = 3) AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data;
	END //


-- Gets all of the queues that the given user is allowed to use
DROP PROCEDURE IF EXISTS GetQueuesForUser //
CREATE PROCEDURE GetQueuesForUser(IN _userID INT)
	BEGIN
		SELECT DISTINCT id, name, status, global_access, cpuTimeout,clockTimeout
		FROM queues
			LEFT JOIN comm_queue ON queues.id = comm_queue.queue_id
		WHERE
			queues.status = "ACTIVE"
			AND (
				(IsLeader(comm_queue.space_id, _userId) = 1)	-- Either you are the leader of the community it was given access to
				OR
				(global_access)							-- or it is a global queue
				);
	END //


DROP PROCEDURE IF EXISTS GetDescForQueue //
CREATE PROCEDURE GetDescForQueue(IN _qID INT)
	BEGIN
		SELECT description 
		FROM queues 
		WHERE id = _qID;
	END //

DROP PROCEDURE IF EXISTS SetDescForQueue //
CREATE PROCEDURE SetDescForQueue(IN _qID INT, IN _desc VARCHAR(200)) 
	BEGIN
		UPDATE queues
		SET description = _desc
		WHERE id = _qid;
	END //

-- ================================================================================
-- Reports PROCEDURES
-- ================================================================================

-- Description: This file contains all weekly-report-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Set the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS SetEventOccurrencesNotRelatedToQueue //
CREATE PROCEDURE SetEventOccurrencesNotRelatedToQueue(IN _eventName VARCHAR(64), IN _eventOccurrences INT)
	BEGIN
		UPDATE report_data
		SET occurrences = _eventOccurrences
		WHERE event_name = _eventName AND queue_name IS NULL;
	END //

-- Set the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS SetEventOccurrencesForQueue //
CREATE PROCEDURE SetEventOccurrencesForQueue(IN _eventName VARCHAR(64), IN _eventOccurrences INT, IN _queueName VARCHAR(64))
	BEGIN
		-- check if the event already exists for this queue and set it if it does
		IF EXISTS (SELECT 1 FROM report_data WHERE queue_name=_queueName) AND EXISTS (SELECT 1 FROM report_data WHERE event_name=_eventName) THEN
			UPDATE report_data
			SET occurrences = _eventOccurrences
			WHERE event_name = _eventName AND queue_name = _queueName;
		-- otherwise create the event with the given number of occurrences
		ELSE
			INSERT INTO report_data (event_name, queue_name, occurrences)
			VALUES (_eventName, _queueName, _eventOccurrences);
		END IF;
	END //


-- Adds to the value of an event's occurrences not related to a queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS AddToEventOccurrencesNotRelatedToQueue //
CREATE PROCEDURE AddToEventOccurrencesNotRelatedToQueue(IN _eventName VARCHAR(64), IN _eventOccurrences INT)
	BEGIN
		UPDATE report_data
		SET occurrences = occurrences + _eventOccurrences
		WHERE event_name = _eventName AND queue_name IS NULL;
	END //

-- Add to the value of an event's occurrences for a specific queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS AddToEventOccurrencesForQueue //
CREATE PROCEDURE AddToEventOccurrencesForQueue(IN _eventName VARCHAR(64), IN _eventOccurrences INT, IN _queueName VARCHAR(64))
	BEGIN

		INSERT IGNORE INTO report_data (event_name, queue_name, occurrences) VALUES (_eventName, _queueName, 0);

		UPDATE report_data
		SET occurrences = occurrences + _eventOccurrences
		WHERE event_name = _eventName AND queue_name = _queueName;
	END //

-- Add to the value of an event's occurrences for a specific queue related to a specific job pair.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS AddToEventOccurrencesForJobPairsQueue //
CREATE PROCEDURE AddToEventOccurrencesForJobPairsQueue(IN _eventName VARCHAR(64), IN _eventOccurrences INT, IN _pairId INT)
	BEGIN
		 SET @queueId := (SELECT queue_id
			 			  FROM job_pairs
			 			  INNER JOIN jobs
			 			  ON job_pairs.job_id=jobs.id
			 			  WHERE job_pairs.id=_pairId);

		IF @queueId IS NOT NULL THEN
			SET @queueName := (SELECT name FROM queues WHERE id=@queueId);

			INSERT IGNORE INTO report_data (event_name, occurrences, queue_name) VALUES (_eventName, 0, @queueName);


			CALL AddToEventOccurrencesForQueue(_eventName, _eventOccurrences, @queueName);
		END IF;
	END //

-- Gets all event names and occurrences for all events not related to a queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetAllEventsAndOccurrencesNotRelatedToQueues //
CREATE PROCEDURE GetAllEventsAndOccurrencesNotRelatedToQueues()
	BEGIN
		SELECT event_name, occurrences
		FROM report_data
		WHERE queue_name IS NULL;
	END //

-- Gets all event names and occurrences for every queue
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetAllEventsAndOccurrencesForAllQueues //
CREATE PROCEDURE GetAllEventsAndOccurrencesForAllQueues()
	BEGIN
		SELECT event_name, occurrences, queue_name
		FROM report_data
		WHERE queue_name IS NOT NULL
		ORDER BY queue_name, event_name;
	END //

-- Gets the number of occurrences for an event not related to a queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetEventOccurrencesNotRelatedToQueues //
CREATE PROCEDURE GetEventOccurrencesNotRelatedToQueues(IN _eventName VARCHAR(64))
	BEGIN
		SELECT occurrences
		FROM report_data
		WHERE event_name = _eventName AND queue_name IS NULL;
	END //


-- Gets the number of an event's occurrences for a specific queue.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetEventOccurrencesForQueue //
CREATE PROCEDURE GetEventOccurrencesForQueue(IN _eventName VARCHAR(64), _queueName VARCHAR(64))
	BEGIN
		SELECT occurrences
		FROM report_data
		WHERE event_name = _eventName AND queue_name=_queueName;
	END //


-- Resets all report data by setting all occurrences to 0 and deleting queue related rows
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS ResetReports //
CREATE PROCEDURE ResetReports()
	BEGIN
		UPDATE report_data
		SET occurrences = 0
		WHERE queue_name IS NULL;
		DELETE FROM report_data
		WHERE queue_name IS NOT NULL;
	END //


-- Gets the number of unique user logins in the logins table.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetNumberOfUniqueLogins //
CREATE PROCEDURE GetNumberOfUniqueLogins()
	BEGIN
		SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM logins) AS T;
	END //

-- Delete all information in the logins table.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS ResetLogins //
CREATE PROCEDURE ResetLogins()
	BEGIN
		DELETE FROM logins;
	END //


-- ================================================================================
-- Requests PROCEDURES
-- ================================================================================

-- Description: This file contains all stored procedures used for requesting membership in a community, registering, and the resetting of passwords
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds an activation code for a specific user
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS AddCode //
CREATE PROCEDURE AddCode(IN _id INT, IN _code VARCHAR(36))
	BEGIN
		INSERT INTO verify(user_id, code, created)
		VALUES (_id, _code, SYSDATE());
	END //

-- Adds a request to join a community, provided the user isn't already a part of that community
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS AddCommunityRequest //
CREATE PROCEDURE AddCommunityRequest(IN _id INT, IN _community INT, IN _code VARCHAR(36), IN _message VARCHAR(512))
	BEGIN
		IF NOT EXISTS(SELECT * FROM user_assoc WHERE user_id = _id AND space_id = _community) THEN
			INSERT INTO community_requests(user_id, community, code, message, created)
			VALUES (_id, _community, _code, _message, SYSDATE());
		END IF;
	END //

-- Adds a user to USER_ASSOC, deletes their entry in INVITES, and makes their
-- role 'user' if not so already
-- Author: Todd Elvers & Skylar Stark
DROP PROCEDURE IF EXISTS ApproveCommunityRequest //
CREATE PROCEDURE ApproveCommunityRequest(IN _id INT, IN _community INT)
	BEGIN
		DECLARE _newPermId INT;
		DECLARE _pid INT;

		IF EXISTS(SELECT * FROM community_requests WHERE user_id = _id AND community = _community) THEN
			DELETE FROM community_requests
			WHERE user_id = _id and community = _community;

			-- Copy the default permission for the community
			SELECT default_permission FROM spaces WHERE id=_community INTO _pid;
			CALL CopyPermissions(_pid, _newPermId);

			INSERT INTO user_assoc(user_id, space_id, permission)
			VALUES(_id, _community, _newPermId);

			-- make the user a 'user' if they are currently 'unauthorized'
			IF EXISTS(SELECT email FROM user_roles WHERE email = (SELECT email FROM users WHERE users.id = _id) AND role = 'unauthorized') THEN
				UPDATE user_roles
				JOIN users ON users.email=user_roles.email
				SET role = 'user'
				WHERE users.id = _id;
			END IF;
		END IF;
	END //

-- Adds a new entry to pass_reset_request for a given user (also deletes previous
-- entries for the same user)
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS AddPassResetRequest //
CREATE PROCEDURE AddPassResetRequest(IN _id INT, IN _code VARCHAR(36))
	BEGIN
		IF EXISTS(SELECT * FROM pass_reset_request WHERE user_id = _id) THEN
			DELETE FROM pass_reset_request
			WHERE user_id = _id;
		END IF;
		INSERT INTO pass_reset_request(user_id, code, created)
		VALUES(_id, _code, SYSDATE());
	END //

-- Deletes a user's entry in INVITES, and if the user is unregistered
-- (i.e. has a role of 'unauthorized') then they are completely
-- deleted from the system
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS DeclineCommunityRequest //
CREATE PROCEDURE DeclineCommunityRequest(IN _id INT, IN _community INT)
	BEGIN
		DELETE FROM community_requests
		WHERE user_id = _id and community = _community;

		DELETE users FROM users
		JOIN user_roles ON user_roles.email=users.email
		WHERE users.id = _id
		AND role = 'unauthorized';
	END //

-- Returns the community request associated with given user id
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetCommunityRequestById //
CREATE PROCEDURE GetCommunityRequestById(IN _id INT)
	BEGIN
		SELECT *
		FROM community_requests
		WHERE user_id = _id;
	END //

-- See if a request already exists from this user to this community
DROP PROCEDURE IF EXISTS GetCommunityRequestForUser //
CREATE PROCEDURE GetCommunityRequestForUser(IN _user INT, IN _community INT)
	BEGIN
		SELECT 1
		FROM community_requests
		WHERE community = _community
		  AND user_id = _user;
	END //

-- Returns the community request associated with the given activation code
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetCommunityRequestByCode //
CREATE PROCEDURE GetCommunityRequestByCode(IN _code VARCHAR(36))
	BEGIN
		SELECT *
		FROM community_requests
		WHERE code = _code;
	END //

-- Looks for an activation code, and if successful, removes it from VERIFY,
-- then adds an entry to USER_ROLES
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS RedeemActivationCode //
CREATE PROCEDURE RedeemActivationCode(IN _code VARCHAR(36), OUT _id INT)
	BEGIN
		IF EXISTS(SELECT _code FROM verify WHERE code = _code) THEN
			SELECT user_id INTO _id
			FROM verify
			WHERE code = _code;

			DELETE FROM verify
			WHERE code = _code;
		END IF;
	END //

-- Redeems a given password reset code by deleting the corresponding entry
-- in pass_reset_request and returning the user_id of that deleted entry
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS RedeemPassResetRequestByCode //
CREATE PROCEDURE RedeemPassResetRequestByCode(IN _code VARCHAR(36), OUT _id INT)
	BEGIN
		SELECT user_id INTO _id
		FROM pass_reset_request
		WHERE code = _code;
		DELETE FROM pass_reset_request
		WHERE code = _code;
	END //

-- Gets the number of community requests waiting approval
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetCommunityRequestCount //
CREATE PROCEDURE GetCommunityRequestCount()
	BEGIN
		SELECT count(*) AS requestCount
		FROM community_requests;
	END //

-- Gets the number of community requests waiting approval for the specified community.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetCommunityRequestCountForCommunity //
CREATE PROCEDURE GetCommunityRequestCountForCommunity(IN _communityId INT)
	BEGIN
		SELECT count(*) AS requestCount
		FROM community_requests
		WHERE community = _communityId;
	END //

-- Creates a change email request for user with _userId.
-- The email the the user is requesting to change to is _newEmail.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS AddChangeEmailRequest //
CREATE PROCEDURE AddChangeEmailRequest(IN _userId INT, IN _newEmail VARCHAR(64), IN _code VARCHAR(36))
	BEGIN
		INSERT INTO change_email_requests (user_id, new_email, code)
		VALUES (_userId, _newEmail, _code)
		ON DUPLICATE KEY UPDATE user_id=_userId, new_email=_newEmail, code=_code;
	END //


-- Gets a change email request for user with id _userId
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetChangeEmailRequest //
CREATE PROCEDURE GetChangeEmailRequest(IN _userId INT)
	BEGIN
		SELECT * FROM change_email_requests
		WHERE user_id=_userId;
	END //

-- Deletes the change email request associated with the user with id _userId.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS DeleteChangeEmailRequest //
CREATE PROCEDURE DeleteChangeEmailRequest(IN _userId INT)
	BEGIN
		DELETE FROM change_email_requests
		WHERE user_id=_userId;
	END //


-- ================================================================================
-- RunscriptErrors PROCEDURES
-- ================================================================================

-- Description: This file contains all Runscript Error procedures

DROP PROCEDURE IF EXISTS RunscriptError //
CREATE PROCEDURE RunscriptError(IN node VARCHAR(32), IN jobPairId INT, IN stage INT)
	BEGIN
		SET @node_id := (
			SELECT id
			FROM nodes
			WHERE name=node
		);

		INSERT INTO runscript_errors (node_id, job_pair_id)
		VALUES (@node_id, jobPairId);

		CALL UpdatePairStatus(jobPairId, 11);
		CALL UpdateLaterStageStatuses(jobPairId, stage, 11);
		CALL SetRunStatsForLaterStagesToZero(jobPairId, stage);
	END //

DROP PROCEDURE IF EXISTS GetRunscriptErrorsCount //
CREATE PROCEDURE GetRunscriptErrorsCount(IN _begin TIMESTAMP, IN _end TIMESTAMP)
	BEGIN
		SELECT COUNT(*) as 'count'
		FROM runscript_errors
		WHERE time >= _begin
		  AND time <= _end;
	END //

DROP PROCEDURE IF EXISTS GetRunscriptErrors //
CREATE PROCEDURE GetRunscriptErrors(IN _begin TIMESTAMP, IN _end TIMESTAMP)
	BEGIN
		SELECT name AS node, job_pair_id, time
		FROM runscript_errors
		JOIN nodes on nodes.id=node_id
		WHERE time >= _begin
		  AND time <= _end;
	END //


-- ================================================================================
-- Settings PROCEDURES
-- ================================================================================

-- This file contains procedures for DefaultSettings functionality

-- Gets a settings profile given its id
DROP PROCEDURE IF EXISTS getProfileById //
CREATE PROCEDURE getProfileById(IN _id INT)
	BEGIN
		SELECT * FROM default_settings WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS GetDefaultSettingsByIdAndType //
CREATE PROCEDURE GetDefaultSettingsByIdAndType(IN _prim_id INT, IN _type INT)
	BEGIN
		SELECT * FROM default_settings WHERE prim_id=_prim_id AND setting_type=_type;
	END //

-- Checks to see whether the given benchmark is a community default for any community
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsBenchACommunityDefault //
CREATE PROCEDURE IsBenchACommunityDefault(IN _benchId INT)
	BEGIN
		SELECT count(*) as benchDefault
		FROM default_settings
		WHERE default_benchmark = _benchId AND setting_type=1;
	END //

-- Checks to see whether the given solver is a community default for any community
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsSolverACommunityDefault //
CREATE PROCEDURE IsSolverACommunityDefault(IN _solverId INT)
	BEGIN
		SELECT count(*) as solverDefault
		FROM default_settings
		WHERE default_solver = _solverId AND setting_type=1;
	END //

-- Updates the maximum memory setting for a default_settings tuple
DROP PROCEDURE IF EXISTS SetMaximumMemorySetting //
CREATE PROCEDURE SetMaximumMemorySetting(IN _id INT, IN _bytes BIGINT)
	BEGIN
		UPDATE default_settings
		SET maximum_memory=_bytes
		WHERE id = _id;
	END //

-- Updates the default settings object with the given id
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS SetDefaultSettingsById //
CREATE PROCEDURE SetDefaultSettingsById(IN _id INT, IN _num INT, IN _setting INT)
	BEGIN
      CASE _num
		WHEN 1 THEN
		UPDATE default_settings
		SET post_processor = _setting
		WHERE id = _id;

		WHEN 2 THEN
		UPDATE default_settings
		SET cpu_timeout = _setting
		WHERE id = _id;

		WHEN 3 THEN
		UPDATE default_settings
		SET clock_timeout = _setting
		WHERE id = _id;

		WHEN 4 THEN
		UPDATE default_settings
		SET dependencies_enabled=_setting
		WHERE id=_id;

		WHEN 5 THEN
		UPDATE default_settings
		SET default_benchmark=_setting
		WHERE id=_id;

		WHEN 6 THEN
		UPDATE default_settings
		SET pre_processor=_setting
		WHERE id=_id;

		WHEN 7 THEN
		UPDATE default_settings
		SET default_solver=_setting
		WHERE id=_id;

		WHEN 8 THEN
		UPDATE default_settings
		SET bench_processor=_setting
		WHERE id=_id;

    END CASE;
	END //

-- Insert a default setting of a space given by id when it's initiated.
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS CreateDefaultSettings //
CREATE PROCEDURE CreateDefaultSettings(IN _prim_id INT, IN _pp INT, IN _cto INT, IN _clto INT, IN _dp BOOLEAN, IN _dm BIGINT, IN _defaultSolver INT, IN _benchProc INT, IN _preProc INT, IN _type INT, IN _name VARCHAR(32), IN _benchmarkingFramework ENUM("BENCHEXEC", "RUNSOLVER"), OUT _id INT)
	BEGIN
		INSERT INTO default_settings (prim_id, post_processor, cpu_timeout, clock_timeout, dependencies_enabled, maximum_memory, default_solver, bench_processor, pre_processor, setting_type,name, benchmarking_framework) VALUES (_prim_id, _pp, _cto, _clto, _dp,_dm,_defaultSolver,_benchProc, _preProc, _type,_name, _benchmarkingFramework);
		SELECT LAST_INSERT_ID() INTO _id;

	END //




-- Insert a default setting of a space given by id when it's initiated.
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS UpdateDefaultSettings //
CREATE PROCEDURE UpdateDefaultSettings(IN _pp INT, IN _cto INT, IN _clto INT, IN _dp BOOLEAN, IN _dm BIGINT, IN _defaultSolver INT, IN _benchProc INT, IN _preProc INT, IN _benchmarkingFramework ENUM("BENCHEXEC", "RUNSOLVER"), IN _id INT)
	BEGIN
		UPDATE default_settings SET
		post_processor = _pp,
		cpu_timeout=_cto,
		clock_timeout=_clto,
		dependencies_enabled=_dp,
		maximum_memory=_dm,
		default_solver=_defaultSolver,
		bench_processor=_benchProc,
		pre_processor=_preProc,
		benchmarking_framework=_benchmarkingFramework
		WHERE id=_id;

	END //


-- deletes a DefaultSettings profile
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS DeleteDefaultSettings //
CREATE PROCEDURE DeleteDefaultSettings(IN _id INT)
	BEGIN
		DELETE FROM default_settings WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS DeleteAllDefaultBenchmarks //
CREATE PROCEDURE DeleteAllDefaultBenchmarks(IN _settingId INT)
	BEGIN
		DELETE FROM default_bench_assoc WHERE setting_id=_settingId;
	END //

DROP PROCEDURE IF EXISTS SetDefaultProfileForUser //
CREATE PROCEDURE SetDefaultProfileForUser(IN _uid INT, IN _sid INT)
	BEGIN
		UPDATE users SET default_settings_profile=_sid WHERE id=_uid;
	END //


DROP PROCEDURE IF EXISTS GetDefaultProfileForUser //
CREATE PROCEDURE GetDefaultProfileForUser(IN _uid INT)
	BEGIN
		SELECT default_settings_profile FROM users WHERE id=_uid;
	END //

DROP PROCEDURE IF EXISTS AddDefaultBenchmark //
CREATE PROCEDURE AddDefaultBenchmark(IN _settingId INT, IN _benchId INT)
	BEGIN
		INSERT INTO default_bench_assoc (setting_id, bench_id)
		VALUES (_settingId, _benchId);
	END //

DROP PROCEDURE IF EXISTS GetDefaultBenchmarksForSetting //
CREATE PROCEDURE GetDefaultBenchmarksForSetting(IN _settingId INT)
	BEGIN
		SELECT b.*
		FROM benchmarks b JOIN default_bench_assoc dba ON b.id=dba.bench_id
				JOIN default_settings ds ON dba.setting_id=ds.id
		WHERE _settingId=ds.id;

	END //

DROP PROCEDURE IF EXISTS GetDefaultBenchmarkIdsForSetting //
CREATE PROCEDURE GetDefaultBenchmarkIdsForSetting(IN _settingId INT)
  BEGIN
    SELECT b.id as default_bench_id
    FROM benchmarks b JOIN default_bench_assoc dba ON b.id=dba.bench_id
      JOIN default_settings ds ON dba.setting_id=ds.id
    WHERE _settingId=ds.id;
  END //

DROP PROCEDURE IF EXISTS DeleteDefaultBenchmark //
CREATE PROCEDURE DeleteDefaultBenchmark(IN _settingId INT, _benchId INT)
  BEGIN
    DELETE FROM default_bench_assoc
    WHERE setting_id=_settingID AND bench_id=_benchid;
  END //


-- ================================================================================
-- Solvers PROCEDURES
-- ================================================================================

-- Description: This file contains all solver-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a solver and returns the solver ID
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS AddSolver //
CREATE PROCEDURE AddSolver(IN _userId INT, IN _name VARCHAR(128), IN _downloadable BOOLEAN, IN _path TEXT, IN _description TEXT, OUT _id INT, IN _diskSize BIGINT, IN _type INT, IN _build_status INT)
	BEGIN
		UPDATE users SET disk_size=disk_size+_diskSize WHERE id = _userId;
		INSERT INTO solvers (user_id, name, uploaded, path, description, downloadable, disk_size, executable_type, build_status)
		VALUES (_userId, _name, SYSDATE(), _path, _description, _downloadable, _diskSize, _type, _build_status);

		SELECT LAST_INSERT_ID() INTO _id;
	END //

-- Gets all solvers that reside in public spaces
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetPublicSolvers //
CREATE PROCEDURE GetPublicSolvers()
	BEGIN
		SELECT DISTINCT solvers.*
		FROM solvers
		JOIN solver_assoc ON solver_assoc.solver_id=solvers.id
		JOIN spaces ON spaces.id=solver_assoc.space_id
		WHERE public_access=1 AND deleted=false AND recycled=false;
	END //

-- Gets the number of conflicting benchmarks a given config was run against for a stage.
-- A conflicting benchmark is a benchmark for which two solvers gave different results.
DROP PROCEDURE IF EXISTS GetConflictsForConfigInJob //
CREATE PROCEDURE GetConflictsForConfigInJob(IN _jobId INT, IN _configId INT, IN _stageNumber INT)
  BEGIN
	SELECT COUNT(DISTINCT jp_o.bench_id) AS conflicting_benchmarks
	FROM jobs j_o JOIN job_pairs jp_o ON j_o.id=jp_o.job_id
		JOIN jobpair_stage_data jpsd_o ON jpsd_o.jobpair_id=jp_o.id
		JOIN job_attributes ja_o ON ja_o.pair_id=jp_o.id
		JOIN
			(SELECT jp.bench_id
			FROM jobs j join job_pairs jp ON j.id=jp.job_id
				JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id=jp.id
				JOIN job_attributes ja ON ja.pair_id=jp.id
			WHERE j.id=_jobId
				AND ja.stage_number=_stageNumber
				AND ja.attr_key='starexec-result'
				AND ja.attr_value!='starexec-unknown'
			GROUP BY jp.bench_id
			HAVING COUNT(DISTINCT ja.attr_value) > 1) AS conflicting
		ON jp_o.bench_id=conflicting.bench_id
	WHERE jpsd_o.config_id=_configId
		AND ja_o.attr_key='starexec-result'
		AND ja_o.attr_value!='starexec-unknown'
	;
  END //

-- Gets the data for conflicting benchmarks in the job.
-- A conflicting benchmark is a benchmark for which two solvers gave different results.
DROP PROCEDURE IF EXISTS GetConflictingBenchmarksForConfigInJob //
CREATE PROCEDURE GetConflictingBenchmarksForConfigInJob(IN _jobId INT, IN _configId INT, IN _stageNumber INT)
	BEGIN
		SELECT b_o.*
		FROM jobs j_o JOIN job_pairs jp_o ON j_o.id=jp_o.job_id
			JOIN jobpair_stage_data jpsd_o ON jpsd_o.jobpair_id=jp_o.id
			JOIN job_attributes ja_o ON ja_o.pair_id=jp_o.id
			JOIN benchmarks b_o ON b_o.id=jp_o.bench_id
			JOIN
			(SELECT jp.bench_id
			 FROM jobs j join job_pairs jp ON j.id=jp.job_id
				 JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id=jp.id
				 JOIN job_attributes ja ON ja.pair_id=jp.id
			 WHERE j.id=_jobId
						 AND ja.stage_number=_stageNumber
						 AND ja.attr_key='starexec-result'
						 AND ja.attr_value!='starexec-unknown'
			 GROUP BY jp.bench_id
			 HAVING COUNT(DISTINCT ja.attr_value) > 1) AS conflicting
				ON jp_o.bench_id=conflicting.bench_id
		WHERE jpsd_o.config_id=_configId
					AND ja_o.attr_key='starexec-result'
					AND ja_o.attr_value!='starexec-unknown'
		GROUP BY b_o.id
		;
	END //

-- Gets the all of the solvers, configs, and results run on a benchmark in a job.
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetSolverConfigResultsForBenchmarkInJob //
CREATE PROCEDURE GetSolverConfigResultsForBenchmarkInJob(IN _jobId INT, IN _benchId INT, IN _stageNum INT)
	BEGIN
		SELECT
				s.*, c.*,
				-- solver fields
				/*
				s.id AS s_id, s.user_id AS s_user_id, s.name AS s_name, s.uploaded AS s_uploaded, s.path AS s_path, s.description AS s_description,
				s.downloadable AS s_downloadable, s.disk_size AS s_disk_size, s.deleted AS s_deleted, s.recycled AS s_recycled,
				s.executable_type AS s_exectuable_type, s.build_status AS s_build_status,
				-- configuration fields
				c.id AS c_id, c.solver_id AS c_solver_id, c.name AS c_name, c.description AS c_description, c.updated AS c_updated,
				-- Value of starexec-result attribute
				*/
			 	ja.attr_value
		FROM jobs j JOIN job_pairs jp ON j.id=jp.job_id
				JOIN jobpair_stage_data jpsd ON jpsd.jobpair_id=jp.id
				JOIN solvers s ON jpsd.solver_id=s.id
				JOIN configurations c ON jpsd.config_id=c.id
				JOIN job_attributes ja ON ja.pair_id=jp.id
		WHERE
				j.id = _jobId
				AND jp.bench_id=_benchid
				AND ja.attr_key='starexec-result'
				AND ja.attr_value!='starexec-unknown'
				AND jpsd.stage_number=_stageNum
				AND c.deleted = 0
				-- configs are no longer removed from the table, so only non-deleted ones should be selected
				-- actually specifing c.deleted = 0 here prevents the row from showing the the solver summary table
				-- unless other problems arise, I will leave this statement able to from select configs flagged as deleted
				-- Alexander Brown, 9/2/20
		;
	END //


-- Adds a Space/Solver association
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS AddSolverAssociation //
CREATE PROCEDURE AddSolverAssociation(IN _spaceId INT, IN _solverId INT)
	BEGIN
		INSERT IGNORE INTO solver_assoc VALUES (_spaceId, _solverId);
	END //

-- Adds a run configuration to the specified solver
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS AddConfiguration //
CREATE PROCEDURE AddConfiguration(IN _solverId INT, IN _name VARCHAR(128), IN _description TEXT, IN _time TIMESTAMP, OUT configId INT)
	BEGIN
		INSERT INTO configurations (solver_id, name, description, updated)
		VALUES (_solverId, _name, _description, _time);

		SELECT LAST_INSERT_ID() INTO configId;
	END //


-- Deletes a configuration given that configuration's id
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS DeleteConfigurationById //
CREATE PROCEDURE DeleteConfigurationById(IN _configId INT)
	BEGIN
--        DELETE FROM configurations -- no longer deleting rows from configurations due to GHI#269; Alexander Brown 2020
--				now marking them as "deleted" instead
        UPDATE configurations SET deleted = 1
		WHERE id = _configId;
		-- we are now marking the config as deleted and updating the owning solver
        CALL UpdateConfigDeletedInSolvers( _configId, 1 );
	END //


-- Updates the solvers table to properly reflect that the corresponding configuration has been deleted
-- Author: Alexander Brown
DROP PROCEDURE IF EXISTS UpdateConfigDeletedInSolvers //
CREATE PROCEDURE UpdateConfigDeletedInSolvers( IN _configId INT, IN _configDeleted INT )
    BEGIN
        UPDATE solvers SET config_deleted = _configDeleted
        WHERE id = _configId;
    END //


-- Deletes a solver given that solver's id
-- Author: Todd Elvers + Eric Burns
DROP PROCEDURE IF EXISTS SetSolverToDeletedById //
CREATE PROCEDURE SetSolverToDeletedById(IN _solverId INT, OUT _path TEXT)
	BEGIN
		UPDATE users JOIN solvers ON solvers.user_id=users.id
		SET users.disk_size=users.disk_size-solvers.disk_size
		WHERE solvers.id = _solverId;

		SELECT path INTO _path FROM solvers WHERE id = _solverId;
		UPDATE solvers
		SET deleted=true, disk_size=0
		WHERE id = _solverId;
	END //

-- Gets the IDs of all the spaces associated with the given solver
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetAssociatedSpaceIdsBySolver //
CREATE PROCEDURE GetAssociatedSpaceIdsBySolver(IN _solverId INT)
	BEGIN
		SELECT space_id
		FROM solver_assoc
		WHERE solver_id=_solverId;
	END //

-- Retrieves the configurations with the given id
-- Author: Tyler Jensen
-- NOTE: only retrieves configurations that have _not_ been marked as deleted (Alexander Brown)
DROP PROCEDURE IF EXISTS GetConfiguration //
CREATE PROCEDURE GetConfiguration(IN _id INT)
	BEGIN
		SELECT *
		FROM configurations
    -- need to ensure config has not been deleted
--		WHERE id = _id;
    WHERE id = _id AND deleted = 0;
	END //

-- Retrieves the configurations with the given id, including deleted configs
-- Author: Alexander Brown
DROP PROCEDURE IF EXISTS GetConfigurationIncludeDeleted //
CREATE PROCEDURE GetConfigurationIncludeDeleted( IN _id INT )
	BEGIN
		SELECT *
		FROM configurations
    WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS GetAllSolversInJob //
CREATE PROCEDURE GetAllSolversInJob(IN _jobId INT)
	BEGIN
		SELECT DISTINCT solver_id, solver_name
		FROM jobpair_stage_data
		INNER JOIN job_pairs ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.job_id=_jobId;
	END //

DROP PROCEDURE IF EXISTS GetAllConfigsInJob //
CREATE PROCEDURE GetAllConfigsInJob(IN _jobId INT)
	BEGIN
		SELECT DISTINCT config_id, config_name
		FROM jobpair_stage_data
		INNER JOIN job_pairs ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.job_id=_jobId;
	END //

DROP PROCEDURE IF EXISTS GetAllConfigIdsInJob //
CREATE PROCEDURE GetAllConfigIdsInJob( IN _jobId INT)
	BEGIN
		SELECT DISTINCT config_id
		FROM jobpair_stage_data
		INNER JOIN job_pairs ON jobpair_stage_data.jobpair_id=job_pairs.id
		WHERE job_pairs.job_id=_jobId;
	END //


-- Retrieves the configurations that belong to a solver with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetConfigsForSolver //
CREATE PROCEDURE GetConfigsForSolver(IN _id INT)
	BEGIN
		SELECT *
		FROM configurations
--		WHERE solver_id = _id; -- need to ensure config has not been deleted
        WHERE solver_id = _id AND deleted = 0;
	END //


-- Retrieves all solvers belonging to a space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSpaceSolversById //
CREATE PROCEDURE GetSpaceSolversById(IN _id INT)
	BEGIN
		SELECT *
		FROM solvers
		JOIN solver_assoc ON solver_assoc.solver_id=solvers.id
		WHERE deleted=false AND recycled=false AND solver_assoc.space_id=_id;
	END //

-- Retrieves the solver associated with the configuration with the given id
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS GetSolverIdByConfigId //
CREATE PROCEDURE GetSolverIdByConfigId(IN _id INT)
	BEGIN
		SELECT solver_id AS id
		FROM configurations
--		WHERE id=_id; -- need to ensure config has not been deleted; Alexander Brown
        WHERE id=_id AND deleted = 0;
	END //

-- Retrieves the solver with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSolverById //
CREATE PROCEDURE GetSolverById(IN _id INT)
	BEGIN
		SELECT *
		FROM solvers
		WHERE id = _id and deleted=false AND recycled=false;
	END //

-- Retrieves the solver with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSolverByIdIncludeDeleted //
CREATE PROCEDURE GetSolverByIdIncludeDeleted(IN _id INT)
	BEGIN
		SELECT *
		FROM solvers
		WHERE id = _id;
	END //

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSolverCountInSpaceWithQuery //
CREATE PROCEDURE GetSolverCountInSpaceWithQuery(IN _spaceId INT, IN _query TEXT)
	BEGIN
		SELECT COUNT(*) AS solverCount
		FROM solver_assoc
			JOIN solvers AS solvers ON solvers.id=solver_assoc.solver_id
		WHERE _spaceId=solver_assoc.space_id AND
				(solvers.name 	LIKE	CONCAT('%', _query, '%')
				OR		solvers.description	LIKE 	CONCAT('%', _query, '%'));
	END //


-- Retrieves the solvers owned by a given user id
-- Todd Elvers
DROP PROCEDURE IF EXISTS GetSolversByOwner //
CREATE PROCEDURE GetSolversByOwner(IN _userId INT)
	BEGIN
		SELECT *
		FROM solvers
		WHERE user_id = _userId and deleted=false AND recycled=false;
	END //

-- Returns the number of public spaces a solver is in
-- Benton McCune
DROP PROCEDURE IF EXISTS IsSolverPublic //
CREATE PROCEDURE IsSolverPublic(IN _solverId INT)
	BEGIN
		SELECT count(*) as solverPublic
		FROM solver_assoc
		WHERE solver_id = _solverId
		AND IsPublic(space_id);
	END //

DROP PROCEDURE IF EXISTS IsSolverDeleted //
CREATE PROCEDURE IsSolverDeleted(IN _solverId INT)
	BEGIN
		SELECT count(*) AS solverDeleted
		FROM solvers
		WHERE deleted=true AND id=_solverId;
	END //

-- Removes the association between a solver and a given space;
-- Author: Todd Elvers + Eric Burns
DROP PROCEDURE IF EXISTS RemoveSolverFromSpace //
CREATE PROCEDURE RemoveSolverFromSpace(IN _solverId INT, IN _spaceId INT)
	BEGIN
		IF _spaceId >= 0 THEN
			DELETE FROM solver_assoc
			WHERE solver_id = _solverId
			AND space_id = _spaceId;
		END IF;
	END //

-- Updates the disk_size attribute of a given solver
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS UpdateSolverDiskSize //
CREATE PROCEDURE UpdateSolverDiskSize(IN _solverId INT, IN _newDiskSize BIGINT)
	BEGIN
		UPDATE users JOIN solvers ON solvers.user_id=users.id
		SET users.disk_size=(users.disk_size-solvers.disk_size)+_newDiskSize
		WHERE solvers.id = _solverId;
		UPDATE solvers
		SET disk_size = _newDiskSize
		WHERE id = _solverId;
	END //

-- Updates the details associated with a given configuration
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS UpdateConfigurationDetails //
CREATE PROCEDURE UpdateConfigurationDetails(IN _configId INT, IN _name VARCHAR(128), IN _description TEXT, IN _time TIMESTAMP)
	BEGIN
		UPDATE configurations
		SET name = _name,
		description = _description,
		updated = _time
		WHERE id = _configId;
	END //


-- Updates the details associated with a given solver
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS UpdateSolverDetails //
CREATE PROCEDURE UpdateSolverDetails(IN _solverId INT, IN _name VARCHAR(128), IN _description TEXT, IN _downloadable BOOLEAN)
	BEGIN
		UPDATE solvers
		SET name = _name,
		description = _description,
		downloadable = _downloadable
		WHERE id = _solverId;
	END //

-- Get the total count of the solvers belong to a specific user
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetSolverCountByUser //
CREATE PROCEDURE GetSolverCountByUser(IN _userId INT)
	BEGIN
		SELECT COUNT(*) AS solverCount
		FROM solvers
		WHERE user_id = _userId AND deleted=false AND recycled=false;
	END //

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSolverCountByUserWithQuery //
CREATE PROCEDURE GetSolverCountByUserWithQuery(IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT COUNT(*) AS solverCount
		FROM solvers
		WHERE user_id=_userId AND deleted=false AND recycled=false AND
				(name 		LIKE	CONCAT('%', _query, '%')
				OR		description	LIKE 	CONCAT('%', _query, '%'));
	END //

-- Sets the recycled attribute to the given value for the given solver
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetSolverRecycledValue //
CREATE PROCEDURE SetSolverRecycledValue(IN _solverId INT, IN _recycled BOOLEAN)
	BEGIN
		UPDATE solvers
		SET recycled=_recycled
		WHERE id=_solverId;
	END //

-- Checks to see whether the "recycled" flag is set for the given solver
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsSolverRecycled //
CREATE PROCEDURE IsSolverRecycled(IN _solverId INT)
	BEGIN
		SELECT recycled FROM solvers
		WHERE id=_solverId;
	END //

-- Returns the number of solvers in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledSolverCountByUser //
CREATE PROCEDURE GetRecycledSolverCountByUser(IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT COUNT(*) AS solverCount
		FROM solvers
		WHERE solvers.user_id=_userId AND recycled=true AND deleted=false AND
				(solvers.name 	LIKE	CONCAT('%', _query, '%')
				OR		solvers.description	LIKE 	CONCAT('%', _query, '%'));
	END //

-- Gets the path to every recycled solver a user has
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledSolverPaths //
CREATE PROCEDURE GetRecycledSolverPaths(IN _userId INT)
	BEGIN
		SELECT path,id FROM solvers
		WHERE recycled=true AND user_id=_userId AND deleted=false;
	END //

-- Sets all the solvers the user has in the database to "deleted"
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetRecycledSolversToDeleted //
CREATE PROCEDURE SetRecycledSolversToDeleted(IN _userId INT)
	BEGIN
		UPDATE users
		SET users.disk_size=users.disk_size-(SELECT COALESCE(SUM(disk_size),0) FROM solvers WHERE user_id=_userId AND recycled=true AND deleted=false)
		WHERE users.id=_userId;
		UPDATE solvers
		SET deleted=true, disk_size=0
		WHERE user_id = _userId AND recycled=true AND deleted=false;
	END //

-- Gets all recycled solver ids a user has in the database
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetRecycledSolverIds //
CREATE PROCEDURE GetRecycledSolverIds(IN _userId INT)
	BEGIN
		SELECT id FROM solvers
		WHERE user_id=_userId AND recycled=true;
	END //

-- Permanently removes a solver from the database
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RemoveSolverFromDatabase //
CREATE PROCEDURE RemoveSolverFromDatabase(IN _id INT)
	BEGIN
		DELETE FROM solvers
		WHERE id=_id;
	END //

-- Gets all the solver ids of solvers that are in at least one space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSolversAssociatedWithSpaces //
CREATE PROCEDURE GetSolversAssociatedWithSpaces()
	BEGIN
		SELECT DISTINCT solver_id AS id FROM solver_assoc;
	END //

-- Gets the solver ids of all solvers associated with at least one pair
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSolversAssociatedWithPairs //
CREATE PROCEDURE GetSolversAssociatedWithPairs()
	BEGIN
		SELECT DISTINCT solver_id AS id from jobpair_stage_data;
	END //

-- Gets the solver ids of all deleted solvers
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetDeletedSolvers //
CREATE PROCEDURE GetDeletedSolvers()
	BEGIN
		SELECT * FROM solvers WHERE deleted=true;
	END //

-- Sets the recycled flag for a single solver back to false
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RestoreSolver //
CREATE PROCEDURE RestoreSolver(IN _solverId INT)
	BEGIN
		UPDATE solvers
		SET recycled=false
		WHERE _solverId=id;
	END //

-- Gets the timestamp of the configuration that was most recently added or updated
-- on this solver
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetMaxConfigTimestamp //
CREATE PROCEDURE GetMaxConfigTimestamp(IN _solverId INT)
	BEGIN
		SELECT MAX(updated) AS recent
		FROM configurations
--		WHERE configurations.solver_id=_solverId; -- need to ensure config has not been deleted; Alexander Brown
        WHERE configurations.solver_id=_solverId AND configurations.deleted = 0;
	END //

-- Gets the ids of every orphaned solver a user owns (orphaned meaning the solver is in no spaces
DROP PROCEDURE IF EXISTS GetOrphanedSolverIds //
CREATE PROCEDURE GetOrphanedSolverIds(IN _userId INT)
	BEGIN
		SELECT solvers.id FROM solvers
		LEFT JOIN solver_assoc ON solver_assoc.solver_id=solvers.id
		WHERE solvers.user_id=_userId AND solver_assoc.space_id IS NULL;
	END //

-- returns every solver that shares a space with the given user
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSolversInSharedSpaces //
CREATE PROCEDURE GetSolversInSharedSpaces(IN _userId INT)
	BEGIN
		SELECT DISTINCT solvers.*
		FROM solvers
		JOIN solver_assoc ON solver_assoc.solver_id = solvers.id
		JOIN user_assoc ON user_assoc.space_id = solver_assoc.space_id
		WHERE user_assoc.user_id=_userId;
	END //

-- Sets the build_status status code of the solver
-- Author: Andrew Lubinus
DROP PROCEDURE IF EXISTS SetSolverBuildStatus //
CREATE PROCEDURE SetSolverBuildStatus(IN _solverId INT, IN _build_status INT)
    BEGIN
        UPDATE solvers
        SET build_status = _build_status
        WHERE id = _solverId;
    END //

-- Updates path to solver
-- Author: Andrew Lubinus
DROP PROCEDURE IF EXISTS SetSolverPath //
CREATE PROCEDURE SetSolverPath(IN _solverId INT, IN _path TEXT)
    BEGIN
        UPDATE solvers
        SET path = _path
        WHERE id = _solverId;
    END //

-- This deletes the dummy config from a solver built on Starexec
DROP PROCEDURE IF EXISTS DeleteBuildConfig //
CREATE PROCEDURE DeleteBuildConfig(IN _solverId INT)
    BEGIN
        DELETE FROM configurations -- dummy configs are deleted but not other configs
        WHERE solver_id=_solverId AND name="starexec_build";
    END //

-- Pauses all JobPairs containing Solver and rebuilds Solver
DROP PROCEDURE IF EXISTS RebuildSolver //
CREATE PROCEDURE RebuildSolver(IN _solverId INT)
	BEGIN
		-- Pause all jobs containing solver
		UPDATE jobs
		SET paused = TRUE
		WHERE killed = FALSE
		AND deleted = FALSE
		AND buildJob = FALSE
		AND id IN (
			SELECT job_id FROM (
				SELECT job_id, id
				FROM job_pairs
				WHERE id IN (
					SELECT jobpair_id
					FROM jobpair_stage_data
					WHERE solver_id = _solverId
				)
			) AS jobPairsWithSolver
		)
		;
		-- Pause all jobpairs containing solver
		UPDATE job_pairs
		SET status_code = 20
		WHERE  status_code = 1
		AND id IN (
			SELECT jobpair_id
			FROM jobpair_stage_data
			WHERE solver_id = _solverId
		)
		;
		-- Set Solver status to Unbuilt
		UPDATE solvers
			SET build_status = 0, -- 0 = Unbuilt : SolverBuildStatus.java
			    path = CONCAT(path, "_src")
			WHERE id = _solverId
			AND build_status = 2  -- 2 = Built by StarExec
		;
	END //


-- ================================================================================
-- Spaces PROCEDURES
-- ================================================================================

-- Description: This file contains all space-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a new space with the given information
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddSpace //
CREATE PROCEDURE AddSpace(IN _name VARCHAR(255), IN _desc TEXT, IN _locked TINYINT(1), IN _permission INT, IN _parent INT, IN _sticky BOOLEAN, OUT id INT)
	BEGIN
		INSERT INTO spaces (name, created, description, locked, default_permission,sticky_leaders)
		VALUES (_name, SYSDATE(), _desc, _locked, _permission,_sticky);
		SELECT LAST_INSERT_ID() INTO id;
		INSERT INTO closure (ancestor, descendant)	-- Update closure table
			SELECT ancestor, id FROM closure
			WHERE descendant = _parent
			UNION ALL SELECT _parent, id UNION SELECT id, id;
	END //

-- Adds a new job space with the given information
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS AddJobSpace //
CREATE PROCEDURE AddJobSpace(IN _name VARCHAR(255), IN _job_id INT, OUT id INT)
	BEGIN
		INSERT INTO job_spaces (name, job_id)
		VALUES (_name, _job_id);
		SELECT LAST_INSERT_ID() INTO id;
	END //

DROP PROCEDURE IF EXISTS SetJobSpaceMaxStages //
CREATE PROCEDURE SetJobSpaceMaxStages(IN _id INT, IN _max INT)
	BEGIN
		UPDATE job_spaces SET max_stages=_max WHERE id=_id;
	END //

-- Clears entries from the job_space_closure table that are older than the given time
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS ClearOldJobClosureEntries //
CREATE PROCEDURE ClearOldJobClosureEntries(IN _cutoff TIMESTAMP)
	BEGIN
		DELETE FROM job_space_closure WHERE last_used<_cutoff;
	END //

-- Insets a new ancestor/descendant pair into the job space closure table
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS InsertIntoJobSpaceClosure //
CREATE PROCEDURE InsertIntoJobSpaceClosure(IN _ancestor INT, IN _descendant INT, IN _time TIMESTAMP)
	BEGIN
		INSERT IGNORE INTO job_space_closure (ancestor, descendant, last_used) VALUES (_ancestor,_descendant, _time);
	END //

-- Adds an association between two spaces
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AssociateSpaces //
CREATE PROCEDURE AssociateSpaces(IN _parentId INT, IN _childId INT)
	BEGIN
		INSERT IGNORE INTO set_assoc
		VALUES (_parentId, _childId);
	END //

-- Moves an existing space to the new parent
-- Note: The order of arguments is (Destination, Source) to match
--       AssociateSpaces, which was apparently written by Intel engineers
DROP PROCEDURE IF EXISTS MoveSpace //
CREATE PROCEDURE MoveSpace(IN _parentId INT, IN _childId INT)
	BEGIN
		DELETE                                      -- remove
			FROM closure                            --   all existing closures
			WHERE descendant=_childId;              --   for this child space
		UPDATE set_assoc
			SET space_id=_parentId
			WHERE child_id=_childId;
		INSERT INTO closure (ancestor, descendant)
			SELECT ancestor, _childId AS descendant -- all ancestors of parent space
			FROM closure
			WHERE descendant=_parentId
			UNION ALL SELECT _parentId, _childId    -- parent space
			UNION SELECT _childId, _childId;        -- child space
	END //

-- Rebuild closure entries for a space, assuming its parent has fully correct
-- closure entries. When moving a space, we must call this for each child space,
-- using a pre-order traversal of the tree.
DROP PROCEDURE IF EXISTS RebuildSpaceClosures //
CREATE PROCEDURE RebuildSpaceClosures(IN _childId INT)
	BEGIN
		DECLARE _parentId INT;
		SELECT space_id INTO _parentId FROM set_assoc WHERE child_id=_childId;
		DELETE                                      -- remove
			FROM closure                            --   all existing closures
			WHERE descendant=_childId;              --   for this child space
		INSERT INTO closure (ancestor, descendant)  -- insert as ancestors
			SELECT ancestor, _childId AS descendant --   all ancestors of parent space
			FROM closure
			WHERE descendant=_parentId
			UNION ALL SELECT _parentId, _childId    --   parent space
			UNION SELECT _childId, _childId;        --   this space
	END //

-- Adds an association between two job spaces
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS AssociateJobSpaces //
CREATE PROCEDURE AssociateJobSpaces(IN _parentId INT, IN _childId INT)
	BEGIN
		INSERT IGNORE INTO job_space_assoc
		VALUES (_parentId, _childId);
	END //

-- Gets all the descendants of a space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetDescendantsOfSpace //
CREATE PROCEDURE GetDescendantsOfSpace(IN _spaceId INT)
	BEGIN
		SELECT descendant
		FROM closure
		WHERE ancestor = _spaceId AND NOT descendant=_spaceId;
	END //

-- Gets all the leaders of a space
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetLeadersBySpaceId //
CREATE PROCEDURE GetLeadersBySpaceId(IN _id INT)
	BEGIN
		SELECT *
		FROM users
		WHERE email IN
			(SELECT DISTINCT users.email
			FROM spaces
			JOIN user_assoc ON spaces.id=user_assoc.space_id
			JOIN users ON user_assoc.user_id=users.id
			JOIN permissions ON user_assoc.permission=permissions.id
			WHERE spaces.id=_id AND permissions.is_leader=1);
	END //

-- Returns basic space information for the space with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSpaceById //
CREATE PROCEDURE GetSpaceById(IN _id INT)
	BEGIN
		SELECT *
		FROM spaces
		WHERE id = _id;
	END //

-- Returns basic space information for the space with the given id
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobSpaceById //
CREATE PROCEDURE GetJobSpaceById(IN _id INT)
	BEGIN
		SELECT *
		FROM job_spaces
		WHERE id = _id;
	END //


-- Gets all the spaces that a user has access to
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetSpacesByUser //
CREATE PROCEDURE GetSpacesByUser(IN _userId INT)
	BEGIN
		SELECT space.name,space.id,space.locked,space.description,space.sticky_leaders
		FROM user_assoc
			JOIN spaces AS space ON space.id=space_id
		WHERE user_id=_userId;
	END //

-- Gets all the spaces
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetAllSpaces //
CREATE PROCEDURE GetAllSpaces()
	BEGIN
		SELECT name, id, locked, description
		FROM spaces;
	END //

-- Returns all spaces a user can see in the hierarchy rooted at the given space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubSpaceHierarchyById //
CREATE PROCEDURE GetSubSpaceHierarchyById(IN _spaceId INT, IN _userId INT)
	BEGIN
		IF _spaceId <= 0 THEN	-- If we get an invalid ID, return the root space (the space with the minimum ID)
			SELECT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM spaces
			WHERE id =
				(SELECT MIN(id)
				FROM spaces);
		ELSE					-- Else find all children spaces that are an ancestor of a space the user is apart of
			SELECT DISTINCT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM closure
				JOIN spaces ON spaces.id=closure.descendant
				JOIN user_assoc ON ( (user_assoc.user_id = _userId OR spaces.public_access) AND user_assoc.space_id=closure.descendant)
				WHERE closure.ancestor=_spaceId and closure.ancestor!=closure.descendant;
		END IF;
	END //

-- Returns all spaces belonging to the space with the given id.
-- Author: Tyler Jensen & Benton McCune & Eric Burns
DROP PROCEDURE IF EXISTS GetSubSpacesById //
CREATE PROCEDURE GetSubSpacesById(IN _spaceId INT, IN _userId INT)
	BEGIN
		IF _spaceId <= 0 THEN	-- If we get an invalid ID, return the root space (the space with the minimum ID)
			SELECT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM spaces
			WHERE id =
				(SELECT MIN(id)
				FROM spaces);
		ELSE					-- Else find all children spaces that are an ancestor of a space the user is apart of
			SELECT DISTINCT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM set_assoc
				JOIN closure ON set_assoc.child_id=closure.ancestor
				JOIN spaces ON spaces.id=set_assoc.child_id
				JOIN spaces AS s ON s.id=closure.descendant
				LEFT JOIN user_assoc ON user_assoc.space_id=closure.descendant
				WHERE set_assoc.space_id=_spaceId AND (s.public_access OR user_assoc.user_id=_userId)
				ORDER BY spaces.name;
		END IF;
	END //

-- Returns all the spaces belonging to the space (doesn't require user to be in user_assoc)
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetSubSpacesAdmin //
CREATE PROCEDURE GetSubSpacesAdmin(IN _spaceId INT)
	BEGIN
		IF _spaceId <= 0 THEN -- If we get an invalid ID, return the root space (the space with the minimum ID)
			SELECT spaces.name, spaces.description,spaces.locked,spaces.id
			FROM spaces
			WHERE id =
				(SELECT MIN(id)
				FROM spaces);
		ELSE
			SELECT DISTINCT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM set_assoc
				JOIN spaces ON spaces.id=set_assoc.child_id
				WHERE set_assoc.space_id=_spaceId
				ORDER BY name;
		END IF;
	END //

-- Returns all the spaces in the hierarchy rooted at the given space (doesn't require user to be in user_assoc)
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubSpaceHierarchyAdmin //
CREATE PROCEDURE GetSubSpaceHierarchyAdmin(IN _spaceId INT)
	BEGIN
		IF _spaceId <= 0 THEN -- If we get an invalid ID, return the root space (the space with the minimum ID)
			SELECT spaces.name, spaces.description,spaces.locked,spaces.id
			FROM spaces
			WHERE id =
				(SELECT MIN(id)
				FROM spaces);
		ELSE
			SELECT DISTINCT spaces.name,spaces.description,spaces.locked,spaces.id
			FROM closure
				JOIN spaces ON spaces.id=closure.descendant
				WHERE closure.ancestor=_spaceId and closure.ancestor!=closure.descendant;
		END IF;
	END //


-- Returns the parent space of a given space ID
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetParentSpaceById //
CREATE PROCEDURE GetParentSpaceById(IN _spaceId INT)
	BEGIN
		IF _spaceID <=0 THEN	-- Invalid ID => return root space
			SELECT *
			FROM spaces
			WHERE id =
				(SELECT MIN(id)
				FROM spaces);
		ELSE
			SELECT space_id AS id
			FROM set_assoc
			WHERE child_id = _spaceId;
		END IF;
	END //

-- Returns all subsspaces of a given name belonging to the space with the given id.
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetSubSpaceByName //
CREATE PROCEDURE GetSubSpaceByName(IN _spaceId INT, IN _userId INT, IN _name VARCHAR(255))
	BEGIN
		IF _spaceId <= 0 THEN	-- If we get an invalid ID, return the root space (the space with the minimum ID)
			SELECT id
			FROM spaces
			ORDER BY id limit 1;
		ELSE					-- Else find all children spaces that are an ancestor of a space the user is apart of
			IF _userId>0 THEN
				SELECT *
				FROM spaces
				WHERE id IN
					(SELECT child_id
					FROM set_assoc
						JOIN closure ON set_assoc.child_id=closure.ancestor
						JOIN user_assoc ON (user_assoc.user_id=_userId AND user_assoc.space_id=closure.descendant)
						WHERE set_assoc.space_id=_spaceId)
				AND name = _name;
			ELSE
				SELECT child_id AS id FROM spaces
				JOIN set_assoc ON set_assoc.child_id =spaces.id
				WHERE space_id=_spaceId AND spaces.name=_name;
			END IF;
		END IF;
	END //


-- Returns all spaces that are a subspace of the root
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetSubSpacesOfRoot //
CREATE PROCEDURE GetSubSpacesOfRoot()
	BEGIN
		SELECT *
		FROM spaces
		WHERE id IN
				(SELECT child_id
				 FROM set_assoc
				 WHERE space_id=1);
	END //

-- Gets all the subspaces of a given space needed for a given job (non-recursive)
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetJobSubSpaces //
CREATE PROCEDURE GetJobSubspaces(IN _spaceId INT)
	BEGIN
		SELECT *
		FROM job_spaces
		JOIN job_space_assoc ON job_space_assoc.child_id=job_spaces.id
		WHERE job_space_assoc.space_id = _spaceId
		ORDER BY name ASC;
	END //

-- Gets the ids of the first level of subspaces of a given space (not recursive)
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubSpaceIds //
CREATE PROCEDURE GetSubSpaceIds(IN _spaceId INT)
	BEGIN
		SELECT child_id AS id FROM set_assoc WHERE space_id=_spaceId;
	END //

-- Returns the recursive number of subspaces a user can see in a given space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubspaceCountBySpaceIdInHierarchy //
CREATE PROCEDURE GetSubspaceCountBySpaceIdInHierarchy(IN _spaceId INT, IN _userId INT)
	BEGIN
		SELECT COUNT(*) AS spaceCount
		FROM closure
				JOIN spaces ON spaces.id=closure.descendant
				JOIN user_assoc ON ( (user_assoc.user_id=_userId OR spaces.public_access) AND user_assoc.space_id=descendant)

		WHERE ancestor=_spaceId AND ancestor!=descendant;
	END //

DROP PROCEDURE IF EXISTS GetTotalSubspaceCountBySpaceIdInHierarchy //
CREATE PROCEDURE GetTotalSubspaceCountBySpaceIdInHierarchy(IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) AS spaceCount
		FROM closure
		WHERE ancestor=_spaceId AND ancestor!=descendant;
	END //

-- Returns the number of subspaces in a given space without checking membership
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubspaceCountBySpaceIdAdmin //
CREATE PROCEDURE GetSubspaceCountBySpaceIdAdmin(IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) AS spaceCount
		FROM set_assoc
		WHERE set_assoc.space_id=_spaceId;
	END //

-- Returns the number of subspaces in a given space
-- Author: Todd Elvers + Eric Burns
DROP PROCEDURE IF EXISTS GetSubspaceCountBySpaceId //
CREATE PROCEDURE GetSubspaceCountBySpaceId(IN _spaceId INT, IN _userId INT)
	BEGIN
		SELECT	COUNT(DISTINCT child_id) AS spaceCount
		FROM	set_assoc
		JOIN	user_assoc ON set_assoc.child_id = user_assoc.space_id
		JOIN	spaces ON spaces.id=set_assoc.child_id
		WHERE	set_assoc.space_id = _spaceId
		AND		(user_assoc.user_id = _userId OR spaces.public_access);
	END //

-- Returns the number of subspaces in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubspaceCountBySpaceIdWithQuery //
CREATE PROCEDURE GetSubspaceCountBySpaceIdWithQuery(IN _spaceId INT, IN _userId INT, IN _query TEXT)
	BEGIN
		SELECT	COUNT(DISTINCT child_id) AS spaceCount
		FROM	set_assoc
		JOIN	spaces ON spaces.id=set_assoc.child_id
		JOIN	user_assoc ON set_assoc.child_id = user_assoc.space_id
		JOIN	users ON users.id=_userId
		JOIN	user_roles ON user_roles.email=users.email
		WHERE	set_assoc.space_id = _spaceId AND (user_assoc.user_id = _userId OR spaces.public_access OR role="admin"
		OR role="developer")
				AND		(name			LIKE	CONCAT('%', _query, '%')
				OR		description		LIKE	CONCAT('%', _query, '%'));
	END //

-- Returns the number of subspaces in a given job space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetSubspaceCountByJobSpaceId //
CREATE PROCEDURE GetSubspaceCountByJobSpaceId(IN _spaceId INT)
	BEGIN
		SELECT	COUNT(*) AS spaceCount
		FROM	job_space_assoc
		WHERE space_id=_spaceId;
	END //


-- Removes the association between a space and a subspace and deletes the subspace
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS RemoveSubspace //
CREATE PROCEDURE RemoveSubspace(IN _subspaceId INT)
	BEGIN
		-- Remove that space's default permission
		DELETE FROM permissions
			WHERE id=(SELECT default_permission FROM spaces WHERE id=_subspaceId);

		-- Remove the space
		DELETE FROM spaces
		WHERE id = _subspaceId;
	END //

-- Updates the name of the space with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateSpaceName //
CREATE PROCEDURE UpdateSpaceName(IN _id INT, IN _name VARCHAR(255))
	BEGIN
		UPDATE spaces
		SET name = _name
		WHERE id = _id;
	END //

-- Updates the name of the space with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS UpdateSpaceDescription //
CREATE PROCEDURE UpdateSpaceDescription(IN _id INT, IN _desc TEXT)
	BEGIN
		UPDATE spaces
		SET description = _desc
		WHERE id = _id;
	END //

-- Updates all details of the space with the given id, and returns the permission id to
-- help update default permissions.
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdateSpaceDetails //
CREATE PROCEDURE UpdateSpaceDetails(IN _spaceId INT, IN _name VARCHAR(255), IN _desc TEXT, IN _locked BOOLEAN, IN _sticky BOOLEAN, OUT _perm INT)
	BEGIN
		UPDATE spaces
		SET name = _name,
		description = _desc,
		locked = _locked,
		sticky_leaders=_sticky
		WHERE id = _spaceId;

		SELECT default_permission INTO _perm
		FROM spaces
		WHERE id = _spaceId;
	END //


-- Get the id of the community where the space belongs to
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS GetCommunityOfSpace //
CREATE PROCEDURE GetCommunityOfSpace(IN _id INT)
	BEGIN
		SELECT min(ancestor) AS community FROM closure WHERE descendant=_id AND ancestor != 1;
	END //

-- Query if a space is a public space
-- Author: Ruoyu Zhang, edited by Benton McCune + Eric Burns
DROP PROCEDURE IF EXISTS IsPublicSpace //
CREATE PROCEDURE IsPublicSpace(IN _spaceId INT)
	BEGIN
		SELECT public_access
		FROM spaces
		WHERE id = _spaceId;
	END //

-- Determines whether a hierarchy is public, meaning every space rooted at the given one
-- (including the given one) is public
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsPublicHierarchy //
CREATE PROCEDURE IsPublicHierarchy(IN _spaceId INT)
	BEGIN
		SELECT IF((
		SELECT COUNT(*) FROM closure
			JOIN spaces AS space ON space.id=descendant
		WHERE ancestor=_spaceId AND space.public_access=FALSE) =0 ,1,0) AS public;
	END //


-- Change a space to a public space or a private one
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS setPublicSpace //
CREATE PROCEDURE setPublicSpace(IN _spaceId INT, IN _pbc BOOLEAN)
	BEGIN
		UPDATE spaces
		SET public_access = _pbc
		WHERE id = _spaceId;
	END //

-- Count the number of solvers in a specific space
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS countSpaceSolversByName //
CREATE PROCEDURE countSpaceSolversByName(IN _name VARCHAR(255), IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) FROM solvers JOIN solver_assoc ON id = solver_id WHERE name = _name AND space_id = _spaceId;
	END //

-- Count the number of benchmarks in a specific space
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS countSpaceBenchmarksByName //
CREATE PROCEDURE countSpaceBenchmarksByName(IN _name VARCHAR(256), IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) FROM benchmarks JOIN bench_assoc ON id = bench_id WHERE name = _name AND space_id = _spaceId;
	END //

-- Count the number of jobs in a specific space
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS countSpaceJobsByName //
CREATE PROCEDURE countSpaceJobsByName(IN _name VARCHAR(255), IN _spaceId INT)
	BEGIN
		SELECT COUNT(*) FROM jobs JOIN job_assoc ON id = job_id WHERE name = _name AND space_id = _spaceId;
	END //

-- Count the number of subspaces in a specific space
-- Author: Ruoyu Zhang
DROP PROCEDURE IF EXISTS countSubspacesByName //
CREATE PROCEDURE countSubspacesByName(IN _name VARCHAR(255), IN _spaceId INT)
	BEGIN
		SELECT COUNT(*)
		FROM spaces AS parent
			 JOIN set_assoc ON parent.id = set_assoc.space_id
			 JOIN spaces AS child ON set_assoc.child_id = child.id
		WHERE parent.id = _spaceId AND child.name = _name
		;
	END //

DROP PROCEDURE IF EXISTS GetSpacesByJob //
CREATE PROCEDURE GetSpacesByJob(IN _jobId INT)
  BEGIN
	SELECT DISTINCT space_id
	FROM job_assoc
	WHERE job_id=_jobId
	;
  END //

-- Retrieves all jobs belonging to a space (but not their job pairs)
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSpaceJobsById //
CREATE PROCEDURE GetSpaceJobsById(IN _spaceId INT)
	BEGIN
		SELECT *
		FROM jobs
		WHERE id IN
			(SELECT job_id
			 FROM job_assoc
			 WHERE space_id=_spaceId)
		ORDER BY created DESC;
	END //

-- Removes the association between a job and a given space
-- Author: Todd Elvers + Eric Burns
DROP PROCEDURE IF EXISTS RemoveJobFromSpace //
CREATE PROCEDURE RemoveJobFromSpace(IN _jobId INT, IN _spaceId INT)
BEGIN
	DELETE FROM job_assoc
	WHERE job_id = _jobId
	AND space_id = _spaceId;
END //


-- Sets the "sticky_leader" flag for a given space
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetStickyLeader //
CREATE PROCEDURE SetStickyLeader(IN _spaceID INT, IN _val BOOLEAN)
BEGIN
	UPDATE spaces SET sticky_leader =  _val WHERE id=_spaceID;
END //

-- Get all the communities that are not already attached to a queue
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNonAttachedCommunities //
CREATE PROCEDURE GetNonAttachedCommunities(IN _queueId INT)
	BEGIN
		SELECT DISTINCT spaces.id, spaces.name
		FROM spaces JOIN set_assoc ON spaces.id = set_assoc.child_id
		WHERE set_assoc.space_id = 1
		AND spaces.id NOT IN
			(SELECT space_id FROM comm_queue WHERE queue_id = _queueId );
	END //

-- Get the "Users" subspace for a given Community
DROP PROCEDURE IF EXISTS GetUsersSpace //
CREATE PROCEDURE GetUsersSpace(IN _id INT)
	BEGIN
		SELECT id
		FROM spaces
		WHERE name = "Users"
		AND id IN
			(SELECT child_id FROM set_assoc WHERE space_id = _id);
	END //

-- Create the "Users" subspace for a given Community
DROP PROCEDURE IF EXISTS CreateUsersSpace //
CREATE PROCEDURE CreateUsersSpace(IN _communityId INT)
	BEGIN
		DECLARE _name VARCHAR(255) DEFAULT "Users";
		DECLARE _permission  INT   DEFAULT 1; -- default for root space
		DECLARE _locked      INT   DEFAULT 0;
		DECLARE _sticky      INT   DEFAULT 0;
		DECLARE _description TEXT  DEFAULT "Holding personal spaces for users";
		DECLARE _newSpaceId  INT;

		CALL AddSpace(
			_name,
			_description,
			_locked,
			_permission,
			_communityId,
			_sticky,
			_newSpaceId
		);
		CALL AssociateSpaces(_communityId, _newSpaceId);
	END //


-- ================================================================================
-- UploadStatus PROCEDURES
-- ================================================================================

-- Creates a new UpdateStatus entry when user uploads a benchmark
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS CreateBenchmarkUploadStatus //
CREATE PROCEDURE CreateBenchmarkUploadStatus(IN _spaceId INT, IN _userId INT, OUT id INT)
	BEGIN
		INSERT INTO benchmark_uploads (space_id, user_id, upload_time,error_message) VALUES (_spaceId, _userId, NOW(),"no error");
		SELECT LAST_INSERT_ID() INTO id;
	END //

-- Creates a new upload status entry for a space XML upload
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS CreateSpaceXMLUploadStatus //
CREATE PROCEDURE CreateSpaceXMLUploadStatus(IN _userId INT, OUT id INT)
	BEGIN
		INSERT INTO space_xml_uploads (user_id, upload_time,error_message) VALUES (_userId, NOW(), "no error");
		SELECT LAST_INSERT_ID() INTO id;
	END //

-- Updates status when file upload is complete
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS XMLFileUploadComplete //
CREATE PROCEDURE XMLFileUploadComplete(IN _id INT)
	BEGIN
		UPDATE space_xml_uploads
		SET file_upload_complete = 1
		WHERE id = _id;
	END //

-- Updates status when file upload is complete
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS BenchmarkFileUploadComplete //
CREATE PROCEDURE BenchmarkFileUploadComplete(IN _id INT)
	BEGIN
		UPDATE benchmark_uploads
		SET file_upload_complete = 1
		WHERE id = _id;
	END //

-- Updates status when file extraction is complete
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS FileExtractComplete //
CREATE PROCEDURE FileExtractComplete(IN _id INT)
	BEGIN
		UPDATE benchmark_uploads
		SET file_extraction_complete = 1
		WHERE id = _id;
	END //

-- Updates status when java object is created and processing/entering of benchmarks in db has begun
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS ProcessingBegun //
CREATE PROCEDURE ProcessingBegun(IN _id INT)
	BEGIN
		UPDATE benchmark_uploads
		SET processing_begun = 1
		WHERE id = _id;
	END //

-- Updates status when the entire upload benchmark process has completed
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS XMLEverythingComplete //
CREATE PROCEDURE XMLEverythingComplete(IN _id INT)
	BEGIN
		UPDATE space_xml_uploads
		SET everything_complete = 1
		WHERE id = _id;
	END //


DROP PROCEDURE IF EXISTS BenchmarkEverythingComplete //
CREATE PROCEDURE BenchmarkEverythingComplete(IN _id INT)
	BEGIN
		UPDATE benchmark_uploads
		SET everything_complete = 1
		WHERE id = _id;
	END //
-- Updates status when a directory is encountered when traversing extracted file
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementTotalSpaces //
CREATE PROCEDURE IncrementTotalSpaces(IN _id INT, IN _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET total_spaces = total_spaces + _num
		WHERE id = _id;
	END //

-- Updates status when a file is encountered when traversing extracted file
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementTotalBenchmarks //
CREATE PROCEDURE IncrementTotalBenchmarks(IN _id INT, _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET total_benchmarks = total_benchmarks + _num
		WHERE id = _id;
	END //

-- Indicates a space is completely added to the db.
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementCompletedSpaces //
CREATE PROCEDURE IncrementCompletedSpaces(IN _id INT, IN _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET completed_spaces = completed_spaces + _num
		WHERE id = _id;
	END //

-- Updates status when a benchmark is completed and entered into the db
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementCompletedBenchmarks //
CREATE PROCEDURE IncrementCompletedBenchmarks(IN _id INT, IN _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET completed_benchmarks = completed_benchmarks + _num
		WHERE id = _id;
	END //

-- Updates status when a benchmark is validated
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementValidatedBenchmarks //
CREATE PROCEDURE IncrementValidatedBenchmarks(IN _id INT, IN _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET validated_benchmarks = validated_benchmarks + _num
		WHERE id = _id;
	END //

-- Updates status when a benchmark fails validation
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS IncrementFailedBenchmarks //
CREATE PROCEDURE IncrementFailedBenchmarks(IN _id INT,IN _num INT)
	BEGIN
		UPDATE benchmark_uploads
		SET failed_benchmarks = failed_benchmarks + _num
		WHERE id = _id;
	END //


DROP PROCEDURE IF EXISTS SetXMLErrorMessage //
CREATE PROCEDURE SetXMLErrorMessage(IN _id INT, IN _message TEXT)
	BEGIN
		UPDATE space_xml_uploads
		SET error_message = _message
		WHERE id = _id;
	END //

-- Updates status when an error occurs
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS SetBenchmarkErrorMessage //
CREATE PROCEDURE SetBenchmarkErrorMessage(IN _id INT, IN _message TEXT)
	BEGIN
		UPDATE benchmark_uploads
		SET error_message = _message
		WHERE id = _id;
	END //

-- Retrieves the upload status with the given id
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetBenchmarkUploadStatusById //
CREATE PROCEDURE GetBenchmarkUploadStatusById(IN _id INT)
	BEGIN
		SELECT *
		FROM benchmark_uploads
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS GetUploadStatusForInvalidBenchmarkId //
CREATE PROCEDURE GetUploadStatusForInvalidBenchmarkId(IN _id INT)
	BEGIN
		SELECT benchmark_uploads.*
		FROM benchmark_uploads JOIN unvalidated_benchmarks ON benchmark_uploads.id=status_id
		WHERE unvalidated_benchmarks.id = _id;
	END //

-- Updates status when  benchmark fails validation
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS AddUnvalidatedBenchmark //
CREATE PROCEDURE AddUnvalidatedBenchmark(IN _id INT, IN _name VARCHAR(256), IN _error TEXT)
	BEGIN
		INSERT INTO unvalidated_benchmarks (status_id, bench_name, error_message)
		VALUES (_id, _name, _error);
	END //

-- Gets direct count of unvalidated benchmarks if there are no more than maximum
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS UnvalidatedBenchmarkCount //
CREATE PROCEDURE UnvalidatedBenchmarkCount(IN _status_id INT)
	BEGIN
		select count(*) from unvalidated_benchmarks
		WHERE status_id = _status_id;
	END //

-- Gets unvalidated benchmark names
-- Author: Benton McCune
DROP PROCEDURE IF EXISTS GetUnvalidatedBenchmarks //
CREATE PROCEDURE GetUnvalidatedBenchmarks(IN _status_id INT)
	BEGIN
		select bench_name, id from unvalidated_benchmarks
		WHERE status_id = _status_id;
	END //

DROP PROCEDURE IF EXISTS SetXMLTotalSpaces //
CREATE PROCEDURE SetXMLTotalSpaces(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET total_spaces = _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS SetXMLTotalSolvers //
CREATE PROCEDURE SetXMLTotalSolvers(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET total_solvers = _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS SetXMLTotalBenchmarks //
CREATE PROCEDURE SetXMLTotalBenchmarks(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET total_benchmarks = _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS SetXMLTotalUpdates //
CREATE PROCEDURE SetXMLTotalUpdates(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET total_updates = _num
		WHERE id = _id;
	END //


DROP PROCEDURE IF EXISTS IncrementXMLCompletedUpdates //
CREATE PROCEDURE IncrementXMLCompletedUpdates(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET completed_updates = completed_updates +  _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS IncrementXMLCompletedSolvers //
CREATE PROCEDURE IncrementXMLCompletedSolvers(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET completed_solvers = completed_solvers +  _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS IncrementXMLCompletedBenchmarks //
CREATE PROCEDURE IncrementXMLCompletedBenchmarks(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET completed_benchmarks = completed_benchmarks +  _num
		WHERE id = _id;
	END //

DROP PROCEDURE IF EXISTS IncrementXMLCompletedSpaces //
CREATE PROCEDURE IncrementXMLCompletedSpaces(IN _id INT, IN _num INT)
	BEGIN
		UPDATE space_xml_uploads
		SET completed_spaces = completed_spaces +  _num
		WHERE id = _id;
	END //

-- Gets the error message for a particular row in the unvalidated benchmarks table
DROP PROCEDURE IF EXISTS GetInvalidBenchmarkMessage //
CREATE PROCEDURE GetInvalidBenchmarkMessage(IN _id INT)
	BEGIN
		SELECT error_message
		FROM unvalidated_benchmarks
		WHERE id = _id;
	END //
-- Gets the total count of the Uploads that belong to a specific user
DROP PROCEDURE IF EXISTS GetUploadCountByUser //
CREATE PROCEDURE GetUploadCountByUser(IN _userId INT)
        BEGIN
                SELECT COUNT(*) AS uploadCount
                FROM benchmark_uploads
                WHERE user_id = _userId;
        END //

DROP PROCEDURE IF EXISTS GetUploadCountByUserWithQuery //
CREATE PROCEDURE GetUploadCountByUserWithQuery(IN _userId INT, IN _query TEXT)
        BEGIN
                SELECT  COUNT(*) AS uploadCount
                FROM    benchmark_uploads
                WHERE   user_id=_userId AND
                                (upload_time  LIKE   CONCAT('%', _query, '%'));
        END //


-- ================================================================================
-- Users PROCEDURES
-- ================================================================================

-- Description: This file contains all user-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Begins the registration process by adding a user to the USERS table
-- Makes their role "unauthorized"
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS AddUser //
CREATE PROCEDURE AddUser(IN _firstName VARCHAR(32), IN _lastName VARCHAR(32), IN _email VARCHAR(64), IN _institute VARCHAR(64), IN _password VARCHAR(128),  IN _diskQuota BIGINT(20), OUT _id INT)
	BEGIN
		INSERT INTO users(email, first_name, last_name, institution, created, password, disk_quota)
		VALUES (_email, _firstName, _lastName, _institute, SYSDATE(), _password, _diskQuota);

		SELECT LAST_INSERT_ID() INTO _id;

		INSERT INTO user_roles(email, role)
		VALUES (_email, 'unauthorized');
	END //

DROP PROCEDURE IF EXISTS AddUserAuthorized //
CREATE PROCEDURE AddUserAuthorized(IN _firstName VARCHAR(32), IN _lastName VARCHAR(32), IN _email VARCHAR(64), IN _institute VARCHAR(64), IN _password VARCHAR(128), IN _diskQuota BIGINT(20),IN _role VARCHAR(24), IN _pairQuota INT, OUT _id INT)
	BEGIN
		INSERT INTO users(email, first_name, last_name, institution, created, password, disk_quota, job_pair_quota)
		VALUES (_email, _firstName, _lastName, _institute, SYSDATE(), _password, _diskQuota, _pairQuota);
		SELECT LAST_INSERT_ID() INTO _id;

		INSERT INTO user_roles(email, role)
		VALUES (_email, _role);
	END //



-- Removes the user given by _userId from every space in the hierarchy rooted at _spaceId that _requestUserId can see
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS RemoveUserFromSpaceHierarchy //
CREATE PROCEDURE RemoveUserFromSpaceHierarchy(IN _userId INT, IN _spaceId INT, IN _requestUserId INT)
	BEGIN
		-- Remove the permission associated with this user/community
		DELETE FROM permissions
			WHERE id IN (SELECT permission FROM user_assoc
						JOIN closure ON descendant=_spaceId
						JOIN user_assoc AS ua ON ( (ua.user_id = _requestUserId OR spaces.public_access) AND ua.space_id=descendant)
						WHERE user_id = _userId);

		DELETE FROM user_assoc
		WHERE user_id=_userId AND space_id IN (SELECT descendant
		FROM closure JOIN spaces ON descendant=spaces.id
		JOIN user_assoc ON ( (user_assoc.user_id = _requestUserId OR spaces.public_access) AND user_assoc.space_id=descendant)
		WHERE ancestor=_spaceId);


	END //

-- Adds the user given by _userId to every space in the hierarchy rooted at _spaceId that _requestUserId can see
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS AddUserToSpaceHierarchy //
CREATE PROCEDURE AddUserToSpaceHierarchy(IN _userId INT, IN _spaceId INT, IN _requestUserId INT)
	BEGIN
		DECLARE _newPermId INT;
		DECLARE _pid INT;

		-- Copy the default permission for the community
		SELECT default_permission FROM spaces WHERE id=_spaceId INTO _pid;
		CALL CopyPermissions(_pid, _newPermId);

		INSERT IGNORE INTO user_assoc (user_id, space_id, permission)
		SELECT _userId, descendant, _newPermId
		FROM closure JOIN spaces ON descendant=spaces.id
		JOIN user_assoc ON ( (user_assoc.user_id = _requestUserId OR spaces.public_access) AND user_assoc.space_id=descendant)
		WHERE ancestor=_spaceId;

	END //

-- Adds an association between a user and a space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddUserToSpace //
CREATE PROCEDURE AddUserToSpace(IN _userId INT, IN _spaceId INT)
	BEGIN
		DECLARE _newPermId INT;
		DECLARE _pid INT;
		IF NOT EXISTS(SELECT * FROM user_assoc WHERE user_id = _userId AND space_id = _spaceId) THEN
			-- Copy the default permission for the community
			SELECT default_permission FROM spaces WHERE id=_spaceId INTO _pid;
			CALL CopyPermissions(_pid, _newPermId);

			INSERT INTO user_assoc (user_id, space_id, permission)
			VALUES (_userId, _spaceId, _newPermId);
		END IF;
	END //



-- Returns the (hashed) password of the user with the given user id
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS GetPasswordById //
CREATE PROCEDURE GetPasswordById(IN _id INT)
	BEGIN
		SELECT password
		FROM users
		WHERE users.id = _id;
	END //


-- Returns unregistered user corresponding to the given id
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS GetUnregisteredUserById //
CREATE PROCEDURE GetUnregisteredUserById(IN _id INT)
	BEGIN
		SELECT *
		FROM users JOIN user_roles ON users.email = user_roles.email
		WHERE users.id = _id
		AND user_roles.role = 'unauthorized';
	END //

-- Returns the number of users in the entire system
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetUserCount //
CREATE PROCEDURE GetUserCount()
	BEGIN
		SELECT COUNT(*) as userCount
		FROM users;
	END //

-- Returns the number of users in a given space that match a given query
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetUserCountInSpaceWithQuery //
CREATE PROCEDURE GetUserCountInSpaceWithQuery(IN _spaceId INT, IN _query TEXT)
	BEGIN
		SELECT 	COUNT(*) AS userCount
		FROM 	user_assoc
			JOIN users ON users.id=user_id
		WHERE 	space_id=_spaceId AND
				(CONCAT(users.first_name, ' ', users.last_name)	LIKE	CONCAT('%', _query, '%')
				OR		users.institution						LIKE 	CONCAT('%', _query, '%')
				OR		users.email								LIKE 	CONCAT('%', _query, '%'));
	END //

-- Returns the user record with the given email address
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetUserByEmail //
CREATE PROCEDURE GetUserByEmail(IN _email VARCHAR(64))
	BEGIN
		SELECT *
		FROM users NATURAL JOIN user_roles
		WHERE users.email = _email;
	END //


-- Returns the user record with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetUserById //
CREATE PROCEDURE GetUserById(IN _id INT)
	BEGIN
		SELECT *
		FROM users NATURAL JOIN user_roles
		WHERE users.id = _id;
	END //

DROP PROCEDURE IF EXISTS UnsubscribeUserFromErrorLogs //
CREATE PROCEDURE UnsubscribeUserFromErrorLogs(IN _id INT)
	BEGIN
		UPDATE users
		SET subscribed_to_error_logs=FALSE
		WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS SubscribeUserToErrorLogs //
CREATE PROCEDURE SubscribeUserToErrorLogs(IN _id INT)
	BEGIN
		UPDATE users
		SET subscribed_to_error_logs=TRUE
		WHERE id=_id;
	END //

DROP PROCEDURE IF EXISTS GetAllUsersSubscribedToErrorLogs //
CREATE PROCEDURE GetAllUsersSubscribedToErrorLogs()
	BEGIN
		SELECT *
		FROM users NATURAL JOIN user_roles
		WHERE subscribed_to_error_logs=TRUE;
	END //

-- Retrieves all users belonging to a space
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetSpaceUsersById //
CREATE PROCEDURE GetSpaceUsersById(IN _id INT)
	BEGIN
		SELECT DISTINCT *
		FROM user_assoc
			JOIN users ON users.id=user_assoc.user_id
		WHERE _id=user_assoc.space_id
		ORDER BY first_name;
	END //




-- Updates the email address of the user with the given user id to the
-- given email address. The email address should already be validated
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdateEmail //
CREATE PROCEDURE UpdateEmail(IN _id INT, IN _email VARCHAR(64))
	BEGIN
		UPDATE users
		SET email = _email
		WHERE users.id = _id;
	END //

-- Updates the first name of the user with the given user id to the
-- given first name. The first name should already be validated.
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdateFirstName //
CREATE PROCEDURE UpdateFirstName(IN _id INT, IN _firstname VARCHAR(32))
	BEGIN
		UPDATE users
		SET first_name = _firstname
		WHERE users.id = _id;
	END //

-- Updates the last name of the user with the given user id to the
-- given last name. The last name should already be validated
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdateLastName //
CREATE PROCEDURE UpdateLastName(IN _id INT, IN _lastname VARCHAR(32))
	BEGIN
		UPDATE users
		SET last_name = _lastname
		WHERE users.id = _id;
	END //

-- Updates the institution of the user with the given user id to the
-- given institution. The institution should already be validated
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdateInstitution //
CREATE PROCEDURE UpdateInstitution(IN _id INT, IN _institution VARCHAR(64))
	BEGIN
		UPDATE users
		SET institution = _institution
		WHERE users.id = _id;
	END //

-- Updates the password of the user with the given user id to the
-- given (already hashed and validated) password.
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS UpdatePassword //
CREATE PROCEDURE UpdatePassword(IN _id INT, IN _password VARCHAR(128))
	BEGIN
		UPDATE users
		SET password = _password
		WHERE users.id = _id;
	END //

-- Gets the default page size for a given user
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetDefaultPageSize //
CREATE PROCEDURE GetDefaultPageSize(IN _id INT)
	BEGIN
		SELECT default_page_size AS pageSize
		FROM users
		WHERE id=_id;
	END //

-- Sets the default page size for a user, which is the number of rows per datatable they see by default
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS SetDefaultPageSize //
CREATE PROCEDURE SetDefaultPageSize(IN _id INT, IN _size INT)
	BEGIN
		UPDATE users
		SET default_page_size=_size
		WHERE id=_id;
	END //

-- Sets the user disk quota limit to the value of _newBytes
-- for the given user
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS UpdateUserDiskQuota //
CREATE PROCEDURE UpdateUserDiskQuota(IN _userId INT, IN _newQuota BIGINT)
	BEGIN
		UPDATE users
		SET disk_quota = _newQuota
		WHERE id = _userId;
	END //

-- Sets the user pair quota limit for the given user
DROP PROCEDURE IF EXISTS UpdateUserPairQuota //
CREATE PROCEDURE UpdateUserPairQuota(IN _userId INT, IN _newQuota INT)
	BEGIN
		UPDATE users
		SET job_pair_quota = _newQuota
		WHERE id = _userId;
	END //

-- Gets the total disk usage for a given user.
DROP PROCEDURE IF EXISTS GetUserDiskUsage //
CREATE PROCEDURE GetUserDiskUsage(IN _userID INT)
	BEGIN
		SELECT disk_size FROM users WHERE id=_userID;
	END //

-- Sums up the disk_size columns of solvers, benchmarks, and jobs and places that value in the
-- the user disk_size column. Returns the difference between the old and new values in _sizeDelta
DROP PROCEDURE IF EXISTS UpdateUserDiskUsage //
CREATE PROCEDURE UpdateUserDiskUsage(IN _userID INT, OUT _sizeDelta BIGINT)
	BEGIN
		DECLARE _sumDiskSize BIGINT;
		DECLARE _userDiskSize BIGINT;
		SELECT COALESCE(SUM(disk_size),0) AS disk_usage FROM
		(SELECT disk_size FROM solvers WHERE user_id=_userID AND deleted=false
		UNION ALL
		SELECT disk_size FROM benchmarks WHERE user_id=_userID AND deleted=false
		UNION ALL
		SELECT disk_size FROM jobs WHERE user_id=_userID AND deleted=false) AS tmp INTO _sumDiskSize;

		SELECT disk_size FROM users WHERE id=_userID INTO _userDiskSize;

		SELECT (_userDiskSize-_sumDiskSize) INTO _sizeDelta;

		UPDATE users SET disk_size=_sumDiskSize WHERE id=_userID;
	END //

-- Returns the number of bytes a given user's benchmarks is consuming on disk
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetUserBenchmarkDiskUsage //
CREATE PROCEDURE GetUserBenchmarkDiskUsage(IN _userID INT)
	BEGIN
		SELECT sum(benchmarks.disk_size) AS disk_usage
		FROM   benchmarks
		WHERE  benchmarks.user_id = _userId;
	END //

-- Returns the number of bytes a given user's benchmarks is consuming on disk
-- Author: Eric Burns

DROP PROCEDURE IF EXISTS GetUserSolverDiskUsage //
CREATE PROCEDURE GetUserSolverDiskUsage(IN _userID INT)
	BEGIN
		SELECT sum(solvers.disk_size) AS disk_usage
		FROM   solvers
		WHERE  solvers.user_id = _userId;
	END //



-- Returns one record if a given user is a member of a particular space
-- otherwise it returns an empty set
-- Author: Todd Elvers
DROP PROCEDURE IF EXISTS IsMemberOfSpace //
CREATE PROCEDURE IsMemberOfSpace(IN _userId INT, IN _spaceId INT)
	BEGIN
		SELECT *
		FROM  user_assoc
		WHERE user_id  = _userId
		AND   space_id = _spaceId;
	END //

-- Gets every user subscribed to the weekly reports
-- Author: Albert Giegerich
DROP PROCEDURE IF EXISTS GetAllUsersSubscribedToReports //
CREATE PROCEDURE GetAllUsersSubscribedToReports()
	BEGIN
		SELECT *
		FROM users
			INNER JOIN user_roles AS roles on users.email = roles.email
		WHERE subscribed_to_reports = TRUE;
	END //

-- Gets every user whose role is 'admin'
DROP PROCEDURE IF EXISTS GetAdmins //
CREATE PROCEDURE GetAdmins()
	BEGIN
		SELECT *
		FROM users
			INNER JOIN user_roles AS roles ON users.email = roles.email
		WHERE roles.role = "admin";
	END //

-- Checks to see whether the given user is a member of the given community
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS IsMemberOfCommunity //
CREATE PROCEDURE IsMemberOfCommunity(IN _userId INT, IN communityId INT)
	BEGIN
		SELECT COUNT(*) AS spaceCount FROM closure
			JOIN user_assoc AS assoc ON assoc.space_id=descendant
		WHERE assoc.user_id=_userId AND ancestor=communityId;
	END //


-- Deletes a user from the database. Right now, this is only used to get rid of temporary test users
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS DeleteUser //
CREATE PROCEDURE DeleteUser(IN _userId INT)
	BEGIN
		DELETE FROM logins WHERE user_id=_userId;
		DELETE FROM users WHERE id=_userId;
	END //

-- Sets the role of the given user to the given value
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS ChangeUserRole //
CREATE PROCEDURE ChangeUserRole(IN _userId INT, IN _role VARCHAR(24))
	BEGIN
		UPDATE user_roles
		JOIN users ON users.email=user_roles.email
		SET role=_role
		WHERE id=_userId;
	END //

DROP PROCEDURE IF EXISTS SetUserReportSubscription //
CREATE PROCEDURE SetUserReportSubscription(IN _userId INT, IN _willBeSubscribed BOOLEAN)
	BEGIN
		UPDATE users
		SET subscribed_to_reports = _willBeSubscribed
		WHERE id = _userId;
	END //


-- ================================================================================
-- Websites PROCEDURES
-- ================================================================================

-- Description: This file contains all website-related stored procedures for the starexec database
-- The procedures are stored by which table they're related to and roughly alphabetic order. Please try to keep this organized!

-- Adds a website that is associated with a user
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS AddUserWebsite //
CREATE PROCEDURE AddUserWebsite(IN _userId INT, IN _url TEXT, IN _name VARCHAR(64))
	BEGIN
		INSERT INTO website(user_id, url, name)
		VALUES(_userId, _url, _name);
	END //

-- Adds a website that is associated with a solver
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddSolverWebsite //
CREATE PROCEDURE AddSolverWebsite(IN _solverId INT, IN _url TEXT, IN _name VARCHAR(64))
	BEGIN
		INSERT INTO website(solver_id, url, name)
		VALUES(_solverId, _url, _name);
	END //

-- Adds a website that is associated with a space (community)
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddSpaceWebsite //
CREATE PROCEDURE AddSpaceWebsite(IN _spaceId INT, IN _url TEXT, IN _name VARCHAR(64))
	BEGIN
		INSERT INTO website(space_id, url, name)
		VALUES(_spaceId, _url, _name);
	END //

-- Deletes the website with the given website id
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS DeleteWebsite //
CREATE PROCEDURE DeleteWebsite(IN _id INT)
	BEGIN
		DELETE FROM website
		WHERE id = _id;
	END //


-- Returns all websites associated with the user with the given user id
-- Author: Skylar Stark
DROP PROCEDURE IF EXISTS GetWebsitesByUserId //
CREATE PROCEDURE GetWebsitesByUserId(IN _userid INT)
	BEGIN
		SELECT *
		FROM website
		WHERE website.user_id = _userid
		ORDER BY name;
	END //

-- Gets all websites that are associated with the solver with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetWebsitesBySolverId //
CREATE PROCEDURE GetWebsitesBySolverId(IN _id INT)
	BEGIN
		SELECT *
		FROM website
		WHERE website.solver_id = _id
		ORDER BY name;
	END //

-- Gets all websites that are associated with the space with the given id
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS GetWebsitesBySpaceId //
CREATE PROCEDURE GetWebsitesBySpaceId(IN _id INT)
	BEGIN
		SELECT *
		FROM website
		WHERE website.space_id = _id
		ORDER BY name;
	END //

DROP PROCEDURE IF EXISTS GetWebsiteById //
CREATE PROCEDURE GetWebsiteById(IN _id INT)
BEGIN
	SELECT * FROM website WHERE id = _id;
END //

-- Gets the next page of data table for community requests
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetNextPageOfPendingCommunityRequests //
CREATE PROCEDURE GetNextPageOfPendingCommunityRequests(IN _startingRecord INT, IN _recordsPerPage INT)
	BEGIN
		SELECT 	user_id,
				community,
				code,
				message,
				created
		FROM	community_requests
		ORDER BY
			created
		 ASC

		-- Shrink the results to only those required for the next page
		LIMIT _startingRecord, _recordsPerPage;
	END //

DROP PROCEDURE IF EXISTS GetNextPageOfPendingCommunityRequestsForCommunity //
CREATE PROCEDURE GetNextPageOfPendingCommunityRequestsForCommunity(IN _startingRecord INT, IN _recordsPerPage INT, IN _communityId INT)
	BEGIN
		SELECT 	user_id,
				community,
				code,
				message,
				created
		FROM	community_requests
		WHERE   community = _communityId
		ORDER BY
			created
		 ASC

		-- Shrink the results to only those required for the next page
		LIMIT _startingRecord, _recordsPerPage;
	END //

-- ================================================================================
-- FUNCTIONS from StarFunctions.sql
-- ================================================================================

-- Gets the number of completed job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS GetCompletePairs //
CREATE FUNCTION GetCompletePairs(_jobId INT)
	RETURNS INT
	READS SQL DATA
	BEGIN
		DECLARE completePairs INT;

		SELECT COUNT(*) INTO completePairs
		FROM job_pairs
		WHERE job_id=_jobId
		AND status_code=7;

		RETURN completePairs;
	END //

-- Gets the number of errored job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS GetErrorPairs //
CREATE FUNCTION GetErrorPairs(_jobId INT)
	RETURNS INT
	READS SQL DATA
	BEGIN
		DECLARE errorPairs INT;

		SELECT COUNT(*) INTO errorPairs
		FROM job_pairs
		WHERE job_id=_jobId
		AND (status_code BETWEEN 8 AND 17 OR status_code=0 OR status_code BETWEEN 24 AND 26);

		RETURN errorPairs;
	END //

-- Returns "complete" if the job represented by the given id had no pending job pairs,
-- and returns "incomplete" otherwise
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS GetJobStatus //
CREATE FUNCTION GetJobStatus(_jobId INT)
	RETURNS ENUM("incomplete", "complete")
	READS SQL DATA
	BEGIN
		DECLARE status ENUM("incomplete", "complete");

		SELECT IF (
			_jobId IN (
				SELECT job_id
				FROM job_pairs
				WHERE status_code BETWEEN 1 AND 6
			),
			"incomplete",
			"complete")
		INTO status;

		RETURN status;
	END //

-- Returns human readable description of this job's status
-- This Function looks intimidating, but it is just a big IF ELSE IF chain
DROP FUNCTION IF EXISTS GetJobStatusDetail //
CREATE FUNCTION GetJobStatusDetail(_jobId INT)
	RETURNS ENUM("RUNNING", "PROCESSING", "COMPLETE", "DELETED", "KILLED", "PAUSED", "GLOBAL_PAUSE")
	READS SQL DATA
	BEGIN
		DECLARE status ENUM("RUNNING", "PROCESSING", "COMPLETE", "DELETED", "KILLED", "PAUSED", "GLOBAL_PAUSE");

		SELECT
			IF ( _jobId IN ( SELECT     id FROM jobs      WHERE deleted ), "DELETED",
			IF ( _jobId IN ( SELECT     id FROM jobs      WHERE killed  ), "KILLED",
			IF ( _jobId IN ( SELECT     id FROM jobs      WHERE paused  ), "PAUSED",
			IF ( _jobId IN ( SELECT job_id FROM job_pairs WHERE status_code=22), "PROCESSING",
			IF ( _jobId IN ( SELECT job_id FROM job_pairs WHERE status_code BETWEEN 1 AND 6),
				IF ( TRUE IN (SELECT paused FROM system_flags)
					AND _jobId NOT IN (
						SELECT jobs.id
						FROM jobs
						JOIN users ON user_id=users.id
						JOIN user_roles ON user_roles.email=users.email
						WHERE (role = "admin" OR role = "developer")
					),
					"GLOBAL_PAUSE",
					"RUNNING"
				),
			"COMPLETE"
			)))))
		INTO status;

		RETURN status;
	END //

-- Gets the number of pending job pairs for a given job id
-- Author: Todd Elvers
DROP FUNCTION IF EXISTS GetPendingPairs //
CREATE FUNCTION GetPendingPairs(_jobId INT)
	RETURNS INT
	READS SQL DATA
	BEGIN
		DECLARE pendingPairs INT;

		SELECT COUNT(*) INTO pendingPairs
		FROM job_pairs
		WHERE job_id=_jobId
		AND (status_code BETWEEN 1 AND 6);

		RETURN pendingPairs;
	END //

--  Tells you whether a space is public or not
-- Author: Eric Burns
DROP FUNCTION IF EXISTS IsPublic //
CREATE FUNCTION IsPublic(_spaceId int)
	RETURNS BOOLEAN
	READS SQL DATA
	BEGIN
		DECLARE isPublic BOOLEAN;
	  		select public_access INTO isPublic
	  		from spaces
	  		where id = _spaceId;
	  	RETURN isPublic;
	END //

--  Determines if User is Leader of Space
--  Author: Benton McCune
DROP FUNCTION IF EXISTS IsLeader //
CREATE FUNCTION IsLeader(_spaceId int, _userId int)
	RETURNS BOOLEAN
	READS SQL DATA
	BEGIN
		DECLARE isLeader BOOLEAN;
	  		select is_Leader INTO isLeader
	  		from permissions
	  		where id = (select permission from user_assoc where space_id=_spaceId and user_id = _userId LIMIT 1);
	  	RETURN isLeader;
	END //

DELIMITER ;
