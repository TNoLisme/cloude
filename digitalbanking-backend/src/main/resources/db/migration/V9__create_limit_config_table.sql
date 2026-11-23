-- BE-ADM-INT-01 / ADM-OI-05: Limit-owned configuration table used by the Admin Maker/Checker flow.
-- Canonical source: docs-common/04. Database design & analysis.md §4.5.1.
-- Forward-only: V1-V8 already applied and untouched.
-- Module limit is the single write-owner of banking.limit_config. Admin writes it only through
-- limit.api.LimitConfigMutationPort inside the approval transaction; no other module touches this table.
-- Conventions (04 §4.1): snake_case, BIGINT IDENTITY PK, enum = VARCHAR + CHECK, TIMESTAMPTZ UTC,
-- money NUMERIC(19,2)/CHAR(3), FK ON DELETE/UPDATE RESTRICT (never cascading), index uq_/ix_, check ck_.
-- Deliberately absent: scope_type/scope_id (04 canonical uses customer_id/account_id; _overview/06 is a draft),
-- hard delete paths and any auto-apply trigger.

CREATE TABLE banking.limit_config (
    limit_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id           BIGINT        NOT NULL,
    account_id            BIGINT        NULL,
    per_transaction_limit NUMERIC(19,2) NOT NULL
        CONSTRAINT ck_limit_per_tx_positive CHECK (per_transaction_limit > 0),
    daily_limit           NUMERIC(19,2) NOT NULL
        CONSTRAINT ck_limit_daily_positive CHECK (daily_limit > 0),
    currency              CHAR(3)       NOT NULL DEFAULT 'VND'
        CONSTRAINT ck_limit_currency CHECK (currency = 'VND'),
    effective_from        TIMESTAMPTZ   NOT NULL,
    effective_to          TIMESTAMPTZ   NULL,
    status                VARCHAR(16)   NOT NULL DEFAULT 'PENDING_APPROVAL'
        CONSTRAINT ck_limit_status CHECK (
            status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    version               INT           NOT NULL DEFAULT 1
        CONSTRAINT ck_limit_version_positive CHECK (version > 0),
    created_by            BIGINT        NOT NULL,
    approved_by           BIGINT        NULL,
    approval_id           BIGINT        NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_at           TIMESTAMPTZ   NULL,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_limit_customer FOREIGN KEY (customer_id)
        REFERENCES banking.customer (customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_limit_account FOREIGN KEY (account_id)
        REFERENCES banking.account (account_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_limit_created_by FOREIGN KEY (created_by)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_limit_approved_by FOREIGN KEY (approved_by)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_limit_approval FOREIGN KEY (approval_id)
        REFERENCES banking.approval_request (approval_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 04 §4.5.1 checks: separation of duties, decided states, reject shape and effective range.
    CONSTRAINT ck_limit_approved_by_differs CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT ck_limit_daily_ge_per_tx CHECK (daily_limit >= per_transaction_limit),
    CONSTRAINT ck_limit_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_limit_decided_fields CHECK (
        status NOT IN ('ACTIVE', 'SUPERSEDED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_limit_rejected_fields CHECK (
        status <> 'REJECTED' OR (approved_by IS NULL AND approved_at IS NULL)),
    -- One configuration row per approval: makes duplicate application impossible at the DB level.
    CONSTRAINT uq_limit_approval UNIQUE (approval_id)
);

-- 04 §4.5.1 partial unique: at most one ACTIVE configuration per scope.
CREATE UNIQUE INDEX uq_limit_active_customer
    ON banking.limit_config (customer_id) WHERE account_id IS NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_limit_active_account
    ON banking.limit_config (account_id) WHERE account_id IS NOT NULL AND status = 'ACTIVE';

-- Maker/checker review needs the pending row of an approval without scanning the table.
CREATE INDEX ix_limit_approval ON banking.limit_config (approval_id) WHERE approval_id IS NOT NULL;
CREATE INDEX ix_limit_customer_status ON banking.limit_config (customer_id, status);
CREATE INDEX ix_limit_account_status ON banking.limit_config (account_id, status);
CREATE INDEX ix_limit_effective ON banking.limit_config (effective_from, effective_to);
