# BE-ACC-DOC-01 — Kế hoạch triển khai Account

> Digital Banking MVP | Baseline: `TNoLisme/cloude`, branch `dev2`, tree `6d4ce7a2d346f70c47ec71924a4989d004cd85f1` (17/11/2026).  
> Trạng thái: **PLANNED — documentation only**. Tài liệu này không chứng nhận code đã triển khai.
> **Cập nhật 17/11/2026:** BE-ACC-02/03/04 đã triển khai và verify trong working tree (xem [BE-ACC-02](BE-ACC-02.md), [BE-ACC-03](BE-ACC-03.md), [BE-ACC-04](BE-ACC-04.md), [checklist](tasks/README.md)); phần mô tả "Account production chỉ có `.gitkeep`" dưới đây là hiện trạng tại baseline và không còn đúng cho working tree hiện tại.

## 1. Nguồn tham chiếu và hiện trạng

- `digitalbanking-docs/docs-common/03. SRS.md`: FR-009–012, FR-015, FR-039, FR-049; NFR-009/010; AC-005/006/023/024/027.
- `docs-common/04. Database design & analysis.md` §4.3.1: schema `banking.account`, integrity, money invariants.
- `docs-common/07. API contracts.md` §8.2: API-ACC-01/02, API-ADM-02; RBAC, response/error.
- `docs-common/design/05. Modules contract.md` §6–7, §13: Customer and Account ports, JOIN T2, consumer tests.
- `docs-common/design/02. consistency-concurrency.md`: T0–T3, rollback, T-CON-01–04.
- `docs-backend/00. architecture & conventions.md`, `modules/_overview/03-account.md`, OpenAPI, `AGENTS.md`.

Đã kiểm tra tree `dev2`: Account production `api`, `application`, `domain`, `adapter/in`, `adapter/out` chỉ có `.gitkeep`; test chỉ có boundary fixture, chưa có functional Account implementation. Identity đã có authentication, session, permission; Common có security, crypto, masking, error/context; Transfer có `AccountOwnershipPort` ở application. Không copy các implementation chung và không nhầm historical status của revision khác với current code.

## 2. Scope và write-ownership

**Account chịu trách nhiệm:** tài khoản ngân hàng; account number encrypted + blind index + last4; ownership và masked account view; status; `available_balance` và `ledger_balance`; row locking và conditional debit/credit trong Transfer T2; opening-balance provenance phục vụ reconciliation.

**Không chịu trách nhiệm:** authentication/RBAC (Identity); customer profile/KYC (Customer); transfer state/Idempotency-Key (Transfer); double-entry `ledger_entry` (Ledger); limits (Limit); audit subsystem (Audit); fraud (Fraud); notification (Notification); orchestration admin (Admin). Chỉ Account được ghi bảng `banking.account`.

| Dependency | Contract / yêu cầu |
|---|---|
| Identity/Common | Identity principal/customer scope; RBAC `ACCOUNT_READ_SELF`/`ACCOUNT_READ`; common crypto, masked logging, error, security |
| Customer | Customer identity/status qua public query port, không import persistence implementation |
| Transfer | Account query/ownership và balance ports, JOIN cùng physical T2 transaction |
| Ledger | Account cung cấp balance-after và opening provenance; Ledger sở hữu bút toán |
| Admin | API-ADM-02: staff scope + `ACCOUNT_READ`; tránh 2 controller cùng route |
| Audit | Ownership denied `SECURITY_ACCESS_DENIED` C3; future status change C2 theo policy |

## 3. Database design

Theo `04` §4.3.1: `account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`; `customer_id BIGINT NOT NULL` FK; `account_no_enc BYTEA NOT NULL`; `account_no_hash CHAR(64) UNIQUE NOT NULL`; `account_no_last4 CHAR(4) NOT NULL`; `currency CHAR(3)='VND'`; `status VARCHAR(16)` (`ACTIVE`, `LOCKED`, `CLOSED` trong schema nguồn); `available_balance NUMERIC(19,2)` và `ledger_balance NUMERIC(19,2)`, mặc định 0; `closed_at`, `created_at`, `updated_at TIMESTAMPTZ`. Constraints `available_balance >= 0`, `ledger_balance >= 0`, `available_balance <= ledger_balance`; indexes `account_no_hash`, `customer_id`, `status`. FK không CASCADE xóa financial records.

- Dùng Common AES-256-GCM để lưu account number, HMAC-SHA256 cho blind index, chỉ xuất last4/masked; không lưu plaintext ở logs/DTO.
- Không dùng `float/double` cho tiền; không update balance ngoài T2; không hard delete financial account.
- `ledger_balance = opening_balance + SUM(credits) - SUM(debits)` theo DEC-LED-003, **phải có provenance** cho opening balance; không im lặng tự cân đối khi số liệu lệch.
- **Conflict cần chốt:** overview nêu `FROZEN` nhưng schema `04` dùng `LOCKED`; overview cho ID `UUID/BIGINT` nhưng `04` dùng `BIGINT`; overview có `CHECKING/SAVINGS` nhưng bảng `04` §4.3.1 chưa có `account_type`. Không tự đổi schema.

## 4. Workflow nghiệp vụ

### 4.1 Read list/detail

1. Common/Identity xác thực session và resolved actor/customerId từ server; không nhận owner từ client.
2. Validate `accountId` đúng `BIGINT > 0`, optional scope đã định nghĩa.
3. List self query theo authenticated `customer_id`; detail chỉ cho owner hoặc staff có `ACCOUNT_READ` và scope hợp lệ.
4. Other-owner: `403 AUTH_FORBIDDEN` + C3 audit; not found: `404` theo API contract; không lộ detail khi từ chối.
5. Map immutable read model với `maskedAccountNumber`, balance, status, timestamp theo OpenAPI; không decrypt account number để hiển thị.
6. DB/authorization unavailable: fail closed; không trả danh sách trống giả hoặc bỏ kiểm tra scope.

### 4.2 Resolve destination

1. Validate format/normalize account number theo policy phải chốt (OQ-02).
2. Tính HMAC-SHA256 qua Common, tìm trên `account_no_hash` unique index; không log plaintext.
3. Trả minimal snapshot `accountId`, `customerId`, `status`, `currency`, masked last4; không lộ full owner PII.
4. Preflight snapshot không thay row lock và revalidate trong T2.

### 4.3 Money processing (Account provider JOIN Transfer T2)

1. Transfer owns idempotency/step-up và T1; Account không tự insert idempotency record.
2. Trong T2 đã lấy guard/transfer lock, Account khóa source/destination row theo ID ASC bằng `SELECT ... ORDER BY account_id ASC FOR UPDATE`; không dùng lock token sau transaction.
3. Revalidate owner source, cả hai `ACTIVE`, `VND`, amount > 0, source ≠ destination, available balance đủ.
4. `debitConditional` dùng `UPDATE ... WHERE status='ACTIVE' AND available_balance >= :amount RETURNING ...`; 0 rows được phân loại `Insufficient/Inactive` khi chưa debit.
5. `credit` dùng conditional `ACTIVE`; nếu credit 0 rows/DB error **sau debit** thì technical failure, rollback **toàn T2**, không commit một phía hay tùy tiện ghi `FAILED` trong transaction chứa partial debit.
6. Ledger double-entry, limits, audit mandatory và terminal transfer commit cùng transaction theo orchestrator; balance-after truyền sang Ledger; Account không ghi `ledger_entry`.
7. Deadlock/timeout: rollback cả transaction, retry toàn T2 chỉ theo orchestrator protocol; unknown commit outcome phải reconcile/verify primary, không phỏng đoán hay retry từng SQL.
8. Test trên PostgreSQL: T-CON-01..04, overdraft, opposite transfers A↔B, injection sau debit/trước credit/trước ledger, failure audit, lost update, isolation.

Lock order giảm nguy cơ deadlock, không bảo đảm tuyệt đối không deadlock.

### 4.4 Status / lifecycle và failure

Không có public create/freeze/close endpoint trong `07`/API overview của MVP. Chỉ lên kế hoạch provision/close internal nếu product policy được approve: validate customer, generate unique account number, crypto, opening provenance, status transition và audit. Tuyệt đối không tự thêm REST operation ngoài hợp đồng. GET là side-effect-free, không cần `Idempotency-Key`; Transfer owns transfer idempotency (`>=24h` after terminal), Account chỉ đảm bảo atomic balance mutation dưới T2. Account provisioning idempotency là open question riêng.

## 5. HTTP và internal contracts

| API | Actor | Acceptance |
|---|---|---|
| `GET /api/v1/accounts` | CUSTOMER | Chỉ self accounts, masked number, `items`/`asOf` theo OpenAPI |
| `GET /api/v1/accounts/{accountId}` | Owner or STAFF with `ACCOUNT_READ` | Correct masked detail; foreign owner 403+C3; missing 404; no data leak |
| `GET /api/v1/admin/customers/{customerId}/accounts` | STAFF with `ACCOUNT_READ` | Staff-scope list; CUSTOMER forbidden; agree HTTP controller owner with Admin |

Public `account.api` theo `design/05`: read `resolveDestination`, `getAccountView`, `assertOwnership`; money `lockTransferAccounts`, `debitConditional`, `credit` (**JOIN T2**). Không lập trùng interface trong Transfer; đối chiếu với `transfer.application.AccountOwnershipPort` trước khi thay provider/consumer contract. Chính xác DTO/status/nullability theo OpenAPI và `07` trước lúc triển khai.

## 6. Task breakdown

| ID | Priority | Dependency | Work | Acceptance criteria |
|---|---|---|---|---|
| BE-ACC-01 | P0 | Shared docs | Reconcile SRS/DB/OpenAPI/module port, record open decisions | Traceability complete; conflicts + owner documented |
| BE-ACC-02 | P0 | 01, Customer schema | Flyway account DDL/index/check/FK | Fresh + upgrade PostgreSQL migration pass; no destructive migration |
| BE-ACC-03 | P0 | 02, Common crypto | Account entity, number encryption/blind index, repository | Unique HMAC, masked last4, no plaintext in log/DB/DTO |
| BE-ACC-04 | P0 | 03, Identity/Customer | Query/resolve/ownership service and ports | Positive/negative owner, invalid number, DB fail-closed |
| BE-ACC-05 | P0 | 04, Common/Audit | API-ACC-01/02, auth/validation/errors | HTTP+OpenAPI contract, 401/403/404, C3 denied audit |
| BE-ACC-06 | P0 | 02, contract | Row lock repository, ID ascending | Real PG concurrency tests prove lock semantics |
| BE-ACC-07 | P0 | 06 | Conditional debit/credit | No negative balance; rollback after credit error |
| BE-ACC-08 | P0 | 07, Transfer/Ledger | JOIN T2 integration | Same physical TX, atomic money+ledger, consumer contract pass |
| BE-ACC-09 | P1 | 04, Admin | Staff customer accounts endpoint/provider | Staff `ACCOUNT_READ`/scope validated, no duplicate route |
| BE-ACC-10 | P1 | 02, Ledger | Opening-balance provenance and reconciliation projection | Reconcile exact, alert on mismatch/unverifiable baseline |
| BE-ACC-11 | P0 | 05–10 | Unit/contract/PG/security/failure/concurrency suite | T-CON-01..04, IDOR, atomicity, migration evidence |
| BE-ACC-12 | P1 | 11 | Verification/report/hand-off | Links to commit/PR/run, test counts, blockers, reviewer |

**Triển khai theo pha:** A: 01→02/03→04→05 (dùng test fake nếu dependency chưa có). B: 06→07→08 phối hợp Transfer/Ledger. C: 09/10→11→12 và end-to-end. Không coi fake/mock test là bằng chứng T2 production.

## 7. Open decisions & acceptance gates

> **Cập nhật 17/11/2026:** OQ-ACC-01/02/04/06 đã được phân xử và OQ-ACC-03/05/07/08 được tách thành 6 blocker cụ thể tại [BE-ACC-01 §E](BE-ACC-01.md#e-decision-register) / [DEC-ACC-001](../../../../DECISIONS.md). Bảng dưới giữ nguyên làm trace lịch sử.

| ID | Blocker | Hướng xử lý |
|---|---|---|
| OQ-ACC-01 | `FROZEN` vs `LOCKED` | Baseline theo DB design là `LOCKED`, owner confirm |
| OQ-ACC-02 | ID UUID vs BIGINT | Baseline `BIGINT`, align DTO |
| OQ-ACC-03 | Format/generation of account number | BA/Security decision; chưa tự tạo policy |
| OQ-ACC-04 | `account_type` absent in DB table | Confirm whether schema extension required |
| OQ-ACC-05 | Opening balance provenance | Coordinate Ledger/DBA, no silent correction |
| OQ-ACC-06 | Admin API route ownership | Single route owner with Admin |
| OQ-ACC-07 | Account status-changing operations and audit C2 | No unapproved public endpoint |
| OQ-ACC-08 | Idempotent account provisioning | Separate contract/policy pending approval |

**Done ≠ Verified:** `PLANNED`, `BLOCKED`, `IN_PROGRESS`, `IMPLEMENTED`, `NOT_TESTED`, `VERIFIED` must be grounded in revision-specific evidence. All new tasks in this document are unimplemented on reviewed baseline. Do not claim release readiness without real PostgreSQL, provider/consumer integration and review sign-off.
