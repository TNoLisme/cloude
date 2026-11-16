# E1 — LIM-01/LIM-02 Verification

> **Ghi chú chuyển nền16/11/2026:** các kết quả dưới đây là lịch sử trên nền cũ. Readiness hiện tại theo [DEV2-MIGRATION.md](DEV2-MIGRATION.md); audit production cũ không được chuyển, các test liên quan dùng contract double có ghi rõ.

> **Ngày:** 15/11/2026
> **Phạm vi:** Provider nội bộ E1; không phải nghiệm thu transfer/approval toàn luồng
> **Trạng thái:** E1 đã kiểm chứng trong phạm vi clarify; scoped tests/build pass; independent converge pass

## Quyết định và nguồn

- Ưu tiên `docs-common/01–11 → design → docs-backend`; mô hình 04 §4.5.1–2, ngữ nghĩa 07 §8.5.1–2, giao dịch/module 06 §6, migration 08 §8.1, chiến lược bằng chứng 09.
- Clarify user ngày 15/11/2026: chỉ tạo hai bảng limit, hoãn FK liên module; thiếu cả hai cấu hình trả `NOT_CONFIGURED`, không phải `PASS`, không thêm default. D1 policy transfer vẫn mở.
- SDD local: `specs` (spec, clarifications, plan, research, data-model, internal contract, quickstart, tasks, checklist 16/16). Thư mục specs bị ignore theo cấu hình repo hiện có.

## Phạm vi đã triển khai

- V3 tạo đúng hai bảng/cột của limit; partial unique ACTIVE customer/account và usage scope/date, bốn trạng thái, approval attribution, amounts/currency/interval/version constraints. Không sửa V1/V2.
- Immutable snapshots và `LimitJdbcRepository` insert/read; writes dùng caller transaction MANDATORY, không update/delete. App role chỉ SELECT/INSERT và sequence usage; không hard-delete/truncate history.
- `LimitRules` chọn cả hai scope ACTIVE trong khoảng [start,end), không fallback lịch sử. Kiểm tra từng lần dùng compareTo, equality pass, vượt một scope là LIMIT_EXCEEDED; invalid money là VALIDATION_ERROR; thiếu cấu hình là NOT_CONFIGURED riêng.
- Money được validate range/scale trước JDBC, tránh PostgreSQL tự làm tròn; DB cũng chặn NaN. Ngày/instant truyền rõ, chưa tự quyết định timezone/policy.

## Traceability và kiểm thử

| Phạm vi | Nguồn | Bằng chứng executable |
|---|---|---|
| Schema, trạng thái, attribution, defaults và uniqueness | 04 §4.5.1–2; LIM-01 | `LimitPersistenceIT` — 40 ca PostgreSQL |
| Fresh install, upgrade từ V2, Flyway validate và app role | 08 §8.1; LIM-01 | `LimitPersistenceIT` |
| Snapshot fields, caller transaction/rollback, usage/version independence | 04 §4.5; 06 §6; LIM-01 | `LimitRulesIT` |
| ACTIVE + hiệu lực [start,end), open-ended, unrelated pair isolation | 07 §8.5.1; LIM-02 | `LimitRulesIT` |
| Customer-only, account-only, both/none; immutable reads/no usage changes | 07 §8.5.1; LIM-02 | `LimitRulesIT`, `LimitRuleServiceTest` |
| Equality, cả hai hướng scope chặt hơn, invalid/max precision và NOT_CONFIGURED | SRS FR-016; 07 §8.5.1–2; clarify | `LimitRuleServiceTest`, `LimitRulesIT` |

## Lệnh và kết quả

Chạy từ `digitalbanking-backend/`, Java 21, Docker/Testcontainers `postgres:16-alpine`, Flyway migrations thật. Không dùng mock để chứng minh constraint/persistence.

1. Trước code E1: `./gradlew build --offline --console=plain` — **448 tests, 17 failed**. Sáu test context cố ý tắt datasource nhưng scan toàn backend; AuditRecorder thiếu JdbcTemplate. Kết quả baseline được giữ tại `/tmp/limit-e1-baseline-results` trong phiên làm việc.
2. Red migration test trước V3: `./gradlew test --tests 'com.simulatedbank.banking.limit.LimitPersistenceIT' --offline --console=plain` — **40 failed**, thiếu bảng limit như dự kiến.
3. Focused: `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.BackendApplicationContextTest' --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' --offline --console=plain` — **BUILD SUCCESSFUL**, 75 tests / 0 failures / 0 skipped; riêng E1 **64 tests** (40 persistence + 10 integration provider + 14 unit).
4. Full build trước review: `./gradlew build --offline --console=plain` — **BUILD SUCCESSFUL**, 512 tests / 0 failures / 0 errors / 0 skipped (2m18s). Đây là kết quả trước remediation, không dùng thay bằng chứng bản cuối.
5. Theo chỉ dẫn mới của user, dừng full regression không liên quan. Lượt thử harness TestConfiguration đã được ngắt (exit 130); reviewer xác định annotation này khiến SpringBootTest tìm lại BackendApplication. Bản sửa dùng Configuration + TestComponent, vẫn giữ TypeExcludeFilter; thêm test xác nhận full application không scan harness.
6. Regression T018 trước sửa: chỉ chạy `LimitRulesIT.nanosecondAsOfHonorsDatabaseBoundaries` — **6 ca, 2 fail** tại start−1ns và end−1ns, tái hiện JDBC làm tròn sai ranh giới.
7. Bản cuối sau T017–T018: scoped tests **BUILD SUCCESSFUL** (28s), **93 tests / 0 failures / 0 errors / 0 skipped**: E1 **71** (40 PostgreSQL constraints/migration + 17 PostgreSQL provider/wiring + 14 unit), sáu class context trực tiếp sửa **17**, module boundary **5**. Chỉ chạy các selector dưới đây, không chạy regression module không liên quan.
8. Packaging: `./gradlew build -x test --offline --console=plain` — **BUILD SUCCESSFUL** (1s). Lệnh này chỉ kiểm tra đóng gói sau scoped tests; không được diễn giải là full regression bản cuối.
9. `git diff --check` — pass.

Lệnh scoped cuối:

```bash
./gradlew test --tests 'com.simulatedbank.banking.limit.*' \
  --tests 'com.simulatedbank.banking.BackendApplicationContextTest' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurityServletTest' \
  --tests 'com.simulatedbank.banking.common.context.ContextServletTest' \
  --tests 'com.simulatedbank.banking.common.crypto.CryptoOperationsTest' \
  --tests 'com.simulatedbank.banking.health.HealthEndpointTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --offline --console=plain
```

So sánh thời gian dùng asOf floor về microsecond chỉ khi bind query; metadata giữ Instant gốc. Điều này giữ đúng [start,end) ngay cả với input nano giây.

XML/report chuẩn: `digitalbanking-backend/build/test-results/test/TEST-*.xml` và `build/reports/tests/test/index.html` (được ghi lại bởi lần test kế tiếp). Log phiên: `/tmp/limit-e1-red.log`, `/tmp/limit-e1-focused.log`, `/tmp/limit-e1-build.log`, `/tmp/limit-e1-boundary-red.log`, `/tmp/limit-e1-scoped.log`, `/tmp/limit-e1-package.log`.

## Sửa baseline để đáp ứng build gate

Thêm test-only `support.CommonTestApplication` dùng Configuration + TestComponent, scan common và giữ Boot TypeExcludeFilter; sáu test không dùng datasource trỏ rõ tới harness này. Không đổi assertion, không disable test, không làm production audit conditional. Các integration tests dùng PostgreSQL vẫn chạy full BackendApplication, bao gồm provider E1.

## Chưa nghiệm thu / điều kiện tích hợp

- FK/reference existence tới customer/account/app_user/approval_request và account ownership. Khi các owner có migration thật, thêm/validate FK bằng migration forward-only và kiểm thử dữ liệu không hợp lệ. E1 chỉ lọc pair, không chứng minh ownership.
- D1 policy max/default và hành vi transfer với NOT_CONFIGURED; E2 cần quyết định trước tích hợp enforcement.
- Daily locking/increment, concurrent money path, retry/recovery/idempotency, API self limits, maker/checker/audit activation: E2–E4. E1 PASS chỉ chứng minh hạn mức từng lần, không chứng minh còn hạn mức ngày.
- D2 timezone còn đề xuất; D3 future activation và D4 scope giữa ngày chưa được giải quyết bởi provider này.
- Independent converge vòng 1: 2 HIGH findings, append T017–T018 (test harness isolation và nanosecond effective-time boundaries). T017–T018 đã sửa và kiểm chứng bằng 93 scoped tests cùng build đóng gói. Vòng 2 đã CONVERGED: 0 findings, reviewer không thay đổi tasks.md. Không thay thế human review hoặc Cloud quality gate.


## Kết quả independent converge

Subagent `limit_reviewer` chạy speckit-converge riêng biệt, chỉ append tasks khi có gap; không sửa code/spec/plan. Vòng 2 kiểm tra 10 FR, 5 SC, 8 acceptance scenarios, 6 edge cases, 10 plan decisions và 18 tasks. Constitution được bỏ qua vì là template chưa điền. Kết quả **CONVERGED**, không còn remediation task. T016 được đánh dấu hoàn tất sau báo cáo reviewer; cả 18/18 task đã hoàn tất.

Bản cuối: **93 scoped tests pass**, **build -x test pass**, không chạy lại full regression không liên quan sau chỉ dẫn của user. FK/ownership và policy D1 vẫn là các dependency đã được giới hạn rõ ở bước clarify, không phải phần đã nghiệm thu bằng các test này.
