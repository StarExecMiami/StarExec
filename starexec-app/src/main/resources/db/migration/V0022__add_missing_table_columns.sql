-- Add missing columns to tables that are referenced by stored procedures

-- ============================================================================
-- CONFIGURATIONS TABLE
-- ============================================================================

-- Add contents column (TEXT for storing configuration file contents)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='configurations' AND column_name='contents') THEN
        ALTER TABLE starexec.configurations ADD COLUMN contents TEXT;
    END IF;
END $$;

-- Add upload_date column
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='configurations' AND column_name='upload_date') THEN
        ALTER TABLE starexec.configurations ADD COLUMN upload_date TIMESTAMP DEFAULT NOW();
    END IF;
END $$;

-- Add user_id column (references the user who created the configuration)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='configurations' AND column_name='user_id') THEN
        ALTER TABLE starexec.configurations ADD COLUMN user_id INT;
        
        -- Set user_id from the solver's user_id for existing configurations
        UPDATE starexec.configurations c
        SET user_id = s.user_id
        FROM starexec.solvers s
        WHERE c.solver_id = s.id AND c.user_id IS NULL;
        
        -- Add foreign key constraint
        ALTER TABLE starexec.configurations 
            ADD CONSTRAINT configurations_user_id 
            FOREIGN KEY (user_id) REFERENCES starexec.users(id) ON DELETE SET NULL;
    END IF;
END $$;

-- ============================================================================
-- BENCHMARKS TABLE
-- ============================================================================

-- Add upload_date column as alias for uploaded (for consistency with stored procedures)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='benchmarks' AND column_name='upload_date') THEN
        ALTER TABLE starexec.benchmarks ADD COLUMN upload_date TIMESTAMP;
        
        -- Copy values from 'uploaded' column to 'upload_date'
        UPDATE starexec.benchmarks SET upload_date = uploaded WHERE upload_date IS NULL;
    END IF;
END $$;

-- ============================================================================
-- JOB_PAIRS TABLE
-- ============================================================================

-- Add config_name column (denormalized from jobpair_stage_data for performance)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='job_pairs' AND column_name='config_name') THEN
        ALTER TABLE starexec.job_pairs ADD COLUMN config_name VARCHAR(128);
    END IF;
END $$;

-- ============================================================================
-- JOB_ATTRIBUTES TABLE
-- ============================================================================

-- Add solver_id column
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='job_attributes' AND column_name='solver_id') THEN
        ALTER TABLE starexec.job_attributes ADD COLUMN solver_id INT;
    END IF;
END $$;

-- Add solver_name column
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='job_attributes' AND column_name='solver_name') THEN
        ALTER TABLE starexec.job_attributes ADD COLUMN solver_name VARCHAR(128);
    END IF;
END $$;

-- Add config_id column
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='job_attributes' AND column_name='config_id') THEN
        ALTER TABLE starexec.job_attributes ADD COLUMN config_id INT;
    END IF;
END $$;

-- Add config_name column
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema='starexec' AND table_name='job_attributes' AND column_name='config_name') THEN
        ALTER TABLE starexec.job_attributes ADD COLUMN config_name VARCHAR(128);
    END IF;
END $$;
