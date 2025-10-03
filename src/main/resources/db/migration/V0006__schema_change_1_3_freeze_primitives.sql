-- Schema Change: Add freeze_primitives system flag
-- When this is set to TRUE, uploading Benchmarks and Solvers is disabled

UPDATE system_flags SET minor_version=3;

ALTER TABLE system_flags ADD freeze_primitives BOOLEAN DEFAULT FALSE;