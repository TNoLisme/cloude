-- BE-INT-01: merge the Limit usage model after Customer and Account exist.
-- The earlier module-local V3 created this table before its referenced providers;
-- the integrated chain can enforce the canonical foreign keys immediately.
CREATE TABLE banking.limit_usage (
    usage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES banking.customer(customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    account_id BIGINT REFERENCES banking.account(account_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    usage_date DATE NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND' CONSTRAINT ck_limit_usage_currency CHECK (currency = 'VND'),
    used_amount NUMERIC(19,2) NOT NULL DEFAULT 0
        CONSTRAINT ck_limit_usage_amount CHECK (used_amount >= 0 AND used_amount <> 'NaN'::numeric),
    transfer_count INT NOT NULL DEFAULT 0 CONSTRAINT ck_limit_usage_count CHECK (transfer_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_limit_usage_customer ON banking.limit_usage(customer_id, usage_date)
    WHERE account_id IS NULL;
CREATE UNIQUE INDEX uq_limit_usage_account ON banking.limit_usage(account_id, usage_date)
    WHERE account_id IS NOT NULL;
CREATE INDEX ix_limit_usage_date ON banking.limit_usage(usage_date);

-- The application role is separate from the migration role in deployed setups and some integration tests.
-- Grant only runtime tables; do not grant access to Flyway metadata or audit mutation.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT USAGE ON SCHEMA banking TO banking_app;
        GRANT SELECT ON banking.role, banking.permission, banking.role_permission TO banking_app;
        GRANT SELECT, INSERT, UPDATE ON banking.app_user, banking.device,
            banking.customer, banking.account, banking.approval_request,
            banking.admin_approval_attempt TO banking_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON banking.session, banking.auth_challenge,
            banking.auth_attempt, banking.password_history, banking.user_role,
            banking.user_privilege, banking.admin_idempotency_record TO banking_app;
        GRANT SELECT, INSERT ON banking.limit_config, banking.limit_usage TO banking_app;
        GRANT UPDATE (status, effective_from, effective_to, approved_by, approved_at, updated_at)
            ON banking.limit_config TO banking_app;
        GRANT UPDATE (used_amount, transfer_count, updated_at)
            ON banking.limit_usage TO banking_app;
        GRANT SELECT, INSERT ON banking.audit_log TO banking_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA banking TO banking_app;
    END IF;
END;
$$;
