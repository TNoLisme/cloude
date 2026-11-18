# Module 09: `notification` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-09`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.notification`
> **Owner chính:** **Thịnh** (Phối hợp: Đạt — `identity` & `customer`, Vũ — `transfer`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 – Tuần 5 (W4–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-041..043, NFR-008, AC-025), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7.3), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.5, §10), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.4, §10.4, Phụ lục A.10, A.13, B.6), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.3), [[DECISIONS.md]] (`DEC-COM-001`, `DEC-ID-006`, `DEC-TRF-011`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `notification` chịu trách nhiệm tiếp nhận yêu cầu thông báo kết quả giao dịch (`TRANSFER_RESULT`) và cảnh báo bảo mật (`SECURITY_ALERT`) theo mẫu **Transactional Outbox (`T2` enqueue $\rightarrow$ `T3` async worker delivery)**, đồng thời cung cấp cổng gửi mã OTP đồng bộ sau khi commit (`OtpGateway`) cho module `identity`. Thiết kế này đảm bảo:
1. Không bao giờ gửi thông báo biến động số dư giả nếu giao dịch `T2` bị rollback.
2. Lỗi hoặc độ trễ của cổng gửi thông báo bên ngoài (SMS/Email/In-App simulator) **không bao giờ** làm đảo ngược hay làm thất bại một giao dịch chuyển tiền đã `COMPLETED` (`NFR-008`, `AC-025`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `notification` sở hữu độc quyền bảng **`banking.notification`** (`04. Database design & analysis.md` §4.7.3, migration `V10` theo `DEC-COM-001` & `DEC-TRF-011`):

| Cột chính | Kiểu & Ràng buộc | Quy tắc Vận hành Outbox |
|---|---|---|
| `notification_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh bản ghi thông báo trong hàng đợi outbox. |
| `customer_id` | `BIGINT NOT NULL FK -> customer` | Khách hàng nhận thông báo (`04` §4.7.3). |
| `transfer_id` | `BIGINT NULL FK -> transfer` | Giao dịch chuyển tiền liên quan (nếu là thông báo kết quả chuyển tiền). |
| `auth_challenge_id` | `BIGINT NULL FK -> auth_challenge` | Liên kết truy vết tới challenge xác thực (không lưu mã OTP bản rõ trong bảng này). |
| `notification_type` | `VARCHAR(24) NOT NULL CHECK IN ('TRANSFER_RESULT','OTP_DELIVERY','SECURITY_ALERT')` | Phân loại nghiệp vụ của thông báo (`NotificationType`). |
| `channel` | `VARCHAR(16) NOT NULL CHECK IN ('IN_APP','EMAIL','SMS')` | Kênh gửi thông báo (`NotificationChannel`). |
| `template_code` | `VARCHAR(32) NOT NULL` | Mã mẫu thông báo (VD: `TRANSFER_DEBIT_ALERT`, `TRANSFER_CREDIT_ALERT`). |
| `payload_masked` | `JSONB NOT NULL` | Dữ liệu thông báo **đã được che dấu PII** (`reference`, `amount`, `currency`, `maskedAccountNo`, `balanceAfter`), kiểm tra nghiêm ngặt qua `NotificationPayloadValidator`. |
| `status` | `VARCHAR(16) NOT NULL CHECK IN ('PENDING','SENT','FAILED','RETRYING','CANCELLED')` | Trạng thái gửi thông báo (`NotificationStatus`, mặc định `PENDING`). |
| `retry_count`, `next_retry_at` | `SMALLINT NOT NULL DEFAULT 0`, `TIMESTAMPTZ` | Số lần đã thử gửi (tối đa **5 lần** theo `DEC-TRF-011`) và thời điểm thử gửi tiếp theo. |
| `claim_token`, `locked_until`, `claim_version` | `VARCHAR(64)`, `TIMESTAMPTZ`, `BIGINT` | Quản lý lease (`30 giây`) và fencing token khi worker đang xử lý, ngăn chặn stale worker ghi đè kết quả. |
| `failure_code`, `correlation_id`, `sent_at`, `created_at`, `updated_at` | — | Mã lỗi nội bộ an toàn (`GATEWAY_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DELIVERY_EXHAUSTED`) và mã truy vết đầu-cuối. |

- **Unique Constraint chống nhân bản thông báo (`DEC-TRF-011`):** `UNIQUE (transfer_id, customer_id, notification_type, channel)`.

---

## 3. Hợp đồng Giao tiếp Nội bộ & Luồng Outbox Worker (`T2`, `T3` & `OtpGateway`)

### 3.1. Ports cung cấp cho các module khác gọi (`notification.api`)
- **`NotificationOutboxPort.enqueueTransferNotificationsInT2(List<TransferNotice> notices)`** *(Được gọi bởi `transfer` trong Pha `T2`)*:
  - Chạy bắt buộc bên trong giao dịch `T2` (`@Transactional(propagation = Propagation.MANDATORY)`).
  - Kiểm tra tính hợp lệ và PII allowlist của từng `TransferNotice` qua `NotificationPayloadValidator`, sau đó chèn vào `banking.notification` với `status = 'PENDING'`.
- **`OtpGateway.sendOtp(...)`** (`com.simulatedbank.banking.notification.api.OtpGateway` — *Được gọi bởi `identity` sau khi commit `T0` theo `DEC-ID-006`*):
  - Nhận mảng `char[]` OTP tạm thời trong bộ nhớ và gửi đồng bộ bên ngoài giao dịch DB với thời hạn chờ hữu hạn (`5 giây`), trả về kết quả chuẩn hóa (`ACCEPTED` / lỗi gửi) mà không bao giờ ghi mã OTP ra DB hay log.

### 3.2. Tiến trình Gửi Bất đồng bộ ở Pha `T3` (`NotificationOutboxWorker` — `DEC-TRF-011`)
1. **Bước 1 — Claim lô thông báo bằng `FOR UPDATE SKIP LOCKED` (`REQUIRES_NEW`):**
   - Quét định kỳ mỗi **2 giây**, chọn tối đa **50 bản ghi** đến hạn (`status IN ('PENDING','RETRYING')` và `next_retry_at <= clock_timestamp()` hoặc lease `locked_until` đã hết hạn).
   - Tăng `retry_count`, gán `claim_token` mới và `locked_until = clock_timestamp() + 30s`, rồi commit giao dịch ngắn. Nếu `retry_count` vượt quá giới hạn tối đa (`5 lần`), chuyển ngay sang `status = 'FAILED'` (`failure_code = 'DELIVERY_EXHAUSTED'`) để chặn vòng lặp crash.
2. **Bước 2 — Giải mã địa chỉ nhận & Gọi Gateway bên ngoài DB Transaction (`NOT_SUPPORTED`):**
   - Kiểm tra đảm bảo không có giao dịch DB nào đang mở trên luồng hiện tại; giải mã địa chỉ nhận tạm thời qua `CustomerQuery.resolveNotificationDestination` (`DEC-TRF-011`) và gọi `NotificationDeliveryPort` (`SimulatedNotificationGateway`, timeout `5 giây`).
3. **Bước 3 — Cập nhật kết quả gửi có điều kiện theo `claim_token` (`REQUIRES_NEW`):**
   - Nếu thành công: cập nhật `status = 'SENT'`, `sent_at = clock_timestamp()`, xóa lease khi `claim_token` còn khớp.
   - Nếu thất bại: tính thời gian lùi hàm mũ qua `NotificationRetryPolicy` ($2^{k-1} \times \text{baseBackoff}$), cập nhật `status = 'RETRYING'` (hoặc `FAILED` khi chạm ngưỡng tối đa) kèm `failure_code` an toàn.

---

## 4. Danh sách Công việc & Trạng thái Triển khai

1. [x] Hiện thực domain & validator (`TransferNotice`, `NotificationItem`, `NotificationChannel`, `NotificationType`, `NotificationStatus`, `NotificationPayloadValidator` với 44 unit tests chặn PII/secret, `NotificationRetryPolicy`) (`BE-NOT-01`).
2. [x] Hiện thực tầng persistence & ports (`NotificationOutboxPort`, `OtpGateway`, `NotificationPersistencePort`, `NotificationDeliveryPort`, `NotificationEntity`, `NotificationJpaRepository`, `NotificationMapper`, `NotificationJdbcRepository`) (`BE-NOT-01`).
3. [x] Hiện thực `NotificationOutboxService` (`MANDATORY`), `NotificationOutboxWorker` (`SKIP LOCKED` + lease recovery + fencing token + active-transaction guard), `NotificationOutboxMetrics` và `SimulatedNotificationGateway` (chỉ bật khi opt-in rõ ràng) — tổng cộng **72 tests PASS** (unit + PostgreSQL 16 Testcontainers) (`BE-NOT-01`).
4. [ ] Đưa bảng `banking.notification` vào migration `V10` theo chuỗi `DEC-COM-001` và ghép nối runtime với `transfer` (`T2`), `identity` (`OtpGateway`) và `customer` (`resolveNotificationDestination`).

**Tài liệu chi tiết module `notification`:**
- [Chỉ mục Module Notification](../notification/README.md)
- [BE-NOT-01 — Transactional Outbox, Delivery & Simulator](../notification/BE-NOT-01-transactional-outbox.md) ([Báo cáo kiểm chứng](../notification/BE-NOT-01-verification-report.md))
