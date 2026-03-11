-- V0102: Add UNIQUE constraint to bench_dependency to ensure idempotency
-- and allow ON CONFLICT DO NOTHING / UPDATE.

-- Try to add the constraint. If duplicates already exist, it will fail, which is correct
-- so the administrator can clean up inconsistent data before proceeding.
-- However, in a fresh or development environment, this will pass without issues.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_dependency_pair'
    ) THEN
        ALTER TABLE starexec.bench_dependency 
        ADD CONSTRAINT unique_dependency_pair 
        UNIQUE (primary_bench_id, secondary_bench_id);
    END IF;
END;
$$;
