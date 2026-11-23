# BE-ADM-TST-05 — Failure/recovery tests: DB, audit, provider timeout, crash window và retry

> **Mã task:** `BE-ADM-TST-05` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — fault injection ở biên hạ tầng thật (JDBC, PostgreSQL trigger/row lock, transaction, audit port) + PostgreSQL 16 + Identity thật; mọi scenario đều assert state đã persist, không mock transaction/repository. **BLOCKED** riêng phần phụ thuộc provider chưa có: durable `AuditModuleApi` (`ADM-OI-07`), Limit provider/`422` (`ADM-OI-05`), tenant (`ADM-OI-06`).
> **Nguồn:** `docs-common/09. Test plan.md`, `design/02. consistency-concurrency.md` §5–§7, `design/05. Modules contract.md` §3.2/§12.1, `07. API contracts.md` §5.2/§5.6/§8.5/§8.8, `BE-ADM-APP-02/03/04`, `BE-ADM-DB-03`, `BE-ADM-TST-02/03/04`.

## 1. Phạm vi

Các acceptance của `BE-ADM-TST-05`:

- Failure injection tái sử dụng, đặt đúng biên hạ tầng: pool JDBC (`08006`), PostgreSQL trigger (`58000`), row lock + `lock_timeout` thật (`55P03`), audit port seam, crash window sau commit.
- Database failure/rollback: không commit một phần; exception map theo hợp đồng; recovery cho phép request hợp lệ tiếp theo.
- Audit: C2 fail-closed (rollback toàn bộ), không để lại **success fact sai**, replay không nhân đôi fact, không rò secret.
- Provider timeout/uncertain outcome: timeout trong transaction của Identity → `503` retryable, rollback provider/claim/challenge; retry cùng proof chạy đúng một lần.
- Crash window và idempotency: commit nhưng client mất response → replay response đã lưu, không thêm effect; restart toàn bộ application → replay vẫn đúng.
- Retry không chạy trong transaction đã abort: chứng minh bằng `xid` khác nhau + `afterCompletion` `ROLLED_BACK` → `COMMITTED`, không còn transaction bound sau rollback.

Không thêm automatic retry vào operation chưa có retry-safety contract: Admin hiện **không** có retry interceptor; test đếm invocation để chứng minh mỗi request chỉ chạy một lần business attempt.

## 2. Fault injection infrastructure

| Fault | Cách inject | Vì sao hợp lệ |
|---|---|---|
| Database outage | `FaultyDataSource` (JDBC boundary) ném `SQLTransientConnectionException` SQLState `08006` cho mọi `getConnection` | Đúng lớp lỗi pool gặp khi PostgreSQL down; transaction/exception path production giữ nguyên; bật/tắt được để test recovery |
| Repository write failure | PostgreSQL trigger `BEFORE INSERT/UPDATE ON banking.approval_request` raise `58000` | Lỗi DB thật tại write cuối/giữa transaction, không mock repository |
| Provider lock timeout | Row lock `app_user` từ connection riêng + role-level `lock_timeout='400ms'` | Identity khoá `app_user` trong cùng transaction; PostgreSQL trả `55P03` thật → `DatabaseErrorMapper` → `503` |
| Audit failure | `@MockitoSpyBean AdminAuditPort` ném lỗi ở `recordInTransaction` | Durable Audit provider chưa tồn tại (`ADM-OI-07`); seam là port production, không thay orchestration |
| Crash window | Gọi `ApprovalDecisionService.decide` trực tiếp (commit) rồi mới replay qua HTTP | Mô phỏng app commit xong nhưng client không nhận response |
| Application recovery | Hai `SpringApplicationContext` độc lập (WebApplicationType.NONE) chia sẻ PostgreSQL | Bean/memory state bị xoá hoàn toàn; chỉ DB còn lại |

Không dùng H2, không mock `PlatformTransactionManager`, không mock repository, không bypass `TransactionTemplate`.

## 3. Test class và scenario

### 3.1. `AdminFailureRecoveryPostgreSqlIntegrationTest` (mới, 9 test)

Đường dẫn: `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminFailureRecoveryPostgreSqlIntegrationTest.java`.

| # | Test | Scenario | Assertion chính |
|---|---|---|---|
| 1 | `databaseOutageIsReportedAndRecovers` | PostgreSQL unreachable (write + read) | `503 SERVICE_UNAVAILABLE` + `Retry-After` + envelope/no-store/`X-Request-ID`; 0 approval/claim/challenge; không rò SQL/token; DB trở lại → `428` bình thường |
| 2 | `connectionFailureInsideTheTransactionRollsBackAndRetries` | Connection fail trước khi transaction của service mở (precheck) | `CannotCreateTransactionException`/`DataAccessResourceFailureException`; approval vẫn `PENDING`, challenge `VERIFIED`, 0 decision claim, 0 C2; recovery → `200 APPLIED`, role 1 lần, claim `COMPLETED`, 1 C2 |
| 3 | `approvalInsertFailureRollsBackEverything` | Trigger fail `INSERT approval_request` sau khi claim + consume challenge | `500 INTERNAL_ERROR` không lộ internals; 0 approval/claim; challenge `VERIFIED`/`consumed_at NULL`; 0 C2; bỏ trigger → retry cùng key/proof = `201`, 1 approval, 1 claim `COMPLETED`, 1 C2; replay = stored response, không approval thứ hai |
| 4 | `decisionUpdateFailureRollsBackProviderAndChallenge` | Trigger fail `UPDATE approval_request` (sau khi provider đã mutate trong tx) | `500`; approval `PENDING`, không role assignment, version 0, 0 decision claim, challenge `VERIFIED`, 0 C2; bỏ trigger → retry = `200 APPLIED`, role 1 lần, version 1, 1 claim, 1 C2 |
| 5 | `providerLockTimeoutFailsClosedAndRetriesUnderTheSameProof` | Row lock trên target + `lock_timeout 400ms` → `55P03` | `503 SERVICE_UNAVAILABLE` + `Retry-After`; rollback provider/claim/challenge; release lock → retry cùng proof = `200 APPLIED`, 1 C2 |
| 6 | `committedDecisionReplaysWhenTheResponseWasLost` | Commit qua service, HTTP replay sau đó | replay `200 Idempotent-Replayed: true`, đúng status `APPLIED`; role 1 lần, version 1, claim `COMPLETED`, **1** C2 (không nhân đôi) |
| 7 | `auditFailureRollsBackTheWholeDecisionAndRetryEmitsOneFact` | C2 audit provider timeout (port seam) | `500`; rollback approval/provider/claim/challenge; response/log không chứa message lỗi audit; retry = `200 APPLIED` + 1 C2; replay không thêm C2 |
| 8 | `retryRunsInANewTransactionAfterRollback` | Ghi `txid_current()` + `afterCompletion` trong audit hook, fail lần 1 | `first=ROLLED_BACK`; `TransactionSynchronizationManager.isActualTransactionActive()` false; 0 decision claim; retry `COMMITTED` với **xid khác**; chỉ 2 invocation audit (không có retry ẩn), role 1 lần, 1 C2 |
| 9 | `nonRetryableFailuresAreNotRetried` | 403 (checker thiếu quyền), 404 (approval không tồn tại), 409 (maker self-approve) | Không mở decision transaction (`recordInTransaction` never), 0 decision claim, approval `PENDING`, challenge `VERIFIED`, 0 C2/C3 business fact, provider không chạy |

### 3.2. `AdminIdempotencyRestartPostgreSqlIntegrationTest` (mới, 1 test)

Đường dẫn: `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminIdempotencyRestartPostgreSqlIntegrationTest.java`.

| Bước | Assertion |
|---|---|
| Context 1: proposal + decision (OTP transport double, challenge/consume thật) | `201`, `200 APPLIED`, version 1, 1 C2 |
| Đóng context 1 (bean bị destroy) | — |
| Context 2 (bean mới, cùng PostgreSQL) | `SessionAuthenticator` vẫn xác thực session; replay cùng key = `replayed=true`, body **bằng** body gốc, 0 C2 mới, version vẫn 1, đúng 1 role AUDITOR, claim `COMPLETED` |
| Key khác trên approval terminal | `409 APPROVAL_ALREADY_DECIDED`, version vẫn 1 |

## 4. Production defect phát hiện và fix

**Defect (BE-ADM-TST-05, phát hiện bởi test #4):** `SecurityLogAdminAuditAdapter.recordInTransaction` phát structured log + metric `admin.audit.events` **ngay trong transaction**. Khi transaction fail ở commit-time (Hibernate flush bị trigger/constraint từ chối sau khi audit đã emit), hệ thống để lại **success audit fact cho một transaction đã rollback** — vi phạm acceptance "failed transactions do not leave misleading success audit records".

**Fix:** C2 fact được publish trong `TransactionSynchronization.afterCommit()` khi có transaction synchronization; rollback không phát fact nào. Không có transaction (unit/adapter call trực tiếp) vẫn emit ngay.

- File: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/out/SecurityLogAdminAuditAdapter.java`.
- Trade-off: log/metric không transactional nên không thể "rollback" — publish sau commit là cách duy nhất để fact khớp state đã persist. Khi `AuditModuleApi`/`audit_log` có runtime (`ADM-OI-07`), provider **phải** insert trong transaction (fail-closed bằng DB) và thay hành vi này.
- Regression: test #4 assert `admin.audit.events{action=ROLE_CHANGE_APPROVED}` = 0 sau commit-time failure, và = 1 sau retry thành công; test #6/#7 assert replay không nhân đôi.
- Không phát hiện defect khác trong transaction/idempotency/provider path của Admin.

## 5. Retry semantics

- Admin **không** có retry interceptor/`@Retryable`; mỗi HTTP request là một business attempt. Test #8 chứng minh:
  - attempt 1 rollback xảy ra ở transaction riêng, `xid` được ghi lại;
  - không còn transaction bound sau rollback;
  - attempt 2 (client retry) commit với `xid` khác → retry không chạy trong transaction aborted;
  - không có invocation audit thứ ba (không retry ẩn);
  - retry exhaustion: không áp dụng vì không có retry loop; failure được trả đúng status (`500`/`503`) và state sạch.
- Nếu sau này thêm retry framework, test #8 là regression bắt buộc phải cập nhật cùng contract.

## 6. Lệnh và kết quả

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.AdminFailureRecoveryPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.AdminIdempotencyRestartPostgreSqlIntegrationTest' \
  --console=plain
```

- Targeted: **BUILD SUCCESSFUL** — `AdminFailureRecoveryPostgreSqlIntegrationTest` 9/9 PASS, `AdminIdempotencyRestartPostgreSqlIntegrationTest` 1/1 PASS (PostgreSQL 16 Testcontainers + Flyway `V1`–`V8` + Identity/transaction thật).
- Regression rộng: `./gradlew test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.common.*' --tests 'com.simulatedbank.banking.architecture.*' --tests 'com.simulatedbank.banking.migration.*' --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' --tests 'com.simulatedbank.banking.account.adapter.in.AdminCustomerAccountsControllerPostgreSqlIntegrationTest' --max-workers=2` → **BUILD SUCCESSFUL**, 65 classes / **559 tests** / 0 failure / 0 error / 0 skip.
- Full backend `./gradlew test`: **BUILD SUCCESSFUL** (8m16s), 120 classes / **1345 tests** / 0 failure / 0 error / 0 skip.

## 7. Blocked scenario (từng phần)

| Scenario | Trạng thái | Dependency / điều kiện mở | Evidence cần khi mở |
|---|---|---|---|
| C2 audit persistence fail-closed thật (`audit_log` insert fail) | **BLOCKED** | `AuditModuleApi` + `audit_log` + hash chain (`ADM-OI-07`, owner Audit/Thịnh) | Test với provider thật: insert fail → rollback approval/provider/claim/challenge; audit row không tồn tại; hash chain đúng; replay không duplicate |
| Audit provider timeout khi publish async/outbox | **BLOCKED** | Outbox/provider delivery của Audit | Test transaction + delivery semantics của outbox thật |
| LIMIT_CHANGE approve provider failure/uncertain outcome | **BLOCKED** | `limit.api.applyApprovedLimitChange` + `limit_config PENDING_APPROVAL` (`ADM-OI-05`) | 503/rollback hiện được pin; success + timeout + provider-applied-then-lost cần provider thật |
| `422` semantic policy cho limit | **BLOCKED** | Limit/Audit/API owners duyệt policy (`ADM-OI-05`) | Test 422 thật + OpenAPI khớp; hiện runtime fail-closed 503/400 và **không** phát 422 |
| Cross-tenant failure isolation | **BLOCKED** | Tenant column trong schema + scope (`ADM-OI-06`) | Cross-tenant read/write denial + rollback evidence |
| Automatic retry (nếu được duyệt) | **N/A hiện tại** | Chưa có retry framework trong Admin | Nếu thêm: test #8 mở rộng để assert retry không nằm trong aborted TX và retry-exhaustion behavior |

## 8. Kết luận

- `BE-ADM-TST-05`: **DONE (phần có runtime)** — DB failure/rollback, C2 fail-closed + success-fact correctness (có fix production), provider `55P03` timeout, crash window, replay sau restart, idempotency replay và retry-trong-transaction-mới đều có test pass trên PostgreSQL 16 thật.
- **BLOCKED:** `ADM-OI-05`, `ADM-OI-06`, `ADM-OI-07` (ghi riêng ở §7, không đánh dấu task phụ thuộc là DONE).
- Full backend suite `./gradlew test`: **BUILD SUCCESSFUL** (8m16s) — 120 classes / **1345 tests** / 0 failure / 0 error / 0 skip.
