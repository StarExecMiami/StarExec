-- Receipt structure conserves trusted observations; SQL cannot attest kernel or filesystem state.
CREATE OR REPLACE FUNCTION starexec.guard_job_execution_receipt() RETURNS trigger AS $$
DECLARE
    _claim starexec.execution_storage_claims%ROWTYPE;
    _initial starexec.execution_storage_claims%ROWTYPE;
    _resource starexec.execution_storage_resources%ROWTYPE;
    _request starexec.job_execution_requests%ROWTYPE;
    _created starexec.job_execution_receipts%ROWTYPE;
    _stop starexec.job_execution_receipts%ROWTYPE;
    _cleanup starexec.job_execution_receipts%ROWTYPE;
    _payload JSONB; _facts JSONB; _usage BIGINT; _finished TIMESTAMPTZ;
    _start BOOLEAN := FALSE;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'Ordinary phase mutations require a current read-committed snapshot' USING ERRCODE='40001';
    END IF;
    SELECT * INTO _initial FROM starexec.execution_storage_claims WHERE execution_token=NEW.execution_token;
    IF NOT FOUND OR _initial.lease_kind<>'JOB' OR _initial.use_started_at IS NULL OR _initial.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'Ordinary phase requires an active used job reservation';
    END IF;
    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=NEW.execution_token;
    IF NEW.receipt_kind='CREATED' AND NEW.receipt_payload::jsonb->'facts' ? 'start_requested' THEN
        IF jsonb_typeof(NEW.receipt_payload::jsonb->'facts'->'start_requested') IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'Ordinary start claim requires a boolean request';
        END IF;
        _start := (NEW.receipt_payload::jsonb->'facts'->>'start_requested')::boolean;
    END IF;
    IF (NEW.receipt_kind='RELEASE' OR _start) AND (_request.execution_token IS NULL
        OR NOT starexec.lock_current_job_storage_pair(_request.pair_id,_request.attempt_no,
            _request.owner_id,_request.execution_id,TRUE)) THEN
        RAISE EXCEPTION 'Ordinary action requires the exact current admitted locator';
    END IF;
    SELECT disk_size INTO _usage FROM starexec.users WHERE id=_initial.origin_owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _usage<starexec.active_execution_storage_bytes(_initial.origin_owner_id) THEN
        RAISE EXCEPTION 'Ordinary phase requires fully funded storage';
    END IF;
    SELECT * INTO _resource FROM starexec.execution_storage_resources WHERE resource_key=_initial.resource_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _resource.capacity_bytes IS DISTINCT FROM _initial.limit_bytes
        OR starexec.execution_storage_root_unreferenced(_resource.root_path) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary phase resource incarnation is unavailable';
    END IF;
    SELECT * INTO _claim FROM starexec.execution_storage_claims WHERE execution_token=NEW.execution_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _claim IS DISTINCT FROM _initial
        OR octet_length(NEW.receipt_payload)>LEAST(_claim.limit_bytes,16777216) THEN
        RAISE EXCEPTION 'Ordinary phase reservation changed or payload exceeds its bound';
    END IF;
    -- Re-read after the claim lock: a creation may have committed after the first lookup.
    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=NEW.execution_token;
    IF starexec.job_execution_json_unique(NEW.receipt_payload::json) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary phase contains duplicate JSON keys';
    END IF;
    _payload := NEW.receipt_payload::jsonb;
    _facts := _payload->'facts';
    IF (_payload @> jsonb_build_object('version',1,'kind',NEW.receipt_kind,
        'execution_token',NEW.execution_token::text,'pair_id',_claim.origin_pair_id,
        'attempt_no',_claim.attempt_no,'owner_id',_claim.origin_owner_id,
        'resource_key',_claim.resource_key,'resource_identity',_resource.observed_identity,
        'operation_token',NEW.operation_token::text,'engine_identity',NEW.engine_identity,
        'request_sha256',_request.request_sha256,'preparation_sha256',_request.preparation_sha256)
        AND jsonb_typeof(_facts)='object') IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Ordinary phase envelope differs from conserved ownership';
    END IF;
    SELECT * INTO _cleanup FROM starexec.job_execution_receipts
        WHERE execution_token=NEW.execution_token AND receipt_kind='CLEANUP_REQUEST';
    IF _cleanup.execution_token IS NOT NULL AND NEW.receipt_kind<>'CLEANUP' THEN
        RAISE EXCEPTION 'Ordinary cleanup intent fences later execution phases';
    END IF;
    SELECT * INTO _created FROM starexec.job_execution_receipts
        WHERE execution_token=NEW.execution_token AND receipt_kind='CREATED';
    SELECT * INTO _stop FROM starexec.job_execution_receipts
        WHERE execution_token=NEW.execution_token AND receipt_kind='STOP';
    IF _request.execution_token IS NOT NULL THEN
        IF NEW.engine_identity IS NULL OR NEW.engine_identity !~ ('^podman:' || _request.execution_id::text || ':[0-9a-f]{64}$')
            OR NEW.receipt_kind<>'CREATED' AND NEW.engine_identity IS DISTINCT FROM _created.engine_identity THEN
            RAISE EXCEPTION 'Ordinary phase engine differs from conserved creation';
        END IF;
    ELSIF NEW.receipt_kind NOT IN ('CLEANUP_REQUEST','CLEANUP') OR NEW.engine_identity IS NOT NULL THEN
        RAISE EXCEPTION 'Only fenced never-created cleanup is valid without a consumed request';
    END IF;
    CASE NEW.receipt_kind
    WHEN 'CREATED' THEN
        IF (_facts @> jsonb_build_object('resource_id',split_part(NEW.engine_identity,':',3),
            'container_name',_request.container_name,'image_id',_request.image_identity)) IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Ordinary creation observation identity differs';
        END IF;
        IF _start AND (_stop.execution_token IS NOT NULL
            OR jsonb_typeof(_facts->'configuration_payload') IS DISTINCT FROM 'string'
            OR jsonb_typeof((_facts->>'configuration_payload')::jsonb) IS DISTINCT FROM 'object'
            OR starexec.job_execution_json_unique((_facts->>'configuration_payload')::json) IS DISTINCT FROM TRUE
            OR _facts->>'configuration_sha256' IS DISTINCT FROM encode(sha256(convert_to(_facts->>'configuration_payload','UTF8')),'hex')) THEN
            RAISE EXCEPTION 'Ordinary start claim requires exact configured identity before stop';
        END IF;
    WHEN 'RELEASE' THEN
        IF _stop.execution_token IS NOT NULL OR jsonb_typeof(_facts->'runtime_payload') IS DISTINCT FROM 'string'
            OR starexec.solver_build_release_observation_valid(_facts->>'runtime_payload') IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Ordinary release requires complete trusted runtime observation before stop';
        END IF;
    WHEN 'STOP' THEN
        IF (_facts @> jsonb_build_object('resource_id',split_part(NEW.engine_identity,':',3),
            'image_id',_request.image_identity,'pid',0,'running',FALSE,'paused',FALSE,'restarting',FALSE,
            'privileged',FALSE,'private_pid_namespace',TRUE,'restart_policy','no')
            AND jsonb_typeof(_facts->'finished_at')='string'
            AND jsonb_typeof(_facts->'started_at')='string'
            AND jsonb_typeof(_facts->'exit_code')='number'
            AND (_facts->>'exit_code') ~ '^-?[0-9]+$') IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Ordinary stop requires a complete positively stopped provider scope';
        END IF;
        IF _facts->>'status'='created' THEN
            -- A never-started provider retains zero dates; they are not completion times.
            IF _facts->>'disposition' IS DISTINCT FROM 'never_started'
                OR (_facts->>'started_at')::timestamptz IS DISTINCT FROM '0001-01-01T00:00:00Z'::timestamptz
                OR (_facts->>'finished_at')::timestamptz IS DISTINCT FROM '0001-01-01T00:00:00Z'::timestamptz
                OR (_facts->>'exit_code')::numeric<>0
                OR EXISTS (SELECT 1 FROM starexec.job_execution_receipts
                    WHERE execution_token=NEW.execution_token AND receipt_kind='RELEASE') THEN
                RAISE EXCEPTION 'Ordinary created stop requires an unreleased positively never-started scope';
            END IF;
        ELSE
            IF (_facts->>'status' IN ('exited','dead')) IS DISTINCT FROM TRUE THEN
                RAISE EXCEPTION 'Ordinary stop requires a complete positively stopped provider scope';
            END IF;
            _finished := (_facts->>'finished_at')::timestamptz;
            IF _finished IS NULL OR NOT isfinite(_finished) OR _finished<='epoch'::timestamptz
                OR NOT isfinite((_facts->>'started_at')::timestamptz)
                OR (_facts->>'started_at')::timestamptz>_finished THEN
                RAISE EXCEPTION 'Ordinary stop time observations are invalid';
            END IF;
        END IF;
        IF _facts ? 'disposition' AND (jsonb_typeof(_facts->'disposition')='string'
            AND _facts->>'disposition' IN ('cancelled','never_started')) IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Ordinary stop disposition is unknown';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM starexec.job_execution_receipts
            WHERE execution_token=NEW.execution_token AND receipt_kind='RELEASE')
            AND COALESCE(_facts->>'disposition','') NOT IN ('cancelled','never_started') THEN
            RAISE EXCEPTION 'An unreleased hold cannot produce a normal execution result';
        END IF;
    WHEN 'OUTPUT' THEN
        IF _stop.execution_token IS NULL OR _facts->>'stop_sha256' IS DISTINCT FROM _stop.receipt_sha256
            OR jsonb_typeof(_facts->'manifest_payload') IS DISTINCT FROM 'string'
            OR jsonb_typeof((_facts->>'manifest_payload')::jsonb) IS DISTINCT FROM 'object'
            OR starexec.job_execution_json_unique((_facts->>'manifest_payload')::json) IS DISTINCT FROM TRUE
            OR _facts->>'manifest_sha256' IS DISTINCT FROM encode(sha256(convert_to(_facts->>'manifest_payload','UTF8')),'hex') THEN
            RAISE EXCEPTION 'Ordinary output requires the exact stop and immutable output manifest';
        END IF;
    WHEN 'CLEANUP_REQUEST' THEN
        IF jsonb_typeof(_facts->'staging_identity') IS DISTINCT FROM 'string' OR btrim(_facts->>'staging_identity')='' THEN
            RAISE EXCEPTION 'Ordinary cleanup requires the observed owned staging identity';
        END IF;
        IF _request.execution_token IS NULL THEN
            IF _facts->>'reason' IS DISTINCT FROM 'never_created' THEN
                RAISE EXCEPTION 'Ordinary no-create cleanup requires a locked never-created decision';
            END IF;
        ELSIF _stop.execution_token IS NULL OR _facts->>'stop_sha256' IS DISTINCT FROM _stop.receipt_sha256
            OR NOT (_stop.receipt_payload::jsonb->'facts' ? 'disposition') AND NOT EXISTS (
                SELECT 1 FROM starexec.job_execution_receipts WHERE execution_token=NEW.execution_token AND receipt_kind='OUTPUT') THEN
            RAISE EXCEPTION 'Ordinary cleanup requires a conserved stopped outcome';
        END IF;
    WHEN 'CLEANUP' THEN
        IF _cleanup.execution_token IS NULL OR NEW.operation_token IS DISTINCT FROM _cleanup.operation_token
            OR NEW.engine_identity IS DISTINCT FROM _cleanup.engine_identity
            OR _facts->>'staging_identity' IS DISTINCT FROM _cleanup.receipt_payload::jsonb->'facts'->>'staging_identity'
            OR (_facts @> jsonb_build_object('entries',0,'provider_absent',TRUE)) IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Ordinary cleanup completion requires the same operation and positively empty resource';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unknown ordinary execution phase';
    END CASE;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS job_execution_receipt_guard ON starexec.job_execution_receipts;
CREATE TRIGGER job_execution_receipt_guard BEFORE INSERT ON starexec.job_execution_receipts
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_job_execution_receipt();

-- A committed row is durable observation, never replacement permission for an uncertain side effect.
CREATE OR REPLACE FUNCTION starexec.record_job_execution_receipt(
    _token UUID, _kind TEXT, _operation UUID, _engine TEXT, _payload TEXT, _sha256 TEXT) RETURNS BOOLEAN AS $$
DECLARE _inserted INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM starexec.job_execution_receipts
        WHERE execution_token=_token AND receipt_kind=_kind) THEN RETURN FALSE; END IF;
    INSERT INTO starexec.job_execution_receipts
        (execution_token,receipt_kind,operation_token,engine_identity,receipt_payload,receipt_sha256)
        VALUES (_token,_kind,_operation,_engine,_payload,_sha256) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS _inserted=ROW_COUNT;
    RETURN _inserted=1;
END;
$$ LANGUAGE plpgsql;

