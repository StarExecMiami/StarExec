-- Repeatable migration for procedures, functions, and views
-- Bring forward selected routines from sql/procedures/*.sql in an idempotent way.

-- MySQL doesn't support CREATE OR REPLACE PROCEDURE directly.
-- Use DROP ... IF EXISTS guards with custom delimiter.

-- Note: Defining/dropping routines requires elevated privileges in MySQL 8 when
-- the definer is a different user. We'll keep this file as a no-op until we run
-- migrations with an admin user. See README-db-migrations.md for details.
SELECT 1;
