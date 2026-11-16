# BE-FRD-01 — Verification report

> **Dự án:** Digital Banking MVP; **Owner:** Thịnh  
> **Ngày:** 15/11/2026 (Cập nhật sau hoàn thiện độc lập Phase 2); **Trạng thái:** Component Verified / Integration Blocked
> **Spec:** [BE-FRD-01-rules-and-review.md](BE-FRD-01-rules-and-review.md)
> **Baseline:** `thinh` branch (`c570986` + Common baseline)

## Phân biệt các mức bằng chứng

Pure rules dùng fixture/fake chứng minh evaluator, không chứng minh transfer/DB query. Testcontainers chứng minh fraud persistence/locking. Chỉ transfer/limit integration thật chứng minh end-to-end money-path interaction.

| Check | Lệnh / thủ tục dự kiến | Expected | Actual | Status |
|---|---|---|---|---|
| Proposal review | Review với Vũ/Đạt | Scope/ownership/decision status rõ; Option B không tự thành MVP | Decision register và handoff doc đã tạo; chờ owner họp chốt | Verified (Doc) / Pending Review |
| Rule boundary unit | `FraudRuleEvaluatorTest` (16 tests) | Boundary/version/time results khớp fixture/proposal; so sánh `LARGE_VALUE` và `HIGH_FREQUENCY` | 16 test cases pass; `LARGE_VALUE` (`amount >= threshold`), `HIGH_FREQUENCY` (`>` và `>=`), time bounds, replay exclusion | Verified |
| Matched-value safety at persistence boundary | `FraudFlagIT.directInsertFlagRejectsUnsafeMatchedValue` (Test 5) | `FraudJdbcRepository.insertFlag(...)` trực tiếp chặn unmasked account (kể cả có space/hyphen), email, phone, nested maps/lists, sai kiểu dữ liệu amount/count (fractional, negative, NaN, infinity, overflow), invalid comparator, non-VND currency, và secrets; không echo raw value; zero rows inserted on failure | 12 kịch bản kiểm tra (vi phạm và hợp lệ) đều pass; ném ngoại lệ fail-closed trước khi ghi JSONB; xác nhận không có dòng rác nào vào DB; không echo PII/raw values; số tiền lớn 10,000,000 VND hợp lệ không bị false-positive | Verified |
| Reviewer ID format guard | `FraudFlagIT.reviewFlagRejectsInvalidReviewerId` (Test 6) | Chặn reviewerId null, 0 hoặc âm | Throw `IllegalArgumentException`; chỉ chứng minh format ID, không chứng minh authenticated identity hay RBAC | Verified (Component) / Identity integration Blocked |
| Review state machine & notes validation | `ReviewStateTransitionTest` (10 tests) | State transition đúng; terminal flag không thể review lại (409 Conflict); note max 500 ký tự và không chứa PII/secrets | 10 test cases pass; ném `FRAUD_FLAG_ALREADY_CLOSED`, reject unmasked PII/secrets trong note | Verified |
| Flag persistence & rollback | `FraudFlagIT.t2RollbackLeavesNoFraudFlag` (Test 1) | Insert `fraud_flag` trong T2; rollback transaction làm mất flag; commit giữ flag | Transaction rollback không lưu flag; commit lưu đúng flag | Verified |
| Unique constraint | `FraudFlagIT.uniqueFlagPerTransferAndRuleVersion` (Test 2) | Ràng buộc duy nhất trên `(transfer_id, rule_code, rule_version)` ngăn duplicate flag | Vi phạm ném `DataIntegrityViolationException` | Verified |
| Concurrent review & row locking | `FraudFlagIT.concurrentReviewOneTerminalWinner` (Test 3) | Hai admin review cùng lúc trên cùng 1 flag; cơ chế `FOR UPDATE NOWAIT` + `WHERE status IN ('OPEN', 'UNDER_REVIEW')` đảm bảo 1 winner và 1 conflict | Duy nhất 1 review terminal thành công; review thứ hai nhận `FRAUD_FLAG_ALREADY_CLOSED` (409) | Verified |
| Savepoint isolation primitive | `FraudFlagIT.savepointIsolationRollsBackSubtransaction` (Test 4) | Lỗi DB trong subtransaction rollback về savepoint; transaction chính tiếp tục bình thường | Chứng minh PostgreSQL savepoint primitive hoạt động đúng; chưa phải caller orchestration thật | Verified (DB Primitive) |
| UNUSUAL_PATTERN status | Code inspection `FraudRuleEvaluator.java` | Không tự động kích hoạt rule thử nghiệm khi chưa có policy | Rule `UNUSUAL_PATTERN` đang ở trạng thái Proposed/disabled trong MVP (không evaluate) | Verified (Disabled by default) |
| Caller query correctness | PostgreSQL integration với transfer/limit thật | Timezone, COMPLETED-only, replay exclusion, boundary và concurrent count đúng; ledger/limit invariants giữ | Caller query thuộc Transfer module chưa sẵn sàng; chờ Vũ | Blocked |
| Money integration | `FraudTransferIT` với transfer thật | Approved policy đúng; không double debit/usage; failure behavior đúng | Chưa tích hợp caller T2 thật; chờ Vũ | Blocked |
| Real RBAC integration | Security filter chain với Identity token thật | Enforce quyền `FRAUD_REVIEW` (Ops) và `FRAUD_READ` (Auditor) | Mock/fake test; chờ Đạt tích hợp identity principal | Blocked |
| C2 Audit integration | Review action ghi kèm audit log atomic | Review và append audit log trong cùng transaction | Review service hiện tại chưa gọi `AuditRecorder`; chờ tích hợp C2 | Blocked |

## Evidence và giới hạn

- **Revision:** `dev` branch (`833ee24` + stack convention alignment)
- **Môi trường:** Local Docker PostgreSQL 16 Alpine via Testcontainers, Java 21, Spring Boot 3.5.7
- **Kiểm thử thực thi:**
  ```powershell
  & "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend\gradlew.bat" `
    -p "D:\work\Xgame\XCreative\yuiyL\cloude\digitalbanking-backend" `
    test `
    --tests '*FraudRuleEvaluatorTest' `
    --tests '*ReviewStateTransitionTest' `
    --tests '*ReviewFraudFlagRequestTest' `
    --tests '*FraudFlagIT' `
    --tests '*FraudJpaRepositoryIT' `
    --tests '*FraudMapperTest' `
    --rerun-tasks `
    --no-daemon
  ```
- **Historical result (15/11/2026; superseded by verification correction below):**
  - **Unit Evidence (32 tests, 0 failures, 0 errors, 0 skipped):**
    - `FraudRuleEvaluatorTest`: 16 tests (Pure evaluation: LARGE_VALUE, HIGH_FREQUENCY, boundary, time, replay, safe matched_value)
    - `ReviewStateTransitionTest`: 10 tests (State machine, terminal status protection, 409 Conflict, note length/PII guard)
    - `ReviewFraudFlagRequestTest`: 4 tests (Jakarta Bean Validation: @NotNull, @Positive, @Size(max = 500))
    - `FraudMapperTest`: 2 tests (MapStruct bidirectional Entity ↔ DTO mapping cho RuleConfig và Flag)
  - **Integration Evidence (8 tests on disposable Testcontainers PostgreSQL 16 Alpine, 0 failures, 0 errors, 0 skipped):**
    - `FraudFlagIT`: 6 tests (Persistence, unique constraint, concurrent row lock with NOWAIT, savepoint primitive, direct insertFlag deep validation 12 kịch bản, zero rows on failure, reviewerId guard)
    - `FraudJpaRepositoryIT`: 2 tests (Spring Data JPA CRUD, composite key, active rule window, JSONB matched_value, review updates)
  - **Tổng Fraud tests: 40 tests passed, 0 failures, 0 errors, 0 skipped.**

## Stack & Convention Alignment — Persistence Audit — 15/11/2026

Bảng rà soát chi tiết từng phương thức persistence trong `FraudJdbcRepository` theo `docs-backend/00` §3.1, §4.2 và `docs-common/05` §5.2.2:

| Phương thức / Class | Bản chất thao tác | Trạng thái triển khai | Quyết định công nghệ | Lý do kỹ thuật cụ thể | Tests liên quan |
|---|---|---|---|---|---|
| `insertRuleConfig(FraudRuleConfig)` | Ordinary CRUD (Config insert) | Spring Data JPA | **Chuyển 100% sang JPA** | `ruleConfigJpaRepository.saveAndFlush(ruleConfigMapper.toEntity(config))` với `Persistable<FraudRuleConfigId>` để giữ strict INSERT semantics. Composite PK `@IdClass(FraudRuleConfigId.class)`. Fail-fast nếu thiếu JPA | `FraudJpaRepositoryIT`, `FraudFlagIT` |
| `findActiveRuleConfigs(Instant)` | Ordinary CRUD (Window lookup) | Spring Data JPA | **Chuyển 100% sang JPA** | `ruleConfigJpaRepository.findActiveRules(evalTime)`. MapStruct chuyển đổi sang domain DTO. Fail-fast nếu thiếu JPA | `FraudJpaRepositoryIT`, `FraudFlagIT` |
| `insertFlag(...)` | Ordinary CRUD (Flag insert) | Spring Data JPA | **Chuyển 100% sang JPA** | Sau khi validate allowlist `matchedValueValidator.validate(matchedValue)`, persist entity qua `flagJpaRepository.save(entity)`. Ánh xạ JSONB qua `@JdbcTypeCode(SqlTypes.JSON)`. Không giữ raw-JDBC fallback; fail-fast nếu thiếu JPA | `FraudFlagIT`, `FraudJpaRepositoryIT` |
| `findFlagById(long)` | Ordinary CRUD (Lookup by PK) | Spring Data JPA | **Chuyển 100% sang JPA** | `flagJpaRepository.findById(id).map(flagMapper::toDomain)`. Fail-fast nếu thiếu JPA | `FraudFlagIT`, `FraudJpaRepositoryIT` |
| `findFlagsByTransferId(long)` | Ordinary CRUD (FK lookup) | Spring Data JPA | **Chuyển 100% sang JPA** | `flagJpaRepository.findByTransferId(transferId)`. Fail-fast nếu thiếu JPA | `FraudJpaRepositoryIT` |
| `findFlagForUpdateNowait(long)` | PostgreSQL-specific concurrency | Native SQL / JdbcTemplate | **Giữ Native SQL / JDBC** | Thực thi `SELECT ... FOR UPDATE NOWAIT` để fail-fast ngay lập tức nếu flag đang bị admin khác giữ lock, ngăn chặn deadlock và latency cao | `FraudFlagIT` |
| `updateReview(...)` | PostgreSQL-specific concurrency | Native SQL / JdbcTemplate | **Giữ Native SQL / JDBC** | Atomic conditional update `WHERE flag_id = ? AND status IN ('OPEN', 'UNDER_REVIEW')` bảo đảm duy nhất 1 winner terminal và trả về 409 Conflict cho reviewer thứ hai mà không vi phạm baseline schema `docs-common/04 §4.7.2` (không tự thêm cột `version`) | `FraudFlagIT` |

Bảng rà soát các thành phần khác:

| Class / Component | Hiện trạng | Convention áp dụng | Quyết định | Lý do kỹ thuật cụ thể | Tests liên quan |
|---|---|---|---|---|---|
| `FraudRuleConfigEntity` & `FraudRuleConfigId` | Mới tạo | Spring Data JPA Entity | **Tạo mới JPA Entity** | Bảng `banking.fraud_rule_config` là reference/configuration data, ánh xạ composite PK (`rule_code`, `version`) qua `@IdClass(FraudRuleConfigId.class)` và Lombok `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` | `FraudJpaRepositoryIT`, `FraudMapperTest` |
| `FraudRuleConfigJpaRepository` | Mới tạo | Spring Data JPA | **Tạo mới JPA Repository** | Quản lý cấu hình rule (`save`, `findById`, `findActiveRules`) | `FraudJpaRepositoryIT`, `FraudMapperTest` |
| `FraudRuleConfigMapper` | Mới tạo | MapStruct 1.6.x | **Tạo mới MapStruct Mapper** | Chuyển đổi hai chiều giữa `FraudRuleConfigEntity` và domain `FraudRuleConfig` (`componentModel = "spring"`) | `FraudMapperTest` |
| `FraudFlagEntity` | Mới tạo | Spring Data JPA Entity | **Tạo mới JPA Entity** | Bảng `banking.fraud_flag`, sử dụng `@JdbcTypeCode(SqlTypes.JSON)` cho `matched_value` JSONB và Lombok | `FraudJpaRepositoryIT`, `FraudMapperTest` |
| `FraudFlagJpaRepository` | Mới tạo | Spring Data JPA | **Tạo mới JPA Repository** | Quản lý CRUD flag thông thường (`findById`, `findByTransferId`, `save`) | `FraudJpaRepositoryIT`, `FraudMapperTest` |
| `FraudFlagMapper` | Mới tạo | MapStruct 1.6.x | **Tạo mới MapStruct Mapper** | Chuyển đổi hai chiều giữa `FraudFlagEntity` và domain `FraudFlagItem` (`componentModel = "spring"`) | `FraudMapperTest` |
| `ReviewFraudFlagRequest` | Mới tạo | Jakarta Bean Validation | **Tạo mới Request DTO** | Khớp 100% OpenAPI spec `FraudReviewRequest`: chỉ gồm `@NotNull` cho `decision` và `@Size(max = 500)` cho `notes`. Đã loại bỏ `reviewerId` khỏi payload để ngăn ngừa giả mạo danh tính | `ReviewFraudFlagRequestTest` |
| `FraudTransferSnapshot` | Java record | Pure domain snapshot | **Giữ nguyên Java record** | Không chuyển sang Lombok theo nguyên tắc bảo vệ domain records | `FraudRuleEvaluatorTest` |
| `FraudRuleEvaluator` | Pure evaluator | Pure business rules | **Giữ nguyên pure service** | Logic thuần túy, deterministic, không phụ thuộc framework hay DB | `FraudRuleEvaluatorTest` |
| `FraudMatchedValueValidator` | Custom validator | Domain security invariant | **Giữ nguyên custom validator** | Bảo vệ ranh giới JSONB persistence (allowlist, PII masking, ép kiểu số hữu hạn/dương, no-raw-echo) | `FraudFlagIT` |
| `ReviewStateTransition` | Pure state machine | Domain state transition | **Giữ nguyên state machine** | Bảo vệ terminal status (`ALLOWED`, `BLOCKED`, `CLOSED`), ném 409 Conflict | `ReviewStateTransitionTest` |
| `FraudPersistencePort` | Mới tạo | Outbound Persistence Port | **Tạo mới Outbound Port** | Đặt trong `fraud.application.port`; định nghĩa đúng 3 thao tác cần thiết cho review workflow: `findFlagForUpdateNowait`, `updateReview`, `findFlagById`. `FraudJdbcRepository` hiện thực port này | `FraudFlagIT`, `ModuleBoundaryArchTest` |
| `FraudReviewService` | Application Service | Dependency Inversion & Outbound Port | **Refactor inject port** | Inject `FraudPersistencePort` thay vì `FraudJdbcRepository`. Không import `adapter.out` | `FraudFlagIT`, `ReviewFraudFlagRequestTest`, `ModuleBoundaryArchTest` |

## Historical regression — 15/11/2026 (superseded)

Combined NOT/FRD/architecture `--rerun-tasks --no-daemon`: **125 tests, 0 failures, 0 errors, 0 skipped**; Fraud suites: evaluator 16, review 10, review DTO validation & format boundary 5, flag IT 7, JPA IT 5, mapper 2 (tổng 45 Fraud tests). Notification suites: payload 44, configuration 5, retry 3, outbox IT 14, JPA IT 3, mapper 1 (tổng 70 Notification tests). Architecture: 10 `ModuleBoundaryArchTest` (bao gồm `fraudApplicationMustNotDependOnOutboundAdapters` và `notificationApplicationMustNotDependOnOutboundAdapters`). PostgreSQL Testcontainers disposable. All tests verified pass.

## Verification correction — 15/11/2026

- Latest attempted 13-suite rerun on 16/11/2026: **BUILD FAILED**, 102 tests completed, 4 failures. Testcontainers initialization failed because Docker API at `npipe:////./pipe/dockerDesktopLinuxEngine` was unavailable; no PostgreSQL integration tests executed in this attempt.
- Last successful sequential combined run on 15/11/2026 (`--rerun-tasks --no-daemon`): **127 tests, 0 failures, 0 errors, 0 skipped**. Fraud 45, Notification 72, architecture 10. Testcontainers used disposable schema fixtures, not production migrations.
- `reviewFlagRejectsInvalidReviewerId` only validates positive numeric ID; it does not prove authentication or RBAC.
- `reviewWhileRowLockedMapsPostgresLockConflict` separately holds a PostgreSQL row lock and verifies SQLState `55P03` maps to current `FRAUD_FLAG_ALREADY_CLOSED` behavior. Error semantics for an OPEN row temporarily locked by another review remain subject to owner/API contract confirmation.
- Prior independent run had 8 ArchUnit rules and 123 tests. The latest run includes both `fraudApplicationMustNotDependOnOutboundAdapters` and `notificationApplicationMustNotDependOnOutboundAdapters` and reports 10 architecture tests.

Historical combined-run claims below are superseded by the verification correction before owner decisions. Do not use those counts as current evidence.

## Owner decisions

1. **Schema Baseline Compatibility (`fraud_flag`) & Rule Ordering:**
   - Bảng `fraud_flag` tuân thủ 100% baseline `docs-common/04 §4.7.2` (không thêm cột extension `version`). Concurrency control sử dụng pessimistic lock `FOR UPDATE NOWAIT` kết hợp `WHERE status IN ('OPEN', 'UNDER_REVIEW')`.
   - Script DDL trong `src/test/resources/fraud/fraud-schema.sql` là **test fixture** phục vụ Testcontainers; production Flyway migration chưa được tạo (chờ Phương).
   - Truy vấn `findActiveRules` khôi phục đúng thứ tự `ORDER BY r.ruleCode ASC, r.version DESC` khớp 100% baseline JDBC cũ.
   - Semantics `insertRuleConfig` được bảo toàn là strict INSERT: `FraudRuleConfigEntity` triển khai `Persistable<FraudRuleConfigId>` kết hợp `saveAndFlush`, đảm bảo duplicate `(rule_code, version)` luôn ném `DataIntegrityViolationException` và không ghi đè dữ liệu có sẵn.
2. **Quy tắc `UNUSUAL_PATTERN` & Snapshot Signature:**
   - `FraudTransferSnapshot` chỉ chứa 10 trường cần thiết (`transferId`, `customerId`, `sourceAccountId`, `destinationAccountId`, `amount`, `currency`, `occurredAt`, `windowTransactionCount`, `isReplay`, `correlationId`). Quy tắc `UNUSUAL_PATTERN` được giữ **disabled/proposed** trong MVP.
3. **Savepoint Test Evidence & Concurrency Lock Mapping:**
   - Test `savepointIsolationRollsBackSubtransaction` trong `FraudFlagIT` chứng minh năng lực kỹ thuật của PostgreSQL/JDBC savepoint primitive trên Testcontainers khi connection/transaction còn usable. Chưa phải caller orchestration end-to-end trong real Transfer `@Transactional`.
   - Test concurrency `concurrentReviewOneTerminalWinner` assert chính xác ngoại lệ nghiệp vụ `BankingBusinessException(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED)` cho reviewer thứ hai; `findFlagForUpdateNowait` ánh xạ PostgreSQL error `55P03` (lock unavailable) sang `FRAUD_FLAG_ALREADY_CLOSED` và propagate mọi lỗi DB không liên quan.
4. **C2 Audit Boundary:**
   - `FraudReviewService` chưa gọi `AuditRecorder`, do đó review action chưa thể tuyên bố atomic với C2 audit. Tuyệt đối không thêm Fraud C2 Audit integration khi `AuditModuleApi` và actor contract chưa được Architecture/Security duyệt chính thức; giữ trạng thái **BLOCKED**.
5. **Reviewer Identity & RBAC Enforcement:**
   - Đã tách `reviewerId` hoàn toàn khỏi `ReviewFraudFlagRequest` DTO; client payload không thể chỉ định reviewer identity. Trường `reviewerId` bắt buộc phải truyền qua đối số riêng biệt `trustedReviewerId` từ caller context.
   - Kiểm tra `reviewerId > 0` thuần túy là kiểm tra format số nguyên dương hợp lệ, **tuyệt đối không chứng minh authenticated identity hoặc quyền RBAC**.
   - Quyền hạn `FRAUD_REVIEW` (Ops) và `FRAUD_READ` (Auditor) giữ trạng thái **BLOCKED** cho đến khi Đạt (Identity) hoàn thành tích hợp token/principal thật.
6. **Vũ (Transfer):** Cần chốt comparator cho `HIGH_FREQUENCY` (`>` hay `>=`), cửa sổ tính toán, và loại trừ replay. Tích hợp caller T2 thật giữ trạng thái **BLOCKED**.
7. **Phương (DB):** Chưa tạo Flyway migration production do chưa được cấp version chính thức từ Phương; các kiểm thử hiện tại sử dụng script DDL cô lập trong `src/test/resources/`. Migration production giữ trạng thái **BLOCKED**.

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial fraud review proposal |
| 0.2 | 11/11/2026 | Added caller/query evidence boundaries |
| 0.3 | 12/11/2026 | Aligned Common context/error/config/security ownership |
| 0.4 | 14/11/2026 | Reconciled docs-common source priority, schema/API conflicts, rule proposals and owner decisions |
| 0.5 | 15/11/2026 | Triển khai 100% component độc lập; 30 tests pass |
| 0.6 | 15/11/2026 | Independent Review đính chính: baseline 04, UNUSUAL_PATTERN disabled, savepoint primitive |
| 0.7 | 15/11/2026 | Bổ sung validation fail-closed tại persistence boundary `FraudJdbcRepository.insertFlag` (chặn unmasked account, email, phone, nested structures, type mismatch); thêm test 5 trong `FraudFlagIT`; tổng 31 tests pass |
| 0.8 | 15/11/2026 | Bổ sung xác thực reviewerId hợp lệ trong `FraudReviewService.reviewFlag`; thêm test 6 trong `FraudFlagIT`; tổng 32 tests pass |
| 0.9 | 15/11/2026 | Làm sâu kiểm tra persistence boundary (chặn fractional count, negative/overflow, invalid comparator, non-VND currency; không echo raw value; kiểm chứng zero DB rows khi insert fail; kiểm chứng số tiền lớn 10M VND không bị false positive); cập nhật 97 fresh tests pass |
| 0.10 | 15/11/2026 | Cập nhật suite fresh run tổng hợp 100 tests pass (kết hợp Notification hardened opt-in 5 configuration tests); toàn bộ 32 Fraud tests giữ nguyên kết quả pass hoàn hảo |
| 0.11 | 15/11/2026 | Hoàn thiện JPA + MapStruct persistence migration cho toàn bộ ordinary CRUD: `insertFlag`, `findById`, `findByTransferId`, `insertRuleConfig`, `findActiveRuleConfigs`. Giữ JDBC native SQL cho `findFlagForUpdateNowait` và `updateReview`. Cập nhật combined regression fresh run 114 tests pass |
| 0.12 | 15/11/2026 | Tách biệt `reviewerId` khỏi `ReviewFraudFlagRequest` DTO khớp OpenAPI spec; bổ sung test nullable fields & transaction rollback trên JPA Testcontainers; fresh run 117 tests pass |
| 0.13 | 15/11/2026 | Khôi phục ordering `ruleCode ASC, version DESC` trong `findActiveRules`; bảo toàn INSERT semantics cho `insertRuleConfig` qua `Persistable<ID>`; ánh xạ chính xác PostgreSQL 55P03 lock failure trong `findFlagForUpdateNowait` sang `FRAUD_FLAG_ALREADY_CLOSED`; hiệu chỉnh claim RBAC; fresh run 121 tests pass |
| 0.14 | 15/11/2026 | Chuẩn hóa Hexagonal Architecture & Dependency Inversion: tách `FraudPersistencePort` trong `fraud.application.port`, `FraudReviewService` inject port và loại bỏ hoàn toàn import `adapter.out`; bổ sung ArchUnit test `fraudApplicationMustNotDependOnOutboundAdapters`. Test count ở entry này là lịch sử, xem verification correction hiện hành. |
