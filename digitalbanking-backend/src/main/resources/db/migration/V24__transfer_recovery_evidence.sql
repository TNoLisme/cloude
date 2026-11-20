-- The T1 writer supplies both values only when the participating T2 writes Account evidence.
ALTER TABLE banking.transfer
    ADD COLUMN recovery_t1_policy VARCHAR(128),
    ADD COLUMN recovery_t1_date DATE,
    ADD CONSTRAINT ck_transfer_recovery_basis CHECK (
        (recovery_t1_policy IS NULL AND recovery_t1_date IS NULL)
        OR (recovery_t1_policy IS NOT NULL AND length(btrim(recovery_t1_policy)) > 0
            AND recovery_t1_date IS NOT NULL)
    );

CREATE TABLE banking.account_transfer_movement (
    transfer_id BIGINT PRIMARY KEY REFERENCES banking.transfer(transfer_id),
    source_account_id BIGINT NOT NULL REFERENCES banking.account(account_id),
    destination_account_id BIGINT NOT NULL REFERENCES banking.account(account_id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    source_available_after NUMERIC(19,2) NOT NULL,
    destination_available_after NUMERIC(19,2) NOT NULL,
    source_ledger_after NUMERIC(19,2) NOT NULL,
    destination_ledger_after NUMERIC(19,2) NOT NULL,
    CHECK (source_account_id <> destination_account_id),
    CHECK (source_available_after BETWEEN 0 AND source_ledger_after AND source_ledger_after <> 'NaN'::numeric),
    CHECK (destination_available_after BETWEEN 0 AND destination_ledger_after AND destination_ledger_after <> 'NaN'::numeric)
);

CREATE FUNCTION banking.reject_account_transfer_movement_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account transfer movement is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_account_transfer_movement_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON banking.account_transfer_movement
    FOR EACH STATEMENT EXECUTE FUNCTION banking.reject_account_transfer_movement_mutation();

-- Purge runs with the runtime role. Keep the terminal/expiry restriction in the DB as well.
CREATE FUNCTION banking.guard_transfer_idempotency_purge() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_user = 'banking_app' AND (OLD.status NOT IN ('COMPLETED', 'FAILED')
        OR OLD.expires_at >= statement_timestamp()
        OR NOT EXISTS (SELECT 1 FROM banking.transfer t
                       WHERE t.transfer_id = OLD.transfer_id AND t.status = OLD.status)) THEN
        RAISE EXCEPTION 'Transfer idempotency row cannot be purged' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_transfer_idempotency_purge
    BEFORE DELETE ON banking.idempotency_record
    FOR EACH ROW EXECUTE FUNCTION banking.guard_transfer_idempotency_purge();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE ALL ON banking.account_transfer_movement FROM banking_app;
        GRANT SELECT, INSERT ON banking.account_transfer_movement TO banking_app;
        GRANT DELETE ON banking.idempotency_record TO banking_app;
    END IF;
END;
$$;
