# BE-ADM-API-01 — REST DTO + controller cho `POST /admin/limits` và `/admin/roles`

> **Mã task:** `BE-ADM-API-01` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **PARTIAL** — `POST /api/v1/admin/roles` (201/`Location`/replay/428/400/401/403/404/409/429/5xx)
> hoàn chỉnh và có evidence HTTP thật. `POST /api/v1/admin/limits` đúng DTO → validate → **fail-closed
> `503 SERVICE_UNAVAILABLE`**, không tạo approval/idempotency/audit giả vì `limit.api` và
> `limit_config PENDING_APPROVAL` chưa có runtime (`ADM-OI-05`). Vì vậy acceptance "valid POST /admin/limits ⇒ 201"
> chưa thể đóng; phần còn lại của API-01 đã đạt.
> **Nguồn:** `07` §2/§5.6/§6/§8.5.2/§8.5.3/§8.8.1/§8.8.2/§7.2.14, `design/04` §4.3/§7, `design/05` §12.1,
> OpenAPI `digital-banking-v1.yaml` (`/admin/limits`, `/admin/roles`, `ApprovalSummary`, `ApprovalDetail`),
> `BE-ADM-CON-02/03`, `BE-ADM-APP-02/03/04`.

## 1. Endpoint đã mở (đúng method/path của contract)

| API | Method/path | Controller | Trạng thái |
|---|---|---|---|
| `API-ROL-01` | `POST /api/v1/admin/roles` | `RoleAdminController#create` | **DONE** — 201 + `Location`, idempotent replay |
| `API-ROL-02` | `POST /api/v1/admin/roles/{id}/approve` | `RoleAdminController#decide` | **DONE** (approve/reject role) |
| `API-LIM-02` | `POST /api/v1/admin/limits` | `LimitAdminController#create` | **BLOCKED (503 fail-closed)** — Limit provider |
| `API-LIM-03` | `POST /api/v1/admin/limits/{id}/approve` | `LimitAdminController#decide` | Reject DONE; approve **BLOCKED (503)** |

`/admin/roles` không có thao tác nào khác trong `07` §8.8 (không có GET/PUT/DELETE); list/detail là
`/admin/approvals` (`BE-ADM-API-03`, ngoài phạm vi task này). Không thêm method ngoài contract.

## 2. DTO

| DTO | Field | Ghi chú |
|---|---|---|
| `RoleChangeHttpRequest` | `operation`, `targetUserId`, `roleCode`, `privilegeCode`, `reason` | ID là string theo `07` §2; `expectedVersion`, `makerId`, `checkerId` **không** là input |
| `LimitChangeHttpRequest` | `customerId`, `accountId`, `perTransactionLimit`, `dailyLimit`, `currency`, `effectiveFrom`, `reason` | Validate shape rồi fail-closed |
| `ApprovalDecisionHttpRequest` | `decision`, `decisionNote` | `MAYBE`/field lạ → 400 (`FAIL_ON_UNKNOWN_PROPERTIES`) |

- Không trả entity/persistence type: controller chỉ nhận/trả `record` + `Map` đã mask (`AdminApprovalView`).
- `ApprovalSummary` đúng 12 field contract (`approvalId, requestType, targetType, targetId, status, maker, checker,
  createdAt, decidedAt, appliedAt, decisionNote, correlationId`); `ApprovalDetail` thêm `payload` đã mask (bỏ
  `expectedVersion`) và `result` khi provider trả.
- Parse fail-closed tập trung ở `AdminHttpSupport` (`ID` regex `[1-9][0-9]{0,18}`, money `BigDecimal`, `Instant`,
  enum); số vượt `BIGINT` → `400 VALIDATION_ERROR`, không 500.
- Bean Validation: project dùng validate thủ công trong `AdminHttpSupport`/domain (không có `@Valid` ở các
  controller hiện hữu); task này giữ đúng convention, không thêm framework thứ hai. Chi tiết field error:
  Problem `invalidParams` do `GlobalExceptionHandler` sinh (header `Idempotency-Key` thiếu → field-level entry).

## 3. HTTP semantics

| Tình huống | Kết quả | Bằng chứng |
|---|---|---|
| Tạo role proposal thành công (sau step-up) | `201`, `Location: /api/v1/admin/approvals/{id}`, `Cache-Control: no-store`, body `ApprovalSummary` | `AdminHttpAuditPostgreSqlIntegrationTest#verifiedStepUpCreatesOneApprovalAndReplaysWithoutDuplicateAudit` |
| Replay cùng key+payload | `201` + `Idempotent-Replayed: true` + body gốc | cùng test; `RoleAdminControllerTest#replayAddsIdempotentReplayedHeader` |
| Chưa step-up | `428 STEP_UP_REQUIRED` + `challenge` public, không tạo state | HTTP test (test 5, 7); `RoleAdminControllerTest#stepUpRequiredIsMappedWithPublicChallenge` |
| Body sai/ID overflow/decision sai/field lạ | `400` (`VALIDATION_ERROR`/`MALFORMED_REQUEST`) | `RoleAdminControllerTest`, `LimitAdminControllerTest` |
| Thiếu/invalid session | `401 AUTH_SESSION_EXPIRED` | HTTP test 1; `WWW-Authenticate: Bearer` |
| Thiếu authority | `403 AUTH_FORBIDDEN` + C3 audit | HTTP test 2/3 |
| Approval không tồn tại/sai type | `404 APPROVAL_NOT_FOUND` (anti-enumeration) | HTTP test 4 |
| Role target không tồn tại | `404 USER_NOT_FOUND`, không tạo approval/idempotency/audit | HTTP test 8 |
| Terminal/đã quyết định, maker–checker, idempotency conflict/in-progress | `409` theo `07` §4 | service/PG tests (APP-02/03/04) |
| `POST /admin/limits` khi provider chưa có | `503 SERVICE_UNAVAILABLE`, không state | `LimitAdminControllerTest`, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#limitApproveFailsClosedAndRejectIsIdempotent` |
| Rate limit toàn cục (`read-per-user-per-minute`) | `429 RATE_LIMITED` + `Retry-After`, `no-store`, correlation | `AdminRateLimitHttpPostgreSqlIntegrationTest` (limit=2, third request) |
| Provider/DB failure | `503`/`500` qua `DatabaseErrorMapper`/`GlobalExceptionHandler`, không lộ internals | mapping tests + Problem assertions |

`422 LIMIT_OUT_OF_POLICY` chỉ phát sinh khi Limit policy provider tồn tại (`ADM-OI-05`), nên **chưa** có đường
production; không tạo 422 giả cho role.

## 4. Security

- Authorization: chain `SecurityFilterChainConfig` — `POST /admin/roles` cần `ROLE_MANAGE`; approve cần
  `CHECKER` + `ROLE_MANAGE`; limit cần `LIMIT_MAKER|LIMIT_MANAGE` (approve thêm `CHECKER`). Service re-check
  authority từ trusted session (`AdminAuthorization`), không tin `makerId/checkerId/role` từ client.
- CSRF: cookie session (`DB_SESSION`) bắt buộc `X-CSRF-Token`; Bearer không áp dụng CSRF (design/04 §4.3, `07`
  §8.1.6). HTTP test 6 chứng minh: thiếu token → 403 không audit; có token → request đi tiếp (428).
- Response: `ApiSecurityHeadersFilter` + controller `CacheControl.noStore()`; `X-Correlation-ID` echo từ
  `CorrelationAndRequestIdFilter`/`RequestContext`; lỗi theo RFC 9457 với `correlationId`.
- Không lộ entity/secret/PII: HTTP test assert body/`Location`/log không chứa `expectedVersion`, payload, token,
  OTP, `password`, stack trace, SQL.
- Cross-tenant/cross-scope: **BLOCKED** (`ADM-OI-06`; schema v1 không có tenant/scope), hiện enforce đúng authority
  đã duyệt; ghi rõ để Security/Admin review trước release.

## 5. OpenAPI

- `openapi/digital-banking-v1.yaml` đã có `/admin/limits`, `/admin/limits/{id}/approve`, `/admin/roles`,
  `/admin/roles/{id}/approve` với `Idempotency-Key`, `X-Step-Up-Challenge`, `X-CSRF-Token`, `X-Correlation-ID`,
  201 + `Location`, 400/401/403/404/409/422/428/429/500/503, schema `ApprovalSummary`/`ApprovalDetail`.
- Mô tả OpenAPI đã ghi đúng hành vi fail-closed của `LIMIT_CHANGE` (limit proposal/approve `503` khi provider
  chưa có) và replay/`Idempotent-Replayed`; implementation và tài liệu khớp nhau (không có endpoint/method thừa).
- Test HTTP ở §6 verify trực tiếp shape/field-set của `ApprovalSummary` và `Location`, không chỉ annotation.

## 6. Test

| Lớp | Nội dung | Kết quả |
|---|---|---|
| `RoleAdminControllerTest` | 201 + Location + no-store, replay header, 428 public challenge, thiếu key 400 (field-level), ID overflow 400, decision sai 400, field lạ 400, 200 no-store | 8 PASS |
| `LimitAdminControllerTest` | proposal fail-closed 503 không gọi write, reject map `LIMIT_CHANGE` + no-store | 2 PASS |
| `AdminHttpAuditPostgreSqlIntegrationTest` (mới) | 401/403/404/428/CSRF/201/replay trên security chain thật, PostgreSQL 16, correlation, no-store, không leak; unknown role target 404 không state | 8 PASS |
| `AdminRateLimitHttpPostgreSqlIntegrationTest` (mới) | 2 request đầu tới Maker step-up (428), request thứ ba `429 RATE_LIMITED` + `Retry-After` + `no-store` + correlation, không leak token | 1 PASS |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | replay/conflict/in-progress, limit reject idempotent, C2 failure rollback | 11 PASS |
| `ApprovalStepUpPostgreSqlIntegrationTest` | approve/reject role end-to-end, 2-checker race, provider rollback | 22 PASS |

## 7. Evidence tổng hợp

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.common.config.*' \
  --tests 'com.simulatedbank.banking.common.context.*' \
  --tests 'com.simulatedbank.banking.common.logging.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' \
  --console=plain
```

**BUILD SUCCESSFUL** — 35 classes / 297 tests / 0 failure / 0 error / 0 skip.

Full build `./gradlew cleanTest build`: **BUILD FAILED** — 1241 tests, 6 failure, tất cả trong
`DeviceMfaLoginHttpIntegrationTest` (Identity): `HttpTimeoutException` và `BankingHikariPool … total=0` — hạ tầng
Testcontainers/JVM bị contention (build chạy 45m thay vì ~7m; worktree chia sẻ có build module Account chạy song
song). Chạy lại `DeviceMfaLoginHttpIntegrationTest` trong cùng suite Admin/Common/ArchUnit: **PASS** (22 test; tổng
319 tests / 0 failure) ⇒ 6 failure trên là flake hạ tầng, không có test Admin/migration/audit nào fail.

## 8. Acceptance và blocker

| # | Yêu cầu | Trạng thái |
|---|---|---|
| 1 | Valid `POST /admin/limits` | **BLOCKED** — `ADM-OI-05` (503 fail-closed, không state) |
| 2 | Valid `/admin/roles` operations | PASS (`POST /admin/roles`, `POST /admin/roles/{id}/approve`) |
| 3 | Response schema + status | PASS |
| 4 | 201 + `Location` | PASS |
| 5 | Invalid DTO/validation → 400 | PASS |
| 6 | 400/401/403/404/409/422/429/5xx | PASS cho phần có đường production; `422` chỉ khi Limit policy provider có |
| 7 | AuthN/AuthZ restrictions | PASS (chain + service re-check) |
| 8 | Cross-tenant/cross-scope | **BLOCKED** `ADM-OI-06` |
| 9 | Correlation ID propagation | PASS |
| 10 | CSRF + no-store | PASS |
| 11 | Không lộ entity/secret/PII | PASS |
| 12 | API compatibility + OpenAPI | PASS |

Không đánh dấu `DONE`: `POST /admin/limits` 201 và `LIMIT_CHANGE_APPROVED` phụ thuộc Limit provider
(`ADM-OI-05`), cross-scope phụ thuộc `ADM-OI-06`.
