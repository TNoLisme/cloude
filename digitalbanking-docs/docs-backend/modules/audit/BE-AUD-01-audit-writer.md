# BE-AUD-01 — Audit writer và internal port

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-01; **Task:** BE-AUD-01  
> **Phiên bản:** 0.4; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — component code staged/chưa review; integration blocked  
> **Owner:** Thịnh; **Reviewer đề xuất:** Phương (common/ledger), Đạt (identity/admin), Vũ (transfer); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và trạng thái sau Common merge

AUD-01 cung cấp audit writer C1/C2/C3/C4 cho module nghiệp vụ, append-only `audit_log`, hash chain và transaction atomicity.

Common branch đã cung cấp cross-cutting implementation. AUD-01 phải reuse, không duplicate:

| Capability | Common implementation | Audit usage |
|---|---|---|
| RFC 8785 | `common.crypto.JcsCanonicalizer` | Canonicalize raw JSON / canonical payload; dependency alias `libs.jcs` |
| SHA/HMAC | `common.crypto.HmacService` | SHA-256 bytes/hash; HMAC reserved for caller-specific keyed index |
| Context | `common.context.RequestContextHolder` | Correlation/request ID at integration boundary |
| PII masking | `common.crypto.PiiMaskingUtils` | Caller prepares masked fields; audit validator enforces allowlist |
| Error | `common.error.ErrorCode.AUDIT_WRITE_FAILED`, common Problem writer | Map failure at API boundary; no duplicate public error catalog |

`AuditCanonicalJson` là adapter cho Java values và kiểm tra safe-integer/depth riêng của audit; RFC 8785 được giao cho Common `JcsCanonicalizer`, kể cả scalar/null root. SHA-256 dùng Common `HmacService.sha256Hex`. Không duy trì thuật toán JCS thứ hai.

**Current evidence:** fresh targeted matrix 14/11/2026 passes 139 tests. See verification report for exact breakdown, scope and owner decisions. AUD-01 remains incomplete; no owner sign-off.

## 2. Scope

**In scope:**
- Source-backed action catalog and allowed actor/entity metadata.
- Immutable `AuditEvent` and deep snapshot copy.
- Event validation, entity allowlists, resource limits and safe client info.
- Canonical payload/hash v1 proposal.
- `AuditRecorder` C1/C2 component implementation.
- V2 audit migration proposal and disposable PostgreSQL tests.
- Append-only mutation protection and runtime app-role checks.

**Out of scope until owner review:**
- Public `AuditModuleApi` exposed to identity/transfer/admin.
- Caller-specific actor/session/permission adapters.
- Full identity FK/migrations.
- REST query/export, retention/legal hold, external anchor.
- Performance/SLO claim and production deployment.

## 3. Ownership and APIs

Internal current API:

```java
AuditRecordRef recordInTransaction(AuditEvent event, Instant occurredAt)
```

`AuditRecorder` uses `Propagation.MANDATORY` và chỉ nhận action C1/C2; C3/C4 bị từ chối trước DB lock. Caller sở hữu transaction. Common sở hữu request context/security/error primitives. Audit không triển khai authentication hoặc authorization thay identity.

Proposed future boundary remains:

```java
public interface AuditModuleApi {
    AuditRecordRef recordInTransaction(AuditEvent event);
    Optional<AuditRecordRef> recordStandalone(AuditEvent event);
}
```

Do not expose this port until Phương, Đạt and Vũ review DTO names, context source, error mapping and transaction semantics.

## 4. Action catalog

Catalog contains action groups from design/01 §5.3. Unknown actions fail closed. `RETENTION_PURGED` and `SECURITY_ACCESS_DENIED` require owner review and validator rejects them until contracts are resolved. Other metadata remains partially reviewed; do not treat every listed action as caller-ready.

Each action defines:
- Criticality C1/C2/C3/C4.
- Allowed actor types.
- Entity type.
- Required state fields.

Any source action missing from code remains an implementation gap. Add action only from source catalog with test metadata; do not invent wildcard actions.

## 5. Event validation and snapshots

Validator enforces:
- Actor type allowed by action; USER requires positive actor ID; SYSTEM has no user ID.
- Non-empty actor role.
- Entity type matches action and entity ID uses safe identifier format.
- Outcome `SUCCESS/FAILURE/REJECTED`.
- Correlation/request IDs use safe characters and bounded length.
- Amount/currency: VND, non-negative, max two decimals; null amount requires null currency.
- Entity-specific snapshot allowlist from design/01 §5.6.
- Required status/amount/currency fields per action.
- Scalar value types for IDs, money, status and counters.
- Masked IPv4 and bounded user agent; no full PII, token, OTP, password, notes or description.
- Snapshot field count, byte estimate and nesting limit.

`AuditEvent` deep-copies Maps/Lists at construction. Unsupported object types fail before persistence. This is input validation, not full PII scanner; caller and integration layer still need scanner/mapper tests.

## 6. Canonical payload and hash protocol

Current proposal follows design/01 §7.2–7.3:

- JCS canonical payload with fixed v1 fields.
- `hash_version` appears once inside payload.
- Amount fixed two-decimal text.
- IDs/money in state represented as strings according to source protocol.
- `record_hash = lowercase_hex(SHA-256(UTF8(canonical_json) || "|" || prev_hash))`.
- Genesis `prev_hash` is 64 lowercase zeros.
- Canonical payload/hash and normalized snapshot fields are proposal v1; owner review required before persistence contract acceptance.
- Chain writer requires READ COMMITTED because repeatable-read snapshots can miss predecessor commits after lock wait.

**Source conflict remains:** `04 §4.6.1` wording differs from design/01/06. Current component runs proposal v1 below; do not call hash protocol production-approved until owner review. `AuditCanonicalJson` delegates JCS to Common and keeps only audit-specific Java input validation.

## 7. Persistence and migration

`V2__create_audit_log.sql` proposal creates:
- `banking.audit_log` fields from `04 §4.6.1`.
- `canonical_payload JSONB` for verifier input; JSONB text is not canonical source.
- Hash/version/actor/money constraints.
- Query indexes.
- Append-only update/delete/truncate triggers.
- `banking_app` SELECT/INSERT and sequence privileges on disposable test DB.

`actor_user_id` FK is deferred until identity schema exists. Migration number must be coordinated before merge; do not assume V2 is free after other branch migrations.

## 8. Transaction and failure behavior

1. Validate event before DB lock.
2. Caller opens transaction.
3. Audit obtains global chain lock after business writes.
4. Read prior hash, canonicalize, hash, insert.
5. Return record reference.
6. Caller commits.

Audit failure marks current caller transaction rollback-only. C1/C2 caller failure must rollback business and audit state. C3 standalone semantics remain unimplemented until identity/common integration defines safe fallback. Caught downstream JDBC failures require dedicated test before claiming rollback-only behavior.

## 9. Tests and evidence

Current successful verification scope:
- Validator and snapshot contract: 64 tests.
- Canonical JSON and hashing: 9 tests.
- PostgreSQL writer: latest subset includes rollback, lock/isolation, permission, normalization and concurrency tests.
- Common JCS and module boundary regressions: see verification report.

Owner-dependent items remain blocked: C3/C4 semantics, identity/transfer/admin callers, migration coordination, hash protocol approval, performance SLO and external anchor.

## 10. Next steps

1. Resolve build conflict using version-catalog Common build; keep `libs.jcs`, remove duplicate direct dependency.
2. Replace audit-local canonicalization with common `JcsCanonicalizer` adapter.
3. Re-run domain tests and writer Testcontainers tests when Docker endpoint is available.
4. Fix any real PostgreSQL failure with TDD; do not change expected hash to implementation output.
5. Resolve source hash-version conflict with owners before marking protocol accepted.
6. Coordinate V2 migration with identity/other module migrations.
7. Review `AuditModuleApi` with Phương, Đạt and Vũ before caller integration.

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial writer/hash/transaction proposal |
| 0.2 | 11/11/2026 | Global-lock/JCS limitations |
| 0.3 | 12/11/2026 | Component implementation and unresolved integration status |
| 0.4 | 12/11/2026 | Aligned spec with Common implementation and merged branch dependencies |
