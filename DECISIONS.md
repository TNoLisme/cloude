# Sổ tay Quyết định Kiến trúc & Kỹ thuật (`DECISIONS.md`)

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `DECISIONS-LOG`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành (Baseline v1.0 trước tích hợp liên module)
> **Owner:** System Architect & Backend Lead
> **Reviewer:** Toàn bộ Module Owners (`common`, `identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`)

---

## 1. Mục đích và Nguyên tắc Áp dụng

Tài liệu này ghi nhận tập trung toàn bộ các quyết định kiến trúc, nghiệp vụ và giao tiếp nội bộ đã chốt cho dự án **Digital Banking MVP**. Khi phát triển độc lập từng module hoặc khi ghép tích hợp (`merge`), các quyết định tại đây cùng với bộ tài liệu `digitalbanking-docs/docs-common/` (`01`–`11` và `design/01`–`05`) là nguồn tham chiếu chuẩn duy nhất.

**Nguyên tắc phát triển độc lập và tích hợp:**
1. **Thứ bậc nguồn sự thật:** `docs-common/01–11` → `docs-common/design/01–05` → `openapi/digital-banking-v1.yaml` → `docs-backend/`.
2. **Giao tiếp liên module:** Chỉ giao tiếp qua public port/DTO bất biến trong package `com.simulatedbank.banking.<provider>.api` theo đúng đặc tả tại `docs-common/design/05. Modules contract.md`. Không truy cập trực tiếp bảng database, repository hoặc JPA entity của module khác.
3. **Kiểm thử khi module phụ thuộc chưa hoàn tất:** Consumer định nghĩa port theo đúng hợp đồng tại `05. Modules contract.md` và sử dụng test double/fake chỉ trong phạm vi `src/test` (không đăng ký bean giả làm provider production).

---

## 2. Quyết định Chuẩn hoá Tài liệu, Hạ tầng & Kiến trúc Chung (`DEC-DOC` / `DEC-COM`)

### DEC-DOC-001 — Chuẩn hoá bộ tài liệu kỹ thuật Baseline v1.0 và giải quyết xung đột liên tài liệu

- **Ngày chốt:** 18/11/2026.
- **Bối cảnh:** Do các module được phát triển độc lập theo từng nhánh công việc, tài liệu phát sinh nhiều ghi chú tạm thời theo từng task, thuật ngữ pha trộn Anh – Việt, các mục Open Issues (`OI-*`, `OQ-*`, `MOD-OI-*`) đã có quyết định nhưng chưa đóng, và một số xung đột về tên bảng, trạng thái giao dịch, mã HTTP replay và thứ tự migration.
- **Quyết định:**
  1. **Chuẩn hoá trạng thái và văn phong:** Toàn bộ tài liệu `docs-common/01–11`, `docs-common/design/01–05`, `openapi/` và `docs-backend/` được chuẩn hoá về văn phong kỹ thuật tiếng Việt thống nhất, có khối metadata chuẩn ở đầu tài liệu (`Phiên bản: 1.0`, `Trạng thái: Hoàn thành`), loại bỏ các ghi chú nhật ký hội thoại/task tạm thời và đóng toàn bộ danh mục Open Issues đã được phân xử.
  2. **Đồng bộ tên bảng và cột cơ sở dữ liệu (`banking` schema):** Sử dụng thống nhất tên bảng số ít chuẩn trong `04. Database design & analysis.md`: `app_user` (`failed_attempt_count`, `role_assignment_version`), `session` (`session_token_hash`, `csrf_token_hash`), `device` (`fido_public_key_cose`, `fido_user_handle`, `fido_sign_count`), `auth_challenge` (`purpose IN ('LOGIN_NEW_DEVICE','TRANSFER_STEP_UP','SENSITIVE_ACTION')`, `status IN ('CREATED','SENT','VERIFIED','EXPIRED','LOCKED')`, `consumed_at`, `fido_nonce`, `resend_count`), `customer`, `account`, `transfer`, `ledger_entry`, `idempotency_record`, `limit_config`, `limit_usage`, `approval_request`, `audit_log` (`canonical_payload`), `fraud_rule_config`, `fraud_flag`, `notification`.
  3. **Đồng bộ thông số bảo mật và vận hành:**
     - Cookie phiên: `DB_SESSION` (`HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=28800`), idle timeout `15 phút`, absolute timeout `8 giờ`.
     - Chính sách khóa: Nhập sai mật khẩu hoặc OTP **5 lần liên tiếp** sẽ khóa tạm thời **10 phút** (`423 AUTH_ACCOUNT_LOCKED` / `423 AUTH_OTP_LOCKED`).
     - Rate limit đăng nhập: **10 req/phút/IP** và **10 req/phút/username**; tạo lệnh chuyển tiền: **30 req/phút/customer**.
     - Định dạng che dấu số tài khoản (PII Masking): **`**** **** 1234`** (`"**** **** " + last4`).
     - Chu kỳ job đối soát/phục hồi giao dịch treo (`TransferRecoveryJob`): **60 giây/lần** (quét các giao dịch `PENDING` có tuổi $\ge 300\text{ giây}$).
  4. **Đồng bộ HTTP status cho Idempotency Replay:** Khi phát lại (`replay`) một yêu cầu chuyển tiền đã ở trạng thái kết thúc (`COMPLETED` hoặc `FAILED`), hệ thống trả về **nguyên mã HTTP gốc (`201 Created` cho `COMPLETED` hoặc `422 Unprocessable Entity` cho `FAILED`)** kèm header `Idempotent-Replayed: true` (loại bỏ mã `200 OK` khỏi đặc tả replay của `POST /api/v1/transfers`).
- **Tài liệu ảnh hưởng:** Toàn bộ `docs-common/`, `docs-common/design/`, `openapi/`, `docs-backend/`, `PROGRESS.md`.

### DEC-COM-001 — Chuỗi phiên bản Flyway Migration chuẩn khi tích hợp (`V1` – `V10`)

- **Ngày chốt:** 18/11/2026.
- **Bối cảnh:** Trong quá trình phát triển độc lập, module `identity` và `audit` cùng đặt tên migration là `V2` (`V2__create_identity_and_rbac_tables.sql` và `V2__create_audit_log.sql`), trong khi `transfer` và `ledger` đặt SQL ứng viên tại `db/transfer-candidate/V2__transfer.sql` và `db/ledger-candidate/V2__create_ledger_entry.sql`. Ngoài ra các JPA entity của `fraud`, `notification`, `ledger` nằm trong package quét mặc định khiến `ddl-auto: validate` yêu cầu bảng tương ứng phải tồn tại trong chuỗi Flyway chính.
- **Quyết định:** Chốt thứ tự đánh số Flyway Migration duy nhất tại `classpath:db/migration` khi ghép tích hợp các module:
  - `V1__init_schema.sql`: Khởi tạo schema `banking`.
  - `V2__create_identity_and_rbac_tables.sql`: Bảng `app_user`, `device`, `session` (gồm `csrf_token_hash`), `auth_challenge` (gồm `resend_count`), `auth_attempt`, `password_history`, `role`, `permission`, `user_role`, `role_permission` và seed RBAC chuẩn.
  - `V3__add_fido_verification_material.sql`: Bổ sung các cột xác thực FIDO2/WebAuthn trên `device` và `auth_challenge`.
  - `V4__add_role_assignment_version.sql`: Bổ sung `app_user.role_assignment_version`.
  - `V5__create_audit_log.sql`: Đổi số hiệu từ `V2__create_audit_log.sql` sang `V5`; tạo bảng `audit_log`, `retention_policy`, `legal_hold`, trigger append-only và phân quyền `banking_app`.
  - `V6__create_customer_and_account_tables.sql`: Tạo bảng `customer` và `account`.
  - `V7__create_transfer_and_idempotency_tables.sql`: Chuyển từ `db/transfer-candidate/V2__transfer.sql` sang `V7` (chuẩn hoá PK `GENERATED ALWAYS AS IDENTITY`).
  - `V8__create_ledger_entry.sql`: Chuyển từ `db/ledger-candidate/V2__create_ledger_entry.sql` sang `V8`; tạo bảng `ledger_entry` và trigger append-only.
  - `V9__create_limit_and_approval_tables.sql`: Tạo bảng `limit_config`, `limit_usage`, `limit_reservation` và `approval_request`.
  - `V10__create_fraud_and_notification_tables.sql`: Tạo bảng `fraud_rule_config`, `fraud_flag` và `notification`.
  - `db/seed/R__synthetic_demo_seed.sql`: Chỉ chứa dữ liệu mẫu synthetic cho môi trường `local`/`demo`.
- **Tài liệu ảnh hưởng:** `docs-common/04. Database design & analysis.md`, `docs-common/08. Deployment infrastructure.md`, `docs-backend/00. architecture & conventions.md`, `docs-backend/deployment/configuration.md`.

### DEC-COM-002 — Phân tách tài khoản cơ sở dữ liệu (`banking_migrator` và `banking_app`)

- **Ngày chốt:** 16/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:** Sử dụng hai role PostgreSQL tách biệt:
  - `banking_migrator`: Sở hữu DDL và chạy Flyway migrations khi khởi động.
  - `banking_app`: Tài khoản runtime của ứng dụng Spring Boot, chỉ có quyền `SELECT, INSERT, UPDATE, DELETE` trên các bảng nghiệp vụ thông thường và chỉ có quyền **`SELECT, INSERT`** (thu hồi `UPDATE, DELETE, TRUNCATE`) trên hai bảng bằng chứng bất biến `banking.ledger_entry` và `banking.audit_log`.
- **Tài liệu ảnh hưởng:** `docs-common/04. Database design & analysis.md`, `docs-common/design/01. audit-traceability.md`, `docs-common/design/04. security.md`, `docs-backend/deployment/configuration.md`.

---

## 3. Quyết định Module Identity & RBAC (`DEC-ID`)

### DEC-ID-001 — Seed dữ liệu Role & Permission chuẩn trong Migration `V2`

- **Ngày chốt:** 16/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:** Đặt seed danh mục RBAC chuẩn trực tiếp trong migration `V2__create_identity_and_rbac_tables.sql` thay vì `R__synthetic_demo_seed.sql` để mọi môi trường (`local`, `test`, `staging`, `prod`) đều có sẵn cấu hình phân quyền khi khởi chạy:
  - **4 Role:** `CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR` (`CHECKER` là cờ đặc quyền `app_user.is_checker` gắn trực tiếp trên nhân viên, không phải role).
  - **15 Permission** và ánh xạ `role_permission` theo `04. Database design & analysis.md` Appendix A.2 và `07. API contracts.md` §3.4, trong đó **`AUDITOR` được cấp đầy đủ 6 quyền đọc/kiểm toán:** `AUDIT_READ`, `AUDIT_EXPORT`, `TRANSACTION_READ`, `ACCOUNT_READ`, `CUSTOMER_READ`, `FRAUD_READ`. Quyền `AUDIT_EXPORT` chỉ cấp duy nhất cho `AUDITOR`.
- **Tài liệu ảnh hưởng:** `03. SRS.md`, `04. Database design & analysis.md`, `07. API contracts.md`, `design/04. security.md`, `docs-backend/00. architecture & conventions.md`.

### DEC-ID-002 — Lưu trữ `session.csrf_token_hash` trong bảng `banking.session`

- **Ngày chốt:** 16/11/2026.
- **Quyết định:** Bổ sung cột `csrf_token_hash CHAR(64) NOT NULL` vào bảng `banking.session`. Khi tạo phiên hoặc làm mới CSRF token, hệ thống sinh token ngẫu nhiên 32 byte (`Base64Url`), trả giá trị thô cho client để gửi qua header `X-CSRF-Token` và chỉ lưu mã băm `SHA-256` chữ thường (64 ký tự hex) trong `session.csrf_token_hash`. So khớp CSRF sử dụng `MessageDigest.isEqual` chống timing attack.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.3, `07. API contracts.md` §3.3, `design/04. security.md` §4.3.

### DEC-ID-003 — Cơ chế Xác thực Mật khẩu, Khóa Tạm thời và Giao tiếp Phụ thuộc của Identity

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Băm mật khẩu và chống dò tài khoản:** Dùng `Argon2id` (`m=65536 KB`, `t=3`, `p=4`, `salt=16B`, `hash=32B`). Khi `username` không tồn tại, thực hiện băm giả lập (`dummy hash`) và trả cùng mã `401 AUTH_INVALID_CREDENTIALS`.
  2. **Khóa tạm thời (`Lockout`):** Nhập sai 5 lần liên tiếp chuyển `app_user.status = 'LOCKED'`, `locked_until = now() + 10 phút` trong giao dịch độc lập và trả `423 AUTH_ACCOUNT_LOCKED`.
  3. **Giao tiếp phụ thuộc theo hợp đồng:** Sử dụng `SessionAuthenticator` SPI cho `common`, `AuthAuditPort` (chờ nối `AuditModuleApi`), `OtpGateway` (chờ nối adapter `notification`) và `CustomerQuery.findByUserId` (chờ nối `customer`).
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §8.1.1, `design/04. security.md` §4.1, `design/05. Modules contract.md` §5.

### DEC-ID-004 — Xoay vòng CSRF Token nguyên tử tại `GET /api/v1/auth/session` và Kiểm soát Quyền theo Request

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Do cơ sở dữ liệu chỉ lưu `csrf_token_hash` một chiều, endpoint `GET /api/v1/auth/session` (`API-AUTH-07`) cấp lại `csrfToken` cho SPA sau khi tải lại trang bằng cách sinh `csrfToken` mới và cập nhật `session.csrf_token_hash` nguyên tử trên phiên `ACTIVE` còn hiệu lực.
  2. Quyền hạn (`authorities`) của người dùng được nạp từ cơ sở dữ liệu trên mỗi request thông qua `SessionAuthenticator`, đảm bảo thu hồi quyền có hiệu lực tức thì từ request kế tiếp.
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §8.1.7, `design/04. security.md` §4.3, `design/05. Modules contract.md` §5.1.

### DEC-ID-005 — Cập nhật Trạng thái OTP Challenge bằng Câu lệnh Điều kiện Nguyên tử (`Conditional UPDATE`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:** Không giữ khóa dòng `SELECT ... FOR UPDATE` xuyên suốt phép kiểm tra `Argon2id` (~100 ms) và không ném exception làm rollback bộ đếm số lần thử sai. Thay vào đó:
  - Mọi bước tăng `attempt_count`, chuyển trạng thái `LOCKED` khi chạm ngưỡng 5 lần sai, chuyển `EXPIRED`, và đánh dấu `VERIFIED` đều thực hiện qua các câu lệnh `UPDATE ... WHERE ...` nguyên tử trong giao dịch ngắn và được commit bền vững.
  - Bước tiêu thụ (`consumeVerified`) chạy trong giao dịch nghiệp vụ của caller (`T1` hoặc giao dịch duyệt `admin`) với `Propagation.MANDATORY`, đặt `consumed_at = clock_timestamp()` khi `status = 'VERIFIED' AND consumed_at IS NULL AND expires_at > clock_timestamp()`.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.5, `design/02. consistency-concurrency.md` §5.1, `design/05. Modules contract.md` §5.2.

### DEC-ID-006 — Gửi OTP sau khi Commit (`Post-Commit Dispatch`) và Chính sách Fail-Closed

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Bản ghi `auth_challenge` (`status = 'CREATED'`) và sự kiện kiểm toán `AUTH_CHALLENGE_CREATED` (`C2`) được commit trong một giao dịch ngắn (`REQUIRES_NEW`) trước khi gọi `OtpGateway.sendOtp` bên ngoài giao dịch (`NOT_SUPPORTED`).
  2. Mã OTP thô chỉ lưu trong mảng `char[]` tạm thời trong bộ nhớ và được xóa trắng (`wipe`) trong khối `finally`; cơ sở dữ liệu chỉ lưu mã băm `Argon2id`.
  3. Chỉ khi `OtpGateway` trả về `ACCEPTED`, hệ thống mới cập nhật có điều kiện `status = 'CREATED' -> 'SENT'`. Chỉ các challenge ở trạng thái **`SENT`** mới được phép xác thực OTP (`verifyChallenge`). Nếu gửi thất bại hoặc quá thời gian chờ, hệ thống trả `503 OTP_DISPATCH_FAILED` (fail-closed).
  4. Job dọn dẹp (`OtpRecoveryJob`) chạy định kỳ với `FOR UPDATE SKIP LOCKED` (tối đa 100 bản ghi/lần) để chuyển các challenge quá hạn sang `EXPIRED`.
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §8.1.8, `design/04. security.md` §4.4, `design/05. Modules contract.md` §5.2 & §11.3.

### DEC-ID-007 — Ràng buộc Ngữ cảnh Step-Up (`StepUpChallengePort`) và Phân loại Mã lỗi

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. `auth_challenge` giữ trạng thái `status = 'VERIFIED'` và đánh dấu tiêu thụ một lần bằng cột `consumed_at` (không dùng giá trị enum `CONSUMED`).
  2. Khi tiêu thụ challenge tại `T1` hoặc `admin`, hệ thống đối chiếu đầy đủ `user_id`, `purpose`, `request_hash` (canonical SHA-256 theo `07` §5.2) và `idempotency_key`. Sai khác ngữ cảnh trả `409 STEP_UP_CHALLENGE_MISMATCH`; challenge của người dùng khác trả `404 AUTH_CHALLENGE_NOT_FOUND`; quá hạn trả `410 AUTH_OTP_EXPIRED`.
  3. Endpoint `POST /api/v1/auth/step-up/verify` lấy `userId` từ phiên đăng nhập hiện hành và chỉ chấp nhận challenge có `purpose` là `TRANSFER_STEP_UP` hoặc `SENSITIVE_ACTION` (không chấp nhận `LOGIN_NEW_DEVICE`).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.5, `07. API contracts.md` §6.4–§6.6, `design/04. security.md` §4.4, `design/05. Modules contract.md` §5.2.

### DEC-ID-008 — Bắt buộc MFA (`LOGIN_NEW_DEVICE`) cho Đăng nhập Mật khẩu và Chống Bypass bằng Metadata

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Các trường `deviceRef` và `deviceFingerprint` do client gửi lên chỉ là metadata nhận diện để gắn ngữ cảnh thiết bị, không phải bằng chứng mật mã chống sao chép.
  2. Theo chính sách bảo mật MVP (`mfa_every_login`), mọi lần đăng nhập bằng mật khẩu hợp lệ (`POST /api/v1/auth/login`) đều khởi tạo challenge `LOGIN_NEW_DEVICE` và trả về `202 Accepted`. Phiên đăng nhập (`200 OK`) chỉ được cấp nguyên tử sau khi xác thực OTP thành công tại `POST /api/v1/auth/otp/verify`.
  3. Thiết bị đã bị thu hồi (`status = 'REVOKED'`) không được phép tái kích hoạt hoặc gắn phiên mới.
- **Tài liệu ảnh hưởng:** `03. SRS.md` §4.1, `07. API contracts.md` §3.8 & §8.1.1, `design/04. security.md` §4.2, `design/05. Modules contract.md` §5.

### DEC-ID-009 — Chính sách Fail-Closed cho Passcode Login và Lịch sử Mật khẩu (`password_history`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Passcode Login (`POST /api/v1/auth/passcode`):** Do MVP chưa cung cấp quy trình đăng ký khóa thiết bị bất đối xứng cho Passcode, endpoint `POST /api/v1/auth/passcode` kiểm tra trạng thái thiết bị theo nguyên tắc fail-closed và trả về `403 AUTH_DEVICE_NOT_TRUSTED` (không cho phép dùng metadata `deviceRef`/`fingerprint` để vượt kiểm tra thiết bị tin cậy).
  2. **Lịch sử mật khẩu (`password_history`):** Mật khẩu phải dài 8–128 ký tự (gồm chữ hoa, chữ thường, chữ số, ký tự đặc biệt) và không được trùng với mật khẩu hiện tại hoặc **3 mật khẩu gần nhất** trong bảng `banking.password_history`.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.7, `07. API contracts.md` §8.1.2, `design/04. security.md` §4.1.

### DEC-ID-010 — Xác thực Sinh trắc học / Khóa Bảo mật FIDO2/WebAuthn (`API-AUTH-03`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Bổ sung các cột lưu trữ khóa công khai và bộ đếm chữ ký trên `banking.device` (`fido_public_key_cose BYTEA`, `fido_user_handle BYTEA`, `fido_sign_count BIGINT`) và `fido_nonce BYTEA` trên `banking.auth_challenge` (migration `V3`).
  2. Sử dụng thư viện chuẩn `com.yubico:webauthn-server-core` với chính sách bắt buộc xác minh người dùng (`userVerification = "required"`, `timeout = 30000 ms`), kiểm tra chặt chẽ `rpId`, `origin` allowlist, chữ ký mật mã và `signCount` (từ chối nếu `signCount` không tăng khi giá trị lưu trước đó $> 0$; cho phép `0 -> 0` đối với authenticator không dùng bộ đếm).
  3. Khi FIDO thất bại hoặc thiết bị không hỗ trợ, client quay về luồng chuẩn `POST /api/v1/auth/login` (mật khẩu + OTP).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.4–§4.2.5, `05. Technical stack.md` §5.2, `07. API contracts.md` §8.1.3, `design/05. Modules contract.md` §5.

### DEC-ID-011 — Quản lý Phiên bản Phân quyền (`role_assignment_version`) và Thu hồi Phiên Tức thì

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Bổ sung cột `role_assignment_version BIGINT NOT NULL DEFAULT 0` trên bảng `banking.app_user` (migration `V4`). Mỗi khi gán/thu hồi role hoặc đặc quyền `CHECKER` thành công, phiên bản này tăng thêm đúng `1`.
  2. Module `admin` đọc phiên bản hiện tại qua `IdentityRoleCommand.getRoleAssignmentVersion(targetUserId)` khi Maker tạo yêu cầu phê duyệt và truyền `expectedVersion` vào `IdentityRoleCommand.applyApprovedRoleChange` trong giao dịch phê duyệt của Checker (`Propagation.MANDATORY`). Nếu phiên bản đã thay đổi, từ chối với `409 ROLE_CONFLICT`.
  3. Mọi thay đổi quyền hoặc đặc quyền thành công đều **thu hồi ngay lập tức toàn bộ phiên `ACTIVE`** của người dùng mục tiêu (`status = 'LOGGED_OUT'`, `logged_out_at = clock_timestamp()`) trong cùng giao dịch. Nghiêm cấm Maker hoặc Checker tự thay đổi quyền của chính mình (`409 ROLE_CONFLICT`).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.1, `07. API contracts.md` §8.8, `design/04. security.md` §5.3, `design/05. Modules contract.md` §5.3 & §12.1.

### DEC-ID-012 — Chuẩn hoá Tầng Adapter REST của Identity và Xử lý Lỗi RFC 9457

- **Ngày chốt:** 16/11/2026.
- **Quyết định:** Toàn bộ các endpoint xác thực (`API-AUTH-01` đến `API-AUTH-08`) sử dụng chung pipeline `GlobalExceptionHandler` và `ProblemWriter` của module `common` để trả về định dạng lỗi chuẩn `application/problem+json` (RFC 9457) kèm `X-Correlation-ID` và `X-Request-ID`. Đối với `API-AUTH-08` (`POST /api/v1/auth/challenges/{challengeId}/resend`), cơ sở dữ liệu lưu `resend_count` (tối đa 3 lần gửi lại, thời gian chờ tối thiểu 60 giây giữa hai lần gửi, làm mới OTP hash và TTL 5 phút nhưng giữ nguyên `attempt_count`).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.2.5, `07. API contracts.md` §8.1, `openapi/digital-banking-v1.yaml`.

### DEC-ID-013 — Giám sát Metrics, Giới hạn Tần suất theo Username và Che dấu Dữ liệu Nhạy cảm (`LogRedaction`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Rate Limit theo Username:** Áp dụng giới hạn `10 request/phút/username` (sau khi chuẩn hoá `toLowerCase(Locale.ROOT)`) trước bước kiểm tra mật khẩu; khi vượt ngưỡng trả về `429 RATE_LIMITED` kèm `retryAfterSeconds` và header `Retry-After`.
  2. **Metrics vận hành:** Ghi nhận các chỉ số Micrometer `auth.login.requests`, `auth.login.duration`, `auth.mfa.verify`, `auth.session.created`, `auth.session.terminated`, `auth.login.rate_limited`, `rate.limit.rejections` với tập nhãn hữu hạn (không gắn `userId`, `username`, `correlationId` hoặc token làm label).
  3. **Che dấu log (`common.logging.LogRedaction`):** Toàn bộ dữ liệu chi tiết trước khi đưa vào security fallback log phải đi qua bộ lọc `LogRedaction` để loại bỏ mật khẩu, passcode, OTP, token, secret, cookie, CSRF và username.
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §2.8, `design/04. security.md` §8.3, `docs-backend/00. architecture & conventions.md` §10.

### DEC-ID-TEST-02 & DEC-ID-TEST-03 — Hạ tầng Kiểm thử Tích hợp PostgreSQL, Bảo mật và Hiệu năng Identity

- **Ngày chốt:** 17/11/2026.
- **Quyết định:**
  1. Kiểm thử tích hợp và kiểm thử khởi động lại ứng dụng (`Restart Verification`) bắt buộc chạy trên container `postgres:16-alpine` thật thông qua `PostgreSqlIntegrationTestSupport` và hai `SpringApplication` context độc lập (không dùng H2, không dùng `context.refresh()`).
  2. Tách tác vụ Gradle `perfTest` (`maxHeapSize = "3g"`, tắt JaCoCo bytecode instrumentation khi đo tải) để đo chính xác độ trễ xác thực đồng thời của `Argon2id` mà không làm sai lệch kết quả kiểm thử độ phủ (`jacocoTestReport`).
- **Tài liệu ảnh hưởng:** `docs-common/09. Test plan.md`, `docs-backend/modules/identity/README.md`.

---

## 4. Quyết định Module Transfer, Limit, Concurrency & Khôi phục (`DEC-TRF`)

### DEC-TRF-001 & DEC-TRF-002 — Công thức Tính Tổng Chuyển tiền Dự kiến trong Ngày và Ngưỡng Step-Up Mặc định

- **Ngày chốt:** 15/11/2026 (hợp nhất với `DEC-TRF-008` ngày 16/11/2026).
- **Quyết định:**
  1. **Ngưỡng kích hoạt Step-Up (`T0` trước `T1`):** Bắt buộc xác thực tăng cường (`428 STEP_UP_REQUIRED`) khi:
     - Giá trị một lệnh chuyển tiền **`amount >= 10.000.000 VND`** (`9.999.999,99` không kích hoạt; `10.000.000,00` kích hoạt), **HOẶC**
     - Tổng giá trị chuyển tiền dự kiến trong ngày của khách hàng **`projected_daily_total > 20.000.000 VND`** (`20.000.000,00` không kích hoạt; `20.000.000,01` kích hoạt).
  2. **Công thức tổng dự kiến (`projected_daily_total`):**
     $$\text{Projected Daily Total} = \text{Used}_{\text{COMPLETED (today)}} + \sum \text{Reservation}_{\text{PENDING (active)}} + \text{Amount}_{\text{current}}$$
     Mỗi lệnh chuyển tiền chỉ được tính đúng một lần. Các lệnh đã kết thúc ở trạng thái `FAILED` được giải phóng reservation và không đóng góp vào tổng dự kiến.
- **Tài liệu ảnh hưởng:** `02. BRD.md` §6.7, `03. SRS.md` §2.13.6 & `AC-033`, `07. API contracts.md` §6.1, `design/03. fraud-detection-rules.md`, `design/04. security.md` §4.4, `design/05. Modules contract.md` §10.

### DEC-TRF-003 — Phân định Lỗi Trước `T1`, Lỗi Nghiệp vụ Sau `T1` và Xử lý Lỗi Ghi Có (`Credit Failure`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Lỗi phát hiện ở bước kiểm tra trước `T1` (`Preflight`):** Số tiền không hợp lệ, tài khoản nguồn/đích không hoạt động hoặc số dư không đủ phát hiện trước `T1` bị từ chối ngay mà **không** tạo bản ghi `transfer`, `idempotency_record` hay `ledger_entry` (`AC-006`).
  2. **Thay đổi trạng thái hoặc số dư sau khi `T1` đã commit (`Race Condition`):** Khi vào `T2` và lấy khóa tài khoản, nếu trạng thái tài khoản hoặc số dư khả dụng đã thay đổi làm điều kiện nghiệp vụ không còn thỏa mãn, giao dịch được kết thúc nguyên tử ở trạng thái **`FAILED`** (lưu mã lỗi, lưu response vào `idempotency_record`, giải phóng reservation, ghi outbox thông báo và ghi audit `TRANSFER_FAILED` `C1` trong cùng giao dịch; không ghi nợ/ghi có và không tạo bút toán sổ cái).
  3. **Lỗi ở bước ghi có sau khi đã ghi nợ (`Debit Succeeds -> Credit Fails`, `AC-012`):**
     - Mọi lỗi kỹ thuật/ràng buộc tại bước ghi có hoặc ghi sổ cái trong `T2` đều **rollback toàn bộ hiệu ứng tiền tệ của `T2`** (không bao giờ commit lệnh ghi nợ đơn lẻ).
     - Nếu `T2` khôi phục được về savepoint trước khi ghi nợ trong khi vẫn giữ khóa, hoặc thông qua giao dịch `Tfail` đã xác minh bằng chứng rollback và khóa hàng rào (`fencing`) an toàn, hệ thống chuyển bản ghi `transfer` sang `FAILED`, giải phóng reservation và ghi audit `TRANSFER_FAILED`.
     - Nếu mất kết nối cơ sở dữ liệu, crash tiến trình hoặc kết quả commit chưa xác định, hệ thống tuyệt đối không suy đoán trạng thái mà giữ nguyên trạng thái thực tế trên primary để `TransferRecoveryJob` đối soát bằng chứng và xử lý an toàn.
- **Tài liệu ảnh hưởng:** `03. SRS.md` `AC-006` & `AC-012`, `04. Database design & analysis.md` §7.7, `07. API contracts.md` §8.3, `design/02. consistency-concurrency.md` §5 & §8.

### DEC-TRF-004 — Hành vi Thử lại HTTP (`Idempotency Retry`) và Phân tách với Tiến trình Phục hồi Nội bộ

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Khi client gửi lại request có cùng `Idempotency-Key`, cùng actor và cùng `request_hash`:
     - Nếu bản ghi `idempotency_record` đã ở trạng thái kết thúc (`COMPLETED` hoặc `FAILED`): phát lại nguyên mã HTTP gốc (`201 Created` hoặc `422 Unprocessable Entity`) và nguyên thân phản hồi đã che dấu PII kèm header `Idempotent-Replayed: true`.
     - Nếu bản ghi chưa kết thúc (`RECEIVED` / `IN_PROGRESS`): trả về ngay **`409 Conflict` (`IDEMPOTENCY_IN_PROGRESS`)** kèm header `Retry-After` (thread HTTP không trực tiếp nhảy vào chạy tiếp `T2` để tránh tranh chấp cùng worker).
  2. Nếu `Idempotency-Key` trùng nhưng khác actor hoặc khác `request_hash`: trả về **`409 Conflict` (`IDEMPOTENCY_CONFLICT`)**, ghi sự kiện bảo mật `SECURITY_IDEMPOTENCY_CONFLICT` (`C3`) và không tiết lộ dữ liệu của giao dịch gốc.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §7.7.3, `07. API contracts.md` §5, `openapi/digital-banking-v1.yaml`, `design/02. consistency-concurrency.md` §6.

### DEC-TRF-005 — Thời hạn Lưu trữ (`Retention TTL`) của `idempotency_record`

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Thời hạn lưu trữ mặc định và tối thiểu của `idempotency_record` là **24 giờ (`86400 giây`)** tính từ thời điểm giao dịch đạt trạng thái kết thúc (`completed_at`).
  2. Tại `T1`, khởi tạo `expires_at = created_at + ttl_t1_seconds`. Khi giao dịch kết thúc tại `T2` (`COMPLETED` hoặc `FAILED`), cập nhật nguyên tử `completed_at` và `expires_at = GREATEST(expires_at, completed_at + ttl_terminal_seconds)`.
  3. Tiến trình dọn dẹp (`purge`) chỉ được phép xóa các bản ghi đã ở trạng thái kết thúc (`COMPLETED`/`FAILED`) và có `expires_at < clock_timestamp()` sau khi khóa và kiểm tra lại trạng thái; tuyệt đối không xóa bản ghi của giao dịch còn `PENDING`.
- **Tài liệu ảnh hưởng:** `03. SRS.md` §2.13.2, `04. Database design & analysis.md` §4.4.3, `design/02. consistency-concurrency.md` §6.5.

### DEC-TRF-006 & DEC-TRF-014 — Giao thức Đối soát & Phục hồi Giao dịch `PENDING` Hai Pha (`Two-Phase Claim & Recovery`)

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Pha 1 — Chiếm quyền xử lý bền vững (`Durable Claim Transaction`):** Sau khi truy vấn danh sách ứng viên `PENDING` quá hạn không giữ khóa, worker mở giao dịch ngắn lấy `customer step-up guard`, khóa dòng `transfer` bằng `FOR UPDATE SKIP LOCKED`, tăng `attempt_epoch`, gán `attempt_token`, `attempt_owner`, `attempt_lease_expires_at`, tăng `recovery_attempt_count` và **commit ngay** trước khi xử lý.
  2. **Pha 2 — Kiểm tra bằng chứng và thực thi (`Verification & Execution Transaction`):** Mở giao dịch riêng lấy lại `customer step-up guard` → khóa `transfer` và xác minh khớp `attempt_epoch`/`attempt_token` cùng trạng thái `PENDING`. Đọc bằng chứng trên primary qua các port công khai: `Account.MovementEvidence`, `Ledger.LedgerEvidence`, `Limit.ReservationEvidence`, và `Audit.CompletionEvidence`.
  3. **Quy tắc phân nhánh an toàn:**
     - Chỉ tự động hoàn tất sửa chữa sang `COMPLETED` (`T-REC-02`) khi có đủ bằng chứng nhất quán (đúng cặp bút toán sổ cái DEBIT/CREDIT, hiệu ứng số dư, hạn mức và dữ liệu phản hồi gốc đã che dấu) và ghi sự kiện `TRANSFER_RECONCILED` (`C1`, actor `SYSTEM`). Không bao giờ ghi nợ/ghi có hoặc tạo bút toán sổ cái lần thứ hai.
     - Nếu giao dịch chưa phát sinh bất kỳ hiệu ứng tiền tệ/sổ cái nào và còn trong giới hạn thử lại, thực thi `T2` ngay trong giao dịch đang giữ khóa.
     - Nếu bằng chứng bị lệch một phần hoặc không xác định được trạng thái an toàn, giữ nguyên trạng thái, chuyển `attempt_state = 'MANUAL'` và phát cảnh báo vận hành tức thì.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §7.7.3, `06. System architecture.md` §7.4, `design/02. consistency-concurrency.md` §10, `design/05. Modules contract.md` §4 & §13.4.

### DEC-TRF-007 — Thứ tự Ghi Kiểm toán `C2` (`FRAUD_FLAG_CREATED`) và `C1` (`TRANSFER_COMPLETED`/`FAILED`) Cuối Giao dịch `T2`

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. Trong `T2`, module `fraud` trả về quyết định kèm danh sách các cờ gian lận thực sự được giữ lại sau savepoint.
  2. Sau khi toàn bộ các bước ghi nghiệp vụ (số dư `account`, bút toán `ledger`, hạn mức `limit_usage`, thông báo `notification`, trạng thái `transfer` và `idempotency_record`) đã hoàn tất và `flush`, `transfer` gọi `AuditModuleApi.recordInTransaction` (`Propagation.MANDATORY`) tuần tự cho từng sự kiện `FRAUD_FLAG_CREATED` (`C2`) theo thứ tự `flag_id` tăng dần, sau đó ghi sự kiện kết thúc `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED` (`C1`).
  3. Không thực hiện thêm bất kỳ câu lệnh SQL nghiệp vụ hay gọi mạng nào sau khi đã lấy khóa chuỗi băm `audit_log`. Mọi lỗi khi ghi `C2` hoặc `C1` đều làm rollback toàn bộ `T2` (fail-closed; cơ chế fail-open của fraud engine không được phép bỏ qua lỗi ghi audit `C2`).
- **Tài liệu ảnh hưởng:** `design/01. audit-traceability.md` §5.3, `design/02. consistency-concurrency.md` §5.3, `design/05. Modules contract.md` §11.1.

### DEC-TRF-008 — Cơ chế Khóa Bảo vệ (`Customer Step-Up Guard`), Giữ chỗ (`Reservation`) và Thứ tự Khóa Toàn cục

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Khóa bảo vệ theo khách hàng (`Customer Step-Up Guard`):** Sử dụng khóa mức khách hàng do module `limit` quản lý để tuần tự hoá việc đánh giá tổng tiền dự kiến trong ngày và tạo reservation tại `T1`, cũng như giải phóng/chốt usage tại `T2`.
  2. **Múi giờ và ngày nghiệp vụ:** Múi giờ chuẩn là `Asia/Ho_Chi_Minh`. Thời điểm kết thúc logic `terminal_at` được đọc một lần từ `clock_timestamp()` trên PostgreSQL primary trong `T2` (sau khi đã khóa tài khoản và trước khi khóa `limit_usage`), lưu ở dạng UTC và quy đổi ra ngày nghiệp vụ `Asia/Ho_Chi_Minh` để cập nhật `limit_usage`.
  3. **Thứ tự khóa toàn cục bắt buộc (`Global Lock Order`):**
     $$\text{1. Customer Guard} \rightarrow \text{2. Transfer Row} \rightarrow \text{3. Accounts (account\_id ASC)} \rightarrow \text{4. Limit Usage (CUSTOMER} \rightarrow \text{ACCOUNT)} \rightarrow \text{5. Audit Chain Advisory Lock}$$
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §7.7.2, `06. System architecture.md` §8.2, `design/02. consistency-concurrency.md` §7, `design/05. Modules contract.md` §10.

### DEC-TRF-009 — Chụp Cấu hình Hạn mức (`Limit Snapshot`), Quy tắc Gian lận `HIGH_FREQUENCY` và Bất biến Sổ cái

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Hạn mức (`Limit`):** Trong `T2`, đọc cấu hình `ACTIVE` của hai phạm vi `CUSTOMER` và `ACCOUNT` trong một snapshot nhất quán, lưu lại phiên bản cấu hình và kiểm tra hạn mức từng giao dịch (`max_single_amount`) lẫn hạn mức ngày (`daily_amount`). Khi `settleOrReleaseReservation` hoàn tất cho giao dịch `COMPLETED`, hàm này thực hiện chốt reservation sang `limit_usage` đúng một lần (không gọi chồng `incrementUsage` gây cộng kép).
  2. **Quy tắc gian lận tần suất cao (`HIGH_FREQUENCY`):** Tạo cờ `HIGH_FREQUENCY` khi số lượng lệnh chuyển tiền (`transfer` intent đã commit `T1`, tính cả `PENDING`/`COMPLETED`/`FAILED`) của cùng khách hàng có `created_at` nằm trong cửa sổ trượt `(terminal_at - 600 giây, terminal_at]` **lớn hơn `5`** (tức từ lệnh thứ `6` trở đi trong 10 phút). Mặc định chỉ gắn cờ (`OPEN`) và tiếp tục xử lý giao dịch.
  3. **Bất biến sổ cái (`Ledger Invariant`):** Kiểm tra đồng bộ ngay trong `T2` đảm bảo đúng 2 dòng bút toán (`seq 1 DEBIT` nguồn, `seq 2 CREDIT` đích) khớp tuyệt đối số tiền, loại tiền `VND` và tài khoản trước khi chuyển sang pha ghi audit cuối.
- **Tài liệu ảnh hưởng:** `03. SRS.md` §2.13.5, `04. Database design & analysis.md` §4.4–§4.7, `design/03. fraud-detection-rules.md`, `design/05. Modules contract.md` §9 & §10.

### DEC-TRF-010 — Tham số Vận hành Tiến trình Phục hồi (`TransferRecoveryJob`) và Mục tiêu Kiểm thử Tải

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. `TransferRecoveryJob` chạy khi khởi động và định kỳ mỗi **60 giây**, xét các giao dịch `PENDING` có tuổi $\ge 300\text{ giây}$ theo lô tối đa 100 bản ghi.
  2. Thử lại tối đa **5 lần** hoặc trong vòng **15 phút** kể từ lần thử đầu tiên với khoảng lùi hàm mũ (`30s`, `60s`, `120s`, `240s` kèm jitter `0–5s`). Khi vượt ngưỡng hoặc gặp trạng thái không xác định, dừng tự động, chuyển sang `MANUAL` và phát cảnh báo vận hành.
  3. Mục tiêu kiểm thử tải (`D5 Load Test`): duy trì **20 RPS trong 10 phút**, độ trễ `p95 <= 2 giây`, `p99 <= 5 giây`, tỷ lệ lỗi kỹ thuật `< 1%`, đảm bảo tuyệt đối 0 số dư âm và 0 giao dịch bị ghi nợ lặp.
- **Tài liệu ảnh hưởng:** `08. Deployment infrastructure.md`, `09. Test plan.md` §7.6, `design/02. consistency-concurrency.md` §10.

### DEC-TRF-011 — Transactional Outbox cho Thông báo (`T2`/`T3`) và Giải mã Địa chỉ Nhận tại Thời điểm Gửi

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Outbox trong `T2` và Worker `T3`:** Bản ghi `banking.notification` (`status = 'PENDING'`, chỉ chứa `payload_masked`, không chứa PII bản rõ) được chèn trong cùng giao dịch `T2`, có ràng buộc duy nhất theo `(transfer_id, customer_id, notification_type, channel)`. Worker `T3` quét định kỳ mỗi **2 giây** (lô 50 bản ghi) bằng `FOR UPDATE SKIP LOCKED` với thời hạn thuê (`lease`) **30 giây**, gửi qua gateway bên ngoài giao dịch DB và thử lại tối đa **5 lần**.
  2. **Giải mã địa chỉ đích (`resolveNotificationDestination`):** Ngay trước khi gửi thông báo hoặc OTP sau khi giao dịch DB đã commit, `notification`/`identity` gọi port `CustomerQuery.resolveNotificationDestination` với ngữ cảnh `SYSTEM` để lấy số điện thoại/email thực tế trong bộ nhớ tạm thời (tuyệt đối không lưu bản rõ vào bảng `notification` hoặc ghi ra log).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.7.3 & §7.7.1, `06. System architecture.md` §7.5, `design/05. Modules contract.md` §6 & §11.3.

### DEC-TRF-012 — Bảo vệ `Idempotency-Key`, Phân quyền Tra cứu Giao dịch và Quản lý Phiên bản Hợp đồng Module

- **Ngày chốt:** 16/11/2026.
- **Quyết định:**
  1. **Bảo vệ `Idempotency-Key`:** Client sinh khóa ngẫu nhiên bằng CSPRNG (UUIDv4 hoặc tối thiểu 128 bit entropy) dài `1–255` ký tự (`^[A-Za-z0-9._:~-]{1,255}$`). Không ghi khóa thô vào log; khi phát hiện xung đột, chỉ ghi mã băm HMAC fingerprint.
  2. **Phân quyền đọc giao dịch:** `CUSTOMER` phải có quyền `TRANSACTION_READ_SELF` và sở hữu tài khoản nguồn **hoặc** tài khoản đích. Nhân viên (`STAFF`) phải có `TRANSACTION_READ` trong phạm vi được phân quyền; trường `fraudFlags` chỉ trả về khi người gọi có quyền `FRAUD_READ` hoặc `FRAUD_REVIEW`.
  3. **Kiểm soát kiến trúc (`ArchUnit`):** Mọi giao tiếp liên module phải đi qua package `<provider>.api` với các Java record bất biến; kiểm tra tự động bằng `ModuleBoundaryArchTest`.
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §3.5 & §5, `design/04. security.md` §5.2 & §8.4, `design/05. Modules contract.md` §3 & §12.3.

### DEC-TRF-013, DEC-TRF-015, DEC-TRF-016, DEC-TRF-017, DEC-TRF-018, DEC-TRF-019 — Cấu trúc Cột Fencing/TTL và Các Thành phần Thực thi Độc lập của Module Transfer

- **Ngày chốt:** 16–17/11/2026.
- **Quyết định:**
  1. **Cột TTL trên `idempotency_record`:** Lưu `ttl_t1_seconds`, `ttl_t1_config_version`, `ttl_terminal_seconds`, `ttl_terminal_config_version` để đảm bảo truy vết cấu hình TTL tại thời điểm tạo và thời điểm kết thúc giao dịch.
  2. **Cột Fencing & Recovery trên `transfer`:** Lưu `attempt_epoch`, `attempt_token`, `attempt_owner`, `attempt_lease_expires_at`, `attempt_state` (`NONE`, `CLAIMED`, `MANUAL`), `recovery_attempt_count`, `recovery_first_attempt_at`, `recovery_next_attempt_at`.
  3. **Gắn kết `step_up_challenge_id` tại `T1` (`DEC-TRF-017`):** Tại `T1`, chèn dòng `transfer` với `step_up_challenge_id = NULL`, chèn `idempotency_record` và `limit_reservation`, sau đó gọi `StepUpChallengePort.consumeVerified(challengeRef UUID)` để nhận về khóa nội bộ `challengeId (BIGINT)` và cập nhật `transfer.step_up_challenge_id` trước khi flush và ghi audit `TRANSFER_CREATED`.
  4. **Trạng thái tích hợp (`DEC-TRF-016/018/019`):** Toàn bộ logic `TransferCreateOrchestrator`, `TransferCreateService`, `TransferQueryService`, `TransferIntentCandidate`, `TransferSettlementCandidate`, `TransferRecoveryCandidate` đã được hiện thực và kiểm thử độc lập với port/contract v1 (`05. Modules contract.md` §13.4); kích hoạt component-scan vào runtime chính ngay khi hoàn tất ghép các provider `account`, `limit`, `ledger`, `audit`.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.4, `design/05. Modules contract.md` §8 & §13.4, `docs-backend/modules/transfer/design.md`.

---

## 5. Quyết định Module Ledger (`DEC-LED`)

### DEC-LED-001, DEC-LED-004 & DEC-LED-005 — Ghi Sổ cái Kép (`LedgerPosting`), Tính Bất biến và Kiểm chứng Đọc lại (`Native Read-Back`)

- **Ngày chốt:** 17/11/2026.
- **Quyết định:**
  1. **Hợp đồng `LedgerPosting.postDoubleEntry`:** Chạy bắt buộc trong giao dịch `T2` (`@Transactional(propagation = MANDATORY)`). Nhận `PostingCommand` bất biến, kiểm tra chặt chẽ `transferId > 0`, `sourceAccountId != destinationAccountId`, `amount > 0`, `balanceAfter >= 0`, `scale <= 2`, `currency = 'VND'`.
  2. **Ghi cặp bút toán và xác minh trực tiếp từ DB:** Chèn đúng 2 dòng vào `banking.ledger_entry` (`entry_seq = 1` là `DEBIT` tài khoản nguồn; `entry_seq = 2` là `CREDIT` tài khoản đích), thực hiện `flush()`, sau đó đọc lại bằng truy vấn native scalar projection (`ORDER BY entry_seq, entry_id`) để `PairVerifier` xác minh tính toàn vẹn của cả cặp trên cơ sở dữ liệu trước khi trả về `PostingRef(debitEntryId, creditEntryId)`.
  3. **Bảo vệ Append-Only:** Bảng `banking.ledger_entry` được bảo vệ bởi cả phân quyền PostgreSQL (`banking_app` chỉ có quyền `SELECT, INSERT`) lẫn trigger mức câu lệnh chặn mọi thao tác `UPDATE`, `DELETE`, `TRUNCATE` với mã lỗi SQLSTATE `55000`.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.4.2, `design/05. Modules contract.md` §9, `docs-backend/modules/ledger/design.md`.

### DEC-LED-002 — Phân quyền Sở hữu Truy vấn Lịch sử Giao dịch (`TransactionHistoryQuery`) cho Module Transfer

- **Ngày chốt:** 17/11/2026.
- **Quyết định:** Module `transfer` sở hữu truy vấn lịch sử giao dịch (`GET /api/v1/transactions` và `GET /api/v1/transactions/{reference}`) trên bảng `banking.transfer` (do các giao dịch `PENDING` hoặc `FAILED` không có bút toán trong `ledger_entry`), kết hợp thông tin quyền sở hữu và số tài khoản che dấu từ module `account`. Module `ledger` không tham gia vào đường đọc lịch sử giao dịch của khách hàng mà chỉ cung cấp `LedgerPosting.postDoubleEntry` và `LedgerQuery.findEvidence` cho đối soát/phục hồi.
- **Tài liệu ảnh hưởng:** `07. API contracts.md` §8.4, `design/05. Modules contract.md` §9 & §12.3.

### DEC-LED-003 — Đối soát Số dư Sổ cái dựa trên Số dư Đầu kỳ Có Nguồn gốc (`Opening Balance Provenance`)

- **Ngày chốt:** 17/11/2026.
- **Quyết định:** Module `account` cung cấp số dư mở sổ bất biến có nguồn gốc kiểm toán qua port `AccountReconciliationQuery.readOpeningBalance(accountId, systemContext)` (kể cả khi số dư đầu kỳ bằng `0`). Tiến trình đối soát sổ cái kiểm tra bất biến:
  $$\text{Opening Balance} + \sum \text{CREDIT} - \sum \text{DEBIT} = \text{Account Ledger Balance}$$
  trên cùng một snapshot đọc từ primary. Tuyệt đối không tự suy diễn số dư đầu kỳ từ số dư hiện tại hoặc tự động chỉnh sửa số dư khi phát hiện lệch.
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.3–§4.4, `design/05. Modules contract.md` §9.

---

## 6. Quyết định Module Audit, Fraud & Notification (`DEC-AUD` / `DEC-FRD` / `DEC-NOT`)

### DEC-AUD-001 — Kiến trúc Ghi Nhận Chuỗi Băm Kiểm toán (`AuditRecorder`) và Phân lớp Sự kiện `C1`–`C4`

- **Ngày chốt:** 16/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:**
  1. **Phân lớp sự kiện:**
     - `C1` (Giao dịch tài chính) và `C2` (Hành động bảo mật/quản trị trọng yếu, tạo cờ gian lận, xuất dữ liệu kiểm toán): Ghi đồng bộ trong cùng giao dịch nghiệp vụ (`Propagation.MANDATORY`, fail-closed). Nếu ghi `audit_log` thất bại, toàn bộ giao dịch nghiệp vụ bị rollback.
     - `C3` (Từ chối xác thực, khóa tài khoản, vi phạm phân quyền `SECURITY_ACCESS_DENIED`, xung đột `SECURITY_IDEMPOTENCY_CONFLICT`): Ghi trong giao dịch độc lập (`Propagation.REQUIRES_NEW`, best-effort). Nếu cơ sở dữ liệu lỗi, ghi ra `SecurityAuditFallbackLog` (JSON đã che dấu PII) + tăng bộ đếm metric cảnh báo mà không làm thay đổi quyết định từ chối.
     - `C4` (Sự kiện kỹ thuật/vận hành): Ghi vào structured application log và hệ thống metrics.
  2. **Chuỗi băm tuần tự (`Hash Chain v1`):** Sử dụng khóa tư vấn giao dịch `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))` ở pha cuối cùng của giao dịch trước khi commit. Chuẩn hoá payload theo RFC 8785 (JCS), giới hạn kích thước `<= 32 KiB`, và tính `record_hash = LowerHex(SHA-256(UTF8(canonical_json) || "|" || prev_hash))` (dòng đầu tiên dùng `prev_hash = "0" x 64`).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.6, `design/01. audit-traceability.md`, `design/05. Modules contract.md` §11.1, `docs-backend/modules/audit/README.md`.

### DEC-AUD-002 — Truy vấn, Xuất Bằng chứng Kiểm toán (`AUDIT_EXPORTED`) và Chính sách Lưu trữ 5 Năm

- **Ngày chốt:** 16/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:**
  1. Các truy vấn tìm kiếm/xem chi tiết `audit_log` (`GET /api/v1/audit`, `GET /api/v1/audit/{auditId}`) chỉ ghi nhận vào technical access log (không sinh dòng `audit_log` mới để tránh vòng lặp đệ quy).
  2. Thao tác xuất bằng chứng (`GET /api/v1/audit/export`, chỉ dành cho `AUDITOR`) bắt buộc ghi bền vững sự kiện `AUDIT_EXPORTED` (`C2`) kèm mã băm SHA-256 của tệp xuất trước khi trả dữ liệu về client.
  3. Thời hạn lưu trữ bằng chứng kiểm toán mặc định là **5 năm (`1825 ngày`)**; các bản ghi thuộc phạm vi `legal_hold` đang `ACTIVE` được miễn trừ tuyệt đối khỏi mọi tác vụ dọn dẹp/lưu trữ.
- **Tài liệu ảnh hưởng:** `03. SRS.md` §7.5, `07. API contracts.md` §8.6, `design/01. audit-traceability.md` §9 & §10.

### DEC-FRD-001 — Chuẩn hoá Mô hình Quy tắc Phát hiện Gian lận (`FraudRuleEvaluator`) và Thẩm định Cờ Gian lận

- **Ngày chốt:** 17/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:**
  1. Loại bỏ các trạng thái giao dịch giữ chờ duyệt (`PENDING_REVIEW`, `AWAITING_OTP`) và mã PIN 6 số khỏi thiết kế gian lận để đồng bộ tuyệt đối với mô hình 3 trạng thái giao dịch của MVP (`PENDING`, `COMPLETED`, `FAILED`).
  2. Bộ máy đánh giá gian lận chạy trong `T2` hỗ trợ 3 nhóm quy tắc cấu hình trong bảng `banking.fraud_rule_config` (khóa chính kép `(rule_code, version)`): `LARGE_VALUE` ($\ge 10.000.000\text{ VND}$), `HIGH_FREQUENCY` ($> 5\text{ lệnh trong } 600\text{ giây}$), và `UNUSUAL_PATTERN`.
  3. Lỗi kỹ thuật trong quá trình đánh giá gian lận được cô lập bằng JDBC savepoint để rollback phần dữ liệu trung gian của fraud và cho phép giao dịch tiếp tục (`fail-open` có kiểm soát kèm cảnh báo `ENGINE_ERROR`). Khi nhân viên `OPERATIONS` thẩm định cờ gian lận (`POST /api/v1/fraud-flags/{flagId}/review`), trạng thái cờ (`OPEN`, `UNDER_REVIEW`, `ALLOWED`, `BLOCKED`, `CLOSED`) và quyết định (`ALLOW`, `BLOCK`, `ESCALATE`, `CLOSE`) được cập nhật nguyên tử kèm bản ghi kiểm toán `FRAUD_FLAG_REVIEWED` (`C2`).
- **Tài liệu ảnh hưởng:** `04. Database design & analysis.md` §4.7, `07. API contracts.md` §8.7, `design/03. fraud-detection-rules.md`, `docs-backend/modules/fraud/README.md`.

### DEC-NOT-001 — Chuẩn hoá Hợp đồng Outbox Thông báo (`NotificationOutboxPort`) và `OtpGateway`

- **Ngày chốt:** 17/11/2026 (chuẩn hoá 18/11/2026).
- **Quyết định:**
  1. Mọi DTO trao đổi qua `notification.api.NotificationOutboxPort` và `notification.api.OtpGateway` phải đặt hoàn toàn trong `notification.api` (không lộ class thuộc `notification.domain` ra ngoài ranh giới module).
  2. Bảng `banking.notification` chỉ lưu trữ nội dung đã che dấu PII (`payload_masked`); worker gửi thông báo (`NotificationOutboxWorker`) hoạt động bất đồng bộ sau khi `T2` commit, đảm bảo lỗi từ nhà cung cấp SMS/Email bên ngoài không bao giờ làm đảo ngược kết quả giao dịch chuyển tiền.
- **Tài liệu ảnh hưởng:** `design/05. Modules contract.md` §11.3, `docs-backend/modules/notification/README.md`.
