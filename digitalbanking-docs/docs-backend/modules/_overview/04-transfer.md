# Module 04: `transfer` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-04`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.transfer`
> **Owner chính:** **Vũ** (Phối hợp: Phương — `ledger`, Đạt — `account` & `identity`, Thịnh — `fraud`, `audit`, `notification`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 (W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-013..030), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.4.1, §4.4.3), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§5, §6, §8.3, §8.4), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Toàn bộ `T0–T3`), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§8, §12.3, §13.4), [[DECISIONS.md]] (`DEC-TRF-001..019`, `DEC-LED-002`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `transfer` là bộ điều phối trung tâm của luồng chuyển tiền nội bộ và truy vấn lịch sử giao dịch. Module quản lý máy trạng thái giao dịch (`PENDING -> COMPLETED / FAILED`), cưỡng chế **Idempotency (`Idempotency-Key`)** chống ghi nợ kép khi client thử lại hoặc mất kết nối giữa chừng, điều phối ranh giới giao dịch 4 pha (**`T0 -> T1 -> T2 -> T3`**), cung cấp truy vấn lịch sử giao dịch (`API-TXN-01/02` theo `DEC-LED-002`) và vận hành tiến trình tự động đối soát/phục hồi hai pha (`TransferRecoveryJob`) cho các giao dịch bị treo ở trạng thái `PENDING`.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `transfer` sở hữu độc quyền 2 bảng (`04. Database design & analysis.md` §4.4.1 & §4.4.3, migration `V7`):

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc & Bất biến |
|---|---|---|
| **`banking.transfer`** | - `transfer_id BIGINT IDENTITY PK`, `reference_no VARCHAR(32) UNIQUE NOT NULL`<br>- `customer_id`, `initiated_by_user_id`, `source_account_id`, `destination_account_id`, `amount NUMERIC(19,2) CHECK (> 0)`, `currency CHAR(3) ('VND')`, `description`<br>- `status IN ('PENDING','COMPLETED','FAILED')`, `failure_code`, `step_up_challenge_id` (partial unique), `correlation_id`, `created_at`, `completed_at`, `updated_at`<br>- **Các cột Fencing & Recovery (`DEC-TRF-013/014`):** `attempt_epoch BIGINT NOT NULL DEFAULT 0`, `attempt_token VARCHAR(64)`, `attempt_owner VARCHAR(64)`, `attempt_lease_expires_at TIMESTAMPTZ`, `attempt_state IN ('NONE','CLAIMED','MANUAL')`, `recovery_attempt_count INT NOT NULL DEFAULT 0`, `recovery_first_attempt_at TIMESTAMPTZ`, `recovery_next_attempt_at TIMESTAMPTZ` | - `source_account_id <> destination_account_id`, `amount > 0`.<br>- **Terminal State Guard (`T-STATE-01`):** một khi đã chuyển sang `COMPLETED` hoặc `FAILED` thì bất biến, không bao giờ chuyển sang trạng thái khác.<br>- Token fencing (`attempt_epoch` / `attempt_token`) ngăn chặn worker cũ hoặc tiến trình bị treo ghi đè kết quả. |
| **`banking.idempotency_record`** | - `idempotency_key VARCHAR(255) PK` (khóa toàn cục)<br>- `transfer_id BIGINT NOT NULL UNIQUE FK -> transfer`<br>- `request_hash CHAR(64) NOT NULL` (SHA-256 của JCS payload)<br>- `status IN ('RECEIVED','IN_PROGRESS','COMPLETED','FAILED')`<br>- `response_status SMALLINT`, `response_body JSONB` (đã che dấu PII)<br>- `created_at`, `completed_at`, `expires_at`<br>- **Các cột TTL Snapshot (`DEC-TRF-013`):** `ttl_t1_seconds INT NOT NULL`, `ttl_t1_config_version VARCHAR(128) NOT NULL`, `ttl_terminal_seconds INT`, `ttl_terminal_config_version VARCHAR(128)` | - Một `idempotency_key` toàn cục gắn với tối đa một `transfer_id`; kiểm tra khớp `initiated_by_user_id`, `customer_id` và `request_hash` trước khi replay.<br>- Thời hạn lưu trữ mặc định/tối thiểu **24 giờ (`86400 giây`)** tính từ `completed_at` (`expires_at = GREATEST(expires_at, completed_at + ttl_terminal_seconds)`); tuyệt đối không xóa (`purge`) bản ghi của giao dịch còn `PENDING` (`DEC-TRF-005`). |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.3, §8.4)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-TRF-01` | `POST /api/v1/transfers` | `CUSTOMER` (`TRANSFER_CREATE`) | Khởi tạo lệnh chuyển tiền (bắt buộc có `Idempotency-Key` và `X-CSRF-Token` khi dùng cookie). Thực thi luồng `T0 -> T1 -> T2`; trả về `201 Created` (`TransferResponse`) hoặc mã lỗi theo catalog (`409`, `422`, `428`). |
| `API-TRF-02` | `GET /api/v1/transfers/{reference}` | `CUSTOMER` (`TRANSACTION_READ_SELF`, chủ TK nguồn/đích) hoặc `STAFF` (`TRANSACTION_READ`) | Tra cứu trạng thái và biên nhận giao dịch theo mã `reference`. Trường `fraudFlags` chỉ trả về cho nhân viên có quyền `FRAUD_READ` hoặc `FRAUD_REVIEW` (`DEC-TRF-012`). |
| `API-TXN-01` | `GET /api/v1/transactions` | `CUSTOMER` (`TRANSACTION_READ_SELF`) hoặc `STAFF` (`TRANSACTION_READ`) | Tra cứu lịch sử giao dịch có phân trang/lọc (`status`, `direction`, `accountId`, `from`–`to`, `minAmount`–`maxAmount`) từ bảng `transfer` kết hợp thông tin tài khoản che dấu từ `account` (`DEC-LED-002`). |
| `API-TXN-02` | `GET /api/v1/transactions/{reference}` | `CUSTOMER` (`TRANSACTION_READ_SELF`) hoặc `STAFF` (`TRANSACTION_READ`) | Xem chi tiết giao dịch trong lịch sử theo mã `reference` (`DEC-LED-002`). |

---

## 4. Kiến trúc Điều phối 4 Pha (`T0 -> T1 -> T2 -> T3`) & Xử lý Lỗi (`design/02`, `DEC-TRF-001..019`)

1. **Pha `T0` — Kiểm tra Idempotency & Điều kiện Step-up (Trước `T1`):**
   - Chuẩn hóa payload theo RFC 8785 JCS để tính `request_hash` (`TransferRequestHash`).
   - Tra cứu `idempotency_record` trên primary **trước** khi phân giải tài khoản:
     - Khác `request_hash` hoặc khác người dùng/khách hàng $\rightarrow$ ghi audit `SECURITY_IDEMPOTENCY_CONFLICT` (`C3`) và trả `409 IDEMPOTENCY_CONFLICT`.
     - Cùng actor/hash và đã kết thúc (`COMPLETED`/`FAILED`) $\rightarrow$ phát lại nguyên mã HTTP gốc (`201`/`422`) và body đã lưu kèm header `Idempotent-Replayed: true`.
     - Cùng actor/hash nhưng chưa kết thúc (`RECEIVED`/`IN_PROGRESS`) $\rightarrow$ trả ngay `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After` (`DEC-TRF-004`).
   - Lấy `customer step-up guard` (`Limit.lockStepUpGuard`) và kiểm tra ngưỡng Step-up (`amount >= 10.000.000 VND` hoặc tổng dự kiến ngày `> 20.000.000 VND` gồm `COMPLETED` trong ngày + mọi `PENDING` reservation còn hiệu lực + lệnh hiện tại theo `DEC-TRF-001/002/008`): nếu chưa có challenge `VERIFIED` hợp lệ $\rightarrow$ gọi `StepUpChallengePort.requestChallenge` và trả `428 STEP_UP_REQUIRED` mà không tạo `transfer`, `idempotency_record`, `ledger_entry` hay `limit_reservation`.
2. **Pha `T1` — Ghi nhận Ý định Giao dịch Bền vững (`REQUIRES_NEW`, `READ_COMMITTED`):**
   - Lấy `customer step-up guard` và kiểm tra lại `idempotency_key`.
   - Chèn `transfer (status = 'PENDING', step_up_challenge_id = NULL)` trước để thỏa FK, sau đó chèn `idempotency_record (status = 'RECEIVED', ttl_t1_seconds, ttl_t1_config_version)` (`ON CONFLICT DO NOTHING RETURNING`); nếu thua unique thì rollback toàn bộ `T1` rồi đọc lại ngoài transaction.
   - Tạo bản ghi giữ chỗ `Limit.reserveForPendingTransfer`.
   - Nếu có step-up: gọi `StepUpChallengePort.consumeVerified(challengeRef UUID)` (`status = 'VERIFIED'`, đặt `consumed_at = clock_timestamp()`), nhận về `challengeId (BIGINT)` và cập nhật `transfer.step_up_challenge_id` (`DEC-TRF-017`).
   - Flush thay đổi và ghi audit `TRANSFER_CREATED` (`C1` fail-closed) ở bước cuối cùng trước khi commit `T1`.
3. **Pha `T2` — Hạch toán Nguyên tử (`READ_COMMITTED`, `lock_timeout = 2s`, `statement_timeout = 5s`):**
   - Tuân thủ nghiêm ngặt thứ tự khóa toàn cục (`DEC-TRF-008`):
     1. `Limit.lockStepUpGuard(customerId)`.
     2. Khóa dòng `transfer` (`FOR UPDATE NOWAIT`) và kiểm tra fencing token (`attempt_epoch`/`attempt_token`).
     3. `AccountBalance.lockTransferAccounts(sourceId, destId)` (`ORDER BY account_id ASC FOR UPDATE`).
     4. Chụp mốc thời gian kết thúc logic `terminal_at = clock_timestamp()` từ primary, quy đổi ngày nghiệp vụ `Asia/Ho_Chi_Minh`, rồi gọi `Limit.lockAndCheckLimitsInT2(customerId, sourceId, amount, businessDate)` (`CUSTOMER` trước, `ACCOUNT` sau).
     5. `FraudEvaluationPort.evaluateTransferInT2(snapshot)` (In-process, ngân sách 1 giây, cô lập lỗi bằng JDBC Savepoint fail-open).
     6. `AccountBalance.debitConditional(...)` & `AccountBalance.credit(...)`.
     7. `LedgerPosting.postDoubleEntry(...)` (ghi 2 dòng `seq 1 DEBIT` & `seq 2 CREDIT`, kiểm tra đồng bộ cặp bút toán trước pha audit theo `DEC-TRF-009`).
     8. `Limit.settleOrReleaseReservation(...)` (chốt sang `limit_usage` đúng một lần khi `COMPLETED`, hoặc giải phóng reservation khi `FAILED`).
     9. `NotificationOutboxPort.enqueueTransferNotificationsInT2(...)` (`status = 'PENDING'`).
     10. Cập nhật `transfer` và `idempotency_record` sang `COMPLETED` (`HTTP 201`) hoặc `FAILED` (`HTTP 422`), cập nhật `ttl_terminal_seconds` và `expires_at`.
     11. Flush toàn bộ thay đổi nghiệp vụ trước khi lấy khóa audit; gọi `AuditModuleApi.recordInTransaction` tuần tự cho từng cờ `FRAUD_FLAG_CREATED` (`C2`) theo `flag_id ASC`, sau đó ghi `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED` (`C1`) ở bước cuối cùng. Mọi lỗi ghi audit `C1`/`C2` đều làm rollback toàn bộ `T2` (`DEC-TRF-007`).
4. **Đối soát & Phục hồi Giao dịch `PENDING` (`TransferRecoveryJob` — `DEC-TRF-006/010/014`):**
   - Chạy khi khởi động và định kỳ mỗi **60 giây**, quét các giao dịch `PENDING` có tuổi $\ge 300\text{ giây}$ theo giao thức 2 pha (Pha 1: chiếm quyền xử lý dưới customer guard + `SKIP LOCKED` và commit `attempt_epoch`/`attempt_token`/lease; Pha 2: kiểm tra bằng chứng trên `Account`, `Ledger`, `Limit`, `Audit` để hoàn tất `TRANSFER_RECONCILED`, chạy lại `T2` nếu chưa phát sinh hiệu ứng tiền tệ, hoặc chuyển `MANUAL` + phát cảnh báo khi bằng chứng lệch).

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Chốt hợp đồng giao tiếp liên module v1 (`05` §13.4), công thức tổng dự kiến ngày, ngưỡng Step-up, chính sách TTL/replay và giao thức phục hồi 2 pha (`BE-TRF-01`, `DEC-TRF-001..019`).
2. [x] Hiện thực domain model, value objects, SQL primitives và JPA/Flyway candidate cho `transfer` và `idempotency_record` (`BE-TRF-02`).
3. [x] Hiện thực HTTP boundary candidate (`TransferController`, `TransferCreateInput`, `TransferCreateService`, `TransferQueryService`, `SecurityDenyAudit`) (`BE-TRF-03`).
4. [x] Hiện thực `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy`, `IdempotencyRetention`, `TransferReferenceGenerator`, `TransferPreflightCandidate`, `TransferIntentCandidate` và `TransferCreateOrchestrator` (`BE-TRF-04`).
5. [x] Hiện thực `TransferSettlementCandidate` (`T2`), `TransferRecoveryCandidate` / `TransferRecoveryJob` (`BE-TRF-05`, `BE-TRF-06`) cùng tích hợp telemetry/metrics/outbox mapping (`BE-TRF-07`) với 163 tests Transfer PASS.
6. [ ] Đánh số migration `V7` theo chuỗi `DEC-COM-001`, ghép nối các provider thật (`account`, `limit`, `ledger`, `identity`, `audit`, `fraud`, `notification`), kích hoạt bean production và chạy bộ kiểm thử nghiệm thu `D1`–`D5` (`BE-TRF-08`, `BE-TRF-09`).
