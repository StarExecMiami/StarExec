CREATE OR REPLACE FUNCTION starexec.normalize_execution_storage_path(_path TEXT)
RETURNS TEXT AS $$
DECLARE _part TEXT; _parts TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF _path IS NULL OR left(_path,1)<>'/' THEN RETURN NULL; END IF;
    IF strpos(_path,'//')=0 AND _path !~ '(^|/)[.]{1,2}(/|$)' THEN
        RETURN CASE WHEN _path='/' THEN '/' ELSE rtrim(_path,'/') END;
    END IF;
    FOREACH _part IN ARRAY string_to_array(_path,'/') LOOP
        IF _part='' OR _part='.' THEN CONTINUE; END IF;
        IF _part='..' THEN _parts := _parts[1:greatest(cardinality(_parts)-1,0)];
        ELSE _parts := array_append(_parts,_part); END IF;
    END LOOP;
    RETURN '/' || array_to_string(_parts,'/');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION starexec.execution_storage_paths_overlap(_left TEXT, _right TEXT)
RETURNS BOOLEAN AS $$
    WITH paths AS (SELECT starexec.normalize_execution_storage_path(_left) l,
                         starexec.normalize_execution_storage_path(_right) r)
    SELECT COALESCE(l=r OR l='/' AND r IS NOT NULL OR r='/' AND l IS NOT NULL
        OR starts_with(l,r || '/') OR starts_with(r,l || '/'),FALSE) FROM paths;
$$ LANGUAGE SQL IMMUTABLE;

CREATE OR REPLACE FUNCTION starexec.execution_storage_root_unreferenced(_root TEXT)
RETURNS BOOLEAN AS $$
    SELECT NOT EXISTS (SELECT 1 FROM starexec.solvers s, unnest(ARRAY[s.path,s.build_source_path]) p
        WHERE starexec.execution_storage_paths_overlap(_root,p))
    AND NOT EXISTS (SELECT 1 FROM starexec.benchmarks b
        WHERE starexec.execution_storage_paths_overlap(_root,b.path))
    AND NOT EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a,
        unnest(ARRAY[a.source_path,a.storage_root,a.artifact_path]) p
        WHERE a.state<>'DELETED' AND starexec.execution_storage_paths_overlap(_root,p));
$$ LANGUAGE SQL;

-- Version the shared policy row so repeatable-read asset writers cannot miss a new resource.
CREATE OR REPLACE FUNCTION starexec.guard_execution_storage_registration() RETURNS trigger AS $$
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'New storage registration requires a current read-committed snapshot' USING ERRCODE='40001';
    END IF;
    PERFORM singleton FROM starexec.execution_storage_policy WHERE singleton FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'Execution storage policy is unavailable' USING ERRCODE='55000'; END IF;
    UPDATE starexec.execution_storage_policy SET changed_at=clock_timestamp() WHERE singleton;
    IF NOT starexec.execution_storage_root_unreferenced(NEW.root_path) THEN
        RAISE EXCEPTION 'Execution storage overlaps retained application assets' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER execution_storage_registration_guard BEFORE INSERT
    ON starexec.execution_storage_resources FOR EACH ROW
    EXECUTE FUNCTION starexec.guard_execution_storage_registration();

CREATE OR REPLACE FUNCTION starexec.guard_execution_storage_asset_reference() RETURNS trigger AS $$
DECLARE _path TEXT; _record JSONB := to_jsonb(NEW);
BEGIN
    PERFORM singleton FROM starexec.execution_storage_policy WHERE singleton FOR SHARE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'Execution storage policy is unavailable' USING ERRCODE='55000'; END IF;
    FOREACH _path IN ARRAY ARRAY[_record->>'path',_record->>'build_source_path',
        _record->>'source_path',_record->>'storage_root',_record->>'artifact_path'] LOOP
        IF EXISTS (SELECT 1 FROM starexec.execution_storage_resources r
            WHERE starexec.execution_storage_paths_overlap(r.root_path,_path)) THEN
            RAISE EXCEPTION 'Application assets cannot reference private execution storage' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER solver_execution_storage_reference BEFORE INSERT OR UPDATE OF path,build_source_path
    ON starexec.solvers FOR EACH ROW EXECUTE FUNCTION starexec.guard_execution_storage_asset_reference();
CREATE OR REPLACE TRIGGER benchmark_execution_storage_reference BEFORE INSERT OR UPDATE OF path
    ON starexec.benchmarks FOR EACH ROW EXECUTE FUNCTION starexec.guard_execution_storage_asset_reference();
CREATE OR REPLACE TRIGGER artifact_execution_storage_reference BEFORE INSERT OR UPDATE OF source_path,storage_root,artifact_path
    ON starexec.solver_build_artifacts FOR EACH ROW EXECUTE FUNCTION starexec.guard_execution_storage_asset_reference();

CREATE OR REPLACE FUNCTION starexec.solver_build_staging_outcome_conserved(_artifact BIGINT)
RETURNS BOOLEAN AS $$
DECLARE _a starexec.solver_build_artifacts%ROWTYPE; _receipt JSONB; _disposition TEXT;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND OR _a.execution_token IS NULL OR _a.execution_stopped_at IS NULL
        OR _a.execution_stop_receipt_recorded_at IS NULL OR _a.engine_identity IS NULL
        OR _a.state NOT IN ('PUBLISHED','RETIRED','REJECTED','DELETED')
        OR NOT starexec.solver_build_stop_receipt_matches(_a.execution_stop_receipt_payload,
            _a.execution_stop_receipt_sha256,_a.id,_a.origin_pair_id,_a.attempt_no,_a.engine_identity) THEN RETURN FALSE; END IF;
    _receipt := _a.execution_stop_receipt_payload::jsonb;
    IF NOT COALESCE(_receipt->>'backend' IN ('podman','kubernetes'),FALSE)
        OR _a.engine_identity NOT LIKE (_receipt->>'backend') || ':%' THEN RETURN FALSE; END IF;
    IF _a.state='DELETED' THEN RETURN _a.cleanup_finished_at IS NOT NULL; END IF;
    _disposition := _receipt->'facts'->>'disposition';
    IF _disposition IS NOT NULL THEN
        RETURN _a.state='REJECTED' AND _disposition IN ('cancelled','never_started');
    END IF;
    RETURN _a.validated_at IS NOT NULL AND _a.storage_root IS NOT NULL AND _a.byte_size IS NOT NULL AND _a.byte_size>=0
        AND _a.manifest_payload IS NOT NULL AND _a.manifest_sha256 IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

-- Commit this identity before unlinking only the execution's registered staging child.
CREATE OR REPLACE FUNCTION starexec.claim_solver_build_staging_cleanup(
    _artifact BIGINT, _token UUID, _key TEXT, _identity TEXT, _cleanup UUID, _staging_identity TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _r starexec.execution_storage_resources%ROWTYPE;
    _l starexec.solver_build_storage_leases%ROWTYPE;
    _c starexec.solver_build_storage_charges%ROWTYPE;
BEGIN
    IF _token IS NULL OR _cleanup IS NULL OR _key IS NULL OR _identity IS NULL
        OR _staging_identity IS NULL OR btrim(_staging_identity)='' THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND OR _a.execution_token IS DISTINCT FROM _token THEN RETURN FALSE; END IF;
    IF NOT (_a.state='DELETED' AND _a.cleanup_finished_at IS NOT NULL) THEN
        PERFORM starexec.lock_user_solver_storage_for_deletion(_a.origin_owner_id);
    END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE NOWAIT;
    SELECT * INTO _r FROM starexec.execution_storage_resources WHERE resource_key=_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _r.observed_identity IS DISTINCT FROM _identity
        OR _a.staging_path IS DISTINCT FROM _r.root_path || '/build-' || _token::text THEN RETURN FALSE; END IF;
    SELECT * INTO _l FROM starexec.solver_build_storage_leases WHERE artifact_id=_artifact FOR UPDATE NOWAIT;
    IF NOT FOUND OR _l.resource_key IS DISTINCT FROM _key OR _l.execution_token IS DISTINCT FROM _token
        OR _l.cleanup_token IS NOT NULL AND (_l.cleanup_token IS DISTINCT FROM _cleanup
            OR _l.cleanup_staging_identity IS DISTINCT FROM _staging_identity) THEN RETURN FALSE; END IF;
    IF _l.released_at IS NOT NULL THEN RETURN _l.cleanup_token=_cleanup; END IF;
    PERFORM singleton FROM starexec.execution_storage_policy WHERE singleton FOR SHARE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    IF NOT starexec.execution_storage_root_unreferenced(_r.root_path)
        OR NOT starexec.solver_build_staging_outcome_conserved(_artifact) THEN RETURN FALSE; END IF;
    SELECT * INTO _c FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact AND storage_kind='STAGING' FOR UPDATE NOWAIT;
    IF NOT FOUND OR _c.owner_id IS DISTINCT FROM _a.origin_owner_id OR _c.path IS DISTINCT FROM _a.staging_path
        OR _c.released_at IS NULL AND (_c.limit_bytes<=0 OR _c.charged_bytes IS DISTINCT FROM _c.limit_bytes)
        OR _c.released_at IS NOT NULL AND NOT (_a.state='DELETED' AND _a.cleanup_finished_at IS NOT NULL) THEN RETURN FALSE; END IF;
    IF _l.cleanup_token IS NULL THEN
        UPDATE starexec.solver_build_storage_leases SET cleanup_token=_cleanup,cleanup_staging_identity=_staging_identity,
            cleanup_started_at=clock_timestamp()
            WHERE artifact_id=_artifact;
    END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Called only after forced staging removal and positive absence of every byte in the resource.
CREATE OR REPLACE FUNCTION starexec.finish_solver_build_staging_cleanup(
    _artifact BIGINT, _token UUID, _key TEXT, _identity TEXT, _cleanup UUID, _staging_identity TEXT) RETURNS BOOLEAN AS $$
DECLARE _a starexec.solver_build_artifacts%ROWTYPE; _c starexec.solver_build_storage_charges%ROWTYPE; _finished TIMESTAMPTZ;
BEGIN
    IF NOT starexec.claim_solver_build_staging_cleanup(_artifact,_token,_key,_identity,_cleanup,_staging_identity) THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_storage_leases WHERE artifact_id=_artifact AND released_at IS NOT NULL) THEN RETURN TRUE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    SELECT * INTO _c FROM starexec.solver_build_storage_charges WHERE artifact_id=_artifact AND storage_kind='STAGING';
    _finished := clock_timestamp();
    UPDATE starexec.solver_build_storage_leases SET cleanup_finished_at=_finished,released_at=_finished WHERE artifact_id=_artifact;
    IF _c.released_at IS NULL THEN
        UPDATE starexec.users SET disk_size=disk_size-_c.charged_bytes
            WHERE id=_a.origin_owner_id AND disk_size>=_c.charged_bytes;
        IF NOT FOUND THEN RAISE EXCEPTION 'Staging settlement exceeds conserved owner usage'; END IF;
        UPDATE starexec.solver_build_storage_charges SET released_at=_finished
            WHERE artifact_id=_artifact AND storage_kind='STAGING';
    END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.guard_solver_build_storage_lease() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Build storage lease history must be retained'; END IF;
    IF (NEW.artifact_id,NEW.execution_token,NEW.resource_key,NEW.created_at)
        IS DISTINCT FROM (OLD.artifact_id,OLD.execution_token,OLD.resource_key,OLD.created_at)
        OR OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD
        OR OLD.cleanup_token IS NOT NULL AND (NEW.cleanup_token,NEW.cleanup_staging_identity,NEW.cleanup_started_at)
            IS DISTINCT FROM (OLD.cleanup_token,OLD.cleanup_staging_identity,OLD.cleanup_started_at)
        OR OLD.cleanup_finished_at IS NOT NULL AND NEW.cleanup_finished_at IS DISTINCT FROM OLD.cleanup_finished_at THEN
        RAISE EXCEPTION 'Build storage lease and cleanup identity are immutable';
    END IF;
    IF NEW.cleanup_token IS NOT NULL AND NOT starexec.solver_build_staging_outcome_conserved(NEW.artifact_id) THEN
        RAISE EXCEPTION 'Staging cleanup requires conserved outcome and matching physical stop';
    END IF;
    IF NEW.released_at IS NOT NULL AND NEW.cleanup_finished_at IS NULL AND (
        NOT EXISTS (SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.execution_token=NEW.execution_token AND a.state='DELETED' AND a.cleanup_finished_at IS NOT NULL)
        OR EXISTS (SELECT 1 FROM starexec.solver_build_storage_charges c WHERE c.artifact_id=NEW.artifact_id AND c.released_at IS NULL)) THEN
        RAISE EXCEPTION 'Build storage lease release requires completed cleanup and settlement';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.guard_solver_build_storage_charge() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Build storage charge history must be retained'; END IF;
    IF (NEW.artifact_id,NEW.storage_kind,NEW.owner_id,NEW.path,NEW.limit_bytes,NEW.created_at)
        IS DISTINCT FROM (OLD.artifact_id,OLD.storage_kind,OLD.owner_id,OLD.path,OLD.limit_bytes,OLD.created_at)
        OR OLD.verified_at IS NOT NULL AND (NEW.verified_bytes,NEW.verified_at,NEW.charged_bytes)
            IS DISTINCT FROM (OLD.verified_bytes,OLD.verified_at,OLD.charged_bytes)
        OR OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Build storage charge identity is immutable';
    END IF;
    IF NEW.verified_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.storage_root=NEW.path AND a.validated_at IS NOT NULL AND a.byte_size=NEW.verified_bytes) THEN
        RAISE EXCEPTION 'Output charge requires conserved verified bytes';
    END IF;
    IF NEW.released_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM starexec.solver_build_artifacts a WHERE a.id=NEW.artifact_id
            AND a.state='DELETED' AND a.cleanup_finished_at IS NOT NULL)
        AND NOT (NEW.storage_kind='STAGING' AND EXISTS (
            SELECT 1 FROM starexec.solver_build_storage_leases l JOIN starexec.solver_build_artifacts a ON a.id=l.artifact_id
            WHERE l.artifact_id=NEW.artifact_id AND l.execution_token=a.execution_token
                AND l.cleanup_finished_at IS NOT NULL AND l.released_at IS NOT NULL
                AND starexec.solver_build_staging_outcome_conserved(a.id))) THEN
        RAISE EXCEPTION 'Build storage settlement requires completed cleanup';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.check_released_build_storage_settled() RETURNS trigger AS $$
BEGIN
    IF NEW.released_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM starexec.solver_build_storage_charges
        WHERE artifact_id=NEW.artifact_id AND storage_kind='STAGING' AND released_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Released execution storage still has an unsettled staging charge';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='starexec.solver_build_storage_leases'::regclass
        AND tgname='released_build_storage_settled') THEN
        CREATE CONSTRAINT TRIGGER released_build_storage_settled AFTER UPDATE ON starexec.solver_build_storage_leases
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION starexec.check_released_build_storage_settled();
    END IF;
END $$;
