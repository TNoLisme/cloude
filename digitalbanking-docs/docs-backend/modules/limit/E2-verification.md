# E2 — LIM-03/LIM-04 Verification and LIM-05 handoff

> **Ngày:** 15/11/2026
> **Phạm vi:** Provider transaction/locking/accounting LIM-03/04
> **Trạng thái:** LIM-03/04 hoàn tất; 113 scoped tests pass, package build pass, independent converge vòng 3 PASS; LIM-05 deferred

## Nguồn và clarify

Nguồn ưu tiên `docs-common/01–11 → design → docs-backend`: SRS FR-016/AC-009–013/034, Database §4.5.2/6.6/7.7, Architecture §6–8, API §8.3.1, Consistency §5.3/7.3/7.5/9.2 và test catalog T-LIM-01–03.

User chốt ba điểm tại clarify:

1. Hoàn tất LIM-03/04; hoãn LIM-05 vì checkout chưa có production transfer/account/ledger/identity.
2. Không có cấu hình hiệu lực: NOT_CONFIGURED, không cấp phép cộng usage; mapping lỗi transfer hoãn tới LIM-05. Không thêm default/policy vô hạn.
3. Nghiệm thu Asia/Ho_Chi_Minh, vẫn đọc app.timezone; DB transaction start quyết định ngày.

Hồ sơ SDD local: [spec](specs/002-limit-enforcement/spec.md), [plan](specs/002-limit-enforcement/plan.md), [research](specs/002-limit-enforcement/research.md), [contract](specs/002-limit-enforcement/contracts/limit-enforcement.md), [tasks](specs/002-limit-enforcement/tasks.md). Checklist clarify 16/16. Spec directory bị ignore theo cấu hình repo hiện có.

## Phần đã triển khai

- LimitEnforcement.lockAndCheck bắt buộc caller transaction có thể ghi, READ COMMITTED; transaction_timestamp từ PostgreSQL, ngày theo ZoneId cấu hình. Customer mutex luôn được tạo/khóa trước account counter; account chỉ khi có config.
- Đọc lại ACTIVE config trong khoảng hiệu lực tại T2, sau customer lock; kiểm tra per-transaction/daily cả hai scope, equality pass. NOT_CONFIGURED không phải PASS.
- Business rejection (LIMIT_EXCEEDED/VALIDATION_ERROR) cho phép caller catch và commit FAILED. Không gọi lại E1 checkPerTransaction qua proxy có rollback mặc định.
- Handle opaque giữ amount/date/scope/config, gắn synchronization của caller transaction. recordCompleted chỉ nhận handle PASS chưa dùng trong đúng transaction; kiểm tra cuối bằng conditional UPDATE bảo vệ nhiều handle trong cùng T2. Lỗi completion/SQL rollback caller kể cả được catch.
- Increment customer tổng hợp và account áp dụng; tăng amount/count, không tự xử lý tiền hay trạng thái transfer. Config snapshot giữ nguyên giữa check và increment.
- V4 chỉ cấp UPDATE used_amount/transfer_count/updated_at cho banking_app. Không rewrite V3, không cấp sửa scope/date/config/history. Điều chỉnh test upgrade E1 để kỳ vọng hai migration sau V2 (V3 + V4).

## Bằng chứng kiểm thử

| Yêu cầu | Executable evidence | Kết quả |
|---|---|---|
| Scope customer/account/both; equality, vượt từng per-txn/daily; invalid money; không config | LimitEnforcementIT | Pass |
| T0 snapshot không thay kiểm tra T2; refresh config/usage; config snapshot trong T2 | LimitEnforcementIT | Pass |
| Cạnh tranh 20 thao tác, 2 worker, cùng/khác account, counter mới/cũ; usage=sum accepted | LimitEnforcementIT (4 variants) | Mỗi variant đúng 10 lần accepted × 10 = 100/count 10 |
| Customer mutex giữ tới caller commit; transaction thứ hai thật sự chờ DB Lock | LimitEnforcementIT.checkHoldsCustomerLockUntilCallerCommitAfterIncrement | Pass |
| Commit/rejection/technical rollback; real AuditRecorder validation và DB insert failure, cả counter đã tồn tại | LimitEnforcementIT | Pass |
| Handle duplicate/expired/foreign/REQUIRES_NEW; multi-handle overspend rollback | LimitEnforcementIT | Pass |
| MANDATORY, read-only/isolation rejection; account counter khác customer fail-closed; app-role quyền cột | LimitEnforcementIT | Pass |
| 23:59:59→00:00:00, giữ ngày xuyên midnight, ngày mới cho tx sau; timezone UTC khác; real SQL timestamp stable | LimitBusinessDateIT | Pass |
| E1 constraints/config/read provider và migration upgrade; architecture | LimitPersistenceIT, LimitRulesIT, LimitRuleServiceTest, ModuleBoundaryArchTest | Pass |

Ngày boundary dùng test-only spy trên phương thức lấy timestamp; mọi config/counter/update/locking vẫn chạy PostgreSQL thật. Ca riêng gọi SQL thật chứng minh timestamp là transaction start và không thay đổi theo wall clock. Không dùng mock để chứng minh constraint hay concurrency.

## Lệnh và kết quả

Chạy từ digitalbanking-backend/, Java 21, Docker/Testcontainers PostgreSQL 16. Giới hạn max-workers=2; test executor concurrency 2, không chạy full regression.

- Baseline trước code: `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --offline --console=plain --max-workers=2` → **71 tests pass** (24s).
- Red trước provider: selector LimitEnforcementIT.completionAtEqualityUsesAllApplicableScopes → **3 tests failed**, thiếu bean LimitEnforcement như dự kiến.
- Focused cuối trước reviewer: `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (33s), 111 tests / 0 failures / 0 errors / 0 skipped**: E1 71, E2 35 (32 + 3), architecture 5.
- Package: `./gradlew build -x test --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (1s)**; không phải full regression.
- `git diff --check` → pass.

XML và report: digitalbanking-backend/build/test-results/test/TEST-*.xml và build/reports/tests/test/index.html. Log phiên: /tmp/limit-e2-baseline.log, /tmp/limit-e2-red.log, /tmp/limit-e2-scoped.log, /tmp/limit-e2-package.log. Lần chạy tiếp theo có thể ghi đè report.

## Bàn giao LIM-05 — chưa nghiệm thu

Transfer owner phải:

1. T0 chỉ đọc snapshot để đánh giá sớm/step-up; terminal replay trước mọi enforcement mới.
2. T2 khóa transfer/state guard → accounts ASC → gọi lockAndCheck → fraud/money/ledger → recordCompleted → terminal/idempotency/outbox và audit cuối; tất cả cùng transaction. NOT_CONFIGURED không được tiếp tục tiền trước khi chốt mapping/policy.
3. Catch business reject tại check để ghi FAILED, không gọi recordCompleted. Technical/audit/completion error phải rollback T2. Không tái dùng handle qua transaction, không xem guard một handle là idempotency transfer.
4. Chứng minh bằng transfer thật: LIMIT_EXCEEDED không debit/credit/ledger/usage; equality complete; cạnh tranh customer/account; replay/recovery không increment lại; failure sau increment trước commit rollback cả tiền và usage.

Harness chỉ dùng business_probe để chứng minh transaction provider, không dựng money/ledger giả trong production. Các AC transfer/idempotency/recovery không được tuyên bố đạt bởi 111 tests trên.

FK/reference existence và ownership vẫn hoãn theo E1. D1 max/default policy thuộc E4 còn mở. D4 bật account scope giữa ngày chưa có policy backfill/reset: provider chỉ tạo account counter khi config áp dụng theo nguồn; E4/LIM-05 phải chốt và kiểm chứng trước nghiệm thu ca đó. Không có API E3 hay workflow E4 trong thay đổi này.

## Independent converge

Vòng 1: reviewer độc lập chạy speckit-converge, kiểm tra 10 FR, 5 SC, 7 acceptance hiện tại, 6 edge cases, 10 plan decisions và 16 tasks. Một HIGH finding: Spring còn giữ transaction metadata/synchronizations trong afterCommit sau physical commit, nên handle chưa dùng vẫn được chấp nhận. Reviewer append T017; không sửa source/spec/plan.

T017: thêm expired flag tại beforeCompletion và kiểm tra ở recordCompleted. Regression PostgreSQL afterCommit trước sửa **fail** (provider chấp nhận, không có exception); sau sửa pass và usage giữ nguyên. Lượt scoped cuối sau remediation: cùng selectors limit.* + architecture → **BUILD SUCCESSFUL (32s), 112 tests / 0 failures / 0 errors / 0 skipped** (E1 71 + E2 36 + architecture 5). Log /tmp/limit-e2-aftercommit-red.log và /tmp/limit-e2-final-scoped.log. Package cuối được ghi tại /tmp/limit-e2-final-package.log.

Vòng 2: T017 đạt; reviewer append T018 (HIGH) cho nhánh gọi lockAndCheck lần đầu từ afterCommit, khi chưa có marker cũ. T018 thêm kiểm tra EntityManagerHolder/physical JPA EntityTransaction.isActive ở đầu provider; không thêm thư viện hoặc transaction framework. Regression PostgreSQL nhánh mới trước sửa fail (không có exception); sau sửa pass, không tạo counter sau commit, business probe trước đó vẫn commit.

Lượt cuối sau T018: **113 tests / 0 failures / 0 errors / 0 skipped**, scoped BUILD SUCCESSFUL (36s), gồm E1 71 + E2 37 + architecture 5. Log /tmp/limit-e2-fresh-aftercommit-red.log, /tmp/limit-e2-final-scoped.log. Build package cuối **BUILD SUCCESSFUL (1s)** tại /tmp/limit-e2-final-package.log.

Vòng 3: **CONVERGED — 0 findings**, reviewer kiểm tra 10 FR, 5 SC, 7 acceptance hiện tại, 6 edge cases, 10 plan decisions và 18 tasks. T017/T018 đạt; tasks.md không bị reviewer thay đổi. T016 được parent đánh dấu sau báo cáo clean; **18/18 tasks hoàn tất**. Constitution bỏ qua vì template; không có extension hooks.

Bản cuối: **113 scoped tests pass; build -x test pass; git diff --check pass**. Không chạy full regression module không liên quan. Kết quả không thay thế human review hoặc nghiệm thu LIM-05.
