# BE-COM-IMP-05 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản task:** [BE-COM-IMP-05](../tasks/BE-COM-IMP-05.md), v1.0 ngày 12/11/2026; không hạ hoặc thay AC
> **Ngày / người thực hiện:** 12/11/2026 UTC+07:00 / Codex theo ủy quyền owner
> **Trạng thái task:** **Blocked — phần common đã triển khai, còn thiếu tích hợp identity thật**
> **Kết luận verification:** 5/9 AC Verified; AC01/02/04/05 Blocked ở phần tích hợp owner/môi trường, không tính harness là integration thật
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Hoàn thiện phần common độc lập: security chain, permission/method gates, Problem 401/403 có trace, CORS/CSRF, headers, bounded body reader, token-bucket enforcement và SPI nối identity. Không triển khai session/OTP/login lifecycle, repository/DDL nghiệp vụ, ownership/maker-checker transaction hoặc proxy TLS. **Không kết luận task Done** từ build/harness pass.

- Revision: HEAD `b44e927` (`b44e927` là commit BE-COM-IMP-04) + thay đổi chưa commit. [source.sha256](evidence/BE-COM-IMP-05/source.sha256), [source snapshot](evidence/BE-COM-IMP-05/source-snapshot.tar.gz) và [inspection](evidence/BE-COM-IMP-05/inspection.txt) xác định đầy đủ runtime/config/test/docs đã kiểm tra. Không amend/commit/reset trong task 05.
- Đầu phiên working tree sạch. Trong lúc làm xuất hiện ba deletion `.gitkeep` đã staged tại common/config, context, error; giữ nguyên index, không nhận chúng là thay đổi task 05 hoặc tự restore. Snapshot ghi trạng thái file thực tế; không sửa/xóa nghiệp vụ từ các thay đổi này.
- Môi trường: Linux, OpenJDK 21.0.13.0.3, Gradle 8.14.3, Spring Boot 3.5.7/Spring Security 6.5.6, profile test. PostgreSQL thật `postgres:16-alpine` qua Testcontainers cho regression. [Environment](evidence/BE-COM-IMP-05/environment.txt). Common HTTP tests exclude datasource/JPA/Flyway; MockMvc dùng **production SecurityFilterChainConfig**, resolver giả lập chỉ ở @TestConfiguration. Native HTTP chạy Tomcat thật.
- Dependency 01–04: code đã commit và regression đạt trong E06; HmacService task 04 được tái sử dụng để hash CSRF/subject quota. Identity hiện không có Java source thực thi; `modules/identity/README.md` chỉ có `# identity`. SPI mới của common cần owner identity triển khai/adopt; không nhận đó là contract identity production đã có.

| Nguồn đã đọc / mục | Áp dụng / truy vết |
|---|---|
| README gốc, docs-common/README, docs-backend/README | Cách tra cứu và ưu tiên `01–11 → design → backend`; giữ Đề xuất/Open |
| `03. SRS` §4.2, §4.5; `06. System architecture` §9.1–9.3 | FR-040, NFR-013/017/018/019/020, AC-024/030; RBAC, ownership ở owner; opaque session và phân tách TLS trust boundary |
| `07. API contracts` §2.8–2.9, §3.1–3.6, §4; endpoint index §7/§8 | Public/auth routes, DB_SESSION cookie, CSRF session-bound, Bearer không dựa cookie, CORS allowlist, Problem headers, 64KB/quotas Đề xuất |
| `04. Database design & analysis` §4.2.3 session | Session owner/table hiện chưa có CSRF hash field; common không tự thêm schema hoặc giữ state CSRF trong memory như DB giả |
| `design/04. security` §6.1, §8.1–8.3 | Headers bổ sung, explicit CORS/exposed export headers, token bucket; không dùng ngưỡng transfer 20/user hoặc login username 5 khi trái 07 |
| `09. Test plan` §2.2, §8, §10; common tasks README COM-G01..04 | Evidence/status/truy vết, không dùng harness/mock/H2 chứng minh persistence/ownership/lock/Cloud |
| common README §1.5, §2.1, §4.1, §7.2; task 05; identity README | OI-COM-06/08 đã phân xử, HTTP test matrix, giới hạn không mở rộng task thành viết identity/transfer |
| [Spring CSRF reference](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html); source jars **6.5.6** đã đọc `CsrfFilter`/`CsrfConfigurer` | Native CSRF components, filter order và strategy; API cụ thể đối chiếu source version đang build, không suy từ phiên bản docs web hiện tại. CsrfFilter dùng MessageDigest.isEqual; không lấy timing test làm bằng chứng |

## 2. Thay đổi thực tế

Java paths dưới đây thuộc `digitalbanking-backend/src/`. Bàn giao đầy đủ input/output/cách dùng tại [backend README](../../../../../digitalbanking-backend/README.md#http-security-and-identity-integration).

| File/thành phần | Thay đổi và mục đích | AC được đáp ứng / phần hỗ trợ |
|---|---|---|
| `main/java/.../common/config/SecurityFilterChainConfig.java` | Bật native CSRF, Problem entrypoint/denied/firewall handler, explicit public/permission/CHECKER gates; stateless Servlet context, no default logout/login/cache session | AC01..04 phần common |
| `SessionAuthenticator.java`, `SessionAuthenticationFilter.java`, `SessionCsrfTokens.java` | SPI identity trả immutable internal snapshot user/customer/authorities/CSRF hash; single cookie/Bearer, không mixed/fallback; đọc CSRF hash, không lifecycle. Native constant-time compare | AC01/02; chưa integration |
| `ApiSecurityHeadersFilter.java` | Header filter trước correlation để cả pre-security failures có no-store/Pragma/nosniff/DENY/referrer/CSP; HSTS chỉ actual secure request | AC04 phần backend |
| `RequestBodyLimitFilter.java` | Declared + streamed max65536B, đọc tối đa limit+1, bao gồm chunked; bounded wrapper chuyển bytes cho owner | AC05 body |
| `RequestRateLimiter.java`, `RequestRateLimitFilter.java` | Synchronized bounded process-local token buckets, hashed subjects, Clock UTC; scope IP/user/customer; hết capacity fail-closed 503 | AC05 quota phần common |
| `main/java/.../common/error/ProblemWriter.java` | Overload `write(response, problem, retryAfterSeconds)` chỉ override 429 bằng thời gian chờ thực; giữ fallback existing cho caller khác | AC05 429 |
| `AppProperties.java`, `application.yml`, Compose, `.env.example` | HTTP body/capacity config, positive validation, env forwarding; username10/min theo 07 thay 5; các ngưỡng vẫn Đề xuất | AC03/05 |
| `test/java/.../common/config/HttpSecurity{Test,ComponentsTest,ServletTest}.java` | 30 tests mới: production chain + resolver harness, concurrency/config/component, native Tomcat/chunked/no-adapter | AC01..05 phần common |
| Existing `HealthEndpointTest`, `ContextServletTest`, `CryptoOperationsTest` | Đổi anonymous expected 403→401 đúng contract sau wiring entry point; không đổi test oracle khác | Regression |
| backend README/deployment config/common README/task/index/report | Contract SPI, enforcement ownership, cấu hình, gap và trạng thái Blocked | G01/G04 |

Không thêm code identity hoặc transfer giả trong production. Không tạo bảng/CSRF persistence, issue thật session/cookie, audit sự kiện nghiệp vụ hoặc rate-limiter state DB. Không thay OpenAPI/docs-common. Các sửa ngoài common Java chỉ là test expected-status, cấu hình/env và tài liệu cần thiết cho HTTP layer.

## 3. Ma trận tiêu chí chấp nhận

Giữ nguyên nội dung AC từ nguồn task/tasks README. Một AC có nhiều điều kiện vẫn Blocked nếu còn thiếu phần bắt buộc.

| AC ID | Tiêu chí / kết quả kỳ vọng từ nguồn | Test hoặc cách kiểm tra | Kết quả thực tế | Evidence ID | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Đối chiếu nguồn §1, DEC-01..09 và code native Spring 6.5.6 | Giữ ưu tiên 01–11; không đổi policy ngưỡng Đề xuất hoặc tạo identity nghiệp vụ giả | E01/E07 | Verified |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Review diff/imports và ArchUnit; xem §2 | Chỉ common/config/error, cấu hình/doc/test; không sửa module nghiệp vụ/schema. Ba deletion .gitkeep đang staged do thay đổi đồng thời, không do task này | E06/E07 | Verified |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | clean build và XML final | 181 tests đạt, 0 failure/error/skip; 30 test mới HTTP security. Các integration owner thiếu tách Blocked tại AC riêng | E06 | Verified |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report đủ 9 AC, docs/task/index/handoff/snapshot/evidence | Có report, snapshot/manifest, log/XML đã redact generated security password; giữ nguyên scope chưa verified và owner nhận việc | E07 | Verified |
| COM-IMP-05-AC01 | Security chain dùng contract identity và bảo vệ endpoint/method đúng nguồn; 401/403 là Problem có trace, không redirect/HTML | HttpSecurityTest anonymousExpiredAndDeniedUseProblemAndTraceWithNoRedirect, permissionAndCheckerGatesApplyBeforeOwnersBusinessChecks; HttpSecurityServletTest noIdentityAdapterFailsClosedAndSecurityFiltersAreNotDoubleRegistered | Common URL/method gates và traced 401/403/WWW-Authenticate đạt với harness; runtime không có identity adapter, token bất kỳ bị từ chối. Tích hợp session/permission thật chưa có | E06/E07 | Blocked |
| COM-IMP-05-AC02 | Cookie-authenticated mutation cần CSRF token gắn session; thiếu/sai trả 403 AUTH_FORBIDDEN; Bearer không dựa cookie được miễn, preflight không yêu cầu CSRF | HttpSecurityTest cookieMutationsRequireMatchingSessionCsrfWhileAuthenticatedBearerDoesNot, expiredAmbientCookieDoesNotPreventPublicLoginButActiveCookieStillNeedsCsrf, corsAllowsOnlyConfiguredOriginMethodsHeadersAndExposesContractHeaders | Native CSRF positive/negative/cross-session/duplicate header/valid Bearer/invalid Bearer/OPTIONS đạt qua snapshot giả lập; chưa có CSRF issuance/persistence/session thật | E04/E06 | Blocked |
| COM-IMP-05-AC03 | CORS chỉ cho origin cấu hình, credentials đúng, methods/headers/exposed headers hỗ trợ contract; origin ngoài allowlist không được cấp quyền đọc qua CORS | HttpSecurityTest CORS preflight/actual allow/deny; HttpSecurityComponentsTest corsRejectsInvalidConfigurationWithoutEchoingIt; native filter registration | Explicit origin/credentials đúng, methods/headers/expose hỗ trợ contract; invalid origin/header/method không có ACAO, trả Problem 403; wildcard config bị từ chối, filter chạy một lần | E06 | Verified |
| COM-IMP-05-AC04 | Security/cache headers theo `07` §2.9 trên response liên quan, gồm cả lỗi; cookie thuộc ứng dụng tích hợp đúng thuộc tính ở §3.2 | HttpSecurityTest headersAlsoCoverPreSecurityErrorsAndHstsRequiresActualSecureTransport; bodyBoundaryUsesBytesAndDoesNotLeakRejectedBody; native HTTP tests | Backend headers cả success/lỗi/bad correlation đạt; HSTS chỉ request secure. Không có identity Set-Cookie thật, chưa kiểm attributes; TLS/proxy/full SPA CSP chưa kiểm ở môi trường | E06/E07 | Blocked |
| COM-IMP-05-AC05 | Body-limit và rate-limit có enforcement tại lớp được xác định; quá giới hạn trả 413/429 đúng Problem/trace, 429 có Retry-After; transfer bucket theo customer, không mỗi session/user | HttpSecurityTest body/transfer/login/global/audit/OTP/step-up quota; HttpSecurityComponentsTest unknown length/concurrency/capacity/config; HttpSecurityServletTest chunked boundary | Body 65536/65537B traced 413; token buckets traced 429/real Retry-After; nhiều session/user cùng customer dùng chung quota. Username normalized và pre-login OTP/challenge quota chưa tích hợp identity; multi-session production chưa có | E06/E07 | Blocked |

## 4. Nhật ký kiểm chứng và evidence

Ngày 12/11/2026 UTC+07:00. Gradle chạy từ `digitalbanking-backend/` với quyền cache/socket phù hợp; không dùng thiếu môi trường làm Pass. E06 là runtime/test/config bản cuối; sau đó chỉ ghi docs/evidence.

| Evidence ID | Thời điểm / môi trường / revision | Lệnh hoặc bước thực chạy | Expected → actual / exit code | Artifact |
|---|---|---|---|---|
| E01 | Trước focused, bản common đầu | `./gradlew compileJava --console=plain` | Compile đạt, exit 0 | [compile.log](evidence/BE-COM-IMP-05/compile.log) |
| E02 | Lần focused đầu | `./gradlew test --tests '*HttpSecurity*' --console=plain` | Compile test fail vì checked UnsupportedEncodingException trong helper; exit 1. Sửa helper dùng bytes UTF-8, chỉ định bean CORS rõ để tránh ambiguity | [focused.log](evidence/BE-COM-IMP-05/focused.log) |
| E03 | Lần focused tiếp | Cùng command E02; diagnostic `--tests '*HttpSecurityTest.anonymousExpiredAndDeniedUseProblemAndTraceWithNoRedirect'` | 30 runner cases, 7 fail ở authenticated paths; diagnostic phát hiện CsrfAuthenticationStrategy gọi saveToken/rotation trái ownership của adapter. Exit 1 cả hai. Không hạ AC | [focused-retest.log](evidence/BE-COM-IMP-05/focused-retest.log), [diagnostic.log](evidence/BE-COM-IMP-05/diagnostic.log), [inspection note](evidence/BE-COM-IMP-05/diagnostic-explanation.txt) |
| E04 | Sau E03, trước E05 | `./gradlew test --tests '*HttpSecurity*' --console=plain` | Đặt NullAuthenticatedSessionStrategy riêng cho CSRF, native CSRF filter vẫn bật; bỏ diagnostic tạm. Tests đạt, exit 0 | [focused-retest-2.log](evidence/BE-COM-IMP-05/focused-retest-2.log) |
| E05 | Khoảng 15:22 | `./gradlew clean build --console=plain` | Build/check/packaging/regression đạt, exit 0. Sau đó rà soát bổ sung public re-login với cookie expired và dùng token bucket theo design/04, kiểm lại E06 | [build.log](evidence/BE-COM-IMP-05/build.log) |
| E06 | Kết thúc khoảng **18:13:11 UTC+07:00**, bản cuối | `./gradlew clean build --console=plain` | **181 tests**, 0 failures/errors/skips; 30 HTTP tests mới, 4 ArchUnit và PostgreSQL thật; exit 0 | [build-final.log](evidence/BE-COM-IMP-05/build-final.log), [summary](evidence/BE-COM-IMP-05/test-summary.txt), XML bên dưới |
| E07 | Sau E06 | `docker compose config --quiet`; `git diff --check`; review native source jars, imports/ownership/credentials; snapshot/hash và link checks | Compose config hợp lệ, diff source/docs không lỗi whitespace; không sửa runtime sau E06; thiếu identity/proxy được ghi Blocked | [inspection](evidence/BE-COM-IMP-05/inspection.txt), [compose](evidence/BE-COM-IMP-05/compose.log), [manifest](evidence/BE-COM-IMP-05/source.sha256), [snapshot](evidence/BE-COM-IMP-05/source-snapshot.tar.gz) |

XML: [production-chain harness](evidence/BE-COM-IMP-05/TEST-com.simulatedbank.banking.common.config.HttpSecurityTest.xml), [components](evidence/BE-COM-IMP-05/TEST-com.simulatedbank.banking.common.config.HttpSecurityComponentsTest.xml), [native Tomcat](evidence/BE-COM-IMP-05/TEST-com.simulatedbank.banking.common.config.HttpSecurityServletTest.xml). XML của toàn bộ regression được lưu cùng folder, không chỉ 30 tests mới. Chỉ redact dòng generated Spring security password; giữ nguyên testcase/count/assertions. Mọi credential/PII trong test là synthetic; không lưu `.env`, environment dump, session cookie/token thật hoặc secret triển khai.

Mapping: COM-CFG-02 → explicit CORS/header/transport tests **chỉ phần backend**. COM-HTTP-03/CT-API-004/015/016 → anonymous/expired/permission/method/CSRF/Bearer/preflight. COM-HTTP-01 → body 65536/65537, unknown/misreported length và native chunked. Quota tests chứng minh 30 concurrent được nhận trên100 requests vào cùng bucket; transfer bucket chia sẻ nhiều session/user cùng customer; khác customer độc lập; token tiếp theo tại2s, sau1.1s Retry-After=1. LoginIP burst10 rồi retry6s, export burst5 rồi retry720s, global120, step-up10/5min và OTP20/min bị429 ở đúng boundary; username generic primitive không thay enforcement trong controller identity.

**Chưa chạy và không tính là pass:** login/session expiry/logout/permission snapshot/CSRF persistence/Set-Cookie identity thật; normalized username limiter gắn login thật; pre-login OTP/user binding/challenge lock/resend; money/owner scope/audit integration; ingress TLS/full SPA CSP/forward trust/cluster quota/Cloud. Đây là dependency blockers ở AC01/02/04/05 và gap §5. Không có automated test bị skipped trong E06. PostgreSQL regression không chứng minh session or quota persistence, locking tiền hoặc stateful auth.

## 5. Gap, lỗi và quyết định còn mở

### 5.1. Quyết định thực hiện theo ủy quyền owner

| ID | Vấn đề / nguồn | Quyết định và tác động |
|---|---|---|
| DEC-01 | Identity chỉ có placeholder, task yêu cầu không mở rộng thành viết identity | Tạo SPI ở common để identity implement; không adapter chấp nhận token giả trong production. Missing/invalid session →401, no redirect/HTML; giữ integration Blocked |
| DEC-02 | 07 cho phép cookie hoặc Bearer, không chốt precedence khi cùng gửi/duplicate | Từ chối mixed/duplicate credentials 401; không fallback Bearer lỗi sang cookie. Bearer chỉ miễn CSRF sau successful adapter resolution. Đây là credential boundary, không tạo auth method mới |
| DEC-03 | Cookie HttpOnly cũ vẫn có thể đi kèm POST login public sau idle expiry; 07 phân biệt public/auth và yêu cầu CSRF khi cookie-authenticated | Cookie inactive/unknown được bỏ qua **chỉ** trên public login/passcode/FIDO/OTP/resend; request đó anonymous để identity xử lý body/challenge. Cookie active vẫn require CSRF; invalid explicit Bearer vẫn401. Không clear/mint cookie trong common; valid-state lookup và challenge ownership thuộc identity |
| DEC-04 | 07 CSRF raw token trả qua response và gắn session; không có code/state identity, 04 session chưa có CSRF column | SPI trả SHA256 CSRF hash, native CsrfFilter so constant-time với SHA256 header; không tạo token/lưu DB/HttpSession. NullAuthenticatedSessionStrategy chỉ ngăn Spring rotate/clear CSRF ở auth transition; filter CSRF vẫn bật. Identity phải chốt storage mapping/persistence; không thêm migration lấn ownership |
| DEC-05 | 07 §2.8 10/min/IP + username khác design/04/default cũ username5; transfer30/customer đã phân xử | Đồng bộ username10; HTTP enforce peer-IP/global/verified user/customer buckets. Identity normalize username và gọi shared limiter trước verify; common không parse password/login body hoặc tự lấy username từ raw JSON. Giữ Đề xuất, không nhận policy release đã phê duyệt |
| DEC-06 | 07 không định rõ cách tính wait; design/04 §8.3 nêu token bucket | Bản cuối dùng token bucket virtual scheduling với burst=limit và continuous refill, UTC Clock, làm tròn khoảng refill lên ns để không cấp quá quota. 429 Retry-After làm tròn lên giây đến token tiếp theo; không dùng fixed-window implementation thử nghiệm hoặc fallback2 để chứng minh quota |
| DEC-07 | Không có distributed/edge quota service; phải giới hạn state và không trust forwarding | Map process-local tối đa10000 buckets, subjects lưu SHA256, acquire synchronized; full capacity503, reclaim bucket khi token refill đầy. Reset khi restart, không cluster-wide. Dùng peer socket IP; sau proxy mọi request có thể chung bucket cho đến08 chốt trusted ingress. Không tự tạo infra/DB/session giả |
| DEC-08 | 64KB chưa định nghĩa decimal/binary; headers phải cả pre-security errors, HSTS thuộc TLS | Chọn baseline65536 bytes, kiểm declared lẫnstream limit+1 trước auth; request body synchronous bounded buffer, chưa hỗ trợ Servlet nonblocking ReadListener. Header filter trước correlation, CORS một lần trong security; HSTS chỉactual secure, CSP backend chỉframe-ancestors. Native/auth/cookie/SPA/proxy evidence vẫn tách đúng ownership |
| DEC-09 | SRS §4.2 bảng tổng quan có Auditor đọc fraud, trong khi 07 §3.5 GET/fraud-flags không cấp Auditor; 07 permission seed là tối thiểu, chưa đủ chứng minh toàn endpoint matrix | Dùng 07 cho URL/HTTP gates theo vai trò nguồn ngữ nghĩa API trong README, không suy ưu tiên từ số thứ tự file. Endpoint fraud dùng FRAUD_REVIEW/FRAUD_READ; snapshot permissions thật phải do identity/owner đồng bộ theo matrix trước nghiệm thu AC01. Ghi lại khác biệt 01–11 để owner architecture/Security xử lý, không sửa SRS/API seed hoặc tự đóng policy từ harness |

### 5.2. Blockers và handoff

| ID | AC ảnh hưởng | Gap và tác động | Trạng thái | Đầu mối / điều kiện tiếp tục | Evidence |
|---|---|---|---|---|---|
| B05-IDENTITY | AC01/02/04/05 | Chưa có session resolver/login/session/logout production; không thể kiểm permission/expiry, session-bound CSRF persistence, Set-Cookie hoặc nhiều session thật | **Blocked** | Đạt/identity implement SessionAuthenticator, chuẩn hóa quyền/customer identity, phiên active/expiry/revoke/last-activity, cookie issue/delete đúng07; chạy actual integration qua production chain | Native no-adapter test, source inventory, E06 |
| B05-CSRF-STORE | AC02/04 | 07 cần CSRF gắn session; 04 session table hiện thiếu field và chưa có mapping identity | **Blocked** | Identity + architecture chọn persistence mapping phù hợp nguồn; common không tự thêm schema/state lifecycle | DEC-04, 04 §4.2.3 |
| B05-OWNER-LIMITS | AC05 | Login normalized username chưa gọi limiter; pre-login OTP không có userId đã xác minh, attempt5/challenge/resend cần state owner. Invalid session-token lookup bị trả401 trước post-auth limiter | **Blocked** | Identity enforce username trướccredential verify, bind OTP/challenge/user và attempts; bảo vệ lookuptoken tại owner/edge. Không coi generic primitive/property là enforcement thật | DEC-05, HTTP+component tests |
| B05-PROXY | AC04 phần môi trường; readiness AC05 | Chưa TLS/full SPA CSP, origin/header stripping, direct backend isolation, trusted client IP, nhiều replicas | **Blocked ở integration môi trường** | DevOps/Security và task08 kiểm ingress, actual Set-Cookie Secure; shared/edge quota nếu scale/restart cần aggregate limit | DEC-07/08; headers secureMock chỉ là backend unit/component |
| B05-MATRIX | AC01 | SRS tổng quan và 07 endpoint matrix còn lệch Auditor/fraud; permission seed tối thiểu không chứng minh đủ quyền customer profile cho Auditor theo endpoint | Blocked cùng identity integration | Architecture/Security + identity/business owner đồng bộ projection/seed và xác minh mỗi route theo 07 trước sign-off; không lấy permission fixture làm nguồn mới | DEC-09 |
| OI-API-06 / policy | Release, không sửa ID nguồn | Body/rate threshold vẫn Đề xuất; không tự biến thành approved production policy | Open tại nguồn | Owner/Security chốt ngưỡng trước release, sau đó retest cấu hình môi trường | 07 §2.8/§11.3 |

Code common không còn test failure sau E06. Các blockers là phần AC thật còn thiếu, không hạ thành N/A/Deferred để đóng task. Test fixture không cấp session/cookie hợp lệ ngoài harness; không dùng mock/H2 để nhận nghiệm thu state/persistence. Cookie properties hiện có không tự issue Set-Cookie; không nhận attributes đã verified chỉ vì AppProperties có default.

## 6. Kết luận và bàn giao

- Tổng **9 AC: 5 Verified, 4 Blocked** — COM-IMP-05-AC01/02/04/05; không có AC bị xóa/hạ. **181 tests đạt**, 30 tests HTTP mới, 0 failures/errors/skips. Build/check/package/regression đạt ở E06.
- Kết luận implementer: phần common độc lập đã triển khai và kiểm chứng; **task 05 chưa hoàn thành toàn bộ**, trạng thái **Blocked**, reviewer/sign-off **Chưa review**.
- Identity (Đạt): triển khai/adopt SPI, actual sessions/CSRF storage/cookies, normalize username và gọi limiter trước auth, pre-login OTP/challenge binding/attempt/resend, tránh flood lookuptoken. Retest AC01/02/04/05 với adapter thật; không đưa adapter test vào production.
- Transfer (Vũ): customerId phải do identity xác thực, không lấy từ header/query/body; quota không thay idempotency/rate ownership và owner validation. Ownership/maker-checker/permission snapshot cần owner tests ở07.
- Phương/common: giữ HTTP layer/Problem/header/limit contract, hỗ trợ identity integration; task06 observability không tự biến quota/session thành operational readiness.
- Security/DevOps/task08: kiểm TLS/proxy/full SPA CSP/actual cookie attributes, sourceIP trust và aggregate quotas trước scale/Cloud; task07 kiểm DB/session/boundary flow thực. Report/source snapshot bàn giao đủ để tiếp tục, không tự ký review hoặc chốt policy nguồn.
