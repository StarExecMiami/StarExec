-- V0140: Add B-tree indexes on foreign keys and retention-lookup columns traversed during
-- user deletion (DeleteUser), space removal (RemoveSubspace), and orphaned deleted-solver
-- cleanup (cleanOrphanedDeletedSolvers).
--
-- Without these indexes, CASCADE / SET NULL / NOT EXISTS checks trigger full sequential scans
-- across multi-million-row child tables (jobpair_stage_data, bench_dependency, benchmarks,
-- bench_assoc) inside the transaction, exceeding the 30s JDBC socket timeout and leaving
-- LockUserJobLifecycle held on the PostgreSQL backend.
--
-- Regular CREATE INDEX IF NOT EXISTS is required here (never CONCURRENTLY inside Flyway).

-- High-volume tables (multi-hundred-thousand to multi-million rows)
CREATE INDEX IF NOT EXISTS idx_jobpair_stage_data_stage_id
    ON starexec.jobpair_stage_data(stage_id);

CREATE INDEX IF NOT EXISTS idx_jobpair_stage_data_solver_id
    ON starexec.jobpair_stage_data(solver_id);

CREATE INDEX IF NOT EXISTS idx_bench_dependency_secondary_bench_id
    ON starexec.bench_dependency(secondary_bench_id);

CREATE INDEX IF NOT EXISTS idx_benchmarks_bench_type
    ON starexec.benchmarks(bench_type);

CREATE INDEX IF NOT EXISTS idx_bench_assoc_bench_id
    ON starexec.bench_assoc(bench_id);

-- Mid- and small-volume cascade and retention tables under users, solvers, spaces, jobs, and processors
CREATE INDEX IF NOT EXISTS idx_closure_descendant
    ON starexec.closure(descendant);

CREATE INDEX IF NOT EXISTS idx_job_space_assoc_space_id
    ON starexec.job_space_assoc(space_id);

CREATE INDEX IF NOT EXISTS idx_job_space_assoc_child_id
    ON starexec.job_space_assoc(child_id);

CREATE INDEX IF NOT EXISTS idx_configurations_user_id
    ON starexec.configurations(user_id);

CREATE INDEX IF NOT EXISTS idx_solver_assoc_solver_id
    ON starexec.solver_assoc(solver_id);

CREATE INDEX IF NOT EXISTS idx_jobpair_inputs_bench_id
    ON starexec.jobpair_inputs(bench_id);

CREATE INDEX IF NOT EXISTS idx_job_space_closure_descendant
    ON starexec.job_space_closure(descendant);

CREATE INDEX IF NOT EXISTS idx_spaces_default_permission
    ON starexec.spaces(default_permission);

CREATE INDEX IF NOT EXISTS idx_user_assoc_space_id
    ON starexec.user_assoc(space_id);

CREATE INDEX IF NOT EXISTS idx_user_assoc_permission
    ON starexec.user_assoc(permission);

CREATE INDEX IF NOT EXISTS idx_set_assoc_child_id
    ON starexec.set_assoc(child_id);

CREATE INDEX IF NOT EXISTS idx_pipeline_stages_config_id
    ON starexec.pipeline_stages(config_id);

CREATE INDEX IF NOT EXISTS idx_pipeline_stages_pipeline_id
    ON starexec.pipeline_stages(pipeline_id);

CREATE INDEX IF NOT EXISTS idx_solvers_build_output_artifact_id
    ON starexec.solvers(build_output_artifact_id);

CREATE INDEX IF NOT EXISTS idx_job_stage_params_space_id
    ON starexec.job_stage_params(space_id);

CREATE INDEX IF NOT EXISTS idx_job_stage_params_pre_processor
    ON starexec.job_stage_params(pre_processor);

CREATE INDEX IF NOT EXISTS idx_job_stage_params_post_processor
    ON starexec.job_stage_params(post_processor);

CREATE INDEX IF NOT EXISTS idx_solver_pipelines_primary_stage_id
    ON starexec.solver_pipelines(primary_stage_id);

CREATE INDEX IF NOT EXISTS idx_solver_pipelines_user_id
    ON starexec.solver_pipelines(user_id);

CREATE INDEX IF NOT EXISTS idx_runscript_errors_job_pair_id
    ON starexec.runscript_errors(job_pair_id);

CREATE INDEX IF NOT EXISTS idx_job_assoc_job_id
    ON starexec.job_assoc(job_id);

CREATE INDEX IF NOT EXISTS idx_users_default_settings_profile
    ON starexec.users(default_settings_profile);

CREATE INDEX IF NOT EXISTS idx_processors_community
    ON starexec.processors(community);

CREATE INDEX IF NOT EXISTS idx_analytics_users_user_id
    ON starexec.analytics_users(user_id);

CREATE INDEX IF NOT EXISTS idx_notifications_jobs_users_user_id
    ON starexec.notifications_jobs_users(user_id);

CREATE INDEX IF NOT EXISTS idx_anonymous_primitive_names_job_id
    ON starexec.anonymous_primitive_names(job_id);

CREATE INDEX IF NOT EXISTS idx_website_user_id
    ON starexec.website(user_id);

CREATE INDEX IF NOT EXISTS idx_website_space_id
    ON starexec.website(space_id);

CREATE INDEX IF NOT EXISTS idx_website_solver_id
    ON starexec.website(solver_id);

CREATE INDEX IF NOT EXISTS idx_community_requests_community
    ON starexec.community_requests(community);

CREATE INDEX IF NOT EXISTS idx_default_bench_assoc_bench_id
    ON starexec.default_bench_assoc(bench_id);

CREATE INDEX IF NOT EXISTS idx_default_settings_bench_processor
    ON starexec.default_settings(bench_processor);

CREATE INDEX IF NOT EXISTS idx_default_settings_default_benchmark
    ON starexec.default_settings(default_benchmark);

CREATE INDEX IF NOT EXISTS idx_default_settings_default_solver
    ON starexec.default_settings(default_solver);

CREATE INDEX IF NOT EXISTS idx_default_settings_pre_processor
    ON starexec.default_settings(pre_processor);

CREATE INDEX IF NOT EXISTS idx_default_settings_post_processor
    ON starexec.default_settings(post_processor);

CREATE INDEX IF NOT EXISTS idx_benchmark_uploads_user_id
    ON starexec.benchmark_uploads(user_id);

CREATE INDEX IF NOT EXISTS idx_benchmark_uploads_space_id
    ON starexec.benchmark_uploads(space_id);

CREATE INDEX IF NOT EXISTS idx_unvalidated_benchmarks_status_id
    ON starexec.unvalidated_benchmarks(status_id);

CREATE INDEX IF NOT EXISTS idx_space_xml_uploads_user_id
    ON starexec.space_xml_uploads(user_id);

CREATE INDEX IF NOT EXISTS idx_upload_sessions_space_id
    ON starexec.upload_sessions(space_id);

CREATE INDEX IF NOT EXISTS idx_upload_sessions_dep_root_space_id
    ON starexec.upload_sessions(dep_root_space_id);

CREATE INDEX IF NOT EXISTS idx_upload_jobs_upload_session_id
    ON starexec.upload_jobs(upload_session_id);
