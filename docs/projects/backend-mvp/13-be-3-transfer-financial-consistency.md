# BE-3 — Kế hoạch Transfer và Financial Consistency

**Người phụ trách:** BE-3  
**Reviewer:** BE-2, BE-1, BE-4  
**Phối hợp:** FE/BE  
**Trạng thái:** P0 rủi ro cao nhất; chưa nghiệm thu trước khi test PostgreSQL pass.  
**Mục tiêu:** Chứng minh tiền không mất, không tạo thêm, không debit hai lần, không âm balance, không deadlock và retry an toàn.

---

## 1. Phạm vi công việc

### 1.1 Bao gồm

- Recipient resolve.
- Transfer create/confirm/list/detail.
- Small transfer atomicity.
- Large transfer OTP state machine.
- Idempotency replay/conflict.
- Ordered account-pair locking.
- Test-only rollback injection.
- Timeout-after-commit retry.
- Concurrent overdraft.
- Concurrent duplicate idempotency.
- Opposite-direction locking.
- Duplicate OTP confirm.
- Block-vs-confirm race.
- PostgreSQL/Testcontainers evidence.

### 1.2 Không bao gồm

- Account repository redesign.
- Identity/OTP sender redesign.
- Audit repository redesign.
- Risk rule redesign.
- Real notification delivery.
- Cancel/resend endpoint.
- Ledger/double-entry accounting.
- Cloud deployment.
- Sửa OpenAPI nếu chưa approved.

---

## 2. File và vùng code

```text
be/src/main/java/com/bank/simulator/transfer/**
be/src/test/java/com/bank/simulator/transfer/**
be/src/test/java/com/bank/simulator/UiApiRegressionPostgresTest.java
```

Nguyên tắc module:

- Không inject Account repository mới vào transfer business path nếu đã có module API.
- Nếu cần đổi `AccountModuleApi`, BE-2 review trước.
- Không đổi Identity OTP implementation trực tiếp; BE-1 cung cấp boundary.
- Không sửa migration cũ.
- Test concurrency phải chạy PostgreSQL, không chỉ mock/H2.

---

## 3. Kết quả phải bàn giao

```text
- Test rollback.
- Test concurrency.
- Test timeout/retry.
- Test state race.
- PostgreSQL output.
- Financial invariant summary.
- FE transfer handoff.
- Known flaky/timing limitations.
```

Mẫu:

```text
Scenario:
Owner:
Files:
Database:
Command:
Expected:
Actual:
Pass/fail:
Financial invariant:
FE behavior:
Next owner:
```

---

## 4. Kế hoạch thực thi theo bước

### Bước 1 — Đối chiếu transfer contract

Đọc:

- `docs/baseline/api-and-team-contract.md`.
- `docs/baseline/mvp-requirements-and-architecture.md`.
- `docs/baseline/quality-security-and-cloud.md`.
- `docs/projects/backend-mvp/05-transfers.md`.
- `docs/projects/2026-10-01-security-transaction-refinements.md`.
- `contracts/openapi.yaml`.

Lập bảng:

| Scenario | Request | Expected status/code | Balance effect | Test | Gap |
|---|---|---|---|---|---|
| Small success | <=5M | 201 | debit+credit | ... | ... |
| Large create | >5M | 200 | unchanged | ... | ... |
| OTP success | confirm | 201 | debit+credit | ... | ... |
| OTP replay | confirm again | 200 replay | unchanged | ... | ... |
| Insufficient | any | 409 | unchanged | ... | ... |
| Blocked | any | 409 | unchanged | ... | ... |
| Expired | confirm | 409 | unchanged | ... | ... |
| Wrong OTP | confirm | 400/409 | unchanged | ... | ... |

### Bước 2 — Tạo test-only failure seam

Khuyến nghị interface:

```java
public interface TransferFailureInjector {
    void afterDebitBeforeCredit();
}
```

Quy tắc:

- Production implementation no-op.
- Test profile implementation throw exception.
- Không dùng `if (testMode)` trong business code.
- Không bật injector trong `local`, `demo`, shared hoặc cloud.
- Bean test phải được bật rõ trong test context.

### Bước 3 — Rollback sau debit trước credit

Flow:

1. Tạo disposable PostgreSQL.
2. Tạo source/destination account.
3. Seed source.
4. Gửi transfer hợp lệ.
5. Debit source chạy.
6. Injector throw trước credit.
7. Transaction rollback.

Assert:

- Source balance về giá trị trước request.
- Destination balance không đổi.
- Không có `COMPLETED` transfer.
- Không có successful idempotency response.
- Không có audit committed.
- Không có risk flag từ transfer rollback.
- Retry cùng key xử lý theo semantics đã chốt, không tạo double mutation.

### Bước 4 — Timeout-after-commit

Mô phỏng bằng test harness hoặc hook chỉ dành cho test:

1. Submit transfer với key K1.
2. Commit DB.
3. Delay/suppress response.
4. Client coi kết quả là unknown.
5. Retry đúng body + K1.
6. Assert original logical response.
7. Assert source debit đúng một lần.
8. Assert destination credit đúng một lần.

Không:

- Tạo K2 tự động.
- Trừ tiền theo UI assumption.
- Trả business failure chỉ vì HTTP timeout.

### Bước 5 — Concurrent overdraft

Scenario:

```text
Source balance: 1,000,000 VND
Request A: 600,000 VND
Request B: 600,000 VND
```

Implementation:

- `CountDownLatch` cho start barrier.
- `ExecutorService` fixed pool.
- Timeout cho mỗi `Future`.
- Shutdown executor trong `finally`.
- Log test-only request outcome, không log secret.

Expected:

- Exactly one commit.
- One `409 INSUFFICIENT_FUNDS`.
- Final source `400,000`.
- Destination receives one `600,000`.
- No negative balance.
- No lost update.

Không dùng sleep tùy ý để đồng bộ. Dùng latch/barrier/timeout.

### Bước 6 — Concurrent duplicate idempotency

Gửi N request cùng:

- actor.
- operation.
- idempotency key.
- canonical payload.

Assert:

- Một transfer row.
- Một debit.
- Một credit.
- Replay trả original logical result.
- Payload khác cùng key trả `409 IDEMPOTENCY_KEY_REUSED`.
- Không có duplicate audit/risk mutation.

### Bước 7 — Opposite-direction locking

Chạy A→B và B→A đồng thời nhiều lần:

- Hai flow dùng cùng lock API.
- SQL lock order theo PostgreSQL `id ASC`.
- Future timeout hữu hạn.
- Không deadlock lâu dài.
- Nếu fail, capture exception/lock evidence rồi fail test.
- Không retry vô hạn để che lỗi.

### Bước 8 — Duplicate OTP confirm

Tạo transfer `AWAITING_OTP`, gửi confirm đồng thời:

- Một `COMPLETED`.
- Một debit/credit pair.
- OTP consume một lần.
- Request còn lại replay hoặc state conflict đúng contract.
- Confirm sau completed không đổi balance.

### Bước 9 — Block-vs-confirm race

1. Tạo large transfer `AWAITING_OTP`.
2. Chạy block source/destination và confirm OTP cạnh tranh.
3. Confirm lock lại transfer/challenge/accounts.
4. Revalidate status, ownership, currency, account state, balance dưới lock.

Assert:

- Account không eligible → `FAILED` đúng failure code.
- Balance không đổi.
- OTP invalidated theo contract.
- Terminal state không bị ghi đè.

### Bước 10 — Review transaction boundary

Xác nhận:

- PIN verification trước money transaction.
- Large transfer create chưa debit/reserve.
- Confirm OTP lock accounts trong confirm transaction.
- Debit, credit, complete, audit, OTP consume atomic.
- Risk chạy sau commit.
- Không network call trong money transaction.

---

## 5. Lệnh kiểm thử

Unit/MVC:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*TransferServiceTest,*TransferServiceLifecycleTest,*TransferControllerMvcTest,*Recipient*Test" test
```

PostgreSQL:

```powershell
mvn -q -Dtest="*Transfer*PostgresTest,UiApiRegressionPostgresTest" test
```

Package:

```powershell
mvn -q -DskipTests package
```

Dùng cấu hình Testcontainers trong:

```text
docs/projects/backend-mvp/evidence/docker-testcontainers.md
```

Nếu Docker/Testcontainers fail:

- Ghi exact error.
- Phân loại environment/tooling/code.
- Không thay bằng shared PostgreSQL.
- Không bỏ qua test rồi ghi pass.

---

## 6. Tiêu chí nghiệm thu

### Financial correctness

- Rollback test pass.
- Không one-sided debit/credit.
- Không balance âm.
- Exact VND integer amount.
- Reject không đổi balance.

### Concurrency

- Overdraft test pass.
- Same-key duplicate pass.
- Opposite-direction no persistent deadlock.
- Duplicate confirm một mutation.
- Block-vs-confirm revalidate dưới lock.

### State/idempotency

- `5,000,000` hoàn tất không OTP.
- `5,000,001` tạo `AWAITING_OTP`, balance unchanged.
- Expired thành `EXPIRED`.
- Fifth wrong OTP thành failure đúng contract.
- Terminal state không quay lui.
- Timeout retry trả original result.

### Evidence

```text
- Command.
- Test count.
- PostgreSQL version.
- Thread/barrier setup.
- Expected/actual.
- Final balances.
- Transfer rows.
- Idempotency rows.
- Audit/risk rows.
- Timeout/deadlock diagnostics.
```

---

## 7. Handoff cho FE/BE

Gửi:

1. State machine `AWAITING_OTP/COMPLETED/EXPIRED/FAILED`.
2. Boundary `5,000,000` và `5,000,001`.
3. Request body/header.
4. Idempotency key generate/retry.
5. Timeout = unknown, không failed.
6. `GET /transfers/{transferId}` reconciliation.
7. Wrong OTP attempts.
8. Expiry.
9. Insufficient funds/account eligibility/currency mismatch.
10. `Idempotency-Replayed`.
11. Không optimistic balance.
12. Không cancel/resend UI.
13. Error matrix:

```text
201 COMPLETED
200 AWAITING_OTP
200 replay
400 OTP_INVALID
409 STATE_CONFLICT
409 TRANSFER_EXPIRED
409 INSUFFICIENT_FUNDS
409 ACCOUNT_NOT_ELIGIBLE
503 SERVICE_UNAVAILABLE
```

14. OTP mailbox prerequisites.
15. Synthetic source/destination dataset.
16. Expected balance before/after từng scenario.

---

## 8. Handoff cho owner khác

### BE-1

- PIN verification boundary.
- OTP linkage/consume.
- Wrong attempt commit.
- PIN failure không rollback theo money failure.

### BE-2

- Account lock API.
- Pair lock order.
- Status/currency/balance snapshot.
- Block-vs-confirm setup.
- DB lock metric.

### BE-4

- Failure injector design.
- Testcontainers command/output.
- Financial invariant summary.
- Timing/flakiness limitations.
- Required evidence paths.

---

## 9. Definition of Done

- Dedicated tests tồn tại.
- Mỗi concurrency test bounded timeout.
- PostgreSQL/Testcontainers pass.
- Test hook chỉ bật test profile.
- FE nhận exact status/error/reconciliation rules.
- BE-4 review evidence.
- Không commit/push nếu chưa được yêu cầu.
