CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY,
    identifier_normalized VARCHAR(254) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_otp_channel CHECK (channel IN ('SMS', 'EMAIL')),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('REGISTRATION', 'OPERATOR_CREATE_CUSTOMER', 'RECOVERY', 'PIN_RESET', 'TRANSFER_STEP_UP')),
    CONSTRAINT ck_otp_attempts CHECK (attempts BETWEEN 0 AND max_attempts AND max_attempts = 5),
    CONSTRAINT ck_otp_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_otp_terminal_times CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);
CREATE INDEX ix_otp_lookup ON otp_challenges (identifier_normalized, purpose, channel, created_at DESC);
CREATE INDEX ix_otp_expiry ON otp_challenges (expires_at) WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_session_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_refresh_hash CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_expiry CHECK (expires_at > created_at),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refresh_replacement FOREIGN KEY (replaced_by_session_id) REFERENCES refresh_sessions(id) ON DELETE RESTRICT
);
CREATE INDEX ix_refresh_sessions_user_active ON refresh_sessions (user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status SMALLINT,
    response_body JSONB,
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_scope_key UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_key_length CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_status CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 599),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);
