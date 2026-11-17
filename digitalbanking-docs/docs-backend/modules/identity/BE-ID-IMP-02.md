# BE-ID-IMP-02 — Password login, Argon2id, Logout & Anti-Enumeration: báo cáo triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-IMP-02`
> **Phiên bản:** v1.0
> **Ngày:** 16/11/2026
> **Trạng thái:** DONE (theo scope đã duyệt; các phần BLOCKED nêu rõ §4)
> **Owner:** Đạt (module identity)
> **Branch:** `datht-backend-rework` (baseline `origin/dev2@56210cc` + V2 của IMP-01)
> **Nguồn chuẩn:** `07. API contracts.md` §7.2.3/§8.1.1/§8.1.6/§4.3; `design/04. security.md` §4.1–§4.3; `design/02. consistency-concurrency.md` §9.1; `design/01. audit-traceability.md` §5.2–§5.3; `04. Database design & analysis.md` §4.2.1/§4.2.3/§4.2.6; common `SessionAuthenticator` SPI, error contract.

## 1. Tóm tắt triển khai

| Năng lực | File chính | Ghi chú |
|---|---|---|
| Password login API-AUTH-01 | `identity/adapter/in/AuthController.java`, `adapter/in/LoginRequest.java`, `adapter/in/AuthResponse.java` | `POST /api/v1/auth/login`, public, Bean Validation theo 07 §8.1.1; `Set-Cookie: DB_SESSION` |
| Luồng login + lockout + anti-enumeration | `identity/application/PasswordLoginService.java` | Normalize username lowercase; unknown user vẫn chạy Argon2id dummy verify; cùng `401 AUTH_INVALID_CREDENTIALS` (code+detail giống hệt); `423 AUTH_ACCOUNT_LOCKED` + extension `lockedUntil` (Instant); `403` cho `DISABLED`; success reset counter trong TX fail-closed |
| Argon2id | `identity/adapter/out/Argon2PasswordHasher.java` | m=65536 KiB, t=3, p=4, salt 16B, hash 32B (design/04 §4.1); PHC format; constant-time compare; dummy hash sinh một lần khi load class (BouncyCastle `Argon2BytesGenerator`) |
| Lockout nguyên tử | `identity/adapter/out/JdbcUserAccountRepository.java` | Failed attempt = **một** `UPDATE … RETURNING` có điều kiện (row lock), SET `status='LOCKED'`, `locked_until` khi đạt ngưỡng — chống lost update (design/02 §9.1); unlock khi `locked_until <= now()`; reset counter khi thành công |
| Session + CSRF + SPI | `identity/adapter/out/JdbcSessionRepository.java`, `IdentitySessionAuthenticator.java` | Token 32B SecureRandom Base64Url; DB chỉ lưu SHA-256 hash token + hash CSRF (`session.csrf_token_hash`, DEC-ID-002); SPI validate + gia hạn idle trong một `UPDATE` fail-closed; authority snapshot từ RBAC seed |
| Logout API-AUTH-06 | `identity/application/LogoutService.java` + `AuthController` | `POST /api/v1/auth/logout` (authenticated; cookie-auth bắt buộc CSRF theo chain common); `status='LOGGED_OUT'` + `logged_out_at`; idempotent; xóa cookie; token sau logout không dùng lại được |
| Audit port + adapter tạm | `identity/application/AuthAuditPort.java`, `adapter/out/SecurityLogAuditAdapter.java` | 4 action đã duyệt: `AUTH_LOGIN_SUCCESS` (C2, cùng TX), `AUTH_LOGIN_FAILED`/`AUTH_ACCOUNT_LOCKED`/`AUTH_SESSION_LOGOUT` (C3 standalone best-effort); metric `auth.audit.events` |
| DB audit nghiệp vụ | `banking.auth_attempt` | Row LOGIN với outcome SUCCESS/FAILURE/LOCKED + reason_code nội bộ + `client_info_masked` (IP/UA đã mask) |
| DB failure | Services map `JpaSystemException`/`CannotCreateTransactionException`/`DataAccessResourceFailureException` → `503 SERVICE_UNAVAILABLE` (07 §8.1.1/§8.1.6), fail-closed | Đã bổ sung vì common `DatabaseErrorMapper` map lỗi mở-TX về 500 (DEC-ID-003 mục 7, chờ common owner rà) |
| Migration | `db/migration/V2__create_identity_and_rbac_tables.sql` | Bổ sung `session.csrf_token_hash CHAR(64) NOT NULL` (DEC-ID-002) — file chưa commit của IMP-01 |

Cấu trúc layer đúng quy ước 5 package: controller ở `adapter/in` chỉ gọi `application`; port trong `application`, JDBC impl ở `adapter/out` (ArchUnit `ModuleBoundaryArchTest` pass).

## 2. Quyết định thuộc task (chi tiết tại `DECISIONS.md`)

- **DEC-ID-002:** thêm `csrf_token_hash` vào `V2` — xung đột `04` §4.2.3 vs `design/04` §4.3 + contract SPI common; chờ DB/Security owner review.
- **DEC-ID-003:** audit persistence là **mock có kiểm soát** theo design/01 (module audit chưa có — `AuditModuleApi` MISSING hai phía); OTP thiết bị mới (UC-003, 202) BLOCKED theo IMP-05/06/08 + notification; `customerId` = null (customer module chưa có); BCrypt compat và "3 khóa/24h" (đề xuất OI-001/OI-006) chưa triển khai; username rate-limit thuộc IMP-13.
- Transaction: boundary ở `application` (BE-CON-16/27); verify password ngoài TX; success path một TX OWN gồm reset counter + insert session + `auth_attempt` + audit C2 (fail-closed). design/04 §4.1 mô tả counter bằng REQUIRES_NEW — BE-CON-28 chỉ cho phép REQUIRES_NEW cho audit C3/job nên giữ OWN; ghi nhận chờ owner xác nhận.

## 3. Bằng chứng kiểm thử

Lệnh: `./gradlew test` — **BUILD SUCCESSFUL, 29 classes / 242 tests / 0 fail / 0 skip** (2026-11-16).

Bộ test identity (31 tests, file tại `src/test/java/com/simulatedbank/banking/identity/`):

| File | Tests | Phủ |
|---|---|---|
| `Argon2PasswordHasherTest` | 4 | T06, T07, T08, dummy hash hợp lệ + ổn định |
| `PasswordLoginServiceTest` | 14 | T01–T04, T08–T10, T12–T14, T17–T19, T21, T22 (unit, mock port theo hợp đồng) |
| `LogoutServiceTest` | 2 | T15, T16 (unit) |
| `IdentityAuthPostgreSqlIntegrationTest` | 10 | T01, T02+T04, T05, T09+T10, **T11 (5 request failed đồng thời, CyclicBarrier, không lost update)**, T12, T13+T14, T15 (kèm CSRF 403), T16, T22 — PostgreSQL thật (Testcontainers), HTTP thật qua filter chain + SPI thật |
| `IdentityAuthDbFailureIntegrationTest` | 1 | T21 — dừng PostgreSQL container giữa chừng → 503 fail-closed, không credential nào cấp |

Kết quả nổi bật:

- **T11 (concurrency, bắt buộc):** 5 thread cùng sai mật khẩu, đồng bộ bằng `CyclicBarrier`; kết quả đúng 4×401 + 1×423; `failed_attempt_count = 5` (không lost update), `status='LOCKED'`, `locked_until` set; 5 row `auth_attempt` (4 FAILURE + 1 LOCKED).
- **T04 (anti-enumeration):** Problem 401 của unknown user và sai password giống hệt sau khi khử `correlationId`/timestamp; không lộ username trong body; timing: cả hai path chạy đúng một phép Argon2id full-cost (unknown → dummy hash).
- **T15/T16:** cookie-auth logout thiếu CSRF header → `403 AUTH_FORBIDDEN`; có CSRF → `204` + `Set-Cookie` Max-Age=0; session `LOGGED_OUT`; SPI `authenticate(token)` → empty; logout lặp → `401 AUTH_SESSION_EXPIRED` (07 §8.1.6 idempotent theo token).
- **T21:** container stop giữa chừng → `503 SERVICE_UNAVAILABLE`, response không chứa DB internals.
- **T22:** không password/hash/raw token trong AuthResponse/Problem; `csrfToken` raw chỉ xuất hiện một lần tại login (đúng design/04 Level 4); log audit không chứa password/hash (ListAppender capture).

## 4. Còn mở (không mark DONE các mục sau)

1. Audit persistence thật (`AuditModuleApi` + `audit_log`, C2 fail-closed thật) — BE-ID-IMP-13.
2. UC-003 OTP thiết bị mới (202 ChallengeRequiredResponse) — BE-ID-IMP-05/06/08 + notification (MOD-OI-07).
3. `customerId` mapping — chờ `CustomerQuery.findByUserId` (design/05 §6).
4. BCrypt compat + password history — chờ FR đổi password (OD-ID-09) và BE-ID-IMP-09.
5. "3 lần khóa/24h" — chờ OI-001/OI-006 xác nhận giá trị.
6. Rate limit per-username — BE-ID-IMP-13.
7. Common owner rà: (a) `DatabaseErrorMapper` map lỗi mở-TX → 500 thay vì 503 (identity đã vá cục bộ); (b) `04` §4.2.3 thiếu `csrf_token_hash` (DEC-ID-002).

## Change Log

| Version | Ngày | Người | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Codex (owner: Đạt) | Tạo báo cáo triển khai BE-ID-IMP-02: login/Argon2id/lockout/anti-enumeration/logout/SPI/audit port + 31 tests pass trên PostgreSQL thật. |
