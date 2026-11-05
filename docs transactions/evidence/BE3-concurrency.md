# BE3 — Concurrency + Risk Evidence (CON-01..03, AC-07, AF-06)

> **Commit:** `fd3364cd3d8aa53f8820518bf2f57c011a747693` (nhánh `transactions`)
> **Thời điểm chạy:** 2026-11-01T09:36:40Z – 2026-11-01T09:38:17Z (16:36–16:38 giờ máy, Asia/Ho_Chi_Minh)
> **Môi trường:** JDK 21 (Temurin 21), Spring Boot 3.3.13, PostgreSQL 16 (`postgres:16-alpine`, Testcontainers per-class)
> **Lệnh:** `mvn -Dtest='TransferConcurrencyPostgresTest,TransferOtpRacePostgresTest,TransferReplayPostgresTest,TransferRiskIntegrationPostgresTest,TransferRiskFailureIsolationPostgresTest,TransferLargeTransferPostgresTest,TransferFailureInjectionPostgresTest' test`
> **Fresh output:** 29 tests / 0 failures / 0 errors (xem [BE3-otp-race §1](BE3-otp-race.md#1-kết-quả-batch-fresh-output)).

## 1. CON-01..03 — PostgreSQL thật, không sleep-sync

| ID | Setup | Source trước → sau | Destination trước → sau | Outcome | `transfers` | `idempotency_records` |
|---|---|---|---|---|---|---|
| CON-01 overdraft | source 1.000.000; 2 lệnh 600.000 song song, 2 key khác nhau | 1.000.000 → **400.000** | A+B: 0+0 → **600.000** (một dest nhận) | 1× `COMPLETED` + 1× `409 INSUFFICIENT_FUNDS` | **1** | không assert (2 key khác nhau) |
| CON-02 same key | source 10.000.000; 6 request song song cùng key + cùng payload 250.000 | 10.000.000 → **9.750.000** | 0 → **250.000** | **1× created + 5× replay**, tất cả cùng `transferId` | **1** | **1** |
| CON-02 same key, khác payload (AC-07) | source 10.000.000; 2 request song song cùng key, amount 100.000 vs 200.000 | — | 0 → **100.000 hoặc 200.000** | 1× success + **1× `409 IDEMPOTENCY_KEY_REUSED`**, không mutation từ request thua | **1** | **1** |
| CON-03 opposite-direction | A 1.000.000, B 1.000.000; A→B và B→A mỗi lệnh 100.000 song song | A: 1.000.000 → **1.000.000** | B: 1.000.000 → **1.000.000** | 2× `COMPLETED`, hoàn tất trong `Future.get(20s)` | **2** | không assert |

- CON-01: `SELECT count(*) FROM accounts WHERE balance < 0` = **0**; request thua rollback idempotency record cùng TX nên không có row mồ côi.
- CON-02: cả 6 outcome không lỗi; replay trả cùng body; balance chỉ đổi một lần.
- CON-03: lock hai account theo UUID ASC nên hai chiều ngược nhau không deadlock.

**Harness (dùng chung):** `CountDownLatch ready(n) + start(1)`, `ExecutorService(n)`, `Future.get(20s)`; không `Thread.sleep`, không H2. Nguồn: `be/src/test/java/com/bank/simulator/transfer/TransferConcurrencyPostgresTest.java` và `TransferOtpRacePostgresTest.java`.

**Kết luận:** AC-01, AC-02, AC-03, AC-07 pass trên DB thật; idempotency unique index + row lock balance là cơ chế bảo vệ, không dựa vào cache/in-memory.

## 2. Risk AFTER_COMMIT — LARGE_TRANSFER + HIGH_FREQUENCY (AF-06, UC-06)

**Fresh output:** `TransferRiskIntegrationPostgresTest` — **3 tests / 0 fail**, time 8.397s; `TransferRiskFailureIsolationPostgresTest` — **2 tests / 0 fail**, time 3.223s.

### 2.1 LARGE_TRANSFER

| Case | Setup | Kết quả |
|---|---|---|
| Large | source 10.000.000, transfer **6.000.000** → confirm OTP | transfer `COMPLETED`; `risk_flags`: **1 row**, `rule_id=LARGE_TRANSFER`, `rule_version=1`, `reason` non-blank, `source_account_id` = source thật của transfer |
| Boundary ≤5M | source 10.000.000, transfer **5.000.000** | transfer `COMPLETED`; `risk_flags` cho transfer này = **0 row** |

Rule hiện tại dùng ngưỡng `amount > 5.000.000`, version `"1"` (code risk hiện hữu; không sửa `risk/**`).

### 2.2 HIGH_FREQUENCY

Setup: source 10.000.000, destination 0; 6 transfer 100.000 hoàn tất liên tiếp từ cùng source (trong window 10 phút, `Clock` cố định — không sleep).

| Transfer | Flag |
|---|---|
| #1..#5 | **0** flag |
| #6 | **1** flag: `rule_id=HIGH_FREQUENCY`, `rule_version=1`, `source_account_id` = source |

Sau run: source **9.400.000**, destination **600.000**, `transfers` = **6**, `accounts WHERE balance < 0` = **0**. Flag chỉ cảnh báo, không chặn giao dịch (fan-out AC-08).

### 2.3 Risk failure isolation (AF-06)

`@MockBean RiskEvaluationService` + `doThrow`:

| Chỉ số | Kết quả |
|---|---|
| Response service | `COMPLETED`, `replayed=false` — không exception leak |
| Transfer row | `COMPLETED`, `completed_at` NOT NULL |
| Balances | source 10.000.000 → **9.900.000**; destination 0 → **100.000** |
| `audit_events` (`TRANSFER_COMPLETED`) | **1** |
| `risk_flags` | **0** (chỉ thiếu flag) |
| `verify(risk).evaluate(any())` | đã được gọi — listener AFTER_COMMIT chạy, exception bị catch/log |

Listener: `src/main/java/com/bank/simulator/risk/application/TransferCommittedRiskListener.java:19` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) + `catch (RuntimeException)` tại dòng 24; evaluation chạy trong transaction riêng `REQUIRES_NEW`, nên không rollback money TX.

### 2.4 Rollback không trigger risk

Hai lớp bằng chứng:

1. `TransferRiskFailureIsolationPostgresTest.af06TransferThatNeverCommitsDoesNotTriggerRiskEvaluation`: source 1.000.000, lệnh 2.000.000 → `409 INSUFFICIENT_FUNDS`; `verify(risk, never()).evaluate(any())`; `risk_flags` = 0; `transfers` = 0; balances **1.000.000 / 0**.
2. `TransferFailureInjectionPostgresTest.fi01ThrowAfterDebitRollsBackBalancesTransferAuditAndRisk`: throw sau debit → `risk_flags` = 0, `transfers` = 0, `audit_events` = 0 (chi tiết [BE3-rollback-timeout §2](BE3-rollback-timeout.md#2-fi-01--rollback-toàn-bộ-af-01)).

AFTER_COMMIT chỉ chạy khi money TX commit thành công — rollback không phát event cho listener.

### 2.5 Latency risk chậm hơn giao dịch

Risk chạy AFTER_COMMIT trong luồng response, transaction độc lập; failure không đổi outcome. Đo latency định lượng dưới tải **chưa có** trong phiên này — load test bị blocked (xem [BE3-handover §5](BE3-handover.md#5-load-test-r5--blocked-note)). Đây là hạng mục đo bổ sung khi có k6 + FE data, không phải bug chức năng.

## 3. Terminal guard — tổng hợp SM-01..03 (Task 5.2)

### 3.1 Guard trên mọi path ghi transfer

| Path | Câu lệnh guard | Test chứng minh |
|---|---|---|
| `complete` | `UPDATE ... WHERE id=? AND status='AWAITING_OTP'` (`TransferJdbcRepository.java:101`) | `terminalStatesAreNeverOverwrittenByCompleteExpireOrFail`; `compensationFailAfterCompletionTouchesNoRows`; `fi04ConfirmWinsBeforeCompensationAndCompensationIsANoop` |
| `expire` | `UPDATE ... WHERE id=? AND status='AWAITING_OTP'` (`TransferJdbcRepository.java:106`) | `terminalStatesAreNeverOverwrittenByCompleteExpireOrFail`; expiry tests |
| `fail` | `UPDATE ... WHERE id=? AND status='AWAITING_OTP'` (`TransferJdbcRepository.java:111`) | `terminalStatesAreNeverOverwrittenByCompleteExpireOrFail`; FI-03/FI-04 compensation tests |

### 3.2 Kết quả ma trận terminal

`terminalStatesAreNeverOverwrittenByCompleteExpireOrFail` tạo 3 fixture `COMPLETED`, `EXPIRED`, `FAILED(OTP_DISPATCH_FAILED)`, gọi lần lượt `complete` → `expire` → `fail` trên từng row:

- Status, `failure_code`, `completed_at`, `expires_at` **giữ nguyên** ở cả 3 fixture.
- `UPDATE transfers SET status='AWAITING_OTP'` trực tiếp trên row `FAILED`/`EXPIRED` bị CHECK constraint từ chối (`DataIntegrityViolationException`) — terminal không thể hồi sinh kể cả khi bỏ qua repository.

SM-01..03 (COMPLETED/FAILED/EXPIRED terminal) đã pass từ W2 và được chạy lại trong batch lớn (`TransferLargeTransferPostgresTest` 7/7, gồm `completedTerminalCannotBeUpdatedBackToAwaitingOtp`). Nguồn chi tiết: [Week2 §4.4](../Work/Week2.md).

## 4. Kết luận nhóm concurrency + risk

- CON-01..03 + AC-07 pass trên PostgreSQL thật; không negative balance, không duplicate debit, không deadlock.
- Risk AFTER_COMMIT gắn `LARGE_TRANSFER` và `HIGH_FREQUENCY` đúng rule/version; ≤5M không gắn LARGE_TRANSFER; flag không chặn giao dịch.
- Risk failure không ảnh hưởng response/money đã commit; rollback không trigger risk.
- Mọi path ghi trạng thái transfer đều có guard `status='AWAITING_OTP'`; terminal bất biến ở cả tầng repository và CHECK constraint.
