-- Receipt shape is not kernel attestation; only the trusted adapter supplies these observations.
CREATE OR REPLACE FUNCTION starexec.solver_build_release_observation_valid(_payload TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    _j JSON;
    _o JSON;
    _n JSON;
    _count INTEGER;
    _distinct INTEGER;
    _name TEXT;
    _value TEXT;
    _previous BIGINT := -1;
    _cpu BIGINT;
BEGIN
    IF _payload IS NULL OR octet_length(_payload) NOT BETWEEN 2 AND 8192 THEN RETURN FALSE; END IF;
    _j := _payload::json;
    IF json_typeof(_j) IS DISTINCT FROM 'object' THEN RETURN FALSE; END IF;
    SELECT count(*),count(DISTINCT key) INTO _count,_distinct FROM json_each(_j);
    IF _count<>2 OR _distinct<>2 OR json_typeof(_j->'version') IS DISTINCT FROM 'number'
        OR _j->>'version' IS DISTINCT FROM '1'
        OR json_typeof(_j->'observation') IS DISTINCT FROM 'object' THEN RETURN FALSE; END IF;
    _o := _j->'observation';
    SELECT count(*),count(DISTINCT key) INTO _count,_distinct FROM json_each(_o);
    IF _count<>5 OR _distinct<>5 OR EXISTS (SELECT 1 FROM json_each(_o)
        WHERE key NOT IN ('startTicks','memoryBytes','pids','cpus','namespaces')) THEN RETURN FALSE; END IF;
    FOREACH _name IN ARRAY ARRAY['startTicks','memoryBytes','pids'] LOOP
        _value := _o->>_name;
        IF json_typeof(_o->_name) IS DISTINCT FROM 'number' OR _value !~ '^[1-9][0-9]{0,18}$'
            OR _value::bigint<=0 THEN RETURN FALSE; END IF;
    END LOOP;
    _value := _o->>'cpus';
    IF json_typeof(_o->'cpus') IS DISTINCT FROM 'string' OR _value !~ '^[0-9]+(,[0-9]+)*$' THEN RETURN FALSE; END IF;
    FOREACH _name IN ARRAY string_to_array(_value,',') LOOP
        _cpu := _name::bigint;
        IF _cpu>65535 OR _cpu<=_previous OR _cpu::text IS DISTINCT FROM _name THEN RETURN FALSE; END IF;
        _previous := _cpu;
    END LOOP;
    _n := _o->'namespaces';
    IF json_typeof(_n) IS DISTINCT FROM 'object' THEN RETURN FALSE; END IF;
    SELECT count(*),count(DISTINCT key) INTO _count,_distinct FROM json_each(_n);
    IF _count<>6 OR _distinct<>6 OR EXISTS (SELECT 1 FROM json_each(_n)
        WHERE key NOT IN ('pid','ipc','net','mnt','user','cgroup') OR json_typeof(value)<>'string'
            OR value#>>'{}' !~ ('^' || key || ':\[[1-9][0-9]{0,19}\]$')) THEN RETURN FALSE; END IF;
    RETURN TRUE;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Return permission only for a new insert; neither replay nor readback can permit another signal.
CREATE OR REPLACE FUNCTION starexec.claim_solver_build_release(
    _artifact BIGINT, _pair INTEGER, _attempt INTEGER, _token UUID,
    _source TEXT, _staging TEXT, _key TEXT, _identity TEXT, _engine TEXT,
    _request UUID, _runtime TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _l starexec.solver_build_storage_leases%ROWTYPE;
BEGIN
    IF _request IS NULL OR _engine IS NULL OR NOT (_engine LIKE 'podman:%' OR _engine LIKE 'kubernetes:%')
        OR NOT starexec.solver_build_release_observation_valid(_runtime)
        OR EXISTS (SELECT 1 FROM starexec.solver_build_release_decisions WHERE artifact_id=_artifact OR request_token=_request)
        THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND OR _a.engine_identity IS DISTINCT FROM _engine
        OR NOT EXISTS (SELECT 1 FROM starexec.solver_build_storage_leases
            WHERE artifact_id=_artifact AND execution_token=_token AND resource_key=_key
                AND released_at IS NULL AND cleanup_token IS NULL) THEN RETURN FALSE; END IF;
    IF NOT starexec.admit_leased_solver_build_execution(
        _artifact,_pair,_attempt,_token,_source,_staging,_key,_identity,_engine) THEN RETURN FALSE; END IF;
    SELECT * INTO _l FROM starexec.solver_build_storage_leases WHERE artifact_id=_artifact FOR UPDATE NOWAIT;
    IF NOT FOUND OR _l.execution_token IS DISTINCT FROM _token OR _l.resource_key IS DISTINCT FROM _key
        OR _l.released_at IS NOT NULL OR _l.cleanup_token IS NOT NULL THEN RETURN FALSE; END IF;
    INSERT INTO starexec.solver_build_release_decisions
        (artifact_id,execution_token,request_token,resource_key,resource_identity,engine_identity,runtime_payload,runtime_sha256)
        VALUES (_artifact,_token,_request,_key,_identity,_engine,_runtime,encode(sha256(convert_to(_runtime,'UTF8')),'hex'))
        ON CONFLICT DO NOTHING;
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;
