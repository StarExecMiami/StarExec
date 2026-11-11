-- The trusted adapter supplies freshly observed capacity, not user-provided configuration.
CREATE OR REPLACE FUNCTION starexec.register_execution_storage_resource(
    _key TEXT, _root TEXT, _identity TEXT, _bytes BIGINT, _inodes BIGINT) RETURNS BOOLEAN AS $$
BEGIN
    IF _key IS NULL OR _key !~ '^[0-9a-f]+$' OR _key ~ '^0+$' OR _root IS NULL
        OR left(_root,1)<>'/' OR _root='/' OR _root ~ '(^|/)[.]{1,2}(/|$)'
        OR strpos(_root,'//')>0 OR right(_root,1)='/' OR _identity IS NULL OR btrim(_identity)=''
        OR _bytes IS NULL OR _bytes<=0 OR _inodes IS NULL OR _inodes<=0 THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM starexec.execution_storage_resources
        WHERE resource_key=_key AND root_path=_root AND observed_identity=_identity
            AND capacity_bytes=_bytes AND inode_limit=_inodes) THEN
        RETURN starexec.execution_storage_root_unreferenced(_root);
    END IF;
    INSERT INTO starexec.execution_storage_resources
        (resource_key,root_path,observed_identity,capacity_bytes,inode_limit)
        VALUES (_key,_root,_identity,_bytes,_inodes) ON CONFLICT DO NOTHING;
    RETURN EXISTS (SELECT 1 FROM starexec.execution_storage_resources
        WHERE resource_key=_key AND root_path=_root AND observed_identity=_identity
            AND capacity_bytes=_bytes AND inode_limit=_inodes);
END;
$$ LANGUAGE plpgsql;

-- Follow current build locks before nonwaiting quota/resource locks; refusal does not authorize launch.
CREATE OR REPLACE FUNCTION starexec.reserve_solver_build_storage_lease(
    _artifact BIGINT, _pair INTEGER, _attempt INTEGER, _token UUID,
    _source TEXT, _staging TEXT, _key TEXT, _identity TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _r starexec.execution_storage_resources%ROWTYPE;
    _c starexec.solver_build_storage_charges%ROWTYPE;
    _l starexec.solver_build_storage_leases%ROWTYPE;
    _usage BIGINT;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact;
    IF NOT FOUND OR _token IS NULL OR _source IS NULL OR _staging IS NULL OR _key IS NULL OR _identity IS NULL
        OR _a.origin_pair_id IS DISTINCT FROM _pair OR _a.attempt_no IS DISTINCT FROM _attempt
        OR _a.execution_token IS DISTINCT FROM _token OR _a.source_path IS DISTINCT FROM _source
        OR _a.staging_path IS DISTINCT FROM _staging THEN RETURN FALSE; END IF;
    IF NOT starexec.prepare_solver_build_storage(_pair,_attempt,_token,_staging) THEN RETURN FALSE; END IF;
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR UPDATE;
    IF _a.execution_stopped_at IS NOT NULL OR _a.cleanup_token IS NOT NULL
        OR _a.state NOT IN ('PLANNED','STAGED') THEN RETURN FALSE; END IF;
    SELECT disk_size INTO _usage FROM starexec.users WHERE id=_a.origin_owner_id FOR SHARE NOWAIT;
    IF NOT FOUND OR _usage IS NULL OR _usage<0 THEN RETURN FALSE; END IF;
    SELECT * INTO _c FROM starexec.solver_build_storage_charges
        WHERE artifact_id=_artifact AND storage_kind='STAGING' FOR SHARE NOWAIT;
    IF NOT FOUND OR _c.owner_id IS DISTINCT FROM _a.origin_owner_id OR _c.path IS DISTINCT FROM _staging
        OR _c.limit_bytes<=0 OR _c.charged_bytes IS DISTINCT FROM _c.limit_bytes
        OR _c.verified_at IS NOT NULL OR _c.released_at IS NOT NULL THEN RETURN FALSE; END IF;
    IF _usage<starexec.active_execution_storage_bytes(_a.origin_owner_id) THEN RETURN FALSE; END IF;
    SELECT * INTO _r FROM starexec.execution_storage_resources WHERE resource_key=_key FOR UPDATE NOWAIT;
    IF NOT FOUND OR _r.observed_identity IS DISTINCT FROM _identity OR _r.capacity_bytes>_c.limit_bytes
        OR NOT starexec.execution_storage_root_unreferenced(_r.root_path)
        OR _staging IS DISTINCT FROM _r.root_path || '/build-' || _token::text
        OR _source=_r.root_path OR starts_with(_source,_r.root_path || '/')
        OR starts_with(_r.root_path,_source || '/') THEN RETURN FALSE; END IF;
    SELECT * INTO _l FROM starexec.solver_build_storage_leases WHERE artifact_id=_artifact FOR UPDATE NOWAIT;
    IF FOUND THEN RETURN _l.execution_token=_token AND _l.resource_key=_key AND _l.released_at IS NULL; END IF;
    IF _a.engine_identity IS NOT NULL OR EXISTS (SELECT 1 FROM starexec.execution_storage_claims
        WHERE resource_key=_key AND released_at IS NULL) THEN RETURN FALSE; END IF;
    INSERT INTO starexec.solver_build_storage_leases(artifact_id,execution_token,resource_key)
        VALUES (_artifact,_token,_key);
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION starexec.admit_leased_solver_build_execution(
    _artifact BIGINT, _pair INTEGER, _attempt INTEGER, _token UUID,
    _source TEXT, _staging TEXT, _key TEXT, _identity TEXT, _engine TEXT) RETURNS BOOLEAN AS $$
BEGIN
    IF NOT starexec.reserve_solver_build_storage_lease(
        _artifact,_pair,_attempt,_token,_source,_staging,_key,_identity) THEN RETURN FALSE; END IF;
    RETURN starexec.admit_solver_build_execution(_artifact,_pair,_attempt,_token,_source,_staging,_engine);
END;
$$ LANGUAGE plpgsql;

-- The trusted adapter must also reattest the resource and confirm it physically empty.
CREATE OR REPLACE FUNCTION starexec.release_solver_build_storage_lease(
    _artifact BIGINT, _token UUID, _key TEXT, _identity TEXT) RETURNS BOOLEAN AS $$
DECLARE
    _a starexec.solver_build_artifacts%ROWTYPE;
    _l starexec.solver_build_storage_leases%ROWTYPE;
BEGIN
    SELECT * INTO _a FROM starexec.solver_build_artifacts WHERE id=_artifact FOR SHARE NOWAIT;
    IF NOT FOUND OR _token IS NULL OR _a.execution_token IS DISTINCT FROM _token THEN RETURN FALSE; END IF;
    IF EXISTS (SELECT 1 FROM starexec.solver_build_storage_leases l
        JOIN starexec.execution_storage_resources r ON r.resource_key=l.resource_key
        JOIN starexec.solver_build_storage_charges c ON c.artifact_id=l.artifact_id AND c.storage_kind='STAGING'
        WHERE l.artifact_id=_artifact AND l.execution_token=_token AND l.resource_key=_key
            AND r.observed_identity=_identity AND l.released_at IS NOT NULL AND c.released_at IS NOT NULL) THEN RETURN TRUE; END IF;
    IF _a.state<>'DELETED' OR _a.cleanup_finished_at IS NULL
        OR EXISTS (SELECT 1 FROM starexec.solver_build_storage_charges
            WHERE artifact_id=_artifact AND released_at IS NULL) THEN RETURN FALSE; END IF;
    PERFORM resource_key FROM starexec.execution_storage_resources
        WHERE resource_key=_key AND observed_identity=_identity FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO _l FROM starexec.solver_build_storage_leases WHERE artifact_id=_artifact FOR UPDATE NOWAIT;
    IF NOT FOUND OR _l.execution_token IS DISTINCT FROM _token OR _l.resource_key IS DISTINCT FROM _key THEN RETURN FALSE; END IF;
    IF _l.released_at IS NOT NULL THEN RETURN TRUE; END IF;
    UPDATE starexec.solver_build_storage_leases SET released_at=clock_timestamp() WHERE artifact_id=_artifact;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
