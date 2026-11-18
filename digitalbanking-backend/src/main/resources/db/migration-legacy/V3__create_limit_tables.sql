-- Canonical model: docs-common/04 section 4.5.1-2 and 07 section 8.5.2.
-- Cross-module FKs to customer, account, app_user and approval_request are staged:
-- their owners must supply canonical tables before a later migration adds/validates FKs.
-- This migration does not certify reference existence or account/customer ownership.
CREATE TABLE banking.limit_config (
    limit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    account_id BIGINT,
    per_transaction_limit NUMERIC(19,2) NOT NULL,
    daily_limit NUMERIC(19,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_APPROVAL',
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    approved_by BIGINT,
    approval_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_limit_amounts CHECK (
        per_transaction_limit > 0 AND daily_limit > 0
        AND per_transaction_limit <> 'NaN'::numeric AND daily_limit <> 'NaN'::numeric
        AND per_transaction_limit <= daily_limit
    ),
    CONSTRAINT ck_limit_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_limit_version CHECK (version > 0),
    CONSTRAINT ck_limit_effective CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_limit_status CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    CONSTRAINT ck_limit_checker CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT ck_limit_approved CHECK (
        status NOT IN ('ACTIVE', 'SUPERSEDED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT ck_limit_rejected CHECK (
        status <> 'REJECTED' OR (approved_by IS NULL AND approved_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_limit_active_customer ON banking.limit_config(customer_id)
    WHERE account_id IS NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_limit_active_account ON banking.limit_config(account_id)
    WHERE account_id IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX ix_limit_customer_status ON banking.limit_config(customer_id, status);
CREATE INDEX ix_limit_account_status ON banking.limit_config(account_id, status);
CREATE INDEX ix_limit_effective ON banking.limit_config(effective_from, effective_to);

CREATE TABLE banking.limit_usage (
    usage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    account_id BIGINT,
    usage_date DATE NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    used_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    transfer_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_limit_usage_amount CHECK (used_amount >= 0 AND used_amount <> 'NaN'::numeric),
    CONSTRAINT ck_limit_usage_count CHECK (transfer_count >= 0),
    CONSTRAINT ck_limit_usage_currency CHECK (currency = 'VND')
);

CREATE UNIQUE INDEX uq_limit_usage_customer ON banking.limit_usage(customer_id, usage_date)
    WHERE account_id IS NULL;
CREATE UNIQUE INDEX uq_limit_usage_account ON banking.limit_usage(account_id, usage_date)
    WHERE account_id IS NOT NULL;
CREATE INDEX ix_limit_usage_date ON banking.limit_usage(usage_date);

REVOKE ALL ON banking.limit_config, banking.limit_usage FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE ALL ON banking.limit_config, banking.limit_usage FROM banking_app;
        GRANT USAGE ON SCHEMA banking TO banking_app;
        GRANT SELECT, INSERT ON banking.limit_config, banking.limit_usage TO banking_app;
        GRANT USAGE, SELECT ON SEQUENCE banking.limit_config_limit_id_seq, banking.limit_usage_usage_id_seq TO banking_app;
    END IF;
END;
$$;
