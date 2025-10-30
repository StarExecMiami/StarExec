-- Schema Change: Add read_only mode system flag
-- This adds the read only mode, which disables job submission. This implements
-- the requirements as specified in ticket 353
-- Author: aguo2

-- Add 'read_only' column if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
            AND table_name = 'system_flags'
            AND column_name = 'read_only'
    ) THEN
        ALTER TABLE system_flags ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;