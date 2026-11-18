# Module `account` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-ACCOUNT-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đang phát triển (Skeleton — đã chốt hợp đồng giao tiếp)
> **Owner:** Đạt
> **Phối hợp / Reviewer:** Phương (`ledger`, `common`), Vũ (`transfer`), Thịnh (`audit`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-009..012, FR-015, AC-024), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.3.1), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2.6, §8.2, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, Phụ lục A), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§7, §9, §12.3, §13.4), [[digitalbanking-docs/docs-backend/modules/_overview/03-account.md]], [[DECISIONS.md]] (`DEC-DOC-001`, `DEC-LED-002/003`, `DEC-TRF-006/008`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.account` quản lý vòng đời tài khoản thanh toán và tiết kiệm (`CHECKING`, `SAVINGS`), sở hữu số dư khả dụng (`available_balance`) và số dư sổ cái (`ledger_balance`), cưỡng chế kiểm tra quyền sở hữu tài khoản (`Ownership Isolation` — chống IDOR), cung cấp các thao tác khóa dòng bi quan (`ORDER BY account_id ASC FOR UPDATE`) và cập nhật số dư có điều kiện trong giao dịch `T2`, cũng như cung cấp số dư mở sổ đầu kỳ (`OpeningBalance`) phục vụ đối soát sổ cái.

---

## 2. Bảng Dữ liệu Sở hữu Độc quyền (`Write-Owner`)

Module `account` là chủ sở hữu ghi duy nhất của bảng **`banking.account`** (`04. Database design & analysis.md` §4.3.1, migration `V6` theo `DEC-COM-001`):

| Cột | Kiểu dữ liệu & Ràng buộc | Quy tắc Nghiệp vụ & Bảo mật |
|---|---|---|
| `account_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh nội bộ của tài khoản. |
| `customer_id` | `BIGINT NOT NULL FK -> banking.customer` | Chủ sở hữu tài khoản (`ON DELETE RESTRICT`). |
| `account_no_enc` | `BYTEA NOT NULL` | Số tài khoản đầy đủ mã hóa `AES-256-GCM` qua `PiiCryptoService`. |
| `account_no_hash` | `CHAR(64) UNIQUE NOT NULL` | Chỉ mục mù `HMAC-SHA256` (giữ nguyên số `0` đứng đầu) để phân giải tài khoản đích. |
| `account_no_last4` | `CHAR(4) NOT NULL` | 4 chữ số cuối dùng để hiển thị định dạng che dấu chuẩn **`**** **** 1001`** (`DEC-DOC-001`). |
| `account_type` | `VARCHAR(16) NOT NULL CHECK IN ('CHECKING','SAVINGS')` | Loại tài khoản. |
| `currency` | `CHAR(3) NOT NULL CHECK (currency = 'VND')` | Đơn vị tiền tệ (`VND`). |
| `available_balance` | `NUMERIC(19,2) NOT NULL CHECK (available_balance >= 0)` | Số dư khả dụng; bất biến `available_balance <= ledger_balance`. |
| `ledger_balance` | `NUMERIC(19,2) NOT NULL CHECK (ledger_balance >= 0)` | Số dư sổ cái; khớp với `opening_balance + SUM(CREDIT) - SUM(DEBIT)` (`DEC-LED-003`). |
| `status` | `VARCHAR(16) NOT NULL CHECK IN ('ACTIVE','LOCKED','CLOSED')` | Trạng thái tài khoản; chỉ tài khoản `ACTIVE` mới được phép ghi nợ/ghi có. |
| `created_at`, `updated_at` | `TIMESTAMPTZ NOT NULL` | Thời điểm tạo và cập nhật (UTC). |

---

## 3. Hợp đồng Giao tiếp Nội bộ (`design/05` §7, §9, §12.3, §13.4)

### 3.1. Public Ports do `account` cung cấp (`com.simulatedbank.banking.account.api`)

| Port & Phương thức | Consumer chính | Giao dịch (`TX`) | Hợp đồng Đầu vào / Đầu ra & Bất biến |
|---|---|---|---|
| `AccountQuery.resolveDestination(destinationRef)` (`TRFC-AC-01`) | `transfer` (`T0`) | `R` (Read-only) | Nhận `accountId` hoặc `accountNumber` (chuẩn hóa + băm `HMAC-SHA256` để tìm theo `account_no_hash`), trả về `AccountSnapshot(accountId, customerId, status, currency, maskedNumber, last4)`. |
| `AccountQuery.getAccountView(accountId, actor)` / `assertOwnership(accountId, customerId)` (`TRFC-AC-02`) | `transfer`, `admin` | `R` (Read-only) | Kiểm tra quyền sở hữu tài khoản hoặc quyền nhân viên (`ACCOUNT_READ`), trả về thông tin tài khoản đã che dấu (`**** **** 1001`) phục vụ biên nhận và lịch sử giao dịch (`DEC-LED-002`). |
| `AccountBalance.lockTransferAccounts(sourceId, destinationId)` (`TRFC-AC-03`) | `transfer` (`T2`) | `JOIN T2` (`MANDATORY`) | Khóa bi quan hai tài khoản theo thứ tự định danh tăng dần (`WHERE account_id IN (:src, :dst) ORDER BY account_id ASC FOR UPDATE`) sau khi đã giữ `Customer Guard` và khóa dòng `transfer` (`DEC-TRF-008`). |
| `AccountBalance.debitConditional(lockedSourceId, amount)` (`TRFC-AC-04`) | `transfer` (`T2`) | `JOIN T2` (`MANDATORY`) | Cập nhật có điều kiện `WHERE account_id = :id AND status = 'ACTIVE' AND available_balance >= :amount RETURNING available_balance, ledger_balance`. Nếu số dòng cập nhật bằng `0`, trả về kết quả từ chối nghiệp vụ (`INSUFFICIENT_BALANCE` / `ACCOUNT_INACTIVE`) mà không làm âm số dư. |
| `AccountBalance.credit(lockedDestinationId, amount)` (`TRFC-AC-05`) | `transfer` (`T2`) | `JOIN T2` (`MANDATORY`) | Cộng tiền có điều kiện `WHERE account_id = :id AND status = 'ACTIVE' RETURNING available_balance, ledger_balance` và trả về số dư sau hạch toán để truyền sang `LedgerPosting.postDoubleEntry`. |
| `AccountBalance.findTransferMovementEvidence(transferId, systemContext)` (`TRFC-AC-06`) | `transfer` (`Recovery`) | `R` trong `Recovery` | Cung cấp bằng chứng biến động số dư theo giao dịch cho `TransferRecoveryJob` (`DEC-TRF-006`). |
| `AccountReconciliationQuery.readOpeningBalance(accountId, systemContext)` | `ledger` (Đối soát) | `R` (Read-only) | Trả về `Optional<OpeningBalance(accountId, amount, currency, effectiveAt, evidenceId)>` có nguồn gốc kiểm toán trước bút toán đầu tiên (`DEC-LED-003`). |

### 3.2. Chiến lược Kiểm thử Độc lập cho Consumer (`AGENTS.md`)

Trong thời gian module `account` đang ở trạng thái Skeleton (`src/main/java/com/simulatedbank/banking/account/**/.gitkeep`), các module phụ thuộc (`transfer`, `ledger`, `admin`) tuân thủ quy tắc tại `AGENTS.md` và `design/05` §13:
- Định nghĩa và kiểm thử thông qua các test double / fake chỉ nằm trong `src/test` bám sát chữ ký và vector kiểm thử tại `05. Modules contract.md` §7 & §13.4 (ví dụ `AccountOwnershipPort` và fixture trong `TransferPreflightCandidateTest`, `TransferSettlementCandidateTest`, `LedgerPostingPostgresTest`).
- Tuyệt đối không đăng ký bean giả làm provider production trong `src/main`.

---

## 4. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.2, §8.8)

- `API-ACC-01` — `GET /api/v1/accounts`: Khách hàng (`CUSTOMER`, `ACCOUNT_READ_SELF`) lấy danh sách tài khoản của chính mình.
- `API-ACC-02` — `GET /api/v1/accounts/{accountId}`: Khách hàng (chủ sở hữu) hoặc nhân viên (`OPERATIONS`, `ADMIN`, `AUDITOR` có `ACCOUNT_READ`) xem chi tiết tài khoản; nếu khách hàng truy cập tài khoản của người khác thì trả `403 AUTH_FORBIDDEN` và ghi audit `SECURITY_ACCESS_DENIED` (`C3`).
- `API-ADM-02` — `GET /api/v1/admin/customers/{customerId}/accounts`: Nhân viên có quyền `ACCOUNT_READ` tra cứu danh sách tài khoản theo khách hàng.

---

## 5. Checklist Công việc Triển khai (`Tasks`)

- [ ] **BE-ACC-01 (P0 — Migration & Domain/Persistence):** Tạo bảng `banking.account` trong migration `V6__create_customer_and_account_tables.sql` (`DEC-COM-001`), hiện thực `AccountEntity`, `AccountJpaRepository` và tích hợp `PiiCryptoService`/`HmacService`/`PiiMaskingUtils`.
- [ ] **BE-ACC-02 (P0 — Internal Ports `AccountQuery` & `AccountBalance`):** Hiện thực `resolveDestination`, `getAccountView`/`assertOwnership`, `lockTransferAccounts` (`ORDER BY account_id ASC FOR UPDATE`), `debitConditional`, `credit`, `findTransferMovementEvidence` và `AccountReconciliationQuery.readOpeningBalance`.
- [ ] **BE-ACC-03 (P0 — REST Endpoints & Anti-IDOR):** Hiện thực `AccountController` cho `API-ACC-01`, `API-ACC-02`, `API-ADM-02` kèm kiểm tra quyền sở hữu và ghi sự kiện `SECURITY_ACCESS_DENIED` (`C3`).
- [ ] **BE-ACC-04 (P0 — Kiểm thử Tích hợp & Đồng thời PostgreSQL):** Chạy kiểm thử đồng thời `T-CON-01` (nhiều kết nối trừ tiền cùng tài khoản không gây âm số dư), `T-CON-03` (chuyển tiền ngược chiều `A <-> B` không deadlock) và `CT-API-015` (chống IDOR) trên PostgreSQL 16 Testcontainers.
