-- Fail the migration explicitly if an upgrade needs an operator to reconcile active versions.
DO $$
BEGIN
    IF EXISTS (
        SELECT rule_code FROM banking.fraud_rule_config
        WHERE enabled AND effective_to IS NULL
        GROUP BY rule_code HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Reconcile duplicate open-ended enabled fraud rule versions before V21';
    END IF;
END;
$$;

ALTER TABLE banking.fraud_rule_config
    ADD COLUMN auto_block BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_fraud_effective_interval CHECK (effective_to IS NULL OR effective_to > effective_from),
    ADD CONSTRAINT ck_fraud_auto_block_type CHECK (NOT auto_block OR rule_type = 'UNUSUAL_PATTERN');

CREATE UNIQUE INDEX uq_fraud_rule_active ON banking.fraud_rule_config (rule_code)
    WHERE enabled AND effective_to IS NULL;
CREATE INDEX ix_fraud_rule_type ON banking.fraud_rule_config (rule_type, enabled);

-- Transfer-owned read model for TRFC-FR-01. DISTINCT is unnecessary: one row per durable intent.
CREATE VIEW banking.fraud_transfer_intent_history AS
    SELECT customer_id, created_at FROM banking.transfer WHERE status IN ('PENDING', 'COMPLETED', 'FAILED');
COMMENT ON VIEW banking.fraud_transfer_intent_history IS 'Transfer-owned, read-only intent count projection for Fraud';
REVOKE ALL ON banking.fraud_transfer_intent_history FROM PUBLIC;

DO $$
DECLARE
    seed_actor BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM banking.fraud_rule_config WHERE rule_code = 'LARGE_VALUE_TRANSFER')
        OR NOT EXISTS (SELECT 1 FROM banking.fraud_rule_config WHERE rule_code = 'HIGH_FREQUENCY_TRANSFER') THEN
        -- Dedicated non-login audit principal. Let a username collision abort instead of adopting an existing user.
        INSERT INTO banking.app_user (username, password_hash, user_type, status)
            VALUES ('__fraud_rule_bootstrap__', '!DISABLED', 'STAFF', 'DISABLED')
            RETURNING user_id INTO seed_actor;

        INSERT INTO banking.fraud_rule_config
            (rule_code, version, rule_name, rule_type, threshold_amount, threshold_count,
             window_seconds, severity, effective_from, created_by)
        SELECT seed.rule_code, 1, seed.rule_name, seed.rule_type, seed.threshold_amount, seed.threshold_count,
               seed.window_seconds, seed.severity, TIMESTAMPTZ '1970-01-01 00:00:00+00', seed_actor
        FROM (VALUES
            ('LARGE_VALUE_TRANSFER', 'Large value transfer', 'LARGE_VALUE', 10000000.00, NULL, NULL, 'HIGH'),
            ('HIGH_FREQUENCY_TRANSFER', 'High frequency transfer', 'HIGH_FREQUENCY', NULL, 5, 600, 'MEDIUM')
        ) AS seed(rule_code, rule_name, rule_type, threshold_amount, threshold_count, window_seconds, severity)
        WHERE NOT EXISTS (SELECT 1 FROM banking.fraud_rule_config existing WHERE existing.rule_code = seed.rule_code);
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT SELECT ON banking.fraud_transfer_intent_history TO banking_app;
    END IF;
END;
$$;
