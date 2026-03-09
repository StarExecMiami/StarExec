-- Add dependency-related columns to upload_jobs so that the async upload worker
-- can resolve benchmark dependencies (starexec-dependency-N attributes) correctly.
-- Previously these were hard-coded to false/null in UploadJobWorker.handleConvertMethod.

ALTER TABLE starexec.upload_jobs
    ADD COLUMN IF NOT EXISTS has_dependencies BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS dep_root_space_id INTEGER,
    ADD COLUMN IF NOT EXISTS linked BOOLEAN NOT NULL DEFAULT FALSE;
