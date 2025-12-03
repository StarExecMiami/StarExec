-- gets the next page of enqueued pairs to populate the datatable on the cluster status page
-- vars
-- id The ID of the queue to get the running pairs on

SELECT 	   job_pairs.id,
		   job_pairs.path,
		   job_pairs.primary_jobpair_data AS primaryJobpairData,
		   job_pairs.job_id AS jobId,
		   job_pairs.bench_id AS benchId,
		   job_pairs.bench_name AS benchName,
		   job_pairs.queuesub_time AS queuesubTime,
		   jobpair_stage_data.solver_id AS solverId,
		   jobpair_stage_data.solver_name AS solverName,
		   jobpair_stage_data.config_id AS configId,
		   jobpair_stage_data.config_name AS configName,
		   jobs.id AS jobIdDup,
		   jobs.name AS jobName,
		   users.id AS userId,
		   users.first_name AS firstName,
		   users.last_name AS lastName
FROM job_pairs
-- Where the job_pair is running on the input Queue
	JOIN jobs ON jobs.id = job_pairs.job_id
	JOIN users ON users.id = jobs.user_id
	JOIN jobpair_stage_data ON jobpair_stage_data.jobpair_id = job_pairs.id
WHERE (jobs.queue_id = :id AND job_pairs.status_code = 2 AND jobpair_stage_data.stage_number=job_pairs.primary_jobpair_data)
