# BE-FRD-01 — Fraud rule-based và review flag

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-FRAUD-01; **Task:** BE-FRD-01  
> **Phiên bản:** 0.2; **Ngày:** 11/11/2026  
> **Trạng thái tài liệu:** Draft — chờ review; **Implementation:** Planned; **Verification:** Not verified  
> **Người đề xuất / Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer/limit), Đạt (identity/admin), Thắng (FE); chưa có approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7.1–2; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5.3–5.4; [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] §4–§13; [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.7; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/09. Test plan.md]] §6.

## 1. Phạm vi, nguồn và trạng thái quyết định

FR-044–048, AC-026/027, NFR-008–010: đánh giá risk rule-based và ghi fraud flag có thể review. Rule engine là deterministic, in-process; AI/ML không thuộc MVP task này. Fraud không tự ghi account, ledger, transfer hoặc limit. Không thêm model/provider ngoài.

Thịnh đề xuất rule matrix và contract trước. Có thể triển khai engine thuần, repository và API tests độc lập với fake caller. Money-path integration hoặc behavior change cần review Vũ/Đạt và đồng bộ nguồn. Không chờ owner khác hoàn thành mới viết docs/unit/component code.

**Phân loại nguồn:** `04 §4.7` và OpenAPI có rule types LARGE_VALUE/HIGH_FREQUENCY/UNUSUAL_PATTERN và flag statuses OPEN/UNDER_REVIEW/ALLOWED/BLOCKED/CLOSED. `design/03 §4.2` có daily step-up/cap, repeated velocity, pending-review/reservation như Option B; §13.2 vẫn hỏi scope. `09 §6` ghi frequency threshold còn dependency-blocked. Không xem Option B đã duyệt chỉ vì docs có ví dụ số.

## 2. Contract và file map — đề xuất v0.1

Root production `digitalbanking-backend/src/main/java/com/simulatedbank/banking/fraud/`; tests dưới `src/test/java` tương ứng.

| File | Trách nhiệm |
|---|---|
| `api/FraudEvaluationPort.java`, `api/dto/FraudTransferSnapshot.java`, `FraudEvaluationResult.java` | Port cho transfer, DTO immutable |
| `domain/FraudRule.java`, `RuleMatch.java`, `FraudRuleEvaluator.java` | Pure deterministic decision theo rule version |
| `application/FraudEvaluationService.java` | Read active rule snapshot, evaluate, persist flag trong T2 |
| `application/FraudReviewService.java` | Lock/transition flag và audit C2 |
| `adapter/in/FraudFlagController.java` | OpenAPI REST list/review |
| `adapter/out/FraudJdbcRepository.java` | SQL config, flag, locking/pagination |

```java
public interface FraudEvaluationPort {
    FraudEvaluationResult evaluateTransferInT2(FraudTransferSnapshot input);
}
public record FraudTransferSnapshot(
    long transferId, long customerId, long sourceAccountId,
    BigDecimal amount, String currency, Instant occurredAt,
    long completedTransfersInWindow, long pendingTransfersInWindow,
    String correlationId) {}
public record RuleMatch(String ruleCode, int ruleVersion,
                        String severity, Map<String,Object> matchedValue) {}
public record FraudEvaluationResult(List<RuleMatch> matches,
                                    boolean autoBlock) {}
```

Counts là snapshot do transfer/limit cung cấp qua contract; Fraud không query bảng owner tùy tiện. Lists/maps phải defensive-copy; amount dùng BigDecimal, currency VND. Caller chịu trách nhiệm authoritative query, eligibility, lock và count. Engine không coi dữ liệu client là authoritative. `autoBlock` chỉ true khi approved policy active. `matchedValue` chỉ amount/count/window.

## 3. Rule matrix v0.1 và điểm mở

| Rule | Input | Expected có thể implement/test | Chưa chốt |
|---|---|---|---|
| LARGE_VALUE | amount, active `threshold_amount` | Flag nếu amount **>= T**; `T-FRAUD-B01..B03` | Giá trị T/config owner và rule action |
| HIGH_FREQUENCY | eligible count, window, active threshold count | Evaluator dùng comparator trong proposal; chưa khóa integration | `>`/`>=` tại N; tính PENDING/replay |
| UNUSUAL_PATTERN | Features/threshold chưa chốt | Disabled mặc định, không auto-block | Feature, threshold, privacy, scope |
| Auto-block | Approved rule action | Nếu policy được chốt, transfer owner xử lý FAILED/FRAUD_BLOCKED | `04 rule_config` thiếu action/auto_block; cần schema/authority review |
| Daily cap/step-up/repeated velocity | limit/identity/transfer | Không chuyển mandatory logic sang fraud | Option B/reservation/PENDING_REVIEW/API chưa chốt |

**FRD-P05 — local unit-test proposal:** HIGH_FREQUENCY comparator `>= N`; cửa sổ `(occurredAt - window, occurredAt]`; đếm transfer COMPLETED đủ điều kiện của cùng customer cộng ứng viên hiện tại đúng một lần; không tính replay cùng transfer, FAILED hoặc PENDING. Fixtures N=3/120 giây chỉ là test values, không seed release policy. `pendingTransfersInWindow` diagnostic, không cộng count v0.1. Nếu Option B reservation được duyệt, sửa contract/tests. B05 vẫn Blocked theo nguồn; unit test có thể kiểm tra proposal mà không gọi nó Accepted. Daily business date dùng `Asia/Ho_Chi_Minh`, timestamp lưu UTC. Effective_from inclusive/effective_to exclusive là FRD-P01 proposal.

## 4. Dữ liệu, migration và SQL

`fraud_rule_config`: composite PK `(rule_code, version)`; active version unique theo `04 §4.7.1`; threshold amount/count/window, severity, effective interval. `fraud_flag`: flag_id, transfer_id, rule_code/version FK, severity/status, matched_value JSONB, reviewer/time/decision/notes/correlation, created_at; unique `(transfer_id, rule_code, rule_version)`. Baseline DB này thắng overview khác; không âm thầm đổi.

Migration chỉ tạo fraud-owned schema. Nếu transfer/app_user chưa tồn tại, ghi FK dependency và dùng disposable fixture; không tạo production table giả. Query active rule theo enabled/as-of; duplicate active config là error, không chọn ngẫu nhiên. `ON CONFLICT` chỉ target đúng unique key.

```sql
SELECT flag_id, status, rule_code, rule_version
FROM banking.fraud_flag WHERE flag_id = :flag_id FOR UPDATE NOWAIT;
```

Review update chỉ khi status OPEN/UNDER_REVIEW; audit cùng transaction. Lock failure/terminal conflict map qua common error catalog, không lộ SQLSTATE.

## 5. API, quyền, lỗi và transaction

- `GET /api/v1/fraud-flags`: filters status/severity/ruleCode/reference/customer/from/to/page/size/sort; response theo OpenAPI. Operations `FRAUD_REVIEW`; Admin/Auditor `FRAUD_READ`; Customer bị từ chối.
- `POST /api/v1/fraud-flags/{id}/review`: Operations `FRAUD_REVIEW`; required `decision`, optional `notes` <=500; `additionalProperties=false`; CSRF nếu cookie. ALLOW→ALLOWED, BLOCK→BLOCKED, ESCALATE→UNDER_REVIEW, CLOSE→CLOSED. Chỉ OPEN/UNDER_REVIEW; terminal trả 409.
- Ví dụ: `{"decision":"ALLOW","notes":"Đã đối chiếu dữ liệu mô phỏng"}`. Notes không ghi raw vào audit/log.
- HTTP errors theo OpenAPI/common; không thêm pending-transfer approve hoặc đổi transfer status từ flag review.

T2: transfer/limit lock theo global order, lấy snapshot; Fraud đánh giá in-process với budget tham chiếu 1 giây; flag insert cùng T2. Savepoint isolation cho phần optional là proposal: PostgreSQL error cần rollback savepoint trước query tiếp; nếu driver/transaction manager không hỗ trợ, integration Blocked. Restore `SET LOCAL statement_timeout` sau evaluation. Không fail-open mandatory limit/auth/idempotency/audit.

Rule auto-block chỉ khi approved; transfer owner ghi FAILED/FRAUD_BLOCKED, không ledger/usage. Fraud không mutation transfer. Optional engine failure chỉ fail-open sau rollback savepoint và nếu policy cho phép.

## 6. Audit, bảo mật và metrics

`FRAUD_FLAG_CREATED`, `FRAUD_FLAG_REVIEWED` C2 theo audit catalog. C4 `FRAUD_ENGINE_ERROR` cho lỗi optional, không che mandatory failure. `matched_value` allowlist amount/count/window; không PII/raw device/notes. Metrics không tag customer/transfer/correlation IDs.

## 7. Thứ tự code và test

- [ ] `FraudRuleEvaluatorTest`: T−unit/T/T+, disabled/effective interval/version/scale; comparator tests theo proposal ghi rõ.
- [ ] `FraudFlagIT`: migration/FK/unique version/replay/rollback/config conflict.
- [ ] `FraudReviewApiTest`: role matrix, unknown fields/decision, notes >500, statuses/errors, OpenAPI schema.
- [ ] `FraudReviewConcurrencyIT`: 2 transactions/latch, một terminal transition; audit failure rollback flag.
- [ ] `FraudTransferIT`: transfer/limit thật, same-customer concurrency, timezone/window/status/replay, savepoint rollback và ledger invariants. Block nếu caller chưa sẵn sàng.
- [ ] Map `FRD-T01..18`, `T-FRAUD-01`, `T-FRAUD-B01..B08`. Option B tests không verified bằng review-flag API; B05 giữ Blocked đến khi sources đồng bộ.

Lệnh dự kiến: `./gradlew test --tests '*fraud.FraudRuleEvaluatorTest' --tests '*fraud.FraudFlagIT' --tests '*fraud.FraudReviewApiTest' --tests '*fraud.FraudReviewConcurrencyIT'`. Class chưa tồn tại; kết quả thực ghi [report](BE-FRD-01-verification-report.md).

## 8. Caller correctness và evidence boundaries

`FraudRuleEvaluator` là pure function; không thể chứng minh caller snapshot đúng. Transfer/limit owner chịu trách nhiệm authoritative query, timezone, eligible statuses, replay exclusion, serialization và lock chống write skew.

- **Evaluator unit:** chứng minh expected decision khi supplied snapshot đúng fixture. Cases gồm eligible COMPLETED trong/ngoài window, exact boundary, FAILED/PENDING, replay, UTC/business-day boundary, duplicate IDs và invalid input.
- **Caller component:** fake query result/map test chứng minh mapping sang `FraudTransferSnapshot` theo tested scenarios; không chứng minh DB query thật.
- **Transfer/PostgreSQL integration:** kiểm tra query thật, status/timezone/window/replay và concurrency trên cùng customer; assert fraud result cùng ledger/limit invariants. Đây là evidence cần để claim caller count correctness.
- Báo cáo tách `Evaluator unit`, `Caller component`, `Transfer/PostgreSQL integration`. Không claim HIGH_FREQUENCY integration verified từ pure unit tests.
- Nếu owner module chưa sẵn sàng, evaluator vẫn có thể hoàn thành; caller integration giữ Blocked/Not verified.

## Proposal, Definition of Done và handoff

FRD-P01 effective interval, FRD-P02 snapshot contract, FRD-P03 savepoint, FRD-P04 auto-block schema và FRD-P05 local comparator/count fixture là proposals của Thịnh. Có thể code core với fake; interface/schema chéo còn linh hoạt đến review. Không đổi money-path contract hoặc Option B scope ngầm.

Done component khi unit/DB/API tests có evidence; done integration khi caller PostgreSQL thật chứng minh count/timezone/concurrency và money invariants. Không gộp component pass với caller pass hoặc AI readiness.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Đề xuất matrix, port, DB/review và isolation; chưa code |
| 0.2 | 11/11/2026 | Phân định caller responsibility và unit/component/integration evidence |
