# BE-ID-IMP-03 — Opaque session, CSRF binding, logout & SessionAuthenticator SPI: báo cáo triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-IMP-03`
> **Phiên bản:** v1.0
> **Ngày:** 16/11/2026
> **Trạng thái:** DONE (theo scope đã duyệt; các phần BLOCKED/mock nêu rõ §6)
> **Owner:** Đạt (module identity)
> **Branch:** `datht-backend-rework` (baseline `origin/dev2@56210cc` + V2 của IMP-01 + phần chưa commit của IMP-02)
> **Nguồn chuẩn:** `07. API contracts.md` §3.1/§7.2.3/§8.1.6/§8.1.7; `design/04. security.md` §4.3/§6.2; `design/05. Modules contract.md` §5.1/§12.2; `03. SRS.md` AC-004; `04. Database design & analysis.md` §4.2.3; common `SessionAuthenticator`/`SessionCsrfTokens`/`SessionAuthenticationFilter`.

## 1. Tóm tắt triển khai

Phần lớn năng lực thuộc scope IMP-03 đã được IMP-02 dựng sẵn trên worktree (token opaque, hash SHA-256, CSRF session-bound, logout idempotent, SPI wiring). Task này rà lại toàn bộ implementation đối chiếu AC-004, vá 2 lỗ hổng fail-open/fail-wrong-code, bổ sung bộ test còn thiếu theo bảng ID-03-T01..T20 và chứng minh không có filter/SPI trùng.

| Năng lực | Trạng thái đầu task | Hành động IMP-03 |
|---|---|---|
| Token opaque 32B SecureRandom Base64Url, DB chỉ lưu SHA-256 hash (`session_token_hash` unique) | Có (IMP-02, `PasswordLoginService.freshToken`) | Rà lại + test T17 fixation |
| Idle/absolute expiry, gia hạn idle `LEAST(now()+idle, absolute)` trong một UPDATE fail-closed | Có (`JdbcSessionRepository.validateAndRefresh`) | Thêm test T05/T06/T07/T08 biên timestamps |
| CSRF binding vào session (`SessionCsrfTokens` đọc hash từ snapshot) | Có (common) | Thêm test T10/T11 (sai proof, proof phiên khác) |
| Logout revoke + cookie clear + idempotent | Có (IMP-02) | Thêm test T12/T13/T15 (race logout/auth) |
| SPI `SessionAuthenticator` implement + wiring filter | Có (IMP-02) | Test T01/T18 + T19 (không duplicate) |
| **DB failure khi xác thực phiên → fail-open raw exception → 500** | **LỖ HỔNG** | **Vá: map `503 SERVICE_UNAVAILABLE`** trong `IdentitySessionAuthenticator` |
| **Thiếu credential ở `/auth/logout` → `NoSuchElementException` → 500** | **LỖ HỔNG** | **Vá: check session attribute trước → `401 AUTH_SESSION_EXPIRED`** |
| Bộ test ID-03-T01..T20 | Thiếu 10 scenario | Bổ sung 11 test mới (§4) |

## 2. File thay đổi (thực tế)

| File | Thay đổi |
|---|---|
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/out/IdentitySessionAuthenticator.java` | Wrap `authenticate()` try/catch `DataAccessException` + `CannotCreateTransactionException` → `BankingBusinessException(SERVICE_UNAVAILABLE)` (503 retryable, `07` §8.1.1/§8.1.6, DEC-ID-003 mục 7). Session/user/authority đọc lỗi → KHÔNG cấp phiên, fail-closed. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/in/AuthController.java` | `logout()`: đọc `SessionAuthenticationFilter.session` attribute (401 `AUTH_SESSION_EXPIRED` nếu vắng) TRƯỚC khi extract token — thiếu credential không còn rơi 500. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityAuthPostgreSqlIntegrationTest.java` | +10 test ID-03 (T01–T08, T10–T12, T15, T17, T19) + helper `expireSession`/`column`; nâng quota rate-limit test-only (`login-per-ip-per-minute: 1000`) vì class dùng chung IP loopback với >10 login — quota per-IP/username thật đã được kiểm chứng riêng ở `HttpSecurityComponentsTest`. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityAuthDbFailureIntegrationTest.java` | T21 mở rộng: xác thực phiên đã cấp qua SPI thật khi DB chết → 503 `SERVICE_UNAVAILABLE`, response không chứa token (ID-03-T16). |

Không đổi schema, không đổi migration, không đổi common contracts, không tạo SPI/filter/repo trùng.

## 3. Hành vi session/CSRF/logout (đối chiếu contract)

- **Opaque:** token 32 byte `SecureRandom` Base64Url-no-padding (`[A-Za-z0-9_-]{43}`); DB chỉ có `session_token_hash CHAR(64)` (SHA-256) + `csrf_token_hash`; snapshot SPI `Session[redacted]` không chứa raw token. Không có trường nhúng identity vào token.
- **Kiểm mỗi request:** `SessionAuthenticationFilter` thu credential duy nhất (Bearer XOR cookie `DB_SESSION`, mơ hồ → 401) → SPI → `validateAndRefresh` là MỘT `UPDATE … WHERE status='ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now() RETURNING` — revoked/idle/absolute hết hạn đều trả rỗng → 401 `AUTH_SESSION_EXPIRED`, không fallback anonymous.
- **Gia hạn idle:** `idle_expires_at = LEAST(now() + idleWindow, absolute_expires_at)` (design/04 §4.3); absolute KHÔNG bao giờ được dịch bởi activity; UPDATE có điều kiện → dòng đã hết hạn không bị đụng tới (last_activity giữ nguyên — test T05/T08).
- **Concurrent:** refresh chỉ UPDATE dòng `ACTIVE`; revoke điều kiện `WHERE status='ACTIVE'`. Theo READ COMMITTED, hai UPDATE xen kẽ chỉ cho hai kết cục: refresh trước → revoke vẫn ghi LOGGED_OUT; revoke trước → refresh trả rỗng. Không thể hồi sinh phiên đã logout (test T15, CyclicBarrier + 4 thread × 40 lần).
- **CSRF:** `SessionCsrfTokens.loadToken` đọc hash từ session snapshot; client gửi raw token `X-CSRF-Token`, `CsrfFilter` so SHA-256(header) với hash phiên hiện tại → proof của phiên khác không khớp (T11); phiên revoked/expired → không có snapshot → CSRF fail. Enforce chỉ với cookie-auth mutation (`requireCsrfProtectionMatcher`), logout không được miễn (T15 IMP-02).
- **Logout:** `POST /api/v1/auth/logout` (authenticated): `status='LOGGED_OUT'`, `logged_out_at`, cookie `Max-Age=0`; idempotent nghiệp vụ — token đã logout replay → `401 AUTH_SESSION_EXPIRED`; revoke không phụ thuộc client-side cookie. Không logout-all (MVP cho phép multi-session, revoke theo device/user là Deferred OI-API-12 — `07` §3.1).
- **Fail-closed:** thiếu session 401; token lạ/sai định dạng 401; DB chết 503; không có bean SPI → 401 (common `HttpSecurityServletTest`).
- **Không rò rỉ:** Problem/log/response không chứa token raw, hash, username (assert trong T03/T16/T22).

## 4. Bằng chứng kiểm thử

Lệnh chạy:
- `./gradlew test --tests 'com.simulatedbank.banking.identity.*' --rerun` — 44 tests pass.
- `./gradlew test --rerun` (full suite) — **BUILD SUCCESSFUL, 29 classes / 255 tests / 0 fail / 0 skip** (16/11/2026; trước IMP-03: 242 tests).
- Class integration chính chạy lặp 3 lần liên tiếp (`--rerun`) để ổn định race test: 23/23 pass mỗi lần.

Bảng ánh xạ ID-03-Txx → test method (file `IdentityAuthPostgreSqlIntegrationTest` + `IdentityAuthDbFailureIntegrationTest`, PostgreSQL thật qua Testcontainers, HTTP thật qua filter chain + SPI thật):

| Test ID | Scenario | Test method | Kết quả |
|---|---|---|---|
| T01 | Phiên ACTIVE → auth succeeds | `id03_t01_t18_activeSessionAuthenticatesThroughRealSpi` + `t01_httpLoginSuccessReturnsSessionAndNoSecrets` | PASS |
| T02 | Thiếu session → rejected 401 | `id03_t02_missingSessionRejected` | PASS |
| T03 | Opaque token lạ/sai định dạng → 401 | `id03_t03_invalidOpaqueSessionRejected` | PASS |
| T04 | Revoked → rejected | `id03_t04_revokedSessionRejected` (+ `t15`/`t16` IMP-02) | PASS |
| T05 | Idle hết hạn → rejected, không hồi sinh | `id03_t05_idleExpiredSessionRejectedWithoutRevival` | PASS |
| T06 | Absolute hết hạn → rejected | `id03_t06_t08_absoluteExpiredSessionRejectedWithoutExtension` | PASS |
| T07 | Activity trước idle → refresh theo policy, cap tại absolute | `id03_t07_activityBeforeIdleTimeoutRefreshesIdleCappedAtAbsolute` | PASS |
| T08 | Activity sau absolute → rejected, không gia hạn | `id03_t06_t08_…` | PASS |
| T09 | Thiếu CSRF → 403 | `t15_httpLogoutRevokesSessionAndClearsCookie` (IMP-02) | PASS |
| T10 | CSRF proof sai → 403, session không bị revoke | `id03_t10_invalidCsrfProofRejected` | PASS |
| T11 | CSRF proof phiên khác → 403 | `id03_t11_csrfProofFromAnotherSessionRejected` | PASS |
| T12 | Logout hợp lệ → revoke | `id03_t12_t13_logoutRevokesAndReuseRejected` + `t15` | PASS |
| T13 | Reuse sau logout → 401 | `id03_t12_t13_…` + `t16` | PASS |
| T14 | Logout lặp → idempotent 401 theo 07 §8.1.6 | `t16_repeatedLogoutReturnsApprovedIdempotentBehavior` | PASS |
| T15 | Concurrent logout/auth → không hồi sinh | `id03_t15_concurrentLogoutAndAuthenticationNeverReactivatesSession` (4 thread × 40 lần + revoke, chạy ổn định 3 lần liên tiếp) | PASS |
| T16 | Storage failure → fail closed 503 | `t21_databaseFailureReturnsServiceUnavailableFailClosed` (mở rộng) | PASS |
| T17 | Session fixation → rejected | `id03_t17_sessionFixationAttemptRejected` | PASS |
| T18 | SPI integration thật | `id03_t01_t18_…` + `t13_t14_…` (wiring identity qua filter chain HTTP) | PASS |
| T19 | Không duplicate filter/SPI | `id03_t19_noDuplicateSpiOrFilterRegistration` — đúng 1 bean `SessionAuthenticator`, đúng 1 `SessionAuthenticationFilter`/`CsrfFilter`/`CorsFilter` trong chain, 1 `springSecurityFilterChain`, không servlet registration trùng | PASS |
| T20 | Regression IMP-01/02 + Common | full suite `./gradlew test` — 255/255 pass | PASS |

Biên thời gian: test dùng SQL `now() - interval '1 second'` / `+ interval '10|30 seconds'` trực tiếp trên PostgreSQL (clock của DB), không sleep thực. Ghi nhận kỹ thuật: `spring.datasource.hikari.auto-commit: false` (application.yml) — mọi write test phải qua `TransactionTemplate`, nếu không bị rollback khi trả connection về pool (đã gây 3 test fail ban đầu, đã sửa).

## 5. Tuân thủ kiến trúc

- Giữ nguyên 5 layer; SPI implement ở `adapter/out`, port ở `application`; không import ngược common→identity.
- Không tạo interface/session table/enum trùng; `session.status` vẫn chỉ `ACTIVE/EXPIRED/LOGGED_OUT` (04 §4.2.3, OD-ID-04 chưa chốt — revoke ghi `LOGGED_OUT`).
- Error contract chuẩn: 401 `AUTH_SESSION_EXPIRED` retryable / 403 `AUTH_FORBIDDEN` / 503 `SERVICE_UNAVAILABLE`.

## 6. Còn mở (không mark DONE)

1. Audit persistence thật (`AuditModuleApi`, C2/C3 thật) — BE-ID-IMP-13; hiện `SecurityLogAuditAdapter` mock có kiểm soát (DEC-ID-003).
2. Purge phiên hết hạn (`EXPIRED`) — đề xuất job 30 ngày (04 §4.2.3), chưa có trong scope task nào; dòng hết hạn hiện giữ `status='ACTIVE'` nhưng vô dụng vì predicate timestamp.
3. `customerId` trong snapshot = null — chờ `customer.api.CustomerQuery` (design/05 §6).
4. Session token refresh/`GET /auth/session` (API-AUTH-07) — BE-ID-IMP-12; yêu cầu mở OD "session token refresh" (DOC-01 §3.5 mục 8).
5. Quota rate-limit per-username thật — BE-ID-IMP-13.

## Change Log

| Version | Ngày | Người | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Codex (owner: Đạt) | Tạo BE-ID-IMP-03: rà session/CSRF/logout/SPI, vá 2 lỗi fail-open/500, thêm 11 test ID-03 (Testcontainers PostgreSQL), kiểm chứng không duplicate filter/SPI. |
