-- The permissions chosen on the benchmark upload form, for the spaces an upload with "convert"
-- creates from the archive's directories. The asynchronous upload dropped that choice: the worker
-- gave every created space the same five "add" permissions and none of the "remove" ones, whatever
-- the uploader had selected.
--
-- Both paths carry it. A local file goes through a resumable session (upload_sessions), which
-- becomes an upload job when it is finalized; a URL or Git upload, and StarexecCommand, enqueue the
-- job directly (upload_jobs).
--
-- The value is the granted permission names, comma-separated and sorted, as
-- Permission.getOnPermissions() names them (for example 'addBench,removeSolver'); an empty string
-- grants none. NULL is a row written before this column existed, or by a page loaded before it,
-- and keeps the old default.

ALTER TABLE starexec.upload_jobs
    ADD COLUMN IF NOT EXISTS space_permissions TEXT;

ALTER TABLE starexec.upload_sessions
    ADD COLUMN IF NOT EXISTS space_permissions TEXT;
