-- Repairs benchmarks inserted by the asynchronous ("dump") upload path.
--
-- BoundedUploadProcessor hand-rolled `INSERT INTO benchmarks` instead of calling
-- AddAndAssociateBenchmark, so it satisfied only one of the three invariants that
-- procedure encodes. Two writes were skipped for every benchmark it inserted:
--
--   1. the bench_assoc row, so the benchmarks never appeared in their space; and
--   2. the users.disk_size increment, so uploads consumed no quota. Because
--      SetBenchmarkToDeletedById decrements unconditionally, deleting such a
--      benchmark drives disk_size negative, and users.disk_size has no CHECK
--      constraint to catch it.
--
-- The code fix routes the batch path through starexec.AddAndAssociateBenchmarks.
-- This migration repairs rows already written by the broken path.
--
-- Deliberately self-contained: Flyway applies every repeatable migration after all
-- versioned ones, so on a fresh install R__procedures_and_views.sql has not run yet
-- and starexec.UpdateUserDiskUsage does not exist. Its recomputation is inlined below
-- rather than called.
--
-- TWO LIMITATIONS, both deliberate:
--
-- a. Adopted rows carry no bench_attributes and were never validated by a benchmark
--    processor -- the broken code inserted only the benchmarks row, and it stamped
--    bench_type with the job's processor id regardless. This migration makes those rows
--    visible in their space; it cannot retro-validate them, because that means executing
--    the processor against files that may no longer exist. Operators who care whether
--    those benchmarks would pass validation should re-upload them. bench_type is left
--    alone rather than downgraded to NO_TYPE_PROC_ID, so the existing metadata is not
--    rewritten underneath anyone.
--
-- b. The disk_size recomputation below is an absolute write, so it assumes no upload or
--    deletion is committing concurrently. Its statement snapshot is taken before it waits
--    on any user row lock, so a benchmark committed in that window would be missed. Run
--    it with the application quiesced where possible; otherwise re-run
--    Users.updateAllUserDiskSizes() afterwards, which recomputes the same totals and logs
--    any residual drift.

DO $$
DECLARE
    _adopted           INT := 0;
    _unmatched         INT := 0;
    _usersRepaired     INT := 0;
BEGIN
    -- Step 1: adopt orphaned benchmarks into the space their upload job targeted.
    --
    -- Benchmarks carry no upload_job reference, so the link is the filesystem path:
    -- every benchmark from a job lives under that job's extract_path. Restricted to
    -- upload_method = 'dump' on purpose -- the 'convert' path distributes benchmarks
    -- across generated subspaces, so adopting those into upload_jobs.space_id would
    -- file them in the wrong space. That path was never broken; it goes through the
    -- legacy synchronous code, which associates correctly.
    --
    -- DISTINCT ON with the longest extract_path wins, so that a job whose directory
    -- happens to prefix another's cannot claim the other's benchmarks.
    WITH orphan_owner AS (
        SELECT DISTINCT ON (b.id)
               b.id AS bench_id,
               uj.space_id
        FROM starexec.benchmarks b
        JOIN starexec.upload_jobs uj
              ON uj.user_id = b.user_id
             AND uj.upload_method = 'dump'
             AND uj.extract_path IS NOT NULL
             AND uj.extract_path <> ''
             -- starts_with(), not LIKE: paths may contain '_' and '%', which LIKE
             -- would treat as wildcards.
             AND starts_with(b.path, rtrim(uj.extract_path, '/') || '/')
        WHERE b.deleted = false
          AND b.recycled = false
          AND NOT EXISTS (
              SELECT 1 FROM starexec.bench_assoc ba WHERE ba.bench_id = b.id
          )
        ORDER BY b.id, length(uj.extract_path) DESC
    ),
    adopted AS (
        INSERT INTO starexec.bench_assoc (space_id, bench_id)
        SELECT o.space_id, o.bench_id
        FROM orphan_owner o
        ON CONFLICT DO NOTHING
        RETURNING bench_id
    )
    SELECT count(*) INTO _adopted FROM adopted;

    -- Orphans we could not attribute to any dump job -- reported, never guessed at.
    SELECT count(*) INTO _unmatched
    FROM starexec.benchmarks b
    WHERE b.deleted = false
      AND b.recycled = false
      AND NOT EXISTS (
          SELECT 1 FROM starexec.bench_assoc ba WHERE ba.bench_id = b.id
      );

    -- Step 2: recompute users.disk_size from the authoritative tables, for users who
    -- owned at least one dump-path upload job. Scoped rather than fleet-wide so that
    -- drift from any unrelated cause stays visible instead of being silently absorbed.
    -- Users.updateAllUserDiskSizes() performs the fleet-wide pass when that is wanted.
    WITH affected AS (
        SELECT DISTINCT uj.user_id
        FROM starexec.upload_jobs uj
        WHERE uj.upload_method = 'dump'
    ),
    recomputed AS (
        SELECT a.user_id,
               COALESCE((
                   SELECT SUM(x.disk_size)
                   FROM (
                       SELECT disk_size FROM starexec.solvers    WHERE user_id = a.user_id AND deleted = false
                       UNION ALL
                       SELECT disk_size FROM starexec.benchmarks WHERE user_id = a.user_id AND deleted = false
                       UNION ALL
                       SELECT disk_size FROM starexec.jobs       WHERE user_id = a.user_id AND deleted = false
                   ) x
               ), 0) AS actual
        FROM affected a
    ),
    repaired AS (
        UPDATE starexec.users u
        SET disk_size = r.actual
        FROM recomputed r
        WHERE u.id = r.user_id
          AND u.disk_size IS DISTINCT FROM r.actual
        RETURNING u.id
    )
    SELECT count(*) INTO _usersRepaired FROM repaired;

    RAISE NOTICE 'V0113: adopted % orphaned benchmark(s) into their upload space', _adopted;
    RAISE NOTICE 'V0113: corrected disk_size for % user(s)', _usersRepaired;

    IF _unmatched > 0 THEN
        RAISE NOTICE 'V0113: % benchmark(s) remain unassociated and could not be matched to a dump-method upload job; these need manual review', _unmatched;
    END IF;
END;
$$;
