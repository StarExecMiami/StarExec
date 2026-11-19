-- Preserve existing phase positions and history while adding a distinct copy intent.
ALTER TABLE starexec.job_execution_receipts DROP CONSTRAINT job_execution_receipts_receipt_kind_check;
ALTER TABLE starexec.job_execution_receipts ADD CONSTRAINT job_execution_receipts_receipt_kind_check
    CHECK (receipt_kind IN ('CREATED','RELEASE','STOP','OUTPUT','CLEANUP_REQUEST','CLEANUP','OUTPUT_REQUEST'));

-- Origin IDs are immutable accounting snapshots, not references to deletable live rows.
CREATE TABLE starexec.job_output_storage_reservations (
    execution_token UUID PRIMARY KEY CONSTRAINT job_output_reservation_claim_fk
        REFERENCES starexec.execution_storage_claims(execution_token) ON DELETE RESTRICT,
    receipt_kind TEXT NOT NULL DEFAULT 'OUTPUT_REQUEST' CHECK (receipt_kind='OUTPUT_REQUEST'),
    operation_token UUID NOT NULL UNIQUE CHECK (operation_token<>'00000000-0000-0000-0000-000000000000'::uuid),
    origin_owner_id INTEGER NOT NULL CHECK (origin_owner_id>0),
    origin_pair_id INTEGER NOT NULL CHECK (origin_pair_id>0),
    parent_path TEXT NOT NULL,
    parent_identity TEXT NOT NULL CHECK (btrim(parent_identity)<>''),
    target_path TEXT NOT NULL UNIQUE,
    source_manifest_sha256 TEXT NOT NULL CHECK (source_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    limit_bytes BIGINT NOT NULL CHECK (limit_bytes>=0),
    entry_limit INTEGER NOT NULL CHECK (entry_limit>=2),
    charged_bytes BIGINT NOT NULL CHECK (charged_bytes BETWEEN 0 AND limit_bytes),
    copy_started_at TIMESTAMPTZ,
    directory_identity TEXT,
    verified_bytes BIGINT CHECK (verified_bytes BETWEEN 0 AND limit_bytes),
    verified_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT job_output_reservation_receipt_fk FOREIGN KEY (execution_token,receipt_kind)
        REFERENCES starexec.job_execution_receipts(execution_token,receipt_kind) ON DELETE RESTRICT,
    CHECK (left(parent_path,1)='/' AND parent_path<>'/' AND right(parent_path,1)<>'/'
        AND parent_path !~ '(^|/)[.]{1,2}(/|$)' AND strpos(parent_path,'//')=0),
    CHECK (target_path=parent_path || '/job-' || execution_token::text),
    CHECK (directory_identity IS NULL OR copy_started_at IS NOT NULL AND btrim(directory_identity)<>''),
    CHECK ((verified_at IS NULL AND verified_bytes IS NULL) OR
        verified_at IS NOT NULL AND verified_bytes IS NOT NULL AND directory_identity IS NOT NULL
        AND charged_bytes=verified_bytes)
);

CREATE INDEX job_output_reservation_owner_active_idx ON starexec.job_output_storage_reservations(origin_owner_id)
    WHERE released_at IS NULL;
CREATE INDEX job_output_reservation_pair_active_idx ON starexec.job_output_storage_reservations(origin_pair_id)
    WHERE released_at IS NULL;
