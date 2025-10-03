-- Schema Change: Add description column to queues table
-- This adds the descriptions column. This implements the
-- requirements for ticket #326.
-- Author: aguo2
-- Made idempotent: skips ALTER if column already exists.

-- Conditionally add 'description' column (works on MySQL versions without ADD COLUMN IF NOT EXISTS)
SET @stmt := (
  SELECT IF(
	EXISTS(
	  SELECT 1
	  FROM INFORMATION_SCHEMA.COLUMNS
	  WHERE TABLE_SCHEMA = DATABASE()
		AND TABLE_NAME = 'queues'
		AND COLUMN_NAME = 'description'
	),
	'SELECT 1',  -- no-op if column exists
	'ALTER TABLE queues ADD COLUMN description VARCHAR(200)'
  )
);

PREPARE add_col FROM @stmt;
EXECUTE add_col;
DEALLOCATE PREPARE add_col;
