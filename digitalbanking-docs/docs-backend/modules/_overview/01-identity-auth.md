# Module 01: `identity` / `auth` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-01`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.identity` (`identity` / `auth`)
> **Owner chính:** **Đạt** (Phối hợp: Thắng — FE, Thịnh — `audit` & `notification`, Vũ — `transfer` step-up)
> **Mốc tập trung:** **Tuần 3 (W3)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-001..007, FR-026, FR-040), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.1), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3, §6, §8.1), [[digitalbanking-docs/docs-common/design/04. security.md]] (§4, §5), [[DECISIONS.md]] (`DEC-ID-001..013`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `identity` (`auth`) chịu trách nhiệm quản lý toàn bộ định danh người dùng (`CUSTOMER` và `STAFF`), xác thực đăng nhập (mật khẩu + MFA, FIDO2/WebAuthn, passcode), quản lý thiết bị (`device`), duy trì phiên đăng nhập phía server (`Opaque Server-Side Session`), cấp phát và xác minh mã xác thực tăng cường (`OTP Step-up Challenge`), quản lý lịch sử mật khẩu (`password_history`) và đánh giá phân quyền RBAC.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `identity` là chủ sở hữu ghi duy nhất của 11 bảng định danh và bảo mật phiên (`04. Database design & analysis.md` §4.2, migrations `V2`–`V4`):

| Bảng | Mục đích chính | Ràng buộc & Quy tắc cốt lõi |
|---|---|---|
| `banking.app_user` | Tài khoản đăng nhập của `CUSTOMER` và `STAFF` | `user_id BIGINT IDENTITY PK`, `username` chữ thường duy nhất, `password_hash` (`Argon2id`), `failed_attempt_count`, `locked_until`, `status IN ('ACTIVE','LOCKED','DISABLED')`, `role_assignment_version BIGINT NOT NULL DEFAULT 0` (`V4`, `DEC-ID-011`). |
| `banking.role`, `banking.permission`, `banking.user_role`, `banking.role_permission`, `banking.user_privilege` | Mô hình phân quyền RBAC | Seed cố định tại `V2` (`DEC-ID-001`): **4 role chuẩn** (`CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR`) và **15 permission** (trong đó `AUDITOR` có đủ 6 quyền đọc/xuất gồm cả `CUSTOMER_READ` & `FRAUD_READ`). Đặc quyền duyệt 4 mắt **`CHECKER`** lưu tại `user_privilege` (`privilege_code = 'CHECKER'`), không phải role. |
| `banking.device` | Quản lý thiết bị đăng nhập của người dùng | `device_id BIGINT IDENTITY PK`, `device_ref UUID UNIQUE`, `device_fingerprint_hash CHAR(64)` (`HMAC-SHA256`), `trusted BOOLEAN`, `passcode_hash`, `fido_credential_id`, cùng các cột xác thực FIDO2 (`V3`, `DEC-ID-010`): `fido_public_key_cose BYTEA`, `fido_user_handle BYTEA`, `fido_sign_count BIGINT`, `status IN ('ACTIVE','REVOKED')`. |
| `banking.session` | Phiên đăng nhập Opaque Server-side | `session_id BIGINT IDENTITY PK`, `session_token_hash CHAR(64) UNIQUE` (`SHA-256`), `csrf_token_hash CHAR(64) NOT NULL` (`DEC-ID-002`), `idle_expires_at` (`15 phút`), `absolute_expires_at` (`8 giờ`), `status IN ('ACTIVE','EXPIRED','LOGGED_OUT')`, `logged_out_at`. |
| `banking.auth_challenge` | Quản lý OTP / FIDO / Step-up challenge | `challenge_id BIGINT IDENTITY PK`, `challenge_ref UUID UNIQUE`, `purpose IN ('LOGIN_NEW_DEVICE','TRANSFER_STEP_UP','SENSITIVE_ACTION')`, `method IN ('OTP','FIDO','PASSCODE')`, `status IN ('CREATED','SENT','VERIFIED','EXPIRED','LOCKED')`, `otp_hash`, `fido_nonce BYTEA` (`V3`), `request_hash CHAR(64)`, `idempotency_key`, `attempt_count`, `max_attempts` (`5`), `resend_count` (tối đa `3`), `verified_at`, **`consumed_at TIMESTAMPTZ`** (tiêu thụ một lần khi `status = 'VERIFIED' AND consumed_at IS NULL`, `DEC-ID-005/007`). Partial unique `uq_auth_challenge_active_stepup(idempotency_key, request_hash)`. |
| `banking.auth_attempt` | Nhật ký lần thử xác thực | Lưu `attempt_type IN ('LOGIN','PASSCODE','FIDO','OTP','STEP_UP')`, `outcome IN ('SUCCESS','FAILURE','LOCKED')`, `client_info_masked JSONB`, `correlation_id`. |
| `banking.password_history` | Lịch sử mật khẩu đã dùng | Ngăn tái sử dụng mật khẩu hiện tại hoặc **3 mật khẩu gần nhất** (`BRL-006`, `DEC-ID-009`). |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.1)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-AUTH-01` | `POST /api/v1/auth/login` | Public | Kiểm tra mật khẩu bằng `Argon2id` (chống dò tài khoản bằng dummy hash); sai 5 lần liên tiếp khóa tạm thời **10 phút** (`423 AUTH_ACCOUNT_LOCKED`). Theo `DEC-ID-008`, mật khẩu hợp lệ luôn tạo challenge `LOGIN_NEW_DEVICE`, gửi OTP sau commit và trả `202 Accepted` (`ChallengeRequiredResponse`). |
| `API-AUTH-02` | `POST /api/v1/auth/passcode` | Public | Xác thực nhanh bằng passcode trên thiết bị tin cậy. Trong phạm vi MVP chưa có quy trình cấp khóa thiết bị bất đối xứng cho passcode nên kiểm tra fail-closed trả `403 AUTH_DEVICE_NOT_TRUSTED` (`DEC-ID-009`). |
| `API-AUTH-03` | `POST /api/v1/auth/fido/verify` | Public | Xác thực FIDO2/WebAuthn hai bước trên cùng endpoint (`DEC-ID-010`): bước Begin trả `202 Accepted` kèm `fidoRequestOptions`; bước Finish kiểm tra chữ ký, `rpId`, `origin`, `userVerification = required`, `signCount` và cấp phiên `200 OK` (`AuthResponse` + cookie `DB_SESSION`). |
| `API-AUTH-04` | `POST /api/v1/auth/otp/verify` | Public | Xác minh mã OTP cho luồng đăng nhập (`LOGIN_NEW_DEVICE`). Nếu đúng (`status = 'SENT' -> 'VERIFIED'` và đánh dấu `consumed_at`), khởi tạo phiên nguyên tử và trả `200 OK` (`AuthResponse` + cookie `DB_SESSION` + `csrfToken`). Sai quá 5 lần chuyển sang `LOCKED` (`423 AUTH_CHALLENGE_LOCKED`). |
| `API-AUTH-05` | `POST /api/v1/auth/step-up/verify` | Authenticated | Xác minh challenge xác thực tăng cường (`purpose` là `TRANSFER_STEP_UP` hoặc `SENSITIVE_ACTION`) cho đúng người dùng đang đăng nhập; trả `200 OK` (`StepUpVerifyResponse` với `status = 'VERIFIED'`). |
| `API-AUTH-06` | `POST /api/v1/auth/logout` | Authenticated | Thu hồi phiên hiện tại (`status = 'LOGGED_OUT'`, `logged_out_at = clock_timestamp()`), xóa cookie `DB_SESSION` (`Max-Age=0`) và trả `204 No Content`. |
| `API-AUTH-07` | `GET /api/v1/auth/session` | Authenticated | Kiểm tra phiên hợp lệ, gia hạn `idle_expires_at`, xoay vòng (`rotate`) `csrfToken` nguyên tử (`DEC-ID-004`) và trả về `AuthResponse` (`user`, `roles`, `permissions`, `session`). |
| `API-AUTH-08` | `POST /api/v1/auth/challenges/{challengeId}/resend` | Public (`LOGIN_NEW_DEVICE`) / Authenticated | Gửi lại mã OTP (thời gian chờ tối thiểu `60 giây`, tối đa `3 lần` gửi lại, làm mới mã OTP và TTL 5 phút nhưng giữ nguyên `attempt_count` — `DEC-ID-012`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §5)

### 4.1. Ports cung cấp cho các module khác gọi
- **`common.config.SessionAuthenticator` SPI** *(Được `IdentitySessionAuthenticator` hiện thực)*:
  - `authenticate(String opaqueToken) -> Optional<Session>`: Kiểm tra `session_token_hash`, trạng thái phiên/người dùng `ACTIVE`, hạn `idle`/`absolute`, gia hạn `idle_expires_at` và nạp danh sách quyền (`authorities`) trực tiếp từ DB trên mỗi request.
- **`identity.api.StepUpChallengePort`** *(Được gọi bởi `transfer` ở pha `T0`/`T1` và `admin` khi duyệt Maker-Checker)*:
  - `requestChallenge(...)` (`OWN/T0`): Tạo challenge `CREATED` + ghi audit `AUTH_CHALLENGE_CREATED` (`C2`), commit rồi gửi OTP qua `OtpGateway` ngoài transaction và chuyển sang `SENT` (`DEC-ID-006`).
  - `verifyChallenge(...)` (`OWN/T0`): Kiểm tra mã OTP cho challenge ở trạng thái `SENT`, cập nhật nguyên tử `status = 'VERIFIED'`, `verified_at = clock_timestamp()`.
  - `consumeVerified(...)` (`JOIN/T1` hoặc approval TX, `@Transactional(MANDATORY)`): Kiểm tra khớp `challengeRef`, `userId`, `purpose`, `request_hash`, `idempotency_key` với điều kiện `status = 'VERIFIED' AND consumed_at IS NULL AND expires_at > clock_timestamp()`, đặt `consumed_at = clock_timestamp()` và trả về `ConsumedChallenge(challengeId BIGINT)` (`DEC-ID-007`, `DEC-TRF-017`).
- **`identity.api.IdentityRoleCommand`** *(Được gọi bởi `admin` khi Maker tạo yêu cầu và khi Checker phê duyệt `ROLE_CHANGE` — `DEC-ID-011`)*:
  - `getRoleAssignmentVersion(targetUserId)`: Đọc phiên bản phân quyền hiện tại của nhân viên đích.
  - `applyApprovedRoleChange(ApprovedRoleChange)` (`JOIN/MANDATORY`): Kiểm tra `maker != checker`, đối chiếu `expectedVersion`, cập nhật `user_role` / `user_privilege`, tăng `role_assignment_version + 1` và **thu hồi ngay lập tức toàn bộ phiên `ACTIVE`** của nhân viên đích (`status = 'LOGGED_OUT'`, `logged_out_at = clock_timestamp()`).

### 4.2. Ports của module khác mà `identity` cần gọi
- **`AuditModuleApi`** (của module `audit`, qua `AuthAuditPort`): Ghi nhận các sự kiện `AUTH_LOGIN_SUCCESS` (`C2`), `AUTH_CHALLENGE_CREATED` (`C2`), `AUTH_STEP_UP_VERIFIED` (`C2`), `AUTH_LOGIN_FAILED` (`C3`), `AUTH_ACCOUNT_LOCKED` (`C3`), `AUTH_SESSION_LOGOUT` (`C3`), `AUTH_CHALLENGE_LOCKED` (`C3`), `AUTH_STEP_UP_FAILED` (`C3`).
- **`OtpGateway`** (`notification.api.OtpGateway`): Gửi mã OTP qua kênh mô phỏng bên ngoài giao dịch DB.
- **`CustomerQuery`** (`customer.api.CustomerQuery`): Ánh xạ `userId -> customerId` và lấy địa chỉ nhận OTP đã che dấu khi ghép tích hợp.

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Hiện thực Flyway migrations `V2` (11 bảng Identity & seed RBAC), `V3` (FIDO2 verification material) và `V4` (`role_assignment_version`) (`BE-ID-IMP-01`, `BE-ID-IMP-10`, `BE-ID-IMP-11`).
2. [x] Hiện thực `IdentitySessionAuthenticator` SPI, quản lý phiên Opaque Server-side, xoay vòng CSRF token và thu hồi phiên (`BE-ID-IMP-02/03/04`).
3. [x] Hiện thực đăng nhập mật khẩu với `Argon2id`, chống dò tài khoản, khóa tạm thời 5 lần sai / 10 phút, MFA bắt buộc (`LOGIN_NEW_DEVICE`), WebAuthn/FIDO2 (`Yubico`) và kiểm tra lịch sử mật khẩu (`BE-ID-IMP-02/08/09/10`).
4. [x] Hiện thực máy trạng thái `auth_challenge` (`CREATED -> SENT -> VERIFIED` + `consumed_at`), `StepUpChallengePort`, `OtpDispatchService` và `OtpRecoveryJob` (`BE-ID-IMP-05/06/07`).
5. [x] Hiện thực `IdentityRoleCommandService`, REST Controllers/DTOs (`API-AUTH-01..07`), giới hạn tần suất theo username, metrics và `LogRedaction` (`BE-ID-IMP-11/12/13`) cùng bộ kiểm thử tích hợp PostgreSQL/bảo mật/hiệu năng 597 tests (`BE-ID-TEST-01..03`).
6. [ ] Ghép nối adapter runtime với `AuditModuleApi` (`audit`), `OtpGateway` (`notification`), `CustomerQuery` (`customer`) và luồng `T0`/`T1` của `transfer` / `admin`.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v1.0` | 12/11/2026 | Khởi tạo tóm tắt module `identity` và đồng bộ chính sách lockout/cookie/session theo `docs-common/01–11`. |
| `v1.1` | 18/11/2026 | Đồng bộ danh mục `API-AUTH-01..08`, schema `V2`–`V4` (`role_assignment_version`, `csrf_token_hash`, `consumed_at`, cột FIDO2), 4 role chuẩn + đặc quyền `CHECKER` và cập nhật trạng thái triển khai `DEC-ID-001..013`. |
