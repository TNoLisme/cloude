-- Test-only seed that runs AFTER the real Identity (V2-V4) and Customer (V5) migrations.
-- Rows satisfy the candidate transfer/idempotency foreign keys; IDs are forced with
-- OVERRIDING SYSTEM VALUE to keep the identifiers the transfer tests assert on.
-- Not a provider migration and does not prove Identity/Customer contracts.
INSERT INTO banking.app_user (user_id, username, password_hash, user_type)
OVERRIDING SYSTEM VALUE
VALUES (2, 'transfer-candidate-seed', 'seed-not-a-real-credential', 'CUSTOMER');

INSERT INTO banking.customer (customer_id, user_id, full_name_enc)
OVERRIDING SYSTEM VALUE
VALUES (1, 2, decode('736565642d6e6f742d7265616c2d7069', 'hex'));

INSERT INTO banking.auth_challenge
    (challenge_id, challenge_ref, user_id, purpose, method, channel, otp_hash,
     request_hash, idempotency_key, status, expires_at, correlation_id)
OVERRIDING SYSTEM VALUE
VALUES (6, '00000000-0000-4000-8000-000000000006', 2, 'TRANSFER_STEP_UP', 'OTP', 'SMS',
        'seed-otp-hash-6', 'a64chars00000000000000000000000000000000000000000000000000000000', 'seed-key-6',
        'CREATED', now() + interval '1 hour', 'seed-challenge-6'),
       (7, '00000000-0000-4000-8000-000000000007', 2, 'TRANSFER_STEP_UP', 'OTP', 'SMS',
        'seed-otp-hash-7', 'b64chars00000000000000000000000000000000000000000000000000000000', 'seed-key-7',
        'CREATED', now() + interval '1 hour', 'seed-challenge-7');

INSERT INTO banking.account (account_id) VALUES (3), (4), (5);
