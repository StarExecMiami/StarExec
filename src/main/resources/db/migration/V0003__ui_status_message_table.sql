-- Create UI status message table if it does not exist and seed default row
CREATE TABLE IF NOT EXISTS ui_status_message (
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    message TEXT,
    url     TEXT
);

-- Ensure a single row exists
INSERT INTO ui_status_message (enabled)
SELECT FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ui_status_message);
