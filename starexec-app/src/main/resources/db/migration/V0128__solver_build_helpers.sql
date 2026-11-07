CREATE TABLE starexec.solver_build_helpers (
    benchmark_id INTEGER PRIMARY KEY,
    owner_id INTEGER NOT NULL,
    path TEXT NOT NULL CHECK (path<>''),
    byte_size BIGINT NOT NULL CHECK (byte_size>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cleanup_token UUID,
    cleanup_started_at TIMESTAMPTZ,
    cleanup_finished_at TIMESTAMPTZ,
    CONSTRAINT solver_build_helper_cleanup_ck CHECK (
        cleanup_token IS NULL AND cleanup_started_at IS NULL AND cleanup_finished_at IS NULL
        OR cleanup_token IS NOT NULL AND cleanup_started_at IS NOT NULL
            AND (cleanup_finished_at IS NULL OR cleanup_finished_at>=cleanup_started_at))
);

-- Historical helper identity survives deletion of its pair, solver and benchmark.
ALTER TABLE starexec.solver_build_artifacts ADD COLUMN helper_benchmark_id INTEGER;
CREATE INDEX solver_build_artifacts_helper_idx ON starexec.solver_build_artifacts(helper_benchmark_id);

CREATE FUNCTION starexec.capture_solver_build_helper() RETURNS trigger AS $$
DECLARE
    _b starexec.benchmarks%ROWTYPE;
    _h starexec.solver_build_helpers%ROWTYPE;
BEGIN
    SELECT b.* INTO _b FROM starexec.benchmarks b JOIN starexec.job_pairs jp ON jp.bench_id=b.id
        JOIN starexec.jobs j ON j.id=jp.job_id
        WHERE jp.id=NEW.origin_pair_id AND j.buildjob AND j.user_id=NEW.origin_owner_id
            AND b.user_id=NEW.origin_owner_id AND b.name='starexec_build' AND b.deleted IS NOT TRUE
        FOR SHARE OF b;
    IF NOT FOUND THEN RETURN NEW; END IF;
    INSERT INTO starexec.solver_build_helpers(benchmark_id,owner_id,path,byte_size)
        VALUES (_b.id,_b.user_id,_b.path,_b.disk_size) ON CONFLICT (benchmark_id) DO NOTHING;
    SELECT * INTO _h FROM starexec.solver_build_helpers WHERE benchmark_id=_b.id FOR SHARE;
    IF (_h.owner_id,_h.path,_h.byte_size) IS DISTINCT FROM (_b.user_id,_b.path,_b.disk_size)
        OR _h.cleanup_token IS NOT NULL THEN
        RAISE EXCEPTION 'Build helper identity changed or is being retired' USING ERRCODE='55006';
    END IF;
    NEW.helper_benchmark_id := _b.id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_build_helper_capture BEFORE INSERT ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.capture_solver_build_helper();

CREATE FUNCTION starexec.guard_solver_build_helper_identity() RETURNS trigger AS $$
BEGIN
    IF NEW.helper_benchmark_id IS DISTINCT FROM OLD.helper_benchmark_id THEN
        RAISE EXCEPTION 'Build helper origin is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_build_helper_identity BEFORE UPDATE OF helper_benchmark_id ON starexec.solver_build_artifacts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_build_helper_identity();

CREATE FUNCTION starexec.guard_solver_build_helper_record() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Build helpers must retain their cleanup records'; END IF;
    IF (NEW.benchmark_id,NEW.owner_id,NEW.path,NEW.byte_size,NEW.created_at)
        IS DISTINCT FROM (OLD.benchmark_id,OLD.owner_id,OLD.path,OLD.byte_size,OLD.created_at)
        OR OLD.cleanup_token IS NOT NULL AND (NEW.cleanup_token,NEW.cleanup_started_at)
            IS DISTINCT FROM (OLD.cleanup_token,OLD.cleanup_started_at)
        OR OLD.cleanup_finished_at IS NOT NULL AND NEW.cleanup_finished_at IS DISTINCT FROM OLD.cleanup_finished_at THEN
        RAISE EXCEPTION 'Build helper cleanup identity is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER solver_build_helper_record BEFORE UPDATE OR DELETE ON starexec.solver_build_helpers
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_solver_build_helper_record();

CREATE FUNCTION starexec.guard_managed_build_helper_benchmark() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM starexec.solver_build_helpers h
        WHERE h.cleanup_finished_at IS NULL AND EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a
            WHERE a.helper_benchmark_id=h.benchmark_id AND a.execution_token IS NOT NULL)
          AND (h.benchmark_id=OLD.id OR h.path=OLD.path OR starts_with(h.path,OLD.path || '/')
                OR starts_with(OLD.path,h.path || '/'))) THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Managed build helper cleanup is unsettled' USING ERRCODE='55006'; END IF;
        IF (NEW.user_id,NEW.name,NEW.path,NEW.disk_size,NEW.deleted)
            IS DISTINCT FROM (OLD.user_id,OLD.name,OLD.path,OLD.disk_size,OLD.deleted) THEN
            RAISE EXCEPTION 'Managed build helper cleanup is unsettled' USING ERRCODE='55006';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER managed_build_helper_benchmark BEFORE UPDATE OR DELETE ON starexec.benchmarks
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_managed_build_helper_benchmark();

CREATE FUNCTION starexec.guard_build_helper_consumer() RETURNS trigger AS $$
BEGIN
    IF NEW.bench_id IS NULL OR TG_OP='UPDATE' AND NEW.bench_id IS NOT DISTINCT FROM OLD.bench_id THEN RETURN NEW; END IF;
    PERFORM id FROM starexec.benchmarks WHERE id=NEW.bench_id FOR KEY SHARE;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_helpers WHERE benchmark_id=NEW.bench_id AND cleanup_token IS NOT NULL) THEN
        RAISE EXCEPTION 'Build helper is being removed or is no longer available' USING ERRCODE='55006';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER build_helper_consumer BEFORE INSERT OR UPDATE OF bench_id ON starexec.job_pairs
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_build_helper_consumer();
