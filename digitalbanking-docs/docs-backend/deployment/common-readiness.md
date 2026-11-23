# Kiểm tra Sẵn sàng & Runbook Ứng dụng (`docs-backend/deployment/common-readiness.md`)

> **Dự án:** Digital Banking MVP (Single-Bank, Core Simulated)
> **Mã tài liệu:** `BE-COM-OPS-08`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành (Chuẩn bị W6 local readiness; chờ hoàn tất ghép tích hợp liên module trước khi phát hành Cloud W7/W8)
> **Owner:** Phương (`common`), phối hợp các Module Owners (`Đạt`, `Vũ`, `Thịnh`)
> **Báo cáo kiểm chứng:** [BE-COM-IMP-08-verification.md](../modules/common/report/BE-COM-IMP-08-verification.md)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2–§4, §8.9.1), [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§4–§7, §9, §11), [[digitalbanking-docs/docs-common/09. Test plan.md]] (§8–§10), [[digitalbanking-docs/docs-common/10 Master timelines.md]] (§4.6–4.8), [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] (§7–§10), [[DECISIONS.md]]

---

## 1. Điều kiện Phát hành (`Release Readiness Gates`)

Tài liệu này quy định danh mục kiểm tra sẵn sàng cấp ứng dụng (`Spring Boot executable JAR`), quản lý tham chiếu khóa bí mật và quy trình khởi động, khởi động lại, hoàn tác (`rollback`) cho môi trường `demo`/Cloud.

Trước khi mở cổng phát hành lên môi trường `demo`/Cloud (`W7`/`W8`), hệ thống bắt buộc đạt đủ các điều kiện sau:
1. Hoàn tất ghép tích hợp các module nghiệp vụ (`identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`) và vượt qua cổng kiểm thử chất lượng tích hợp ([BE-COM-IMP-07-verification.md](../modules/common/report/BE-COM-IMP-07-verification.md)).
2. Chuỗi Flyway migration chuẩn `V1`–`V10` (`DEC-COM-001`) chạy sạch từ cơ sở dữ liệu trống với `ddl-auto: validate`.
3. Cấp phát đầy đủ hạ tầng mạng riêng (`Private DB`), chứng chỉ HTTPS/TLS tại Nginx, và các tham chiếu secret độc lập cho `banking_app`, `banking_migrator`, khóa `AES-256-GCM` và khóa `HMAC-SHA256`.
4. Có gói artifact bất biến kèm mã băm `SHA-256` / image digest và bản sao lưu cơ sở dữ liệu đã kiểm chứng khôi phục (`restore drill`).

Khi bất kỳ điều kiện nào ở trên chưa hoàn tất, trạng thái phát hành Cloud giữ ở mức **`NO-GO`** (chỉ chạy kiểm thử sẵn sàng cục bộ `W6` với dữ liệu synthetic). Chi tiết kết quả thực chạy gói JAR xem tại [BE-COM-IMP-08-verification.md](../modules/common/report/BE-COM-IMP-08-verification.md).

---

## 2. Cấu hình Profile `demo` và Tham chiếu Bí mật (`Secret References`)

Trên môi trường `demo`/Cloud, chỉ kích hoạt duy nhất profile **`demo`** (`application.yml` + `application-demo.yml`). Tuyệt đối không gộp profile `local` hoặc `test` vào `SPRING_PROFILES_ACTIVE`, và không dùng các khóa mẫu trong `.env.example` hay `compose.yaml` local.

| Tên biến / thuộc tính | Quy tắc cấp phát và kiểm tra lúc khởi động |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Đặt tường minh là `demo`; không để fallback về `local`. |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | Trỏ tới endpoint PostgreSQL 16 trong mạng nội bộ; không nhúng mật khẩu vào chuỗi JDBC URL. |
| `DB_APP_USER` / `DB_APP_PASSWORD` | Tài khoản runtime `banking_app` (least-privilege, không có quyền DDL hoặc `UPDATE/DELETE/TRUNCATE` trên `ledger_entry` và `audit_log`); nạp mật khẩu từ secret store. |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | Tài khoản migration `banking_migrator` tách biệt hoàn toàn với `banking_app`. |
| `APP_PII_AES_KEY_BASE64` / `APP_HMAC_KEY_BASE64` | Hai khóa 256-bit (Base64 giải mã đúng 32 byte) độc lập nhau; `CryptoConfig` tự động từ chối khóa mẫu phát triển hoặc khóa lặp byte trên profile `demo`. |
| `APP_PII_AES_KEY_ID` | Định danh phiên bản khóa hiện hành (mặc định `k-v1`). |
| `app.crypto.previous-keys` | Giữ rỗng `[]` trong phạm vi MVP. |
| `APP_CORS_ALLOWED_ORIGINS` | Danh sách origin HTTPS của SPA tường minh; trên profile `demo` không có fallback `localhost` và từ chối khởi động nếu thiếu hoặc chứa `*`. |
| `app.security.cookie.secure` | Được cố định `true` trong `application-demo.yml` để đảm bảo cookie `DB_SESSION` chỉ truyền qua HTTPS. |
| `APP_ENVIRONMENT` / `APP_VERSION` | Nhãn môi trường và phiên bản gói build phục vụ structured JSON logging và `/api/v1/health`. |
| `APP_TIMEZONE` | Mặc định `Asia/Ho_Chi_Minh` (`ZoneId` hợp lệ); mọi timestamp lưu trữ và trả qua API vẫn ở chuẩn UTC. |
| `server.forward-headers-strategy` | Giữ `none` cho đến khi Nginx ingress được xác nhận đã chặn truy cập trực tiếp và ghi đè an toàn `X-Forwarded-*`. |

---

## 3. Đóng gói và Kiểm tra Sẵn sàng Cục bộ (`W6`)

Từ thư mục `digitalbanking-backend/` (yêu cầu Java 21 và Docker cho Testcontainers):

```bash
./gradlew clean check --console=plain
shasum -a 256 build/libs/digitalbanking-backend-0.1.0-SNAPSHOT.jar
```

Bài kiểm thử tích hợp `PackagedApplicationReadinessIntegrationTest` khởi chạy gói JAR vừa đóng gói trong một tiến trình JVM độc lập với `--spring.profiles.active=demo` cùng container PostgreSQL 16 và script phân quyền role thực tế để xác minh:
- Khởi động thành công, chạy Flyway migration bằng `banking_migrator` và kết nối runtime bằng `banking_app` (không có quyền superuser).
- Phản hồi `/actuator/health` trả về `200 {"status":"UP"}`.
- Khởi động lại (`restart`) trên cùng cơ sở dữ liệu giữ nguyên tính toàn vẹn.
- Từ chối khởi động (`fail-fast`) đối với 10 kịch bản cấu hình sai hoặc dùng khóa mẫu trên profile `demo` mà không làm lộ giá trị bí mật ra log.

---

## 4. Quy trình Khởi động, Khởi động lại và Hoàn tác (`Rollback`)

1. **Ghi nhận thông tin phát hành:** Lưu đường dẫn gói JAR/image, commit SHA, mã băm `SHA-256`, phiên bản cấu hình và phiên bản tham chiếu secret vào nhật ký triển khai.
2. **Kiểm tra cơ sở dữ liệu và bản sao lưu:** Xác nhận bản sao lưu (`pg_dump`) trước khi triển khai đã có checksum và đã qua kiểm tra khôi phục; xác nhận tách biệt hai tài khoản `banking_migrator` và `banking_app`.
3. **Khởi chạy ứng dụng:** Nạp biến môi trường từ secret store và chạy gói artifact bất biến:
   ```bash
   java -jar "$APP_JAR" --spring.profiles.active=demo
   ```
   Tuyệt đối không truyền mật khẩu hoặc khóa mật mã qua đối số dòng lệnh (`command-line flags`).
4. **Kiểm tra sức khỏe (`Health Probe`):** Chờ `/actuator/health` trả về `200 {"status":"UP"}`. Nếu trả về `503 {"status":"DOWN"}`, dừng định tuyến lưu lượng mới và kiểm tra kết nối DB, phân quyền hoặc cấu hình secret.
5. **Khởi động lại (`Graceful Restart`):** Ngắt nhận lưu lượng mới tại Nginx, gửi tín hiệu `SIGTERM` để ứng dụng đóng kết nối an toàn (`server.shutdown=graceful`), sau đó khởi chạy lại cùng gói artifact và chạy smoke test.
6. **Hoàn tác ứng dụng (`Application Rollback`):** Chuyển về gói artifact bất biến đã được nghiệm thu liền trước, kiểm tra tính tương thích schema Flyway và chạy lại bộ smoke test (`/actuator/health`, `/api/v1/health`, đăng nhập, tra cứu tài khoản, chuyển tiền mẫu và kiểm tra chuỗi băm audit).
7. **Bảo toàn cơ sở dữ liệu:** Không bao giờ sửa hoặc xóa các phiên bản Flyway migration đã áp dụng. Nếu phát sinh lỗi tương thích schema, dừng lưu lượng và thực hiện **forward compensating migration** đã được phê duyệt hoặc khôi phục từ bản sao lưu chuẩn.

---

## 5. Ma trận Kiểm chứng Triển khai Cloud (`W7`/`W8`)

| Hạng mục | Kỳ vọng & Bằng chứng nghiệm thu | Đầu mối phối hợp |
|---|---|---|
| **W7 — Deploy & Startup** | URL HTTPS hoạt động, ghi nhận deployment ID, commit SHA, image digest, kết quả Flyway `V1`–`V10` và `/actuator/health` `UP`. | Hạ tầng / DevOps + Phương (`common`) |
| **W7 — Negative Config Gate** | Cấu hình thiếu secret hoặc dùng khóa mẫu trên môi trường cô lập bị từ chối khởi động an toàn, không lộ secret trong log. | Phương (`common`) + Security |
| **W7 — Reverse Proxy & TLS** | TLS 1.2/1.3 hợp lệ; cổng Backend và PostgreSQL không public ra Internet; Nginx ghi đè an toàn `X-Forwarded-*`. | Hạ tầng / Security |
| **W7/W8 — HTTP Trace & Error Contract** | Phản hồi thành công và lỗi (`400`, `401`, `403`, `413`, `429`) trả đúng định dạng RFC 9457 Problem Details kèm `X-Correlation-ID` và security headers. | Phương (`common`) + Đạt (`identity`) |
| **W8 — Session Cookie & CSRF** | Cookie `DB_SESSION` có đủ `Secure; HttpOnly; SameSite=Strict; Path=/`; bắt buộc `X-CSRF-Token` cho các request thay đổi dữ liệu dùng cookie; thu hồi phiên tức thì khi đăng xuất hoặc đổi quyền. | Đạt (`identity`) + Thắng (`FE`) |
| **W8 — Observability & Health** | Truy vết `correlation_id` xuyên suốt từ HTTP header vào JSON log và các bảng DB; `/api/v1/health` và `/actuator/prometheus` chỉ cho phép `ADMIN`/`AUDITOR`. | Phương (`common`) + Thịnh (`audit`, `notification`) |
| **W8 — End-to-End Business Journeys (`D1–D5`)** | Chạy nghiệm thu đầy đủ các kịch bản `D1` (Concurrency), `D2` (Idempotency), `D3` (Recovery), `D4` (Security/Audit/Fraud) và `D5` (Load test 20 RPS × 10 phút). | Toàn bộ Module Owners (`Đạt`, `Vũ`, `Phương`, `Thịnh`) + QA |

---

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v1.0` | 12/11/2026 | Khởi tạo checklist sẵn sàng ứng dụng, quy tắc secret profile `demo`, quy trình start/restart/rollback và ma trận kiểm chứng `W7`/`W8` (`BE-COM-IMP-08`). |
| `v1.1` | 18/11/2026 | Chuẩn hóa khối metadata, đồng bộ chuỗi Flyway `V1`–`V10` (`DEC-COM-001`) và chuẩn hóa văn phong kỹ thuật. |
