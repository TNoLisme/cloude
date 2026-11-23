-- The composite key ties Limit's immutable evidence to the Transfer facts it received.
ALTER TABLE banking.transfer ADD CONSTRAINT uq_transfer_limit_link
    UNIQUE (transfer_id, customer_id, source_account_id, amount, currency, created_at);

CREATE TABLE banking.limit_transfer_reservation (
    transfer_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES banking.customer(customer_id),
    source_account_id BIGINT NOT NULL REFERENCES banking.account(account_id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    transfer_created_at TIMESTAMPTZ NOT NULL,
    gate_at TIMESTAMPTZ NOT NULL,
    t1_date DATE NOT NULL,
    policy_version VARCHAR(128) NOT NULL CHECK (length(btrim(policy_version)) > 0),
    state VARCHAR(8) NOT NULL DEFAULT 'PENDING',
    terminal_at TIMESTAMPTZ,
    terminal_date DATE,
    effect_count SMALLINT,
    CONSTRAINT fk_limit_transfer_link FOREIGN KEY
        (transfer_id, customer_id, source_account_id, amount, currency, transfer_created_at)
        REFERENCES banking.transfer(transfer_id, customer_id, source_account_id, amount, currency, created_at),
    CONSTRAINT ck_limit_reservation_state CHECK (
        (state = 'PENDING' AND terminal_at IS NULL AND terminal_date IS NULL AND effect_count IS NULL)
        OR (state IN ('SETTLED', 'RELEASED') AND terminal_at IS NOT NULL
            AND terminal_at >= transfer_created_at AND terminal_at >= gate_at AND terminal_date IS NOT NULL
            AND effect_count IS NOT NULL
            AND ((state = 'SETTLED' AND effect_count IN (1, 2)) OR (state = 'RELEASED' AND effect_count = 0))))
);

CREATE INDEX ix_limit_reservation_pending_customer
    ON banking.limit_transfer_reservation(customer_id) WHERE state = 'PENDING';

CREATE TABLE banking.limit_transfer_usage_effect (
    transfer_id BIGINT NOT NULL REFERENCES banking.limit_transfer_reservation(transfer_id),
    scope VARCHAR(8) NOT NULL CHECK (scope IN ('CUSTOMER', 'ACCOUNT')),
    usage_id BIGINT NOT NULL CHECK (usage_id > 0),
    amount_delta NUMERIC(19,2) NOT NULL CHECK (amount_delta > 0 AND amount_delta <> 'NaN'::numeric),
    count_delta SMALLINT NOT NULL CHECK (count_delta = 1),
    config_id BIGINT NOT NULL CHECK (config_id > 0),
    config_version INTEGER NOT NULL CHECK (config_version > 0),
    per_transaction_limit NUMERIC(19,2) NOT NULL
        CHECK (per_transaction_limit > 0 AND per_transaction_limit <> 'NaN'::numeric),
    daily_limit NUMERIC(19,2) NOT NULL CHECK (daily_limit >= per_transaction_limit AND daily_limit <> 'NaN'::numeric),
    PRIMARY KEY (transfer_id, scope)
);

CREATE FUNCTION banking.guard_limit_reservation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('SETTLED', 'RELEASED') THEN
        RAISE EXCEPTION 'invalid Limit reservation transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_limit_reservation_transition
    BEFORE UPDATE ON banking.limit_transfer_reservation FOR EACH ROW
    EXECUTE FUNCTION banking.guard_limit_reservation_transition();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT SELECT ON banking.limit_transfer_reservation, banking.limit_transfer_usage_effect TO banking_app;
        GRANT INSERT (transfer_id, customer_id, source_account_id, amount, currency,
                      transfer_created_at, gate_at, t1_date, policy_version)
            ON banking.limit_transfer_reservation TO banking_app;
        GRANT UPDATE (state, terminal_at, terminal_date, effect_count)
            ON banking.limit_transfer_reservation TO banking_app;
        GRANT INSERT ON banking.limit_transfer_usage_effect TO banking_app;
    END IF;
END;
$$;
