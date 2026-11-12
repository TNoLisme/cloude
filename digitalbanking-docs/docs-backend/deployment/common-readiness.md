# Common — readiness và runbook ứng dụng

> **Mã tài liệu:** BE-COM-OPS-08
> **Ngày:** 12/11/2026
> **Owner ứng dụng:** Phương; owner/reviewer hạ tầng: chưa phân công theo tài liệu 11 §8
> **Trạng thái:** Chuẩn bị W6; **NO-GO cho release/Cloud** khi task 07 còn Blocked
> **Verification:** [BE-COM-IMP-08](../modules/common/report/BE-COM-IMP-08-verification.md)

## 1. Bản kiểm và điều kiện release

Nguồn quyết định: docs-common 08 §4–§7/§9/§11, 09 §8–§10, 10 §4.6–4.8, 11 §7–§10; ngữ nghĩa HTTP theo 07 §2–§4/§8.9.1. Hướng dẫn này mô tả phần ứng dụng common, không thay kế hoạch provisioning/backup/CI/CD của owner hạ tầng.

Artifact, SHA-256, source revision cùng dirty snapshot và kết quả thực chạy nằm trong report 08. JAR local là candidate để kiểm readiness; chưa là release candidate được nghiệm thu. Báo cáo [task 07](../modules/common/report/BE-COM-IMP-07-verification.md) ghi các consumer W3/W4/W5 còn thiếu. Chưa có URL Cloud, deployment ID, image digest hoặc owner hạ tầng được xác nhận. Không coi một lần startup local thành bằng chứng W7/W8.

Trước khi phát hành cần đủ: task 07 và Must AC liên quan đạt; owner/reviewer hạ tầng được phân công; URL/private DB/TLS/ingress và secret references được cấp; artifact và image digest bất biến; OpenAPI/scan/DB migration/backup compatibility được review; có artifact trước đã nghiệm thu để rollback. Hiện quyết định là **NO-GO**. Không tự phê duyệt ngoại lệ policy, rotation hoặc D1–D5.

## 2. Cấu hình và secret references

Chỉ dùng profile **demo** trên môi trường demo/Cloud. Không gộp local/test vào active profiles, không dùng `.env.example` hoặc mặc định của `compose.yaml` local để deploy. Source production là application.yml + application-demo.yml; application-local.yml và application-test.yml chỉ cho phát triển/kiểm thử.

| Tên biến / property | Cách cấp và kiểm tra |
|---|---|
| SPRING_PROFILES_ACTIVE | Giá trị demo, khai báo tường minh; không để fallback local |
| DB_HOST / DB_PORT / DB_NAME | Private endpoint của owner hạ tầng; không ghi credential trong JDBC URL |
| DB_APP_USER / DB_APP_PASSWORD | Runtime role banking_app, mật khẩu qua secret reference ngoài image; không superuser |
| DB_MIGRATION_USER / DB_MIGRATION_PASSWORD | Migration role banking_migrator riêng; secret reference khác, giữ least privilege |
| APP_PII_AES_KEY_BASE64 / APP_HMAC_KEY_BASE64 | Hai secret references tách biệt, Base64 đúng 32 byte, không published development keys, không cùng khóa; không in giá trị để kiểm |
| APP_PII_AES_KEY_ID | Metadata cho khóa hiện tại; không phải implementation rotation |
| app.crypto.previous-keys | Giữ rỗng; rotation nhiều khóa còn OQ-15/OI-COM-05, không bật trước khi có protocol |
| APP_CORS_ALLOWED_ORIGINS | Bắt buộc cấp các HTTP(S) origin SPA tường minh; demo không có localhost fallback, thiếu/wildcard bị từ chối lúc startup |
| app.security.cookie.secure | Profile demo đặt true; APP_COOKIE_SECURE chỉ còn có ý nghĩa ở base/local. Identity phải mint cookie đúng contract và được kiểm HTTPS thực |
| APP_ENVIRONMENT / APP_VERSION | Nhãn môi trường và phiên bản artifact được triển khai; không dùng nhãn để giả source revision hoặc trạng thái release |
| APP_TIMEZONE | Mặc định Asia/Ho_Chi_Minh, ZoneId hợp lệ; API timestamps UTC |
| DB_POOL_MIN_IDLE / DB_POOL_MAX_SIZE | Theo cấu hình đã kiểm/owner benchmark; không coi ngưỡng Đề xuất là policy release |
| server.forward-headers-strategy | Giữ none đến khi ingress trust boundary được cấu hình/kiểm. TLS kết thúc ở proxy không tự làm backend tin Forwarded/X-Forwarded-* |

Secret-store URI/version, provider IAM, expiry và owner rotation của từng reference hiện **chưa được cung cấp**. Owner hạ tầng cần ghi reference identifiers đã che nhạy cảm vào deployment record; không đưa giá trị khóa, password, cookie hoặc token vào report/CLI arguments. Test readiness dùng secret synthetic chỉ trong child process/environment và disposable PostgreSQL.

## 3. Build và tái kiểm tra W6

Từ digitalbanking-backend/, Java 21 và Docker sẵn sàng:

```bash
./gradlew clean check --offline --console=plain
sha256sum build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar
```

Bỏ --offline khi cần tải dependency. Test task phụ thuộc bootJar để kiểm chính JAR vừa đóng gói; test classpath không được inject vào process ứng dụng. PackagedApplicationReadinessIntegrationTest chạy profile demo, PostgreSQL 16 và script init role hiện có; kiểm startup, health, restart cùng checksum, runtime role không superuser, Flyway V1 và negative config. Child process chỉ nhận environment synthetic đã chọn, không thừa kế secret hay JAVA_TOOL_OPTIONS/SPRING_APPLICATION_JSON của máy.

`postgres:16-alpine` trong test/local là image baseline; release cần pin digest do owner ghi nhận. Build JAR không tạo image/registry release hoặc chứng minh reproducible Docker build. Test bootJar không thay quality gate nghiệp vụ: artifacts chỉ được deploy sau khi toàn bộ release gate đạt.

## 4. Start, restart và rollback phần ứng dụng

Các bước dưới đây dành cho owner vận hành sau khi gate được thông qua; không phải tuyên bố đã chạy trên Cloud.

1. Ghi artifact path, source revision, SHA-256/image digest, config version và secret reference versions. So checksum với artifact đã duyệt; nếu worktree dirty, giữ manifest trong report, không gọi artifact đó là release đã nghiệm thu.
2. Xác nhận schema/migrations tương thích, runtime và migration role tách biệt, backup/restore evidence đã được owner DB kiểm. Common không tự nhận việc backup hoặc phục hồi dữ liệu.
3. Service manager/container của owner nạp environment từ secret store. Lệnh ứng dụng đã kiểm về hình thức là `java -jar "$APP_JAR" --spring.profiles.active=demo`; APP_JAR phải trỏ artifact đã kiểm, không mutable download. Không truyền secret bằng command-line flags.
4. Chờ aggregate `/actuator/health` UP; health/liveness hoặc readiness riêng không chứng minh database sẵn sàng. Nếu DOWN, dừng nhận traffic mới và điều tra secret references/DB connectivity/permissions/migration/config; không sửa trạng thái transfer hoặc retry POST mù.
5. Restart: owner drain traffic nếu cần, gửi graceful stop qua service manager/SIGTERM, chờ process dừng rồi start **cùng artifact/config đã ghi**. Kiểm checksum, health, trace và smoke test. Test local chỉ chứng minh restart cùng JAR với DB giữ nguyên, chưa chứng minh rolling restart/Cloud connection drain.
6. Rollback ứng dụng: chọn **artifact đã được nghiệm thu trước đó**, kiểm checksum và schema compatibility, thay artifact qua service manager rồi chạy lại health/API/identity/account/transfer/audit smoke. Không có artifact trước được nghiệm thu thì gate rollback chưa đạt; restart cùng JAR không phải rollback drill.
7. Không sửa/xóa migration đã áp dụng. Nếu schema không tương thích, giữ traffic dừng và để DB owner thực hiện forward compensating migration hoặc restore đã được duyệt; tuyệt đối không dùng local reset/TRUNCATE hoặc `docker compose down -v` trên môi trường đang phục vụ.

Nếu process startup exit khác 0: lưu timestamp/revision/config version, logger/exception class và safe error code; không bật raw message/stack fallback để debug bằng secret. Rotation secret cần review và kiểm lại toàn bộ consumer; không tự thêm previousKeys hoặc thay khóa làm dữ liệu cũ không đọc được.

## 5. Ma trận W7/W8 cần thực chạy trên Cloud

| Mốc / scenario | Kỳ vọng và evidence cần ghi | Owner / điều kiện |
|---|---|---|
| W7 deploy/start | URL HTTPS, deployment ID, revision/digest, config/secret references, startup/Flyway và app/DB UP | Owner hạ tầng chưa phân công, phối hợp Phương; cần RC qua task 07 |
| W7 negative config | Cấu hình thiếu/sai phải fail an toàn, không log secret | Môi trường cô lập tương đương, không sửa secret của môi trường đang phục vụ |
| W7 proxy/TLS | TLS 1.2+, đúng certificate/domain; backend/DB không public; Forwarded headers bị strip/overwrite theo trust design | Owner hạ tầng/Security; không suy từ HTTP localhost |
| W7/W8 HTTP trace/errors | Success, invalid correlation, unauthorized401, forbidden403, origin sai403, CSRF sai403, body quá lớn413; proxy error có trace/headers/content type đúng contract | Cần ingress và identity adapter thật; ghi cả lỗi proxy lẫn filter, không giả 413 của app chứng minh proxy |
| W8 cookie/CSRF | Cookie DB_SESSION Secure/HttpOnly/SameSite/Path; CSRF bound session, Bearer semantics đúng; login/logout/expiry hoạt động | Identity/Đạt + hạ tầng; không lưu token/cookie thật |
| W8 observability | Correlation header → JSON log → owner data/flow; metrics low cardinality, admin/auditor protection, health DOWN503 đúng HealthResponse | Phương + owners identity/transfer/audit/notification; cần credentials test và consumer thật |
| W8 journeys | Identity → account → transfer/step-up/retry/history, operational permissions/audit/fraud/outbox | Owners W3/W4/W5 theo report 07; chứng minh D1–D5 bằng evidence riêng |
| W8 recovery | Restart/rollback compatibility, DB loss trong môi trường cô lập, fix/redeploy/retest cùng revision mới | Hạ tầng/DB/owner module; không coi rollback marker probe là phục hồi tiền |

Mỗi lần ghi URL/environment, giờ UTC+07, revision/digest/config version, expected/actual/status, người thực hiện/reviewer, lỗi và lần retest. Lưu dữ liệu synthetic và output đã che secret trong report 08; không cần tạo evidence/ riêng theo yêu cầu owner. Cột Cloud hiện chưa có kết quả thực chạy.

## 6. Trách nhiệm và policy còn mở

Phương phụ trách common artifact/config/primitive/error/context/observability và hỗ trợ integration. Đạt phụ trách identity/customer/account/admin, Vũ transfer/limit và điều phối, Thịnh audit/fraud/notification và phần admin; Phương còn là owner ledger. Theo tài liệu 11 §8, chưa ai được giao kỹ thuật hạ tầng; Vũ theo dõi quyết định nhưng không mặc nhiên provisioning. Không tự gửi thông báo/nhận sign-off thay người khác.

Các threshold lockout/OTP/session/rate/timeout còn Đề xuất phải giữ nhãn và được owner chốt trước release; rotation OQ-15, hash-version wording của audit, retention/rotation log/metric, source IP/proxy trust, distributed quotas và full C3 fallback vẫn có trạng thái trong reports 04–07. Quyết định phạm vi hiện tại: chỉ candidate common và W6 readiness cục bộ; không phát hành Cloud trong khi các gate liên quan còn thiếu. Tài liệu này không tự phê duyệt deferred scope hoặc nghiệm thu toàn hệ thống.
