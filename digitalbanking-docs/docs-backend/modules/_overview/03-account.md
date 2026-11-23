# Module 03: `account` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-03`
> **Module:** `com.simulatedbank.banking.account`
> **Owner chính:** **Đạt** (Phối hợp: Phương — `ledger`, Vũ — `transfer`, Thắng — FE)
> **Mốc tập trung:** **Tuần 3 – Tuần 4 (W3–W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-009..012, FR-015), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.3, §11.11), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§A.3, §A.6, §A.7)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `account` quản lý vòng đời tài khoản ngân hàng, duy trì số dư khả dụng (`available_balance`) và số dư sổ cái (`ledger_balance`), cưỡng chế kiểm tra quyền sở hữu tài khoản (`Ownership Isolation` — chống tấn công IDOR), và cung cấp giao diện khóa bi quan (`FOR UPDATE`) cùng cập nhật số dư có điều kiện cho luồng hạch toán chuyển tiền.

> Baseline schema canonical là `04` §4.3.1; các mục `UUID`, `FROZEN`, `account_type CHECKING/SAVINGS`, `BIGINT` balance đã bị loại khỏi tài liệu này theo [DEC-ACC-001](../../../../DECISIONS.md) (BE-ACC-01).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `account` là chủ sở hữu ghi duy nhất của bảng **`banking.account`**. Migration canonical: `V8__create_account_table.sql` ([BE-ACC-02](../account/BE-ACC-02.md)) — 12 cột, PK IDENTITY, FK `RESTRICT`, 8 CHECK, 4 index:

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bất biến |
|---|---|---|
| `account_id` | `BIGINT GENERATED ALWAYS AS IDENTITY (PK)` | Định danh nội bộ của tài khoản; ra HTTP dưới dạng decimal string `/accounts/{accountId}`. |
| `customer_id` | `BIGINT NOT NULL FK -> customer` (`RESTRICT`) | Chủ sở hữu tài khoản. |
| `account_no_enc` | `BYTEA NOT NULL` | Số tài khoản đầy đủ mã hóa bằng **`AES-256-GCM`**. |
| `account_no_hash` | `CHAR(64) UNIQUE NOT NULL` | **`HMAC-SHA256`** của số tài khoản dùng để phân giải nhanh tài khoản đích khi chuyển tiền bằng số tài khoản. |
| `account_no_last4` | `CHAR(4) NOT NULL` | 4 số cuối để hiển thị dạng che dấu `**** **** 1001`. |
| `currency` | `CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND')` | Đơn vị tiền tệ (`VND`). |
| `available_balance` | `NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (>= 0)` | Số dư khả dụng. **Bất biến `INV-BAL-01`:** Không bao giờ được âm (`>= 0`). |
| `ledger_balance` | `NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (>= 0)` | Số dư sổ cái. **Bất biến `INV-BAL-02`:** Luôn khớp với tổng đại số `SUM(CREDIT) - SUM(DEBIT)` trong `ledger_entry`. |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','CLOSED'))` | Trạng thái tài khoản. Chỉ tài khoản `ACTIVE` mới được phép ghi nợ/ghi có. |
| `closed_at` / `created_at` / `updated_at` | `TIMESTAMPTZ`, `closed_at` nullable | Không hard delete; đóng bằng `status='CLOSED'` + `closed_at`. |

MVP **không có** cột `account_type`: `04` §4.3.1, `07` §7.2.7 và OpenAPI `AccountSummary` không có field này (xem ACC-DR-03 trong [BE-ACC-01](../account/BE-ACC-01.md)).

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.3, §11.11)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-ACC-01` | `GET /api/v1/accounts` | `CUSTOMER` | Lấy danh sách toàn bộ tài khoản thuộc sở hữu của khách hàng đang đăng nhập kèm số dư và `maskedAccountNumber`. |
| `API-ACC-02` | `GET /api/v1/accounts/{accountId}` | `CUSTOMER` (Owner) hoặc `STAFF` (`ACCOUNT_READ`) | Xem chi tiết tài khoản. **Kiểm tra Ownership bắt buộc (`AC-024`):** Nếu `CUSTOMER` gọi vào `accountId` của khách hàng khác $\rightarrow$ trả về `403 AUTH_FORBIDDEN` và ghi audit `SECURITY_ACCESS_DENIED` (`C3`). |
| `API-ADM-02` | `GET /api/v1/admin/customers/{customerId}/accounts` | `STAFF` (`ACCOUNT_READ`) | Nhân viên tra cứu danh sách tài khoản của một khách hàng cụ thể. **Đã có runtime (BE-ACC-09, PARTIAL):** `AdminCustomerAccountsController`, masked `AccountsResponse`, `status` optional, không pagination/sort; data scope staff (ACC-DR-13) và `404 CUSTOMER_NOT_FOUND` (ACC-DR-10) còn BLOCKED. |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`account.api`, chốt tại BE-ACC-01)
- **`account.api.AccountQuery`** *(Read-only SPI cho `transfer`, `ledger`, admin read path)*:
  - `findAccount(accountId)` / `resolveDestination(rawAccountNumber)`: snapshot tối thiểu `AccountSnapshot(accountId, customerId, status, currency, maskedNumber, last4)`; số tài khoản được normalize `^[0-9]{6,20}$`, tính `HMAC-SHA256` và tìm theo `account_no_hash`; không log/không trả plaintext.
  - `getAccountView(AccountViewQuery)`: masked projection theo owner scope hoặc staff scope; owner mismatch → `403 AUTH_FORBIDDEN` + audit `SECURITY_ACCESS_DENIED`.
  - `assertOwnership(accountId, customerId, correlationId)`: xác nhận chủ sở hữu; sai → `403 AUTH_FORBIDDEN`, không tồn tại → `404 ACCOUNT_NOT_FOUND`.
- **`account.api.AccountBalance`** *(Lock/Write SPI chỉ dành cho `T2` của Transfer, propagation `MANDATORY`)*:
  - `lockTransferAccounts(sourceAccountId, destinationAccountId)`: khóa bi quan 2 tài khoản theo `WHERE account_id IN (:src, :dst) ORDER BY account_id ASC FOR UPDATE` (`design/02` A.3) để giảm deadlock khi có giao dịch ngược chiều (`A -> B` và `B -> A`). Thứ tự lock giảm nguy cơ, không bảo đảm tuyệt đối không deadlock.
  - `debitConditional(sourceAccountId, amount)`: conditional UPDATE `status='ACTIVE' AND available_balance >= :amount RETURNING available_balance, ledger_balance` (A.4); 0 row → `INSUFFICIENT_BALANCE`/`INACTIVE` sau khi đọc lại dưới lock, không bao giờ âm. **Đã có runtime (BE-ACC-07)** trong `AccountBalanceService` + `JdbcAccountBalanceAdapter`, propagation `MANDATORY`, amount `> 0`/scale ≤ 2/≤ 17 chữ số nguyên.
  - `credit(destinationAccountId, amount)`: conditional UPDATE `status='ACTIVE' RETURNING …` (A.4); 0 row/DB lỗi sau debit → system error, rollback toàn T2.

### 4.2. Ports của module khác mà `account` cần gọi
- **`customer.api.CustomerQuery`** (đã có runtime): resolve `userId → customerId` + status phục vụ ownership và `AccountDetail.owner.customerId/status`; Account không đọc bảng/PII Customer.
- **`getCustomerView` của Customer** (chưa có): chỉ cần cho `AccountDetail.owner.fullName`; đang BLOCKED (ACC-DR-10) — Account không tự decrypt/thay thế.
- **`AuditModuleApi`** (của module `audit`, chưa có runtime): ghi `ACCOUNT_STATUS_CHANGED` (`C2`) khi đổi trạng thái (chưa có transition được duyệt) và `SECURITY_ACCESS_DENIED` (`C3`); hiện dùng structured security log như DEC-CUS-005, durable audit vẫn BLOCKED.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

Trạng thái: BE-ACC-01 chốt schema/API/ports; BE-ACC-02 tạo `V8`; BE-ACC-03 hiện thực entity/crypto/blind index/read repository; BE-ACC-04 hiện thực read/ownership/resolve; BE-ACC-05 hiện thực REST `API-ACC-01/02` + security/C3 runtime; BE-ACC-06 hiện thực ASC row lock `FOR UPDATE`; BE-ACC-07 hiện thực conditional debit/credit + rollback same-transaction; BE-ACC-11 hoàn tất PostgreSQL concurrency/security/failure tests (Account **128/128 PASS**); BE-ACC-12 hoàn tất evidence + handoff, full `clean build` **PASS 1244/1244** (xem [BE-ACC-03](../account/BE-ACC-03.md)…[BE-ACC-07](../account/BE-ACC-07.md), [BE-ACC-11](../account/BE-ACC-11.md), [BE-ACC-12](../account/BE-ACC-12.md), [DEC-ACC-003/004/005/006/007/010/011](../../../../DECISIONS.md)). T2 end-to-end với Transfer/Ledger (BE-ACC-08) chưa có runtime vì chưa có consumer/orchestrator.

1. [x] Hiện thực `AccountEntity`, `AccountReadPort`/`JpaAccountReader` và mã hóa/băm số tài khoản (`account_no_enc`, `account_no_hash`, `account_no_last4`) — BE-ACC-03.
2. [x] Hiện thực row lock `ORDER BY account_id ASC FOR UPDATE` (`AccountLockService`/`AccountLockPort`/`JdbcAccountLockAdapter`) — BE-ACC-06; [x] `debitConditional` + `credit` conditional UPDATE, rollback/`MANDATORY` transaction (`AccountBalanceService`/`AccountBalancePort`/`JdbcAccountBalanceAdapter`) — BE-ACC-07.
3. [x] Hiện thực kiểm tra Ownership và audit `SECURITY_ACCESS_DENIED` khi truy cập trái phép (`CT-API-015`) ở service + REST adapter `API-ACC-01/02` với 401/403/404/400 (BE-ACC-04/05); durable audit C3 vẫn BLOCKED (ACC-DR-15).
4. [ ] Phối hợp với Vũ (`transfer`) và Phương (`ledger`) chạy kiểm thử đồng thời `T-CON-01` (nhiều luồng trừ tiền cùng tài khoản không gây âm tiền) và `T-CON-03` (chuyển tiền ngược chiều `A <-> B` không gây deadlock). **Account scope PASS (BE-ACC-11):** 10 T2 tranh chấp vượt số dư chỉ áp dụng đúng 3 lượt, không credit phía bị từ chối, tổng tiền bất biến; A↔B 2×25 không deadlock. Phần ledger entry/Transfer state chờ BE-ACC-08.
