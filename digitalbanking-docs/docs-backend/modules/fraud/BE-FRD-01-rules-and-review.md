# BE-FRD-01 — Fraud rule-based và review flag

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-FRAUD-01; **Task:** BE-FRD-01  
> **Phiên bản:** 0.4; **Ngày:** 14/11/2026
> **Trạng thái:** Common-aligned draft — pure evaluator độc lập; caller/API/migration policy còn review
> **Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer/limit), Đạt (identity/admin), Phương (common); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]]; [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.7; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]].

## 1. Mục tiêu và phạm vi

FR-044–048, AC-026/027: deterministic rule evaluation, versioned `fraud_flag`, Ops review. AI/ML ngoài MVP. Fraud module sở hữu rule config/flag và review persistence; không tự mutate transfer/account/ledger/limit. Caller Transfer sở hữu money state, authoritative history query, lock order, idempotency/replay và cách áp dụng kết quả.

**Nguồn ưu tiên:** docs-common/01–11 là business/API/DB contract source; design bổ sung chi tiết khi không trái baseline; backend module docs triển khai hóa. Conflict phải giữ làm owner decision, không tự promote Proposed/Design-B thành baseline.

### 1.1. Có thể triển khai độc lập

- Pure evaluator nhận immutable snapshot/config, trả immutable matches/results.
- Large-value comparator với threshold là input, không hard-code policy.
- High-frequency comparator/window có thể test parameterized; không khẳng định caller query semantics.
- Matched-value allowlist, config version validation và deterministic unit tests.

### 1.2. Chờ owner/integration

- HIGH_FREQUENCY threshold/comparator/window/count rules.
- Daily step-up/cap và Option B pending-review/reservation flow.
- Auto-block execution trong T2.
- Query history đúng customer, replay exclusion, locking và race semantics.
- Migration/FK/version coordination.
- Review API, production RBAC/actor, real AUDIT C2 integration.

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

## 4. Rules và conflict register

| Rule | Baseline source | Hướng implementation |
|---|---|---|
| LARGE_VALUE | SRS §2.13.5: `amount >= threshold` | Pure evaluator dùng threshold config input; no hard-coded runtime threshold |
| HIGH_FREQUENCY | SRS §2.13.5 nêu count `>= threshold`, đồng thời ví dụ proposed `>5/10 phút`; 04 §4.7.1 nêu `>5/10 phút`; design/03 §4.2 Design-B nêu giao dịch thứ 3/120s | Parameterized evaluator/test fixture. Baseline comparator/count model chưa đủ để gắn vào caller money path; Vũ/architecture chốt `>` vs `>=`, candidate counting, PENDING/COMPLETED/FAILED/replay và window inclusivity |
| UNUSUAL_PATTERN | SRS FR-047 là Should; 04 có rule type, nhưng feature/threshold chưa chốt | Giữ Proposed/disabled cho caller integration; pure fixture chỉ khi rule contract rõ |
| AUTO_BLOCK | 04 §4.7.2 cho phép nếu policy cấu hình; mặc định flag/continue | Không invent `auto_block` DB column trong baseline; execution/policy source cần Vũ/Phương chốt. Nếu approved, Transfer writes FAILED/FRAUD_BLOCKED, không ledger/usage |
| Option B | design/03 §4.2 daily threshold, 3/120s, repeated velocity, pending-review/reservation | Design-B proposal; không tự coi là MVP/source-approved hoặc thêm money-state endpoint |

- `AppProperties.Fraud` hiện có defaults `velocityCount=3`, `velocityWindowSeconds=120`, `warningTtlMinutes=15`, `reviewTimeoutMinutes=30`; `AppProperties.Transfer` có `fraudEvalTimeoutMs=1000` và `dailyCapVnd=50000000`. Đây là Common configuration defaults, không tự biến thành business-policy approval khi SRS/04/design/03 còn conflict.

## 5. Data, transaction và fail policy

### 5.1. Schema baseline `04 §4.7.1–4.7.2`

`fraud_rule_config` có PK composite `(rule_code, version)`, `rule_type`, `threshold_amount`, `threshold_count`, `window_seconds`, `severity`, `enabled`, `effective_from/to`, maker/checker và timestamps. Các `rule_type` baseline: `LARGE_VALUE`, `HIGH_FREQUENCY`, `UNUSUAL_PATTERN`. Version mới khi đổi config; active uniqueness/index theo 04.

`fraud_flag` có `flag_id`, `transfer_id`, `(rule_code, rule_version)` composite FK, `severity`, `status`, `matched_value`, `reviewed_by/at`, `decision`, `notes`, `correlation_id`, `created_at`. Unique `(transfer_id, rule_code, rule_version)`, không rút thành `(transfer_id, rule_code)` từ overview. Status baseline: `OPEN`, `UNDER_REVIEW`, `ALLOWED`, `BLOCKED`, `CLOSED`; không tự thêm `CLOSED_FALSE_POSITIVE`/`CLOSED_CONFIRMED` từ design/03 hoặc overview.

Các `version_no`, `threshold_json`, `auto_block`, `action`, `score_details` trong overview không phải baseline fields 04. Bất kỳ extension nào là proposal có migration và owner review, không tự thêm hoặc thay baseline.

### 5.2. T2 và failure

- Evaluation trong T2 sau customer/limit lock, trước debit/credit theo 06 §7.6 và design/02 §5.3. Flag matched được ghi cùng T2; caller Transfer sở hữu lock order và authoritative history.
- Optional fraud engine error fail-open theo NFR-008; `FRAUD_ENGINE_ERROR` là C4 operational event, không phải public ErrorCode; log/metric/audit fallback phải được phối hợp.
- DB exception trong PostgreSQL có thể làm transaction aborted. Nếu optional fraud query/insert fail và muốn continue, phải chứng minh true savepoint rollback, timeout restoration và T2 còn usable; catch exception đơn thuần không đủ. Không bypass mandatory auth/limit/idempotency/audit để giữ fail-open.
- `FRAUD_FLAG_CREATED`/`FRAUD_FLAG_REVIEWED` là C2 audit; review update và C2 audit cùng transaction, audit fail rollback review. AUD-01 hiện có `AuditRecorder` nội bộ C1/C2 proposal, chưa có public `AuditModuleApi` owner-approved; không gọi public port giả hoặc claim integrated.
- Mâu thuẫn giữa optional engine fail-open và matched flag/audit C2 failure cần Vũ/Phương/architecture chốt exception boundary trước real T2 integration; không tự nuốt C2 audit failure.

## 6. API/RBAC theo `07 §8.7` và OpenAPI

| Endpoint | Quyền | Contract |
|---|---|---|
| `GET /api/v1/fraud-flags` | Operations `FRAUD_REVIEW`; Admin/Auditor `FRAUD_READ` | read-only; filters `status`, `severity`, `ruleCode`, `reference`, `customerId`, `from`, `to`, `page`, `size`, `sort`; response PageResponse<FraudFlag>; no full PII |
| `POST /api/v1/fraud-flags/{id}/review` | Operations `FRAUD_REVIEW` | body `decision` required, `notes` optional ≤500; 200 updated FraudFlag; 404 FRAUD_FLAG_NOT_FOUND; terminal flag 409 FRAUD_FLAG_ALREADY_CLOSED |

Decision/status mapping: `ALLOW -> ALLOWED`, `BLOCK -> BLOCKED`, `ESCALATE -> UNDER_REVIEW`, `CLOSE -> CLOSED`. Chỉ `OPEN`/`UNDER_REVIEW` được review. Review không thay đổi transfer/ledger đã hoàn tất; phải ghi `FRAUD_FLAG_REVIEWED` C2.

API dùng `notes`, không tự đổi sang `reviewNote` từ module overview. Notes có thể chứa dữ liệu nhạy cảm: không log hoặc copy free text vào audit; response/storage policy phải review theo 07 và security. Identity adapter/session/CSRF/authorities absent; test fake principal chỉ component evidence, chưa chứng minh RBAC integration.

## 7. Tests và acceptance boundaries

### Independent component tests

- Pure evaluator: `amount >= threshold`, exact boundary, disabled/effective rule, deterministic version/result, safe matched value (`amount/count/window`) and no PII/free text.
- HIGH_FREQUENCY accepts threshold/window as inputs; fixtures label each source alternative. Passing fixture proves evaluator arithmetic only, not chosen production comparator/history semantics.
- Config validation: `rule_code/version/type`, required threshold per type, effective time, one active version; do not test proposed DB columns not in baseline.
- Review state transition pure tests may prove decision mapping only.

### Requires DB/module integration

- PostgreSQL persistence/FK/unique/version, concurrent review one terminal winner, C2 audit atomicity.
- Savepoint rollback and timeout restoration while caller T2 remains usable.
- Transfer caller query: business timezone, event timestamp bounds, included statuses, current candidate handling, replay/idempotency exclusion, customer mutex, concurrent count.
- Money invariants: auto-block no debit/credit/ledger/usage; audit failure rollback per agreed policy.
- HTTP/OpenAPI/RBAC and real reviewer identity.

Do not report pure evaluator pass as FR-044/046 caller acceptance or AC-026 end-to-end. Preserve FRD-T test IDs; mark tests depending on Design-B/threshold conflict `BLOCKED` until owner resolves.

## 8. Owner decision register

### Vũ — Transfer/Limit

**Cần chốt:** HIGH_FREQUENCY comparator; count/window boundary; which transfer statuses count; whether current candidate is included once; replay exclusion; business time zone; customer/account key; customer mutex/lock ordering; auto-block action/policy; T2 result and failure path.

**Đề xuất:** authoritative snapshot/query owned by Transfer; count completed committed transactions in configured sliding window plus candidate exactly once; retry replay never counted twice; derive deterministic facts under customer mutex. Comparator/window remain config values until approved; no hard-coded Design-B default.

**Lý do/evidence:** SRS §2.13.5 and 04 §4.7.1 propose `>5/10m`; design/03 Design-B says third/120s; design/02 requires customer lock before fraud count to avoid write skew. Need boundary tests at N-1/N/N+1, exact time edges, concurrent same-customer transfers, replay, timezone boundary, and no double debit/usage.

### Phương — DB/Common

**Cần chốt:** migration version, schema/FK/unique alignment, rule config seed authority and savepoint/statement-timeout support.

**Đề xuất:** keep 04 schema baseline; don't add `auto_block/action/threshold_json` columns without reviewed migration. Keep `matched_value` allowlist. Verify savepoint rollback behavior against PostgreSQL before using fail-open.

**Lý do/evidence:** DB §4.7 schema differs from module overview; transaction aborted JDBC exception cannot be made safe by catching it. Need disposable PG migration/FK/unique/savepoint/timeout tests.

### Đạt — Identity/Admin/Security

**Cần chốt:** production principal/authority mapping; Ops reviewer identity; permission and row scope; notes privacy; maker/checker/approval if review decision requires dual control.

**Đề xuất:** `FRAUD_REVIEW` only for Operations review endpoint; actor from trusted identity, not request. No new maker/checker or approval workflow unless source contract requires it.

**Lý do/evidence:** 07 §8.7 grants review to Operations; Common session adapter has no production identity. Need positive/negative authority, revoked session, cross-scope, concurrent reviewer and audit actor tests.

### Architecture/Security owner

**Cần chốt:** whether Design-B enters MVP; failure boundary for optional engine vs matched flag/C2 audit; evaluation time budget and operational telemetry; whether unusual-pattern rules are enabled.

**Đề xuất:** keep unusual-pattern disabled; keep Design-B thresholds Proposed; fail-open only for optional evaluation failure that is isolated by proven savepoint. C2 persistence/audit failures follow transaction failure policy; do not swallow them as evaluator errors.

**Lý do/evidence:** SRS says rule engine errors fail-open, but 04 also requires flag/audit consistency. Need injected evaluator, flag insert, savepoint, audit-write, timeout tests and explicit result matrix.

### Common boundary

Common primitives already provide config/time/context/security/error/logging. Do not edit Common or treat Common SPI fixture as real identity evidence.

## 9. Nguồn đối chiếu và xung đột

| Source | Contract / conflict |
|---|---|
| SRS 03 §2.10/2.13.5, FR-044–048, AC-026/027 | Rule evaluation/flag/review; default flag and continue; `>5/10m` is labeled proposal in SRS text |
| DB 04 §4.7.1–4.7.2 | Schema baseline: composite `(rule_code,version)`, thresholds, rule_type, DB flag statuses/unique; proposed high-frequency `>5/10m`; no `auto_block` column |
| API 07 §8.7 / OpenAPI | list/review filters, permissions, `notes` field and decision-to-status mapping |
| design/02 §5.3/§5.6 | T2 customer lock before count, fail-open optional engine, savepoint isolation, flag+money transaction |
| design/03 §4.2/§6 | Design-B third transfer/120s, daily step-up/cap, warning/review/reservation proposal; not automatically MVP baseline |
| Fraud overview | Uses extra schema names/statuses/auto-block details that conflict with 04/07; subordinate until sources updated |
| Common TKCT/current state | Common supplies primitives only; real identity/config consumer integration absent |

## 10. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial rule/review proposal |
| 0.2 | 11/11/2026 | Caller ownership and evidence boundaries |
| 0.3 | 12/11/2026 | Aligned Common context/error/config/security ownership |
| 0.4 | 14/11/2026 | Reconciled SRS/DB/API baseline, Design-B conflicts, transaction boundaries and owner decision register |
