# BE-ID-TEST-02 — PostgreSQL integration, Spring wiring và failure injection

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** `BE-ID-TEST-02`  
> **Ngày:** 17/11/2026  
> **Owner thực hiện:** Codex (Identity)  
> **Branch:** `datht-backend-rework`  
> **Baseline so sánh đã có:** `origin/dev2@cf60428f8df99acf5f7284e7b62ad3c1e17587ea`  
> **Trạng thái:** **PARTIAL** — hạ tầng PostgreSQL, migration, wiring, persistence/restart và failure injection phía Identity đã VERIFIED; integration production với Audit, Notification, Transfer và Admin vẫn BLOCKED.

## 1. Phạm vi và nguồn

Đã đối chiếu `README.md` backend, `docs-common/04`, `09`, `design/01`, `design/04`, `design/05`, tài liệu BE-ID-IMP-01..13 và BE-ID-TEST-01. Không sửa business rule hoặc migration đã áp dụng. Migration V5 Customer đang có trong working tree được chạy như một phần complete chain nhưng test mới không thay đổi hay nhận ownership schema Customer.

Các gap không lặp lại TEST-01:

- Chưa có reusable Testcontainers setup dùng chung cho test mới.
- Chưa có test một database hoàn toàn rỗng chạy toàn chain V1..V5 rồi migrate lại no-op.
- Chưa có bằng chứng đóng context, tạo toàn bộ Spring bean mới và đọc lại state Identity trên cùng PostgreSQL.
- Gateway outage đã có timeout/result-vector nhưng chưa có kịch bản connection refused → replay fail-closed → expire/recovery → dispatch mới.

Concurrency consume/verify, rollback C2, DB outage, CSRF/RBAC và persistence lifecycle đã có test đúng contract; task này chạy lại và tham chiếu, không sao chép test.

## 2. Hạ tầng PostgreSQL Testcontainers

`PostgreSqlIntegrationTestSupport` cung cấp:

- PostgreSQL `16-alpine` thật, database/user/password ngẫu nhiên cho từng container; không dùng credential production/shared.
- Cấu hình đồng nhất `spring.datasource.*` và `spring.flyway.*` tới đúng container.
- Factory Flyway với `validateOnMigrate=true`, `outOfOrder=false`, schema/default schema `banking`.
- Guard so URL của `DataSource` với JDBC URL container trước assertion persistence.
- Map property cho application recreation; test restart đặt property source ở precedence cao hơn YAML để credential rỗng mặc định không thể thắng cấu hình container.

Không dùng H2. Container do JUnit/Testcontainers lifecycle quản lý và Ryuk cleanup.

## 3. Test class và mục đích

| Test class | Phạm vi |
|---|---|
| `PostgreSqlFreshMigrationIntegrationTest` | Empty database; full V1..V5; thứ tự/checksum Flyway; 11 bảng Identity; cột V3/V4; PK/FK/check/unique/index/default; sentinel ngoài schema; rerun no-op và schema fingerprint bất biến. |
| `IdentityApplicationRestartPostgreSqlIntegrationTest` | Hai `SpringApplication` servlet context nối tiếp, context đầu đóng hoàn toàn trước context hai; bean thực được tạo lại; user/credential history/device/RBAC/session/lockout/challenge/auth attempt sống qua restart; SPI authenticate và consume challenge thật; limiter process-local được reset. |
| `OtpDeliveryPostgreSqlIntegrationTest` | Bổ sung connection refused thật ở exception chain; không retry tự động, không session/verify, replay cùng binding không redispatch; chỉ sau expiry recovery mới tạo challenge/dispatch mới; provider secret không lộ log. |
| `StepUpConsumptionPostgreSqlIntegrationTest` | Test hiện hữu được tái sử dụng cho 2/8 consumer, binding race, MANDATORY/JOIN, caller rollback và single committed consume. |
| `IdentityAuthPostgreSqlIntegrationTest`, `IdentitySecurityIntegrationTest`, `IdentityAuthDbFailureIntegrationTest` | Test hiện hữu cho service/repository, opaque session SPI, SecurityFilterChain, CSRF/RBAC, logout/lockout concurrency và DB outage fail-closed. |

## 4. Kết quả theo area

| Test area | Test class | Executed | Result | Evidence |
|---|---|---:|---|---|
| Fresh migration | `PostgreSqlFreshMigrationIntegrationTest` | Có | **VERIFIED** | 1 test PASS; V1..V5 đúng thứ tự, rerun `migrationsExecuted=0`, sentinel ngoài schema còn nguyên. |
| Spring wiring | `IdentityApplicationRestartPostgreSqlIntegrationTest` | Có | **VERIFIED** | 1 test PASS; đúng một `SessionAuthenticator`, `StepUpChallengePort`, `IdentityRoleCommand`, `AuthController`, `SecurityFilterChain`, transaction manager và `AuthAuditPort`; không có production `OtpGateway`. |
| Persistence | Restart test + các PostgreSQL suite hiện hữu | Có | **VERIFIED trong Identity** | User, Argon2 credential/history, device, RBAC, session hash, lockout counter, challenge và auth attempt đọc lại sau context recreation. |
| Actual SPI | Restart/Auth/consume suites | Có | **PARTIAL** | `SessionAuthenticator` và `StepUpChallengePort` implementation thật được gọi. Transfer/Admin production consumer không tồn tại nên end-to-end consumer **BLOCKED**. |
| Audit | Restart + `OtpDeliveryPostgreSqlIntegrationTest` | Có | **PARTIAL/BLOCKED** | Bean thật hiện tại là `SecurityLogAuditAdapter`; C2 failure rollback và C3 fallback đã test. Không có `AuditModuleApi`/`audit_log`, không tuyên bố durable audit integration. |
| Gateway outage | `OtpDeliveryPostgreSqlIntegrationTest` | Có | **VERIFIED simulated boundary** | Connection refused, timeout, unavailable, malformed result, ack failure, missing provider, replay và recovery; production Notification adapter/provider **BLOCKED**. |
| Restart | `IdentityApplicationRestartPostgreSqlIntegrationTest` | Có | **VERIFIED** | Context thứ nhất đóng; context/beans thứ hai là instance mới trên cùng container DB; persistent state còn nguyên, limiter in-memory reset. Đây không phải `refresh()` cùng bean. |
| Concurrency | `OtpDeliveryPostgreSqlIntegrationTest`, `StepUpConsumptionPostgreSqlIntegrationTest`, TEST-01 suites | Có | **VERIFIED phía Identity** | Barrier/latch, transaction/connection độc lập, bounded timeout; không arbitrary sleep. Transfer idempotency/money side effect **BLOCKED**. |
| Failure injection | Delivery/audit/DB failure suites | Có | **VERIFIED phía Identity** | C2 rollback, DB outage 503 fail-closed, provider exception sanitized, không duplicate dispatch/session. Hạ tầng thật bên ngoài chưa có. |

## 5. Mock/test-double disclosure

| Double | Nơi dùng | Lý do | Integration thật chưa được chứng minh |
|---|---|---|---|
| `TestGateway` (`OtpGateway`) | `OtpDeliveryPostgreSqlIntegrationTest` | Điều khiển accepted/timeout/unavailable/exception và quan sát transaction boundary; chỉ test bean. | Notification contact resolver, transport, timeout config, physical delivery/dedupe. |
| `CapturingGenerator` (`OtpCodeGenerator`) | `OtpDeliveryPostgreSqlIntegrationTest` | OTP deterministic để assert hash/wipe; production generator không bị thay trong runtime. | Không có gap production crypto; đây chỉ là fixture secret. |
| `CapturingAudit` (`AuthAuditPort`, wrap `SecurityLogAuditAdapter`) | `OtpDeliveryPostgreSqlIntegrationTest` | Inject lỗi C2 có kiểm soát và ghi nhận transaction active. | Durable `audit_log`, hash chain, Audit transaction implementation. |
| `@MockitoSpyBean JdbcChallengeRepository` | `OtpDeliveryPostgreSqlIntegrationTest` | Inject local acknowledgement DB exception; các call khác delegate repository PostgreSQL thật. | Không thay thế repository trong scenario bình thường. |
| Mockito `LoginSessionService` | Một scenario missing-gateway trong delivery test | Constructor dependency không được gọi ở path request step-up; tránh tạo session ngoài scope. | Login completion không được suy ra từ scenario này; đã có suite login PostgreSQL riêng. |
| Deterministic generator + accepting `OtpGateway` test bean | `StepUpConsumptionPostgreSqlIntegrationTest` | Tạo challenge VERIFIED để test port/lock/transaction thật. | Provider Notification thật và physical delivery. |
| `LoginGateway` test bean | Auth/Security integration suites hiện hữu | Cấp OTP deterministic để đi qua service/HTTP/security/database thật. | Notification provider/contact routing. |

`PostgreSqlFreshMigrationIntegrationTest` và `IdentityApplicationRestartPostgreSqlIntegrationTest` không mock/spy bean. Restart test chủ động assert không có `OtpGateway`, thay vì cài fake rồi báo production wiring thành công.

## 6. Defect và blocker

### 6.0. File thay đổi

| File | Loại | Mục đích |
|---|---|---|
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/testsupport/PostgreSqlIntegrationTestSupport.java` | Mới | Testcontainer factory/isolated credential, cấu hình Spring/Flyway, guard datasource container. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/PostgreSqlFreshMigrationIntegrationTest.java` | Mới | Fresh V1..V5, constraint/index/column/default, rerun no-op, sentinel schema khác. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityApplicationRestartPostgreSqlIntegrationTest.java` | Mới | Wiring bean thật + restart full-context + recovery persistent state + consume/SPI sau restart. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpDeliveryPostgreSqlIntegrationTest.java` | Sửa | Dùng helper container/credential; thêm `connectionRefusedFailsClosedAndRecoveryRequiresExpiryBeforeAFreshDispatch`. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpChallengePostgreSqlIntegrationTest.java` | Sửa | T20 seed `attempt_count=4`, 2 worker — giữ threshold race, loại OOM heap test-defect. |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-02.md` | Mới | Báo cáo evidence, mock disclosure, blockers. |
| `digitalbanking-docs/docs-backend/modules/identity/tasks/README.md` | Sửa | Checklist row BE-ID-TEST-02 PARTIAL. |
| `digitalbanking-docs/docs-backend/modules/identity/README.md` | Sửa | Index báo cáo TEST-02. |
| `DECISIONS.md` | Sửa | DEC-ID-TEST-02 ghi quyết định phạm vi + test-defect record. |

### 6.1. Test infrastructure defect đã sửa

1. Manual Spring application startup ban đầu dùng `SpringApplicationBuilder.properties`, có precedence thấp hơn `application.yml`; Flyway nhận password rỗng và fail SCRAM. Đã chuyển Testcontainer properties thành `MapPropertySource` precedence cao.
2. Flyway tạo schema-history marker có `version/checksum = NULL`; assertion ban đầu tính marker như versioned migration. Đã lọc `version IS NOT NULL`.
3. `StepUpChallengePort` là Spring transactional proxy; assertion exact class ban đầu sai. Đã assert implementation qua `isInstanceOf`.
4. AssertJ `asList()` ban đầu coi authority `Set` là một object. Đã assert trực tiếp collection.

Không phát hiện production Identity defect trong lần chạy focused. Không giảm assertion, không disable test, không đổi production code.

### 6.2. Blocker production

- **Audit:** module chỉ có skeleton; không có `AuditModuleApi`, `audit_log`, hash chain hoặc transaction writer. Durable C2/C3 vẫn `BLOCKED` theo DEC-ID-013.
- **Notification:** chỉ có `OtpGateway` contract; không adapter/provider/contact resolver, resend generation/fencing hoặc durable secret handoff. Physical outage/recovery vẫn `BLOCKED` theo MOD-OI-07/09.
- **Transfer/Limit/Account/Ledger:** Transfer hiện chỉ skeleton; không T0/T1/idempotency/debit/ledger consumer để chứng minh AC-033/T-STEP-01 end-to-end.
- **Admin:** không approval orchestrator production để gọi `IdentityRoleCommand` và Audit trong cùng transaction.
- Không tạo mock production hoặc sửa module khác để che các blocker trên.

## 7. Acceptance checklist

- [x] PostgreSQL Testcontainers infrastructure operational.
- [x] Fresh full Flyway chain pass trên PostgreSQL thật; rerun no-op.
- [x] Spring Boot context khởi động với Identity wiring thật.
- [x] Identity persistence, transaction và sensitive-hash assertions đã chạy.
- [ ] Actual SPI **và production consumer** integration — Session SPI/Identity provider VERIFIED; Transfer/Admin consumer còn BLOCKED.
- [ ] Durable Audit integration — BLOCKED vì module Audit chưa có runtime.
- [x] Gateway outage/failure injection tại contract boundary; ghi rõ simulated, không coi là provider thật.
- [x] Persistent state recovery sau full context/bean recreation.
- [x] Concurrent verify/consume/recovery phía Identity.
- [x] Mock inventory và dependency thiếu được công khai.
- [x] Focused applicable tests đã chạy với evidence.
- [x] Không có Identity-owned production defect còn mở từ lần chạy này.
- [x] Tài liệu/checklist task được cập nhật.
- [x] Không đổi behavior production ngoài phạm vi — thay đổi chỉ ở test/docs.

## 8. Lệnh và evidence thực thi

| Lệnh | Exit | Kết quả |
|---|---:|---|
| `docker info --format 'server={{.ServerVersion}} driver={{.Driver}}'` | 0 | Docker Server 29.7.2, `overlay2`. |
| Focused run đầu trên shared worktree | 1 | 25 test; 2 test-infrastructure failure được chẩn đoán và sửa (§6.1). |
| Focused rerun trên shared worktree | 1 | Phát hiện thêm 2 assertion defect; XML report bị process Gradle khác trong shared worktree ghi đồng thời. Không kill process của người dùng. |
| Focused isolated run: migration + restart + delivery | 0 | `BUILD SUCCESSFUL`; **25/25 PASS**, 0 fail/error/skip (1 migration + 1 restart + 23 delivery). |
| Full `./gradlew build` (backend-only isolated copy, trước khi sửa T20) | 1 | 819 tests, 17 fail: 14 failure môi trường copy (OpenAPI/API-contract path `../digitalbanking-docs/...` không tồn tại, Customer bean thiếu vì copy backend-only) + 1 Identity OOM test-defect T20 + 2 openapi/contract path tương tự. T20 được sửa (§6.1). Không phải production defect; chạy lại trên full repo copy. |
| Identity regression isolated: `./gradlew test --tests 'com.simulatedbank.banking.identity.*' --tests '...IdentityV2MigrationPostgreSqlIntegrationTest' --tests '...PostgreSqlFreshMigrationIntegrationTest'` | 0 | `BUILD SUCCESSFUL`; **32 classes / 585 tests / 0 fail / 0 skip**. |
| Full `./gradlew build --rerun-tasks --no-daemon --max-workers=1 --console=plain` trên full-repo isolated copy (sau sửa T20) | 0 | **BUILD SUCCESSFUL in 6m 41s**; **61 classes / 824 tests / 0 failure / 0 error / 0 skipped**; Identity **30 classes / 577 tests / 0 fail**. Include Customer regression + common error pipeline + OpenAPI contract path đầy đủ. |

## 9. Trạng thái cuối

`BE-ID-TEST-02`: **PARTIAL**. Phần có thể thực hiện độc lập của Identity đã IMPLEMENTED và focused VERIFIED trên PostgreSQL 16; task không thể COMPLETE khi production Audit/Notification/Transfer/Admin dependencies chưa tồn tại. Các acceptance tương ứng giữ `[ ]`, không nâng fake thành integration evidence.
