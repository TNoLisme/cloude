# Module 03: `account` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-03`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.account`
> **Owner chính:** **Đạt** (Phối hợp: Phương — `ledger`, Vũ — `transfer`, Thắng — FE)
> **Mốc tập trung:** **Tuần 3 – Tuần 4 (W3–W4)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-009..012, FR-015), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.3.1), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §8.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2.6, §8.2, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, Phụ lục A), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§7, §9, §12.3, §13.4), [[DECISIONS.md]] (`DEC-DOC-001`, `DEC-LED-002/003`, `DEC-TRF-006/008`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `account` quản lý vòng đời tài khoản ngân hàng (`CHECKING`, `SAVINGS`), duy trì số dư khả dụng (`available_balance`) và số dư sổ cái (`ledger_balance`), cưỡng chế kiểm tra quyền sở hữu tài khoản (`Ownership Isolation` — chống tấn công IDOR), cung cấp giao diện khóa bi quan (`FOR UPDATE`) cùng cập nhật số dư có điều kiện cho giao dịch hạch toán `T2`, và cung cấp dữ liệu số dư đầu kỳ (`Opening Balance`) phục vụ đối soát sổ cái.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `account` là chủ sở hữu ghi duy nhất của bảng **`banking.account`** (`04. Database design & analysis.md` §4.3.1, migration `V6`):

| Cột chính | Kiểu & Ràng buộc | Quy tắc Nghiệp vụ & Bất biến |
|---|---|---|
| `account_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh nội bộ của tài khoản (trả về dạng chuỗi số trên API `/accounts/{accountId}`). |
| `customer_id` | `BIGINT NOT NULL FK -> customer` | Chủ sở hữu tài khoản. |
| `account_no_enc` | `BYTEA NOT NULL` | Số tài khoản đầy đủ được mã hóa bằng **`AES-256-GCM`** qua `PiiCryptoService`. |
| `account_no_hash` | `CHAR(64) UNIQUE NOT NULL` | Chỉ mục mù **`HMAC-SHA256`** của số tài khoản đã chuẩn hóa (giữ nguyên số `0` đứng đầu), dùng để phân giải nhanh tài khoản đích khi chuyển tiền theo số tài khoản. |
| `account_no_last4` | `CHAR(4) NOT NULL` | 4 chữ số cuối dùng để hiển thị định dạng che dấu chuẩn **`**** **** 1001`** (`PiiMaskingUtils.maskAccountLast4`, `DEC-DOC-001`). |
| `account_type` | `VARCHAR(16) CHECK IN ('CHECKING','SAVINGS')` | Loại tài khoản thanh toán hoặc tiết kiệm. |
| `currency` | `CHAR(3) CHECK (currency = 'VND')` | Đơn vị tiền tệ (`VND`). |
| `available_balance` | `NUMERIC(19,2) NOT NULL CHECK (>= 0)` | Số dư khả dụng. **Bất biến `INV-BAL-01`:** Không bao giờ được âm (`>= 0`) và `available_balance <= ledger_balance`. |
| `ledger_balance` | `NUMERIC(19,2) NOT NULL CHECK (>= 0)` | Số dư sổ cái. **Bất biến `INV-BAL-02`:** Luôn khớp với `opening_balance + SUM(CREDIT) - SUM(DEBIT)` trong `ledger_entry` (`DEC-LED-003`). |
| `status` | `VARCHAR(16) CHECK IN ('ACTIVE','LOCKED','CLOSED')` | Trạng thái tài khoản (`04` §4.3.1). Chỉ tài khoản `ACTIVE` mới được phép ghi nợ/ghi có. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.2, §8.8)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-ACC-01` | `GET /api/v1/accounts` | `CUSTOMER` (`ACCOUNT_READ_SELF`) | Lấy danh sách toàn bộ tài khoản thuộc sở hữu của khách hàng đang đăng nhập kèm số dư và `maskedAccountNumber` (`**** **** 1001`). |
| `API-ACC-02` | `GET /api/v1/accounts/{accountId}` | `CUSTOMER` (Owner) hoặc `STAFF` (`ACCOUNT_READ`) | Xem chi tiết tài khoản. **Kiểm tra Ownership bắt buộc (`AC-024`):** Nếu `CUSTOMER` truy cập `accountId` của khách hàng khác $\rightarrow$ trả về `403 AUTH_FORBIDDEN` và ghi audit `SECURITY_ACCESS_DENIED` (`C3`). |
| `API-ADM-02` | `GET /api/v1/admin/customers/{customerId}/accounts` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`ACCOUNT_READ`) | Nhân viên tra cứu danh sách tài khoản của một khách hàng cụ thể (`DEC-ID-001`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §7, §9, §13.4)

### 4.1. Ports cung cấp cho các module khác gọi (`account.api`)
- **`AccountQuery`** *(Read-only SPI cho `transfer`, `ledger`, `admin`)*:
  - `resolveDestination(AccountDestinationRef)` (`TRFC-AC-01`): Phân giải tài khoản đích theo `accountId` hoặc `accountNumber` (chuẩn hóa và tra cứu qua `account_no_hash`), trả về `AccountSnapshot(accountId, customerId, status, currency, maskedAccountNumber, last4)`.
  - `getAccountView(accountId, actor)` / `assertOwnership(accountId, customerId)` (`TRFC-AC-02`): Kiểm tra quyền sở hữu tài khoản và cung cấp thông tin hiển thị đã che dấu (`**** **** 1234`) phục vụ biên nhận chuyển tiền và lịch sử giao dịch (`DEC-LED-002`).
- **`AccountBalance`** *(Write/Lock SPI bắt buộc chạy trong giao dịch `T2` với `Propagation.MANDATORY`)*:
  - `lockTransferAccounts(sourceAccountId, destinationAccountId)` (`TRFC-AC-03`): Khóa bi quan 2 tài khoản theo thứ tự định danh tăng dần (`WHERE account_id IN (:src, :dst) ORDER BY account_id ASC FOR UPDATE` — `design/02` Pattern A.3) để **chống Deadlock** khi có giao dịch ngược chiều đồng thời (`A -> B` và `B -> A`).
  - `debitConditional(lockedSourceAccountId, amount)` (`TRFC-AC-04`): Trừ tiền có điều kiện (`WHERE account_id = :id AND status = 'ACTIVE' AND available_balance >= :amount RETURNING available_balance, ledger_balance` — Pattern A.6).
  - `credit(lockedDestinationAccountId, amount)` (`TRFC-AC-05`): Cộng tiền có điều kiện (`WHERE account_id = :id AND status = 'ACTIVE' RETURNING available_balance, ledger_balance` — Pattern A.7) và trả về số dư sau hạch toán để truyền sang `LedgerPosting.postDoubleEntry`.
  - `findTransferMovementEvidence(transferId, systemContext)` (`TRFC-AC-06`): Cung cấp bằng chứng biến động số dư theo giao dịch cho tiến trình đối soát/phục hồi `TransferRecoveryJob` (`DEC-TRF-006`).
- **`AccountReconciliationQuery`** *(Read-only SPI cho đối soát sổ cái — `DEC-LED-003`)*:
  - `readOpeningBalance(accountId, systemContext)`: Trả về `Optional<OpeningBalance(accountId, amount, currency, effectiveAt, evidenceId)>` có nguồn gốc kiểm toán trước bút toán đầu tiên để đối soát công thức `opening + SUM(CREDIT) - SUM(DEBIT) == ledger_balance`.

### 4.2. Ports của module khác mà `account` cần gọi
- **`CustomerQuery`** (của module `customer`): Kiểm tra trạng thái hồ sơ khách hàng chủ sở hữu (`ACTIVE`, `kyc_status = 'VERIFIED'`).
- **`PiiCryptoService`, `HmacService`, `PiiMaskingUtils`** (của module `common`): Mã hóa `account_no_enc`, tính `account_no_hash` và định dạng `**** **** 1001`.
- **`AuditModuleApi`** (của module `audit`): Ghi `ACCOUNT_STATUS_CHANGED` (`C2`) khi đổi trạng thái tài khoản và `SECURITY_ACCESS_DENIED` (`C3`) khi vi phạm quyền sở hữu.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực migration `V6` (phần bảng `banking.account`), `AccountEntity`, `AccountRepository` và mã hóa/băm số tài khoản (`account_no_enc`, `account_no_hash`, `account_no_last4`).
2. [ ] Hiện thực `account.api.AccountQuery`, `account.api.AccountBalance` (`ORDER BY account_id ASC FOR UPDATE`, `debitConditional`, `credit`) và `account.api.AccountReconciliationQuery`.
3. [ ] Hiện thực các endpoint `GET /api/v1/accounts` (`API-ACC-01`), `GET /api/v1/accounts/{accountId}` (`API-ACC-02`) và `GET /api/v1/admin/customers/{customerId}/accounts` (`API-ADM-02`) kèm kiểm tra Ownership chống IDOR (`CT-API-015`).
4. [ ] Phối hợp với Vũ (`transfer`) và Phương (`ledger`) chạy kiểm thử đồng thời trên PostgreSQL `T-CON-01` (nhiều luồng trừ tiền cùng tài khoản không gây âm tiền) và `T-CON-03` (chuyển tiền ngược chiều `A <-> B` không gây deadlock).
