-- Create ui_status_message table for system status messages
-- This table is used to display status messages in the UI footer

CREATE TABLE IF NOT EXISTS ui_status_message (
    integrity_keeper ENUM('') NOT NULL,
    enabled BOOLEAN DEFAULT FALSE,
    message TEXT,
    url TEXT,
    PRIMARY KEY (integrity_keeper)
);

-- Insert default row if it doesn't exist
INSERT IGNORE INTO ui_status_message (integrity_keeper, enabled, message, url) 
VALUES ('', false, NULL, NULL);
