# BE-COM-IMP-06 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản:** [BE-COM-IMP-06](../tasks/BE-COM-IMP-06.md), v1.0 ngày 12/11/2026
> **Ngày / người thực hiện:** 2026-11-12T18:47:03+07:00; Codex theo yêu cầu owner
> **Trạng thái task:** Done — phạm vi common/local
> **Kết luận verification:** Verified — 9/9 AC trong phạm vi task; không phải nghiệm thu identity, Cloud hoặc toàn bộ NFR-025
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Đã đọc trước ba README của `digitalbanking-docs/`, `docs-common/`, `docs-backend/`, rồi mới đọc task/code. Tên thực tế của repo là `digitalbanking-docs` và `modules/common/tasks`; các tên `digitalbankingdocs`/`taks` trong yêu cầu được hiểu là đường dẫn này, không tạo cây tài liệu trùng.

Phạm vi thực hiện: structured JSON stdout và redaction toàn logger; HTTP completion trước cleanup; HTTP timer/histogram/export; bảo vệ operational endpoints; health app/DB và HealthResponse; kiểm thử, hướng dẫn và report. Không viết business metric emitter, audit recorder, dashboard, central log stack, job hoặc nghiệp vụ giả trong production. Theo chỉ dẫn của owner, mọi kết quả/decision nằm trong report này, **không tạo evidence/**. Thư mục evidence task 05 có sẵn từ trước được giữ nguyên.

Baseline Git: `b44e927adbe9efad60c4af737b151e2d9e67c7af`; worktree đã có thay đổi task 05 chưa commit trước phiên này. Không reset/stage/commit hoặc nhận các thay đổi đó là sản phẩm task 06. Snapshot đúng runtime/test đã chạy được định danh bằng SHA-256 ở §4.5; working directory `/home/phuong/workspace/cloude/digitalbanking-backend`. Các thay đổi tài liệu và wiring APP_ENVIRONMENT trong Compose được kiểm riêng sau clean check, không thay byte runtime/test đã kiểm.

Môi trường: Linux 7.0.0-43-generic amd64; OpenJDK 21.0.13.0.3; Gradle wrapper 8.14.3; Spring Boot 3.5.7; Docker Engine 29.8.0; Testcontainers `postgres:16-alpine`; profile `test`; embedded Tomcat random TCP port + Java HttpClient, Prometheus exporter thật qua `@AutoConfigureObservability`. Chạy offline dùng dependency/image đã có. Flyway chạy trên PostgreSQL thật; không H2 hoặc mock DB trong integration.

Nguồn chuẩn và traceability đã đối chiếu:

- `docs-common/03. SRS.md`: FR-038/039, NFR-020/025, AC-022/023/032; phạm vi metric nghiệp vụ không đồng nhất với metric HTTP.
- `docs-common/05. Technical stack.md`: §5.6 và mục observability (Actuator/Micrometer, JSON stdout, correlation; tracing/central logs optional).
- `docs-common/06. System architecture.md`: §6 (common không phụ thuộc nghiệp vụ), §12.1–12.3 (log/metric/health).
- `docs-common/07. API contracts.md`: §2.2, §2.6–2.7, §7.2.17, §8.9.1; DTO health, Admin/Auditor, probe riêng, DOWN→503 HealthResponse; đối chiếu OpenAPI HealthResponse/HealthComponent, không đổi hợp đồng nguồn.
- `docs-common/08. Deployment infrastructure.md`: §9.1–9.3 (safe health, log fields, secret/PII prohibition, low-cardinality metrics, retention Open).
- `docs-common/09. Test plan.md`: §2.2, §8, §10; Verified chỉ trong scope/environment đã thực chạy, không tự nhận sign-off.
- `docs-common/design/01. audit-traceability.md`: §5.6–5.7, §12.1; log kỹ thuật tách audit, health không tạo audit; chỉ bổ sung khi không trái 01–11.
- `docs-backend/modules/common/README.md`: §6.2, §7.2 COM-OBS-01; `tasks/README.md` COM-G01..04 và task 06; deployment configuration §7.

Dependency 02/03/04 đã có writer/context/crypto và verification từ trước, regression được chạy lại. Task 05 đã có common security chain/SPI nhưng **vẫn Blocked do thiếu adapter identity thật**. Task 06 dùng đúng production chain với resolver/controller **chỉ trong test** để xác minh common observability/RBAC; không sinh session giả trong production hoặc đóng blocker của 05. Login/revoke/OTP/cookie lifecycle thật và propagation DB xuyên nghiệp vụ bàn giao identity/07. Đây không phải bằng chứng mọi dependency đã nghiệm thu.

## 2. Thay đổi thực tế và quyết định

| File/thành phần | Hành vi thay đổi | AC |
|---|---|---|
| `common/config/SafeJsonLogEncoder.java`, `resources/logback-spring.xml` | JSON một dòng, UTC/service/environment/trace; allowlist event HTTP; không serialize raw message/arguments/unknown MDC/throwable message hoặc stack | AC01/02 |
| `common/context/HttpRequestLog.java`, `CorrelationAndRequestIdFilter.java` | Completion có route template/method/status/latency/action/outcome/error code; một record qua async/redispatch, trước cleanup; fallback UNMATCHED | AC01/02/05 |
| `common/config/SessionAuthenticationFilter.java` | Chỉ thêm request attribute SHA-256 masked internal user ID sau khi session hợp lệ; giữ nguyên xác thực/cleanup của task 05 | AC01/02 |
| `common/error/ProblemWriter.java` | Gắn approved ErrorCode vào attribute cho completion; không thêm metric nghiệp vụ, không log exception detail | AC01/03 |
| `common/config/SecurityFilterChainConfig.java` | Sau public GET health/info, mọi actuator endpoint còn lại yêu cầu ROLE_ADMIN hoặc ROLE_AUDITOR | AC04 |
| `common/config/HealthController.java` | GET /api/v1/health: safe database/application statuses, checkedAt UTC/version; DOWN→503 HealthResponse JSON; không outbox giả | AC04 |
| `resources/application.yml`, `application-test.yml` | Bật HTTP histogram, ẩn health components/details, tắt banner; log environment explicit override cho test/deployment | AC01/03/04 |
| `compose.yaml`, `.env.example` | Truyền APP_ENVIRONMENT cho container; config --quiet đạt, không in secret | AC01/05 |
| `ObservabilityIntegrationTest.java` (6 tests) | Actual JSON logs, leak/injection, async/concurrency, live timer/export, role/probe và PostgreSQL loss | AC01..04 |
| `HealthControllerTest.java` (3 tests) | Contract DEGRADED→200, missing DB/liveness fail closed, bỏ contributor details; mocks chỉ chứng minh DTO/mapping | AC04 |
| Backend README, deployment/configuration §7, common README v1.8 | Cách đọc trace/log/metric/health, ownership, suppression tradeoff và handoff | AC05/G04 |
| Task 06 và tasks README | Cập nhật Done common/local, chờ review; giữ nguyên task 05 Blocked | G04 |

Mọi source Java mới thuộc 4 package common hiện hành; health là operational HTTP contract của common/config, không tạo module nghiệp vụ mới. Không sửa migration, schema, audit, transaction tiền hoặc policy nguồn. Common không import ngược module nghiệp vụ; 4 ArchUnit checks đạt.

Các quyết định khi tài liệu/implementation chưa cụ thể:

| ID | Điểm cần quyết định | Quyết định và căn cứ |
|---|---|---|
| D06-01 | Mẫu yêu cầu evidence folder nhưng owner nói chỉ report | Ưu tiên chỉ dẫn phiên này; nhúng kết quả, runtime excerpts và hash vào report. Test reports tiêu chuẩn vẫn do Gradle sinh trong build/, không tạo evidence/06 |
| D06-02 | MDC camelCase so với log snake_case; route trước MVC | Giữ MDC correlationId/requestId của 03, map sang correlation_id/request_id theo 06/08; route dùng template, UNMATCHED khi chưa có handler. Không dùng raw URI/query |
| D06-03 | Không thể bảo đảm redaction free text từ bất kỳ thư viện/exception | Global fail-closed allowlist; bỏ message/arguments/stack/unknown MDC, giữ logger/level/exception class. HTTP completion cung cấp event/action/outcome/error code an toàn. Hạn chế chẩn đoán free text được chấp nhận cho common MVP; richer coded events và C3 fallback của owner cần tích hợp riêng, không bật raw fallback |
| D06-04 | user_id source 08 yêu cầu mask, 07 nói internal ID nếu có | SHA-256 internal ID từ validated Session snapshot; không username/customer/account ID thô, không dùng user hash làm metric label. Đây là masking, không tuyên bố anonymization |
| D06-05 | Metrics chỉ cần bảo vệ/internal, role cụ thể chưa quy định cho scraper | Dùng Admin/Auditor cho operational read endpoints, nhất quán health read contract và deployment hướng dẫn quản trị. Không tạo anonymous scrape hoặc dùng credentials tĩnh; ingress/identity integration thật còn bàn giao |
| D06-06 | Health 503 và global Problem convention | Theo 07 §8.9.1: trả application/json HealthResponse, không cưỡng chế Problem; thiếu DB contributor→DOWN, liveness lỗi→DOWN. Không giả lập notificationOutbox pending=0; đây là ví dụ DTO, module chưa cung cấp tín hiệu |
| D06-07 | Public health groups thêm vào payload Actuator | Giữ tên nhóm liveness/readiness vốn được Actuator cung cấp; không nhầm tên nhóm với detail. show-components/show-details=never; aggregate gồm DB, riêng liveness/readiness không chứng minh DB |
| D06-08 | NFR-025 gồm cả transfer metrics; generic handler có thể đếm lặp | Common chỉ timer HTTP built-in/histogram; transfer/identity/audit/fraud/notification/job owners emit đúng state/transaction boundary. Không dựng counter nghiệp vụ trong error handler, không tuyên bố toàn NFR-025 đạt |
| D06-09 | Profile test không luôn thể hiện ở spring.profiles.active property | logging.environment có APP_ENVIRONMENT override, explicit active profiles fallback, cuối cùng local; application-test đặt test. Compose forward APP_ENVIRONMENT; không suy timezone/môi trường từ host |
| D06-10 | Correlation ID do client gửi có thể cố tình chứa nội dung nhạy cảm trong regex hợp lệ | Giữ opaque ID đúng 07 §2.2/2.7; không tự đổi ID hoặc regex. Client phải giữ secrets/PII ngoài trace ID như hướng dẫn existing 03; invalid/CR-LF/space bị từ chối. Không tuyên bố detector nhận diện mọi PII/secret có thể được giả làm ID hợp lệ |
| D06-11 | Task 05 Blocked, task 06 có cần tạo identity thay thế | Không tạo identity trong production. Test-only resolver chứng minh production common chain/role enforcement; thiếu identity thật giữ nguyên ở 05/07/08, không phải bằng chứng end-to-end authentication |

## 3. Ma trận tiêu chí chấp nhận

Các tiêu chí giữ nguyên ID/nội dung; Verified chỉ cho common/local, dựa trên nhật ký bên dưới.

| AC ID | Tiêu chí | Test/cách kiểm | Kết quả thực tế | Evidence | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Trace nguồn + D06-01..11 | Nguồn 01–11 ưu tiên; không tự đổi contract; gap được ghi | E01/E05 | Verified |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Diff + ArchUnit | Không business import/DB write/transaction; 4 architecture tests đạt | E04/E05 | Verified |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | clean check + XML tổng | 190 tests, 0 failures/errors/skipped; PostgreSQL 16 thật | E04 | Verified |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report/link/hash + docs inspection | Đủ 9 AC, quyết định, source hashes, giới hạn và handoff; không evidence/ mới | E05 | Verified |
| COM-IMP-06-AC01 | JSON log có timestamp và context/route/status/latency theo nguồn, correlation/request ID khớp request; log kết thúc được ghi trước cleanup | realLogsCoverSuccessValidationSecurityAndServerErrorWithoutLeaks; asyncAndConcurrentRequestsKeepOneCompletionAndDistinctRequestIds | JSON thực 200/400/401/500 đủ trace/status/latency; response payload requestId trùng log success; async mỗi request một completion | E04 §4.2 | Verified |
| COM-IMP-06-AC02 | Log không lộ credential/key/full PII, raw auth/transfer body, sensitive query/header; control characters không tạo log record giả | Leak/injection trong integration + invalidCorrelationAndSensitiveUnknownPathsNeverEnterLogs | Không synthetic credential/key/OTP/full account/phone/email từ body/query/header/cookie/exception; CR-LF không tạo record giả; unknown route/invalid correlation không echo | E04 §4.2 | Verified |
| COM-IMP-06-AC03 | HTTP metrics phản ánh request count, lỗi và latency; labels theo route/method/status, không chứa account/user/correlation/request/idempotency ID | realHttpMetricsCountLatencyErrorsAndUseBoundedLabels | 3 concrete IDs→cùng template; timer count tăng đúng 3, latency>0, 400/401/500 counted; Prometheus count/bucket thật; không tag ID hoặc business counter | E04 §4.3 | Verified |
| COM-IMP-06-AC04 | Health phản ánh app/DB; phân biệt infrastructure probe và `/api/v1/health` Admin/Auditor; metrics/chi tiết nhạy cảm được bảo vệ | healthAndOperationalEndpointsEnforceRolesAndHideDetails; stoppedPostgresReturnsSafe503HealthResponseAndPublicDownProbe; HealthControllerTest | DB UP 200, DB stop 503 thật; anonymous 401/customer 403/Admin+Auditor 200; safe public probe; DOWN API HealthResponse JSON, DEGRADED 200 và missing DB/liveness fail closed | E04 §4.4 | Verified |
| COM-IMP-06-AC05 | Có hướng dẫn đọc trace/log/metric/health và trách nhiệm owner phát tín hiệu nghiệp vụ; không ghi technical log thay audit trail | Docs + actual response/log trace | Hướng dẫn correlate/metric query/health/owner; một success truy từ response đến log; scope local và missing owner signals rõ | E04/E05 | Verified |

## 4. Nhật ký kiểm chứng và kết quả giữ trong report

### 4.1. Lệnh thực chạy, các lần fail và retest

Tất cả lệnh Gradle chạy tại `/home/phuong/workspace/cloude/digitalbanking-backend`. Các test dùng synthetic data, không lưu secret thật. Cần quyền chạy Gradle cache và Docker socket của máy; lần xem version không escalated bị read-only lock file, sau đó chạy lại cùng wrapper với quyền môi trường cần thiết, không phải test failure.

| Evidence | Lệnh thực chạy | Expected → actual | Exit / kết luận |
|---|---|---|---|
| E01 | `./gradlew compileJava --offline --console=plain` | Biên dịch production source mới → BUILD SUCCESSFUL | 0 |
| E02a | `./gradlew test --tests '*ObservabilityIntegrationTest' --offline --console=plain` | 5 tests → 4 fail, 1 pass. environment local thay vì test; assertion probe thiếu groups; metric 500 thiếu vì test trước dừng sớm | 1; không tính pass |
| E02b | Cùng lệnh, sau sửa environment và assertion status/probe safety; thêm invalid-header test | 6 tests → 4 pass, 2 fail: `/actuator/prometheus` 404 vì exporter mặc định bị Spring Boot test disable | 1; không sửa production contract để pass |
| E03 | Cùng lệnh, thêm `@AutoConfigureObservability` và làm metric scenario tự phát 500/401 | 6 tests → 6 pass; BUILD SUCCESSFUL 25s, exporter thật hoạt động | 0 |
| E04 | `./gradlew clean check --offline --console=plain` | Thêm raw transfer/cookie checks + 3 HealthController contracts; toàn suite 190 tests, 0 fail/error/skip; JaCoCo report và ArchUnit hoàn thành; BUILD SUCCESSFUL 42s | 0 |
| E05 | `git diff --check`; `./gradlew --version`; `docker version --format '{{.Server.Version}}'`; `docker compose --env-file .env.example config --quiet` | Diff không whitespace error; Gradle 8.14.3, Docker 29.8.0; Compose valid, không dump resolved env | 0 |

E04 artifacts tiêu chuẩn: `digitalbanking-backend/build/test-results/test/TEST-*.xml`, `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`. Chúng có thể được Gradle tái tạo/ghi đè; snapshot kết quả, trích log, hash XML/runtime/test được giữ ngay trong report này. E02/E03 là lịch sử khắc phục, kết luận dựa trên E04 cuối cùng. Không có test bị skip hoặc còn lỗi trong phạm vi task.

### 4.2. Log thực và trace/leak/injection

Những dòng sau trích nguyên output của E04 ObservabilityIntegrationTest. Đây là test-only HTTP routes/session resolver chạy qua production servlet/filter/security/encoder; không dùng lời mô tả config làm bằng chứng runtime.

```jsonl
{"timestamp":"2026-11-12T11:41:47.976Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:success","request_id":"0874aceb-3d94-4029-8edd-393a7bfd5295","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/auth/login","method":"POST","status":200,"latency_ms":28,"action":"HTTP_REQUEST","outcome":"SUCCESS"}
{"timestamp":"2026-11-12T11:41:48.135Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:validation","request_id":"b374fbdf-a393-4eeb-9680-a9cd5564dbda","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/auth/login","method":"POST","status":400,"latency_ms":17,"error_code":"VALIDATION_ERROR","action":"HTTP_REQUEST","outcome":"CLIENT_ERROR"}
{"timestamp":"2026-11-12T11:41:48.251Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:security","request_id":"69efcfe3-f431-4d0f-a7e1-ed6c88cf43fb","event":"HTTP_REQUEST_COMPLETED","route":"UNMATCHED","method":"GET","status":401,"latency_ms":6,"error_code":"AUTH_SESSION_EXPIRED","action":"HTTP_REQUEST","outcome":"CLIENT_ERROR"}
{"timestamp":"2026-11-12T11:41:48.366Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:server","request_id":"f0e214ca-aec1-4f8a-bde5-7453bf4f2022","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/auth/passcode","method":"POST","status":500,"latency_ms":6,"error_code":"INTERNAL_ERROR","action":"HTTP_REQUEST","outcome":"SERVER_ERROR"}
{"timestamp":"2026-11-12T11:41:48.871Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:metric-0","request_id":"7d40a489-acb1-4db0-8e3a-73d76f5f1ce8","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/fixture/{id}","method":"GET","status":200,"latency_ms":3,"user_id":"sha256:6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b","action":"HTTP_REQUEST","outcome":"SUCCESS"}
{"timestamp":"2026-11-12T11:41:49.332Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:health-up","request_id":"d74bf033-6ac3-4061-a0b8-63c794787e9f","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/health","method":"GET","status":200,"latency_ms":3,"user_id":"sha256:6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b","action":"HTTP_REQUEST","outcome":"SUCCESS"}
{"timestamp":"2026-11-12T11:41:49.556Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:probe-down","request_id":"c8e65d59-b6b8-4548-84ab-23cfdbff0759","event":"HTTP_REQUEST_COMPLETED","route":"/actuator/health","method":"GET","status":503,"latency_ms":8,"action":"HTTP_REQUEST","outcome":"SERVER_ERROR"}
{"timestamp":"2026-11-12T11:41:55.570Z","level":"INFO","service":"digital-banking-backend","environment":"test","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"obs:health-down","request_id":"75150c1b-64fe-4e15-83d2-1bcd1f8995bb","event":"HTTP_REQUEST_COMPLETED","route":"/api/v1/health","method":"GET","status":503,"latency_ms":6010,"user_id":"sha256:d4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35","action":"HTTP_REQUEST","outcome":"SERVER_ERROR"}
```

Success response body `requestId` được assert trùng request_id dòng obs:success; response X-Correlation-ID trùng correlation_id trên cả success/error. Vì completion có MDC/context sau downstream và được gọi trong finally bên trong scope của outer filter, việc ghi xảy ra trước cleanup; RequestContext/context servlet regression cũng pass. Hai async requests song song có một completion/request, requestId khác nhau. Invalid correlation chứa space bị 400 với safe UUID mới; raw path nhạy cảm chỉ xuất UNMATCHED.

Leak tests gửi synthetic sentinel qua auth/transfer body, sensitive query, User-Agent, Idempotency-Key, X-CSRF-Token, Cookie; direct logger gửi credential arguments/structured password và exception có CR-LF/FAKE_RECORD, phone/email/account/OTP. Assertions quét actual captured stdout: không có sentinel/full PII/OTP hoặc FAKE_RECORD; parse từng JSON line thành công. Các literal chỉ nằm trong test code synthetic, không có credential thật. Không coi pattern/allowlist này là detector toàn bộ dữ liệu nhạy cảm ngụy trang trong opaque ID hợp lệ (D06-10).

Mapping: COM-OBS-01, COM-PII-02/03, COM-SEC-01; FR-038/039, AC-022/023 trong phạm vi request/log; không chứng minh propagation vào transfer/ledger/audit/notification thật.

### 4.3. Metric thực

`realHttpMetricsCountLatencyErrorsAndUseBoundedLabels` phát 3 GET với 3 path IDs khác nhau vào `/api/v1/fixture/{id}` rồi đợi timer: count_after = count_before + 3; totalTime(NANOSECONDS)>0. Các request validation 400, anonymous 401 (uri=UNKNOWN) và server 500 đều có count>0. Live GET `/actuator/prometheus` với test admin trả 200, có `http_server_requests_seconds_count` và `http_server_requests_seconds_bucket`; histogram xuất thực, không chỉ cấu hình YAML. Không ghi con số p95/p99 như load result vì chưa thực hiện workload D5.

Kiểm mọi tag của http.server.requests: key chỉ thuộc method/uri/status/outcome/exception/error; không chứa sentinel path IDs, obs: correlation, user hash. Payload export không chứa account/user/correlation/request/idempotency labels. Production không đăng ký timer HTTP thứ hai hoặc business counters trong handler; không đếm lặp metric nghiệp vụ. HTTP status/outcome đủ tính error rate; p95/p99 hướng dẫn dùng histogram_quantile. Hợp đồng metric nghiệp vụ vẫn do từng owner phát, không coi bảng hướng dẫn là implemented.

### 4.4. Health thật, role matrix và test doubles

| Điều kiện thực chạy | Endpoint | Kết quả |
|---|---|---|
| PostgreSQL 16 thật UP, anonymous | /actuator/health | 200, status UP; không components/details/JDBC URL |
| Anonymous | /api/v1/health; /actuator/metrics; /actuator/prometheus | Cả ba 401 |
| Test Session ROLE_CUSTOMER | Cùng ba endpoint | Cả ba 403 |
| Test Session ROLE_ADMIN và ROLE_AUDITOR | Cùng ba endpoint | Mỗi role đều 200 trên cả ba; production SecurityFilterChain dùng thực |
| Admin health khi DB UP | /api/v1/health | status UP, database UP, checkedAt UTC, version; không password/URL/stack/error detail |
| Dừng container PostgreSQL thật bằng Docker stop | /actuator/health | 503 DOWN, không components/details/JDBC URL |
| DB container đã dừng, Auditor | /api/v1/health | 503, Content-Type application/json, HealthResponse status DOWN/database DOWN, không code của Problem hoặc secret |
| Unit fixture: DB contributor thiếu, aggregate UP | HealthController | DOWN/503; không false healthy |
| Unit fixture: aggregate DEGRADED, DB UP | HealthController | 200 DEGRADED; Clock.fixed UTC; bỏ password detail của contributor |
| Unit fixture: liveness BROKEN, DB UP | HealthController | DOWN/503 |

SessionAuthenticator fixture chỉ xác thực token synthetic trong test để kiểm role wiring; không chứng minh session DB, login, revocation hoặc quyền được identity cấp thật. HealthControllerTest dùng mock HealthEndpoint/ApplicationAvailability chỉ chứng minh contract mapping; DB UP/DOWN đã được integration PostgreSQL thật chứng minh riêng. Health không tạo audit action và không trả outbox pending count giả. Không chạy Cloud/Nginx probe, workload/load, alert delivery hoặc dashboard verification trong task này.

### 4.5. Snapshot tái kiểm tra

Source hashes dưới đây bao gồm đúng production/test bytes của E04 (Compose/env kiểm bằng E05). Git dirty baseline không đủ định danh một mình, nên dùng hash này khi tái lập.

| File (relative to digitalbanking-backend/) | SHA-256 |
|---|---|
| `src/main/java/com/simulatedbank/banking/common/config/SafeJsonLogEncoder.java` | `64407f50bd2bca2881bd40db469d2e18f1bd1929dedf38d24d89ff3922061e10` |
| `src/main/java/com/simulatedbank/banking/common/config/HealthController.java` | `2e47bc8504ec877cbdc0eb42fa2f41b887cac42708ca3f7ca24eabe0831021c5` |
| `src/main/java/com/simulatedbank/banking/common/config/SecurityFilterChainConfig.java` | `13586390658cdb3b84ba00507dea7de4845ef656d6f99dc7ffab6dd9a81d042e` |
| `src/main/java/com/simulatedbank/banking/common/config/SessionAuthenticationFilter.java` | `3df7c47d6c49162db610e26a1d54ab51e399a99cbafd9b115bf2bd3dce1043d5` |
| `src/main/java/com/simulatedbank/banking/common/context/HttpRequestLog.java` | `69b5d5e828ffb5b4edd35f97658faf598ce7d29691cf6cdd1191467ce678e4d1` |
| `src/main/java/com/simulatedbank/banking/common/context/CorrelationAndRequestIdFilter.java` | `6b8ed67eabb08ac6f3caba33af9235edde877c6cbc71b4b8e85ababc7e6120e5` |
| `src/main/java/com/simulatedbank/banking/common/error/ProblemWriter.java` | `fca7bff157c0195dbcd7311fecd7aab0bf7bbf164d232310b9fc68170f444bc1` |
| `src/main/resources/logback-spring.xml` | `3f7d1ad94e43cf5f2198049a2b50f98ffdd29ee9175f75e35bd12db8183d0cf6` |
| `src/main/resources/application.yml` | `8dc702c10aad49f08341e29815d34e631803793c9cfedfc4615fa94bce4b4f88` |
| `src/main/resources/application-test.yml` | `ecf4ccfb7b7347610065e1394c8110bde1499859e62b7e8e94836b8b89470b4d` |
| `src/test/java/com/simulatedbank/banking/common/config/ObservabilityIntegrationTest.java` | `4dd84c207c3d6440f31d1cb1592957d8cc7ab034e3abc968e3125a805a584f1e` |
| `src/test/java/com/simulatedbank/banking/common/config/HealthControllerTest.java` | `fb6e00de791adb02c6749f5778a4a92a56943b72b87dd43b99b601010aac10f5` |
| `compose.yaml` | `9a968d000a50446f4f659f7faf1a7eff94eb675dc71a9e206ba4d2ac672a4fb0` |
| `.env.example` | `2066b1b69685dec5ef351e5803b15b03086a5e18fb086d2a74e159685c75743b` |

XML E04:

| Suite artifact | SHA-256 |
|---|---|
| `build/test-results/test/TEST-com.simulatedbank.banking.common.config.ObservabilityIntegrationTest.xml` | `52d75e971da1bd90758ac6edfbee5051394fee4a9b69f86f98ec7b9f80e76bd1` |
| `build/test-results/test/TEST-com.simulatedbank.banking.common.config.HealthControllerTest.xml` | `06815a4a9e759dfde81984d5be2386908f72163f2519a09c498df4b74a5fc602` |

Tổng hợp XML E04 (21 suites):

| Suite | Tests | Fail | Skip |
|---|---:|---:|---:|
| BackendApplicationContextTest | 1 | 0 | 0 |
| ModuleBoundaryArchTest | 4 | 0 | 0 |
| AppPropertiesTest | 20 | 0 | 0 |
| HealthControllerTest | 3 | 0 | 0 |
| HttpSecurityComponentsTest | 18 | 0 | 0 |
| HttpSecurityServletTest | 2 | 0 | 0 |
| HttpSecurityTest | 10 | 0 | 0 |
| ObservabilityIntegrationTest | 6 | 0 | 0 |
| ContextHttpTest | 3 | 0 | 0 |
| ContextServletTest | 1 | 0 | 0 |
| RequestContextTest | 19 | 0 | 0 |
| CryptoConfigTest | 15 | 0 | 0 |
| CryptoOperationsTest | 1 | 0 | 0 |
| CryptoPrimitivesTest | 6 | 0 | 0 |
| JcsCanonicalizerTest | 44 | 0 | 0 |
| PiiMaskingUtilsTest | 15 | 0 | 0 |
| ErrorContractTest | 4 | 0 | 0 |
| ErrorHttpTest | 14 | 0 | 0 |
| HealthEndpointTest | 2 | 0 | 0 |
| FlywayMigrationTest | 1 | 0 | 0 |
| PostgreSqlFlywayMigrationIntegrationTest | 1 | 0 | 0 |
| **Tổng** | **190** | **0** | **0** |

## 5. Gap và bàn giao còn lại

| ID | Phạm vi/AC | Giới hạn và tác động | Trạng thái / đầu mối |
|---|---|---|---|
| H06-01 | Identity end-to-end ngoài task06 common | Resolver thật chưa có; metric scrape/API health chưa có cách cấp credential thật qua identity. Không tự coi task 05 Done | Blocked từ task05; identity/Đạt + common/Phương, kiểm lại ở 07 |
| H06-02 | NFR-025 toàn hệ thống | Transfer/auth/idempotency/audit/fraud/notification/jobs metrics, C3 security fallback và business trace persistence chưa được task06 chứng minh | Chưa verified; owner từng module tích hợp ở 07; audit/Thịnh, transfer/Vũ, common/Phương |
| H06-03 | Môi trường/Cloud | Ingress protection, scrape credentials/network, alert pipeline, retention/rotation, Docker logs trên Cloud chưa chạy; không phải kết quả NFR-025 release | Task08 + DevOps/owner hạ tầng; giữ các policy retention Open theo 08 §9 |
| H06-04 | Technical diagnostic detail | Global suppression cố ý bỏ free-text/stack. Owner muốn thêm coded business/security signal phải review allowlist/integration; không bật raw fallback hoặc coi common log thay audit | Giới hạn thiết kế D06-03; owner audit/security/common khi tích hợp 07 |
| H06-05 | Client trace IDs | Không thể suy secret/PII trong ID hợp lệ; giữ contract opaque IDs, yêu cầu client không gửi PII. Invalid/control-character IDs được chặn | Contract boundary D06-10; owner client/FE/identity |
| H06-06 | D5/performance | Histogram export hoạt động không chứng minh p95/p99 target, throughput, availability demo hoặc sức chịu tải | Chưa verified ngoài scope; QA/07/08 chạy workload chuẩn |

Các mục này là handoff/giới hạn của phạm vi common/local đã công bố, không được dùng để tuyên bố nghiệm thu các module hay toàn NFR. Không có lỗi/blocked AC bắt buộc nào còn lại **trong scope task06 đã kiểm**.

## 6. Kết luận và bàn giao

- Tổng 9 AC (COM-G01..04 và COM-IMP-06-AC01..05): **9 Verified**, 0 chưa đạt trong scope common/local.
- E04 clean check: **190 tests pass, 0 failure/error/skip**; 6 integration observability + 3 unit health mới, PostgreSQL 16 và HTTP thật. E05 diff/Compose checks đạt.
- Đề nghị **Done — chờ review**, đã đồng bộ task06/tracker và docs runtime. Không thay đổi trạng thái Blocked của task05 hoặc Planned của 07/08.
- Bàn giao Phương/common và owners ở 07: tích hợp identity thật, richer safe coded owner events/C3 fallback, business metrics/audit/propagation. Task08/DevOps xác nhận internal ingress/scrape/Cloud và policy retention/rotation.
- Review/sign-off: **Chưa review**. Đây là kết luận implementer và snapshot kiểm chứng local, không phải owner/security/QA sign-off hoặc nghiệm thu toàn hệ thống.
