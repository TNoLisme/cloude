CREATE TABLE registration_verification_tokens (
    id UUID PRIMARY KEY,
    phone VARCHAR(10) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_registration_phone CHECK (phone ~ '^0[3-9][0-9]{8}$'),
    CONSTRAINT ck_registration_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_registration_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_registration_terminal CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);
CREATE INDEX ix_registration_tokens_phone_active ON registration_verification_tokens(phone, expires_at)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
