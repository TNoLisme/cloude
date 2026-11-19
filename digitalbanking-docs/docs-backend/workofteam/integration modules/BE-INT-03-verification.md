# BE-INT-03 — Kiểm chứng session, Customer và Account read

| Trường | Giá trị |
|---|---|
| Status | **Verified** cho lát đọc single-bank MVP theo DEC-INT-008/009 |
| Ngày | 19/11/2026, Asia/Ho_Chi_Minh |
| Scope | Spring Boot chạy Common, Identity, Customer, Account và Audit thật trên cùng PostgreSQL. OTP gateway test-only nhận mã để test gửi lại qua HTTP. Các ca cần lỗi đọc và lỗi audit dùng SQL trong container cô lập. |
| Requirements | FR-006, FR-008–012, FR-039, FR-049, AC-005/023/024/027 và INT03-S01–S03/C01–C03/T01–T03/A01 |
| Môi trường | Profile `test`, OpenJDK 21.0.1, Gradle 8.14.3, Docker 29.5.3, PostgreSQL 16 Testcontainers. Flyway validate chuỗi V1–V19, Hibernate validate schema. |
| Source revision | `dev2@ba3441cc035ed34c5153e28c10b7e86cb7011971` và thay đổi workspace BE-INT-03. [Manifest mã nguồn và OpenAPI](evidence/BE-INT-03/source-manifest.sha256). |
| Evidence | [Log Gradle, JUnit XML và tóm tắt theo run](evidence/BE-INT-03/results.tar.gz). |

## 1. Expected và actual

Các kết quả dưới đây trỏ tới 8 ca trong `SessionCustomerAccountReadPostgreSqlIntegrationTest`. Test gọi HTTP qua cổng ngẫu nhiên và dùng SQL độc lập để xác nhận trạng thái persisted.

| Vector | Expected | Actual |
|---|---|---|
| S01, C01 | MFA và refresh dùng cùng mapping server-side. Owner chỉ đọc profile/account của mình. | **PASS** — Customer ID khác user ID và khớp trong login, refresh và hồ sơ. Kiểm cookie và Bearer, no-store, correlation, tên owner, phone/email đã mask, account last4, tiền dạng chuỗi và `asOf`. Account LOCKED trả owner ACTIVE. Checksum Customer/Account và số dòng bảng nghiệp vụ giữ nguyên. |
| S02 | INACTIVE giữ ID/status. Customer thiếu mapping trả null và self 404. Staff ID null. Lỗi đọc trong Identity trả 503. | **PASS** — Kiểm cả đăng nhập và refresh cho các kiểu mapping. Account có owner INACTIVE vẫn đọc được. Khi PostgreSQL từ chối truy vấn Customer, request đã đăng nhập và xác minh MFA trả 503. Session mới chưa được tạo và challenge vẫn SENT. Khôi phục cột rồi retry cùng challenge thành công. |
| S03 | Thiếu/sai/expired/logout/user disabled → 401. Thu hồi role có hiệu lực lần đọc sau. CSRF đổi khi refresh. | **PASS** — Kiểm từng trường hợp trên session PostgreSQL. CSRF cũ bị từ chối, token mới logout thành công. Xóa role staff rồi gọi cùng route và cùng session nhận 403 có C3. |
| C02, C03 | Foreign account → 403 và C3. Vắng → 404. ID sai hoặc vượt BIGINT → 400. Input client không đổi scope. | **PASS** — Headers/query giả không đổi profile hay tập account; foreign account bị từ chối và record có đúng actor user ID, target và trace. Response/log không chứa PII kiểm thử. Ca snapshot mixed hoặc chỉ có permission được đưa trực tiếp vào service thật trên PostgreSQL để kiểm lớp tái phân quyền. Target vắng vẫn 403 và có C3 trước lookup. |
| T01 | OPERATIONS/ADMIN được đọc profile đã mask. AUDITOR, CUSTOMER, staff thiếu quyền bị từ chối. | **PASS** — Tên, phone, email được mask cho hai role hợp lệ. AUDITOR nhận 403 và C3. Caller thiếu quyền nhận 403 cho cả ID có và ID vắng. Staff hợp lệ nhận 404 cho ID vắng và 400 cho ID tràn BIGINT. |
| T02, T03 | Staff có ACCOUNT_READ đọc detail/list. Phân biệt Customer vắng và customer chưa có account. Filter sai → 400. | **PASS** — OPERATIONS/ADMIN/AUDITOR đọc Account đã mask. Customer bị chặn ở staff list. Customer vắng trả 404 và Customer có hồ sơ nhưng chưa có tài khoản trả 200 với `items=[]`. Account vắng trả 404. Tiêm lỗi SELECT Customer với staff trả 500 an toàn, thay vì empty/404. |
| A01 | Deny ở chain và service được ghi C3; lỗi ghi giữ 403 và có log/metric. 401/404/200 thường không ghi deny. | **PASS** — SQL xác nhận actor/role, entity/ID, outcome REJECTED, correlation/request ID và state chỉ có mã lỗi. CHECK tạm chặn hai lượt append; vẫn nhận hai HTTP 403 và hai fallback log đã mask actor, metric tăng 2, không thêm audit row. Gỡ CHECK rồi thao tác tương tự ghi được audit. Các ca 401/404/200 kiểm deny count theo từng nhóm. |

## 2. Đối chiếu API

| Hợp đồng | Kết quả đối chiếu |
|---|---|
| AuthResponse sau MFA và `GET /auth/session` | `user.customerId` dạng ID string hoặc null theo Modules contract §5.1. `displayName` hiện dùng username. GET refresh trả `deviceTrusted=false` theo logic Identity hiện hành, tách khỏi binding Customer. |
| `GET /customers/me` và `GET /admin/customers/{customerId}` | Cùng shape CustomerProfile. Owner nhận fullName và liên hệ đã mask. Staff nhận cả ba field đã mask. API-ADM-01 theo permission seed, AUDITOR thiếu CUSTOMER_READ. |
| `GET /accounts` và `GET /admin/customers/{customerId}/accounts` | AccountsResponse có items và asOf. OpenAPI ghi status filter của staff list và 400/404 của self list. |
| `GET /accounts/{accountId}` | Owner object runtime gồm `customerId` và `status` từ Customer. OpenAPI release vẫn yêu cầu `owner.fullName`. Deviation được chấp nhận trong BE-INT-03 theo DEC-INT-008. Khi nghiệm thu API release, cần provider-owned masked view cho trường này. |

## 3. Lượt chạy

| Lượt | Actual |
|---|---|
| Compile ban đầu và retry | Test Common có tên lambda trùng biến cục bộ. Sau đổi tên, compile và test classes exit 0. Lệnh retry gọi wrapper từ sai thư mục exit 127 trước khi chạy Gradle. |
| HTTP 1 | Compile test lỗi package OTP gateway. Import đúng `notification.api.OtpGateway`. |
| HTTP 2 | 8 ca dừng ở fixture. Các lệnh seed phải commit độc lập và danh sách Flyway phải đếm migration có version. |
| HTTP 3 | 7/8 PASS. PostgreSQL từ chối hai bản ghi audit, làm lộ số đếm lỗi bị nhân đôi do rollback thoát khỏi proxy. |
| HTTP 4 và Audit | 43/43 PASS gồm 8 ca BE-INT-03, 7 Audit persistence, 7 JSON log và 21 ArchUnit. Ranh giới transaction C3 bắt lỗi sau rollback và ghi log fallback có cấu trúc. |
| Hồi quy liên quan | 143/152 PASS. 9 ca setup Account Read còn xóa user đã được Audit tham chiếu. Fixture dùng username duy nhất và giữ user cũ. |
| Chạy lại Account Read và BE-INT-03 | **39/39 PASS**, exit 0 trong 38s. Account Read 10/10, ma trận BE-INT-03 8/8, ArchUnit 21/21. Đối chiếu các ca hồi quy chưa đổi ở lượt trước cho kết quả 152/152 ca riêng biệt đều đạt. |

### Lệnh tái lập

Chạy từ `digitalbanking-backend`. Gradle đóng gói bootJar trước khi chạy các class được chọn. Hồi quy chọn controller, service, encoder, Audit và luồng FIDO liên quan trực tiếp. Lượt cuối thu hẹp về fixture Account Read và ma trận mục tiêu theo DEC-INT-007.

```sh
export DOCKER_HOST="$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

./gradlew test --offline --no-daemon --max-workers=1 --console=plain \
  --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest' \
  --tests '*AccountAccessServiceTest' \
  --tests '*AccountReadPostgreSqlIntegrationTest' \
  --tests '*AccountControllerPostgreSqlIntegrationTest' \
  --tests '*AdminCustomerAccountsControllerPostgreSqlIntegrationTest' \
  --tests '*AccountSecurityLogAuditAdapterTest' \
  --tests '*CustomerProfileServiceTest' \
  --tests '*StaffCustomerDetailServiceTest' \
  --tests '*CustomerProfileControllerPostgreSqlIntegrationTest' \
  --tests '*CustomerAdminDetailControllerPostgreSqlIntegrationTest' \
  --tests '*CustomerSecurityLogAuditAdapterTest' \
  --tests '*SecurityAccessDeniedAuditorTest' \
  --tests '*SafeJsonLogEncoderTest' \
  --tests '*SecurityAuditLogEncoderTest' \
  --tests '*AuditModulePostgreSqlIntegrationTest' \
  --tests '*FidoLoginHttpIntegrationTest.validAssertionConsumesChallengeAdvancesCounterAndAuthenticatesRealSession'

./gradlew test --offline --no-daemon --max-workers=1 --console=plain \
  --tests '*AccountReadPostgreSqlIntegrationTest' \
  --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest'
```

## 4. Giới hạn

Môi trường dùng fixture tổng hợp và giả lập transport OTP. Snapshot mixed được tạo trong test vì authority reader thật đã cô lập role theo user type. Fault injection chọn lỗi truy vấn Customer và lỗi append Audit trên DB dùng một lần. Kết quả áp dụng cho lát đọc và quyền single-bank MVP. G0, G2, G3, nghiệm thu chuyển tiền và OTP delivery thực tế có tiêu chí riêng.
