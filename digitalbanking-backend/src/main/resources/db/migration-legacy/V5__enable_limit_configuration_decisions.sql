-- One limit version per real admin approval; seed configurations may have no link.
-- Cross-module FK validation remains staged until its owning tables exist.
CREATE UNIQUE INDEX uq_limit_approval ON banking.limit_config(approval_id)
    WHERE approval_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT UPDATE (status, effective_from, effective_to, approved_by, approved_at, updated_at)
            ON banking.limit_config TO banking_app;
    END IF;
END;
$$;
