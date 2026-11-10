-- Scientific reports are immutable input data, not another quota authority.
CREATE OR REPLACE FUNCTION starexec.record_job_output_stage_statistics(
    _token UUID, _output_sha TEXT, _stage INTEGER, _node_name TEXT, _payload TEXT, _sha TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    _request starexec.job_execution_requests%ROWTYPE;
    _claim starexec.execution_storage_claims%ROWTYPE;
    _resource starexec.execution_storage_resources%ROWTYPE;
    _allocation starexec.job_output_storage_reservations%ROWTYPE;
    _output starexec.job_execution_receipts%ROWTYPE;
    _usage BIGINT; _job INTEGER; _node INTEGER; _matches INTEGER;
    _manifest JSONB; _tree JSONB; _entry JSONB; _data JSONB;
    _field TEXT; _path TEXT; _count NUMERIC; _measurement DOUBLE PRECISION;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'Retained statistics require a current read-committed snapshot' USING ERRCODE='40001';
    END IF;
    IF _token IS NULL OR _output_sha IS NULL OR _stage IS NULL OR _stage<1
        OR _node_name IS NULL OR btrim(_node_name)='' OR length(_node_name)>128
        OR _payload IS NULL OR octet_length(_payload)>16777216
        OR _sha IS NULL OR _sha !~ '^[0-9a-f]{64}$'
        OR _sha<>encode(sha256(convert_to(_payload,'UTF8')),'hex') THEN RETURN FALSE; END IF;

    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=_token;
    _job:=starexec.job_execution_original_job(_token);
    IF _request.execution_token IS NULL OR _job IS NULL
        OR NOT starexec.lock_current_job_storage_pair(_request.pair_id,_request.attempt_no,
            _request.owner_id,_request.execution_id,FALSE) THEN RETURN FALSE; END IF;
    IF NOT EXISTS (SELECT 1 FROM starexec.job_pairs p JOIN starexec.jobs j ON j.id=p.job_id
        WHERE p.id=_request.pair_id AND j.id=_job AND j.deleted IS NOT TRUE
            AND j.killed IS NOT TRUE AND p.status_code IS DISTINCT FROM 21) THEN RETURN FALSE; END IF;
    SELECT disk_size INTO _usage FROM starexec.users WHERE id=_request.owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _usage<starexec.active_execution_storage_bytes(_request.owner_id) THEN RETURN FALSE; END IF;
    SELECT * INTO _resource FROM starexec.execution_storage_resources WHERE resource_key=_request.resource_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _resource.observed_identity IS DISTINCT FROM _request.resource_identity
        OR starexec.execution_storage_root_unreferenced(_resource.root_path) IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
    SELECT * INTO _claim FROM starexec.execution_storage_claims WHERE execution_token=_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _claim.lease_kind<>'JOB' OR _claim.origin_pair_id IS DISTINCT FROM _request.pair_id
        OR _claim.attempt_no IS DISTINCT FROM _request.attempt_no OR _claim.origin_owner_id IS DISTINCT FROM _request.owner_id
        OR _claim.resource_key IS DISTINCT FROM _resource.resource_key OR _claim.limit_bytes IS DISTINCT FROM _resource.capacity_bytes
        OR _claim.use_started_at IS NULL OR _claim.released_at IS NOT NULL THEN RETURN FALSE; END IF;
    SELECT * INTO _allocation FROM starexec.job_output_storage_reservations WHERE execution_token=_token FOR SHARE NOWAIT;
    IF NOT FOUND OR _allocation.released_at IS NOT NULL OR _allocation.verified_at IS NULL
        OR _allocation.verified_bytes IS DISTINCT FROM _allocation.charged_bytes THEN RETURN FALSE; END IF;
    SELECT * INTO _output FROM starexec.job_execution_receipts WHERE execution_token=_token AND receipt_kind='OUTPUT';
    IF NOT FOUND OR _output.receipt_sha256 IS DISTINCT FROM _output_sha
        OR NOT starexec.job_output_completion_matches(_token,_output.operation_token,_output.engine_identity,_output.receipt_payload)
        OR NOT EXISTS (SELECT 1 FROM starexec.job_execution_receipts s WHERE s.execution_token=_token
            AND s.receipt_kind='STOP' AND s.engine_identity=_output.engine_identity
            AND NOT (s.receipt_payload::jsonb->'facts' ? 'disposition'))
        OR NOT EXISTS (SELECT 1 FROM starexec.job_execution_receipts WHERE execution_token=_token AND receipt_kind='RELEASE')
        OR EXISTS (SELECT 1 FROM starexec.job_execution_receipts WHERE execution_token=_token
            AND receipt_kind IN ('CLEANUP_REQUEST','CLEANUP')) THEN RETURN FALSE; END IF;

    _manifest:=(_output.receipt_payload::jsonb->'facts'->>'manifest_payload')::jsonb;
    _tree:=(_manifest->'monitor'->>'manifest_payload')::jsonb;
    IF jsonb_typeof(_tree->'entries') IS DISTINCT FROM 'array' THEN RETURN FALSE; END IF;
    _path:='stage-stats/' || _stage::text || '.json';
    SELECT count(*) INTO _matches FROM jsonb_array_elements(_tree->'entries') e WHERE e->>'path'=_path;
    IF _matches<>1 THEN RETURN FALSE; END IF;
    SELECT e INTO _entry FROM jsonb_array_elements(_tree->'entries') e WHERE e->>'path'=_path;
    IF (_entry @> jsonb_build_object('kind','file','sha256',_sha,'size',octet_length(_payload))) IS DISTINCT FROM TRUE
        OR starexec.job_execution_json_unique(_payload::json) IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
    _data:=_payload::jsonb;
    IF jsonb_typeof(_data) IS DISTINCT FROM 'object'
        OR jsonb_typeof(_data->'pairId') IS DISTINCT FROM 'number'
        OR jsonb_typeof(_data->'stageNumber') IS DISTINCT FROM 'number' THEN RETURN FALSE; END IF;
    IF (_data->>'pairId')::numeric<>_request.pair_id OR (_data->>'stageNumber')::numeric<>_stage THEN RETURN FALSE; END IF;
    FOREACH _field IN ARRAY ARRAY['wallclockTime','cpuTime','userTime','systemTime','maxVirtualMemory'] LOOP
        IF jsonb_typeof(_data->_field) IS DISTINCT FROM 'number' THEN RETURN FALSE; END IF;
        _measurement:=(_data->>_field)::double precision;
        IF _measurement<0 OR _measurement>='Infinity'::double precision THEN RETURN FALSE; END IF;
    END LOOP;
    FOREACH _field IN ARRAY ARRAY['maxResidentSetSize','diskSize'] LOOP
        IF jsonb_typeof(_data->_field) IS DISTINCT FROM 'number' THEN RETURN FALSE; END IF;
        _count:=(_data->>_field)::numeric;
        IF _count<0 OR _count>9223372036854775807 OR _count<>trunc(_count) THEN RETURN FALSE; END IF;
    END LOOP;
    IF jsonb_typeof(_data->'hostname')='string' AND btrim(_data->>'hostname')<>''
        AND btrim(_data->>'hostname')<>_node_name THEN RETURN FALSE; END IF;
    SELECT id INTO _node FROM starexec.nodes WHERE name=_node_name FOR KEY SHARE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    PERFORM 1 FROM starexec.jobpair_stage_data WHERE jobpair_id=_request.pair_id AND stage_number=_stage FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    -- Preserve reported values exactly in their existing fields, without changing quota.
    UPDATE starexec.job_pairs SET node_id=_node WHERE id=_request.pair_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retained statistics pair disappeared'; END IF;
    UPDATE starexec.jobpair_stage_data SET
        wallclock=(_data->>'wallclockTime')::double precision,
        cpu=(_data->>'cpuTime')::double precision,
        user_time=(_data->>'userTime')::double precision,
        system_time=(_data->>'systemTime')::double precision,
        max_vmem=(_data->>'maxVirtualMemory')::double precision,
        max_res_set=(_data->>'maxResidentSetSize')::numeric::bigint,
        disk_size=(_data->>'diskSize')::numeric::bigint
        WHERE jobpair_id=_request.pair_id AND stage_number=_stage;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retained statistics stage disappeared'; END IF;
    RETURN TRUE;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN FALSE;
END;
$$ LANGUAGE plpgsql;
