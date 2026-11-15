# BE-NOT-01 — Verification report

> **Trạng thái:** Component Verified / Integration Blocked
> **Task spec:** [BE-NOT-01-transactional-outbox.md](BE-NOT-01-transactional-outbox.md)
> **Implementer:** Thịnh
> **Ngày:** 15/11/2026 (Cập nhật sau hoàn thiện độc lập Phase 2)
> **Baseline:** `thinh` branch (`c570986` + Common baseline)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Payload masking & copy | `NotificationPayloadValidatorTest` (44 test cases in latest combined run) | Validate tested allowlisted fields and reject nested values | Tested cases pass; DTO copies top-level map only. No claim of complete PII scanning or deep immutability before validation | Component tested; limitations open |
| Worker transaction boundary | `NotificationTransactionalOutboxIT.workerRejectsActiveCallerTransaction` (Test 12) | `processBatch` từ chối caller transaction đang active bằng `IllegalStateException` trước khi claim/gateway; outbox row giữ nguyên `PENDING`; gọi ngoài transaction thành công | Test RED rồi GREEN trên disposable PostgreSQL Testcontainers; verify row không bị thay đổi và scheduler call chạy bình thường | Verified (Component Safety Guard) |
| Retry policy formula | `NotificationRetryPolicyTest` (3 unit tests) | Deterministic exponential backoff ($2^{k-1} \times \text{baseBackoff}$); max 64x multiplier; safe failure codes (`GATEWAY_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DELIVERY_EXHAUSTED`); không tràn số | 3 test cases pass; code thực tế triển khai exponential backoff; max attempts và lease duration là config proposals | Verified (Component) / Pending Policy Approval |
| Default gateway runtime safety | `NotificationConfigurationTest` (5 unit tests) & code inspection | Đã loại bỏ `"default"` và `"local"` khỏi cấu hình simulator tự động. Simulator gateway chỉ bật khi có explicit opt-in (profile `simulator` hoặc property `app.notification.simulator.enabled=true`). Worker bean kích hoạt conditional theo `SimulatedNotificationGateway.class`; khi thiếu gateway (cả simulator lẫn real provider), worker không được tạo (fail-closed, không bao giờ đánh dấu `SENT` khống). Khi có bean provider thật trong `prod`, worker kích hoạt với `isSimulator() == false`. | 5 test cases pass qua `ApplicationContextRunner` (default fail-closed, prod absent fail-closed, profile simulator opt-in, property opt-in, real provider prod `isSimulator() == false`) | Verified |
| T2 rollback | `NotificationTransactionalOutboxIT.testT2RollbackLeavesNoOutboxRows` | T2 rollback để lại zero notification rows | Transaction rollback để lại 0 rows trong DB | Verified |
| Claim concurrency | `NotificationTransactionalOutboxIT.testConcurrentBatchClaimSkipLocked` | Hai DB connections độc lập; `FOR UPDATE SKIP LOCKED` phân chia batch mutually exclusive; không tranh chấp duplicate lease | Row chỉ có 1 active lease; worker thứ hai nhận empty batch | Verified |
| Gateway failure isolation | `NotificationTransactionalOutboxIT.testT3WorkerGatewayFailureDoesNotAffectT2` | Transfer/ledger/audit không đổi; row retry/FAILED đúng policy | T3 failure cô lập hoàn toàn; T2 transfer state giữ nguyên; outbox row chuyển RETRYING | Verified |
| Lease recovery | `NotificationTransactionalOutboxIT.testLeaseExpiryAllowsReclaimByAnotherWorker` | Claim rồi dừng worker; expire lease theo DB time; worker khác reclaim | Row với expired lease được worker mới reclaim an toàn | Verified |
| Stale worker reject | `NotificationTransactionalOutboxIT.testStaleWorkerCannotOverwriteAfterLeaseExpired` | Stale worker gửi thành công sau khi lease hết hạn và bị reclaim không thể overwrite SENT | Update conditional on `claim_token` trả về 0 rows; ngăn chặn stale overwrite | Verified |
| Duplicate-send window | `NotificationTransactionalOutboxIT.testDuplicateSendWindowGatewayCrashBeforeSent` | Gateway fake success rồi crash trước mark SENT; retry có thể duplicate; money state không đổi | Chấp nhận duplicate-send window trong giới hạn at-least-once delivery | Verified |
| Retry terminal state | `NotificationTransactionalOutboxIT.retryReachesFailedAtMaxAttempts` (Test 7) | Backoff đúng; cuối cùng FAILED sau max attempts; failure code `DELIVERY_EXHAUSTED` | Chuyển trạng thái sang `FAILED` sau 3 lần retry thất bại | Verified |
| Crash/reclaim beyond maxAttempts | `NotificationTransactionalOutboxIT.repeatedCrashReclaimExceedingMaxAttemptsReachesTerminalFailed` (Test 13) | Worker crash lặp lại sau khi claim (không gọi markSent/failure), lease hết hạn, worker khác reclaim; khi `retry_count > maxAttempts`, worker chuyển ngay sang `FAILED` (`DELIVERY_EXHAUSTED`), không gọi gateway | Đã tái hiện RED và fix GREEN bằng TDD; bảo đảm chặn lặp vô hạn và bảo vệ terminal state `FAILED` | Verified |
| Polling latency & backlog age | Benchmark workload với sample size, timer precision và load đo lường | p50/p95/p99 có số liệu tin cậy; so sánh với SLO | Chưa đo lường chính thức (không dùng thời gian chạy Testcontainers/unit test làm latency claim); cần harness benchmark riêng | Not measured / Planned |
| OTP boundary | `NotificationPayloadValidatorTest` + Identity integration | OTP plaintext không được persist/log; auth outcome không báo success sai | Validator reject trường `otp` và keyword `otp`; caller integration thật chờ Đạt | Verified (Component) / Blocked (Integration) |
| Transfer caller integration | Tích hợp caller T2 từ Transfer module | `TransferNotice` được tạo và enqueue tự động trong transaction chuyển tiền | Caller chưa sẵn sàng; chờ Vũ | Blocked |
| Real Provider integration | Production SMS/Email provider HTTP adapters | Provider idempotency và real network delivery | Đang dùng `SimulatedNotificationGateway` trong dev/test profiles | Blocked |

## Review correction — 15/11/2026

- Retry: `retry_count` tăng khi claim. `>= maxAttempts` sau gateway failure và `> maxAttempts` trước dispatch tiếp theo nhất quán với giới hạn tối đa N claim/attempt; nhận xét review trước cho rằng hai điều kiện tự mâu thuẫn là sai. Crash trước send vẫn tiêu tốn claim; policy đó cần owner duyệt.
- Test `rejectsAccountLikeValuesInReferenceAndTemplateFields` pass trước thay đổi production: không coi đó là RED hoặc chứng minh generic PII scanner. Chưa đổi format reference/template trong payload để tránh giả định contract.
- Test `rejectsSensitiveTopLevelTemplateCode` RED vì `user@example.com` được chấp nhận; đã bổ sung kiểm tra top-level template code và fresh targeted suite pass. Kiểm tra này không chứng minh chặn mọi PII/secret; field format/DTO contract vẫn cần review.
- Fresh combined NOT/FRD/architecture run sau thay đổi: **102 tests, 0 failures, 0 errors, 0 skipped** (`--rerun-tasks`). NOT validator 44, retry 3, outbox IT 13, configuration 5; Fraud evaluator 16, review 10, flag IT 6; architecture 5. Không suy ra real caller/RBAC/provider integration.


## Evidence

- **Revision:** `thinh` branch (`c570986` + uncommitted component files)
- **Môi trường:** Local Docker PostgreSQL 16 Alpine via Testcontainers, Java 21, Spring Boot 3.3.5
- **Kiểm thử thực thi:**
  ```powershell
  & "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend\gradlew.bat" `
    -p "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend" `
    test `
    --tests '*NotificationConfigurationTest' `
    --tests '*NotificationPayloadValidatorTest' `
    --tests '*NotificationRetryPolicyTest' `
    --tests '*NotificationTransactionalOutboxIT' `
    --rerun-tasks `
    --no-daemon
  ```
- **Kết quả JUnit XML (Fresh run):**
  - `NotificationConfigurationTest`: 5 tests, 0 failures, 0 errors, 0 skipped
  - `NotificationPayloadValidatorTest`: 42 tests, 0 failures, 0 errors, 0 skipped
  - `NotificationRetryPolicyTest`: 3 tests, 0 failures, 0 errors, 0 skipped
  - `NotificationTransactionalOutboxIT`: 13 tests, 0 failures, 0 errors, 0 skipped
  - **Tổng Notification tests: 63 tests passed, 0 failures, 0 errors, 0 skipped.**

## Owner decisions

1. **Retry Policy & Crash/Reclaim Semantics:**
   - *Code thực tế:* `NotificationRetryPolicy` triển khai exponential backoff: $2^{\min(\max(0, \text{retryCount}-1), 6)} \times \text{baseBackoff}$.
   - *Semantics:* `retry_count` tăng khi claim batch. Nếu worker crash lặp lại và `retry_count > maxAttempts`, worker tự động chuyển sang `FAILED` (`DELIVERY_EXHAUSTED`) mà không gửi gateway để tránh vòng lặp crash poison pill.
   - *Cấu hình đề xuất:* Base 5s, max 3-5 lần thử, lease 30s; chờ Architecture team chốt cấu hình chính thức.
2. **Worker Transaction Boundary & Caller Safety:**
   - Worker sử dụng `PROPAGATION_REQUIRES_NEW` cho claim và status update, `PROPAGATION_NOT_SUPPORTED` cho gateway send.
   - `processBatch` hiện fail-fast bằng `IllegalStateException` nếu caller transaction đang active để ngăn việc giữ connection/locks của caller trong thời gian network I/O.
   - Ranh giới này là component safety guard, chưa phải production architecture decision; cần sự thống nhất của team khi viết scheduler T3.
3. **Default Gateway & Simulator Opt-In Convention:**
   - Đã loại bỏ hoàn toàn việc simulator tự bật ngầm trong default profile (`local`).
   - Bean simulator chỉ được đăng ký khi có explicit opt-in: profile `simulator` hoặc property `app.notification.simulator.enabled=true`.
   - `NotificationOutboxWorker` được đăng ký conditional `@ConditionalOnBean(SimulatedNotificationGateway.class)`: nếu không có gateway nào (cả simulator lẫn real provider), worker bean hoàn toàn không được khởi tạo (fail-closed, outbox rows giữ nguyên trạng thái an toàn, không có worker nào claim hay đánh dấu SENT khống).
   - Khi có bean provider thật trong production, worker kích hoạt bình thường và `isSimulator() == false`.
4. **Polling Latency Claim:**
   - Giữ trạng thái `Not measured / Planned` cho đến khi có harness benchmark tải chuyên dụng.
5. **Vũ (Transfer):** Duyệt DTO `TransferNotice`, trường dữ liệu nguồn/đích, thứ tự insert trong T2 (trước audit commit), và ngữ nghĩa notification khi chuyển tiền FAILED.
6. **Đạt (Identity):** OTP/challenge linkage & delivery boundary. Không persist/log OTP plaintext; transient handoff an toàn tới gateway còn cần thiết kế và phê duyệt.
7. **Phương (DB):** Cần review migration version, FK và lease extension. `claim_token`, `locked_until`, `claim_version` hiện là fixture proposal; chưa kết luận cả ba đều bắt buộc.

## Pre-commit verification — 15/11/2026

Fresh targeted combined `--rerun-tasks --no-daemon`: **100 tests, 0 failures, 0 errors, 0 skipped**. Scope: 5 NotificationConfigurationTest, 42 NotificationPayloadValidatorTest, 3 NotificationRetryPolicyTest, 13 NotificationTransactionalOutboxIT, 16 FraudRuleEvaluatorTest, 10 ReviewStateTransitionTest, 6 FraudFlagIT, 5 ModuleBoundaryArchTest. PostgreSQL tests dùng Testcontainers disposable. JUnit XML đọc ngay sau run; không coi đây là real caller/provider integration.

TDD: test currency `USD`, non-finite amount, negative amount/fee, invalid outcome/channel/transferId, and unmodifiable payload RED trước fix, GREEN sau validation bổ sung. Test `workerRejectsActiveCallerTransaction` (Test 12) và `repeatedCrashReclaimExceedingMaxAttemptsReachesTerminalFailed` (Test 13) đều verified pass. Test profile `NotificationConfigurationTest` (5 tests: default fail-closed, prod absent fail-closed, profile simulator opt-in, property opt-in, real provider prod isSimulator=false) verified pass.

`git diff --check` chỉ kiểm tracked diff, không kiểm 34 file mới untracked. Whitespace của các file untracked đã được quét độc lập (0 lỗi). Chưa claim pre-commit review toàn bộ untracked source/tests/fixtures hoặc production release.
