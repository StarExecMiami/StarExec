-- V0102: Agregar UNIQUE constraint a bench_dependency para garantizar idempotencia
-- y permitir ON CONFLICT DO NOTHING / UPDATE.

-- Intentar agregar el constraint. Si ya existen duplicados, fallará, lo cual es correcto
-- para que el administrador limpie los datos inconsistentes antes de proceder.
-- Sin embargo, en un entorno de desarrollo/fresco, esto pasará sin problemas.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_dependency_pair'
    ) THEN
        ALTER TABLE starexec.bench_dependency 
        ADD CONSTRAINT unique_dependency_pair 
        UNIQUE (primary_bench_id, secondary_bench_id);
    END IF;
END;
$$;
