# Module 04: `transfer` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-04`
> **Module:** `com.simulatedbank.banking.transfer`
> **Owner chính:** **Vũ** (Phối hợp: Phương — `ledger`, Đạt — `account` & `identity`, Thịnh — `fraud`, `audit`, `notification`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 (W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-013..026), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.3, §5.4), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§5, §6, §11.4), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Toàn bộ `T0–T3`, Phụ lục A, B, C, D)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `transfer` là bộ điều phối trung tâm của luồng chuyển tiền nội bộ. Module quản lý máy trạng thái giao dịch (`PENDING -> COMPLETED / FAILED`), cưỡng chế **Idempotency (`Idempotency-Key`)** chống ghi nợ kép khi client retry hoặc mạng đứt giữa chừng, điều phối ranh giới giao dịch 4 pha (**`T0 -> T1 -> T2 -> T3`**), và vận hành tiến trình tự động đối soát/phục hồi các giao dịch bị kẹt ở trạng thái `PENDING`.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `transfer` sở hữu độc quyền 2 bảng:

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc & Bất biến |
|---|---|---|
| **`banking.transfer`** | - `transfer_id` (`PK`), `reference` (`UNIQUE NOT NULL`)<br>- `source_account_id`, `dest_account_id` (`CHECK source <> dest`)<br>- `initiated_by_user_id`, `idempotency_key`<br>- `amount` (`CHECK > 0`), `currency` (`'VND'`), `description`<br>- `status` (`CHECK IN ('PENDING','COMPLETED','FAILED')`)<br>- `failure_code`, `failure_reason`, `step_up_challenge_id`<br>- `correlation_id`, `created_at`, `completed_at` | - **Unique Constraint:** `UNIQUE (initiated_by_user_id, idempotency_key)`.<br>- **Terminal State Guard (`T-STATE-01`):** Một khi đã ở `COMPLETED` hoặc `FAILED` thì không bao giờ chuyển sang trạng thái khác. |
| **`banking.idempotency_record`** | - `idempotency_id` (`PK`), `actor_user_id`, `operation`, `idempotency_key`<br>- `request_hash` (`CHAR(64)` — SHA-256 của RFC 8785 JCS body)<br>- `status` (`CHECK IN ('RECEIVED','COMPLETED','FAILED')`)<br>- `response_status`, `response_body` (`JSONB`), `transfer_id`<br>- `created_at`, `expires_at` (`+24h`) | - **Unique Constraint:** `UNIQUE (actor_user_id, operation, idempotency_key)`.<br>- Cho phép phát lại (replay) nguyên vẹn response kèm header `Idempotent-Replayed: true`. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.4)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-TRF-01` | `POST /api/v1/transfers` | `CUSTOMER` | Khởi tạo lệnh chuyển tiền (bắt buộc có `Idempotency-Key` và `X-CSRF-Token`). Thực thi luồng `T0 -> T1 -> T2`. |
| `API-TRF-02` | `GET /api/v1/transfers/{reference}` | `CUSTOMER` (Owner) hoặc `STAFF` | Tra cứu trạng thái và biên nhận giao dịch theo mã `reference`. Kiểm tra quyền sở hữu tài khoản nguồn/đích đối với Customer. |

---

## 4. Kiến trúc Điều phối 4 Pha (`T0 -> T1 -> T2 -> T3`) & Xử lý Lỗi (`design/02`)

1. **Pha `T0` — Kiểm tra Idempotency & Điều kiện Step-up (Trước `T1`):**
   - Chuẩn hóa payload theo RFC 8785 JCS để tính `request_hash`.
   - Kiểm tra bảng `idempotency_record`:
     - Nếu `request_hash` khác với bản ghi đã lưu của cùng `Idempotency-Key` $\rightarrow$ ghi audit `SECURITY_IDEMPOTENCY_CONFLICT` (`C3`) và trả về `409 IDEMPOTENCY_KEY_CONFLICT`.
     - Nếu bản ghi đã ở `COMPLETED` hoặc `FAILED` với cùng `request_hash` $\rightarrow$ phát lại ngay `response_body` đã lưu kèm header `Idempotent-Replayed: true`.
   - Kiểm tra ngưỡng Step-up (`amount` vượt ngưỡng đơn lẻ hoặc luỹ kế ngày vượt ngưỡng `20,000,000 VND`): Nếu chưa có challenge đã `VERIFIED` $\rightarrow$ gọi `StepUpChallengePort` tạo challenge và trả về `428 STEP_UP_REQUIRED` (**tuyệt đối không** tạo dòng `transfer` hay `ledger_entry` nào ở bước này — `AC-033`).
2. **Pha `T1` — Ghi nhận Ý định Giao dịch (Short Transaction):**
   - Tiêu thụ Step-up challenge (`VERIFIED -> CONSUMED`).
   - Chèn `idempotency_record (status = 'RECEIVED')` (`ON CONFLICT DO NOTHING`) và chèn `transfer (status = 'PENDING')`.
   - Ghi Audit `TRANSFER_CREATED` (`C1` Fail-closed) và commit `T1`.
3. **Pha `T2` — Hạch toán Nguyên tử (Main Accounting Transaction):**
   - Thiết lập `SET LOCAL lock_timeout = '2s'`, `statement_timeout = '5s'`.
   - Khóa `SELECT ... FROM banking.transfer WHERE transfer_id = :id FOR UPDATE NOWAIT` (nếu đang bị luồng khác giữ khóa $\rightarrow$ trả về `409 IDEMPOTENCY_IN_PROGRESS` kèm `Retry-After: 2`).
   - Gọi tuần tự theo đúng thứ tự khóa toàn cục:
     1. `AccountBalancePort.lockTransferAccountsInOrder(sourceId, destId)` (`ORDER BY account_id ASC FOR UPDATE`).
     2. `LimitEvaluationPort.lockAndCheckLimitsInT2(customerId, sourceId, amount)` (`CUSTOMER` trước, `ACCOUNT` sau).
     3. `FraudEvaluationPort.evaluateTransferInT2(transfer)` (In-process, Savepoint Fail-open).
     4. `AccountBalancePort.debitConditional(...)` & `creditConditional(...)`.
     5. `LedgerPostingPort.postDoubleEntryInT2(...)` (ghi 2 dòng `DEBIT` & `CREDIT`).
     6. `LimitEvaluationPort.incrementUsageInT2(...)`.
     7. `NotificationOutboxPort.enqueueTransferNotificationsInT2(...)` (`status = 'PENDING'`).
     8. Cập nhật `transfer` và `idempotency_record` sang `COMPLETED` (`HTTP 201`) hoặc `FAILED` (`HTTP 422` khi vi phạm số dư/hạn mức/auto-block).
     9. Gọi `AuditModuleApi.recordInTransaction(...)` (`TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED`) ở thao tác DB cuối cùng trước khi commit `T2`.
   - **Nếu ghi Audit thất bại (`AC-034`) hoặc lỗi hệ thống:** Rollback toàn bộ `T2` (không trừ tiền, không có `ledger_entry`, `transfer` quay về `PENDING`), trả về `500 AUDIT_WRITE_FAILED` / `503`.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `TransferOrchestrator` (không gắn `@Transactional` ở hàm bao ngoài) điều phối tách biệt `T0`, `T1`, `T2`.
2. [ ] Hiện thực máy trạng thái `IdempotencyService` (New, Replay, Conflict, In-Progress, và Self-Healing Resume khi retry một giao dịch đang dừng ở `PENDING`).
3. [ ] Hiện thực `TransferReconciliationJob` chạy định kỳ quét các giao dịch `PENDING` quá hạn bằng `FOR UPDATE SKIP LOCKED` (`design/02` Pattern A.12).
4. [ ] Viết đầy đủ bộ kiểm thử chứng minh cho Demo **D1** (`T-CON-01..04`), **D2** (`T-IDEM-01..06`) và **D3** (`T-AUD-06`, `T-REC-01..04`).
