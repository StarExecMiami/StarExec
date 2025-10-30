-- Schema Change: Add description column to queues table
-- This adds the descriptions column. This implements the
-- requirements for ticket #326.
-- Author: aguo2
-- Made idempotent: skips ALTER if column already exists.

-- Add 'description' column if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
            AND table_name = 'queues'
            AND column_name = 'description'
    ) THEN
        ALTER TABLE queues ADD COLUMN description VARCHAR(200);
    END IF;
END $$;
