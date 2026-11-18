CREATE TABLE banking.audit_log (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_type VARCHAR(8) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_user_id BIGINT,
    actor_role VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    before_state JSONB,
    after_state JSONB,
    amount NUMERIC(19,2),
    currency CHAR(3),
    correlation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'REJECTED')),
    client_info_masked JSONB,
    prev_hash CHAR(64) NOT NULL CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    record_hash CHAR(64) NOT NULL UNIQUE CHECK (record_hash ~ '^[0-9a-f]{64}$'),
    hash_version SMALLINT NOT NULL CHECK (hash_version > 0),
    canonical_payload JSONB NOT NULL,
    CONSTRAINT ck_audit_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL AND actor_user_id > 0)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
    ),
    CONSTRAINT ck_audit_money CHECK (
        (amount IS NULL AND currency IS NULL)
        OR (amount IS NOT NULL AND amount >= 0 AND currency IS NOT NULL AND currency = 'VND')
    )
);

-- The identity module adds the actor_user_id foreign key when app_user exists.
CREATE INDEX ix_audit_occurred ON banking.audit_log (occurred_at);
CREATE INDEX ix_audit_correlation ON banking.audit_log (correlation_id);
CREATE INDEX ix_audit_entity ON banking.audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_actor ON banking.audit_log (actor_user_id);
CREATE INDEX ix_audit_action ON banking.audit_log (action);

CREATE FUNCTION banking.reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append-only' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER audit_no_update_delete
BEFORE UPDATE OR DELETE ON banking.audit_log
FOR EACH ROW EXECUTE FUNCTION banking.reject_audit_mutation();
CREATE TRIGGER audit_no_truncate
BEFORE TRUNCATE ON banking.audit_log
FOR EACH STATEMENT EXECUTE FUNCTION banking.reject_audit_mutation();

REVOKE ALL ON banking.audit_log FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE ALL ON banking.audit_log FROM banking_app;
        GRANT SELECT, INSERT ON banking.audit_log TO banking_app;
        GRANT USAGE, SELECT ON SEQUENCE banking.audit_log_audit_id_seq TO banking_app;
    END IF;
END;
$$;
