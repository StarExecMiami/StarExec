DELIMITER // -- Tell MySQL how we will denote the end of each prepared statement

-- Adds a new queue given a name
-- Author: Tyler Jensen
DROP PROCEDURE IF EXISTS AddQueue;
CREATE PROCEDURE AddQueue(IN _name VARCHAR(128),IN _wall INT, IN _cpu INT, OUT id INT)
	BEGIN		
		INSERT IGNORE INTO queues (name,clockTimeout,cpuTimeout, status)
		VALUES (_name,_wall,_cpu, "INACTIVE");
		SELECT LAST_INSERT_ID() INTO id;
	END //
	
-- Remove a queue given its id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueue;
CREATE PROCEDURE RemoveQueue(IN _queueId INT)
	BEGIN
		DELETE FROM queues
		WHERE id = _queueId;
	END //

-- Retrieves the id of a queue given its name
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetIdByName;
CREATE PROCEDURE GetIdByName(IN _queueName VARCHAR(64))
	BEGIN
		SELECT id
		FROM queues
		WHERE name = _queueName;
	END //

	
-- Retrieves all jobs with pending job pairs for the given queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetPendingJobs;
CREATE PROCEDURE GetPendingJobs(IN _queueId INT)
	BEGIN
		SELECT distinct jobs.id, user_id,name,seed,primary_space,
		jobs.clockTimeout,jobs.cpuTimeout,jobs.maximum_memory, jobs.suppress_timestamp
		FROM jobs
		JOIN job_pairs ON job_pairs.job_id=jobs.id
		WHERE status_code=1 and queue_id = _queueId;
	END //
		
-- Retrieves the number of enqueued job pairs for the given queue
-- Author: Benton McCune and Aaron Stump
-- TODO: This might be slow. Think about a possible index on queueId?
DROP PROCEDURE IF EXISTS GetNumEnqueuedJobs;
CREATE PROCEDURE GetNumEnqueuedJobs(IN _queueId INT)
	BEGIN
		SELECT COUNT(*) AS count FROM job_pairs JOIN jobs ON job_pairs.job_id = jobs.id
                WHERE job_pairs.status_code=2 AND jobs.queue_id = _queueId;
	END //	

	
-- Gets the sum of wallclock timeouts for all 
DROP PROCEDURE IF EXISTS GetUserLoadOnQueue;
CREATE PROCEDURE GetUserLoadOnQueue(IN _queueId INT, IN _user INT)
	BEGIN
		SELECT SUM(jobs.clockTimeout) AS load FROM job_pairs JOIN jobs ON job_pairs.job_id = jobs.id
                WHERE (job_pairs.status_code=4 OR job_pairs.status_code=3
                OR job_pairs.status_code=2 OR job_pairs.status_code=5)
                AND jobs.queue_id = _queueId AND jobs.user_id=_user;
	END //	
	
-- Retrieves basic info about enqueued job pairs for the given queue id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetCountOfEnqueuedJobPairsByQueue;
CREATE PROCEDURE GetCountOfEnqueuedJobPairsByQueue(IN _id INT)
	BEGIN
		SELECT count(*) AS count
		FROM job_pairs
			-- Where the job_pair is running on the input Queue
			INNER JOIN jobs AS enqueued ON job_pairs.job_id = enqueued.id
		WHERE enqueued.queue_id = _id AND job_pairs.status_code = 2;
	END //
	
-- Retrieves basic info about running job pairs for the given node id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetCountOfRunningJobPairsByQueue;
CREATE PROCEDURE GetCountOfRunningJobPairsByQueue(IN _id INT)
	BEGIN
		SELECT count(*) AS count
		FROM job_pairs
		WHERE node_id = _id AND (status_code = 4 OR status_code = 3);
		END //	

-- Get the name of a queue given its id
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS getNameById;
CREATE PROCEDURE getNameById(IN _queueId INT)
	BEGIN
		SELECT name
		FROM queues
		WHERE id = _queueId;
	END // 
	
-- Determines if the queue is a permanent queue
-- Author: Wyatt kaiser
DROP PROCEDURE IF EXISTS IsQueuePermanent;
CREATE PROCEDURE IsQueuePermanent (IN _queueId INT)
	BEGIN
		SELECT permanent
		FROM queues
		WHERE id = _queueId;
	END //
	
-- Makes a queue permanent by setting its value to true
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS MakeQueuePermanent;
CREATE PROCEDURE MakeQueuePermanent (IN _queueId INT)
	BEGIN
		UPDATE queues
		SET permanent = true
		WHERE id = _queueId;
	END //

-- Updates the max wallclock timeout for a queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdateQueueClockTimeout;
CREATE PROCEDURE UpdateQueueClockTimeout(IN _queueId INT, IN _timeout INT)
	BEGIN
		UPDATE queues
		SET clockTimeout=_timeout
		WHERE id=_queueId;
	END //

-- Updates the max cpu timeout for a queue
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS UpdateQueueCpuTimeout;
CREATE PROCEDURE UpdateQueueCpuTimeout(IN _queueId INT, IN _timeout INT)
	BEGIN
		UPDATE queues
		SET cpuTimeout=_timeout
		WHERE id=_queueId;
	END //	

-- Determines if the queue has global access
-- Author: Wyatt kaiser
DROP PROCEDURE IF EXISTS IsQueueGlobal;
CREATE PROCEDURE IsQueueGlobal (IN _queueId INT)
	BEGIN
		SELECT global_access
		FROM queues
		WHERE id = _queueId;
	END //
	
-- Removes a queue's association with a space
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueueAssociation;
CREATE PROCEDURE RemoveQueueAssociation(IN _queueId INT)
	BEGIN
		DELETE FROM comm_queue
		WHERE queue_id = _queueId;
	END //
	
-- Make a permanent queue have global access
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS MakeQueueGlobal;
CREATE PROCEDURE MakeQueueGlobal(IN _queueId INT)
	BEGIN
		UPDATE queues
		SET global_access = true
		WHERE id = _queueId;
		
		DELETE FROM comm_queue
		WHERE queue_id = _queueId;
	END //
		
-- remove global access from a permanent queue
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS RemoveQueueGlobal;
CREATE PROCEDURE RemoveQueueGlobal(IN _queueId INT)
	BEGIN
		UPDATE queues
		SET global_access = false
		WHERE id = _queueId;
	END //
	
-- Get the active queues that have been reserved for a particular space
-- Author: Wyatt Kaiser
DROP PROCEDURE IF EXISTS GetQueuesForSpace;
CREATE PROCEDURE GetQueuesForSpace(IN _spaceId INT)
	BEGIN
		SELECT DISTINCT queue_id
		FROM comm_queue
		JOIN queues ON comm_queue.queue_id = queues.id
		WHERE comm_queue.space_id = _spaceId 
			AND queues.status = "ACTIVE" 
			AND queues.permanent = false;
	END //
	
-- Gets all of the permanent queues that the given user is allowed to use
DROP PROCEDURE IF EXISTS GetPermanentQueuesForUser;
CREATE PROCEDURE GetPermanentQueuesForUser(IN _userID INT)
	BEGIN
		SELECT DISTINCT id, name, status, permanent, global_access, cpuTimeout,clockTimeout
		FROM queues 
			JOIN queue_assoc ON queues.id = queue_assoc.queue_id
			LEFT JOIN comm_queue ON queues.id = comm_queue.queue_id
		WHERE 	
				queues.status = "ACTIVE"
			AND queues.permanent = true
			AND (
				(IsLeader(comm_queue.space_id, _userId) = 1)	-- Either you are the leader of the community it was given access to
				OR
				(global_access = true)							-- or it is a global permanent queue
				);				 
	END //

	
-- Sets the test queue in the database to a new value
DROP PROCEDURE IF EXISTS SetTestQueue;
CREATE PROCEDURE SetTestQueue(IN _qid INT)
	BEGIN
		UPDATE system_flags SET test_queue=_qid;
	END //
-- Gets the ID of the queue for running test jobs on solver uploads
-- Author: Eric Burns
DROP PROCEDURE IF EXISTS GetTestQueue;
CREATE PROCEDURE GetTestQueue()
	BEGIN
		SELECT test_queue FROM system_flags;
	END //
	
DELIMITER ; -- This should always be at the end of this file
