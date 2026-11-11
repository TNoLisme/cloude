# BE-FRD-01 — Verification report

> **Dự án:** Digital Banking MVP; **Owner:** Thịnh  
> **Ngày:** 11/11/2026; **Trạng thái:** Planned — chưa chạy  
> **Spec:** [BE-FRD-01-rules-and-review.md](BE-FRD-01-rules-and-review.md)

## Phân biệt các mức bằng chứng

Pure rules dùng fixture/fake chứng minh evaluator, không chứng minh transfer/DB query. Testcontainers chứng minh fraud persistence/locking. Chỉ transfer/limit integration thật chứng minh end-to-end money-path interaction.

| Check | Lệnh / thủ tục dự kiến | Expected | Actual | Status |
|---|---|---|---|---|
| Proposal review | Review với Vũ/Đạt | Scope/ownership/decision status rõ; Option B không tự thành MVP | Chưa review | Planned |
| Rule boundary unit | `./gradlew test --tests '*fraud.FraudRuleEvaluatorTest'` | Boundary/version/time results khớp fixture/proposal | Class chưa có; chưa chạy | Planned |
| Caller mapping component | Fake query result qua adapter mapping test | Snapshot map đúng với tested input; không chứng minh DB query correctness | Chưa có implementation | Planned |
| Flag persistence | `./gradlew test --tests '*fraud.FraudFlagIT'` | Migration/unique/rollback/version đúng | Class chưa có; chưa chạy | Planned |
| Review contract | `./gradlew test --tests '*fraud.FraudReviewApiTest'` | OpenAPI/status/RBAC/masking đúng | Class chưa có; chưa chạy | Planned |
| Concurrent review | Hai PostgreSQL transactions + latch | Một terminal decision; audit atomic | Chưa chạy | Planned |
| Savepoint failure | Inject PostgreSQL error trong optional evaluation | Rollback savepoint; transaction usable; timeout được restore; mandatory guards giữ nguyên | Chưa tích hợp | Planned |
| Caller query correctness | PostgreSQL integration với transfer/limit thật | Timezone, COMPLETED-only, replay exclusion, boundary và concurrent count đúng; ledger/limit invariants giữ | Caller chưa sẵn sàng | Blocked |
| Money integration | `FraudTransferIT` với transfer thật | Approved policy đúng; không double debit/usage; failure behavior đúng | Chưa tích hợp | Blocked |

Evaluator unit pass chỉ cho phép claim: “evaluator xử lý đúng snapshot trong tested cases”. Không claim HIGH_FREQUENCY caller correctness nếu chưa có PostgreSQL caller-path evidence.

## Evidence và giới hạn

Revision, môi trường, command, logs/artifacts sẽ ghi sau mỗi run. Hiện chỉ có draft spec; chưa có code, owner approval hoặc test execution. `FRD-T09..16` và B05 giữ Blocked nếu Option B/comparator chưa được nguồn chuẩn chốt.

## Quyết định đang mở

Threshold/config authority, HIGH_FREQUENCY comparator, caller snapshot/query contract, savepoint support, auto-block schema/policy và pending-review money flow. Giữ Proposed đến khi review; không thay expected để che test failure.
