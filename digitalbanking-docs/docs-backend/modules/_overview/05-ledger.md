# Module 05: `ledger` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-05`
> **Module:** `com.simulatedbank.banking.ledger`
> **Owner chính:** **Phương** (Phối hợp: Vũ — `transfer`, Đạt — `account`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 (W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-019, FR-020, FR-030, FR-034), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.3), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.5), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.8, §A.9)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `ledger` quản lý sổ cái kế toán kép bất biến (**Immutable Double-Entry Ledger**), đảm bảo mọi giao dịch chuyển tiền thành công đều sinh ra đúng 1 cặp bút toán đối ứng (`DEBIT` và `CREDIT`) trong cùng transaction `T2` với tổng đại số bằng `0`, đồng thời cung cấp các API tra cứu lịch sử giao dịch cho khách hàng và công cụ đối soát toàn vẹn số dư.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `ledger` sở hữu độc quyền bảng bất biến (append-only) **`banking.ledger_entry`**:

| Cột chính | Kiểu & Ràng buộc | Quy tắc & Bất biến Sổ cái |
|---|---|---|
| `entry_id` | `BIGSERIAL` / `BIGINT IDENTITY` (`PK`) | Khóa chính tự tăng tuần tự. |
| `transfer_id` | `NOT NULL FK -> transfer` | Liên kết tới lệnh chuyển tiền sinh ra bút toán này. |
| `reference` | `VARCHAR(32) NOT NULL` | Mã tham chiếu giao dịch (sao chép từ `transfer.reference` để tối ưu truy vấn). |
| `entry_seq` | `SMALLINT CHECK IN (1, 2)` | Thứ tự trong cặp bút toán kép: `1` luôn là `DEBIT`, `2` luôn là `CREDIT` (`chk_ledger_seq_type`). |
| `account_id` | `NOT NULL FK -> account` | Tài khoản bị ghi nợ (`seq 1`) hoặc được ghi có (`seq 2`). |
| `counterparty_account_id` | `NOT NULL FK -> account` | Tài khoản đối ứng trong giao dịch. |
| `entry_type` | `CHECK IN ('DEBIT','CREDIT')` | Loại bút toán. |
| `amount` | `BIGINT` / `NUMERIC`, `CHECK (> 0)` | Giá trị tuyệt đối luôn dương (`> 0`). |
| `currency` | `CHAR(3) CHECK (currency = 'VND')` | `'VND'`. |
| `balance_after` | `BIGINT` / `NUMERIC`, `CHECK (>= 0)` | Số dư của `account_id` ngay sau khi thực hiện bút toán này. |
| `correlation_id`, `created_at` | `NOT NULL` | Mã truy vết đầu-cuối và thời điểm hạch toán. |

- **Unique Constraint chống ghi sổ kép (`INV-LED-01`):** `UNIQUE (transfer_id, entry_seq)`.
- **Bảo vệ Append-only (`INV-LED-03`):** Tài khoản DB `banking_app` bị thu hồi quyền `UPDATE, DELETE` trên bảng này, kết hợp trigger `trg_ledger_entry_immutable` chặn mọi sửa/xóa (`SQLSTATE 55000`).

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.5)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-TXN-01` | `GET /api/v1/transactions` | `CUSTOMER` (Owner) hoặc `STAFF` (`TRANSACTION_READ`) | Tra cứu danh sách lịch sử biến động số dư (hỗ trợ lọc theo `accountId`, `direction`/`entryType`, khoảng thời gian `from`–`to`, phân trang). |
| `API-TXN-02` | `GET /api/v1/transactions/{reference}` | `CUSTOMER` (Owner) hoặc `STAFF` (`TRANSACTION_READ`) | Xem chi tiết giao dịch theo `reference`. Tính toán chính xác `direction`: `DEBIT` (chuyển đi), `CREDIT` (nhận đến), hoặc `INTERNAL` (chuyển giữa 2 tài khoản của cùng 1 khách hàng). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`ledger.api`)
- **`LedgerPostingPort`** *(Chỉ được gọi bởi `transfer` trong pha `T2` với `Propagation.MANDATORY`)*:
  - `postDoubleEntryInT2(transfer, sourceBalanceAfter, destBalanceAfter)`: Chèn nguyên tử 2 dòng vào `banking.ledger_entry` (`entry_seq = 1, DEBIT` cho `source_account_id` và `entry_seq = 2, CREDIT` cho `dest_account_id` — `design/02` Pattern A.9).
- **`LedgerReconciliationQueryPort`** *(Được gọi bởi `transfer` reconciliation job và health/invariant checker)*:
  - `hasBalancedEntriesForTransfer(transferId)`: Kiểm tra một giao dịch đã có đủ cặp bút toán cân bằng chưa.
  - `findMismatchedAccountBalances()`: Đối soát toàn cục `account.ledger_balance` với `SUM(CREDIT) - SUM(DEBIT)` trong `ledger_entry`.

### 4.2. Ports của module khác mà `ledger` cần gọi
- **`AccountQueryPort`** (của module `account`): Kiểm tra quyền sở hữu `accountId` khi khách hàng truy vấn lịch sử giao dịch và lấy thông tin che dấu của tài khoản đối ứng (`******0002`).

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `LedgerEntryEntity` (Immutable) và `LedgerJdbcRepository` chèn cặp bút toán kép trong `T2`.
2. [ ] Hiện thực `TransactionQueryService` và `TransactionController` (`GET /api/v1/transactions`, `GET /api/v1/transactions/{reference}`), đảm bảo ẩn số dư `balance_after` của tài khoản đối ứng thuộc khách hàng khác.
3. [ ] Viết bộ truy vấn kiểm chứng bất biến sổ cái (`FinancialInvariantAssertions`) dùng chung cho toàn bộ các bài test Concurrency (`D1`), Idempotency (`D2`), Recovery (`D3`) và Load test (`D5`).
4. [ ] Viết test chứng minh tính bất biến Append-only của `banking.ledger_entry` (thử `UPDATE`/`DELETE` bị DB trigger chặn).
