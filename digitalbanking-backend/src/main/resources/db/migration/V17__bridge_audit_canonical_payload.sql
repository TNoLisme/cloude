-- BE-INT-01: the existing AuditRecorder writer still stores its canonical payload.
-- Keep that append-only evidence column while both provider APIs share the integrated audit table.
ALTER TABLE banking.audit_log ADD COLUMN canonical_payload JSONB;
CREATE UNIQUE INDEX uq_audit_record_hash ON banking.audit_log(record_hash);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE ALL ON banking.audit_log FROM banking_app;
        GRANT SELECT, INSERT ON banking.audit_log TO banking_app;
    END IF;
END;
$$;
