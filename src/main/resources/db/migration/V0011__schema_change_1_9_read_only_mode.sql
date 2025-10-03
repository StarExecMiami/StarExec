-- Schema Change: Add read_only mode system flag
-- This adds the read only mode, which disables job submission. This implements
-- the requirements as specified in ticket 353
-- Author: aguo2

SET @stmt := (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'system_flags'
                AND COLUMN_NAME = 'read_only'
        ),
        'SELECT 1',  -- no-op if column exists
        'ALTER TABLE system_flags ADD COLUMN read_only VARCHAR(200) NOT NULL DEFAULT \'false\''
    )
);

PREPARE add_col FROM @stmt;
EXECUTE add_col;
DEALLOCATE PREPARE add_col;