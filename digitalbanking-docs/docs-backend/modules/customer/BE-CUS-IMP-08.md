# BE-CUS-IMP-08 — Security, API, Database regression và evidence

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** **PARTIAL** — toàn bộ regression thuộc Customer chạy được đã PASS; các tiêu chí toàn hệ thống còn phụ thuộc Account, Admin data-scope/Maker-Checker, Notification và durable Audit.
> **Baseline khi chạy:** `199e33e8fed19361a6727c80f236e784364d5daa`.

## A. Nguồn yêu cầu đã đối chiếu

| ID | Định nghĩa authoritative | Kết luận phạm vi |
|---|---|---|
| `AC-005` | `docs-common/03. SRS.md` §10.1: customer chỉ thấy account của mình, balance đúng, account number mask | Account runtime chưa tồn tại; Customer profile không thay thế account acceptance. |
| `AC-023` | `03. SRS` §10.1: PII trong response/log/notification phải mask; không lộ full account/phone/email | Customer response/log/database kiểm được; Notification và full system scan ngoài Customer chưa có. |
| `AC-024` | `03. SRS` §10.1: Customer A truy cập dữ liệu B → 403 + security audit event | Customer admin path kiểm 403, no disclosure và C3 event; Account ownership runtime vẫn thiếu. |
| `AC-027` | `03. SRS` §10.1: Ops có quyền đọc customer/account/transaction trong scope; ngoài scope 403 | Permission Customer kiểm được; scope CUS-OI-05, Account và Transaction chưa có. |
| `CT-API-015` | `docs-common/design/04. security.md` §11: IDOR giữa hai customer → `403 AUTH_FORBIDDEN`, không lộ dữ liệu, `SECURITY_ACCESS_DENIED` | Customer HTTP/C3 path kiểm được; durable Audit và Account path còn thiếu. |
| `CT-API-016` | `design/04` §11: RBAC staff + Maker-Checker, truy cập trái quyền 403, self-approve bị `MAKER_CHECKER_VIOLATION` | Customer kiểm staff thiếu `CUSTOMER_READ`; Maker-Checker thuộc Admin và chưa có runtime. |
| `CT-API-017` | `design/04` §11: scanner không thấy full account/CCCD/secret trong response/log/notification/audit | Customer phone/email/token/ciphertext response/log scan PASS; Notification/Audit scanner chưa có. |
| `T-AUD-16` | `docs-common/design/01. audit-traceability.md` §13.2: scanner accountNo/phone/email/OTP/description phải reject và không ghi audit | Customer contract chỉ tạo allowlisted C3 fields và stdout an toàn; `AuditModuleApi`, validator và `audit_log` không tồn tại nên exact reject/no-write BLOCKED. |

`docs-backend/modules/customer/design.md` §2 gắn self profile với `AC-005`, nhưng `03. SRS` định nghĩa `AC-005` bằng account list/detail. Báo cáo này dùng SRS làm nguồn ưu tiên và không nhận self-profile test thay cho account acceptance.

## B. Test implementation

### File thay đổi

| File | Thay đổi BE-CUS-IMP-08 |
|---|---|
| `CustomerProfileControllerPostgreSqlIntegrationTest.java` | Thêm malformed/unsupported/oversized Bearer regression; xác nhận 401, RFC 9457 bắt buộc và không lộ credential/framework/DB internals. |
| `CustomerAdminDetailControllerPostgreSqlIntegrationTest.java` | Gắn trace `AC-023/024/027`, `CT-API-015/016/017`; scan cả HTTP response và stdout JSON sau `SafeJsonLogEncoder`; mở rộng kiểm RFC 9457. |
| `CustomerPiiPostgreSqlIntegrationTest.java` | Kiểm plaintext byte sequence không tồn tại trong ba cột encrypted; thêm FK-failure rollback qua service/JPA/PostgreSQL thật. |
| `CustomerSecurityLogAuditAdapterTest.java` | Gắn trace contract-level `CT-API-017/T-AUD-16`; kiểm event C3 chỉ có field allowlist, timestamp/trace và failure policy. |

Không thêm production Audit scanner hoặc `audit_log` trong Customer. Làm như vậy sẽ vi phạm ownership của Audit và tạo hạ tầng trùng.

### Coverage thực thi

- Authentication: anonymous, invalid, expired, disabled, malformed scheme/token và token quá dài.
- Authorization: thiếu self permission, CUSTOMER gọi admin path, staff thiếu `CUSTOMER_READ`, forged role/permission headers không bypass.
- IDOR/BOLA: query parameter không đổi principal; unbound identity không đọc customer khác; admin path cross-customer bị 403 + C3 event.
- API contract: success DTO đúng 8 field; 400/401/403/404 Problem bắt buộc; no-store/correlation; missing/inactive/read-only.
- PII: owner/staff masking, stdout redaction, safe audit fields, ciphertext-at-rest và tamper fail-closed.
- PostgreSQL: fresh Flyway V5, schema/PK/FK/unique/check/index, nullable/NOT NULL, sequential/concurrent duplicate, rollback và persistence/query correctness.
- Không áp dụng theo published Customer API hiện tại: request JSON/unknown fields/mass assignment, create/update/delete, lifecycle transition, pagination/filter/sort, write idempotency. Hai endpoint Customer đã công bố đều là GET không body; không tạo contract mới chỉ để test.

## C. Lệnh và môi trường

Chạy từ `digitalbanking-backend`:

```bash
./gradlew testClasses --console=plain
./gradlew test \
  --tests 'com.simulatedbank.banking.customer.*' \
  --tests 'com.simulatedbank.banking.migration.CustomerV5MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.common.config.SecurityAccessDeniedAuditorTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --rerun-tasks --max-workers=1 --console=plain
./gradlew build --rerun-tasks --max-workers=1 --console=plain
```

| Thành phần | Version / cấu hình |
|---|---|
| OS | Linux x86_64, kernel `6.19.14+kali-amd64` |
| Java | OpenJDK `21.0.11-ea` |
| Gradle | Wrapper `8.14.3` |
| Spring Boot | `3.5.7` |
| PostgreSQL | Testcontainers `postgres:16-alpine` |
| Docker | Client/Server `29.7.2` |
| Test data | Synthetic; không production account/PII/credential |

## D. Kết quả focused regression

Lệnh focused cuối: **BUILD SUCCESSFUL in 31s**.

| Scope | Classes | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|---:|
| Customer unit/API/security/database/audit + V5 migration + security auditor + architecture | 13 | 94 | 94 | 0 | 0 | 0 |

Evidence generated:

- `digitalbanking-backend/build/evidence/BE-CUS-IMP-08/focused/junit-xml/`
- `digitalbanking-backend/build/evidence/BE-CUS-IMP-08/focused/html-report/index.html`
- Gradle canonical output: `digitalbanking-backend/build/test-results/test/` và `build/reports/tests/test/` sau mỗi lần chạy.

### Full backend regression

`./gradlew build --rerun-tasks --max-workers=1 --console=plain`: **FAILED in 6m16s — 70 classes / 900 tests / 899 pass / 1 failure / 0 error / 0 skipped**.

Failure duy nhất: `IdentityAuthPostgreSqlIntegrationTest.t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate`; assertion nhận `OutOfMemoryError: Java heap space` trong BouncyCastle `Argon2BytesGenerator`. Đây là test/resource defect của Identity đã được ghi tại DEC-ID-TEST-02 và BE-CUS-IMP-06/07; stack không đi qua Customer. Không sửa hoặc rerun Identity để tạo kết quả xanh giả.

Evidence:

- `digitalbanking-backend/build/evidence/BE-CUS-IMP-08/full/junit-xml/`
- `digitalbanking-backend/build/evidence/BE-CUS-IMP-08/full/html-report/index.html`
- Failure XML: `full/junit-xml/TEST-com.simulatedbank.banking.identity.IdentityAuthPostgreSqlIntegrationTest.xml`.

## E. Acceptance traceability và actual result

| Requirement ID | Test case | Scope | Preconditions | Expected | Actual | Status | Evidence | Defect |
|---|---|---|---|---|---|---|---|---|
| `AC-005` | `CustomerProfileControllerPostgreSqlIntegrationTest.authenticatedOwnerReceivesExactCustomerProfileContract` chỉ là Customer-profile supporting test | API/Security | Session CUSTOMER + row Customer; Account runtime cần cho exact AC | Chỉ account owner, balance đúng, account number mask | Self Customer profile PASS; không có Account endpoint/entity/balance để chạy exact AC | **BLOCKED** | Customer profile XML; SRS §10.1 line AC-005 | Owner Account phải triển khai §7.1 contract/runtime |
| `AC-023` | `CustomerAdminDetailControllerPostgreSqlIntegrationTest.authorizedStaffReceivesMaskedCustomerProfileContract`; `CustomerPiiPostgreSqlIntegrationTest.creationPersistsOnlyAesGcmCiphertextAndSerializesOwnerSafeFields` | Security/API/DB | Real Identity session, Customer crypto/JPA, PostgreSQL | Không full account/phone/email trong response/log/notification | Customer response/stdout/DB PASS; Notification và system-wide scan chưa chạy được | **BLOCKED** | JUnit XML hai class; focused HTML | Notification runtime và full audit scanner thiếu |
| `AC-024` | `CustomerAdminDetailControllerPostgreSqlIntegrationTest.customerRoleIsDeniedAndAccessDeniedIsAudited` | Security/API/Audit C3 | Customer A session, Customer B row | 403, không lộ dữ liệu, security event | 403 `AUTH_FORBIDDEN`; no PII; structured `SECURITY_ACCESS_DENIED` + metric có actor/target/trace | **PASS** (Customer path) | Display name `AC-024/CT-API-015`; HTTP integration XML | Durable Audit vẫn là blocker integration, không đổi observed C3 result |
| `AC-027` | `authorizedStaffReceivesMaskedCustomerProfileContract`; `staffWithoutCustomerReadPermissionIsDeniedAndAudited` | Security/API | OPERATIONS/ADMIN/AUDITOR sessions + RBAC DB | In-scope allowed; out-of-scope 403 | Permission success/deny PASS; không có scope model, account/transaction runtime | **BLOCKED** | Staff HTTP XML | CUS-OI-05 + Account/Transaction owners |
| `CT-API-015` | `customerRoleIsDeniedAndAccessDeniedIsAudited` | Security/API/Audit C3 | Customer A calls B ID on protected Customer resource | 403, no disclosure, `SECURITY_ACCESS_DENIED` | Exact HTTP/error/no-PII/C3 event assertions PASS | **PASS** (Customer path) | JUnit display name `AC-024/CT-API-015` | Account-specific IDOR remains owner Account scope |
| `CT-API-016` | `staffWithoutCustomerReadPermissionIsDeniedAndAudited` | Security/API | Authenticated staff without `CUSTOMER_READ` | 403; Maker cannot self-approve | Customer RBAC branch PASS; Maker-Checker branch unavailable | **BLOCKED** | JUnit display name `CT-API-016` | Owner Admin must provide approval runtime |
| `CT-API-017` | Staff response/stdout scan + `CustomerSecurityLogAuditAdapterTest.deniedOperationProducesTraceablePiiSafeEvidence` | Security/API/Audit contract | Synthetic PII, real encoder, Customer audit adapter | No full account/CCCD/secret in response/log/notification/audit | Customer phone/email/token/ciphertext absent; Notification and durable audit validator unavailable | **BLOCKED** | Staff/audit XML; focused HTML | Notification + Audit owners |
| `T-AUD-16` | `CustomerSecurityLogAuditAdapterTest.deniedOperationProducesTraceablePiiSafeEvidence` (contract-level only) | Audit contract | Customer C3 adapter; no Audit provider | PII payload rejected; no audit row written | Customer event cannot carry PII and encoded output is safe; reject/no-write against `audit_log` not executable | **BLOCKED** | Audit adapter XML; `design/01` §5.6/§13.2 | Owner Audit must implement validator + persistence |

## F. Defect và fix

- Không phát hiện production defect thuộc Customer trong focused execution hoặc failure list của full build.
- Test-code defect trong lần compile đầu: AssertJ version hiện tại không có `doesNotContainSubsequence(byte[])`. Đổi sang byte-sequence scanner deterministic cục bộ; `testClasses` rerun PASS rồi focused 94/94 PASS. Không đổi/weaken assertion.

## G. Blockers

1. **Account owner:** thiếu `account.api.AccountQuery.getAccountView/assertOwnership`, Account persistence/endpoints/balance/status. Chặn exact `AC-005`, phần Account của `AC-024/CT-API-015`.
2. **Admin / Security / BA:** thiếu data-scope runtime CUS-OI-05. Chặn `AC-027`; AUDITOR seed vẫn mâu thuẫn API §8.2.4. Owner Admin còn thiếu Maker-Checker runtime cho full `CT-API-016`.
3. **Audit owner:** thiếu `AuditModuleApi.recordStandalone`, validator PII allowlist/scanner, `banking.audit_log`, hash chain. Chặn exact `T-AUD-16` và durable evidence của security denial.
4. **Notification owner:** thiếu notification runtime/payload scanner. Chặn full `AC-023/CT-API-017`.
5. **API/BA/Security:** CUS-OI-02 mask fullName staff hiện dùng DEC-CUS-005 tạm thời; cần sign-off oracle.

## H. Final status

**PARTIAL.** Customer-owned executable surface đã compile và focused regression **94/94 PASS**. Full backend chạy **900 tests: 899 PASS, 1 FAIL** do Identity Argon2 concurrent OOM đã biết, không thuộc Customer. Không đánh dấu DONE vì sáu trong tám required identifiers còn điều kiện bắt buộc không thể thực thi do provider/runtime ngoài Customer chưa tồn tại; không đánh dấu test chưa chạy là PASS.
