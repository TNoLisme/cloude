CREATE TABLE account_seed_records (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    amount NUMERIC(19,0) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    actor_id UUID NOT NULL,
    reference VARCHAR(100) NOT NULL,
    idempotency_record_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_seed_amount CHECK (amount BETWEEN 1 AND 100000000),
    CONSTRAINT ck_seed_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_seed_reference CHECK (length(btrim(reference)) BETWEEN 1 AND 100)
);
CREATE INDEX ix_seed_account_created ON account_seed_records (account_id, created_at DESC, id DESC);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount NUMERIC(19,0) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL,
    failure_code VARCHAR(80),
    memo VARCHAR(140),
    otp_challenge_id UUID,
    idempotency_record_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transfers_amount CHECK (amount BETWEEN 2000 AND 10000000),
    CONSTRAINT ck_transfers_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_transfers_status CHECK (status IN ('AWAITING_OTP', 'COMPLETED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_transfers_completion CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL) OR (status <> 'COMPLETED' AND completed_at IS NULL)),
    CONSTRAINT ck_transfers_pending_expiry CHECK (status <> 'AWAITING_OTP' OR expires_at IS NOT NULL)
);
CREATE INDEX ix_transfers_source_created_id ON transfers (source_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_destination_created_id ON transfers (destination_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_status_expiry ON transfers (status, expires_at) WHERE status = 'AWAITING_OTP';
CREATE INDEX ix_transfers_created_id ON transfers (created_at DESC, id DESC);
