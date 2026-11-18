-- BE-INT-02 / DEC-INT-005: approval identity and maker facts are immutable to the runtime role.
-- V12 granted table-wide UPDATE; revoke it before granting only workflow columns.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE UPDATE ON banking.approval_request FROM banking_app;
        GRANT UPDATE (payload, status, checker_id, decision_note, decided_at, applied_at)
            ON banking.approval_request TO banking_app;
    END IF;
END;
$$;
