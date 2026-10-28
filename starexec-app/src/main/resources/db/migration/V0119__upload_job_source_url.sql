-- A benchmark upload by URL is downloaded by the upload worker, not by the request that
-- enqueued it (#139): an archive can take minutes to fetch, and the request returns at once.
-- The worker needs the address; archive_path already names the file it downloads it to.
-- NULL for every other upload.

ALTER TABLE starexec.upload_jobs
    ADD COLUMN IF NOT EXISTS source_url TEXT;
