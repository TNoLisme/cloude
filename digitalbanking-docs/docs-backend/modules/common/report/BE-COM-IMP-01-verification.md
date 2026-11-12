# BE-COM-IMP-01 — Verification report

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-COM-IMP-01-VERIFICATION
> **Task / phiên bản task:** [BE-COM-IMP-01](../tasks/BE-COM-IMP-01.md), v1.0 ngày 12/11/2026; snapshot/hash tại E05
> **Phiên bản:** v1.0
> **Ngày / người thực hiện:** 2026-11-12T13:55:08+07:00 — Codex, theo yêu cầu của Phương
> **Owner:** Phương
> **Trạng thái task:** Done — implementer verified
> **Kết luận verification:** Verified trong phạm vi cấu hình nền và thời gian
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Hoàn thiện binding/default/override của cấu hình dùng chung, sửa baseline lockout/transfer/session và kiểm chứng Clock UTC/ZoneId nghiệp vụ. Thêm validation cho timezone và ba trường số sửa, redact `Crypto.toString()` để không in key khi debug config. Không thực thi session/OTP/lockout/rate limiter, rule/job, transaction tiền, crypto validation/rotation hoặc HTTP security.

- Revision gốc: `73f190e0a2f34b5f95cc295a90e5fe8a23a4479b`; thay đổi chưa commit. [source.sha256](evidence/BE-COM-IMP-01/source.sha256) xác định từng file; [source-snapshot.tar.gz](evidence/BE-COM-IMP-01/source-snapshot.tar.gz) chứa source/config/test/docs/task đã kiểm tra. Giải nén tại checkout sạch rồi chạy lại Gradle để tái kiểm chứng.
- Môi trường: Linux 7.0.0-43-generic, OpenJDK 21.0.13.0.3, Gradle wrapper 8.14.3, Spring Boot 3.5.7, Docker Engine 29.8.0; PostgreSQL thật qua Testcontainers image `postgres:16-alpine`. Context cấu hình chạy cả không YAML và profile local/test/demo; regression app/migration dùng profile test. Không cần DB cho test binding/thời gian; không suy ra persistence từ context đã exclude datasource.
- Dependency: nền BE-FND-01 có source hiện tại; không dùng report lịch sử làm bằng chứng runtime. BE-COM-DOC-02 là căn cứ phân xử đã đọc, không phải bằng chứng thực thi. Task 02–06 chưa được thực thi trong phiên này; không cần chúng để bind cấu hình hoặc dùng Clock.
- Trước phiên có thay đổi của người dùng trong common README v1.2 và các file IMP-01..08/template/index chưa track; giữ nguyên nội dung ngoài cập nhật liên quan IMP-01, không viết lại report BE-COM-DOC-01/02.

Nguồn chuẩn đã đọc và oracle:

| Nguồn / mục | Áp dụng / truy vết |
|---|---|
| `docs-common/README.md` §2, §4, §7 | Tra cứu theo tên file; ưu tiên 01–11 → design → backend; không suy ưu tiên giữa 01–11 theo số hiệu |
| `docs-common/03. SRS.md` §4.5, §8.5 | AUTH-03/BRL-003/FR-005: 5 lần/10 phút Đề xuất; AUTH-05/BRL-005 session; không biến ngưỡng đề xuất thành policy release |
| `docs-common/06. System architecture.md` §6.1–6.3, §9, §11.2 | Ranh giới common, ownership identity/transfer, external config, không import ngược/module tables |
| `docs-common/07. API contracts.md` §2.4, §2.8, §3.2, §3.7, §11.2–11.3 | UTC timestamp, ngày nghiệp vụ theo zone; 30/phút/customer Đề xuất/OI-API-06; nhiều session và revoke Deferred/OI-API-12; 5/10 Đề xuất |
| `docs-common/09. Test plan.md` §2.2, §8, §10 | Evidence Verified, kết quả fail/retest, môi trường/revision, không mock/H2 cho DB guarantee |
| `docs-common/10 Master timelines.md` §3–§4; `11. Resource & Staffing Plan.md` §3–§4, §7 | Nền W2–W3 và owner Phương, identity Đạt, transfer/limit Vũ, audit/notification Thịnh; đây là kế hoạch/phân công, không bằng chứng tiến độ |
| `docs-backend/modules/common/README.md` §1.5, §2.1, §7.2 | OI-COM-06/08 đã phân xử nguồn; COM-CFG-01, phần binding COM-CFG-03; NFR-016/025/029 theo bảng test, không tuyên bố hoàn thành toàn NFR |
| `docs-backend/deployment/configuration.md` §4–§5, §9–§10 | Hợp đồng env/profiles/config tiêu thụ; đã đối chiếu các trường sửa với 01–11 |

Các link nguồn theo cấu trúc kho: [docs-common](../../../../docs-common/README.md), [common](../TKCT.md), [deployment config](../../../deployment/configuration.md). Không phát hiện mâu thuẫn cần quyết định mới giữa 01–11 cho các trường thay đổi. Không thay các ngưỡng transfer/fraud/OTP/step-up khác để tự đóng open issue.

## 2. Thay đổi thực tế

| File/thành phần | Thay đổi và mục đích | AC được đáp ứng |
|---|---|---|
| [AppProperties.java](../../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/AppProperties.java) | Nested defaults không null; cascade validation cho lockout/rate transfer; 10 phút/30 customer; bỏ cap session; redact crypto toString | AC01/02/04 |
| [TimeConfig.java](../../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/TimeConfig.java) | Tái dùng Clock.systemUTC; ZoneId sai/rỗng fail với diagnostic không echo input | AC01/03/04 |
| [application.yml](../../../../../digitalbanking-backend/src/main/resources/application.yml) | Đồng bộ baseline, bỏ keys cap, đổi scope key và thêm 3 env override; ghi Đề xuất/chưa enforcement | AC01/02/05 |
| [AppPropertiesTest.java](../../../../../digitalbanking-backend/src/test/java/com/simulatedbank/banking/common/config/AppPropertiesTest.java) | 20 ca gồm parameterized cases: binding/override/profiles, startup fail/redaction, UTC/business midnight và injectable fixed Clock | AC01–04 |
| [.env.example](../../../../../digitalbanking-backend/.env.example), [compose.yaml](../../../../../digitalbanking-backend/compose.yaml) | Ghi/forward ba biến env mới; không đổi service, credential hoặc hạ tầng | AC01/05 |
| [Backend README](../../../../../digitalbanking-backend/README.md#common-configuration-and-time) | Hướng dẫn ngắn env/CLI/default, startup validation, injected time, config-only và consumer migration | AC05 |
| [Deployment config](../../../deployment/configuration.md), [common README](../TKCT.md) | Đồng bộ mẫu/hiện trạng cho trường sửa, giữ nhãn và việc bàn giao | AC02/05, G01/04 |
| [Task](../tasks/BE-COM-IMP-01.md), [task index](../tasks/README.md), report/evidence này | Ghi Done ở mức implementer; giữ reviewer/sign-off chưa review | G04 |

Sửa ngoài common chỉ ở cấu hình dùng chung, wiring env Compose, test và tài liệu bàn giao. Không sửa module nghiệp vụ, migration, build dependencies, security chain hoặc spec nguồn. Lịch sử DOC-02 được giữ là snapshot trước implement.

## 3. Ma trận tiêu chí chấp nhận

| AC ID | Tiêu chí / kết quả kỳ vọng từ nguồn | Test hoặc cách kiểm tra | Kết quả thực tế | Evidence ID | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Đối chiếu nguồn và test oracle §1; COM-CFG-01, phần config COM-CFG-03 | Theo 01–11; giữ Đề xuất/Deferred, không đóng OI nguồn | E03, E05 | Verified |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Diff/scope inspection + 4 luật ModuleBoundaryArchTest | Không import module nghiệp vụ; không repository/rule/job/transaction mới | E03, E05 | Verified |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | Focused test, clean build và regression PostgreSQL Testcontainers | 29 pass / 0 fail / 0 error / 0 skip; exit 0; không dùng H2/mock chứng minh persistence | E02, E03 | Verified |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report, docs, evidence, handoff §6 và kiểm tra artifact | Đủ 9 AC; source snapshot/hash, XML/log; chỉ fixture synthetic và output test đã che password sinh tự động | E03, E05, E06 | Verified |
| COM-IMP-01-AC01 | Cấu hình được bind và override theo môi trường đúng hợp đồng; cấu hình bắt buộc sai bị phát hiện khi khởi động với lỗi không lộ giá trị nhạy cảm | defaultsAreUsableWithoutYamlIncludingNestedGroups; yamlAndRecordDefaultsAgreeAcrossProfiles; environmentOverridesYamlAndProfileDefaults; explicitPropertiesOverrideYaml; invalidConfigurationFailsStartupWithoutSecrets; blankTimezoneFailsWithSafeDiagnostic | Nested binding và override đạt; timezone không rỗng/hợp lệ, 3 trường số sửa phải dương/đúng kiểu; startup lỗi không lộ key; Crypto.toString redacted | E03 | Verified |
| COM-IMP-01-AC02 | Default liên quan tuân nguồn: lockout 5 lần/10 phút và transfer 30/phút/customer giữ nhãn Đề xuất; không áp trần session 3/1 từ design cấp dưới; các nơi khai báo không mâu thuẫn nhau | Default/profile/override tests; record components Session; inspection code/YAML/docs | 5 lần/10 phút, 30/phút/customer; không còn keys/accessor cap 3/1 hoặc transfer per-user; nhãn Đề xuất giữ ở YAML/docs | E03, E05 | Verified |
| COM-IMP-01-AC03 | Clock dùng UTC; timezone nghiệp vụ cấu hình được; kiểm thử không phụ thuộc timezone/giờ thật của máy | utcClockAndBusinessDateDoNotDependOnHostTimezone; consumerCanInjectFixedClockAndConfiguredZone; environmentOverridesYamlAndProfileDefaults | Clock UTC; host Honolulu không ảnh hưởng; 16:59:59Z → 17:00:00Z đổi ngày VN; UTC nửa đêm vẫn là ngày trước ở New York; Clock.fixed inject được | E03 | Verified |
| COM-IMP-01-AC04 | Hợp đồng cấu hình/thời gian có thể dùng từ module khác mà common không import ngược module nghiệp vụ hoặc tự chạy rule/job | 4 luật ArchUnit; consumerCanInjectFixedClockAndConfiguredZone; inspection usage | AppProperties/Clock/ZoneId injectable; CORS accessor tương thích; không thêm reverse dependency hoặc rule/job; clean build/check đạt | E03, E05 | Verified |
| COM-IMP-01-AC05 | Có hướng dẫn ngắn về cấu hình bắt buộc, default/override và tham số còn mở; phân biệt khả năng đã triển khai với config chỉ dành cho owner dùng sau | Backend README Common configuration and time; deployment §4.1/§5.2/§9–10; common §1.4–§2.1 | Đủ default/env/CLI, yêu cầu startup, migration consumer và owner; phân biệt config có sẵn với enforcement/rotation chưa có | E04, E05, E06 | Verified |

## 4. Nhật ký kiểm chứng và evidence

Các lệnh Gradle chạy tại `/home/phuong/workspace/cloude/digitalbanking-backend`; inspection/hash chạy tại root workspace. Thời gian dưới đây là UTC+07:00 ngày 12/11/2026. E03 XML ghi UTC, cộng 7 giờ để đối chiếu. Snapshot E05 là revision cuối; thay đổi giữa E01 và E02 chỉ sửa assertion đường dẫn lỗi trong test, không sửa production để làm pass.

| Evidence ID | Thời điểm / môi trường / revision | Working directory + lệnh hoặc bước thực chạy | Expected → actual; exit code, pass/fail/skip | Artifact tái kiểm tra được |
|---|---|---|---|---|
| E01 | 13:47:23–13:47:26, Java 21; cùng production, test trước sửa assertion | backend: `./gradlew test --tests '*AppPropertiesTest' --console=plain` | Mong pass → 24 checks, 6 fail assertion diagnostic (18 pass, 0 skip), exit 1; startup validation vẫn từ chối số 0/âm đúng | [XML lần đầu](evidence/BE-COM-IMP-01/focused-initial.xml) |
| E02 | Khoảng 13:48, revision E05 của Java/test/config | backend: `./gradlew test --tests '*AppPropertiesTest' --console=plain` (redirect log) | Sau sửa assertion camelCase: pass, exit 0; ArchUnit engine cũng chạy 4 rule dù dùng filter | [Focused retest log](evidence/BE-COM-IMP-01/focused-retest.log) |
| E03 | 13:49:27–13:49:41, test profile + PostgreSQL 16, revision E05 | backend: `./gradlew clean build --console=plain` (redirect log) | clean compile/package/check/test/JaCoCo đạt; 29 pass, 0 fail/error/skip; exit 0 | [Build log](evidence/BE-COM-IMP-01/build.log), [suite totals](evidence/BE-COM-IMP-01/test-summary.txt), [config/time XML](evidence/BE-COM-IMP-01/TEST-com.simulatedbank.banking.common.config.AppPropertiesTest.xml), [ArchUnit XML](evidence/BE-COM-IMP-01/TEST-com.simulatedbank.banking.architecture.ModuleBoundaryArchTest.xml), [PostgreSQL XML](evidence/BE-COM-IMP-01/TEST-com.simulatedbank.banking.migration.PostgreSqlFlywayMigrationIntegrationTest.xml) |
| E04 | Khoảng 13:50, Compose của E05 | backend: `docker compose config --quiet` | Schema/interpolation hợp lệ, không in credential, exit 0; không deploy service | [Inspection record](evidence/BE-COM-IMP-01/inspection.txt) |
| E05 | Sau build, cùng source Java/test/YAML đã chạy | root: `git diff --check`; `rg` old keys/accessors trong src/main; `rg` business imports/repository/job/transaction trong common; SHA-256 và source archive | Diff check exit 0; hai rg exit 1 = không có match. Usage CORS/TimeConfig tương thích, không còn consumer accessor cũ | [Inspection](evidence/BE-COM-IMP-01/inspection.txt), [hash manifest](evidence/BE-COM-IMP-01/source.sha256), [snapshot](evidence/BE-COM-IMP-01/source-snapshot.tar.gz) |
| E06 | Khi hoàn tất report | root: kiểm hash, tổng XML, đủ 9 AC, link artifact và nội dung nhạy cảm | Các artifact tồn tại/khớp revision; 9 AC Verified; không có password sinh tự động chưa che hoặc secret thật trong artifact | [Integrity result](evidence/BE-COM-IMP-01/integrity.txt) |

Test mapping:

- **COM-CFG-01**: `utcClockAndBusinessDateDoNotDependOnHostTimezone` dùng Clock.fixed tại `16:59:59Z`/`17:00:00Z`, host Honolulu; `consumerCanInjectFixedClockAndConfiguredZone` inject primary fixed Clock tại UTC midnight/New York. Không assert thời gian thực của máy.
- **COM-CFG-03, chỉ phần config**: bare/default/profiles/override tests, reflection xác nhận Session chỉ còn idle/absolute, validation 0/âm/sai kiểu và startup timezone sai/rỗng. Test bucket nhiều session/customer và enforcement thuộc 05/07, không tính đã Verified toàn COM-CFG-03.
- **AC01/G04**: `invalidConfigurationFailsStartupWithoutSecrets` kiểm cả exception stack và CapturedOutput với key sentinel synthetic; `configurationToStringRedactsAllKeyMaterial` kiểm root/nested toString. Không chứng minh serialization accessor an toàn hoặc crypto key validation.
- **AC04/G02**: 4 luật ModuleBoundaryArchTest (common không phụ thuộc business, domain boundary, inbound/outbound boundary, no module cycles); app context và health regression đạt. Package chưa có class nghiệp vụ nên không suy ra tích hợp các nghiệp vụ tương lai đã đạt.
- **COM-G03**: `PostgreSqlFlywayMigrationIntegrationTest.flywayAppliesV1SchemaAndJpaEntityManagerIsOperational` thực thi Flyway/JPA trên PostgreSQL thật. Đây là regression nền, không chứng minh lock/constraint nghiệp vụ chưa triển khai.

Giới hạn/check chưa chạy: không chạy Cloud/TLS, session/OTP/rate limiting enforcement, multi-key rotation hoặc job. Đây là ngoài phạm vi IMP-01, không dùng chúng thay bằng mock để kết luận đã đạt. Không có check bắt buộc nào của IMP-01 bị skip/blocked. Ban đầu sandbox không cho ghi lock Gradle cache và truy cập Docker; các check cần thiết đã chạy thành công bằng escalation, không còn blocker môi trường.

Kiểm tra link artifact lần đầu phát hiện file kết quả `integrity.txt` chưa được tạo; đã tạo artifact rồi chạy lại toàn bộ kiểm tra integrity đạt (E06), không đổi production/test.

XML cuối là bản sao từ Gradle, chỉ redact dòng password Spring sinh cho test; summary dẫn về XML gốc đã redact, không đổi kết quả/số test. Tất cả input test là synthetic. Manifest/snapshot không thu `.env`, environment dump hoặc credential thực.

## 5. Gap, lỗi và quyết định còn mở

| ID | AC ảnh hưởng | Gap/lỗi và tác động | Trạng thái | Đầu mối / điều kiện tiếp tục | Quyết định hoặc evidence liên quan |
|---|---|---|---|---|---|
| ASSERT-PATH | AC01/G03 | 6 assertion lần đầu tìm kebab-case thay vì camelCase của Bean Validation; không phải validation thiếu | Fixed và retested | Codex; E02/E03 pass | E01–E03 |
| OI-001 / OI-API-06 | Không chặn AC02/05: task yêu cầu giữ nhãn | Lockout/rate limit, session/OTP/threshold còn Đề xuất; không tự phê duyệt policy | Open tại nguồn | Phương phối hợp Đạt/Vũ và owner Security theo nguồn; chốt trước release policy | SRS §8.5; API §11.2–11.3 |
| OI-API-12 | Không chặn AC02/05 | Nhiều session được cho phép; revoke theo device/user Deferred; không áp cap mới | Deferred tại nguồn | Đạt/identity + Security, theo trigger API source | API §3.2/§11.3 |
| OI-COM-05 | Ngoài IMP-01 | Multi-key rotation còn thiếu thiết kế; `previousKeys` chỉ là field config hiện có, không cơ chế rotation | Open | IMP-04/Phương + Security và account/customer; không tự thêm format/schema | common §1.5 |
| CONSUMERS | Không chặn AC04/05 | Module nghiệp vụ chưa có implementation; config/Clock sẵn để inject, runtime enforcement do owner tích hợp sau | Handoff, chưa thực thi | IMP-02..06/Phương, identity/Đạt, transfer-limit/Vũ, audit-notification/Thịnh | §6; E05 |

Không có gap ảnh hưởng AC bắt buộc của IMP-01 còn chưa đạt. Các giới hạn trên không bị chuyển thành N/A để đóng tiêu chí; AC02/05 yêu cầu công khai nhãn và việc chưa triển khai, đã kiểm chứng đúng phạm vi đó.

## 6. Kết luận và bàn giao

- Tổng **9 AC**: **9 Verified**, 0 chưa đạt/chưa kiểm chứng. Kết luận implementer: đạt phạm vi BE-COM-IMP-01; đề nghị **Done**. Clean build 29 pass/0 fail/0 skip và evidence lưu trong report, không đồng nghĩa toàn common hoặc hệ thống đã nghiệm thu.
- **IMP-02 (Phương):** inject Clock cho timestamp lỗi; không gọi giờ thật trực tiếp. Dùng AppProperties hiện tại, không copy default riêng.
- **IMP-03 (Phương):** dùng config/Clock nền cho request context; chưa có pipeline trace do task 01.
- **IMP-04 (Phương + Security/account/customer):** đọc AES/HMAC qua accessor, không log/serialize; bổ sung fail-fast missing/Base64/32-byte và chính sách key đúng nguồn. `Crypto.toString()` đã redact; rotation vẫn thiếu thiết kế, không bật từ `previousKeys`.
- **IMP-05 (Phương + Đạt/identity):** giữ DB_SESSION/path hiện có; triển khai security/CSRF/session/OTP/lockout đúng source. Không dùng accessor cap session đã bỏ. Lockout dùng maxFailedAttempts/lockDurationMinutes, còn Đề xuất.
- **IMP-06 (Phương phối hợp owner module):** dùng Clock UTC cho timestamp quan sát; config jobs chỉ được owner dùng, không tự bật scheduler ở common. Kiểm redaction/logging khi thêm consumer.
- **Transfer/limit (Vũ), identity (Đạt), audit/notification (Thịnh), tích hợp 07:** dùng `transferPerCustomerPerMinute()` và bucket customer, bỏ key cũ `transfer-per-user-per-minute`; remove keys cap session khỏi deployment. Clock.instant cho timestamp/TTL; LocalDate.now(clock.withZone(businessZoneId)) cho business date. App code hiện chưa có consumer nghiệp vụ cần sửa tại task 01.
- **Review/sign-off thực tế:** Chưa review; không tự nhận phê duyệt thay Backend Lead/QA/Security. Cần người review kiểm diff, report/evidence và policy mở trước release.
