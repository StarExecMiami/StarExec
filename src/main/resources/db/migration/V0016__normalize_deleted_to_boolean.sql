-- Migration: Normalize configurations.deleted and solvers.config_deleted from INT to BOOLEAN
-- This corrects a historical inconsistency where these columns used INT (0/1) while
-- all other deleted columns (benchmarks, solvers, jobs) use BOOLEAN.
--
-- Background:
-- - V0008 added configurations.deleted as INT based on incorrect assumption about jobs.deleted
-- - This caused type mismatch errors with PostgreSQL's strict type system
-- - Required workarounds like "WHERE deleted = 0" instead of "WHERE deleted = false"
-- - Java code had to use int getters/setters instead of boolean
--
-- This migration normalizes to BOOLEAN for:
-- - Type safety and semantic clarity
-- - Consistency with 95% of codebase
-- - PostgreSQL best practices
-- - Simplified maintenance
--
-- Author: Andres Cadavid
-- Date: November 2, 2025

-- Update the minor version flag
UPDATE system_flags SET minor_version=16;

DO $$
BEGIN
    -- Convert configurations.deleted: INT → BOOLEAN
    -- Uses (deleted != 0) to safely convert any int value to boolean
    -- 0 → false, any non-zero → true (though we only use 0/1)
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'configurations'
          AND column_name = 'deleted'
          AND data_type IN ('integer', 'int', 'smallint', 'bigint')
    ) THEN
        RAISE NOTICE 'Converting configurations.deleted from INT to BOOLEAN...';
        
        -- Drop default first (can't cast INT default to BOOLEAN)
        ALTER TABLE configurations
        ALTER COLUMN deleted DROP DEFAULT;
        
        -- Convert type
        ALTER TABLE configurations
        ALTER COLUMN deleted TYPE BOOLEAN
        USING (deleted != 0);
        
        -- Set new default
        ALTER TABLE configurations
        ALTER COLUMN deleted SET DEFAULT false;
        
        RAISE NOTICE 'Successfully converted configurations.deleted to BOOLEAN';
    ELSE
        RAISE NOTICE 'configurations.deleted is already BOOLEAN, skipping';
    END IF;

    -- Convert solvers.config_deleted: INT → BOOLEAN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'solvers'
          AND column_name = 'config_deleted'
          AND data_type IN ('integer', 'int', 'smallint', 'bigint')
    ) THEN
        RAISE NOTICE 'Converting solvers.config_deleted from INT to BOOLEAN...';
        
        -- Drop default first (can't cast INT default to BOOLEAN)
        ALTER TABLE solvers
        ALTER COLUMN config_deleted DROP DEFAULT;
        
        -- Convert type
        ALTER TABLE solvers
        ALTER COLUMN config_deleted TYPE BOOLEAN
        USING (config_deleted != 0);
        
        -- Set new default
        ALTER TABLE solvers
        ALTER COLUMN config_deleted SET DEFAULT false;
        
        RAISE NOTICE 'Successfully converted solvers.config_deleted to BOOLEAN';
    ELSE
        RAISE NOTICE 'solvers.config_deleted is already BOOLEAN, skipping';
    END IF;

    -- Verification: Report current data types
    RAISE NOTICE 'Final verification:';
    PERFORM data_type FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'configurations'
      AND column_name = 'deleted';
    RAISE NOTICE 'configurations.deleted type: %', 
        (SELECT data_type FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'configurations'
           AND column_name = 'deleted');
    
    PERFORM data_type FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'solvers'
      AND column_name = 'config_deleted';
    RAISE NOTICE 'solvers.config_deleted type: %',
        (SELECT data_type FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'solvers'
           AND column_name = 'config_deleted');
END
$$ LANGUAGE plpgsql;
