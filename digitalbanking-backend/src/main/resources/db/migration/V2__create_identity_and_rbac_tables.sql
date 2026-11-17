-- BE-ID-IMP-01: Identity & RBAC tables.
-- Nguồn chuẩn duy nhất: docs-common/04. Database design & analysis.md §4.1, §4.2 (app_user, session,
-- device, auth_challenge, auth_attempt, password_history) và §4.2.8 + Appendix A.1/A.2 (RBAC, seed).
-- Bảng `customer` KHÔNG thuộc migration này: thuộc module customer, theo kế hoạch V3
-- (docs-backend/00. architecture & conventions.md §8.5).
-- Quy ước: snake_case, PK IDENTITY, enum = VARCHAR + CHECK, TIMESTAMPTZ UTC, FK RESTRICT,
-- không CASCADE, index uq_/ix_, check ck_ (04 §4.1; BE-DAT-01..05).
-- Các quyết định mở OD-ID-04/05/06 đã phân xử theo thứ bậc thẩm quyền (00 §1.5: docs-common 01–11
-- > design/ > docs-backend): enum session/challenge giữ nguyên theo 04 §4.2.3/§4.2.5; consume
-- challenge = `consumed_at`, status KHÔNG có 'CONSUMED' (04 §4.2.5 + design/02 §5.1).

-- ---------------------------------------------------------------------------
-- 4.2.1. app_user
-- ---------------------------------------------------------------------------
CREATE TABLE banking.app_user (
    user_id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL
        CONSTRAINT ck_app_user_username_len CHECK (length(username) BETWEEN 1 AND 64),
    password_hash         VARCHAR(255) NOT NULL,
    user_type             VARCHAR(16)  NOT NULL
        CONSTRAINT ck_app_user_user_type CHECK (user_type IN ('CUSTOMER', 'STAFF')),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_attempt_count  SMALLINT     NOT NULL DEFAULT 0
        CONSTRAINT ck_app_user_failed_attempt_count CHECK (failed_attempt_count >= 0),
    locked_until          TIMESTAMPTZ  NULL,
    last_failed_at        TIMESTAMPTZ  NULL,
    last_login_at         TIMESTAMPTZ  NULL,
    password_changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);

CREATE INDEX ix_app_user_status ON banking.app_user (status);
CREATE INDEX ix_app_user_locked_until ON banking.app_user (locked_until)
    WHERE locked_until IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4.2.4. device
-- ---------------------------------------------------------------------------
CREATE TABLE banking.device (
    device_id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_ref              UUID         NOT NULL,
    user_id                 BIGINT       NOT NULL,
    device_name             VARCHAR(100) NOT NULL
        CONSTRAINT ck_device_name_len CHECK (length(device_name) BETWEEN 1 AND 100),
    device_fingerprint_hash CHAR(64)     NOT NULL,
    fido_credential_id      VARCHAR(255) NULL,
    passcode_hash           VARCHAR(255) NULL,
    trusted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_device_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    trusted_at              TIMESTAMPTZ  NULL,
    last_seen_at            TIMESTAMPTZ  NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_ref UNIQUE (device_ref),
    CONSTRAINT fk_device_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- FIDO credential unique khi có giá trị; nhiều NULL được phép (credential chưa đăng ký).
CREATE UNIQUE INDEX uq_device_fido ON banking.device (fido_credential_id);
CREATE UNIQUE INDEX uq_device_user_fingerprint
    ON banking.device (user_id, device_fingerprint_hash);
CREATE INDEX ix_device_user_status ON banking.device (user_id, status);

-- ---------------------------------------------------------------------------
-- 4.2.3. session
-- ---------------------------------------------------------------------------
-- BE-ID-IMP-02 bổ sung `csrf_token_hash`: `SessionAuthenticator.Session` (common) bắt buộc
-- csrfTokenHash [0-9a-f]{64} và design/04 §4.3 định nghĩa CSRF session-bound; 04 §4.2.3 thiếu cột
-- này (xung đột nguồn, DEC-ID-002 trong DECISIONS.md — chờ DB owner rà lại).
CREATE TABLE banking.session (
    session_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_token_hash  CHAR(64)    NOT NULL,
    csrf_token_hash     CHAR(64)    NOT NULL,
    user_id             BIGINT      NOT NULL,
    device_id           BIGINT      NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_session_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'LOGGED_OUT')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    idle_expires_at     TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    logged_out_at       TIMESTAMPTZ NULL,
    CONSTRAINT uq_session_token_hash UNIQUE (session_token_hash),
    CONSTRAINT ck_session_absolute_after_created
        CHECK (absolute_expires_at > created_at),
    CONSTRAINT ck_session_idle_after_activity
        CHECK (idle_expires_at >= last_activity_at),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_session_device FOREIGN KEY (device_id)
        REFERENCES banking.device (device_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_session_user_status ON banking.session (user_id, status);
CREATE INDEX ix_session_idle_expires ON banking.session (idle_expires_at);
CREATE INDEX ix_session_absolute_expires ON banking.session (absolute_expires_at);

-- ---------------------------------------------------------------------------
-- 4.2.5. auth_challenge
-- ---------------------------------------------------------------------------
CREATE TABLE banking.auth_challenge (
    challenge_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenge_ref     UUID         NOT NULL,
    user_id           BIGINT       NOT NULL,
    device_id         BIGINT       NULL,
    purpose           VARCHAR(32)  NOT NULL
        CONSTRAINT ck_auth_challenge_purpose
            CHECK (purpose IN ('LOGIN_NEW_DEVICE', 'TRANSFER_STEP_UP', 'SENSITIVE_ACTION')),
    method            VARCHAR(16)  NOT NULL
        CONSTRAINT ck_auth_challenge_method CHECK (method IN ('OTP', 'FIDO', 'PASSCODE')),
    channel           VARCHAR(16)  NULL
        CONSTRAINT ck_auth_challenge_channel CHECK (channel IN ('SMS', 'EMAIL', 'IN_APP')),
    otp_hash          VARCHAR(255) NULL,
    fido_credential_id VARCHAR(255) NULL,
    request_hash      CHAR(64)     NULL,
    idempotency_key   VARCHAR(255) NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED'
        CONSTRAINT ck_auth_challenge_status
            CHECK (status IN ('CREATED', 'SENT', 'VERIFIED', 'LOCKED', 'EXPIRED', 'CANCELLED')),
    attempt_count     SMALLINT     NOT NULL DEFAULT 0
        CONSTRAINT ck_auth_challenge_attempt_count CHECK (attempt_count >= 0),
    max_attempts      SMALLINT     NOT NULL DEFAULT 5
        CONSTRAINT ck_auth_challenge_max_attempts CHECK (max_attempts > 0),
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ  NULL,
    verified_at       TIMESTAMPTZ  NULL,
    consumed_at       TIMESTAMPTZ  NULL,
    correlation_id    VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_auth_challenge_ref UNIQUE (challenge_ref),
    CONSTRAINT ck_auth_challenge_otp_required
        CHECK (method <> 'OTP' OR (otp_hash IS NOT NULL AND channel IS NOT NULL)),
    CONSTRAINT ck_auth_challenge_fido_required
        CHECK (method <> 'FIDO' OR (fido_credential_id IS NOT NULL AND device_id IS NOT NULL)),
    CONSTRAINT ck_auth_challenge_passcode_required
        CHECK (method <> 'PASSCODE' OR device_id IS NOT NULL),
    CONSTRAINT ck_auth_challenge_stepup_binding
        CHECK (purpose <> 'TRANSFER_STEP_UP'
            OR (request_hash IS NOT NULL AND idempotency_key IS NOT NULL)),
    CONSTRAINT ck_auth_challenge_expires_after_created
        CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_challenge_attempts_within_max
        CHECK (attempt_count <= max_attempts),
    CONSTRAINT fk_auth_challenge_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_auth_challenge_device FOREIGN KEY (device_id)
        REFERENCES banking.device (device_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_auth_challenge_user_status
    ON banking.auth_challenge (user_id, status, expires_at);
CREATE INDEX ix_auth_challenge_idempotency ON banking.auth_challenge (idempotency_key);
CREATE INDEX ix_auth_challenge_expires ON banking.auth_challenge (expires_at);

-- Một challenge step-up active duy nhất cho mỗi (idempotency_key, request_hash):
-- chặn hai challenge cùng look tại tổng ngày dưới ngưỡng (04 §4.2.5 C-03; DEC-TRF-001).
-- Consume = set consumed_at (OD-ID-06); status giữ 'VERIFIED', enum không có 'CONSUMED'.
CREATE UNIQUE INDEX uq_auth_challenge_active_stepup
    ON banking.auth_challenge (idempotency_key, request_hash)
    WHERE purpose = 'TRANSFER_STEP_UP'
      AND status IN ('CREATED', 'SENT', 'VERIFIED')
      AND consumed_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4.2.6. auth_attempt
-- ---------------------------------------------------------------------------
CREATE TABLE banking.auth_attempt (
    attempt_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT      NOT NULL,
    attempt_type       VARCHAR(16) NOT NULL
        CONSTRAINT ck_auth_attempt_type
            CHECK (attempt_type IN ('LOGIN', 'PASSCODE', 'FIDO', 'OTP', 'STEP_UP')),
    outcome            VARCHAR(16) NOT NULL
        CONSTRAINT ck_auth_attempt_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'LOCKED')),
    reason_code        VARCHAR(32) NULL,
    correlation_id     VARCHAR(64) NOT NULL,
    attempted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_info_masked JSONB       NULL,
    CONSTRAINT fk_auth_attempt_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_auth_attempt_user_time ON banking.auth_attempt (user_id, attempted_at DESC);
CREATE INDEX ix_auth_attempt_user_outcome_time
    ON banking.auth_attempt (user_id, outcome, attempted_at DESC);

-- ---------------------------------------------------------------------------
-- 4.2.7. password_history
-- ---------------------------------------------------------------------------
CREATE TABLE banking.password_history (
    history_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_password_history_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_password_history_user_time
    ON banking.password_history (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4.2.8. role / permission / user_role / role_permission / user_privilege
-- ---------------------------------------------------------------------------
CREATE TABLE banking.role (
    role_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_code   VARCHAR(32)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_role_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_code UNIQUE (role_code)
);

CREATE TABLE banking.permission (
    permission_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_code VARCHAR(64)  NOT NULL,
    description     VARCHAR(255) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_permission_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_permission_code UNIQUE (permission_code)
);

CREATE TABLE banking.user_role (
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    assigned_by BIGINT      NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id)
        REFERENCES banking.role (role_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_role_assigned_by FOREIGN KEY (assigned_by)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_user_role_role ON banking.user_role (role_id);

CREATE TABLE banking.role_permission (
    role_id       BIGINT      NOT NULL,
    permission_id BIGINT      NOT NULL,
    granted_by    BIGINT      NULL,
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id)
        REFERENCES banking.role (role_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id)
        REFERENCES banking.permission (permission_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_role_permission_granted_by FOREIGN KEY (granted_by)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_role_permission_permission ON banking.role_permission (permission_id);

CREATE TABLE banking.user_privilege (
    user_id        BIGINT      NOT NULL,
    privilege_code VARCHAR(32) NOT NULL
        CONSTRAINT ck_user_privilege_code CHECK (privilege_code IN ('CHECKER')),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_user_privilege_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    granted_by     BIGINT      NULL,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_privilege PRIMARY KEY (user_id, privilege_code),
    CONSTRAINT fk_user_privilege_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_privilege_granted_by FOREIGN KEY (granted_by)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_user_privilege_code ON banking.user_privilege (privilege_code);

-- ---------------------------------------------------------------------------
-- Seed role/permission tối thiểu theo 04 Appendix A.2 (seed/migration có kiểm soát
-- theo 04 §4.2.8; CHECKER là privilege, không seed — gán theo user).
-- ---------------------------------------------------------------------------
INSERT INTO banking.role (role_code, name, description) VALUES
    ('CUSTOMER', 'Customer', 'Khách hàng cá nhân'),
    ('OPERATIONS', 'Operations', 'Nhân viên nghiệp vụ (maker)'),
    ('ADMIN', 'Admin', 'Quản trị hệ thống'),
    ('AUDITOR', 'Auditor', 'Kiểm toán, chỉ đọc');

INSERT INTO banking.permission (permission_code) VALUES
    ('ACCOUNT_READ_SELF'),
    ('TRANSACTION_READ_SELF'),
    ('TRANSFER_CREATE'),
    ('LIMIT_READ_SELF'),
    ('CUSTOMER_READ'),
    ('ACCOUNT_READ'),
    ('TRANSACTION_READ'),
    ('AUDIT_READ'),
    ('FRAUD_REVIEW'),
    ('LIMIT_MAKER'),
    ('ROLE_MANAGE'),
    ('LIMIT_MANAGE'),
    ('RETENTION_MANAGE'),
    ('FRAUD_READ'),
    ('AUDIT_EXPORT');

INSERT INTO banking.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r
JOIN banking.permission p ON p.permission_code = ANY (ARRAY[
    'ACCOUNT_READ_SELF', 'TRANSACTION_READ_SELF', 'TRANSFER_CREATE', 'LIMIT_READ_SELF'])
WHERE r.role_code = 'CUSTOMER';

INSERT INTO banking.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r
JOIN banking.permission p ON p.permission_code = ANY (ARRAY[
    'CUSTOMER_READ', 'ACCOUNT_READ', 'TRANSACTION_READ', 'AUDIT_READ', 'FRAUD_REVIEW',
    'LIMIT_MAKER'])
WHERE r.role_code = 'OPERATIONS';

INSERT INTO banking.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r
JOIN banking.permission p ON p.permission_code = ANY (ARRAY[
    'CUSTOMER_READ', 'ACCOUNT_READ', 'TRANSACTION_READ', 'AUDIT_READ', 'ROLE_MANAGE',
    'LIMIT_MANAGE', 'RETENTION_MANAGE', 'FRAUD_READ'])
WHERE r.role_code = 'ADMIN';

INSERT INTO banking.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM banking.role r
JOIN banking.permission p ON p.permission_code = ANY (ARRAY[
    'AUDIT_READ', 'AUDIT_EXPORT', 'TRANSACTION_READ', 'ACCOUNT_READ'])
WHERE r.role_code = 'AUDITOR';
