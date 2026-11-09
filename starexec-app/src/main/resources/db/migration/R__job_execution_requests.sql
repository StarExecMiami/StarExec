-- Reject duplicate keys before interpretation; preserve the original request text separately.
CREATE OR REPLACE FUNCTION starexec.job_execution_json_unique(_value JSON) RETURNS BOOLEAN AS $$
DECLARE _entry RECORD; _keys TEXT[] := ARRAY[]::TEXT[]; _child JSON;
BEGIN
    IF json_typeof(_value)='object' THEN
        FOR _entry IN SELECT key,value FROM json_each(_value) LOOP
            IF _entry.key=ANY(_keys) OR NOT starexec.job_execution_json_unique(_entry.value) THEN RETURN FALSE; END IF;
            _keys := array_append(_keys,_entry.key);
        END LOOP;
    ELSIF json_typeof(_value)='array' THEN
        FOR _child IN SELECT value FROM json_array_elements(_value) LOOP
            IF NOT starexec.job_execution_json_unique(_child) THEN RETURN FALSE; END IF;
        END LOOP;
    END IF;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

-- Lock in ordinary dispatch order; a claim or a request never proves physical execution.
CREATE OR REPLACE FUNCTION starexec.guard_job_execution_request() RETURNS trigger AS $$
DECLARE
    _initial starexec.execution_storage_claims%ROWTYPE;
    _claim starexec.execution_storage_claims%ROWTYPE;
    _resource starexec.execution_storage_resources%ROWTYPE;
    _usage BIGINT; _quota BIGINT; _payload JSONB;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'Ordinary creation mutation requires a current read-committed snapshot' USING ERRCODE='40001';
    END IF;
    SELECT * INTO _initial FROM starexec.execution_storage_claims WHERE execution_token=NEW.execution_token;
    IF NOT FOUND OR _initial.lease_kind<>'JOB' OR _initial.origin_pair_id IS DISTINCT FROM NEW.pair_id
        OR _initial.attempt_no IS DISTINCT FROM NEW.attempt_no OR _initial.origin_owner_id IS DISTINCT FROM NEW.owner_id
        OR _initial.resource_key IS DISTINCT FROM NEW.resource_key OR _initial.use_started_at IS NULL
        OR _initial.released_at IS NOT NULL
        OR NOT starexec.lock_current_job_storage_pair(NEW.pair_id,NEW.attempt_no,NEW.owner_id,_initial.prior_execution_id,TRUE) THEN
        RAISE EXCEPTION 'Ordinary creation requires the current used job reservation';
    END IF;
    SELECT disk_size,disk_quota INTO _usage,_quota FROM starexec.users WHERE id=NEW.owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _quota IS NULL OR _usage<0
        OR _usage<starexec.active_execution_storage_bytes(NEW.owner_id) THEN
        RAISE EXCEPTION 'Ordinary creation requires fully funded storage';
    END IF;
    SELECT * INTO _resource FROM starexec.execution_storage_resources WHERE resource_key=NEW.resource_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _resource.observed_identity IS DISTINCT FROM NEW.resource_identity
        OR starexec.execution_storage_root_unreferenced(_resource.root_path) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary creation resource incarnation differs';
    END IF;
    SELECT * INTO _claim FROM starexec.execution_storage_claims WHERE execution_token=NEW.execution_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _claim IS DISTINCT FROM _initial OR _claim.limit_bytes IS DISTINCT FROM _resource.capacity_bytes
        OR octet_length(NEW.request_payload)>LEAST(_claim.limit_bytes,16777216) THEN
        RAISE EXCEPTION 'Ordinary creation reservation changed or request exceeds its bound';
    END IF;
    IF EXISTS (SELECT 1 FROM starexec.job_execution_receipts
        WHERE execution_token=NEW.execution_token AND receipt_kind='CLEANUP_REQUEST') THEN
        RAISE EXCEPTION 'Ordinary cleanup intent fences creation';
    END IF;
    IF starexec.job_execution_json_unique(NEW.request_payload::json) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary creation request contains duplicate JSON keys';
    END IF;
    _payload := NEW.request_payload::jsonb;
    IF (_payload @> jsonb_build_object('Image',NEW.image_identity)
        AND jsonb_typeof(_payload->'HostConfig')='object'
        AND _payload->'HostConfig'->'ReadonlyRootfs'='true'::jsonb
        AND jsonb_typeof(_payload->'Entrypoint')='array'
        AND jsonb_typeof(_payload->'Cmd')='array'
        AND jsonb_typeof(_payload->'Env')='array'
        AND _payload->'WorkingDir'='"/starexec/execution/work"'::jsonb
        AND _payload->'Labels' @> jsonb_build_object(
            'starexec.managed','true','starexec.label.version','2','starexec.kind','job-pair',
            'starexec.job.layout','private-v1','starexec.job.token',NEW.execution_token::text,
            'starexec.storage.resource',NEW.resource_key,'starexec.job.inputs',NEW.preparation_sha256,
            'starexec.pair.id',NEW.pair_id::text,'starexec.attempt',NEW.attempt_no::text,
            'starexec.exec.id',NEW.execution_id::text)) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary creation request shape or ownership differs';
    END IF;
    NEW.consumed_at := clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Conserve the cancellation locator in the same transaction as first consumption.
CREATE OR REPLACE FUNCTION starexec.bind_job_execution_request() RETURNS trigger AS $$
DECLARE _prior INTEGER;
BEGIN
    SELECT prior_execution_id INTO _prior FROM starexec.execution_storage_claims WHERE execution_token=NEW.execution_token;
    UPDATE starexec.job_pairs SET sge_id=NEW.execution_id
        WHERE id=NEW.pair_id AND sge_id IS NOT DISTINCT FROM _prior;
    IF NOT FOUND THEN RAISE EXCEPTION 'Ordinary creation cancellation locator changed'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS job_execution_request_guard ON starexec.job_execution_requests;
CREATE TRIGGER job_execution_request_guard BEFORE INSERT ON starexec.job_execution_requests
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_job_execution_request();
DROP TRIGGER IF EXISTS job_execution_request_binding ON starexec.job_execution_requests;
CREATE TRIGGER job_execution_request_binding AFTER INSERT ON starexec.job_execution_requests
    FOR EACH ROW EXECUTE FUNCTION starexec.bind_job_execution_request();

-- Only an acknowledged first TRUE may precede a single send; an existing row is observation only.
CREATE OR REPLACE FUNCTION starexec.consume_job_execution_request(
    _token UUID, _pair INTEGER, _attempt INTEGER, _owner INTEGER, _key TEXT, _identity TEXT,
    _execution INTEGER, _image TEXT, _preparation TEXT, _transport TEXT, _payload TEXT, _sha256 TEXT) RETURNS BOOLEAN AS $$
DECLARE _inserted INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM starexec.job_execution_requests
        WHERE execution_token=_token OR (pair_id=_pair AND attempt_no=_attempt)) THEN RETURN FALSE; END IF;
    INSERT INTO starexec.job_execution_requests
        (execution_token,pair_id,attempt_no,owner_id,resource_key,resource_identity,execution_id,
            container_name,image_identity,preparation_sha256,transport,request_payload,request_sha256)
        VALUES (_token,_pair,_attempt,_owner,_key,_identity,_execution,
            'starexec-pair-' || _pair::text || '-attempt-' || _attempt::text,
            _image,_preparation,_transport,_payload,_sha256) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS _inserted=ROW_COUNT;
    RETURN _inserted=1;
END;
$$ LANGUAGE plpgsql;

