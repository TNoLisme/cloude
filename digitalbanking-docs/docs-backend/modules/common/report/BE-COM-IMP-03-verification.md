# BE-COM-IMP-03 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản task:** [BE-COM-IMP-03](../tasks/BE-COM-IMP-03.md), v1.0 / 12/11/2026
> **Ngày / người thực hiện:** 12/11/2026, UTC+07:00 — Codex theo yêu cầu Phương
> **Trạng thái task:** Done — implementer verified
> **Kết luận verification:** Verified trong phạm vi context/filter và lifecycle thành phần
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Triển khai context bất biến, correlation/request ID, MDC, servlet filter trước security, shared error writer và capture/bind/cleanup trên worker. Không truy cập DB nghiệp vụ, tạo job/executor production, thêm auth/session/OTP, ghi audit hoặc claim AC-022 xuyên persistence.

- Revision gốc: `73f190e0a2f34b5f95cc295a90e5fe8a23a4479b`; working tree chưa commit, đã có thay đổi IMP-01/02 và backlog/report trước phiên. E05 snapshot toàn bộ source/test/resources/build/contract xác định đúng bản đã test; không dùng diff toàn workspace để nhận mọi thay đổi là của task 03.
- Môi trường: Linux 7.0.0-43-generic; OpenJDK 21.0.13.0.3; Gradle wrapper 8.14.3; Spring Boot 3.5.7. Unit dùng Servlet mocks; HTTP dùng MockMvc với security harness; servlet test dùng Tomcat thật, random localhost port, production SecurityFilterChain, profile test và loại DB auto-config. Regression migration dùng Testcontainers PostgreSQL 16.15, không dùng H2.
- Dependency 01–02: dùng Clock UTC, ErrorCode/ProblemWriter/GlobalExceptionHandler hiện có; chạy lại toàn suite. Report [01](BE-COM-IMP-01-verification.md), [02](BE-COM-IMP-02-verification.md) là baseline lịch sử; E04 chứng minh regression trên source hiện tại. Security production vẫn trả 403 baseline cho anonymous; authentication/session/writer integration thật thuộc task 05.
- Đường dẫn user ghi `digitalbankingdocs`/`taks` được đối chiếu thành `digitalbanking-docs`/`tasks` trên đĩa; không tạo bản tài liệu trùng.

| Nguồn đã đọc | Mục / oracle |
|---|---|
| [README gốc](../../../../README.md), [common README](../../../../docs-common/README.md), [backend README](../../../README.md) | Cách tra cứu; docs-common/01–11 → design → backend |
| [03. SRS](../../../../docs-common/03.%20SRS.md) | FR-038, §2.13.3, NFR-018/020/025, §10 AC-022: dùng/sinh/propagate ID, response trace |
| [07. API contracts](../../../../docs-common/07.%20API%20contracts.md) | §2.2 pattern, reuse/missing/400; §2.7 mọi response và MDC; §4 Problem; requestId internal |
| [design/01](../../../../docs-common/design/01.%20audit-traceability.md) | §4.6 server requestId, retry/job propagation; §5.6 masked IP, UA ≤256, audit allowlist |
| [design/04](../../../../docs-common/design/04.%20security.md) | §8.1 input/log injection; §7.1–7.2 privacy |
| [09. Test plan](../../../../docs-common/09.%20Test%20plan.md) | §2.2, §6, §8, §10 evidence, exit criteria và giới hạn mock |
| [common spec](../README.md), [task index](../tasks/README.md), [task](../tasks/BE-COM-IMP-03.md) | §2.2, §5.1–5.2, COM-CTX-01..06; COM-G01..04 và AC01..05 |

## 2. Thay đổi thực tế

Đường dẫn Java bên dưới tính từ `digitalbanking-backend/src/` trong snapshot E05.

| File/thành phần | Thay đổi và mục đích | AC |
|---|---|---|
| `main/java/.../common/context/RequestContext.java` | Snapshot IDs/metadata; validate IDs, mask IPv4, IPv6/unknown null, bỏ control/format characters và cap UA; toString chỉ IDs | AC02/04/05 |
| `main/java/.../common/context/RequestContextHolder.java` | ThreadLocal không inheritable; scope bind đồng bộ holder/MDC, phục hồi binding cũ và giữ MDC khác khi đóng | AC02/04 |
| `main/java/.../common/context/CorrelationAndRequestIdFilter.java` | Validate header cả duplicate, UUIDv4, bind context, set header trước downstream; safe Problem cho validation/pre-MVC exception; reuse snapshot trên dispatch, không double write | AC01–04 |
| `main/java/.../common/config/RequestContextConfig.java` | Đăng ký servlet filter duy nhất, order -101 trước security -100, REQUEST/ASYNC/ERROR, async supported; không add vào security chain | AC03 |
| `main/java/.../common/error/ProblemWriter.java` | Tái dùng correlation validator đúng contract có dấu `:`; tránh writer thay ID hợp lệ bằng UUID khác | AC01–03 |
| `main/resources/application.yml` | `forward-headers-strategy: none` vì chưa có proxy trust boundary được enforce | AC05 |
| `test/java/.../common/context/{RequestContextTest,ContextHttpTest,ContextServletTest}.java` | 23 test cases: biên/cleanup/concurrency/dispatch, MVC async success/failure, 401/403 harness và servlet thật | AC01–05 |
| [Backend usage](../../../../../digitalbanking-backend/README.md#request-context-and-propagation), [configuration](../../../deployment/configuration.md), [common spec](../README.md), task/index | Đồng bộ API bind, wiring, metadata, proxy và propagation guidance; cập nhật trạng thái và report | G04/AC05 |

Không sửa module nghiệp vụ, migrations, dependencies hoặc production SecurityFilterChain. Các thay đổi AppProperties/TimeConfig/.env/Compose và error layer còn lại thuộc baseline trước phiên, giữ nguyên.

## 3. Ma trận tiêu chí chấp nhận

| AC ID | Tiêu chí / kết quả kỳ vọng từ nguồn | Test hoặc cách kiểm tra | Kết quả thực tế | Evidence ID | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Đối chiếu nguồn §1; quyết định §5 | Oracle đúng 07/SRS, không đổi contract; ghi rõ giới hạn AC-022 | E04/E05/E06 | Verified |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | ArchUnit + scope inspection | 4 ArchUnit pass; common không import owner, không DB/transaction nghiệp vụ | E04/E05 | Verified |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | Clean build và XML summary | 70 pass, 0 fail/error/skip; PostgreSQL regression thật | E04 | Verified |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report, usage, task/index và evidence integrity | Đủ 9 AC; link/hash/XML được kiểm; handoff có owner | E05/E06 | Verified |
| COM-IMP-03-AC01 | Header hợp lệ được giữ; thiếu sinh UUID; rỗng/sai pattern/vượt 64 ký tự trả 400 VALIDATION_ERROR; không echo/log giá trị header sai | missingOrValidHeaderAndRetriesHaveConsistentIds; invalidHeaderNeverReachesDownstreamOrOutput; duplicateHeadersAreRejected | Missing UUIDv4; valid 1/64 giữ; empty/65/control/Unicode/duplicate 400 safe ID, không downstream/echo/log raw | E04 | Verified |
| COM-IMP-03-AC02 | Mỗi request có requestId mới do server sinh; context/MDC/correlation response nhất quán; client không điều khiển requestId | missingOrValidHeaderAndRetriesHaveConsistentIds; downstreamProblemWriterRetainsColonCorrelation; ContextHttpTest | Retry giữ correlation, request UUIDv4 khác, bỏ client X-Request-ID; MDC/context/header/Problem nhất quán kể cả dấu : | E04 | Verified |
| COM-IMP-03-AC03 | Filter đăng ký một lần trước security; X-Correlation-ID có trên success, validation, 401/403 và lỗi trước MVC; không double-write khi error/async dispatch | ContextServletTest; ContextHttpTest; asyncErrorAndNestedErrorRedispatchReuseSnapshotAndCleanThreads; exceptionsBeforeMvcAreSafeAndCommittedResponsesAreNotRewritten | Registration duy nhất -101; headers trên 200/400/401/403/404/500; ASYNC/ERROR/nested ERROR giữ IDs; không rewrite committed/handled body | E04 | Verified |
| COM-IMP-03-AC04 | Context/MDC không rò giữa request nối tiếp/cùng worker hoặc đồng thời; cleanup cả khi exception; async/job có cách capture/bind/clear rõ ràng | concurrentRequestsAndReusedWorkersNeverShareContext; explicitHandoffRestoresWorkerMdcEvenOnFailure; ContextHttpTest async success/failure; exception/dispatch tests | 12 request trên 4 workers có barrier; reuse thread, capture/bind, worker exception, nested binding/redispatch đều cleanup, MDC khác được giữ | E04 | Verified |
| COM-IMP-03-AC05 | Metadata IP/UA không tin header proxy tùy ý, không log raw PII/control characters; có hướng dẫn consumer propagate correlation thay vì sinh ID mới giữa flow | metadataIgnoresUntrustedProxyAndNeverLogsRawIpOrAgent; ContextServletTest; usage inspection | Ignore Forwarded/XFF; peer IPv4 masked, IPv6 null; UA controls removed/≤256; không log raw metadata; hướng dẫn copy ID/job/proxy limits | E04/E05/E06 | Verified |

## 4. Nhật ký kiểm chứng và evidence

Mọi lệnh Gradle chạy từ `digitalbanking-backend/`, ngày 12/11/2026 UTC+07:00. Các lần fail không được tính pass. E04 là final runtime/test source; sau E04 chỉ thay docs/evidence.

| Evidence | Thời điểm / lệnh thực chạy | Expected → actual | Artifact |
|---|---|---|---|
| E00 | Đầu kiểm thử: `./gradlew test --tests '*common.context.*' --console=plain` trong sandbox | Blocked cache Gradle read-only, exit 1; chưa chạy test. Chuyển sang execution được cấp quyền cho cache/local socket | [focused.log](evidence/BE-COM-IMP-03/focused.log) |
| E01 | Khoảng 14:30–14:31: cùng lệnh E00 | Compile fail: gọi nhầm `defaultDetail()` thay API hiện có `detail()`, exit 1; sửa lời gọi, không sửa catalog | [compile log](evidence/BE-COM-IMP-03/focused-retest.log) |
| E02 | Khoảng 14:32: cùng lệnh E00 | 26 tests completed, 3 failed: tên bean `requestContextFilter` trùng WebMvcAutoConfiguration; đổi tên bean/registration thành `correlationAndRequestIdFilter`, exit 1 | [wiring log](evidence/BE-COM-IMP-03/focused-retest-2.log) |
| E03 | Khoảng 14:32:44: cùng lệnh E00 | Focused build đạt, exit 0; sau đó bổ sung test MVC async failure và chạy E04 | [focused pass](evidence/BE-COM-IMP-03/focused-retest-3.log) |
| E04 | Khoảng 14:33:08–14:33:28: `./gradlew clean build --console=plain` | Compile/package/test/check/JaCoCo đạt, exit 0; XML tổng 70 tests, 0 failures/errors/skips, gồm 23 context tests | [build](evidence/BE-COM-IMP-03/build.log), [summary](evidence/BE-COM-IMP-03/test-summary.txt), [unit XML](evidence/BE-COM-IMP-03/TEST-com.simulatedbank.banking.common.context.RequestContextTest.xml), [MVC XML](evidence/BE-COM-IMP-03/TEST-com.simulatedbank.banking.common.context.ContextHttpTest.xml), [servlet XML](evidence/BE-COM-IMP-03/TEST-com.simulatedbank.banking.common.context.ContextServletTest.xml) |
| E05 | Sau E04: `git diff --check`; source/ownership inspection; snapshot/hash script | Diff check exit 0; source được đóng gói và hash; không import business/DB transaction | [inspection](evidence/BE-COM-IMP-03/inspection.txt), [source manifest](evidence/BE-COM-IMP-03/source.sha256), [snapshot](evidence/BE-COM-IMP-03/source-snapshot.tar.gz) |
| E06 | Sau report: `python3 digitalbanking-docs/docs-backend/modules/common/report/evidence/BE-COM-IMP-03/verify-evidence.py` | Kiểm hash source/snapshot, đủ 9 AC Verified, link file, XML totals, redaction và git diff; exit 0 | [integrity](evidence/BE-COM-IMP-03/integrity.txt), [script](evidence/BE-COM-IMP-03/verify-evidence.py) |

Test mapping: COM-CTX-01/02 → missing/valid/retry tests; COM-CTX-03 → invalid/duplicate/metadata tests; COM-CTX-04 → worker/concurrency/exception tests; COM-CTX-05 → ContextHttpTest/ContextServletTest/dispatch tests; COM-CTX-06 chỉ cung cấp primitive và hướng dẫn handoff, chưa nghiệm thu flow xuyên DB. CT-API-015/016 chỉ phần trace/Problem security harness. Các XML regression được lưu cùng evidence, bao gồm ArchUnit và PostgreSQL integration.

Không có test task 03 skipped/blocked trong E04. PostgreSQL regression chứng minh nền không hỏng; không chứng minh money path/locking/audit propagation. Không chạy Cloud/Nginx/TLS/session identity thật. ERROR/nested ERROR là servlet component harness; async completion và failure chạy qua MVC thực. Registration/production 403 và health 200 được gọi bằng Java HttpClient trên Tomcat thật. 401/403 Problem dùng security chain harness có writer, không thay cho task 05.

XML sao từ Gradle chỉ thay dòng mật khẩu Spring tự sinh bằng `[REDACTED]`, không sửa counts/testcases/assertions. Nguồn test chỉ chứa dữ liệu synthetic. Snapshot không lấy `.env`, secrets hoặc thư mục cache. Không nhận technical log là audit record.

## 5. Gap, lỗi và quyết định

Owner đã ủy quyền tự quyết định điểm mơ hồ/conflict dựa trên tài liệu và ghi lại trong report. Không phát hiện conflict chưa giải quyết giữa 01–11 trong phạm vi task.

| ID | Quyết định / lý do và nguồn | Tác động / trạng thái |
|---|---|---|
| DEC-03-01 | Chọn path hiện hữu `digitalbanking-docs/.../tasks/BE-COM-IMP-03.md`; README xác nhận đây là kho chính thức | Đã áp dụng; không tạo thư mục theo typo |
| DEC-03-02 | 07 §2.2 yêu cầu pattern có `:`; writer cũ thiếu dấu này. Tái dùng validator chung, giữ nguyên mọi ID hợp lệ. Header sai dùng UUIDv4 mới; nhiều header bị coi là đầu vào mơ hồ và từ chối 400 | Đã triển khai/kiểm; không echo/log header sai, không auto-trim |
| DEC-03-03 | Backend từng minh họa HIGHEST_PRECEDENCE và set(holder), nhưng nguồn bắt buộc filter trước security và cleanup. Chọn registration duy nhất order -101 trước security -100; bind scope tự đồng bộ MDC và phục hồi context cũ | Đã triển khai/kiểm; tên bean riêng tránh đè Spring MVC; không thêm registration trong chain |
| DEC-03-04 | Runtime `framework` tin forwarded headers trong khi chưa có trusted proxy enforcement. Theo NFR-018/020 và common §2.2, chọn `none`, socket peer; IPv4 mask octet cuối theo design/01 §5.6; IPv6/unknown null theo common §2.3 fail-closed | Đã triển khai/kiểm. Sau proxy chỉ thấy proxy peer; không dùng masked IP làm limiter key. IMP-05/08 cần ingress trust verification trước đổi cấu hình; HTTPS redirects có thể cần cấu hình ở ingress |
| DEC-03-05 | UA là input tùy ý: bỏ control/format/Unicode separators và cap 256; không đưa metadata vào MDC/log/Problem, toString chỉ IDs. Không dùng heuristic đoán PII trong UA; consumer không log hoặc serialize raw/sanitized UA ngoài allowlist | Đã triển khai/kiểm; masking primitives tổng quát vẫn thuộc 04 |
| DEC-03-06 | ERROR/ASYNC dùng snapshot trên request, không tạo requestId mới cho redispatch. Catch ServletException/IOException/RuntimeException ngoài MVC → INTERNAL_ERROR cố định; committed response giữ nguyên, handled Problem không chạy lại downstream | Đã triển khai/kiểm; không thể đổi body/status đã commit. Không ghi raw throwable; JVM Error vẫn được cleanup bởi scope, không chuyển thành lỗi nghiệp vụ |
| DEC-03-07 | ThreadLocal không inheritable; worker capture snapshot và bind trong try-with-resources. Async continuation cùng request giữ requestId; job run riêng tạo requestId mới và giữ correlation của transfer. Không tạo executor/TaskDecorator/job khi chưa có consumer | Đã triển khai/kiểm ở component và MVC; ownership propagation vẫn theo design/01 §4.6 |

| Gap / giới hạn còn lại | AC ảnh hưởng | Đầu mối / điều kiện tiếp tục |
|---|---|---|
| Production authentication entrypoint/denied writer, cookie/Bearer/CSRF/session thật chưa tích hợp | Ngoài acceptance độc lập 03, theo chính AC03 | IMP-05 / Phương + Đạt: tái dùng writer, filter đã chạy trước security |
| Transfer → ledger/audit/outbox/challenge persistence và job production chưa kiểm | AC-022 toàn hệ thống, ngoài component scope 03 | IMP-07 / Phương, Vũ transfer, Thịnh audit/notification và Đạt identity: copy original correlation, job requestId riêng, kiểm DB thật |
| Trusted ingress, TLS và Cloud chưa kiểm; tắt forwarded headers có nghĩa không dùng forwarded client IP/scheme | Ngoài trust boundary local đã kiểm của AC05 | IMP-05/08 / owner security và hạ tầng: strip/overwrite headers, restrict direct access và kiểm redirects trước bật support |
| Structured HTTP access log/metrics đầy đủ chưa triển khai | Ngoài context/MDC scope 03 | IMP-06 / Phương: log IDs trước cleanup, không log metadata/body/raw cause; không dùng ID làm metric label |

Không có blocker còn lại ảnh hưởng 9 AC của task 03. Các gap bàn giao không được dùng như bằng chứng đã đạt toàn AC-022/D3/D4. Hai lỗi implementation ban đầu (API catalog/tên bean) đã sửa và được clean build kiểm lại E04.

## 6. Kết luận và bàn giao

- Tổng **9 AC**, **9 Verified** trong scope task; 0 chưa đạt/chưa kiểm chứng. Clean build **70 pass, 0 fail/error/skip**, gồm **23 context tests**.
- Kết luận implementer: **Done**, chờ review; không đồng nghĩa common/toàn hệ thống đã nghiệm thu.
- Bàn giao primitive và usage cho IMP-05/06/07, proxy readiness cho IMP-08; owner và điều kiện ở §5.
- Reviewer/sign-off: **Chưa review**; Backend Lead/QA/Security kiểm diff/evidence và các consumer. Không tự nhận phê duyệt thay reviewer.
