# Module 05: `ledger` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-05`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.ledger`
> **Owner chính:** **Phương** (Phối hợp: Vũ — `transfer`, Đạt — `account`, Thịnh — `audit`)
> **Mốc tập trung:** **Tuần 4 (W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-019, FR-020, FR-022, FR-034, AC-007, AC-008, AC-012, AC-013), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.4.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §8.4), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.4), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §10), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§9, §12.3, §13.4), [[DECISIONS.md]] (`DEC-LED-001..005`, `DEC-TRF-009`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `ledger` quản lý sổ cái bút toán kép bất biến (**Append-Only Double-Entry Sub-Ledger**) cho toàn bộ các giao dịch chuyển tiền thành công trong hệ thống. Module đảm bảo mỗi giao dịch chuyển tiền hoàn tất (`COMPLETED`) trong pha `T2` luôn sinh ra đúng 2 dòng bút toán đối ứng (`entry_seq = 1` ghi nợ `DEBIT` tài khoản nguồn và `entry_seq = 2` ghi có `CREDIT` tài khoản đích) với tổng đại số bằng `0`, kiểm tra đồng bộ tính toàn vẹn của cặp bút toán ngay từ cơ sở dữ liệu trước khi chuyển sang pha kiểm toán cuối, và cung cấp bằng chứng bút toán (`LedgerEvidence`) phục vụ tiến trình đối soát/phục hồi giao dịch.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `ledger` là chủ sở hữu ghi duy nhất của bảng **`banking.ledger_entry`** (`04. Database design & analysis.md` §4.4.2, migration `V8` theo `DEC-COM-001`):

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bất biến Sổ cái |
|---|---|---|
| `entry_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh duy nhất của từng dòng bút toán sổ cái. |
| `transfer_id` | `BIGINT NOT NULL FK -> transfer` | Liên kết tới giao dịch chuyển tiền gốc (`ON DELETE RESTRICT`). |
| `account_id` | `BIGINT NOT NULL FK -> account` | Tài khoản chịu tác động của dòng bút toán (`ON DELETE RESTRICT`). |
| `entry_seq` | `SMALLINT NOT NULL CHECK (entry_seq IN (1, 2))` | Thứ tự bút toán trong cặp: `1` cho dòng `DEBIT` tài khoản nguồn, `2` cho dòng `CREDIT` tài khoản đích. |
| `entry_type` | `VARCHAR(8) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT'))` | Loại bút toán; ràng buộc `CHECK ((entry_seq = 1 AND entry_type = 'DEBIT') OR (entry_seq = 2 AND entry_type = 'CREDIT'))`. |
| `amount` | `NUMERIC(19,2) NOT NULL CHECK (amount > 0)` | Số tiền hạch toán dương, đúng 2 chữ số thập phân; khớp tuyệt đối giữa 2 dòng `DEBIT` và `CREDIT`. |
| `currency` | `CHAR(3) NOT NULL CHECK (currency = 'VND')` | Đơn vị tiền tệ (`VND`). |
| `balance_after` | `NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0)` | Số dư sổ cái (`ledger_balance`) của tài khoản ngay sau khi áp dụng bút toán (do module `account` trả về trong `T2`). |
| `correlation_id` | `VARCHAR(64) NOT NULL` | Mã truy vết đầu-cuối kế thừa từ giao dịch chuyển tiền gốc. |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` | Thời điểm ghi nhận bút toán (UTC). |

- **Ràng buộc cặp bút toán duy nhất:** `UNIQUE (transfer_id, entry_seq)` (`uq_ledger_transfer_seq`).
- **Bảo vệ Append-Only tuyệt đối (`DEC-LED-004/005`):** Tài khoản runtime `banking_app` chỉ được cấp quyền `SELECT, INSERT` (thu hồi `UPDATE, DELETE, TRUNCATE`), kết hợp trigger mức câu lệnh trên PostgreSQL chặn mọi thao tác sửa/xóa với mã lỗi SQLSTATE `55000`.
- **Không ghi bút toán cho giao dịch thất bại:** Các giao dịch ở trạng thái `PENDING` (chưa chạy `T2`) hoặc kết thúc ở trạng thái `FAILED` có `0` dòng trong `banking.ledger_entry`.

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.4, `DEC-LED-002`)

Theo quyết định kiến trúc **`DEC-LED-002`**, hai endpoint tra cứu lịch sử giao dịch của khách hàng và nhân viên:
- `API-TXN-01` (`GET /api/v1/transactions`)
- `API-TXN-02` (`GET /api/v1/transactions/{reference}`)

được phục vụ bởi tầng truy vấn tổng hợp của module **`transfer`** (kết hợp thông tin quyền sở hữu và số tài khoản che dấu từ module `account`), bởi vì lịch sử giao dịch trên API bao gồm cả các giao dịch `PENDING` và `FAILED` vốn không có bản ghi trong `ledger_entry`. Module `ledger` không mở REST Controller công khai riêng mà tập trung cung cấp các Internal Ports cho hạch toán `T2` và đối soát.

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §9, §13.4)

### 4.1. Ports cung cấp cho các module khác gọi (`ledger.api`)
- **`LedgerPosting`** (`com.simulatedbank.banking.ledger.api.LedgerPosting` — `TRFC-LE-01`, `DEC-LED-001/005`):
  - `postDoubleEntry(PostingCommand command) -> PostingRef(long debitEntryId, long creditEntryId)`:
    - Chạy bắt buộc bên trong giao dịch `T2` của `transfer` với `@Transactional(propagation = Propagation.MANDATORY)`.
    - Kiểm tra nghiêm ngặt `transferId > 0`, `sourceAccountId != destinationAccountId`, `amount > 0` (`scale <= 2`), `sourceBalanceAfter >= 0`, `destinationBalanceAfter >= 0`, `currency = 'VND'` và `correlationId` hợp lệ trước khi ghi.
    - Chèn 2 dòng bút toán (`seq 1 DEBIT` nguồn, `seq 2 CREDIT` đích), thực hiện `flush()`, sau đó đọc lại trực tiếp từ cơ sở dữ liệu bằng truy vấn native scalar projection (`ORDER BY entry_seq, entry_id`) để `PairVerifier` kiểm chứng đồng bộ cả cặp bút toán trước khi trả về `PostingRef` (`DEC-TRF-009`, `DEC-LED-005`). Nếu thiếu transaction, vi phạm unique/FK hoặc lệch dữ liệu đọc lại, ném ngoại lệ để rollback toàn bộ `T2`.
- **`LedgerQuery`** (`TRFC-LE-02`, `DEC-LED-001`):
  - `findEvidence(long transferId, SystemActorContext systemContext) -> Optional<LedgerEvidence>`:
    - Đọc trên primary trong giao dịch đối soát/phục hồi của `transfer` (`BE-LED-06`), trả về danh sách các dòng bút toán thực tế gắn với `transferId` để phân biệt rõ trường hợp chưa có bút toán, đã có đủ cặp hợp lệ, hay bị lệch bất thường.

### 4.2. Ports của module khác mà `ledger` phối hợp
- **`AccountReconciliationQuery.readOpeningBalance`** (của module `account` — `DEC-LED-003`): Cung cấp số dư mở sổ đầu kỳ (`OpeningBalance`) có nguồn gốc kiểm toán trước bút toán đầu tiên để job đối soát sổ cái kiểm tra bất biến `opening + SUM(CREDIT) - SUM(DEBIT) == account.ledger_balance`.

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Chốt hợp đồng giao tiếp `TRFC-LE-01/02`, phân định quyền sở hữu projection lịch sử giao dịch (`DEC-LED-002`), quy tắc số dư mở sổ (`DEC-LED-003`) và bộ 32 test vectors hợp đồng (`BE-LED-01`).
2. [x] Hiện thực migration schema `banking.ledger_entry`, constraints, indexes, trigger chặn `UPDATE/DELETE/TRUNCATE` (`SQLSTATE 55000`), phân quyền `banking_app` và 14 PostgreSQL integration tests (`BE-LED-02`).
3. [x] Hiện thực đầy đủ provider `LedgerPosting` (`PostingCommand`, `PostingRef`, `Money`, `EntryDraft`, `ValidatedPosting`, `PairVerifier`, `LedgerPostingService`, `LedgerEntryPersistenceAdapter`) cùng 103 tests (`LED-POST-01..14`) trên PostgreSQL 16 Testcontainers (`BE-LED-03`, `DEC-LED-005`).
4. [ ] Chuyển migration từ `db/ledger-candidate` sang `V8__create_ledger_entry.sql` theo chuỗi chuẩn `DEC-COM-001`, hiện thực `LedgerQuery.findEvidence` và ghép nối trực tiếp vào giao dịch `T2` / tiến trình phục hồi cùng `transfer`, `account`, `limit`, `audit` (`BE-LED-04..09`).

**Tài liệu chi tiết module `ledger`:**
- [Chỉ mục Module Ledger](../ledger/README.md) · [Thiết kế chi tiết](../ledger/design.md) · [Kế hoạch & Checklist](../ledger/tasks/README.md)
- [BE-LED-01](../ledger/tasks/BE-LED-01.md) ([Evidence](../ledger/tasks/evidence/BE-LED-01.md)) · [BE-LED-02](../ledger/tasks/BE-LED-02.md) ([Evidence](../ledger/tasks/evidence/BE-LED-02.md)) · [BE-LED-03](../ledger/tasks/BE-LED-03.md) ([Evidence](../ledger/tasks/evidence/BE-LED-03.md))
