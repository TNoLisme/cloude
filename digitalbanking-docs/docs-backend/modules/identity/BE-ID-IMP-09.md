# BE-ID-IMP-09 — Passcode fail-closed và Password History

> **Ngày/branch:** 16/11/2026 · `datht-backend-rework`.
> **Mã/phiên bản:** BE-ID-IMP-09 · v1.1 (audit kiến trúc/persistence).
> **Owner:** Đạt (module); **Người thực hiện:** Codex; **Reviewer:** Backend Lead/Security — chưa có sign-off.
> **Trạng thái tổng:** **PARTIAL** theo quyết định trực tiếp `preserve_contracts`, DEC-ID-009. Không đánh DONE/AC-002 hoàn tất.
> **Nguồn:** [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §1.5/3.1/4.2/6.2/6.4/6.9/9.1; [[digitalbanking-docs/docs-common/05. Technical stack.md]] §5.2.2; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.2.1/4.2.4/4.2.7; [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.1.2; BRD BRL-006; DEC-ID-008/009.

## 1. Phạm vi và dependency thực tế

Đã kiểm tra branch và worktree trước sửa; giữ nguyên thay đổi chưa commit IMP-01..08 và Common. Không checkout/reset, không sửa migration đã có, không commit/push. Repo dùng `docs-backend/modules/identity/`, không có `docs-backend/Identity/`.

| Dependency | Trạng thái quan sát | Ảnh hưởng |
|---|---|---|
| IMP-01 | Có V2, bảng device/password_history/app_user/session và FK/index | Tái sử dụng, không thêm DDL |
| IMP-03 | Có opaque session/SPI/CSRF/logout/expiry | Không tạo token/session mechanism khác |
| IMP-08 | PARTIAL, mọi password login bắt buộc OTP theo DEC-ID-008 | Không có persistent device proof/enrollment/trust expiry được duyệt |
| Audit | Có AuthAuditPort, adapter log/metric tạm, chưa có persistence thật | C3 dùng port hiện có; public credential-change C2 contract còn thiếu |
| Notification | Chưa có production OTP provider/contact resolver | Existing MFA vẫn fail-closed khi thiếu gateway; không thêm fake production |

Người điều hành được thông báo các gap và chọn giữ contract: triển khai Passcode denial và credential-history component; không tự thiết kế thêm public endpoint, lifecycle hay trust duration. Đây là scope được duyệt thu hẹp, không phải coi failure path là positive authentication.

## 2. Passcode / trusted-device validation

**IMPLEMENTED cho scope PARTIAL:** `POST /api/v1/auth/passcode` qua pipeline hiện có. Đây chỉ là denial path được người điều hành cho phép giữ tạm; **không đáp ứng success contract API-AUTH-02 hoặc AC-002** của baseline dev2. Không định nghĩa lại contract nguồn thành “luôn 403”.

- Validate username 1–64, UUID canonical, fingerprint 1–512, Passcode là JSON string 6–8 chữ số ASCII; field thiếu/sai constraint → 400 VALIDATION_ERROR, JSON number/array cho Passcode hoặc unknown property → 400 MALFORMED_REQUEST theo Common hiện có.
- Contract chưa chứa device-possession proof. Vì vậy mọi request đúng định dạng đều nhận **403 AUTH_DEVICE_NOT_TRUSTED**, kể cả DB row ACTIVE/trusted hoặc metadata được sao chép. Không query account/device để suy diễn proof; không kiểm Passcode, mint session/challenge, thay credential/history hoặc tăng credential-failure counter.
- Không chấp nhận body/header userId/deviceId/trusted/method làm đường bypass. Bearer session không hợp lệ vẫn bị filter hiện có trả 401; session cũng không được chuyển nghĩa thành device credential.
- C3 AUTH_LOGIN_FAILED dùng anonymous pseudonym SHA-256 của username normalized, reason AUTH_DEVICE_NOT_TRUSTED; không log username, Passcode, fingerprint hay hash credential. Audit C3 lỗi không thay response denial.
- Passcode dùng char[] WRITE_ONLY, redacted toString, wipe trong finally kể cả validation failure sau deserialize. Raw JSON do framework parse không được coi là bộ nhớ đã wipe toàn bộ; không thêm secret logging.
- Các hành vi positive login, invalid-Passcode lockout, registration/verification/trust expiry và public lifecycle **BLOCKED**, không dựng success giả. Fallback password hiện có vẫn MFA bắt buộc.

IMP-08 đã có user-scoped metadata registration, không reactivate REVOKED, recheck ownership/status dưới lock khi OTP hoàn tất và từ chối session gắn revoked/foreign device. Task này giữ nguyên implementation đó; không tuyên bố mới xây revoke API, trust duration hay state EXPIRED không có trong schema.

## 3. Password-history component

**IMPLEMENTED:** `PasswordHistoryService.replacePassword(authenticatedUserId, newPassword)`, chỉ nội bộ Identity.

1. **MANDATORY/JOIN** transaction caller, không commit riêng. Không phải public password-change API hoặc authorization boundary. Caller tương lai phải lấy actor/user từ server-authenticated session, kiểm ownership/PII/step-up policy và C2 audit cùng transaction.
2. Áp phần policy đã có: mặc định 8–128 ký tự, hoa/thường/số/ký tự đặc biệt; max 128 tương thích login hiện có. Ngưỡng được bind bằng `PasswordPolicyProperties` (`app.security.password`): `history-size=3`, `min-length=8`, `max-length=128`; Bean Validation không cho history-size <3, min-length <8, max-length >128 hoặc khoảng độ dài đảo ngược. Có thể cấu hình chặt hơn; không thay giá trị mặc định nguồn hoặc đóng OI-001/OI-006. Rule complexity thuần ở `identity.domain.PasswordPolicy`; policy không chứa thông tin cá nhân vẫn BLOCKED.
3. Spring Data JPA `@Lock(PESSIMISTIC_WRITE)` khóa đúng app_user theo user ID, sau đó `EntityManager.refresh` dưới lock để không dùng entity cũ trong persistence context caller. ACTIVE mới được thay; LOCKED → AUTH_ACCOUNT_LOCKED, disabled/missing → AUTH_FORBIDDEN; không tự unlock tài khoản từ component.
4. Từ chối current password kể cả chưa có history; mặc định so với **3 hash password cũ** mới nhất theo `04` §4.2.7. JPQL projection dùng Pageable, sort `createdAt DESC, historyId DESC`, không tải toàn bộ lịch sử. Hash thứ tư cũ hơn ngoài window mặc định; không tính current hash là một trong ba dòng password cũ.
5. Dùng PasswordHasher/Argon2id hiện có (m=65536, t=3, p=4, salt16/hash32); Spring Data JPA persist hash cũ, dirty-check update credential/timestamps, flush trước khi trả. Cả hai dùng cùng Instant từ Clock UTC đã inject và cùng transaction. DB lỗi/caller rollback → cả hai rollback; candidate wipe ở finally. Không bỏ qua hash/SQL error như missing history.
6. Hai caller khác connection/session cùng user phải chờ cùng user-row lock; caller sau đọc credential/history đã commit mới nhất, không dùng snapshot preflight. Không có in-memory lock hay retry statement trong transaction lỗi.
7. Password bị cấm dùng chung VALIDATION_ERROR, không tiết lộ hash/index/password cũ khớp. Credential snapshot redacted. Không lưu plaintext, không purge history khi retention chưa được duyệt, không tạo storage trùng.

Caller phải quản lý buffer nếu invocation bị Spring MANDATORY interceptor từ chối trước khi vào method. Component không có HTTP entry point; kiểm thử JOIN transaction không được gọi là end-to-end password-change/session/audit authorization đã hoàn tất. Legacy BCrypt chưa được hasher hiện có hỗ trợ; historical hash không parse được sẽ fail-closed, không bị bỏ qua.

## 4. Database và API integration

- **Migration:** không thêm/sửa migration. Reuse `V2__create_identity_and_rbac_tables.sql`: password_history FK user_id RESTRICT, ix_password_history_user_time, app_user credential/timestamps; device/session giữ nguyên.
- Không có tenant_id trong mô hình Single-Bank hiện hành; kiểm scope bằng user_id. Không tuyên bố multi-tenant isolation ngoài schema/contract.
- API-AUTH-02 đã có trong nguồn nhưng chỉ triển khai denial path; public password-change endpoint vẫn chưa có. Success contract 200 + AuthResponse/session của `07` §8.1.2 không bị thay thế; task giữ PARTIAL/BLOCKED.
- Audit đã gỡ riêng ghi chú IMP-09 tự thêm vào `07` §8.1.2 và `design/05` §5.5. Không sửa architecture/Technical stack/schema/OpenAPI để hợp thức hóa code; không reset thay đổi IMP-06..08 có sẵn.

## 5. File implementation và tests

Dưới `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/`:

- `adapter/in/PasscodeController.java`, `PasscodeLoginRequest.java`.
- `application/PasscodeLoginService.java`, `PasswordHistoryService.java`, `PasswordPolicyProperties.java`.
- `application/port/PasswordHistoryRepository.java` (đã chuyển khỏi application root theo BE-CON-39), `domain/PasswordPolicy.java`.
- `adapter/out/JpaPasswordHistoryRepository.java` (thay JdbcPasswordHistoryRepository), `UserCredentialJpaRepository.java`, `PasswordHistoryJpaRepository.java`, `UserCredentialEntity.java`, `PasswordHistoryEntity.java`. Entity chỉ ở adapter/out, không lộ vào API/domain; projection app_user chỉ map credential/timestamps cần dùng, không provision user hoặc ghi lockout/status.

Dưới `src/test/java/com/simulatedbank/banking/identity/`:

- `PasswordHistoryServiceTest.java`: invalid complexity không chạm persistence, wipe buffer; `PasswordPolicyPropertiesTest.java`: cấu hình yếu/sai giới hạn fail startup.
- `PasscodeLoginServiceTest.java`: anonymous C3 denial, audit failure không fail-open/leak.
- `PasscodeHistoryPostgreSqlIntegrationTest.java`: PostgreSQL16/HTTP thật, history/window/ownership/rollback/failure/concurrency; bổ sung stale JPA persistence context và hai lần thay trong cùng caller transaction.
- `OtpDeliveryPostgreSqlIntegrationTest.java`: sửa isolation của log-capture fixture sau full-run failure; attach/detach appender vào namespace Identity thay root, không bỏ assertions secret/throwable/retry và không sửa production OTP/Common.

## 6. Evidence lịch sử trước audit

Runtime: Java 21, Docker 29.7.2, Testcontainers PostgreSQL 16 Alpine, Spring HTTP/filter chain thật. Không mock hashing/history/database locking.

Lần đầu tại `digitalbanking-backend/`:

```bash
./gradlew build --console=plain
```

**BUILD FAILED trong 4m44s:** 41 classes, 494 tests, 2 failures, 0 errors/skips. Hai failure đều ở test mới: `containsOnly('\0')` không áp dụng cho empty buffer; test gộp JSON type/unknown-property vào VALIDATION_ERROR trong khi Common trả MALFORMED_REQUEST. Đã sửa assertion thành so toàn bộ buffer zero và tách malformed JSON khỏi field validation; không đổi application code, không nới security assertion hoặc đổi Common.

Sau sửa, chạy narrowest affected suites và hoàn tất build:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.identity.PasswordHistoryServiceTest' \
  --tests 'com.simulatedbank.banking.identity.PasscodeHistoryPostgreSqlIntegrationTest' \
  build --console=plain
```

**BUILD SUCCESSFUL trong 24s:** 34 tests, 0 failures/errors/skips; compileTestJava, test, jacocoTestReport, check và build hoàn tất. compileJava/bootJar/jar thành công từ lần full, UP-TO-DATE ở lần này.

| Evidence cuối không chồng lặp | Tests PASS | Fail/error/skip còn lại |
|---|---:|---:|
| Các suite ngoài hai class sửa assertion, từ lần full | 460 | 0 |
| PasscodeHistoryPostgreSqlIntegrationTest chạy lại | 26 | 0 |
| PasswordHistoryServiceTest chạy lại | 8 | 0 |
| **Tổng evidence cuối qua hai lần chạy** | **494** | **0** |

Trong tổng trên: Identity **281** tests, gồm **36** tests mới (26 PostgreSQL/HTTP, 8 password-policy unit, 2 Passcode-audit unit); migration/Flyway **10** tests; ArchUnit **5** tests đều có evidence PASS. Không tuyên bố 494 PASS trong một lệnh full cuối: build thành công cuối filter còn 34 tests. Existing password/MFA login/session/CSRF/RBAC/step-up và revocation regression thuộc các suite full đã PASS.

- Race deterministic: giữ transaction sau archive/update đầu tiên; `pg_blocking_pids` chứng minh connection thứ hai chờ user-row lock; release commit rồi caller sau bị VALIDATION_ERROR do password vừa thành current. Chỉ một history row; không arbitrary sleep làm oracle.
- CHECK constraint thật trên INSERT history hoặc UPDATE user chứng minh DB failure rollback cả credential/history, wipe candidate. Caller rollback cũng qua transaction thật.
- Bốn historical hashes cùng timestamp chứng minh tie-break history_id, chỉ ba hash mới nhất bị cấm; hash thứ tư được chấp nhận, không purge.
- HTTP chứng minh unknown/unregistered/untrusted/revoked/trusted/foreign metadata không cấp session/challenge; JSON/UUID/header manipulation và invalid session bị deny qua pipeline thật. Đây là negative proof, không phải positive trusted-device login.
- Static analysis có sẵn: Java compilation và 5 ArchUnit rules **VERIFIED**. Repo không cấu hình Checkstyle/PMD/SpotBugs; scanner độc lập **NOT_TESTED**, không có LSP server cấu hình.
- Evidence local: `build/test-results/test/TEST-com.simulatedbank.banking.identity.{PasscodeHistoryPostgreSqlIntegrationTest,PasswordHistoryServiceTest}.xml`, `build/reports/tests/test/index.html`, `build/reports/jacoco/test/`. XML selection bị Gradle thay sau narrow run; full totals được trích trước khi chạy lại và ghi ở trên. Không coi coverage report narrow cuối là full-suite coverage.

## 7. Acceptance/checklist

- [x] Scope `preserve_contracts` được người điều hành chọn, ghi DEC-ID-009.
- [x] Code Passcode fail-closed và transactional history component đã có.
- [x] Không thêm trust duration/public password-change API/duplicate schema.
- [x] Automated evidence sau audit **VERIFIED**: full build không filter, 501/501 PASS sau sửa cuối; xem §8.4. Bảng §6 chỉ là evidence lịch sử trước audit.
- [ ] AC-002 positive trusted-device Passcode login: **BLOCKED** bởi device-proof/enrollment/expiry contract.
- [ ] Public password-change authorization/PII policy/C2 action/persistence: **BLOCKED** bởi contract/provider chưa có.
- [ ] Trust lifecycle/public revoke API và production Audit/OTP provider: **BLOCKED**, không nhận evidence fixture là integration thật.
- [ ] BE-ID-IMP-09 DONE: không đánh dấu; tổng task giữ **PARTIAL**.

## 8. Audit kiến trúc/persistence và provenance contract

### 8.1. Baseline và phương pháp

- Branch `datht-backend-rework`; HEAD và local remote-tracking `origin/dev2` cùng `56210cc80ad742ddad9139dc1a08fe9ff524465a`. Không có local branch tên `dev2`; dùng `git show origin/dev2:<path>` để đọc bản gốc, không checkout/reset/fetch hoặc suy diễn là remote mới nhất.
- Đọc AGENTS.md; architecture §1.5, §3.1, §4.2, §6.2–6.9, §8–§9, §11; build.gradle.kts; adapters Identity hiện có; so sánh API/Modules/Technical stack/Database/SRS với bản gốc dev2.
- `build.gradle.kts:34` đã có starter-data-jpa; không cần thêm dependency. Spring Data JPA/Hibernate là stack bắt buộc (`05` §4.1/5.2.2), PostgreSQL16/Flyway/READ COMMITTED giữ nguyên.

### 8.2. Findings và xử lý

| Finding | Evidence nguồn/code ban đầu | Xử lý trong phạm vi IMP-09 |
|---|---|---|
| JDBC dùng cho CRUD credential/history, không đúng phân loại persistence | `05` §5.2.2: CRUD/domain → Spring Data JPA; JdbcTemplate cho query phức tạp/batch. JdbcPasswordHistoryRepository trước audit dùng jdbc.update INSERT/UPDATE và không có JPA entity/repository | Đã thay bằng JpaPasswordHistoryRepository, hai Spring Data repositories và hai entity ánh xạ V2; không sửa nguồn stack |
| Port sai package | BE-CON-39 yêu cầu adapter/out hoặc application/port; interface cũ nằm application root | Chuyển sang application/port, migrate toàn bộ callers, không alias/shim |
| Ngưỡng hard-code/rule không ở domain | BE-CON-64 và BE-CON-35/37; service cũ có HISTORY_WINDOW=3, min8/max128 và vòng lặp complexity | Externalize ngưỡng bằng ConfigurationProperties, giữ minimum/default nguồn; complexity chuyển domain Java thuần |
| Scope approval bị ghi thành policy trong nguồn authoritative | DEC-ID-009 chỉ duyệt preserve_contracts/PARTIAL; `07` dev2 §8.1.2 vẫn có 200/session/lockout; Modules dev2 không có §5.5 | Gỡ riêng bullet IMP-09 của API và §5.5 của Modules; chỉ giữ evidence/blocker trong module report/DECISIONS, không đổi ngữ nghĩa nguồn |
| ORM persistence context có thể stale khi lock query trả managed entity đã load trước | Nguy cơ sau chuyển JPA: credential của caller cũ không được dùng làm authoritative check | Refresh sau PESSIMISTIC_WRITE; thêm regression với caller preload, transaction khác đổi password, caller phải từ chối reuse |
| Log-capture fixture bị nhiễu context khác trong full run | OtpDeliveryPostgreSqlIntegrationTest bắt root logs, allSatisfy throwable=null bị Hikari background warning từ cached context khác làm fail | Cô lập appender vào namespace Identity; giữ nguyên assertions OTP/secret/throwable/retry, không sửa production hoặc suppress warning |

**Không có lệnh cấm toàn bộ JDBC:** BE-ARCH-00 §4.2 cho phép JdbcTemplate/native SQL ở adapter/out; §3.1 và `05` §5.2.2 phân biệt công nghệ mặc định với ngoại lệ cho locking/query/batch. Vấn đề ở CRUD thông thường, không ở việc dùng JDBC transport hoặc test fixture JDBC. Existing JdbcUserAccountRepository/JdbcDeviceRepository là implementation của task trước, không phải quyết định miễn trừ kiến trúc. Giữ nguyên chúng theo yêu cầu không sửa phần ngoài scope; cần owner audit riêng các đường CRUD còn lại.

### 8.3. Policy và phần chưa được nghiệm thu

- “Last 3” có nguồn rõ: `04` §4.2.7 định nghĩa hash **password cũ**, so ba hash mới nhất; current hash được kiểm riêng khi history rỗng. Mặc định không đổi thành current + hai dòng cũ, không purge khi thiếu retention. Test default từ chối từng hash trong ba dòng, chấp nhận dòng thứ tư; cấu hình không được hạ dưới ba.
- Fail-closed Passcode phù hợp yêu cầu không bypass và scope tạm được duyệt, **không tương đương đáp ứng FR-002/AC-002**. SRS dev2 AC-002 yêu cầu trusted registered device tạo session, sai quá ngưỡng lockout; `07` §8.1.2 yêu cầu 200/AuthResponse. Các nhánh đó chưa có; không tự invent proof/enrollment/trust lifetime để ép PASS.
- DEC-ID-008 và các thay đổi có sẵn của IMP-06..08 không thuộc lần sửa này. Baseline dev2 §3.8 cho metadata trusted bỏ OTP khác DEC-ID-008; đây là deviation đã ghi quyết định trước, không được báo là trùng hoàn toàn dev2.
- Caller/API đổi password, authentication/ownership/PII/step-up/C2 action/provider thực vẫn BLOCKED. MANDATORY chỉ bảo đảm JOIN transaction, không tự chứng minh authorization/audit. Không có thay đổi migration hoặc module khác để che các thiếu sót.

### 8.4. Full verification sau sửa cuối

Lệnh full, không có `--tests` filter:

```bash
./gradlew build --rerun-tasks --console=plain
```

- **Lần full đầu sau sửa JPA/config/port:** BUILD FAILED trong 10m49s; **501 tests, 500 PASS, 1 failure, 0 errors/skips** (42 classes). Identity 288 tests/1 failure; cả 43 tests Passcode/history/config đều PASS, migration 10 và ArchUnit 5 đều PASS.
- Failure duy nhất: `OtpDeliveryPostgreSqlIntegrationTest.throwingTimeoutDoesNotExposeProviderMessageOrAutomaticallyRetry`, assertion `getThrowableProxy()==null` bắt cả Hikari warning vì fixture attach root logger. Evidence thực tế có event `BankingHikariPool - Pool is empty...` cùng throwable, không phải provider-private/OTP leak hoặc lỗi password-history JPA. Đã sửa fixture isolation vào logger `com.simulatedbank.banking.identity`, giữ nguyên tất cả assertions của scenario.
- **Lần full cuối sau sửa fixture:** **BUILD SUCCESSFUL trong 8m16s**; cùng lệnh không filter, rerun toàn bộ tasks. JUnit XML đọc sau chạy xác nhận **42 classes / 501 tests / 0 failures / 0 errors / 0 skipped**. Đây là một full run sau sửa cuối, không phải cộng evidence từ nhiều selections.

| Phạm vi trong full run cuối | Tests PASS | Fail/error/skip |
|---|---:|---:|
| Toàn bộ backend | 501 | 0 |
| Identity (bao gồm các suite bên dưới) | 288 | 0 |
| Passcode/history/config: 28 PostgreSQL/HTTP + 8 policy unit + 5 config + 2 audit unit | 43 | 0 |
| OTP delivery regression | 21 | 0 |
| Migration/Flyway PostgreSQL | 10 | 0 |
| ArchUnit | 5 | 0 |

Các hàng là phạm vi con, không cộng lại. Hai scenario JPA mới (stale persistence context và hai lần thay trong cùng caller transaction) PASS trong suite PostgreSQL 28 tests. Các assertion history window, hash-only, rollback/failure injection, user ownership và HTTP denial vẫn PASS; không đổi assertion để né lỗi ORM.

- `compileJava`, `compileTestJava`, packaged JAR/build và full tests thành công. Compiler còn cảnh báo unchecked/unsafe operations ở test compilation; không có compile error. Static verification có 5 ArchUnit rules; không cấu hình/chạy scanner Checkstyle/PMD/SpotBugs riêng, không báo chúng PASS.
- Evidence tái đọc được: `digitalbanking-backend/build/test-results/test/TEST-*.xml` (đầy đủ 42 classes ở lần cuối), `digitalbanking-backend/build/reports/tests/test/index.html`, `digitalbanking-backend/build/reports/jacoco/test/`, packaged `digitalbanking-backend/build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar`. Kết quả full cuối thay thế evidence narrow §6 cho lần audit này.
- **Nghiệm thu:** persistence/component/negative-path regression đã VERIFIED theo evidence trên. Task tổng vẫn **PARTIAL**, positive Passcode AC-002 và public password-change/audit/lifecycle/provider vẫn **BLOCKED**; test xanh không thay đổi authoritative requirements hoặc phê duyệt contract.

## Change Log

| Ngày | Nội dung |
|---|---|
| 16/11/2026 | Triển khai scope DEC-ID-009; tái sử dụng V2, thêm denial endpoint/history component và unit/PostgreSQL/HTTP scenarios; ghi rõ phạm vi BLOCKED. |
| 16/11/2026 | Audit theo yêu cầu người điều hành: sửa JDBC CRUD sang Spring Data JPA, port/config/domain, gỡ ghi chú tự sửa contract IMP-09; thêm stale-context/same-transaction/config validation regressions và yêu cầu chạy full build không filter. |
