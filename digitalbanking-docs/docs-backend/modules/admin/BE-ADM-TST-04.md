# BE-ADM-TST-04 — Security/API contract tests trên session/security chain thật

> **Mã task:** `BE-ADM-TST-04` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — security chain thật (session filter, URL gate, CSRF, correlation/request-id, security headers, Problem writer, C3 auditor), Identity session/authority thật và PostgreSQL 16 thật; OpenAPI được so khớp bằng reflection với controller/DTO/enum thực tế. Cross-tenant vẫn **BLOCKED** (`ADM-OI-06`: schema canonical không có cột tenant) và được ghi bằng evidence test thay vì suy diễn.
> **Nguồn:** `docs-common/07. API contracts.md` §4.3/§5.2/§6/§8.8, `design/04. security.md` §5.1, `design/05. Modules contract.md` §4/§12.1, `openapi/digital-banking-v1.yaml`, `BE-ADM-SEC-01`, `BE-ADM-SEC-02`, `BE-ADM-API-01/02/03/04`.

## 1. Phạm vi

Task này đóng các acceptance của `BE-ADM-TST-04`:

- Test chạy trên **security filter chain thật**: `CorrelationAndRequestIdFilter` → `ApiSecurityHeadersFilter` → `SessionAuthenticationFilter` → CSRF → URL authorization → controller → `GlobalExceptionHandler` → `ProblemWriter`.
- Không thay authentication bằng cấu hình test permissive; không `@WithMockUser`; session/authority được tạo bằng row DB thật rồi đi qua `IdentitySessionAuthenticator` thật.
- OpenAPI ↔ implementation drift được kiểm bằng **reflection trên controller/DTO/enum thật**, không phải so khớp source text.
- Status/envelope/header/correlation được pin theo hợp đồng; CSRF được kiểm đúng policy thực tế (cookie cần token, bearer không cần).
- Forged metadata (header/body/query) và IDOR/actor-scope được kiểm; xâm phạm bị từ chối và không mutate dữ liệu.
- Response/log không rò token, session hash, password hash, OTP, SQL, stack trace, package nội bộ.

Không thêm endpoint, không nới authorization, không sửa contract OpenAPI.

## 2. Test infrastructure

| Thành phần | Sử dụng |
|---|---|
| HTTP server | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `HttpClient` thật (không MockMvc) cho contract/security suite |
| Security | `SecurityFilterChainConfig` production: `SessionAuthenticationFilter`, native `CsrfFilter` với `SessionCsrfTokens`, `AuthorizationManagers`, `SecurityAccessDeniedAuditor`, `ApiSecurityHeadersFilter`, `CorrelationAndRequestIdFilter` |
| Identity | PostgreSQL 16 Testcontainers + Flyway `V1`–`V8`; `app_user`/`role`/`user_role`/`user_privilege`/`session` rows thật; authority snapshot từ `JpaAuthorityReader` |
| OTP | chỉ transport `OtpGateway` là test double (notification provider chưa có); challenge state/hash/verify/consume là production code |
| OpenAPI | `digitalbanking-docs/openapi/digital-banking-v1.yaml` parse bằng SnakeYAML; controller reflection (`@RequestMapping` merged annotation, `@RequestHeader`, `@RequestBody`) |
| Isolation | `@BeforeEach` xoá `admin_idempotency_record` → `auth_challenge` → `approval_request`; mỗi test tạo principal riêng |

## 3. Test class và phạm vi

### 3.1. `AdminOpenApiContractTest` (mới, 5 test, không cần DB)

Đường dẫn: `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminOpenApiContractTest.java`.

| Test | Nội dung | Assertion chính |
|---|---|---|
| `documentedAdminOperationsMatchTheImplementedControllersExactly` | 8 operation Admin (`/admin/roles`, `/admin/roles/{id}/approve`, `/admin/limits`, `/admin/limits/{id}/approve`, `/admin/approvals`, `/admin/approvals/{approvalId}`, `/admin/customers/{customerId}`, `/admin/customers/{customerId}/accounts`) | path/method/`operationId` khớp controller thật; path variable so theo vị trí; **không** có operation implemented mà không documented |
| `adminOperationsRequireAuthenticationAndTheDocumentedWriteHeaders` | Security kế thừa global `sessionCookie`/`bearerAuth`; header write | Không operation nào override `security: []`; write có `Idempotency-Key` (required, ≤255), `X-CSRF-Token`, `X-Step-Up-Challenge`; handler thật khai báo đúng header + `@RequestBody`; read không có các header ghi |
| `documentedStatusSetsMatchTheApprovedContract` | Status documented theo từng operation | Tập status == hợp đồng đã duyệt; mọi response ≥400 dùng schema Problem (kể cả `ValidationProblem`/`StepUpProblem` allOf); mọi status documented đều tồn tại trong `ErrorCode`; **422 chỉ có ở 2 route limit** |
| `requestSchemasMatchTheControllerDtosAndDomainEnums` | Request/response schema ↔ DTO/enum | `RoleChangeRequest.oneOf` = 4 `RoleChangeOperation`, `additionalProperties=false`, field bắt buộc đúng; `RoleChangeHttpRequest` không có field actor/status/tenant; `RoleApprovalRequest` = `ApprovalDecisionHttpRequest` + enum `APPROVE/REJECT`; `LimitChangeRequest` = `LimitChangeHttpRequest`; `ApprovalSummary` không có `payload`/`expectedVersion`/`result`; enum documented ⊆ domain, `RETENTION_*` không được publish |
| `everyDocumentedReferenceResolvesToACompleteComponent` | Toàn vẹn `$ref` | Mọi `$ref` parameter/response resolve; path parameter `required: true`; mọi response có content; `Problem.required` đúng 9 field |

### 3.2. `AdminHttpContractPostgreSqlIntegrationTest` (mới, 5 test)

Đường dẫn: `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminHttpContractPostgreSqlIntegrationTest.java`.

| Test | Nội dung | Assertion chính |
|---|---|---|
| `securityHeadersAndProblemEnvelopeAcrossStatuses` | 200/400/401/403/404/428 trên Admin surface | `Cache-Control: no-store`, `Pragma`, `Expires: 0`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: frame-ancestors 'none'`, `X-Request-ID` UUID, echo `X-Correlation-ID`; error `application/problem+json` + 9 field envelope; 428 challenge đúng shape (không OTP/hash/binding); correlation sai → `400 VALIDATION_ERROR` + id server-generated; không rò token/hash/SQL/stack |
| `csrfPolicyIsBoundToCookieSessionsOnly` | CSRF thật của chain | Cookie GET không cần CSRF (200); cookie POST thiếu/sai/foreign token → `403 AUTH_FORBIDDEN`, **không** state, **không** C3; cookie POST limit approve cũng 403; token đúng → qua CSRF tới `428`; bearer POST không cần CSRF → `428` |
| `documentedStatusMappingForValidationConflictAndTerminalState` | 400/404/409/503 và 422 | `VALIDATION_ERROR` cho body/field/enum sai; `MALFORMED_REQUEST` cho unknown field; `requestType=RETENTION_HOLD` → 400 (giá trị documented nhưng ngoài MVP); `404 APPROVAL_NOT_FOUND`/`USER_NOT_FOUND`; `409 ROLE_CONFLICT` (self-target), `409 IDEMPOTENCY_CONFLICT` (cùng key khác payload), `409 APPROVAL_ALREADY_DECIDED` (terminal, key khác); `503 SERVICE_UNAVAILABLE` cho limit proposal fail-closed, **không** 422 và không state |
| `forgedMetadataAndIdorCannotWidenScope` | Forged header/query + IDOR | `X-User-Id`/`X-Actor-User-Id`/`X-Tenant-Id`/`X-Organization-Id`/`X-Role`/`X-Permissions`/`X-Maker-Id` bị bỏ qua; `makerId`/`checkerId` filter không mở scope; approval của maker khác = `404`; ROLE_CHANGE id trên route limit = `404`; AUDITOR (module khác) = `403`; cùng idempotency key ở hai checker không replay chéo (chỉ 1 claim, provider apply 1 lần); rejection không đổi row khác |
| `tenantIsolationIsBlockedByTheCanonicalSchema` | Evidence cho blocker `ADM-OI-06` | `information_schema` không có cột `tenant|organization|org_` trên `approval_request`/`admin_idempotency_record` → không thể assert cross-tenant; boundary hiện tại là actor scope (test trên) |

### 3.3. Coverage đã có (không nhân bản)

| Lớp | Tests | Phạm vi đã phủ |
|---|---:|---|
| `AdminSecurityMatrixPostgreSqlIntegrationTest` | 8 | CUSTOMER 403 toàn route + C3; forged header/body proposal; disabled/expired/revoked session 401; AUDITOR permission; checker thiếu `ROLE_MANAGE`; maker self-propose/approve 409 |
| `AdminHttpAuditPostgreSqlIntegrationTest` | 9 | 401 không audit; 403 + 1 C3; 404 anti-enumeration; 428 không state; cookie CSRF; 428→verify→201 + `Location` + 1 C2 + replay; sensitive free-text |
| `ApprovalReadHttpPostgreSqlIntegrationTest` | 9 | Maker/checker scope SQL, filter/page/sort, 404 out-of-scope, payload mask, read-only |
| `ApprovalDecisionHttpPostgreSqlIntegrationTest` | 6 | 428→verify→200 `APPLIED`, replay, key khác 409, reject không provider, maker-self 409, checker thiếu quyền 403, 2 checker race |
| `AdminDtoValidationTest`, `RoleAdminControllerTest`, `LimitAdminControllerTest`, `ApprovalAdminControllerTest`, `AdminAuthorizationTest`, `ApprovalReadAuthorizationTest` | 6/8/2/9/8/7 | Validation DTO, mapping controller, authorization service-layer, 404 anti-enumeration |

## 4. Map acceptance criteria

| Acceptance `BE-ADM-TST-04` | Bằng chứng |
|---|---|
| Real security-chain integration tests executable | `AdminHttpContractPostgreSqlIntegrationTest` (RANDOM_PORT, chain thật) + các suite §3.3 |
| Admin OpenAPI contracts checked | `AdminOpenApiContractTest` |
| Exact 401/403/428/409 mappings | 401/403/428 trong test 1 + §3.3; 409 `ROLE_CONFLICT`/`IDEMPOTENCY_CONFLICT`/`APPROVAL_ALREADY_DECIDED` trong test 3 |
| 422 mapping where specified | 422 được documented **chỉ** ở `/admin/limits[/{id}/approve]`; runtime hiện fail-closed `503` (Limit provider absent — `ADM-OI-05`). Test pin rằng Admin trả 400/503 chứ không tự phát 422; drift được ghi ở §6 |
| CSRF policy tested | `csrfPolicyIsBoundToCookieSessionsOnly` |
| Forged metadata rejected/ignored | `forgedMetadataAndIdorCannotWidenScope` + `AdminSecurityMatrix...` |
| IDOR/cross-tenant covered | IDOR actor-scope: PASS. Cross-tenant: **BLOCKED** `ADM-OI-06` — evidence schema ở test 5 |
| No sensitive leakage | Test 1, 4 + `AdminHttpAuditPostgreSqlIntegrationTest#sensitiveFreeTextIsRedacted...`, `LogRedactionTest`, `SafeJsonLogEncoderTest` |
| No authorization bypass introduced | Chỉ thêm test; production authorization không đổi |

## 5. Lệnh và kết quả

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.adapter.in.AdminOpenApiContractTest' \
  --tests 'com.simulatedbank.banking.admin.adapter.in.AdminHttpContractPostgreSqlIntegrationTest' \
  --console=plain
```

Kết quả targeted: **BUILD SUCCESSFUL**, `AdminOpenApiContractTest` 5/5 PASS, `AdminHttpContractPostgreSqlIntegrationTest` 5/5 PASS (PostgreSQL 16 Testcontainers + Flyway `V1`–`V8` + security chain/Identity thật; OTP transport là test double duy nhất).

Kết quả regression rộng (Admin + Common + ArchUnit + migration + Customer/Account admin HTTP): **BUILD SUCCESSFUL**, 65 classes / 559 tests / 0 failure / 0 error / 0 skip.

## 6. Drift và quyết định

| # | Quan sát | Xử lý |
|---|---|---|
| 1 | OpenAPI `ApprovalRequestType` chỉ publish `LIMIT_CHANGE`/`ROLE_CHANGE`, trong khi domain enum còn `RETENTION_HOLD`/`RETENTION_POLICY_CHANGE` (schema-reserved, `openInMvp() == false`) | Không sửa domain/không mở API. Contract test assert document ⊆ domain và chỉ publish giá trị `openInMvp()`; HTTP test assert `requestType=RETENTION_HOLD` → `400 VALIDATION_ERROR` (không âm thầm chấp nhận). Retention approval thuộc module/contract khác, vẫn OPEN |
| 2 | OpenAPI document `422 UnprocessableEntity` cho 2 route limit, nhưng runtime không có path nào trả 422 (Limit provider chưa có → `503` fail-closed) | Ghi nhận là drift có kiểm soát: test pin 400/503 thực tế và **không** thêm mã 422 giả. Khi `ADM-OI-05` đóng, 422 policy phải được Limit/Audit owners duyệt và bổ sung test |
| 3 | `Tenant`/`organization` không tồn tại trong schema canonical (`04` §4.5.3) | Cross-tenant isolation **BLOCKED** (`ADM-OI-06`). Test `tenantIsolationIsBlockedByTheCanonicalSchema` ghi evidence; boundary hiện tại = actor scope trong SQL |
| 4 | HSTS chỉ được ghi khi `request.isSecure()` (đúng thiết kế) | Test HTTP (plain HTTP) assert các header không điều kiện; case TLS được pin bởi `HttpSecurityTest#headersAlsoCoverPreSecurityErrorsAndHstsRequiresActualSecureTransport` |

## 7. Regression và trạng thái

- Không phát hiện production defect trong authorization/contract của Admin ở phạm vi task này.
- Các thay đổi production duy nhất thuộc về `BE-ADM-TST-05` (C2 chỉ publish sau commit — xem báo cáo đó); `BE-ADM-TST-04` chỉ thêm test.
- `./gradlew test` targeted 2 class: **BUILD SUCCESSFUL**, 10 tests / 0 failure / 0 error / 0 skip.
- Regression Admin + Common + ArchUnit + migration + Customer/Account admin HTTP: **BUILD SUCCESSFUL**, 65 classes / 559 tests / 0 failure / 0 error / 0 skip.
- Full backend `./gradlew test`: **BUILD SUCCESSFUL** (8m16s), 120 classes / 1345 tests / 0 failure / 0 error / 0 skip.
- **BLOCKED còn lại:** `ADM-OI-05` (Limit provider/422 policy), `ADM-OI-06` (tenant/staff data scope), `ADM-OI-07` (durable audit).
