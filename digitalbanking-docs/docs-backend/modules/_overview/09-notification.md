# Module 09: `notification` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-09`
> **Module:** `com.simulatedbank.banking.notification`
> **Owner chính:** **Thịnh** (Phối hợp: Đạt — `identity`, Vũ — `transfer`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 – Tuần 5 (W4–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-041..043, NFR-008, AC-025), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.9), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.2, §10), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.10, §10.4, §A.10, §A.13, §B.6)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `notification` chịu trách nhiệm tiếp nhận yêu cầu thông báo kết quả giao dịch (biến động số dư cho người gửi/người nhận) và cảnh báo bảo mật theo mẫu **Transactional Outbox (`T2` enqueue $\rightarrow$ `T3` async worker delivery)**. Thiết kế này đảm bảo:
1. Không bao giờ gửi thông báo biến động số dư giả nếu giao dịch `T2` bị rollback.
2. Lỗi hoặc độ trễ của cổng gửi thông báo bên ngoài (SMS/Email/Push simulator) **không bao giờ** làm đảo ngược hay làm thất bại một giao dịch chuyển tiền đã `COMPLETED` (`NFR-008`, `AC-025`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `notification` sở hữu độc quyền bảng **`banking.notification`**:

| Cột chính | Kiểu & Ràng buộc | Quy tắc Vận hành Outbox |
|---|---|---|
| `notification_id` | `UUID` / `BIGINT` (`PK`) | Định danh bản ghi thông báo trong hàng đợi outbox. |
| `user_id` | `NOT NULL FK -> app_user` | Người dùng nhận thông báo. |
| `transfer_id` | `NULL FK -> transfer` | Giao dịch chuyển tiền liên quan (nếu là thông báo biến động số dư). |
| `channel` | `CHECK IN ('IN_APP','EMAIL','SMS')` | Kênh gửi thông báo. |
| `template_code` | `VARCHAR(50) NOT NULL` | Mã mẫu thông báo (VD: `TRANSFER_DEBIT_ALERT`, `TRANSFER_CREDIT_ALERT`). |
| `payload` | `JSONB NOT NULL` | Dữ liệu thông báo **đã được che dấu PII** (`reference`, `amount`, `currency`, `maskedAccountNo`, `balanceAfter`). |
| `status` | `CHECK IN ('PENDING','PROCESSING','SENT','FAILED')` | Trạng thái gửi thông báo. |
| `attempt_count`, `max_attempts` | `SMALLINT` (mặc định `max = 5`) | Số lần đã thử gửi và giới hạn thử tối đa. |
| `next_retry_at` | `TIMESTAMPTZ NOT NULL` | Thời điểm được phép lấy ra thử gửi lần tiếp theo. |
| `locked_by`, `locked_until` | `VARCHAR`, `TIMESTAMPTZ` | Quản lý lease khi worker đang giữ xử lý (`PROCESSING`), chống kẹt khi worker crash. |
| `correlation_id`, `last_error`, `created_at`, `sent_at` | — | Giữ nguyên `correlation_id` gốc từ giao dịch chuyển tiền để truy vết đầu-cuối. |

- **Unique Constraint chống nhân bản thông báo:** `UNIQUE (transfer_id, user_id, template_code)`.

---

## 3. Hợp đồng Giao tiếp Nội bộ & Luồng Outbox Worker (`T2` & `T3`)

### 3.1. Port cung cấp cho `transfer` gọi trong Pha `T2` (`notification.api`)
- **`NotificationOutboxPort.enqueueTransferNotificationsInT2(transfer, sourceAccount, destAccount)`:**
  - Chạy bên trong transaction `T2` (`Propagation.MANDATORY`) ngay sau khi ghi sổ cái thành công.
  - Chèn các bản ghi thông báo với `status = 'PENDING'`, `next_retry_at = clock_timestamp()` và `ON CONFLICT DO NOTHING` (`design/02` Pattern A.10).

### 3.2. Tiến trình Gửi Bất đồng bộ ở Pha `T3` (`NotificationOutboxWorker` — `design/02` Pattern A.13, B.6)
1. **Bước 1 — Claim lô thông báo bằng `FOR UPDATE SKIP LOCKED` (Transaction ngắn #1):**
   - Chọn tối đa `50` bản ghi có `(status = 'PENDING' AND next_retry_at <= now())` hoặc `(status = 'PROCESSING' AND locked_until <= now())`.
   - Cập nhật sang `status = 'PROCESSING'`, `locked_by = workerId`, `locked_until = now() + 30s` rồi đóng transaction.
2. **Bước 2 — Gọi Adapter gửi thông báo bên ngoài DB Transaction:**
   - Gọi `SimulatedNotificationGateway` (timeout `5 giây`) mà không giữ kết nối DB.
3. **Bước 3 — Cập nhật kết quả gửi (Transaction ngắn #2):**
   - Nếu thành công: cập nhật `status = 'SENT'`, `sent_at = now()`, xóa `locked_by`/`locked_until`.
   - Nếu thất bại: tăng `attempt_count = attempt_count + 1`. Nếu chưa vượt `max_attempts`, đặt lại `status = 'PENDING'` và lùi `next_retry_at` theo **Exponential Backoff** (`5s, 10s, 20s, 40s...`); nếu đã chạm `max_attempts`, chuyển sang `status = 'FAILED'` và phát metric cảnh báo vận hành (`R-CON-06`).

---

## 4. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `NotificationEntity`, `NotificationJdbcRepository` và `NotificationOutboxPort` (đảm bảo che dấu PII trong `payload` trước khi lưu).
2. [ ] Hiện thực `NotificationOutboxWorker` sử dụng `FOR UPDATE SKIP LOCKED` + cơ chế thu hồi lease hết hạn (`locked_until`).
3. [ ] Hiện thực `SimulatedNotificationAdapter` ghi log có cấu trúc kèm `correlation_id` để phục vụ chứng minh nghiệm thu.
4. [ ] Viết bộ test kiểm chứng `T-OUT-01` (cổng gửi thông báo lỗi không làm hỏng giao dịch chuyển tiền đã `COMPLETED` — `AC-025`) và `T-OUT-02` (worker bị dừng giữa chừng sau khi claim vẫn được thu hồi và gửi lại sau khi hết hạn lease).
