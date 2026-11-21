-- PostgreSQL row locks require UPDATE privilege. Expose only the Identity catalogue read lock,
-- keeping banking_app unable to mutate role/permission definitions (DEC-INT-018).
CREATE FUNCTION banking.lock_role_for_assignment(p_role_code TEXT)
RETURNS TABLE(role_id BIGINT, role_code VARCHAR, status VARCHAR)
LANGUAGE sql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT r.role_id, r.role_code, r.status
      FROM banking.role r
     WHERE r.role_code = p_role_code
       FOR SHARE OF r
$$;

REVOKE ALL ON FUNCTION banking.lock_role_for_assignment(TEXT) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        GRANT EXECUTE ON FUNCTION banking.lock_role_for_assignment(TEXT) TO banking_app;
    END IF;
END;
$$;
