# BE-ACC-01 — Resolve Schema/API/Ports & Open Decisions

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-01
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 17/11/2026
> **Trạng thái:** **PARTIAL** — schema/API/port contract đã chốt và implement/verify; 6 quyết định cross-module còn **BLOCKED**
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần Architect, BA, Security, Audit và owner Customer
> **Branch:** `datht-backend-rework` (baseline `4d0ed75`), không tạo/không switch branch
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §FR-009–012/FR-015/FR-039/FR-049, AC-005/023/024/027; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.3.1; [[digitalbanking-docs/docs-common/07. API contracts.md]] §7.2.7–7.2.8, §8.2.2–8.2.5; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5.3, Appendix A.3/A.4; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §7; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] `AccountSummary`/`AccountDetail`/`AccountStatus`; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §4–§8; [DEC-ACC-001](../../../../DECISIONS.md)

---

## A. Phạm vi và hiện trạng đã đối chiếu

Đã đọc và đối chiếu: `docs-backend/account/` (README, BE-ACC-DOC-01, tasks), `_overview/03-account.md`, `docs-common/03`, `04` §4.3.1, `07` §8.2, `design/02` §5.3/Appendix A, `design/05` §3/§6–§7, OpenAPI, `00. architecture & conventions.md`, `ModuleBoundaryArchTest`, source Identity/Customer/Common/Admin hiện có, migration `V1`–`V7`.

Hiện trạng tại baseline `4d0ed75`:

| Hạng mục | Thực tế |
|---|---|
| Account production source | Chỉ có 5 package rỗng (`api`, `application`, `domain`, `adapter/in`, `adapter/out`), không có class nghiệp vụ trước task này |
| Migration | `V1`–`V7` không có bảng `banking.account`; `V8` là version kế tiếp chưa dùng |
| Consumer runtime | `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification` cũng chưa có Java nghiệp vụ → chưa có `AccountOwnershipPort`/`AccountModuleApi` nào trên baseline này để tái sử dụng hay xung đột |
| Provider sẵn có | `customer.api.CustomerQuery.findByUserId` (userId → customerId/status), Identity session snapshot qua `common.config.SessionAuthenticator`, Common crypto/masking/error đã có |
| Tài liệu mâu thuẫn | `_overview/03-account.md` ghi `FROZEN`, `UUID/BIGINT`, `account_type CHECKING/SAVINGS`, balance `BIGINT` — khác `04` §4.3.1 và OpenAPI |

Ngoài phạm vi task này (giữ nguyên cho task kế tiếp): Flyway DDL (`BE-ACC-02`), entity/crypto/repository (`BE-ACC-03`), read service/ownership (`BE-ACC-04`), REST adapter (`BE-ACC-05`), lock/debit/credit SQL (`BE-ACC-06/07`), T2 integration (`BE-ACC-08`), staff list (`BE-ACC-09`), provenance (`BE-ACC-10`).

---

## B. Schema contract đã chốt (`banking.account`)

Nguồn duy nhất: `04` §4.3.1 (khớp `07` §7.2.7 và OpenAPI). Account là write-owner duy nhất; không module nào khác được `INSERT/UPDATE/DELETE` bảng này.

| Column | Type | Constraint | Ghi chú |
|---|---|---|---|
| `account_id` | `BIGINT` | PK, `GENERATED ALWAYS AS IDENTITY`, NOT NULL | ID nội bộ; ra HTTP là decimal string `[1-9][0-9]{0,18}` |
| `customer_id` | `BIGINT` | FK `banking.customer(customer_id)`, NOT NULL, `ON DELETE/UPDATE RESTRICT` | Chủ sở hữu; không cascade xóa financial record |
| `account_no_enc` | `BYTEA` | NOT NULL | AES-256-GCM qua Common `PiiCryptoService` |
| `account_no_hash` | `CHAR(64)` | NOT NULL, UNIQUE `uq_account_no_hash` | HMAC-SHA256 blind index qua Common `HmacService`; natural key immutable |
| `account_no_last4` | `CHAR(4)` | NOT NULL, `char_length = 4` | Khớp 4 ký tự cuối (application kiểm) |
| `currency` | `CHAR(3)` | NOT NULL, DEFAULT `'VND'`, CHECK `= 'VND'` | MVP chỉ VND |
| `status` | `VARCHAR(16)` | NOT NULL, DEFAULT `'ACTIVE'`, CHECK `IN ('ACTIVE','LOCKED','CLOSED')` | Canonical status set |
| `available_balance` | `NUMERIC(19,2)` | NOT NULL, DEFAULT `0`, CHECK `>= 0` | Số dư khả dụng |
| `ledger_balance` | `NUMERIC(19,2)` | NOT NULL, DEFAULT `0`, CHECK `>= 0` | Số dư materialized theo ledger |
| `closed_at` | `TIMESTAMPTZ` | NULL | Bắt buộc khi `status='CLOSED'` (application + test) |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT `now()` | Immutable |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT `now()` | Cập nhật trong mọi conditional UPDATE |

**Table-level checks:** `available_balance <= ledger_balance` (MVP không hold), `currency='VND'`, `account_no_last4` khớp 4 ký tự cuối (application).

**Indexes:** `uq_account_no_hash (account_no_hash)`; `ix_account_customer (customer_id)`; `ix_account_status (status)`. Không thêm index/partial unique ngoài `04` §4.3.1.

### B.1. Trạng thái và transition

| Nội dung | Trạng thái | Căn cứ |
|---|---|---|
| Status set | `ACTIVE`, `LOCKED`, `CLOSED` | `04` §4.3.1 CHECK, `07` §7.2.7, OpenAPI `AccountStatus` |
| Chỉ `ACTIVE` được debit/credit | **Bắt buộc** | `04` §4.3.1 business rule, `design/02` A.4, `design/05` §7.2 |
| `CLOSED` kèm `closed_at`, không hard delete | **Bắt buộc** | `04` §4.3.1 |
| Transition matrix `ACTIVE↔LOCKED`, `CLOSED` terminal, re-open | **Chưa duyệt** — ACC-DR-12 BLOCKED | Không có public operation/`C2` action trong `07`/OpenAPI |

Không implement state machine/transition mới. `LOCKED`/`CLOSED` chỉ là giá trị đọc được từ DB; write path chưa mở.

### B.2. Balance, audit và concurrency

- Tiền dùng `NUMERIC(19,2)`/`BigDecimal`; không `double/float`; so sánh `compareTo` (BE-CON-02).
- Cập nhật balance **chỉ** trong T2 của Transfer bằng conditional UPDATE trên cả `available_balance` và `ledger_balance` (`design/02` A.4); không update ngoài service tiền.
- `ledger_balance` là materialized từ `ledger_entry`; Ledger nhận `balanceAfter` từ Account và không ghi bảng `account`. Reconciliation định kỳ `ledger_balance = SUM(credits) - SUM(debits)` thuộc Ledger/Account phối hợp.
- Concurrency: pessimistic row lock `SELECT … ORDER BY account_id ASC FOR UPDATE` + conditional UPDATE `WHERE available_balance >= :amount AND status='ACTIVE'`. **Không có cột `version`/optimistic locking** trong schema canonical (ACC-DR-07); thêm cột phải qua thay đổi `04` được duyệt.
- Audit fields: chỉ `created_at`, `updated_at`, `closed_at`. Audit nghiệp vụ (C1–C3) không lưu trong bảng Account; module Audit sở hữu hash-chain (chưa có runtime).

### B.3. Idempotency và transaction boundary

- `banking.account` **không** có idempotency column. `idempotency_record` thuộc Transfer/business orchestrator; Account không tự insert record.
- Read (`API-ACC-01/02`, `API-ADM-02`) là side-effect-free, không cần `Idempotency-Key`.
- Money: Account operations `JOIN T2` với propagation `MANDATORY`; không transaction riêng, không commit một phía.
- Account provisioning (tạo tài khoản, sinh số, opening balance) chưa có operation được duyệt → idempotency policy thuộc ACC-DR-04 (BLOCKED).

### B.4. Migration ownership

`V8` là version kế tiếp; `BE-ACC-02` sở hữu `V8__create_account_table.sql` theo đúng bảng/constraint/index trên. Không sửa `V1`–`V7`, không bật out-of-order, không destructive migration. `BE-ACC-01` không tạo migration (giữ đúng phân rã task và tránh đánh dấu hoàn thành task khác).

---

## C. API contract đã chốt

Chỉ ba operation `GET` tồn tại trong `07`/OpenAPI. **Không** thêm create/update/status/close endpoint.

| API | Method + Path | Authority (security chain) | Response | Lỗi |
|---|---|---|---|---|
| `API-ACC-01` | `GET /api/v1/accounts` | `ACCOUNT_READ_SELF` | `200 AccountsResponse{items[AccountSummary], asOf}` | 401, 403, 500, 503 |
| `API-ACC-02` | `GET /api/v1/accounts/{accountId}` | `ACCOUNT_READ_SELF` hoặc `ACCOUNT_READ` | `200 AccountDetail` | 400 `VALIDATION_ERROR`, 401, 403 `AUTH_FORBIDDEN`, 404 `ACCOUNT_NOT_FOUND`, 500, 503 |
| `API-ADM-02` | `GET /api/v1/admin/customers/{customerId}/accounts` | `ACCOUNT_READ` | `200 AccountsResponse` | 400, 401, 403, 404 `CUSTOMER_NOT_FOUND`, 500, 503 |

Quy tắc đã chốt:

1. **Actor** lấy từ principal snapshot server-side (`SessionAuthenticationFilter`); endpoint không nhận `customerId`/`userId`/role từ client. Self list/detail tự resolve `userId → customerId` qua `CustomerQuery` (owner Customer sở hữu mapping).
2. **Validation:** `accountId`/`customerId` theo schema `ID` `^[1-9][0-9]{0,18}$` — sai → 400 trước persistence. `status` filter optional, enum `ACTIVE|LOCKED|CLOSED`; giá trị khác → 400.
3. **Ownership (AC-024):** owner khác → `403 AUTH_FORBIDDEN` + audit `SECURITY_ACCESS_DENIED` (C3) trước khi trả bất kỳ field nào; account vắng → `404 ACCOUNT_NOT_FOUND` không audit (design/01 §5.7). Staff cần `ACCOUNT_READ`; auditor read-only (RBAC Identity).
4. **Masking (AC-023):** chỉ trả `accountNumberMasked` (`**** **** <last4>`) + `accountNumberLast4`; không decrypt/plaintext/hash trong response, log, metric tag. `Cache-Control: no-store`.
5. **DTO** đúng `07` §7.2.7–7.2.8: `AccountSummary{accountId, accountNumberMasked, accountNumberLast4, currency, status, availableBalance, ledgerBalance, asOf}`; `AccountDetail` thêm `owner{customerId, fullName, status}, createdAt, updatedAt`. Money là string `(0|[1-9][0-9]{0,16})\.[0-9]{2}`; `asOf`/timestamp là ISO-8601 UTC.
6. **Pagination/filter:** OpenAPI chỉ có `status` cho self list, không `page/size/cursor` → **không** implement pagination. Customer có thể có N tài khoản; thứ tự ổn định theo `account_id ASC` (đề xuất implementation, không phải contract mới).
7. **Error body:** RFC 9457 `application/problem+json` qua `GlobalExceptionHandler`/`ProblemWriter`; controller không tự dựng problem.
8. **Idempotency:** GET không yêu cầu `Idempotency-Key`; không có replay header.
9. **Route ownership:** `API-ADM-02` do module Account implement (Account sở hữu dữ liệu account; Customer không đọc bảng account theo DEC-CUS-006). Không tạo controller thứ hai cùng route; Customer giữ `API-ADM-01`.

**BLOCKED trong contract API:** `AccountDetail.owner.fullName` và 404 `CUSTOMER_NOT_FOUND` của `API-ADM-02` cần operation Customer theo `customerId` (design/05 §6.2 `getCustomerView`) chưa được duyệt/triển khai — ACC-DR-10. Account không đọc/decrypt PII Customer.

---

## D. Ports và dependency đã chốt

### D.1. Ma trận sở hữu

| Interface | Owner | Consumer | Trạng thái |
|---|---|---|---|
| `account.api.AccountQuery` | Account | Transfer, Ledger, Admin read composition, Account REST adapter | **Implement tại BE-ACC-01** |
| `account.api.AccountBalance` | Account | Transfer (T2) | **Implement tại BE-ACC-01** |
| `customer.api.CustomerQuery` | Customer | Account | Có runtime (`findByUserId`), tái sử dụng |
| `getCustomerView` (Customer) | Customer | Account/Admin | **BLOCKED** — chưa duyệt/triển khai |
| `AuditModuleApi` | Audit | Account | **BLOCKED** — audit chưa có runtime; dùng structured security log tạm thời |
| `SessionAuthenticator`/`Session` | Common (+ Identity adapter) | Account REST adapter | Có runtime |
| `PiiCryptoService`, `HmacService`, `PiiMaskingUtils`, `ErrorCode`/`BankingBusinessException` | Common | Account | Có runtime |

Không tạo interface Account trùng trong Transfer; Transfer chưa có source trên baseline nên chỉ cần bind đúng `account.api` khi triển khai.

### D.2. Signature và transaction expectation

Read (`AccountQuery`): `R` / `@Transactional(readOnly = true)`, không side effect:

```java
Optional<AccountSnapshot> findAccount(long accountId);
Optional<AccountSnapshot> resolveDestination(String accountNumber);   // input ^[0-9]{6,20}$
AccountView getAccountView(AccountViewQuery query);                   // forOwner | forStaff
void assertOwnership(long accountId, long customerId, String correlationId);
```

Money (`AccountBalance`): `JOIN T2`, propagation `MANDATORY`, cùng connection/transaction:

```java
LockedAccounts lockTransferAccounts(long sourceAccountId, long destinationAccountId);
DebitResult debitConditional(long sourceAccountId, BigDecimal amount); // APPLIED | INSUFFICIENT_BALANCE | INACTIVE
CreditResult credit(long destinationAccountId, BigDecimal amount);     // 0 row/DB lỗi sau debit = system error
```

### D.3. Ranh giới kiến trúc và error handling

- Hướng dependency: consumer → `account.api`; Account → `common.*`, `customer.api`, (tương lai) `audit.api`. Account **không** import `transfer/admin/ledger/limit/fraud/notification` hay internals Customer/Identity (ArchUnit).
- Account không đọc/ghi bảng của module khác, không lộ entity qua `api`, không trả `account_no_enc`/`account_no_hash`.
- Business outcome (`ACCOUNT_NOT_FOUND`, `ACCOUNT_INACTIVE`, `INSUFFICIENT_BALANCE`, `AUTH_FORBIDDEN`, `VALIDATION_ERROR`) ném `BankingBusinessException` theo catalog `ErrorCode`; lỗi kỹ thuật (SQLSTATE timeout/deadlock, DB down) lan truyền để orchestrator rollback/retry toàn transaction, không bị nuốt hay đổi thành empty/404.
- Ownership check là defense-in-depth: chain kiểm permission; service/provider kiểm ownership/status thật từ DB.
- `LockedAccount` chỉ có hiệu lực trong transaction hiện tại; không được cache hoặc truyền ra ngoài T2.

### D.4. Contract guards đã implement

`AccountContractChecks` (package-private trong `account.api`) chặn tại biên DTO: ID phải dương; `currency` phải `VND`; số tài khoản phải đúng `**** **** <last4>` và khớp `last4`; balance không âm, scale ≤ 2, vừa `NUMERIC(19,2)`; `correlationId` theo pattern trace `07` §2.3; `LockedAccounts` source ≠ destination; `DebitResult` chỉ mang `balanceAfter` khi `APPLIED`. Mục tiêu: lỗi lập trình không thể biến thành PII/số dư sai trên response hoặc log.

---

## E. Decision register

| ID | Topic | State | Evidence | Resolution | Impact | Owner | Next action |
|---|---|---|---|---|---|---|---|
| ACC-DR-01 | Schema/status | **RESOLVED** | `04` §4.3.1 CHECK, `07` §7.2.7, OpenAPI `AccountStatus`; `_overview/03-account.md` (cũ) ghi `FROZEN` | `ACTIVE\|LOCKED\|CLOSED`; `FROZEN` là tài liệu lỗi thời, đã sửa overview | BE-ACC-02/03/05 | Account | V8 CHECK `ck_account_status` đúng 3 giá trị |
| ACC-DR-02 | Schema/ID type | **RESOLVED** | `04` §4.3.1 `BIGINT IDENTITY`; OpenAPI `ID` `^[1-9][0-9]{0,18}$`; overview cũ ghi UUID/BIGINT | `BIGINT` + decimal string trên wire; không UUID | Entity, DTO, path variable, FK | Account | Giữ `long` nội bộ, parse/validate ở adapter |
| ACC-DR-03 | Schema/`account_type` | **RESOLVED** | `04` §4.3.1, `07` §7.2.7–7.2.8, OpenAPI không có field; overview cũ ghi `CHECKING/SAVINGS` | MVP **không** có `account_type`; không thêm cột/enum | BE-ACC-02/03/05 | Account (+Architect) | Không tạo cột; nếu cần sau này phải sửa `04` + OpenAPI trước |
| ACC-DR-04 | Business/provisioning + số tài khoản | **BLOCKED** (input format RESOLVED) | OpenAPI `DestinationAccountNumber` `^[0-9]{6,20}$`; `04` §4.3.1 "format/generation là open question"; OQ-ACC-03/08 | Input bound chốt `^[0-9]{6,20}$`; **chưa** duyệt thuật toán sinh số, checksum/prefix, unique scope, provisioning idempotency | BE-ACC-03 generator, provisioning task | BA + Security + Product | Chốt policy sinh số + idempotency trước khi mở write path |
| ACC-DR-05 | Schema/balance | **RESOLVED** | `04` §4.3.1, `design/02` A.4, `design/05` §7.2 | `NUMERIC(19,2)` NOT NULL default 0; checks `>=0`, `available<=ledger`; chỉ conditional UPDATE trong T2 | BE-ACC-02/06/07/08 | Account + Transfer + Ledger | V8 constraints + real PostgreSQL concurrency tests |
| ACC-DR-06 | Business/opening balance provenance | **BLOCKED** | `04` §4.3.1 không có cột opening; OQ-ACC-05; `design/02` §10 reconciliation | Chưa có mô hình provenance; **không** thêm cột/không tự cân đối số lệch | BE-ACC-10, reconciliation | Ledger (Phương) + DBA | Chốt provenance/reconciliation projection trước BE-ACC-10 |
| ACC-DR-07 | Schema/audit + optimistic locking | **RESOLVED** | `04` §4.3.1 (chỉ `created_at/updated_at/closed_at`, không `version`) | Dùng pessimistic lock + conditional UPDATE; **không** thêm `@Version` | BE-ACC-02/06/07 | Account + Architect | Test lost-update bằng row lock thật |
| ACC-DR-08 | Schema/FK-delete-index | **RESOLVED** | `04` §4.3.1, §4.5 FK standard, §4.3 indexes | FK `RESTRICT`, không hard delete, 3 index canonical | BE-ACC-02 | Account | V8 đúng FK action + index name/order |
| ACC-DR-09 | API/surface | **RESOLVED** | `07` §8.2.2–8.2.5, OpenAPI `/accounts*`; `03` FR-009–011 | Chỉ 3 operation GET; không create/update/status/close; không pagination; không idempotency key | BE-ACC-05/09 | Account | Implement đúng 3 route, không mở thêm |
| ACC-DR-10 | API/owner projection + customer existence | **BLOCKED** | `07` §7.2.8 yêu cầu `owner{fullName}`, §8.2.5 lỗi 404 `CUSTOMER_NOT_FOUND`; design/05 §6.2 `getCustomerView` chưa có; CUS-OI-02/05 (DEC-CUS-005/006) | Account không đọc/decrypt PII Customer; cần `getCustomerView` theo `customerId` (mask theo role + existence) | BE-ACC-05 (owner block), BE-ACC-09 | Customer (+BA/Security) | Customer owner duyệt/triển khai `getCustomerView`; Account ghép sau |
| ACC-DR-11 | API/route ownership `API-ADM-02` | **RESOLVED** | `07` §8.2.5, OpenAPI tag `[Customer, Account]`, DEC-CUS-006: Customer không đọc bảng account | Account sở hữu controller/provider của `GET /api/v1/admin/customers/{customerId}/accounts`; không trùng route với `API-ADM-01` | BE-ACC-09 | Account + Admin | Một controller duy nhất, path khác `/{customerId}` |
| ACC-DR-12 | Business/status transition + `C2` audit | **BLOCKED** | `07`/OpenAPI không có endpoint đổi status; không có `ACCOUNT_STATUS_CHANGED` action được duyệt; OQ-ACC-07 | Không mở transition/endpoint; không tự định nghĩa state machine/C2 | BE-ACC-09+ (chưa có task) | Account + Audit + BA | Duyệt transition matrix + audit action trước khi mở write |
| ACC-DR-13 | Security/staff data scope | **BLOCKED** | `07` §8.2.5 "scope"; CUS-OI-05 chưa duyệt; Identity chỉ có permission `ACCOUNT_READ` | Coarse permission đã enforce; data scope (chi nhánh/segment) chưa có model | BE-ACC-09 | BA + Security + Admin | Chốt scope model trước production staff list |
| ACC-DR-14 | Port/contract | **RESOLVED** | `design/05` §7.1–7.2, `design/02` A.3/A.4, `04` §4.3.1 | Chốt `account.api.AccountQuery`/`AccountBalance` + DTO/guard như §D; transaction expectation ghi rõ | BE-ACC-04/06/07/08/09 | Account + Transfer + Ledger | Consumer bind đúng signature; thay đổi = contract change (BE-CON-45) |
| ACC-DR-15 | Audit/C3 persistence | **BLOCKED** | Audit chưa có runtime `audit_log`; DEC-CUS-005 dùng structured security log; design/01 C3 | C3 sẽ emit structured `SECURITY_ACCESS_DENIED` (không PII) như Customer; durable audit chờ module Audit | BE-ACC-05, AC-024 evidence | Audit (Thịnh) | Cung cấp `AuditModuleApi`/`audit_log`; thay adapter log bằng adapter thật |

---

## F. Source files created/modified

| File | Thay đổi |
|---|---|
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/account/api/AccountQuery.java` | **Mới** — public read contract + `Status`, `AccountSnapshot`, `AccountView`, `AccountViewQuery` |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/account/api/AccountBalance.java` | **Mới** — public T2 money contract + `LockedAccount(s)`, `BalanceAfter`, `DebitOutcome`, `DebitResult`, `CreditResult` |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/account/api/AccountContractChecks.java` | **Mới** — package-private contract guards (mask/money/ID/trace) |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/account/api/AccountApiContractTest.java` | **Mới** — 6 test bảo vệ contract guards |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/architecture/ModuleBoundaryArchTest.java` | **Sửa** — thêm 4 ArchUnit rule cho Account (api self-contained, internals isolation, single application implementor, no sibling/orchestrator import) |
| `digitalbanking-docs/docs-common/design/05. Modules contract.md` | **Sửa** §7 — chốt signature/DTO/T2 semantics; cập nhật trạng thái Account |
| `digitalbanking-docs/docs-backend/modules/_overview/03-account.md` | **Sửa** — bỏ `FROZEN`/UUID/`account_type`/`BIGINT` balance, align port/status/API |
| `digitalbanking-docs/docs-backend/account/BE-ACC-01.md` | **Mới** — báo cáo/decision register này |
| `digitalbanking-docs/docs-backend/account/README.md`, `tasks/README.md` | **Sửa** — trạng thái BE-ACC-01 và gate |
| `DECISIONS.md` | **Sửa** — thêm `DEC-ACC-001` |

Không sửa source/migration/API của Identity, Customer, Admin, Common, Transfer, Ledger hay OpenAPI.

---

## G. Kiểm thử và kết quả

Chạy trong `digitalbanking-backend` (JDK 21):

```bash
./gradlew test --tests 'com.simulatedbank.banking.account.api.AccountApiContractTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
./gradlew build --max-workers=1 --console=plain
```

| Suite | Kết quả |
|---|---|
| `AccountApiContractTest` | **6/6 PASS**, 0 skip/failure/error — mask/last4, VND, ID dương, money scale/âm, owner vs staff scope, debit result shape |
| `ModuleBoundaryArchTest` | **14/14 PASS** (10 rule cũ + 4 rule Account), 0 skip/failure/error |
| `./gradlew build --max-workers=1` | **BUILD SUCCESSFUL** (6m37s) — **84 classes / 1021 tests / 0 failure / 0 error / 0 skip**; so với baseline 1011 tests/83 classes (DEC-ADM-005) tăng đúng 10 test/1 class của task này (6 contract + 4 ArchUnit) |

Không có test nào bị đánh dấu pass khi chưa chạy. Không dùng fake/mock làm bằng chứng T2; bằng chứng concurrency/PostgreSQL thuộc BE-ACC-06/07/11.

---

## H. Blockers còn lại và owner

| Blocker | Ảnh hưởng | Owner | Điều kiện gỡ |
|---|---|---|---|
| ACC-DR-04 — sinh số tài khoản + provisioning idempotency | Write/provisioning path, generator BE-ACC-03 | BA + Security + Product | Policy format/checksum/uniqueness/idempotency được duyệt và ghi vào `04`/`07` |
| ACC-DR-06 — opening balance provenance | BE-ACC-10, reconciliation | Ledger (Phương) + DBA | Chốt mô hình provenance/projection, không thêm cột trước khi duyệt |
| ACC-DR-10 — `getCustomerView(customerId)` | `AccountDetail.owner.fullName`, 404 `CUSTOMER_NOT_FOUND` của API-ADM-02 | Customer (+BA/Security) | Provider duyệt mask oracle/scope và triển khai operation |
| ACC-DR-12 — status transition + C2 action | Mọi write lifecycle | Account + Audit + BA | Transition matrix + audit action được duyệt |
| ACC-DR-13 — staff data scope | Staff list production (BE-ACC-09) | BA + Security + Admin | Scope model được duyệt |
| ACC-DR-15 — durable audit | C3 production, AC-024 evidence | Audit (Thịnh) | `AuditModuleApi`/`audit_log` runtime |

---

## I. Tác động tới các task Account tiếp theo

| Task | Trạng thái sau BE-ACC-01 | Ghi chú |
|---|---|---|
| BE-ACC-02 (Flyway V8) | **VERIFIED** — `V8__create_account_table.sql` | Dùng bảng/constraint/index §B; không thêm cột. Chi tiết [BE-ACC-02](BE-ACC-02.md), `DEC-ACC-002` |
| BE-ACC-03 (entity/crypto/repository) | **READY (phần lưu trữ)**; generator **BLOCKED** ACC-DR-04 | `account_no_enc`/`hash`/`last4` dùng Common crypto |
| BE-ACC-04 (read/ownership/resolve) | **READY** — implement `AccountQuery` | Input number `^[0-9]{6,20}$`; không log plaintext |
| BE-ACC-05 (REST API-ACC-01/02) | **PARTIAL READY** — self list/detail khả thi; owner block + C3 durable **BLOCKED** | `owner.fullName` chờ ACC-DR-10; C3 dùng structured log tạm |
| BE-ACC-06/07 (lock, debit/credit) | **READY** — signature/semantics đã chốt | `MANDATORY`, `ORDER BY account_id ASC FOR UPDATE`, conditional UPDATE |
| BE-ACC-08 (JOIN T2) | **BLOCKED** — chờ Transfer/Ledger runtime | Consumer bind qua `account.api` |
| BE-ACC-09 (staff list) | **PARTIAL BLOCKED** — route owner đã chốt; scope chưa | ACC-DR-11 resolved, ACC-DR-13/10 blocked |
| BE-ACC-10 (provenance) | **BLOCKED** — ACC-DR-06 | Không thêm cột/số liệu giả |
| BE-ACC-11/12 (suite, evidence) | Chờ implementation | Không dùng test của task này thay bằng chứng end-to-end |

**Kết luận trạng thái:** BE-ACC-01 **PARTIAL** — schema/API/port contract đã chốt, port đã implement và verify; các quyết định provisioning, opening balance, owner projection, status transition, staff scope và durable audit vẫn BLOCKED bởi owner ngoài Account. Không có task nào khác được đánh dấu hoàn thành.

---

## J. Change log

| Version | Ngày | Nội dung |
|---|---|---|
| v1.0 | 17/11/2026 | Chốt schema/API/ports, implement `account.api`, decision register, cập nhật overview/design/05/checklist |
