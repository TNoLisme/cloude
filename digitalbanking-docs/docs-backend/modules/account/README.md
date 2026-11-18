# Account module

Trạng thái: **Schema `V8` VERIFIED (BE-ACC-02) + read/crypto/repository COMPLETED (BE-ACC-03) + read/ownership/resolve COMPLETED (BE-ACC-04) + REST/security COMPLETED (BE-ACC-05) + ASC row lock COMPLETED (BE-ACC-06) + conditional debit/credit + rollback COMPLETED (BE-ACC-07) + staff list PARTIAL (BE-ACC-09) + PostgreSQL concurrency/security/failure tests COMPLETED (BE-ACC-11) + evidence/handoff COMPLETED (BE-ACC-12)**, baseline `07188e9` (18/11/2026). Bảng `banking.account` có migration canonical; Account có entity/crypto/blind index/read service, REST `GET /api/v1/accounts[/{id}]` và `GET /api/v1/admin/customers/{customerId}/accounts`, pessimistic row lock `ORDER BY account_id FOR UPDATE` và money path conditional debit/credit trong T2 (`AccountBalanceService`, rollback same-transaction); consumer T2 thật (Transfer/Ledger) vẫn chờ BE-ACC-08; opening provenance/reconciliation BLOCKED (BE-ACC-10). Account suite **128/128 PASS** trên PostgreSQL 16; full `clean build --max-workers=1` **PASS 1244/1244** (8m20s) — xem [BE-ACC-12](BE-ACC-12.md).

- [BE-ACC-01 — Resolve schema/API/ports & open decisions](BE-ACC-01.md)
- [BE-ACC-02 — Flyway schema, constraints & indexes](BE-ACC-02.md)
- [BE-ACC-03 — Entity / Crypto / Blind index / Repository](BE-ACC-03.md)
- [BE-ACC-04 — Account Read / Ownership / Resolve](BE-ACC-04.md)
- [BE-ACC-05 — GET Account APIs + security/C3](BE-ACC-05.md)
- [BE-ACC-06 — ASC row lock FOR UPDATE](BE-ACC-06.md)
- [BE-ACC-07 — Conditional debit/credit và rollback](BE-ACC-07.md)
- [BE-ACC-09 — Staff-scoped list `API-ADM-02`](BE-ACC-09.md) — PARTIAL
- [BE-ACC-10 — Opening provenance/reconciliation](BE-ACC-10.md) — BLOCKED
- [BE-ACC-11 — PostgreSQL concurrency, security & failure tests](BE-ACC-11.md) — COMPLETED (Account scope)
- [BE-ACC-12 — Verification evidence & handoff](BE-ACC-12.md) — COMPLETED
- [BE-ACC-DOC-01 — Kế hoạch triển khai và phân rã công việc](BE-ACC-DOC-01.md)
- [Task checklist](tasks/README.md)
- [Module overview](../_overview/03-account.md)
- Nguồn chuẩn: `docs-common/03. SRS.md`, `04. Database design & analysis.md` §4.3.1, `07. API contracts.md` §8.2, `design/02. consistency-concurrency.md`, `design/05. Modules contract.md` §7, OpenAPI.

Đã chốt tại BE-ACC-01: status `ACTIVE|LOCKED|CLOSED` (không `FROZEN`), ID `BIGINT` (không UUID), không có `account_type` trong MVP, balance `NUMERIC(19,2)`, chỉ 3 operation GET, port `account.api.AccountQuery`/`AccountBalance` với transaction expectation rõ ràng. BE-ACC-02 đã tạo schema: `V8__create_account_table.sql` (12 cột, PK/FK RESTRICT/unique blind index/8 CHECK/4 index) chạy sạch trên PostgreSQL 16 fresh + upgrade từ `V7` — chi tiết [BE-ACC-02](BE-ACC-02.md). BE-ACC-03 đã hiện thực `AccountEntity`/`AccountJpaRepository`/`AccountReadPort`, crypto AES-256-GCM + HMAC-SHA256 blind index qua Common, normalize strict `^[0-9]{6,20}$` và test PostgreSQL 16 — chi tiết [BE-ACC-03](BE-ACC-03.md). BE-ACC-04 đã hiện thực `AccountQueryService` (find/resolve/view/ownership) và `AccountAccessService` (userId→customer self read) với 403 + C3, 404 không audit — chi tiết [BE-ACC-04](BE-ACC-04.md). REST adapter `API-ACC-01/02` có runtime với 401/403/404/400 + C3 metric (BE-ACC-05, `owner.fullName`/durable C3/staff scope còn BLOCKED); row lock `FOR UPDATE` có PostgreSQL 16 concurrency evidence (BE-ACC-06). Conditional debit/credit + rollback (BE-ACC-07) đã có runtime với PostgreSQL 16 concurrency/rollback evidence; T2 end-to-end với Transfer/Ledger (BE-ACC-08) chưa có vì chưa có consumer/orchestrator. Các quyết định còn **BLOCKED**: chính sách sinh số tài khoản/provisioning, opening balance provenance, `getCustomerView` cho `owner.fullName`/404 customer, status transition + C2 audit, staff data scope, durable audit C3 — xem [decision register](BE-ACC-01.md#e-decision-register).
