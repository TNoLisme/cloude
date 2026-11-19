# customer

Với POST Transfer và key mới, Customer INACTIVE nhận 403 trước preflight account. Request có key đã tồn tại đi theo phân loại idempotency. CustomerQuery tiếp tục cung cấp snapshot cho các consumer. [BE-INT-05](../../workofteam/integration%20modules/BE-INT-05.md)

[BE-INT-03](../../workofteam/integration%20modules/BE-INT-03.md) xác minh Customer self và hồ sơ staff đã mask trên PostgreSQL. Staff exact-ID theo DEC-INT-008 cho single-bank MVP. Deny sau xác thực nối với Audit C3 theo DEC-INT-009.

> Baseline thiết kế `dev2@56210cc` (16/11/2026); BE-CUS-IMP-01/02/03 đã có implementation và PostgreSQL evidence trên branch `datht-backend-rework`.

- [Thiết kế chi tiết và phụ thuộc](design.md)
- [Task breakdown và checklist](tasks/README.md)
- [BE-CUS-IMP-01 — Flyway migration `banking.customer`](BE-CUS-IMP-01.md)
- [BE-CUS-IMP-02 — JPA entity, repository và PostgreSQL mapping](BE-CUS-IMP-02.md)
- [BE-CUS-IMP-03 — AES-GCM encrypted PII và masking dùng Common](BE-CUS-IMP-03.md)
- [BE-CUS-IMP-04 — CustomerQuery public API + immutable minimal DTO](BE-CUS-IMP-04.md)
- [BE-CUS-IMP-05 — GET /api/v1/customers/me + Customer Profile Service](BE-CUS-IMP-05.md)
- [BE-CUS-IMP-06 — Staff GET /api/v1/admin/customers/{customerId} (PARTIAL)](BE-CUS-IMP-06.md)
- [BE-CUS-IMP-07 — Identity/Account/Admin/Audit integration (PARTIAL)](BE-CUS-IMP-07.md)
- [BE-CUS-IMP-08 — Security/API/Database regression và evidence (PARTIAL)](BE-CUS-IMP-08.md)
- [BE-CUS-IMP-09 — Provision/create/update/status contract gate (BLOCKED)](BE-CUS-IMP-09.md)
- [BE-CUS-IMP-10 — Staff search/full-PII contract gate (P2 BLOCKED)](BE-CUS-IMP-10.md)

Customer sở hữu `banking.customer`, PII hồ sơ và masked customer projection. Identity giữ authentication; Account giữ account/ownership; Admin giữ staff authorization; Audit giữ audit event. Flyway V5, JPA, AES-GCM/masking, `CustomerQuery.findByUserId`, self API và staff API đã có. BE-CUS-IMP-07 xác minh Identity session/RBAC thật từ HTTP tới PostgreSQL, chống client giả role/permission, C3 audit có actor/target/outcome/correlation/request ID, failure policy và ArchUnit boundary. Trạng thái tổng vẫn **PARTIAL**: Account runtime chưa có nên ownership/account status E2E BLOCKED; Admin data-scope CUS-OI-05 và durable Audit `AuditModuleApi`/`audit_log` chưa có. `_overview/02-customer.md` còn xung đột CIF/KYC/national ID/status với `docs-common/04`; không tự bổ sung (IMP-04 bỏ `kycStatus` theo DEC-CUS-003). BE-CUS-IMP-09 giữ **BLOCKED** vì chưa có Customer write API/authorization/C2 audit/idempotency/status-transition contract; primitive create/owner-update nội bộ đã được regression test, không được coi là production workflow.
