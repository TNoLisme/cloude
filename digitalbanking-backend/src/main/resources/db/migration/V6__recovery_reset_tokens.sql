CREATE TABLE recovery_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_recovery_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recovery_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_recovery_token_terminal CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

CREATE INDEX ix_recovery_reset_tokens_user_active
    ON recovery_reset_tokens (user_id, expires_at)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
