
-- Flyway baseline migration
-- Import of initial baseline schema for StarExec.
-- Generated from sql/new-install/StarSchema.sql

-- Author: Tyler Jensen
-- Description: This file is the schema used to create the starexec database in MySQL

-- Note: Flyway is configured with defaultSchema("starexec") and createSchemas(true),
-- so the starexec schema is created automatically and all objects go there

-- The table of all users in the system
CREATE TABLE users (
	id SERIAL NOT NULL,
	email VARCHAR(64) NOT NULL,
	first_name VARCHAR(32) NOT NULL,
	last_name VARCHAR(32) NOT NULL,
	institution VARCHAR(64) NOT NULL,
	created TIMESTAMP NOT NULL,
	password VARCHAR(128) NOT NULL,
	disk_quota BIGINT NOT NULL,
	job_pair_quota INT DEFAULT 750000 NOT NULL,
	subscribed_to_reports BOOLEAN NOT NULL DEFAULT FALSE,
	default_page_size INT NOT NULL DEFAULT 10,
	default_settings_profile INT DEFAULT NULL,
	disk_size BIGINT NOT NULL DEFAULT 0,
	subscribed_to_error_logs BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (id),
	-- the following foreign key is used, but it is added at the end because you can't declare a foreign key before declaring the table
	-- CONSTRAINT users_default_settings_profile FOREIGN KEY (default_settings_profile) REFERENCES default_settings(id) ON DELETE SET NULL,
	UNIQUE (email)
);

-- An associative table that maps a user to a role.
-- The application uses 'unauthorized', 'user' and 'admin' for now.
CREATE TABLE user_roles (
	email  VARCHAR(64) NOT NULL,
	role VARCHAR(24) NOT NULL,
	PRIMARY KEY (email, role),
	CONSTRAINT user_roles_email FOREIGN KEY (email) REFERENCES users(email) ON DELETE CASCADE ON UPDATE CASCADE
);

-- A history record of all logins to the system
CREATE TABLE logins (
	id SERIAL NOT NULL,
	user_id INT NOT NULL,
	login_date TIMESTAMP NOT NULL,
	ip_address VARCHAR(15) DEFAULT '0.0.0.0',
	browser_agent TEXT,
	PRIMARY KEY (id),
	CONSTRAINT logins_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE NO ACTION
);

-- A convenience table, this contains a specific set of
-- permissions that can be associated with a user (or set as
-- the default of a space so when a new user is ed, they take on
-- these permissions)
CREATE TABLE permissions (
	id SERIAL NOT NULL,
	add_solver BOOLEAN DEFAULT FALSE,
	add_bench BOOLEAN DEFAULT FALSE,
	add_user BOOLEAN DEFAULT FALSE,
	add_space BOOLEAN DEFAULT FALSE,
	add_job BOOLEAN DEFAULT FALSE,
	remove_solver BOOLEAN DEFAULT FALSE,
	remove_bench BOOLEAN DEFAULT FALSE,
	remove_user BOOLEAN DEFAULT FALSE,
	remove_space BOOLEAN DEFAULT FALSE,
	remove_job BOOLEAN DEFAULT FALSE,
	is_leader BOOLEAN DEFAULT FALSE,
	PRIMARY KEY(id)
);

-- Default permission for the root space
INSERT INTO permissions VALUES (1, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE);

-- All of the spaces in starexec. A space is simply a set where
-- solvers, benchmarks, users and jobs exist (think of it as a folder)
CREATE TABLE spaces (
	id SERIAL NOT NULL,
	name VARCHAR(255) NOT NULL,
	created TIMESTAMP NULL DEFAULT NULL,
	description TEXT,
	locked BOOLEAN DEFAULT FALSE,
	default_permission INT,
	public_access BOOLEAN DEFAULT FALSE,
	sticky_leaders BOOLEAN DEFAULT FALSE,
	PRIMARY KEY (id),
	CONSTRAINT spaces_default_permission FOREIGN KEY (default_permission) REFERENCES permissions(id) ON DELETE SET NULL
);

-- The set of all associations between each node and it's descendants
-- (see the hierarchical data representation PDF on the wiki for more details)
CREATE TABLE closure (
	ancestor INT NOT NULL,
	descendant INT NOT NULL,
	UNIQUE (ancestor, descendant),
	CONSTRAINT closure_ancestor FOREIGN KEY (ancestor) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT closure_descendant FOREIGN KEY (descendant) REFERENCES spaces(id) ON DELETE CASCADE
);

-- The root space
INSERT INTO spaces (name, created, description, locked, default_permission, public_access) VALUES
('root', CURRENT_TIMESTAMP, 'this is the starexec container space which holds all communities.', TRUE, 1, TRUE);
INSERT INTO closure VALUES(1,1);

-- A table where we can keep track of the different syntax highlighters we
-- support for benchmarks
--   name:  Human readable display name of the syntax
--   class: Class name that will be used in the HTML to denote this syntax
--   js:    Relative path to JavaScript lexer for this syntax
CREATE TABLE syntax (
	id SERIAL NOT NULL,
	name CHAR(32) NOT NULL,
	class CHAR(32) NOT NULL,
	js CHAR(32),
	PRIMARY KEY (id),
	UNIQUE (name)
);

-- Here we add the supported lexers to the DB
-- In general, id is not important and should be set automatically. However, it
-- is important that 1 represent Plain Text (ie: No highlighting) because that
-- will be the default for processors without a syntax set
INSERT INTO syntax (id, name, class, js) VALUES
	(1, 'Plain Text', 'prettyprinted',  NULL            ),
	(2, 'C'         , 'lang-c'       ,  NULL            ),
	(3, 'SMT-LIB'   , 'lang-smtlib'  , 'lib/lang-smtlib'),
	(4, 'TPTP'      , 'lang-tptp'    , 'lib/lang-tptp'  )
;

-- All pre, post and bench processors in the system
CREATE TABLE processors (
	id SERIAL NOT NULL,
	name VARCHAR(64) NOT NULL,
	description TEXT,
	path TEXT NOT NULL,
	community INT NOT NULL,
	processor_type SMALLINT DEFAULT 0,
	disk_size BIGINT NOT NULL,
	preserve_input BOOLEAN DEFAULT TRUE,
	syntax_id INT DEFAULT 1,
	time_limit SMALLINT DEFAULT 15,
	PRIMARY KEY (id),
	CONSTRAINT processors_community FOREIGN KEY (community) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT processors_syntax FOREIGN KEY (syntax_id) REFERENCES syntax(id)
);

-- The record for an individual benchmark
CREATE TABLE benchmarks (
	id SERIAL NOT NULL,
	user_id INT NOT NULL,
	name VARCHAR(256) NOT NULL,
	bench_type INT,
	uploaded TIMESTAMP NOT NULL,
	path TEXT NOT NULL,
	description TEXT,
	downloadable BOOLEAN DEFAULT TRUE,
	disk_size BIGINT NOT NULL,
	deleted BOOLEAN DEFAULT FALSE,
	recycled BOOLEAN DEFAULT FALSE,
	PRIMARY KEY (id),
	CONSTRAINT benchmarks_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
	CONSTRAINT benchmarks_bench_type FOREIGN KEY (bench_type) REFERENCES processors(id) ON DELETE SET NULL
);

-- All attributes for each benchmark
CREATE TABLE bench_attributes (
	bench_id INT NOT NULL,
	attr_key VARCHAR(128) NOT NULL,
	attr_value VARCHAR(128) NOT NULL,
	PRIMARY KEY (bench_id, attr_key),
	CONSTRAINT bench_attributes_bench_id FOREIGN KEY (bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE
);

-- This table holds the names of executable types so that they are accessible for SQL sorts and filters.
-- The contents in this table should match with the enum in Solver.java to ensure proper sorts!
CREATE TABLE executable_types (
	type_id INT NOT NULL,
	type_name VARCHAR(32),
	PRIMARY KEY (type_id)
);

INSERT INTO executable_types (type_id, type_name) VALUES (1,'solver'), (2,'transformer'),(3,'result checker'),(4,'other');

-- The record for an individual solver
CREATE TABLE solvers (
	id SERIAL NOT NULL,
	user_id INT NOT NULL,
	name VARCHAR(128) NOT NULL,
	uploaded TIMESTAMP NOT NULL,
	path TEXT NOT NULL,
	description TEXT,
	downloadable BOOLEAN DEFAULT FALSE,
	disk_size BIGINT NOT NULL,
	deleted BOOLEAN DEFAULT FALSE,
	recycled BOOLEAN DEFAULT FALSE,
	executable_type INT DEFAULT 1,
	build_status INT DEFAULT 1,
	PRIMARY KEY (id),
	CONSTRAINT solvers_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
	CONSTRAINT solvers_executable_type FOREIGN KEY (executable_type) REFERENCES executable_types(type_id) ON DELETE SET NULL
);

-- All the configurations that belong to a solver. A solver
-- may have different settings that the user wants to run it with,
-- so they provide one or more configuration that tells us how they want
-- us to run their solver.
CREATE TABLE configurations (
	id SERIAL NOT NULL,
	solver_id INT,
	name VARCHAR(128) NOT NULL,
	description TEXT,
	updated TIMESTAMP NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT configurations_solver_id FOREIGN KEY (solver_id) REFERENCES solvers(id) ON DELETE CASCADE
);

-- All the SGE node queues on the system
CREATE TABLE queues (
	id SERIAL NOT NULL,
	name VARCHAR(64) NOT NULL,
	status VARCHAR(32),
	global_access BOOLEAN DEFAULT FALSE,
	cpuTimeout INT DEFAULT 259200,
	clockTimeout INT DEFAULT 259200, -- timeouts are maxes for any jobs created on the queue
	description VARCHAR(200) DEFAULT '',
	PRIMARY KEY (id),
	UNIQUE (name)
);

-- All the SGE worker nodes that jobs can be executed on in the cluster.
-- This just maintains hardware information manually to be viewed by
CREATE TABLE nodes (
	id SERIAL NOT NULL,
	name VARCHAR(128) NOT NULL,
	status VARCHAR(32),
	PRIMARY KEY (id),
	UNIQUE (name)
);

-- All the SGE node queues on the system
CREATE TABLE queue_assoc (
	queue_id INT NOT NULL,
	node_id INT NOT NULL,
	PRIMARY KEY (queue_id, node_id),
	CONSTRAINT queue_assoc_queue_id FOREIGN KEY (queue_id) REFERENCES queues(id) ON DELETE CASCADE,
	CONSTRAINT queue_assoc_node_id FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE CASCADE
);

-- Association between exclusive queues and spaces (initially only communities)
CREATE TABLE comm_queue (
	space_id INT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
	queue_id INT NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
	PRIMARY KEY (space_id, queue_id)
);

-- table for storing the top level of solver pipelines. These should generally not be deleted
-- if there are jobs making use of them.
CREATE TABLE solver_pipelines (
	id SERIAL NOT NULL,
	name VARCHAR(128),
	user_id INT NOT NULL,
	uploaded TIMESTAMP NOT NULL,
	primary_stage_id INT,
	PRIMARY KEY(id)
);

-- Stages for solver pipelines. Stages are ordered by their stage_id primary key
CREATE TABLE pipeline_stages (
	stage_id SERIAL NOT NULL, -- orders the stages of this pipeline
	pipeline_id INT NOT NULL,
	config_id INT,
	is_noop BOOLEAN NOT NULL DEFAULT FALSE, -- note that we cannot say that this is a noop if config_id is null, because the config
						   -- could have just been deleted. We really do need to store this explicitly
	PRIMARY KEY (stage_id), -- pipelines can have many stages
	CONSTRAINT pipeline_stages_pipeline_id FOREIGN KEY (pipeline_id) REFERENCES solver_pipelines(id) ON DELETE CASCADE,
	CONSTRAINT pipeline_stages_config_id FOREIGN KEY (config_id) REFERENCES configurations(id) ON DELETE SET NULL
);

-- Stores any dependencies that a particular stage has.
CREATE TABLE pipeline_dependencies (
	stage_id INT NOT NULL, -- ID of the stage that must receive output from a previous stage
	input_type SMALLINT NOT NULL, -- ID of the stage that produces the output
	input_id SMALLINT NOT NULL, -- if the type is an artifact, this is the the 1-indexed number of the stage that is needed
					   -- if the type is a benchmark, this is the the 1-indexed number of the benchmark that is needed
	input_number SMALLINT NOT NULL, -- which input to the stage is this? First input, second input, and so on
	PRIMARY KEY (stage_id, input_number), -- obviously a given stage may only have one dependency per number
	CONSTRAINT pipeline_dependencies_stage_id FOREIGN KEY (stage_id) REFERENCES pipeline_stages(stage_id) ON DELETE CASCADE
);

-- All of the jobs within the system, this is the overarching entity
-- that contains individual job pairs (solver/config -> benchmark)
CREATE TABLE jobs (
	id SERIAL NOT NULL,
	user_id INT NOT NULL,
	name VARCHAR(64),
	queue_id INT,
	created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	completed TIMESTAMP,
	description TEXT,
	deleted BOOLEAN DEFAULT FALSE,
	paused BOOLEAN DEFAULT FALSE,
	killed BOOLEAN DEFAULT FALSE,
	seed BIGINT DEFAULT 0,
	cpuTimeout INT,
	clockTimeout INT,
	kill_delay INT,
	soft_time_limit INT,
	maximum_memory BIGINT DEFAULT 1073741824,
	primary_space INT, -- This is a JOB_SPACE, not simply a "space"
	using_dependencies BOOLEAN NOT NULL DEFAULT FALSE, -- whether jobline dependencies are used by any pair
	suppress_timestamp BOOLEAN NOT NULL DEFAULT FALSE,
	buildJob BOOLEAN NOT NULL DEFAULT FALSE,
	total_pairs INT NOT NULL, -- How many pairs are in this job? Used to avoid needing to count from pairs table for efficiency
	disk_size BIGINT NOT NULL,
	is_high_priority BOOLEAN NOT NULL DEFAULT FALSE,
	benchmarking_framework VARCHAR(20) NOT NULL DEFAULT 'RUNSOLVER' CHECK (benchmarking_framework IN ('RUNSOLVER', 'BENCHEXEC')),
	output_benchmarks_directory_path TEXT DEFAULT NULL, -- directory for benchmarks created by this job
	PRIMARY KEY (id),
	CONSTRAINT jobs_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
	CONSTRAINT jobs_queue_id FOREIGN KEY (queue_id) REFERENCES queues(id) ON DELETE SET NULL
);

-- This table stores timeouts for individual pipeline stages for this job.
-- These are essentially overrides for the columns in the jobs table
CREATE TABLE job_stage_params (
	job_id INT,
	stage_number INT,
	cpuTimeout INT,
	clockTimeout INT,
	maximum_memory BIGINT DEFAULT 1073741824,
	space_id INT, -- if we're keeping benchmarks from this stage, where should we be putting them? null if not keeping them
	bench_suffix VARCHAR(64), -- if we're keeping benchmarks, what suffix? If none given, we use the suffix of the input benchmark
	post_processor INT,
	pre_processor INT,
	results_interval INT DEFAULT 0, -- Interval at which to copy back incremental results for pairs, in seconds. 0 means do not use incremental results
	stdout_save_option INT NOT NULL DEFAULT 2,         -- see SaveResultsOption enum (same for below).
	extra_output_save_option INT NOT NULL DEFAULT 2,
	PRIMARY KEY (job_id,stage_number),
	CONSTRAINT job_stage_params_job_id FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
	CONSTRAINT job_stage_params_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE SET NULL,
	CONSTRAINT job_stage_params_post_processor FOREIGN KEY (post_processor) REFERENCES processors(id) ON DELETE SET NULL,
	CONSTRAINT job_stage_params_pre_processor FOREIGN KEY (pre_processor) REFERENCES processors(id) ON DELETE SET NULL
);

-- Table which contains specific information about a job pair
-- When changing to using runsolver, wallclock changed from bigint to double
-- note: while some data is redundant in this table (solver_name, config_name, and so on),
-- it is essential for speeding up queries.
CREATE TABLE job_pairs (
	id SERIAL NOT NULL,
	job_id INT NOT NULL,
	sge_id INT,
	bench_id INT,
	bench_name VARCHAR(255),
	status_code SMALLINT DEFAULT 0,
	node_id INT,
	queuesub_time TIMESTAMP(3) NULL DEFAULT NULL,
	start_time TIMESTAMP NULL DEFAULT NULL,
	end_time TIMESTAMP NULL DEFAULT NULL,
	job_space_id INT,
	path VARCHAR(2048),
	sandbox_num INT,
	primary_jobpair_data INT, -- which of this pairs stages is the primary one? references jobpair_stage_data.stage_number
	PRIMARY KEY(id),
	CONSTRAINT job_pairs_job_id FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
	CONSTRAINT job_pairs_node_id FOREIGN KEY (node_id) REFERENCES nodes(id) ON DELETE NO ACTION
);

-- Indexes for job_pairs table
CREATE INDEX idx_job_pairs_sge_id ON job_pairs(sge_id);
CREATE INDEX idx_job_pairs_job_space_id_bench_name ON job_pairs(job_space_id, bench_name);
CREATE INDEX idx_job_pairs_node_id_status_code ON job_pairs(node_id, status_code);
CREATE INDEX idx_job_pairs_status_code ON job_pairs(status_code);
CREATE INDEX idx_job_pairs_job_id_status_code ON job_pairs(job_id, status_code);

CREATE TABLE jobpair_stage_data (
	stage_number INT NOT NULL, -- this id orders the stages
	jobpair_id INT NOT NULL,
	stage_id INT, -- References pipeline_stages stages are ordered by this ID as well.
	cpu DOUBLE PRECISION,
	wallclock DOUBLE PRECISION,
	max_vmem DOUBLE PRECISION,
	max_res_set DOUBLE PRECISION,
	user_time DOUBLE PRECISION,
	system_time DOUBLE PRECISION,
	status_code SMALLINT DEFAULT 0,
	solver_name VARCHAR(128), -- These columns are redundant, but they allow us to keep stages even with deleted configs
	config_name VARCHAR(128),
	solver_id INT,
	config_id INT,
	job_space_id INT,
	disk_size BIGINT NOT NULL,
	PRIMARY KEY (jobpair_id,stage_number),
	CONSTRAINT jobpair_stage_data_jobpair_id FOREIGN KEY (jobpair_id) REFERENCES job_pairs(id) ON DELETE CASCADE,
	CONSTRAINT jobpair_stage_data_stage_id FOREIGN KEY (stage_id) REFERENCES pipeline_stages(stage_id) ON DELETE SET NULL
);

-- Indexes for jobpair_stage_data table
CREATE INDEX idx_jobpair_stage_data_job_space_id_config_id ON jobpair_stage_data(job_space_id, config_id);
CREATE INDEX idx_jobpair_stage_data_job_space_id_solver_name ON jobpair_stage_data(job_space_id, solver_name);
CREATE INDEX idx_jobpair_stage_data_job_space_id_config_name ON jobpair_stage_data(job_space_id, config_name);

-- ================================================================================
-- Post-deploy compatibility fixes for PostgreSQL
-- These ALTER statements add columns that older baseline imports from MySQL
-- may have omitted and adjust text column collations to a PostgreSQL-compatible
-- collation. These are guarded with IF NOT EXISTS so they are safe to re-run.
-- NOTE: Changing collations requires the database to support the target locale
-- (e.g. en_US.UTF-8). If the locale does not exist on the host, adjust as needed.
-- ================================================================================

-- Add missing columns referenced by procedures and legacy application code
ALTER TABLE job_pairs
	ADD COLUMN IF NOT EXISTS solver_name VARCHAR(255);

ALTER TABLE solvers
	ADD COLUMN IF NOT EXISTS recycled_original_name VARCHAR(255);

-- Track whether a solver's configurations were removed when the solver was recycled
ALTER TABLE solvers
	ADD COLUMN IF NOT EXISTS config_deleted BOOLEAN DEFAULT FALSE;

-- Ensure common user-facing text columns use a UTF-8 collation (PostgreSQL style)
-- Adjust these ALTERs if your database cluster was initialized with a different
-- collation or if the target locale is not available on the host.
-- These operations only change the column type/collation and are guarded
-- by TRY/CATCH in case the collation is not present at runtime.
DO $$
BEGIN
	BEGIN
		ALTER TABLE users ALTER COLUMN first_name TYPE VARCHAR(255) COLLATE "en_US.UTF-8";
	EXCEPTION WHEN undefined_object THEN
		-- Collation not present; leave column unchanged
		RAISE NOTICE 'Collation en_US.UTF-8 not available; skipping ALTER on users.first_name';
	END;

	BEGIN
		ALTER TABLE users ALTER COLUMN last_name TYPE VARCHAR(255) COLLATE "en_US.UTF-8";
	EXCEPTION WHEN undefined_object THEN
		RAISE NOTICE 'Collation en_US.UTF-8 not available; skipping ALTER on users.last_name';
	END;

	BEGIN
		ALTER TABLE solvers ALTER COLUMN name TYPE VARCHAR(255) COLLATE "en_US.UTF-8";
	EXCEPTION WHEN undefined_object THEN
		RAISE NOTICE 'Collation en_US.UTF-8 not available; skipping ALTER on solvers.name';
	END;
END;
$$;
CREATE INDEX idx_jobpair_stage_data_status_code ON jobpair_stage_data(status_code);

-- this table stores, for every user, the difference in time
-- between that user's job pair wallclock timeouts and actual
-- runtime. This data is used by the JobManager for scheduling pairs
-- among different users. This data is stored only temporarily between calls
-- to submitJobs. It is completely wiped out after each call,
-- so data here is generally stored for only about 30 seconds
-- at a time.
CREATE TABLE jobpair_time_delta (
	user_id INT NOT NULL,
	queue_id INT NOT NULL,
	time_delta INT DEFAULT 0,
	PRIMARY KEY (user_id, queue_id),
	CONSTRAINT jobpair_time_delta_queue_id FOREIGN KEY (queue_id) REFERENCES queues(id) ON DELETE CASCADE,
	CONSTRAINT jobpair_time_delta_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Stores all inputs to a particular job pair, outside of the primary benchmark
CREATE TABLE jobpair_inputs (
	jobpair_id INT NOT NULL,
	input_number SMALLINT NOT NULL, -- ordered from 1 to n, with n being the number of inputs
	bench_id INT NOT NULL,
	PRIMARY KEY (jobpair_id,input_number),
	CONSTRAINT jobpair_inputs_jobpair_id FOREIGN KEY (jobpair_id) REFERENCES job_pairs(id) ON DELETE CASCADE,
	CONSTRAINT jobpair_inputs_bench_id FOREIGN KEY (bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE
);

-- Stores the IDs of completed jobs and gives each a completion ID, indicating order of completion
-- We cannot use job_pairs.end_time to simulate this table, as it is possible to have job pairs finish at the same time
CREATE TABLE job_pair_completion (
	pair_id INT NOT NULL,
	completion_id SERIAL NOT NULL,
	PRIMARY KEY (completion_id),
	UNIQUE (pair_id),
	CONSTRAINT job_pair_completion_pair_id FOREIGN KEY (pair_id) REFERENCES job_pairs(id) ON DELETE CASCADE
);

-- All attributes for each job pair
CREATE TABLE job_attributes (
	pair_id INT NOT NULL,
	attr_key VARCHAR(128) NOT NULL,
	attr_value VARCHAR(128) NOT NULL,
	job_id INT NOT NULL,
	stage_number INT NOT NULL,
    PRIMARY KEY (pair_id,stage_number, attr_key),
	CONSTRAINT job_attributes_pair_id FOREIGN KEY (pair_id) REFERENCES job_pairs(id) ON DELETE CASCADE
);

-- Indexes for job_attributes table
CREATE INDEX idx_job_attributes_job_id ON job_attributes(job_id);

-- The table that keeps track of verification codes that should
-- be redeemed when the user verifies their e-mail address
CREATE TABLE verify (
	user_id INT NOT NULL,
	code VARCHAR(36) NOT NULL,
	created TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, code),
	UNIQUE (user_id),
	UNIQUE (code),
	CONSTRAINT verify_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Websites which are associated with either a space, solver or user.
-- It only makes sense to have one of the three id's set to a value,
-- they're all included for convenience though so we don't have to
-- have 3 redundant tables
CREATE TABLE website (
	id SERIAL NOT NULL,
	space_id INT,
	user_id INT,
	solver_id INT,
	url TEXT NOT NULL,
	name VARCHAR(64),
	PRIMARY KEY (id),
	CONSTRAINT website_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT website_solver_id FOREIGN KEY (solver_id) REFERENCES solvers(id) ON DELETE CASCADE,
	CONSTRAINT website_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Which user belongs to which space.
CREATE TABLE user_assoc (
	user_id INT NOT NULL,
	space_id INT NOT NULL,
	permission INT,
	PRIMARY KEY (user_id, space_id),
	CONSTRAINT user_assoc_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT user_assoc_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
	CONSTRAINT user_assoc_permission FOREIGN KEY (permission) REFERENCES permissions(id) ON DELETE SET NULL
);

-- Which spaces exists within another space
CREATE TABLE set_assoc (
	space_id INT NOT NULL,
	child_id INT NOT NULL,
	PRIMARY KEY (space_id, child_id),
	CONSTRAINT set_assoc_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT set_assoc_child_id FOREIGN KEY (child_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- Which benchmarks belong to which spaces
CREATE TABLE bench_assoc (
	space_id INT NOT NULL,
	bench_id INT NOT NULL,
	order_id SERIAL UNIQUE,
	PRIMARY KEY (space_id, bench_id),
	CONSTRAINT bench_assoc_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT bench_assoc_bench_id FOREIGN KEY (bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE
);

-- Which jobs belong to which spaces
CREATE TABLE job_assoc (
	space_id INT NOT NULL,
	job_id INT NOT NULL,
	PRIMARY KEY (space_id, job_id),
	CONSTRAINT job_assoc_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT job_assoc_job_id FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

-- Which solvers belong to which spaces
CREATE TABLE solver_assoc (
	space_id INT NOT NULL,
	solver_id INT NOT NULL,
	PRIMARY KEY (space_id, solver_id),
	CONSTRAINT solver_assoc_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT solver_assoc_solver_id FOREIGN KEY (solver_id) REFERENCES solvers(id) ON DELETE CASCADE
);

-- Pending requests to join a community
-- Author: Todd Elvers
CREATE TABLE community_requests (
	user_id INT NOT NULL,
	community INT NOT NULL,
	code VARCHAR(36) NOT NULL,
	message TEXT NOT NULL,
	created TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, community),
	UNIQUE (code),
	CONSTRAINT community_requests_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
	CONSTRAINT community_requests_community FOREIGN KEY (community) REFERENCES spaces(id) ON DELETE CASCADE
);

CREATE TABLE anonymous_links (
	unique_id VARCHAR(36) NOT NULL,
	primitive_id INT NOT NULL,
	primitive_type VARCHAR(10) NOT NULL CHECK (primitive_type IN ('solver', 'job', 'bench')),
	primitives_to_anonymize VARCHAR(12) NOT NULL CHECK (primitives_to_anonymize IN ('all', 'allButBench', 'none')),
	date_created DATE NOT NULL,

	PRIMARY KEY (unique_id),
	UNIQUE (primitive_id, primitive_type, primitives_to_anonymize)
);

CREATE TABLE anonymous_primitive_names (
	anonymous_name VARCHAR(36) NOT NULL,
	primitive_id INT NOT NULL,
	primitive_type VARCHAR(10) NOT NULL CHECK (primitive_type IN ('solver', 'job', 'bench', 'config')),
	job_id INT NOT NULL,

	PRIMARY KEY (primitive_id, primitive_type, job_id),
	CONSTRAINT anonymous_names_job_id FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

CREATE TABLE change_email_requests (
	user_id INT NOT NULL,
	new_email VARCHAR(64) NOT NULL,
	code VARCHAR(36) NOT NULL,
	PRIMARY KEY (user_id),
	UNIQUE (code),
	CONSTRAINT change_email_request_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Pending requests to reset a user's password
-- Author: Todd Elvers
CREATE TABLE pass_reset_request (
	user_id INT NOT NULL,
	code VARCHAR(36) NOT NULL,
	created TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, code),
	UNIQUE (user_id),
	UNIQUE (code),
	CONSTRAINT pass_reset_request_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Benchmark dependencies - e.g. a benchmark may reference other benchmarks such as axioms
-- Author: Benton McCune
CREATE TABLE bench_dependency (
	id SERIAL NOT NULL,
	primary_bench_id INT NOT NULL,
	secondary_bench_id INT NOT NULL,
	include_path TEXT NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT bench_dependency_primary_bench_id FOREIGN KEY (primary_bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE,
	CONSTRAINT bench_dependency_secondary_bench_id FOREIGN KEY (secondary_bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE
);


-- Default settings for a community space.
-- Author: Ruoyu Zhang + Eric Burns
CREATE TABLE default_settings (
	id SERIAL NOT NULL, -- unique ID
	prim_id INT, -- either a user ID or community ID depending on what the setting_type is
	post_processor INT,
	pre_processor INT,
	cpu_timeout INT DEFAULT 1,
	clock_timeout INT DEFAULT 1,
	dependencies_enabled BOOLEAN DEFAULT FALSE,
	maximum_memory BIGINT DEFAULT 1073741824,
	default_benchmark INT DEFAULT NULL,
	default_solver INT DEFAULT NULL,
	benchmarking_framework VARCHAR(20) NOT NULL DEFAULT 'RUNSOLVER' CHECK (benchmarking_framework IN ('RUNSOLVER', 'BENCHEXEC')),
	bench_processor INT,
	setting_type INT DEFAULT 1,
	name VARCHAR(32) DEFAULT 'settings',
	PRIMARY KEY (id),
	CONSTRAINT default_settings_post_processor FOREIGN KEY (post_processor) REFERENCES processors(id) ON DELETE SET NULL,
	CONSTRAINT default_settings_default_benchmark FOREIGN KEY (default_benchmark) REFERENCES benchmarks(id) ON DELETE SET NULL,
	CONSTRAINT default_settings_pre_processor FOREIGN KEY (pre_processor) REFERENCES processors(id) ON DELETE SET NULL,
	CONSTRAINT default_settings_default_solver FOREIGN KEY (default_solver) REFERENCES solvers(id) ON DELETE SET NULL,
	CONSTRAINT default_settings_bench_processor FOREIGN KEY (bench_processor) REFERENCES processors(id) ON DELETE SET NULL
);

CREATE TABLE default_bench_assoc(
	setting_id INT NOT NULL,
	bench_id INT NOT NULL,
	PRIMARY KEY(setting_id, bench_id),
	CONSTRAINT default_setting_id FOREIGN KEY (setting_id) REFERENCES default_settings(id) ON DELETE CASCADE,
	CONSTRAINT default_bench_id FOREIGN KEY (bench_id) REFERENCES benchmarks(id) ON DELETE CASCADE
);

-- For Status Updates on a space XML upload
-- Author: Eric Burns
CREATE TABLE space_xml_uploads (
	id SERIAL NOT NULL,
	user_id INT NOT NULL,
	upload_time TIMESTAMP NOT NULL,
	file_upload_complete BOOLEAN DEFAULT FALSE,
	everything_complete BOOLEAN DEFAULT FALSE,
	total_spaces INT DEFAULT 0,
	completed_spaces INT DEFAULT 0,
	total_benchmarks INT DEFAULT 0,
	completed_benchmarks INT DEFAULT 0,
	total_solvers INT DEFAULT 0,
	completed_solvers INT DEFAULT 0,
	total_updates INT DEFAULT 0,
	completed_updates INT DEFAULT 0,
	error_message TEXT,
	PRIMARY KEY (id),
	CONSTRAINT space_xml_uploads_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- For Status Updates on a Benchmark upload
-- Author: Benton McCune
CREATE TABLE benchmark_uploads (
	id SERIAL NOT NULL,
	space_id INT NOT NULL,
	user_id INT NOT NULL,
	upload_time TIMESTAMP NOT NULL,
	file_upload_complete BOOLEAN DEFAULT FALSE,
	file_extraction_complete BOOLEAN DEFAULT FALSE,
	processing_begun BOOLEAN DEFAULT FALSE,
	everything_complete BOOLEAN DEFAULT FALSE,
	total_spaces INT DEFAULT 0,
	total_benchmarks INT DEFAULT 0,
	validated_benchmarks INT DEFAULT 0,
	failed_benchmarks INT DEFAULT 0,
	completed_benchmarks INT DEFAULT 0,
	completed_spaces INT DEFAULT 0,
	error_message TEXT,
	PRIMARY KEY (id),
	CONSTRAINT benchmark_uploads_space_id FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE,
	CONSTRAINT benchmark_uploads_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- For benchmarks that fail validation
-- Author: Benton McCune
CREATE TABLE unvalidated_benchmarks (
	id SERIAL NOT NULL,
	status_id INT REFERENCES benchmark_uploads(id) ON DELETE CASCADE,
	bench_name VARCHAR(256) NOT NULL,
	error_message TEXT,
	PRIMARY KEY (id)
);

-- Saves all job space information
-- Author: Eric Burns
CREATE TABLE job_spaces (
	id SERIAL NOT NULL,
	job_id INT,
	name VARCHAR(255),
	max_stages INT DEFAULT 1, -- This columns stores the maximum number of stages any job pair has
							  -- anywhere in the job space hierarchy rooted at this job space
	PRIMARY KEY (id),
	CONSTRAINT job_spaces_job_id FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

-- The set of all associations between each job space and it's descendants
CREATE TABLE job_space_closure (
	ancestor INT NOT NULL,
	descendant INT NOT NULL,
	last_used TIMESTAMP NOT NULL,
	UNIQUE (ancestor, descendant),
	CONSTRAINT job_space_ancestor FOREIGN KEY (ancestor) REFERENCES job_spaces(id) ON DELETE CASCADE,
	CONSTRAINT job_space_descendant FOREIGN KEY (descendant) REFERENCES job_spaces(id) ON DELETE CASCADE
);

-- Saves associations between spaces relevant to a particular job
-- Author: Eric Burns
CREATE TABLE job_space_assoc (
	space_id INT NOT NULL,
	child_id INT NOT NULL,
	CONSTRAINT job_space_assoc_space_id FOREIGN KEY (space_id) REFERENCES job_spaces(id) ON DELETE CASCADE,
	CONSTRAINT job_space_assoc_child_id FOREIGN KEY (child_id) REFERENCES job_spaces(id) ON DELETE CASCADE
);

-- Stores a cache of stats for a particular job space. Incomplete pairs are not stored,
-- as we only store complete jobs, so incomplete=failed. Stats are hierarchical,
-- so stats at a particular job space include all pairs below that job space
-- Author: Eric Burns
CREATE TABLE job_stats (
	job_space_id INT NOT NULL,
	config_id INT NOT NULL,
	complete INT NOT NULL,
	correct INT NOT NULL,
	incorrect INT NOT NULL,
	incomplete INT NOT NULL,
	conflicts INT NOT NULL,
	failed INT NOT NULL,
	wallclock DOUBLE PRECISION,
	cpu DOUBLE PRECISION,
	resource_out INT NOT NULL,
	stage_number INT NOT NULL DEFAULT 0, -- what stage is this? from 1...n, with 0 meaning the primary stage
	PRIMARY KEY (job_space_id,config_id,stage_number),
	CONSTRAINT job_stats_job_space_id FOREIGN KEY (job_space_id) REFERENCES job_spaces(id) ON DELETE CASCADE
);

-- Indexes for job_stats table
CREATE INDEX idx_job_stats_config_id ON job_stats(config_id);

-- Table that contains some global flags
--  * Minor version is incremented on each change
--  * Major version is only incremented when a change may require manual
--      intervention and cannot be applied automatically
-- Author: Wyatt Kaiser
CREATE TABLE system_flags (
	integrity_keeper VARCHAR(1) NOT NULL CHECK (integrity_keeper = ''),
	paused BOOLEAN DEFAULT FALSE,
	test_queue INT,
	major_version BIGINT,
	minor_version BIGINT,
	read_only BOOLEAN NOT NULL DEFAULT FALSE,
	PRIMARY KEY (integrity_keeper),
	CONSTRAINT system_flags_test_queue FOREIGN KEY (test_queue) REFERENCES queues(id) ON DELETE SET NULL
);

-- table for storing statistics for the weekly report
CREATE TABLE report_data (
	id SERIAL NOT NULL,
	event_name VARCHAR(64),
	queue_name VARCHAR(64), -- NULL if data is not associated with a queue
	occurrences INT NOT NULL,
	PRIMARY KEY(id),
	UNIQUE (event_name, queue_name)
);

-- Pairs that have been rerun after job script failure.
CREATE TABLE pairs_rerun (
	pair_id INT NOT NULL,
	PRIMARY KEY (pair_id),
	CONSTRAINT id_of_rerun_pair FOREIGN KEY (pair_id) REFERENCES job_pairs(id) ON DELETE CASCADE
);

CREATE TABLE log_levels(
	id SERIAL NOT NULL,
	name VARCHAR(32) NOT NULL,
	PRIMARY KEY(id),
	UNIQUE(name)
);

INSERT INTO log_levels (name) VALUES ('OFF'),('FATAL'),('ERROR'),('WARN'),('INFO'),('DEBUG'),('TRACE'),('ALL');

CREATE TABLE error_logs(
	id SERIAL NOT NULL,
	message TEXT NOT NULL,
	time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	log_level_id INT,

	PRIMARY KEY(id),
	CONSTRAINT error_level FOREIGN KEY (log_level_id) REFERENCES log_levels(id) ON DELETE SET NULL
);

-- Creates a view of the closure table that includes only communities as ancestors
CREATE VIEW community_assoc AS
SELECT ancestor AS comm_id, descendant AS space_id FROM closure
JOIN set_assoc ON set_assoc.child_id=closure.ancestor
WHERE set_assoc.space_id=1;

ALTER TABLE solver_pipelines ADD CONSTRAINT primary_stage_id FOREIGN KEY (primary_stage_id) REFERENCES pipeline_stages(stage_id) ON DELETE SET NULL;

ALTER TABLE users ADD CONSTRAINT users_default_settings_profile FOREIGN KEY (default_settings_profile) REFERENCES default_settings(id) ON DELETE SET NULL;

INSERT INTO report_data (event_name, queue_name, occurrences) VALUES ('unique logins', NULL, 0), ('jobs initiated', NULL, 0),
	('job pairs run', NULL, 0), ('solvers uploaded', NULL, 0), ('benchmarks uploaded', NULL, 0), ('benchmark archives uploaded', NULL, 0);

-- insert no_type processor, which the system does not expect actually exists on disk. This is mandatory for
-- the system to function.
INSERT INTO processors (id,name,description,path,community,processor_type,disk_size)
VALUES (1,'no_type', 'this is the default benchmark type for rejected benchmarks and benchmarks that are not associated with a type n=no_type','no path',1,3,0);

-- Contains all events that can be logged as analytics
CREATE TABLE analytics_events (
	event_id SERIAL NOT NULL,
	name CHAR(32) NOT NULL,
	PRIMARY KEY (event_id),
	UNIQUE (name)
);

-- A list of all events
INSERT INTO analytics_events (name) VALUES
	('JOB_ATTRIBUTES'),
	('JOB_CREATE'),
	('JOB_CREATE_QUICKJOB'),
	('JOB_DETAILS'),
	('JOB_PAUSE'),
	('JOB_RESUME'),
	('PAGEVIEW_HELP'),
	('STAREXEC_DEPLOY'),
	('STAREXECCOMMAND_LOGIN');

-- Contains historical analytics data:
--  * number of times an event was recorded on a particular date
CREATE TABLE analytics_historical (
	event_id INT NOT NULL,
	date_recorded DATE NOT NULL,
	count INT NOT NULL DEFAULT 0,
	PRIMARY KEY (event_id, date_recorded),
	CONSTRAINT id_assoc FOREIGN KEY (event_id) REFERENCES analytics_events(event_id)
);

-- Table to keep track of how many Users trigger an Event
CREATE TABLE analytics_users (
	event_id INT NOT NULL,
	date_recorded DATE NOT NULL,
	user_id INT NOT NULL,
	PRIMARY KEY (event_id, date_recorded, user_id),
	CONSTRAINT analytics_users_to_event FOREIGN KEY (event_id) REFERENCES analytics_events(event_id),
	CONSTRAINT analytics_users_to_users FOREIGN KEY (user_id)  REFERENCES users(id)
);

-- Users can subscribe to be notified when a Job status changes
-- Here we record the Job, User, and the last seen status
-- A periodic task will check the Job current status against the status recorded
-- here, and alert the User if status has changed
CREATE TABLE notifications_jobs_users (
	job_id  INT NOT NULL,
	user_id INT NOT NULL,
	last_seen_status
		VARCHAR(20) NOT NULL CHECK (last_seen_status IN ('RUNNING', 'PROCESSING', 'COMPLETE', 'DELETED', 'KILLED', 'PAUSED', 'GLOBAL_PAUSE')),
	PRIMARY KEY (job_id, user_id),
	CONSTRAINT notifications_jobs_users_to_jobs  FOREIGN KEY (job_id)   REFERENCES jobs(id)  ON DELETE CASCADE,
	CONSTRAINT notifications_jobs_users_to_users FOREIGN KEY (user_id)  REFERENCES users(id) ON DELETE CASCADE
);
