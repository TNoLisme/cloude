# BE-ID-IMP-04 — CSRF/RBAC/Actor/IDOR security integration: báo cáo triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-IMP-04`
> **Phiên bản:** v1.0
> **Ngày:** 16/11/2026
> **Trạng thái:** DONE (scope task này; các phần BLOCKED/chờ owner nêu rõ §6)
> **Owner:** Đạt (module identity)
> **Branch:** `datht-backend-rework` (baseline `origin/dev2@56210cc` + IMP-01/02/03)
> **Nguồn chuẩn:** `07. API contracts.md` §8.1.6/§8.1.7; `design/04. security.md` §4.3/§5/§8; `design/05. Modules contract.md` §5.1/§12.2; common `SecurityFilterChainConfig`/`SessionAuthenticationFilter`/`SessionCsrfTokens`/`SessionAuthenticator`.

## 1. Tóm tắt triển khai

Common đã có sẵn cơ chế bảo mật chuẩn hóa (`SessionAuthenticationFilter` → SPI identity → `SessionCsrfTokens` session-bound → `authorizeHttpRequests` deny-by-default). IMP-04 **không tạo cơ chế trùng**, mà (1) hoàn thiện `GET /api/v1/auth/session` (API-AUTH-07) làm bề mặt actor propagation thật, (2) chứng minh toàn bộ CSRF/RBAC/actor/IDOR bằng bộ test integration HTTP thật + PostgreSQL thật (`IdentitySecurityIntegrationTest`, 19 test), (3) vá các điểm yếu chứng minh được qua test.

| Năng lực | Trạng thái đầu task | Hành động IMP-04 |
|---|---|---|
| CSRF session-bound trên mutation cookie-auth (POST/PUT/PATCH/DELETE) | Có ở common, chỉ test mock-harness (`HttpSecurityTest`) | Chứng minh e2e với SPI + DB thật: thiếu/sai/proof phiên khác/2 dòng header/expired → reject; bearer không bị áp CSRF cookie; method-override không bypass |
| CSRF lifecycle | Token cấp 1 lần tại login; không có "cấp lại" | `GET /auth/session` rotation (DEC-ID-004): UPDATE điều kiện fail-closed, token cũ chết ngay, token mới ràng buộc phiên |
| Trusted actor propagation | Snapshot filter qua attribute (logout dùng) | `SessionViewService`: toàn bộ actor (userId, roles, privileges) chỉ từ snapshot SPI; không đọc identifier/role từ query/body/header; thêm bất biến controller-level (response không vượt ra ngoài actor đã xác thực) |
| RBAC | `authorizeHttpRequests` của common + snapshot per-request từ `JdbcAuthorityReader` (seed V2, DEC-ID-001) | Không tạo role/permission mới; test tương phản deny (403) vs authorized-pass (non-403 trên cùng endpoint), forge role header/body không leo thang, thu hồi role hiệu lực từ request kế |
| Object-level authorization / IDOR | Logout chỉ đụng phiên của token (đúng) | Chứng minh e2e: logout forged `X-User-Id` chỉ revoke phiên chủ token; proof phiên khác không mượn được; phiên thu hồi/tamper absolute-expiry không hồi sinh; không có API nào nhận resource ID của actor khác (DEC-ID-004 mục 3) |
| Security error | Problem chuẩn `AUTH_FORBIDDEN`/`AUTH_SESSION_EXPIRED` từ common | Kiểm e2e, không rò rỉ token/hash trong response |

## 2. File thay đổi (thực tế)

| File | Thay đổi |
|---|---|
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/SessionRepository.java` | Thêm operation `rotateCsrfToken(sessionTokenHash, newCsrfTokenHash)` — UPDATE điều kiện `status='ACTIVE'` + expiry hợp lệ `RETURNING` giá trị expiry hiện tại; fail-closed, không hồi sinh phiên. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/out/JdbcSessionRepository.java` | Implement `rotateCsrfToken` bằng một statement (`ROTATE_CSRF`), cùng mẫu `RETURNING`/mapper với `validateAndRefresh`. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/SessionViewService.java` (mới) | UC API-AUTH-07: actor chỉ từ `Session` snapshot của filter; roles từ `ROLE_*` của snapshot, privileges từ các authority còn lại (nhất quán `LoginResult`); fail-closed user không ACTIVE → 401; DB failure → 503 `SERVICE_UNAVAILABLE` (cùng quy ước IMP-03); sinh token raw mới 32B Base64Url cho rotation. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/in/AuthController.java` | Thêm `GET /api/v1/auth/session`; refactoring: tách `sessionSnapshot()` từ `actorUserId()` (1 nguồn đọc principal duy nhất); bất biến anti-IDOR: `view.userId() != actorUserId` → 401 trước khi trả response. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentitySecurityIntegrationTest.java` (mới) | 19 test ID-04: CSRF (10), RBAC (4), Actor (3), IDOR (2) — HTTP thật qua filter chain + SPI thật, PostgreSQL Testcontainers. |

Không đổi schema/migration, không đổi common contracts, không tạo SPI/filter/repo trùng (đã kiểm chứng lại bởi test T19 IMP-03 trong full suite).

## 3. Hành vi bảo mật (đối chiếu contract)

- **CSRF (`design/04` §4.3, `07` §8.1.6):** `SessionAuthenticationFilter` gắn attribute `COOKIE_AUTH`; `CsrfFilter` chỉ áp cho cookie-auth mutation; `SessionCsrfTokens.resolveCsrfTokenValue` trả `null` khi 0, ≥2 header hoặc rỗng/độ dài > 512 → `403 AUTH_FORBIDDEN`. Bearer-auth mutation không bị áp CSRF cookie (07 §8.1.6); request public với ambient cookie ACTIVE vẫn cần CSRF.
- **CSRF rotation (API-AUTH-07, DEC-ID-004):** DB chỉ lưu `csrf_token_hash` nên "cấp lại" = cấp raw mới + UPDATE hash; proof cũ chết ngay sau rotation (test CSRF-10), không ảnh hưởng phiên khác; token hết hạn phiên → proof cũ không cứu được (401, test CSRF-06).
- **Actor integrity (`design/05` §3.1):** `Session` snapshot chỉ do SPI tạo từ token hash + RBAC seed DB; `toString()` redacted; response `/auth/session` chỉ trả actor của token; mọi giá trị query/header (userId/userType/roles/customerId) bị bỏ qua (test ACTOR-01, RBAC-03).
- **RBAC (`design/04` §5.1, seed A.2):** authority per-request qua `JdbcAuthorityReader`; `authorizeHttpRequests` deny-by-default (`anyRequest().authenticated()`); CUSTOMER → `/api/v1/admin/**`, `/api/v1/audit` → `403 AUTH_FORBIDDEN`; thu hồi `user_role` trong DB → request kế tiếp bị 403 và `/auth/session` hết roles (test RBAC-04). Điểm pass của role hợp lệ trên endpoint chưa có controller là `404 NOT_FOUND` (qua ranh giới bảo mật; evidence tương phản 403/404).
- **Object-level / IDOR (`design/04` §5.2):** mọi thao tác identity đều theo token; không có endpoint nhận resource ID của actor khác; logout forged `X-User-Id` chỉ revoke phiên chủ token (test ACTOR-03), proof mượn từ phiên khác → 403 và không đụng phiên nào (test IDOR-01); tamper DB trả `status='ACTIVE'` với absolute đã hết hạn vẫn 401 (test IDOR-02).
- **Không rò rỉ:** Problem response không chứa raw token, raw CSRF, hash (assert `assertNoSecretLeak` trong CSRF-01/05/IDOR-02); không có stack/SQL trong body (ProblemWriter common).

## 4. Bằng chứng kiểm thử

Lệnh chạy (thư mục `digitalbanking-backend`):

- `./gradlew test --tests 'com.simulatedbank.banking.identity.IdentitySecurityIntegrationTest'` — **19/19 pass** (0 failure/0 error; PostgreSQL 16 Testcontainers, HTTP thật).
- `./gradlew test --rerun` (full suite regression) — kết quả ghi ở §4.1.

Bảng ánh xạ ID-04 → test method (file `IdentitySecurityIntegrationTest`):

| Test ID | Scenario | Test method | Kết quả |
|---|---|---|---|
| CSRF-01 | POST/PUT/PATCH/DELETE cookie-auth thiếu CSRF → 403; GET an toàn đọc được | `id04_csrf01_cookieMutationWithoutCsrfRejectedForAllUnsafeMethods` | PASS |
| CSRF-02 | Proof sai → 403, phiên không bị revoke | `id04_csrf02_invalidCsrfProofRejectedSessionIntact` | PASS |
| CSRF-03 | Proof phiên khác → 403, cả hai phiên nguyên vẹn | `id04_csrf03_csrfProofFromAnotherSessionRejected` | PASS |
| CSRF-04 | 2 dòng `X-CSRF-Token` → 403 (không chọn "một trong hai") | `id04_csrf04_duplicateCsrfHeaderRejected` | PASS |
| CSRF-05 | Proof hợp lệ → 204, phiên `LOGGED_OUT`, cookie `Max-Age=0`, replay → 401 | `id04_csrf05_validCsrfLogoutRevokesSessionAndTokenDies` | PASS |
| CSRF-06 | Phiên hết hạn → proof cũ vô dụng, `401 AUTH_SESSION_EXPIRED` | `id04_csrf06_expiredSessionInvalidatesCsrfProofFailClosed` | PASS |
| CSRF-07 | Public login giữ nguyên; ambient cookie ACTIVE không CSRF → 403 | `id04_csrf07_publicLoginPreservedActiveAmbientStillNeedsCsrf` | PASS |
| CSRF-08 | Bearer-auth mutation không cần CSRF cookie → 204 | `id04_csrf08_bearerMutationDoesNotRequireCookieCsrf` | PASS |
| CSRF-09 | Method-override (`X-HTTP-Method-Override`, `_method`) không bypass → 403 | `id04_csrf09_methodOverrideCannotBypassCsrf` | PASS |
| CSRF-10 | Rotation: token cũ chết, token mới hợp lệ, phiên khác không bị ảnh hưởng | `id04_csrf10_sessionRotationInvalidatesOldProofOnlyForItsSession` | PASS |
| RBAC-01 | CUSTOMER/AUDITOR vào endpoint không đủ quyền → 403 deny-by-default | `id04_rbac01_unauthorizedRoleDeniedOnProtectedOperations` | PASS |
| RBAC-02 | ADMIN/OPERATIONS vượt ranh giới bảo mật (non-403; 404 = controller chưa có) | `id04_rbac02_authorizedRolesPassTheSecurityBoundary` | PASS |
| RBAC-03 | Forge role qua header (X-Roles/X-User-Id/X-Privileges) và JSON body (`roles` → 400) không leo thang | `id04_rbac03_forgedRoleHeadersAndBodyCannotEscalate` | PASS |
| RBAC-04 | Thu hồi `user_role` trong DB → request kế bị 403, `/auth/session` hết roles | `id04_rbac04_roleRevocationTakesEffectOnNextRequest` | PASS |
| ACTOR-01 | `/auth/session` chỉ trả actor từ snapshot; query/header forge bị bỏ qua | `id04_actor01_sessionViewReturnsOnlyTrustedActor` | PASS |
| ACTOR-02 | Hai phiên độc lập — context không trộn | `id04_actor02_actorContextIsolatedBetweenSessions` | PASS |
| ACTOR-03 | Forge `X-User-Id` khi logout chỉ revoke phiên chủ token | `id04_actor03_forgedActorIdCannotLogoutAnotherSession` | PASS |
| IDOR-01 | Proof mượn từ phiên khác → 403, không đụng phiên nào, không lộ actor | `id04_idor01_crossActorResourceAccessDeniedWithoutLeak` | PASS |
| IDOR-02 | Phiên thu hồi + tamper `status='ACTIVE'`/absolute-expired vẫn 401 (không hồi sinh) | `id04_idor02_revokedSessionCannotBeUsedAgainstItsResource` | PASS |

Ghi nhận kỹ thuật test: validation `X-Correlation-ID` ở common không bật cho request của bộ này (không cần); quota rate-limit login-per-ip/username nâng test-only 1000 (như IMP-03, quota thật đã kiểm chứng ở `HttpSecurityComponentsTest`).

### 4.1. Regression full suite

`./gradlew test --rerun` — **BUILD SUCCESSFUL: 30 classes / 274 tests / 0 fail / 0 error** (16/11/2026; trước IMP-04: 255 tests theo IMP-03, tức +19 test ID-04).

## 5. Tuần thủ kiến trúc

- Giữ 5 layer; port mới `rotateCsrfToken` thuộc `SessionRepository` (identity-owned, không vượt module); service/adapter chỉ import common `SessionAuthenticator`/error/crypto như trước.
- Không đổi `SessionAuthenticator.Session` (common contract) — roles/privileges suy ra từ `authorities` snapshot; không cần sửa shared code.
- Error contract: 401 `AUTH_SESSION_EXPIRED` retryable / 403 `AUTH_FORBIDDEN` / 503 `SERVICE_UNAVAILABLE` / 404 `NOT_FOUND` (controller chưa có).

## 6. Còn mở (không tự đóng)

1. **CSRF rotation semantics (DEC-ID-004):** chờ Security owner review trade-off multi-tab SPA (token cũ chết sau `/auth/session`).
2. **`displayName`/`customerId`/`deviceTrusted` trong `/auth/session`:** trả `username`/`null`/`false` — BLOCKED như IMP-02 (`customer.api.CustomerQuery` chưa có; device trust thuộc IMP-08); schema đầy đủ thuộc BE-ID-IMP-12.
3. **RBAC positive-pass trên endpoint chưa có controller:** evidence 404 (module account/transfer/admin chưa triển khai handler); khi các module có controller, boundary không đổi (đã khóa ở common).
4. **Audit C2/C3 thật cho các sự kiện security (`SECURITY_ACCESS_DENIED` v.v.):** thuộc BE-ID-IMP-13 (`SecurityLogAuditAdapter` mock có kiểm soát theo DEC-ID-003).
5. **Revoke session khi quyền RBAC thay đổi (`design/04` §3.2 E):** snapshot per-request đã vô hiệu hóa quyền ngay lập tức; revoke sessions hàng loạt thuộc BE-ID-IMP-11.

## Change Log

| Version | Ngày | Người | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Codex (owner: Đạt) | Tạo BE-ID-IMP-04: API-AUTH-07 actor propagation + CSRF rotation (DEC-ID-004), `rotateCsrfToken` fail-closed, 19 test integration bảo mật CSRF/RBAC/actor/IDOR (Testcontainers PostgreSQL). |
