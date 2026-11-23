-- Rewrite filesystem paths imported from the legacy StarExec deployment (se-m1)
-- to match the containerized data directory mount (/app/data).
--
-- WHEN: once, right after legacy solvers, benchmarks, and processors have been
-- loaded into this PostgreSQL database. Re-running it is safe and idempotent.
--
-- WHY: se-m1 stores asset paths rooted at /home/starexec/ (e.g.
-- /home/starexec/Solvers/..., /home/starexec/Benchmarks/...,
-- /home/starexec/processor_scripts/...), whereas the containerized deployment and
-- Kubernetes/Podman job-runner pods mount the shared data volume at /app/data
-- (STAREXEC_DATA_DIR=/app/data). Without rewriting the prefix, adjustForK8s and
-- jobscript lookups fail immediately with ERROR_RUNSCRIPT (status 11).
--
-- WHAT IT TOUCHES: only rows whose stored path starts with '/home/starexec/'.
-- Rows already rooted at '/app/data/' or non-path sentinels (such as 'no path'
-- in processors) are left unchanged. Historical immutable columns in
-- solver_build_artifacts are intentionally untouched to preserve their guard
-- invariants.

UPDATE starexec.solvers
SET path = regexp_replace(path, '^/home/starexec/', '/app/data/')
WHERE path LIKE '/home/starexec/%';

UPDATE starexec.solvers
SET build_source_path = regexp_replace(build_source_path, '^/home/starexec/', '/app/data/')
WHERE build_source_path LIKE '/home/starexec/%';

UPDATE starexec.benchmarks
SET path = regexp_replace(path, '^/home/starexec/', '/app/data/')
WHERE path LIKE '/home/starexec/%';

UPDATE starexec.processors
SET path = regexp_replace(path, '^/home/starexec/', '/app/data/')
WHERE path LIKE '/home/starexec/%';
