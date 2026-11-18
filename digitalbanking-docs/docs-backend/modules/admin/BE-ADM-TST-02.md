# BE-ADM-TST-02 — PostgreSQL migration/constraint/transaction integration tests

> **Mã task:** `BE-ADM-TST-02` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — Testcontainers PostgreSQL 16 + Flyway thật; schema metadata, FK/CHECK/unique, duplicate PENDING, C2 rollback và transaction boundary đều có assertion trên dữ liệu đã persist. Không dùng H2, không mock transaction/repository.
> **Nguồn:** `docs-common/09. Test plan.md`, `04. Database design & analysis.md` §4.5.3/§4.5.4, `07. API contracts.md` §5.6/§8.5/§8.8, `design/05. Modules contract.md` §12.1, `BE-ADM-DB-01/02/03`, `BE-ADM-CON-02/03`, `BE-ADM-APP-02/03/04`.

## 1. Phạm vi

Task này chứng minh các hợp đồng **PostgreSQL thật** của Admin sau khi đã có runtime:

- Fresh database chạy toàn bộ Flyway `V1`–`V8`; V6/V7 được ghi đúng một lần trong `flyway_schema_history`.
- Cột/type/length/nullability/default, PK, FK `RESTRICT`, CHECK, index và unique index khớp `04` §4.5.3/§4.5.4.
- DML hợp lệ được persist; DML vi phạm bị PostgreSQL từ chối và **không** để lại partial state.
- Duplicate PENDING được xử lý đúng canonical: schema **không** có partial unique PENDING (`ADM-OI-03` chưa được duyệt), uniqueness thật là `uq_admin_idempotency_scope`.
- C2 audit là write cuối transaction: audit fail → approval/claim/challenge rollback; retry cùng key/challenge commit sạch.
- Sau mỗi scenario, assertion đọc `banking.approval_request`, `banking.admin_idempotency_record`, `banking.auth_challenge`, `banking.user_role` và audit fact thực tế, không chỉ kiểm response.

Không thay đổi schema, không nới constraint, không thêm H2, không sửa business rule.

## 2. Test infrastructure

| Thành phần | Sử dụng |
|---|---|
| Database | `PostgreSqlIntegrationTestSupport.newContainer(...)` → `postgres:16-alpine`, database/schema riêng cho từng test class |
| Migration | Flyway thật từ `classpath:db/migration`, `schemas=banking`, `validateOnMigrate=true`, `outOfOrder=false` |
| Spring | `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`, `@DynamicPropertySource` trỏ datasource vào container |
| Transaction | `TransactionTemplate` của production `PlatformTransactionManager`; raw `Connection` (`autoCommit=false`) cho test rollback |
| OTP | `OtpCodeGenerator`/`OtpGateway` test double duy nhất (provider ngoài); Identity/Session/Challenge/Flyway là runtime thật |
| Isolation | `@BeforeEach` xóa `admin_idempotency_record` → `approval_request` → `auth_challenge` → RBAC/user của prefix test; mỗi test tạo user riêng |

`spring.datasource.hikari.auto-commit=false` là cấu hình production (mọi write phải nằm trong transaction). Vì vậy test ghi dữ liệu qua `TransactionTemplate` hoặc `Connection` có `commit/rollback` tường minh — không dựa vào autocommit.

## 3. Test class và phạm vi

### 3.1. `AdminPersistencePostgreSqlIntegrationTest` (mới, 6 test)

Đường dẫn: `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminPersistencePostgreSqlIntegrationTest.java`.

| Test | Nội dung | Assertion chính |
|---|---|---|
| `canonicalSchemaAllowsTwoPendingTargetsButTheIdempotencyScopeIsUnique` | Hai PENDING cùng `(request_type,target_type,target_id)` được persist; claim trùng `(actor, operation, scope, key)` bị `23505` | 2 PENDING; record gốc giữ `RECEIVED`/hash gốc; `uq_admin_idempotency_scope`; actor khác vẫn insert được |
| `failedForeignKeyInsideATransactionRollsBackEveryEarlierWrite` | Trong một transaction: insert approval hợp lệ rồi insert maker không tồn tại | `23503`/`fk_approval_maker`; rollback → 0 row; transaction sạch commit lại đúng 1 row |
| `foreignKeyRestrictLeavesTheCommittedApprovalIntact` | Xóa `app_user` đang được approval tham chiếu | `23503`/`fk_approval_maker`; approval vẫn `PENDING`, user vẫn tồn tại sau rollback |
| `checkViolationsNeverMutateTheExistingValidRow` | Update sang status ngoài enum, `APPLIED` thiếu `applied_at`, `checker_id = maker_id` | `23514` đúng constraint; row vẫn `PENDING`, `checker_id = NULL` |
| `duplicateCompletedClaimKeepsTheOriginalReplayResponse` | Claim `COMPLETED` rồi insert trùng scope với hash khác | `23505`/`uq_admin_idempotency_scope`; `request_hash`, `response_status=200`, `response_body.status=APPLIED` không đổi |
| `c2AuditFailureRollsBackApprovalClaimAndChallengeConsumption` | `AdminAuditPort.recordInTransaction` ném lỗi sau khi claim/consume/insert approval | 0 approval, 0 claim; challenge vẫn `VERIFIED`, `consumed_at NULL`; không role/version; retry cùng key/challenge → 201, 1 approval, claim `COMPLETED`, challenge consumed |

### 3.2. Test migration đã có (được task này xác nhận lại)

| Lớp | Tests | Phạm vi |
|---|---:|---|
| `ApprovalRequestV6MigrationPostgreSqlIntegrationTest` | 10 | Fresh `V1`–`V8`; 13 cột/type/length/default; PK; 2 FK RESTRICT; 8 CHECK; 5 index không unique/partial; DML dương/âm; timezone |
| `AdminIdempotencyV7MigrationPostgreSqlIntegrationTest` | 8 | 13 cột/type/length/default; PK; 2 FK RESTRICT; 10 CHECK; unique `(actor_id, operation, scope_key, idempotency_key)`; index purge/status/approval; DML `23505/23514/23503/22001` |
| `PostgreSqlFreshMigrationIntegrationTest` | 1 | Database rỗng chạy toàn bộ migration, kiểm schema/tables thiết yếu |
| `FlywayMigrationTest` | 5 | Nội dung DDL canonical (không thay thế metadata test) |

Không tạo migration mới: V6/V7 đã khớp canonical và không có delta được duyệt.

## 4. Map acceptance criteria

| Acceptance | Bằng chứng |
|---|---|
| Fresh PostgreSQL migrations pass | `PostgreSqlFreshMigrationIntegrationTest`; `ApprovalRequestV6...`; `AdminIdempotencyV7...` |
| FK constraints tested | `failedForeignKeyInsideATransactionRollsBackEveryEarlierWrite`; `foreignKeyRestrictLeavesTheCommittedApprovalIntact`; V6/V7 metadata + DML tests |
| CHECK constraints tested | `checkViolationsNeverMutateTheExistingValidRow`; `c2AuditFailure...`; V6/V7 `23514` tests |
| Unique constraints/indexes tested | `canonicalSchemaAllowsTwoPendingTargetsButTheIdempotencyScopeIsUnique`; `duplicateCompletedClaimKeepsTheOriginalReplayResponse`; V7 unique-scope/index tests |
| Duplicate PENDING behavior tested | `canonicalSchemaAllowsTwoPendingTargets...` — canonical cho phép nhiều PENDING cùng target; cùng idempotency scope bị chặn; `ADM-OI-03` vẫn OPEN |
| C2 rollback tested | `c2AuditFailureRollsBackApprovalClaimAndChallengeConsumption`; regression `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#c2AuditFailureRollsBackApprovalProviderAndChallengeConsumption` |
| No partial business state | Cả 6 test mới + `ApprovalRequestPostgreSqlIntegrationTest#concurrentCheckersSerializeOnRowLockAndOnlyOneApplies` |
| Audit facts verified against persisted state | C2 rollback test + `ApprovalProposalPostgreSqlIntegrationTest#retryWithVerifiedChallengeCreatesOneApprovalAndReplays`, `ApprovalDecisionIdempotency...` (audit C2/C3 count sau commit/rollback) |
| Testcontainers PostgreSQL, no H2 | Toàn bộ class dùng `PostgreSqlIntegrationTestSupport`; không có dependency H2 trong `build.gradle.kts` |

## 5. Quyết định duplicate PENDING

`04` §4.5.3 quy định `approval_request` **không** có unique/partial index trên `(request_type,target_type,target_id,status)`.
Vì vậy:

- Hai maker request độc lập cho cùng target đều được persist `PENDING`; đây là hành vi canonical, không phải bug.
- Thứ tự thực thi được bảo vệ ở tầng provider: `ROLE_CHANGE.expectedVersion` do Identity lock/refresh và kiểm tra; request thứ hai nhận `ROLE_CONFLICT`, giữ `PENDING`, không revoke/apply lần hai (test trong `BE-ADM-TST-03`).
- `ADM-OI-03` (partial unique PENDING) vẫn **OPEN**; nếu DB owner duyệt phải thêm migration mới, không sửa `V6`, và bổ sung test race tương ứng.

## 6. Kết quả thực thi

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.AdminPersistencePostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.ApprovalRequestV6MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.PostgreSqlFreshMigrationIntegrationTest' \
  --console=plain --no-daemon --max-workers=1
```

**BUILD SUCCESSFUL** — PostgreSQL 16 Testcontainers, Flyway `V1`–`V8`, Identity/Session/Challenge runtime thật.

| Lớp | Tests | Kết quả |
|---|---:|---|
| `AdminPersistencePostgreSqlIntegrationTest` | 6 | PASS |
| `ApprovalRequestV6MigrationPostgreSqlIntegrationTest` | 10 | PASS |
| `AdminIdempotencyV7MigrationPostgreSqlIntegrationTest` | 8 | PASS |
| `PostgreSqlFreshMigrationIntegrationTest` | 1 | PASS |

Broad regression (Admin + migration):

```bash
./gradlew test --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --console=plain --no-daemon --max-workers=1
```

**BUILD SUCCESSFUL** — **37 classes / 291 tests / 0 failure / 0 error / 0 skip**.

Full backend regression:

```bash
./gradlew test --console=plain --no-daemon --max-workers=1
```

**BUILD SUCCESSFUL** (10m56s) — **116 classes / 1325 tests / 0 failure / 0 error / 0 skip**. Sau chỉnh sửa cuối của test (bổ sung `@Timeout(60)` và assertion audit fact), chạy lại 2 class mới: **PASS** (10 test).

## 7. Defect và fix

- **Không phát hiện production defect** trong transaction/constraint của Admin; các test mới pass với code hiện tại.
- Test-infra defect đã sửa trong quá trình viết test: datasource production đặt `auto-commit=false`, nên các fixture insert/update phải chạy trong `TransactionTemplate`/`Connection` có commit tường minh; nếu không, assertion sẽ đọc 0 row và constraint test không chạm dữ liệu. Đã sửa bằng helper transaction-bound, không đổi production code.
- Test FK ban đầu bị chặn bởi `fk_user_role_user` trước `fk_approval_maker`; test đã xóa reference RBAC không liên quan để cô lập đúng Admin FK.

## 8. Giới hạn / blocker

- `ADM-OI-07`: chưa có durable `AuditModuleApi`/`audit_log`/hash chain; C2 fact được xác minh qua `AdminAuditPort` + structured log, chưa phải bảng audit bền vững.
- `ADM-OI-03`: partial unique PENDING chưa được duyệt; test khẳng định hành vi canonical hiện tại (nhiều PENDING cùng target) và ghi rõ đây là giới hạn schema, không giả lập constraint.
- `ADM-OI-05`: LIMIT approve không có provider; chỉ test fail-closed/rollback của nhánh limit reject.
