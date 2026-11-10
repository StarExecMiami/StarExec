-- Validate conserved byte/count bounds; physical file identity remains the trusted observer's responsibility.
CREATE OR REPLACE FUNCTION starexec.job_output_source_manifest_valid(_payload TEXT,_max_bytes BIGINT,_max_entries INTEGER)
RETURNS BOOLEAN AS $$
DECLARE
    _root JSONB; _scope JSONB; _tree JSONB; _entry JSONB; _path TEXT;
    _bytes NUMERIC:=0; _entries NUMERIC:=2; _scope_bytes NUMERIC;
BEGIN
    IF _payload IS NULL OR octet_length(_payload)>16777216 OR _max_bytes IS NULL OR _max_bytes<0
        OR _max_entries IS NULL OR _max_entries<2
        OR starexec.job_execution_json_unique(_payload::json) IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
    _root:=_payload::jsonb;
    IF (_root @> '{"version":1,"kind":"ordinary-output"}'::jsonb
        AND jsonb_typeof(_root->'byte_size')='number' AND (_root->>'byte_size') ~ '^[0-9]+$'
        AND jsonb_typeof(_root->'entry_count')='number' AND (_root->>'entry_count') ~ '^[0-9]+$') IS DISTINCT FROM TRUE
        OR (_root->>'byte_size')::numeric>_max_bytes OR (_root->>'entry_count')::numeric>_max_entries THEN RETURN FALSE; END IF;
    FOREACH _scope IN ARRAY ARRAY[_root->'monitor',_root->'savedoutput'] LOOP
        IF (jsonb_typeof(_scope)='object' AND jsonb_typeof(_scope->'manifest_payload')='string'
            AND jsonb_typeof(_scope->'manifest_sha256')='string'
            AND _scope->>'manifest_sha256'=encode(sha256(convert_to(_scope->>'manifest_payload','UTF8')),'hex')
            AND starexec.job_execution_json_unique((_scope->>'manifest_payload')::json)
            AND jsonb_typeof(_scope->'byte_size')='number' AND (_scope->>'byte_size') ~ '^[0-9]+$'
            AND jsonb_typeof(_scope->'entry_count')='number' AND (_scope->>'entry_count') ~ '^[0-9]+$') IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
        _tree:=(_scope->>'manifest_payload')::jsonb;
        IF (_tree @> '{"version":1}'::jsonb AND jsonb_typeof(_tree->'entries')='array') IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
        IF jsonb_array_length(_tree->'entries')>_max_entries-2 THEN RETURN FALSE; END IF;
        _scope_bytes:=0;
        FOR _entry IN SELECT value FROM jsonb_array_elements(_tree->'entries') LOOP
            IF (jsonb_typeof(_entry)='object' AND jsonb_typeof(_entry->'path')='string'
                AND _entry->>'kind' IN ('file','directory') AND jsonb_typeof(_entry->'size')='number'
                AND (_entry->>'size') ~ '^[0-9]+$' AND jsonb_typeof(_entry->'executable')='boolean'
                AND NOT (_entry ? 'target')) IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
            _path:=_entry->>'path';
            IF _path='' OR left(_path,1)='/' OR right(_path,1)='/' OR strpos(_path,'//')>0
                OR _path ~ '(^|/)[.]{1,2}(/|$)' THEN RETURN FALSE; END IF;
            IF _entry->>'kind'='file' THEN
                IF (jsonb_typeof(_entry->'sha256')='string' AND _entry->>'sha256' ~ '^[0-9a-f]{64}$') IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
                _scope_bytes:=_scope_bytes+(_entry->>'size')::numeric;
                IF _scope_bytes>_max_bytes THEN RETURN FALSE; END IF;
            ELSIF (_entry->>'size')::numeric<>0 OR _entry ? 'sha256' OR (_entry->>'executable')::boolean THEN RETURN FALSE;
            END IF;
        END LOOP;
        IF _scope_bytes<>(_scope->>'byte_size')::numeric
            OR jsonb_array_length(_tree->'entries')<>(_scope->>'entry_count')::numeric
            OR (SELECT count(DISTINCT value->>'path') FROM jsonb_array_elements(_tree->'entries'))<>jsonb_array_length(_tree->'entries') THEN RETURN FALSE; END IF;
        _bytes:=_bytes+_scope_bytes;_entries:=_entries+jsonb_array_length(_tree->'entries');
        IF _bytes>_max_bytes OR _entries>_max_entries THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN _bytes=(_root->>'byte_size')::numeric AND _entries=(_root->>'entry_count')::numeric;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN FALSE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Copy mutations retain the same current job, funded resource and normal stop.
CREATE OR REPLACE FUNCTION starexec.lock_job_output_copy(
    _token UUID,_operation UUID,_request_sha TEXT,_parent_identity TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _request starexec.job_execution_requests%ROWTYPE;
    _phase starexec.job_execution_receipts%ROWTYPE;
    _claim starexec.execution_storage_claims%ROWTYPE;
    _resource starexec.execution_storage_resources%ROWTYPE;
    _output starexec.job_output_storage_reservations%ROWTYPE;
    _usage BIGINT;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'Ordinary output copy requires a current read-committed snapshot' USING ERRCODE='40001';
    END IF;
    SELECT * INTO _phase FROM starexec.job_execution_receipts WHERE execution_token=_token AND receipt_kind='OUTPUT_REQUEST';
    IF NOT FOUND OR _phase.operation_token IS DISTINCT FROM _operation OR _phase.receipt_sha256 IS DISTINCT FROM _request_sha
        OR _phase.receipt_payload::jsonb->'facts'->>'parent_identity' IS DISTINCT FROM _parent_identity THEN RETURN FALSE; END IF;
    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=_token;
    IF NOT FOUND OR NOT starexec.lock_current_job_storage_pair(_request.pair_id,_request.attempt_no,
        _request.owner_id,_request.execution_id,FALSE) THEN RETURN FALSE; END IF;
    IF NOT EXISTS (SELECT 1 FROM starexec.job_pairs p JOIN starexec.jobs j ON j.id=p.job_id
        WHERE p.id=_request.pair_id AND p.status_code IS DISTINCT FROM 21
            AND j.deleted IS NOT TRUE AND j.killed IS NOT TRUE) THEN RETURN FALSE; END IF;
    SELECT disk_size INTO _usage FROM starexec.users WHERE id=_request.owner_id FOR UPDATE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _usage<starexec.active_execution_storage_bytes(_request.owner_id) THEN RETURN FALSE; END IF;
    SELECT * INTO _resource FROM starexec.execution_storage_resources WHERE resource_key=_request.resource_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _resource.observed_identity IS DISTINCT FROM _request.resource_identity
        OR starexec.execution_storage_root_unreferenced(_resource.root_path) IS DISTINCT FROM TRUE THEN RETURN FALSE; END IF;
    SELECT * INTO _claim FROM starexec.execution_storage_claims WHERE execution_token=_token FOR UPDATE NOWAIT;
    IF NOT FOUND OR _claim.lease_kind<>'JOB' OR _claim.resource_key IS DISTINCT FROM _request.resource_key
        OR _claim.origin_owner_id IS DISTINCT FROM _request.owner_id OR _claim.origin_pair_id IS DISTINCT FROM _request.pair_id
        OR _claim.attempt_no IS DISTINCT FROM _request.attempt_no OR _claim.limit_bytes IS DISTINCT FROM _resource.capacity_bytes
        OR _claim.use_started_at IS NULL OR _claim.released_at IS NOT NULL THEN RETURN FALSE; END IF;
    IF NOT EXISTS (SELECT 1 FROM starexec.job_execution_receipts s WHERE s.execution_token=_token AND s.receipt_kind='STOP'
        AND s.engine_identity=_phase.engine_identity AND NOT (s.receipt_payload::jsonb->'facts' ? 'disposition')
        AND s.receipt_sha256=_phase.receipt_payload::jsonb->'facts'->>'stop_sha256')
        OR EXISTS (SELECT 1 FROM starexec.job_execution_receipts WHERE execution_token=_token
            AND receipt_kind IN ('OUTPUT','CLEANUP_REQUEST','CLEANUP')) THEN RETURN FALSE; END IF;
    SELECT * INTO _output FROM starexec.job_output_storage_reservations WHERE execution_token=_token FOR UPDATE NOWAIT;
    RETURN FOUND AND _output.operation_token IS NOT DISTINCT FROM _operation
        AND _output.parent_identity IS NOT DISTINCT FROM _parent_identity
        AND _output.verified_at IS NULL AND _output.released_at IS NULL;
END;
$$ LANGUAGE plpgsql;

-- Allocation rows mirror the immutable phase; only the first copy and binding can advance.
CREATE OR REPLACE FUNCTION starexec.guard_job_output_storage_reservation() RETURNS trigger AS $$
DECLARE _phase starexec.job_execution_receipts%ROWTYPE; _request starexec.job_execution_requests%ROWTYPE; _facts JSONB;
BEGIN
    IF TG_OP<>'INSERT' THEN
        IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
        IF TG_OP='UPDATE' AND (NEW.execution_token,NEW.receipt_kind,NEW.operation_token,NEW.origin_owner_id,NEW.origin_pair_id,
            NEW.parent_path,NEW.parent_identity,NEW.target_path,NEW.source_manifest_sha256,NEW.limit_bytes,NEW.entry_limit,
            NEW.charged_bytes,NEW.verified_bytes,NEW.verified_at,NEW.released_at,NEW.created_at)
            IS NOT DISTINCT FROM (OLD.execution_token,OLD.receipt_kind,OLD.operation_token,OLD.origin_owner_id,OLD.origin_pair_id,
            OLD.parent_path,OLD.parent_identity,OLD.target_path,OLD.source_manifest_sha256,OLD.limit_bytes,OLD.entry_limit,
            OLD.charged_bytes,OLD.verified_bytes,OLD.verified_at,OLD.released_at,OLD.created_at) THEN
            SELECT * INTO _phase FROM starexec.job_execution_receipts
                WHERE execution_token=OLD.execution_token AND receipt_kind='OUTPUT_REQUEST';
            IF starexec.lock_job_output_copy(OLD.execution_token,OLD.operation_token,_phase.receipt_sha256,OLD.parent_identity) THEN
                IF OLD.copy_started_at IS NULL AND NEW.copy_started_at IS NOT NULL
                    AND NEW.directory_identity IS NOT DISTINCT FROM OLD.directory_identity THEN
                    NEW.copy_started_at:=clock_timestamp();RETURN NEW;
                ELSIF OLD.copy_started_at IS NOT NULL AND NEW.copy_started_at IS NOT DISTINCT FROM OLD.copy_started_at
                    AND OLD.directory_identity IS NULL AND NEW.directory_identity IS NOT NULL AND btrim(NEW.directory_identity)<>'' THEN
                    RETURN NEW;
                END IF;
            END IF;
        END IF;
        RAISE EXCEPTION 'Ordinary output reservation requires conserved completion or registered cleanup';
    END IF;
    SELECT * INTO _phase FROM starexec.job_execution_receipts
        WHERE execution_token=NEW.execution_token AND receipt_kind='OUTPUT_REQUEST';
    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=NEW.execution_token;
    _facts:=_phase.receipt_payload::jsonb->'facts';
    IF _phase.execution_token IS NULL OR _request.execution_token IS NULL
        OR NEW.operation_token IS DISTINCT FROM _phase.operation_token
        OR NEW.origin_owner_id IS DISTINCT FROM _request.owner_id OR NEW.origin_pair_id IS DISTINCT FROM _request.pair_id
        OR NEW.parent_path IS DISTINCT FROM _facts->>'parent_path'
        OR NEW.parent_identity IS DISTINCT FROM _facts->>'parent_identity'
        OR NEW.target_path IS DISTINCT FROM _facts->>'target_path'
        OR NEW.source_manifest_sha256 IS DISTINCT FROM _facts->>'source_manifest_sha256'
        OR NEW.limit_bytes IS DISTINCT FROM (_facts->>'limit_bytes')::bigint
        OR NEW.entry_limit IS DISTINCT FROM (_facts->>'entry_limit')::integer
        OR NEW.charged_bytes IS DISTINCT FROM NEW.limit_bytes OR NEW.copy_started_at IS NOT NULL
        OR NEW.directory_identity IS NOT NULL OR NEW.verified_bytes IS NOT NULL
        OR NEW.verified_at IS NOT NULL OR NEW.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'Ordinary output reservation differs from its exact funded phase';
    END IF;
    NEW.created_at:=clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- A historical TRUE is never another write permission after a lost response.
CREATE OR REPLACE FUNCTION starexec.claim_job_output_copy(
    _token UUID,_operation UUID,_request_sha TEXT,_parent_identity TEXT) RETURNS BOOLEAN AS $$
BEGIN
    IF NOT starexec.lock_job_output_copy(_token,_operation,_request_sha,_parent_identity) THEN RETURN FALSE; END IF;
    UPDATE starexec.job_output_storage_reservations SET copy_started_at=clock_timestamp()
        WHERE execution_token=_token AND copy_started_at IS NULL AND directory_identity IS NULL;
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.bind_job_output_copy(
    _token UUID,_operation UUID,_request_sha TEXT,_parent_identity TEXT,_directory_identity TEXT) RETURNS BOOLEAN AS $$
BEGIN
    IF _directory_identity IS NULL OR btrim(_directory_identity)='' OR
        NOT starexec.lock_job_output_copy(_token,_operation,_request_sha,_parent_identity) THEN RETURN FALSE; END IF;
    UPDATE starexec.job_output_storage_reservations SET directory_identity=_directory_identity
        WHERE execution_token=_token AND copy_started_at IS NOT NULL AND directory_identity IS NULL;
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS job_output_storage_guard ON starexec.job_output_storage_reservations;
CREATE TRIGGER job_output_storage_guard BEFORE INSERT OR UPDATE OR DELETE ON starexec.job_output_storage_reservations
    FOR EACH ROW EXECUTE FUNCTION starexec.guard_job_output_storage_reservation();
DROP TRIGGER IF EXISTS job_output_storage_truncate ON starexec.job_output_storage_reservations;
CREATE TRIGGER job_output_storage_truncate BEFORE TRUNCATE ON starexec.job_output_storage_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION starexec.guard_job_output_storage_reservation();

-- Charge only an inserted row; conflict-discarded writes must never debit quota.
CREATE OR REPLACE FUNCTION starexec.account_job_output_storage_reservation() RETURNS trigger AS $$
BEGIN
    UPDATE starexec.users SET disk_size=disk_size+NEW.limit_bytes WHERE id=NEW.origin_owner_id
        AND disk_size>=0 AND disk_quota>=disk_size AND NEW.limit_bytes<=disk_quota-disk_size;
    IF NOT FOUND THEN RAISE EXCEPTION 'Ordinary output copy allowance exceeds available quota'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS job_output_storage_accounting ON starexec.job_output_storage_reservations;
CREATE TRIGGER job_output_storage_accounting AFTER INSERT ON starexec.job_output_storage_reservations
    FOR EACH ROW EXECUTE FUNCTION starexec.account_job_output_storage_reservation();

-- Both history and booked destination commit together before a caller may attempt copying.
CREATE OR REPLACE FUNCTION starexec.allocate_job_output_storage() RETURNS trigger AS $$
DECLARE _request starexec.job_execution_requests%ROWTYPE; _facts JSONB;
BEGIN
    IF NEW.receipt_kind<>'OUTPUT_REQUEST' THEN RETURN NEW; END IF;
    SELECT * INTO _request FROM starexec.job_execution_requests WHERE execution_token=NEW.execution_token;
    _facts:=NEW.receipt_payload::jsonb->'facts';
    INSERT INTO starexec.job_output_storage_reservations
        (execution_token,operation_token,origin_owner_id,origin_pair_id,parent_path,parent_identity,
         target_path,source_manifest_sha256,limit_bytes,entry_limit,charged_bytes)
        VALUES (NEW.execution_token,NEW.operation_token,_request.owner_id,_request.pair_id,
            _facts->>'parent_path',_facts->>'parent_identity',_facts->>'target_path',_facts->>'source_manifest_sha256',
            (_facts->>'limit_bytes')::bigint,(_facts->>'entry_limit')::integer,(_facts->>'limit_bytes')::bigint);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS job_output_storage_allocation ON starexec.job_execution_receipts;
CREATE TRIGGER job_output_storage_allocation AFTER INSERT ON starexec.job_execution_receipts
    FOR EACH ROW EXECUTE FUNCTION starexec.allocate_job_output_storage();
