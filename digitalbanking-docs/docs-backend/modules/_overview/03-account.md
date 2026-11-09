# Module 03: `account` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-03`
> **Module:** `com.simulatedbank.banking.account`
> **Owner chính:** **Đạt** (Phối hợp: Phương — `ledger`, Vũ — `transfer`, Thắng — FE)
> **Mốc tập trung:** **Tuần 3 – Tuần 4 (W3–W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-009..012, FR-015), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.3, §11.11), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§A.3, §A.6, §A.7)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `account` quản lý vòng đời tài khoản ngân hàng (`CHECKING`, `SAVINGS`), duy trì số dư khả dụng (`available_balance`) và số dư sổ cái (`ledger_balance`), cưỡng chế kiểm tra quyền sở hữu tài khoản (`Ownership Isolation` — chống tấn công IDOR), và cung cấp giao diện khóa bi quan (`FOR UPDATE`) cùng cập nhật số dư có điều kiện cho luồng hạch toán chuyển tiền.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `account` là chủ sở hữu ghi duy nhất của bảng **`banking.account`**:

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bất biến |
|---|---|---|
| `account_id` | `UUID` / `BIGINT` (`PK`) | Định danh nội bộ của tài khoản (dùng trên URL `/accounts/{accountId}`). |
| `customer_id` | `NOT NULL FK -> customer` | Chủ sở hữu tài khoản. |
| `account_no_enc` | `BYTEA NOT NULL` | Số tài khoản đầy đủ mã hóa bằng **`AES-256-GCM`**. |
| `account_no_hash` | `CHAR(64) UNIQUE NOT NULL` | **`HMAC-SHA256`** của số tài khoản dùng để phân giải nhanh tài khoản đích khi chuyển tiền bằng số tài khoản. |
| `account_no_last4` | `CHAR(4) NOT NULL` | 4 số cuối để hiển thị dạng che dấu `******1001`. |
| `account_type` | `CHECK IN ('CHECKING','SAVINGS')` | Loại tài khoản thanh toán hoặc tiết kiệm. |
| `currency` | `CHAR(3) CHECK (currency = 'VND')` | Đơn vị tiền tệ (`VND`). |
| `available_balance` | `BIGINT` / `NUMERIC`, `CHECK (>= 0)` | Số dư khả dụng. **Bất biến `INV-BAL-01`:** Không bao giờ được âm (`>= 0`). |
| `ledger_balance` | `BIGINT` / `NUMERIC`, `CHECK (>= 0)` | Số dư sổ cái. **Bất biến `INV-BAL-02`:** Luôn khớp với tổng đại số `SUM(CREDIT) - SUM(DEBIT)` trong `ledger_entry`. |
| `status` | `CHECK IN ('ACTIVE','FROZEN','CLOSED')` | Trạng thái tài khoản. Chỉ tài khoản `ACTIVE` mới được phép ghi nợ/ghi có. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.3, §11.11)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-ACC-01` | `GET /api/v1/accounts` | `CUSTOMER` | Lấy danh sách toàn bộ tài khoản thuộc sở hữu của khách hàng đang đăng nhập kèm số dư và `maskedAccountNumber`. |
| `API-ACC-02` | `GET /api/v1/accounts/{accountId}` | `CUSTOMER` (Owner) hoặc `STAFF` (`ACCOUNT_READ`) | Xem chi tiết tài khoản. **Kiểm tra Ownership bắt buộc (`AC-024`):** Nếu `CUSTOMER` gọi vào `accountId` của khách hàng khác $\rightarrow$ trả về `403 AUTH_FORBIDDEN` và ghi audit `SECURITY_ACCESS_DENIED` (`C3`). |
| `API-ADM-02` | `GET /api/v1/admin/customers/{customerId}/accounts` | `STAFF` (`ACCOUNT_READ`) | Nhân viên tra cứu danh sách tài khoản của một khách hàng cụ thể. |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`account.api`)
- **`AccountQueryPort`** *(Read-only SPI cho `transfer`, `ledger`, `limit`)*:
  - `getAccountOrThrow(accountId)`: Lấy thông tin tài khoản.
  - `resolveByAccountNumber(rawAccountNumber)`: Chuẩn hóa chuỗi số tài khoản, tính `HMAC-SHA256` và tìm theo `account_no_hash`.
  - `assertOwnership(accountId, customerId)`: Xác nhận tài khoản thuộc về đúng khách hàng; nếu sai thì ghi audit `SECURITY_ACCESS_DENIED` và ném lỗi `403 AUTH_FORBIDDEN`.
- **`AccountBalancePort`** *(Write/Lock SPI chỉ dành riêng cho giao dịch hạch toán `T2`)*:
  - `lockTransferAccountsInOrder(sourceAccountId, destAccountId)`: Khóa bi quan 2 tài khoản theo thứ tự định danh tăng dần (`WHERE account_id IN (:src, :dst) ORDER BY account_id ASC FOR UPDATE` — `design/02` Pattern A.3) để **chống Deadlock tuyệt đối** khi có giao dịch ngược chiều đồng thời (`A -> B` và `B -> A`).
  - `debitConditional(sourceAccountId, amount)`: Trừ tiền có điều kiện (`WHERE account_id = :id AND status = 'ACTIVE' AND available_balance >= :amount RETURNING available_balance, ledger_balance` — Pattern A.6).
  - `creditConditional(destAccountId, amount)`: Cộng tiền có điều kiện (`WHERE account_id = :id AND status = 'ACTIVE' RETURNING available_balance, ledger_balance` — Pattern A.7).

### 4.2. Ports của module khác mà `account` cần gọi
- **`CustomerQueryPort`** (của module `customer`): Kiểm tra trạng thái hồ sơ khách hàng chủ sở hữu.
- **`AuditModuleApi`** (của module `audit`): Ghi `ACCOUNT_STATUS_CHANGED` (`C2`) khi đổi trạng thái tài khoản và `SECURITY_ACCESS_DENIED` (`C3`) khi vi phạm quyền sở hữu.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `AccountEntity`, `AccountRepository` và mã hóa/băm số tài khoản (`account_no_enc`, `account_no_hash`, `account_no_last4`).
2. [ ] Hiện thực `AccountLockJdbcRepository` với đúng 3 câu lệnh SQL chuẩn: `ORDER BY account_id ASC FOR UPDATE`, `debitConditional` và `creditConditional`.
3. [ ] Hiện thực kiểm tra Ownership chặt chẽ và tích hợp ghi audit `SECURITY_ACCESS_DENIED` khi truy cập trái phép (`CT-API-015`).
4. [ ] Phối hợp với Vũ (`transfer`) và Phương (`ledger`) chạy kiểm thử đồng thời `T-CON-01` (nhiều luồng trừ tiền cùng tài khoản không gây âm tiền) và `T-CON-03` (chuyển tiền ngược chiều `A <-> B` không gây deadlock).
