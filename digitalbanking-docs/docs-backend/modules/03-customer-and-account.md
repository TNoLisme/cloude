# Module 03: Customer & Account (`customer`, `account`)

| Trường | Giá trị |
|---|---|
| **Dự án** | Digital Banking Platform Simulator (Single-Bank, Core Simulated) |
| **Mã tài liệu** | `BE-MOD-03` |
| **Phiên bản** | v1.0 |
| **Ngày cập nhật** | 2026-11-07 |
| **Trạng thái** | Hoàn thành — Tài liệu tham chiếu chuẩn cho Backend Developer & Coding Agent |
| **Nguồn tham chiếu** | `[[digitalbanking-docs/docs-common/03. SRS]]` (§3.2, §3.3), `[[digitalbanking-docs/docs-common/04. Database design & analysis]]` (§5.2), `[[digitalbanking-docs/docs-common/06. System architecture]]` (§4.3), `[[digitalbanking-docs/docs-common/07. API contracts]]` (§11.2, §11.3, §11.11), `[[digitalbanking-docs/docs-common/design/02. consistency-concurrency]]` (§A.3–A.7) |

---

## 1. Phạm vi & Trách nhiệm Module

Tài liệu này định nghĩa đặc tả kỹ thuật cho 2 module nghiệp vụ cốt lõi:

1. **Module `customer` (`com. simulatedbank.banking.customer`):**
   - **Write-owner độc quyền** của bảng `banking.customer`.
   - Cung cấp API xem hồ sơ cá nhân của khách hàng (`GET /api/v1/customers/me`) và API tra cứu khách hàng cho nhân viên ngân hàng (`GET /api/v1/admin/customers/{customerId}`).
   - Cung cấp Internal Port `CustomerQueryPort` để các module khác (`account`, `transfer`, `limit`, `audit`) kiểm tra trạng thái hồ sơ khách hàng (`ACTIVE`, `FROZEN`, `CLOSED`).

2. **Module `account` (`com.simulatedbank.banking.account`):**
   - **Write-owner độc quyền** của bảng `banking.account`.
   - Cung cấp API danh sách tài khoản (`GET /api/v1/accounts`), chi tiết tài khoản (`GET /api/v1/accounts/{accountId}`), và API tra cứu tài khoản của khách hàng cho Staff (`GET /api/v1/admin/customers/{customerId}/accounts`).
   - Quản lý mã hóa số tài khoản (`account_no_enc` bằng AES-256-GCM), băm tra cứu (`account_no_hash` bằng HMAC-SHA256) và che dấu hiển thị (`account_no_last4`).
   - Cung cấp **Internal Account Locking & Mutation Port (`AccountBalancePort`)** cho phép module `transfer` khóa bi quan theo thứ tự định sẵn (`ORDER BY account_id ASC FOR UPDATE`) và thực hiện ghi nợ/ghi có có điều kiện (`debit`/`credit`) trong giao dịch `T2`.

---

## 2. Cấu trúc Package chuẩn

```text
com.simulatedbank.banking
├── customer
│   ├── api
│   │   ├── CustomerController.java              // GET /api/v1/customers/me
│   │   ├── AdminCustomerController.java         // GET /api/v1/admin/customers/{customerId}
│   │   └── dto
│   │       └── CustomerProfileResponse.java
│   ├── application
│   │   ├── CustomerQueryPort.java               // Public SPI cho các module khác
│   │   └── CustomerService.java
│   └── infrastructure
│       ├── CustomerEntity.java                  // Bảng banking.customer
│       └── CustomerRepository.java
│
└── account
    ├── api
    │   ├── AccountController.java               // GET /api/v1/accounts, GET /api/v1/accounts/{accountId}
    │   ├── AdminCustomerAccountController.java  // GET /api/v1/admin/customers/{customerId}/accounts
    │   └── dto
    │       ├── AccountSummaryResponse.java
    │       └── AccountDetailResponse.java
    ├── application
    │   ├── AccountQueryPort.java                // Read-only SPI (resolve account, check ownership)
    │   ├── AccountBalancePort.java              // Write/Lock SPI chỉ dành cho module transfer trong T2
    │   └── AccountService.java
    └── infrastructure
        ├── AccountEntity.java                   // Bảng banking.account
        ├── AccountRepository.java
        └── AccountLockJdbcRepository.java       // Native SQL FOR UPDATE & conditional UPDATE
```

---

## 3. Mô hình Dữ liệu & Quy tắc Bảo vệ PII

### 3.1. Bảng `banking.customer` (Write-owner: `customer`)

| Cột | Kiểu dữ liệu | Ràng buộc | Ý nghĩa & Quy tắc |
|---|---|---|---|
| `customer_id` | `UUID` | `PK` | Định danh khách hàng duy nhất. |
| `user_id` | `UUID` | `UNIQUE`, `FK -> users(user_id)` | Liên kết 1-1 với tài khoản đăng nhập có `user_type = CUSTOMER`. |
| `cif_number` | `VARCHAR(32)` | `UNIQUE NOT NULL` | Mã định danh khách hàng trên Core Banking (VD: `CIF00000001`). |
| `full_name` | `VARCHAR(150)` | `NOT NULL` | Họ và tên khách hàng. |
| `national_id_enc` | `BYTEA` | `NOT NULL` | Số CCCD/CMND mã hóa `AES-256-GCM` (`IV 12B \|\| Ciphertext \|\| Tag 16B`). |
| `national_id_hash` | `CHAR(64)` | `UNIQUE NOT NULL` | `HMAC-SHA256(lowercase(trim(national_id)), APP_HMAC_KEY)` dùng để kiểm tra trùng lặp và tìm kiếm chính xác. |
| `national_id_last4` | `CHAR(4)` | `NOT NULL` | 4 số cuối CCCD để hiển thị che dấu (`********1234`). |
| `kyc_status` | `VARCHAR(20)` | `CHECK IN ('PENDING','VERIFIED','REJECTED')` | Chỉ khách hàng có `kyc_status = 'VERIFIED'` mới được phép khởi tạo chuyển tiền đi (`FR-CUS-02`). |
| `status` | `VARCHAR(20)` | `CHECK IN ('ACTIVE','FROZEN','CLOSED')` | Trạng thái hồ sơ khách hàng. Nếu `!= 'ACTIVE'`, từ chối mọi giao dịch tài chính (`422 ACCOUNT_NOT_ACTIVE`). |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | Thời điểm tạo và cập nhật. |

### 3.2. Bảng `banking.account` (Write-owner: `account`)

| Cột | Kiểu dữ liệu | Ràng buộc | Ý nghĩa & Quy tắc |
|---|---|---|---|
| `account_id` | `UUID` | `PK` | Định danh tài khoản nội bộ (sử dụng trên URL path `/accounts/{accountId}`). |
| `customer_id` | `UUID` | `NOT NULL`, `FK -> customer(customer_id)` | Chủ sở hữu tài khoản. |
| `account_no_enc` | `BYTEA` | `NOT NULL` | Số tài khoản đầy đủ mã hóa `AES-256-GCM`. |
| `account_no_hash` | `CHAR(64)` | `UNIQUE NOT NULL` | `HMAC-SHA256(normalize(account_no), APP_HMAC_KEY)` dùng để tra cứu tài khoản đích khi chuyển tiền bằng số tài khoản. |
| `account_no_last4` | `CHAR(4)` | `NOT NULL` | 4 chữ số cuối dùng để tạo chuỗi hiển thị `******1234`. |
| `account_type` | `VARCHAR(20)` | `CHECK IN ('CHECKING','SAVINGS')` | Loại tài khoản thanh toán (`CHECKING`) hoặc tiết kiệm (`SAVINGS`). |
| `currency` | `CHAR(3)` | `CHECK (currency = 'VND')` | Chỉ hỗ trợ `VND` trong phạm vi v1. |
| `available_balance` | `BIGINT` | `NOT NULL DEFAULT 0`, `CHECK (>= 0)` | Số dư khả dụng (đơn vị đồng nguyên, không thập phân). |
| `ledger_balance` | `BIGINT` | `NOT NULL DEFAULT 0`, `CHECK (>= 0)` | Số dư sổ cái. Trong phạm vi v1 (chuyển khoản nội bộ tức thời), bất biến `available_balance == ledger_balance` luôn được duy trì sau mỗi giao dịch `T2`. |
| `status` | `VARCHAR(20)` | `CHECK IN ('ACTIVE','FROZEN','CLOSED')` | Trạng thái tài khoản. |
| `opened_at`, `closed_at`, `updated_at` | `TIMESTAMPTZ` | `NOT NULL` (`closed_at` nullable) | Dấu thời gian vòng đời tài khoản. |

> [!IMPORTANT]
> **Quy tắc bảo mật số tài khoản & CCCD (`DAR-002`):**
> - Không bao giờ lưu plaintext số tài khoản hoặc CCCD vào database hay ghi ra log/audit.
> - Trên các API danh sách hoặc tra cứu thông thường (`GET /accounts`, `GET /admin/customers/{id}`), chỉ trả về dạng che dấu:
>   - `maskedAccountNumber`: `"******" + accountNoLast4` (ví dụ: `"******1001"`)
>   - `maskedNationalId`: `"********" + nationalIdLast4` (ví dụ: `"********5678"`)
> - Chỉ giải mã `account_no_enc` bằng `PiiCryptoService.decrypt(...)` tại API chi tiết tài khoản của chính chủ sở hữu (`GET /api/v1/accounts/{accountId}`) nếu nghiệp vụ UI yêu cầu hiển thị số tài khoản nhận tiền, hoặc trả về `maskedAccountNumber` nhất quán theo contract OpenAPI.

---

## 4. Đặc tả Chi tiết API Endpoints

### 4.1. `API-CUS-01` — Xem Hồ sơ Cá nhân (`GET /api/v1/customers/me`)

- **Quyền truy cập:** Chỉ dành cho `user_type = CUSTOMER` (`@PreAuthorize("hasRole('CUSTOMER')")`). Nếu `STAFF` gọi endpoint này, trả về `403 AUTH_FORBIDDEN`.
- **Luồng xử lý:**
  1. Lấy `userId` và `customerId` từ `BankingSecurityPrincipal` trong `SecurityContextHolder`.
  2. Truy vấn `customerRepository.findByCustomerId(customerId)` (hoặc `findByUserId(userId)`).
  3. Nếu không tìm thấy hồ sơ, trả về `404 RESOURCE_NOT_FOUND`.
  4. Map sang DTO trả về (che dấu `national_id` thành `********` + `national_id_last4`).
- **Response `200 OK`:**
```json
{
  "data": {
    "customerId": "c1000000-0000-0000-0000-000000000001",
    "userId": "u1000000-0000-0000-0000-000000000001",
    "cifNumber": "CIF00000001",
    "fullName": "Nguyen Van A",
    "maskedNationalId": "********1234",
    "kycStatus": "VERIFIED",
    "status": "ACTIVE",
    "createdAt": "2026-01-10T08:00:00Z"
  },
  "meta": {
    "requestId": "req-9c8a7b6d",
    "timestamp": "2026-11-07T10:15:30Z"
  }
}
```

### 4.2. `API-ACC-01` — Danh sách Tài khoản của Khách hàng (`GET /api/v1/accounts`)

- **Quyền truy cập:** `user_type = CUSTOMER`.
- **Query Parameters:**
  - `status` (optional): `ACTIVE`, `FROZEN`, `CLOSED`.
  - `accountType` (optional): `CHECKING`, `SAVINGS`.
- **Luồng xử lý:**
  1. Lấy `customerId` từ `BankingSecurityPrincipal`.
  2. Truy vấn danh sách tài khoản theo `customer_id = :customerId` (tận dụng index `idx_account_customer_status`), sắp xếp theo `opened_at ASC, account_id ASC`.
  3. Trả về danh sách tài khoản kèm số dư `availableBalance`, `ledgerBalance` và `maskedAccountNumber`.
- **Response `200 OK`:**
```json
{
  "data": [
    {
      "accountId": "a1000000-0000-0000-0000-000000000001",
      "customerId": "c1000000-0000-0000-0000-000000000001",
      "maskedAccountNumber": "******1001",
      "accountType": "CHECKING",
      "currency": "VND",
      "availableBalance": 50000000,
      "ledgerBalance": 50000000,
      "status": "ACTIVE",
      "openedAt": "2026-01-10T08:05:00Z"
    }
  ],
  "meta": {
    "requestId": "req-11223344",
    "timestamp": "2026-11-07T10:16:00Z"
  }
}
```

### 4.3. `API-ACC-02` — Xem Chi tiết Tài khoản (`GET /api/v1/accounts/{accountId}`)

- **Quyền truy cập:**
  - `CUSTOMER`: Chỉ được xem tài khoản thuộc sở hữu của chính mình (`account.customer_id == principal.customerId`).
  - `STAFF` (`OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`): Có quyền `ACCOUNT_READ` được phép xem để hỗ trợ vận hành/tra soát.
- **Kiểm tra Ownership & Ghi Audit khi vi phạm (`DAR-001`, `SYS-RL-SEC-01`):**
  1. Tìm tài khoản theo `accountId`. Nếu không tồn tại trong DB:
     - Với `STAFF`: trả về `404 ACCOUNT_NOT_FOUND`.
     - Với `CUSTOMER`: trả về `404 ACCOUNT_NOT_FOUND` (nếu UUID không tồn tại) hoặc nếu UUID tồn tại nhưng `account.customer_id != principal.customerId` thì **bắt buộc**:
       - Ghi 1 bản ghi `audit_log` phân loại `C3` (trong `REQUIRES_NEW` transaction): `action = SECURITY_ACCESS_DENIED`, `target_type = ACCOUNT`, `target_id = accountId`, `result = REJECTED`, `error_code = AUTH_FORBIDDEN`.
       - Trả về `403 AUTH_FORBIDDEN`.

### 4.4. `API-ADM-01` & `API-ADM-02` — Tra cứu Khách hàng & Tài khoản dành cho Staff

1. **`GET /api/v1/admin/customers/{customerId}` (`API-ADM-01`):**
   - **RBAC:** Chỉ `STAFF` có quyền `CUSTOMER_READ` (roles: `OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`). Nếu `CUSTOMER` gọi -> `403 AUTH_FORBIDDEN` + audit `SECURITY_ACCESS_DENIED`.
   - **Xử lý:** Truy vấn `banking.customer` theo `customerId`. Nếu không tồn tại -> `404 RESOURCE_NOT_FOUND`.
2. **`GET /api/v1/admin/customers/{customerId}/accounts` (`API-ADM-02`):**
   - **RBAC:** Chỉ `STAFF` có quyền `ACCOUNT_READ`.
   - **Xử lý:** Kiểm tra `customerId` tồn tại (nếu không -> `404 RESOURCE_NOT_FOUND`), sau đó trả về danh sách toàn bộ tài khoản của `customerId` đó.

---

## 5. Internal Ports cho Giao dịch Chuyển tiền (`T1` & `T2`)

Để đảm bảo tính đóng gói của Modular Monolith (module `transfer` không truy cập trực tiếp `AccountRepository`), module `account` và `customer` cung cấp các Internal Ports sau:

### 5.1. `CustomerQueryPort` (Module `customer`)

```java
package com.simulatedbank.banking.customer.application;

import java.util.UUID;

public interface CustomerQueryPort {
    CustomerSnapshot getCustomerOrThrow(UUID customerId);
    void assertCustomerActiveAndKycVerified(UUID customerId);

    record CustomerSnapshot(
        UUID customerId,
        UUID userId,
        String cifNumber,
        String fullName,
        String kycStatus,
        String status
    ) {}
}
```
- **Quy tắc kiểm tra trong `assertCustomerActiveAndKycVerified(customerId)`:**
  - Nếu `status != 'ACTIVE'` -> ném `BusinessRuleException(ErrorCode.ACCOUNT_NOT_ACTIVE, "Customer profile is not active")`.
  - Nếu `kycStatus != 'VERIFIED'` -> ném `BusinessRuleException(ErrorCode.VALIDATION_ERROR, "Customer KYC is not verified")`.

### 5.2. `AccountQueryPort` & `AccountBalancePort` (Module `account`)

```java
package com.simulatedbank.banking.account.application;

import java.util.UUID;

public interface AccountQueryPort {
    AccountSnapshot getAccountOrThrow(UUID accountId);
    AccountSnapshot resolveByAccountNumber(String rawAccountNumber);
    void assertOwnership(UUID accountId, UUID customerId);

    record AccountSnapshot(
        UUID accountId,
        UUID customerId,
        String maskedAccountNumber,
        String accountType,
        String currency,
        long availableBalance,
        long ledgerBalance,
        String status
    ) {}
}
```

- **Thuật toán `resolveByAccountNumber(String rawAccountNumber)`:**
  1. Chuẩn hóa chuỗi số tài khoản: `String normalized = rawAccountNumber.trim();`
  2. Tính HMAC: `String hash = hmacService.hmacSha256Hex(normalized);`
  3. Truy vấn `SELECT * FROM banking.account WHERE account_no_hash = :hash`.
  4. Nếu không tìm thấy -> ném `ResourceNotFoundException(ErrorCode.ACCOUNT_NOT_FOUND)`.

```java
package com.simulatedbank.banking.account.application;

import java.util.UUID;

public interface AccountBalancePort {
    /**
     * Khóa bi quan 2 tài khoản theo thứ tự UUID tăng dần (ORDER BY account_id ASC FOR UPDATE)
     * để chống deadlock tuyệt đối trong pha T2.
     */
    LockedAccountPair lockTransferAccountsInOrder(UUID sourceAccountId, UUID destAccountId);

    /**
     * Trừ tiền có điều kiện: WHERE account_id = :id AND status = 'ACTIVE' AND available_balance >= :amount.
     * Trả về AccountBalanceAfterMutation chứa số dư sau khi trừ để ghi vào ledger_entry.balance_after.
     */
    AccountBalanceAfterMutation debitConditional(UUID sourceAccountId, long amount);

    /**
     * Cộng tiền có điều kiện: WHERE account_id = :id AND status = 'ACTIVE'.
     * Trả về AccountBalanceAfterMutation chứa số dư sau khi cộng để ghi vào ledger_entry.balance_after.
     */
    AccountBalanceAfterMutation creditConditional(UUID destAccountId, long amount);

    record LockedAccountPair(AccountQueryPort.AccountSnapshot source, AccountQueryPort.AccountSnapshot destination) {}
    record AccountBalanceAfterMutation(UUID accountId, long availableBalanceAfter, long ledgerBalanceAfter) {}
}
```

---

## 6. Mẫu SQL Bắt buộc trong `AccountLockJdbcRepository`

Tất cả câu lệnh khóa và cập nhật số dư trong `AccountBalancePort` **bắt buộc** thực thi bên trong transaction `T2` (sau khi đã chạy `SET LOCAL lock_timeout = '2s'` và `SET LOCAL statement_timeout = '5s'`).

### 6.1. Khóa cặp tài khoản theo thứ tự định sẵn (`Pattern A.3`)

```sql
SELECT account_id, customer_id, account_no_last4, account_type, currency,
       available_balance, ledger_balance, status
FROM banking.account
WHERE account_id IN (:sourceAccountId, :destAccountId)
ORDER BY account_id ASC
FOR UPDATE;
```
- **Kiểm tra sau khi SELECT:**
  - Nếu số dòng trả về `< 2` (khi `sourceAccountId != destAccountId`), nghĩa là 1 trong 2 tài khoản không tồn tại -> đánh dấu giao dịch thất bại (`ACCOUNT_NOT_FOUND`).
  - Map kết quả về đúng vai trò `source` và `destination` theo `account_id`.

### 6.2. Ghi nợ có điều kiện kèm `RETURNING` (`Pattern A.6`)

```sql
UPDATE banking.account
SET available_balance = available_balance - :amount,
    ledger_balance    = ledger_balance - :amount,
    updated_at        = clock_timestamp()
WHERE account_id = :sourceAccountId
  AND status = 'ACTIVE'
  AND available_balance >= :amount
  AND ledger_balance >= :amount
RETURNING account_id, available_balance, ledger_balance;
```
- Nếu câu lệnh không trả về dòng nào (`Optional.empty()`):
  - Vì tài khoản đã được khóa `FOR UPDATE` ở bước 6.1, nguyên nhân chắc chắn là `status != 'ACTIVE'` hoặc `available_balance < :amount`.
  - Kiểm tra snapshot đã khóa để phân loại chính xác mã lỗi nghiệp vụ (`ACCOUNT_NOT_ACTIVE` hoặc `INSUFFICIENT_FUNDS`).

### 6.3. Ghi có có điều kiện kèm `RETURNING` (`Pattern A.7`)

```sql
UPDATE banking.account
SET available_balance = available_balance + :amount,
    ledger_balance    = ledger_balance + :amount,
    updated_at        = clock_timestamp()
WHERE account_id = :destAccountId
  AND status = 'ACTIVE'
RETURNING account_id, available_balance, ledger_balance;
```

---

## 7. Bất biến Số dư & Đối soát Sổ cái (`INV-BAL-01`, `INV-BAL-02`)

Mọi thay đổi trên `available_balance` và `ledger_balance` của bảng `banking.account` phải tuân thủ tuyệt đối 3 bất biến:

1. **Bất biến không âm (`INV-BAL-01`):** `available_balance >= 0` và `ledger_balance >= 0` được bảo vệ ở cả tầng SQL điều kiện (`AND available_balance >= :amount`) và tầng DDL (`CHECK (available_balance >= 0)`).
2. **Bất biến đồng bộ sổ cái (`INV-BAL-02`):** Không một code path nào (kể cả Admin hay Seed script sau khởi tạo) được phép `UPDATE banking.account SET available_balance = ...` mà không ghi cặp bút toán tương ứng vào `banking.ledger_entry` trong cùng một DB transaction.
3. **Truy vấn kiểm tra đối soát (`Reconciliation Check Query`):**
   ```sql
   SELECT a.account_id,
          a.ledger_balance,
          COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS computed_ledger_balance
   FROM banking.account a
   LEFT JOIN banking.ledger_entry l ON a.account_id = l.account_id
   GROUP BY a.account_id, a.ledger_balance
   HAVING a.ledger_balance <> COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0);
   ```
   *(Lưu ý: Đối với các tài khoản seed ban đầu trong môi trường simulator, cần tạo bút toán `CREDIT` mở số dư đầu kỳ hoặc đối soát độ lệch phát sinh sau thời điểm seed).*

---

## 8. Danh mục Mã lỗi & Audit Events của Module

| Tình huống | Mã lỗi (`error.code`) | HTTP Status | Audit Action (`audit_log.action`) | Phân loại Audit |
|---|---|---|---|---|
| Khách hàng xem tài khoản của người khác | `AUTH_FORBIDDEN` | `403 Forbidden` | `SECURITY_ACCESS_DENIED` | `C3` (`REQUIRES_NEW`) |
| `CUSTOMER` gọi API `/admin/customers/...` | `AUTH_FORBIDDEN` | `403 Forbidden` | `SECURITY_ACCESS_DENIED` | `C3` (`REQUIRES_NEW`) |
| Không tìm thấy `customerId` | `RESOURCE_NOT_FOUND` | `404 Not Found` | *(Không ghi audit)* | — |
| Không tìm thấy `accountId` hoặc số tài khoản đích | `ACCOUNT_NOT_FOUND` | `404 Not Found` | *(Không ghi audit trên GET; ghi `TRANSFER_FAILED` nếu trong `T2`)* | `C1` (nếu trong `T2`) |
| Tài khoản nguồn/đích hoặc hồ sơ `customer` bị `FROZEN`/`CLOSED` | `ACCOUNT_NOT_ACTIVE` | `422 Unprocessable Entity` | `TRANSFER_FAILED` | `C1` (trong `T2`) |
| Số dư khả dụng không đủ (`available_balance < amount`) | `INSUFFICIENT_FUNDS` | `422 Unprocessable Entity` | `TRANSFER_FAILED` | `C1` (trong `T2`) |

---

## 9. Checklist Kiểm thử cho Developer & Coding Agent

- [ ] **Ownership Isolation (`T-SEC-OWN-01`):** Đăng nhập bằng `Customer A`, gọi `GET /api/v1/accounts/{accountId_of_Customer_B}` -> nhận `403 AUTH_FORBIDDEN` và có 1 dòng `SECURITY_ACCESS_DENIED` trong `banking.audit_log`.
- [ ] **PII Masking (`T-SEC-PII-01`):** Kiểm tra JSON trả về từ `GET /api/v1/customers/me` và `GET /api/v1/accounts` không chứa mảng byte `account_no_enc`, `national_id_enc` hoặc mã hash `account_no_hash`.
- [ ] **Deadlock Prevention (`T-CON-02`):** Chạy đồng thời 10 luồng chuyển tiền `A -> B` và 10 luồng chuyển tiền `B -> A` qua `AccountBalancePort.lockTransferAccountsInOrder(sourceId, destId)` -> `0` lỗi PostgreSQL deadlock (`40P01`).
- [ ] **Non-negative Balance (`T-CON-01`):** Chạy đồng thời 20 luồng rút/chuyển `80,000 VND` từ tài khoản chỉ có `100,000 VND` qua `debitConditional` -> duy nhất 1 luồng thành công, 19 luồng nhận `INSUFFICIENT_FUNDS`, số dư cuối cùng `= 20,000 VND`.
