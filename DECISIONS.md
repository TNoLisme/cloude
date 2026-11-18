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
# Quyết định dự án

## DEC-TRF-001 — Thành phần tổng ngày để đánh giá step-up

> Bổ sung ngày 16/11/2026: scope/ngày và cơ chế đã được chọn tại DEC-TRF-008; nội dung lịch sử bên dưới ghi phạm vi quyết định tại thời điểm ban đầu.

- **Ngày:** 15/11/2026.
- **Người quyết định:** Codex, theo chỉ đạo của người điều hành trong cuộc trao đổi BE-TRF-01 ngày 15/11/2026: “Tự đưa ra quyết định phù hợp dựa trên tài liệu hiện có và chỉnh các tài liệu liên quan”. Quyết định này không phải xác nhận review của BA, Product, Security hay các provider.
- **Phạm vi:** đối với điều kiện step-up dựa trên tổng chuyển tiền trong ngày, tính tổng dự kiến gồm số tiền chuyển thành công đã ghi nhận trong ngày + số tiền của các lệnh đã tạo và còn đang xử lý có hiệu lực + số tiền của lệnh đang xét; mỗi lệnh chỉ được tính một lần. Lệnh đã FAILED không tiếp tục đóng góp vào tổng dự kiến. Phạm vi khách hàng/tài khoản, ngày nghiệp vụ, ngưỡng số, toán tử so sánh và thay đổi cấu hình chưa được quyết định trong mục này.
- **Lý do:** tránh hai lệnh đồng thời cùng nhìn thấy tổng đã hoàn tất thấp rồi bỏ qua step-up khi tổng dự kiến vượt ngưỡng; có thể yêu cầu OTP bảo thủ nếu lệnh đang xử lý sau đó thất bại. Phù hợp với phương án cộng reservation tại `digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md` §§4.3, 6.1, đồng thời giữ yêu cầu `428` trước khi tạo transfer theo SRS AC-033 và `07` §6.
- **Các phương án:** chỉ tính lệnh đã thành công — không chọn do không tự giải quyết race của các lệnh đồng thời; tính cả lệnh đang xử lý — chọn cho phạm vi trên. Cơ chế guard/reservation hoặc queue là **đề xuất kỹ thuật**, chưa được quyết định, chưa chứng minh lock order/atomicity và chưa là phê duyệt triển khai.
- **Nguồn và phạm vi ảnh hưởng:** `03. SRS.md` AC-033; `07. API contracts.md` §6; `design/03. fraud-detection-rules.md` §§4.3, 6.1; `design/05. Modules contract.md` §10.1, MOD-OI-01; Transfer `design.md` và BE-TRF-01. Các ngưỡng/điều kiện khác nhau trong `design/03` và `design/04` không được mục này phân xử.
- **Theo dõi:** Identity, Limit, Transfer, Architect, BA/Product, Security và QA review ngữ nghĩa còn lại, contract/protocol, race `428`/idempotency, migration và evidence PostgreSQL/provider thật. MOD-OI-01 và release money path vẫn Blocked cho đến khi các phần này được review và chứng minh. Không coi quyết định này là sign-off thay các owner.

## DEC-TRF-002 — Ngưỡng tiền mặc định yêu cầu step-up

> Bổ sung ngày 16/11/2026: DEC-TRF-008 đã chọn scope customer và ngày nghiệp vụ; các phần khác ghi ngoài phạm vi dưới đây vẫn không tự được quyết định.

- **Ngày:** 15/11/2026.
- **Người quyết định:** Codex, theo chỉ đạo người điều hành trong cuộc trao đổi BE-TRF-01: “Về ngưỡng tiền, chọn ngưỡng phù hợp nhất và sửa các tài liệu liên quan.” Quyết định này không đại diện review hay sign-off của các owner.
- **Quyết định:** cấu hình mặc định MVP yêu cầu step-up khi **số tiền một lệnh ≥ 10.000.000 VND** hoặc **tổng chuyển dự kiến trong ngày > 20.000.000 VND**. Hai điều kiện độc lập; các trigger rủi ro khác vẫn được đánh giá. Thành phần tổng ngày theo DEC-TRF-001; ngưỡng step-up không phải hạn mức chuyển tiền và không tự chấp thuận lệnh vượt limit. Ngưỡng phải cấu hình được, có kiểm soát thay đổi; không tạo hai cấu hình độc lập cho cùng trigger.
- **Biên:** `9.999.999,99` VND không kích hoạt điều kiện đơn lệnh; `10.000.000,00` kích hoạt. Tổng ngày `20.000.000,00` không kích hoạt điều kiện tổng ngày; `20.000.000,01` kích hoạt. Các kỳ vọng này chỉ xét điều kiện tương ứng, giả định các trigger khác không kích hoạt và giao dịch hợp lệ theo policy tiền tệ/hạn mức.
- **Lý do và phương án:** chọn phương án đã được Security ghi `OI-SEC-02` Resolved tại `design/04` §4.4/§13, khớp default step-up hiện có và tránh thay đổi ngưỡng bảo mật đã mô tả; `design/03` từng nêu OTP trên 5 triệu, nay không áp dụng làm trigger OTP riêng. Cả hai là thiết kế mô phỏng; SRS/API yêu cầu ngưỡng cấu hình, không ấn định con số. Không tuyên bố đây là quy định ngân hàng thực tế hay thay thế đánh giá rủi ro.
- **Ngoài phạm vi:** scope customer/account, ngày nghiệp vụ/timezone, PIN, hạn mức tối thiểu/tối đa từng giao dịch, daily cap, phương thức xác thực khác, rule fraud/velocity, và protocol xử lý đồng thời không được quyết định tại đây. Xác thực và lỗi vẫn theo `07` §6; việc triển khai, versioning cấu hình và phối hợp provider phải review/test.
- **Nguồn và ảnh hưởng:** `03. SRS.md` §2.13.6, AC-033; `07. API contracts.md` §6; `design/03. fraud-detection-rules.md` §§4–6, 11; `design/04. security.md` §§4.4, 13; `design/05. Modules contract.md` §10.1; `docs-backend/deployment/configuration.md` và Transfer BE-TRF-01. Không tự đóng MOD-OI-01: guard/reservation, race `428`, scope/ngày và evidence provider/PostgreSQL vẫn cần được chứng minh.
## DEC-LIM-001 — Phạm vi GET limits khi không chọn account

- **Ngày:** 17/11/2026. **Người quyết định:** người điều hành trong phiên triển khai E3/LIM-06.
- **Quyết định:** `GET /api/v1/limits` không có `accountId` chỉ trả cấu hình CUSTOMER của customer trong phiên. Khi có `accountId` thuộc customer, trả cấu hình CUSTOMER và ACCOUNT tương ứng đang hiệu lực; effective tính trên các scope được trả.
- **Lý do:** hạn mức từng account chỉ có ý nghĩa khi biết account được chọn; không trộn account khác vào một effective chung.
- **Nguồn và ảnh hưởng:** `07` §7.2.13/§8.5.1; OpenAPI `/limits`; E3/LIM-06. Không quyết định policy enforcement hay timezone nghiệm thu.

## AUD-01/AUD-02 — Quyết định triển khai độc lập

These entries record the current implementation and proposals for coordination. They do not imply owner approval.

## Scope

- Independent AUD-01/AUD-02 component work may proceed without owner approval.
- Public API, caller integration, RBAC, export, C3, checkpoint and external anchor remain blocked until owner contracts are reviewed.

## Decisions used for independent component work

- `AuditRecorder` accepts only C1/C2 events through `Propagation.MANDATORY`; C3/C4 are rejected before database locking.
- JDBC/native SQL remains in `audit.adapter.out` for hash-chain locking, JSONB projection, fixed audit projections and bounded verification scans. Spring Data JPA is not forced onto append-only or PostgreSQL-specific paths.
- `AuditQueryPort` and `AuditRecordWriterPort` are outbound persistence ports in `audit.application.port`. They are not public cross-module APIs.
- `AuditModuleApi` is not created or exposed before owner review of event DTOs, actor context, error mapping and transaction semantics.
- Audit read projection fails closed. Raw JSON does not leave the internal persistence boundary; public API/RBAC is not implemented in this independent scope.
- Writer canonical payload limit remains 32 KiB. Reader allows bounded PostgreSQL `jsonb::text` representation expansion, then enforces compact JSON <= 32 KiB before projection. This does not alter hash input or stored evidence.
- Audit hash v1 remains a proposal. Current tests characterize implementation behavior; they are not production protocol approval or cross-language golden-vector sign-off.
- Testcontainers PostgreSQL evidence is disposable component evidence. `ddl-auto=none` in isolated Audit tests does not prove full production `ddl-auto=validate` readiness.
- Missing provider dependencies are not replaced with production stubs. Fake data is allowed only in tests and is not used to claim RBAC or caller integration.

## Open owner decisions

- Hash v1 formula, field set, delimiter and golden vectors: DB/Common and Architecture.
- `AuditModuleApi`, actor role/scope and principal source: Identity, Architecture and business callers.
- C3 standalone audit and security fallback: Identity, Security and Architecture.
- Export byte/resource/CSV policy: DB, Security and Architecture.
- Migration role/FK coordination, scheduled verification, checkpoint and external anchor: DB, Architecture and Operations.
- Transfer/Ledger/Fraud/Admin caller integration fixtures: module owners.

## DEC-ID-001 — Seed role/permission tối thiểu đặt trong migration V2

- **Ngày:** 16/11/2026.
- **Người quyết định:** Codex (BE-ID-IMP-01), theo thứ bậc thẩm quyền nguồn `docs-backend/00. architecture & conventions.md` §1.5: `docs-common/01–11` > `design/` > `docs-backend/`.
- **Quyết định:** seed 4 role (`CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR`), 15 permission và 22 dòng `role_permission` theo `docs-common/04. Database design & analysis.md` Appendix A.2 được đặt trong `V2__create_identity_and_rbac_tables.sql`. `CHECKER` không seed (privilege gắn user, 04 §4.2.8).
- **Xung đột nguồn (ghi nhận minh bạch):** `docs-backend/00. architecture & conventions.md` §8.5 liệt kê "roles, permissions" trong `db/seed/R__synthetic_demo_seed.sql` (chỉ local/demo); `docs-common/04` (thẩm quyền cao hơn) định nghĩa seed tối thiểu MVP và cho phép "seed/migration có kiểm soát" (§4.2.8). Theo thẩm quyền, chọn seed production trong V2; `R__` demo seed vẫn có thể chứa dữ liệu demo bổ sung sau này. Cần Security/DB owner rà lại khi review.
- **Các phương án:** (a) seed trong V2 — chọn, vì RBAC vô dụng khi thiếu role và 04 cho phép; (b) chỉ seed qua `R__` — từ chối vì `R__` chỉ chạy local/demo, production không có role; (c) seed bằng job runtime — từ chối, thêm cơ chế ngoài quy ước.
- **Nguồn và ảnh hưởng:** `04` §4.2.8, Appendix A.2; `00` §8.5; `V2__create_identity_and_rbac_tables.sql`; BE-ID-IMP-01. Không đóng OD nào khác.

## DEC-ID-002 — Cột `session.csrf_token_hash` bổ sung vào migration V2

- **Ngày:** 16/11/2026.
- **Người quyết định:** Codex (BE-ID-IMP-02), theo thứ bậc thẩm quyền `docs-backend/00. architecture & conventions.md` §1.5.
- **Quyết định:** thêm `csrf_token_hash CHAR(64) NOT NULL` vào `banking.session` trong `V2__create_identity_and_rbac_tables.sql` (file chưa commit của IMP-01 trên branch làm việc `datht-backend-rework`).
- **Xung đột nguồn:** `docs-common/04. Database design & analysis.md` §4.2.3 KHÔNG định nghĩa cột csrf cho `session`; nhưng `docs-common/design/04. security.md` §4.3/§6.2 yêu cầu `sessions.csrf_token_hash`, và contract `SessionAuthenticator.Session` (common) bắt buộc `csrfTokenHash` `[0-9a-f]{64}` + `SessionCsrfTokens` so khớp SHA-256(header) với hash lưu trong session. Không có nơi lưu hash thì cơ chế CSRF session-bound đã duyệt không thể triển khai, và `AuthResponse` không thể trả `csrfToken` theo `07` §7.2.3.
- **Các phương án:** (a) thêm cột vào V2 — chọn, vì V2 chưa commit, tránh migration stub; (b) ALTER trong migration mới — từ chối vì V2 chưa được version hóa công khai; (c) không lưu, CSRF token suy diễn từ session token — từ chối vì lộ token phiên qua header.
- **Nguồn và ảnh hưởng:** `04` §4.2.3 vs `design/04` §4.3/§6.2 vs common `SessionAuthenticator`/`SessionCsrfTokens`; `07` §7.2.3; `V2__create_identity_and_rbac_tables.sql`; BE-ID-IMP-02. **Chờ DB owner + Security rà lại** khi review; nếu 04 §4.2.3 được giữ nguyên thì phải thiết kế lại CSRF contract ở common (ngoài scope IMP-02).

## DEC-ID-003 — BE-ID-IMP-02: phạm vi triển khai và các phần BLOCKED/mock theo module phụ thuộc

- **Ngày:** 16/11/2026.
- **Người quyết định:** Codex (BE-ID-IMP-02), theo AGENTS.md (mock test theo tài liệu giao tiếp khi module phụ thuộc chưa có; ghi phương án vào DECISIONS.md).
- **Đã triển khai:** password login `POST /api/v1/auth/login` (API-AUTH-01), Argon2id (m=65536, t=3, p=4, salt 16B, hash 32B — design/04 §4.1, BouncyCastle), dummy hash unknown-user, anti-enumeration (401 generic), lockout atomic 5 lần/10 phút (cấu hình `app.security.lockout`), logout `POST /api/v1/auth/logout` (API-AUTH-06), SPI `SessionAuthenticator` (validate + idle renewal + authority snapshot), audit port C2/C3 với 4 action đã duyệt, `auth_attempt` DB rows.
- **BLOCKED / mock có kiểm soát:**
  1. **Audit persistence (mock theo design/01 §5.2):** module audit chưa triển khai `AuditModuleApi` (design/01 §5.2). Identity định nghĩa port `AuthAuditPort` (recordInTransaction C2 / recordStandalone C3) và adapter tạm `SecurityLogAuditAdapter` (metric + security log). Khi module audit có (BE-ID-IMP-13), adapter phải thay bằng write `audit_log` trong TX, C2 fail → rollback (fail-closed). Hiện adapter tạm không thể fail → C2 fail-closed chưa test được thật.
  2. **OTP thiết bị mới (UC-003, 202 ChallengeRequiredResponse):** BLOCKED — phụ thuộc BE-ID-IMP-05/06/08 + module notification (MOD-OI-07). Login hiện luôn tạo session theo luồng thiết bị tin cậy; `deviceTrusted=false` trong AuthResponse. `auth_attempt` cho unknown user không ghi được vì FK `user_id NOT NULL` (04 §4.2.6) — dùng audit C3 anonymous (`anon:<sha256(username)>` theo design/01 §A.3).
  3. **`customerId` trong AuthResponse = `null`:** BLOCKED — `customer.api.CustomerQuery.findByUserId` (design/05 §6) chưa có.
  4. **BCrypt compat (DelegatingPasswordEncoder, design/04 §4.1):** không triển khai — chưa có dữ liệu hash legacy nào tồn tại; sẽ bổ sung khi có FR đổi password (OD-ID-09).
  5. **"3 lần khóa/24h → khóa tài khoản" (BRL-003):** chưa triển khai — giá trị là "đề xuất" chờ OI-001/OI-006, không có cấu hình trong `AppProperties`; chỉ triển khai đúng boundary "lần thứ 5 → khóa" như nhiệm vụ yêu cầu.
  6. **Rate limit per-username (07 §2.8):** IP quota đã có ở common filter; username quota thuộc BE-ID-IMP-13.
  7. **DB failure → 503:** lỗi mở TX/connection (JpaSystemException, CannotCreateTransactionException, DataAccessResourceFailureException) được map 503 `SERVICE_UNAVAILABLE` tại identity service theo 07 §8.1.1/§8.1.6; phần còn lại theo `DatabaseErrorMapper` của common. Cần common owner rà: hiện common map `CannotCreateTransactionException` → 500 INTERNAL_ERROR.
- **Nguồn và ảnh hưởng:** `07` §7.2.3/§8.1.1/§8.1.6; `design/01` §5.2–§5.3; `design/02` §9.1; `design/04` §4.1–§4.3; `design/05` §5.1/§6; BE-ID-IMP-02..13. Không tự đóng OD-ID nào ngoài các mục BLOCKED nêu trên.

## DEC-ID-004 — BE-ID-IMP-04: CSRF rotation cho `GET /api/v1/auth/session` và phạm vi RBAC/IDOR của Identity

- **Ngày:** 16/11/2026.
- **Người quyết định:** Codex (BE-ID-IMP-04), theo thứ bậc thẩm quyền nguồn `docs-backend/00. architecture & conventions.md` §1.5.
- **Quyết định 1 — CSRF rotation:** triển khai `GET /api/v1/auth/session` (API-AUTH-07, `07` §8.1.7 "cấp lại csrfToken cho SPA sau refresh") bằng cách **cấp token raw MỚI và cập nhật `session.csrf_token_hash` trong MỘT statement UPDATE điều kiện** (`status='ACTIVE'` và chưa hết hạn idle/absolute; 0 dòng → `401 AUTH_SESSION_EXPIRED`, không hồi sinh phiên). Vì DB chỉ lưu SHA-256 hash (DEC-ID-002, design/04 §4.3) nên server không thể cấp lại token cũ; rotation là cách duy nhất tuân thủ hợp đồng không phá cơ chế hash-only đã duyệt.
- **Trade-off rotation:** token cũ của một phiên chết ngay sau `/auth/session` → nếu SPA gọi nhiều tab song song thì tab có token cũ sẽ bị 403 CSRF cho mutation kế tiếp và phải đọc lại từ response. MVP chấp nhận (SPA gọi một lần khi boot); nếu owner yêu cầu multi-tab, cần review lại theo `07` §8.1.7.
- **Quyết định 2 — RBAC:** không tạo role/permission mới; enforcement dùng đúng `authorizeHttpRequests`/`@PreAuthorize` của common (`SecurityFilterChainConfig`) với authority snapshot per-request từ `JdbcAuthorityReader` (RBAC seed V2 theo DEC-ID-001). Thu hồi role trong DB có hiệu lực từ request kế tiếp (không cần revoke session; revoke session theo role change thuộc BE-ID-IMP-11).
- **Quyết định 3 — IDOR scope:** Identity API hiện chỉ có `login`/`logout`/`session` — không có endpoint nào nhận resource ID của actor khác; object-level authorization chứng minh bằng ràng buộc token→session→CSRF proof (logout/thao tác chỉ đụng phiên của token; proof phiên khác bị 403). Đối tượng `session`, `device`, `auth_challenge` của actor khác chưa có API đọc/sửa/xóa nên không có đường IDOR để đóng ở task này.
- **Nguồn và ảnh hưởng:** `07` §8.1.7; `design/04` §4.3; `design/05` §5.1/§12.2; `SessionRepository.rotateCsrfToken`; `SessionViewService`; `AuthController`; BE-ID-IMP-04/12. **Chờ Security owner rà lại rotation semantics khi review.**

## DEC-ID-005 — BE-ID-IMP-05: verify bằng conditional UPDATE thay SELECT…FOR UPDATE; phạm vi mock/BLOCKED theo module phụ thuộc

- **Ngày:** 16/11/2026.
- **Người quyết định:** Codex (BE-ID-IMP-05), theo thứ bậc thẩm quyền nguồn `docs-backend/00. architecture & conventions.md` §1.5 và AGENTS.md (mock đúng contract khi dependency chưa có; ghi quyết định vào DECISIONS.md).
- **Quyết định 1 — verify không giữ `SELECT…FOR UPDATE` xuyên qua phép so khớp OTP:** `design/02` §5.1 mô tả verify là "lock challenge row → kiểm tra → tăng attempt → set VERIFIED" trong một T0. Triển khai thực tế phát hiện hai vấn đề khi làm đúng theo mô tả: (a) nhánh rejection (OTP sai/hết hạn) ném business exception trong cùng T0 khiến **attempt counter bị rollback** — phá invariant "tăng attempt atomically, chốt LOCKED tại ngưỡng" (bắt được bằng test integration thật, trước khi vá 5 thread sai OTP đồng thời đều 401 và không bao giờ LOCKED); (b) giữ row lock xuyên qua Argon2id ~100ms serialize các verify không cần thiết. Do đó verify pre-check bằng read thường, mọi mutation là **conditional UPDATE nguyên tử** (`recordFailedVerifyAttempt`/`markVerified`/`expireIfDue` — design/02 §9.1, pattern được task BE-ID-IMP-05 §7 cho phép "conditional database update"); `markVerified` + audit C2 vẫn chung MỘT OWN TX (fail-closed design/01 §5.3). Row lock vẫn implicit trong từng UPDATE; race được xử lý bằng re-read + map fail-closed.
- **Quyết định 2 — repo `@Transactional` (REQUIRED) trên mọi method:** Hikari chạy `auto-commit: false` (application.yml) nên statement ngoài transaction không bao giờ commit (bắt được qua probe: INSERT/UPDATE tự do biến mất). Consume được gọi trong T1 caller → JOIN; bump/expire trong verify path không outer TX → mở TX ngắn và COMMIT đúng nghĩa rejection.
- **Quyết định 3 — verify cho phép từ `CREATED` lẫn `SENT`:** `design/05` §5.2 không gate verify vào SENT; SENT chỉ là marker delivery (dispatch sau commit thuộc BE-ID-IMP-06). Trước khi IMP-06 có gateway, challenge đứng ở `CREATED` — gate verify vào SENT sẽ khiến OTP không bao giờ verify được.
- **Quyết định 4 — consume không phát audit event riêng:** catalog design/01 §5.3 chỉ có `AUTH_CHALLENGE_CREATED/RESENT/LOCKED` + `AUTH_STEP_UP_VERIFIED/FAILED`; bằng chứng consume là audit T1 nghiệp vụ (ví dụ `TRANSFER_CREATED` kèm `step_up_challenge_id`). Không tự chế action mới.
- **Quyết định 5 — kênh OTP mặc định `SMS` ở MVP:** CHECK `method='OTP' ⇒ channel NOT NULL`; policy kênh/resolve destination/maskedDestination thuộc BE-ID-IMP-06 qua notification. Không thêm cột/config mới.
- **BLOCKED / mock có kiểm soát (theo AGENTS.md):** (1) OTP dispatch gateway/resend/timeout/recovery — module notification chưa có, thuộc BE-ID-IMP-06 (MOD-OI-07); (2) audit persistence thật (`audit_log` trong TX, C2 fail-closed thật) — module audit chưa có, `SecurityLogAuditAdapter` mock theo DEC-ID-003, thuộc BE-ID-IMP-13; (3) HTTP endpoints verify (API-AUTH-04/05) + DTO — thuộc BE-ID-IMP-12; (4) flow login thiết bị mới 202 + session sau verify — thuộc BE-ID-IMP-08 (phần OTP của AC-003 đã test trực tiếp T04–T07); (5) transfer 428/T1 thật — thuộc BE-ID-IMP-07 (hợp đồng port + T1 consume + race đã chứng minh bằng test gọi trực tiếp port: T11/T17/T18/T19).
- **Nguồn và ảnh hưởng:** `04` §4.2.5; `design/02` §5.1–§5.2, §9.1; `design/05` §3.2/§5.2; `design/01` §5.3; `07` §3.7/§6/§6.6; BE-ID-DOC-01 OD-ID-06; `OtpChallengeService`/`JdbcChallengeRepository`/`ChallengeState`; BE-ID-IMP-05..13. Không tự đóng OD nào ngoài các mục BLOCKED nêu trên.

## DEC-ID-006 — BE-ID-IMP-06: delivery sau commit, fail-closed; MOD-OI-07 chưa được đóng

- **Ngày:** 16/11/2026. **Branch/baseline:** `datht-backend-rework`, `origin/dev2@56210cc`; giữ nguyên phần IMP-01..05 chưa commit. Đây là quyết định triển khai phần độc lập, không phải phê duyệt thiết kế secret handoff hay sign-off thay Notification/Security/Product.
- **Nguồn:** `design/05` §5.2, §11.3, §13.1, MOD-OI-07; `07` §8.1.8; `04` §4.2.5; BE-ID-IMP-05 và OD-ID-07.
- **Ranh giới:** INSERT challenge + C2 audit trong một OWN `REQUIRES_NEW` transaction; chỉ sau khi commit mới gọi gateway trong `NOT_SUPPORTED` (suspend cả transaction caller). OTP chỉ ở `char[]` mượn đồng bộ, wipe ở `finally`; chỉ verifier Argon2id theo implementation IMP-05 được ghi DB. Không thêm ciphertext, queue, outbox secret hoặc cơ chế suy diễn OTP từ DB.
- **Gateway phụ thuộc chưa có:** thêm `notification.api.OtpGateway` theo `sendOtp` §11.3; routing handle `recipientUserId` thay destination PII khi chưa có Customer/Notification contact resolver. Adapter thật phải resolve contact tin cậy, áp connect/response deadline, không giữ/log/persist OTP, dùng challenge ref làm dedupe key nếu provider hỗ trợ. Không có adapter → `503 OTP_DISPATCH_FAILED`; fake chỉ nằm trong test.
- **Fail-closed:** chỉ provider ACCEPTED được conditional UPDATE `CREATED→SENT` với đúng verifier và chưa hết hạn. Verify OTP yêu cầu `SENT` ở cả service và SQL; CREATED (chưa gửi/thất bại/kết quả mơ hồ/chưa ack local) không thể VERIFIED. Điều này thay DEC-ID-005 quyết định 3 cho OTP; domain chung vẫn giữ transition CREATED cho phương thức khác.
- **Idempotency:** unique active step-up hiện có ngăn duplicate logical TRANSFER_STEP_UP; replay SENT/VERIFIED trả cùng reference, không phát lại OTP/audit CREATED. Replay CREATED trả 503, không dùng OTP vừa sinh cho verifier cũ. Không trả challenge của user/purpose khác nếu key/hash trùng. Chưa có đủ context/unique cho login và SENSITIVE_ACTION để tuyên bố toàn bộ issuance idempotent.
- **Recovery độc lập:** expiry sweep `SKIP LOCKED`, tối đa 100 row/TX, chỉ active chưa consumed và đã hết hạn; không dispatch/reactivate challenge. Lịch vận hành `app.identity.otp-expiry-sweep-delay` (ISO-8601 duration, mặc định `PT1M`), không dùng `security.otp.recovery-ttl-seconds` (TTL OTP recovery) làm lịch job.
- **BLOCKED bắt buộc:** MOD-OI-07 không cho phép giả định hash có thể khôi phục OTP; hiện chưa có thiết kế được duyệt cho protected durable handoff, lease/dedupe/attempt/backoff/dead-letter và invalidate sau ambiguous acceptance. Không tự dùng khóa AES PII để mã hóa OTP. Crash trước send hoặc sau acceptance/trước ack chỉ được giữ fail-closed rồi expire; **không** có crash-safe redelivery và không tuyên bố exactly-once.
- **Resend BLOCKED:** API `07` quy định rotate OTP, reset TTL, giữ verify attempts, cooldown/max 3 từ config; schema `04`/V2 không có resend counter, last-resend hay generation/fencing metadata và MOD-OI-07 chưa chốt tương tác với in-flight send. Không giả đếm trong RAM, không dùng verify attempts làm resend counter, không tự sửa V2 hay thêm schema chưa duyệt. Cần owner chốt forward migration + protocol trước khi triển khai API-AUTH-08 end-to-end.
- **Session:** issue/delivery/expiry không truy cập session repository, không phát token. Login-new-device 202→verify→atomic session activation vẫn thiếu IMP-08; password login hiện tại chưa có device-trust gate, không coi test OTP “0 session” là chứng minh toàn bộ AC-003.
- **Trạng thái:** BE-ID-IMP-06 **PARTIAL**, checklist giữ `[ ]`. Evidence và ma trận chưa kiểm thử nằm trong `docs-backend/modules/identity/BE-ID-IMP-06.md`.

## DEC-ID-007 — BE-ID-IMP-07: hardening Identity, tích hợp Transfer vẫn BLOCKED

- **Ngày/baseline:** 16/11/2026; đã fetch `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a`; triển khai trên `datht-backend-rework`, giữ nguyên thay đổi IMP-01..06 chưa commit. Không sửa `dev2`/`main`, không commit/push.
- **Bằng chứng dependency:** baseline lẫn working tree không có controller/service/API/migration nghiệp vụ Transfer, Limit, Account, Ledger; các package chỉ có `.gitkeep`. `modules/limit/README.md` baseline chỉ có heading; `modules/transfer/design.md` §1.3 xác nhận chưa triển khai. Không có execution/idempotency thật để ghép `StepUpChallengePort`.
- **Phạm vi có thể thực hiện:** tái sử dụng duy nhất `StepUpChallengePort`/`OtpChallengeService`/`JdbcChallengeRepository`; harden binding và expiration tại persistence boundary, thêm endpoint đã đặc tả `POST /api/v1/auth/step-up/verify` (`07` §6.4/§8.1.5), kiểm thử PostgreSQL và HTTP. Consumer fixture/gateway giả chỉ trong test theo `AGENTS.md` và `design/05` §13.1; không giả lập production Transfer để đóng task.
- **Xung đột và thẩm quyền:** overview `modules/_overview/01-identity-auth.md` §StepUpChallengePort ghi `status='CONSUMED'`; `04. Database design & analysis.md` §4.2.5 (thẩm quyền cao hơn) không có state này, dùng `consumed_at` trên VERIFIED. Giữ schema/enum của `04`. `design/04. security.md` §CT-API-010 ghi `403 STEP_UP_INVALID`, trong khi `07. API contracts.md` §6.6 quy định binding mismatch `409 STEP_UP_CHALLENGE_MISMATCH`, foreign user `404 AUTH_CHALLENGE_NOT_FOUND`; dùng catalog `07`, không thêm mã lỗi mới.
- **Purpose/method:** endpoint step-up không nhận user/purpose từ client. User lấy từ session thật; `VerifyCommand.expectedPurpose=null` chỉ chọn stored `TRANSFER_STEP_UP` hoặc `SENSITIVE_ACTION`, không cho LOGIN_NEW_DEVICE; caller login phải chỉ định purpose riêng. OTP verifier từ chối stored method khác OTP trước mutation; không triển khai FIDO/passcode giả.
- **Không tự quyết policy:** MOD-OI-01/09 tại `design/05` §10.1/§14 vẫn OPEN; đề bài gọi MOD-OT-01/09 nhưng không tìm thấy ID đó trong tài liệu baseline. Guard/reservation và ngữ nghĩa retry `04` §7.7.3 so với `07` §5.4/§8.3 còn chờ owner; không hard-code ngưỡng đơn lệnh rồi bỏ qua tổng dự kiến, không ép mọi transfer step-up, không tự duyệt schema/protocol.
- **Điều kiện gỡ BLOCKED:** Transfer/Limit cung cấp consumer và protocol được duyệt, Account/Ledger/Audit cung cấp API transaction thật; Notification có adapter/resolver thật và MOD-OI-07 được xử lý. Sau đó chạy Scenario C/E trên execution/idempotency thật, kiểm `428` không có business writes và audit rollback xuyên module. Test port/fixture không thay bằng chứng này; checklist IMP-07 giữ `[ ]`.
- **Bàn giao được người điều hành chấp nhận:** sau khi nhận báo cáo 289 tests PASS cho phần Identity và integration còn thiếu dependency, người điều hành chọn `blocked_handoff`. Kết thúc phạm vi thực thi hiện tại ở trạng thái **BLOCKED**, không đánh dấu BE-ID-IMP-07 DONE và không coi đây là phê duyệt protocol MOD-OI-01/09.

## DEC-ID-008 — BE-ID-IMP-08: MFA cho mọi password login, không metadata bypass

- **Ngày/baseline:** 16/11/2026; đã fetch `origin/dev2@56210cc`; giữ nguyên thay đổi IMP-01..07 trên `datht-backend-rework`.
- **Xung đột cần người điều hành chốt:** `07. API contracts.md` §3.8 cho phép `deviceRef` + `deviceFingerprint` khớp trusted device bỏ OTP; cả hai là client-controlled metadata, không có server-verifiable device secret trong API/schema `04` §4.2.4. Task IMP-08 yêu cầu copied metadata không đủ bypass. Chính sách tự trust và trust expiration cũng chưa định nghĩa.
- **Quyết định trực tiếp của người điều hành:** chọn `mfa_every_login`: luôn MFA cho password login; không cấp persistent trust, không cho metadata bypass. Không phê duyệt extension cookie DB_DEVICE/device-secret migration/API revoke được nêu làm phương án thay thế.
- **Triển khai theo quyết định:** password hợp lệ chỉ tạo challenge LOGIN_NEW_DEVICE và trả 202; OTP verify đúng mới mark VERIFIED + consume + tạo session + audit trong một transaction. Device metadata chỉ dùng nhận diện/binding, không phải credential; deviceTrusted=false và không tự chuyển trusted=true. User/device phải lấy từ challenge đã persist, không nhận từ body verify.
- **Schema:** dùng `device`, `auth_challenge.device_id`, `session.device_id` hiện có; không sửa migration V2, không thêm duplicate challenge/trust table hoặc enum mới. Revoked device không được hồi sinh hoặc cấp session gắn vào nó.
- **Phạm vi chưa hoàn tất có chủ đích:** trusted-device bypass, registration persistent trust/expiry/re-registration và public lifecycle API giữ PARTIAL theo lựa chọn trên; không bịa protocol tương thích giả với frontend. Gateway/provider/contact resolver và Audit persistence thật vẫn là dependency thiếu của IMP-06/13; fake chỉ trong test. Checklist IMP-08 không đánh DONE toàn bộ khi các phần này chưa hoàn tất.

## DEC-ID-009 — BE-ID-IMP-09: giữ contract hiện hành, không suy diễn device proof

- **Ngày/branch:** 16/11/2026, `datht-backend-rework`; giữ nguyên thay đổi chưa commit IMP-01..08.
- **Quyết định trực tiếp của người điều hành:** chọn `preserve_contracts` sau khi được thông báo DEC-ID-008 không cho persistent trust và chưa có contract enrollment/proof/expiry hoặc endpoint đổi password. Phạm vi được duyệt: endpoint Passcode fail-closed, application service cho password history có transaction và kiểm thử; task tổng **PARTIAL/BLOCKED**, không tự mở rộng API/lifecycle/trust duration.
- **Passcode:** API-AUTH-02 hiện chỉ nhận username/passcode/deviceRef/fingerprint. Metadata hoặc row `trusted=true` không chứng minh thiết bị; mọi request hợp lệ bị `403 AUTH_DEVICE_NOT_TRUSTED` trước khi verify passcode/cấp session. Không triển khai success giả, không biến session hiện tại thành device proof và không tự enroll/trust.
- **Password history:** tái sử dụng V2/`04` §4.2.7: kiểm current hash và 3 hash password cũ mới nhất, lưu hash cũ khi thay đổi, không purge khi chưa có retention được duyệt. `created_at DESC, history_id DESC` phân xử timestamp trùng. Service nội bộ JOIN transaction caller, lock `app_user` trước khi đọc history/update; caller tương lai phải xác thực actor, áp policy PII/step-up và C2 audit cùng transaction. Không tạo password-change endpoint hoặc action audit chưa có catalog.
- **Policy:** dùng yêu cầu đã ghi BRL-006 (≥8 ký tự, hoa/thường/số/ký tự đặc biệt) và giới hạn 128 của credential login hiện có; không suy diễn dữ liệu cá nhân từ Customer chưa có. OI-001/OI-006, policy PII, public password-change API/audit action và persistent device proof/lifecycle vẫn OPEN.
- **Dependency:** IMP-01 schema và IMP-03 session có implementation; IMP-08 chỉ PARTIAL (`mfa_every_login`). Audit persistence và Notification provider thật vẫn thiếu; không coi fixture test là provider production.
- **Đính chính sau audit BE-ID-IMP-09 (16/11/2026):** `preserve_contracts` chỉ duyệt phạm vi triển khai PARTIAL, không phê duyệt thay đổi success contract API-AUTH-02 hoặc miễn trừ stack. Đã gỡ riêng ghi chú IMP-09 khỏi `07` §8.1.2 và `design/05` §5.5; giữ nguyên thay đổi IMP-06..08 có sẵn. CRUD history phải theo Spring Data JPA (`05` §5.2.2), port theo BE-CON-39, ngưỡng theo BE-CON-64; code đã chuyển sang JPA/configuration thay vì sửa nguồn để hợp thức hóa JDBC CRUD. Baseline và yêu cầu positive login vẫn giữ nguyên; evidence full sau sửa ghi trong báo cáo IMP-09, không suy diễn PASS từ scope approval.

## DEC-ID-010 — BE-ID-IMP-10: extension verification FIDO được người điều hành duyệt

- **Ngày/baseline:** 16/11/2026; fetch `origin/dev2` thành công, vẫn `56210cc80ad742ddad9139dc1a08fe9ff524465a`, trên branch `datht-backend-rework`. Giữ nguyên toàn bộ thay đổi của các task trước.
- **Thiếu nguồn:** `04` chỉ lưu credential ID, chưa có public key/userHandle/signCount/nonce; OI-API-13 chưa chốt provider. `07` §8.1.3 đã chốt begin/finish, fallback password/passcode và purpose `LOGIN_NEW_DEVICE`. AC-002 trong SRS bao gồm session/lockout/fallback; checklist IMP-10 bổ sung RP ID/origin/signature/counter/replay, không thay AC nguồn.
- **Quyết định trực tiếp:** người điều hành chọn `approve_verification_extension`: migration forward-only trong Identity bổ sung COSE public key, userHandle, signCount trên device và nonce trên auth_challenge; dùng Yubico, UV required; counter anomaly từ chối, `0→0` hợp lệ. Không tự revoke thiết bị khi anomaly; vẫn áp attempt/lockout và audit. Không sửa V2.
- **Boundary:** credential trusted được provision ngoài task; không thêm enrollment, trust duration, metadata bypass hoặc tự trust. Không thay API-AUTH-03 request/response; dùng lại session/OTP/audit ports. Public key thiếu hoặc credential revoked không được xác thực.
- **Fallback:** bắt đầu lại password + OTP theo DEC-ID-008; không chuyển kết quả FIDO lỗi thành MFA success, không tự cấp session/OTP từ username hoặc credential ID. Passcode positive vẫn BLOCKED theo DEC-ID-009. Cancellation/unavailable phía browser dùng password flow, không invent endpoint cancellation/recovery.
- **Production:** feature opt-in, RP ID và HTTPS origin allowlist tường minh; HTTP localhost chỉ local/test. Không wildcard/subdomain/port relaxation. Enrollment và Audit/Notification provider thật vẫn là dependency ngoài task; evidence phải phân biệt verification runtime với lifecycle và integration thật.
- **Evidence sau triển khai:** full `./gradlew build --rerun-tasks --console=plain` SUCCESSFUL (5m09s), 46 classes / 666 tests PASS, 0 failure/error/skip; trong đó Identity 453, FIDO mới 165 (66 crypto + 45 config + 21 PostgreSQL + 33 HTTP). RP ID/origin/signature/counter/replay VERIFIED trong runtime/fixtures; task tổng giữ PARTIAL do enrollment/Audit/Notification production chưa có. Báo cáo `digitalbanking-docs/docs-backend/modules/identity/BE-ID-IMP-10.md`, checklist `[ ]`.

## DEC-ID-011 — BE-ID-IMP-11: version assignment và revoke được người điều hành duyệt

- **Ngày/branch:** 16/11/2026; giữ `datht-backend-rework`, HEAD và `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a` sau fetch. Không commit/push; giữ nguyên thay đổi IMP-01..10 chưa commit.
- **Gap nguồn:** `04` §4.2.1/§4.2.8 không có assignment version; `design/05` §5.3 đề xuất `expectedVersion` nhưng chưa định nghĩa nơi lưu; `07` §8.8 không nhận version ở REST. Policy revoke chưa chốt (OD-ID-04). Admin/Audit chưa có implementation, chỉ có module skeleton.
- **Quyết định trực tiếp:** người điều hành chọn `approve_identity_extension`: forward migration thêm `app_user.role_assignment_version BIGINT NOT NULL DEFAULT 0`, không âm; tăng đúng 1 mỗi thay đổi role/privilege thành công. `expectedVersion` chỉ ở internal command, Admin phải capture version và persist trong approval payload khi tạo request; không đổi public endpoint/request.
- **Policy được duyệt:** mọi thay đổi role/privilege revoke toàn bộ session `ACTIVE` của target bằng `LOGGED_OUT`/`logged_out_at`; không thêm enum `REVOKED`, blacklist, JWT hay refresh token. Cấm maker/checker thay đổi quyền của chính mình.
- **Boundary:** Identity command JOIN/MANDATORY, dùng Spring Data JPA, khóa user theo ID tăng dần rồi so version authoritative, mutate assignment/version/revoke cùng transaction. Admin lock/validate approval, consume step-up `SENSITIVE_ACTION`, truyền facts đã kiểm tra và update `APPLIED`, gọi Audit C2 cuối transaction. Identity không ghi bảng Admin/Audit, không import Admin entity/DTO hoặc thêm endpoint.
- **Không phê duyệt giả dependency:** `ROLE_CHANGE_REQUESTED/APPROVED/REJECTED` thuộc Admin; `SECURITY_ACCESS_DENIED` C3 thuộc security/Audit. Identity trả metadata/result để consumer ghi audit; không tạo event tên mới hoặc dùng security log thay durable C2. Contract fixture trong test là phương án thay thế theo AGENTS.md, không là production provider.
- **Trạng thái còn mở:** API/version capture phía Admin, approval/step-up orchestration thật, audit durable/hash chain/C3 và production atomic approval+role+audit tiếp tục BLOCKED đến khi owner Admin/Audit cung cấp implementation và chạy end-to-end. Idempotency admin write OI-API-19 chưa được quyết định; duplicate approval đã quyết định phải reject, không tự tạo idempotency table.
- **Evidence cuối:** full `./gradlew build --rerun-tasks --console=plain` SUCCESSFUL (5m40s), 48 classes / 721 tests PASS, 0 failure/error/skip; Identity 508, gồm 55 test IMP-11 mới (RoleChange 45 + RBAC 10). Role/version/session, concurrency/rollback và HTTP denied đã kiểm chứng; production approval/Audit vẫn BLOCKED, task tổng PARTIAL. Báo cáo `digitalbanking-docs/docs-backend/modules/identity/BE-ID-IMP-11.md`.

## DEC-ID-012 — BE-ID-IMP-12: REST độc lập, không giả lập dependency bị chặn

- **Ngày/branch:** 16/11/2026, `datht-backend-rework`, HEAD `56210cc80ad742ddad9139dc1a08fe9ff524465a`; giữ nguyên thay đổi chưa commit của các task trước.
- **Nguồn:** `07` §2–4, §7.2, §8.1; `design/05` §5, §11.3.1, §13.1; DEC-ID-006/008/009/010/011. Thư mục thực tế là `docs-backend/modules/identity/`, không tạo thư mục Identity thứ hai.
- **Áp dụng quyết định đã có, không phê duyệt extension mới:** hoàn thiện DTO/validation/response của bảy route hiện hữu; password luôn MFA theo DEC-ID-008, FIDO UV required theo DEC-ID-010. Tái sử dụng duy nhất `GlobalExceptionHandler`/`ProblemWriter` và security chain Common; không thêm handler/SPI/token issuer/persistence trùng.
- **BLOCKED:** API-AUTH-02 positive vẫn thiếu device-possession/enrollment/lifecycle contract theo DEC-ID-009. API-AUTH-08 vẫn thiếu resend counter/generation/fencing và protocol được duyệt theo DEC-ID-006/MOD-OI-07. Không thêm route resend giả trả 202/503 cố định, không dùng RAM hoặc verify-attempt count thay resend count, không tự sửa migration để đóng task. Step-up FIDO/PASSCODE credential verification chưa có provider; proof đúng schema không được coi là OTP hoặc cấp VERIFIED giả.
- **Phương án kiểm thử:** mock application failure chỉ tại boundary của controller để kiểm REST/error mapping; regression HTTP + PostgreSQL hiện có vẫn chạy với Identity/Spring Security thật, gateway capture và Audit failure fixture chỉ trong test theo Modules contract §13.1. Không coi fixture là Notification/Audit production.
- **Điều kiện hoàn tất:** Identity/Đạt + Notification/Thịnh + DB/Security/Product duyệt và triển khai dependency resend/device-proof; Customer/Đạt cung cấp customer mapping, Notification trusted-contact/provider và Audit/Thịnh durable C2/C3. Trạng thái tổng IMP-12 phải PARTIAL khi còn thiếu endpoint/success behavior, dù các kiểm thử phần độc lập PASS. Chi tiết và evidence tại `docs-backend/modules/identity/BE-ID-IMP-12.md`.

## DEC-ID-013 — BE-ID-IMP-13: audit/metrics/rate-limit/redaction Identity; durable audit persistence vẫn BLOCKED theo module Audit

- **Ngày/branch:** 16/11/2026, `datht-backend-rework`, baseline `origin/dev2@56210cc` + thay đổi chưa commit của IMP-01..12 được giữ nguyên.
- **Nguồn:** `design/01` §5.2/§5.3/§5.6/§8.2/§12, `design/04` §7.1/§7.2/§8.3/§9.1, `design/05` §3.2/§11.1/§12.2, `07` §2.8/§4.4, `00` §9.4/§10.5/§10.6 (BE-SEC-16/17, BE-OBS-16/18), DEC-ID-003, DEC-05/DEC-02 của BE-COM-IMP-05/02.
- **Audit:** integration đi qua port `AuthAuditPort` hiện hữu (C2 `recordInTransaction` JOIN/MANDATORY fail-closed, C3 `recordStandalone` best-effort). Catalog action được phát từ các flow IMP-02..10 không đổi (8 action đã có, không tự chế event mới). `SecurityLogAuditAdapter` được harden: redaction trung tâm trên `detail`, metric success `auth.audit.events{action,class,mode,outcome}` và failure `auth.audit.failures{action,class}`, C3 failure → fallback security log + metric, C2 exception lan truyền để rollback TX. **Durable persistence `audit_log` + `AuditModuleApi` vẫn BLOCKED** (module audit runtime chưa tồn tại; ownership thuộc Audit/Thịnh) — không tạo audit DB/service riêng, không tuyên bố durable C2 delivery; thay thế khi có: adapter phải write `audit_log` trong TX, test double chỉ mô phỏng lỗi write qua seam `emit` protected.
- **Metrics:** `AuthMetrics` (Micrometer/MeterRegistry Common) — `auth.login.requests{method,result,reason}`, `auth.login.duration{method}` (Timer), `auth.mfa.verify{purpose,result,reason}`, `auth.session.created{method}`, `auth.session.terminated{outcome}`, `auth.login.rate_limited{group}` + `rate.limit.rejections{group}` đếm ngay trong `RequestRateLimiter`. Label bounded (method/purpose/result/outcome/group/ErrorCode), KHÔNG userId/username/correlation/token (BE-OBS-18). Metrics fail-open (lỗi hạ tầng metrics không ảnh hưởng auth) — khác policy audit C2/C3.
- **Rate limit:** username quota triển khai theo DEC-05 của common owner: `PasswordLoginService` normalize username (`toLowerCase(Locale.ROOT)`, cùng chuỗi dùng cho lookup — nhất quán, không bypass casing) và gọi `RequestRateLimiter.acquire("login-username", …)` TRƯỚC credential verify; chặn → `429 RATE_LIMITED` + extension `retryAfterSeconds` (giây thật từ limiter, được `ProblemWriter` set header `Retry-After`); bucket đầy → 503 fail-closed như common filter. `ProblemResponse.checkedExtensions` cho phép `retryAfterSeconds` chỉ cho `RATE_LIMITED` (Long 1–86400). Config `app.security.rate-limit.login-per-username-per-minute` mặc định 10 theo `07` §2.8 (design/04 §8.3 ghi 5 — đã được common owner xử ở DEC-05; không tự đổi baseline). Scope MVP in-process per-instance (design/05 §12.2, design/02) — không tuyên bố distributed quota; passcode/fido login không quota riêng (chỉ bucket global/IP của common filter), sẽ rà lại khi contract yêu cầu.
- **Redaction:** `common.logging.LogRedaction` — nguồn trung tâm duy nhất: `redact(map)` đệ quy theo key nhạy cảm (normalization bỏ non-alnum; exact + substring fragments: password/passwd/passcode/otp/token/secret/cookie/authorization/csrf/apikey/recoverycode/mfasecret/credential; `username` cũng bị redact) và `redactText(text)` scrub `key=value`/JSON assignment + Bearer token. Được dùng bởi `SecurityLogAuditAdapter` (defense-in-depth trên `detail`); structured stdout vẫn fail-closed qua `SafeJsonLogEncoder` (không serialize message/args/MDC). Không áp redaction vào dữ liệu nghiệp vụ/API.
- **Chưa làm/không phạm vi:** quota per-challenge OTP attempt (đã có `423` BRL-004), rate-limit event vào `audit_log` (design/01 §5.7: chỉ metric + security log), audit cho ROLE_CHANGE_* (thuộc Admin/Audit phase khi Admin approval tồn tại — không tự phát event ngoài flow của Identity), distributed limiter, audit-of-audit (OI-AUD-06).
- **Nguồn và ảnh hưởng:** `AuthAuditPort`, `SecurityLogAuditAdapter`, `AuthMetrics` (mới), `PasswordLoginService`, `OtpChallengeService`, `LoginSessionService`, `LogoutService`, `PasscodeLoginService`, `FidoLoginService`, `RequestRateLimiter`, `ProblemResponse`, `ProblemWriter`, `LogRedaction` (mới); tests mới: `LogRedactionTest`, `AuthMetricsTest`, `SecurityLogAuditAdapterTest`, `LoginUsernameRateLimitHttpIntegrationTest`, IMP-13 additions trong `PasswordLoginServiceTest`. **Chờ Audit/Security owner review** adapter seam + retryAfterSeconds extension; durable C2 phải thay adapter khi BE-AUD có `AuditModuleApi`.
## DEC-TRF-003 — Validation và credit failure

- **Ngày / thẩm quyền:** 16/11/2026; Codex quyết định theo chỉ đạo người điều hành “Quyết định sao cho phù hợp dựa vào tài liệu hiện có, nhớ sửa các docs liên quan”. Không đại diện sign-off của provider/QA.
- **Quyết định:** lỗi amount, tài khoản inactive hoặc thiếu tiền được phát hiện **trước T1** bị từ chối không tạo transfer/idempotency/ledger. Nếu status hoặc balance thay đổi **sau T1**, revalidate dưới lock; từ chối nghiệp vụ kết thúc FAILED, lưu response/idempotency và audit bắt buộc trong cùng transaction, không money/ledger/usage; outbox theo policy. Không xóa intent/audit đã commit.
- **Credit failure:** nếu biết debit chưa commit và rollback về savepoint được chứng minh trong T2 còn giữ lock, hoàn tất FAILED cùng response/idempotency, release reservation và audit/outbox bắt buộc trong transaction; không commit debit đơn lẻ. Nếu T2 đã abort, chỉ dùng transaction Tfail khi đã xác minh rollback và có durable fence ngăn scheduler/worker thực thi lại; claim/re-read PENDING, kiểm chứng evidence, release reservation và ghi FAILED/response/audit atomically. Nếu fence hoặc bằng chứng không đủ, mất kết nối/crash hay commit outcome chưa biết, không suy đoán rollback hoặc trạng thái: đọc primary, claim/reconcile, giữ nguyên thực trạng và alert/manual cho đến khi an toàn. Audit/Tfail lỗi rollback nhánh đó; không báo FAILED giả.
- **Lý do / ảnh hưởng:** phân biệt thời điểm AC-006, giữ AC-012 rollback và FAILED khi có thể xác định an toàn, không xóa bằng chứng hay tạo debit kép. Tfail/savepoint/fence là contract thiết kế phải kiểm chứng trên PostgreSQL; trường hợp crash không thể cam kết FAILED tức thời. Nguồn `03` AC-006/012, `07` §8.3, `design/02` §5/8, `05` §7/8. BA/QA/Account/Transfer/Architect review và test, chưa xác nhận implementation.

## DEC-TRF-004 — Retry và reconciliation

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành ở DEC-TRF-003.
- **Quyết định:** cùng key/hash và record chưa terminal → HTTP `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`; request HTTP không trực tiếp resume T2. Scheduler/worker nội bộ claim và phục hồi an toàn. Terminal replay nguyên HTTP status/body đã lưu (`201` hoặc `422`) cùng `Idempotent-Replayed: true`; khác actor/hash conflict an toàn. Key global theo `04`/`07`. Replay body giữ correlation gốc, header dùng correlation của request hiện tại để trace; không tiết lộ dữ liệu actor khác.
- **Lý do / ảnh hưởng:** chọn hành vi API `07`, phân biệt client retry với recovery của `04`; có thể tăng thời gian chờ worker. Cần API/FE/QA/Ops review, test crash, nhiều worker, replay và correlation; không khẳng định provider hiện đã sẵn sàng.

## DEC-TRF-005 — Retention idempotency

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành ở DEC-TRF-003.
- **Quyết định:** TTL cấu hình mặc định và tối thiểu là 24 giờ tính từ `completed_at` được lưu khi transfer terminal; cấu hình nhỏ hơn phải bị từ chối khi triển khai. T1 vẫn đặt `expires_at = created_at + TTL` để tuân thủ NOT NULL; khi terminal cập nhật atomically `completed_at`, response và `expires_at = max(expires_at, completed_at + TTL)`. Đây là mốc DB lưu, không hứa đủ 24 giờ sau lúc client nhận response. PENDING/nonterminal không purge; job chỉ xóa terminal đã hết hạn sau khi lock/recheck. Dù quá timestamp, khi record còn tồn tại phải replay/conflict; chỉ sau purge key có thể thành lệnh mới.
- **Thay đổi cấu hình:** T1 dùng snapshot `TTL_T1` hợp lệ; transaction terminal đọc một snapshot `TTL_terminal` hợp lệ tại thời điểm finalize, ghi cùng config version để truy vết và dùng `max(expires_at hiện có, completed_at + TTL_terminal)`. Giảm TTL không rút ngắn expiry đã lưu; cả hai snapshot phải ≥24 giờ. Không đọc được hoặc cấu hình không hợp lệ thì không commit terminal dựa trên giá trị đoán. Cơ chế lưu version/migration chưa có runtime và cần review.
- **Khách hàng / ảnh hưởng:** giữ cùng key, query reference nếu biết; nếu chưa rõ kết quả hoặc không có reference thì liên hệ vận hành trước khi tạo lệnh mới. Sau purge không bảo đảm chống trùng vĩnh viễn. Cần migration, test terminal sau PENDING > TTL, concurrent purge/retry và review Product/Security/Ops; không thay dữ liệu đang chạy.

## DEC-TRF-006 — Recovery PENDING bất thường

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành ở DEC-TRF-003.
- **Quyết định:** recovery claim trên primary theo lock order; với PENDING có ledger, ghi incident và đọc evidence qua public API Ledger, Account, Limit, Audit. Chỉ tự hoàn tất T-REC-02 khi chứng minh đúng cặp ledger/linkage/amount/currency/accounts, hiệu ứng tiền và settlement/usage cho từng transfer, audit completion, mode reservation và có response gốc đã mask hoặc đủ dữ liệu chứng minh có thể tái tạo chính xác. Không suy diễn bằng số dư hiện tại hay aggregate usage. Không post lại tiền/ledger. Nếu đủ proof, cùng một transaction cập nhật transfer, idempotency/status/body, settlement cần thiết và audit reconciliation cuối; lỗi rollback toàn bộ. Nếu thiếu/lệch, DB lỗi, commit trước chưa rõ, không chứng minh response gốc hoặc mode, giữ nguyên trạng thái thực tế và alert/manual; terminal không ghi đè.
- **Lý do / ảnh hưởng:** bảo toàn T-REC-02 khi đủ bằng chứng mà không giả định ledger/audit đơn lẻ đủ. Các evidence provider, fence, transaction và response reconstruction cần contract, migration và test thật; không coi phương án trên giấy là bằng chứng integration.

## DEC-TRF-007 — Audit C2/C1 cuối T2

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành ở DEC-TRF-003.
- **Quyết định:** chọn phương án A: Fraud trả dữ kiện tối thiểu của flag thực sự còn sau savepoint; Transfer gom trong T2, hoàn tất và flush business writes, sau đó Audit `recordInTransaction` JOIN/MANDATORY lần lượt ghi C2 theo flag ID tăng dần rồi C1 trong pha audit cuối. Không business SQL/ORM deferred flush hoặc network sau khi lấy audit chain lock; kiểm chứng trigger/deferred constraint. Lỗi C2/C1 rollback toàn T2, không bị fraud fail-open nuốt. Engine failure chỉ fail-open khi rollback savepoint và persistence context thực sự usable; bỏ descriptor của flag đã rollback.
- **Lý do / ảnh hưởng:** dùng API đơn hiện mô tả, batch là phương án thay thế, không khẳng định runtime đã có. Audit/Fraud/Transfer/Architect/QA phải review/test lỗi từng event, rollback, concurrent chain và thứ tự trên PostgreSQL.

## DEC-TRF-008 — Guard/reservation step-up

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành ở DEC-TRF-003.
- **Quyết định:** chọn guard/reservation tại `05` §10.1. Dùng một guard theo customer, không tách ngày/version; business date tính theo `Asia/Ho_Chi_Minh` cấu hình và nguồn thời gian primary thống nhất. Dưới guard tính: chuyển thành công thuộc ngày nghiệp vụ hiện tại + **mọi** reservation PENDING của customer còn hiệu lực kể cả từ ngày trước + lệnh mới, mỗi lệnh một lần. Tính các lệnh cũ có thể gây step-up bảo thủ nhưng không bỏ sót tiền đang xử lý qua nửa đêm. Khi COMPLETED, trong cùng guard/T2 atomically chuyển reservation sang usage ngày hoàn tất T2; ngày T1 và version được lưu để truy vết. FAILED release atomically, PENDING không release theo TTL.
- **Mốc ngày hoàn tất:** gate và T2 đọc thời gian từ PostgreSQL primary; gate chụp một mốc sau khi giữ guard để tính ngày hiện tại. Sau khi T2 lấy guard, lock transfer và account, **trước khi khóa usage theo ngày**, lấy một lần `terminal_at` từ PostgreSQL `clock_timestamp()` trên primary; lưu UTC và xác định business date từ timestamp đó theo zone cấu hình để chọn các usage row. Nếu `terminal_at` sớm hơn `created_at` đã lưu, không commit terminal dựa trên thời gian không hợp lệ; alert và xử lý sự cố đồng hồ. Dùng cùng timestamp cho `completed_at`, settlement/usage, audit/response theo contract; TTL DEC-TRF-005 tính từ mốc đã lưu. Đây là **thời điểm logic trong transaction**, không phải thời điểm commit thực: nếu lấy lúc 23:59:59 và commit sau 00:00 thì transfer thuộc ngày cũ. Reservation vẫn được tính là PENDING cho request khác cho đến commit atomically; test cả hai phía nửa đêm, clock lùi và hành vi commit qua ngày.
- **Đồng thời:** lock order mục tiêu là guard customer → transfer → account ID tăng → limit customer/account → audit cuối. Với recovery/T2/Tfail, đọc không khóa context bất biến để lấy customer, lấy guard, rồi lock/re-read transfer và xác minh context; claim/skip và mọi khóa FK, idempotency, challenge, config, purge phải tương thích. Không giữ lock/transaction chờ OTP. Sau guard đọc lại key, unique global là chốt cuối. `428` không tạo transfer/idempotency/ledger **bởi request đó**; request khác cùng key có thể commit T1 trước response, retry xử lý theo idempotency và challenge thừa hết hạn/vô hiệu an toàn.
- **Lý do / ảnh hưởng:** đảm bảo policy DEC-TRF-001/002 không bỏ sót PENDING qua ngày; scope, date và config version phải được lưu/kiểm thử. Đây là lựa chọn thiết kế, không phải chứng minh deadlock-free hoặc provider runtime. Identity/Limit/Transfer/DBA/QA/Security phải chứng minh race 428, commit/rollback, midnight, config đổi, worker, lock order và no double-count bằng PostgreSQL trước release.

## DEC-TRF-009 — Snapshot limit, fraud và invariant ledger

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành “chốt hết”. Các lựa chọn là thiết kế MVP, không phải xác nhận provider hay QA đã review.
- **Limit:** sau guard và lock account, đọc hai scope customer/account trong cùng một statement snapshot; chọn cấu hình ACTIVE có hiệu lực tại thời điểm đọc, ghi ID/version và các giá trị thực dùng vào evidence T2. Các kiểm tra per-transaction và daily dùng đúng snapshot đó dù cấu hình đổi sau khi đọc. Config bắt buộc thiếu, có nhiều bản hợp lệ hoặc lỗi đọc thì fail closed; chỉ coi account config là optional nếu contract quy định rõ. Ngày usage theo `terminal_at`/zone của DEC-TRF-008, không nhầm timestamp logic với thời điểm commit. Các trường lưu snapshot/version chưa có phải bổ sung migration.
- **Fraud:** chọn ngưỡng BRD/SRS đã đề xuất: flag khi số intent transfer duy nhất của cùng customer có T1 đã commit và `created_at` thuộc `(terminal_at − 600 giây, terminal_at]` lớn hơn 5; tính cả PENDING/COMPLETED/FAILED. Giao dịch hiện tại chỉ tính nếu thực sự nằm trong window, không cộng thêm khi đã được đếm; retry/replay/428 không tạo intent không tăng count. Đúng 5 không flag, 6 flag. Đọc rule/version một lần, lưu count, window và version khi có flag; rule này không tự block. Lỗi engine chỉ fail-open theo điều kiện savepoint của DEC-TRF-007. Ngưỡng cũ 3/120 giây không áp dụng cho rule này.
- **Ledger:** Ledger đồng bộ kiểm đúng hai entry debit/credit, source/destination, amount và currency khớp transfer, không entry thừa, trước pha audit cuối T2; lỗi/mismatch rollback. Dùng unique/FK/check và append-only theo schema cùng application invariant; reconciliation phát hiện và cảnh báo, không tự sửa hoặc thay kiểm tra đồng bộ. Không chọn deferred constraint trigger MVP vì phải chứng minh tương thích lock cuối audit; có thể cân nhắc sau benchmark/review.
- **Nguồn / rủi ro:** `03` SRS §2.13.5, `04` §§4.4–4.5, `09` T-FRAUD-B05, `design/02`, `design/03`, `design/05`; OI-CON-05/06/08/09, MOD-OI-06. Contract/provider, migration, isolation, xử lý cạnh tranh và kiểm chứng PostgreSQL vẫn cần review/evidence.

## DEC-TRF-010 — Recovery đa instance và mục tiêu audit

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo “chốt hết”.
- **Recovery:** job chạy khi khởi động và mỗi 60 giây, xét PENDING ít nhất 300 giây; batch/cursor hữu hạn, tránh bỏ đói. Claim an toàn và fencing trên primary theo lock order DEC-TRF-008; lock bận thì skip không tính lần thử. Lưu bền vững count, lần đầu, thời điểm thử tiếp và trạng thái; tối đa 5 lần hoặc 15 phút từ lần đầu, điều kiện nào đến trước thì dừng tự động và alert/manual. Backoff 30/60/120/240 giây có jitter; ghi attempt bền vững trước xử lý. Unknown commit, thiếu evidence hay fence không an toàn: giữ trạng thái thực tế và alert/manual ngay, không mù quáng retry money path. Ops có quyền có thể rearm qua thao tác được audit sau khi điều tra; không sửa ledger trực tiếp. Cần migration và test crash, stale claim, race; metric không thay durable state.
- **Audit:** giữ C2/C1 đồng bộ fail-closed và audit lock cuối theo DEC-TRF-007. Mục tiêu kiểm thử MVP là 20 RPS trong 10 phút, p95 ≤2 giây, p99 ≤5 giây, lỗi <1%, đồng thời giữ invariant, theo `09` D5; đây là mục tiêu chưa đo, không cam kết SLA. Benchmark cạnh tranh C1/C2; nếu không đạt thì tối ưu sau đo và giữ atomicity, không tự chuyển audit bắt buộc sang async.
- **Nguồn / ảnh hưởng:** `09` §7.6, `design/02` OI-CON-07/10/12, `design/05` MOD-OI-03/06; implementation, phối hợp Ops/Audit, load test và review chưa có evidence.

## DEC-TRF-011 — Notification và vòng đời OTP

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo “chốt hết”.
- **Notification:** outbox cùng T2; delivery at-least-once sau commit. Chống tạo bản ghi kết quả trùng bằng unique theo transfer, customer, loại và kênh; worker claim batch bằng `SKIP LOCKED`, lease 30 giây, token/owner và CAS khi ACK để worker cũ không ghi đè; poll 2 giây, batch 50. Gửi gateway ngoài transaction; retry tối đa 5 lần, backoff có jitter, cuối cùng FAILED/alert và Ops replay có audit; không thay đổi transfer. Dùng notification ID opaque ổn định làm provider idempotency key nếu provider hỗ trợ; crash sau send trước ACK vẫn có thể gửi trùng, đặc biệt ngoài thời hạn provider dedupe. Cần migration lease và test nhiều instance, crash và stale token.
- **Địa chỉ đích:** Notification/Identity gọi Customer-owned `resolveNotificationDestination` với SYSTEM context, mục đích và channel được phép, lấy destination thực tại thời điểm gửi; dữ liệu chỉ tồn tại tạm trong bộ nhớ, không lưu raw/log/outbox, lỗi hoặc thiếu đích được phân biệt. Masked customer projection không đủ để gửi. Phương án khác là chỉ IN_APP hoặc lưu encrypted recipient có key/retention được Security duyệt; không chọn cho baseline này.
- **OTP:** Identity chỉ lưu hash và metadata mask; sau commit T0 mới gửi OTP trong bộ nhớ qua gateway, không giữ DB transaction qua network. Chỉ cho verify khi `SENT` và ACK gateway của generation tương ứng đã lưu; `CREATED`/dispatch chưa xác định không dùng được. Chỉ trả 428 sau ACK và persist thành công; thất bại/timeout không rõ trả 503 `OTP_DISPATCH_FAILED`, vô hiệu khi có thể và không giả định DB đã ghi được khi DB lỗi. Không retry plaintext từ hash; reissue/resend khi fence an toàn. Resend dưới row lock chỉ cho CREATED/SENT chưa verify/hết hạn/locked; xoay OTP hash và generation, mã trước vô hiệu, giữ số lần thử sai, tối đa 3 resend và cooldown 60 giây cấu hình, TTL 5 phút theo API. ACK chỉ CAS generation hiện tại, ACK cũ không hồi sinh mã cũ. Nếu state chưa thể xác định an toàn, fail closed và alert/manual.
- **Nguồn / ảnh hưởng:** `07` §8.1.8, `04` §4.7, `design/04`, `design/05` §§5/6/11; OI-CON-11/12, MOD-OI-07. Thiết kế chưa chứng minh gateway, provider, schema, migration, security review hay tests.

## DEC-TRF-012 — Bảo vệ key, quyền đọc và version module

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo “chốt hết”.
- **Idempotency:** giữ key global và contract 1–255 ký tự theo `07` và DEC-TRF-004; client nên tạo UUIDv4 hoặc giá trị CSPRNG ít nhất 128 bit mới cho mỗi logical request và giữ nguyên key/payload khi retry. Không thể chứng minh entropy chỉ bằng format; preclaim/DoS vẫn là rủi ro. Conflict giữa actor không trả chủ key hay response của người khác; C3 best-effort, log fingerprint HMAC thay raw key/hash. Quota POST transfer 30 request/phút/customer; khi chạy nhiều instance bắt buộc limiter atomic shared tại gateway tin cậy hoặc shared store, lỗi limiter trả 503 fail-closed, 429 có Retry-After. Limiter hiện per-instance không chứng minh quota toàn cụm.
- **Read:** CUSTOMER cần `TRANSACTION_READ_SELF` và Account xác minh sở hữu source hoặc destination; staff cần `TRANSACTION_READ` và scope server-side. Chỉ trả fraud flags khi có `FRAUD_READ` hoặc `FRAUD_REVIEW`; cấp `FRAUD_READ` cho AUDITOR trong seed/matrix để khớp contract fraud hiện có. History ghép qua public API provider và projection mask, không đọc bảng private. Giữ hợp đồng hiện tại 403 khi có resource nhưng ngoài scope, 404 khi không tồn tại; điều này có thể lộ sự tồn tại, giảm bằng reference khó đoán, rate limit và security audit, không giả vờ triệt tiêu enumeration.
- **Boundary/version:** chọn provider-owned immutable public API/DTO v1; consumer chỉ import `provider.api`, không entity hay private table. Thay đổi breaking, kể cả thay constructor Java record, sang v2; CI phải có ArchUnit cấm import nội bộ kèm negative fixture và provider/consumer contract suite cùng vector. Fake được dùng khi provider chưa có nhưng không chứng minh lock/rollback; cần PostgreSQL thật. Một test kiến trúc pass rỗng không phải bằng chứng enforcement.
- **Nguồn / ảnh hưởng:** `07` §§3/5/8, `design/04`, `design/05` §§3/12/13; OI-CON-04/13, MOD-OI-08/09. Các control, quyền seed, test và owner review vẫn cần triển khai/chứng minh.

## DEC-TRF-013 — Vị trí lưu TTL và metadata fencing Transfer

- **Ngày / thẩm quyền:** 16/11/2026; Codex chọn phương án theo chỉ đạo người điều hành “Please finish this task. I'll give you all permission to do it.” Quyền lựa chọn thiết kế không thay review hay sign-off DBA, Ops, Security hoặc owner provider.
- **TTL:** lưu `ttl_t1_seconds` và `ttl_t1_config_version` trong `idempotency_record`, bắt buộc tại T1, TTL ít nhất 86400 giây; snapshot terminal gồm `ttl_terminal_seconds` và `ttl_terminal_config_version`, cùng NULL khi nonterminal, cùng có và hợp lệ khi terminal. Version là chuỗi không rỗng tối đa 128 ký tự. Quy tắc snapshot, expiry không giảm và fail-closed theo DEC-TRF-005; không purge nonterminal. Snapshot cùng vòng đời record và bị purge cùng record, không coi là audit retention dài hạn.
- **Fencing:** lưu `attempt_epoch`, `attempt_token`, `attempt_owner`, `attempt_lease_expires_at`, `attempt_state` cùng `recovery_attempt_count`, `recovery_first_attempt_at`, `recovery_next_attempt_at` trên `transfer`. Epoch tăng đơn điệu khi claim/rearm; các state điều khiển và hình dạng claim theo `04` §4.4.1. Đây là metadata, không chứng minh an toàn transaction. Trước money/terminal write phải xác minh claim theo `05` và giữ lock đúng thứ tự; hết lease không chứng minh rollback. Không tự chạy Tfail/recovery khi thiếu evidence/fence; unknown commit giữ thực trạng và alert/manual.
- **Migration / giới hạn:** SQL Transfer ứng viên ở `digitalbanking-backend/src/main/resources/db/transfer-candidate/V2__transfer.sql` nằm ngoài Flyway location chạy mặc định; `V2` chỉ là version thử nghiệm, không chiếm version production. Tích hợp sau migration Customer/Identity/Account thực và chọn version duy nhất; không tạo bảng production thay owner. SQL và các giả định PK/FK/delete phải được DBA và provider review, test PostgreSQL với migration thật và rollout/restore trước activation. Fake chỉ hỗ trợ test độc lập, không là bằng chứng integration. Limit/Audit/Identity transaction và recovery provider vẫn cần proof; không tuyên bố task/release đã Verified.
- **Nguồn:** `04` §4.4, `05` §8/10.1, DEC-TRF-003/005/008/010 và `BE-TRF-02-schema-proposal.md`. Nhu cầu lưu bằng chứng lâu hơn TTL, lease duration, cơ chế lưu evidence rollback và rearm/history vẫn cần owner quyết định trước recovery; giữ thiết kế fail-closed khi chưa rõ.

## DEC-TRF-014 — Hai transaction claim và xử lý recovery

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo yêu cầu tự quyết của người điều hành. Đây là lựa chọn thiết kế, không thay review provider/DBA/QA hoặc bằng chứng runtime.
- **Quyết định:** làm rõ `05` §4 và §8.1: discovery không khóa; transaction claim ngắn lấy guard customer rồi lock/re-read transfer `SKIP LOCKED`, ghi epoch/token/lease và metadata attempt bền rồi commit trước xử lý. Transaction xử lý riêng lấy lại guard → transfer/fence, xác minh context, PENDING và cả epoch/token; đọc evidence, chọn nhánh an toàn và chạy executor cùng transaction đó. Executor dùng transaction hiện hữu khi recovery gọi, không commit riêng; kết quả chỉ có hiệu lực sau commit caller. T2 độc lập có thể tự mở transaction riêng, không bao T0–T2 trong outer transaction.
- **Lý do / ảnh hưởng:** claim phải bền trước xử lý để crash/fencing có evidence; guard, evidence và executor phải chia sẻ lock/transaction ở pha xử lý để không tạo race. Cách hiểu trước đó rằng durable claim và executor bắt buộc trong cùng một transaction mâu thuẫn yêu cầu claim bền trước xử lý; làm rõ mà không thay HTTP contract hoặc quyền sở hữu module. Không suy rollback từ hết lease hay unknown commit; thiếu evidence giữ thực trạng và alert/manual.
- **Kiểm chứng còn thiếu:** claim/lease/CAS thực, recovery/evidence provider, lock order, `SKIP LOCKED`, crash, stale worker và transaction propagation cần test PostgreSQL nhiều connection và provider thật; candidate/fake chưa chứng minh các điều kiện này.

## DEC-TRF-015 — Baseline contract Transfer v1 và phát triển độc lập

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành hoàn thành BE-TRF-01/02 và toàn quyền quyết định. Không đại diện review hay sign-off của các provider.
- **Quyết định:** chốt hình dạng dữ liệu, nullability, transaction, outcome và các lỗi/invariant tối thiểu của các operation Transfer dùng tại `docs-common/design/05. Modules contract.md` §13.4, theo các quyết định DEC-TRF-001–014. Các DTO là provider-owned v1; consumer có thể xây/test độc lập bằng fake test-only ngay, không đợi module khác hoàn thành. Trước khi ghép phải đồng bộ implementation/provider contract suite và review transaction thật. Thiếu API evidence recovery thì giữ thực trạng và alert/manual; không tạo bean fake production hoặc truy cập bảng private.
- **Đếm usage:** operation `settleOrReleaseReservation` là điểm settlement reservation và tăng usage COMPLETED đúng một lần cho từng scope áp dụng; caller không gọi tiếp `incrementUsage` cho cùng transfer/scope. FAILED release không tăng usage. Điều này làm rõ hai operation từng được liệt kê riêng để tránh double-count, không thay đổi ngưỡng hoặc owner Limit.
- **Tương thích:** hợp đồng trước là mô tả khái niệm, chưa có bằng chứng provider runtime; mọi code thử nghiệm phải đối chiếu chữ ký và semantics v1. Không tự nhận review hay nói thay provider. Khi v1 đã phát hành, thay field bắt buộc, constructor record, lỗi, ngữ nghĩa hay transaction là breaking, cần v2 và kế hoạch migrate consumer.
- **Evidence:** contract vectors và test fake chỉ xác nhận consumer shape/nhánh; PostgreSQL với provider thật, nhiều connection, fault injection, release và D1–D5 vẫn là gate hậu tích hợp. Các quyết định độc lập có thể hoàn thành mà không chờ provider nhưng không được báo là integration đã đạt.

## DEC-TRF-016 — Boundary HTTP BE-TRF-03: kích hoạt, wiring validation và port consumer

- **Ngày / thẩm quyền:** 16/11/2026; Codex theo chỉ đạo người điều hành triển khai BE-TRF-03 và tự quyết định kỹ thuật không cần hỏi lại. Không đại diện review/sign-off của API/FE/QA/Security hay các provider.
- **Kích hoạt candidate:** `TransferController`, `TransferCreateService`, `TransferQueryService` và cấu hình `TransferHttpConfiguration` là thành phần boundary **không component-scan**; chỉ đăng ký tường minh khi orchestrator (BE-TRF-04/05), provider thật và migration đã review sẵn sàng, theo đúng quy ước candidate của `TransferPersistence` và lựa chọn an toàn “không kích hoạt money path” tại `05` §13.4. Until then production chưa route POST/GET `/api/v1/transfers` (404 như hiện trạng); test đăng ký qua `@Import` với fake test-only. Không tạo bean fake production, không đổi test security của common đang chiếm route fixture.
- **Wiring validation:** controller nhận raw body bytes + danh sách header `Idempotency-Key` và ủy quyền cho parser `TransferCreateInput` hiện có (giữ strict duplicate-key/UTF-8/byte-bound/unknown-field đã test). Parser mở rộng mang `errors[].field` an toàn (JSON path tương đối hoặc `Idempotency-Key`). Controller dịch lỗi parser: VALIDATION → `MethodArgumentNotValidException` (field errors qua handler chung), MALFORMED → `BankingBusinessException(MALFORMED_REQUEST)`, TOO_LARGE → `BankingBusinessException(REQUEST_TOO_LARGE)`; không tự dựng Problem (BE-CON-26/53). Response là record DTO (`TransferResponse`, `TransferDetail`, `AccountRef`, `FraudFlagSummary`).
- **Sửa hạ tầng chung tối thiểu:** regex `GlobalExceptionHandler.fieldError` cho phép dấu gạch nối để field header `Idempotency-Key` không bị sanitize thành `request` (BE-CON-55, CT-API-006). Field do server sinh, không phải input người dùng; thay đổi an toàn, phạm vi common liên quan trực tiếp task.
- **Port consumer:** Transfer định nghĩa interface port thuộc `transfer.application` ánh xạ `05` §13.4: `AccountOwnershipPort` (TRFC-AC-02), `AuditEventPort` (TRFC-AU-02), `TransferReadPort`, `FraudFlagReadPort`, và seam `TransferOrchestrator` cho 04/05. Không tạo class trong package `provider.api` của module khác; fake chỉ trong test. Đề xuất seam consumer ghi tại `05` §13.4 để review.
- **Thứ tự authorization POST:** service boundary kiểm tra lại `TRANSFER_CREATE` + session customer (C3 khi deny) rồi ủy quyền orchestrator; orchestrator lookup idempotency **trước** khi đọc account (replay trước preflight, CT-API-007/DEC-TRF-004). Ownership/resolve trả về outcome có kiểu; boundary phát C3 và map 403/404/409. Wiring `assertOwnership`/`resolveDestination` thật là integration 04/05 (Blocked).
- **GET:** `TransferQueryService` triển khai đầy đủ (read-only), ownership qua `AccountOwnershipPort` nguồn **hoặc** đích; staff `TRANSACTION_READ` theo matrix `07` §3.5, cơ chế scope server-side vẫn Blocked; `fraudFlags` chỉ `FRAUD_READ`/`FRAUD_REVIEW`. 403 tồn tại ngoài scope / 404 vắng theo DEC-TRF-012.
- **Format reference:** GET validate theo đề xuất `^TXN[0-9]{8}[A-Z0-9]{8,20}$` (khớp `ProblemResponse` common và YAML); OQ-02 chưa chốt bởi API owner, ghi nhận chứ không đóng băng.
- **PII:** không thêm regex phát hiện PII production; AC PII giữ Blocked (BA/Security).
- **C3 deny audit:** best-effort `recordStandalone` qua `AuditEventPort` + fallback security log + metric counter; lỗi audit không đổi response deny. Validation error trước tạo transfer không ghi audit (design/01 §5.7).
- **Nguồn và ảnh hưởng:** `07` §§2–8, `05` §§8/13.4, `00. architecture & conventions` BE-CON-21–26/53/55, design/01 §5.6–5.7, DEC-TRF-003/004/012/015; BE-TRF-03. Kiểm chứng còn thiếu: session/Account/Audit thật, PostgreSQL, orchestrator 04/05, quota shared đa instance, review API/FE/QA và policy PII/scope staff vẫn Blocked.

## DEC-ID-TEST-02 — BE-ID-TEST-02: PostgreSQL integration/wiring/restart test; consumer production còn BLOCKED

- **Ngày/branch:** 17/11/2026, `datht-backend-rework`; baseline so sánh `origin/dev2@cf60428`. Không commit/push, không đổi production code ngoài test/docs.
- **Quyết định phạm vi:** chỉ thêm test/test-infra + tài liệu; migration V1..V5 (kể cả V5 Customer của BE-CUS-IMP-01) được chạy trong complete chain nhưng test không nhận ownership hay sửa schema. Restart test dùng hai `SpringApplication` servlet context thật (context đầu đóng hoàn toàn trước khi tạo lại bean), không trình bày `context.refresh()` như restart.
- **Hạ tầng:** `PostgreSqlIntegrationTestSupport` — PostgreSQL 16-alpine, database/user/password ngẫu nhiên mỗi container, cấu hình Flyway/Datasource nhất quán, guard so sánh JDBC URL, property `MapPropertySource` precedence cao (credential rỗng YAML không thể thắng). Không dùng H2.
- **Test defects đã sửa trong task:** (1) manual Spring properties bị YAML override → property source precedence cao; (2) assertion Flyway history tính NULL marker như version → lọc `version IS NOT NULL`; (3) assert exact class trên Spring proxy → `isInstanceOf`; (4) AssertJ authority `Set` → assert collection trực tiếp; (5) `OtpChallengePostgreSqlIntegrationTest.t20` cấp 5 Argon2id đồng thời gây OOM → seed `attempt_count=4`, 2 worker, vẫn kiểm chứng threshold race/LOCKED/count=5. Không giảm assertion hay disable test; không phát hiện production Identity defect trong các lần chạy.
- **Không phê duyệt dependency giả:** durable Audit (`AuditModuleApi`/`audit_log`), Notification adapter/provider/contact resolver + MOD-OI-07, Transfer T0/T1/idempotency/ledger, Admin approval orchestrator vẫn BLOCKED; fake/test double chỉ trong test theo Modules contract §13.1 và được liệt kê đủ trong mock disclosure. Kết quả focused: migration/restart/delivery 25/25 PASS; Identity regression isolated 32 classes/585 tests/0 fail. Full-build regression cuối được ghi trong [BE-ID-TEST-02.md](digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-02.md).

## DEC-ID-TEST-03 — BE-ID-TEST-03: QA/security/performance phía Identity VERIFIED; FE/QA sign-off PENDING

- **Ngày/branch:** 17/11/2026, `datht-backend-rework` trên working tree hiện tại; baseline so sánh `origin/dev2@cf60428` + 5 commit local. Không checkout/reset/merge, không push.
- **Quyết định phạm vi:** chỉ thêm test + tài liệu + build task; không đổi production source Identity/Common, không đổi security config FE-facing. CSRF không bật thêm cho Bearer: kiến trúc cookie (`DB_SESSION`) + CSRF synchronizer giữ nguyên; Bearer là explicit credential nên CSRF proof không áp dụng (design/04 §4.3, 07 §8.1.6) — đã chứng minh bằng test `tsec06`.
- **JWT/refresh-token:** N/A theo thiết kế đã duyệt (opaque session, Modules contract §5.3.1); kịch bản tương đương đã phủ trên opaque token (hết hạn idle/absolute, revoke, malformed, forged trên kênh khác).
- **Performance:** không có SLA duyệt nên chỉ ĐO và ghi số liệu; NFR-003 «đề xuất» (p95 ≤ 1 s) chỉ dùng để so sánh trong [BE-ID-TEST-03.md](digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-03.md) §8, không enforce trong CI (tránh flaky). Test chỉ fail khi có lỗi chức năng trong cửa sổ đo.
- **Test-infrastructure defects đã sửa trong task:** (1) seed ngoài transaction không commit (Hikari `auto-commit=false`) → 401 hàng loạt; (2) `@TestInstance(PER_CLASS)` + Testcontainers ordering → «Mapped port…» → start container trong `@DynamicPropertySource`; (3) Gradle test worker heap mặc định 512 MB + 8 Argon2id đồng thời (m=64 MB) → connection-timeout/500 → `perfTest { maxHeapSize = "3g" }` (cùng lớp lỗi TEST-01 §6); (4) harness note lỗi thiếu status → sửa; (5) **JaCoCo agent đính kèm mọi `Test` task làm 8 Argon2id đồng thời chậm ~10× (358 ms → 3707 ms/op, reproducible bằng probe độc lập; tuần tự ~114 ms không đổi; DB idle toàn cửa sổ)** → `perfTest` tắt `JacocoTaskExtension` (coverage vô nghĩa cho run đo, `test` giữ nguyên); (6) harness race `okNanos`/`totalRequests++` → `ConcurrentLinkedQueue` + `AtomicInteger`. Không giảm security parameter production.
- **Build blocker chéo module:** `CustomerQueryService.toPublicStatus` compile FAIL (in-flight BE-CUS-IMP-04 — unqualified `CustomerStatus` bị inherited nested type che khuất, JLS §8.5). Owner Customer đã tự sửa bằng rename `CustomerQuery.CustomerStatus` → `CustomerQuery.Status`; bản vá tạm của TEST-03 không giữ lại, working tree sạch. `compileJava` SUCCESSFUL sau rename. **Còn OPEN:** `./gradlew build` full FAIL 2 test `customer.adapter.in.CustomerProfileControllerPostgreSqlIntegrationTest` (email mask expectation không khớp `PiiMaskingUtils` + `timestamptz`→`Instant` trong test code) — thuộc BE-CUS in-flight, owner Customer sửa; Identity 589/589 PASS trong cùng build, TEST-03 không can thiệp module khác.
- **Shared worktree race:** một Gradle build khác (Customer owner) ghi đồng thời `build/test-results` trong thời gian chạy → toàn bộ verification của TEST-03 chạy trong isolated full-repo copy (rsync không `.git`/`build`), đúng precedent TEST-01/02; không kill process của người khác.
- **Không tự ký:** FE/QA integration + external sign-off là `PENDING_EXTERNAL_SIGNOFF` (thư mục `digitalbanking-frontend/` trống); durable Audit/Notification/Transfer/Admin giữ BLOCKED theo TEST-01/02. Trạng thái task: PARTIAL.
## DEC-TRF-017 — Seam orchestrator BE-TRF-04, boundary challenge UUID và T0

- **Ngày / thẩm quyền:** 17/11/2026; Qoder theo chỉ đạo người điều hành triển khai BE-TRF-04 và tự quyết định kỹ thuật không cần hỏi lại. Không đại diện review/sign-off của Identity/Limit/Account/Audit, API/FE/QA, DBA/Architect hay Security.
- **Seam orchestrator:** `TransferCreateOrchestrator` (candidate, `transfer.application`, không component-scan) hiện thực `TransferOrchestrator` cho boundary BE-TRF-03: nhận `TransferCreateCommand` (actor server-side + input đã validate), dựng request preflight, gọi `TransferPreflightCandidate` (T1/T2 qua transaction boundary thật, không outer transaction bao T0–T2) và map `Result` sang `TransferCreateOutcome` không đổi contract HTTP. Nhánh `ChallengeRequired` gọi T0 `requestChallenge` **sau khi gate transaction kết thúc**; chỉ trả `StepUpRequired` khi provider đã ACK + persist SENT. Lỗi có kiểu map: challenge mismatch → `409 STEP_UP_CHALLENGE_MISMATCH`, hết hạn → `410 AUTH_OTP_EXPIRED`, audit CREATED lỗi → `500 AUDIT_WRITE_FAILED`, dispatch OTP lỗi/unknown → `503 OTP_DISPATCH_FAILED`; lỗi hệ thống khác propagate. Đăng ký runtime vẫn theo DEC-TRF-016 (chờ provider thật + migration review).
- **Boundary challenge UUID (đóng gap `05` §13.4):** `TransferIntentCandidate.Command` giữ `verifiedChallengeRef` UUID snapshot (TRFC-ID-05), không nhận BIGINT trước consume, không suy UUID→ID, không đọc bảng Identity. T1 insert transfer với `step_up_challenge_id` NULL → insert key → reservation → `consumeVerified(UUID)` (TRFC-ID-04, lock/revalidate/consume một lần) trả BIGINT `challengeId` → `attachChallenge` cập nhật FK **trước** flush/audit/commit; sai binding/hết hạn/đã dùng ném `ChallengeRejectedException` rollback toàn T1. `insertPendingAndKey` không còn tham số challengeId; consume trả ID ≤0 là invariant error.
- **Seam preflight:** `ChallengeRequired` mang thêm `requestHash` (redacted `toString`) để T0 bind đúng hash mà không lộ ra boundary; `settleCreated` trả `Settlement(outcome, receipt)` gồm receipt masked đã lưu khi terminal, kiểm tra nhất quán status/failureCode với outcome, để orchestrator dựng `Completed`/`Failed` không đọc lại và không đổi contract. Đây là seam consumer-owned nội bộ, không phải provider API đã phát hành.
- **Port Identity consumer:** `StepUpChallengePort` (`transfer.application`) ánh xạ TRFC-ID-02 `requestChallenge` và TRFC-ID-05 `findVerifiedBindingIfPresent`; `ChallengeIssued` mang trường hiển thị an toàn cho 428 (methods/channel/maskedDestination/expiresAt/maxAttempts/remainingAttempts) — **đề xuất mở rộng** baseline tối thiểu, ghi `05` §13.4 chờ Identity review. `OtpDispatchException` biểu diễn dispatch lỗi/unknown; provider invalidate khi an toàn. `findVerifiedBindingIfPresent` là seam cho adapter preflight thật (chưa nối runtime).
- **Reference:** `TransferReferenceGenerator` sinh theo đề xuất OQ-02 `TXN[0-9]{8}[A-Z0-9]{8,20}` (SecureRandom, 8 số + 12 alphanumeric); unique DB là chốt cuối, collision reference là lỗi unique riêng không phải replay. OQ-02 chưa chốt bởi API owner, không đóng băng.
- **TTL:** quy tắc snapshot ≥24h + version, fail-closed và expiry không rút ngắn đã có ở `Command`/`PendingInsert`/`IdempotencyRetention` (bổ sung test fail-closed). Cơ chế đọc snapshot từ config runtime vẫn là open issue (BE-TRF-04 §8), chưa tự dựng nguồn config production.
- **Còn Blocked:** adapter provider thật (Identity challenge, Limit guard/reservation, Account resolve/ownership, Audit C1) và wiring preflight (gọi `findVerifiedBindingIfPresent`, ownership/status/balance/limit, đọc TTL config); proof PostgreSQL guard/key race/challenge consume-rollback/audit fail-closed; replay/428 HTTP end-to-end; review DEC-TRF-008, OQ-02, policy PII. Test fake không chứng minh lock/unique/rollback thật hay integration.
- **Nguồn và ảnh hưởng:** `05` §§5.2/8/10.1/11.1/13.4, `02` §5.1–5.2/§6/§11.4/Appendix B.1–B.3, `07` §§5–6, BE-TRF-04 §3–§6, DEC-TRF-003/004/005/008/011/013/015/016.

## DEC-TRF-018 — Recovery candidate với proof rollback và escalation

- **Ngày / thẩm quyền:** 17/11/2026. Codex theo yêu cầu triển khai độc lập BE-TRF-06 và tự quyết kỹ thuật candidate. Activation cần review Transfer, provider, DBA, QA và Ops.
- **Claim/fence:** batch 100, cursor `(created_at, transfer_id)`, giới hạn tuổi cố định trong mỗi vòng. Lease candidate 120 giây, timeout transaction 30 giây. Claim bền tách transaction xử lý, guard CUSTOMER → transfer, kiểm epoch/token/state/lease dưới lock. T2 trực tiếp sau T1 có seam cấp fence. Candidate bỏ qua lock bận khi claim. Lease hết với outcome chưa rõ chuyển MANUAL. Chỉ T2 báo lỗi contention từ thân transaction và callback xác nhận rollback mới được retry. Backoff 30/60/120/240 giây + 0–5 giây jitter. Khi xử lý báo commit không rõ, lấy guard/lock và đọc primary trong transaction mới, giữ terminal đã commit; PENDING chưa đủ proof chuyển MANUAL.
- **Reconciliation:** đọc evidence qua seam consumer theo `05` §13.4 và yêu cầu basis/replay do Transfer sở hữu. Khi đủ proof, giữ timestamp terminal gốc từ audit evidence cho transfer và usage. Timestamp repair trên primary dùng cho snapshot TTL idempotency, giữ expiry lớn hơn. Chỉ chạy T2 hiện có với reservation bắt buộc, PENDING và proof chưa dùng usage. Legacy có thể repair với proof usage theo transfer, date và version; trường hợp version cross-policy chưa rõ giữ MANUAL. Audit C1 SYSTEM ở pha cuối. Purge khóa idempotency, xóa terminal hết hạn nghiêm ngặt sau recheck và kiểm status transfer cùng outcome.
- **Phạm vi vận hành:** tất cả wiring candidate là tường minh. Provider thật phải chứng minh guard đồng nhất, proof mode/version, masked replay, JOIN transaction và phân quyền SYSTEM. Khi chưa có audited rearm và Tfail được chứng minh, giữ MANUAL. Xem [BE-TRF-06](digitalbanking-docs/docs-backend/modules/transfer/tasks/BE-TRF-06.md).

## DEC-TRF-019 — BE-TRF-07 candidate phía Transfer

**Ngày:** 17/11/2026. **Trạng thái:** Candidate đã triển khai; nghiệm thu tích hợp Blocked.

- HTTP candidate yêu cầu `TransferStaffScope` theo API §3.6 và dùng Common cho session, CSRF, correlation, Problem và quota. Cấp scope thật và quota shared atomic tiếp tục do owner provider/Common thực hiện.
- Dùng correlation bền và Common scope tối thiểu cho recovery, request ID mới theo bản ghi. Bản ghi có correlation không hợp lệ báo incident và được bỏ qua để xác minh; không sửa correlation lịch sử hoặc cấp quyền bằng context truy vết.
- T2 adapter dùng seam consumer-owned trong Modules contract §11.3.2 và nhận policy recipient từ ngoài. Outbox synthetic tham gia transaction thật trong test. API Notification công bố DTO, policy thành viên/channel/consent, durable uniqueness, T3 resolver/gateway và proof lease/dedupe vẫn thuộc các owner liên quan.
- C3 dùng Common JSON encoder theo allowlist action/outcome/actor hash và context sẵn có. T2 thất bại ở pha audit trả mã `AUDIT_WRITE_FAILED` qua Common Problem.
- Counter terminal chỉ ghi khi commit; snapshot PENDING đọc primary ngoài scrape, lỗi đọc giữ mẫu cũ kèm availability/tuổi dữ liệu. Đăng kèm Prometheus rule candidate và vector fire/resolve. Kích hoạt, tích hợp dashboard/route, deadlock/lock wait và signal backlog thật cần Ops/provider xác nhận.

## DEC-LED-001 — Ledger posting và evidence v1

- **Ngày / thẩm quyền:** 17/11/2026. Codex theo yêu cầu triển khai độc lập BE-LED-01 và tự quyết kỹ thuật. Reviewer liên module xác nhận trước khi ghép provider.
- **Chọn:** giữ TRFC-LE-01/02 theo `05` §9/§13.4. Posting JOIN/MANDATORY, kiểm input, các entry ID và đầy đủ cặp sau ghi, trước audit; một lỗi lan ra rollback T2. Transfer truyền intent và snapshots Account đã khóa; Ledger chỉ ghi bảng mình sở hữu. Theo DEC-TRF-009, unique/CHECK/FK hỗ trợ kiểm đồng bộ. Deferred constraint trigger cho cặp chưa dùng trong MVP. BE-LED-02 xử lý append-only bằng quyền runtime và chứng minh trên PostgreSQL.
- **Evidence:** read primary sau guard/fence trong recovery, SYSTEM, trả mọi entry theo seq/ID với correlation của intent. Empty sau đọc thành công khác dữ kiện lệch hoặc lỗi đọc. Chọn evidence theo entry vì boolean không đủ phát hiện các loại sai lệch. Evidence Ledger chỉ là một phần proof theo DEC-TRF-006/014.
- **Ảnh hưởng:** schema/chain Flyway, Java provider, review transaction/roles và test PostgreSQL thật thuộc BE-LED-02–04/06/08. Nguồn `04` §4.4.2, DEC-TRF-007/009/015, `05` §9/§13.4.

## DEC-LED-002 — Transfer sở hữu projection lịch sử

- **Ngày / thẩm quyền:** 17/11/2026. Chọn baseline kỹ thuật BE-LED-01 để triển khai độc lập.
- **Chọn:** Transfer đọc intent trên primary cho API-TXN-01/02, vì PENDING/FAILED có thể không có bút toán. Account cung cấp facts ownership và masked DTO. Scope lấy từ server, áp dụng trước count/page. Projection có tie-break transfer ID, snapshot thống nhất trong request và `asOf` theo `05` §12.3; scope cho staff và semantics mặc định cần API/Security review. Ledger không tham gia truy vấn history. Có thể tách read model khi đo được tải và chấp nhận freshness/pipeline.
- **Ảnh hưởng:** Account projection phục vụ predicate và staff scope provider là dependency chờ review; dùng fake đúng contract trong test. Giữ semantics công khai theo `07` §§2.5/3.6/7.2.12/8.4. BE-LED-05 bàn giao cho Transfer và phối hợp integration, MOD-OI-08 tiếp tục theo dõi scope/provider/runtime.

## DEC-LED-003 — Opening balance có provenance

- **Ngày / thẩm quyền:** 17/11/2026. Chọn baseline kỹ thuật BE-LED-01 để triển khai độc lập.
- **Chọn:** Account sở hữu opening baseline bất biến xác minh được, hiệu lực trước bút toán đầu tiên, kể cả khi opening bằng 0. Đọc cùng snapshot primary rồi đối chiếu `opening + SUM(CREDIT) − SUM(DEBIT)` với materialized ledger balance. Seed/legacy thiếu proof là incident; không suy opening từ số dư hiện tại, tự backfill hay sửa tiền.
- **Phương án khác:** opening journal entry cần mô hình posting ngoài transfer hoặc thay FK bắt buộc, thêm policy/audit và migration. Baseline giữ nguyên schema Ledger và dựa vào Account/Audit xác nhận provenance.
- **Ảnh hưởng:** thêm phương án `AccountReconciliationQuery` trong `05` §9. Account/DB/Audit/BA review nguồn evidence, storage và phương án cho legacy trước BE-LED-06. Baseline toàn account không dùng làm evidence recovery per-transfer. Nguồn `04` §§4.3–4.4, `05` §9/§13.4 và DEC-TRF-006.

## DEC-LED-004 — Migration Ledger trên chain candidate

- **Ngày:** 17/11/2026. BE-LED-02 dùng location riêng `db/ledger-candidate` và fixture test-only với hai PK Account/Transfer. Version V2 thuộc chain fixture. Version production chờ DB owner đối chiếu history, xử lý duplicate V2 và phối hợp predecessor Account/Transfer.
- `banking_migrator` tạo bảng, indexes và trigger trong cùng migration. `banking_app` nhận SELECT/INSERT trên bảng và dùng identity mặc định. SQL thu hồi quyền sequence trực tiếp cùng các quyền mutation trên Ledger, kiểm ACL hiệu lực và tách role. Trigger mức statement từ chối UPDATE/DELETE/TRUNCATE với SQLSTATE `55000`. CHECK tiền loại `NaN`; kiểm đủ cặp và validate scale trước ghi thuộc BE-LED-03.
- Xem [BE-LED-02](digitalbanking-docs/docs-backend/modules/ledger/tasks/BE-LED-02.md) để chạy harness và kiểm các gate production. Owner review và Hibernate validate chờ chain và mapping thật.

## DEC-LED-005 — BE-LED-03: provider posting độc lập, taxonomy lỗi và wiring candidate

- **Ngày / thẩm quyền:** 17/11/2026. Qoder theo chỉ đạo người điều hành triển khai BE-LED-03 và tự quyết định kỹ thuật không cần hỏi lại. Không đại diện review/sign-off của Transfer, Account, DB, Architect, Audit, Security hay QA.
- **Provider đã triển khai** trong `com.simulatedbank.banking.ledger` theo đúng 5 lớp (BE-LED-03 §6): `api.LedgerPosting` + `api.dto.PostingCommand`/`PostingRef` (record bất biến, không annotation persistence); `domain` thuần Java gồm `Money`, `ValidatedPosting`, `EntryDraft`, `PostedEntry`, `EntrySide`, `PairVerifier` và hai exception; `application.LedgerPostingService` implements port với `@Transactional(propagation = MANDATORY)` và `application.port.LedgerEntryStore` (append/flush/read-back, không phải public API); `adapter.out.persistence` gồm `LedgerEntryEntity`, `LedgerEntryJpaRepository` và `LedgerEntryPersistenceAdapter`. Ledger chỉ ghi `banking.ledger_entry`, không gọi provider khác, không import `transfer.api`/Account.
- **Validation trước ghi:** ID `long > 0`, source ≠ destination; amount `signum > 0`, balance `signum >= 0`, `scale <= 2`, `(long) precision - scale <= 17`; từ chối `1.000`/`1.235`/`1E+17` trước chuẩn hóa; chuẩn hóa `setScale(2)` không rounding; currency đúng `VND`; correlation `[A-Za-z0-9._:-]{1,64}`. So sánh tiền bằng `compareTo`.
- **Thuật toán (§7.2):** validate → append DEBIT seq 1 nguồn → append CREDIT seq 2 đích → flush → đọc **toàn bộ** entry của transfer `ORDER BY entry_seq, entry_id` → `PairVerifier` kiểm đủ cặp (§7.3) → trả `PostingRef`. Read-back dùng **native scalar projection** (`LedgerEntryRow`) để quan sát cột database thật sau flush, không đọc lại managed entity trong persistence context; nhờ vậy phát hiện được corruption và fault injection.
- **Taxonomy lỗi (không thêm public error code):** `InvalidPostingCommandException` (invalid-contract) và `LedgerInvariantException` (invariant) là unchecked thuần Java trong domain; thiếu transaction → `IllegalTransactionStateException` của Spring; persistence → `DataAccessException`. Tất cả đi qua `GlobalExceptionHandler`/`DatabaseErrorMapper` của Common: invariant/unique/invalid-contract → `500 INTERNAL_ERROR`, timeout/deadlock/connection → `503 SERVICE_UNAVAILABLE`. Provider không dựng HTTP response, không log command/số dư/PII.
- **Wiring candidate (theo DEC-TRF-016):** `LedgerPostingService` và adapter là class thường, **không component-scan**; đăng ký tường minh trong harness test (và sẽ đăng ký khi BE-LED-04 ghép T2). `LedgerEntryEntity`/`LedgerEntryJpaRepository` đặt trong `banking.ledger.adapter.out.persistence` theo đặc tả §6, cùng precedent fraud/notification (entity trong module, bảng chưa có trong chain production). Hibernate `ddl-auto=validate` chạy với mapping thật trên chain candidate `db/ledger-fixture` + `db/ledger-candidate`; **chưa** đóng gate validate production vì `ledger_entry` chưa nằm trong chain Flyway production.
- **Giới hạn fixture đã disclosure:** trigger append-only + CHECK/unique khiến **không thể** corruption thật bằng UPDATE/DELETE/TRUNCATE trên `ledger_entry` (kể cả test-only). Các vector read-back lệch (LED-POST-10/11) dùng **mocked projection** qua seam test `TestLedgerEntryStore.readMutator`; hai INSERT và rollback vẫn thật trên PostgreSQL 16. FK/CHECK error (LED-POST-08) và unique concurrent (LED-POST-12) là lỗi thật. Fixture FK Account/Transfer test-only; chưa chứng minh money path/T2 thật.
- **Evidence:** 103 test mới (49 validation + 15 pair + 8 service + 2 contract reuse oracle + 29 PostgreSQL LED-POST-01–14). Regression xanh: `LedgerContractOracleTest` 32, `LedgerCandidateMigrationPostgresTest` 14, `ModuleBoundaryArchTest` 10, `AuditBoundaryArchTest` 3, Transfer candidate 26+7+26+8. Xem [evidence BE-LED-03](digitalbanking-docs/docs-backend/modules/ledger/tasks/evidence/BE-LED-03.md).
- **Pre-existing, không thuộc task:** full-context PostgreSQL test fail tại HEAD do duplicate V2 Flyway (`V2__create_audit_log`/`V2__create_identity_and_rbac_tables`, gate LED-OI-05/BE-LED-02) và `audit.AuditJdbcWriter` cần `JdbcTemplate` trong test loại trừ DataSource. Đã xác minh bằng worktree HEAD tách biệt: fail y hệt khi không có thay đổi Ledger. BE-LED-03 không sửa migration/audit/transfer của owner khác.
- **Còn Blocked:** chain migration production (duplicate V2 + predecessor Account/Transfer/Ledger), ghép T2 với Transfer/Account/Limit/Audit thật và fence (BE-LED-04/08), public `LedgerQuery.findEvidence`/recovery (BE-LED-06), owner review DTO/error/transaction/snapshots. Không đánh dấu task hoàn thành chỉ vì provider/test đã có.
- **Nguồn và ảnh hưởng:** `05` §9/§13.4, `04` §4.4.2, `00` §4/§6/§8/§11, BE-LED-01/02/03, DEC-LED-001–004, DEC-TRF-007/009/015/016.

