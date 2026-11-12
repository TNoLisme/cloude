# Module common — Hướng dẫn sử dụng và tra cứu

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-COM-GUIDE
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Owner:** Phương; reviewer/sign-off: chưa review
> **Trạng thái:** Nền tảng đã triển khai và kiểm chứng local; tích hợp nghiệp vụ/Cloud còn Blocked

`common` cung cấp cấu hình/thời gian, request context, HTTP security, crypto/JCS/masking, lỗi và observability cho các module backend. Thư mục này chứa **tài liệu**; [Java source](../../../../digitalbanking-backend/src/main/java/com/simulatedbank/banking/common) nằm trong backend. Common không sở hữu bảng nghiệp vụ, không mở transaction tiền và không ghi audit thay owner.

## 1. Bắt đầu từ đâu

| Bạn cần | Đọc tại |
|---|---|
| Biết việc đã làm, việc còn thiếu và owner tiếp tục | [Final report — trạng thái module](report/BE-COM-FINAL-REPORT.md) |
| Class/API hiện có, ranh giới, HTTP/SQLSTATE, luồng xử lý, test mapping | [TKCT](TKCT.md) |
| Nhận task, xem AC/dependency và quy tắc đóng việc | [Task tracker](tasks/README.md) |
| Bằng chứng runtime theo task/revision/môi trường | Các verification report được liên kết trong tracker/final report |
| Tích hợp vào Java code | Mục 3 bên dưới → phần TKCT tương ứng → [backend usage](../../../../digitalbanking-backend/README.md) |
| Biến môi trường/profile/secret references | [Deployment configuration](../../deployment/configuration.md) |
| Demo, start/restart/rollback và kiểm W7/W8 | [Common readiness/runbook](../../deployment/common-readiness.md), [deployment guide](../../deployment/README.md) |
| Viết verification mới | [Mẫu report](report/VERIFICATION-TEMPLATE.md); theo yêu cầu owner chỉ report, không tạo evidence/ mới |

Người mới nên đọc README → final report → phần TKCT đúng nhu cầu → task và verification liên quan. Các tham chiếu lịch sử tới “common README §1–§7” nói về bản thiết kế nay nằm ở **TKCT.md**; revision và kết quả lịch sử vẫn theo report gốc.

## 2. Nguồn chuẩn và trạng thái

Ưu tiên **docs-common/01–11 → docs-common/design → docs-backend**; không suy ưu tiên giữa 01–11 từ số thứ tự. Xem [README docs](../../../README.md), [docs-common](../../../docs-common/README.md), [backend docs](../../README.md).

| Nội dung | Nguồn |
|---|---|
| Hành vi, AC/NFR | [03. SRS](../../../docs-common/03.%20SRS.md) |
| Schema/constraint/ownership | [04. Database](../../../docs-common/04.%20Database%20design%20%26%20analysis.md) |
| Module/transaction boundary | [06. Architecture](../../../docs-common/06.%20System%20architecture.md) |
| HTTP, lỗi, idempotency, step-up, masking | [07. API contracts](../../../docs-common/07.%20API%20contracts.md); [OpenAPI](../../../openapi/digital-banking-v1.yaml) đồng bộ theo 07 |
| Vận hành, kiểm thử và owner | [08. Deployment](../../../docs-common/08.%20Deployment%20infrastructure.md), [09. Test plan](../../../docs-common/09.%20Test%20plan.md), [11. Resource & Staffing](../../../docs-common/11.%20Resource%20%26%20Staffing%20Plan.md) |

README hướng dẫn sử dụng; TKCT mô tả thiết kế/implementation; task quy định AC; verification ghi kết quả thực; final report tổng hợp state. Không lấy report làm đặc tả mới.

Hiện **5/8 task implementation Done** trong phạm vi công bố (01–04, 06), **3/8 Blocked** (05, 07, 08); chưa sign-off toàn module. Code/test pass hoặc Done local không chứng minh consumer thật, Cloud hay toàn D1–D5 đã nghiệm thu. Các policy Đề xuất/Open giữ trạng thái nguồn.

## 3. Sử dụng trong module của bạn

### 3.1. Cấu hình và thời gian

Inject `AppProperties`, `Clock` UTC và bean `businessZoneId`; không copy defaults. Dùng `clock.instant()` cho timestamp/TTL; business date theo nguồn và transaction boundary của owner. Các nhóm transfer/fraud/jobs trong AppProperties là cấu hình, không phải rule/job đã được common thực thi. Xem [TKCT §2.1](TKCT.md#21-gói-config).

Demo dùng [application-demo.yml](../../../../digitalbanking-backend/src/main/resources/application-demo.yml): Secure=true, CORS origin tường minh và external DB/AES/HMAC secrets. Không ghép profile local/test vào demo hoặc lấy Compose local/.env.example làm cấu hình Cloud. Rotation nhiều khóa chưa hỗ trợ.

### 3.2. Context và job

Đọc `RequestContextHolder.current()` trong request đã qua filter. Correlation hợp lệ được giữ, requestId do server sinh mới; không đặt PII/secret trong trace ID, không đăng ký thêm filter hoặc sinh ID mới giữa flow.

Capture trước enqueue và bind trên execution thread khi dùng worker của owner:

```java
RequestContext snapshot = RequestContextHolder.current().orElseThrow();
executor.execute(() -> {
    try (var scope = RequestContextHolder.bind(snapshot)) {
        consumer.process();
    }
});
```

Đây là mẫu sử dụng, không tạo executor/job production trong common. ThreadLocal không tự truyền sang worker; redispatch không bao phủ mọi callback. Owner copy IDs vào challenge/transfer/ledger/audit/notification và tạo requestId riêng cho job run. Xem [TKCT §2.2](TKCT.md#22-gói-context), [propagation](TKCT.md#52-propagation-và-job).

### 3.3. Crypto/JCS/masking

Inject `PiiCryptoService`, `HmacService`, `JcsCanonicalizer`; caller tự chuẩn hóa payload theo nguồn, gọi primitive rồi tự persist. `canonicalize(String rawJson)` nhận JSON còn giữ duplicate keys; không đưa qua JsonNode/Map trước strict parsing. Không copy fixture thành protocol audit/idempotency mới.

`PiiMaskingUtils` dùng trong mapper phù hợp quyền; masking không cấp quyền đọc dữ liệu, ngoài biên không fallback plaintext. HMAC/SHA không thay password hash; identity vẫn sở hữu Argon2id/bcrypt, OTP binding/TTL/attempts. Xem [TKCT §2.3](TKCT.md#23-gói-crypto), [crypto usage](../../../../digitalbanking-backend/README.md#crypto-canonicalization-and-masking).

### 3.4. Lỗi và transaction

Owner phân loại nghiệp vụ bằng `BankingBusinessException`/`ErrorCode`, chỉ truyền detail/extensions đã an toàn và đúng schema/masking. MVC advice dựng Problem; filter/security gọi `ProblemWriter`; success giữ DTO trực tiếp. Handler không retry, mở transaction, ghi audit hoặc quyết định outcome tiền.

`55P03` đơn độc không đủ kết luận in-progress; `23505` không mặc định conflict409. Owner xử lý rollback/retry/context đúng trước khi dựng HTTP. Xem [TKCT §2.4](TKCT.md#24-gói-error), [SQLSTATE](TKCT.md#44-exception--sqlstate-mapping) và nguồn 07 trước khi thêm code.

### 3.5. Identity/security

Identity implement bean `SessionAuthenticator`: xác minh token/session/user/expiry/revoke, trả internal userId/customerId, authorities và CSRF hash. Customer ID phải từ identity đã xác thực, không từ header/body. Common không mint session, lưu CSRF hoặc kiểm ownership/maker≠checker thay owner.

Cookie mutation cần session-bound CSRF; Bearer hợp lệ miễn CSRF theo contract. Token buckets là process-local, transfer theo customer; username/pre-auth challenge quota và bảo vệ token lookup còn cần identity/edge. Xem [TKCT §2.5](TKCT.md#25-http-security-đã-triển-khai-identity-còn-thiếu), [verification 05](report/BE-COM-IMP-05-verification.md). Không đưa resolver test vào production để bỏ blocker.

### 3.6. Logs/metrics/health

Từ response `X-Correlation-ID`, tìm `correlation_id` trong JSON log, dùng `request_id` phân biệt từng lần xử lý. Encoder **bỏ arbitrary message/arguments/stack trace và unknown MDC**; không dựa vào `logger.info(...)` tùy ý để lưu audit/business event. Owner cần coded event an toàn và audit port đúng trách nhiệm; technical log không thay C1/C2/C3 audit.

Timer/histogram HTTP dùng route template, không gắn ID user/account/request/correlation/idempotency vào metric tags. `/actuator/health` là public probe tối giản; API health/metrics/Prometheus yêu cầu Admin/Auditor. Thiếu identity adapter thì authenticated production access chưa sẵn sàng. Xem [health](TKCT.md#46-health-và-operational-endpoints-đã-triển-khai), [observability](TKCT.md#62-logsmetricshealth).

## 4. Chạy và kiểm tra

Từ repository root, cần Java 21 và Docker cho Testcontainers:

```bash
cd digitalbanking-backend
./gradlew clean check --offline --console=plain
```

Bỏ --offline nếu cần tải dependency. Build bootJar chạy trước tests; packaged readiness chạy JVM riêng profile demo với PostgreSQL cô lập. Sau khi start Compose local theo backend README, probe mặc định là `http://localhost:18080/actuator/health`. Không chạy reset/shutdown DB test trên môi trường chung.

Lần runtime gần nhất trong [verification 08](report/BE-COM-IMP-08-verification.md): **203 tests, 0 fail/error/skip, 23 suites**. Đợt tài liệu này chỉ đối chiếu source/report/XML và links, không tuyên bố chạy lại suite hoặc sign-off security/Cloud.

## 5. Cập nhật tiếp

Mở đúng task/AC, đọc nguồn và dependency; chỉ thêm primitive có nhu cầu chung. Thiếu consumer thì ghi Blocked cùng owner/điều kiện tiếp tục. Khi code đổi, cập nhật TKCT/usage, chạy kiểm phù hợp, ghi verification rồi đồng bộ final report/tracker.

Giữ nguyên revision, test counts và reviewer của report lịch sử. Sửa đường dẫn do tách TKCT phải giữ kết quả/artifact gốc. DOC-01/02 là hồ sơ thiết kế, không phải runtime evidence.
