-- Test-only seed that runs AFTER the real Account (V8) migration.
-- Rows satisfy the candidate transfer/idempotency foreign keys; IDs are forced with
-- OVERRIDING SYSTEM VALUE to keep the identifiers the transfer tests assert on.
-- Not a provider migration and does not prove the Account contract.
INSERT INTO banking.account
    (account_id, customer_id, account_no_enc, account_no_hash, account_no_last4,
     currency, status, available_balance, ledger_balance)
OVERRIDING SYSTEM VALUE
VALUES (3, 1, decode('deadbeef01', 'hex'), repeat('a', 64), '0003', 'VND', 'ACTIVE', 0, 0),
       (4, 1, decode('deadbeef02', 'hex'), repeat('b', 64), '0004', 'VND', 'ACTIVE', 0, 0),
       (5, 1, decode('deadbeef03', 'hex'), repeat('c', 64), '0005', 'VND', 'ACTIVE', 0, 0);
