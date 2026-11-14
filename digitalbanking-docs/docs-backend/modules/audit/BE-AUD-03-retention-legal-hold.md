# BE-AUD-03 — Retention, legal hold và archival

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-03; **Task:** BE-AUD-03  
> **Phiên bản:** 0.3; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; implementation chưa bắt đầu  
> **Owner:** Thịnh; **Reviewer đề xuất:** Đạt (admin/approval), Phương (DB/Common), Vũ (transfer/ledger); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]]; [[digitalbanking-docs/docs-common/07. API contracts.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và safety gate

Thiết kế policy/legal hold/archive/checkpoint/purge có thể kiểm chứng. Purge disabled trong local/shared/demo/production. Chỉ test purge trên disposable Testcontainers. Không claim legal compliance hoặc production recoverability từ component tests.

## 2. Common integration

Reuse:
- `AppProperties`/deployment config cho retention/job values; không hard-code.
- `JcsCanonicalizer`/`HmacService` cho manifest/filter/hash bytes khi protocol được duyệt.
- `RequestContextHolder` cho correlation ở admin/request path.
- Common security/Problem/ErrorCode ở API boundary sau identity/admin integration.
- Common logging/metrics conventions; không emit PII/high-cardinality tags.

Không tạo JCS, error catalog, context holder, security gate hoặc privileged DB wrapper duplicate.

## 3. Ownership và contracts

`audit` sở hữu `audit_log`, checkpoint, legal_hold, retention_policy. `admin` sở hữu approval_request. Identity owns principal/strong auth; admin owns maker/checker. Không ghi trực tiếp bảng module khác.

`RetentionPolicyPort`/`LegalHoldPort` vẫn proposal. Không expose public retention/legal-hold endpoint vì OpenAPI MVP chưa chốt contract.

## 4. Hash/checkpoint dependency

Audit chain protocol/hash_version vẫn phải được owner audit/architecture chốt. Checkpoint phải dùng canonical bytes từ Common JCS và hash protocol AUD-01. Không hash PostgreSQL JSONB text. Checkpoint serialized bytes, archive bytes, range/count/hash phải verify độc lập trước purge.

## 5. Flow

1. Load enabled policy từ config/common binding.
2. Resolve scope qua owner ports; resolver lỗi thì block.
3. Check active hold dưới agreed scope lock.
4. Archive continuous prefix, compute manifest.
5. Readback/verify archive.
6. Create append-only checkpoint.
7. Recheck hold/approval inside short DB transaction.
8. Purge permitted rows and write `RETENTION_PURGED` C2 via AUD-01.
9. Verify retained chain from checkpoint.
10. On mismatch/failure: stop, alert, preserve evidence, never reset genesis/overwrite chain.

## 6. Security and blockers

Common security gate alone does not provide identity/approval. Need real principal, maker/checker, strong auth, DB privileged execution review and restore drill. No production purge flag until independent review.

## 7. Tests

Unit scope resolver and policy. Testcontainers hold race, archive/checkpoint corruption, rollback, restart idempotency and contiguous-prefix verification. Verify Common JCS output and AUD-01 hash protocol. Keep release/Cloud/real data out of scope.

## 8. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial safety-first proposal |
| 0.2 | 11/11/2026 | Purge gate/checkpoint recovery |
| 0.3 | 12/11/2026 | Aligned Common JCS/config/context/security ownership |
