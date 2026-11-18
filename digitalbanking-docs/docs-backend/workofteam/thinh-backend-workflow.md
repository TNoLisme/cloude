# Quy trình và thứ tự task Backend của Thịnh

> **Dự án:** Digital Banking MVP; **Mã tài liệu:** BE-WORK-THINH-01
> **Phiên bản:** 1.1; **Ngày:** 18/11/2026; **Baseline:** `c570986` + Common implementation
> **Người đề xuất:** Thịnh; **Reviewer đề xuất:** Đạt, Vũ, Phương
> **Trạng thái:** Hoàn thành cấp độ Component Verified — chờ ghép tích hợp liên module
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Phạm vi sau khi hợp nhất Common

Module `common` đã triển khai đầy đủ nền tảng dùng chung (config/time, errors, request context/correlation, JCS/HMAC/PII crypto, HTTP security, observability và quality gates). Các module nghiệp vụ phải tái sử dụng trực tiếp `common`, không tự viết lại các primitive trùng lặp.

Trạng thái của `common`:
- `BE-COM-IMP-01..04/06`: Hoàn thành trong phạm vi nền tảng, chờ review.
- `BE-COM-IMP-05/07/08`: Đạt phần độc lập, một số tiêu chí nghiệm thu phụ thuộc tích hợp toàn hệ thống / Cloud.
- Quyết định phát hành toàn hệ thống giữ `NO-GO` cho đến khi hoàn tất ghép tích hợp các module nghiệp vụ và đạt đầy đủ quality gates.

## 2. Phân công sở hữu module

Thịnh phụ trách chính các module `audit`, `notification`, `fraud` và phối hợp phần tích hợp fraud/audit trong `admin`. Đạt phụ trách `identity`, `customer`, `account`, `admin`; Vũ phụ trách `transfer`, `limit`; Phương phụ trách `ledger`, `common`.

Mã nguồn đặt tại `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/`, kiểm thử tại package tương ứng dưới `src/test/java/`, và tài liệu đặt tại `digitalbanking-docs/docs-backend/modules/<module>/`.

## 3. Thứ tự triển khai các task

| Thứ tự | Task | Đầu ra chính | Phụ thuộc tích hợp |
|---|---|---|---|
| 01 | `BE-AUD-01` (Audit Writer) | Validator, action catalog, hash chain, JDBC writer và kiểm thử migration | Common JCS/context/error; callers từ `identity`/`transfer`/`admin` |
| 02 | `BE-AUD-02` (Query/Export/Verify) | Dịch vụ truy vấn, xuất CSV và kiểm định chuỗi băm (`AuditChainVerifier`) | `BE-AUD-01` + `identity`/RBAC |
| 03 | `BE-NOT-01` (Transactional Outbox) | Ghi nhận outbox trong T2 (`MANDATORY`), tiến trình gửi T3 theo lease và retry backoff | Common context/metrics/PII + `transfer`/`identity`/`customer` |
| 04 | `BE-FRD-01` (Fraud Rules & Review) | Bộ đánh giá quy tắc (`LARGE_VALUE`, `HIGH_FREQUENCY`), ghi nhận cờ và xử lý hậu kiểm | Common config/context/error + `transfer`/`limit`/`identity` |
| 05 | `BE-ADM-01` (Admin Fraud/Audit) | Luồng hậu kiểm cờ gian lận kèm kiểm toán `C2` đồng giao dịch | Common security + `admin`/`identity` |
| 06 | `BE-AUD-03` (Retention & Legal Hold) | Chính sách lưu trữ, chốt chặn `LEGAL_HOLD_ACTIVE` và đối chiếu anchor trước khi dọn dẹp | `BE-AUD-01/02` + phê duyệt `admin` |
| 07 | `BE-AI-01` (AI Fraud Feasibility) | Nghiên cứu khả thi và bộ trích xuất đặc trưng ngoại tuyến (`FraudFeatureExtractor`) | `BE-FRD-01` baseline; không chặn đường găng MVP |

Đặc tả kỹ thuật được viết trước; thành phần độc lập được triển khai và kiểm thử với test doubles/Testcontainers trước khi đấu nối vào caller thật. Không đánh dấu `Done` toàn hệ thống khi chưa hoàn tất tích hợp với caller/provider thật.

## 4. Quy trình thực hiện mỗi task

### 4.1. Khảo sát và chốt hợp đồng (Discovery & Contract Gate)

1. Đọc `docs-common/01–11`, `docs-common/design/`, OpenAPI, `00. architecture & conventions.md`, `common/CURRENT-STATE.md`, `common/TKCT.md` và mã nguồn `common`.
2. Đọc đặc tả task và báo cáo kiểm chứng trong `docs-backend/modules/<module>/`.
3. Kiểm tra trạng thái nhánh làm việc (`git status --short --branch`) trước khi chỉnh sửa.
4. Phân loại năng lực đã có sẵn trong `common`, năng lực cần phối hợp với chủ sở hữu module khác và các điểm mở.
5. Chốt hợp đồng của task: mục tiêu, đầu vào/đầu ra, phạm vi trong/ngoài task, phụ thuộc liên module và tiêu chí hoàn thành (Definition of Done).

### 4.2. Tài liệu hóa trước và ghi nhận quyết định (Docs-First & Decision Register)

1. Viết hoặc cập nhật đặc tả kỹ thuật: đầu vào/đầu ra, chủ sở hữu, phụ thuộc, ranh giới giao dịch, mã lỗi, bảo mật, che dấu PII, kịch bản kiểm thử và giới hạn phạm vi.
2. Đối chiếu nguồn chuẩn theo thứ tự ưu tiên: `docs-common/01–11` → `docs-common/design/` → `docs-backend/`; ghi rõ nếu có xung đột và báo người điều hành để chốt vào `DECISIONS.md`.
3. Mỗi quyết định kỹ thuật cần nêu rõ chủ sở hữu, căn cứ, phương án đề xuất, đánh đổi (trade-offs) và bằng chứng nghiệm thu.
4. Cập nhật tài liệu bàn giao trong `docs-backend/modules/<module>/` sau mỗi cột mốc.

### 4.3. Triển khai thành phần độc lập (Independent Implementation Gate)

1. Tách phần có thể triển khai độc lập: domain model, validator, DTO nội bộ, câu lệnh truy vấn, bộ đánh giá thuần (pure evaluator), persistence adapter và dữ liệu kiểm thử.
2. Phát triển theo hướng kiểm thử dẫn dắt (TDD): viết kiểm thử thất bại (RED), cài đặt tối thiểu để vượt qua (GREEN), sau đó tái cấu trúc (REFACTOR).
3. Giữ bề mặt port giao tiếp nhỏ gọn, đúng hợp đồng trong `design/05. Modules contract.md` và không sao chép lại tiện ích của `common`.
4. Kiểm thử dữ liệu lịch sử và bảo mật theo cơ chế đóng an toàn (fail-closed); tuyệt đối không ghi log hoặc trả về PII thô, khóa bí mật hay payload chưa che dấu.

### 4.4. Kiểm chứng tích hợp (Integration Verification Gate)

1. Chạy bộ kiểm thử đơn vị (unit tests) và kiểm thử thành phần (component tests) có mục tiêu trước.
2. Chạy kiểm thử tích hợp với PostgreSQL thật qua Testcontainers cho các kịch bản giao dịch, khóa dòng, ràng buộc dữ liệu, phân quyền và phát hiện sửa đổi trái phép.
3. Đọc trực tiếp kết quả JUnit XML để lấy số lượng bài kiểm thử, lỗi hoặc bỏ qua; ghi nhận đầy đủ câu lệnh, môi trường, kết quả kỳ vọng và kết quả thực tế vào báo cáo kiểm chứng (`*-verification-report.md`).
4. Kiểm tra định dạng khác biệt (`git diff --check`) để bảo đảm không phát sinh lỗi khoảng trắng.

### 4.5. Rà soát và chuẩn bị bàn giao (Review & Staging Gate)

1. Rà soát toàn bộ thay đổi theo đúng phạm vi sở hữu của module; không trộn lẫn tệp của module khác.
2. Tách bạch mã nguồn/kiểm thử của module và tài liệu báo cáo.
3. Trước khi bàn giao, xác nhận không có khóa bí mật, log gỡ lỗi tạm thời hoặc tệp sinh tự động ngoài phạm vi.

### 4.6. Phân loại trạng thái và bàn giao (Status Gate & Handoff)

Sử dụng thống nhất các mức trạng thái:

- **Planned:** Chưa có mã nguồn hoặc bằng chứng kiểm thử.
- **Component Verified:** Thành phần độc lập và bộ kiểm thử mục tiêu (gồm Testcontainers PostgreSQL) đã vượt qua; chờ đấu nối caller/provider liên module.
- **Integration Blocked:** Thành phần độc lập đã hoàn tất nhưng còn chờ module phụ thuộc, chuỗi migration hợp nhất hoặc phê duyệt liên module.
- **Done:** Đạt toàn bộ tiêu chí nghiệm thu, tích hợp thực tế với caller/provider, hợp đồng API/bảo mật và có xác nhận của các chủ sở hữu liên quan.

### 4.7. Phối hợp làm việc song song (Parallel-Work Gate)

1. Mỗi thành viên sở hữu thư mục mã nguồn và tài liệu module riêng; không tự ý chỉnh sửa `common`, OpenAPI, cấu hình build chung hoặc số thứ tự migration khi chưa thống nhất.
2. Giao tiếp qua tài liệu hợp đồng (`design/05. Modules contract.md`) và bảng quyết định (`DECISIONS.md`): nêu rõ chữ ký port, tệp thay đổi, bài kiểm thử và các điểm phụ thuộc.
3. Hợp nhất theo thứ tự phụ thuộc: thành phần nền tảng và port trước, đấu nối tích hợp sau; chạy lại bộ kiểm thử hồi quy sau mỗi bước hợp nhất.

### 4.8. AUD-02 checkpoint — 15/11/2026

| Gate | Hiện trạng | Còn thiếu trước Done |
|---|---|---|
| Discovery/contract | Spec AUD-02 v0.3, Common/API/OpenAPI đối chiếu; D1–D7 ghi proposal | Owner review/sign-off; không sửa source chung đơn phương |
| Independent component | Query/detail/verifier có code và tests; hash v1 vẫn proposal | Review diff và compatibility/security edge cases; không gọi toàn task Done |
| Verification report | Report có matrix và evidence run 14/11/2026: 153 tests | Bổ sung fresh run 15/11/2026 nếu dùng làm evidence chuẩn bị commit; giữ evidence lịch sử và không ghi đè kết quả owner khác |
| DB integration | Fresh targeted suite 15/11/2026: `BUILD SUCCESSFUL`; 153 tests, 0 failures/errors/skips; disposable PostgreSQL Testcontainers | Ghi command, môi trường, revision/working-tree và test scope vào report; không dùng test run này để claim public API/RBAC |
| Integration | Public controller, export, real identity/RBAC/data scope, C3 chưa triển khai | Chốt D1–D5; kiểm tra actual callers và API/security contracts |
| Verifier operations | Component scan theo upper watermark và payload/column comparison | D6 job/checkpoint/anchor; không claim tail-truncation hoặc post-purge verification |
| Commit preparation | 7 AUD-02 source và 5 AUD-02 test files hiện untracked; AUD-02 docs modified; Fraud/Notification docs cũng modified | Phân loại đúng file; review untracked source/test bằng cách đọc trực tiếp vì `git diff` không hiển thị; stage khi user cho phép, xem cached diff và không gom module khác |

Fresh test command scope: `AuditQueryContractTest`, `AuditQuerySqlTest`, `AuditQueryJdbcStoreIT`, `AuditDetailProjectionTest`, `AuditQueryServiceTest`, `AuditChainVerifierTest`, `AuditWriterIT`, `AuditEventValidatorTest`, `AuditSnapshotContractTest`, `AuditCanonicalJsonTest`, `AuditHashingTest`, `ModuleBoundaryArchTest`; `--rerun-tasks --no-daemon`. Lệnh đầy đủ và evidence 14/11 ở AUD-02 report; run 15/11 chưa được ghi vào report tại thời điểm cập nhật workflow này.

Kết luận: AUD-02 **Component Verified / Integration Blocked**, không phải `Done`. Commit component có thể chuẩn bị độc lập sau diff review và test evidence; release/merge acceptance vẫn cần owner review.

### 4.9. BE-NOT-01 checkpoint — 15/11/2026

| Gate | Hiện trạng | Còn thiếu trước Done |
|---|---|---|
| Discovery/contract | Spec BE-NOT-01, docs-04 baseline, DTO `TransferNotice` độc lập, bất biến sâu | Vũ (Transfer) duyệt shape DTO và thứ tự gọi trong T2 |
| Independent component | Domain `TransferNotice`, `NotificationPayloadValidator` (chặn unmasked PII, khoảng trắng/gạch ngang, nested structures, ràng buộc per-field type 42 unit tests), `NotificationRetryPolicy` (deterministic exponential backoff), repository, outbox service (`@Transactional(MANDATORY)`), T3 worker (transaction isolation & suspension, conditional on gateway bean), simulator gateway (chỉ bật khi có explicit opt-in profile/property) đầy đủ | Real provider HTTP adapters (SMS/Email) |
| Verification report | Report đã hoàn thiện ma trận và evidence Testcontainers PostgreSQL (63 tests pass) | Ký nhận từ Transfer owner và Architecture owner |
| DB integration | `NotificationTransactionalOutboxIT` (13 tests pass trên Testcontainers PG): T2 rollback không để lại row, SKIP LOCKED phân batch, lease expiry reclaim, stale worker rejection, SENT transition, retry scheduling, terminal FAILED, duplicate-send window, T3 failure isolation, worker transaction fail-fast rejection, crash/reclaim beyond maxAttempts terminal state | Phương điều phối migration đưa lease columns (`claim_token`, `locked_until`, `claim_version`) vào Flyway production |
| Integration | `NotificationOutboxPort.enqueueTransferNotificationsInT2` sẵn sàng | Transfer module inject port và gọi trong T2; Đạt tích hợp OTP delivery |
| Delivery operations | Worker claim theo lease, exponential backoff deterministic, duplicate-send window trong giới hạn at-least-once, suspension đảm bảo gateway không chạy trong tx, fail-fast nếu caller gọi trong active transaction, crash/reclaim guard bảo đảm terminal `FAILED` khi vượt `maxAttempts`. Simulator gateway loại bỏ khỏi default profile, chỉ kích hoạt khi opt-in rõ ràng; worker không khởi tạo nếu thiếu gateway (fail-closed). Polling latency: Not measured / Planned (cần benchmark chính thức) | Chốt polling interval, batch size và retry max attempts với team |
| Commit preparation | 12 source files và 5 test files thuộc `com.simulatedbank.banking.notification` độc lập; không đụng chạm AUD-02 | Chờ lệnh commit từ người dùng |

Kết luận: BE-NOT-01 **Component Verified / Integration Blocked**, không phải `Done`.

### 4.10. BE-FRD-01 checkpoint — 15/11/2026

| Gate | Hiện trạng | Còn thiếu trước Done |
|---|---|---|
| Discovery/contract | Spec BE-FRD-01, DTO `FraudTransferSnapshot` (10 trường chuẩn), `FraudEvaluationResult`, rules proposal | Vũ chốt comparator `HIGH_FREQUENCY` (`>` vs `>=`) và replay exclusion |
| Independent component | Pure `FraudRuleEvaluator` (`LARGE_VALUE`, `HIGH_FREQUENCY`; `UNUSUAL_PATTERN` disabled by default trong MVP), `FraudMatchedValueValidator` (loại bỏ PII/secrets, per-field type validation, fix false-positive amount, deep validation fractional/overflow/comparator/currency, no-raw-echo), `ReviewStateTransition` (state machine, terminal status protection 409 Conflict, notes validator), repository (chuẩn baseline 04, validation tại persistence boundary), `FraudReviewService` (xác thực reviewerId hợp lệ > 0) đầy đủ | Tích hợp RBAC thực tế với Identity |
| Verification report | Report đã hoàn thiện ma trận và evidence Testcontainers PostgreSQL (32 tests pass) | Ký nhận từ Transfer, Identity và Architecture owners |
| DB integration | `FraudFlagIT` (6 tests pass trên Testcontainers PG): insert flag trong T2, rollback làm mất flag, unique constraint, concurrent review row locking (`FOR UPDATE NOWAIT` + status check), savepoint rollback DB primitive, direct insertFlag persistence boundary safety (12 kịch bản), reviewerId guard | Phương điều phối migration chính thức cho `fraud_rule_config` và `fraud_flag` |
| Integration | Evaluator và Review Service sẵn sàng | Vũ gọi evaluator trong T2 trước debit/credit; Đạt gắn API endpoints với quyền `FRAUD_REVIEW`/`FRAUD_READ` |
| Review operations | Review state transition bảo đảm terminal flag (`ALLOWED`, `BLOCKED`, `CLOSED`) không bị ghi đè, ghi nhận reviewer và validate note <= 500 chars | Identity token injection cho `reviewed_by` |
| Commit preparation | 13 source files và 3 test files thuộc `com.simulatedbank.banking.fraud` độc lập; không đụng chạm AUD-02 | Chờ lệnh commit từ người dùng |

Kết luận: BE-FRD-01 **Component Verified / Integration Blocked**, không phải `Done`.

## 5. Ánh xạ tái sử dụng từ Common

| Nhu cầu | Tái sử dụng từ `common` | Không tự cài đặt lại |
|---|---|---|
| Chuẩn hóa JCS | `common.crypto.JcsCanonicalizer` | Bộ chuẩn hóa JSON nội bộ riêng |
| Băm SHA/HMAC | `common.crypto.HmacService` | Tiện ích mật mã trùng lặp |
| Che dấu PII | `PiiMaskingUtils` + danh sách trường cho phép của module | Fallback trả về dữ liệu rõ (plaintext) |
| Mã tương quan | `RequestContextHolder` | `ThreadLocal` riêng trong module |
| Xử lý lỗi | `ErrorCode`, `BankingBusinessException`, `ProblemWriter` | Danh mục mã lỗi công khai trùng lặp |
| Bảo mật HTTP | Chuỗi filter của `common` + SPI `SessionAuthenticator` | Cơ chế xác thực giả lập trên production |
| Cấu hình & thời gian | `AppProperties`, `TimeConfig`, `deployment/configuration.md` | Ngưỡng cố định (hard-coded thresholds) |
| Nhật ký & chỉ số | `SafeJsonLogEncoder`, `LogRedaction`, quy tắc nhãn cardinality thấp | Ghi PII hoặc nhãn cardinality cao |

## 6. Quy tắc chuẩn hóa JCS trong `BE-AUD-01`

Lớp `AuditCanonicalJson` trong module `audit` là tầng kiểm tra kiểu dữ liệu Java đặc thù cho kiểm toán và ủy quyền bước chuẩn hóa RFC 8785 cho `JcsCanonicalizer` của `common` (không phải một bộ JCS thứ hai). Giao thức chuỗi băm của `audit` (`hash_version`, cấu trúc `canonical_payload`, dấu phân cách `|`, `prev_hash` và khóa chuỗi `pg_advisory_xact_lock`) thuộc thẩm quyền thiết kế của module `audit` và kiến trúc hệ thống (`design/01. audit-traceability.md`).

Sự kiện kiểm toán sử dụng ngữ cảnh từ `RequestContextHolder` khi tích hợp; trong kiểm thử đơn vị có thể truyền trực tiếp các định danh tường minh. Module `identity` cung cấp thông tin chủ thể và quyền hạn; module `audit` không tự thực hiện xác thực hay phân quyền.

## 7. Các giới hạn kỹ thuật cần lưu ý

- Khóa tuần tự chuỗi băm kiểm toán (`pg_advisory_xact_lock`) có thể tuần tự hóa các lượt ghi `C1`/`C2`; cần đo kiểm hiệu năng thực tế trước khi công bố thông lượng (TPS).
- Kiểm thử đơn vị của bộ đánh giá gian lận (`FraudRuleEvaluator`) không thay thế kiểm thử câu lệnh đếm tần suất giao dịch thực tế bên phía `transfer`.
- Tiến trình gửi thông báo (`notification` T3) hoạt động theo ngữ nghĩa ít nhất một lần (at-least-once); khả năng gửi trùng khi hết hạn lease là giới hạn được chấp nhận trong MVP (`DEC-TRF-011`).
- Tính năng dọn dẹp nhật ký kiểm toán (`purge`) mặc định tắt trên các môi trường dùng chung/demo/production; mọi thao tác xóa phải qua đối chiếu anchor và kiểm tra `LEGAL_HOLD_ACTIVE`.
- Bộ giới hạn tốc độ (`RequestRateLimiter`) trong `common` hoạt động theo từng tiến trình (process-local), chưa phải hạn ngạch phân tán toàn cụm.

## 8. Quy tắc xử lý xung đột cấu hình build

Khi hợp nhất nhánh hoặc giải quyết xung đột với cấu hình của `common`:
- Giữ nguyên cấu trúc Version Catalog (`gradle/libs.versions.toml`) của `common` làm nền tảng.
- Chuyển các khai báo phụ thuộc trực tiếp sang bí danh `libs.*` (ví dụ `libs.jcs`).
- Giữ đầy đủ các phụ thuộc Spring Security, JPA, Validation, JaCoCo và Testcontainers từ `common`.
- Sau khi giải quyết xung đột, chạy lại bộ kiểm thử mục tiêu để xác nhận xanh trước khi bàn giao.

## 9. Lịch sử thay đổi (Change Log)

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial docs-first workflow |
| 0.2 | 11/11/2026 | Limitations/evidence boundaries |
| 0.3 | 11/11/2026 | Core/API flexibility before owner review |
| 0.4 | 12/11/2026 | Aligned all task flow with merged Common implementation |
| 0.5 | 15/11/2026 | Added discovery/decision/independent-component/integration/commit/status/parallel-agent gates and evidence-first handoff rules |
| 0.6 | 15/11/2026 | Added BE-NOT-01 and BE-FRD-01 checkpoints with Testcontainers evidence (88 tests passed); marked Component Verified / Integration Blocked |
| 0.7 | 15/11/2026 | Independent Review: sửa lỗi unmasked account & nested structures; đính chính fraud_flag baseline 04 (không có cột version); xác nhận UNUSUAL_PATTERN disabled; cập nhật fresh 74 tests pass |
| 0.8 | 15/11/2026 | Hoàn thiện độc lập: worker transaction suspension & isolation (`REQUIRES_NEW` + `NOT_SUPPORTED`); matched-value safety tại persistence boundary `FraudJdbcRepository.insertFlag`; fix false-positive amount; 83 fresh tests pass |
| 0.9 | 15/11/2026 | Bổ sung xử lý crash/reclaim vượt quá maxAttempts bảo đảm terminal `FAILED` và chặn gọi gateway; log warning khi caller gọi worker trong active transaction; xác thực reviewerId hợp lệ; 85 fresh tests pass |
| 1.0 | 15/11/2026 | Hoàn thiện Notification per-field validation (42 tests), default gateway profile safety & context test (2 tests), Fraud persistence boundary deep check (12 assertions, zero rows on failure, no-raw-echo); 97 fresh tests pass |
| 1.1 | 15/11/2026 | Loại bỏ hoàn toàn simulator khỏi default/local profile; bổ sung explicit opt-in (profile simulator / property enabled); worker conditional on gateway bean (fail-closed); 5 configuration tests pass; fresh 100 tests pass |
