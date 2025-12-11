-- Add missing columns to jobs table that are required by stored procedures
-- These columns are referenced by GetSpaceJobsById and other procedures

-- ============================================================================
-- JOBS TABLE - Missing columns for stored procedure compatibility
-- ============================================================================

-- Add completed_pairs column (tracks number of completed job pairs)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='completed_pairs') THEN
        ALTER TABLE starexec.jobs ADD COLUMN completed_pairs INT DEFAULT 0;
    END IF;
END $$;

-- Add errored_pairs column (tracks number of errored job pairs)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='errored_pairs') THEN
        ALTER TABLE starexec.jobs ADD COLUMN errored_pairs INT DEFAULT 0;
    END IF;
END $$;

-- Add pending_pairs column (tracks number of pending job pairs)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='pending_pairs') THEN
        ALTER TABLE starexec.jobs ADD COLUMN pending_pairs INT DEFAULT 0;
    END IF;
END $$;

-- Add status_code column (job status indicator)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='status_code') THEN
        ALTER TABLE starexec.jobs ADD COLUMN status_code INT DEFAULT 0;
    END IF;
END $$;

-- Add max_stages column (maximum number of pipeline stages)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='max_stages') THEN
        ALTER TABLE starexec.jobs ADD COLUMN max_stages INT DEFAULT 1;
    END IF;
END $$;

-- Add job_type column (type of job: normal, quick, etc.)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='job_type') THEN
        ALTER TABLE starexec.jobs ADD COLUMN job_type INT DEFAULT 0;
    END IF;
END $$;

-- Add timeout column (job timeout in seconds, distinct from cpuTimeout/clockTimeout)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='timeout') THEN
        ALTER TABLE starexec.jobs ADD COLUMN timeout INT;

        -- Initialize timeout from clockTimeout for existing jobs
        UPDATE starexec.jobs SET timeout = clockTimeout WHERE timeout IS NULL AND clockTimeout IS NOT NULL;
    END IF;
END $$;

-- Add suppress_output column (whether to suppress job output)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='suppress_output') THEN
        ALTER TABLE starexec.jobs ADD COLUMN suppress_output BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- Add node_queued column (whether job is queued on a node)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='starexec' AND table_name='jobs' AND column_name='node_queued') THEN
        ALTER TABLE starexec.jobs ADD COLUMN node_queued BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- ============================================================================
-- Initialize pair counts from actual job_pairs data for existing jobs
-- ============================================================================

-- Update completed_pairs count from actual data
UPDATE starexec.jobs j
SET completed_pairs = COALESCE((
    SELECT COUNT(*)
    FROM starexec.job_pairs jp
    WHERE jp.job_id = j.id
    AND jp.status_code >= 7
), 0)
WHERE completed_pairs = 0 OR completed_pairs IS NULL;

-- Update pending_pairs count from actual data
UPDATE starexec.jobs j
SET pending_pairs = COALESCE((
    SELECT COUNT(*)
    FROM starexec.job_pairs jp
    WHERE jp.job_id = j.id
    AND jp.status_code < 7
    AND jp.status_code >= 0
), 0)
WHERE pending_pairs = 0 OR pending_pairs IS NULL;

-- Update errored_pairs count from actual data (negative status codes indicate errors)
UPDATE starexec.jobs j
SET errored_pairs = COALESCE((
    SELECT COUNT(*)
    FROM starexec.job_pairs jp
    WHERE jp.job_id = j.id
    AND jp.status_code < 0
), 0)
WHERE errored_pairs = 0 OR errored_pairs IS NULL;
