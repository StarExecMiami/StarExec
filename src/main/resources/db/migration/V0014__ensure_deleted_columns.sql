-- Ensure deleted/config_deleted columns exist and are INT-backed to match
-- application expectations. This migration is idempotent and will convert
-- boolean columns (from older baseline imports) to integer 0/1 where needed.
DO $$
BEGIN
    -- configurations.deleted -> INT DEFAULT 0
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'configurations' AND column_name = 'deleted'
    ) THEN
        ALTER TABLE configurations ADD COLUMN deleted INT DEFAULT 0;
    ELSE
        -- If the column exists but is boolean, convert it to INT safely
        PERFORM 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'configurations' AND column_name = 'deleted' AND data_type = 'boolean';
        IF FOUND THEN
            -- Convert boolean -> int preserving values
            EXECUTE 'ALTER TABLE configurations ALTER COLUMN deleted TYPE INT USING CASE WHEN deleted THEN 1 ELSE 0 END';
        END IF;
    END IF;

    -- solvers.config_deleted -> INT DEFAULT 0
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'solvers' AND column_name = 'config_deleted'
    ) THEN
        ALTER TABLE solvers ADD COLUMN config_deleted INT DEFAULT 0;
    ELSE
        PERFORM 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'solvers' AND column_name = 'config_deleted' AND data_type = 'boolean';
        IF FOUND THEN
            EXECUTE 'ALTER TABLE solvers ALTER COLUMN config_deleted TYPE INT USING CASE WHEN config_deleted THEN 1 ELSE 0 END';
        END IF;
    END IF;
END
$$ LANGUAGE plpgsql;
