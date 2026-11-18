# BE-NOT-01 — Đặc tả Transactional Outbox, Tiến trình Gửi Thông báo và Simulator

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-NOTIFICATION-01` (`Task: BE-NOT-01`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai component (72 tests PASS — chờ ghép nối caller `T2`, `OtpGateway` & `CustomerQuery`)
> **Owner:** Thịnh · **Reviewer:** Vũ (`transfer`), Đạt (`identity`, `customer`), Phương (`common`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7.3), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.4, §10.4, Phụ lục A.10, A.13, B.6), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.3), [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-ID-006`, `DEC-TRF-011`)

---

## 1. Mục tiêu và Phạm vi

Thực hiện các yêu cầu `FR-041..043`, `AC-025`, `NFR-008`:
- Ghi nhận yêu cầu thông báo kết quả giao dịch (`TRANSFER_RESULT`) và cảnh báo bảo mật (`SECURITY_ALERT`) với dữ liệu đã được che dấu PII (`payload_masked`) vào bảng hàng đợi `banking.notification` ngay trong giao dịch `T2` (**Transactional Outbox**).
- Thực thi tiến trình gửi thông báo bất đồng bộ ở pha `T3` (`NotificationOutboxWorker`) sau khi `T2` đã commit, sử dụng cơ chế chiếm lô `FOR UPDATE SKIP LOCKED`, quản lý thời hạn thuê (`lease`), fencing token và thử lại theo hàm mũ có giới hạn (`at-least-once delivery`).
- Cung cấp cổng gửi mã OTP đồng bộ sau khi commit (`OtpGateway`) cho module `identity` (`DEC-ID-006`).
- Đảm bảo mọi lỗi hoặc sự cố của cổng gửi thông báo ở pha `T3` không bao giờ làm đảo ngược hay làm thất bại một giao dịch chuyển tiền đã `COMPLETED` (`AC-025`).

---

## 2. Tái sử dụng Nền tảng `common`

- **Ngữ cảnh truy vết:** Đọc `correlationId` từ `RequestContextHolder` khi xếp hàng trong `T2`, lưu vào cột `notification.correlation_id` và gắn lại vào `RequestContextHolder` trên luồng thực thi của worker `T3`.
- **Che dấu PII:** Kết hợp `PiiMaskingUtils` của `common` với bộ kiểm tra `NotificationPayloadValidator` để đảm bảo cột `payload_masked` chỉ chứa các trường đã che dấu (`reference`, `amount`, `currency`, `maskedAccountNo`, `balanceAfter`), tuyệt đối không chứa số tài khoản đầy đủ, SĐT, email, mật khẩu hay mã OTP.
- **Cấu hình vận hành (`AppProperties.Jobs` — `DEC-TRF-011`):**
  - `outbox-poll-interval-ms = 2000` (chu kỳ quét **2 giây**).
  - `outbox-batch-size = 50` (kích thước lô **50 bản ghi**).
  - `outbox-lease-seconds = 30` (thời hạn thuê **30 giây**).

---

## 3. Hợp đồng Giao tiếp Nội bộ & Cấu trúc Bảng `banking.notification`

### 3.1. Các Public Ports trong `com.simulatedbank.banking.notification.api`

1. **`NotificationOutboxPort`:**
   ```java
   public interface NotificationOutboxPort {
       void enqueueTransferNotificationsInT2(List<TransferNotice> notices);
   }
   ```
   - Chạy bắt buộc bên trong giao dịch `T2` của `transfer` với `@Transactional(propagation = Propagation.MANDATORY)`.
   - Kiểm tra từng `TransferNotice` qua `NotificationPayloadValidator` và chèn vào bảng `banking.notification` ở trạng thái `PENDING`. Tuyệt đối không gọi cổng gửi thông báo bên ngoài trong `T2`.

2. **`OtpGateway` (`DEC-ID-006`):**
   - Cung cấp phương thức gửi mã OTP đồng bộ bên ngoài giao dịch DB (sau khi `identity` đã commit `auth_challenge` ở trạng thái `CREATED`).
   - Mã OTP chỉ được truyền qua mảng `char[]` tạm thời trong bộ nhớ và xóa trắng ngay sau khi gửi; không bao giờ lưu mã OTP bản rõ vào bảng `banking.notification` hoặc ghi ra log.

### 3.2. Cấu trúc Bảng `banking.notification` (`04` §4.7.3, Migration `V10`, `DEC-TRF-011`)

| Cột | Kiểu & Ràng buộc | Mô tả |
|---|---|---|
| `notification_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Khóa chính bản ghi thông báo. |
| `customer_id` | `BIGINT NOT NULL FK -> banking.customer` | Khách hàng nhận thông báo. |
| `transfer_id` | `BIGINT NULL FK -> banking.transfer` | Giao dịch chuyển tiền liên quan (nullable). |
| `auth_challenge_id` | `BIGINT NULL FK -> banking.auth_challenge` | Challenge xác thực liên quan (nullable). |
| `notification_type` | `VARCHAR(24) NOT NULL CHECK IN ('TRANSFER_RESULT','OTP_DELIVERY','SECURITY_ALERT')` | Loại thông báo (`NotificationType`). |
| `channel` | `VARCHAR(16) NOT NULL CHECK IN ('IN_APP','EMAIL','SMS')` | Kênh gửi (`NotificationChannel`). |
| `template_code` | `VARCHAR(32) NOT NULL` | Mã mẫu thông báo (không được chứa email/SĐT hay PII). |
| `payload_masked` | `JSONB NOT NULL` | Nội dung thông báo đã che dấu PII. |
| `status` | `VARCHAR(16) NOT NULL CHECK IN ('PENDING','SENT','FAILED','RETRYING','CANCELLED')` | Trạng thái xử lý (`NotificationStatus`, mặc định `PENDING`). |
| `retry_count`, `next_retry_at` | `SMALLINT NOT NULL DEFAULT 0`, `TIMESTAMPTZ` | Số lần đã thử (tối đa **5 lần** theo `DEC-TRF-011`) và thời điểm thử lại tiếp theo. |
| `claim_token`, `locked_until`, `claim_version` | `VARCHAR(64)`, `TIMESTAMPTZ`, `BIGINT` | Token hàng rào (`fencing token`), thời điểm hết hạn lease (`30 giây`) và phiên bản claim. |
| `failure_code`, `sent_at` | `VARCHAR(64)`, `TIMESTAMPTZ` | Mã lỗi nội bộ an toàn (`GATEWAY_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DELIVERY_EXHAUSTED`) và thời điểm gửi thành công. |
| `correlation_id`, `created_at`, `updated_at` | `VARCHAR(64)`, `TIMESTAMPTZ` | Mã truy vết và các mốc thời gian. |

- **Ràng buộc chống nhân bản thông báo (`DEC-TRF-011`):** `UNIQUE (transfer_id, customer_id, notification_type, channel)`.

---

## 4. Ngữ nghĩa Gửi Thông báo và Tiến trình Worker `T3` (`NotificationOutboxWorker`)

1. **Bảo vệ ranh giới giao dịch của Worker:**
   - `NotificationOutboxWorker.processBatch` và `deliverItem` kiểm tra đảm bảo luồng gọi hiện tại **không nằm trong bất kỳ giao dịch DB nào đang mở** (ném `IllegalStateException` fail-fast nếu bị gọi nhầm từ trong giao dịch đang giữ khóa).
2. **Pha 1 — Chiếm lô thông báo (`claimBatch` — `Propagation.REQUIRES_NEW`):**
   - Chọn tối đa `50` bản ghi đến hạn bằng `FOR UPDATE SKIP LOCKED`, tăng `retry_count`, gán `claim_token` mới cùng `locked_until = clock_timestamp() + 30s` và commit ngay.
   - Nếu một bản ghi bị worker crash lặp lại nhiều lần khiến `retry_count > maxAttempts` (`5 lần`), worker chuyển ngay bản ghi đó sang trạng thái kết thúc `FAILED` (`failure_code = 'DELIVERY_EXHAUSTED'`) mà không gọi gateway, ngăn chặn vòng lặp độc hại (`poison pill`).
3. **Pha 2 — Giải mã địa chỉ nhận & Gửi qua Gateway (`Propagation.NOT_SUPPORTED`):**
   - Giải mã số điện thoại/email thực tế trong bộ nhớ tạm thời qua `CustomerQuery.resolveNotificationDestination` (`DEC-TRF-011`) và gọi `NotificationDeliveryPort.send` bên ngoài giao dịch DB (không giữ kết nối hay khóa DB trong suốt cuộc gọi mạng).
4. **Pha 3 — Cập nhật kết quả có điều kiện theo `claim_token` (`Propagation.REQUIRES_NEW`):**
   - Câu lệnh `markSent` và `markRetryOrFailed` luôn kèm điều kiện `WHERE notification_id = ? AND claim_token = ?`. Nếu một worker cũ bị treo quá 30 giây và bản ghi đã được worker khác thu hồi lease (`reclaim`), kết quả trễ của worker cũ sẽ cập nhật `0` dòng và không thể ghi đè trạng thái mới.
   - Khi gửi thất bại và chưa chạm ngưỡng tối đa, `NotificationRetryPolicy` tính thời gian chờ tiếp theo theo công thức hàm mũ tất định $2^{\min(\max(0, \text{retryCount}-1), 6)} \times \text{baseBackoff}$ và đặt `status = 'RETRYING'`.

---

## 5. Quy ước Kích hoạt Simulator (`Explicit Opt-In`)

- `SimulatedNotificationGateway` chỉ được đăng ký khi bật tường minh qua profile `simulator` hoặc thuộc tính `app.notification.simulator.enabled=true` (và khi chưa có bean `NotificationDeliveryPort` thật).
- `NotificationOutboxWorker` được gắn `@ConditionalOnBean(NotificationDeliveryPort.class)`: nếu môi trường chưa cấu hình cổng gửi thông báo, worker sẽ không tự động chạy ngầm.

---

## 6. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.4` | 11–14/11/2026 | Khởi tạo đặc tả Transactional Outbox, cơ chế lease/fencing token và đối chiếu schema `04` §4.7.3. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt, đồng bộ đầy đủ quyết định `DEC-TRF-011` (`UNIQUE (transfer_id, customer_id, notification_type, channel)`, lease 30s, poll 2s, batch 50, max 5 retries, `resolveNotificationDestination`), `DEC-ID-006` (`OtpGateway`) và chuỗi migration `V10` (`DEC-COM-001`). |
