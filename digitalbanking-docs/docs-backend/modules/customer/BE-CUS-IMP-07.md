# BE-CUS-IMP-07 — Identity / Account / Admin / Audit real integration

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** **PARTIAL** — Identity và Admin permission integration dùng runtime thật; C3 Audit có structured evidence/failure policy; Account runtime, Admin data-scope và durable Audit còn BLOCKED.
> **Quyết định:** [DEC-CUS-006](../../../../DECISIONS.md) · Modules contract §6.4.

## A. Implementation summary

### Identity

- Tái sử dụng nguyên opaque-session pipeline hiện hữu: `IdentitySessionAuthenticator` xác thực token/session/user/authority → `SessionAuthenticationFilter` tạo Spring Security principal → Customer controller lấy snapshot tin cậy.
- Self-service chỉ dùng principal `userId`, resolve authoritative `userId → customerId` qua `customer.api.CustomerQuery.findByUserId`, rồi tái kiểm ownership trước decrypt. Không tin `customerId`, `userId`, role hoặc permission từ client; không tạo JWT/authenticator thứ hai.
- HTTP/PostgreSQL coverage gồm valid principal, anonymous, invalid/expired token, disabled user, user không có Customer row và query-param IDOR. `Session.customerId` hiện `null` nhưng không ảnh hưởng vì Customer sở hữu binding authoritative.

### Account

- Không thêm code Account giả. Module `account` hiện chỉ có skeleton `.gitkeep`; chưa có `account.api.AccountQuery`, entity/repository, service hoặc endpoint.
- Customer-side contract sẵn có và đã test bằng PostgreSQL thật: `CustomerQuery.findByUserId` trả minimal immutable snapshot, phân biệt missing/INACTIVE, không PII, lỗi persistence lan truyền.
- Real ownership, account missing/inactive/closed và integration failure thuộc implementation `account.api.AccountQuery.getAccountView/assertOwnership` của owner Account; trạng thái **BLOCKED**, không trình bày Customer contract test như real Account integration.

### Admin / permission

- Tái sử dụng permission `CUSTOMER_READ` từ Identity RBAC seed tại security chain và re-check trong `StaffCustomerDetailService` trước lookup/decrypt.
- HTTP test dùng session/RBAC/PostgreSQL thật cho OPERATIONS/ADMIN success, CUSTOMER/AUDITOR/anonymous deny. Header `X-Role: ADMIN` và `X-Permissions: CUSTOMER_READ` giả mạo không bypass principal authority.
- Data-scope staff CUS-OI-05 chưa có model/runtime; không tự thêm permission/scope. Integration tổng giữ **PARTIAL**.

### Audit

- `SecurityAccessDeniedAuditor` ghi `SECURITY_ACCESS_DENIED` C3 bằng structured key-value, gồm action, outcome, actor user/roles, target type/ID, reason, correlation ID và request ID từ `RequestContext` tin cậy.
- `CustomerSecurityLogAuditAdapter` dùng cùng structured contract; C3 write failure không thay đổi quyết định 403, phát failure metric + fallback event.
- `SafeJsonLogEncoder` chỉ serialize field audit allowlist cho hai logger Customer/Common; raw message, request body, PII, token, IP và user-agent không đi ra stdout. HTTP integration test kiểm trực tiếp JSON sau encoder, không chỉ event trước encoder.
- Durable `audit.api.AuditModuleApi.recordStandalone`, `banking.audit_log` và hash chain chưa tồn tại; structured log + metric là fallback evidence, không phải durable Audit integration.

### Architecture

- ArchUnit mới chặn mọi dependency từ Customer tới `identity/account/admin/audit` package `adapter`, `application`, `domain`.
- Customer chỉ dùng Common security SPI và public provider contract; không đọc repository/entity module khác, không tạo cycle hoặc migration cross-owner.

## B. Modified files

| File | Thay đổi | Owner |
|---|---|---|
| `common/config/SecurityAccessDeniedAuditor.java` | Structured C3 deny audit với actor/target/correlation/request ID | Common integration surface |
| `common/config/SafeJsonLogEncoder.java` | Allowlist serialize security-audit fields; vẫn drop raw message/args/MDC | Common integration surface |
| `customer/adapter/out/CustomerSecurityLogAuditAdapter.java` | Structured success/failure audit evidence | Customer |
| `CustomerProfileControllerPostgreSqlIntegrationTest.java` | Disabled Identity bị 401 trước Customer resolution | Customer test |
| `CustomerAdminDetailControllerPostgreSqlIntegrationTest.java` | Forged role/permission bị bỏ qua; verify stdout audit JSON không PII | Customer test |
| `CustomerSecurityLogAuditAdapterTest.java` | Audit metadata, timestamp, metric, PII safety và failure policy | Customer test |
| `SecurityAccessDeniedAuditorTest.java` | Trusted principal/target/trace propagation vào chain-level audit | Common test |
| `ModuleBoundaryArchTest.java` | Cấm Customer import internals của Identity/Account/Admin/Audit | Architecture test |
| `DECISIONS.md`, Modules contract §6.4 | Ghi quyết định, exact missing contracts và test-only alternative | Shared docs |
| `docs-backend/modules/customer/*` | Report, design, README và checklist IMP-07 | Customer docs |

Không thay public REST schema, database migration hoặc production source của Identity/Account/Admin/Audit.

## C. Integration status

| Integration | Status | Evidence | Remaining dependency |
|---|---|---|---|
| Identity | **PASS** trong phạm vi Customer | Real Identity session/authentication/RBAC + HTTP + PostgreSQL; valid/missing/invalid/expired/disabled/unresolved/cross-customer cases | Identity owner có thể wire `Session.customerId`; Customer không phụ thuộc field này |
| Account | **BLOCKED** | Customer provider `CustomerQuery` đã có PostgreSQL contract tests; ArchUnit no repository coupling | Owner Account triển khai `account.api.AccountQuery.getAccountView/assertOwnership`, Account persistence/status/ownership và real E2E |
| Admin | **PARTIAL** | OPERATIONS/ADMIN success; CUSTOMER/AUDITOR/anonymous/missing permission/forged headers denied qua chain thật | CUS-OI-05 data-scope model; phân xử AUDITOR seed với API §8.2.4 |
| Audit | **PARTIAL** | Structured C3 event + metric + trace; stdout JSON allowlist; provider failure giữ 403 | `AuditModuleApi.recordStandalone`, `audit_log`, hash chain/durability từ owner Audit |

## D. Test evidence

### Focused final verification

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerProfileControllerPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerSecurityLogAuditAdapterTest' \
  --tests 'com.simulatedbank.banking.common.config.SecurityAccessDeniedAuditorTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
```

- **BUILD SUCCESSFUL — 5 classes / 34 tests / 0 failure / 0 error / 0 skipped**.
- 12 self-profile HTTP/PostgreSQL tests; 9 admin HTTP/PostgreSQL tests; 3 audit tests; 10 ArchUnit tests.
- Real integration evidence: Identity session/user/role/permission tables, Flyway V1–V5, security filters, controller/service, JPA/crypto and PostgreSQL Testcontainers. Audit adapter failure test là controlled test double, không phải production Audit provider.

### Full backend build

```bash
./gradlew build --max-workers=1 --console=plain
```

- **FAILED — 898 tests, 1 failure, 0 error**.
- Failure duy nhất: `IdentityAuthPostgreSqlIntegrationTest.t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate`, assertion nhận `OutOfMemoryError: Java heap space` tại BouncyCastle `Argon2BytesGenerator`.
- Phân loại: **existing Identity test-resource failure**, cùng test/Argon2 concurrent 64 MB đã được ghi tại BE-CUS-IMP-06 §E và DEC-ID-TEST-02; không tham chiếu Customer/Common code của IMP-07. Không sửa Identity production/test chỉ để làm build xanh.
- Sau failure này, focused final verification ở trên compile và chạy toàn bộ changed behavioral surface thành công.

## E. Blockers

1. **Owner Account**
   - Thiếu: production `account.api.AccountQuery` implementation (`getAccountView/assertOwnership`), Account entity/repository/service/endpoints.
   - Impact: không thể chứng minh real Customer↔Account ownership, missing/inactive/closed account hoặc provider failure.
   - Action: triển khai contract §7.1; Account consume `CustomerQuery` qua `customer.api`; chạy HTTP/PostgreSQL E2E, không import Customer internals.
2. **Owner Admin / Security / BA**
   - Thiếu: data-scope model/policy cho staff (CUS-OI-05).
   - Impact: mọi staff có `CUSTOMER_READ` hiện đọc được mọi Customer row; permission đúng nhưng scope production chưa hoàn tất.
   - Action: chốt scope representation, enforcement point, deny audit metadata và contract tests.
3. **Owner Audit**
   - Thiếu: `audit.api.AuditModuleApi.recordStandalone(AuditEvent)`, `banking.audit_log`, hash chain và standalone transaction implementation.
   - Impact: deny evidence hiện là structured security log + metric, chưa durable/queryable/tamper-evident.
   - Action: cung cấp provider API/runtime; thay Customer adapter và chạy failure/durability integration thật.
4. **Owner API / Identity / DB**
   - Thiếu phân xử: API §8.2.4 nêu AUDITOR với `CUSTOMER_READ`, nhưng seed hiện không cấp permission này.
   - Impact: AUDITOR bị 403 fail-closed.
   - Action: sửa contract hoặc seed bằng task owner-approved; Customer không tự cấp quyền.

## F. Acceptance criteria

| Tiêu chí | Kết quả | Evidence |
|---|---|---|
| End-to-end principal propagation and validation | **PASS** trong Customer | Real Identity session → Spring principal → CustomerQuery → ownership/decrypt; disabled/unresolved/cross-customer tests |
| Correct permission enforcement and RBAC | **PARTIAL** | Permission/roles/bypass PASS; staff data-scope và AUDITOR conflict BLOCKED |
| Reliable Customer ↔ Account mapping | **BLOCKED** | Customer `userId→customerId` provider PASS; Account ownership/status runtime không tồn tại |
| No direct repository access across boundaries | **PASS** | ArchUnit 10/10; explicit cross-module internals rule |
| Audit evidence for sensitive Customer operations | **PARTIAL** | Structured C3 stdout + metric + actor/target/trace/failure policy PASS; durable Audit BLOCKED |
| Automated integration success/failure tests | **PARTIAL** | Changed surface 34/34 PASS; real Account/Audit provider scenarios BLOCKED; full build có 1 existing Identity OOM |

Không đánh dấu BE-CUS-IMP-07 DONE vì ba dependency production còn thiếu.
