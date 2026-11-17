# BE-ADM-01 — Admin integration với fraud và audit

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-ADMIN-01; **Task:** BE-ADM-01  
> **Phiên bản:** 0.2; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; implementation chưa bắt đầu  
> **Owner admin:** Đạt; **Phần fraud/audit:** Thịnh; **Reviewer đề xuất:** Đạt, Vũ, Phương  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]]; [[digitalbanking-docs/docs-common/07. API contracts.md]]; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]].

## 1. Scope và ownership

Đạt là owner chính `admin`, `approval_request` và admin orchestration. Thịnh đề xuất và triển khai phần provider fraud/audit, test C2 audit atomicity/RBAC/caller contract. Không ghi chéo `approval_request`, `fraud_flag`, `audit_log`, identity tables.

## 2. Common integration

- Common `SecurityFilterChainConfig` supplies HTTP permission gates, CSRF, CORS and safe Problem responses.
- `SessionAuthenticator` is an SPI; identity production adapter/session/CSRF persistence is absent. Common harness pass is not real RBAC/session integration.
- `RequestContextHolder` supplies correlation/request IDs; caller must propagate into audit.
- `ErrorCode`/Problem writer supplies public error mapping; do not create duplicate admin/audit error model.
- `PiiMaskingUtils` supports masking; admin/fraud/audit owner still enforce DTO field allowlists and scope.
- Process-local rate limiter does not prove cluster-wide quota, ownership, maker/checker or step-up.

## 3. Proposed integration contract

- Admin calls AUD-01 `recordInTransaction` for C2 sensitive state; audit failure rolls back the same transaction.
- Security denied C3 follows agreed standalone/fallback behavior; do not rollback security enforcement.
- Fraud flag review occurs in fraud-owned service; admin does not update table directly.
- Identity provides trusted principal/permissions/step-up proof; caller must not accept actor/checker IDs as authority from client.
- No HTTP localhost proxy between modules; use reviewed internal ports/DTOs.

## 4. API/security boundaries

- Audit list/detail/export use existing `/api/v1/audit*` contract.
- Fraud list/review use `/api/v1/fraud-flags*` contract.
- Approval role/limit endpoints remain admin-owned.
- Fraud flag review does not approve/complete/reverse a transfer.
- Do not silently add fraud review/legal hold to approval enum; source/OpenAPI must change first.
- Role matrix and self-approval error code follow `07` + owner decisions; differences between source docs remain Open.

## 5. Test/acceptance

Unit/contract tests with fake identity validate local mapping only. PostgreSQL integration checks concurrent decision, C2 audit failure rollback and no cross-module table write. Real identity/admin integration must prove role/ownership, maker≠checker, CSRF/step-up and audit trace; until then mark Blocked, not Verified.

## 6. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Admin/fraud/audit collaboration proposal |
| 0.2 | 12/11/2026 | Aligned Common security/context/error ownership |
