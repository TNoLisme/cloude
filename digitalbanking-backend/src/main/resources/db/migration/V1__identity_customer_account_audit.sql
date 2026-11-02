CREATE TABLE users (
    id UUID PRIMARY KEY,
    phone VARCHAR(10) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_phone UNIQUE (phone),
    CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_users_phone_vn CHECK (phone ~ '^0[3-9][0-9]{8}$'),
    CONSTRAINT ck_users_email_normalized CHECK (email_normalized = lower(btrim(email_normalized))),
    CONSTRAINT ck_users_email_not_empty CHECK (length(email_normalized) > 0)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN'))
);
CREATE INDEX ix_user_roles_role_user ON user_roles (role, user_id);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    address VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_customers_full_name CHECK (length(btrim(full_name)) BETWEEN 1 AND 120)
);
CREATE INDEX ix_customers_created_at_id ON customers (created_at DESC, id DESC);

CREATE TABLE customer_pins (
    customer_id UUID PRIMARY KEY,
    pin_hash VARCHAR(255),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    configured_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_customer_pins_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_customer_pins_configured CHECK (
        (pin_hash IS NULL AND configured_at IS NULL) OR
        (pin_hash IS NOT NULL AND configured_at IS NOT NULL)
    )
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(12) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    account_type VARCHAR(20) NOT NULL DEFAULT 'CHECKING',
    balance NUMERIC(19,0) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT TRUE,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_accounts_number CHECK (account_number ~ '^[0-9]{12}$'),
    CONSTRAINT ck_accounts_balance CHECK (balance >= 0),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_type CHECK (account_type = 'CHECKING')
);
CREATE UNIQUE INDEX uq_accounts_one_default_per_customer ON accounts (customer_id) WHERE is_default;
CREATE INDEX ix_accounts_customer_opened ON accounts (customer_id, opened_at DESC, id DESC);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID,
    actor_role VARCHAR(20),
    event_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID NOT NULL,
    outcome VARCHAR(10) NOT NULL,
    correlation_id UUID NOT NULL,
    summary VARCHAR(500) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_actor_role CHECK (actor_role IS NULL OR actor_role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN')),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_summary CHECK (length(btrim(summary)) BETWEEN 1 AND 500),
    CONSTRAINT ck_audit_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX ix_audit_occurred_id ON audit_events (occurred_at DESC, id DESC);
CREATE INDEX ix_audit_event_type_occurred ON audit_events (event_type, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_actor_occurred ON audit_events (actor_id, occurred_at DESC, id DESC);
