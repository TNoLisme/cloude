# BE-ID-IMP-13 — Audit integration, metrics, username rate limit và log redaction

> **Ngày:** 16/11/2026 · **Owner module:** Đạt (Identity) · **Branch:** `datht-backend-rework`
> **Baseline:** `origin/dev2@56210cc` + thay đổi chưa commit của IMP-01..12 trên worktree (giữ nguyên, không tạo branch mới).
> **Trạng thái:** **PARTIAL** — phần Identity-side (audit port semantics + metrics + username quota + redaction) đã triển khai và kiểm thử; **durable audit persistence C2/C3 BLOCKED** vì module Audit runtime chưa tồn tại. Không đánh DONE toàn task.
> **Quyết định ghi nhận:** [DEC-ID-013](../../../../DECISIONS.md).

Nguồn chuẩn: `docs-common/design/01. audit-traceability.md` §5.2/§5.3/§5.6/§8.2/§12; `design/04. security.md` §7.1/§7.2/§8.3/§9.1; `design/05. Modules contract.md` §3.2/§11.1/§12.2; `07. API contracts.md` §2.8/§4.4; `docs-backend/00. architecture & conventions.md` §9.4/§10.5/§10.6; DEC-ID-003; DEC-05/DEC-02 của `docs-backend/modules/common/report/BE-COM-IMP-05-verification.md`/`BE-COM-IMP-02-verification.md`.

## 1. Tóm tắt

BE-ID-IMP-13 yêu cầu tích hợp audit, metrics, rate limiting và redaction cho Identity. Trạng thái đầu task: các flow IMP-02..10 đã phát đúng event catalog qua port `AuthAuditPort` (8 action C2/C3 đã duyệt), common đã có `RequestRateLimiter`/`RequestRateLimitFilter`/`SafeJsonLogEncoder`, nhưng (1) quota per-username chưa có, (2) chưa có metrics tập trung cho xác thực, (3) adapter audit tạm chưa có metric failure/redaction, (4) `Retry-After` thật không lan được qua MVC error layer. Task này hoàn thiện các phần Identity-side, không sáng tạo event/persistence mới.

## 2. Audit integration

| Phần | Trạng thái | Bằng chứng |
|---|---|---|
| C2 `recordInTransaction` (JOIN/MANDATORY, fail-closed) | Contract giữ nguyên; exception (kể cả từ emit) lan truyền → TX rollback | `SecurityLogAuditAdapterTest.c2WriteFailurePropagates…`; rollback thật: `DeviceMfaLoginHttpIntegrationTest.eachC2AuditFailureRollsBack…` (IMP-08, vẫn PASS) |
| C3 `recordStandalone` (best-effort, fallback security log + metric + alert) | Hardened: bắt RuntimeException → `auth.audit.failures` + `SECURITY_AUDIT_WRITE_FAILED`; metric/log failure không làm mất evidence | `SecurityLogAuditAdapterTest.c3Failure…`, `metricsInfrastructureFailure…` |
| Event catalog (8 action) | `AUTH_LOGIN_SUCCESS` C2, `AUTH_LOGIN_FAILED` C3, `AUTH_ACCOUNT_LOCKED` C3, `AUTH_SESSION_LOGOUT` C3, `AUTH_CHALLENGE_CREATED` C2, `AUTH_CHALLENGE_LOCKED` C3, `AUTH_STEP_UP_VERIFIED` C2, `AUTH_STEP_UP_FAILED` C3 — phát tại flow đã duyệt (login/lockout/logout/challenge/verify; thiết bị mới luôn MFA theo DEC-ID-008) | các flow test IMP-02..10; không thêm event mới trong task này |
| SECURITY_ACCESS_DENIED C3 (403) | Chưa phát: nguồn 403 là `authorizeHttpRequests` của common (BE-CON-08 cấm common → business module); cần SPI common → audit. Ghi nhận BLOCKED cho common owner. | Mục 8 blockers |
| ROLE_CHANGE_* C2 | Thuộc phase Audit của flow Admin approval (design/05 §5.3); chưa có runtime Admin → không phát giả | DEC-ID-013 |
| **Durable `audit_log` persistence (`AuditModuleApi`)** | **BLOCKED** — module Audit runtime chưa có (`docs-backend/modules/audit/README.md` trống; contract chỉ ở `design/01` §4.3/`_overview/07-audit.md`). Adapter hiện phát metric + security log; seam `emit` `protected` sẵn sàng thay bằng INSERT `audit_log` trong TX khi BE-AUD sẵn sàng | `SecurityLogAuditAdapter` javadoc; DEC-ID-013 |

Redaction áp vào audit metadata: `detail` đi qua `LogRedaction.redactText` (defense-in-depth; các field khác là mã enum/id đã mask từ các flow).

## 3. Metrics (Common Micrometer, BE-OBS-18: không userId/username/correlation/token)

| Meter | Kiểu | Labels (bounded) | Điểm phát |
|---|---|---|---|
| `auth.login.requests` | Counter | method=`PASSWORD`/`FIDO`/`PASSCODE`, result=`SUCCESS`/`FAILURE`, reason=`ErrorCode`/`NONE` | `PasswordLoginService.login`, `FidoLoginService.finish`, `PasscodeLoginService.rejectUnprovenDevice` |
| `auth.login.duration` | Timer | method | quanh `login()`/`finish()` |
| `auth.mfa.verify` | Counter | purpose=`LOGIN`/`STEP_UP`, result, reason | `OtpChallengeService.verifyLoginChallenge`/`verifyChallenge` |
| `auth.session.created` | Counter | method=`OTP`/`FIDO` | `LoginSessionService.completeVerified` (sau audit C2, trong TX) |
| `auth.session.terminated` | Counter | outcome=`REVOKED`/`IDEMPOTENT` | `LogoutService.logout` |
| `auth.login.rate_limited` | Counter | group=`login-username` | quota Identity từ chối |
| `auth.audit.events` / `auth.audit.failures` | Counter | action/class/mode/outcome | `SecurityLogAuditAdapter` |
| `rate.limit.rejections` | Counter | group (global/login-ip/login-username/step-up/otp-ip/otp-user/transfer-customer/audit-export) | ngay trong `RequestRateLimiter.acquire` khi bucket chặn — phủ cả filter common lẫn quota Identity |

Failure policy: hạ tầng metrics lỗi → nuốt + `METRIC_WRITE_FAILED`/`AUDIT_METRIC_WRITE_FAILED` (telemetry fail-open), không ảnh hưởng xác thực — khác policy audit C2 (fail-closed)/C3 (fallback). Verified bởi `AuthMetricsTest.metricsInfrastructureFailureNeverPropagates`, `SecurityLogAuditAdapterTest`.

## 4. Rate limiting (username quota)

- **Điểm chặn:** `PasswordLoginService.enforceUsernameQuota` — trước credential verify, sau khi normalize username. Common không parse body login (DEC-05) nên Identity gọi trực tiếp `RequestRateLimiter` (hạ tầng Common, không framework mới).
- **Normalization:** `toLowerCase(Locale.ROOT)` — đúng chuỗi dùng cho DB lookup, đảm bảo casing không bypass (`07` §3.1 chỉ quy định lowercase; không tự thêm trim để không đổi semantics lookup).
- **Response:** `429 RATE_LIMITED` + extension `retryAfterSeconds` (giây thật ceil từ limiter) → `ProblemWriter` set header `Retry-After` thật thay vì fallback 2s; fallback 2s giữ nguyên cho filter/lỗi khác. Extension chỉ hợp lệ cho `RATE_LIMITED` (validate trong `ProblemResponse.checkedExtensions`).
- **Fail-closed:** `CapacityExceededException` → `503 SERVICE_UNAVAILABLE` như common filter. Anti-enumeration: unknown user cùng shape 429 như known user (test).
- **Giới hạn minh bạch:** in-process per-instance (design/05 §12.2, design/02); không distributed guarantee; passcode/FIDO login không có bucket riêng (bucket global/IP của filter vẫn áp) — rà lại khi có contract. Chưa log/metric tên subject (chỉ group).

## 5. Log redaction

- `common/logging/LogRedaction.java` (mới) — nguồn trung tâm:
  - `redact(Map)` đệ quy (nested map/list) theo key: normalization bỏ ký tự non-alnum + lowercase; exact set (password/passcode/otp/token/session/csrftoken/authorization/cookie/setcookie/secret/apikey/…) + substring fragments (`password_hash`, `refreshToken`, `mfaSecret`, `recoveryCode`, `access_token`, biến thể casing); `username` cũng bị redact (design/01 §5.6: không lưu username trong audit).
  - `redactText(String)`: scrub `key=value`, `key: value`, JSON `"key":"value"`, Bearer token — dùng cho exception/detail text.
- Áp dụng: `SecurityLogAuditAdapter` (detail). Structured stdout đã fail-closed sẵn (`SafeJsonLogEncoder` không serialize message/args/MDC; `HttpRequestLog` không log body/header). Redaction không đụng dữ liệu nghiệp vụ/API response.
- Không log raw auth payload: `LoginRequest`/`LoginOtpVerificationRequest`/`StepUpVerificationRequest` đã `toString()` redacted + OTP `char[]` WRITE_ONLY + buffer `\0` (IMP-12); verified lại trong `LoginUsernameRateLimitHttpIntegrationTest.structuredLogsNeverContain…` (password/username/OTP/`$argon2`/Bearer vắng mặt trong captured stdout).

## 6. File thay đổi

Main (`digitalbanking-backend/src/main/java/com/simulatedbank/banking/`):

| File | Nội dung |
|---|---|
| `common/logging/LogRedaction.java` | **Mới** — redaction trung tâm (map + text). |
| `identity/application/AuthMetrics.java` | **Mới** — metrics component fail-open, label bounded. |
| `identity/adapter/out/SecurityLogAuditAdapter.java` | Redaction trên detail, counter success/failure, metric guard; `emit` `protected` (seam thay adapter khi Audit module có). |
| `identity/application/PasswordLoginService.java` | Username quota trước verify + metrics + timer; 503 khi bucket đầy. |
| `identity/application/OtpChallengeService.java` | `auth.mfa.verify` cho verify login/step-up. |
| `identity/application/LoginSessionService.java` | `auth.session.created`. |
| `identity/application/LogoutService.java` | `auth.session.terminated`. |
| `identity/application/PasscodeLoginService.java`, `FidoLoginService.java` | `auth.login.requests`/`auth.login.duration` cho PASSCODE/FIDO. |
| `common/config/RequestRateLimiter.java` | Inject `MeterRegistry`, counter `rate.limit.rejections{group}` khi chặn. |
| `common/error/ProblemResponse.java` | Extension `retryAfterSeconds` chỉ cho `RATE_LIMITED` (Long 1–86400). |
| `common/error/ProblemWriter.java` | `Retry-After` thật từ extension khi có; fallback 2s giữ nguyên. |

Tests (`src/test/java/com/simulatedbank/banking/`):

| File | Nội dung |
|---|---|
| `common/logging/LogRedactionTest.java` | **Mới** — biến thể header/field name, nested/list, null/empty, text scrub, không đụng diagnostic thường. |
| `identity/AuthMetricsTest.java` | **Mới** — label bounded theo ErrorCode catalog, timer, metrics hạ tầng lỗi không lan. |
| `identity/SecurityLogAuditAdapterTest.java` | **Mới** — C2 propagate fail-closed, C3 swallow + fallback + failure metric, metrics lỗi không mất evidence, redaction detail. |
| `identity/PasswordLoginServiceTest.java` | +6 test IMP-13: T-RL-01..04 (quota/casing/pre-verify/capacity→503), T-MET-01/02. |
| `identity/LoginUsernameRateLimitHttpIntegrationTest.java` | **Mới** (Testcontainers PostgreSQL, HTTP thật) — 6 test: 429+Retry-After thật, anti-enumeration, casing bypass, bucket độc lập, metrics trên registry của app, security: không password/username/OTP/`$argon2`/Bearer trong structured stdout. |
| `common/config/HttpSecurityComponentsTest.java`, `PublicOtpRateLimitTest.java` | Constructor `RequestRateLimiter` + SimpleMeterRegistry. |

Docs: `DECISIONS.md` (DEC-ID-013), `tasks/README.md` (row IMP-13), `README.md` (index/status).

## 7. Kiểm thử

| Suite | Kết quả |
|---|---|
| Unit mới/cập nhật: `LogRedactionTest`, `AuthMetricsTest`, `SecurityLogAuditAdapterTest`, `PasswordLoginServiceTest` (T-RL/T-MET), `LogoutServiceTest`, `PasscodeLoginServiceTest`, `OtpChallengeServiceTest` | **PASS** (72 tests, 0 fail) |
| Integration mới: `LoginUsernameRateLimitHttpIntegrationTest` (6 test, PostgreSQL Testcontainers, security chain thật) | **PASS** |
| Full build `./gradlew build --rerun-tasks` | Xem mục 10 (evidence run cuối). |

Failure paths được test: audit C2 write fail (rollback), audit C3 fail (fallback + metric), metrics infra fail (nuốt, không mất evidence), rate limiter capacity fail (503), redaction input null/empty/non-string.

## 8. Blockers và dependencies còn mở

1. **Durable audit persistence (BLOCKED):** `AuditModuleApi` + bảng `audit_log` thuộc module Audit (owner Thịnh) chưa có runtime. Identity không tự tạo audit DB/service (I-AUD-02 append-only + hash chain là trách nhiệm Audit). Khi sẵn sàng: thay `emit` của `SecurityLogAuditAdapter` bằng INSERT `audit_log` trong TX; C2 fail → rollback đã đúng hợp đồng; test double chỉ mô phỏng lỗi write.
2. **SECURITY_ACCESS_DENIED (C3)** chưa phát từ 403 chung: cần SPI/port do common sở hữu (BE-CON-08 cấm common → business). Đề xuất cho common owner; không tự đục SecurityFilterChainConfig.
3. **Distributed rate limiting:** in-process per-instance hiện tại; không thay thế distributed guarantee — cần contract riêng nếu owner yêu cầu (design/05 §12.2).
4. **Audit event cho rate-limit/audit-view/ROLE_CHANGE:** không tự phát; chờ policy/catalog của Audit/Security/Admin.

## 9. Đổi khác database

Không có. Không sửa migration V2/V3/V4.

## 10. Evidence

Kiểm thử: mục 7 (unit 72 PASS; integration 6 PASS; full build `./gradlew build --rerun-tasks` **SUCCESSFUL — 55 classes / 791 tests / 0 failure/error/skip**).

Commits trên branch `datht-backend-rework`:
- `21ecdd96dc5` — BE-ID-IMP-13 implementation + tests + docs (kèm baseline backend IMP-01..12 chưa commit trước đó, để commit tự nhất quán).
- `869fa958de9` — README module status IMP-13 PARTIAL.

Trạng thái tổng: **PARTIAL** cho đến khi mục 8.1 được Audit module đóng (điều kiện của Gate D).
