-- T2 may change counters, never their scope/date or configuration history.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT UPDATE (used_amount, transfer_count, updated_at)
            ON banking.limit_usage TO banking_app;
    END IF;
END;
$$;
