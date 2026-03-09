CREATE TABLE queue_metrics_history (
    id SERIAL PRIMARY KEY,
    queue_id INTEGER NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    queue_size INTEGER NOT NULL,
    CONSTRAINT idx_queue_metrics_time UNIQUE (queue_id, recorded_at)
);

CREATE INDEX idx_queue_metrics_queue_time ON queue_metrics_history(queue_id, recorded_at DESC);
