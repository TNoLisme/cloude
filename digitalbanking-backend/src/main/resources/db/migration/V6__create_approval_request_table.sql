-- BE-ADM-CON-01: Admin-owned approval workflow table.
-- Canonical source: docs-common/04. Database design & analysis.md §4.5.3.
-- Scope: BIGINT identity PK and canonical columns only; 04 §4.5.3 is authoritative over overview drafts.
-- The MVP applies provider changes in the checker transaction, so APPROVED remains schema-reserved only.
CREATE TABLE banking.approval_request (
    approval_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_type  VARCHAR(32) NOT NULL
        CONSTRAINT ck_approval_request_type CHECK (
            request_type IN ('LIMIT_CHANGE', 'ROLE_CHANGE', 'RETENTION_HOLD', 'RETENTION_POLICY_CHANGE')),
    target_type   VARCHAR(32) NOT NULL
        CONSTRAINT ck_approval_target_type CHECK (
            target_type IN ('CUSTOMER', 'ACCOUNT', 'APP_USER', 'DATA_SCOPE', 'RETENTION_POLICY')),
    target_id     VARCHAR(64) NOT NULL
        CONSTRAINT ck_approval_target_id CHECK (length(target_id) BETWEEN 1 AND 64),
    payload       JSONB       NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_approval_status CHECK (
            status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'CANCELLED')),
    maker_id      BIGINT      NOT NULL,
    checker_id    BIGINT      NULL,
    decision_note VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL
        CONSTRAINT ck_approval_correlation_id CHECK (length(correlation_id) BETWEEN 1 AND 64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ NULL,
    applied_at    TIMESTAMPTZ NULL,
    CONSTRAINT fk_approval_maker FOREIGN KEY (maker_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_approval_checker FOREIGN KEY (checker_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_approval_maker_checker CHECK (checker_id IS NULL OR checker_id <> maker_id),
    CONSTRAINT ck_approval_decided_fields CHECK (
        status NOT IN ('APPROVED', 'REJECTED', 'APPLIED')
        OR (checker_id IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_approval_applied_at CHECK (status <> 'APPLIED' OR applied_at IS NOT NULL)
);

CREATE INDEX ix_approval_status_time ON banking.approval_request (status, created_at);
CREATE INDEX ix_approval_type_status ON banking.approval_request (request_type, status);
CREATE INDEX ix_approval_target ON banking.approval_request (target_type, target_id);
CREATE INDEX ix_approval_maker ON banking.approval_request (maker_id);
CREATE INDEX ix_approval_checker ON banking.approval_request (checker_id);
