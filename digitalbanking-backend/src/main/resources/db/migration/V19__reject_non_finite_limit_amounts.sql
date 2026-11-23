-- BE-INT-02 / DEC-INT-006: PostgreSQL NUMERIC NaN sorts above ordinary numbers,
-- so positive/range checks alone do not guarantee a valid monetary limit.
ALTER TABLE banking.limit_config
    ADD CONSTRAINT ck_limit_amounts_finite CHECK (
        per_transaction_limit <> 'NaN'::numeric AND daily_limit <> 'NaN'::numeric
    );
