# BE-FRD-01 — Verification report

> **Dự án:** Digital Banking MVP; **Owner:** Thịnh  
> **Ngày:** 15/11/2026 (Cập nhật sau hoàn thiện độc lập Phase 2); **Trạng thái:** Component Verified / Integration Blocked
> **Spec:** [BE-FRD-01-rules-and-review.md](BE-FRD-01-rules-and-review.md)
> **Baseline:** `thinh` branch (`c570986` + Common baseline)

## Phân biệt các mức bằng chứng

Pure rules dùng fixture/fake chứng minh evaluator, không chứng minh transfer/DB query. Testcontainers chứng minh fraud persistence/locking. Chỉ transfer/limit integration thật chứng minh end-to-end money-path interaction.

| Check | Lệnh / thủ tục dự kiến | Expected | Actual | Status |
|---|---|---|---|---|
| Proposal review | Review với Vũ/Đạt | Scope/ownership/decision status rõ; Option B không tự thành MVP | Decision register và handoff doc đã tạo; chờ owner họp chốt | Verified (Doc) / Pending Review |
| Rule boundary unit | `FraudRuleEvaluatorTest` (16 tests) | Boundary/version/time results khớp fixture/proposal; so sánh `LARGE_VALUE` và `HIGH_FREQUENCY` | 16 test cases pass; `LARGE_VALUE` (`amount >= threshold`), `HIGH_FREQUENCY` (`>` và `>=`), time bounds, replay exclusion | Verified |
| Matched-value safety at persistence boundary | `FraudFlagIT.directInsertFlagRejectsUnsafeMatchedValue` (Test 5) | `FraudJdbcRepository.insertFlag(...)` trực tiếp chặn unmasked account (kể cả có space/hyphen), email, phone, nested maps/lists, sai kiểu dữ liệu amount/count (fractional, negative, NaN, infinity, overflow), invalid comparator, non-VND currency, và secrets; không echo raw value; zero rows inserted on failure | 12 kịch bản kiểm tra (vi phạm và hợp lệ) đều pass; ném ngoại lệ fail-closed trước khi ghi JSONB; xác nhận không có dòng rác nào vào DB; không echo PII/raw values; số tiền lớn 10,000,000 VND hợp lệ không bị false-positive | Verified |
| Reviewer authentication & ID guard | `FraudFlagIT.reviewFlagRejectsInvalidReviewerId` (Test 6) | `FraudReviewService.reviewFlag(...)` chặn reviewerId null, 0 hoặc âm | Throw `IllegalArgumentException` fail-fast; bảo đảm chỉ operator hợp lệ mới được cập nhật review | Verified |
| Review state machine & notes validation | `ReviewStateTransitionTest` (10 tests) | State transition đúng; terminal flag không thể review lại (409 Conflict); note max 500 ký tự và không chứa PII/secrets | 10 test cases pass; ném `FRAUD_FLAG_ALREADY_CLOSED`, reject unmasked PII/secrets trong note | Verified |
| Flag persistence & rollback | `FraudFlagIT.t2RollbackLeavesNoFraudFlag` (Test 1) | Insert `fraud_flag` trong T2; rollback transaction làm mất flag; commit giữ flag | Transaction rollback không lưu flag; commit lưu đúng flag | Verified |
| Unique constraint | `FraudFlagIT.uniqueFlagPerTransferAndRuleVersion` (Test 2) | Ràng buộc duy nhất trên `(transfer_id, rule_code, rule_version)` ngăn duplicate flag | Vi phạm ném `DataIntegrityViolationException` | Verified |
| Concurrent review & row locking | `FraudFlagIT.concurrentReviewOneTerminalWinner` (Test 3) | Hai admin review cùng lúc trên cùng 1 flag; cơ chế `FOR UPDATE NOWAIT` + `WHERE status IN ('OPEN', 'UNDER_REVIEW')` đảm bảo 1 winner và 1 conflict | Duy nhất 1 review terminal thành công; review thứ hai nhận `FRAUD_FLAG_ALREADY_CLOSED` (409) | Verified |
| Savepoint isolation primitive | `FraudFlagIT.savepointIsolationRollsBackSubtransaction` (Test 4) | Lỗi DB trong subtransaction rollback về savepoint; transaction chính tiếp tục bình thường | Chứng minh PostgreSQL savepoint primitive hoạt động đúng; chưa phải caller orchestration thật | Verified (DB Primitive) |
| UNUSUAL_PATTERN status | Code inspection `FraudRuleEvaluator.java` | Không tự động kích hoạt rule thử nghiệm khi chưa có policy | Rule `UNUSUAL_PATTERN` đang ở trạng thái Proposed/disabled trong MVP (không evaluate) | Verified (Disabled by default) |
| Caller query correctness | PostgreSQL integration với transfer/limit thật | Timezone, COMPLETED-only, replay exclusion, boundary và concurrent count đúng; ledger/limit invariants giữ | Caller query thuộc Transfer module chưa sẵn sàng; chờ Vũ | Blocked |
| Money integration | `FraudTransferIT` với transfer thật | Approved policy đúng; không double debit/usage; failure behavior đúng | Chưa tích hợp caller T2 thật; chờ Vũ | Blocked |
| Real RBAC integration | Security filter chain với Identity token thật | Enforce quyền `FRAUD_REVIEW` (Ops) và `FRAUD_READ` (Auditor) | Mock/fake test; chờ Đạt tích hợp identity principal | Blocked |
| C2 Audit integration | Review action ghi kèm audit log atomic | Review và append audit log trong cùng transaction | Review service hiện tại chưa gọi `AuditRecorder`; chờ tích hợp C2 | Blocked |

## Evidence và giới hạn

- **Revision:** `thinh` branch (`c570986` + uncommitted component files)
- **Môi trường:** Local Docker PostgreSQL 16 Alpine via Testcontainers, Java 21, Spring Boot 3.3.5
- **Kiểm thử thực thi:**
  ```powershell
  & "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend\gradlew.bat" `
    -p "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend" `
    test `
    --tests '*FraudRuleEvaluatorTest' `
    --tests '*ReviewStateTransitionTest' `
    --tests '*FraudFlagIT' `
    --rerun-tasks `
    --no-daemon
  ```
- **Kết quả JUnit XML (Fresh run):**
  - `FraudRuleEvaluatorTest`: 16 tests, 0 failures, 0 errors, 0 skipped
  - `ReviewStateTransitionTest`: 10 tests, 0 failures, 0 errors, 0 skipped
  - `FraudFlagIT`: 6 tests, 0 failures, 0 errors, 0 skipped
  - **Tổng Fraud tests: 32 tests passed, 0 failures, 0 errors, 0 skipped.**

## Fresh regression — 15/11/2026

Combined NOT/FRD/architecture `--rerun-tasks --no-daemon`: **102 tests, 0 failures, 0 errors, 0 skipped**; Fraud suites: evaluator 16, review 10, flag IT 6. PostgreSQL Testcontainers disposable. Remaining tests: Notification payload 44, configuration 5, retry 3, outbox IT 13 and architecture 5. This is component/regression evidence, not real caller/RBAC/C2 audit or production migration approval.

## Owner decisions

1. **Schema Baseline Compatibility (`fraud_flag`):**
   - Bảng `fraud_flag` tuân thủ 100% baseline `docs-common/04 §4.7.2` (không thêm cột extension `version`). Concurrency control sử dụng pessimistic lock `FOR UPDATE NOWAIT` kết hợp `WHERE status IN ('OPEN', 'UNDER_REVIEW')`.
2. **Quy tắc `UNUSUAL_PATTERN` & Snapshot Signature:**
   - `FraudTransferSnapshot` chỉ chứa 10 trường cần thiết (`transferId`, `customerId`, `sourceAccountId`, `destinationAccountId`, `amount`, `currency`, `occurredAt`, `windowTransactionCount`, `isReplay`, `correlationId`). Quy tắc `UNUSUAL_PATTERN` được giữ **disabled/proposed** trong MVP.
3. **Savepoint Test Evidence & Boundaries:**
   - Test `savepointIsolationRollsBackSubtransaction` trong `FraudFlagIT` chứng minh năng lực kỹ thuật của PostgreSQL/JDBC savepoint primitive trên Testcontainers khi connection/transaction còn usable. Chưa phải caller orchestration end-to-end trong real Transfer `@Transactional`.
   - Savepoint chỉ cứu lỗi rollbackable cục bộ; không tự động cứu connection loss hoặc DB failover (cần transaction-boundary recovery); không gọi mọi DB failure là fail-open-safe.
4. **C2 Audit Boundary:**
   - `FraudReviewService` chưa gọi `AuditRecorder`, do đó review action chưa thể tuyên bố atomic với C2 audit.
5. **Vũ (Transfer):** Cần chốt comparator cho `HIGH_FREQUENCY` (`>` hay `>=`), cửa sổ tính toán, và loại trừ replay.
6. **Phương (DB):** Phối hợp tạo Flyway migration chính thức cho `fraud_rule_config` và `fraud_flag` (chuẩn baseline 04).

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial fraud review proposal |
| 0.2 | 11/11/2026 | Added caller/query evidence boundaries |
| 0.3 | 12/11/2026 | Aligned Common context/error/config/security ownership |
| 0.4 | 14/11/2026 | Reconciled docs-common source priority, schema/API conflicts, rule proposals and owner decisions |
| 0.5 | 15/11/2026 | Triển khai 100% component độc lập; 30 tests pass |
| 0.6 | 15/11/2026 | Independent Review đính chính: baseline 04, UNUSUAL_PATTERN disabled, savepoint primitive |
| 0.7 | 15/11/2026 | Bổ sung validation fail-closed tại persistence boundary `FraudJdbcRepository.insertFlag` (chặn unmasked account, email, phone, nested structures, type mismatch); thêm test 5 trong `FraudFlagIT`; tổng 31 tests pass |
| 0.8 | 15/11/2026 | Bổ sung xác thực reviewerId hợp lệ trong `FraudReviewService.reviewFlag`; thêm test 6 trong `FraudFlagIT`; tổng 32 tests pass |
| 0.9 | 15/11/2026 | Làm sâu kiểm tra persistence boundary (chặn fractional count, negative/overflow, invalid comparator, non-VND currency; không echo raw value; kiểm chứng zero DB rows khi insert fail; kiểm chứng số tiền lớn 10M VND không bị false positive); cập nhật 97 fresh tests pass |
| 0.10 | 15/11/2026 | Cập nhật suite fresh run tổng hợp 100 tests pass (kết hợp Notification hardened opt-in 5 configuration tests); toàn bộ 32 Fraud tests giữ nguyên kết quả pass hoàn hảo |
