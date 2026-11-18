# BE-ADM-TST-03 — Concurrency và idempotency tests với transaction độc lập

> **Mã task:** `BE-ADM-TST-03` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — PostgreSQL 16 Testcontainers, mỗi worker dùng service/connection/transaction riêng, đồng bộ bằng `CyclicBarrier` với timeout hữu hạn; phủ same-key/same-payload, same-key/different-hash, hai checker một apply, hai PENDING cùng target và một challenge bị hai transaction consume.
> **Nguồn:** `docs-common/09. Test plan.md`, `07. API contracts.md` §5.6/§6.5/§6.6/§8.5/§8.8, `04` §4.5.3/§4.5.4, `design/05` §12.1, `BE-ADM-CON-02/03`, `BE-ADM-APP-02/03/04`, `BE-ADM-DB-03`, `BE-ADM-TST-02`.

## 1. Nguyên tắc concurrency test

- Không dùng H2, không mock transaction/repository/lock/unique.
- Mỗi worker tự lấy `SecurityContext` (server-side session principal), gọi đúng production service (`ApprovalProposalService`, `ApprovalDecisionService`, `StepUpChallengePort`).
- Mỗi worker dùng transaction/connection riêng qua `TransactionTemplate` hoặc transaction nội bộ của service; không chia sẻ `EntityManager`/`Connection` giữa hai thread.
- Đồng bộ bằng `CyclicBarrier(2)` trước điểm contention; tất cả wait/future đều có timeout (`barrier.await(10s)`, `future.get(20s)`, `awaitTermination(10s)`), class có `@Timeout(60)`.
- `ExecutorService` được `shutdownNow()` + `awaitTermination` trong `finally`; lỗi từ worker được `Future.get()` propagate lên test runner.
- Không dùng `Thread.sleep` làm cơ chế đồng bộ chính.
- Sau khi cả hai worker xong, assertion đọc DB thật: status/checker/applied_at/version/session-role/idempotency/challenge/audit count.

## 2. Test class mới

`digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminConcurrencyPostgreSqlIntegrationTest.java` — **4 test**.

| Test | Kịch bản | Kết quả kỳ vọng |
|---|---|---|
| `concurrentSameProposalKeyWithDifferentHashHasOneWinnerAndOneConflict` | Hai proposal cùng key, cùng actor, payload khác (`AUDITOR` vs `OPERATIONS`), hai challenge đã verify | Một `201`; một `409 IDEMPOTENCY_CONFLICT`; 1 approval + 1 record; payload approval khớp winner; chỉ challenge winner bị consume; 1 C2 + 1 C3 |
| `twoConcurrentProposalsForTheSameTargetPersistTwoPendingRowsPerCanonicalSchema` | Hai key khác nhau, cùng target/role, chạy đồng thời | Cả hai `201`; 2 approval `PENDING` (canonical không có partial unique, `ADM-OI-03` OPEN); 2 record; 2 challenge consumed; target version/role chưa đổi |
| `twoPendingApprovalsForTheSameTargetApplyOnlyOnce` | Hai approval `PENDING` cùng target, `expectedVersion=0`; hai checker decision đồng thời | Một `APPLIED`, một `PENDING` + `ROLE_CONFLICT`; version 1; role gán một lần; 1 record; 1 challenge consumed; 1 C2 + 1 C3; loser không có checker/decided_at |
| `oneVerifiedChallengeCanBeConsumedByOnlyOneIndependentTransaction` | Một challenge `VERIFIED`; hai transaction độc lập gọi `consumeVerified` cùng lúc | Đúng một winner; loser nhận `INVALID_STATE_TRANSITION`/`STEP_UP_CHALLENGE_MISMATCH`/`AUTH_CHALLENGE_NOT_FOUND`; challenge consumed đúng một lần; decision sau đó bị từ chối, approval vẫn `PENDING`, 0 record, không role |

## 3. Test concurrency đã có được task này xác nhận lại

| Yêu cầu | Test hiện có |
|---|---|
| Same proposal key + same payload → một logical operation | `ApprovalProposalPostgreSqlIntegrationTest#concurrentSameKeyCreatesExactlyOneApproval` (2 thread, `CyclicBarrier`, 1 approval, replay) |
| Same-key decision → một business effect | `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#concurrentSameKeyHasOneBusinessEffect` |
| Hai checker một apply | `ApprovalStepUpPostgreSqlIntegrationTest#twoCheckersRacingApproveApplyTheRoleOnce`; `ApprovalRequestPostgreSqlIntegrationTest#concurrentCheckersSerializeOnRowLockAndOnlyOneApplies`; HTTP: `ApprovalDecisionHttpPostgreSqlIntegrationTest#concurrentCheckerDecisionsHaveOneWinner` |
| Hai key khác nhau trên cùng approval | `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#concurrentDifferentKeysOnlyOneApplies` |
| Unique claim race / winner rollback | `AdminIdempotencyPostgreSqlIntegrationTest#concurrentClaimsHaveExactlyOneWinner`, `#winnerRollbackReleasesTheKeyToTheBlockedConcurrentRequest` |
| Consumed challenge race | `ApprovalStepUpPostgreSqlIntegrationTest#concurrentConsumersHaveExactlyOneWinner`; test mới mở rộng sang `consumeVerified` với hai transaction độc lập |

Các test này không bị thay đổi nội dung hay nới assertion.

## 4. Cơ chế bảo vệ đã kiểm chứng

| Lớp | Cơ chế thật | Bằng chứng |
|---|---|---|
| Idempotency | `INSERT … ON CONFLICT (actor_id, operation, scope_key, idempotency_key) DO NOTHING` + đọc lại record | same-key tests, conflict test, `uq_admin_idempotency_scope` |
| Approval transition | `SELECT … FOR UPDATE` trong transaction quyết định | two-checker/two-pending tests |
| Provider role | Identity lock `app_user` (sort ID) + refresh + `role_assignment_version` | `twoPendingApprovals...` → loser `ROLE_CONFLICT`, version chỉ tăng một |
| Challenge | Conditional `UPDATE … WHERE consumed_at IS NULL` | `oneVerifiedChallenge...` → một winner |
| Audit | C2 write cuối transaction; C3 standalone cho denial | audit count sau mỗi race |

## 5. Map acceptance criteria

| Acceptance | Bằng chứng |
|---|---|
| Independent connection/transaction tests | `AdminConcurrencyPostgreSqlIntegrationTest`; `TransactionTemplate`/service transaction riêng từng worker |
| Same proposal key + same payload | `concurrentSameKeyCreatesExactlyOneApproval` (existing) |
| Same proposal key + different hash | `concurrentSameProposalKeyWithDifferentHashHasOneWinnerAndOneConflict` |
| Two checker-one apply race | `twoCheckersRacingApproveApplyTheRoleOnce`, `concurrentCheckersSerializeOnRowLockAndOnlyOneApplies`, new two-PENDING apply race |
| Two PENDING target race | `twoConcurrentProposalsForTheSameTargetPersistTwoPendingRowsPerCanonicalSchema` + `twoPendingApprovalsForTheSameTargetApplyOnlyOnce` |
| Consumed challenge race | `oneVerifiedChallengeCanBeConsumedByOnlyOneIndependentTransaction`; `concurrentConsumersHaveExactlyOneWinner` |
| Only one winning transition | Tất cả race test assert đúng một `APPLIED`/một winner |
| No lost update/double revoke | `twoPendingApprovals...` version 1, một role assignment, 1 record, 1 C2 |
| Thread/timeout/connection cleanup | `runPair` dùng future timeout 20s, `shutdownNow`, `awaitTermination` assert; barrier 10s |
| Repeated runs without flakiness | Chạy lặp lớp mới 3 lần (xem §6) |

## 6. Kết quả thực thi

Lệnh targeted (gồm regression của các suite concurrency cũ):

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.AdminConcurrencyPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.AdminPersistencePostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.ApprovalProposalPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.ApprovalDecisionIdempotencyPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.ApprovalStepUpPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.ApprovalRequestV6MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.migration.PostgreSqlFreshMigrationIntegrationTest' \
  --console=plain --no-daemon --max-workers=1
```

**BUILD SUCCESSFUL** — **8 classes / 71 tests / 0 failure / 0 error / 0 skip**.

Chạy lặp lớp concurrency 3 lần:

```bash
for run in 1 2 3; do
  ./gradlew test --tests 'com.simulatedbank.banking.admin.AdminConcurrencyPostgreSqlIntegrationTest' \
    --rerun-tasks --console=plain --no-daemon --max-workers=1
done
```

Kết quả: cả 3 lần **BUILD SUCCESSFUL**, 4/4 test PASS/lần, không có timeout/leaked thread/connection.

Broad regression (Admin + migration): **BUILD SUCCESSFUL**, **37 classes / 291 tests / 0 failure / 0 error / 0 skip**.

Full backend `./gradlew test`: **BUILD SUCCESSFUL** (10m56s), **116 classes / 1325 tests / 0 failure / 0 error / 0 skip**. Sau chỉnh sửa cuối (`@Timeout(60)`), chạy lại `AdminConcurrencyPostgreSqlIntegrationTest` + `AdminPersistencePostgreSqlIntegrationTest`: **PASS** (10 test).

## 7. Defect và fix

- **Không phát hiện production defect** trong idempotency/lock/provider/challenge của Admin; toàn bộ race test pass với code hiện tại.
- Test-infra defect đã sửa: helper thu kết quả ban đầu dùng `List.of(...)` nên không chấp nhận outcome `null` (winner của race); đổi sang `Arrays.asList` để biểu diễn "thành công" là `null` mà vẫn propagate exception từ worker.
- Test-infra defect đã sửa: gọi helper decision (helper có catch `BankingBusinessException`) không thể dùng để assert "phải ném"; test tiêu thụ challenge thứ hai gọi thẳng `ApprovalDecisionService.decide` để quan sát rejection.

## 8. Giới hạn / blocker

- `ADM-OI-03`: canonical chưa có partial unique PENDING; test khẳng định hai request cùng target đều `PENDING` và chỉ một apply được nhờ provider version. Không tự thêm unique index.
- `ADM-OI-05`: LIMIT approve fail-closed 503; race test chỉ áp dụng ROLE_CHANGE. Không claim limit apply concurrency.
- `ADM-OI-06`: chưa có tenant/staff data scope; race dừng ở actor + approval scope hiện tại.
- `ADM-OI-07`: audit durability vẫn là structured log/`AdminAuditPort`; chưa có `audit_log`/hash chain để test C2/C3 trong DB.
