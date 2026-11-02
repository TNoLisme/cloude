CREATE TABLE risk_flags (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL,
    rule_id VARCHAR(80) NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_risk_flag_rule_transfer_version UNIQUE (transfer_id, rule_id, rule_version),
    CONSTRAINT ck_risk_rule_id CHECK (length(btrim(rule_id)) BETWEEN 1 AND 80),
    CONSTRAINT ck_risk_rule_version CHECK (length(btrim(rule_version)) BETWEEN 1 AND 40),
    CONSTRAINT ck_risk_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);
CREATE INDEX ix_risk_flags_detected_id ON risk_flags (detected_at DESC, id DESC);
CREATE INDEX ix_risk_flags_rule_detected ON risk_flags (rule_id, detected_at DESC, id DESC);
CREATE INDEX ix_risk_flags_transfer ON risk_flags (transfer_id);
