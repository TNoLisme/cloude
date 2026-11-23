-- BE-ADM-INT-01 / ADM-OI-07: Audit-owned append-only, tamper-evident audit trail.
-- Canonical source: docs-common/04. Database design & analysis.md §4.6.1 and
-- docs-common/design/01. audit-traceability.md §6.1/§7 (hash_version inside the canonical payload;
-- record_hash = SHA-256(JCS(canonical_payload) || '|' || prev_hash)).
-- Forward-only: V1-V9 already applied and untouched.
-- Module audit is the single write-owner of banking.audit_log. Consumers write only through
-- audit.api.AuditModuleApi; no UPDATE/DELETE path exists (trigger below) and only SELECT/INSERT are used.
-- Deliberately absent: retention/legal-hold/checkpoint tables and export endpoints (separate Audit tasks);
-- this migration only provides the durable evidence table Admin needs.

CREATE TABLE banking.audit_log (
    audit_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at        TIMESTAMPTZ   NOT NULL,
    recorded_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor_type         VARCHAR(8)    NOT NULL
        CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_user_id      BIGINT        NULL,
    actor_role         VARCHAR(32)   NOT NULL
        CONSTRAINT ck_audit_actor_role CHECK (length(actor_role) BETWEEN 1 AND 32),
    action             VARCHAR(64)   NOT NULL
        CONSTRAINT ck_audit_action CHECK (length(action) BETWEEN 1 AND 64),
    entity_type        VARCHAR(32)   NOT NULL
        CONSTRAINT ck_audit_entity_type CHECK (length(entity_type) BETWEEN 1 AND 32),
    entity_id          VARCHAR(64)   NOT NULL
        CONSTRAINT ck_audit_entity_id CHECK (length(entity_id) BETWEEN 1 AND 64),
    before_state       JSONB         NULL,
    after_state        JSONB         NULL,
    amount             NUMERIC(19,2) NULL,
    currency           CHAR(3)       NULL,
    correlation_id     VARCHAR(64)   NOT NULL
        CONSTRAINT ck_audit_correlation_id CHECK (length(correlation_id) BETWEEN 1 AND 64),
    request_id         VARCHAR(64)   NULL,
    outcome            VARCHAR(16)   NOT NULL
        CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'REJECTED')),
    client_info_masked JSONB         NULL,
    -- prev_hash is filled by the writer for every row; the canonical column allows NULL only because
    -- design/01 §6.1 keeps it nullable. A NULL row can never be produced by the module writer.
    prev_hash          CHAR(64)      NULL
        CONSTRAINT ck_audit_prev_hash_hex CHECK (prev_hash IS NULL OR prev_hash ~ '^[0-9a-f]{64}$'),
    record_hash        CHAR(64)      NOT NULL
        CONSTRAINT ck_audit_record_hash_hex CHECK (record_hash ~ '^[0-9a-f]{64}$'),
    hash_version       SMALLINT      NOT NULL DEFAULT 1
        CONSTRAINT ck_audit_hash_version_positive CHECK (hash_version > 0),
    CONSTRAINT fk_audit_actor_user FOREIGN KEY (actor_user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_audit_actor_user_required CHECK (actor_type <> 'USER' OR actor_user_id IS NOT NULL),
    CONSTRAINT ck_audit_amount_non_negative CHECK (amount IS NULL OR amount >= 0)
);

CREATE INDEX ix_audit_occurred ON banking.audit_log (occurred_at);
CREATE INDEX ix_audit_correlation ON banking.audit_log (correlation_id);
CREATE INDEX ix_audit_entity ON banking.audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_actor ON banking.audit_log (actor_user_id);
CREATE INDEX ix_audit_action ON banking.audit_log (action);
CREATE INDEX ix_audit_record_hash ON banking.audit_log (record_hash);

-- Append-only (I-AUD-02): the application role keeps only SELECT/INSERT. The trigger is the second line of
-- defence for environments where the runtime role still owns the table (local/Testcontainers).
CREATE OR REPLACE FUNCTION banking.reject_audit_log_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only'
        USING ERRCODE = 'integrity_constraint_violation',
              CONSTRAINT = 'ck_audit_log_append_only';
END;
$$;

CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON banking.audit_log
    FOR EACH ROW EXECUTE FUNCTION banking.reject_audit_log_mutation();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON banking.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION banking.reject_audit_log_mutation();

COMMENT ON TABLE banking.audit_log IS
    'Append-only audit evidence; hash chain per design/01 §7 (SHA-256 JCS || prev_hash).';
