-- Schema Change: Drop config_deleted column from solvers
-- Modify table solvers by dropping column "config_deleted", which was added in the
-- previous SchemaChange, 00000006.sql
-- Author: Alexander Brown

UPDATE system_flags SET minor_version=7;

ALTER TABLE solvers DROP COLUMN config_deleted;