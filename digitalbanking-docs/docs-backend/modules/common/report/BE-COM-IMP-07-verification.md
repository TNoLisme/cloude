# BE-COM-IMP-07 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản:** [BE-COM-IMP-07](../tasks/BE-COM-IMP-07.md), v1.0 ngày 12/11/2026; giữ nguyên tiêu chí
> **Ngày / người thực hiện:** 2026-11-12T19:09:40+07:00 / Codex theo yêu cầu Phương
> **Trạng thái task:** **Blocked — thiếu implementation consumer nghiệp vụ W3/W4/W5**
> **Kết luận verification:** 5/10 AC Verified, 5/10 Blocked; common regression và PostgreSQL error pipeline đạt, chưa đạt integration quality gate đầy đủ
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Đã đọc lại ba README trước task/source; áp dụng thứ tự **docs-common/01–11 → design → docs-backend**, `07` quyết định ngữ nghĩa API. Task/report không phải nguồn đặc tả mới. Chủ động quyết định phần mơ hồ theo tài liệu và ghi tại §5.2. Theo yêu cầu owner: chỉ report, không tạo evidence/ cho task 07; kết quả/hash/excerpt được nhúng bên dưới, Gradle vẫn sinh build/test-results và reports tiêu chuẩn.

Revision kiểm tra: `87427ce3b1d5e6eb04ffea65d21c2902a0ab0484` (commit gộp 05/06), cộng hai thay đổi test tại §2 và source hashes §4.4. Production Java/resources/migration **không đổi** trong task 07; vì vậy không tạo/chuyển nghiệp vụ của owner vào common. Ba deletion `.gitkeep` đã staged từ trước được giữ nguyên, không stage/restore/commit. Phiên này chưa commit task 07.

Mục tiêu task: chứng minh common qua consumer thực và quality gate. Đã làm phần độc lập: kiểm lỗi PostgreSQL 16 thực qua production servlet/security/advice/writer, marker transaction rollback và không HTTP retry; contract hiện có; rule kiến trúc; regression; mapping và blocker. Chưa có consumer thật để kiểm trace/payload crypto/storage/PII/audit/notification/idempotency/session/money path. Không dùng probe hoặc mocks để nhận các luồng đó Done.

Môi trường: Linux amd64/OpenJDK 21.0.13.0.3; Gradle 8.14.3; Spring Boot 3.5.7; Docker 29.8.0; profile test; embedded Tomcat random TCP port + Java HttpClient; Testcontainers postgres:16-alpine; JDBC/Spring translation/transaction thật. Test-only schema `quality_gate.probe`, ba hàng synthetic. Không H2, mock SQLException hoặc DB shared/real-data. Container tự dọn sau test; không sửa V1 để thêm bảng nghiệp vụ giả.

### 1.1. Nguồn và dependency đã kiểm

| Nguồn | Mục và phạm vi áp dụng |
|---|---|
| `03. SRS.md` | AC-022/023/024/030/032/034; FR-038/039; NFR-018/020/025/027; không suy nghiệm thu end-to-end từ common |
| `04. Database design & analysis.md` | §4 và audit §4.6.1/§9.4; dữ liệu/schema owner phải tồn tại mới kiểm invariants/hash chain |
| `06. System architecture.md` | §6.2–6.3 module ownership/one-way dependency, transaction ở application service |
| `07. API contracts.md` | §2–§6; §4.1–4.4 Problem/catalog/header/retry; §7.2.17/§8.9.1 health; §10 contract matrix CT-API-001..024 |
| `09. Test plan.md` | §2–§4; §7–§10 statuses, scenario/exit/defect/reporting, không H2/mock cho concurrency/DB, không fake Pass khi missing dependency |
| `11. Resource & Staffing Plan.md` | §3–§7 owner/phối hợp/W3–W6; timeline không phải evidence tiến độ |
| `design/01. audit-traceability.md` | §4.6 context persistence, §5.2 C1/C2/C3, §5.6–5.7 allowlist/technical log tách audit |
| `design/02. consistency-concurrency.md` | §7.10 SQLSTATE/retry; §11 transaction/retry boundary; §14 test matrix và Appendix C code context. Dùng kèm nguồn 01–11 |
| `openapi/digital-banking-v1.yaml` | Problem/ErrorCode/HealthResponse/HealthComponent; kiểm subset runtime fields, không gọi đây là full OpenAPI validator |
| common README §3–§7 và reports 01–06 | Primitive/wiring/SQLSTATE đã có; chuyển handoff DB mock-only của 02 thành PG thật trong scope common; giữ gaps task 05/06 |

Reports 01–04: primitive/component verification đã có; 05: Blocked thiếu identity thật; 06: Done common/local, chưa business metrics/C3 fallback/Cloud. Đã đọc kết luận/gap/handoff và source tương ứng, không lấy trạng thái Done thay runtime evidence. E03 regression chạy lại toàn bộ suite trên revision hiện tại.

Inspection `src/main/java/com/simulatedbank/banking/<module>`: cả **10 module nghiệp vụ đều 0 file .java** (bảng §3.1). Migration hiện tại chỉ có `V1__init_schema.sql` với `CREATE SCHEMA IF NOT EXISTS banking;`. Các module README/design có nội dung không đồng nghĩa có implementation. Hiện không có bảng session/challenge/customer/account/transfer/ledger/idempotency/audit/outbox nghiệp vụ để truy bằng chứng.

## 2. Thay đổi thực tế

| File/thành phần | Thay đổi và mục đích | AC |
|---|---|---|
| `src/test/java/.../common/error/PostgreSqlErrorPipelineIntegrationTest.java` | 8 test, PG lỗi thật → Spring DataAccessException → production HTTP Problem; context classification, rollback marker, không HTTP retry, contract health/early errors | AC04/05/06 (một phần), G03 |
| `src/test/java/.../architecture/ModuleBoundaryArchTest.java` | Thêm rule common không phụ thuộc JDBC/repository/JPA/DataSource/transaction infrastructure; 5 architecture rules tổng | AC06 (một phần), G02 |
| backend README: Common integration quality gate | Lệnh chạy, phân biệt test probe/business consumer, dependency/giới hạn | G04/AC01 |
| common README v1.9; task 07; tasks README | Đồng bộ kết quả partial, trạng thái Blocked và link report; giữ scope/AC nguồn | G01/G04/AC01 |
| report này | Ma trận W3/W4/W5, source/test mapping, actual evidence, blockers và handoff | G01/G04/AC01 |

Không phát hiện lỗi production common trong các scenario đã chạy nên không sửa runtime để tạo diff. `Probe`/`Fixture`/session resolver chỉ là test code; không trở thành interface/adapter nghiệp vụ hoặc migration production. Test JDBC coordinator do harness sở hữu để kiểm formatter sau rollback; generic handler không mở TX, không retry/cập nhật outcome.

## 3. Ma trận tiêu chí chấp nhận

Giữ nguyên mọi AC; một AC nhiều vế chỉ Verified khi đủ. Phần generic PostgreSQL của AC04 đạt không thay các vế audit/money/idempotency còn thiếu.

| AC ID | Tiêu chí gốc | Test/inspection | Kết quả thực tế | Evidence | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Source trace + decisions §5.2 | Không đổi contract/policy; nhãn Đề xuất/Open và hash-version gap giữ rõ | E01/E03/E04 | **Verified** |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Diff + 5 ArchUnit rules | Chỉ tests/docs/report; production common không sở hữu DB/TX hoặc phụ thuộc business | E03/E04 | **Verified** |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | clean check với PG16/HTTP thật | 199 pass, 0 fail/error/skip cho các thay đổi; không mock/H2 để chứng minh SQLSTATE | E03 | **Verified** |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report/docs/status/link/hash inspection | Đủ 10 AC, mọi blocked vế/owner/reopen rõ; không evidence/ mới, không token thật | E04 | **Verified** |
| COM-IMP-07-AC01 | Có mapping năng lực common → consumer → scenario/test → nguồn, bao phủ các nhóm W3/W4/W5; mọi phụ thuộc thiếu được ghi, không bỏ luồng để nhận Done | §3.1 matrix + source inventory + test reports | Bao phủ W3/W4/W5, 10 consumer rows; ghi thiếu dependencies, không bỏ luồng/nhận Done | E01/E03/E04 | **Verified** |
| COM-IMP-07-AC02 | Correlation truyền đúng qua request/challenge/transfer/ledger/audit/notification và job liên quan; retry/step-up không làm đứt flow; crypto/JCS được consumer dùng đúng | Context/crypto/JCS regression primitive; consumer inventory | Không có challenge/transfer/ledger/audit/notification/job thật và bảng để liên kết; không kiểm consumer hash protocol | E01/E03/B07-W3/W4/W5 | **Blocked** |
| COM-IMP-07-AC03 | PII/secret được bảo vệ trong storage tương ứng, response, log, audit và notification; quyền/CSRF dùng thực tế và rate-limit bucket đúng customer | Security/crypto/masking/log regressions bằng fixtures; inventory | Không có real sessions/customer quotas/storage/channel để kiểm quyền/CSRF/multi-session/PII xuyên consumer | E01/E03/B07-W3/W4/W5 | **Blocked** |
| COM-IMP-07-AC04 | Lỗi lock/timeout/constraint/audit được trả đúng code/status/retry theo ngữ cảnh; error handler không làm đổi outcome hoặc mở transaction nghiệp vụ | PG pipeline 8 tests, §4.2; inventory | Real generic SQLSTATE/rollback/owner-wrapper formatter đạt; thiếu unique idempotency/ledger invariant/C1-C2 atomic rollback/C3 denial+audit thật | E03/B07-W4/W5 | **Blocked** |
| COM-IMP-07-AC05 | Các consumer trả đúng contract khi success, validation, security, replay/conflict/in-progress và failure; không auto wrap success, không lộ lỗi nội bộ | OpenAPI required fields của health/Problem + 46-code regression | Health/early errors thật đúng contract; không consumer replay/conflict/in-progress/step-up/success nghiệp vụ để kiểm end-to-end | E03/B07-W3/W4/W5 | **Blocked** |
| COM-IMP-07-AC06 | Regression phần common và tích hợp đạt, không còn Critical/High hoặc lỗi vi phạm AC trong phạm vi; dependency một chiều giữ nguyên; report không nhận nghiệm thu toàn bộ D1–D5 chỉ từ common | 199 tests/5 ArchUnit + §3.2 D1–D5 | Common regression đạt; full integration/release security defects/D1–D5 chưa chứng minh do thiếu consumer. Không claim không Critical/High toàn scope | E03/B07-W3/W4/W5/RELEASE | **Blocked** |

### 3.1. Common → consumer → scenario/test → source, đầy đủ W3/W4/W5

Tất cả source business nằm dưới `src/main/java/com/simulatedbank/banking/<consumer>`. “0 Java” là inspection E01, không phải failed business test. Tên test primitive hiện có không được đổi thành bằng chứng consumer thật. Không tạo ID nguồn mới/trùng nghĩa; các COM-* là mapping đã có của common.

| Mốc / consumer / owner | Năng lực common cần dùng và scenario thật | Test/source ID cần đạt | Test hiện có chạy E03 và giới hạn | Dependency / trạng thái |
|---|---|---|---|---|
| W3 identity/auth — Đạt | SessionAuthenticator + CSRF/permission; Clock TTL; HMAC/SHA OTP/challenge; context challenge/audit; login/OTP/step-up/revoke | CT-API-001..005/010; COM-HTTP-03/CRY-04/CTX-06; SRS AC-022/024/030/033, 07 §3/§8.1 | HttpSecurityTest cookieMutationsRequireMatchingSessionCsrfWhileAuthenticatedBearerDoesNot, stepUpAndOtpUserQuotasDoNotBecomePerSessionQuotas; CryptoPrimitivesTest known-answer; **test resolver, không identity thật** | 0 Java; thiếu session/challenge/device/CSRF persistence/permission seed, B07-W3 Blocked |
| W3 customer — Đạt | AES/HMAC identifier storage, masking response/log, context, ownership/scope | CT-API-017; COM-CRY-01..03/PII-02; SRS FR-039/AC-023/024; 07 §2.6/§8.2 | CryptoPrimitivesTest/PiiMaskingUtilsTest/ObservabilityIntegrationTest đạt utility/harness; không customer persistence | 0 Java; thiếu entity/repository/DTO mapper/storage và actual principal, B07-W3 Blocked |
| W3–W4 account — Đạt | Encrypt/blind index/last4; protected account views; resource ownership và lock context | CT-API-015/017; COM-PII-02/DB-01; AC-022/023/024; 06 §6, 07 §8.3 | HttpSecurityTest permission gates + masking utilities; PG probe lock đạt **không account lock/ownership** | 0 Java; thiếu account schema/service/ownership, B07-W3/W4 Blocked |
| W4 transfer — Vũ | Build normalized payload theo07 §5.2 → JCS/SHA; idempotency replay/conflict/in-progress; context retry/step-up; customer bucket | CT-API-006..014; COM-JCS-02/CTX-06/DB-01..04; T-IDEM-01..06; AC-022/034 | JcsCanonicalizerTest/ErrorContractTest; HttpSecurityTest transferQuotaIsSharedAcrossSessionsAndUsersOfOneCustomerWithRealRetryAfter; PG wrapper409 chỉformatter, **không idempotency** | 0 Java; thiếu transfer/idempotency schema + T0/T1/T2 application services và identity/customer, B07-W4 Blocked |
| W4 ledger — Phương | Context ledger, DB-error invariant mapping; same TX debit/credit/account/audit; no duplicate sequence | COM-CTX-06/DB-03..04; SRS AC-022/034; design/02 T-CON-01..04; 06 §7–§8 | PG unique/check probe thật + INTERNAL_ERROR500; **không uq_ledger_transfer_seq hay double-entry** | 0 Java; thiếu ledger schema/service + transfer/account ports, B07-W4 Blocked |
| W4–W5 limit — Vũ | Clock/ZoneId business date theo nguồn; common config; customer quota distinct business limits; lock/timeout | COM-CFG-01/03/DB-01..02; design/02 T-LIM-01..03; 07 §8.6 | AppPropertiesTest/RequestContextTest/HttpSecurityTest + PG timeout; không daily usage race/approval thật | 0 Java; thiếu limit_config/usage/maker-checker và customer lock, B07-W4 Blocked |
| W3–W5 audit — Thịnh | Context persisted, JCS/SHA versioned chain/allowlist; C1/C2 rollback, C3 best-effort fallback; search/export | COM-JCS-02/DB-04/PII-02; design/01 T-AUD-*; CT-API-013/019; AC-022/023/034 | Primitive JCS/hash fixtures; real55P03 wrapped AUDIT_WRITE_FAILED500 chỉ**classification sau probe rollback**, không audit recorder/hash chain | 0 Java; thiếu audit schema/writer/API/chain/permissions và transfer/identity state; hash_version wording gap giữ nguyên, B07-W5 Blocked |
| W5 fraud — Thịnh | Context/error/log + owner metrics; safe flag/review/notes, engine fail-open không đổi tiền | CT-API-022; COM-CTX-06/PII-02; SRS AC-026/027; 07 §8.7; design/03 | Common log/redaction tests; không fraud rule/flag transaction hoặc fail-open consumer | 0 Java; thiếu engine/rule/flag/review và transfer context, B07-W5 Blocked |
| W4–W5 notification — Thịnh | Mask enqueue payload/storage/log, correlation/outbox worker/job bind/cleanup, retry sau commit | COM-CTX-06/PII-02; design/02 T-OUT-*; SRS FR-041..043/AC-023 | ContextHttpTest async + RequestContextTest scopes; **không actual enqueue/delivery/job** | 0 Java; thiếu outbox/worker/gateway và transfer/challenge linkage, B07-W5 Blocked |
| W5 admin — Đạt + Thịnh | RBAC/CSRF/step-up/checker + context/audit + DTO masking | CT-API-016/020/021/022; COM-HTTP-03; SRS AC-024/030 | HttpSecurityTest permissionAndCheckerGatesApplyBeforeOwnersBusinessChecks; chỉHTTP gate fixture, không maker≠checker/approval state | 0 Java; thiếu approval/admin services + identity/limit/fraud/audit consumers, B07-W5 Blocked |
| W3–W6 operational common — Phương | Health app/DB, Problem/trace, low-cardinality HTTP metrics/redaction, rollback formatter/architecture | CT-API-023 **common scope**; COM-OBS-01/HTTP-01/DB-01..03/ARCH-01 | PostgreSqlErrorPipelineIntegrationTest 8; ObservabilityIntegrationTest 6 + HealthControllerTest 3 và regression; real health controller, test-only credentials | Common local Verified; real identity-backed operator login/scrape/Cloud vẫn B07-W3/RELEASE |

### 3.2. D1–D5 quality/release gate

| Gate | Trạng thái của consumer thật | Evidence đã có và điều kiện còn thiếu |
|---|---|---|
| D1 concurrency | **Blocked** | PG deadlock/serialization primitives đạt; chưa có balances/ledger/invariant queries/real transfers để chứng minh no negative balance/double debit |
| D2 idempotency | **Blocked** | JCS primitive, HTTP error catalog và wrapper priority đạt; chưa có key/payload ownership, persisted response/replay/resume/exactly-once side effects |
| D3 recovery/audit | **Blocked** | Probe marker rollback đạt; chưa có T1/T2, audit C1/C2, crash/reconcile và retry cùng key;08-state closed connection không chứng minh commit outcome chưa biết |
| D4 security/audit | **Blocked** | Common fixture RBAC/CSRF/rate/masking/log regression đạt; chưa có actual principal/ownership/storage/channels/immutable audit/security scan/full TLS ingress |
| D5 load | **Blocked** | HTTP histogram/export đạt (06 regression); không chạy workload transfer20RPS×10phút vì endpoint/data/ledger chưa có. Không lấy health load thay workload chuẩn |

## 4. Nhật ký kiểm chứng và evidence nhúng

### 4.1. Các lệnh thực chạy

Working directory Gradle: `/home/phuong/workspace/cloude/digitalbanking-backend`. Evidence thuộc Git baseline ở §1 + new test hashes §4.4; thời gian E03 lấy từ XML và output build.

| Evidence | Lệnh / cách kiểm thực chạy | Expected → actual | Exit |
|---|---|---|---|
| E01 | `rg --files .../src/main/java`; Python Path.rglob('*.java') cho từng module; đọc V1 và reports01–06 | Xác định readiness → common có source, 10 business modules có 0 Java, migration chỉtạo schema; thiếuconsumer, không đổi thành Done | 0 inspection |
| E02a | `./gradlew test --tests '*PostgreSqlErrorPipelineIntegrationTest' --tests '*ModuleBoundaryArchTest' --offline --console=plain` | Compile test mới → lỗi AssertJ generic inference: assertThat(TransactionTemplate.execute(...)) ambiguous; tests chưa chạy | 1; không tính pass |
| E02b | Cùng lệnh sau thêm explicit `<Integer>` cho execute | 7 PostgreSQL tests mới +5 architecture tests đạt; BUILD SUCCESSFUL20s | 0 |
| E03 | `./gradlew clean check --offline --console=plain` sau thêm actual08003 test | 22 suites, **199 tests, 0 fail,0 error,0 skip**, JaCoCo/5 ArchUnit; BUILD SUCCESSFUL48s | 0 |
| E04 | `git diff --check`; kiểm source/XMLhash, relative doc links và AC/status/report; new test whitespace riêng vì file untracked | Không whitespace lỗi source/docs; docs đúng Blocked, đủ10AC; staged.gitkeep unchanged | 0 |

E03 XML PostgreSqlErrorPipelineIntegrationTest bắt đầu `2026-11-12T12:04:55.275Z`, 8 tests/0 fail/0 skip. Lỗi E02a chỉ ở test code, đã sửa và retest E02b/E03; không thay oracle. Không có test bị skip trong suite đã chạy. Các luồng consumer thiếu **không có test execution**; ghi Blocked, không đếm như pass hoặc tự thêm @Disabled để che dependency.

Artifacts tiêu chuẩn được giữ tại `digitalbanking-backend/build/test-results/test/TEST-*.xml`, `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`. Chúng có thể bị Gradle ghi đè; bảng/hash/excerpts ở report này giữ snapshot kết quả. Không tạo evidence/07 hoặc sửa evidence05. Không có scan Critical/High toàn hệ thống hoặc full OpenAPI validator/client generation trong phiên này; đó là gap release, không suy từ green build.

### 4.2. Dòng kết quả thực từ PostgreSQL/HTTP suite

```
QUALITY_DB operation=statement-timeout sqlstate=57014 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
QUALITY_DB operation=unique sqlstate=23505 code=INTERNAL_ERROR status=500 rollback_marker=0 attempts=1
QUALITY_DB operation=check sqlstate=23514 code=INTERNAL_ERROR status=500 rollback_marker=0 attempts=1
QUALITY_DB operation=classified-conflict sqlstate=23505 code=IDEMPOTENCY_CONFLICT status=409 rollback_marker=0 attempts=1
QUALITY_DB operation=captured sqlstate=08003 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
QUALITY_CONTRACT health=200 direct_HealthResponse anonymous=401 customer=403 invalid_header=400
QUALITY_DB operation=nowait sqlstate=55P03 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
QUALITY_DB operation=in-progress sqlstate=55P03 code=IDEMPOTENCY_IN_PROGRESS status=409 rollback_marker=0 attempts=1
QUALITY_DB operation=audit-lock sqlstate=55P03 code=AUDIT_WRITE_FAILED status=500 rollback_marker=0 attempts=1
QUALITY_DB operation=captured sqlstate=40001 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
QUALITY_DB operation=captured sqlstate=40P01 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
QUALITY_DB operation=lock-timeout sqlstate=55P03 code=SERVICE_UNAVAILABLE status=503 rollback_marker=0 attempts=1
```

Các SQLSTATE là exception **thật** từ PostgreSQL/JDBC, không constructor SQLException mock. NOWAIT/lock timeout giữ row lock bằng connection riêng; deadlock có hai connection lock ngược thứ tự với barrier; serialization dùng hai SERIALIZABLE transactions đọc cùng snapshot rồi update; một transaction commit, một victim thật. Statement timeout set local 100ms để failure-injection nhanh; đây không phải đổi baseline timeout production. Unique/check trên probe, không trên bảng idempotency/ledger chưa có. Closed connection08003 từ actual PgConnection đã close; không kill DB shared hoặc giả định commit outcome.

Mỗi HTTP probe increment marker ở id3 trong transaction rồi kích lỗi; sau rollback, query độc lập xác nhận marker0/rowcount3. assert attempts delta1 xác nhận common handler không tự retry; không chứng minh business orchestrator retry2–3 lần hoặc outcome transfer. Victim từ deadlock/serialization được bắt giữ ở test rồi chuyển qua test endpoint tới production advice; không nói request đó điều phối tiền.

Context classification fixture: cùng55P03 generic→503 SERVICE_UNAVAILABLE, explicit in-progress→409 IDEMPOTENCY_IN_PROGRESS+Retry-After, explicit audit→500 AUDIT_WRITE_FAILED; cùng23505 generic→500 INTERNAL_ERROR, explicit conflict→409 IDEMPOTENCY_CONFLICT. Owner wrapper chỉ kiểm priority của formatter; không tự suy nghĩa idempotency/audit từ SQL text. Mọi Problem có code/status/retryable/header đồng bộ; retry header trong1–5s. Response không SQL/constraint/schema/JDBC URL/password/stack.

### 4.3. Contract, regression và giới hạn mức kiểm

`productionHealthAndPreMvcSecurityResponsesMatchAvailableOpenApiFields` gọi **HealthController production** với real DB, đối chiếu required fields/type/status/UTC/components của HealthResponse trong YAML, không success envelope; kiểm anonymous401/customer403/invalid-correlation400 và required Problem fields/catalog/retry/trace. Credential resolver là test double. Không có mismatch YAML phát hiện trong subset đã kiểm; không chứng nhận toàn OpenAPI/API vì chưa có consumers/full schema validator. ErrorContractTest hiện có còn đối chiếu đủ46 code/status/retryable theo07 và YAML, và toànsuite được chạy E03.

Regression 06 trong E03 tiếp tục kiểm DB thật UP/DOWN→200/503HealthResponse, metrics được bảo vệ, JSON log thực/leak injection/async và histogram export. Regression05 dùng test sessions chứng minh CSRF/customer bucket wiring, không nâng thành identity thật integration. Regression04 chứng minh primitive và caller fixtures, không ciphertext lưu DB thật hoặc hash-chain consumer.

ArchUnit 5 rules đều pass: common→business cấm; common→persistence/transaction infrastructure cấm (mới); domain web/adapter cấm; inbound→outbound direct cấm; modulecycles cấm. Rule mới kiểm production classes hiện có, loại test fixtures bằng ImportOption.DoNotIncludeTests; không cấm SQLException classification hoặc safeActuator health. Các business layers còn trống nên không tuyên bố đã review kiến trúc implementation nghiệp vụ.

### 4.4. Snapshot files và toàn bộ kết quả E03

| File | SHA-256 |
|---|---|
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/common/error/PostgreSqlErrorPipelineIntegrationTest.java` | `3493b675bb809f6967708f00efada584f59b720208326d513b0faae70b7dae7f` |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/architecture/ModuleBoundaryArchTest.java` | `df3fb35b7ed7ceb490244a5b1189a21cec2a68589296f6cf86e98307637c92c7` |
| `digitalbanking-backend/src/main/resources/db/migration/V1__init_schema.sql` | `9e8f7acb2c6e9fca17541801bb96ee479937dc4fd582679b02fefb2b1aada484` |
| `digitalbanking-backend/build/test-results/test/TEST-com.simulatedbank.banking.common.error.PostgreSqlErrorPipelineIntegrationTest.xml` | `8de211b8925c132e56cbe043b6348b6ef57f03cff2953f1da65e447445e7bdca` |
| `digitalbanking-backend/build/test-results/test/TEST-com.simulatedbank.banking.architecture.ModuleBoundaryArchTest.xml` | `b23a55e8d9fbd0fe7e28bf27f346a71beb9db57f68b0e96779c21938b85514fe` |

| Suite | Tests | Failure | Error | Skip |
|---|---:|---:|---:|---:|
| BackendApplicationContextTest | 1 | 0 | 0 | 0 |
| ModuleBoundaryArchTest | 5 | 0 | 0 | 0 |
| AppPropertiesTest | 20 | 0 | 0 | 0 |
| HealthControllerTest | 3 | 0 | 0 | 0 |
| HttpSecurityComponentsTest | 18 | 0 | 0 | 0 |
| HttpSecurityServletTest | 2 | 0 | 0 | 0 |
| HttpSecurityTest | 10 | 0 | 0 | 0 |
| ObservabilityIntegrationTest | 6 | 0 | 0 | 0 |
| ContextHttpTest | 3 | 0 | 0 | 0 |
| ContextServletTest | 1 | 0 | 0 | 0 |
| RequestContextTest | 19 | 0 | 0 | 0 |
| CryptoConfigTest | 15 | 0 | 0 | 0 |
| CryptoOperationsTest | 1 | 0 | 0 | 0 |
| CryptoPrimitivesTest | 6 | 0 | 0 | 0 |
| JcsCanonicalizerTest | 44 | 0 | 0 | 0 |
| PiiMaskingUtilsTest | 15 | 0 | 0 | 0 |
| ErrorContractTest | 4 | 0 | 0 | 0 |
| ErrorHttpTest | 14 | 0 | 0 | 0 |
| PostgreSqlErrorPipelineIntegrationTest | 8 | 0 | 0 | 0 |
| HealthEndpointTest | 2 | 0 | 0 | 0 |
| FlywayMigrationTest | 1 | 0 | 0 | 0 |
| PostgreSqlFlywayMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| **Tổng** | **199** | **0** | **0** | **0** |

## 5. Gap, quyết định và điều kiện tiếp tục

### 5.1. Blockers không hạ AC

| ID | AC ảnh hưởng | Gap và tác động | Owner / điều kiện tiếp tục | Trạng thái |
|---|---|---|---|---|
| B07-W3 | AC02/03/05/06 | Identity/customer/account đều 0 Java; thiếu principal/session/challenge/quyền/CSRF thật, PII storage và ownership | Đạt triển khai SessionAuthenticator adapter, migrations/services/DTO của session/challenge/customer/account; cung cấp synthetic data và tests CT-API-001..005/015..017; tích hợp với Phương/common | **Blocked** |
| B07-W4 | AC02/03/04/05/06 | Transfer/ledger/limit đều 0 Java; thiếu money T1/T2, idempotency, schema invariants, usage và luồng customer quota thật | Vũ phụ trách transfer/limit, Phương ledger, Đạt account: cần ports, schema, transactions, seed; chạy T-CON/T-IDEM/T-LIM/T-STEP và CT-API-006..014 theo nguồn | **Blocked** |
| B07-W5 | AC02/03/04/05/06 | Audit/fraud/notification/admin đều 0 Java; thiếu C1/C2 rollback, C3 fallback, immutable chain, PII outbox/jobs/review/maker-checker | Thịnh phụ trách audit/fraud/notification; Đạt + Thịnh admin: cần recorder/API/chain/allowlist/outbox/jobs/approval; tích hợp W3/W4 và chạy các ca audit failure, security denial và notification retry | **Blocked** |
| B07-RELEASE | AC06, AC-030/032 và D1–D5 | Chưa có đầy đủ security/defect gate, load, API validator/generated client, release candidate hoặc Cloud verification | Vũ điều phối cùng các owner; task 08 và owner hạ tầng tiếp tục khi consumer sẵn sàng | **Blocked** |
| OI-COM-05 / OQ-15 | Consumer crypto | Rotation protocol/key version/blind-index migration chưa chốt từ trước; primitive một khóa không chứng minh rotation | Security/Phương + customer/account + DevOps chốt trước khi dùng nhiều khóa; không tự bật previousKeys hay thêm format | **Open tại nguồn, giữ nguyên** |
| AUDIT-HASH-VERSION | AC02 / audit consumer | 04 §4.6.1 mô tả append hash_version; 04 §9.4 và payload của 06 đã chứa version. Report 04 DEC-07 đã ghi; chưa có consumer audit để kiểm protocol | Thịnh/audit + architecture đồng bộ câu chữ normative trước khi persist chain; không lấy primitive fixture làm đặc tả mới | **Open tại nguồn, giữ nguyên** |
| B05 / H06 handoffs | AC02..06 | Actual cookies, username quota, proxy/IP/TLS, business metrics, C3 fallback và retention chưa được chứng minh | Identity/audit/DevOps và task 07/08 theo reports 05/06; không chuyển gap sang Done từ probe test | **Giữ trạng thái nguồn** |

Task 07 cần consumer thật nên không đổi scope AC thành “common/local Done” để đóng việc. Có thể tiếp tục từng nhóm ngay khi owner sẵn sàng. Tái chạy E03 sau thay đổi runtime/consumer và bổ sung evidence theo luồng; chỉ Done khi mọi AC Verified.

### 5.2. Quyết định đã thực hiện

| ID | Điểm mơ hồ/conflict | Quyết định theo nguồn và tác động |
|---|---|---|
| D07-01 | Task yêu cầu end-to-end nhưng chưa có business implementation | Theo task §1 và 06 §6, làm phần common độc lập, ghi missing consumer/Blocked; không tạo nghiệp vụ giả hoặc nhận scope owner vào common |
| D07-02 | Task 02 bàn giao PostgreSQL integration, hiện chỉ có schema rỗng | Tạo disposable quality_gate.probe trong test, không sửa migration V1; kiểm mapper bằng PostgreSQL failures/transactions thật. Không dùng probe để thay evidence bảng idempotency/ledger/audit |
| D07-03 | 55P03 có thể là 409 hoặc 503; 23505 có thể conflict hoặc internal | Theo catalog 07 và common baseline, design/02 chỉ bổ sung context: generic 55P03→503, 23505→500; explicit owner wrapper mới chọn 409/audit 500. Không parse constraint name/SQL để suy rule |
| D07-04 | Cách kiểm handler không sở hữu transaction | Thêm ArchUnit cấm common phụ thuộc persistence/transaction infrastructure; kiểm marker rollback và mỗi HTTP call chỉ một attempt. Test Probe được phép điều phối TX trong src/test; không thêm abstraction production |
| D07-05 | Template đề cập evidence folder nhưng owner chỉ yêu cầu report | Giữ evidence 05 có sẵn; không tạo evidence 07. Nhúng runtime lines/hash/XML summary/lệnh vào report; standard Gradle build outputs không phải evidence folder mới |
| D07-06 | T-AUD IDs bị trùng giữa design/01 và design/02 | Dẫn cả document + ID; dùng các COM-DB/COM-ARCH/CT-API hiện có và method names. Không gán T-AUD hoặc CT-API-013 Pass cho wrapper fixture |
| D07-07 | Timeout test khác baseline Đề xuất | SET LOCAL 100ms chỉ dùng cho disposable failure injection để tạo SQLSTATE nhanh; không đổi production timeout, ngưỡng/quota hoặc công nhận policy release |
| D07-08 | Các fields được kiểm đều đúng, có cần sửa YAML/runtime? | Không sửa khi chưa có hành vi sai. Ghi rõ contract subset đã kiểm và chưa chạy full validator. errors là optional theo 07 §4.1; không ép mọi 400 phải có errors dựa trên examples |
| D07-09 | Common regression có đủ chứng minh không còn Critical/High toàn task? | Không. Chỉ kết luận không phát hiện production defect trong hành vi đã test; AC06 Blocked đến khi consumer/security/quality gate thật đạt. Không nhận QA/Security sign-off |

## 6. Kết luận và bàn giao

- **10 AC: 5 Verified (COM-G01..04, COM-IMP-07-AC01); 5 Blocked (COM-IMP-07-AC02..06)**. Không bỏ hoặc hạ scope AC để nhận Done.
- **199 tests pass, 0 fail/error/skip**, gồm 8 integration tests mới và 1 architecture gate mới. Generic PostgreSQL error pipeline đã được chứng minh thật, bổ sung phần bàn giao từ task 02; các hành vi này không cần sửa production code.
- **Task 07 chưa đóng được**: thiếu cả 10 consumer implementation và schema/nghiệp vụ để kiểm đủ. Task/tracker là **Blocked**, task 05 vẫn Blocked, task 08 vẫn Planned. Reviewer/sign-off: **Chưa review**.
- W3: Đạt; W4: Vũ/Phương/Đạt; W5: Thịnh/Đạt nhận điều kiện tiếp tục ở §3.1/§5.1. Phương/common hỗ trợ tích hợp đúng nguồn và chạy test từng consumer khi có implementation. Không gửi thông báo cho owner ngoài phiên này.
- Bàn giao 08 gồm baseline commit, hashes, test commands/report và giới hạn. Trên Cloud phải chạy lại trace/health DB down/protected metrics/PII redaction/proxy TLS/auth/consumer/throttle cùng D1–D5 theo môi trường thực. Kết quả local không thay bằng chứng Cloud/release.
