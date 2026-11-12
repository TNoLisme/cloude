# BE-COM-IMP-08 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản:** [BE-COM-IMP-08](../tasks/BE-COM-IMP-08.md), v1.0 ngày 12/11/2026; giữ nguyên mọi AC
> **Ngày / người thực hiện:** 2026-11-12T19:25:26+07:00 / Codex theo yêu cầu Phương
> **Trạng thái task:** **Blocked — W6 local readiness đã hoàn thiện; chưa có release candidate/Cloud/owner hạ tầng**
> **Kết luận verification:** **6/10 AC Verified, 4/10 Blocked**; chưa có Cloud verification hoặc nghiệm thu W7/W8
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Thực hiện task cuối của common: chuẩn bị cấu hình demo và artifact, kiểm vận hành ứng dụng trong môi trường cô lập, hoàn thiện readiness/runbook/secret references/dependency và kế hoạch tái kiểm W7/W8. Không nhận provisioning, backup/restore, CI/CD hoặc các nghiệp vụ thiếu của owner khác vào common. Không triển khai ra môi trường đang phục vụ hoặc tạo tài nguyên Cloud khi release gate chưa đạt.

Nguồn ưu tiên theo owner: **docs-common/01–11 → design → docs-backend**. Đã đọc ba README trong phiên và đọc task 08/tasks README, deployment README/configuration, nguồn dưới đây; không lấy backlog/report làm đặc tả mới. Theo yêu cầu owner, chỉ viết report, **không tạo evidence/08**. Output Gradle/JAR là build artifacts tiêu chuẩn; không chứa secret thật.

Baseline Git: `87427ce3b1d5e6eb04ffea65d21c2902a0ab0484` trên branch Phuong/common, cộng task 07 chưa commit và task 08 hiện tại. Các hash §4.5 định danh đúng artifact/source/test đã chạy. Ba deletion `.gitkeep` staged từ trước được giữ nguyên. Không stage/commit/reset các thay đổi trong task 08. Task07 thiếu consumer vẫn Blocked; không tự tạo release candidate đã qua nghiệm thu.

Môi trường kiểm: Linux 7.0.0-43-generic amd64; OpenJDK 21.0.13.0.3; Gradle 8.14.3; Spring Boot 3.5.7; Docker 29.8.0; Testcontainers postgres:16-alpine. Parent suite chạy profile test; **child JVM chạy java -jar với profile demo thật**, không chạy ứng dụng từ test classpath. Child environment được xóa trước khi nạp các biến synthetic có chủ đích; không kế thừa secret/JAVA_TOOL_OPTIONS/SPRING_APPLICATION_JSON của máy.

### 1.1. Nguồn và dependency

| Nguồn | Mục áp dụng |
|---|---|
| `08. Deployment infrastructure.md` | §4–§7: môi trường, config/secret, artifact/release/rollback; §9 safe health/log/metrics; §11 deployment verification |
| `09. Test plan.md` | §8–§10 entry/exit criteria, defect và reporting; thiếu Cloud/owner/consumer không được gọi Pass |
| `10 Master timelines.md` | §4.6 W6 quality gate/RC, §4.7 W7 deploy, §4.8 W8 retest/handover; timeline không phải bằng chứng đã chạy |
| `11. Resource & Staffing Plan.md` | §7–§10; technical owner hạ tầng **chưa phân công**, Vũ chỉ theo dõi quyết định, không mặc nhiên provisioning |
| `07. API contracts.md` | §2–§4 HTTP/secret/masking/trace/security; §8.9.1 API Admin/Auditor vs probe; DOWN503 HealthResponse |
| `03. SRS`, `06. System architecture` | NFR-013/018/019/020/025; AC-022/023/030/032; secure session transport, common ownership và application observability |
| Backend deployment README/configuration, common README | Runtime mapping, cập nhật demo override/runbook theo nguồn, không đổi contract nguồn |
| Reports01–07 | Primitive/HTTP/local observability đã kiểm; task 05 identity và task 07 consumer/release gate còn Blocked. [Report07](BE-COM-IMP-07-verification.md) là dependency bắt buộc |

### 1.2. Artifact, môi trường và release decision

| Trường readiness | Giá trị thực tế / trạng thái |
|---|---|
| Artifact đã kiểm | digitalbanking-backend/build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar; **77684108 bytes** |
| Artifact SHA-256 | `e9f1c722fdec1d300ab09ea5ec1cafbf4e43d395272bae0a90124d8f006f600a` |
| Source revision | `87427ce3b1d5e6eb04ffea65d21c2902a0ab0484` + task 07/08 dirty snapshot §4.5; không giả commit đã chứa tất cả thay đổi |
| Profile/config | application.yml + application-demo.yml; test child active profile demo; APP_VERSION synthetic label readiness-local-candidate |
| DB role | banking_app runtime (đã kiểm không superuser) / banking_migrator chạy Flyway; bootstrap bằng script init hiện có trên disposable PG16 |
| Migration đã kiểm | V1 tạo schema banking; Flyway V1 thành công đúng một record sau hai lần start. Chưa có business tables/migrations |
| Image/registry digest | Chưa build/push image release; postgres:16-alpine là baseline test, không phải digest đã pin của Cloud |
| Secret-store refs/version/IAM | Chưa được owner hạ tầng cấp; tên biến và loại reference ghi tại §2.1/runbook, **không có secret values** |
| Cloud URL / deployment ID | Chưa được cung cấp cho task; không có request/deployment thực trên Cloud |
| Owner/reviewer hạ tầng | Chưa phân công theo tài liệu11 §8; Phương phụ trách common ứng dụng, không tự nhận hạ tầng |
| RC qua quality gate07 | Chưa có; 10 module nghiệp vụ thiếu implementation/schema theo report 07 |
| Quyết định thực tế | **NO-GO cho Cloud/release**, tiếp tục chuẩn bị W6 và đợi các dependency. Không có ngoại lệ release được phê duyệt |

Checksum định danh chính artifact đã chạy; không tuyên bố hai build sạch cho kết quả byte-identical hoặc Docker build reproducibility đã được chứng minh. Test restart kiểm cùng artifact giữ nguyên SHA, không phải rollback tới một bản phát hành trước đã nghiệm thu.

## 2. Thay đổi thực tế và vận hành

| File/thành phần | Hành vi và mục đích | AC |
|---|---|---|
| `src/main/resources/application-demo.yml` | Profile demo đặt cookie Secure=true, CORS origin không fallback localhost (thiếu/wildcard bị validator hiện có từ chối), log environment mặc định demo | AC01/02/03 (phần chuẩn bị), AC06 |
| `build.gradle.kts` | Test phụ thuộc bootJar, truyền đường dẫn artifact vào test bằng property readiness.jar; kiểm JAR ứng dụng đúng bytes đã đóng gói | G03/AC01/02 |
| `PackagedApplicationReadinessIntegrationTest.java` | 4 methods: start/restart JAR thật, secrets/DB/CORS negative cases; PG16/roles riêng; safe logs; child process stop và xóa temporary logs | G03/AC02/03/04 (chỉ local) |
| `AppPropertiesTest.java` | Cập nhật oracle demo theo contract Secure/CORS; giữ so sánh mọi field khác với defaults, local/test vẫn so toàn Security | G01/G03/AC01 |
| `deployment/common-readiness.md` | Runbook W6/W7/W8; secret refs, artifact, NO-GO, start/restart/rollback, ownership/limits và điều kiện tiếp tục | AC01/AC06 |
| deployment README/configuration, backend README, common README v1.10 | Đồng bộ demo behavior/usage; sửa local health port18080 và inventory Compose thực chỉ backend/postgres; link runbook/report | G04/AC06 |
| task 08/tasks README/report | Ghi Blocked, đủ10 AC, tách W6 actual với W7/W8 thiếu evidence; giữ nguyên05/07 Blocked | G04 |

Source production Java/security policy nghiệp vụ/crypto primitives không sửa. Demo override làm rõ environment transport config, không mint session/cookie hoặc tạo identity giả. DB init script không sửa, không nhận broad default privileges của schema trống là bằng chứng least privilege/audit immutability cho business tables chưa có.

### 2.1. Secret references và config bàn giao

| Reference name | Cách cấp dự kiến / kiểm đã có | Reference identifier thực tế |
|---|---|---|
| DB_APP_PASSWORD | External secret cho banking_app; missing/wrong bị fail trong isolated packaged demo | Chưa được cấp bởi owner Cloud |
| DB_MIGRATION_PASSWORD | External secret khác cho banking_migrator; missing/wrong fail startup | Chưa được cấp |
| APP_PII_AES_KEY_BASE64 | External 32-byte Base64 key, tách HMAC; missing/invalid/published development key fail | Chưa được cấp |
| APP_HMAC_KEY_BASE64 | External 32-byte Base64 key, không trùng AES; missing fail, primitives validation regression đạt | Chưa được cấp |
| APP_PII_AES_KEY_ID | Metadata một khóa hiện tại; previousKeys giữ rỗng vì rotation chưa có protocol | Chưa chốt ID Cloud; không tự bật rotation |
| APP_CORS_ALLOWED_ORIGINS | Explicit approved SPA origins; demo thiếu/wildcard fail startup | Chưa có URL SPA Cloud được cấp |
| SPRING_PROFILES_ACTIVE / APP_ENVIRONMENT / APP_VERSION | Explicit demo và nhãn deployment/artifact thực; không dùng local/test config hoặc local Compose secret samples trên Cloud | Profile đã kiểm demo, Cloud labels chưa có |

Không ghi actual password/AES/HMAC/cookie/token vào command arguments, source, report hoặc image. Giá trị synthetic tạo ngẫu nhiên cho fixture chỉ tồn tại trong process environment/disposable DB. Cookie Secure là cấu hình đã kiểm qua AppProperties; **actual Set-Cookie/CSRF lifecycle chưa thể chứng minh khi identity chưa có**. Forwarded headers vẫn none; backend HTTP không tự tin X-Forwarded-Proto, TLS/redirect/proxy cần kiểm riêng trên Cloud.

## 3. Ma trận tiêu chí chấp nhận

Không hạ AC Cloud thành local. AC01/AC06 xác minh hồ sơ readiness và hướng dẫn đúng trạng thái; AC02–05 cần môi trường/deployment thật nên vẫn Blocked dù có kiểm chuẩn bị cục bộ.

| AC ID | Tiêu chí gốc | Test/inspection | Kết quả thực tế | Evidence | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Nguồn §1.1 + decisions §5.2 | Giữ ưu tiên nguồn/policy Open; oracle Secure/CORS theo demo contract; không sửa spec để pass | E01/E03/E04 | **Verified** |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Diff/5 ArchUnit + scope review | Demo config, build/test/docs; không business/provisioning/DB-backup ownership mới | E03/E04 | **Verified** |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | clean check + packaged JVM/PG16 | 203 tests,0 failure/error/skip; không mock/H2 chứng minh DB; local và Cloud phân biệt rõ | E03 | **Verified** |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report/docs/task/link/hash checks | Đủ10 AC, W6/W7/W8 tách; dependency/owner/NO-GO ghi rõ; không secret thật/evidence08 | E04 | **Verified** |
| COM-IMP-08-AC01 | Readiness ghi đúng artifact/revision, config/profile, secret references, dependency và owner hạ tầng; các policy Đề xuất/rotation còn mở có trạng thái và quyết định phạm vi release rõ ràng | Readiness §1.2/§2.1, runbook + report 07 | Artifact/revision/profile/hash/secret references và owner chưa cấp được ghi đúng; quyết định NO-GO rõ, giữ OQ/OI và gate07 | E01/E03/E04 | **Verified** |
| COM-IMP-08-AC02 | Bản triển khai Cloud khởi động, app/DB health đúng; cấu hình thiếu/sai bắt buộc thất bại an toàn; có hướng dẫn restart/rollback phần ứng dụng | Packaged start/restart/negative config local | JAR demo + PG16/roles/migration đạt; hướng dẫn rollback có. Chưa có deployment/startup/health Cloud; restart cùng JAR không chứng minh rollback release | E03/B08-CLOUD/B08-RC | **Blocked** |
| COM-IMP-08-AC03 | HTTPS/proxy, cookie/CSRF/CORS, headers và trace thực tế đúng contract, gồm lỗi tại proxy/filter; không suy TLS hay Cloud behavior từ unit test | Local origin/body/trace/header/forwarded checks + demo config | Local rejection/headers đúng; thiếu URL HTTPS, proxy errors, identity Set-Cookie/CSRF thật và quyền vận hành trên Cloud | E03/B08-CLOUD/B08-IDENTITY | **Blocked** |
| COM-IMP-08-AC04 | Log/metrics/health quan sát được trên Cloud, truy request đến dữ liệu/luồng liên quan; không lộ secret/PII, endpoint vận hành được bảo vệ | Local JSON logs + observability regression | Có trace/log/protected endpoints local; không Cloud logs/metrics hoặc request→businessdata/flow thật | E03/B08-CLOUD/B08-RC | **Blocked** |
| COM-IMP-08-AC05 | Regression phần common trong các hành trình W8 đạt; lỗi sau deploy có fix/redeploy/retest; không còn lỗi vi phạm AC chưa xử lý | Report07 dependency matrix + current suite | Common local regression đạt; W8 identity/account/transfer/operations Cloud chưa chạy, không có fix/redeploy/retest evidence Cloud | E01/E03/B08-RC/B08-CLOUD | **Blocked** |
| COM-IMP-08-AC06 | Tài liệu cấu hình, usage, vận hành, giới hạn và hồ sơ bàn giao khớp bản đã kiểm chứng; người khác có đủ bước để tái kiểm tra | Runbook/config/usage/artifact/owner handoff inspection | Tài liệu khớp local artifact/demo config đã test; đủ steps tái lập, rollback gates và handoff pending; không nhận nghiệm thu hệ thống | E03/E04 | **Verified** |

## 4. Verification W6 / W7 / W8

### 4.1. W6 — nhật ký thực chạy cục bộ

Working directory Gradle/Compose: `/home/phuong/workspace/cloude/digitalbanking-backend`. Revision/config ở §1/§4.5. Các lệnh chạy với quyền môi trường để dùng Gradle cache/Docker; không truy cập secret Cloud hoặc môi trường đang phục vụ.

| ID | Lệnh thực chạy / procedure | Expected → actual | Exit |
|---|---|---|---|
| E01 | Đọc task/source deployment/07report; `git status --short`, `rg --files` tìm Docker/Compose/deployment config | Xác định gate → task 07 Blocked, owner theo11 chưa phân công; chưa có URL/deployment access cấp cho task. Không provisioning hoặc deploy local candidate lên Cloud | 0 inspection |
| E02a | `./gradlew test --tests '*PackagedApplicationReadinessIntegrationTest' --offline --console=plain` | 4 packaged methods pass, BUILD SUCCESSFUL 57s; start/restart và negative keys/DB/CORS đạt. Sau đó thêm missing DB cases, exact endpoint status và cleanup | 0 |
| E02b | `./gradlew clean check --offline --console=plain` | 203 tests,202 pass/1fail. Old AppPropertiesTest đòi demo Security giống local (secure=false/local CORS), không còn đúng source demo contract; packaged tests đều pass | 1; không tính green |
| E03 | Cùng `./gradlew clean check --offline --console=plain` sau sửa oracle profile demo rõ ràng và giữ mọi field còn lại | **203 tests,0 fail/error/skip,23 suites**; packaged tests4, 5 ArchUnit, PostgreSQL/regression; JaCoCo hoàn thành, BUILD SUCCESSFUL 1m33s | 0 |
| E04 | `git diff --check`; `docker compose --env-file .env.example config --quiet`; `java -version`; `./gradlew --version`; Python ZipFile/SHA/source/report/link inspection | Local Compose valid không dump env; Java21/Gradle 8.14.3; artifact có demo config, không có test fixtures/.env; source/XML/hash/report/status đúng | 0 |

E02b là thay đổi test oracle có căn cứ **08 §4/§6,07 §3, deployment config**: demo cần Secure cookie và explicit origins. Test không bị xóa: đổi tên thành `yamlDefaultsAndDemoTransportOverridesFollowEnvironmentContract`, so recursive tất cả Security field trừ hai override, rồi assert Secure=true/origins empty khi chưa được cấp. Local/test vẫn assert toàn Security bằng defaults. Không sửa expected để che bug production.

E03 suite packaged bắt đầu 2026-11-12T12:21:17.585Z (UTC), 4 methods đều pass; trong đó có **2 lần startup cùng JAR** và **10 negative startup cases**. Không skipped tests trong suite đã chạy; các ca Cloud chưa có môi trường được ghi Blocked, không tính pass hoặc tạo @Disabled để che.

Artifacts tiêu chuẩn: `build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar`, `build/test-results/test/TEST-*.xml`, `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`. Build outputs có thể bị ghi đè lần sau; checksum, summary và runtime excerpts được giữ trong report. Child startup logs tạm được xóa sau kiểm; chỉ safe excerpts được đưa vào XML/report.

### 4.2. W6 — kết quả actual packaged runtime

Những dòng sau trích nguyên stdout của test E03, đã assert không chứa secret synthetic trước khi chọn log mẫu:

```
READINESS_NEGATIVE scenario=missing-cors exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=wildcard-cors exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=crypto exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=crypto exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=crypto exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=crypto exit=1 secrets=ABSENT
READINESS_LOG {"timestamp":"2026-11-12T12:21:45.075Z","level":"INFO","service":"digital-banking-backend","environment":"demo","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"readiness:packaged","request_id":"b0d45bf9-4647-49e7-b204-a66c318370e4","event":"HTTP_REQUEST_COMPLETED","route":"/actuator/health","method":"GET","status":200,"latency_ms":70,"action":"HTTP_REQUEST","outcome":"SUCCESS"}
READINESS_PACKAGED run=1 profile=demo health=UP anonymous_operational=DENIED cors=403 body_limit=413 invalid_trace=400 forwarded_headers=IGNORED shutdown=OK jar_sha256=e9f1c722fdec1d300ab09ea5ec1cafbf4e43d395272bae0a90124d8f006f600a
READINESS_LOG {"timestamp":"2026-11-12T12:21:50.110Z","level":"INFO","service":"digital-banking-backend","environment":"demo","logger":"com.simulatedbank.banking.common.context.HttpRequestLog","correlation_id":"readiness:packaged","request_id":"0d609223-d761-4fd7-aa05-18e452663983","event":"HTTP_REQUEST_COMPLETED","route":"/actuator/health","method":"GET","status":200,"latency_ms":66,"action":"HTTP_REQUEST","outcome":"SUCCESS"}
READINESS_PACKAGED run=2 profile=demo health=UP anonymous_operational=DENIED cors=403 body_limit=413 invalid_trace=400 forwarded_headers=IGNORED shutdown=OK jar_sha256=e9f1c722fdec1d300ab09ea5ec1cafbf4e43d395272bae0a90124d8f006f600a
READINESS_NEGATIVE scenario=missing-runtime-db exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=missing-migration-db exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=runtime-db exit=1 secrets=ABSENT
READINESS_NEGATIVE scenario=migration-db exit=1 secrets=ABSENT
```

Positive checks chạy bằng JAR production, không inject test SessionAuthenticator/controller vào child JVM:

- Public `/actuator/health` UP200, không components/details/JDBC URL/secret; correlation header, no-store, nosniff đúng. Hai lần stop/start dùng cùng JAR SHA, exit graceful0/143 và Flyway V1 chỉ một record.
- Anonymous `/api/v1/health`, `/actuator/metrics`, `/actuator/prometheus` đều401; `/v3/api-docs`404 trên demo (Swagger không expose). Không có login giả để làm Admin/Auditor pass; role/authenticated checks ở regression vẫn dùng test-only harness, identity thật chưa tồn tại.
- Origin không được duyệt403; streamed body65537bytes413; correlation chứa space400; spoof X-Forwarded-Proto=https không làm backend HTTP phát HSTS. Các checks này **không chứng minh TLS/proxy Cloud**.
- Script init hiện có tạo banking_app và banking_migrator riêng; JDBC current_user chứng minh runtime role banking_app, rolsuper=false. PostgreSQL bootstrap test sử dụng synthetic admin credential riêng; ứng dụng không dùng admin để vận hành.

Negative packaged startup cases: thiếu AES, thiếu HMAC, AES Base64 không hợp lệ, published development AES key; DB runtime password thiếu/sai, migration password thiếu/sai; CORS thiếu/wildcard. Mỗi process thoát khác0 trong giới hạn45s, stdout không lộ password/AES/HMAC/JDBC URL/invalid value. Không thay secret của hệ thống đang phục vụ. Đây là JVM/PG local cùng cấu hình demo, chưa phải môi trường Cloud tương đương về TLS/network/secret store.

Regression E03 còn chạy task 07 SQLSTATE/rollback probe thật và task06 DB UP/DOWN→503 HealthResponse, metrics export/redaction/trace; không gọi DB DOWN của test06 là packaged-demo Cloud verification. 5 ArchUnit rules đạt; không có business transaction/persistence dependency được thêm vào common.

### 4.3. W7 — Cloud deployment chưa chạy

| Trường | Actual / trạng thái |
|---|---|
| URL / region / provider / environment ID | Chưa được cung cấp cho task |
| Owner hạ tầng/reviewer | Chưa phân công theo11 §8 |
| RC/deployment/image digest | Chưa có RC qua07; chưa deploy/push image |
| Startup/Flyway/app-DB health trên Cloud | **Blocked**, không có execution record |
| HTTPS/proxy/certificate/CORS/CSRF/cookie/proxy errors | **Blocked**, không thay bằng kết quả localhost |
| Secret refs/IAM/private network/scan/backup evidence | Chưa có; cần owner ghi reference identifiers, không secret values |

Runbook chuẩn bị đủ trình tự start/restart/rollback ứng dụng và negative checks trong môi trường cô lập. Không thực hiện rollback production hoặc khôi phục DB từ backup; chưa có accepted prior artifact/schema-compatibility review để chạy rollback drill. NO-GO giữ nguyên theo release gates của08/09/10 và task 07.

### 4.4. W8 — Cloud regression/handover chưa chạy

| Hành trình cần thực hiện | Owner phối hợp | Actual / trạng thái |
|---|---|---|
| Identity login/session/cookie/CSRF/expiry → customer/account ownership/PII | Đạt + hạ tầng + Phương/common | **Blocked**: identity/customer/account implementation thiếu, chưa Cloud |
| Transfer/step-up/retry/idempotency/ledger/limit/history | Vũ/Phương/Đạt | **Blocked**: report 07 W4; không có consumer/schema để chạy D1/D2/D3 |
| Audit/fraud/notification/admin/permissions/security fallback | Thịnh/Đạt + owners money path | **Blocked**: report 07 W5; chưa evidence D4/D1–D5 toàn hệ thống |
| Cloud log/metric/health request→data/flow, protected access, secret/PII review | Phương + từng owner + hạ tầng | **Blocked**: thiếu deployment, credentials test hợp lệ và consumer |
| Restart/rollback/fix/redeploy/retest/recovery | Owner hạ tầng/DB + application owners | **Blocked**: chưa deploy, chưa có defect/redeploy timeline Cloud |

W8 phải ghi URL/revision/digest/config version/time/reviewer cho từng lần; fixes cần artifact mới và retest đúng scenario. Không nhận toàn D1–D5 từ suite common hoặc bảng checklist chuẩn bị.

### 4.5. Snapshot và kết quả suite E03

| File/artifact | SHA-256 |
|---|---|
| `digitalbanking-backend/build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar` | `e9f1c722fdec1d300ab09ea5ec1cafbf4e43d395272bae0a90124d8f006f600a` |
| `digitalbanking-backend/build.gradle.kts` | `908fe167ceb42697d28a503625947e143d6f8f7e7631505b60a6a3f59241ff7f` |
| `digitalbanking-backend/src/main/resources/application-demo.yml` | `ccb34c194931583d0a085d295b228a3f6a0bb26d1529973e0304c6432989366c` |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/readiness/PackagedApplicationReadinessIntegrationTest.java` | `0e08422ae7e356b08a5c97b38bde2b96ec484797366af95d1d867ac8e96e226e` |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/common/config/AppPropertiesTest.java` | `5c0b5b2d514d25d3d427f1f0a26a0494ae62f776abb9de01dcf5a410f510871a` |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/common/error/PostgreSqlErrorPipelineIntegrationTest.java` | `3493b675bb809f6967708f00efada584f59b720208326d513b0faae70b7dae7f` |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/architecture/ModuleBoundaryArchTest.java` | `df3fb35b7ed7ceb490244a5b1189a21cec2a68589296f6cf86e98307637c92c7` |
| `digitalbanking-backend/build/test-results/test/TEST-com.simulatedbank.banking.readiness.PackagedApplicationReadinessIntegrationTest.xml` | `7ae3a7998804082766fd8f08e03401b787f80edcc5f6f265aed846102cb92a5d` |
| `digitalbanking-backend/build/test-results/test/TEST-com.simulatedbank.banking.common.config.AppPropertiesTest.xml` | `9a2bab5a746f4bee571e70d8bf807070b39eb132fa8b5e226560f601b20ec69f` |
| `digitalbanking-docs/docs-backend/deployment/common-readiness.md` | `e48d29bbae21230d1bc711ddf33ec8298a9f17993f47fe4894973ea07d7c2c99` |

| Suite | Tests | Fail | Error | Skip |
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
| PackagedApplicationReadinessIntegrationTest | 4 | 0 | 0 | 0 |
| **Tổng** | **203** | **0** | **0** | **0** |

## 5. Gap, quyết định và việc bàn giao

### 5.1. Blockers và giới hạn

| ID | AC ảnh hưởng | Gap / tác động | Owner / điều kiện tiếp tục | Trạng thái |
|---|---|---|---|---|
| B08-RC | AC02/04/05 | Task07 chưa có consumer W3/W4/W5, chưa RC đạt Must AC/D1–D5/security/contract gate | Đạt identity/customer/account/admin; Vũ transfer/limit và điều phối; Phương ledger/common; Thịnh audit/fraud/notification/admin. Chạy đủ integration và release review theo report 07 | **Blocked** |
| B08-CLOUD | AC02/03/04/05 | Chưa URL/provider/deployment/private DB/TLS/network/secret store hoặc actual Cloud evidence | Vũ theo dõi quyết định; **technical owner hạ tầng chưa phân công** theo11. Cần owner/reviewer, môi trường và quyền phù hợp sau RC | **Blocked** |
| B08-IDENTITY | AC03/04/05 | Không có actual session/Set-Cookie/CSRF/authenticated operational access và business trace | Đạt/identity cùng owner ingress/common; deploy consumer thật rồi kiểm positive/negative và không lưu token/cookie vào report | **Blocked từ05/07** |
| B08-ROLLBACK | AC02/05 | Restart cùng JAR đã kiểm, nhưng không accepted prior release/image digest/schema compatibility/backup restore | Application + DB/hạ tầng owner chuẩn bị artifact trước và compatibility/backup evidence; chỉ sau đó chạy rollback/retest được duyệt | **Blocked cho Cloud/release** |
| POLICY/ROTATION | AC01 scope release | Thresholds Đề xuất; OQ-15/OI-COM-05 rotation; audit hash_version wording; retention/rotation logs/metrics; proxy trust/distributed quotas/C3 fallback | Giữ trạng thái nguồn/reports04–07; owner quyết định trước release. Phạm vi hiện tại là readiness candidate, **không phê duyệt ngoại lệ release** | **Open, không tự đóng** |
| IMAGE/SCAN/CI | Release gate | Chưa image pin/digest, reproducible Docker build, full OpenAPI validator/generated client/security scan/CI release evidence | Owner hạ tầng/CI cùng module owners; JAR/green tests không thay các gate này | **Chưa verified** |

### 5.2. Quyết định đã thực hiện theo tài liệu

| ID | Điểm cần quyết định | Quyết định và căn cứ |
|---|---|---|
| D08-01 | Yêu cầu hoàn thiện task cuối nhưng thiếu Cloud/RC/owner | Theo task 08 §1/§4 và08/09/10/11: hoàn tất phần độc lập W6, NO-GO release, giữ AC Cloud Blocked; không deploy local candidate để làm đẹp checklist |
| D08-02 | Base defaults là local, chưa có file demo riêng | Thêm application-demo.yml chỉ cho transport/readiness: Secure=true, explicit CORS không fallback; không đổi thresholds/policy nghiệp vụ. 07 §3/08 §4/§6 yêu cầu secrets/env và secure transport |
| D08-03 | Cần kiểm startup thật thay context test | Test bootJar bằng child JVM, explicit demo, whitelist environment và PostgreSQL16 với init roles hiện có. Không dùng test classpath/resolver để giả deployment/auth |
| D08-04 | Existing defaults test đòi demo bằng local | Giữ toàn oracle còn lại; chỉ tách hai override và assert cụ thể theo môi trường. Ghi E02b fail và E03 retest; không sửa nguồn hoặc giấu failure |
| D08-05 | Restart/rollback được hiểu thế nào | Kiểm same-artifact restart local; rollback phải dùng accepted prior artifact, schema compatibility và owner DB. Chưa đủ thì không gọi restart là rollback drill |
| D08-06 | APP_COOKIE_SECURE=false trong ví dụ local có dùng demo không? | Demo đặt app.security.cookie.secure=true trong profile; alias APP_COOKIE_SECURE của base/local không được lấy làm cấu hình Cloud. Actual cookie do identity mint và còn cần HTTPS test |
| D08-07 | Proxy forwarded headers có nên bật để làm HSTS pass? | Giữ none theo03/05/06 cho tới khi trust boundary được kiểm; không tin client X-Forwarded-Proto. HSTS/TLS Cloud còn Blocked, không sửa expected cho spoof header |
| D08-08 | Secret references chưa có URI/version | Ghi đúng tên biến và “chưa được cấp”, không phát minh provider/KMS/IAM hoặc đặt mẫu password/key thành reference thật. Fixture chỉ synthetic, không lưu secret values |
| D08-09 | Mẫu báo cáo nói evidence nhưng owner chỉ report | Nhúng startup/log excerpts/hash/suite vào report, không tạo evidence08; cleanup child temp logs. Giữ evidence05 có sẵn, không sửa lịch sử |
| D08-10 | Deployment docs local nói có Nginx và port8080 nhưng Compose không như vậy | Theo code hiện có, sửa hướng dẫn local thành backend/postgres và localhost18080. Cloud topology vẫn theo nguồn, không coi Nginx đã triển khai |
| D08-11 | Có thể coi toàn common xong sau task 08 không? | Không. Task05/07/08 còn dependency; chỉ các phần đã thực chạy mới Verified. Không tự sign-off/review hoặc nghiệm thu toàn hệ thống |

## 6. Kết luận và bàn giao

- **10 AC: 6 Verified (COM-G01..04, COM-IMP-08-AC01/06), 4 Blocked (COM-IMP-08-AC02..05)**. Không bỏ/hạ AC Cloud hoặc dùng N/A/Deferred để đóng task.
- W6 local readiness đã hoàn thiện: profile demo, packaged artifact và checksum, roles/start/restart/negative config, runbook/secret reference inventory/NO-GO/handoff. **203 tests pass, 0 fail/error/skip** trong E03; bản JAR đã kiểm giữ hash ở §1.2/§4.5.
- **Task 08 chưa thể Done** vì chưa RC/Cloud/technical infrastructure owner. Task05 và07 vẫn Blocked; reviewer/sign-off **Chưa review**. Đây là kết luận implementer về phần đã thực hiện, không phải W7/W8 acceptance.
- Phương bàn giao ứng dụng common theo [runbook](../../../deployment/common-readiness.md). Vũ theo dõi việc chốt owner hạ tầng/reviewer và release gate; module owners hoàn thiện consumer theo07. Không tự giao kỹ thuật hạ tầng cho Vũ hoặc gửi thông báo ra ngoài.
- Khi đủ điều kiện: ghi URL/provider/revision/digest/config/secret reference versions, chạy ma trận W7/W8 trong runbook, thêm execution/fix/redeploy/retest vào report này và chỉ đóng sau mọi AC Verified. Không cần xây lại primitives để bắt đầu phần Cloud; cần đúng dependency và môi trường.
