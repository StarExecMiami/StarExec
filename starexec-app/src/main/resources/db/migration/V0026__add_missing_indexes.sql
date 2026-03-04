-- V0026: Missing performance indexes identified in the audit.
--
-- Note: Using regular CREATE INDEX (not CONCURRENTLY) so this migration can
-- run inside Flyway's transaction. CONCURRENTLY is incompatible with Flyway's
-- PostgreSQL advisory lock, which holds an open transaction on a separate
-- connection — causing CREATE INDEX CONCURRENTLY to deadlock indefinitely
-- (it must wait for all active virtual transactions to finish, including the
-- advisory lock's transaction, which in turn waits for the migration to
-- complete).
--
-- Since these indexes are created on a freshly initialised schema (no live
-- traffic), a brief table lock during index build has no practical impact.
--
-- IF NOT EXISTS makes every statement idempotent.

-- job_pairs: the most-queried table — filtering by status_code and job_id
-- is in every hot path.
CREATE INDEX IF NOT EXISTS idx_job_pairs_job_id
    ON starexec.job_pairs(job_id);

CREATE INDEX IF NOT EXISTS idx_job_pairs_status
    ON starexec.job_pairs(status_code);

-- jobs: common filters are deleted/paused/killed flags and queue/user lookups
CREATE INDEX IF NOT EXISTS idx_jobs_status
    ON starexec.jobs(status_code);

CREATE INDEX IF NOT EXISTS idx_jobs_user_id
    ON starexec.jobs(user_id);

CREATE INDEX IF NOT EXISTS idx_jobs_queue_id
    ON starexec.jobs(queue_id);

CREATE INDEX IF NOT EXISTS idx_jobs_deleted
    ON starexec.jobs(deleted);

CREATE INDEX IF NOT EXISTS idx_jobs_paused
    ON starexec.jobs(paused);

-- solvers: user_id is queried in getByOwner lookups
-- Note: solvers are linked to spaces via the solver_assoc junction table,
-- not via a direct space_id column, so no space-based index is needed here.
CREATE INDEX IF NOT EXISTS idx_solvers_user_id
    ON starexec.solvers(user_id);

-- configurations: solver_id is the FK used in N+1 getByConfigId loops
CREATE INDEX IF NOT EXISTS idx_configurations_solver_id
    ON starexec.configurations(solver_id);

-- benchmarks: user_id is hit heavily by getByOwner queries
CREATE INDEX IF NOT EXISTS idx_benchmarks_user_id
    ON starexec.benchmarks(user_id);

-- logins: user_id is needed for login history queries
CREATE INDEX IF NOT EXISTS idx_logins_user_id
    ON starexec.logins(user_id);

-- job_spaces: job_id FK is queried in GetJobSubSpaces and hierarchy traversal
CREATE INDEX IF NOT EXISTS idx_job_spaces_job_id
    ON starexec.job_spaces(job_id);

-- jobpair_stage_data: jobpair_id FK is the primary join key
CREATE INDEX IF NOT EXISTS idx_jobpair_stage_data_jobpair_id
    ON starexec.jobpair_stage_data(jobpair_id);
