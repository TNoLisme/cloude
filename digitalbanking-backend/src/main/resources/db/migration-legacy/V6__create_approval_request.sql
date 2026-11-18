-- Canonical admin-owned approval data (04 section 4.5.3). This release handles LIMIT_CHANGE only.
-- Identity FKs and historical limit approval references remain staged until owner data is available.
CREATE TABLE banking.approval_request (
    approval_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_type VARCHAR(32) NOT NULL CHECK (request_type IN ('LIMIT_CHANGE','ROLE_CHANGE','RETENTION_HOLD','RETENTION_POLICY_CHANGE')),
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN ('CUSTOMER','ACCOUNT','APP_USER','DATA_SCOPE','RETENTION_POLICY')),
    target_id VARCHAR(64) NOT NULL CHECK (length(target_id) BETWEEN 1 AND 64),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED')),
    maker_id BIGINT NOT NULL CHECK (maker_id > 0),
    checker_id BIGINT CHECK (checker_id > 0),
    decision_note VARCHAR(500),
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    CHECK (checker_id IS NULL OR checker_id <> maker_id),
    CHECK (status NOT IN ('APPROVED','REJECTED','APPLIED') OR (checker_id IS NOT NULL AND decided_at IS NOT NULL)),
    CHECK (status <> 'APPLIED' OR applied_at IS NOT NULL)
);
CREATE INDEX ix_approval_status_time ON banking.approval_request(status, created_at);
CREATE INDEX ix_approval_type_status ON banking.approval_request(request_type, status);
CREATE INDEX ix_approval_target ON banking.approval_request(target_type, target_id);
CREATE INDEX ix_approval_maker ON banking.approval_request(maker_id);
CREATE INDEX ix_approval_checker ON banking.approval_request(checker_id);
REVOKE ALL ON banking.approval_request FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='banking_app') THEN
        REVOKE ALL ON banking.approval_request FROM banking_app;
        GRANT SELECT, INSERT ON banking.approval_request TO banking_app;
        GRANT UPDATE (payload, status, checker_id, decision_note, decided_at, applied_at)
            ON banking.approval_request TO banking_app;
        GRANT USAGE, SELECT ON SEQUENCE banking.approval_request_approval_id_seq TO banking_app;
    END IF;
END;
$$;
