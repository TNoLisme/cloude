# E1 Data Model

Source 04 §4.1/4.5.1–2; 07 §8.5.2 adds per-transaction ≤ daily.

## limit_config

limit_id BIGINT identity PK; customer_id BIGINT NOT NULL; account_id BIGINT nullable; per_transaction_limit/daily_limit NUMERIC(19,2) NOT NULL; currency CHAR(3) NOT NULL default VND; effective_from TIMESTAMPTZ NOT NULL; effective_to TIMESTAMPTZ nullable; status VARCHAR(16) NOT NULL default PENDING_APPROVAL; version INT NOT NULL default 1; created_by BIGINT NOT NULL; approved_by/approval_id BIGINT nullable; created_at/updated_at TIMESTAMPTZ NOT NULL default now(); approved_at TIMESTAMPTZ nullable.

Checks: finite amounts > 0, per-transaction ≤ daily, VND, version > 0, end absent or > start; four states PENDING_APPROVAL/ACTIVE/SUPERSEDED/REJECTED. Checker absent or different from maker; ACTIVE/SUPERSEDED require checker and approved_at; REJECTED requires both absent. Use explicit IS NOT NULL in implication checks.

Partial uniques: uq_limit_active_customer(customer_id) WHERE account_id IS NULL AND status='ACTIVE'; uq_limit_active_account(account_id) WHERE account_id IS NOT NULL AND status='ACTIVE'. Indexes ix_limit_customer_status, ix_limit_account_status and ix_limit_effective per source. Nonactive versions coexist; no GLOBAL or history mutation API. No activation lifecycle in E1.

## limit_usage

usage_id BIGINT identity PK; customer_id BIGINT NOT NULL; account_id BIGINT nullable; usage_date DATE NOT NULL; currency CHAR(3) NOT NULL default VND; used_amount NUMERIC(19,2) NOT NULL default 0; transfer_count INT NOT NULL default 0; updated_at TIMESTAMPTZ NOT NULL default now().
Checks: finite used_amount >= 0, transfer_count >= 0 and VND.
Partial uniques uq_limit_usage_customer(customer_id,usage_date) WHERE account_id IS NULL and uq_limit_usage_account(account_id,usage_date) WHERE account_id IS NOT NULL; ix_limit_usage_date(usage_date). No configuration version FK or resetting usage on config insert/read.

## Deferred relationships

Keep reference columns, omit cross-module FK until canonical customer/account/app_user/approval_request migrations exist. No fake tables or silently conditional FK claiming integrity. Account existence/ownership and referential acceptance are explicitly deferred by user choice.

## Java mapping

BigDecimal, Instant, LocalDate; max 99999999999999999.99, at most 2 fractional digits, positive limits/check amount and nonnegative usage. Validate before JDBC so no rounding; compareTo for numerical equality. DB returns scale 2, generated IDs and timestamps. Stored timestamp tests use microsecond-compatible instants. For arbitrary nanosecond asOf inputs, floor only the query parameter to microseconds (PostgreSQL boundary precision) to preserve half-open comparisons; keep the original Instant in response metadata. Boundary regressions include ±1 ns around start/end. Nullable snapshot IDs signify new rows; persisted IDs must not be supplied to insert. Nullable created/updated timestamp inputs use DB defaults.
