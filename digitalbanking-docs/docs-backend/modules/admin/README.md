# admin

> **Mã tài liệu:** `BE-MOD-ADMIN-01`
>
> **Phạm vi:** thiết kế, hợp đồng và kế hoạch triển khai module `admin`
>
> **Trạng thái:** **PARTIAL** — domain/persistence, Checker step-up, Admin idempotency (V7) và ROLE_CHANGE proposal/decision end-to-end đã triển khai, kiểm chứng; LIMIT_CHANGE proposal/approve và durable Audit còn BLOCKED
>
> **Baseline tham chiếu:** nhánh `datht-backend-rework`
>
> **Owner theo phân công:** Đạt; phối hợp Thịnh (`audit`/`fraud`), Vũ (`limit`), Identity/Customer/Account owners

## Mục lục

- [Thiết kế triển khai](design.md)
- [Chốt hợp đồng BE-ADM-CON-01](BE-ADM-CON-01.md)
- [Step-up binding BE-ADM-CON-02](BE-ADM-CON-02.md)
- [Idempotency BE-ADM-CON-03](BE-ADM-CON-03.md)
- [Idempotency persistence/concurrency BE-ADM-DB-03](BE-ADM-DB-03.md)
- [Task breakdown và checklist](tasks/README.md)

## Kết luận hiện tại

- `admin` là **orchestrator** của workflow Maker-Checker, sở hữu write-model `banking.approval_request`; `identity` và `limit` vẫn là write-owner của dữ liệu bị thay đổi.
- **Đã triển khai:** `admin.domain` (`ApprovalRequest`, typed payload, state machine), `admin.application.port.ApprovalRequestStore`, JPA entity/repository/store, Flyway `V6__create_approval_request_table.sql`; test unit + PostgreSQL 16 pass.
- **Đã kiểm chứng (DB-03):** `V7` metadata thật trên PostgreSQL 16 fresh database — 13 cột/type/length/nullability/identity/default, PK, 2 FK `RESTRICT`, 10 CHECK, unique `uq_admin_idempotency_scope (actor_id, operation, scope_key, idempotency_key)`, 3 index phụ non-partial; positive/negative DML (23505/23514/23503/22001) PASS. Concurrency/rollback: winner rollback → loser đang block nhận ownership; failure tại response-persistence rollback cả approval + claim rồi retry cùng key; `RECEIVED` hết hạn không bị purge và vẫn `409 IDEMPOTENCY_IN_PROGRESS`. Cross-tenant vẫn BLOCKED vì schema v1 không có tenant. Chi tiết: [BE-ADM-DB-03.md](BE-ADM-DB-03.md).
- **Đã triển khai (CON-02):** `ApprovalStepUpBinding` (SHA-256 canonical v1 + `bindingKey=approvalId`), `ApprovalStepUpService` (428 bind + consume JOIN), `ApprovalDecisionService` (entry point transition duy nhất: lock, Maker–Checker, consume, provider role, audit C2), `AdminAuthorization`; 31 test mới (7 unit binding, 7 unit gate, 17 PostgreSQL/Identity) PASS.
- **Đã triển khai (CON-03):** `admin_idempotency_record` V7 unique `(actor_id, operation, scope_key, idempotency_key)`; `AdminIdempotencyService` insert-first (`ON CONFLICT DO NOTHING`), replay/conflict/in-progress, TTL 24h + purge terminal-only; `AdminRequestHasher` JCS SHA-256; Maker step-up bind idempotency key + proposal hash; `RoleAdminController`/`LimitAdminController`; 40 test mới. ROLE_CHANGE proposal/decision end-to-end; LIMIT_CHANGE proposal/approve fail-closed 503.
- **Đã chốt trong CON-01:** `04` §4.5.3 là DDL canonical; `PENDING → APPLIED/REJECTED/CANCELLED`; `APPROVED` chỉ schema-reserved; target mapping limit/role; `ROLE_CHANGE.expectedVersion` do server capture từ Identity.
- **REST đã mở có kiểm soát:** `POST /api/v1/admin/roles`, `POST /api/v1/admin/roles/{id}/approve`, `POST /api/v1/admin/limits/{id}/approve` (reject chạy, approve 503), `POST /api/v1/admin/limits` (validate rồi 503). Tất cả yêu cầu `Idempotency-Key`; payload/decision replay đúng body gốc + `Idempotent-Replayed: true`.
- `identity.api.StepUpChallengePort` và `IdentityRoleCommand` đã có runtime; Admin gọi chúng trong transaction quyết định.
- Common đã có security route gates và error catalog approval; đây là boundary plumbing, không phải admin runtime hoàn chỉnh.
- Customer đã có exact-ID masked lookup `GET /api/v1/admin/customers/{customerId}` nhưng data-scope staff và durable Audit còn mở. Account, Limit và Audit runtime tương ứng chưa đủ để đóng integration admin.

## Phạm vi tài liệu

`design.md` giữ thiết kế/khảo sát ban đầu. `BE-ADM-CON-01.md` chốt scope, actor/scope matrix, payload schema, state machine, DB, transaction/failure, dependency và test evidence. `BE-ADM-CON-02.md` chốt canonical request hash, binding key, 428 flow, decision gate, failure/concurrency và ma trận 16 kịch bản. `BE-ADM-CON-03.md` chốt idempotency scope/hash/TTL/replay/conflict/in-progress, Maker step-up và evidence 40 test mới. `tasks/README.md` là checklist triển khai theo dependency.

Các quyết định chưa được phân xử trong `docs-common` được giữ là **BLOCKED/OPEN**, không được biến thành hành vi production bằng cách suy diễn trong tài liệu module. Khi provider chưa có, chỉ dùng fixture/fake ở test theo `Modules contract §13.1`; không đưa fake thành production bean.
