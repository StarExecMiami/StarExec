ALTER TABLE starexec.solver_build_artifacts
    ADD COLUMN cleanup_token UUID,
    ADD COLUMN cleanup_started_at TIMESTAMPTZ,
    ADD COLUMN cleanup_source_path TEXT,
    ADD COLUMN cleanup_solver_bytes BIGINT,
    ADD COLUMN cleanup_finished_at TIMESTAMPTZ,
    ADD CONSTRAINT solver_build_cleanup_identity_ck CHECK (
        cleanup_token IS NULL AND cleanup_started_at IS NULL AND cleanup_source_path IS NULL
            AND cleanup_solver_bytes IS NULL AND cleanup_finished_at IS NULL
        OR cleanup_token IS NOT NULL AND cleanup_started_at IS NOT NULL
            AND cleanup_solver_bytes IS NOT NULL AND cleanup_solver_bytes>=0
            AND (cleanup_source_path IS NOT NULL OR cleanup_solver_bytes=0)),
    ADD CONSTRAINT solver_build_cleanup_completion_ck CHECK (
        cleanup_finished_at IS NULL OR state='DELETED' AND cleanup_finished_at>=cleanup_started_at);

CREATE FUNCTION starexec.guard_solver_build_cleanup() RETURNS trigger AS $$
BEGIN
    IF OLD.cleanup_token IS NOT NULL THEN
        IF (NEW.cleanup_token,NEW.cleanup_started_at,NEW.cleanup_source_path,NEW.cleanup_solver_bytes,
            NEW.staging_path,NEW.storage_root,NEW.artifact_path,NEW.byte_size,NEW.manifest_payload,
            NEW.manifest_sha256,NEW.validated_at)
            IS DISTINCT FROM
           (OLD.cleanup_token,OLD.cleanup_started_at,OLD.cleanup_source_path,OLD.cleanup_solver_bytes,
            OLD.staging_path,OLD.storage_root,OLD.artifact_path,OLD.byte_size,OLD.manifest_payload,
            OLD.manifest_sha256,OLD.validated_at) THEN
            RAISE EXCEPTION 'A registered cleanup identity is immutable';
        END IF;
        IF NEW.state IS DISTINCT FROM OLD.state AND NEW.state<>'DELETED' THEN
            RAISE EXCEPTION 'Registered cleanup cannot become publishable';
        END IF;
        IF OLD.cleanup_finished_at IS NOT NULL AND NEW.cleanup_finished_at IS DISTINCT FROM OLD.cleanup_finished_at THEN
            RAISE EXCEPTION 'Cleanup completion is immutable';
        END IF;
    END IF;
    IF NEW.cleanup_token IS NOT NULL AND NEW.state NOT IN ('RETIRED','REJECTED','DELETED') THEN
        RAISE EXCEPTION 'Cleanup requires a retired or rejected artifact';
    END IF;
    IF NEW.state='DELETED' AND OLD.state<>'DELETED' AND NEW.cleanup_finished_at IS NULL THEN
        RAISE EXCEPTION 'Artifact deletion requires completed cleanup';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_build_cleanup_guard BEFORE UPDATE ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_build_cleanup();

-- A durable source/charge claim must remain usable after a filesystem or commit failure.
CREATE FUNCTION starexec.guard_solver_cleanup_source() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.origin_solver_id=OLD.id
        AND a.cleanup_source_path IS NOT NULL AND a.cleanup_finished_at IS NULL) THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Solver source cleanup is unsettled'; END IF;
        IF (NEW.user_id,NEW.path,NEW.build_source_path,NEW.disk_size,NEW.deleted,NEW.build_generation)
            IS DISTINCT FROM (OLD.user_id,OLD.path,OLD.build_source_path,OLD.disk_size,OLD.deleted,OLD.build_generation) THEN
            RAISE EXCEPTION 'Solver source cleanup identity is immutable';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER solver_cleanup_source_guard BEFORE UPDATE OR DELETE ON starexec.solvers
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_cleanup_source();
