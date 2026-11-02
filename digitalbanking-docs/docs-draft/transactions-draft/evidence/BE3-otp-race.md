# BE3 — OTP Race Evidence (CON-04, CON-05, FI-04)

> **Commit:** `fd3364cd3d8aa53f8820518bf2f57c011a747693` (nhánh `transactions`)
> **Thời điểm chạy:** 2026-11-01T09:36:40Z – 2026-11-01T09:38:17Z (16:36–16:38 giờ máy, Asia/Ho_Chi_Minh)
> **Môi trường:** JDK 21 (Temurin 21), Spring Boot 3.3.13, PostgreSQL 16 (`postgres:16-alpine`, Testcontainers per-class)
> **Lệnh:** `mvn -Dtest='TransferConcurrencyPostgresTest,TransferOtpRacePostgresTest,TransferReplayPostgresTest,TransferRiskIntegrationPostgresTest,TransferRiskFailureIsolationPostgresTest,TransferLargeTransferPostgresTest,TransferFailureInjectionPostgresTest' test`
> **Nguyên tắc:** không H2, không sleep-sync; harness `CountDownLatch ready/start + ExecutorService + Future.get(20s)`; OTP dispatch điều khiển bằng Mockito `Answer` để cố định thứ tự race.

## 1. Kết quả batch (fresh output)

```text
[INFO] Running com.bank.simulator.transfer.TransferConcurrencyPostgresTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.45 s -- in com.bank.simulator.transfer.TransferConcurrencyPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferFailureInjectionPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.258 s -- in com.bank.simulator.transfer.TransferFailureInjectionPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferReplayPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.625 s -- in com.bank.simulator.transfer.TransferReplayPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferRiskFailureIsolationPostgresTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.223 s -- in com.bank.simulator.transfer.TransferRiskFailureIsolationPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferLargeTransferPostgresTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.03 s -- in com.bank.simulator.transfer.TransferLargeTransferPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferRiskIntegrationPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.397 s -- in com.bank.simulator.transfer.TransferRiskIntegrationPostgresTest
[INFO] Running com.bank.simulator.transfer.TransferOtpRacePostgresTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.93 s -- in com.bank.simulator.transfer.TransferOtpRacePostgresTest
[INFO] Results:
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Chi tiết từng test trong `TransferOtpRacePostgresTest` (surefire XML, cùng lần chạy):

```text
con04ConcurrentDuplicateConfirmDebitsOnceAndReplaysTheOther         time=1.400
con04ReplayAfterCompletionWithWrongOtpDoesNotCountAnAttempt        time=1.291
con05BlockBeforeConfirmFailsTransferAsTerminalWithoutMovingMoney    time=1.351
con05ConfirmBeforeBlockKeepsCompletedTransferAndBlockOnlyGuardsFutureTransfers time=1.695
con05ConcurrentBlockAndConfirmAlwaysLeaveAConsistentOutcome         time=1.281
fi04ConfirmWinsBeforeCompensationAndCompensationIsANoop             time=1.289
fi04CompensationWinsThenConfirmRejectsDeadOtpWithoutMovingMoney      time=0.964
```

## 2. CON-04 — Duplicate confirm (AC-04)

**Setup:** source balance **10.000.000**, destination **0**, amount **6.000.000** (large path), transfer `AWAITING_OTP`; 2 thread cùng confirm với cùng OTP đúng, release đồng thời.

| Chỉ số | Trước | Sau |
|---|---|---|
| Source balance | 10.000.000 | **4.000.000** |
| Destination balance | 0 | **6.000.000** |
| `transfers` rows | 1 | **1** (`COMPLETED`, `completed_at` NOT NULL) |
| `otp_challenges` rows | 1 | **1** (`consumed_at` NOT NULL) |
| `accounts WHERE balance < 0` | 0 | **0** |

**Outcome:** đúng **1× non-replay** + **1× replay** (`replayed=true`); cả hai cùng `transferId`; không còn `AWAITING_OTP`. Tổng tiền source+dest giữ nguyên 10.000.000.

**Biến thể OTP sai sau khi đã COMPLETED:** confirm lại với OTP sai → `replayed=true`, `attempts` **không tăng** (replay check đứng trước consume), challenge vẫn `consumed_at` NOT NULL, balances giữ 4.000.000 / 6.000.000, `transfers` = 1.

**Kết luận:** khóa transfer + kiểm tra `COMPLETED` trước khi chạm OTP đảm bảo debit/credit đúng một lần; duplicate confirm không tạo giao dịch thứ hai và không đốt attempt.

## 3. CON-05 — Block-vs-confirm (AC-05)

**Setup:** source **10.000.000**, destination **0**, amount **6.000.000**, transfer `AWAITING_OTP`. Block được thực hiện qua operation thật của module account: `AccountStatusService.change(operatorActor, accountId, "BLOCKED", reason)` (không UPDATE SQL trực tiếp).

| Hướng | Account status | Transfer | HTTP/Service outcome | Source / Dest | `otp_challenges` |
|---|---|---|---|---|---|
| Block trước confirm | `BLOCKED` | `FAILED`, `failure_code=ACCOUNT_NOT_ELIGIBLE` | `409 ACCOUNT_NOT_ELIGIBLE` | **10.000.000 / 0** | `consumed_at` NOT NULL |
| Confirm trước block | `BLOCKED` (sau đó) | `COMPLETED`, `completed_at` giữ nguyên | confirm `201`, block thành công | **4.000.000 / 6.000.000** | `consumed_at` NOT NULL |

- Block-first: `risk_flags` = **0**; transfers = 1; không có mutation balance.
- Confirm-first: block chỉ chặn lệnh tương lai — không đảo ngược giao dịch đã COMPLETED.
- **Race thật (2 thread):** luôn rơi vào một trong hai nhánh trên; nhánh block-thắng cho phép confirm trả `409 ACCOUNT_NOT_ELIGIBLE`, nhánh confirm-thắng giữ `COMPLETED`; sau race `accounts WHERE balance < 0` = **0**, challenge luôn consumed.

**Kết luận:** confirm re-validate trạng thái account dưới lock; terminal `FAILED(ACCOUNT_NOT_ELIGIBLE)` không di chuyển tiền. Block sau COMPLETED không rollback giao dịch đã xong. Phối hợp BE-A: dùng đúng operation `AccountStatusService` của module account (không có blocked-dependency trong test này).

## 4. FI-04 — Confirm-wins-before-compensation (AF-04)

**Harness:** `OtpSender` được mock bằng `Answer` có `started`/`release` latch: thread create bị giữ trong dispatch sau COMMIT; test điều khiển confirm và compensation theo từng thứ tự. Không sleep-sync.

| Thứ tự | Transfer | Debit/credit | Compensation | Confirm sau đó | Ghi chú |
|---|---|---|---|---|---|
| Confirm thắng | `COMPLETED`, `completed_at` NOT NULL | **đúng 1 lần**: 4.000.000 / 6.000.000 | no-op (`WHERE status='AWAITING_OTP'` không match) | — | create trả `503 SERVICE_UNAVAILABLE` sau đó; terminal không bị ghi đè |
| Compensation thắng | `FAILED`, `failure_code=OTP_DISPATCH_FAILED` | **không đổi**: 10.000.000 / 0 | thắng, challenge `invalidated_at` NOT NULL | `409 STATE_CONFLICT`, không chuyển tiền | OTP đã chết; không có COMPLETED |

Cả hai hướng: `transfers` = 1, không có negative balance, `failure_code` của COMPLETED không bị ghi đè.

**Kết luận:** guard `WHERE status='AWAITING_OTP'` biến compensation thành no-op an toàn khi confirm đã thắng; ngược lại compensation thắng thì OTP bị invalidate và confirm không thể chuyển tiền.

## 5. AT-01..03 — tham chiếu (fresh batch)

Các test expiry/OTP của W2 được chạy lại trong batch trên (`TransferLargeTransferPostgresTest` 7/7):

| ID | Test | Kết quả trong batch |
|---|---|---|
| AT-01 | `expiredTransferRejectsConfirmWithoutConsumingChallenge` | pass — `409 TRANSFER_EXPIRED`, challenge không bị consume |
| AT-02 | `lazyExpiryMarksReadsExpiredAndLateOtpCannotMoveMoney` | pass — đọc ra `EXPIRED` không persist; OTP muộn `409`, balances 10.000.000 / 0 |
| AT-03 | `wrongOtpAttemptsAreCommittedThenFifthFailsTransfer` | pass — attempt 1..4 commit dù HTTP 400; lần 5 `FAILED(OTP_ATTEMPTS_EXCEEDED)` + `409` |

Chi tiết gốc: [Week2 §4.4](../Work/Week2.md).

## 6. Kết luận nhóm OTP race

- CON-04, CON-05, FI-04 đều pass trên PostgreSQL thật, không H2, không sleep-sync.
- Không có negative balance, không duplicate debit, terminal state không bị ghi đè.
- Điều kiện tiên quyết đã chứng minh: lock order transfer → challenge → accounts ASC; replay COMPLETED trước consume OTP; re-validate account dưới lock; compensation có guard theo status.
