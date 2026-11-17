# BE-ADM-DB-03 — Admin idempotency persistence và concurrency control

> **Mã task:** `BE-ADM-DB-03` (P0/P1)
> **Ngày:** 17/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không push)
> **Trạng thái:** **DONE** — persistence `banking.admin_idempotency_record` (V7), unique race, conflict, terminal replay,
> PENDING retention và PostgreSQL concurrency tests đã có source + test + evidence. Tenant isolation **BLOCKED (schema)**
> vì `04` §4.5.3/§4.5.4 không có cột tenant; actor + resource là ranh giới hiện tại.
> **Nguồn ưu tiên:** `docs-common/04. Database design & analysis.md` §4.5.4/§9.6 >
> `docs-common/design/02. consistency-concurrency.md` §6/§9.6 > `docs-common/design/05. Modules contract.md` §12.1.3 >
> `docs-common/07. API contracts.md` §5/§5.6/§8.5/§8.8 > `BE-ADM-CON-03.md`, `BE-ADM-DB-01.md`, `DEC-ADM-003`.

## 1. Kết luận

Task này **không tạo bảng mới và không đổi contract**: `BE-ADM-CON-03`/`DEC-ADM-003` đã chốt Admin sở hữu
`banking.admin_idempotency_record` (không tái dùng `transfer.idempotency_record`) và đã triển khai V7 +
`JdbcAdminIdempotencyStore` + `AdminIdempotencyService`. Phần `BE-ADM-DB-03` đóng góp là **kiểm chứng độc lập và
đóng các lỗ hổng evidence** của chính persistence/concurrency:

1. **Migration metadata test mới** (`AdminIdempotencyV7MigrationPostgreSqlIntegrationTest`, 8 test) khóa schema V7
   bằng metadata thật trên PostgreSQL 16 fresh database: 13 cột/type/length/nullability/identity/default, PK,
   2 FK `RESTRICT`, 10 CHECK, unique index scope đúng thứ tự cột, 3 index phụ không unique/không partial, cùng
   positive/negative DML (unique 23505, check 23514, FK 23503, length 22001).
2. **Ba test concurrency/rollback mới** trong `AdminIdempotencyPostgreSqlIntegrationTest`:
   winner rollback → loser đang block trên unique index nhận được ownership; failure tại boundary
   response-persistence rollback cả approval + claim rồi retry cùng key chạy sạch; record `RECEIVED` hết hạn
   **không** bị purge và vẫn chặn execute (`409 IDEMPOTENCY_IN_PROGRESS`).
3. Báo cáo này tổng hợp ma trận Test A–G, transaction boundary, retention/recovery và các giới hạn còn lại.

Không sửa Java production: hành vi đã đúng contract, và mọi thay đổi hành vi sẽ cần contract/DB owner duyệt.

## 2. Vì sao Admin có bảng riêng

`DEC-ADM-003` (nguồn `04` §4.5.4, `design/05` §12.1.3) quyết định Admin sở hữu bảng riêng vì unique scope khác
transfer:

| Module | Unique scope |
|---|---|
| Transfer | `idempotency_key` theo transfer contract |
| Admin | `(actor_id, operation, scope_key, idempotency_key)` |

Dùng chung bảng transfer sẽ buộc phải nới unique constraint của transfer hoặc thêm cột Admin vào bảng của module
khác — cả hai đều phá canonical và tạo cross-module dependency. Vì vậy `V7` là migration đúng, không phải bảng mới
phát sinh trong task này.

## 3. Persistence model

Migration: `digitalbanking-backend/src/main/resources/db/migration/V7__create_admin_idempotency_record_table.sql`
(schema `banking`, forward-only, không sửa `V1`–`V6`).

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `record_id` | `BIGINT` | NO | `GENERATED ALWAYS AS IDENTITY` PK |
| `idempotency_key` | `VARCHAR(255)` | NO | `length 1..255` |
| `actor_id` | `BIGINT` | NO | FK `app_user(user_id)` `RESTRICT/RESTRICT` |
| `operation` | `VARCHAR(32)` | NO | `CREATE_LIMIT_PROPOSAL`, `CREATE_ROLE_PROPOSAL`, `DECIDE_APPROVAL` |
| `scope_key` | `VARCHAR(64)` | NO | default `''`; `approvalId` cho decision |
| `request_hash` | `CHAR(64)` | NO | `^[0-9a-f]{64}$` (SHA-256 lowercase hex) |
| `status` | `VARCHAR(16)` | NO | default `RECEIVED`; `RECEIVED/COMPLETED/FAILED` |
| `response_status` | `SMALLINT` | YES | `100..599` khi có |
| `response_body` | `JSONB` | YES | response masked đã round-trip |
| `approval_id` | `BIGINT` | YES | FK `approval_request(approval_id)` `RESTRICT/RESTRICT`; bắt buộc với `DECIDE_APPROVAL` |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` |
| `completed_at` | `TIMESTAMPTZ` | YES | bắt buộc với terminal |
| `expires_at` | `TIMESTAMPTZ` | NO | `> created_at` |

Ràng buộc trạng thái (DB là trọng tài, không chỉ application):

| Constraint | Nội dung |
|---|---|
| `ck_admin_idempotency_received_fields` | `RECEIVED` ⇒ không có `completed_at`/`response_status`/`response_body` |
| `ck_admin_idempotency_terminal_fields` | `COMPLETED`/`FAILED` ⇒ có đủ `completed_at` + `response_status` + `response_body` |
| `ck_admin_idempotency_decision_scope` | `DECIDE_APPROVAL` ⇒ `approval_id IS NOT NULL` |
| `ck_admin_idempotency_expiry` | `expires_at > created_at` |
| `ck_admin_idempotency_operation/status/response_status/key_len/scope_len/request_hash` | enum, range, length, hash format |

Index:

| Index | Cột | Unique | Partial |
|---|---|---|---|
| `admin_idempotency_record_pkey` | `record_id` | Có (PK) | Không |
| `uq_admin_idempotency_scope` | `(actor_id, operation, scope_key, idempotency_key)` | **Có** | Không |
| `ix_admin_idempotency_expires` | `expires_at` | Không | Không |
| `ix_admin_idempotency_status` | `status` | Không | Không |
| `ix_admin_idempotency_approval` | `approval_id` | Không | Không |

Không lưu request body, credential, access token hay PII: chỉ `request_hash` và response **đã mask**
(`ApprovalPayloadView` bỏ `expectedVersion`; actor chỉ là opaque ID + staff username).

## 4. Idempotency scope

```text
(actor_id, operation, scope_key, idempotency_key)
```

| Thành phần | Proposal | Decision |
|---|---|---|
| `actor_id` | Maker từ session | Checker từ session |
| `operation` | `CREATE_ROLE_PROPOSAL` / `CREATE_LIMIT_PROPOSAL` | `DECIDE_APPROVAL` |
| `scope_key` | `''` | `approvalId` decimal |
| `idempotency_key` | Header `Idempotency-Key`, `^[A-Za-z0-9._:~-]{1,255}$` | Như proposal |

Hệ quả (đều có test PostgreSQL):

- Actor khác cùng key **không** replay response của actor đầu (`sameKeyIsIsolatedByActorOperationAndResource`,
  `anotherCheckerCannotReplayTheStoredResponse`).
- Cùng key trên hai approval khác nhau là hai scope (`sameKeyIsScopedByApprovalResource`,
  `uniqueScopeIsActorOperationResourceAndKey`).
- Proposal và decision cùng key là hai operation độc lập; canonical hash có domain separator.

**Tenant:** schema v1 single-tenant (`04` không có cột tenant). Actor + resource là ranh giới hiện tại. Khi mở
multi-tenant phải thêm tenant vào scope **và** unique index bằng migration mới trước khi expose endpoint — xem §10.

## 5. Atomic acquisition (unique race)

Adapter: `admin/adapter/out/JdbcAdminIdempotencyStore.java`; state machine: `AdminIdempotencyService`.

```sql
INSERT INTO banking.admin_idempotency_record
    (idempotency_key, actor_id, operation, scope_key, request_hash, status, approval_id, created_at, expires_at)
VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?)
ON CONFLICT (actor_id, operation, scope_key, idempotency_key) DO NOTHING
RETURNING ...
```

Đặc tính:

- **Insert-first**, không select-then-insert. Unique index là trọng tài duy nhất; `ON CONFLICT DO NOTHING` không
  abort transaction như khi catch `DataIntegrityViolationException`.
- `claim` và `complete` là `@Transactional(propagation = MANDATORY)`: chúng **join** transaction business
  (proposal/decision + C2 audit), nên approval row, terminal idempotency state và response commit/rollback cùng nhau.
- Loser (insert trả 0 row) đọc lại record trong cùng transaction: terminal cùng hash → replay; khác hash →
  `409 IDEMPOTENCY_CONFLICT`; `RECEIVED` → `409 IDEMPOTENCY_IN_PROGRESS`. Không chạy lại business logic.
- Winner rollback → row biến mất; loser đang block trên unique index được PostgreSQL đánh thức và insert thành công.
  Đây là recovery path của acquisition; test `winnerRollbackReleasesTheKeyToTheBlockedConcurrentRequest`.
- Không có application-memory lock: hai JVM/instance cạnh tranh vẫn đúng.

`claim` đọc lại tối đa 2 lần để xử lý race purge/commit mà không bao giờ re-execute; hết 2 lần trả
`503 SERVICE_UNAVAILABLE` thay vì treo ownership mơ hồ.

## 6. Conflict detection

- Canonical hash: domain separator `ADM-IDEMPOTENCY-V1` + RFC 8785 JCS + `SHA-256` lowercase hex
  (`AdminRequestHasher`).
- Role proposal hash gồm `operation, actorId, requestType, targetType, targetId, changeOperation, targetUserId,
  roleCode, privilegeCode, reason`; **loại trừ** `expectedVersion` server-capture, header, correlation/request id,
  timestamp, challenge ref. Nhờ vậy retry cùng client payload vẫn replay dù role version provider đã đổi.
- Decision hash gồm `operation, actorId, approvalId, decision, decisionNote`.
- Normalize: ID/money string, money scale 2, reason/note strip (null ≡ `""`), currency uppercase, Instant ISO-8601 UTC.
- Cùng key + hash khác → `409 IDEMPOTENCY_CONFLICT` + C3 `SECURITY_ACCESS_DENIED`; record gốc không bị sửa; không
  side effect.
- Không trả chi tiết DB/constraint ra client; `ProblemWriter` map error code đã có trong catalog.

## 7. Terminal response replay

- `complete` ghi `status='COMPLETED'`, `response_status`, `response_body` (masked), `approval_id`,
  `completed_at`, `expires_at = GREATEST(expires_at, completed_at + TTL)` **trong cùng transaction** với business
  row và C2 audit. Nếu UPDATE không trúng row `RECEIVED` (mất ownership) → `IllegalStateException` → rollback toàn bộ.
- Response được JSON round-trip một lần trước khi trả và lưu, nên first response và replay dùng cùng kiểu giá trị
  (`0` không lúc `0L` lúc `0`).
- Replay trả đúng `response_status` (201 proposal, 200 decision) + đúng body đã lưu + `Idempotent-Replayed: true`;
  step-up/provider/audit **không** chạy lại.
- Không fabricate success: chỉ row `COMPLETED` mới replay; business fail ⇒ rollback ⇒ không có terminal row.

**External side effects:** provider Identity (`IdentityRoleCommand`) chạy trong cùng transaction nên rollback đồng bộ
với claim. Provider nằm ngoài PostgreSQL (HTTP/3rd-party) không thể tham gia transaction — với Admin hiện tại chỉ có
Identity provider in-process/JDBC; Limit provider chưa có runtime nên endpoint fail-closed (`ADM-OI-05`). Nếu sau này
thêm provider ngoài DB, cần outbox/saga và **không** được giả định atomicity của task này.

## 8. PENDING retention và recovery

Mô hình đã chốt: claim **nằm trong** transaction business, nên crash/failure/timeout trước commit **rollback claim**;
không tồn tại `RECEIVED` bền vững từ flow một pha. Vì vậy:

| Tình huống | Hành vi |
|---|---|
| Business/provider/step-up/audit fail | Rollback cả claim + approval; retry cùng key chạy sạch, không có record terminal giả |
| Client timeout sau commit | Retry cùng key replay response đã lưu |
| Client disconnect/timeout trước commit | Transaction rollback; key được giải phóng |
| Concurrent loser | Block → replay/conflict; không execute, không tạo row thứ hai |
| Record `RECEIVED` tồn tại (flow hai pha tương lai) | `409 IDEMPOTENCY_IN_PROGRESS`; **không purge**, không đổi status |
| Purge job | Chỉ `COMPLETED`/`FAILED` có `expires_at < now()`, `FOR UPDATE SKIP LOCKED`, batch 500, tối đa 20 batch/lần |
| Record hết hạn nhưng chưa purge | Vẫn replay/conflict; key chỉ tái sử dụng **sau** purge |

Test: `expiredInProgressRecordIsRetainedAndStillBlocksExecution`, `purgeDeletesOnlyTerminalExpiredRecords`,
`failedChallengeConsumptionRollsBackClaimAndRetrySucceeds`,
`providerFailureRollsBackClaimAndTheSameKeyRetries`,
`responsePersistenceFailureRollsBackApprovalAndClaimThenSameKeyRetries`.

**Giới hạn đã biết:** record `RECEIVED` không có transition recovery được contract cho phép, nên nếu một flow hai pha
tương lai để lại `RECEIVED` thì key đó bị giữ vĩnh viễn (`409`). Task này **không** tự thêm `EXPIRED`/timeout-recovery
vì `BE-ADM-CON-03` §8 và `DEC-ADM-003` quy định không đổi trạng thái `RECEIVED`. Đây là open issue cần contract owner
quyết định trước khi mở flow hai pha (xem §10).

## 9. Transaction boundaries

| Operation | Boundary | Ghi chú |
|---|---|---|
| `precheck` | `@Transactional(readOnly = true)` | Đọc record đã commit trước khi cấp challenge |
| `claim` / `complete` | `MANDATORY` | Join transaction của `ApprovalProposalService`/`ApprovalDecisionService` |
| `ApprovalProposalService.proposeRole` | `TransactionTemplate` | claim → consume challenge → insert approval → complete → C2 audit cuối |
| `ApprovalDecisionService.decide` | `TransactionTemplate` | claim → lock approval `FOR UPDATE` → re-check → consume → provider → persist → complete → C2 audit |
| `purgeExpired` | `@Transactional` | `FOR UPDATE SKIP LOCKED`, terminal-only |
| Controller/replay path | Không mở transaction ghi | Replay chỉ đọc record |

Không dùng `REQUIRES_NEW` cho claim/complete: sẽ phá atomicity giữa business row và idempotency state.

## 10. PostgreSQL concurrency tests

Hạ tầng: PostgreSQL 16 Testcontainers + Flyway `V1`–`V8` thật; không H2, không mock unique/lock/transaction.

| Task scenario | Test thật | Kết quả |
|---|---|---|
| **A** — concurrent identical | `ApprovalProposalPostgreSqlIntegrationTest.concurrentSameKeyCreatesExactlyOneApproval`; `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest.concurrentSameKeyHasOneBusinessEffect`; `AdminIdempotencyPostgreSqlIntegrationTest.concurrentClaimsHaveExactlyOneWinner` | PASS — một owner, một approval/apply, loser replay |
| **A/E** — winner rollback | `AdminIdempotencyPostgreSqlIntegrationTest.winnerRollbackReleasesTheKeyToTheBlockedConcurrentRequest` | PASS — loser đang block nhận ownership, đúng 1 row `RECEIVED` |
| **B** — conflicting payload | `sameKeyDifferentPayloadIsAConflict`; `approveThenReplayConflictAndOneBusinessEffect`; `claimCompleteThenReplayConflictAndInProgress`; `uniqueScopeIsActorOperationResourceAndKey` | PASS — `409 IDEMPOTENCY_CONFLICT`, record gốc nguyên vẹn, không execute lần hai |
| **C** — terminal replay | `retryWithVerifiedChallengeCreatesOneApprovalAndReplays`; `rejectReplayAndConflictWithoutProviderMutation`; `RoleAdminControllerTest` replay/`Idempotent-Replayed` | PASS — status/body gốc, không provider call lại |
| **D** — PENDING retention | `expiredInProgressRecordIsRetainedAndStillBlocksExecution`; `purgeDeletesOnlyTerminalExpiredRecords`; `durableInProgressRecordReturns409WithoutExecuting` | PASS — `RECEIVED` hết hạn vẫn tồn tại, vẫn `409`, purge không đụng |
| **E** — rollback boundaries | `responsePersistenceFailureRollsBackApprovalAndClaimThenSameKeyRetries`; `failedChallengeConsumptionRollsBackClaimAndRetrySucceeds`; `providerFailureRollsBackClaimAndTheSameKeyRetries` | PASS — không replay terminal chưa commit, không duplicate execution |
| **F** — actor/resource isolation | `sameKeyIsIsolatedByActorOperationAndResource`; `sameKeyAcrossActorsIsIsolated`; `anotherCheckerCannotReplayTheStoredResponse`; `sameKeyIsScopedByApprovalResource` | PASS — actor/resource scope. **Cross-tenant: NOT_TESTED/BLOCKED** (không có cột tenant) |
| **G** — migration & constraints | `AdminIdempotencyV7MigrationPostgreSqlIntegrationTest` (8 test); `FlywayMigrationTest.adminIdempotencyMigrationDeclaresScopedUniqueRecord` | PASS — metadata/constraint/index/DML thật |

Chi tiết 8 test V7: history V7 success + không duplicate + rerun không execute lại; cột/type/length/nullability/
identity/default; PK + 2 FK `RESTRICT` + 10 CHECK; unique scope index đúng thứ tự `(actor_id, operation, scope_key,
idempotency_key)` + 3 index phụ non-unique/non-partial; insert canonical dùng default (`RECEIVED`, `scope_key=''`,
`created_at=now()`, `expires_at` bắt buộc, JSONB, timestamptz offset); unique scope theo actor/operation/resource;
13 trạng thái không an toàn bị chặn (23505/23514/23503/22001); FK chặn delete actor/approval.

## 11. Lệnh và kết quả

```bash
cd digitalbanking-backend

./gradlew test --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
# BUILD SUCCESSFUL — 23 classes / 188 tests / 0 failure / 0 error / 0 skip

./gradlew test --tests 'com.simulatedbank.banking.admin.AdminIdempotencyPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' --console=plain
# BUILD SUCCESSFUL — 17 tests (9 idempotency + 8 migration) / 0 failure / 0 error / 0 skip

./gradlew cleanTest build --console=plain
# BUILD SUCCESSFUL (6m57s) — 88 classes / 1066 tests / 0 failure / 0 error / 0 skip
```

Flyway validation được chạy trong mọi test trên (`validateOnMigrate=true`); fresh database chạy đủ `V1`–`V8` và
rerun không thực thi migration mới. Không có database staging/production trong môi trường này nên **không** tuyên bố
đã kiểm chứng compatibility với dữ liệu production; V7 là `CREATE TABLE` mới, không sửa object đã có.

## 12. Files

**Created (task này)**

- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/AdminIdempotencyV7MigrationPostgreSqlIntegrationTest.java`
- `digitalbanking-docs/docs-backend/modules/admin/BE-ADM-DB-03.md`

**Modified (task này)**

- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminIdempotencyPostgreSqlIntegrationTest.java`
  (+3 test: winner rollback, response-persistence rollback/retry, expired `RECEIVED` retention)
- `digitalbanking-docs/docs-backend/modules/admin/tasks/README.md` (evidence §0.4 + checklist row)
- `digitalbanking-docs/docs-backend/modules/admin/README.md` (TOC + kết luận)

**Đã tồn tại từ `BE-ADM-CON-03` (không sửa trong task này)**

- `V7__create_admin_idempotency_record_table.sql`
- `admin/domain/AdminIdempotencyRecord|Scope|Status|Operation`
- `admin/application/AdminIdempotencyService`, `AdminRequestHasher`, `port/AdminIdempotencyStore`
- `admin/adapter/out/JdbcAdminIdempotencyStore`, `admin/adapter/in/AdminIdempotencyPurgeJob`
- `ApprovalProposalService`, `ApprovalDecisionService` (idempotency integration)

## 13. BLOCKED / contract ambiguity

| Mục | Trạng thái | Điều kiện mở |
|---|---|---|
| Cross-tenant isolation | **BLOCKED (schema)** | `04` không có tenant column; cần migration thêm tenant vào `admin_idempotency_record` scope + unique index trước khi mở đa tenant |
| `RECEIVED` không có recovery transition | **OPEN (contract)** | Contract hiện không cho phép đổi `RECEIVED`; cần DB/BA/Architect quyết định TTL/recovery cho flow hai pha. Task này không tự thêm |
| LIMIT_CHANGE proposal/approve | **BLOCKED** | Limit provider `applyApprovedLimitChange` (`ADM-OI-05`) chưa có runtime; endpoint fail-closed 503, không tạo state |
| Durable Audit C2/C3 | **BLOCKED** | `SecurityLogAdminAuditAdapter` thay cho `AuditModuleApi` (`ADM-OI-07`) |
| HTTP client timeout end-to-end | **NOT_TESTED** | Cần harness network; service/DB retry cùng key đã chứng minh |

## 14. Acceptance criteria

- [x] Persistence khớp `BE-ADM-CON-03`/`04` §4.5.4 (V7, 13 cột, PK/FK/CHECK/index).
- [x] Chỉ tạo bảng riêng vì contract yêu cầu (DEC-ADM-003); không tái dùng bảng transfer.
- [x] Unique race chỉ một approval execution owner (insert-first + unique index; winner rollback test).
- [x] Conflict cùng key/khác fingerprint trả `409 IDEMPOTENCY_CONFLICT`, record gốc nguyên vẹn.
- [x] Terminal replay trả đúng status/body, không chạy lại business/step-up/provider.
- [x] PENDING (`RECEIVED`) không bị xóa khi fail/timeout/hết hạn; purge terminal-only.
- [x] Actor/resource isolation; cross-tenant **BLOCKED** do schema single-tenant (ghi rõ, không fake).
- [x] PostgreSQL concurrency tests A–G pass (trừ cross-tenant không tồn tại trong schema).
- [x] Flyway migrate/validate trên PostgreSQL 16 fresh database pass; không sửa migration đã áp dụng.
- [x] Regression Admin/migration/ArchUnit + full build pass.
- [x] Documentation và checklist cập nhật.

## 15. Trạng thái cuối

`BE-ADM-DB-03` — **DONE**. Persistence/unique race/conflict/replay/PENDING retention đã được kiểm chứng trên
PostgreSQL thật; phần duy nhất không thể chứng minh là cross-tenant isolation vì canonical schema v1 không mô hình
tenant, và recovery transition cho `RECEIVED` vì contract chưa cho phép — cả hai ghi thành blocker có owner, không
thay bằng hành vi tự phát minh.
