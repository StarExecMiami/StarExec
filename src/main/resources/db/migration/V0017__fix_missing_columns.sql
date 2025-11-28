-- Fix missing columns causing SQL errors in stored procedures

-- Add upload_date to solvers table if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='solvers' AND column_name='upload_date') THEN
        ALTER TABLE solvers ADD COLUMN upload_date TIMESTAMP DEFAULT NOW();
    END IF;
END $$;

-- Add primary_jobpair_data to job_pair_completion table if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='job_pair_completion' AND column_name='primary_jobpair_data') THEN
        ALTER TABLE job_pair_completion ADD COLUMN primary_jobpair_data INTEGER;
    END IF;
END $$;

-- Create SetEventOccurrencesNotRelatedToQueue procedure if it doesn't exist
CREATE OR REPLACE FUNCTION starexec.SetEventOccurrencesNotRelatedToQueue(
    p_event_name VARCHAR,
    p_occurrences INTEGER
)
RETURNS VOID AS $$
BEGIN
    UPDATE reports
    SET event_occurrences = p_occurrences
    WHERE event_name = p_event_name
      AND queue_id IS NULL;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Event ''%'' not found in reports', p_event_name
            USING ERRCODE = 'P0002';
    END IF;
END;
$$ LANGUAGE plpgsql;
