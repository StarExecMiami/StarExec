-- Schema Change: Modify timestamp columns and add Python API event
-- Modify timestamp columns so they are not updated automatically
-- Add Python API event

UPDATE system_flags SET minor_version=5;

ALTER TABLE users              CHANGE COLUMN created     created     TIMESTAMP DEFAULT NOW();
ALTER TABLE spaces             CHANGE COLUMN created     created     TIMESTAMP DEFAULT NOW();
ALTER TABLE benchmarks         CHANGE COLUMN uploaded    uploaded    TIMESTAMP DEFAULT NOW();
ALTER TABLE solvers            CHANGE COLUMN uploaded    uploaded    TIMESTAMP DEFAULT NOW();
ALTER TABLE solver_pipelines   CHANGE COLUMN uploaded    uploaded    TIMESTAMP DEFAULT NOW();
-- ALTER TABLE jobs               CHANGE COLUMN completed   completed   TIMESTAMP DEFAULT NOW();
ALTER TABLE verify             CHANGE COLUMN created     created     TIMESTAMP DEFAULT NOW();
ALTER TABLE community_requests CHANGE COLUMN created     created     TIMESTAMP DEFAULT NOW();
ALTER TABLE pass_reset_request CHANGE COLUMN created     created     TIMESTAMP DEFAULT NOW();
ALTER TABLE benchmark_uploads  CHANGE COLUMN upload_time upload_time TIMESTAMP DEFAULT NOW();

INSERT INTO analytics_events (name) VALUES ('PYTHON_API_LOGIN');