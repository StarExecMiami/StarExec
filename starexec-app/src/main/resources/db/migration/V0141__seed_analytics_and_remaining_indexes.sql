-- V0141: Seed Analytics event definitions and add remaining production indexes.
--
-- Production telemetry showed starexec.analytics_events had 0 rows (causing every Analytics
-- enum constant to resolve id = -1 and log 10 errors per startup) and queue_metrics_history
-- performed thousands of full sequential scans on recorded_at during hourly pruning.

INSERT INTO starexec.analytics_events (name) VALUES
    ('JOB_ATTRIBUTES'),
    ('JOB_CREATE'),
    ('JOB_CREATE_QUICKJOB'),
    ('JOB_DETAILS'),
    ('JOB_PAUSE'),
    ('JOB_RESUME'),
    ('PAGEVIEW_HELP'),
    ('PYTHON_API_LOGIN'),
    ('STAREXEC_DEPLOY'),
    ('STAREXECCOMMAND_LOGIN')
ON CONFLICT (name) DO NOTHING;

-- Hourly pruning deletes WHERE recorded_at < NOW() - (? * INTERVAL '1 hour')
CREATE INDEX IF NOT EXISTS idx_queue_metrics_recorded_at
    ON starexec.queue_metrics_history(recorded_at);

-- Remaining foreign keys with live production rows omitted from V0140
CREATE INDEX IF NOT EXISTS idx_error_logs_log_level_id
    ON starexec.error_logs(log_level_id);

CREATE INDEX IF NOT EXISTS idx_solvers_executable_type
    ON starexec.solvers(executable_type);

CREATE INDEX IF NOT EXISTS idx_runscript_errors_node_id
    ON starexec.runscript_errors(node_id);

CREATE INDEX IF NOT EXISTS idx_processors_syntax_id
    ON starexec.processors(syntax_id);
