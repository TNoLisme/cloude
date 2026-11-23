-- Candidate version is local to db/ledger-fixture + db/ledger-candidate.
CREATE TABLE banking.ledger_entry (
    entry_id BIGINT GENERATED ALWAYS AS IDENTITY,
    transfer_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    entry_seq SMALLINT NOT NULL,
    entry_type VARCHAR(8) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    balance_after NUMERIC(19,2) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT fk_ledger_transfer FOREIGN KEY (transfer_id)
        REFERENCES banking.transfer (transfer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id)
        REFERENCES banking.account (account_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ledger_entry_seq CHECK (entry_seq IN (1, 2)),
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_seq_type CHECK (
        (entry_seq = 1 AND entry_type = 'DEBIT') OR (entry_seq = 2 AND entry_type = 'CREDIT')
    ),
    CONSTRAINT ck_ledger_entry_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_ledger_entry_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_ledger_entry_balance CHECK (balance_after >= 0 AND balance_after <> 'NaN'::numeric),
    CONSTRAINT uq_ledger_transfer_seq UNIQUE (transfer_id, entry_seq)
);

CREATE INDEX ix_ledger_account_time ON banking.ledger_entry (account_id, created_at DESC);
CREATE INDEX ix_ledger_transfer ON banking.ledger_entry (transfer_id);
CREATE INDEX ix_ledger_correlation ON banking.ledger_entry (correlation_id);

CREATE FUNCTION banking.reject_ledger_entry_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'ledger_entry is append-only';
END;
$$;

CREATE TRIGGER trg_ledger_entry_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON banking.ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION banking.reject_ledger_entry_mutation();

-- Identity inserts use the sequence internally without a separate grant.
REVOKE ALL ON TABLE banking.ledger_entry FROM PUBLIC, banking_app;
REVOKE ALL ON SEQUENCE banking.ledger_entry_entry_id_seq FROM PUBLIC, banking_app;
REVOKE ALL ON FUNCTION banking.reject_ledger_entry_mutation() FROM PUBLIC, banking_app;
GRANT SELECT, INSERT ON TABLE banking.ledger_entry TO banking_app;

-- Object grants cannot subtract privileges inherited through another role.
DO $$
BEGIN
    IF current_user <> 'banking_migrator'
        OR pg_has_role('banking_app', 'banking_migrator', 'MEMBER')
        OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app'
                   AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication))
        OR NOT has_schema_privilege('banking_app', 'banking', 'USAGE')
        OR has_schema_privilege('banking_app', 'banking', 'CREATE')
        OR has_table_privilege('banking_app', 'banking.ledger_entry',
                               'UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER, SELECT WITH GRANT OPTION, INSERT WITH GRANT OPTION')
        OR has_sequence_privilege('banking_app', 'banking.ledger_entry_entry_id_seq', 'USAGE, SELECT, UPDATE')
        OR has_function_privilege('banking_app', 'banking.reject_ledger_entry_mutation()', 'EXECUTE')
    THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'ledger_entry requires isolated migration and runtime roles';
    END IF;
END;
$$;
