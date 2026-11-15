CREATE TABLE banking.app_user (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    user_type VARCHAR(16) NOT NULL CHECK (user_type IN ('CUSTOMER', 'STAFF')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (failed_attempt_count >= 0),
    locked_until TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT chk_app_user_normalized_username CHECK (username = lower(username))
);
CREATE INDEX ix_app_user_status ON banking.app_user(status);
CREATE INDEX ix_app_user_locked_until ON banking.app_user(locked_until) WHERE locked_until IS NOT NULL;

CREATE TABLE banking.role (
    role_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE banking.permission (
    permission_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_code VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE banking.user_role (
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    role_id BIGINT NOT NULL REFERENCES banking.role(role_id) ON DELETE RESTRICT,
    assigned_by BIGINT REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_role_role ON banking.user_role(role_id);

CREATE TABLE banking.role_permission (
    role_id BIGINT NOT NULL REFERENCES banking.role(role_id) ON DELETE RESTRICT,
    permission_id BIGINT NOT NULL REFERENCES banking.permission(permission_id) ON DELETE RESTRICT,
    granted_by BIGINT REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX ix_role_permission_permission ON banking.role_permission(permission_id);

CREATE TABLE banking.user_privilege (
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    privilege_code VARCHAR(32) NOT NULL CHECK (privilege_code IN ('CHECKER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    granted_by BIGINT REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, privilege_code)
);
CREATE INDEX ix_user_privilege_code ON banking.user_privilege(privilege_code);

CREATE TABLE banking.customer (
    customer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    cif_number VARCHAR(32) NOT NULL UNIQUE,
    full_name_enc BYTEA NOT NULL,
    phone_enc BYTEA,
    email_enc BYTEA,
    national_id_enc BYTEA NOT NULL,
    national_id_hash CHAR(64) NOT NULL UNIQUE,
    national_id_last4 CHAR(4) NOT NULL CHECK (national_id_last4 ~ '^[0-9]{4}$'),
    kyc_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_customer_status ON banking.customer(status);
CREATE INDEX ix_customer_kyc_status ON banking.customer(kyc_status);

CREATE TABLE banking.device (
    device_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_ref UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    device_name VARCHAR(100) NOT NULL,
    device_fingerprint_hash CHAR(64) NOT NULL,
    fido_credential_id VARCHAR(255) UNIQUE,
    passcode_hash VARCHAR(255),
    trusted BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    trusted_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_user_fingerprint UNIQUE (user_id, device_fingerprint_hash)
);
CREATE INDEX ix_device_user_status ON banking.device(user_id, status);

CREATE TABLE banking.session (
    session_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_token_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    device_id BIGINT REFERENCES banking.device(device_id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'LOGGED_OUT', 'REVOKED')),
    revoked_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    logged_out_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_session_absolute_expiry CHECK (absolute_expires_at > created_at),
    CONSTRAINT chk_session_idle_expiry CHECK (idle_expires_at >= last_activity_at)
);
CREATE INDEX ix_session_user_status ON banking.session(user_id, status);
CREATE INDEX ix_session_idle_expires ON banking.session(idle_expires_at);
CREATE INDEX ix_session_absolute_expires ON banking.session(absolute_expires_at);

CREATE TABLE banking.auth_challenge (
    challenge_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenge_ref UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    device_id BIGINT REFERENCES banking.device(device_id) ON DELETE RESTRICT,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('LOGIN_NEW_DEVICE', 'TRANSFER_STEP_UP', 'SENSITIVE_ACTION')),
    method VARCHAR(16) NOT NULL CHECK (method IN ('OTP', 'FIDO', 'PASSCODE')),
    channel VARCHAR(16) CHECK (channel IN ('SMS', 'EMAIL', 'IN_APP')),
    otp_hash CHAR(64),
    fido_credential_id VARCHAR(255),
    request_hash CHAR(64),
    idempotency_key VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'SENT', 'VERIFIED', 'CONSUMED', 'LOCKED', 'EXPIRED', 'CANCELLED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts SMALLINT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    resend_count SMALLINT NOT NULL DEFAULT 0 CHECK (resend_count >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    correlation_id VARCHAR(64) NOT NULL,
    CONSTRAINT chk_auth_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_auth_challenge_attempts CHECK (attempt_count <= max_attempts),
    CONSTRAINT chk_auth_challenge_otp CHECK (method <> 'OTP' OR (otp_hash IS NOT NULL AND channel IS NOT NULL)),
    CONSTRAINT chk_auth_challenge_fido CHECK (method <> 'FIDO' OR (fido_credential_id IS NOT NULL AND device_id IS NOT NULL)),
    CONSTRAINT chk_auth_challenge_passcode CHECK (method <> 'PASSCODE' OR device_id IS NOT NULL),
    CONSTRAINT chk_auth_challenge_transfer_binding CHECK (purpose <> 'TRANSFER_STEP_UP' OR (request_hash IS NOT NULL AND idempotency_key IS NOT NULL)),
    CONSTRAINT chk_auth_challenge_sensitive_binding CHECK (purpose <> 'SENSITIVE_ACTION' OR request_hash IS NOT NULL)
);
CREATE INDEX ix_auth_challenge_user_status ON banking.auth_challenge(user_id, status, expires_at);
CREATE INDEX ix_auth_challenge_idempotency ON banking.auth_challenge(idempotency_key);
CREATE INDEX ix_auth_challenge_expires ON banking.auth_challenge(expires_at);
CREATE UNIQUE INDEX uq_auth_challenge_active_stepup
    ON banking.auth_challenge(idempotency_key, request_hash)
    WHERE purpose = 'TRANSFER_STEP_UP' AND status IN ('CREATED', 'SENT', 'VERIFIED') AND consumed_at IS NULL;

CREATE TABLE banking.auth_attempt (
    attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    attempt_type VARCHAR(16) NOT NULL CHECK (attempt_type IN ('LOGIN', 'PASSCODE', 'FIDO', 'OTP', 'STEP_UP')),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'LOCKED')),
    reason_code VARCHAR(32),
    correlation_id VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_info_masked JSONB
);
CREATE INDEX ix_auth_attempt_user_time ON banking.auth_attempt(user_id, attempted_at DESC);
CREATE INDEX ix_auth_attempt_user_outcome_time ON banking.auth_attempt(user_id, outcome, attempted_at DESC);

CREATE TABLE banking.password_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_history_user_time ON banking.password_history(user_id, created_at DESC);

CREATE TABLE banking.account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES banking.customer(customer_id) ON DELETE RESTRICT,
    account_no_enc BYTEA NOT NULL,
    account_no_hash CHAR(64) NOT NULL UNIQUE,
    account_no_last4 CHAR(4) NOT NULL CHECK (account_no_last4 ~ '^[0-9]{4}$'),
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS')),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'CLOSED')),
    available_balance NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    ledger_balance NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (ledger_balance >= 0),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_balances CHECK (available_balance <= ledger_balance)
);
CREATE INDEX ix_account_customer ON banking.account(customer_id);
CREATE INDEX ix_account_status ON banking.account(status);

CREATE TABLE banking.approval_request (
    approval_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_type VARCHAR(32) NOT NULL CHECK (request_type IN ('LIMIT_CHANGE', 'ROLE_CHANGE')),
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN ('CUSTOMER', 'ACCOUNT', 'APP_USER')),
    target_id VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    result JSONB,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'CANCELLED', 'EXPIRED')),
    maker_id BIGINT NOT NULL REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    checker_id BIGINT REFERENCES banking.app_user(user_id) ON DELETE RESTRICT,
    decision_note VARCHAR(500),
    step_up_challenge_id BIGINT REFERENCES banking.auth_challenge(challenge_id) ON DELETE RESTRICT,
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT chk_approval_maker_ne_checker CHECK (checker_id IS NULL OR checker_id <> maker_id),
    CONSTRAINT chk_approval_decision CHECK (status NOT IN ('APPROVED', 'REJECTED', 'APPLIED') OR (checker_id IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT chk_approval_applied CHECK (status <> 'APPLIED' OR applied_at IS NOT NULL)
);
CREATE INDEX ix_approval_status_time ON banking.approval_request(status, created_at DESC);
CREATE INDEX ix_approval_type_status ON banking.approval_request(request_type, status);
CREATE INDEX ix_approval_target ON banking.approval_request(target_type, target_id);
CREATE INDEX ix_approval_maker ON banking.approval_request(maker_id);
CREATE INDEX ix_approval_checker ON banking.approval_request(checker_id);
CREATE UNIQUE INDEX uq_approval_pending_target
    ON banking.approval_request(request_type, target_type, target_id)
    WHERE status = 'PENDING';

INSERT INTO banking.role(role_code, name) VALUES
    ('CUSTOMER', 'Customer'),
    ('OPERATIONS', 'Operations'),
    ('ADMIN', 'Administrator'),
    ('AUDITOR', 'Auditor');

INSERT INTO banking.permission(permission_code, description) VALUES
    ('ACCOUNT_READ_SELF', 'Read own accounts and profile'),
    ('TRANSACTION_READ_SELF', 'Read own transactions'),
    ('TRANSFER_CREATE', 'Create transfer from owned account'),
    ('LIMIT_READ_SELF', 'Read own limits'),
    ('CUSTOMER_READ', 'Read customer profiles'),
    ('ACCOUNT_READ', 'Read customer accounts'),
    ('TRANSACTION_READ', 'Read customer transactions'),
    ('AUDIT_READ', 'Read audit records'),
    ('AUDIT_EXPORT', 'Export audit records'),
    ('FRAUD_READ', 'Read fraud flags'),
    ('FRAUD_REVIEW', 'Review fraud flags'),
    ('LIMIT_MAKER', 'Propose limit changes'),
    ('LIMIT_MANAGE', 'Manage limit changes'),
    ('ROLE_MANAGE', 'Manage role and privilege changes'),
    ('RETENTION_MANAGE', 'Manage retention policy');

INSERT INTO banking.role_permission(role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r
JOIN banking.permission p ON
    (r.role_code = 'CUSTOMER' AND p.permission_code IN ('ACCOUNT_READ_SELF', 'TRANSACTION_READ_SELF', 'TRANSFER_CREATE', 'LIMIT_READ_SELF'))
 OR (r.role_code = 'OPERATIONS' AND p.permission_code IN ('CUSTOMER_READ', 'ACCOUNT_READ', 'TRANSACTION_READ', 'AUDIT_READ', 'FRAUD_REVIEW', 'LIMIT_MAKER'))
 OR (r.role_code = 'ADMIN' AND p.permission_code IN ('CUSTOMER_READ', 'ACCOUNT_READ', 'TRANSACTION_READ', 'AUDIT_READ', 'FRAUD_READ', 'LIMIT_MANAGE', 'ROLE_MANAGE', 'RETENTION_MANAGE'))
 OR (r.role_code = 'AUDITOR' AND p.permission_code IN ('AUDIT_READ', 'AUDIT_EXPORT', 'TRANSACTION_READ', 'ACCOUNT_READ'));
