# Quy trình và thứ tự task Backend của Thịnh

> **Dự án:** Digital Banking MVP; **Mã tài liệu:** BE-WORK-THINH-01  
> **Phiên bản:** 0.4; **Ngày:** 12/11/2026; **Baseline:** `c570986` + Common implementation trên nhánh `dev`  
> **Người đề xuất:** Thịnh; **Reviewer đề xuất:** Đạt, Vũ, Phương  
> **Trạng thái:** Draft — chờ review; implementation task chưa đóng  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Scope sau Common merge

Common đã triển khai baseline cross-cutting: config/time, errors, request context/correlation, JCS/HMAC/PII crypto, HTTP security, observability và quality gates. Các module khác phải reuse Common; không copy primitive riêng.

Common **chưa** cung cấp identity production adapter, customer/account/transfer/ledger/fraud/admin/notification Java implementation, session persistence, CSRF persistence, ownership/maker-checker transaction hoặc distributed quota. Common tests/harness không thay consumer integration.

State của Common:
- COM-IMP-01..04/06: Done trong scope implementer, chờ review.
- COM-IMP-05/07/08: Blocked một phần do thiếu consumer/identity/Cloud.
- Reviewer/sign-off: chưa ghi nhận.
- Release: NO-GO cho đến khi consumer integration/quality gates đạt.

## 2. Ownership

Thịnh owner `audit`, `notification`, `fraud`; phối hợp fraud/audit trong `admin`. Đạt owner `identity`, `customer`, `account`, `admin`; Vũ owner `transfer`, `limit`; Phương owner `ledger`, `common`.

Code: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/`. Tests package tương ứng. Docs: `digitalbanking-docs/docs-backend/modules/<module>/`. README chỉ hướng dẫn/link.

## 3. Task order

| Order | Task | Output | Integration dependency |
|---|---|---|---|
| 01 | BE-AUD-01 writer | Validator/action catalog/hash/writer/migration tests | Common JCS/context/error; identity/transfer callers |
| 02 | BE-AUD-02 query/export/verify | REST/query/export/verifier | AUD-01 + identity/RBAC |
| 03 | BE-NOT-01 outbox | T2 enqueue/T3 worker/retry | Common context/metrics/PII + transfer/identity |
| 04 | BE-FRD-01 fraud | Rule engine/flag/review | Common config/context/error + transfer/limit/identity |
| 05 | BE-ADM-01 collaboration | Admin fraud/audit integration | Common security + Đạt admin/identity |
| 06 | BE-AUD-03 retention | Hold/checkpoint/dry-run | AUD-01/02 + admin approval |
| 07 | BE-AI-01 feasibility | Synthetic offline study | FRD baseline; no MVP block |

Specs có thể viết trước. Component code có thể làm với fake. Không claim Integrated/Verified khi consumer thật chưa chạy.

## 4. Workflow mỗi task

1. Đọc `docs-common/01–11`, design, OpenAPI, Backend conventions, Common CURRENT-STATE/TKCT và source code Common.
2. Phân loại capability đã có trong Common, capability cần consumer owner, gap/open issue.
3. Viết spec: input/output, owner, dependency, transaction, error, security, PII, test, non-goals.
4. Code core/domain/validator/SQL với test trước; giữ API port nhỏ. Không duplicate Common.
5. Review integration DTO/error/permissions/schema với owner trước shared integration.
6. Chạy targeted unit/component trước; Testcontainers cho PostgreSQL/lock/constraint; caller thật cho end-to-end.
7. Verification report ghi revision, command, environment, expected, actual, artifact, status và limitation.
8. Cập nhật README/index chỉ bổ sung link; giữ nội dung chung cũ.

## 5. Mapping Common reuse

| Need | Reuse | Không làm lại |
|---|---|---|
| JCS | `common.crypto.JcsCanonicalizer` | Local JSON canonicalizer |
| SHA/HMAC | `common.crypto.HmacService` | Duplicate crypto primitive |
| PII | `PiiMaskingUtils` + module allowlist | Plaintext fallback |
| Correlation | `RequestContextHolder` | Module ThreadLocal |
| Errors | `ErrorCode`, `BankingBusinessException`, Problem writer | Duplicate public error catalog |
| Security | Common filter chain + identity SPI | Fake production authentication |
| Config/time | `AppProperties`, `TimeConfig`, deployment configuration | Hard-coded thresholds |
| Logging/metrics | Common encoder/context/low-cardinality rules | PII/high-cardinality tags |

## 6. Updated AUD-01 rule

Audit-local `AuditCanonicalJson` is transitional. Replace with Common `JcsCanonicalizer` before integration. Keep audit-owned hash protocol: payload fields, hash_version, delimiter, previous hash and chain lock require audit/architecture review. Common JCS existence does not approve hash protocol.

Audit event uses Common context at integration. Unit tests may pass explicit IDs. Identity supplies actor/principal/authority; audit does not authenticate or authorize.

## 7. Known limitations

- Global audit lock may serialize C1/C2 writes; benchmark before TPS claim.
- Common JCS is available, but audit protocol/hash_version remains open.
- Fraud evaluator unit tests do not prove caller query/count correctness.
- Notification remains at-least-once attempts; duplicate/failure/polling delay accepted MVP limits.
- Purge disabled shared/demo/production; checkpoint requires independent verification.
- Common HTTP/security test harness does not prove real identity/session/CSRF/ownership.
- Process-local rate limit is not distributed quota.
- No Cloud/TLS/ingress/release readiness claim.

## 8. Conflict/staging rule

If `git stash pop` conflicts after Common merge:
- Preserve Common version-catalog build as base.
- Convert old direct dependencies to `libs.*` aliases.
- Keep JCS as `libs.jcs`; remove duplicate direct JCS declaration.
- Keep Spring Security/JPA/validation/JaCoCo/readiness/test dependencies from Common.
- Resolve conflict, run targeted build/tests, then `git add` only to mark resolution.
- Stop and report staged state. Do not commit/push automatically.

## 9. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial docs-first workflow |
| 0.2 | 11/11/2026 | Limitations/evidence boundaries |
| 0.3 | 11/11/2026 | Core/API flexibility before owner review |
| 0.4 | 12/11/2026 | Aligned all task flow with merged Common implementation |
