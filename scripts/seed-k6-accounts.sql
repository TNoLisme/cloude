\set ON_ERROR_STOP on
\getenv k6_password_hash K6_PASSWORD_HASH
\getenv k6_pin_hash K6_PIN_HASH

BEGIN;

-- Run only against a disposable PostgreSQL database after Flyway migrations.
-- K6_PASSWORD_HASH and K6_PIN_HASH must be BCrypt hashes for test credentials.
CREATE TEMP TABLE _k6_seed_config (
    password_hash text NOT NULL,
    pin_hash text NOT NULL
) ON COMMIT DROP;

INSERT INTO _k6_seed_config (password_hash, pin_hash)
VALUES (:'k6_password_hash', :'k6_pin_hash');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM _k6_seed_config
        WHERE password_hash LIKE '$2%'
          AND pin_hash LIKE '$2%'
          AND length(password_hash) = 60
          AND length(pin_hash) = 60
    ) THEN
        RAISE EXCEPTION 'K6_PASSWORD_HASH and K6_PIN_HASH must be valid BCrypt hashes';
    END IF;
END
$$;

CREATE TEMP TABLE _k6_seed_rows ON COMMIT DROP AS
SELECT i,
       md5('cloude-k6-user-' || i)::uuid AS user_id,
       md5('cloude-k6-customer-' || i)::uuid AS customer_id,
       md5('cloude-k6-account-' || i)::uuid AS account_id,
       lpad((800000000 + i)::text, 10, '0') AS phone,
       format('k6-customer-%s@example.test', lpad(i::text, 3, '0')) AS email,
       lpad((700000000000 + i)::text, 12, '0') AS account_number
FROM generate_series(1, 100) AS i;

INSERT INTO users (id, phone, email_normalized, password_hash, is_active)
SELECT r.user_id, r.phone, r.email, c.password_hash, TRUE
FROM _k6_seed_rows r CROSS JOIN _k6_seed_config c;

INSERT INTO user_roles (user_id, role)
SELECT user_id, 'CUSTOMER' FROM _k6_seed_rows;

INSERT INTO customers (id, user_id, full_name)
SELECT customer_id, user_id, format('K6 Customer %s', i)
FROM _k6_seed_rows;

INSERT INTO customer_pins (customer_id, pin_hash, configured_at)
SELECT r.customer_id, c.pin_hash, CURRENT_TIMESTAMP
FROM _k6_seed_rows r CROSS JOIN _k6_seed_config c;

INSERT INTO accounts (id, account_number, customer_id, account_type, balance, currency, status, is_default)
SELECT account_id, account_number, customer_id, 'CHECKING', 10000000, 'VND', 'ACTIVE', TRUE
FROM _k6_seed_rows;

SELECT count(*) AS seeded_customers FROM _k6_seed_rows;
SELECT sum(balance)::numeric(19,0) AS seeded_balance_vnd
FROM accounts
WHERE account_number BETWEEN '700000000001' AND '700000000100';

COMMIT;
