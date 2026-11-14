# BE-AUD-02 — Audit query, export và verification

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-02; **Task:** BE-AUD-02  
> **Phiên bản:** 0.2; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; implementation chưa bắt đầu  
> **Owner:** Thịnh; **Reviewer đề xuất:** Đạt (identity/RBAC), Phương (common/DB); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.6; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §7, §9, §13; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và Common integration

FR-036–038, AC-020–022, NFR-021: search/detail/export audit đã mask, verify hash chain.

Reuse Common:
- `JcsCanonicalizer` cho filter hash/export manifest và stored canonical payload verification.
- `RequestContextHolder`/correlation filter cho response trace.
- `ErrorCode.AUDIT_NOT_FOUND`, `EXPORT_TOO_LARGE`, `AUTH_FORBIDDEN`, `AUDIT_WRITE_FAILED` và common Problem writer.
- Security chain/CSRF/CORS/headers/no-store từ Common; identity vẫn phải provide real principal/permission.

Không tạo JCS, Problem, correlation hoặc authorization duplicate trong audit.

## 2. Scope và dependency

Phụ thuộc AUD-01 writer/hash protocol. Không bao gồm retention/purge, external anchor/WORM, identity implementation hoặc thay đổi OpenAPI. Mock principal chỉ dùng component test; không claim RBAC integration.

## 3. Component proposal

| Component | Responsibility |
|---|---|
| `AuditController` | Parse/validate OpenAPI request; delegate; no SQL |
| `AuditQueryService` | Search/detail, permission/data scope |
| `AuditExportService` | Snapshot, masked artifact, C2 audit before response |
| `AuditChainVerifier` | Recanonicalize stored JSON and verify hash_version/prev_hash/record_hash |
| `AuditQueryJdbcStore` | Parameterized SQL, bounded pagination |
| `LocalExportArtifactStore` | Private temp artifact, cleanup |

Do not expose service/API until AUD-01 hash protocol and identity permission contract are reviewed.

## 4. Data and hash verification

Query `banking.audit_log` read-only. Never hash PostgreSQL JSONB `::text` directly. Read stored `canonical_payload`, convert to JSON-compatible structure, pass through Common `JcsCanonicalizer`, then calculate using AUD-01 hash protocol. Stored JSONB may reorder keys; verifier must normalize through JCS.

Search:
- Parameterized filters.
- Sort whitelist `occurredAt`/approved fields only.
- `from` inclusive, `to` exclusive.
- Default 30 days, max 366 days.
- Page size 1–100.
- Mandatory bounded `LIMIT`.

## 5. REST and security

- `GET /api/v1/audit`: `AUDIT_READ`, OpenAPI `AuditPage`.
- `GET /api/v1/audit/{auditId}`: `AUDIT_READ`, `AuditDetail`, 404 `AUDIT_NOT_FOUND`.
- `GET /api/v1/audit/export`: `AUDIT_EXPORT`, CSV/JSONL, maxRows 10,000, 413 `EXPORT_TOO_LARGE`.

Common security chain supplies headers, correlation, CORS/CSRF and Problem output. Identity must supply real authorities and data scope. No component fixture can prove customer/staff/auditor access without identity adapter.

## 6. Export flow

1. Authenticate/authorize with Common + identity.
2. Validate filters and bounds.
3. Freeze upper audit ID/snapshot.
4. Materialize masked CSV/JSONL in private temporary storage.
5. Calculate file hash/filter hash through Common JCS/SHA boundary.
6. Write `AUDIT_EXPORTED` C2 with AUD-01 recorder.
7. Send bytes only after audit commit.
8. Cleanup artifact after response/disconnect; orphan cleanup required.

Audit failure means no file bytes. No C3 best-effort for successful export evidence.

## 7. Tests

- Search filters, sort injection, page bounds and date windows.
- OpenAPI schema, Problem response, correlation/no-store headers.
- Role matrix with test identity and real integration later.
- Export 0/10,000/10,001 rows, CSV/JSONL, mask, hash and cleanup.
- Export failure before response: no bytes/orphan.
- Chain valid/interior tamper/missing record/unknown hash version.
- JSONB key reorder still verifies after Common JCS recanonicalization.

## 8. Blockers and handoff

- AUD-01 hash version/formula source conflict unresolved.
- Identity authorities/data scope absent.
- Common JCS available, but audit must adopt it instead of local canonicalizer.
- `AUDIT_EXPORTED` transaction depends on reviewed AUD-01 caller port.
- External anchor remains outside task.

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial query/export/verifier proposal |
| 0.2 | 12/11/2026 | Aligned with Common JCS/context/error/security implementation |
