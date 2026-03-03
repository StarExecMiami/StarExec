-- flyway:executeInTransaction=false
--
-- V0026: Missing performance indexes identified in the audit.
--
-- CREATE INDEX CONCURRENTLY cannot run inside a transaction block (PostgreSQL
-- restriction).  This file is annotated so that Flyway 7+ skips the implicit
-- transaction wrapper for this migration only.
--
-- IF NOT EXISTS makes every statement idempotent: re-running the migration
-- after a partial failure will not raise errors for indexes that already exist.

-- job_pairs: the most-queried table — filtering by status_code and job_id
-- is in every hot path.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_pairs_job_id
    ON starexec.job_pairs(job_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_pairs_status
    ON starexec.job_pairs(status_code);

-- jobs: common filters are deleted/paused/killed flags and queue/user lookups
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_status
    ON starexec.jobs(status_code);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_user_id
    ON starexec.jobs(user_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_queue_id
    ON starexec.jobs(queue_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_deleted
    ON starexec.jobs(deleted);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_paused
    ON starexec.jobs(paused);

-- solvers: solver_id is used in join paths for config lookups
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_solvers_space
    ON starexec.solvers(space_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_solvers_user_id
    ON starexec.solvers(user_id);

-- configurations: solver_id is the FK used in N+1 getByConfigId loops
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_configurations_solver_id
    ON starexec.configurations(solver_id);

-- benchmarks: user_id is hit heavily by getByOwner queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_benchmarks_user_id
    ON starexec.benchmarks(user_id);

-- logins: user_id is needed for login history queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_logins_user_id
    ON starexec.logins(user_id);

-- job_spaces: job_id FK is queried in GetJobSubSpaces and hierarchy traversal
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_spaces_job_id
    ON starexec.job_spaces(job_id);

-- jobpair_stage_data: jobpair_id FK is the primary join key
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobpair_stage_data_jobpair_id
    ON starexec.jobpair_stage_data(jobpair_id);
