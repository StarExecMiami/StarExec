ALTER TABLE starexec.solver_build_artifacts
    ADD COLUMN execution_stop_receipt_payload TEXT,
    ADD COLUMN execution_stop_receipt_sha256 TEXT,
    ADD COLUMN execution_stop_receipt_recorded_at TIMESTAMPTZ,
    ADD CONSTRAINT solver_build_stop_receipt_complete CHECK (
        execution_stop_receipt_payload IS NULL AND execution_stop_receipt_sha256 IS NULL
            AND execution_stop_receipt_recorded_at IS NULL
        OR execution_stop_receipt_payload IS NOT NULL AND execution_stop_receipt_payload <> ''
            AND execution_stop_receipt_sha256 IS NOT NULL
            AND execution_stop_receipt_recorded_at IS NOT NULL
            AND execution_stop_receipt_sha256 = encode(sha256(convert_to(execution_stop_receipt_payload, 'UTF8')), 'hex'));

CREATE FUNCTION starexec.guard_solver_build_stop_receipt() RETURNS trigger AS $$
BEGIN
    IF OLD.execution_stop_receipt_payload IS NOT NULL AND
        (NEW.execution_stop_receipt_payload, NEW.execution_stop_receipt_sha256, NEW.execution_stop_receipt_recorded_at)
        IS DISTINCT FROM
        (OLD.execution_stop_receipt_payload, OLD.execution_stop_receipt_sha256, OLD.execution_stop_receipt_recorded_at) THEN
        RAISE EXCEPTION 'A build stop receipt is immutable';
    END IF;
    IF OLD.execution_stop_receipt_payload IS NULL AND NEW.execution_stop_receipt_payload IS NOT NULL
        AND OLD.state NOT IN ('PLANNED', 'STAGED', 'REJECTED') THEN
        RAISE EXCEPTION 'Stop evidence must precede artifact validation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_stop_receipt_guard BEFORE UPDATE ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_build_stop_receipt();
