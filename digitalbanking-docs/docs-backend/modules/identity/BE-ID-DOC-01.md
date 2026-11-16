# BE-ID-DOC-01 — Identity: Review kiến trúc, truy vết yêu cầu và hiện trạng triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-DOC-01`
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 16/11/2026
> **Trạng thái:** Draft — chờ review (task tài liệu/review, KHÔNG triển khai code)
> **Owner:** Đạt (module identity); reviewer: Backend Lead, BA, Security, chủ module phụ thuộc — chờ review
> **Baseline:** `origin/dev2` = `56210cc80ad742ddad9139dc1a08fe9ff524465a` (đã fetch, xem §1)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §2.2, §2.12–§2.13, §3.1, §4–§11; [[digitalbanking-docs/docs-common/02. BRD.md]] §4.2, §6.1, §6.7, §11.4; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.2, §9, §10.1; [[digitalbanking-docs/docs-common/06. System architecture.md]] §6.2–§6.3, §7.1; [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§9, §11.1; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5, §8; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§6, §9; [[digitalbanking-docs/docs-common/design/04. security.md]] §4–§8; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §2–§14; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [[digitalbanking-docs/docs-backend/modules/_overview/01-identity-auth.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [../tasks/README.md](tasks/README.md); `DECISIONS.md` (DEC-TRF-001/002)

**Ghi chú phạm vi tài liệu:** đề gán cho task ghi thư mục `docs-backend/Identity/`; kho không có cấu trúc này. Quy ước hiện hành của repo là `docs-backend/modules/identity/` ([[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §12.3; index tại [[digitalbanking-docs/docs-backend/README.md]] §2), do đó toàn bộ sản phẩm nằm tại `docs-backend/modules/identity/` — khớp điều kiện "sửa tài liệu chỉ bên trong thư mục Identity của backend docs" và BE-DOC-03.

---

## 1. Tóm tắt review và baseline

### 1.1. Baseline đã review

| Hạng mục | Giá trị |
|---|---|
| Branch tham chiếu | `origin/dev2` (fetch ngày 16/11/2026) |
| Commit SHA | `56210cc80ad742ddad9139dc1a08fe9ff524465a` (`git rev-parse origin/dev2`) |
| Commit cuối trên dev2 | `56210cc Fixing dependent transfer docs` |
| Worktree backend | `digitalbanking-backend/` đang checkout đúng tại `56210cc` (branch `datht-backend-rework` trùng `origin/dev2`) — chỉ đọc, không đổi |
| Tài liệu identity | `docs-backend/modules/identity/` (README mới sửa, `tasks/README.md` mới) — các thay đổi này nằm ngoài baseline dev2, được ghi rõ ở §1.3 |

### 1.2. Tài liệu và source đã kiểm tra

| Nhóm | Nguồn |
|---|---|
| Yêu cầu | `docs-common/02. BRD.md`, `03. SRS.md` (toàn bộ), `docs-common/design/01–05`, `docs-common/06. System architecture.md` |
| Hợp đồng | `docs-common/07. API contracts.md`, `openapi/digital-banking-v1.yaml` (30 operations, 91 schemas), `docs-backend/00. architecture & conventions.md` |
| Module docs | `docs-backend/modules/_overview/01-identity-auth.md`, `modules/common/README.md` + `CURRENT-STATE.md`, `modules/transfer/README.md` + `design.md`, `modules/{audit,admin}/README.md` (rỗng), `modules/identity/README.md` + `tasks/README.md` |
| Quyết định | `DECISIONS.md` (DEC-TRF-001, DEC-TRF-002), `PROGRESS.md` |
| Source dev2 | `digitalbanking-backend/src/main/java/com/simulatedbank/banking/**` (common triển khai đầy đủ; 10 module nghiệp vụ chỉ `.gitkeep`), `src/main/resources/db/migration/V1__init_schema.sql`, `build.gradle.kts`, `application.yml`, `src/test/java/**` |

### 1.3. Kết luận chính

1. **Toàn bộ chức năng Identity chưa được triển khai tại dev2.** `com.simulatedbank.banking.identity/` chỉ chứa `.gitkeep` ở 5 package chuẩn (adapter/in, adapter/out, api, application, domain); không có controller/service/entity/migration/test nào của identity. `V1__init_schema.sql` chỉ có `CREATE SCHEMA IF NOT EXISTS banking;` — **không có bảng identity nào tồn tại**. Tất cả năng lực Identity thuộc loại `MISSING` (§8).
2. **Nền tảng common đã có và đã được kiểm thử** (28 Java files, 203 tests pass theo report lịch sử [BE-COM-FINAL-REPORT]): `SessionAuthenticator` SPI, security filter chain + CSRF session-bound, error contract 46 mã, crypto (AES-256-GCM/HMAC/JCS/masking), request context, rate limit, JSON log. Identity chỉ cần điền phần thiếu, không tự tạo SPI trùng.
3. **Thiết kế nguồn đầy đủ nhưng nhiều giá trị "Đề xuất"**: BRL-003/004/005/006, NFR-016, ngưỡng step-up — chờ OI-001/OI-006 (BRD §11.4, "Cần xác nhận"). Không coi giá trị đề xuất là policy đã duyệt.
4. **Đã phát hiện một số xung đột giữa các nguồn** (10 phút vs 15 phút lockout; `status='CONSUMED'` trong design/04 nhưng CHECK constraint của `04` không có; `session.status='REVOKED'` trong design/04/overview nhưng enum `04` chỉ có `EXPIRED/LOGGED_OUT`; tên role trong design/04 §5.1 khác `04`/`07`). Được ghi trong §9 với cách phân xử theo thẩm quyền BE-ARCH-00 §1.5 (`docs-common/01–11` > `design/` > `docs-backend/`); chưa có mục nào tự đóng vượt thẩm quyền.
5. `PROGRESS.md` mô tả một vòng iteration trước (`IdentityModuleApi` `issueOtp/dispatchOtp/consumeOtp/invalidateOtp`, migration V8, 230 tests) **không tồn tại trong dev2** — xem OD-ID-13, không dùng làm bằng chứng hiện trạng.

---

## 2. Phạm vi và trách nhiệm Identity

### 2.1. Trách nhiệm sở hữu (write-owner)

Nguồn: `06. System architecture.md` §6.2–§6.3; `04. Database design & analysis.md` §4.2; `00. architecture & conventions.md` §5.1, §5.3; `design/05. Modules contract.md` §2.

| Vùng trách nhiệm | Dữ liệu/bảng sở hữu (`banking.*`) | Yêu cầu chính | Ghi chú |
|---|---|---|---|
| Định danh đăng nhập | `app_user` | FR-001, FR-005, FR-040 | `password_hash` Argon2id/bcrypt, `failed_attempt_count`, `locked_until`, `status (ACTIVE/LOCKED/DISABLED)`; không hard delete |
| Hồ sơ ánh xạ customer | *(bảng `customer` thuộc module `customer`; identity đọc qua projection)* | FR-008–FR-011 | `customer.user_id → app_user` 1:0..1 (`04` §4.2.2); identity cần mapping userId → customerId phải qua `customer.api.CustomerQuery.findByUserId` (`design/05` §6) |
| RBAC | `role`, `permission`, `user_role`, `role_permission`, `user_privilege` | FR-040, FR-050, FR-051 | Seed `CUSTOMER/OPERATIONS/ADMIN/AUDITOR` + permission Appendix A.2 của `04`; `CHECKER` là privilege (`user_privilege`), không phải role (`04` H-04) |
| Phiên | `session` | FR-006 | `session_token_hash` SHA-256, `csrf_token_hash`, idle/absolute expiry |
| Thiết bị | `device` | FR-002, FR-003 | `device_fingerprint_hash` HMAC, `trusted`, `fido_credential_id`, `passcode_hash` |
| Challenge/OTP | `auth_challenge`, `auth_attempt` | FR-004, FR-026, FR-042 | purpose `LOGIN_NEW_DEVICE/TRANSFER_STEP_UP/SENSITIVE_ACTION`; method `OTP/FIDO/PASSCODE`; state `CREATED→SENT→VERIFIED|LOCKED|EXPIRED`; consumed qua `consumed_at` (xem OD-ID-06) |
| Lịch sử mật khẩu | `password_history` | BRL-006 | So khớp 3 mật khẩu gần nhất |
| Xác thực tăng cường | `StepUpChallengePort` (`identity.api`) | FR-026, AC-033 | Transfer/Admin gọi; Identity không tạo transfer/ledger/idempotency |
| Lệnh thay đổi role | `IdentityRoleCommand` (`identity.api`) | FR-050, FR-051 | Admin gọi trong approval TX; Identity cập nhật bảng mình + revoke session theo policy |

**Identity KHÔNG sở hữu:** `transfer`, `idempotency_record` (transfer), `audit_log` (audit), `notification` (notification), `approval_request` (admin), `customer` (customer), `account` (account). Idempotency key toàn cục thuộc transfer (`design/02` §4.2, §6); `auth_challenge` chỉ **bind** key/hash, không lưu idempotency record (`design/02` §5.1).

### 2.2. Ranh giới phụ thuộc (không được vi phạm)

- `identity`, `customer`, `account` là nền tảng, không phụ thuộc `transfer`/`admin` (`06` §6.3; BE-CON-09; `design/05` §2).
- `common` không import identity; identity implement `common.config.SessionAuthenticator` (`design/05` §5.1: "Không tạo interface trùng để thay SPI này").
- Identity không gọi notification trực tiếp trong transaction; dispatch OTP **sau commit T0**, ngoài transaction (`design/05` §11.3 `sendOtp`: "Sau T0 commit, ngoài transaction"; `design/02` §5.1).
- ArchUnit hiện hành (`ModuleBoundaryArchTest` tại dev2): common không phụ thuộc module nghiệp vụ, common không sở hữu persistence/transaction, domain thuần, adapter.in không gọi adapter.out, không cycle.

---

## 3. Ma trận truy vết yêu cầu (SRS/BRD → Identity)

Cấu trúc đánh số SRS: FR/NFR (bảng §2, §3.1), UC (§5), US (§5.4), AC (§10.1), SYS-RL (§8.2), AUTH-01..06 (§4.5 map BRL-001..006 từ BRD §6.1). Chuỗi truy vết chuẩn: `BR- → FR/NFR → UC → AC` (SRS §1.8, §11.1).

Trạng thái triển khai tại dev2: **M** = MISSING (chưa có source), **C** = chỉ có support trong `common`, không phải phần identity.

### 3.1. Yêu cầu chức năng cốt lõi

| Yêu cầu | Mô tả (nguồn) | Chức năng Identity liên quan | Trạng thái dev2 | API | Bảng DB | Contract module | Ghi chú |
|---|---|---|---|---|---|---|---|
| BR-001 / FR-001 | Đăng nhập password, tạo session | Password login | M | API-AUTH-01 | `app_user`, `session`, `auth_attempt` | `07` §8.1.1 | UC-001; AC-001 |
| BR-001 / FR-002 | Passcode trên thiết bị tin cậy | Passcode login | M | API-AUTH-02 | `device.passcode_hash` | `07` §8.1.2 | UC-002; AC-002 |
| BR-001 / FR-003 | FIDO/biometric + fallback | FIDO login (begin/finish) | M | API-AUTH-03 | `device.fido_credential_id` | `07` §8.1.3 | UC-002; AC-002; OI-API-13 (purpose LOGIN) |
| BR-002 / FR-004 | OTP rủi ro cao: thiết bị mới, thay đổi nhạy cảm, giao dịch vượt ngưỡng; single-use/TTL/attempt | OTP issue/verify/consume | M | API-AUTH-04/05/08 | `auth_challenge` | `07` §8.1.4–8.1.5, §6 | UC-003, UC-015; AC-003, AC-033 |
| BR-003 / FR-005 | Đếm sai + khóa theo BRL-003 + audit | Lockout state machine | M | API-AUTH-01/02 | `app_user.failed_attempt_count/locked_until`, `auth_attempt` | `07` §8.1.1 | AC-001; giá trị 5 lần/10 phút là Đề xuất (OI-001/OI-006) |
| BR-003 / FR-006 | Session timeout (idle/absolute), logout | Session lifecycle | M | API-AUTH-06/07 | `session` | `07` §3.1, §8.1.6–8.1.7 | AC-004; SPI đã có ở common (C) |
| BR-005 / FR-007 | Sự kiện xác thực/bảo mật + correlation ID | Audit events Auth | M | — | `audit_log` (audit sở hữu) | `design/01` §5.3 | AC-001, AC-019; 4 action Đề xuất chưa vào DB A.4 (OD-ID-11) |
| BR-002 / FR-026 | Step-up auth cho transfer theo chính sách rủi ro | Step-up T0/T1 | M | 428 từ `POST /transfers`; API-AUTH-05 | `auth_challenge`, `transfer.step_up_challenge_id` | `07` §6; `design/05` §5.2 | UC-015; AC-033; protocol đồng thời BLOCKED (MOD-OI-01) |
| BR-034 / FR-042 | Gửi OTP qua kênh notification, TTL + attempts | OTP delivery | M | API-AUTH-08 | `auth_challenge.sent_at`, `notification.auth_challenge_id` | `design/05` §11.3 `sendOtp`; MOD-OI-07 | Dispatch sau commit, không lưu plaintext |
| BR-034 / FR-043 | Notification không chứa full số TK/nhạy cảm | OTP payload masking | M | — | — | `07` §2.6 | AC-025, AC-033 |
| BR-007 / FR-011, FR-040 | RBAC mọi endpoint + ownership | RBAC evaluation | C/M | mọi endpoint | `role`…, `user_privilege` | `07` §3.4–3.5; `design/04` §5 | Common đã có `@EnableMethodSecurity` + authority matrix (C); dữ liệu role + kiểm ownership identity/caller MISSING (M); AC-024 |
| BR-039 / FR-050 | Admin quản lý user/role/quyền qua approval | `IdentityRoleCommand.applyApprovedRoleChange` | M | API-ROL-01/02 (admin) | `user_role`, `user_privilege`, `session` | `design/05` §5.3, §12.1 | AC-028; revoke session theo policy |
| BR-039 / FR-051 | Maker/checker, cấm tự duyệt | Consume challenge SENSITIVE_ACTION | M | API-LIM-02/03, API-ROL-01/02 | `auth_challenge`, `approval_request` (admin) | `07` §6.5 | SYS-RL-021 |
| BR-025 / FR-035 | Audit cho đăng nhập/khóa/đổi role… | Audit events (cùng FR-007) | M | — | `audit_log` | `design/01` §5.3 | AC-019 |

### 3.2. NFR ràng buộc Identity (SRS §3.1)

| NFR | Target (nguồn SRS) | Áp dụng Identity | Trạng thái dev2 |
|---|---|---|---|
| NFR-003 (Should) | Xác thực/OTP p95 ≤ 1 giây (đề xuất) | Login/OTP latency; chưa có SLA test | M |
| NFR-013 (Must) | TLS 1.2+ (ưu tiên 1.3) | HSTS được set khi `request.isSecure()` trong `ApiSecurityHeadersFilter` (common); TLS tại Nginx theo `design/04` §6.1 | C (backend) — TLS tại proxy ngoài phạm vi backend |
| NFR-014 (Must) | Mã hóa dữ liệu nhạy cảm (AES-256 đề xuất) | `PiiCryptoService` sẵn có; PII của identity chủ yếu là hash, không có cột `_enc` riêng trong §4.2 | C |
| NFR-015 (Must) | Password hash Argon2id/bcrypt, không plaintext | `password_hash`, `passcode_hash`, `password_history` | M |
| NFR-016 (Must) | Hash OTP; TTL 5 phút; tối đa 5 lần thử; single-use (đề xuất) | `auth_challenge.otp_hash`, TTL, `max_attempts` | M |
| NFR-017 (Must) | RBAC mọi endpoint, least privilege | Authority matrix common + dữ liệu role | C/M |
| NFR-018 (Must) | OWASP: input validation, output encoding, CSRF | `SessionCsrfTokens`, `RequestBodyLimitFilter`, Bean Validation | C (framework) / M (identity DTO) |
| NFR-019 (Must) | Secret từ env/vault | `CryptoConfig` fail-fast | C (Verified qua `CryptoConfigTest`) |
| NFR-020 (Must) | Không lộ PII trong log/response/notification | `PiiMaskingUtils`, `SafeJsonLogEncoder` (bỏ message/args/MDC) | C |
| NFR-021/022/023 (Must) | Audit append-only/tamper-evident; retention ≥ 5 năm; 100% sensitive action có audit + correlation | Identity chỉ sinh `AuditEvent`; schema/chain thuộc audit (module chưa có) | M |
| NFR-025 (Must) | Structured log + correlation; metrics; health | common Verified (report 06) | C |
| NFR-028 (Must) | Lỗi rõ, có mã, không lộ dữ liệu nhạy cảm | Error contract common (46 mã, RFC 9457) | C |

### 3.3. Quy tắc nghiệp vụ và AC

| ID | Nội dung | Nguồn | Trạng thái dev2 | Gap chính |
|---|---|---|---|---|
| BRL-001 / AUTH-01 | Password bắt buộc lần đầu; passcode/FIDO theo chính sách thiết bị | BRD §6.1 ("Đề xuất") | M | Chính sách thiết bị chưa định nghĩa (§3.5 mục 2) |
| BRL-002 / AUTH-02 | OTP cho thiết bị mới/hành động rủi ro/vượt ngưỡng | BRD §6.1 | M | Ngưỡng từ DEC-TRF-002, scope/ngày chưa chốt (OD-ID-03) |
| BRL-003 / AUTH-03 | 5 lần sai → khóa 10 phút; 3 lần khóa/24h → khóa tài khoản | BRD §6.1 ("cần xác nhận") | M | Cần OI-001/OI-006; xung đột 15 phút ở design/04 §4.1 (OD-ID-01) |
| BRL-004 / AUTH-04 | OTP 5 phút, single-use, tối đa 5 lần thử | BRD §6.1 ("đề xuất") | M | TTL cấu hình; `AppProperties.Otp` default ttl 300s/maxVerify 5 khớp |
| BRL-005 / AUTH-05 | Session idle 15 phút; absolute 8 giờ; logout vô hiệu | BRD §6.1 ("đề xuất") | M | `AppProperties.Session` default khớp |
| BRL-006 / AUTH-06 | Password ≥ 8 ký tự, complexity, không trùng 3 password gần nhất | BRD §6.1 ("đề xuất") | M | **Không có FR/AC/API nào cho đổi password** (§3.5 mục 1) |
| BRL-007 | Customer chỉ truy cập dữ liệu mình; staff least privilege | BRD §6.1 ("Bắt buộc") | C/M | `07` §3.5; DAR-001..006 (SRS §4.3) |
| BRL-008 | Thay đổi nhạy cảm cần xác thực mạnh, maker/checker, audit | BRD §6.1 ("Bắt buộc") | M | `07` §6.5; `design/05` §12.1 |
| AC-001 | Sai 5 lần → `AUTH_ACCOUNT_LOCKED` (423) | SRS §10.1 | M | 423 + `lockedUntil` trong Problem |
| AC-002 | Passcode/FIDO sai vượt ngưỡng khóa theo BRL-003; fallback password | SRS §10.1 | M | Fallback policy chưa định nghĩa chi tiết |
| AC-003 | OTP thiết bị mới: sai/hết hạn từ chối; vượt 5 lần khóa | SRS §10.1 | M | 410 vs 401 ở UC-003 (OD-ID-08) |
| AC-004 | Idle 15'/logout → `AUTH_SESSION_EXPIRED` (401) | SRS §10.1 | C (filter/sốd) / M (identity expiry) | SPI test fail-closed đã có (`HttpSecurityServletTest`) |
| AC-019 | Login/lockout/đổi role… có audit record | SRS §10.1; design/01 §5.3 | M | C2/C3 đúng class theo design/01 §5.2 |
| AC-023 | PII mask gồm OTP/password | SRS §10.1; FR-039/NFR-020 | C | Masking utils Verified |
| AC-024 | Cross-customer → 403 + audit security event | SRS §10.1 | C/M | `SECURITY_ACCESS_DENIED` C3 |
| AC-028 | Maker/checker apply + audit; maker=checker bị chặn | SRS §10.1 | M | `ROLE_CONFLICT` 409 |
| AC-030 | Security checklist NFR-013..020 | SRS §10.1 | M | Chỉ có checklist design/04 §10–11 |
| AC-033 | Step-up: 428 không tạo transfer/idempotency/ledger; verify + retry cùng key đúng một lần | SRS §10.1; FR-004, FR-026, FR-042 | M | T-STEP-01/02; protocol BLOCKED (MOD-OI-01) |
| SYS-RL-013..017, 021, 026 | Enforcement RBAC/OTP/lockout/session/mask/maker-checker/step-up | SRS §8.2 | M | SYS-RL-026: không ghi ledger trước verify |

### 3.4. Use case Identity

| UC | Mô tả | Module tham gia | Trạng thái |
|---|---|---|---|
| UC-001 | Đăng nhập password + lockout | identity, audit | M |
| UC-002 | Đăng nhập passcode/FIDO nhanh | identity, audit | M |
| UC-003 | Đăng nhập thiết bị mới qua OTP, đánh dấu trusted | identity, notification, audit | M |
| UC-004 | Xem tài khoản (ownership) | account, customer, identity | ngoài Identity |
| UC-015 | Step-up transfer | identity, transfer, audit | M |
| UC-013 | Vai trò/quyền (maker/checker) | admin, identity, audit | M |

### 3.5. Yêu cầu thiếu / mơ hồ (không bịa yêu cầu — đánh dấu OPEN/BLOCKED)

| # | Vấn đề | Bằng chứng | Trạng thái |
|---|---|---|---|
| 1 | **Không có FR/AC/API cho đổi password/quên mật khẩu**, dù BRL-006 yêu cầu "không trùng 3 password gần nhất"; lifecycle tạo user (đăng ký customer, provisioning `app_user`) cũng không có endpoint (`07` chỉ có read `API-ADM-01/02`; FE screen `OPS-CREATE-CUSTOMER` tự ghi `GAP-01 chốt create customer/API… gắn FE-06`) | SRS §2.2/§10.1; BRD §6.1; `07` §8.2; `docs-frontend/design/screens/OPS-CREATE-CUSTOMER.md` §8 | **OPEN** — cần BA/Product định nghĩa FR + API trước BE-ID-IMP-09/12 |
| 2 | **Chính sách thiết bị tin cậy không được định nghĩa**: UC-003 "đánh dấu thiết bị tin cậy (nếu chính sách cho phép)"; không có FR quản lý/thu hồi device, không có AC riêng | SRS §5.2 UC-003, §7.3 DEVICE | **OPEN** |
| 3 | Đăng ký FIDO/passcode chỉ xuất hiện ở alternate flow "nếu được yêu cầu" — mơ hồ, không AC | SRS §5.2 UC-003 | **OPEN** |
| 4 | Toàn bộ giá trị auth (BRL-003..006, AUTH-03..06, NFR-016) là "đề xuất" chờ OI-001/OI-006 (owner STK-002/STK-004) | BRD §6.7, §11.4; SRS §11.4 | **OPEN** (không chặn viết code theo cấu hình, chặn nghiệm thu giá trị) |
| 5 | Ngưỡng step-up 10M/20M VND đến từ DEC-TRF-002 (ngoài SRS/BRD); SRS tự ghi "còn phải chốt và kiểm chứng trước release" | SRS §2.13.6; `DECISIONS.md` DEC-TRF-002 | **BLOCKED** cho release (MOD-OI-01) |
| 6 | `POST /auth/challenges/{challengeId}/resend` **public** cho `LOGIN_NEW_DEVICE` — quyết định bảo mật quan trọng nhưng không có BRL/AC bao phủ (chỉ dòng quyền SRS §4.2.1) | SRS §4.2.1; `07` §8.1.8 | **OPEN** — Security/BA phải rà |
| 7 | Không có FR riêng về rate limiting cho auth endpoints (`RATE_LIMITED` 429 mapped FR-005); ngưỡng ở `07` §2.8 là "đề xuất OI-API-06" | SRS §6.3; `07` §2.8 | **OPEN** (cấu hình common đã có giá trị default) |
| 8 | Session token refresh, session fixation, logout toàn thiết bị không đặc tả; `GET /auth/session` cấp lại CSRF là chi tiết duy nhất | SRS §6.2; `07` §8.1.7 | **OPEN** |
| 9 | Chính sách auth cho staff: "dùng cùng cơ chế với chính sách tương ứng" — chưa định nghĩa | SRS §5.1 note | **OPEN** |
| 10 | FR-002/FR-003 chia sẻ duy nhất AC-002; không có AC phân biệt WebAuthn assertion vs passcode | SRS §10.1 | **OPEN** |
| 11 | `OI-API-07` được SRS §4.2 tham chiếu nhưng không nằm trong danh mục OI-001–006 của BRD/SRS §11.4 (issue "ma") | SRS §4.2 note | **OPEN** |

---

## 4. Architecture review (tại dev2)

### 4.1. Pattern và tổ chức

- **Modular monolith Spring Boot 3.5.7 / Java 21 / PostgreSQL 16**, package-by-feature, 5 lớp chuẩn `api / adapter.in / application / domain / adapter.out` (BE-ARCH-00 §4; `06` §6.1). Source xác nhận: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/` — mỗi module nghiệp vụ đúng 5 package `.gitkeep`; chỉ `common/` + `BackendApplication.java` có code.
- **AD-01..AD-14** (BE-ARCH-00 §2.3; `06` §3.2): PostgreSQL system of record (AD-02), T0–T3 (AD-03), idempotency trong T1 (AD-04), audit trong T2 fail-closed (AD-05), opaque session (AD-06), challenge bind `request_hash` + key (AD-07), READ COMMITTED + lock order (AD-08).
- **Ranh giới enforce bằng ArchUnit** (`src/test/java/.../architecture/ModuleBoundaryArchTest.java`, 5 rules): common không phụ thuộc module nghiệp vụ (BE-CON-08/18); common không persistence/transaction (BE-CON-08); domain framework-free (BE-CON-35); adapter.in không gọi adapter.out (BE-CON-21); không cycle (BE-CON-11/19). Test này đã chạy xanh trong report common (không chạy lại trong task này).

### 4.2. Sản phẩm common mà Identity sẽ tiêu thụ (evidence theo path dev2)

| Thành phần | Path | Hành vi xác nhận từ source | Bằng chứng test |
|---|---|---|---|
| **SessionAuthenticator SPI** | `common/config/SessionAuthenticator.java` | `@FunctionalInterface` — `Optional<Session> authenticate(String opaqueToken)`; `record Session(String userId, String customerId, Set<String> authorities, String csrfTokenHash)` compact constructor validate: `userId` 1–19 digit, `customerId` optional cùng pattern, `csrfTokenHash` `[0-9a-f]{64}`; `toString()` → `Session[redacted]`; empty = token invalid → filter trả 401 `AUTH_SESSION_EXPIRED`; `BankingBusinessException` ném ra được forward kèm code/extensions | `HttpSecurityServletTest` (fail-closed khi chưa có bean — tất cả request 401), `HttpSecurityTest` (stub harness), `PostgreSqlErrorPipelineIntegrationTest` (stub) |
| SessionAuthenticationFilter | `common/config/SessionAuthenticationFilter.java` | Thu `Authorization: Bearer` + cookie `DB_SESSION`; **từ chối credential mơ hồ** (2 header/2 cookie/cả hai) → 401; token ≤512 ký tự regex `[A-Za-z0-9._~+/=-]+`; delegate `ObjectProvider<SessionAuthenticator>`; set `UsernamePasswordAuthenticationToken` + attribute `SESSION`/`COOKIE_AUTH`; cookie hết hạn trên public path không chặn login | `HttpSecurityTest`, `HttpSecurityServletTest` |
| SecurityFilterChainConfig | `common/config/SecurityFilterChainConfig.java` | Stateless, `NullSecurityContextRepository`; URL→authority matrix: **permitAll POST** cho `/api/v1/auth/login`, `/auth/passcode`, `/auth/fido/verify`, `/auth/otp/verify`, `/auth/challenges/*/resend`; `@EnableMethodSecurity`; `SessionAuthenticationFilter` trước `CsrfFilter`, `RequestRateLimitFilter` sau | `HttpSecurityTest`, `HttpSecurityServletTest` |
| CSRF session-bound | `common/config/SessionCsrfTokens.java` | `generateToken/saveToken` throw ("Identity must supply session-bound CSRF state" / "Identity owns CSRF token lifecycle"); `loadToken` trả `DefaultCsrfToken("X-CSRF-Token","_csrf", session.csrfTokenHash())`; `resolveCsrfTokenValue` = `HmacService.sha256Hex(header)` — client gửi raw token, CsrfFilter so SHA-256 với hash identity lưu; enforce chỉ với cookie-auth mutation | `HttpSecurityTest` |
| Error contract | `common/error/ErrorCode.java` (+ `ProblemResponse`, `ProblemWriter`, `BankingBusinessException`, `DatabaseErrorMapper`, `GlobalExceptionHandler`) | **46 mã public** khớp `07` §4.3 (AUTH_INVALID_CREDENTIALS 401, AUTH_ACCOUNT_LOCKED 423, AUTH_SESSION_EXPIRED 401, AUTH_FORBIDDEN 403, AUTH_DEVICE_NOT_TRUSTED 403, AUTH_CHALLENGE_NOT_FOUND 404, AUTH_CHALLENGE_EXPIRED 410, AUTH_CHALLENGE_LOCKED 423, AUTH_OTP_INVALID 401, AUTH_OTP_EXPIRED 410, STEP_UP_REQUIRED 428, STEP_UP_CHALLENGE_MISMATCH 409, OTP_DISPATCH_FAILED 503, …); RFC 9457; extension allowlist `{challenge, reference, transferStatus, failureCode, lockedUntil}` với `challenge` ⇔ `STEP_UP_REQUIRED` | `ErrorContractTest` (parse `07` + OpenAPI), `ErrorHttpTest`, `PostgreSqlErrorPipelineIntegrationTest` |
| Crypto | `common/crypto/{PiiCryptoService,HmacService,JcsCanonicalizer,PiiMaskingUtils}.java` + `CryptoConfig` | AES-256-GCM (IV 12B, blob IV‖CT‖tag); `hmacSha256Hex`, `sha256Hex`, `constantTimeEquals`; RFC 8785 JCS strict; masking `**** **** 0012`, `********1234`, `********123`, `n***@example.com`, `203.0.113.***`; fail-fast key 32-byte, cấm `previous-keys` (rotation chưa hỗ trợ) | `CryptoPrimitivesTest` (NIST vector), `CryptoOperationsTest`, `JcsCanonicalizerTest`, `PiiMaskingUtilsTest`, `CryptoConfigTest` |
| Rate limit | `common/config/RequestRateLimitFilter.java` + `RequestRateLimiter.java` | Process-local token bucket; route buckets: login 10/phút/IP, step-up verify 10/5 phút, otp verify 20/phút, transfer 30/customer/phút, audit export 5/giờ; denied → 429 `RATE_LIMITED` + `Retry-After`; **không chứng minh quota phân tán multi-instance** | `HttpSecurityComponentsTest` (atomicity 30/100), report common 05 (Blocked phần integration) |
| Body/context/log | `RequestBodyLimitFilter`, `CorrelationAndRequestIdFilter`, `RequestContext(Holder)`, `SafeJsonLogEncoder`, `HttpRequestLog` | Body 65536B → 413; correlation `[A-Za-z0-9._:-]{1,64}` sai → 400 + UUID echo; JSON log chỉ `HTTP_REQUEST_COMPLETED` với allowlist, `user_id` dạng `sha256:…` | `ContextHttpTest`, `ContextServletTest`, `RequestContextTest`, `ObservabilityIntegrationTest`, `readiness/PackagedApplicationReadinessIntegrationTest` |
| Cấu hình thời gian/secret | `AppProperties`, `TimeConfig` | Defaults liên quan identity: `cookie DB_SESSION maxAge 28800 (8h)`, `session idle 15m/absolute 8h`, `lockout 5/10m`, `otp ttl 300/recovery 120/maxVerify 5/resendCooldown 60/maxResend 3`, `stepUp single 10.000.000/daily 20.000.000 VND`, rate limits như trên; legacy `transfer.pinOtpThreshold 5.000.000` **cần migration không là trigger tiền thứ hai** (BE-API-26) | `AppPropertiesTest`, `BackendApplicationContextTest` |

### 4.3. Quy ước hiện hành Identity phải tuân theo

- **Transaction:** boundary ở `application` (BE-CON-16/27); `REQUIRES_NEW` chỉ cho audit C3/job (BE-CON-28; `design/01` §5.2); không giữ lock khi chờ OTP/user (BE-CON-30; `design/02` §5.6 "Không mở transaction khi chờ user input/OTP verification").
- **Locking:** T2 lock order toàn cục `transfer → auth_challenge → account ASC → limit_usage (CUSTOMER→ACCOUNT) → pg_advisory_xact_lock('audit:chain')` (BE-CON-17/BE-DAT-22, `00` §8.7–8.8). Identity-side: challenge row lock trong verify/consume (`design/02` §5.1, §7.7, §9.1).
- **Idempotency:** insert-first T1 (`design/02` §6.4, DD-CON-04); identity KHÔNG sở hữu idempotency record; 428 không tạo record; admin write chưa bắt buộc idempotency (OI-API-19, gap §9).
- **SQLSTATE mapping** (BE-CON-54): `55P03` owner phân loại in-progress/503; `40P01`/`57014` → 503 retryable; `23505` owner phân loại replay/conflict — không blanket 409.
- **Audit:** C1/C2 `MANDATORY` cùng TX fail-closed; C3 `REQUIRES_NEW` best-effort + security log + alert (BE-OBS-01..02; `design/01` §5.2). Auth events: `AUTH_LOGIN_SUCCESS` C2 cùng TX tạo session; `AUTH_CHALLENGE_CREATED/RESENT` C2; `AUTH_STEP_UP_VERIFIED` C2; `AUTH_LOGIN_FAILED/AUTH_ACCOUNT_LOCKED/AUTH_SESSION_LOGOUT/AUTH_CHALLENGE_LOCKED/AUTH_STEP_UP_FAILED` C3 (`design/01` §5.3).
- **Migration:** forward-only Flyway, `V2__create_identity_and_rbac_tables.sql` là file gợi ý chuẩn (BE-ARCH-00 §8.5; BE-DAT-15/16); không sửa V1; `ddl-auto: validate`.
- **Kiểm thử:** Testcontainers PostgreSQL, không H2, concurrency test connection độc lập, không mock chứng minh invariant (BE-TST-01..10); evidence theo `09` §10.

### 4.4. Đánh giá: pattern sẵn có đã đáp ứng nhu cầu Identity

Không cần kiến trúc mới: SPI session + CSRF plumbing + error contract + crypto + rate limit + log đã phủ các nhu cầu cross-cutting của Identity (`design/05` §12.2; common README §3.5: "Identity implement bean SessionAuthenticator… Customer ID phải từ identity đã xác thực"). Phần Identity phải tự xây: persistence identity, use case services, challenge state machine, OTP gateway port, step-up port, role command, controller/DTO cho API-AUTH-01..08.

---

## 5. Ma trận phụ thuộc module (cross-module dependency)

Nguồn hợp đồng: `design/05. Modules contract.md` §2, §5, §6, §11, §12; `07` §6; `design/01` §5.3. Status: theo dev2 (source reality) — contracts đều **Đề xuất/draft** chưa implement.

| # | Dependency | Provider | Consumer | Contract | Status tại dev2 | Vấn đề |
|---|---|---|---|---|---|---|
| 1 | Session SPI + CSRF | `common` | Identity (implement) | `SessionAuthenticator.authenticate(token) → Optional<Session>`; `SessionCsrfTokens` đọc hash từ snapshot | **IMPLEMENTED (common, có test)**; identity-side `MISSING` | Test bắt buộc chứng minh wiring identity thật, expiry/revoke (design/05 §5.1); không tạo SPI trùng |
| 2 | Error/Problem/crypto/context/log/rate-limit | `common` | Identity | 46 ErrorCode, RFC 9457, masking, JCS, token bucket | **IMPLEMENTED (Verified theo report common 01–04, 06)** | Rotation key chưa hỗ trợ; limiter process-local |
| 3 | Audit events Auth | `audit` (sẽ có) | Identity | `audit.api.AuditModuleApi.recordInTransaction` (C2 MANDATORY) / `recordStandalone` (C3 REQUIRES_NEW); `AuditEvent` do audit sở hữu | **MISSING cả hai phía** | C2 `AUTH_LOGIN_SUCCESS`/`AUTH_CHALLENGE_*`/`AUTH_STEP_UP_VERIFIED` fail-closed; C3 + 4 action Đề xuất chưa vào DB A.4 (OD-ID-11) |
| 4 | OTP gateway | `notification` (sẽ có) | Identity | `sendOtp(challengeRef, maskedDestination, otp tạm thời, expiry) → accepted/retryable/failed` — sau commit T0, ngoài transaction; không log/lưu plaintext | **MISSING cả hai phía** | Lifecycle crash/resend/invalidate = MOD-OI-07 (OPEN); dispatch fail → `503 OTP_DISPATCH_FAILED`, không tạo session |
| 5 | Step-up challenge | Identity | `transfer` (T0/T1), `admin` (approval) | `identity.api.StepUpChallengePort`: `requestChallenge` (OWN/T0), `verifyChallenge` (OWN/T0), `consumeVerified` (JOIN T1/approval) → `ConsumedChallenge(challengeId)` | **Đề xuất (design/05 §5.2); MISSING source** | Binding `request_hash`+`idempotency_key`; consume tối đa 1 lần; rollback T1 hoàn lại; protocol tổng ngày BLOCKED (MOD-OI-01) |
| 6 | Role command | Identity | `admin` | `identity.api.IdentityRoleCommand.applyApprovedRoleChange(approvalId, targetUserId, roleChange, expectedVersion, actorContext)` JOIN approval TX | **Đề xuất (§5.3); MISSING** | Stale version → conflict; revoke session theo policy; không import admin DTO |
| 7 | Mapping userId→customerId | `customer` | Identity (khi cần ánh xạ) | `customer.api.CustomerQuery.findByUserId(userId) → Optional<CustomerIdentity(customerId, userId, status, kycStatus)>` — R, snapshot tối thiểu | **Đề xuất (§6); MISSING** | Chỉ đọc; không import ngược; blind-index/masking thuộc customer |
| 8 | Session/permission snapshot cho mọi consumer | Identity | transfer, admin, mọi endpoint qua filter | `Session` (userId, customerId?, authorities, csrfTokenHash) qua SPI; ActorContext server tạo (`design/05` §3.1) | **SPI có, dữ liệu MISSING** | Permission snapshot theo policy; revoke tức thì khi đổi role |
| 9 | Bảng FK tham chiếu identity | identity (owner) | transfer (`transfer.step_up_challenge_id`, `initiated_by_user_id`), limit (`created_by/approved_by`), admin (`approval_request.maker_id/checker_id`), audit (`actor_user_id` NULLABLE cho SYSTEM), notification (`auth_challenge_id`) | FK vật lý `ON DELETE RESTRICT` (`04` §3.7) | **MISSING (chưa có bảng)** | `uq_transfer_step_up_challenge` partial unique — một challenge/transfer; audit dùng quan hệ logic polymorphic |
| 10 | Notification OTP record | notification | Identity (trace) | `notification.auth_challenge_id` FK NULL + `notification_type='OTP_DELIVERY'` (`04` §4.7.3) | **MISSING** | Không dùng record để đọc lại OTP đã hash (MOD-OI-07) |

**Kết luận §3.3 của đề bài (trùng lặp chức năng?):** không phát hiện module nào khác sở hữu chức năng của Identity. `common` giữ SPI kỹ thuật nhưng không mint session/không lưu CSRF/không kiểm ownership (common README §3.5; `design/05` §12.2); risk/fraud rule FRD-07 `AUTH_ABUSE` chỉ flag, khóa PIN vẫn theo Identity (`design/03` §4.2). Các dependency identity cần đều **đã có trong `design/05` §5/§6/§11/§12** → không có dependency chưa tài liệu hóa cần bổ sung phương án vào `05` theo AGENTS.md.

---

## 6. Phân tích workflow Identity

Mọi workflow dưới đây **chưa được triển khai** (dev2); trạng thái thực thi là thiết kế nguồn. Bảng đầy đủ bảng DDL: §7.2.

### 6.1. Đăng nhập password + lockout (UC-001, AC-001)

- **Preconditions:** `app_user` tồn tại, `status='ACTIVE'`; deviceRef optional.
- **Trigger:** `POST /api/v1/auth/login` (public, permitAll trong `SecurityFilterChainConfig`; rate limit login 10/phút/IP + 10/phút/username).
- **Steps:** (1) validate DTO (`LoginRequest` `additionalProperties:false`, username 1–64 normalize lowercase, password 1–128); (2) lookup `app_user` theo `username`; (3) **anti-enumeration**: user không tồn tại vẫn chạy Argon2id dummy verification, trả cùng `401 AUTH_INVALID_CREDENTIALS` (`design/04` §4.1; `07` §8.1.1); (4) verify password Argon2id; (5) kiểm `locked_until`/`status`.
- **Business validation:** sai password → tăng `failed_attempt_count` bằng **atomic UPDATE … RETURNING + row lock** chống lost update khi 5 request đồng thời (`design/02` §9.1; AC-06); đạt 5 → `status='LOCKED'`, `locked_until = now() + lock-duration` (10 phút theo BRL-003/`07` §3.7; `AppProperties.Lockout` default 5/10m), trả `423 AUTH_ACCOUNT_LOCKED` + `lockedUntil`; 3 lần khóa/24h → khóa tài khoản (BRL-003, đề xuất).
- **DB operations:** `app_user` update (OWN transaction; `design/04` §4.1 mô tả `REQUIRES_NEW` cho bộ đếm — khớp BE-CON-28 chỉ cho job/C3, cần owner chốt propagation ở BE-ID-IMP-02); insert `auth_attempt` (LOGIN/FAILURE, `client_info_masked` JSONB).
- **Module interactions:** audit C2 `AUTH_LOGIN_SUCCESS` cùng TX tạo session / C3 `AUTH_LOGIN_FAILED`, `AUTH_ACCOUNT_LOCKED` standalone (`design/01` §5.3).
- **Success:** thiết bị tin cậy → tạo session + `Set-Cookie: DB_SESSION` + `AuthResponse` 200; audit C2.
- **Failure:** 400/401/403/423/429/500/503 theo catalog; **không tiết lộ username tồn tại**.
- **Open:** OI-001/OI-006 giá trị lockout; OD-ID-01 (10 vs 15 phút).

### 6.2. Thiết bị mới + MFA login (UC-003, AC-003)

- **Trigger:** password đúng nhưng `device_fingerprint_hash = HMAC-SHA256(deviceRef|UA)` chưa thấy trong `device` hoặc `trusted=false`.
- **Steps:** tạo `auth_challenge` purpose `LOGIN_NEW_DEVICE`, method OTP, TTL 5 phút, `max_attempts=5` (BRL-004); audit C2 `AUTH_CHALLENGE_CREATED` cùng TX; **commit trước**, sau đó dispatch OTP qua gateway notification **ngoài transaction** (`design/05` §11.3).
- **Success:** `202 Accepted` + `ChallengeRequiredResponse` (`challengeId`, `maskedDestination`, `expiresAt`, `maxAttempts/remainingAttempts`); **chưa tạo session** (`07` §8.1.1; `design/04` §4.2).
- **Failure:** gateway lỗi → `503 OTP_DISPATCH_FAILED` (retryable), không tạo session; challenge invalidate theo policy (MOD-OI-07 OPEN).
- **Verify:** `POST /auth/otp/verify` (public) → đúng OTP: `attempt_count` tăng atomic; `status: SENT → VERIFIED`; tạo session + đánh dấu `trusted=true` "nếu chính sách cho phép" (chính sách OPEN, §3.5 mục 2); sai → `401 AUTH_OTP_INVALID` retryable trong giới hạn; hết hạn → `410 AUTH_OTP_EXPIRED`; vượt → `423 AUTH_CHALLENGE_LOCKED` + C3 `AUTH_CHALLENGE_LOCKED`.
- **Resend:** `POST /auth/challenges/{id}/resend` — **public cho `LOGIN_NEW_DEVICE`** (OPEN OD-ID-07); tối đa 3 lần (default `AppProperties.Otp.maxResend=3`); reset TTL nhưng không reset attempt đã dùng; OTP cũ vô hiệu; C2 `AUTH_CHALLENGE_RESENT`.

### 6.3. Passcode / FIDO login (UC-002, AC-002)

- **Passcode:** `POST /auth/passcode`; yêu cầu `deviceRef` trusted + fingerprint khớp, không → `403 AUTH_DEVICE_NOT_TRUSTED`; verify `device.passcode_hash`; lockout chung `app_user` (AC-002 theo BRL-003); audit attempt_type `PASSCODE`.
- **FIDO:** `POST /auth/fido/verify` hai pha trên một endpoint (Begin 202 → `fidoRequestOptions` WebAuthn; Finish 200 → session); challenge begin lưu `auth_challenge` method=FIDO, `device_id`, `fido_credential_id`; `clientDataJSON`/challenge phải khớp khi finish; single-use; fallback passcode/password theo policy (INT-004 timeout 30s; IC-002). **Open:** DB `auth_challenge.purpose` chỉ có `LOGIN_NEW_DEVICE` cho login — FIDO trusted-device dùng purpose này hoặc bổ sung `LOGIN` (OI-API-13, OD-ID-05).

### 6.4. Step-up transfer T0 (UC-015, AC-033; FR-026)

- **Preconditions:** session hợp lệ; `POST /transfers` với `Idempotency-Key` hợp lệ; trigger per DEC-TRF-002: amount ≥ 10.000.000 VND hoặc tổng ngày dự kiến > 20.000.000 VND (tổng theo DEC-TRF-001); các trigger risk khác độc lập.
- **Steps (T0, OWN):** Transfer đánh giá → chưa có challenge VERIFIED phù hợp → gọi `requestChallenge` (purpose `TRANSFER_STEP_UP`, bind `user_id`, `request_hash` (SHA-256 JCS của payload chuẩn hóa), `idempotency_key`, `expires_at`, `correlation_id`) → audit C2 → commit → dispatch OTP sau commit → adapter trả **`428 STEP_UP_REQUIRED`** (Problem + extension `challenge`, `retryable=true`). **Không tạo transfer/idempotency_record/ledger** (`design/02` §5.1; `07` §6.2; AD-04).
- **Concurrency:** chỉ **một challenge active** cho mỗi `(idempotency_key, request_hash)` nhờ partial unique `uq_auth_challenge_active_stepup` (`04` §4.2.5 C-03). **BLOCKED:** hai request key khác nhau đồng thời có thể cùng dưới ngưỡng — protocol reservation/guard chưa được duyệt (MOD-OI-01; `design/05` §10.1; DEC-TRF-001).
- **Failure:** dispatch fail → 503, challenge invalidate; verify sai/expired/locked như §6.2; challenge không khớp request/key → `409 STEP_UP_CHALLENGE_MISMATCH`.

### 6.5. Verify + consume step-up trong T1 (AC-033, T-STEP-01/02)

- **Verify (OWN/T0):** lock challenge row; kiểm user/purpose/expiry/attempt; tăng `attempt_count` atomic; set `VERIFIED + verified_at`; audit C2 `AUTH_STEP_UP_VERIFIED` (`design/02` §5.1; `design/04` §4.4).
- **Consume (JOIN T1 của transfer):** atomic conditional update —`design/04` §4.4:
  `UPDATE banking.auth_challenge SET consumed_at… WHERE challenge_id AND user_id AND purpose AND request_hash AND status='VERIFIED' AND expires_at > clock_timestamp()` — 0 rows → từ chối (`403/409 STEP_UP_*`), chặn hai request dùng một challenge (T-STEP-01).
- **Rollback restore:** T1 rollback → `consumed_at` rollback theo, challenge về VERIFIED; T2 fail **không** hoàn challenge (T1 đã commit) — retry dùng transfer đã tạo (`design/02` §5.1). Transfer retry sau verify dùng cùng key; T2 không verify lại chỉ vì challenge hết hạn sau T1 (`design/05` §5.2).
- **DB note:** `transfer.step_up_challenge_id` unique partial = chốt cuối chống double-link (`04` §4.4.1).

### 6.6. Sensitive action (maker/checker) (BRL-008, SYS-RL-021)

- `POST /admin/limits`, `/admin/roles` (maker) và `.../approve` (checker): lần gọi chưa step-up → `428 STEP_UP_REQUIRED` purpose `SENSITIVE_ACTION` bind `approvalId` + hash payload; sau verify client retry với header `X-Step-Up-Challenge: <challengeId>` (chiều client→server; 428 của transfer không có header riêng — `07` §6.5, §2.2).
- Admin gọi `identity.consumeVerified` rồi `applyApprovedRoleChange`/`limit.applyApprovedLimitChange` trong **cùng approval transaction**; audit C2 `ROLE_CHANGE_*` cuối (`design/05` §12.1); stale version/maker=checker → conflict `ROLE_CONFLICT` 409; revoke session theo policy (OD-ID-04).

### 6.7. Session lifecycle (FR-006, AC-004)

- **Tạo:** token 32 bytes SecureRandom Base64Url; DB chỉ lưu `session_token_hash` = SHA-256; cookie `DB_SESSION` HttpOnly/Secure/SameSite=Strict/Path=/ Max-Age 28800 (`07` §3.1; `design/04` §4.3); CSRF raw trả client một lần, hash lưu `session.csrf_token_hash`.
- **Kiểm mỗi request:** qua SPI (M) — user/session active, expiry, revoke; idle 15 phút (`idle_expires_at = LEAST(now()+15m, absolute_expires_at)` theo design/04 §4.3 — cơ chế gia hạn chi tiết là đề xuất), absolute 8 giờ; quá hạn → `401 AUTH_SESSION_EXPIRED` (retryable).
- **Logout:** `POST /auth/logout` → `status='LOGGED_OUT'`, `logged_out_at`, xóa cookie; C3 `AUTH_SESSION_LOGOUT` sau khi session revoke commit (`07` §8.1.6; `design/01` §5.3).
- **Concurrent sessions:** MVP **cho phép**, revoke theo device/user là Deferred OI-API-12 (`07` §3.1); **không áp trần 3/1** từ `design/04` §4.3 (OD-ID-02).
- **Revoke khi đổi role:** theo policy `IdentityRoleCommand` (design/05 §5.3) — schema `04` chưa có cột `revoked_reason` (OD-ID-04).

### 6.8. RBAC + role change (FR-040/050/051, AC-024/028)

- Seed role/permission theo `04` Appendix A.2 (`CUSTOMER/OPERATIONS/ADMIN/AUDITOR`; `CHECKER` là privilege). Authority matrix runtime đã khai trong common (roles `ADMIN/AUDITOR/OPERATIONS`, permissions `ACCOUNT_READ_SELF`, `TRANSFER_CREATE`, `LIMIT_MAKER`, `AUDIT_EXPORT`, …); **dữ liệu RBAC + evaluation + snapshot vào `Session` là phần identity MISSING**.
- Ownership/anti-IDOR kiểm ở service (DAR-001..006); từ chối → `403 AUTH_FORBIDDEN` + C3 `SECURITY_ACCESS_DENIED`.
- Đổi role: chỉ qua approval (API-ROL-01/02) → `IdentityRoleCommand`; permission change seed/migration có kiểm soát (`04` §4.2.8 rules).

### 6.9. Idempotency liên quan Identity

- Key global 1–255, `request_hash` JCS/SHA-256 — **transfer sở hữu** `idempotency_record` (insert-first T1, DD-CON-04; `design/02` §6). Identity chỉ bind key/hash trong `auth_challenge` (`ix_auth_challenge_idempotency`, partial unique active).
- **Không idempotency cho auth endpoints** (login/passcode/FIDO/otp/verify/logout) — đúng nguồn (`07` §5: chỉ `POST /transfers`); **gap:** `POST /admin/limits`, `/admin/roles` chưa bắt buộc idempotency → retry có thể tạo approval trùng (OI-API-19, §9 OD-ID-10).
- 428/step-up không replay được; TTL key 24h (đề xuất BRL-013/OI-001).

### 6.10. Failure handling (tổng hợp)

| Lỗi | Phân loại | Phản ứng | Nguồn |
|---|---|---|---|
| Audit C2 fail (login success/challenge create/verified/role apply) | Non-retryable systemic | Rollback TX nghiệp vụ; không công bố state; `500 AUDIT_WRITE_FAILED`/503 | `design/01` §5.2, §8.6 |
| Audit C3 fail (login failed/lockout/logout/step-up failed) | Best-effort | Không rollback; security log JSON + metric + alert (DD-AUD-19); response giữ nguyên | `design/01` §5.2, §8.6 |
| OTP gateway fail | Retryable (503) | `OTP_DISPATCH_FAILED`; không tạo session/transfer; challenge invalidate theo policy (MOD-OI-07) | `07` §6.6; `design/02` §5.1 |
| DB fail/lock timeout/deadlock | Technical | Rollback; chỉ retry toàn transaction có giới hạn; SQLSTATE map BE-CON-54 | `00` §6.7; `design/02` §7.10 |
| Validation | Non-retryable | 400 `VALIDATION_ERROR` + `errors[]` | `07` §4.1 |
| Session expired/invalid | `AUTH_SESSION_EXPIRED` 401 retryable (đăng nhập lại); fail-closed khi không có bean SPI (đã test ở common) | — | `HttpSecurityServletTest` |
| Fraud engine fail | fail-open (engine only) — không liên quan OTP/lockout; OTP/PIN fail là **fail-closed** cho giao dịch cần xác thực | — | `design/03` §8 |

---

## 7. API và Database hiện trạng

### 7.1. API review (contract — chưa có code)

Catalog chuẩn: `07` §7.1 (API-AUTH-01..08) + OpenAPI `digital-banking-v1.yaml`. **Tất cả đều MISSING tại dev2** (không có controller); security chain đã whitelist đúng 5 path public (common `SecurityFilterChainConfig`).

| ID | Method Path | Auth | Request (bắt buộc) | Success | Lỗi chính | Ghi chú |
|---|---|---|---|---|---|---|
| API-AUTH-01 | POST `/auth/login` | Public | username 1–64, password 1–128, deviceRef?/deviceName?/deviceFingerprint? | 200 `AuthResponse` + cookie; hoặc 202 `ChallengeRequiredResponse` | 400/401/403/423 (`lockedUntil`)/429/503/500 | Anti-enumeration; rate limit IP+username |
| API-AUTH-02 | POST `/auth/passcode` | Public | username, passcode 6–8 digits, deviceRef, deviceFingerprint | 200 | 400/401/403 `AUTH_DEVICE_NOT_TRUSTED`/423/429 | Passcode chỉ trên trusted device |
| API-AUTH-03 | POST `/auth/fido/verify` | Public | oneOf Begin(username, deviceRef) / Finish(username, deviceRef, challengeId, fido{credentialId, clientDataJSON, authenticatorData, signature, userHandle?}) | 202 begin; 200 finish + session | 400/401/403/404/409/410/423/429 | WebAuthn options; single-use; OI-API-13 |
| API-AUTH-04 | POST `/auth/otp/verify` | Public | challengeId UUID, otp 6–8 digits | 200 + session; challenge VERIFIED/consumed | 400/401 `AUTH_OTP_INVALID`/404/409/410/423/429 | BRL-004 |
| API-AUTH-05 | POST `/auth/step-up/verify` | Auth (self) | oneOf StepUpOtp/StepUpPasscode/StepUpFidoRequest (`method` const per variant) | 200 `StepUpVerifyResponse{status:VERIFIED,…}` | 401/403/404/409 `STEP_UP_CHALLENGE_MISMATCH`/410/423/429 | Không tạo transfer; retry với cùng key hoặc `X-Step-Up-Challenge` |
| API-AUTH-06 | POST `/auth/logout` | Auth | không body | 204 + xóa cookie | 401/403(CSRF) | Idempotent nghiệp vụ |
| API-AUTH-07 | GET `/auth/session` | Auth | — | 200 `AuthResponse` (user + session{idleExpiresAt, absoluteExpiresAt, csrfToken, deviceTrusted}) | 401/500/503 | Endpoint bổ sung, cần phê duyệt (l.891 `07`) |
| API-AUTH-08 | POST `/auth/challenges/{challengeId}/resend` | Public (LOGIN_NEW_DEVICE) / Auth | không body (hoặc channel) | 202 `ChallengeRequiredResponse` | 400/401/403/404/409/410/423/429/503 | Max 3 resend; OTP cũ vô hiệu |

Giao diện module nội bộ Identity sẽ expose: `StepUpChallengePort` (3 operations), `IdentityRoleCommand` (`design/05` §5.2–5.3); Identity tiêu thụ: `AuditModuleApi`, OTP gateway `sendOtp`, `CustomerQuery.findByUserId` (tùy ánh xạ), SPI common.

**Gap API chính** (chi tiết từng mục tại §3.5 và §9): không có endpoint tạo user/customer/provisioning (`GAP-01` FE), không có API quản lý device, không có đổi password, không có tìm kiếm user cho role admin (OI-API-18), `API-LIM-03`/`API-ROL-02` dùng `{id}` = approvalId dễ nhầm (DD-API-06), `APPROVED` trong enum approval không bao giờ xuất hiện ở response approve, toàn bộ contract đang **Draft — chưa review Backend/FE/QA/Security/BA** (`07` §11.4).

### 7.2. Database review

**Hiện trạng dev2:** `V1__init_schema.sql` = chỉ `CREATE SCHEMA IF NOT EXISTS banking;`. **Không có bảng identity nào; không có index/trigger/role DB nào.** Migration identity là V2 (tên gợi ý `V2__create_identity_and_rbac_tables.sql`, BE-ARCH-00 §8.5). Tài khoản DB: `banking_migrator` (Flyway) vs `banking_app` (DML) đã cấu hình trong `application.yml` + `docker/postgres/init/01-create-roles.sh`, đã verify qua `PostgreSqlFlywayMigrationIntegrationTest` + readiness test.

**Schema identity theo thiết kế `04` §4.2 (sẽ tạo ở V2):**

| Bảng | PK | Unique/constraint chính | Điểm cần chú ý |
|---|---|---|---|
| `app_user` | `user_id` IDENTITY | `uq_app_user_username`; status CHECK ACTIVE/LOCKED/DISABLED; `failed_attempt_count ≥ 0` | username lowercase; không hard delete |
| `session` | `session_id` | `uq_session_token_hash` CHAR(64); FK user NOT NULL, device NULL; CHECK `absolute > created_at`, `idle >= last_activity` | **enum status CHỈ ACTIVE/EXPIRED/LOGGED_OUT** — không có REVOKED/revoked_reason (OD-ID-04); purge 30 ngày sau hết hạn (đề xuất) |
| `device` | `device_id` | `uq_device_ref` UUID; `uq_device_fido` (`fido_credential_id` NULL unique); `uq_device_user_fingerprint(user_id, fingerprint)`; status ACTIVE/REVOKED | FIDO = 1 cột, không có bảng passkey (multi-credential chưa model — gap `04` §5) |
| `auth_challenge` | `challenge_id` | `uq_auth_challenge_ref` UUID; purpose CHECK 3 giá trị; method CHECK OTP/FIDO/PASSCODE; channel CHECK; conditional checks (OTP⇒otp_hash+channel; FIDO⇒credential+device; PASSCODE⇒device; TRANSFER_STEP_UP⇒request_hash+idempotency_key); `attempt_count <= max_attempts`; **partial unique `uq_auth_challenge_active_stepup(idempotency_key, request_hash)` WHERE purpose='TRANSFER_STEP_UP' AND status IN ('CREATED','SENT','VERIFIED') AND consumed_at IS NULL** | **status enum KHÔNG có CONSUMED** — consume qua `consumed_at` (OD-ID-06); CANCELLED chưa có luồng định nghĩa |
| `auth_attempt` | `attempt_id` | không unique; type CHECK LOGIN/PASSCODE/FIDO/OTP/STEP_UP; outcome SUCCESS/FAILURE/LOCKED; `correlation_id` NOT NULL; `client_info_masked` JSONB | Không lưu credential/OTP |
| `password_history` | `history_id` | FK user NOT NULL | **retention mơ hồ**, không có trong `retention_policy.data_class` (gap `04` §5.1) |
| RBAC 5 bảng | composite PK | `user_privilege.privilege_code` CHECK ('CHECKER') | role_code/permission_code **không CHECK giá trị** (gap `04` §5.2) |

Bảng module khác tham chiếu identity: `customer.user_id` (UK), `transfer.initiated_by_user_id/step_up_challenge_id` (partial unique), `limit_config.created_by/approved_by` (CHECK `approved_by <> created_by`), `approval_request.maker_id/checker_id` (CHECK maker≠checker), `audit_log.actor_user_id` (NULL cho SYSTEM, actor_role snapshot), `notification.auth_challenge_id`. FK toàn bộ `ON DELETE RESTRICT`.

**Gap DB:** `auth_attempt`/`password_history`/`device` không có data_class trong `retention_policy` (gap 1 `04` §5); hash-chain formula lệch trong `04` §4.6.1 vs §9.4 (thuộc audit owner, OI-COM-05/hash_version — chỉ ghi nhận); không có bảng recovery/reset; threshold değerleri đều "đề xuất" chờ OI-001.

---

## 8. Đánh giá hiện trạng triển khai (classification per BE-ID-DOC-01 §3.6)

Baseline: `origin/dev2@56210cc`. Hạn chế xác minh: task này KHÔNG chạy lại test suite; trạng thái `VERIFIED` của common dựa trên (a) source tại worktree trùng commit 56210cc và (b) report lịch sử BE-COM-IMP-* (203 tests, chưa sign-off). Không có bất kỳ runtime identity nào để chạy.

### 8.1. Năng lực Identity

| Năng lực | Trạng thái | Bằng chứng (path dev2) | Gap còn lại | Follow-up |
|---|---|---|---|---|
| SessionAuthenticator SPI (interface) | **IMPLEMENTED (thuộc common)** | `common/config/SessionAuthenticator.java`; test fail-closed `HttpSecurityServletTest` | identity implement bean + persistence | BE-ID-IMP-03 |
| Session persistence + CSRF + cookie | **MISSING** | identity/ `*.gitkeep`; `SessionCsrfTokens` generateToken throw "Identity must supply session-bound CSRF state" | Toàn bộ storage/expiry/revoke | BE-ID-IMP-01/03 |
| Password login + Argon2id + anti-enumeration | **MISSING** | identity/ trống; common cấm dùng SHA/HMAC thay password hash (common README §3.3) | Toàn bộ | BE-ID-IMP-02 |
| Lockout (5/10m, 3 khóa/24h) | **MISSING** | — | Giá trị chờ OI-001/OI-006 | BE-ID-IMP-02 |
| OTP state machine issue/verify/consume | **MISSING** | — | AC-003/033, T-STEP-01/02 | BE-ID-IMP-05 |
| OTP gateway delivery/resend/recovery | **MISSING** | — | MOD-OI-07; không lưu plaintext | BE-ID-IMP-06 |
| Step-up port cho Transfer (T0/T1/428) | **MISSING** | — | Protocol MOD-OI-01 BLOCKED release | BE-ID-IMP-07 |
| Device trust + MFA login | **MISSING** | — | Chính sách trust OPEN | BE-ID-IMP-08 |
| Passcode + password history | **MISSING** | — | Policy password OPEN (OI-006) | BE-ID-IMP-09 |
| WebAuthn/FIDO verify + fallback | **PARTIAL — verification có code thật** | Yubico/COSE/nonce/counter, PostgreSQL locks, API-AUTH-03 và password+OTP mới | DEC-ID-010 duyệt extension; enrollment/Audit/Notification thật chưa có | [BE-ID-IMP-10](BE-ID-IMP-10.md) |
| RBAC storage/evaluation/role command | **MISSING** | — | Revoke session policy | BE-ID-IMP-11 |
| API-AUTH-01..08 REST | **MISSING** (path đã whitelist trong common) | `SecurityFilterChainConfig` | Toàn bộ DTO/validation | BE-ID-IMP-12 |
| Flyway V2 identity DDL | **MISSING** (V1 chỉ tạo schema) | `db/migration/V1__init_schema.sql` | Schema theo `04` §4.2 | BE-ID-IMP-01 |
| Audit events Auth | **MISSING** | — | 4 action Đề xuất chưa vào catalog DB | BE-ID-IMP-13 |
| Rate limit auth (username/pre-auth quota) | **PARTIAL** | `RequestRateLimitFilter` route buckets (login/step-up/otp) đã có; `RequestRateLimiter` javadoc "Identity can use this for normalized username quotas" | Quota username normalize, pre-auth challenge quota, phân tán | BE-ID-IMP-13 |
| Identity test suite | **MISSING** | — | — | BE-ID-TEST-01/02 |

### 8.2. Support infrastructure (common) — dùng được ngay, có giới hạn

`IMPLEMENTED`: error contract (46 mã, khớp `07`/OpenAPI — `ErrorContractTest`), crypto primitives (NIST vectors), JCS strict, masking, request context, security chain, JSON log, health, rate limiter cơ bản, cookie/session filter. `VERIFIED` theo report lịch sử (203 tests, 0 fail) — **chưa reviewer sign-off, chưa consumer integration** (common CURRENT-STATE §1). `BLOCKED` còn: wiring identity thật (report 05 AC01/02/04/05), token lookup protection, multi-instance quota.

**BUG:** không phát hiện BUG nào trong common tại baseline; BUG tiềm tàng khác biệt nguồn được liệt kê ở §9 (ví dụ SQL `status='CONSUMED'` của design/04 sẽ vi phạm CHECK constraint nếu copy nguyên văn — đây là lỗi tài liệu, không phải source).

---

## 9. Xung đột kiến trúc và quyết định mở (Open Decisions)

Mỗi mục: ID — yêu cầu liên quan — mâu thuẫn/thiếu — tác động — phương án — khuyến nghị — status. Quy tắc thẩm quyền: `docs-common/01–11` > `design/` > `docs-backend/` (BE-ARCH-00 §1.5); `07` quyết định ngữ nghĩa API.

| ID | Vấn đề | Nguồn mâu thuẫn | Khuyến nghị (không tự chốt vượt thẩm quyền) | Status |
|---|---|---|---|---|
| **OD-ID-01** | Thời lượng khóa sai liên tiếp: **10 phút** (BRL-003, `03` §6.3/`07` §3.7) vs **15 phút** (`design/04` §4.1) | 01–11 vs design/04 | Theo thẩm quyền → 10 phút; overview đã ghi hướng này (item công việc 2 của `01-identity-auth.md`); giá trị cấu hình `app.security.lockout.lock-duration-minutes`, chờ OI-001 xác nhận con số | **RESOLVED theo thẩm quyền nguồn; giá trị OPEN (OI-001)** |
| **OD-ID-02** | Trần phiên đồng thời 3 (CUSTOMER)/1 (STAFF) + auto-revoke (`design/04` §4.3) vs MVP cho phép, revoke Deferred (`07` §3.1, OI-API-12) | design/04 vs 07 | Theo thẩm quyền → không áp trần ở MVP; revoke theo device/user là Deferred; overview đã ghi | **RESOLVED theo thẩm quyền nguồn (07)**; cột revoked_reason chưa có trong `04` schema — cần DB owner nếu policy revoke muốn ghi lý do |
| **OD-ID-03** | Protocol step-up tổng ngày: hai key khác nhau cùng nhìn dưới ngưỡng; `428` không tạo transfer có thể bị phá | DEC-TRF-001/002; MOD-OI-01; `design/05` §10.1 | Cần Identity+Limit+Transfer+Architect+BA chốt protocol serialization/reservation + race test trước release; trước đó coi như BLOCKED | **BLOCKED (release gate)** |
| **OD-ID-04** | Session status khi revoke: `04` §4.2.3 enum `ACTIVE/EXPIRED/LOGGED_OUT` (không REVOKED, không revoked_reason) vs `design/04` §4.1/overview dùng `REVOKED`, `revoked_reason='ROLE_CHANGED'` | `04` vs design/04 vs docs-backend | Theo thẩm quyền → giữ enum `04`; policy revoke khi đổi role chọn: hết hạn `EXPIRED` + audit `after_state.reasonCode` (design/01 cho phép reasonCode); DB owner chốt nếu cần cột riêng | **OPEN — cần owner Identity/DB + Admin xác nhận** |
| **OD-ID-05** | `auth_challenge.purpose` cho FIDO trusted-device login: DB enum chỉ `LOGIN_NEW_DEVICE`; OI-API-13 đề xuất bổ sung `LOGIN` | `04` §4.2.5 vs `07` §8.1.3 | MVP có thể dùng `LOGIN_NEW_DEVICE` cho begin FIDO; bổ sung enum value là migration + API enum + test (BE-DAT-04) | **OPEN — OI-API-13** |
| **OD-ID-06** | Consume challenge: `design/04` §4.4 SQL đặt `status='CONSUMED'` nhưng CHECK `04` §4.2.5 **không có CONSUMED** và partial unique dùng `consumed_at IS NULL`; `design/02` §5.1 dùng `consumed_at` | design/04 vs `04`+design/02 | Theo thẩm quyền → consume = set `consumed_at` (status giữ VERIFIED), Copy nguyên SQL design/04 sẽ fail constraint | **RESOLVED theo thẩm quyền nguồn (04 + design/02); lưu ý khi viết BE-ID-IMP-05** |
| **OD-ID-07** | Resend public cho `LOGIN_NEW_DEVICE`: proof = challengeId UUID; không có BRL/AC; risk enumeration/OTP flooding | SRS §4.2.1; `07` §8.1.8 | Security/BA review: rate limit resend + entropy challengeId; cần AC riêng | **OPEN — cần Security sign-off** |
| **OD-ID-08** | Mã HTTP OTP hết hạn: catalog `410 AUTH_OTP_EXPIRED` vs UC-003 exception "401/410"; tương tự "vượt attempt → 423/429", "tài khoản khóa → 403/423" | SRS §5.2 vs `07` §4.3.1 | Theo thẩm quyền → catalog `07` (410/423); đồng bộ SRS khi chốt | **RESOLVED theo thẩm quyền (07); cần đồng bộ văn bản SRS** |
| **OD-ID-09** | Thiếu yêu cầu đổi password/quên mật khẩu + provisioning `app_user` (tạo user/customer tại quầy) | BRD/SRS/`07` (§3.5 mục 1) | BA/Product định nghĩa FR + API; không bịa endpoint | **OPEN/BLOCKED BE-ID-IMP-09/12** |
| **OD-ID-10** | Idempotency cho admin write (limits/roles) chưa bắt buộc → approval trùng khi retry | `07` §5/OI-API-19; `design/02` §6.9 | BA/API chốt; nếu bật, key + request_hash cho approval payload | **OPEN** |
| **OD-ID-11** | 4 audit action Auth là "Đề xuất" chưa có trong DB Appendix A.4: `AUTH_CHALLENGE_CREATED/RESENT`, `AUTH_STEP_UP_FAILED`, `SECURITY_ACCESS_DENIED` | `07` §9.4 vs `04` A.4 | Audit/DB owner bổ sung catalog khi chốt; Identity implement đúng class C2/C3 | **OPEN — owner Audit** |
| **OD-ID-12** | Tên role mâu thuẫn: `design/04` §5.1 dùng `OPERATIONS/RISK_COMPLIANCE/ADMIN_SECURITY` vs `04`/`07`/SRS §4.1 dùng `CUSTOMER/OPERATIONS/ADMIN/AUDITOR` | design/04 vs 01–11 | Theo thẩm quyền → 4 role chuẩn `01–11`; `design/04` §5.1 cần cập nhật khi owner review | **RESOLVED theo thẩm quyền (01–11); cần sửa design/04 (owner Security)** |
| **OD-ID-13** | `PROGRESS.md` mô tả iteration trước (transfer + `IdentityModuleApi` issueOtp/dispatchOtp/consumeOtp/invalidateOtp, migration V8, 230 tests) không tồn tại tại dev2 | `PROGRESS.md` vs dev2 source | Ghi nhận lệch; đề xuất: hợp đồng OTP lifecycle trong `05` §5.2/§11.3 là nguồn hiện hành; không dùng PROGRESS.md làm baseline | **OPEN — cần điều hành xác nhận PROGRESS.md thuộc vòng cũ** |
| **OD-ID-14** | `auth_challenge.method` đơn trị vs `methods` array trong 428/`Challenge` | `07` §6.3 (warning schema MVP) vs yaml | MVP: một challengeId một method; chọn method khác → challenge mới (OI-API-17) | **OPEN — OI-API-17** |
| **OD-ID-15** | Rotation key / hash_version audit / threshold policy còn mở từ common (OI-COM-05, OI-001, OI-006) | common CURRENT-STATE §4.1 | Nghiệm thu Identity phụ thuộc một phần các quyết định này (hash chain cần protocol chốt trước audit thật) | **OPEN — owner tương ứng** |

Các quyết định **đã có** (tham chiếu, không tạo mới): DEC-TRF-001 (thành phần tổng ngày), DEC-TRF-002 (ngưỡng 10M/20M) — ghi rõ trong `DECISIONS.md` là quyết định của Codex theo chỉ đạo điều hành, **không phải sign-off owner**.

---

## 10. Khuyến nghị follow-up (không triển khai trong task này)

Thứ tự theo `tasks/README.md` (BE-ID-IMP-01..13, BE-ID-TEST-01..03) và gates A–E:

1. **Gate A (trước IMP-01):** xử lý các OPEN trong §9: OD-ID-01/02/04/05/06 cần xác nhận DB/Security owner trước khi viết V2 DDL (bảng `session`/`auth_challenge` enum); OD-ID-07 cần Security; OD-ID-03 giữ BLOCKED cho release.
2. **BE-ID-IMP-01 (P0):** migration V2 identity/RBAC theo `04` §4.2, forward-only, verify constraint/partial unique trên PostgreSQL Testcontainers.
3. **BE-ID-IMP-02..04 (P0):** login/Argon2id/lockout → session/CSRF implement SPI → integration RBAC. Bằng chứng bắt buộc: wiring `SessionAuthenticator` thật (design/05 §13.2 contract test "Identity session/step-up"), expiry/revoke fail-closed, không mock để đóng gate.
4. **BE-ID-IMP-05..07 (P0):** OTP state machine, gateway delivery (MOD-OI-07 lifecycle), StepUpChallengePort + consume T1 với race tests T-STEP-01/02.
5. **Gate D:** device/passcode/FIDO/RBAC command/API DTO/audit theo IMP-08..13.
6. **Đồng bộ tài liệu sau chốt:** sửa `design/04` §4.1/§4.3/§5.1 (15 phút, trần session, role names) theo thẩm quyền; đồng bộ SRS §5.2 exception codes với `07` §4.3; bổ sung action Đề xuất vào A.4 — thực hiện bởi owner tương ứng, không phải BE-ID-DOC-01.
7. **Nghiệm thu:** theo Definition of Done BE-ARCH-00 §11.3; D4 security checklist AC-030; không đánh Verified khi thiếu PostgreSQL evidence (BE-TST-06).

---

## 11. Checklist hoàn thành BE-ID-DOC-01

| Tiêu chí nghiệm thu | Kết quả | Bằng chứng |
|---|---|---|
| Commit SHA `origin/dev2` được ghi nhận | **PASS** | §1.1: `56210cc80ad742ddad9139dc1a08fe9ff524465a` |
| SRS/BRD yêu cầu liên quan đã review | **PASS** | §3 (FR/NFR/AC/BRL/SYS-RL/UC), nguồn SRS §2.2/§3.1/§4/§10, BRD §4.2/§6 |
| Scope & trách nhiệm Identity rõ | **PASS** | §2 |
| Architecture & coding patterns nhận diện | **PASS** | §4 (common inventory + BE-* conventions) |
| Module contracts đã review | **PASS** | §5 (`design/05` §5/§6/§11/§12; `07` §6) |
| Dependency Common/Transfer/Audit/Admin được ghi | **PASS** | §5 (ma trận 10 dòng) |
| Processing/validation/control/idempotency/failure review | **PASS** | §6.1–6.10 |
| API & DB hiện trạng được đánh giá | **PASS** | §7 (API-AUTH-01..08 + schema `04` §4.2; DB thực tế: chỉ V1 schema) |
| Implementation mapped to requirements | **PASS** | §8 (status classification) |
| Traceability matrix được cung cấp | **PASS** | §3.1–3.4 |
| Conflicts/missing contracts được ghi | **PASS** | §9 (15 OD) + §3.5 (11 gap yêu cầu) |
| Vấn đề chưa giải đánh dấu OPEN/BLOCKED | **PASS** | §3.5, §9 |
| Next tasks đề xuất không triển khai | **PASS** | §10 (chỉ khuyến nghị; không sửa source) |
| Tài liệu theo convention repo | **PASS** | metadata BE-ARCH-00 §12.1, wikilink §12.4, change log |
| Thay đổi tài liệu chỉ trong identity docs | **PASS** | chỉ `docs-backend/modules/identity/` (BE-ID-DOC-01.md + tasks/README.md) |
| Không sửa application source code | **PASS** | `git status`: chỉ docs; backend worktree không đổi |
| Tài liệu sẵn sàng team review | **PASS** | trạng thái Draft — chờ review |

## Change Log

| Version | Ngày | Người cập nhật | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Codex (owner: Đạt) | Tạo BE-ID-DOC-01: baseline dev2@56210cc, truy vết SRS/BRD, architecture review, dependency matrix, workflow, API/DB hiện trạng, 15 open decisions, khuyến nghị follow-up. Chỉ tài liệu, không code. |
| 1.1 | 16/11/2026 | Codex (owner: Đạt) | BE-ID-IMP-01 thực hiện sau review: `V2__create_identity_and_rbac_tables.sql` + test Testcontainers; OD-ID-04/05/06 phân xử theo thẩm quyền nguồn cho DDL (§8.1 dòng "Flyway V2" trở nên lỗi thời so với worktree hiện tại, trạng thái cập nhật tại `tasks/README.md`). Seed RBAC trong V2 theo DEC-ID-001 trong `DECISIONS.md`. |
