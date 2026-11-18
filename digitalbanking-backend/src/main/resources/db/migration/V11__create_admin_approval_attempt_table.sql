-- BE-ADM-OPS-01: durable retry/failure evidence for the Admin approval workflow.
-- Admin-owned operational table (extension approved by DEC-ADM-010, recorded in DECISIONS.md and
-- docs-common/design/05. Modules contract.md §12.1): it is NOT business state and never changes the
-- approval status. Each failed checker attempt is written in its own short transaction so the failed
-- business transaction can still roll back; the row is the evidence used for retry/exhaustion/stuck alerts.
-- Contains no PII, no free-text reason, no payload, no challenge material: bounded codes only.
-- Forward-only: V1-V10 already applied and untouched.

CREATE TABLE banking.admin_approval_attempt (
    attempt_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    approval_id    BIGINT      NOT NULL,
    request_type   VARCHAR(32) NOT NULL
        CONSTRAINT ck_admin_attempt_request_type CHECK (request_type IN ('LIMIT_CHANGE', 'ROLE_CHANGE')),
    actor_user_id  BIGINT      NOT NULL,
    outcome        VARCHAR(32) NOT NULL
        CONSTRAINT ck_admin_attempt_outcome CHECK (outcome IN (
            'PROVIDER_FAILURE', 'VERSION_CONFLICT', 'AUDIT_FAILURE',
            'STEP_UP_FAILURE', 'TECHNICAL_FAILURE')),
    error_code     VARCHAR(64) NOT NULL
        CONSTRAINT ck_admin_attempt_error_code CHECK (length(error_code) BETWEEN 1 AND 64),
    correlation_id VARCHAR(64) NOT NULL
        CONSTRAINT ck_admin_attempt_correlation_id CHECK (length(correlation_id) BETWEEN 1 AND 64),
    request_id     VARCHAR(64) NULL
        CONSTRAINT ck_admin_attempt_request_id CHECK (request_id IS NULL OR length(request_id) <= 64),
    attempted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_admin_attempt_approval FOREIGN KEY (approval_id)
        REFERENCES banking.approval_request (approval_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admin_attempt_actor FOREIGN KEY (actor_user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_admin_attempt_approval ON banking.admin_approval_attempt (approval_id, attempted_at);
CREATE INDEX ix_admin_attempt_attempted ON banking.admin_approval_attempt (attempted_at);
