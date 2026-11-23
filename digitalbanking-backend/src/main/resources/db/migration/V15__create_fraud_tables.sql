-- BE-INT-01: promote Fraud schema after Transfer and Identity.
CREATE TABLE banking.fraud_rule_config (
    rule_code VARCHAR(32) NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    rule_name VARCHAR(100) NOT NULL,
    rule_type VARCHAR(32) NOT NULL CHECK (rule_type IN ('LARGE_VALUE','HIGH_FREQUENCY','UNUSUAL_PATTERN')),
    threshold_amount NUMERIC(19,2),
    threshold_count INT,
    window_seconds INT,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by BIGINT NOT NULL,
    approved_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (rule_code, version),
    CONSTRAINT fk_fraud_rule_created_by FOREIGN KEY (created_by) REFERENCES banking.app_user(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fraud_rule_approved_by FOREIGN KEY (approved_by) REFERENCES banking.app_user(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_large_value CHECK (rule_type != 'LARGE_VALUE' OR threshold_amount IS NOT NULL),
    CONSTRAINT ck_high_freq CHECK (rule_type != 'HIGH_FREQUENCY' OR (threshold_count IS NOT NULL AND window_seconds IS NOT NULL))
);

CREATE TABLE banking.fraud_flag (
    flag_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL,
    rule_code VARCHAR(32) NOT NULL,
    rule_version INT NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','UNDER_REVIEW','ALLOWED','BLOCKED','CLOSED')),
    matched_value JSONB,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMPTZ,
    decision VARCHAR(16) CHECK (decision IN ('ALLOW','BLOCK','ESCALATE','CLOSE')),
    notes VARCHAR(500),
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_fraud_flag_rule FOREIGN KEY (rule_code, rule_version) REFERENCES banking.fraud_rule_config (rule_code, version),
    CONSTRAINT fk_fraud_flag_transfer FOREIGN KEY (transfer_id) REFERENCES banking.transfer(transfer_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fraud_flag_reviewer FOREIGN KEY (reviewed_by) REFERENCES banking.app_user(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_fraud_flag_transfer_rule UNIQUE (transfer_id, rule_code, rule_version)
);

CREATE INDEX IF NOT EXISTS ix_fraud_flag_status_time ON banking.fraud_flag (status, created_at);
CREATE INDEX IF NOT EXISTS ix_fraud_flag_transfer ON banking.fraud_flag (transfer_id);
CREATE INDEX IF NOT EXISTS ix_fraud_flag_rule ON banking.fraud_flag (rule_code, rule_version);
CREATE INDEX IF NOT EXISTS ix_fraud_flag_correlation ON banking.fraud_flag (correlation_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT SELECT, INSERT, UPDATE ON banking.fraud_rule_config, banking.fraud_flag TO banking_app;
        GRANT USAGE, SELECT ON SEQUENCE banking.fraud_flag_flag_id_seq TO banking_app;
    END IF;
END;
$$;
