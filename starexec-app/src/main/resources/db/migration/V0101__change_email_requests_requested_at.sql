-- Add requested_at to change_email_requests for rate limiting (max one email change per 5 minutes per user).
-- Table may be in public (V0001 baseline) or starexec depending on deployment; add column to both if needed.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'change_email_requests') THEN
        ALTER TABLE public.change_email_requests ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP DEFAULT NOW();
        UPDATE public.change_email_requests SET requested_at = NOW() WHERE requested_at IS NULL;
        ALTER TABLE public.change_email_requests ALTER COLUMN requested_at SET NOT NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'starexec' AND table_name = 'change_email_requests') THEN
        ALTER TABLE starexec.change_email_requests ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP DEFAULT NOW();
        UPDATE starexec.change_email_requests SET requested_at = NOW() WHERE requested_at IS NULL;
        ALTER TABLE starexec.change_email_requests ALTER COLUMN requested_at SET NOT NULL;
    END IF;
END
$$;
