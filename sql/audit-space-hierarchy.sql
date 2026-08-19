-- Read-only audit of space hierarchy integrity.
--
-- Safe against production: SELECT only, no DDL, no writes.
--
--   psql -U starexec -d starexec -f sql/audit-space-hierarchy.sql
--
-- WHY THIS EXISTS
--
-- Until MoveSpace gained a guard, POST /move/space would let a space be moved into
-- its own descendant, or into itself. A child holds exactly one parent row, which
-- bounds in-degree but does not prevent a cycle: A parented to B and B parented to
-- A is two spaces with one parent each.
--
-- The guard stops new cycles. It cannot undo one already recorded. A database that
-- predates it may still hold one, and the effects are quiet rather than loud:
--
--   * Spaces.rebuildSpaceClosures now carries a visited set, so it terminates
--     instead of exhausting the stack -- but it stops descending at the repeat, so
--     the looped subtree never gets its closure rows rebuilt.
--   * The closure table drives visibility and permission inheritance, so a space
--     with missing closure rows is not merely untidy: it may be invisible to users
--     who should see it, or visible to users who should not.
--
-- Sections 1 and 2 find cycles. Section 3 finds spaces whose closure rows are
-- missing, which is what a skipped rebuild leaves behind.

\set ON_ERROR_STOP on

\echo ''
\echo '=============================================================================='
\echo 'SECTION 1 -- spaces that are their own parent'
\echo '  The degenerate cycle, and the cheapest to check.'
\echo '=============================================================================='

SELECT sa.space_id AS space_id,
       s.name,
       'space is its own parent' AS problem
FROM starexec.set_assoc sa
JOIN starexec.spaces s ON s.id = sa.space_id
WHERE sa.space_id = sa.child_id;

\echo ''
\echo '=============================================================================='
\echo 'SECTION 2 -- spaces that are their own ancestor (cycles of any length)'
\echo '  Walks parent->child edges, carrying the path, and reports the first repeat.'
\echo '  Bounded at 500 hops so a cycle cannot make the audit itself run away.'
\echo '=============================================================================='

WITH RECURSIVE walk(origin, node, path, is_cycle) AS (
    SELECT sa.space_id,
           sa.child_id,
           ARRAY[sa.space_id, sa.child_id],
           sa.space_id = sa.child_id
    FROM starexec.set_assoc sa
  UNION ALL
    SELECT w.origin,
           sa.child_id,
           w.path || sa.child_id,
           sa.child_id = ANY(w.path)
    FROM walk w
    JOIN starexec.set_assoc sa ON sa.space_id = w.node
    WHERE NOT w.is_cycle
      AND array_length(w.path, 1) < 500
)
SELECT DISTINCT
       origin AS space_id,
       (SELECT name FROM starexec.spaces WHERE id = origin) AS name,
       path   AS cycle_path
FROM walk
WHERE is_cycle
ORDER BY space_id
LIMIT 50;

\echo ''
\echo '-- Section 2 summary'
WITH RECURSIVE walk(origin, node, path, is_cycle) AS (
    SELECT sa.space_id, sa.child_id, ARRAY[sa.space_id, sa.child_id], sa.space_id = sa.child_id
    FROM starexec.set_assoc sa
  UNION ALL
    SELECT w.origin, sa.child_id, w.path || sa.child_id, sa.child_id = ANY(w.path)
    FROM walk w
    JOIN starexec.set_assoc sa ON sa.space_id = w.node
    WHERE NOT w.is_cycle AND array_length(w.path, 1) < 500
)
SELECT count(DISTINCT origin) AS spaces_in_a_cycle,
       CASE WHEN count(*) = 0 THEN 'hierarchy is acyclic'
            ELSE 'CYCLES PRESENT - closure rebuilds will skip these subtrees' END AS verdict
FROM walk
WHERE is_cycle;

\echo ''
\echo '=============================================================================='
\echo 'SECTION 3 -- spaces with missing closure rows'
\echo '  Every space should be its own ancestor in the closure table, and should have'
\echo '  a closure row for each of its real ancestors. A shortfall is what a skipped'
\echo '  or failed rebuild leaves behind, and it is what breaks visibility.'
\echo '=============================================================================='

\echo '-- 3a. spaces with no self-closure row at all'
SELECT s.id AS space_id, s.name
FROM starexec.spaces s
WHERE NOT EXISTS (
    SELECT 1 FROM starexec.closure c WHERE c.ancestor = s.id AND c.descendant = s.id
)
ORDER BY s.id
LIMIT 50;

\echo ''
\echo '-- 3b. parent/child edges with no matching closure row'
SELECT sa.space_id AS parent_id,
       sa.child_id AS child_id,
       'set_assoc edge has no closure row' AS problem
FROM starexec.set_assoc sa
WHERE sa.space_id <> sa.child_id
  AND NOT EXISTS (
      SELECT 1 FROM starexec.closure c
      WHERE c.ancestor = sa.space_id AND c.descendant = sa.child_id
  )
ORDER BY sa.space_id, sa.child_id
LIMIT 50;

\echo ''
\echo '-- Section 3 summary'
SELECT (SELECT count(*) FROM starexec.spaces s
        WHERE NOT EXISTS (SELECT 1 FROM starexec.closure c
                          WHERE c.ancestor = s.id AND c.descendant = s.id)) AS missing_self_closure,
       (SELECT count(*) FROM starexec.set_assoc sa
        WHERE sa.space_id <> sa.child_id
          AND NOT EXISTS (SELECT 1 FROM starexec.closure c
                          WHERE c.ancestor = sa.space_id AND c.descendant = sa.child_id)) AS edges_missing_closure;

\echo ''
\echo 'Audit complete. Nothing was modified.'
\echo 'A cycle cannot be repaired mechanically: deciding which parent a space should'
\echo 'keep is a judgement about intent, not a derivable fact. Re-parent the affected'
\echo 'spaces deliberately, then rerun this audit and rebuild the closures.'
