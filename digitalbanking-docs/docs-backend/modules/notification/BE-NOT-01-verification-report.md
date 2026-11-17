# BE-NOT-01 — Verification report

> **Trạng thái:** Component Verified / Integration Blocked
> **Task spec:** [BE-NOT-01-transactional-outbox.md](BE-NOT-01-transactional-outbox.md)
> **Implementer:** Thịnh
> **Ngày:** 15/11/2026 (Cập nhật sau hoàn thiện độc lập Phase 2)
> **Baseline:** `thinh` branch (`c570986` + Common baseline)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Metrics / event model & synthetic log-artifact scan | Task spec §1.1 / §7 | Bounded, PII-free observability; synthetic payload/log scan | Dedicated evidence not found in reviewed component tests/reports | Not verified / remaining independent work |
| Worker transaction boundary | `NotificationTransactionalOutboxIT.workerRejectsActiveCallerTransaction` (Test 12) | `processBatch` từ chối caller transaction đang active bằng `IllegalStateException` trước khi claim/gateway; outbox row giữ nguyên `PENDING`; gọi ngoài transaction thành công | Test RED rồi GREEN trên disposable PostgreSQL Testcontainers; verify row không bị thay đổi và scheduler call chạy bình thường | Verified (Component Safety Guard) |
| Retry policy formula | `NotificationRetryPolicyTest` (3 unit tests) | Deterministic exponential backoff ($2^{k-1} \times \text{baseBackoff}$); max 64x multiplier; safe failure codes (`GATEWAY_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DELIVERY_EXHAUSTED`); không tràn số | 3 test cases pass; code thực tế triển khai exponential backoff; max attempts và lease duration là config proposals | Verified (Component) / Pending Policy Approval |
| Default gateway runtime safety | `NotificationConfigurationTest` | Simulator chỉ bật khi explicit opt-in và chưa có `NotificationDeliveryPort`; worker không tạo nếu thiếu gateway | ApplicationContextRunner kiểm tra default, prod không gateway, simulator profile/property và provider fixture trực tiếp qua port; không chứng minh provider thật | Component wiring tested / Provider integration blocked |
| T2 rollback | `NotificationTransactionalOutboxIT.testT2RollbackLeavesNoOutboxRows` | T2 rollback để lại zero notification rows | Transaction rollback để lại 0 rows trong DB | Verified |
| Claim concurrency | `NotificationTransactionalOutboxIT.testConcurrentBatchClaimSkipLocked` | Hai DB connections độc lập; `FOR UPDATE SKIP LOCKED` phân chia batch mutually exclusive; không tranh chấp duplicate lease | Row chỉ có 1 active lease; worker thứ hai nhận empty batch | Verified |
| Gateway failure isolation | `NotificationTransactionalOutboxIT.testT3WorkerGatewayFailureDoesNotAffectT2` | Transfer/ledger/audit không đổi; row retry/FAILED đúng policy | T3 failure cô lập hoàn toàn; T2 transfer state giữ nguyên; outbox row chuyển RETRYING | Verified |
| Lease recovery | `NotificationTransactionalOutboxIT.testLeaseExpiryAllowsReclaimByAnotherWorker` | Claim rồi dừng worker; expire lease theo DB time; worker khác reclaim | Row với expired lease được worker mới reclaim an toàn | Verified |
| Stale worker reject | `NotificationTransactionalOutboxIT.testStaleWorkerCannotOverwriteAfterLeaseExpired` | Stale worker gửi thành công sau khi lease hết hạn và bị reclaim không thể overwrite SENT | Update conditional on `claim_token` trả về 0 rows; ngăn chặn stale overwrite | Verified |
| Duplicate-send window | `NotificationTransactionalOutboxIT.testDuplicateSendWindowGatewayCrashBeforeSent` | Gateway fake success rồi crash trước mark SENT; retry có thể duplicate; money state không đổi | Chấp nhận duplicate-send window trong giới hạn at-least-once delivery | Verified |
| Retry terminal state | `NotificationTransactionalOutboxIT.retryReachesFailedAtMaxAttempts` (Test 7) | Backoff đúng; cuối cùng FAILED sau max attempts; failure code `DELIVERY_EXHAUSTED` | Chuyển trạng thái sang `FAILED` sau 3 lần retry thất bại | Verified |
| Crash/reclaim beyond maxAttempts | `NotificationTransactionalOutboxIT.repeatedCrashReclaimExceedingMaxAttemptsReachesTerminalFailed` (Test 13) | Worker crash lặp lại sau khi claim (không gọi markSent/failure), lease hết hạn, worker khác reclaim; khi `retry_count > maxAttempts`, worker chuyển ngay sang `FAILED` (`DELIVERY_EXHAUSTED`), không gọi gateway | Đã tái hiện RED và fix GREEN bằng TDD; bảo đảm chặn lặp vô hạn và bảo vệ terminal state `FAILED` | Verified |
| deliverItem outer tx guard | `NotificationTransactionalOutboxIT.deliverItemRejectsActiveCallerTransactionHoldingLock` (Test 14) | Giới hạn visibility của `deliverItem` thành package-private; bổ sung active transaction guard trực tiếp trong `deliverItem` để fail-fast trước khi gọi gateway nếu caller transaction đang active/holding lock. Không dựa riêng vào NOT_SUPPORTED (vì NOT_SUPPORTED suspend transaction nhưng không nhả locks/resources của outer transaction) | Đã tái hiện RED và fix GREEN trên Testcontainers PostgreSQL; gateway không bao giờ bị gọi khi caller transaction đang giữ lock | Verified (Component Guard) / Architecture Limitation Open |
| Polling latency & backlog age | Benchmark workload với sample size, timer precision và load đo lường | p50/p95/p99 có số liệu tin cậy; so sánh với SLO | Chưa đo lường chính thức (không dùng thời gian chạy Testcontainers/unit test làm latency claim); cần harness benchmark riêng | Not measured / Planned |
| OTP boundary | `NotificationPayloadValidatorTest` + Identity integration | OTP plaintext không được persist/log; auth outcome không báo success sai | Validator reject trường `otp` và keyword `otp`; caller integration thật chờ Đạt | Verified (Component) / Blocked (Integration) |
| Transfer caller integration | Tích hợp caller T2 từ Transfer module | `TransferNotice` được tạo và enqueue tự động trong transaction chuyển tiền | Caller chưa sẵn sàng; chờ Vũ | Blocked |
| Real Provider integration | Production SMS/Email provider HTTP adapters | Provider idempotency và real network delivery | Đang dùng `SimulatedNotificationGateway` trong dev/test profiles | Blocked |

## Review correction — 15/11/2026

- Retry: `retry_count` tăng khi claim. `>= maxAttempts` sau gateway failure và `> maxAttempts` trước dispatch tiếp theo nhất quán với giới hạn tối đa N claim/attempt; nhận xét review trước cho rằng hai điều kiện tự mâu thuẫn là sai. Crash trước send vẫn tiêu tốn claim; policy đó cần owner duyệt.
- Test `rejectsAccountLikeValuesInReferenceAndTemplateFields` pass trước thay đổi production: không coi đó là RED hoặc chứng minh generic PII scanner. Chưa đổi format reference/template trong payload để tránh giả định contract.
- Test `rejectsSensitiveTopLevelTemplateCode` RED vì `user@example.com` được chấp nhận; đã bổ sung kiểm tra top-level template code và fresh targeted suite pass. Kiểm tra này không chứng minh chặn mọi PII/secret; field format/DTO contract vẫn cần review.
- Fresh combined NOT/FRD/architecture run sau thay đổi: **102 tests, 0 failures, 0 errors, 0 skipped** (`--rerun-tasks`). NOT validator 44, retry 3, outbox IT 13, configuration 5; Fraud evaluator 16, review 10, flag IT 6; architecture 5. Không suy ra real caller/RBAC/provider integration.


## Evidence

- **Revision:** `dev` branch (`833ee24` + stack convention alignment)
- **Môi trường:** Local Docker PostgreSQL 16 Alpine via Testcontainers, Java 21, Spring Boot 3.5.7
- **Kiểm thử thực thi:**
  ```powershell
  & "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend\gradlew.bat" `
    -p "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend" `
    test `
    --tests '*NotificationConfigurationTest' `
    --tests '*NotificationPayloadValidatorTest' `
    --tests '*NotificationRetryPolicyTest' `
    --tests '*NotificationTransactionalOutboxIT' `
    --tests '*NotificationJpaRepositoryIT' `
    --tests '*NotificationMapperTest' `
    --rerun-tasks `
    --no-daemon
  ```
- Historical result (15/11/2026; superseded by verification correction below):
  - **Unit Evidence (53 tests, 0 failures, 0 errors, 0 skipped):**
    - `NotificationConfigurationTest`: 5 tests (ApplicationContextRunner: fail-closed khi thiếu gateway, opt-in profiles, real provider activation)
    - `NotificationPayloadValidatorTest`: 44 tests (Allowlist, masking, ISO-8601, number types, prefix/suffix PII guard, unmodifiable deep immutability)
    - `NotificationRetryPolicyTest`: 3 tests (Deterministic exponential backoff $2^{k-1} \times \text{baseBackoff}$ & safe error codes)
    - `NotificationMapperTest`: 1 test (MapStruct bidirectional Entity ↔ DTO mapping)
  - **Integration Evidence (17 tests on disposable Testcontainers PostgreSQL 16 Alpine, 0 failures, 0 errors, 0 skipped):**
    - `NotificationTransactionalOutboxIT`: 14 tests (T2 rollback, SKIP LOCKED claim, lease recovery, stale worker fencing token rejection, duplicate send window, worker active-tx safety guard, crash/reclaim beyond max attempts, deliverItem active-tx lock rejection)
    - `NotificationJpaRepositoryIT`: 3 tests (Spring Data JPA entity persistence, JSONB payload_masked, enum, lease/claim fields, nullable fields & transaction rollback isolation)
  - **Tổng Notification tests: 70 tests passed, 0 failures, 0 errors, 0 skipped.**

## Stack & Convention Alignment — Persistence Audit — 15/11/2026

Bảng rà soát chi tiết từng phương thức persistence trong `NotificationJdbcRepository` theo `docs-backend/00` §3.1, §4.2 và `docs-common/05` §5.2.2:

| Phương thức / Class | Bản chất thao tác | Trạng thái triển khai | Quyết định công nghệ | Lý do kỹ thuật cụ thể | Tests liên quan |
|---|---|---|---|---|---|
| `insert(NotificationItem)` | Ordinary CRUD (Entity insert) | Spring Data JPA | **Chuyển 100% sang JPA** | Chuyển toàn bộ insert outbox sang `NotificationJpaRepository.save(mapper.toEntity(item))`. JSONB `payload_masked` ánh xạ tự động qua `@JdbcTypeCode(SqlTypes.JSON)`. Không giữ raw-JDBC fallback; fail-fast nếu thiếu JPA | `NotificationTransactionalOutboxIT`, `NotificationJpaRepositoryIT` |
| `findById(long)` | Ordinary CRUD (Lookup by PK) | Spring Data JPA | **Chuyển 100% sang JPA** | `NotificationJpaRepository.findById(id).map(mapper::toDomain)`. Không giữ raw-JDBC fallback; fail-fast nếu thiếu JPA | `NotificationTransactionalOutboxIT`, `NotificationJpaRepositoryIT` |
| `claimBatch(int, Duration, String)` | PostgreSQL-specific concurrency | Native SQL / JdbcTemplate | **Giữ Native SQL / JDBC** | Cần atomic subquery `FOR UPDATE SKIP LOCKED` kết hợp `UPDATE ... RETURNING` trên nhiều dòng có cập nhật lease (`locked_until`, `claim_token`, `claim_version`). JPA/JPQL tiêu chuẩn không hỗ trợ cú pháp này | `NotificationTransactionalOutboxIT` |
| `markSent(long, String, Instant)` | PostgreSQL-specific concurrency | Native SQL / JdbcTemplate | **Giữ Native SQL / JDBC** | Conditional atomic update với fencing token `WHERE notification_id = ? AND claim_token = ?` ngăn chặn stale worker overwrite khi lease hết hạn | `NotificationTransactionalOutboxIT` |
| `markRetryOrFailed(...)` | PostgreSQL-specific concurrency | Native SQL / JdbcTemplate | **Giữ Native SQL / JDBC** | Conditional atomic update với fencing token `WHERE notification_id = ? AND claim_token = ?` bảo vệ trạng thái lỗi và exponential backoff | `NotificationTransactionalOutboxIT` |

Bảng rà soát các thành phần khác:

| Class / Component | Hiện trạng | Convention áp dụng | Quyết định | Lý do kỹ thuật cụ thể | Tests liên quan |
|---|---|---|---|---|---|
| `NotificationEntity` | Mới tạo | Spring Data JPA Entity | **Tạo mới JPA Entity** | Entity bảng `banking.notification` với `@JdbcTypeCode(SqlTypes.JSON)` cho `payload_masked`, Lombok annotations đúng chuẩn docs | `NotificationJpaRepositoryIT`, `NotificationMapperTest` |
| `NotificationJpaRepository` | Mới tạo | Spring Data JPA | **Tạo mới JPA Repository** | Quản lý CRUD domain thông thường (`findById`, `findByCustomerId`, `findByTransferId`, `countByStatusIn`) | `NotificationJpaRepositoryIT`, `NotificationMapperTest`, `NotificationConfigurationTest` |
| `NotificationMapper` | Mới tạo | MapStruct 1.6.x | **Tạo mới MapStruct Mapper** | Chuyển đổi hai chiều giữa `NotificationEntity` và domain `NotificationItem` ở ranh giới persistence adapter (`componentModel = "spring"`) | `NotificationMapperTest` |
| `TransferNotice` & `NotificationItem` | Java records | Pure domain records | **Giữ nguyên Java record** | Không chuyển sang Lombok theo nguyên tắc: "Lombok dùng khi giảm boilerplate thực sự; không chuyển Java records thành Lombok chỉ để thêm annotation" | `NotificationPayloadValidatorTest` |
| `NotificationPayloadValidator` | Custom validator | Domain security invariant | **Giữ nguyên custom validator** | Bảo vệ ranh giới payload allowlist, PII masking, định dạng tiền tệ và ISO-8601 timestamp; không thay thế bằng generic bean validation trên domain | `NotificationPayloadValidatorTest` |
| `NotificationRetryPolicy` | Pure domain service | Deterministic exponential backoff | **Giữ nguyên domain service** | Logic thuần túy, tính toán deterministic backoff và safe failure codes | `NotificationRetryPolicyTest` |
| `NotificationOutboxService` | Application Service | Dependency Inversion & Outbound Port | **Refactor inject port** | Inject `NotificationPersistencePort` thay vì `NotificationJdbcRepository`. Thực thi `NotificationOutboxPort` với `@Transactional(propagation = Propagation.MANDATORY)` trong T2. Không import `adapter.out` | `NotificationTransactionalOutboxIT`, `ModuleBoundaryArchTest` |
| `NotificationOutboxWorker` | Application Worker | Dependency Inversion & Outbound Ports | **Refactor inject ports** | Inject `NotificationPersistencePort` và `NotificationDeliveryPort` thay vì `NotificationJdbcRepository` và `SimulatedNotificationGateway`. Tách biệt transaction: claim/update qua `REQUIRES_NEW`, network dispatch qua `NOT_SUPPORTED`. Không import `adapter.out` | `NotificationTransactionalOutboxIT`, `ModuleBoundaryArchTest` |
| `NotificationPersistencePort` | Mới tạo | Outbound Persistence Port | **Tạo mới Outbound Port** | Đặt trong `notification.application.port`; định nghĩa đúng 4 thao tác enqueue/worker: `insert`, `claimBatch`, `markSent`, `markRetryOrFailed`. `NotificationJdbcRepository` hiện thực port này | `NotificationTransactionalOutboxIT`, `ModuleBoundaryArchTest` |
| `NotificationDeliveryPort` | Mới tạo | Outbound Delivery Port | **Tạo mới Delivery Port** | Đặt trong `notification.application.port`; định nghĩa `send` và default `isSimulator()`. `SimulatedNotificationGateway` hiện thực port này | `NotificationConfigurationTest`, `ModuleBoundaryArchTest` |
| `NotificationConfiguration` | Composition Root | Module-level Configuration | **Chuyển sang module package** | Chuyển sang `com.simulatedbank.banking.notification.configuration` ngoài `application` để bảo toàn kiến trúc Hexagonal (application không import concrete adapters), wiring domain services, simulator và worker | `NotificationConfigurationTest`, `ModuleBoundaryArchTest` |

## Pre-commit verification — 15/11/2026

- Historical combined-run claims in this section are superseded by the verification correction below; they are not evidence for the current workspace.
- Historical unit & architecture evidence:
  - Notification: 5 `NotificationConfigurationTest`, 44 `NotificationPayloadValidatorTest`, 3 `NotificationRetryPolicyTest`, 1 `NotificationMapperTest` (53 tests).
  - Fraud: 16 `FraudRuleEvaluatorTest`, 10 `ReviewStateTransitionTest`, 5 `ReviewFraudFlagRequestTest`, 2 `FraudMapperTest` (33 tests).
  - Architecture: 10 `ModuleBoundaryArchTest` (bao gồm `notificationApplicationMustNotDependOnOutboundAdapters` và `fraudApplicationMustNotDependOnOutboundAdapters`).
- **Integration Evidence (29 tests trên PostgreSQL 16 Alpine Testcontainers disposable):**
  - Notification: 14 `NotificationTransactionalOutboxIT`, 3 `NotificationJpaRepositoryIT` (17 tests).
  - Fraud: 7 `FraudFlagIT`, 5 `FraudJpaRepositoryIT` (12 tests).

Root cause xử lý:
1. `BeanInstantiationException` / `NoSuchMethodException: NotificationJdbcRepository.<init>()`: Khi có nhiều constructor mà constructor chính thiếu `@Autowired`, Spring fallback tìm default no-arg constructor không tồn tại. Đã sửa bằng việc thêm `@Autowired` vào full constructor và chuẩn hóa constructor test 1-arg độc lập.
2. `Reviewer ID security boundary`: Tách biệt `reviewerId` ra khỏi `ReviewFraudFlagRequest` DTO (chỉ còn `decision` và `notes`). Service yêu cầu `trustedReviewerId` từ secure caller context.
3. Test schema lưu ý: DDL trong `src/test/resources/notification/notification-schema.sql` là **test fixture**, tuyệt đối không coi là production Flyway migration.

JUnit XML được đọc và đối chiếu trực tiếp ngay sau lượt chạy fresh; không coi đây là real caller/provider integration. Status: Component Verified / Integration Blocked.

## Verification correction — 15/11/2026

- Latest attempted 13-suite rerun on 16/11/2026: **BUILD FAILED**, 102 tests completed, 4 failures. All four Testcontainers suites failed initialization because no valid Docker environment was found; no PostgreSQL tests were executed in that attempt. `docker version` also reported that `//./pipe/dockerDesktopLinuxEngine` could not be found. Start Docker through the normal user-controlled workflow before rerunning integration tests.
- Last successful sequential combined run on 15/11/2026 (`--rerun-tasks --no-daemon`): **127 tests, 0 failures, 0 errors, 0 skipped** across 13 suites. Notification 72, Fraud 45, architecture 10. Disposable PostgreSQL 16 Testcontainers supplied 29 integration tests; it used test fixtures, not production migrations.
- Latest targeted wiring/architecture run passed: 7 NotificationConfigurationTest and 10 ModuleBoundaryArchTest. XML confirms both Fraud and Notification application-to-adapter rules.
- First full rerun failed at `bootJar` with unresolved main class while an AUD Gradle process was concurrently using the same `build/` directory. A second rerun failed during `compileJava` with `illegal character: '\u0000'` in generated `NotificationMapperImpl.java`; another AUD Gradle process was active. A third rerun had class-loading failures while an AUD process was present. Concurrent build output is a likely contributor, not a proven root cause. The subsequent sequential rerun succeeded; see counts above.
- Provider fixture proves port selection only; it does not prove real SMS/Email provider integration.
- `NotificationOutboxPort` currently references `notification.domain.TransferNotice`; the target public DTO package and shape require Notification/Transfer owner review under module contract `05`. This remains a draft boundary, not an approved integration contract.
- Metrics/event model and synthetic log/artifact scan are listed as independent acceptance work in the task spec; no end-to-end scan or dedicated outbox metrics evidence was found in this review. Mark these **Not verified**, not completed.

## Owner decisions

1. **Retry Policy & Crash/Reclaim Semantics:**
   - *Code thực tế:* `NotificationRetryPolicy` triển khai exponential backoff: $2^{\min(\max(0, \text{retryCount}-1), 6)} \times \text{baseBackoff}$.
   - *Semantics:* `retry_count` tăng khi claim batch. Nếu worker crash lặp lại và `retry_count > maxAttempts`, worker tự động chuyển sang `FAILED` (`DELIVERY_EXHAUSTED`) mà không gửi gateway để tránh vòng lặp crash poison pill.
   - *Cấu hình đề xuất:* Base 5s, max 3-5 lần thử, lease 30s; chờ Architecture team chốt cấu hình chính thức.
2. **Worker Transaction Boundary, deliverItem Visibility & Caller Safety:**
   - Worker sử dụng `PROPAGATION_REQUIRES_NEW` cho claim và status update, `PROPAGATION_NOT_SUPPORTED` cho gateway send.
   - `processBatch` fail-fast bằng `IllegalStateException` nếu caller transaction đang active để ngăn việc giữ connection/locks của caller trong thời gian network I/O.
   - `deliverItem` đã được giới hạn visibility thành package-private (internal delivery step; chỉ `processBatch` là public API) và bổ sung active transaction guard trực tiếp để fail-fast nếu caller transaction đang active/holding lock.
   - *Giới hạn kiến trúc:* Không claim gateway chạy ngoài mọi outer transaction nếu chưa chứng minh qua real production caller/scheduler. `PROPAGATION_NOT_SUPPORTED` chỉ suspend transaction synchronization của thread hiện tại nhưng không giải phóng locks/connections mà caller transaction bên ngoài đang nắm giữ. Ranh giới này là component safety guard; an toàn tuyệt đối phụ thuộc vào việc scheduler T3 production được kích hoạt hoàn toàn ngoài database transaction.
3. **Default Gateway & Simulator Opt-In Convention:**
   - Đã loại bỏ hoàn toàn việc simulator tự bật ngầm trong default profile (`local`).
   - Bean simulator chỉ được đăng ký khi có explicit opt-in: profile `simulator` hoặc property `app.notification.simulator.enabled=true`.
   - `NotificationOutboxWorker` được đăng ký conditional `@ConditionalOnBean(NotificationDeliveryPort.class)`; nếu thiếu gateway, worker không được tạo. Simulator fallback chỉ được đăng ký khi opt-in và chưa có `NotificationDeliveryPort`.
   - Test dùng provider fixture trực tiếp qua port, không chứng minh real provider integration.
4. **Polling Latency Claim:**
   - Giữ trạng thái `Not measured / Planned` cho đến khi có harness benchmark tải chuyên dụng.
5. **Vũ (Transfer):** Duyệt DTO `TransferNotice`, trường dữ liệu nguồn/đích, thứ tự insert trong T2 (trước audit commit), và ngữ nghĩa notification khi chuyển tiền FAILED. Tích hợp caller thật giữ trạng thái **BLOCKED**.
6. **Đạt (Identity):** OTP/challenge linkage & delivery boundary. Không persist/log OTP plaintext; transient handoff an toàn tới gateway còn cần thiết kế và phê duyệt. Tích hợp thật giữ trạng thái **BLOCKED**.
7. **Phương (DB):** Chưa tạo Flyway migration production (chờ version chính thức từ Phương). `claim_token`, `locked_until`, `claim_version` hiện là fixture proposal trong `src/test/resources/`. Migration production giữ trạng thái **BLOCKED**.
