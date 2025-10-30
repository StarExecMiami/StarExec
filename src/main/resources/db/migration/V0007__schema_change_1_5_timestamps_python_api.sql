-- Schema Change: Modify timestamp columns and add Python API event
-- Modify timestamp columns so they are not updated automatically
-- Add Python API event

UPDATE system_flags SET minor_version=5;

ALTER TABLE users              ALTER COLUMN created     SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE spaces             ALTER COLUMN created     SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE benchmarks         ALTER COLUMN uploaded    SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE solvers            ALTER COLUMN uploaded    SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE solver_pipelines   ALTER COLUMN uploaded    SET DEFAULT CURRENT_TIMESTAMP;
-- ALTER TABLE jobs               ALTER COLUMN completed   SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE verify             ALTER COLUMN created     SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE community_requests ALTER COLUMN created     SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE pass_reset_request ALTER COLUMN created     SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE benchmark_uploads  ALTER COLUMN upload_time SET DEFAULT CURRENT_TIMESTAMP;

INSERT INTO analytics_events (name) VALUES ('PYTHON_API_LOGIN');