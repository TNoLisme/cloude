# Database 01. Schema, Tables & Constraints Reference

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DB-01`
> **Phiên bản:** 1.0
> **Ngày:** 07/11/2026
> **Trạng thái:** Hoàn thành
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]

---

## 1. Quy ước Vật lý PostgreSQL 16

| Khía cạnh | Quy ước bắt buộc |
|---|---|
| **Schema** | `banking` (tất cả 22 bảng nằm trong schema này) |
| **Primary Key nội bộ** | `BIGINT GENERATED ALWAYS AS IDENTITY` (trừ bảng nối, `idempotency_record` và `fraud_rule_config`) |
| **External / Public ID** | `reference_no` (`VARCHAR(32)`), `challenge_ref` (`UUID`), `device_ref` (`UUID`), `account_no_hash` (`CHAR(64)`) |
| **Kiểu tiền tệ** | `NUMERIC(19,2) NOT NULL`, kèm cột `currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND')` |
| **Kiểu thời gian** | `TIMESTAMPTZ` (lưu UTC); ngày hạn mức dùng `DATE` (tính theo `Asia/Ho_Chi_Minh`) |
| **Enum** | Dùng `VARCHAR(n)` + `CHECK (col IN (...))`; **không** dùng kiểu `CREATE TYPE ... AS ENUM` của Postgres |
| **Mã hóa & Hash** | Cột `*_enc` kiểu `BYTEA` (AES-256-GCM); cột `*_hash` kiểu `CHAR(64)` (HMAC-SHA256 / SHA-256 hex lowercase); password/OTP hash dùng `VARCHAR(255)` |
| **Foreign Key Policy** | Luôn dùng `ON DELETE RESTRICT ON UPDATE RESTRICT`; **tuyệt đối không dùng `ON DELETE CASCADE`** |
| **Xóa dữ liệu** | Không hard-delete bảng tài chính/audit/master; dùng trạng thái (`DISABLED`, `INACTIVE`, `CLOSED`, `REVOKED`) |

---

## 2. Đặc tả Chi tiết 22 Bảng theo Domain

### 2.1. Domain: Identity & Access (`module: identity`)

#### 1) `banking.app_user`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `user_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `username` | `VARCHAR(64)` | NO | — | `uq_app_user_username UNIQUE`; lưu lowercase, `CHECK (length(username) BETWEEN 1 AND 64)` |
| `password_hash` | `VARCHAR(255)` | NO | — | Argon2id / bcrypt hash |
| `user_type` | `VARCHAR(16)` | NO | — | `CHECK (user_type IN ('CUSTOMER','STAFF'))` |
| `status` | `VARCHAR(16)` | NO | `'ACTIVE'` | `CHECK (status IN ('ACTIVE','LOCKED','DISABLED'))` |
| `failed_attempt_count` | `SMALLINT` | NO | `0` | `CHECK (failed_attempt_count >= 0)` |
| `locked_until` | `TIMESTAMPTZ` | YES | — | Thời điểm hết khóa tạm thời (10 phút sau 5 lần sai) |
| `last_failed_at` | `TIMESTAMPTZ` | YES | — | Thời điểm sai gần nhất |
| `last_login_at` | `TIMESTAMPTZ` | YES | — | Thời điểm đăng nhập thành công gần nhất |
| `password_changed_at` | `TIMESTAMPTZ` | NO | `now()` | Phục vụ kiểm tra lịch sử mật khẩu |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Timestamp chuẩn |

- **Indexes:** `uq_app_user_username (username)`, `ix_app_user_status (status)`, `ix_app_user_locked_until (locked_until) WHERE locked_until IS NOT NULL`.

#### 2) `banking.session`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `session_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `session_token_hash` | `CHAR(64)` | NO | — | `uq_session_token_hash UNIQUE` (SHA-256 hex của opaque token) |
| `user_id` | `BIGINT` | NO | — | `FK -> app_user(user_id)` |
| `device_id` | `BIGINT` | YES | — | `FK -> device(device_id)` |
| `status` | `VARCHAR(16)` | NO | `'ACTIVE'` | `CHECK (status IN ('ACTIVE','EXPIRED','LOGGED_OUT'))` |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `last_activity_at` | `TIMESTAMPTZ` | NO | `now()` | Cập nhật khi có request hợp lệ |
| `idle_expires_at` | `TIMESTAMPTZ` | NO | — | `CHECK (idle_expires_at >= last_activity_at)` |
| `absolute_expires_at` | `TIMESTAMPTZ` | NO | — | `CHECK (absolute_expires_at > created_at)` |
| `logged_out_at` | `TIMESTAMPTZ` | YES | — | — |

- **Indexes:** `uq_session_token_hash (session_token_hash)`, `ix_session_user_status (user_id, status)`, `ix_session_idle_expires (idle_expires_at)`, `ix_session_absolute_expires (absolute_expires_at)`.

#### 3) `banking.device`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `device_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `device_ref` | `UUID` | NO | — | `uq_device_ref UNIQUE` (External ID) |
| `user_id` | `BIGINT` | NO | — | `FK -> app_user(user_id)` |
| `device_name` | `VARCHAR(100)` | NO | — | `CHECK (length(device_name) BETWEEN 1 AND 100)` |
| `device_fingerprint_hash` | `CHAR(64)` | NO | — | HMAC-SHA256 fingerprint |
| `fido_credential_id` | `VARCHAR(255)` | YES | — | `uq_device_fido UNIQUE` |
| `passcode_hash` | `VARCHAR(255)` | YES | — | Argon2id hash của Passcode |
| `trusted` | `BOOLEAN` | NO | `FALSE` | — |
| `status` | `VARCHAR(16)` | NO | `'ACTIVE'` | `CHECK (status IN ('ACTIVE','REVOKED'))` |
| `trusted_at` / `last_seen_at` | `TIMESTAMPTZ` | YES | — | — |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |

- **Indexes:** `uq_device_ref (device_ref)`, `uq_device_fido (fido_credential_id)`, `uq_device_user_fingerprint (user_id, device_fingerprint_hash)`, `ix_device_user_status (user_id, status)`.

#### 4) `banking.auth_challenge`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `challenge_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `challenge_ref` | `UUID` | NO | — | `uq_auth_challenge_ref UNIQUE` (External ID trả cho client) |
| `user_id` | `BIGINT` | NO | — | `FK -> app_user(user_id)` |
| `device_id` | `BIGINT` | YES | — | `FK -> device(device_id)` |
| `purpose` | `VARCHAR(32)` | NO | — | `CHECK (purpose IN ('LOGIN_NEW_DEVICE','TRANSFER_STEP_UP','SENSITIVE_ACTION'))` |
| `method` | `VARCHAR(16)` | NO | — | `CHECK (method IN ('OTP','FIDO','PASSCODE'))` |
| `channel` | `VARCHAR(16)` | YES | — | `CHECK (channel IN ('SMS','EMAIL','IN_APP'))` |
| `otp_hash` | `VARCHAR(255)` | YES | — | Hash của mã OTP |
| `fido_credential_id` | `VARCHAR(255)` | YES | — | — |
| `request_hash` | `CHAR(64)` | YES | — | SHA-256 canonical payload của request gắn với step-up |
| `idempotency_key` | `VARCHAR(255)` | YES | — | Khóa idempotency gắn với step-up transfer |
| `status` | `VARCHAR(16)` | NO | `'CREATED'` | `CHECK (status IN ('CREATED','SENT','VERIFIED','LOCKED','EXPIRED','CANCELLED'))` |
| `attempt_count` | `SMALLINT` | NO | `0` | `CHECK (attempt_count >= 0 AND attempt_count <= max_attempts)` |
| `max_attempts` | `SMALLINT` | NO | `5` | `CHECK (max_attempts > 0)` |
| `expires_at` | `TIMESTAMPTZ` | NO | — | `CHECK (expires_at > created_at)` |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `sent_at` / `verified_at` / `consumed_at` | `TIMESTAMPTZ` | YES | — | `consumed_at` được gán trong `T1` khi tạo transfer |
| `correlation_id` | `VARCHAR(64)` | NO | — | Trace ID |

- **Table Checks:**
  - `(method <> 'OTP') OR (otp_hash IS NOT NULL AND channel IS NOT NULL)`
  - `(method <> 'FIDO') OR (fido_credential_id IS NOT NULL AND device_id IS NOT NULL)`
  - `(method <> 'PASSCODE') OR (device_id IS NOT NULL)`
  - `(purpose <> 'TRANSFER_STEP_UP') OR (request_hash IS NOT NULL AND idempotency_key IS NOT NULL)`
- **Partial Unique Index:**
  - `uq_auth_challenge_active_stepup UNIQUE (idempotency_key, request_hash) WHERE purpose = 'TRANSFER_STEP_UP' AND status IN ('CREATED','SENT','VERIFIED') AND consumed_at IS NULL`
- **Other Indexes:** `uq_auth_challenge_ref (challenge_ref)`, `ix_auth_challenge_user_status (user_id, status, expires_at)`, `ix_auth_challenge_idempotency (idempotency_key)`, `ix_auth_challenge_expires (expires_at)`.

#### 5) `banking.auth_attempt` & `banking.password_history`
- **`auth_attempt`**: `attempt_id (PK)`, `user_id (FK)`, `attempt_type VARCHAR(16) IN ('LOGIN','PASSCODE','FIDO','OTP','STEP_UP')`, `outcome VARCHAR(16) IN ('SUCCESS','FAILURE','LOCKED')`, `reason_code VARCHAR(32)`, `correlation_id VARCHAR(64) NOT NULL`, `attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `client_info_masked JSONB`.
  - Indexes: `ix_auth_attempt_user_time (user_id, attempted_at DESC)`, `ix_auth_attempt_user_outcome_time (user_id, outcome, attempted_at DESC)`.
- **`password_history`**: `history_id (PK)`, `user_id (FK)`, `password_hash VARCHAR(255) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - Index: `ix_password_history_user_time (user_id, created_at DESC)`.

#### 6) Các bảng RBAC (`role`, `permission`, `user_role`, `role_permission`, `user_privilege`)
- **`role`**: `role_id (PK)`, `role_code VARCHAR(32) UNIQUE NOT NULL`, `name VARCHAR(100) NOT NULL`, `description VARCHAR(255)`, `status VARCHAR(16) DEFAULT 'ACTIVE' CHECK (IN ('ACTIVE','DISABLED'))`, `created_at`, `updated_at`.
- **`permission`**: `permission_id (PK)`, `permission_code VARCHAR(64) UNIQUE NOT NULL`, `description VARCHAR(255)`, `status VARCHAR(16) DEFAULT 'ACTIVE' CHECK (IN ('ACTIVE','DISABLED'))`, `created_at`, `updated_at`.
- **`user_role`**: `PK (user_id, role_id)`, `assigned_by BIGINT FK -> app_user`, `assigned_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Index: `ix_user_role_role (role_id)`.
- **`role_permission`**: `PK (role_id, permission_id)`, `granted_by BIGINT FK -> app_user`, `granted_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Index: `ix_role_permission_permission (permission_id)`.
- **`user_privilege`**: `PK (user_id, privilege_code)`, `privilege_code VARCHAR(32) CHECK (IN ('CHECKER'))`, `status VARCHAR(16) DEFAULT 'ACTIVE' CHECK (IN ('ACTIVE','REVOKED'))`, `granted_by BIGINT FK -> app_user`, `granted_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Index: `ix_user_privilege_code (privilege_code)`.

---

### 2.2. Domain: Customer & Account (`module: customer`, `account`)

#### 7) `banking.customer`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `customer_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `user_id` | `BIGINT` | NO | — | `uq_customer_user_id UNIQUE`, `FK -> app_user(user_id)` |
| `full_name_enc` | `BYTEA` | NO | — | AES-256-GCM ciphertext |
| `phone_enc` | `BYTEA` | YES | — | AES-256-GCM ciphertext |
| `email_enc` | `BYTEA` | YES | — | AES-256-GCM ciphertext |
| `status` | `VARCHAR(16)` | NO | `'ACTIVE'` | `CHECK (status IN ('ACTIVE','INACTIVE'))` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |

#### 8) `banking.account`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `account_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `customer_id` | `BIGINT` | NO | — | `FK -> customer(customer_id)` |
| `account_no_enc` | `BYTEA` | NO | — | AES-256-GCM số tài khoản đầy đủ |
| `account_no_hash` | `CHAR(64)` | NO | — | `uq_account_no_hash UNIQUE` (HMAC-SHA256 blind index) |
| `account_no_last4` | `CHAR(4)` | NO | — | `CHECK (length(account_no_last4) = 4)` |
| `currency` | `CHAR(3)` | NO | `'VND'` | `CHECK (currency = 'VND')` |
| `status` | `VARCHAR(16)` | NO | `'ACTIVE'` | `CHECK (status IN ('ACTIVE','LOCKED','CLOSED'))` |
| `available_balance` | `NUMERIC(19,2)` | NO | `0.00` | `CHECK (available_balance >= 0)` |
| `ledger_balance` | `NUMERIC(19,2)` | NO | `0.00` | `CHECK (ledger_balance >= 0 AND available_balance <= ledger_balance)` |
| `closed_at` | `TIMESTAMPTZ` | YES | — | — |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |

- **Indexes:** `uq_account_no_hash (account_no_hash)`, `ix_account_customer (customer_id)`, `ix_account_status (status)`.

---

### 2.3. Domain: Transfer, Ledger & Idempotency (`module: transfer`, `ledger`)

#### 9) `banking.transfer`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `transfer_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `reference_no` | `VARCHAR(32)` | NO | — | `uq_transfer_reference UNIQUE`, bất biến |
| `customer_id` | `BIGINT` | NO | — | `FK -> customer(customer_id)` |
| `initiated_by_user_id` | `BIGINT` | NO | — | `FK -> app_user(user_id)` |
| `source_account_id` | `BIGINT` | NO | — | `FK -> account(account_id)` |
| `destination_account_id` | `BIGINT` | NO | — | `FK -> account(account_id)`, `CHECK (source_account_id <> destination_account_id)` |
| `amount` | `NUMERIC(19,2)` | NO | — | `CHECK (amount > 0)` |
| `currency` | `CHAR(3)` | NO | `'VND'` | `CHECK (currency = 'VND')` |
| `description` | `VARCHAR(255)` | YES | — | Không lưu PII |
| `status` | `VARCHAR(16)` | NO | `'PENDING'` | `CHECK (status IN ('PENDING','COMPLETED','FAILED'))` |
| `failure_code` | `VARCHAR(32)` | YES | — | Mã lỗi khi `status = 'FAILED'` |
| `failure_message` | `VARCHAR(255)` | YES | — | Thông điệp an toàn |
| `correlation_id` | `VARCHAR(64)` | NO | — | Trace ID |
| `step_up_challenge_id` | `BIGINT` | YES | — | `FK -> auth_challenge(challenge_id)` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `completed_at` / `failed_at` | `TIMESTAMPTZ` | YES | — | — |

- **Table Checks:**
  - `(status = 'PENDING' AND completed_at IS NULL AND failed_at IS NULL) OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND failed_at IS NULL) OR (status = 'FAILED' AND failed_at IS NOT NULL AND completed_at IS NULL AND failure_code IS NOT NULL)`
- **Indexes:**
  - `uq_transfer_reference UNIQUE (reference_no)`
  - `uq_transfer_step_up_challenge UNIQUE (step_up_challenge_id) WHERE step_up_challenge_id IS NOT NULL`
  - `ix_transfer_customer_time (customer_id, created_at DESC)`
  - `ix_transfer_source_time (source_account_id, created_at DESC)`
  - `ix_transfer_destination_time (destination_account_id, created_at DESC)`
  - `ix_transfer_status_time (status, created_at)`
  - `ix_transfer_correlation (correlation_id)`

#### 10) `banking.ledger_entry` (Append-only)
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `entry_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `transfer_id` | `BIGINT` | NO | — | `FK -> transfer(transfer_id)` |
| `account_id` | `BIGINT` | NO | — | `FK -> account(account_id)` |
| `entry_seq` | `SMALLINT` | NO | — | `CHECK (entry_seq IN (1, 2))` (`1 = DEBIT`, `2 = CREDIT`) |
| `entry_type` | `VARCHAR(8)` | NO | — | `CHECK (entry_type IN ('DEBIT','CREDIT'))` |
| `amount` | `NUMERIC(19,2)` | NO | — | `CHECK (amount > 0)` |
| `currency` | `CHAR(3)` | NO | `'VND'` | `CHECK (currency = 'VND')` |
| `balance_after` | `NUMERIC(19,2)` | NO | — | `CHECK (balance_after >= 0)` |
| `correlation_id` | `VARCHAR(64)` | NO | — | Trace ID |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |

- **Indexes:** `uq_ledger_transfer_seq UNIQUE (transfer_id, entry_seq)`, `ix_ledger_account_time (account_id, created_at DESC)`, `ix_ledger_transfer (transfer_id)`, `ix_ledger_correlation (correlation_id)`.

#### 11) `banking.idempotency_record`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `idempotency_key` | `VARCHAR(255)` | NO | — | `PK`, `CHECK (length(idempotency_key) BETWEEN 1 AND 255)` |
| `request_hash` | `CHAR(64)` | NO | — | SHA-256 canonical payload |
| `transfer_id` | `BIGINT` | NO | — | `uq_idempotency_transfer UNIQUE`, `FK -> transfer(transfer_id)` |
| `status` | `VARCHAR(16)` | NO | `'RECEIVED'` | `CHECK (status IN ('RECEIVED','IN_PROGRESS','COMPLETED','FAILED'))` |
| `response_status` | `SMALLINT` | YES | — | `CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)` |
| `response_body` | `JSONB` | YES | — | Snapshot response đã mask PII |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `completed_at` | `TIMESTAMPTZ` | YES | — | — |
| `expires_at` | `TIMESTAMPTZ` | NO | — | `CHECK (expires_at > created_at)` |

- **Table Check:** `(status NOT IN ('COMPLETED','FAILED')) OR (completed_at IS NOT NULL AND response_status IS NOT NULL)`
- **Indexes:** `uq_idempotency_transfer UNIQUE (transfer_id)`, `ix_idempotency_expires (expires_at)`, `ix_idempotency_status (status)`.

---

### 2.4. Domain: Limits & Approval (`module: limit`, `admin`)

#### 12) `banking.limit_config`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `limit_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `customer_id` | `BIGINT` | NO | — | `FK -> customer(customer_id)` |
| `account_id` | `BIGINT` | YES | — | `FK -> account(account_id)` (`NULL` = Customer-level) |
| `per_transaction_limit` | `NUMERIC(19,2)` | NO | — | `CHECK (per_transaction_limit > 0)` |
| `daily_limit` | `NUMERIC(19,2)` | NO | — | `CHECK (daily_limit > 0 AND daily_limit >= per_transaction_limit)` |
| `currency` | `CHAR(3)` | NO | `'VND'` | `CHECK (currency = 'VND')` |
| `effective_from` | `TIMESTAMPTZ` | NO | — | — |
| `effective_to` | `TIMESTAMPTZ` | YES | — | `CHECK (effective_to IS NULL OR effective_to > effective_from)` |
| `status` | `VARCHAR(16)` | NO | `'PENDING_APPROVAL'` | `CHECK (status IN ('PENDING_APPROVAL','ACTIVE','SUPERSEDED','REJECTED'))` |
| `version` | `INT` | NO | `1` | `CHECK (version > 0)` |
| `created_by` | `BIGINT` | NO | — | `FK -> app_user(user_id)` (Maker) |
| `approved_by` | `BIGINT` | YES | — | `FK -> app_user(user_id)` (Checker), `CHECK (approved_by IS NULL OR approved_by <> created_by)` |
| `approval_id` | `BIGINT` | YES | — | `FK -> approval_request(approval_id)` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `approved_at` | `TIMESTAMPTZ` | YES | — | — |

- **Partial Unique Indexes:**
  - `uq_limit_active_customer UNIQUE (customer_id) WHERE account_id IS NULL AND status = 'ACTIVE'`
  - `uq_limit_active_account UNIQUE (account_id) WHERE account_id IS NOT NULL AND status = 'ACTIVE'`

#### 13) `banking.limit_usage`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `usage_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `customer_id` | `BIGINT` | NO | — | `FK -> customer(customer_id)` |
| `account_id` | `BIGINT` | YES | — | `FK -> account(account_id)` |
| `usage_date` | `DATE` | NO | — | Ngày nghiệp vụ theo `Asia/Ho_Chi_Minh` |
| `currency` | `CHAR(3)` | NO | `'VND'` | `CHECK (currency = 'VND')` |
| `used_amount` | `NUMERIC(19,2)` | NO | `0.00` | `CHECK (used_amount >= 0)` |
| `transfer_count` | `INT` | NO | `0` | `CHECK (transfer_count >= 0)` |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | — |

- **Partial Unique Indexes (Bắt buộc cho `ON CONFLICT`):**
  - `uq_limit_usage_customer UNIQUE (customer_id, usage_date) WHERE account_id IS NULL`
  - `uq_limit_usage_account UNIQUE (account_id, usage_date) WHERE account_id IS NOT NULL`

#### 14) `banking.approval_request`
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `approval_id` | `BIGINT` | NO | `IDENTITY` | `PK` |
| `request_type` | `VARCHAR(32)` | NO | — | `CHECK (request_type IN ('LIMIT_CHANGE','ROLE_CHANGE','RETENTION_HOLD','RETENTION_POLICY_CHANGE'))` |
| `target_type` | `VARCHAR(32)` | NO | — | `CHECK (target_type IN ('CUSTOMER','ACCOUNT','APP_USER','DATA_SCOPE','RETENTION_POLICY'))` |
| `target_id` | `VARCHAR(64)` | NO | — | — |
| `payload` | `JSONB` | NO | — | Chi tiết thay đổi |
| `status` | `VARCHAR(16)` | NO | `'PENDING'` | `CHECK (status IN ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED'))` |
| `maker_id` | `BIGINT` | NO | — | `FK -> app_user(user_id)` |
| `checker_id` | `BIGINT` | YES | — | `FK -> app_user(user_id)`, `CHECK (checker_id IS NULL OR checker_id <> maker_id)` |
| `decision_note` | `VARCHAR(500)` | YES | — | — |
| `correlation_id` | `VARCHAR(64)` | NO | — | — |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `decided_at` / `applied_at` | `TIMESTAMPTZ` | YES | — | — |

---

### 2.5. Domain: Audit & Compliance (`module: audit`)

#### 15) `banking.audit_log` (Append-only + Hash Chain)
| Cột | Kiểu | Null | Default | Ràng buộc / Ghi chú |
|---|---|---|---|---|
| `audit_id` | `BIGINT` | NO | `IDENTITY` | `PK`, dùng làm thứ tự chuỗi hash |
| `occurred_at` | `TIMESTAMPTZ` | NO | — | Thời điểm sự kiện nghiệp vụ |
| `recorded_at` | `TIMESTAMPTZ` | NO | `now()` | Thời điểm ghi DB |
| `actor_type` | `VARCHAR(8)` | NO | — | `CHECK (actor_type IN ('USER','SYSTEM'))` |
| `actor_user_id` | `BIGINT` | YES | — | `FK -> app_user(user_id)`, `CHECK ((actor_type = 'USER' AND actor_user_id IS NOT NULL) OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL))` |
| `actor_role` | `VARCHAR(32)` | NO | — | Snapshot role tại thời điểm thực hiện |
| `action` | `VARCHAR(64)` | NO | — | Thuộc danh mục `AuditAction` chuẩn |
| `entity_type` | `VARCHAR(32)` | NO | — | Ví dụ: `TRANSFER`, `ACCOUNT`, `AUTH_SESSION`... |
| `entity_id` | `VARCHAR(64)` | NO | — | ID của đối tượng |
| `before_state` / `after_state` | `JSONB` | YES | — | Snapshot đã mask PII |
| `amount` | `NUMERIC(19,2)` | YES | — | `CHECK (amount IS NULL OR amount >= 0)` |
| `currency` | `CHAR(3)` | YES | — | `CHECK (currency IS NULL OR currency = 'VND')` |
| `correlation_id` | `VARCHAR(64)` | NO | — | Trace ID |
| `request_id` | `VARCHAR(64)` | YES | — | — |
| `outcome` | `VARCHAR(16)` | NO | — | `CHECK (outcome IN ('SUCCESS','FAILURE','REJECTED'))` |
| `client_info_masked` | `JSONB` | YES | — | IP/User-Agent đã mask |
| `prev_hash` | `CHAR(64)` | YES | — | `NULL` cho bản ghi đầu tiên; bằng `record_hash` của bản ghi ngay trước |
| `record_hash` | `CHAR(64)` | NO | — | `uq_audit_record_hash` / `ix_audit_record_hash`; SHA-256 hex lowercase |
| `hash_version` | `SMALLINT` | NO | `1` | `CHECK (hash_version >= 1)` |

- **Indexes:** `ix_audit_occurred (occurred_at DESC, audit_id DESC)`, `ix_audit_correlation (correlation_id, audit_id)`, `ix_audit_entity (entity_type, entity_id, occurred_at DESC)`, `ix_audit_actor (actor_user_id, occurred_at DESC)`, `ix_audit_action (action, occurred_at DESC)`, `ix_audit_record_hash (record_hash)`.

#### 16) `banking.legal_hold` & 17) `banking.retention_policy`
- **`legal_hold`**: `hold_id (PK)`, `entity_type VARCHAR(32) IN ('CUSTOMER','ACCOUNT','TRANSFER','AUDIT_LOG','DATA_SCOPE')`, `entity_id VARCHAR(64) NOT NULL`, `reason VARCHAR(500) NOT NULL`, `status VARCHAR(16) DEFAULT 'ACTIVE' IN ('ACTIVE','RELEASED')`, `placed_by BIGINT FK`, `placed_at TIMESTAMPTZ DEFAULT now()`, `released_by BIGINT FK`, `released_at TIMESTAMPTZ`, `approval_id BIGINT FK`, `correlation_id VARCHAR(64) NOT NULL`.
- **`retention_policy`**: `policy_id (PK)`, `data_class VARCHAR(32) UNIQUE NOT NULL`, `retention_days INT NOT NULL > 0`, `archive_after_days INT`, `purge_after_days INT`, `legal_hold_supported BOOLEAN DEFAULT TRUE`, `enabled BOOLEAN DEFAULT TRUE`, `effective_from TIMESTAMPTZ NOT NULL`, `created_by BIGINT FK`, `approved_by BIGINT FK`, `created_at`, `updated_at`.

---

### 2.6. Domain: Fraud & Notification (`module: fraud`, `notification`)

#### 18) `banking.fraud_rule_config`
- **PK:** `(rule_code VARCHAR(32), version INT)`
- **Cột:** `rule_name VARCHAR(100) NOT NULL`, `rule_type VARCHAR(32) IN ('LARGE_VALUE','HIGH_FREQUENCY','UNUSUAL_PATTERN')`, `threshold_amount NUMERIC(19,2)`, `threshold_count INT`, `window_seconds INT`, `severity VARCHAR(16) IN ('LOW','MEDIUM','HIGH','CRITICAL')`, `auto_block BOOLEAN NOT NULL DEFAULT FALSE`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`, `effective_from TIMESTAMPTZ NOT NULL`, `effective_to TIMESTAMPTZ`, `created_by BIGINT FK`, `approved_by BIGINT FK`, `created_at`, `updated_at`.
- **Partial Unique Index:** `uq_fraud_rule_active UNIQUE (rule_code) WHERE enabled = TRUE AND effective_to IS NULL`.

#### 19) `banking.fraud_flag`
- **PK:** `flag_id BIGINT IDENTITY`
- **Cột:** `transfer_id BIGINT NOT NULL FK -> transfer`, `rule_code VARCHAR(32) NOT NULL`, `rule_version INT NOT NULL`, `severity VARCHAR(16) NOT NULL`, `status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (IN ('OPEN','UNDER_REVIEW','ALLOWED','BLOCKED','CLOSED'))`, `matched_value JSONB`, `reviewed_by BIGINT FK -> app_user`, `reviewed_at TIMESTAMPTZ`, `decision VARCHAR(16) CHECK (IN ('ALLOW','BLOCK','ESCALATE','CLOSE'))`, `notes VARCHAR(500)`, `correlation_id VARCHAR(64) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- **Constraints:** `FK (rule_code, rule_version) -> fraud_rule_config`, `uq_fraud_flag_transfer_rule UNIQUE (transfer_id, rule_code, rule_version)`.

#### 20) `banking.notification` (Transactional Outbox)
- **PK:** `notification_id BIGINT IDENTITY`
- **Cột:** `customer_id BIGINT NOT NULL FK -> customer`, `transfer_id BIGINT FK -> transfer`, `auth_challenge_id BIGINT FK -> auth_challenge`, `notification_type VARCHAR(32) NOT NULL CHECK (IN ('TRANSFER_RESULT','OTP_DELIVERY','SECURITY_ALERT'))`, `channel VARCHAR(16) NOT NULL CHECK (IN ('IN_APP','EMAIL','SMS'))`, `template_code VARCHAR(32) NOT NULL`, `payload_masked JSONB NOT NULL`, `status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (IN ('PENDING','SENT','FAILED','RETRYING','CANCELLED'))`, `retry_count INT NOT NULL DEFAULT 0`, `next_retry_at TIMESTAMPTZ`, `sent_at TIMESTAMPTZ`, `failure_code VARCHAR(32)`, `correlation_id VARCHAR(64) NOT NULL`, `created_at`, `updated_at`.
- **Indexes:** `ix_notification_status_retry (status, next_retry_at)`, `ix_notification_customer_time (customer_id, created_at DESC)`, `ix_notification_transfer (transfer_id)`.

---

## 3. Triggers Bảo vệ Bất biến Cốt lõi (Database Triggers)

Hai trigger sau bắt buộc phải được tạo trong Flyway migration để bảo vệ tính bất biến ở tầng Database:

### 3.1. Chặn UPDATE / DELETE trên `banking.audit_log` và `banking.ledger_entry`
```sql
CREATE OR REPLACE FUNCTION banking.prevent_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Table %.% is append-only; % is not allowed',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_audit_log_immutable
BEFORE UPDATE OR DELETE ON banking.audit_log
FOR EACH ROW EXECUTE FUNCTION banking.prevent_append_only_mutation();

CREATE TRIGGER trg_ledger_entry_immutable
BEFORE UPDATE OR DELETE ON banking.ledger_entry
FOR EACH ROW EXECUTE FUNCTION banking.prevent_append_only_mutation();
```

### 3.2. Chặn sửa đổi `banking.transfer` sau khi đã vào trạng thái Terminal (`COMPLETED` / `FAILED`)
```sql
CREATE OR REPLACE FUNCTION banking.prevent_terminal_transfer_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'Transfer % is already in terminal state % and cannot be modified',
            OLD.transfer_id, OLD.status
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transfer_terminal_immutable
BEFORE UPDATE OR DELETE ON banking.transfer
FOR EACH ROW EXECUTE FUNCTION banking.prevent_terminal_transfer_mutation();
```
