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
| **`banking.transfer`** | - `transfer_id` (`PK`), `reference_no` (`UNIQUE NOT NULL`)<br>- source/destination account, initiator/customer, amount, currency, description, status và timestamp theo `04` §4.4.1 | - Source khác destination, amount dương.<br>- **Terminal State Guard (`T-STATE-01`):** một khi `COMPLETED` hoặc `FAILED` thì không chuyển trạng thái. |
| **`banking.idempotency_record`** | - `idempotency_key` global `PK`; `transfer_id` NOT NULL UNIQUE/FK<br>- `request_hash` SHA-256 JCS, `status`, response status/body đã mask, `created_at`, `completed_at`, `expires_at` | - Một key global → tối đa một transfer; đối chiếu actor và hash trước replay/conflict.<br>- TTL mặc định/tối thiểu 24 giờ từ `completed_at` được lưu; không purge PENDING. Record terminal còn tồn tại thì replay status/body gốc dù hết TTL (DEC-TRF-005). |

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
     - Khác hash hoặc actor của key global $\rightarrow$ audit C3 và trả `409 IDEMPOTENCY_CONFLICT`, không lộ dữ liệu actor khác.
     - Cùng actor/hash và terminal $\rightarrow$ replay status/body gốc `201`/`422`, header `Idempotent-Replayed: true`; chưa terminal $\rightarrow$ `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`, worker nội bộ xử lý (DEC-TRF-004).
   - Kiểm tra ngưỡng Step-up mặc định (`amount >= 10,000,000 VND` hoặc tổng chuyển dự kiến ngày `> 20,000,000 VND`, theo DEC-TRF-001/002; các trigger rủi ro khác vẫn độc lập): nếu chưa có challenge `VERIFIED` $\rightarrow$ yêu cầu challenge và trả `428 STEP_UP_REQUIRED` trước T1 theo `AC-033`. Nhánh 428 không tạo transfer/idempotency/ledger; request đồng thời khác cùng key có thể commit trước response, retry vẫn kiểm idempotency. Guard/reservation theo `design/05` §10.1 còn cần provider review và PostgreSQL test.
2. **Pha `T1` — Ghi nhận Ý định Giao dịch (Short Transaction):**
   - Tiêu thụ Step-up challenge (`VERIFIED -> CONSUMED`).
   - Insert `transfer (status = 'PENDING')` trước để thỏa FK, sau đó insert `idempotency_record (status = 'RECEIVED')` (`ON CONFLICT DO NOTHING`); request thua unique phải rollback toàn T1 rồi đọc lại ngoài transaction.
   - Ghi reservation và Audit `TRANSFER_CREATED` (`C1` fail-closed) cùng transaction; `expires_at = created_at + TTL`; commit `T1`.
3. **Pha `T2` — Hạch toán Nguyên tử (Main Accounting Transaction):**
   - Thiết lập `SET LOCAL lock_timeout = '2s'`, `statement_timeout = '5s'`.
   - Đọc context bất biến, lấy guard customer theo contract rồi lock transfer và revalidate; HTTP retry record chưa terminal trả 409, không tự chạy T2. Chi tiết guard và lock order tại `design/05` §10.1 cần test PostgreSQL.
   - Gọi tuần tự theo đúng thứ tự khóa toàn cục:
     1. `AccountBalancePort.lockTransferAccountsInOrder(sourceId, destId)` (`ORDER BY account_id ASC FOR UPDATE`).
     2. `LimitEvaluationPort.lockAndCheckLimitsInT2(customerId, sourceId, amount)` (`CUSTOMER` trước, `ACCOUNT` sau).
     3. `FraudEvaluationPort.evaluateTransferInT2(transfer)` (In-process, Savepoint Fail-open).
     4. `AccountBalancePort.debitConditional(...)` & `creditConditional(...)`.
     5. `LedgerPostingPort.postDoubleEntryInT2(...)` (ghi 2 dòng `DEBIT` & `CREDIT`).
     6. Limit settle/release reservation và usage chính xác một lần cùng transaction; không increment kép.
     7. `NotificationOutboxPort.enqueueTransferNotificationsInT2(...)` (`status = 'PENDING'`).
     8. Cập nhật `transfer` và `idempotency_record` sang `COMPLETED` (`HTTP 201`) hoặc `FAILED` (`HTTP 422` khi vi phạm số dư/hạn mức/auto-block).
     9. Flush business writes trước audit lock; ghi tuần tự C2 fraud flag theo thứ tự ổn định, rồi C1 `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED` qua Audit `recordInTransaction` trong cùng T2; lỗi bắt buộc rollback toàn T2 (DEC-TRF-007).
   - **Nếu audit/lỗi hệ thống đã xác nhận rollback T2:** không có money/ledger từ T2, T1 vẫn PENDING; retry an toàn theo contract. Kết quả commit chưa rõ phải kiểm tra primary, không tự suy đoán rollback/terminal; alert/reconcile. Credit failure được xác nhận: rollback money và finalize FAILED an toàn theo DEC-TRF-003, dùng savepoint còn lock hoặc fencing bền; thiếu bằng chứng/fence giữ trạng thái thực tế và alert.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `TransferOrchestrator` (không gắn `@Transactional` ở hàm bao ngoài) điều phối tách biệt `T0`, `T1`, `T2`.
2. [ ] Hiện thực `IdempotencyService` (New, Replay, Conflict, In-Progress); HTTP retry nonterminal trả 409, recovery nội bộ độc lập xử lý PENDING.
3. [ ] Hiện thực `TransferReconciliationJob` chạy định kỳ quét các giao dịch `PENDING` quá hạn bằng `FOR UPDATE SKIP LOCKED` (`design/02` Pattern A.12).
4. [ ] Viết đầy đủ bộ kiểm thử chứng minh cho Demo **D1** (`T-CON-01..04`), **D2** (`T-IDEM-01..06`) và **D3** (`T-AUD-06`, `T-REC-01..04`).
