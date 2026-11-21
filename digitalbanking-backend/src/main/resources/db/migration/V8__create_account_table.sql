-- BE-ACC-02: Account table, constraints and indexes.
-- Canonical source: docs-common/04. Database design & analysis.md §4.3.1 (agrees with 07 §7.2.7-7.2.8
-- and OpenAPI AccountSummary/AccountDetail/AccountStatus); decisions recorded in DEC-ACC-001 /
-- DEC-ACC-002 and docs-backend/modules/account/BE-ACC-01.md §B.
-- Forward-only: V1-V7 are already applied and untouched; no out-of-order migration.
-- Module account is the single write-owner of banking.account; no other module may INSERT/UPDATE/
-- DELETE it. Conventions (04 §4.1): snake_case, BIGINT IDENTITY PK, enum = VARCHAR + CHECK,
-- TIMESTAMPTZ UTC, money NUMERIC(19,2)/CHAR(3), FK ON DELETE/UPDATE RESTRICT (never cascading),
-- index uq_/ix_, check ck_.
-- Deliberate omissions: no account_type (ACC-DR-03, not in MVP), no version column (ACC-DR-07,
-- pessimistic row lock + conditional UPDATE instead), no idempotency column (ACC-DR-04/BE-ACC-03),
-- no opening-balance/provider column (ACC-DR-06 still blocked).

CREATE TABLE banking.account (
    account_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT       NOT NULL,
    -- AES-256-GCM ciphertext produced by common.crypto.PiiCryptoService; plaintext is never stored.
    account_no_enc    BYTEA        NOT NULL,
    -- HMAC-SHA256 blind index (lowercase hex, 64 chars) used for equality/unique lookup.
    account_no_hash   CHAR(64)     NOT NULL
        CONSTRAINT ck_account_no_hash_hex CHECK (account_no_hash ~ '^[0-9a-f]{64}$'),
    -- Last 4 digits for masking (**** **** <last4>); account number itself is ^[0-9]{6,20}$ (OpenAPI).
    account_no_last4  CHAR(4)      NOT NULL
        CONSTRAINT ck_account_no_last4_digits CHECK (account_no_last4 ~ '^[0-9]{4}$'),
    currency          CHAR(3)      NOT NULL DEFAULT 'VND'
        CONSTRAINT ck_account_currency CHECK (currency = 'VND'),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'LOCKED', 'CLOSED')),
    available_balance NUMERIC(19,2) NOT NULL DEFAULT 0
        CONSTRAINT ck_account_available_balance_non_negative CHECK (available_balance >= 0),
    ledger_balance    NUMERIC(19,2) NOT NULL DEFAULT 0
        CONSTRAINT ck_account_ledger_balance_non_negative CHECK (ledger_balance >= 0),
    closed_at         TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_no_hash UNIQUE (account_no_hash),
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id)
        REFERENCES banking.customer (customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- MVP has no hold: available never exceeds the materialized ledger balance (04 §4.3.1).
    CONSTRAINT ck_account_balance_within_ledger CHECK (available_balance <= ledger_balance),
    -- Closing keeps the financial record: status='CLOSED' requires closed_at, never a hard delete.
    CONSTRAINT ck_account_closed_at_required CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
);

-- Customer account listing (API-ACC-01, API-ADM-02): WHERE customer_id = ? ORDER BY account_id.
CREATE INDEX ix_account_customer ON banking.account (customer_id);
-- Canonical status filter/lifecycle scan. Low cardinality (3 values) and therefore only useful for
-- broad operational counts, not as a standalone access path for one customer; kept because it is
-- part of the approved 04 §4.3.1 index set.
CREATE INDEX ix_account_status ON banking.account (status);
