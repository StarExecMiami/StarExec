-- Historical solver snapshots remain unconstrained; new consumers must not race
-- a registered cleanup or acquire managed input whose bytes were already removed.
CREATE FUNCTION starexec.guard_solver_cleanup_input_admission() RETURNS trigger AS $$
BEGIN
    IF NEW.solver_id IS NULL OR TG_OP='UPDATE' AND NEW.solver_id IS NOT DISTINCT FROM OLD.solver_id THEN
        RETURN NEW;
    END IF;
    PERFORM id FROM starexec.solvers WHERE id=NEW.solver_id FOR KEY SHARE;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.origin_solver_id=NEW.solver_id
        AND (a.cleanup_token IS NOT NULL AND a.cleanup_finished_at IS NULL
            OR a.cleanup_source_path IS NOT NULL AND a.cleanup_finished_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'Managed solver input is being removed or is no longer available' USING ERRCODE='55006';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_cleanup_input_admission BEFORE INSERT OR UPDATE OF solver_id ON starexec.jobpair_stage_data
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_cleanup_input_admission();
