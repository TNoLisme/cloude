# BE-FRD-01 — Fraud rule-based và review flag

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-FRAUD-01; **Task:** BE-FRD-01  
> **Phiên bản:** 0.3; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; implementation chưa bắt đầu  
> **Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer/limit), Đạt (identity/admin), Phương (common); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]]; [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.7; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]].

## 1. Mục tiêu và phạm vi

FR-044–048/AC-026/027: rule-based deterministic fraud evaluation, versioned flags, operator review. AI/ML ngoài MVP. Fraud không tự mutate transfer/account/ledger/limit.

Thịnh viết contract/rule matrix và có thể implement engine thuần trước. Transfer/limit caller integration phải review input eligibility/locking và policy trước merge money path.

## 2. Common integration

- Context/correlation: `RequestContextHolder`; caller captures/persists trace.
- Error: `ErrorCode`/Problem Common cho public errors. `FRAUD_ENGINE_ERROR` là audit/operational event C4, **không phải public ErrorCode**.
- PII: caller uses Common masking primitives; fraud owns matched-value allowlist/scanner and never logs free-text review notes.
- Config/time: `AppProperties`, `Clock`, business zone from Common; source thresholds labeled Proposed remain Proposed.
- Security: common permission chain/CSRF/Problem; real identity principal/authority and review ownership remain unverified.
- No duplicate JCS, HMAC, context or error framework in fraud.

## 3. Contract proposal

```java
public interface FraudEvaluationPort {
    FraudEvaluationResult evaluateTransferInT2(FraudTransferSnapshot input);
}
```

Snapshot immutable, provided by transfer/limit owner. It includes transfer/customer/source IDs, amount/currency, occurredAt, eligible counts and correlationId. Caller owns authoritative query, time window, status/replay exclusion and locking; pure evaluator cannot prove input correctness.

## 4. Rules and source gaps

- LARGE_VALUE: proposed `amount >= T`; T must be approved/configured.
- HIGH_FREQUENCY: local fixture may use comparator `>= N`, completed-only + candidate once; `>` vs `>=`, count of pending/replay, time boundaries and window remain open until source/owners align.
- UNUSUAL_PATTERN: disabled until features/threshold/privacy are agreed.
- Auto-block: transfer owner writes `FAILED/FRAUD_BLOCKED`; fraud does not write transfer/ledger.
- Option B pending-review/reservation/approval is not approved merely because design/03 contains examples. No new money-state endpoint without updated source/OpenAPI.

## 5. Data, transaction and fail policy

`fraud_rule_config` and `fraud_flag` are fraud-owned. Schema/version/unique/FK follow `04 §4.7`; coordinate Flyway version. Transfer/limit locks follow global order. Fraud in-process evaluation uses agreed budget; optional rule failure may use true savepoint isolation. Mandatory limit/auth/idempotency/audit cannot fail open. Audit C2 via AUD-01; event `FRAUD_ENGINE_ERROR` C4 is operational, not an API response.

## 6. API/RBAC

- `GET /api/v1/fraud-flags`: role/permission matrix according to source API contract.
- `POST /api/v1/fraud-flags/{id}/review`: Operations `FRAUD_REVIEW`, decision ALLOW/BLOCK/ESCALATE/CLOSE; terminal flags conflict.
- Review changes flag only, not completed transfer/ledger.
- Identity adapter/session/CSRF and production authorities absent; RBAC integration remains Blocked.

## 7. Tests and evidence

- Pure rule boundary/version/timezone tests prove evaluator only.
- Caller component mapping tests use fake inputs and prove mapping only.
- PostgreSQL + transfer/limit integration proves actual count/time window/replay/locking and money invariants.
- Review API contract, permission, concurrent decision and C2 audit rollback.
- Savepoint/timeout restoration tests against PostgreSQL.
- Preserve source test IDs and report B05/Option B as Blocked where source is not aligned.

## 8. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial rule/review proposal |
| 0.2 | 11/11/2026 | Caller ownership and evidence boundaries |
| 0.3 | 12/11/2026 | Aligned Common context/error/config/security ownership |
