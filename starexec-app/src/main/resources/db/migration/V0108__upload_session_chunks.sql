-- V0108: Durable chunk index for resumable upload sessions

CREATE TABLE IF NOT EXISTS starexec.upload_session_chunks (
    session_id BIGINT NOT NULL REFERENCES starexec.upload_sessions(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    chunk_size INT NOT NULL CHECK (chunk_size > 0),
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_upload_session_chunks_session
    ON starexec.upload_session_chunks (session_id, chunk_index);

COMMENT ON TABLE starexec.upload_session_chunks IS 'Tracks persisted resumable upload chunks per session';
