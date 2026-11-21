-- BE-ADM-CON-03: Admin-owned idempotency record for proposal/decision writes.
-- Canonical idempotency semantics: docs-common/07 §5, design/02 §6.
-- Admin does NOT reuse transfer.idempotency_record: the Admin unique scope is actor + operation + resource
-- (DEC-ADM-003). Tenant isolation is inherited from the single-tenant schema; no tenant column exists in 04.
CREATE TABLE banking.admin_idempotency_record (
    record_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL
        CONSTRAINT ck_admin_idempotency_key_len CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    actor_id        BIGINT       NOT NULL,
    operation       VARCHAR(32)  NOT NULL
        CONSTRAINT ck_admin_idempotency_operation CHECK (
            operation IN ('CREATE_LIMIT_PROPOSAL', 'CREATE_ROLE_PROPOSAL', 'DECIDE_APPROVAL')),
    -- Empty string for operations without an existing resource; approvalId for decisions.
    scope_key       VARCHAR(64)  NOT NULL DEFAULT ''
        CONSTRAINT ck_admin_idempotency_scope_len CHECK (length(scope_key) <= 64),
    request_hash    CHAR(64)     NOT NULL
        CONSTRAINT ck_admin_idempotency_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status          VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED'
        CONSTRAINT ck_admin_idempotency_status CHECK (status IN ('RECEIVED', 'COMPLETED', 'FAILED')),
    response_status SMALLINT     NULL
        CONSTRAINT ck_admin_idempotency_response_status CHECK (
            response_status IS NULL OR response_status BETWEEN 100 AND 599),
    response_body   JSONB        NULL,
    approval_id     BIGINT       NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ  NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_admin_idempotency_actor FOREIGN KEY (actor_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admin_idempotency_approval FOREIGN KEY (approval_id)
        REFERENCES banking.approval_request (approval_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_admin_idempotency_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_admin_idempotency_received_fields CHECK (
        status <> 'RECEIVED'
        OR (completed_at IS NULL AND response_status IS NULL AND response_body IS NULL)),
    CONSTRAINT ck_admin_idempotency_terminal_fields CHECK (
        status NOT IN ('COMPLETED', 'FAILED')
        OR (completed_at IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT ck_admin_idempotency_decision_scope CHECK (
        operation <> 'DECIDE_APPROVAL' OR approval_id IS NOT NULL)
);

-- The only arbiter for concurrent same-scope inserts; never select-then-insert.
CREATE UNIQUE INDEX uq_admin_idempotency_scope
    ON banking.admin_idempotency_record (actor_id, operation, scope_key, idempotency_key);
CREATE INDEX ix_admin_idempotency_expires ON banking.admin_idempotency_record (expires_at);
CREATE INDEX ix_admin_idempotency_status ON banking.admin_idempotency_record (status);
CREATE INDEX ix_admin_idempotency_approval ON banking.admin_idempotency_record (approval_id);
