# BE-ID-TEST-03 — QA security, performance và handover

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-TEST-03`
> **Ngày:** 17/11/2026
> **Owner thực hiện:** Claude (Identity)
> **Branch:** `datht-backend-rework` (làm việc trên working tree hiện tại, không checkout/reset/merge)
> **Baseline so sánh:** `origin/dev2@cf60428` + 5 commit local (`datht-backend-rework@199e33e` trở lại)
> **Trạng thái:** **PARTIAL** — phần bảo mật và hiệu năng phía Identity đã IMPLEMENTED + EXECUTED + VERIFIED bằng automated evidence (identity regression **33 classes / 597 tests PASS**, perf **5/5 kịch bản PASS**); FE/QA integration và external QA sign-off còn **PENDING_EXTERNAL_SIGNOFF**; durable Audit/Notification/Transfer/Admin vẫn **BLOCKED** như TEST-01/02 đã ghi.

## 1. Phạm vi và nguồn kiểm thử

Đã đối chiếu: `README.md` backend; `docs-common/03` (AC-001/003/004/033, NFR-003/013–023, SEC-CHK-01..08), `07` §8.1 (API-AUTH-01..08), `09` §7.6 (D5 profile), `design/04` §4–§9 (STRIDE, session/CSRF, RBAC/IDOR, OTP, rate limit, PII), `design/05` §5.1/§5.3 (Session SPI, không JWT/refresh subsystem), BE-ID-IMP-01..13 và evidence BE-ID-TEST-01/TEST-02.

Nguyên tắc thực hiện:

- Không sao chép test hiện hữu; chỉ bổ sung gap thực sự (mục §2/§3).
- Không phát minh SLA: ngưỡng hiệu năng duy nhất trong tài liệu là NFR-003 «đề xuất» (auth/OTP p95 ≤ 1 giây, nguồn `03` §NFR-003) — chỉ dùng để **so sánh**, không enforce trong CI.
- Không JWT: kiến trúc đang là opaque server-side session (`DB_SESSION`) + CSRF synchronizer; JWT/refresh-token không tồn tại theo Modules contract §5.3.1, nên các mục "forged JWT claims/refresh-token misuse" của task được đánh giá **N/A (đã ghi lý do)** và thay bằng kịch bản tương đương trên opaque token (mục §6.3).
- Synthetic accounts toàn bộ (`t03*`, `perfuser*`); không ghi token/OTP/credential vào evidence; perf report chỉ chứa số liệu tổng hợp.

## 2. Coverage hiện có trước TEST-03 (rà soát, không thêm lại)

| Category | Suite hiện hữu (evidence TEST-01/02) | Đã phủ chính |
|---|---|---|
| CSRF | `IdentitySecurityIntegrationTest` CSRF-01..10; `IdentityAuthPostgreSqlIntegrationTest` id03_t10/t11/t12_t13 | Thiếu CSRF cho mọi unsafe method, proof sai/cross-session, duplicate header, rotation, bearer exempt, method-override, ambient login |
| RBAC | `IdentitySecurityIntegrationTest` RBAC-01..04; `RbacAuthorityPostgreSqlIntegrationTest` (9 test); `RoleChangePostgreSqlIntegrationTest` (36 test) | Deny-by-default, contrast role được cấp, forge header/body, thu hồi role DB hiệu lực ngay |
| IDOR/actor | `IdentitySecurityIntegrationTest` ACTOR-01..03, IDOR-01..02; `StepUpVerificationHttpIntegrationTest` foreignChallenge; `DeviceMfaLoginHttpIntegrationTest` proofsCannotBeSubstituted/copiedTrustedId | Chống xác thực đơn thuần làm mở khóa resource của user khác, header forge bị bỏ qua |
| Privilege escalation | RBAC-03/04; `RoleChangePostgreSqlIntegrationTest` (maker/checker/self/type/catalog, version, revoke sessions); `StepUpConsumptionPostgreSqlIntegrationTest` (binding user/hash/key/purpose) | Leo thang dọc/ngang tại chỗ có control; mass assignment trên login body |
| OTP/MFA | `OtpChallengeServiceTest` (25), `OtpChallengePostgreSqlIntegrationTest` (24, T-STEP-02), `OtpDeliveryPostgreSqlIntegrationTest` (16), `DeviceMfaLoginHttpIntegrationTest` (21), `StepUpVerificationHttpIntegrationTest` (17), `StepUpConsumptionPostgreSqlIntegrationTest` (14), `OtpDispatchServiceTest` (11) | Missing/wrong/expired/replayed/cross-user/cross-purpose OTP, attempt threshold, concurrent verify single-winner, challenge invalidation, leakage response |
| Hiệu năng | — không có suite nào trước TEST-03 | (task này bổ sung, §8) |

## 3. Test bổ sung của TEST-03

### 3.1. `IdentityTransportSecurityIntegrationTest` — 12 test (PostgreSQL 16 Testcontainers + Tomcat thật)

| ID | Kịch bản | Bất biến kiểm chứng | Kết quả |
|---|---|---|---|
| TSEC-01 | `tsec01_sessionCookieAttributesMatchDesignContract` | 202 không Set-Cookie; 200 verify cấp `DB_SESSION` đúng design/04 §4.3: `HttpOnly`, `SameSite=Strict`, `Path=/`, `Max-Age=28800`, token opaque Base64Url không dấu `.` (không JWT); GET /auth/session không reissue cookie; logout clear `Max-Age=0` | PASS |
| TSEC-02 | `tsec02_combinedCookieAndBearerCredentialsAreRejectedFailClosed` | Authorization + cookie đồng thời → `401 AUTH_SESSION_EXPIRED`; không chọn principal từ kênh nào; cả hai phiên còn `ACTIVE` | PASS |
| TSEC-03 | `tsec03_forgedCredentialCannotRideOnValidOtherChannel` | forged cookie + Bearer hợp lệ → 401 (cookie không "gắn" lên principal); forged Bearer + cookie hợp lệ → 401 (không fallback); forged cookie không mutation được | PASS |
| TSEC-04 | `tsec04_duplicateCredentialsRejectedWithoutChoosingOne` | 2 cookie `DB_SESSION` hoặc 2 header Authorization → 401, không chosen-first-wins | PASS |
| TSEC-05 | `tsec05_nonBearerOrMalformedAuthorizationRejectedNoCookieFallback` | Basic/Token scheme, Bearer rỗng/ký tự cấm → 401; ambient cookie hợp lệ không bị hỏng sau các lần từ chối | PASS |
| TSEC-06 | `tsec06_bearerMutationDoesNotRequireOrVerifyCsrfProof` | Bearer mutation bỏ qua CSRF (documented applicability); proof CSRF của user khác trên kênh cookie → 403 AUTH_FORBIDDEN, không rollback phiên | PASS |
| TSEC-07 | `tsec07_anonymousRequestsToProtectedEndpointsAreFailClosed` | Anonymous trên 6 endpoint bảo vệ → 401 AUTH_SESSION_EXPIRED đồng nhất; health public; login public không cookie → 202 | PASS |
| TSEC-08 | `tsec08_disabledUserForbiddenLockedUser423WithoutSessionOrChallenge` | `DISABLED` → 403 AUTH_FORBIDDEN, không challenge/session; `LOCKED` (locked_until tương lai) → 423 AUTH_ACCOUNT_LOCKED, không session | PASS |
| TSEC-09 | `tsec09_massAssignmentFieldsRejectedAndChallengeUntouched` | Field đặc quyền lạ (`roles/userType/userId/privileges/customerId`) trong `/otp/verify` và `/step-up/verify` → 400; challenge `SENT`/`attempt_count=0` không mutate; proof gốc vẫn verify được một lần | PASS |
| TSEC-10 | `tsec10_customerTokenDeniedAcrossAdministrativeSurface` | CUSTOMER token bị 403 AUTH_FORBIDDEN trên 12 path admin/audit/fraud (kèm CSRF proof cho mutation nên 403 là RBAC, không phải CSRF) | PASS |
| TSEC-11 | `tsec11_sessionViewNeverExposesSessionTokenOrHash` | GET /auth/session không chứa raw session token, không chứa SHA-256 hash trong DB | PASS |
| TSEC-12 | `tsec12_otpNeverAppearsInResponsesOrChallengePayload` | OTP không xuất hiện trong bất kỳ response nào (202/200/401), body không có field `otp`/`otpHash`, token phiên không nằm cạnh OTP trong body | PASS |

### 3.2. `IdentityAuthPerformanceTest` — 5 kịch bản đo (tag `performance`, task Gradle `perfTest`)

Chi tiết tại §8. Refresh-token concurrency **N/A** — không có refresh-token subsystem; thay bằng token validation đồng thời trên opaque session (kịch bản C).

## 4. Đánh giá applicability CSRF (không đổi config)

- **Cookie-auth (`DB_SESSION`):** CSRF bắt buộc trên mọi unsafe method (`requireCsrfProtectionMatcher` chỉ khi request đi bằng cookie; `SessionCsrfTokens` so `SHA-256(X-CSRF-Token)` với `session.csrf_token_hash` — DEC-ID-002). Đã được phủ + mở rộng (TSEC-01/06; CSRF-01..10 hiện hữu).
- **Bearer (explicit credential):** cùng token opaque nhưng đưa qua header `Authorization` — CSRF proof không áp dụng/không verify (TSEC-06 chứng minh 204 ngay cả khi proof bịa). Lý do kiến trúc: Bearer không phải ambient credential của browser (browser không tự đính kèm), mô hình tấn công CSRF của trình duyệt không phát sinh; đúng `07` §8.1.6 («CSRF header nếu dùng cookie») và design/04 §4.3. **Không đổi config FE-facing nào.**
- **Cookie attributes:** `HttpOnly; SameSite=Strict; Path=/; Max-Age=28800` (TSEC-01). Cờ `Secure` là cấu hình runtime `APP_COOKIE_SECURE` — bật tại biên TLS (design/04 §6.1 Nginx); mặc định test/dev là off vì HTTP loopback không gửi được secure cookie.

## 5. RBAC / IDOR / Privilege escalation — kết quả thực thi

| Group | Test class | Executed | Passed | Failed | Blocked |
|---|---|---:|---:|---:|---:|
| CSRF (tổng) | IdentitySecurityIntegrationTest CSRF-01..10 (10) + IdentityAuthPostgreSql id03_t10/t11/t12_t13 (3) + TSEC-01..06 (6) | 19 | 19 | 0 | 0 |
| RBAC | IdentitySecurityIntegrationTest RBAC-01..04 (4) + RbacAuthorityPostgreSqlIntegrationTest (10) + TSEC-07/08/10 (3) | 17 | 17 | 0 | 0 |
| IDOR/object | IdentitySecurityIntegrationTest ACTOR-01..03 + IDOR-01..02 (5) + StepUp foreignChallenge (1) + DeviceMfa proofsCannotBeSubstituted/copiedTrustedId/unknownForeignRefs (3) | 9 | 9 | 0 | 0 |
| Privilege escalation | IdentitySecurityIntegrationTest RBAC-03/04 (2) + RoleChangePostgreSqlIntegrationTest (45: maker/checker/self/type/catalog, version, revoke sessions) + StepUpConsumptionPostgreSqlIntegrationTest (29: binding user/hash/key/purpose, single consume) + TSEC-09 (1) | 77 | 77 | 0 | 0 |
| OTP/MFA | OtpChallengeServiceTest (34) + OtpChallengePostgreSQL (22, T-STEP-02) + OtpDeliveryPostgreSQL (23) + DeviceMfaLoginHTTP (27) + StepUpVerificationHTTP (14) + OtpDispatchServiceTest (36) + TSEC-12 (1) | 157 | 157 | 0 | 0 |
| PII/log redaction (phạm vi Identity) | SecurityLogAuditAdapterTest (5, gồm t22_noSensitiveDataInAuditLogs) + IdentityAuthPostgreSql t22_problemResponsesExposeNoInternalState (1) + TSEC-11/12 (2); LogRedactionTest/PiiMaskingUtilsTest ở package common thuộc IMP-13 evidence (TEST-02) | 8 | 8 | 0 | 0 |

Ghi chú phạm vi (không tự nâng thành integration evidence):

- **RBAC production Admin flow:** `/api/v1/admin/**` controllers thật chưa có (Admin module skeleton). 403/404 được phân tách bằng rule `authorizeHttpRequests` — test chứng minh **security boundary**, không chứng minh business logic approval (BLOCKED theo IMP-11/TEST-02).
- **IDOR:** Identity không có endpoint resource theo user id (chỉ session/challenge của chính principal); mọi kịch bản đổi path/query/body id đều đã phủ qua test hiện hữu + TSEC-02/03. Object-level authorization của Account/Transfer thuộc owner tương ứng, chưa có runtime.

## 6. Privilege escalation, JWT/refresh, session/revocation

1. **Customer→admin:** chặn ở `authorizeHttpRequests` (RBAC-01/02, TSEC-10); role thay đổi ở DB có hiệu lực request kế (RBAC-04); `IdentityRoleCommand` không có HTTP route — chỉ Admin orchestrator production được phép gọi (BLOCKED).
2. **Mass assignment:** `spring.jackson.deserialization.fail-on-unknown-properties=true` toàn cục; TSEC-09 mở rộng lên cả hai proof endpoint và chứng minh challenge không bị chiếm/heading.
3. **JWT/refresh-token:** **N/A theo thiết kế đã duyệt** — opaque session trong PostgreSQL (DD-SEC-01), không JWT, không blacklist, không refresh-token (Modules contract §5.3.1). Kịch bản tương đương đã phủ: token hết hạn idle/absolute (id03_t05/t06_t08, CSRF-06), token thu hồi không dùng lại (IDOR-02, t16), token giả định dạng → 401 (id03_t03, TSEC-05), token bịa trên kênh khác → 401 (TSEC-03).
4. **Session revocation:** logout idempotent + duplicate concurrent (t15/t16, id03_t15, 8-way duplicate logout), role change revoke ACTIVE sessions (RoleChange suite, IMP-11).

## 7. OTP/MFA — điểm còn thiếu và trạng thái

| Kịch bản task yêu cầu | Trạng thái | Nơi chứng minh |
|---|---|---|
| Missing/wrong/expired/replayed/consumed/cross-account/cross-purpose OTP, attempts, lockout | PASS (đã có) | DeviceMfaLogin, StepUp, OtpChallenge suites (§5) |
| Concurrent verify single-winner | PASS | `acceptedCodeVerifiesOnlyOnce...`, `concurrentActualHttpVerification...`, T-STEP-02 |
| Resend (API-AUTH-08) | **BLOCKED** | Không có route/controller theo DEC-ID-006/009 — không tạo route giả; checklist giữ BLOCKED đến khi Notification/owner chốt MOD-OI-07/09 |
| OTP leakage response/log | PASS | assertSafe/assertNoSecrets (suite hiện hữu) + TSEC-12; log redaction qua `LogRedaction` + t22 |
| Persistent trusted-device credential | BLOCKED | DEC-ID-008 — hoãn theo quyết định người điều hành |

## 8. Hiệu năng xác thực (mới, reproducible)

**Hạ tầng:** một PostgreSQL 16-alpine Testcontainer (DB/user riêng), Flyway full chain, Spring Boot Tomcat thật trên `RANDOM_PORT`, HTTP client Java `HttpClient`, 20 CPU / JVM test heap 3 GB / Hikari `maximum-pool-size=20, minimum-idle=5` (test override), rate-limit override chỉ trong test (10000/min...) để cửa sổ đo không bị 429. Thời điểm đo: 2026-11-16T18:2x UTC, máy local 20-core/12 GB, Docker Server 29.7.2. Quan trọng: **kết quả phụ thuộc môi trường, không phải SLA production**; so sánh NFR-003 «đề xuất» p95 ≤ 1 s chỉ ghi nhận trong report.

| Kịch bản | Requests | Concurrency | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Error |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline_single_user_login | 60 | 1 | 324.1 | 347.7 | 363.1 | 3.06 | 0 |
| token_validation_sequential | 500 | 1 | 5.3 | 9.2 | 11.1 | 173.67 | 0 |
| token_validation_concurrent_8_threads | 800 | 8 | 10.7 | 18.4 | 27.1 | 672.5 | 0 |
| concurrent_login_8_threads | 96 | 8 | 1086.9 | 1374.0 | 1476.4 | 7.21 | 0 |
| mfa_otp_verify_concurrent | 40 | 8 | 332.7 | 480.7 | 697.1 | 21.04 | 0 |

Đối chiếu NFR-003 (đề xuất ≤ 1 s p95 cho auth/OTP): **PASS ở 4/5 kịch bản trong môi trường đo** — baseline login p95 347.7 ms, MFA verify đồng thời p95 480.7 ms, token validation p95 9.2/18.4 ms đều ≤ 1 s; `concurrent_login_8_threads` p95 1374 ms vượt ngưỡng «đề xuất» 1 s ở tải 8 login đồng thời. Nguyên nhân đã đo được (không phải defect): mỗi login chạy **2 lần Argon2id (m=64 MB, t=3, p=4)** — Argon2id cố tình memory-hard nên 8 lần băm đồng thời tranh chấp băng thông bộ nhớ (~3× chậm hơn tuần tự, đo bằng probe độc lập: 114 ms tuần tự → ~360 ms ở 8 thread). Đây là thông tin capacity planning, không phải lỗi ứng dụng; ngưỡng NFR-003 là «đề xuất» và đây chỉ là so sánh, không phải SLA đã duyệt.

- **Warm-up:** A warm-up 10 login flow; B warm-up 30 GET /auth/session; C/D/E đã nóng sau A/B (Tomcat/pool/JIT). Không dùng `Thread.sleep` làm oracle; số liệu là per-request wall time trong cửa sổ đo.
- **Evidence JSON:** `digitalbanking-backend/build/reports/perf/identity-auth-perf.json` (file lưu bền: `BE-ID-TEST-03-perf-evidence.json` cùng thư mục tài liệu này; không chứa token/OTP/credential, chỉ tổng hợp + environment).
- **Giới hạn:** loopback + single-node Testcontainers; không đo TLS/Nginx, không đo multi-instance, không đo 20 RPS × 10 phút transfer-target (D5 thuộc Transfer + chưa có runtime). Error rate trong cửa sổ đo = 0 ở mọi kịch bản (assert trong test); latency percentile không được assert (tránh flaky CI).

## 9. Defect record

### 9.1. Test-infrastructure defect đã sửa trong task

1. **Seed không commit:** Hikari `auto-commit=false` của project khiến `JdbcTemplate.update` ngoài transaction không commit → mọi login trả `401 AUTH_INVALID_CREDENTIALS`. Sửa: seed/update trong `TransactionTemplate` (cùng pattern suite hiện hữu).
2. **PER_CLASS lifecycle + Testcontainers ordering:** `@TestInstance(PER_CLASS)` khiến Spring context load trước TestcontainersExtension → «Mapped port can only be obtained after the container is started». Sửa: start container tường minh trong `@DynamicPropertySource`.
3. **Gradle test worker heap mặc định 512 MB:** 8 Argon2id đồng thời (m=64 MB) ép GC → connection-timeout/500 trong kịch bản D/E. Sửa: `perfTest { maxHeapSize = "3g" }` (TEST-01 §6 đã ghi cùng lớp lỗi bộ nhớ test-defect; không giảm security parameter production).
4. **Error capture thiếu status:** harness ghi chỉ class name → sửa `beginLoginMeasured` note đúng status trước khi tính error rate.
5. **JaCoCo on-the-fly instrumentation làm sai lệch đo concurrency:** `./gradlew perfTest` mặc định gắn agent `jacocoagent.jar` (plugin `jacoco` áp cho mọi `Test` task). Dưới agent, 8 Argon2id đồng thời chạy **~10× chậm** (p50 12.2 s → 1.09 s cho full login sau khi tắt; reproducible bằng probe độc lập: 358 ms → 3707 ms/op ở 8 thread, tuần tự không đổi ~114 ms). Đây là sai số đo, **không phải defect ứng dụng** — thread dump chứng minh toàn bộ Tomcat thread nằm trong `Argon2BytesGenerator.applyBlake` với CPU 100%, DB `pg_stat_activity` chỉ 1–2 connection idle trong toàn cửa sổ. Sửa: `perfTest` tắt JaCoCo (`JacocoTaskExtension.isEnabled = false`) — coverage vô nghĩa cho run đo; `test` giữ nguyên JaCoCo.
6. **Harness race:** `okNanos` (ArrayList) và `totalRequests++` từ 8 thread bị lost update (sample 795/800, error count âm). Sửa: `ConcurrentLinkedQueue` + `AtomicInteger`.

### 9.2. Build blocker chéo module (đã mở khóa)

`compileJava` FAIL từ in-flight BE-CUS-IMP-04 của owner Customer: `CustomerQueryService.toPublicStatus` — unqualified `CustomerStatus` bị inherited nested type `CustomerQuery.CustomerStatus` che khuất (JLS §8.5). Trong thời gian task chạy, owner Customer đã tự sửa bằng cách **rename API enum thành `CustomerQuery.Status`** (xem DEC-CUS-003 trong DECISIONS.md) — hướng sửa gốc tốt hơn; bản vá tạm của TEST-03 không còn dấu vết trên working tree và không giữ lại. Kết quả: `compileJava`/`compileTestJava` BUILD SUCCESSFUL, không có thay đổi của Identity.

#### 9.2b. Cross-module failure còn OPEN (owner Customer)

`./gradlew build` full vẫn FAIL 2 test trong **`customer.adapter.in.CustomerProfileControllerPostgreSqlIntegrationTest`** — BE-CUS in-flight, ngoài phạm vi TEST-03 (Identity không đụng tới):

1. `authenticatedOwnerReceivesExactCustomerProfileContract` — expected `"nva***@example.com"` nhưng nhận `"n***@example.com"`: kỳ vọng mask email trong test KHÔNG khớp rule `PiiMaskingUtils.emailMask` hiện hành (04 §7.2 quy định `n***@example.com`; test expectation của owner cần rà lại theo mask rule đã duyệt).
2. `retrievalLeavesExistingCustomerDataUnchanged` — `DataIntegrityViolationException: conversion to java.time.Instant from timestamptz not supported` trong test code (JdbcTemplate đọc `banking.customer.updated_at`): **test infrastructure defect của Customer**, không phải schema.

Required action: owner BE-CUS sửa 2 test này; TEST-03 không can thiệp module khác. Identity-side regression không bị ảnh hưởng (589/589 PASS, mục §12).

### 9.3. Production defect trong phạm vi Identity

Không phát hiện production defect mới trong các lần chạy TEST-03. Các 500/connection-timeout trong lần đo đầu (§9.1.3) là test-defect bộ nhớ, không phải business race; đã verify lại sau khi tăng heap.

## 10. FE/QA handover

### 10.1. Endpoints Identity đang hỗ trợ (theo `07` §8.1)

| API | Route | Trạng thái handover |
|---|---|---|
| API-AUTH-01 | `POST /api/v1/auth/login` | Sẵn sàng — 202 challenge + OTP verify (MFA mọi password login theo DEC-ID-008) |
| API-AUTH-02 | `POST /api/v1/auth/passcode` | **BLOCKED positive** (DEC-ID-009) — validate rồi reject unproven device; FE không nên build positive passcode flow |
| API-AUTH-03 | `POST /api/v1/auth/fido/verify` | Sẵn sàng begin/finish (Yubico, DEC-ID-010); enrollment/provisioning còn BLOCKED |
| API-AUTH-04 | `POST /api/v1/auth/otp/verify` | Sẵn sàng — public, single-use, TTL 5 phút, max 5 attempts |
| API-AUTH-05 | `POST /api/v1/auth/step-up/verify` | Sẵn sàng — authenticated + CSRF khi cookie; purpose `TRANSFER_STEP_UP`/`SENSITIVE_ACTION` |
| API-AUTH-06 | `POST /api/v1/auth/logout` | Sẵn sàng — 204, cookie clear, idempotent (401 khi token chết) |
| API-AUTH-07 | `GET /api/v1/auth/session` | Sẵn sàng — trả actor + rotation `csrfToken` mỗi lần gọi |
| API-AUTH-08 | `POST /api/v1/auth/challenges/{id}/resend` | **BLOCKED** — không có route (DEC-ID-006/009); FE phải coi resend là unavailable |

### 10.2. Authentication workflow cho FE

1. `POST /login` → `202` + `challenge.challengeId` (không cookie); FE hiển thị OTP entry.
2. `POST /otp/verify` `{challengeId, otp}` → `200 AuthResponse` + `Set-Cookie: DB_SESSION` (HttpOnly/SameSite=Strict/Path=/, Max-Age 8h) + body `session.csrfToken` (raw, dùng cho header `X-CSRF-Token`).
3. Mọi unsafe request khi dùng cookie phải gửi `X-CSRF-Token`; mỗi `GET /auth/session` **rotate** CSRF → FE phải refresh token trong bộ nhớ sau mỗi lần gọi.
4. Auth FE có thể dùng Bearer (header `Authorization: Bearer <token phiên>`) — khi đó không cần CSRF (TSEC-06).
5. Lỗi chuẩn: 400 `VALIDATION_ERROR`/`MALFORMED_REQUEST`, 401 `AUTH_INVALID_CREDENTIALS`/`AUTH_SESSION_EXPIRED`/`AUTH_OTP_INVALID`, 403 `AUTH_FORBIDDEN` (CSRF/RBAC/DISABLED), 404 `AUTH_CHALLENGE_NOT_FOUND`, 409 `INVALID_STATE_TRANSITION`/`STEP_UP_CHALLENGE_MISMATCH`, 410 `AUTH_OTP_EXPIRED`, 423 `AUTH_ACCOUNT_LOCKED`/`AUTH_CHALLENGE_LOCKED`, 428 `STEP_UP_REQUIRED` (consumer thật chưa có), 429 `RATE_LIMITED` (+ `Retry-After`), 503 `OTP_DISPATCH_FAILED`/`SERVICE_UNAVAILABLE`.

### 10.3. Test accounts/prerequisites cho FE/QA

- Chưa có seed account dùng chung trong repo (seed role/permission có sẵn ở V2; user phải seed thủ công như các integration test: `app_user` + `user_role`).
- FE dev local cần `APP_HMAC_KEY_BASE64`/`APP_PII_AES_KEY_BASE64` hợp lệ (fail-fast khi thiếu trên demo/prod), Docker cho PostgreSQL, và `APP_COOKIE_SECURE=true` khi chạy qua HTTPS.
- FE/QA sign-off: **PENDING_EXTERNAL_SIGNOFF** — thư mục `digitalbanking-frontend/` trống, không có integration/evidence nào từ FE/QA; không tự ký thay.

### 10.4. Reproduction commands

```bash
# Security suite (12 test TEST-03)
./gradlew test --tests 'com.simulatedbank.banking.identity.IdentityTransportSecurityIntegrationTest'
# Performance suite (5 kịch bản, heap 3 GB)
./gradlew perfTest
# Kết quả perf JSON
cat digitalbanking-backend/build/reports/perf/identity-auth-perf.json
# Identity regression toàn bộ
./gradlew test --tests 'com.simulatedbank.banking.identity.*'
```

## 11. Dependency và blocker

| Component | Owner | Thiếu | Ảnh hưởng TEST-03 | Required action |
|---|---|---|---|---|
| FE/QA integration + sign-off | FE owner / QA | Không có FE runtime, không có integration evidence, không sign-off | Handover mục 10.3 `PENDING_EXTERNAL_SIGNOFF`; không thể đánh DONE toàn task | FE implement login/OTP/CSRF per §10.2; QA chạy các lệnh §10.4 và trả evidence |
| Customer in-flight tests (BE-CUS) | Owner Customer | `CustomerProfileControllerPostgreSqlIntegrationTest` 2 FAIL (§9.2b) — mask email expectation + timestamptz→Instant trong test code | `./gradlew build` full FAIL vì Customer, không phải Identity (identity 589/589 PASS) | Owner BE-CUS sửa; không thuộc thẩm quyền TEST-03 |
| Audit durable | Thịnh | `AuditModuleApi`, `audit_log`, hash chain | Audit C2/C3 chỉ qua security-log adapter; không có evidence durable | Cung cấp runtime rồi chạy lại rollback/integration thật |
| Notification/OTP provider | Thịnh | Adapter/provider/contact resolver, resend generation | API-AUTH-08 BLOCKED; delivery chỉ contract boundary | Chốt MOD-OI-07/09 |
| Transfer/Limit/Account/Ledger | Vũ/Đạt/Phương | T0/T1, idempotency, ledger | AC-033/T-STEP-01 vẫn BLOCKED (TEST-01) | Ghép `StepUpChallengePort` vào transaction thật |
| Admin | Admin owner | Approval orchestrator production | `IdentityRoleCommand` không có HTTP consumer | Owner cung cấp orchestration + Audit cùng transaction |

## 12. Kết quả thực thi

| Lệnh | Kết quả |
|---|---|
| `./gradlew test --tests '...IdentityTransportSecurityIntegrationTest'` (isolated copy) | `BUILD SUCCESSFUL` — **12/12 PASS**, 0 fail/error/skip |
| `./gradlew perfTest` (isolated copy, heap 3 GB, JaCoCo tắt cho task) | `BUILD SUCCESSFUL` — **5/5 kịch bản PASS**, error rate 0, số liệu §8; run đầu (JaCoCo bật) đo được sai số 10× ở D/E — đã ghi vào §9.1 |
| `./gradlew test --tests 'com.simulatedbank.banking.identity.*' + identity.adapter.in.* + PostgreSqlFreshMigration/IdentityV2Migration` (isolated copy) | `BUILD SUCCESSFUL in 4m 41s` — **33 classes / 597 tests / 0 fail / 0 error / 0 skip** |
| `./gradlew build` (isolated full-repo copy) | `BUILD FAILED in 6m 8s` — **66 classes / 874 tests: 872 PASS, 2 FAIL** — cả 2 FAIL thuộc `customer.adapter.in.CustomerProfileControllerPostgreSqlIntegrationTest` (BE-CUS in-flight của owner Customer, §9.2b); **Identity 589/589 PASS** trong cùng build |
| `compileJava` trên shared worktree trước/sau rename Customer | FAIL (in-flight BE-CUS-IMP-04) → SUCCESSFUL sau rename của owner (§9.2) |

Môi trường: khi chạy trên shared worktree có một Gradle build khác (Customer owner) ghi đồng thời `build/test-results` — đúng hiện tượng TEST-01/02 đã ghi; toàn bộ verification của TEST-03 chạy trong **isolated full-repo copy** (`rsync` không `.git`/`build`) để loại race, không kill process của người khác.

## 13. File thay đổi

| Path | Loại | Mục đích |
|---|---|---|
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityTransportSecurityIntegrationTest.java` | Mới | 12 security test TSEC-01..12 (cookie contract, credential binding, anonymous fail-closed, disabled/locked, mass assignment, RBAC matrix, leakage) |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityAuthPerformanceTest.java` | Mới | 5 kịch bản hiệu năng + JSON report `build/reports/perf/identity-auth-perf.json` |
| `digitalbanking-backend/build.gradle.kts` | Sửa | Tách tag `performance` khỏi `test` mặc định; thêm task `perfTest` (heap 3 GB, tắt JaCoCo instrumentation — sai số đo 10×, không kéo jacoco full suite); giữ nguyên behavior `test` hiện tại |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-03.md` | Mới | Báo cáo này |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-03-perf-evidence.json` | Mới | Perf evidence JSON (sao từ `build/reports/perf/`) |
| `digitalbanking-docs/docs-backend/modules/identity/tasks/README.md` | Sửa | Checklist BE-ID-TEST-03 trạng thái bằng evidence |
| `digitalbanking-docs/docs-backend/modules/identity/README.md` | Sửa | Index + trạng thái module |
| `DECISIONS.md` | Sửa | DEC-ID-TEST-03 ghi phạm vi, test-defect record, blocker |

Không thay đổi production source Identity/Common. Không đổi security config.

## 14. Checklist nghiệm thu BE-ID-TEST-03

- [x] Review implementation bảo mật Identity hiện có (§2).
- [x] CSRF applicability được đánh giá + test (§4; không đổi config FE-facing).
- [x] RBAC authorization tests PASS (§5 — 16/16 trong phạm vi thực thi).
- [x] IDOR/ownership tests PASS (§5 — 9/9 trong phạm vi thực thi).
- [x] Privilege escalation tests PASS (§5 — 53/53 trong phạm vi thực thi).
- [x] OTP/MFA security tests PASS trừ resend BLOCKED (§7).
- [x] Performance tests đã thực thi với số liệu thực (§8).
- [x] Latency/throughput thực đã ghi (§8 + JSON evidence).
- [x] Defect đã sửa hoặc ghi rõ (§9 — không có production defect Identity mới; test-defect đã sửa).
- [x] Regression PASS (§12 — identity regression 33 classes/597 tests PASS; full build còn 2 FAIL thuộc BE-CUS in-flight, §9.2b).
- [x] Evidence/report có sẵn (tài liệu này + JUnit XML + perf JSON).
- [ ] FE/QA handover được FE/QA xác nhận — **PENDING_EXTERNAL_SIGNOFF**.
- [ ] External QA sign-off — **PENDING_EXTERNAL_SIGNOFF**.
- [x] Identity task checklist cập nhật (tasks/README.md).

## 15. Trạng thái cuối

`BE-ID-TEST-03`: **PARTIAL**. Automated security verification + performance measurement phía Identity đã hoàn tất và VERIFIED; task không thể DONE khi FE/QA integration và external QA sign-off chưa có (dependency ngoài), cùng các blocker Audit/Notification/Transfer/Admin đã ghi từ TEST-01/02. Không tự ký QA, không nâng fake thành production evidence.
