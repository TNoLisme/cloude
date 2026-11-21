# BE-ADM-REL-01 — Admin release gate evidence

> **Task:** `BE-ADM-REL-01` (P0) — Release gate, OpenAPI validation, architecture/security review và evidence package.
> **Ngày:** 18/11/2026. **Branch:** `datht-backend-rework` (không tạo/merge/push branch).
> **Owner:** Đạt (Admin); review cần DB, Security, QA và API/BA.
> **Môi trường:** Java 21.0.11, Gradle wrapper 8.14.3, Spring Boot 3.5.7, PostgreSQL 16 (Testcontainers), Flyway `V1`–`V11`.
> **Kết luận:** **BLOCKED** — toàn bộ tiêu chí kỹ thuật P0 **PASS**, nhưng owner sign-off chưa có bằng chứng phê duyệt. Không tuyên bố release PASS khi chưa có sign-off.

## 1. Release gate checklist

| # | Tiêu chí P0 | Kết quả | Evidence |
|---:|---|---|---|
| 1 | `AC-028` verified | **PASS** | [§2](#2-ac-028-verification); `AdminAc028AcceptancePostgreSqlIntegrationTest` 4/4 |
| 2 | Admin API contract suite verified | **PASS** | `AdminOpenApiContractTest`, `AdminHttpContractPostgreSqlIntegrationTest`, `AdminAc028AcceptancePostgreSqlIntegrationTest` — [§7](#7-test-execution) |
| 3 | OpenAPI validated | **PASS** | [§3](#3-openapi-validation); document lint + HTTP contract |
| 4 | Architecture review completed | **PASS** | [§4](#4-architecture-review); `ModuleBoundaryArchTest` |
| 5 | Security review completed | **PASS (technical)** | [§5](#5-security-review); negative tests + fix `SEC-REL-01` |
| 6 | Full relevant build/test evidence | **PASS** | [§7](#7-test-execution); full `cleanTest build` 149 classes / 1575 tests / 0 failure |
| 7 | Fresh database migration | **PASS** | [§8](#8-fresh-migration); `PostgreSqlFreshMigrationIntegrationTest` |
| 8 | No unresolved P0 defect | **PASS** | Không còn P0 mở; P1/P2 ở [§9](#9-p1p2-blockers-và-findings) |
| 9 | P1/P2 documented (severity/impact/owner/follow-up) | **PASS** | [§9](#9-p1p2-blockers-và-findings) |
| 10 | Source/docs/OpenAPI synchronized | **PASS** | `DEC-ADM-016`, task tracker, OpenAPI lint |
| 11 | Owner sign-off | **BLOCKED** | Chưa có approval evidence; xem [§11](#11-owner-sign-off) |

**Overall gate:** **BLOCKED** (technical evidence PASS; sign-off pending).

## 2. AC-028 verification

**Nguồn chuẩn:** `docs-common/03. SRS.md` §10.1 — “Admin Maker tạo thay đổi role/limit; Checker approve → áp dụng; reject → không áp dụng; maker = checker bị chặn; audit”.

| Mệnh đề AC-028 | Implementation | Automated evidence | Kết quả |
|---|---|---|---|
| Checker approve → áp dụng role | `ApprovalDecisionService.applyApprovedChange` → `IdentityRoleCommand.applyApprovedRoleChange` (JOIN/MANDATORY), persist `APPLIED` | `AdminAc028AcceptancePostgreSqlIntegrationTest#checkerApprovalAppliesTheRoleChange`; `ApprovalDecisionHttpPostgreSqlIntegrationTest` | **PASS** |
| Checker approve → áp dụng limit | `ApprovalDecisionService` → `LimitConfigMutationPort.applyApprovedLimitChange` (version/policy), `limit_config` `ACTIVE` | `AdminAc028AcceptancePostgreSqlIntegrationTest#checkerApprovalAppliesTheLimitChange`; `LimitApprovalHttpPostgreSqlIntegrationTest` | **PASS** |
| Checker reject → không áp dụng | `ApprovalRequest.reject` + `rejectPendingChange`; không gọi provider apply | `AdminAc028AcceptancePostgreSqlIntegrationTest#checkerRejectionAppliesNoChange` | **PASS** |
| Maker = Checker bị chặn | `ApprovalRequest.assertCheckerEligible` + DB `ck_approval_maker_checker`; `409 ROLE_CONFLICT` trước consume/provider | `AdminAc028AcceptancePostgreSqlIntegrationTest#makerCannotBeTheChecker`; `AdminSecurityMatrixPostgreSqlIntegrationTest#makerCannotApproveOwnRequest` | **PASS** |
| Audit đầy đủ | C2 `*_CHANGE_APPROVED/REJECTED` trong transaction; C3 `SECURITY_ACCESS_DENIED` độc lập; `audit_log` hash chain | Audit assertions trong cùng test AC-028; `DurableAdminAuditAdapterTest`, `AuditChainTest` | **PASS** |

**Kết quả:** `AdminAc028AcceptancePostgreSqlIntegrationTest` — **4 tests / 0 failure / 0 error / 0 skip**, PostgreSQL 16 + security chain + Identity step-up + Limit provider thật (OTP transport là test double duy nhất).

## 3. OpenAPI validation

**Document:** `digitalbanking-docs/openapi/digital-banking-v1.yaml` (`openapi: 3.1.0`, `info.version: 1.0.1`). Nguồn ngữ nghĩa: `docs-common/07. API contracts.md`.

| Kiểm tra | Cơ chế | Kết quả |
|---|---|---|
| Path + method + `operationId` khớp controller | Reflection trên 8 Admin operation (`/admin/roles`, `/admin/roles/{id}/approve`, `/admin/limits`, `/admin/limits/{id}/approve`, `/admin/approvals`, `/admin/approvals/{approvalId}`, `/admin/customers/{customerId}`, `/admin/customers/{customerId}/accounts`) | PASS |
| Không có endpoint Admin không được document | So sánh tập implemented với documented | PASS |
| Security scheme + kế thừa global | `sessionCookie`/`bearerAuth`, không override `security: []` | PASS |
| Write headers | `Idempotency-Key`, `X-CSRF-Token`, `X-Step-Up-Challenge`; `CorrelationId` mọi operation | PASS |
| Status code + Problem schema | Tập status từng operation; lỗi dùng `Problem`/`ValidationProblem`/`StepUpProblem`; 422 chỉ ở route limit | PASS |
| Request schema ↔ DTO/enum | `RoleChangeRequest` 4 variant, `LimitChangeRequest`, `RoleApprovalRequest`, `ApprovalDecision` | PASS |
| Public projection mask | `ApprovalSummary` không có `payload`/`expectedVersion`/`result`; `ApprovalDetail` có payload đã mask | PASS |
| `$ref` integrity | Mọi parameter/response/schema Admin resolve được; `Problem` đủ required fields | PASS |
| Document envelope + path template | `openapi: 3.1.0`; `operationId` duy nhất toàn document; mỗi biến path có đúng một parameter `in: path`, `required: true`, khớp `@PathVariable` nội bộ | PASS (test mới `documentEnvelopeAndPathParametersMatchEveryAdminOperation`) |

**Ghi nhận drift có kiểm soát:**

- Public template `07` dùng `{id}`; controller dùng `@PathVariable String approvalId` với mapping `{approvalId}`. URL thực tế không đổi (route match theo vị trí); lint so khớp số lượng/vị trí và kiểm tra controller tự nhất quán, không buộc đổi public contract.
- 422 `LIMIT_OUT_OF_POLICY` được document ở hai route limit; policy numeric vẫn chưa có giá trị chuẩn (`OI-API-05`) nên runtime chỉ trả 422 khi `app.limit.policy.*` được cấu hình. Không tạo default giả.
- `springdoc` mặc định **disabled** (`application.yml`); chỉ bật ở profile `local`. `/v3/api-docs`/`swagger-ui` `permitAll` không tạo bề mặt ở profile mặc định; xem P2 `REL-P2-05`.

## 4. Architecture review

**Phạm vi:** `admin/adapter/in`, `admin/application`, `admin/domain`, `admin/adapter/out`, migration `V6/V7/V9/V10/V11`, dependency tới `identity.api`, `limit.api`, `audit.api`, `customer.api`, `account.api`, Common.

| Kiểm tra | Kết quả | Ghi chú |
|---|---|---|
| Controller chỉ map HTTP ↔ command/response; không truy cập entity/provider | PASS | `RoleAdminController`, `LimitAdminController`, `ApprovalAdminController`; ArchUnit chặn import internals |
| Business logic ở application/domain | PASS | `ApprovalProposalService`, `ApprovalDecisionService`, `ApprovalRequest`, `ApprovalStatus` |
| Transaction boundary | PASS | `TransactionTemplate` trong use case; store `MANDATORY`; read-only cho query |
| Provider direction | PASS | Chỉ `*.api`; không import `application/domain/adapter` của provider |
| Audit integration | PASS | `AdminAuditPort`; C2 write cuối transaction; C3 `REQUIRES_NEW` |
| Locking / concurrency | PASS | `SELECT … FOR UPDATE` cho decision; idempotency insert-first; challenge single-use |
| DB consistency | PASS | FK RESTRICT, CHECK maker/checker, state invariants; C2 failure rollback |
| Circular dependency | PASS | `ModuleBoundaryArchTest` + Spring context tests |
| Config/environment | PASS | `app.admin.*`, `app.limit.policy.*`, `app.audit.*`; không hard-code secret |

**Findings và xử lý:**

1. `ApprovalListQuery` Javadoc còn nói data scope “BLOCKED/thuộc task tương lai” trong khi scope đã triển khai — **đã sửa** thành mô tả `ApprovalVisibility` server-side.
2. Xung đột version migration cross-module: Admin `V6` trùng Transfer candidate `V6`; fixture `V1_4` tạo `account` trước Account `V8` — **đã xử lý** bằng `V12__transfer.sql`, bỏ fixture synthetic, seed account thật ở `V8_5` (xem `DEC-ADM-016`). Không sửa migration production `V1`–`V11`.
3. Không phát hiện circular dependency, truy cập bảng chéo hoặc business logic trong controller.

## 5. Security review

**Endpoint × quyền (chain + service re-check):**

| Endpoint | Chain | Service re-check |
|---|---|---|
| `POST /api/v1/admin/roles` | `ROLE_MANAGE` | `AdminAuthorization.requireAuthority("ROLE_MANAGE")`; Maker ≠ target |
| `POST /api/v1/admin/roles/{id}/approve` | `CHECKER` + `ROLE_MANAGE` | `requireChecker` + `assertCanReview(ROLE_CHANGE)`; Maker–Checker |
| `POST /api/v1/admin/limits` | `LIMIT_MAKER`/`LIMIT_MANAGE` | `requireAnyAuthority`; customer/account/policy |
| `POST /api/v1/admin/limits/{id}/approve` | `CHECKER` + (`LIMIT_MAKER`/`LIMIT_MANAGE`) | `requireChecker` + `assertCanReview(LIMIT_CHANGE)`; Maker–Checker |
| `GET /api/v1/admin/approvals[/{id}]` | `ROLE_OPERATIONS`/`ROLE_ADMIN` | `requireApprovalReader` + `ApprovalVisibility` trong SQL |
| `GET /api/v1/admin/customers/{id}[/accounts]` | `CUSTOMER_READ`/`ACCOUNT_READ` | Provider re-check; CUSTOMER → 403 + C3 |

| Kiểm tra | Kết quả | Evidence |
|---|---|---|
| Authentication bắt buộc; session/CUSTOMER/disabled/expired/revoked fail-closed | PASS | `AdminSecurityMatrixPostgreSqlIntegrationTest`, `AdminAuthorizationTest` |
| RBAC server-side; forged header/body/query không bypass | PASS | cùng trên; `AdminHttpContractPostgreSqlIntegrationTest` |
| Object-level authorization/IDOR | PASS | `ApprovalReadHttpPostgreSqlIntegrationTest`, `ApprovalReadAuthorizationTest`, SQL scope + count |
| Mass assignment | PASS | DTO record cố định; Jackson `fail-on-unknown-properties: true`; không field actor/status/tenant |
| Input validation server-side | PASS | `AdminDtoValidationTest`, domain invariants |
| Audit privileged actions | PASS | C2/C3 trong `audit_log`; hash chain; replay không nhân đôi |
| Secret/PII không vào log/response/audit | PASS | `LogRedaction`, allowlist encoder, SEC-02/TST-01 matrix |
| Duplicate/concurrent request không bypass approval | PASS | idempotency unique + `FOR UPDATE`; concurrency tests |
| Invalid state transition bị từ chối | PASS | `ApprovalStatus` matrix + terminal tests |
| Failure fail-open/fail-closed | PASS | C2 fail-closed rollback; C3 best-effort; provider unavailable 503 |

**Finding `SEC-REL-01` (Critical? No — High, đã sửa):** Maker tự đề xuất role cho chính mình bị chặn `409 ROLE_CONFLICT` nhưng **không phát C3** vì validation nằm ngoài `try` và catch chỉ audit `IDEMPOTENCY_CONFLICT`. Đã chuyển validation vào guarded block và phát `SECURITY_ACCESS_DENIED` cho `ROLE_CONFLICT` (entity `APP_USER`). Test unit và HTTP matrix được siết lại; không nới lỏng assertion. Đây là privileged self-escalation attempt, cần audit theo `design/01` §5.3/§5.7.

**Không có finding P0 mở.** Các giới hạn còn lại là P1/P2 ở §9.

## 6. Changes delivered

| File | Thay đổi |
|---|---|
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/ApprovalProposalService.java` | C3 cho `ROLE_CONFLICT` khi Maker self-escalation; đưa validation aggregate vào guarded block |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/query/ApprovalListQuery.java` | Sửa Javadoc scope |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminAc028AcceptancePostgreSqlIntegrationTest.java` | **Mới** — acceptance AC-028 (4 test) |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminOpenApiContractTest.java` | Thêm lint document/path template |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/application/ApprovalProposalServiceTest.java` | Assert C3 self-escalation |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminSecurityMatrixPostgreSqlIntegrationTest.java` | Assert C3 self-propose |
| `digitalbanking-backend/src/main/resources/db/transfer-candidate/V12__transfer.sql` | Rename từ `V6` (nội dung SQL không đổi) |
| `digitalbanking-backend/src/test/resources/db/transfer-fixture/V8_5__transfer_account_seed.sql` | **Mới** — seed account thật sau V8 |
| `digitalbanking-backend/src/test/resources/db/transfer-fixture/V5_5__transfer_provider_seed.sql` | Bỏ seed account trước V8 |
| `digitalbanking-backend/src/test/resources/db/transfer-fixture/V1_4__synthetic_account.sql` | Xóa — Account V8 thay thế |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/transfer/persistence/TransferCandidateMigrationPostgresTest.java` | Cập nhật version assertion |
| `DECISIONS.md` | `DEC-ADM-016`; cập nhật `DEC-TRF-013` |
| `digitalbanking-docs/docs-backend/modules/admin/tasks/README.md` | Evidence + trạng thái `BE-ADM-REL-01` |
| `digitalbanking-docs/docs-backend/modules/admin/README.md`, `design.md` | Trạng thái gate F |
| `digitalbanking-docs/docs-backend/modules/transfer/{design.md,tasks/BE-TRF-02.md}` | Đường dẫn candidate V12 |

### 6.1. Source/test references

| Thành phần | Đường dẫn |
|---|---|
| Proposal service | [`ApprovalProposalService.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/ApprovalProposalService.java) |
| Decision service | [`ApprovalDecisionService.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/ApprovalDecisionService.java) |
| Authorization | [`AdminAuthorization.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/AdminAuthorization.java) |
| Approval read | [`ApprovalAdminController.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/in/ApprovalAdminController.java) |
| Domain/state machine | [`ApprovalRequest.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/domain/ApprovalRequest.java) |
| Durable audit adapter | [`DurableAdminAuditAdapter.java`](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/out/DurableAdminAuditAdapter.java) |
| AC-028 acceptance test | [`AdminAc028AcceptancePostgreSqlIntegrationTest.java`](../../../../digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminAc028AcceptancePostgreSqlIntegrationTest.java) |
| OpenAPI contract test | [`AdminOpenApiContractTest.java`](../../../../digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminOpenApiContractTest.java) |
| Security matrix test | [`AdminSecurityMatrixPostgreSqlIntegrationTest.java`](../../../../digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/adapter/in/AdminSecurityMatrixPostgreSqlIntegrationTest.java) |
| Fresh migration test | [`PostgreSqlFreshMigrationIntegrationTest.java`](../../../../digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/PostgreSqlFreshMigrationIntegrationTest.java) |
| Migrations | [`V6`](../../../../digitalbanking-backend/src/main/resources/db/migration/V6__create_approval_request_table.sql), [`V7`](../../../../digitalbanking-backend/src/main/resources/db/migration/V7__create_admin_idempotency_record_table.sql), [`V9`](../../../../digitalbanking-backend/src/main/resources/db/migration/V9__create_limit_config_table.sql), [`V10`](../../../../digitalbanking-backend/src/main/resources/db/migration/V10__create_audit_log_table.sql), [`V11`](../../../../digitalbanking-backend/src/main/resources/db/migration/V11__create_admin_approval_attempt_table.sql) |
| OpenAPI | [`digital-banking-v1.yaml`](../../../openapi/digital-banking-v1.yaml) |
| SRS AC-028 | [`03. SRS.md`](../../../docs-common/03.%20SRS.md) |
| API contract | [`07. API contracts.md`](../../../docs-common/07.%20API%20contracts.md) |
| Audit design | [`01. audit-traceability.md`](../../../docs-common/design/01.%20audit-traceability.md) |
| Quyết định | [`DECISIONS.md`](../../../../DECISIONS.md) |

## 7. Test execution

> Tất cả test dùng PostgreSQL 16 Testcontainers + Flyway thật; không H2, không mock transaction/repository/lock/audit. OTP transport là test double duy nhất (không có notification provider trong MVP).

| Lệnh | Kết quả |
|---|---|
| `./gradlew test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.architecture.*' --tests 'com.simulatedbank.banking.migration.*' --console=plain --max-workers=1` | **BUILD SUCCESSFUL** (3m15s), **48 classes / 364 tests / 0 failure / 0 error / 0 skip** |
| `./gradlew test --tests 'com.simulatedbank.banking.admin.adapter.in.AdminAc028AcceptancePostgreSqlIntegrationTest' --console=plain --max-workers=1` | **BUILD SUCCESSFUL**, 4 tests / 0 failure / 0 error / 0 skip |
| `./gradlew test --tests 'com.simulatedbank.banking.admin.adapter.in.AdminOpenApiContractTest' --console=plain --max-workers=1` | **BUILD SUCCESSFUL**, 6 tests / 0 failure |
| `./gradlew test --tests 'com.simulatedbank.banking.admin.adapter.in.AdminSecurityMatrixPostgreSqlIntegrationTest' --tests 'com.simulatedbank.banking.admin.application.ApprovalProposalServiceTest' --console=plain --max-workers=1` | **BUILD SUCCESSFUL**, 0 failure |
| `./gradlew test --tests 'com.simulatedbank.banking.transfer.persistence.TransferCandidateMigrationPostgresTest' --tests 'com.simulatedbank.banking.transfer.persistence.TransferJpaCandidatePostgresTest' --console=plain --max-workers=1` | **BUILD SUCCESSFUL**, 0 failure |
| `./gradlew test --tests 'com.simulatedbank.banking.migration.PostgreSqlFreshMigrationIntegrationTest' --tests 'com.simulatedbank.banking.migration.FlywayMigrationTest' --tests 'com.simulatedbank.banking.migration.ApprovalRequestV6MigrationPostgreSqlIntegrationTest' --tests 'com.simulatedbank.banking.migration.AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' --console=plain --max-workers=1` | **BUILD SUCCESSFUL**, 4 classes / 24 tests / 0 failure / 0 error / 0 skip |
| `./gradlew cleanTest build --console=plain --max-workers=1` | **BUILD SUCCESSFUL** (13m 24s), **149 classes / 1575 tests / 0 failure / 0 error / 0 skip** |

**Chi tiết bổ sung:**

- `AdminAc028AcceptancePostgreSqlIntegrationTest`: 4 test — role approve, limit approve, reject không áp dụng cả hai, Maker = Checker bị chặn + C3.
- `AdminOpenApiContractTest`: 6 test — thêm document envelope/path-template lint.
- `ApprovalProposalServiceTest`: 11 test (thêm assertion C3 cho self-escalation).
- `AdminSecurityMatrixPostgreSqlIntegrationTest`: 8 test (thêm assertion C3 cho self-propose).
- `TransferCandidateMigrationPostgresTest` 12 + `TransferJpaCandidatePostgresTest` 1: PASS sau khi tách fixture; đây là regression cross-module bắt buộc để full suite xanh.

## 8. Fresh migration

Lệnh: `./gradlew test --tests 'com.simulatedbank.banking.migration.PostgreSqlFreshMigrationIntegrationTest' --tests 'com.simulatedbank.banking.migration.FlywayMigrationTest' --tests 'com.simulatedbank.banking.migration.ApprovalRequestV6MigrationPostgreSqlIntegrationTest' --tests 'com.simulatedbank.banking.migration.AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' --console=plain --max-workers=1` → **BUILD SUCCESSFUL, 4 classes / 24 tests / 0 failure / 0 error / 0 skip**.

| Kiểm tra | Evidence | Kết quả |
|---|---|---|
| Empty database → Flyway `V1`–`V11` theo thứ tự | `PostgreSqlFreshMigrationIntegrationTest` (PostgreSQL 16 Testcontainers) | **PASS** |
| Checksum/validate và rerun idempotent | `FlywayMigrationTest` + `PostgreSqlFreshMigrationIntegrationTest` | **PASS** |
| Admin `approval_request`/`admin_idempotency_record`/`admin_approval_attempt` metadata | `ApprovalRequestV6MigrationPostgreSqlIntegrationTest`, `AdminIdempotencyV7MigrationPostgreSqlIntegrationTest` | **PASS** |
| FK tới Identity/Customer/Account | V6 FK `app_user`; V9 FK customer/account; fresh test | **PASS** |
| Startup trên schema đã migrate | `BackendApplicationContextTest` + full build | **PASS** |
| Không sửa migration đã áp dụng | `git diff` chỉ thêm V9–V11/V12 candidate; V1–V8 giữ nguyên | **PASS** |

## 9. P1/P2 blockers và findings

| ID | Severity | Mô tả | Impact | Owner | Follow-up |
|---|---|---|---|---|---|
| `ADM-OI-06` / `REL-P1-01` | P1 | Schema v1 không có tenant/branch; chưa có model phân công checker theo tenant | Không chứng minh được cross-tenant isolation; hiện chỉ single-tenant | DB/Architect + Security | Migration + scope contract + negative test đa tenant |
| `OI-API-05` / `REL-P1-02` | P1 | Numeric bounds của Limit policy chưa được API/BA chốt; provider chỉ enforce khi cấu hình | Có thể chấp nhận limit vượt ngưỡng nghiệp vụ nếu ops không cấu hình | API/BA + Limit owner | Chốt giá trị policy; thêm test 422 mặc định |
| `ADM-OI-11` / `REL-P2-01` | P2 | Không có cột `result`; GET detail sau quyết định không trả provider result (chỉ response/replay có) | Thiếu chi tiết kết quả khi tra cứu lại | Admin + DB | Forward-only migration hoặc projection từ audit/provider |
| `ADM-OI-03` / `REL-P2-02` | P2 | Không có partial unique PENDING theo target; hai proposal khác key cùng target được phép | Hai PENDING cùng target; apply race chỉ một thắng (`ROLE_CONFLICT`) | DB owner | Quyết định canonical + migration nếu duyệt |
| `REL-P2-03` | P2 | Audit retention/legal hold/export chưa thuộc Admin | Không đáp ứng đầy đủ AC-029/retention | Audit owner | Task Audit riêng |
| `REL-P2-04` | P2 | Alert rules Prometheus có trong repo nhưng chưa deploy | Không có runtime alert evidence | Ops | Deploy rules + alert test |
| `REL-P2-05` | P2 | `/v3/api-docs`/`swagger-ui` `permitAll` nếu springdoc bật ngoài `local` | Tăng bề mặt trinh sát API | Security/Ops | Gate theo profile hoặc authenticated |
| `REL-P2-06` | P2 | Transfer candidate migration/docs đã đổi version sang V12 | Không ảnh hưởng runtime production; cần Transfer owner xác nhận | Transfer/DB | Review DEC-ADM-016 + candidate evidence |

**Không có P0 mở** trong phạm vi Admin.

## 10. Remaining risks và dependencies

1. **Owner sign-off** chưa có — blocker duy nhất của gate.
2. **Tenant/data scope** (`ADM-OI-06`) là dependency kiến trúc; không được tuyên bố cross-tenant isolation.
3. **Limit policy numbers** (`OI-API-05`) là quyết định nghiệp vụ, không được tự đặt default.
4. **Audit retention/export** thuộc Audit owner; `audit_log` hiện chỉ đáp ứng C2/C3 Admin.
5. **Prometheus deployment** thuộc Ops; repo chỉ có rule file.
6. **Transfer candidate** là artifact review, không phải migration production; việc đổi version chỉ để tránh xung đột Flyway trong test.

## 11. Owner sign-off

| Vai trò | Người | Trạng thái | Bằng chứng |
|---|---|---|---|
| Admin owner | Đạt | **PENDING** | Chưa có approval record/comment trong repo |
| DB owner | — | **PENDING** | Cần review `V6`–`V11`, `DEC-ADM-016` |
| Security | — | **PENDING** | Cần review §5 và `SEC-REL-01` |
| QA | — | **PENDING** | Cần xác nhận §7/§8 |

Không ghi nhận sign-off thay cho owner khi chưa có bằng chứng phê duyệt thực tế.

## 12. Final release readiness decision

- **Technical P0 evidence:** PASS.
- **Overall release gate:** **BLOCKED** — chờ owner sign-off.
- **Điều kiện mở gate:** ký xác nhận của Admin owner + DB + Security + QA trên evidence này, sau đó đổi trạng thái `BE-ADM-REL-01` từ `[~]` sang `[x]`.
