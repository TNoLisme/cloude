# Audit baseline `origin/dev` — modules của Đạt

Audit tại baseline `24606b5` trước khi triển khai trên `datht/backend`.

## Quy tắc phân xử nguồn

1. `docs-common/01–11` và OpenAPI là baseline ưu tiên.
2. `_overview` dùng để phân công và trace requirement; `design/*` không được ghi đè policy khác với baseline 01–11.
3. Lockout dùng cấu hình đề xuất **5 lần / 10 phút**, không dùng 15 phút từ `design/04`.
4. Session cho phép nhiều phiên; không áp trần 3/1 từ `design/04`.
5. Role/permission dùng `CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR` và permission tại `07 §3.4`; `CHECKER` là privilege.
6. Policy còn ghi **Đề xuất/Open** vẫn cấu hình được và không được báo là policy release đã phê duyệt.

## Foundation và common

| Module | Requirement | Đã có tại baseline | Thiếu tại baseline | Dependency | Ưu tiên | File liên quan | State |
|---|---|---|---|---|---|---|---|
| foundation | Java 21, Spring Boot 3.5.7, Gradle, PostgreSQL 16, Flyway | Toolchain/dependencies/Compose/Testcontainers có | Không có business schema | Docker cho PG tests | P0 | `build.gradle.kts`, `gradle/libs.versions.toml`, `compose.yaml` | VERIFIED (config), PARTIAL (runtime chưa chạy trong audit) |
| common/security | Một `SecurityFilterChain`, opaque session SPI, CSRF | Chain, `SessionAuthenticator`, cookie/Bearer parsing, CSRF bridge, CORS, rate limiter có | Production identity adapter | identity | P0 | `common/config/SecurityFilterChainConfig.java`, `SessionAuthenticationFilter.java` | PARTIAL |
| common/crypto | AES-256-GCM, HMAC-SHA256, masking | Primitive và test có | Consumer persistence/normalization | customer/account | P0 | `common/crypto/*` | IMPLEMENTED; consumer BLOCKED |
| common/error/context/log | Problem Details, request context, safe logging | Có implementation | Business classifications/audit integration | business modules, audit | P1 | `common/error/*`, `common/context/*` | IMPLEMENTED; integration PARTIAL |
| migration | Forward-only schema | Chỉ `V1__init_schema.sql` tạo schema | Toàn bộ bảng nghiệp vụ | module owners | P0 | `db/migration/V1__init_schema.sql` | MISSING |
| OpenAPI | 30 endpoints và DTO | Tài liệu YAML có | Không controller business | all modules | P0 | `openapi/digital-banking-v1.yaml` | CONTRACT ONLY |

## Bốn module được phân công

| Module | Requirement | Đã có tại baseline | Thiếu tại baseline | Dependency | Ưu tiên | File liên quan | State |
|---|---|---|---|---|---|---|---|
| identity | Argon2id account identity | Package rỗng | User persistence, encoder, lockout | common time/error | P0 | `identity/**`, DB §4.2.1 | MISSING |
| identity | Opaque session + CSRF + adapter | SPI common có | Session table/service/cookie/revoke/slide | common security | P0 | `SessionAuthenticator.java`, API §3.2–3.3 | BLOCKED tại baseline |
| identity | RBAC snapshot/revoke | URL gates common có | Role schema/seed/current authority snapshot/role command | admin, audit | P0 | API §3.4–3.5 | MISSING; permission gate names có BUG lệch contract |
| identity | Device/MFA/OTP/step-up | Config có | Device/challenge state, payload binding, one-time consume, delivery port | notification/FIDO provider | P0 | API §6, DB §4.2.4–4.2.6 | MISSING; provider BLOCKED |
| identity | PIN/passcode/FIDO | Public routes có | Enrollment/reset contract và provider | product policy/provider | P2 | OpenAPI auth paths | BLOCKED (contract lifecycle chưa đủ) |
| customer | Customer/KYC/PII persistence | Package rỗng, crypto common có | Schema, encrypted fields, blind index, mapper | identity | P1 | DB §4.2.2, overview 02 | MISSING |
| customer | Self/staff API + query port | Security routes có | Port/service/controllers/ownership boundary | identity, audit | P1 | API-CUS-01/API-ADM-01 | MISSING |
| account | Account protected persistence | Package rỗng | Schema/crypto/query mapping | customer | P1 | DB §4.3.1 | MISSING |
| account | Ownership APIs | Security routes có | Owner/service/controllers/staff lookup | identity/customer/audit | P1 | API-ACC-01/02, API-ADM-02 | MISSING |
| account | Lock/debit/credit | Common PG error test chỉ là probe | Real ordered locks and conditional updates | transfer/ledger | P1 | consistency §A.3/A.4 | MISSING; end-to-end BLOCKED |
| admin | Approval/maker-checker | Package rỗng | Schema/service/locks/controllers | identity step-up | P2 | DB §4.5.3, API admin paths | MISSING |
| admin | Role mutation | Chưa có port | Identity role command + session revoke | identity/audit | P2 | overview 10 | MISSING |
| admin | Limit mutation | Limit package rỗng | Contract/implementation | Vũ (`limit`) | P2 | API-LIM-02/03 | BLOCKED |
| admin | Audit integration | Audit package rỗng | Public recorder implementation | Thịnh (`audit`) | P2 | overview 10 | BLOCKED |

## Kế hoạch theo dependency

1. Migration forward-only cho identity/customer/account/admin và RBAC catalog.
2. Identity: Argon2id → session adapter/CSRF → device OTP → step-up consume → role change/revoke; kiểm thử security trước module khác.
3. Customer: encrypted persistence/query port → self/staff API → PII/ownership tests.
4. Account: encrypted persistence/query API → ownership → PostgreSQL ordered lock/conditional update tests.
5. Admin: approval state/locking → identity role command → limit/audit ports; phần không có implementation owner khác giữ `BLOCKED`, không tạo fallback giả.
6. Chạy quality gate một lần sau khi hoàn tất; ghi evidence thực tế và không nâng dependency chưa chạy thành PASS.

## Remediation findings — `datht/backend`

| ID | Nguyên nhân | File sửa chính | Test evidence | Trạng thái |
|---|---|---|---|---|
| AUTH-H01 | Authorization/state checks phụ thuộc quá nhiều vào URL gate và state tại lúc tạo challenge | `IdentityService.java`, `AuthController.java` | `AssignedModulesIntegrationTest.identity*` trên PostgreSQL 16 | FIXED |
| AUTH-H02 | C3 audit exception có thể thay đổi kết quả transaction lockout | `IdentityService.java`, `IdentitySecurityAuditSink.java` | `identityResetsExpiredLockAndC3AuditFailureCannotRollbackLockout` | FIXED |
| AUTH-H03 | Device/session revoke, expired lock counter, cookie name và OTP resend/state chưa đồng bộ | `IdentityService.java`, `AuthController.java`, `SessionAuthenticationFilter.java` | Identity focused suite + customer HTTP cookie path | FIXED |
| CUS-H01 | Ownership/staff authorization chỉ dựa vào chain; DTO lộ field ngoài contract | `CustomerService.java`, `CustomerController.java` | Customer/account focused suite | PARTIAL — ownership/permission FIXED; organization scope BLOCKED do chưa có canonical scope model |
| ACC-H01 | Opening balance và balance-only mutation có thể phá ledger invariant | `AccountService.java` | `accountCreationAndMutationFailClosedWithoutLedgerIntegration` | PARTIAL — zero opening FIXED; mutation BLOCKED/fail-closed chờ ledger contract của Phương |
| ADM-H01 | Approval thiếu service authorization, filter/page đúng contract, payload redaction, expiry và deterministic concurrent decision | `ApprovalService.java`, `ApprovalController.java` | Approval focused suite trên PostgreSQL 16 | FIXED |
| INT-H01 | Audit production API/adapter của Thịnh chưa có | `IdentitySecurityAuditSink.java`, `ApprovalAuditSink.java` | Full context xác nhận không có production bean; test chỉ import adapter test-only | BLOCKED |
| INT-H02 | Limit mutation API/adapter của Vũ chưa có | `ApprovalService.java` | `makerCheckerAndUnavailableLimitIntegrationFailClosed` | BLOCKED — fail closed trước challenge consumption |
| MIG-H01 | V2 dùng status/permission/challenge column lệch canonical contract | `V3__identity_customer_account_admin.sql` | `PostgreSqlFlywayMigrationIntegrationTest` với PostgreSQL 16, V1 → V2 | FIXED; version collision khi rebase vẫn cần team coordination |
| CTX-H01 | Global test property tắt business module, che dependency/controller conflict | Test annotations riêng và `PostgreSqlFlywayMigrationIntegrationTest.java` | Full-context Spring test với `banking.modules.enabled=true` | FIXED |

### Verification thực tế

```text
./gradlew test
BUILD SUCCESSFUL

./gradlew check
BUILD SUCCESSFUL

./gradlew build
BUILD SUCCESSFUL
```

Green build không gỡ các blocker audit, limit, ledger hoặc staff data-scope nêu trên.

## Customer/Account completion — 2026-11-15

Canonical review confirmed that Customer/Account expose read APIs only. Staff branch/portfolio scope, Customer CRUD writes, Account lock/close writes and their permissions are not defined. Those policies were not invented.

| ID | Root Cause | Files Changed | Test Evidence | Status |
|---|---|---|---|---|
| CA-H01 | Self operations trusted principal `customerId` without revalidating the `userId → customerId` mapping | `CustomerService.java`, `AccountService.java`, `AssignedModulesIntegrationTest.java` | Forged principal is denied for profile, list and detail | FIXED |
| CA-H02 | `CUSTOMER_READ`/`ACCOUNT_READ` gave staff global access despite no canonical branch/portfolio scope model | `CustomerService.java`, `AccountService.java`, `AssignedModulesIntegrationTest.java` | Valid Operations session receives fail-closed `503`; invalid role receives 403 | BLOCKED — safe fail-closed |
| CA-H03 | Invalid onboarding input and unique races produced unstable/internal errors | `CustomerService.java`, `AccountService.java`, `AssignedModulesIntegrationTest.java` | Validation 400; concurrent duplicate customer/account creation yields one success and one 409 | FIXED |
| CA-H04 | Customer/account onboarding rollback and repository constraints lacked PostgreSQL evidence | `AssignedModulesIntegrationTest.java` | Forced rollback leaves no row; unique blind indexes and inactive-customer guard pass | FIXED |
| ACC-H02 | Account item `asOf` reflected unrelated `updated_at`, not one response snapshot | `AccountService.java`, `AssignedModulesIntegrationTest.java` | Item and response `asOf` are identical | FIXED |
| ACC-C01 | Ledger-independent debit/credit would break financial invariants | `AccountService.java` | Real-transaction calls fail `SERVICE_UNAVAILABLE`; balances remain zero | BLOCKED — safe fail-closed |
| API-C01 | Customer update/delete and Account lock/close APIs, permissions and transitions are absent from canonical API/RBAC | Reports only | Contract/OpenAPI inspection; authenticated mass-assignment attempts return 405 | BLOCKED |
| AUD-C01 | C2 account-status and C3 access-denied production audit adapters are absent | Reports only | Full context uses no fake production adapter | BLOCKED |
| ADM-L01 | PostgreSQL `55P03` from approval `NOWAIT` could be translated as `UncategorizedSQLException` instead of the domain conflict | `ApprovalService.java` | Focused concurrent approval test and final full suite | FIXED — necessary out-of-scope regression repair |

### Final verification

```text
Customer focused PostgreSQL 16 suite: BUILD SUCCESSFUL
Account focused PostgreSQL 16 suite: BUILD SUCCESSFUL
V1 → V2 full-context PostgreSQL 16 suite: BUILD SUCCESSFUL
./gradlew test --console=plain: BUILD SUCCESSFUL — 215 tests, 0 failed, 0 errors, 0 skipped
./gradlew check --console=plain: BUILD SUCCESSFUL
./gradlew build --console=plain: BUILD SUCCESSFUL
```

V2 was not rewritten in this completion pass. Its current V1 → V2 path passes on PostgreSQL 16; version collision against newer remote migrations remains a pre-merge coordination risk.
