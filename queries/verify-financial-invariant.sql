\set ON_ERROR_STOP on
\getenv expected_balance_vnd K6_EXPECTED_BALANCE_VND

-- Run before and after k6 against the same disposable dataset.
-- Set K6_EXPECTED_BALANCE_VND to the baseline output captured before load.
WITH fixture_accounts AS (
    SELECT id, balance
    FROM accounts
    WHERE account_number BETWEEN '700000000001' AND '700000000100'
), totals AS (
    SELECT count(*) AS account_count,
           coalesce(sum(balance), 0)::numeric(19,0) AS current_balance_vnd,
           count(*) FILTER (WHERE balance < 0) AS negative_balance_count
    FROM fixture_accounts
), transfer_duplicates AS (
    SELECT count(*) AS duplicate_idempotency_resources
    FROM (
        SELECT idempotency_record_id
        FROM transfers
        WHERE status = 'COMPLETED'
          AND idempotency_record_id IS NOT NULL
        GROUP BY idempotency_record_id
        HAVING count(*) > 1
    ) duplicates
)
SELECT account_count,
       current_balance_vnd,
       :'expected_balance_vnd'::numeric(19,0) AS expected_balance_vnd,
       current_balance_vnd - :'expected_balance_vnd'::numeric(19,0) AS delta_vnd,
       negative_balance_count,
       duplicate_idempotency_resources,
       account_count = 100
           AND current_balance_vnd = :'expected_balance_vnd'::numeric(19,0)
           AND negative_balance_count = 0
           AND duplicate_idempotency_resources = 0 AS invariant_pass
FROM totals
CROSS JOIN transfer_duplicates;
