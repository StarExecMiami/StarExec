-- All ownership mutations follow current job locks and refuse contended accounting/resources.
CREATE OR REPLACE FUNCTION starexec.lock_current_job_storage_pair(
    _pair INTEGER, _attempt INTEGER, _owner INTEGER, _prior INTEGER, _dispatch BOOLEAN) RETURNS BOOLEAN AS $$
DECLARE _job INTEGER; _j starexec.jobs%ROWTYPE; _p starexec.job_pairs%ROWTYPE; _current INTEGER;
BEGIN
    IF _dispatch IS NULL OR _attempt IS NULL OR _attempt<=0 THEN RETURN FALSE; END IF;
    SELECT job_id INTO _job FROM starexec.job_pairs WHERE id=_pair;
    SELECT * INTO _j FROM starexec.jobs WHERE id=_job FOR UPDATE NOWAIT;
    IF NOT FOUND OR _j.buildjob IS DISTINCT FROM FALSE OR _j.user_id IS DISTINCT FROM _owner
        OR _dispatch AND (_j.deleted IS TRUE OR _j.killed IS TRUE OR _j.paused IS TRUE) THEN RETURN FALSE; END IF;
    SELECT * INTO _p FROM starexec.job_pairs WHERE id=_pair AND job_id=_job FOR UPDATE NOWAIT;
    IF NOT FOUND OR _p.sge_id IS DISTINCT FROM _prior
        OR _dispatch AND (_p.status_code IS NULL OR _p.status_code NOT IN (1,2)) THEN RETURN FALSE; END IF;
    SELECT current_attempt_no INTO _current FROM starexec.job_pair_attempts WHERE pair_id=_pair FOR UPDATE NOWAIT;
    RETURN FOUND AND _current=_attempt;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.active_execution_storage_bytes(_owner INTEGER) RETURNS NUMERIC AS $$
    SELECT (SELECT COALESCE(sum(charged_bytes),0) FROM starexec.solver_build_storage_charges
        WHERE owner_id=_owner AND released_at IS NULL)
        +(SELECT COALESCE(sum(limit_bytes),0) FROM starexec.execution_storage_claims
        WHERE lease_kind='JOB' AND origin_owner_id=_owner AND released_at IS NULL);
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION starexec.guard_execution_storage_claim() RETURNS trigger AS $$
DECLARE _r starexec.execution_storage_resources%ROWTYPE; _usage BIGINT; _quota BIGINT;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Execution storage ownership history must be retained'; END IF;
    IF TG_OP='UPDATE' THEN
        IF (NEW.execution_token,NEW.resource_key,NEW.lease_kind,NEW.build_artifact_id,
            NEW.origin_pair_id,NEW.attempt_no,NEW.origin_owner_id,NEW.prior_execution_id,NEW.limit_bytes,NEW.created_at)
            IS DISTINCT FROM (OLD.execution_token,OLD.resource_key,OLD.lease_kind,OLD.build_artifact_id,
            OLD.origin_pair_id,OLD.attempt_no,OLD.origin_owner_id,OLD.prior_execution_id,OLD.limit_bytes,OLD.created_at)
            OR OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD
            OR OLD.use_started_at IS NOT NULL AND NEW.use_started_at IS DISTINCT FROM OLD.use_started_at THEN
            RAISE EXCEPTION 'Execution storage ownership identity is immutable';
        END IF;
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    END IF;
    IF NEW.lease_kind='JOB' THEN
        IF TG_OP='INSERT' AND (NEW.use_started_at IS NOT NULL OR NEW.released_at IS NOT NULL)
            OR NEW.use_started_at IS NOT NULL AND NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'Used job storage requires positive termination and registered cleanup';
        END IF;
        IF NOT starexec.lock_current_job_storage_pair(NEW.origin_pair_id,NEW.attempt_no,
            NEW.origin_owner_id,NEW.prior_execution_id,NEW.released_at IS NULL) THEN
            RAISE EXCEPTION 'Job storage ownership does not match the current attempt';
        END IF;
        SELECT disk_size,disk_quota INTO _usage,_quota FROM starexec.users
            WHERE id=NEW.origin_owner_id FOR UPDATE NOWAIT;
        IF NOT FOUND OR _usage IS NULL OR _quota IS NULL OR _usage<0
            OR _usage<starexec.active_execution_storage_bytes(NEW.origin_owner_id)
            OR TG_OP='INSERT' AND (_quota<_usage OR NEW.limit_bytes>_quota-_usage) THEN
            RAISE EXCEPTION 'Job storage allowance is not fully funded';
        END IF;
    END IF;
    SELECT * INTO _r FROM starexec.execution_storage_resources WHERE resource_key=NEW.resource_key FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'Execution storage resource is unregistered'; END IF;
    IF NEW.lease_kind='BUILD' THEN
        IF NOT EXISTS (SELECT 1 FROM starexec.solver_build_storage_leases l
            WHERE l.artifact_id=NEW.build_artifact_id AND l.execution_token=NEW.execution_token
                AND l.resource_key=NEW.resource_key AND l.created_at=NEW.created_at
                AND l.released_at IS NOT DISTINCT FROM NEW.released_at) THEN
            RAISE EXCEPTION 'Build storage ownership must match its conserved lease';
        END IF;
    ELSIF NEW.limit_bytes IS DISTINCT FROM _r.capacity_bytes
        OR NOT starexec.execution_storage_root_unreferenced(_r.root_path) THEN
        RAISE EXCEPTION 'Job storage must book the complete dedicated resource';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS execution_storage_claim_guard ON starexec.execution_storage_claims;
CREATE TRIGGER execution_storage_claim_guard BEFORE INSERT OR UPDATE OR DELETE
    ON starexec.execution_storage_claims FOR EACH ROW EXECUTE FUNCTION starexec.guard_execution_storage_claim();

-- AFTER INSERT avoids charging a row discarded by INSERT ... ON CONFLICT DO NOTHING.
CREATE OR REPLACE FUNCTION starexec.account_job_storage_claim() RETURNS trigger AS $$
BEGIN
    IF NEW.lease_kind<>'JOB' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        UPDATE starexec.users SET disk_size=disk_size+NEW.limit_bytes
            WHERE id=NEW.origin_owner_id AND disk_size>=0 AND disk_quota>=disk_size
                AND NEW.limit_bytes<=disk_quota-disk_size;
        IF NOT FOUND THEN RAISE EXCEPTION 'Job storage quota reservation failed'; END IF;
    ELSIF OLD.released_at IS NULL AND NEW.released_at IS NOT NULL THEN
        UPDATE starexec.users SET disk_size=disk_size-NEW.limit_bytes
            WHERE id=NEW.origin_owner_id AND disk_size>=NEW.limit_bytes;
        IF NOT FOUND THEN RAISE EXCEPTION 'Job storage settlement exceeds conserved owner usage'; END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS execution_storage_claim_accounting ON starexec.execution_storage_claims;
CREATE TRIGGER execution_storage_claim_accounting AFTER INSERT OR UPDATE
    ON starexec.execution_storage_claims FOR EACH ROW EXECUTE FUNCTION starexec.account_job_storage_claim();

CREATE OR REPLACE FUNCTION starexec.reserve_job_storage(
    _pair INTEGER, _attempt INTEGER, _token UUID, _key TEXT, _identity TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _owner INTEGER; _prior INTEGER; _usage BIGINT; _quota BIGINT;
    _r starexec.execution_storage_resources%ROWTYPE; _l starexec.execution_storage_claims%ROWTYPE;
BEGIN
    IF _token IS NULL OR _token='00000000-0000-0000-0000-000000000000'::uuid
        OR _key IS NULL OR _identity IS NULL THEN RETURN FALSE; END IF;
    SELECT j.user_id,p.sge_id INTO _owner,_prior FROM starexec.jobs j JOIN starexec.job_pairs p ON p.job_id=j.id WHERE p.id=_pair;
    IF NOT FOUND OR NOT starexec.lock_current_job_storage_pair(_pair,_attempt,_owner,_prior,TRUE) THEN RETURN FALSE; END IF;
    SELECT disk_size,disk_quota INTO _usage,_quota FROM starexec.users WHERE id=_owner FOR UPDATE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _quota IS NULL OR _usage<0
        OR _usage<starexec.active_execution_storage_bytes(_owner) THEN RETURN FALSE; END IF;
    SELECT * INTO _r FROM starexec.execution_storage_resources WHERE resource_key=_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _r.observed_identity IS DISTINCT FROM _identity
        OR NOT starexec.execution_storage_root_unreferenced(_r.root_path) THEN RETURN FALSE; END IF;
    SELECT * INTO _l FROM starexec.execution_storage_claims WHERE execution_token=_token FOR UPDATE NOWAIT;
    IF FOUND THEN RETURN _l.lease_kind='JOB' AND _l.origin_pair_id=_pair AND _l.attempt_no=_attempt
        AND _l.origin_owner_id=_owner AND _l.prior_execution_id IS NOT DISTINCT FROM _prior
        AND _l.resource_key=_key AND _l.limit_bytes=_r.capacity_bytes
        AND _l.released_at IS NULL AND _l.use_started_at IS NULL; END IF;
    IF _quota<_usage OR _r.capacity_bytes>_quota-_usage
        OR EXISTS (SELECT 1 FROM starexec.execution_storage_claims
            WHERE resource_key=_key AND released_at IS NULL
                OR lease_kind='JOB' AND origin_pair_id=_pair AND (released_at IS NULL OR attempt_no=_attempt)) THEN RETURN FALSE; END IF;
    INSERT INTO starexec.execution_storage_claims
        (execution_token,resource_key,lease_kind,origin_pair_id,attempt_no,origin_owner_id,prior_execution_id,limit_bytes)
        VALUES (_token,_key,'JOB',_pair,_attempt,_owner,_prior,_r.capacity_bytes);
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- Consume before the first filesystem mutation; this marker never authorizes engine execution.
CREATE OR REPLACE FUNCTION starexec.begin_job_storage_use(_token UUID, _key TEXT, _identity TEXT) RETURNS BOOLEAN AS $$
DECLARE _l starexec.execution_storage_claims%ROWTYPE;
BEGIN
    SELECT * INTO _l FROM starexec.execution_storage_claims WHERE execution_token=_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _l.lease_kind<>'JOB' OR _l.resource_key IS DISTINCT FROM _key
        OR _l.released_at IS NOT NULL OR _l.use_started_at IS NOT NULL
        OR NOT EXISTS (SELECT 1 FROM starexec.execution_storage_resources WHERE resource_key=_key AND observed_identity=_identity)
        THEN RETURN FALSE; END IF;
    UPDATE starexec.execution_storage_claims SET use_started_at=clock_timestamp() WHERE execution_token=_token;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

-- The trusted caller must independently observe an unchanged and completely empty resource.
CREATE OR REPLACE FUNCTION starexec.release_unstarted_job_storage(_token UUID, _key TEXT, _identity TEXT) RETURNS BOOLEAN AS $$
DECLARE _l starexec.execution_storage_claims%ROWTYPE;
BEGIN
    SELECT * INTO _l FROM starexec.execution_storage_claims WHERE execution_token=_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _l.lease_kind<>'JOB' OR _l.resource_key IS DISTINCT FROM _key
        OR NOT EXISTS (SELECT 1 FROM starexec.execution_storage_resources WHERE resource_key=_key AND observed_identity=_identity)
        THEN RETURN FALSE; END IF;
    IF _l.released_at IS NOT NULL THEN RETURN TRUE; END IF;
    IF _l.use_started_at IS NOT NULL THEN RETURN FALSE; END IF;
    UPDATE starexec.execution_storage_claims SET released_at=clock_timestamp() WHERE execution_token=_token;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.guard_active_job_storage_deletion() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM starexec.execution_storage_claims WHERE lease_kind='JOB' AND released_at IS NULL
        AND (TG_TABLE_NAME='users' AND origin_owner_id=OLD.id OR TG_TABLE_NAME='job_pairs' AND origin_pair_id=OLD.id)) THEN
        RAISE EXCEPTION 'Active job storage must be settled before deletion';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS active_job_storage_pair_deletion ON starexec.job_pairs;
CREATE TRIGGER active_job_storage_pair_deletion BEFORE DELETE ON starexec.job_pairs
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_active_job_storage_deletion();
DROP TRIGGER IF EXISTS active_job_storage_owner_deletion ON starexec.users;
CREATE TRIGGER active_job_storage_owner_deletion BEFORE DELETE ON starexec.users
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_active_job_storage_deletion();
