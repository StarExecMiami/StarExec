-- Add missing foreign-key constraint on solver_pipelines.user_id so
-- that deleting a user cascades cleanly through their solver pipelines.
--
-- anonymous_links.primitive_id remains intentionally unconstrained because
-- it is a polymorphic reference (the primitive_type column determines
-- whether primitive_id points to solvers, benchmarks, or jobs).  PostgreSQL
-- cannot express a single foreign key spanning multiple target tables.

ALTER TABLE starexec.solver_pipelines
    ADD CONSTRAINT solver_pipelines_user_id
        FOREIGN KEY (user_id)
        REFERENCES starexec.users(id)
        ON DELETE CASCADE
        NOT VALID;

COMMENT ON COLUMN starexec.anonymous_links.primitive_id IS
    'Polymorphic reference — no single-table FK is possible. '
    'primitive_type governs which table (solvers/benchmarks/jobs) this id points to. '
    'Dangling references are cleaned by PeriodicTasks.DELETE_OLD_ANONYMOUS_LINKS_TASK.';
