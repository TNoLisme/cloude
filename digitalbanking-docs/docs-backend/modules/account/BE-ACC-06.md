# BE-ACC-06 — ASC row lock `FOR UPDATE`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-06
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** **COMPLETED** (pessimistic row lock `ORDER BY account_id ASC FOR UPDATE`, transaction `MANDATORY`, bounded wait, PostgreSQL 16 concurrency evidence)
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần Architect + DBA
> **Branch:** `datht-backend-rework`, không tạo/không switch branch, không commit/push
> **Nguồn tham chiếu:** `docs-common/design/02. consistency-concurrency.md` §7.3/§7.4/§7.10/A.3, §11.2/§11.3; `docs-common/design/05. Modules contract.md` §7.2 (`AccountBalance.lockTransferAccounts`); [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.3.1; `07` §2.6; [BE-ACC-02](BE-ACC-02.md); [BE-ACC-04](BE-ACC-04.md); [DEC-ACC-006](../../../../DECISIONS.md)

---

## A. Phạm vi

| Hạng mục | Kết quả |
|---|---|
| Lock primitive | `account.application.AccountLockService.lockTransferAccounts(sourceId, destinationId)` → `AccountBalance.LockedAccounts` |
| Port persistence | `account.application.AccountLockPort` + `account.adapter.out.JdbcAccountLockAdapter` |
| SQL | Một câu: `SELECT … FROM banking.account WHERE account_id IN (?, ?) ORDER BY account_id FOR UPDATE` |
| Transaction | `@Transactional(propagation = MANDATORY)` ở service + adapter; lock giữ tới commit/rollback của T2 |
| Bounded wait | Tôn trọng `lock_timeout` của T2; chỉ áp default **2s** (`design/02` §7.10) khi transaction chưa set |
| Row không liên quan | Không lock (test chứng minh row thứ ba vẫn `FOR UPDATE NOWAIT` được) |
| Debit/credit conditional | **Không thuộc task này** — BE-ACC-07 |
| T2 end-to-end với Transfer | **BLOCKED** — BE-ACC-08 (chưa có orchestrator) |

Không dùng optimistic `@Version` (ACC-DR-07), không `NOWAIT`/`SKIP LOCKED` cho money path, không in-memory lock (không an toàn đa instance).

---

## B. Quyết định triển khai (DEC-ACC-006)

| # | Quyết định | Căn cứ | Hệ quả |
|---|---|---|---|
| 1 | Tách primitive: `AccountLockService` + `AccountLockPort` (application) và `JdbcAccountLockAdapter` (adapter/out) | `design/02` §11.1/§11.3; BE-ACC-03 pattern | BE-ACC-07 chỉ thêm conditional update rồi bọc thành `AccountBalance`; không duplicate SQL |
| 2 | Dùng `JdbcTemplate`/native SQL, không `@Lock(PESSIMISTIC_WRITE)` JPQL | `design/02` §11.3: Hibernate có thể đổi plan/order; native kiểm soát chính xác `ORDER BY`/`FOR UPDATE` | SQL nằm trong test-visible adapter; không phụ thuộc session cache |
| 3 | Một câu lệnh lock cả hai row `ORDER BY account_id` | `design/02` A.3/§7.3 | Giảm deadlock A→B/B→A; thứ tự khóa toàn cục transfer → account ASC |
| 4 | `MANDATORY` ở cả service và adapter | `design/05` §7.2 "gọi ngoài T2 là lỗi lập trình" | Gọi ngoài transaction → `IllegalTransactionStateException` fail fast; provider không tự mở/commit |
| 5 | Không lock `NOWAIT`/`SKIP LOCKED`; chờ có bound | `design/02` §7.3 (chỉ transfer row dùng `NOWAIT`), §7.10 | Lock chờ; 55P03 khi vượt `lock_timeout` là technical failure, orchestrator map 503/retry |
| 6 | Default `lock_timeout = 2s` chỉ khi `current_setting('lock_timeout')` là `0`/`0ms` | `design/02` §7.10/§11.2 | Không treo vô hạn nếu caller quên; không ghi đè timeout caller đã đặt |
| 7 | `SET LOCAL` (transaction-scoped) | `design/02` §11.2 | Không ảnh hưởng pool/job khác |
| 8 | ID cùng nhau/không dương → 400 trước SQL; thiếu row → 404 | `design/05` §7.2; `07` §2 | Không lock gì khi input sai; không tiết lộ row nào thiếu |
| 9 | Timeout/deadlock/SQLSTATE lan truyền nguyên vẹn | BE-CON-54 | Không nuốt thành 404/business result; `DatabaseErrorMapper` map 55P03/40P01/40001/57014 → `SERVICE_UNAVAILABLE` |

---

## C. SQL và transaction

```sql
SELECT account_id, customer_id, status, currency, available_balance, ledger_balance
FROM banking.account
WHERE account_id IN (?, ?)
ORDER BY account_id
FOR UPDATE;
```

- Lock acquisition nằm trong T2 do orchestrator mở; `AccountLockService`/adapter đều `MANDATORY`.
- Lock được nhả khi T2 commit/rollback (PostgreSQL row lock); không có unlock thủ công, không cache snapshot sau commit.
- `LockedAccounts` chỉ là snapshot trong transaction hiện tại; re-check owner/status/balance vẫn bắt buộc ở bước sau (design/02 §7.4).
- Không gọi network/external service trong lúc giữ lock; adapter chỉ chạy 1 `current_setting` + 1 `SELECT … FOR UPDATE` (khi cần default timeout).

---

## D. Concurrency correctness — ma trận bằng chứng

| Yêu cầu | Test (`AccountLockPostgreSqlIntegrationTest`) |
|---|---|
| Hai transaction cùng row bị serialize | `secondTransactionWaitsForTheFirstCommitAndSeesCommittedBalancesWithoutDirtyRead` — transaction 2 không lấy được lock trong 700ms, sau commit thấy `1500.00` (không dirty read) |
| Lock thật ở tầng PostgreSQL | `postgresRejectsNoWaitLockOnTheHeldRowsButNotOnUnrelatedRows` — `FOR UPDATE NOWAIT` từ connection khác nhận SQLSTATE `55P03` cho cả 2 row; row thứ ba vẫn lock được |
| Không lock row không liên quan | cùng test trên (row sibling `unrelated` mở) |
| Không lost update | `concurrentReadModifyWriteOnTheSameRowsLosesNoUpdates` — 6 worker read-modify-write dưới lock, kết quả `1000 + 6×10` |
| Rollback nhả lock + bỏ mutation | `rollbackReleasesTheLockAndDiscardsTheProtectedMutation` |
| Deadlock với chiều ngược | `oppositeTransferDirectionsDoNotDeadlockBecauseRowsAreLockedInAscendingIdOrder` — 2×25 transaction A→B/B→A không deadlock |
| Lock timeout caller đặt | `lockWaitIsBoundedByAnExplicitCallerTimeoutAndReportsTechnicalFailure` — `250ms` → `55P03` trong 200–1500ms |
| Default bound khi caller quên | `defaultLockWaitIsBoundedWhenTheCallerDidNotConfigureOne` — `2s` → `55P03` trong 1.5–5s |
| Fail fast ngoài transaction | `lockRequiresAnActiveTransactionAndFailsFast` |
| Input sai/thiếu row | `sameOrNonPositiveIdentifiersAreRejectedBeforeAnySql`, `missingRowIsAccountNotFoundAndLeavesNoLockBehindAfterRollback` |
| Business invariants | `ck_account_balance_within_ledger`/`>= 0` vẫn đúng sau chuỗi update (assert trong lost-update test) |

Giới hạn có chủ đích: đây là lock-only evidence. T-CON-01/T-CON-03 đầy đủ (kèm debit/credit/ledger/audit trong cùng T2) thuộc BE-ACC-07/08/11; test hiện tại **không** tuyên bố money path end-to-end.

---

## E. Kiểm thử và kết quả

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.account.application.AccountLockPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.account.*' --console=plain
```

| Suite | Kết quả |
|---|---|
| `AccountLockPostgreSqlIntegrationTest` (mới, PostgreSQL 16 Testcontainers) | **11/11 PASS**, 0 failure/error/skip |
| Account module regression (8 suite) | **78/78 PASS** (gồm 17 HTTP BE-ACC-05) |
| `ModuleBoundaryArchTest` | PASS — `account.api` vẫn JDK/Common-only; port chỉ implement trong `application`; adapter/in không chạm adapter/out |

Môi trường: PostgreSQL 16 (`postgres:16-alpine`) qua Testcontainers, Flyway `V1`–`V8`, Hibernate `validate`; pool 10 connection cho kịch bản đa transaction; không H2/mock DB.

Full `./gradlew build --max-workers=1` (isolated copy có `digitalbanking-docs` để test OpenAPI resolve): **BUILD SUCCESSFUL** — **98 classes / 1166 tests / 0 failure / 0 error / 0 skip** (8m10s).

---

## F. Việc còn lại

| Blocker | Ảnh hưởng | Owner | Trạng thái |
|---|---|---|---|
| BE-ACC-07 | Conditional debit/credit + rollback sau debit | Account | **COMPLETED 18/11/2026** — xem [BE-ACC-07](BE-ACC-07.md) |
| BE-ACC-08 | JOIN T2 thật với Transfer/Ledger | Transfer/Account | **BLOCKED** — chưa có consumer/orchestrator |
| BE-ACC-11 | T-CON-01–04 end-to-end + failure injection | Account/Transfer | **BLOCKED** — cần 07/08 |
| Deadlock absolute guarantee | Thứ tự ASC giảm nguy cơ, không loại bỏ tuyệt đối mọi deadlock | Architect | Ghi nhận theo `design/02` §7.4 |

---

## G. Change log

| Version | Ngày | Nội dung |
|---|---|---|
| v1.0 | 18/11/2026 | `AccountLockService` + `AccountLockPort` + `JdbcAccountLockAdapter`; SQL `ORDER BY account_id FOR UPDATE`; default `lock_timeout` 2s; 11 PostgreSQL 16 concurrency tests; full build 1166/1166 PASS; [DEC-ACC-006](../../../../DECISIONS.md) |
