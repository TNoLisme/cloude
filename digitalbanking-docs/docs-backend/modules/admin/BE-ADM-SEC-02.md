# BE-ADM-SEC-02 — PII/secret/log/audit redaction và security header regression

> **Mã task:** `BE-ADM-SEC-02` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — Admin dùng các kiểm soát redaction/no-store/correlation của Common, bổ sung header `Expires` + `X-Request-ID`, redaction free-text ở payload/decision note và C3 cho provider stale version; regression test chạy trên security chain + PostgreSQL 16 thật. Các phần phụ thuộc provider chưa có runtime được ghi rõ ở §9.
> **Nguồn:** `docs-common/07` §2.6/§2.7/§4.3/§7.2.14/§8.8, `design/04` §7/§9, `design/01` §5.2/§5.3, `design/05` §12.1, `BE-ADM-APP-04`, `BE-ADM-API-01/02/03/04`, `BE-ADM-SEC-01`.

## 1. Phân loại dữ liệu nhạy cảm trong Admin

| Nhóm | Ví dụ trong Admin | Xử lý |
|---|---|---|
| Level 4 — secret | OTP, `X-Step-Up-Challenge`, access/refresh/session token, `Authorization`, `Cookie`, `X-CSRF-Token`, password/password hash, challenge hash, provider credential | Không bao giờ trả response/log/audit; `LogRedaction` + `SafeJsonLogEncoder` fail-closed |
| Level 3 — restricted PII | số tài khoản/CCCD đầy đủ, số điện thoại/email đầy đủ | Provider (Customer/Account) mask trước khi Admin trả; Admin không query entity chéo |
| Level 2 — confidential business | `reason`, `decisionNote`, limit/role business values | Chỉ trả field contract; free-text được scrub assignment secret; payload bỏ `expectedVersion` |
| Level 1 — technical | `correlationId`, `requestId`, `approvalId`, error code, status | Được log/audit theo allowlist; metric chỉ dùng nhãn bounded |

Admin không sở hữu OTP/token/password; các giá trị này chỉ xuất hiện nếu client gửi sai vào header/body, nên kiểm soát chính là không echo và không serialize.

## 2. Log redaction

- `common.logging.LogRedaction` là nguồn duy nhất: redact theo key (`password`, `otp`, `token`, `authorization`, `cookie`, `secret`, `apikey`, `username`…), theo fragment (`refreshToken`, `mfaSecret`…), nested map/list, bearer token và assignment trong free text.
- `SafeJsonLogEncoder` chỉ serialize:
  - HTTP completion allowlist (`route`, `method`, `status`, `latency_ms`, `user_id` đã hash, `action`, `outcome`, `error_code`);
  - security-audit allowlist (`action` catalog, `audit_class`, `outcome`, `actor_user_id`, `actor_roles`, `entity_type`, `entity_id`, `request_type`, `reason_code`, `correlation_id`, `request_id`).
- Message gốc, arguments, MDC và throwable không bao giờ được ghi; chỉ `exception_type` (class name) được giữ.
- Admin không log request/response body, DTO, entity, decision note, payload, OTP, token hoặc challenge material.
- HTTP failure log chỉ chứa `code`, `status`, `correlationId`.

## 3. Audit redaction

- `AdminAuditPort` chỉ nhận fact đã kiểm soát: `action`, `auditClass`, `actorUserId`, `entityId` (approval), `requestType`, `correlationId`, `requestId`, `outcome`, `reasonCode` là `ErrorCode`.
- C2 `LIMIT_CHANGE_*`/`ROLE_CHANGE_*` và C3 `SECURITY_ACCESS_DENIED` không chứa payload, `reason`, `decisionNote`, OTP, hash, challenge ref hoặc provider entity.
- `SecurityLogAdminAuditAdapter` chỉ phát structured log + metric bounded; `SafeJsonLogEncoder` drop mọi key ngoài catalog.
- Payload/current-proposed chỉ được trả qua `ApprovalPayloadView`/`AdminApprovalView`; `expectedVersion` bị bỏ, free-text được scrub assignment secret bằng cùng `LogRedaction`.
- C3 được ghi cho provider trả `ROLE_CONFLICT` (stale role version) đúng một fact; maker-self conflict vẫn audit tại call site để không nhân đôi.

## 4. Response headers và cache

- `ApiSecurityHeadersFilter` (servlet, trước security chain, cả `REQUEST/ASYNC/ERROR`) đặt:
  - `Cache-Control: no-store`
  - `Pragma: no-cache`
  - `Expires: 0` (**bổ sung trong task này**)
  - `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP `frame-ancestors 'none'`
  - HSTS chỉ khi request thực sự secure
- Controller Admin vẫn đặt `CacheControl.noStore()` trên response thành công; filter bao phủ cả lỗi trước MVC, 401/403/404/409/428/429/5xx và error dispatch.
- Không thêm filter thứ hai: dùng đúng filter/registration hiện hữu.

## 5. Correlation và request ID

- `CorrelationAndRequestIdFilter` đọc `X-Correlation-ID`:
  - tối đa một header, pattern `[A-Za-z0-9._:-]{1,64}`;
  - sai/trùng → `400 VALIDATION_ERROR` và sinh correlation UUID mới;
  - không nhận `X-Request-ID` từ client; request ID luôn do server sinh UUIDv4.
- Response luôn có `X-Correlation-ID` và (bổ sung) `X-Request-ID`; body `Problem`/`Approval*` có `correlationId`.
- `RequestContext` cắt user-agent 256 ký tự, bỏ control char, mask IP; không bao giờ log raw IP/UA.
- `Approval*` lưu correlation vào `approval_request.correlation_id`; audit C2/C3 nhận cùng correlation/request ID.
- Không echo header injection: giá trị sai không vào log/body; test dùng secret trong header/path để chứng minh.

## 6. Metric labels

- `admin.audit.events{action,class,outcome}` và `admin.audit.failures{action,class}` chỉ dùng catalog/bounded values.
- `security.audit.events{action,outcome,entity_type}`, `rate.limit.rejections{group}` bounded.
- `http.server.requests` dùng route pattern/status/outcome của Spring; không có userId, customerId, email, token, correlation, requestId, approvalId hoặc raw URL.
- `HttpRequestLog.user_id` là `sha256:` hash (log), không phải metric label.

## 7. Thay đổi production

| File | Thay đổi |
|---|---|
| `common/config/ApiSecurityHeadersFilter.java` | thêm `Expires: 0` |
| `common/context/CorrelationAndRequestIdFilter.java` | expose `X-Request-ID` server-generated; hằng số `REQUEST_ID_HEADER` |
| `admin/adapter/out/AdminPayloadJsonCodec.java` | `publicPayload` scrub string values bằng `LogRedaction.redactText` sau khi bỏ `expectedVersion` |
| `admin/application/AdminApprovalView.java` | scrub `decisionNote` và mọi string trong `payload`/`result` |
| `admin/application/ApprovalDecisionService.java` | ghi đúng một C3 khi provider trả `ROLE_CONFLICT` (stale version), trước rollback |

Không sửa migration, OpenAPI, security chain, error catalog hoặc module khác.

## 8. Regression tests

| # | Yêu cầu | Test |
|---:|---|---|
| 1 | OTP không có trong log/audit | `AdminHttpAuditPostgreSqlIntegrationTest#sensitiveFreeTextIsRedactedAndMetricsStayBounded`, `AdminPayloadJsonCodecTest#publicPayloadRedactsCredentialAssignmentsInFreeText` |
| 2 | Access/refresh token không lộ | cùng test SEC-02 (token trong reason/header), `SafeJsonLogEncoderTest`, `LogRedactionTest` |
| 3 | Password/hash không lộ | `LogRedactionTest`, `SafeJsonLogEncoderTest`, `AdminDtoValidationTest` (field lạ bị 400) |
| 4 | Account/customer payload không lộ | `AdminHttpAuditPostgreSqlIntegrationTest` (body/header không chứa secret), `ApprovalReadHttpPostgreSqlIntegrationTest#detailReturnsMaskedPayload` |
| 5 | Current/proposed payload mask | `AdminPayloadJsonCodecTest#publicRolePayloadOmitsServerCapturedVersionAndKeepsContractFields`, `#publicPayloadRedactsCredentialAssignmentsInFreeText`, `ApprovalAdminControllerTest#decisionNoteAndPayloadFreeTextAreRedactedInTheResponse` |
| 6 | Success response no-store | `AdminHttpAuditPostgreSqlIntegrationTest#verifiedStepUpCreatesOneApprovalAndReplaysWithoutDuplicateAudit`, `#sensitiveFreeText...`, `RoleAdminControllerTest#createReturns201LocationAndNoStore` |
| 7 | Error response no-store | `AdminHttpAuditPostgreSqlIntegrationTest` helper `assertProblem` cho 401/403/404/428; `HttpSecurityTest#headersAlsoCoverPreSecurityErrors...` |
| 8 | Correlation/request ID preserved + sanitized | `RequestContextTest`, `ContextServletTest`, `AdminHttpAuditPostgreSqlIntegrationTest` (`X-Correlation-ID`, `X-Request-ID`, `request_id` log) |
| 9 | Metric labels bounded | `AdminHttpAuditPostgreSqlIntegrationTest#sensitiveFreeText...`, `ObservabilityIntegrationTest#realHttpMetrics...`, `SecurityLogAdminAuditAdapterTest` |
| 10 | Provider failure không lộ credential | `ApprovalDecisionServiceTest#providerTechnicalFailure...`, `ApprovalStepUpPostgreSqlIntegrationTest#providerFailureRollsBackConsumption...`, SEC-02 HTTP test |

## 9. Kết quả thực thi

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.common.config.*' \
  --tests 'com.simulatedbank.banking.common.context.*' \
  --tests 'com.simulatedbank.banking.common.logging.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --console=plain --no-daemon --max-workers=1
```

Kết quả ghi ở `tasks/README.md` §0.11 (cùng evidence BE-ADM-TST-01). Targeted trước đó:

- `AdminHttpAuditPostgreSqlIntegrationTest`: **9/9 PASS** (PostgreSQL 16 Testcontainers + Flyway + security chain/Identity thật).
- `AdminDtoValidationTest`: **6/6 PASS**.
- `ApprovalDecisionServiceTest`: PASS (bao gồm 2 test stale/provider failure mới).
- `AdminPayloadJsonCodecTest`, `ApprovalAdminControllerTest`, `RequestContextTest`, `HttpSecurityTest`: PASS.

## 10. Giới hạn / blocker còn mở

- `ADM-OI-07`: durable `AuditModuleApi`/`audit_log`/hash chain chưa có; C2/C3 hiện là structured security log + metric. Không claim audit bất biến đã tồn tại.
- `ADM-OI-05`: Limit provider chưa có runtime; LIMIT proposal/approve fail-closed `503`, nên không có test audit/payload limit thành công.
- `ADM-OI-06`: data scope staff theo chi nhánh/segment/tenant chưa có model; authorization dừng ở permission + anti-IDOR.
- Free-text `reason`/`decisionNote` chỉ được scrub *assignment* secret (`password=`, `otp=`, `token=`); không có bộ phân loại PII ngữ nghĩa cho tên/số tài khoản viết tự do. Contract yêu cầu client/staff không gửi PII vào các field này; nếu cần enforce mạnh hơn phải có validation/oracle được duyệt.
- Không có endpoint Admin nào trả full account number/national ID; các lookup nhạy cảm thuộc provider Customer/Account và đã mask ở provider.
