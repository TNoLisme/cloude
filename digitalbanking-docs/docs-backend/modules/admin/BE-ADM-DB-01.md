# BE-ADM-DB-01 — Flyway migration `banking.approval_request`

> **Mã task:** `BE-ADM-DB-01` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái:** **DONE** — migration canonical đã tồn tại và được kiểm chứng lại trên PostgreSQL 16 fresh database; không cần migration mới.  
> **Nguồn ưu tiên:** `docs-common/04. Database design & analysis.md` §4.5.3 > `docs-common/design/05. Modules contract.md` §12.1/§12.1.1 > `docs-backend/modules/admin/BE-ADM-CON-01.md` §8 > `BE-ADM-CON-04.md` (DEC-ADM-004).

## 1. Kết luận

`banking.approval_request` đã được định nghĩa bởi migration versioned **`V6__create_approval_request_table.sql`** và migration này khớp canonical `04` §4.5.3. Task này **không tạo thêm version** vì:

- `V6` đã tạo đúng bảng, PK, FK, CHECK, default và 5 index canonical.
- `V7__create_admin_idempotency_record_table.sql` đã phụ thuộc `approval_request.approval_id`; thêm một migration `CREATE TABLE` lần nữa sẽ trùng bảng và phá fresh migration.
- Không có delta schema canonical nào cần forward migration.

Phần được bổ sung trong task này là **bằng chứng kiểm chứng độc lập**: một test PostgreSQL mới đối chiếu metadata thật (column/type/nullability/default/identity, PK, FK action, CHECK definition, index column order, partial predicate) và chạy positive/negative DML trên schema vừa migrate.

## 2. Migration và canonical schema

| Thuộc tính | Giá trị |
|---|---|
| Flyway version | `6` |
| Filename | `digitalbanking-backend/src/main/resources/db/migration/V6__create_approval_request_table.sql` |
| Schema | `banking` (tạo bởi `V1__init_schema.sql`) |
| Bảng | `banking.approval_request` |
| Canonical source | `04` §4.5.3 |
| Trạng thái lịch sử | `flyway_schema_history` ghi version `6`, `success = true`, không duplicate version |
| Forward-only | Không sửa `V1`–`V5`; `V6`/`V7` giữ nguyên nội dung đã áp dụng |

### 2.1. Cột

| Column | Type | Null | Default | Check |
|---|---|---|---|---|
| `approval_id` | `BIGINT` | NO | `GENERATED ALWAYS AS IDENTITY` | PK |
| `request_type` | `VARCHAR(32)` | NO | — | `LIMIT_CHANGE`, `ROLE_CHANGE`, `RETENTION_HOLD`, `RETENTION_POLICY_CHANGE` |
| `target_type` | `VARCHAR(32)` | NO | — | `CUSTOMER`, `ACCOUNT`, `APP_USER`, `DATA_SCOPE`, `RETENTION_POLICY` |
| `target_id` | `VARCHAR(64)` | NO | — | độ dài 1–64 |
| `payload` | `JSONB` | NO | — | — |
| `status` | `VARCHAR(16)` | NO | `'PENDING'` | `PENDING`, `APPROVED`, `REJECTED`, `APPLIED`, `CANCELLED` |
| `maker_id` | `BIGINT` | NO | — | FK `app_user(user_id)` |
| `checker_id` | `BIGINT` | YES | — | FK `app_user(user_id)` |
| `decision_note` | `VARCHAR(500)` | YES | — | — |
| `correlation_id` | `VARCHAR(64)` | NO | — | độ dài 1–64 |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | — |
| `decided_at` | `TIMESTAMPTZ` | YES | — | bắt buộc với `APPROVED`/`REJECTED`/`APPLIED` |
| `applied_at` | `TIMESTAMPTZ` | YES | — | bắt buộc với `APPLIED` |

`TIMESTAMPTZ` lưu instant theo UTC; PostgreSQL chuẩn hóa offset đầu vào. Test xác nhận `'2026-11-17T10:00:00+07:00'` đọc lại bằng `'2026-11-17T03:00:00Z'`.

### 2.2. Primary key và foreign keys

| Constraint | Định nghĩa | Referential action |
|---|---|---|
| `approval_request_pkey` | PK (`approval_id`) | — |
| `fk_approval_maker` | `maker_id → banking.app_user(user_id)` | `ON DELETE RESTRICT`, `ON UPDATE RESTRICT` |
| `fk_approval_checker` | `checker_id → banking.app_user(user_id)` | `ON DELETE RESTRICT`, `ON UPDATE RESTRICT` |

FK action theo chuẩn `04` §3.7: không dùng `CASCADE` cho dữ liệu tài chính/audit. Test xác nhận delete/update `app_user.user_id` đang được tham chiếu bị PostgreSQL từ chối với SQLSTATE `23503`.

### 2.3. Check constraints

| Constraint | Nội dung |
|---|---|
| `ck_approval_request_type` | `request_type IN ('LIMIT_CHANGE','ROLE_CHANGE','RETENTION_HOLD','RETENTION_POLICY_CHANGE')` |
| `ck_approval_target_type` | `target_type IN ('CUSTOMER','ACCOUNT','APP_USER','DATA_SCOPE','RETENTION_POLICY')` |
| `ck_approval_status` | `status IN ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED')` |
| `ck_approval_target_id` | `length(target_id) BETWEEN 1 AND 64` |
| `ck_approval_correlation_id` | `length(correlation_id) BETWEEN 1 AND 64` |
| `ck_approval_maker_checker` | `checker_id IS NULL OR checker_id <> maker_id` |
| `ck_approval_decided_fields` | `status IN ('APPROVED','REJECTED','APPLIED') ⇒ checker_id IS NOT NULL AND decided_at IS NOT NULL` |
| `ck_approval_applied_at` | `status = 'APPLIED' ⇒ applied_at IS NOT NULL` |

`ck_approval_correlation_id` là ràng buộc độ dài 1–64 đã được module contract ghi tại `BE-ADM-CON-01` (correlation là trace ID bắt buộc, không rỗng); giá trị tối đa vẫn đúng `VARCHAR(64)` của `04`. Không có enum/status bổ sung: `EXPIRED`, `CONSUMED` bị DB từ chối.

### 2.4. Indexes

| Index | Columns | Unique | Partial |
|---|---|---|---|
| `approval_request_pkey` | `approval_id` | Có (PK) | Không |
| `ix_approval_status_time` | `status, created_at` | Không | Không |
| `ix_approval_type_status` | `request_type, status` | Không | Không |
| `ix_approval_target` | `target_type, target_id` | Không | Không |
| `ix_approval_maker` | `maker_id` | Không | Không |
| `ix_approval_checker` | `checker_id` | Không | Không |

Không có partial index và không có unique constraint ngoài PK. `04` §4.5.3 và `design/05` §12.1.1 quy định rõ schema canonical **không** có partial unique PENDING. `ADM-OI-03` (unique PENDING trên logical target) là extension chưa được DB owner duyệt; nếu được duyệt phải triển khai bằng migration version mới (`V8`), không sửa `V6`.

## 3. Migration safety

- Version `6` nằm giữa chuỗi đã áp dụng `V1`–`V7`; không có version trùng.
- Không edit migration đã áp dụng; không `DROP TABLE`, `TRUNCATE`, reset hay xóa dữ liệu.
- Migration là DDL transactional của PostgreSQL: toàn bộ `V6` thành công hoặc rollback.
- `V7` FK `approval_id → approval_request.approval_id` chạy sau `V6`, đúng dependency order.
- Không thay đổi bảng/cột của Identity, Customer, Account hoặc Common.
- Entity `ApprovalRequestEntity`, repository và store hiện tại map đúng 13 cột canonical; không cần sửa Java production.

### 3.1. Quy trình chạy migration

```bash
cd digitalbanking-backend
./gradlew flywayMigrate \
  -Dflyway.url='jdbc:postgresql://localhost:5432/digital_banking' \
  -Dflyway.user='<user>' \
  -Dflyway.password='<password>' \
  -Dflyway.schemas=banking
```

Trong môi trường ứng dụng, Spring Boot tự chạy Flyway khi khởi động (`spring.flyway.enabled=true`). Kiểm tra:

```sql
SELECT version, description, success, checksum
FROM banking.flyway_schema_history
WHERE version IS NOT NULL
ORDER BY installed_rank;
```

Không chạy `flyway clean`, `DROP SCHEMA` hoặc reset database trên môi trường có dữ liệu.

## 4. Kiểm chứng

### 4.1. Test mới

`digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/ApprovalRequestV6MigrationPostgreSqlIntegrationTest.java`

PostgreSQL 16 Testcontainers, database rỗng; Flyway migrate `V1`–`V7`; 10 test:

| Test | Nội dung |
|---|---|
| `freshMigrationAppliesV6OnceAndRecordsSuccessfulHistory` | 7 migration chạy, V6 success/checksum, không duplicate version |
| `columnsTypesLengthsNullabilityAndDefaultsMatchCanonicalSchema` | đủ 13 cột đúng thứ tự/type/length/nullability/identity/default |
| `primaryKeyForeignKeysAndCanonicalChecksMatch` | PK, 2 FK + RESTRICT, 8 CHECK, tập enum chính xác |
| `canonicalIndexesExistInOrderWithoutPartialPredicates` | 5 index + PK, đúng column order, không unique/partial |
| `validInsertUsesCanonicalDefaultsAndTimestamptzSemantics` | `PENDING`/`now()`/JSONB/identity; offset `+07:00` quy về UTC |
| `canonicalTerminalStatesAndTimestampInvariantsAreAccepted` | `APPROVED`/`REJECTED`/`APPLIED`/`CANCELLED` hợp lệ |
| `missingRequiredColumnsAndInvalidEnumerationsAreRejected` | NOT NULL, `request_type`/`target_type`/`status` sai bị chặn |
| `invalidForeignKeysAndMakerCheckerRelationshipsAreRejected` | FK sai, maker=checker, thiếu checker/decided/applied bị chặn |
| `canonicalLengthBoundsAndCheckConstraintsAreEnforced` | target/note/correlation length, empty correlation bị chặn |
| `foreignKeysRestrictReferencedUserDeleteAndKeyUpdate` | delete/update user bị FK RESTRICT chặn |

Test cũ liên quan vẫn chạy:

- `FlywayMigrationTest.approvalMigrationDeclaresCanonicalAdminTable`
- `PostgreSqlFreshMigrationIntegrationTest.completeMigrationChainIsOrderedRepeatableAndPreservesUnrelatedSchema`
- `ApprovalRequestPostgreSqlIntegrationTest` (8 test persistence/constraint/JPA mapping)

### 4.2. Lệnh và kết quả

```bash
./gradlew test --tests 'com.simulatedbank.banking.migration.ApprovalRequestV6MigrationPostgreSqlIntegrationTest'
# BUILD SUCCESSFUL — 10 tests, 0 failure/error/skip

./gradlew test --tests 'com.simulatedbank.banking.migration.*' --tests 'com.simulatedbank.banking.admin.*'
# BUILD SUCCESSFUL — 18 classes / 129 tests / 0 failure / 0 error / 0 skip

./gradlew cleanTest build
# BUILD SUCCESSFUL (7m24s) — 83 classes / 1011 tests / 0 failure / 0 error / 0 skip
```

Fresh PostgreSQL evidence: container mới, schema `banking` chưa tồn tại, Flyway chạy đủ `V1`–`V7`, rerun không thực thi migration mới và schema fingerprint không đổi (test fresh migration hiện hữu).

### 4.3. Existing database compatibility

Không có database staging/production được cấp trong môi trường làm việc. Không chạy migration trên database có dữ liệu thật và không tuyên bố đã xác minh compatibility với production. Forward path được bảo đảm bởi Flyway history/validate; `V6` là `CREATE TABLE` mới, không sửa object hiện hữu.

## 5. Khác biệt so với canonical

Không có khác biệt cấu trúc. Ghi chú:

- `ck_approval_correlation_id` siết thêm điều kiện không rỗng, nằm trong giới hạn `VARCHAR(64)` và đã có trong module contract (`BE-ADM-CON-01` §8). Không thay đổi type/length canonical.
- Không có `expires_at`, `step_up_challenge_id`, `result`, `version`, `idempotency_key`, `maker_reason`, `checker_reason` — đúng `04` §4.5.3 và `design/05` §12.1.1.
- `APPROVED` tồn tại trong CHECK nhưng MVP chỉ persist `PENDING → APPLIED/REJECTED`; đây là semantics ứng dụng, không phải khác biệt schema.

## 6. Limitations và follow-up

| Mục | Trạng thái | Ghi chú |
|---|---|---|
| `ADM-OI-03` — partial unique PENDING | **OPEN, ngoài canonical** | Cần DB owner duyệt semantics; nếu duyệt thì thêm `V8` + test race. Không phải acceptance của `BE-ADM-DB-01` theo `design/05` §12.1.1. |
| DB owner/Architect sign-off | **Chưa có** | Evidence kỹ thuật đã đủ; sign-off là gate quy trình, không phải lỗi schema. |
| List/detail approval queries | **DONE (persistence) ở `BE-ADM-DB-02`** | Entity/repository/projection, filter/page/sort whitelist, read-only query boundary và lock tests đã có. REST list/detail + data scope vẫn BLOCKED ở `BE-ADM-API-03`/`ADM-OI-06`; `ApprovalDetail.result` chưa persist. |
| Multi-tenant | **Chưa hỗ trợ** | Schema v1 single-tenant theo `04`; nếu multi-tenant phải thêm tenant vào key/index bằng migration mới. |

## 7. Acceptance criteria

- [x] Canonical `banking.approval_request` schema implemented (`V6`).
- [x] Flyway versioning correct (`V6`, sau `V1`–`V5`, trước `V7`; không duplicate).
- [x] Fresh PostgreSQL migration succeeds.
- [x] PK constraints match canonical.
- [x] FK constraints match canonical (`app_user`, RESTRICT).
- [x] CHECK constraints match canonical (8 check, đúng enum/timestamp invariant).
- [x] Status and timestamp columns match canonical.
- [x] Required indexes exist; partial index verification confirms none required by canonical.
- [x] Relevant automated tests pass.
- [x] No previously applied migration modified.
- [x] Existing data/objects and unrelated modules preserved.
- [x] Documentation and task checklist updated.

## 8. Trạng thái cuối

`BE-ADM-DB-01` — **DONE**. Migration canonical: `V6__create_approval_request_table.sql`; verification: `ApprovalRequestV6MigrationPostgreSqlIntegrationTest` (10 test) + migration/Admin regression + full build. Blocker duy nhất còn mở là `ADM-OI-03`, một schema extension ngoài canonical chờ DB owner, không chặn task này.
