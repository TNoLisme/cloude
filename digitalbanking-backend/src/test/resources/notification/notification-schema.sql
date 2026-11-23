CREATE TABLE IF NOT EXISTS banking.notification (
    notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    transfer_id BIGINT,
    auth_challenge_id BIGINT,
    notification_type VARCHAR(32) NOT NULL CHECK (notification_type IN ('TRANSFER_RESULT','OTP_DELIVERY','SECURITY_ALERT')),
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS')),
    template_code VARCHAR(32) NOT NULL,
    payload_masked JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','RETRYING','CANCELLED')),
    retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_retry_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    failure_code VARCHAR(32),
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token VARCHAR(64),
    locked_until TIMESTAMPTZ,
    claim_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_notification_customer_time ON banking.notification (customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_notification_status_retry ON banking.notification (status, next_retry_at);
CREATE INDEX IF NOT EXISTS ix_notification_transfer ON banking.notification (transfer_id);
CREATE INDEX IF NOT EXISTS ix_notification_correlation ON banking.notification (correlation_id);

GRANT ALL ON banking.notification TO banking_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA banking TO banking_app;
