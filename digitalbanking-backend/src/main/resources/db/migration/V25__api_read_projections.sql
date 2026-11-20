-- Read-only provider projections for the History and Fraud HTTP queries.
CREATE VIEW banking.account_history_participant AS
SELECT account_id, customer_id, account_no_last4 FROM banking.account;

CREATE VIEW banking.transfer_fraud_review_context AS
SELECT transfer_id, reference_no, customer_id FROM banking.transfer;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        REVOKE INSERT, UPDATE, DELETE ON banking.account_history_participant, banking.transfer_fraud_review_context
        FROM banking_app;
        GRANT SELECT ON banking.account_history_participant, banking.transfer_fraud_review_context TO banking_app;
    END IF;
END
$$;

-- The approved v1 permission matrix gives Auditor read-only fraud visibility.
INSERT INTO banking.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r CROSS JOIN banking.permission p
WHERE r.role_code = 'AUDITOR' AND p.permission_code = 'FRAUD_READ'
ON CONFLICT DO NOTHING;
