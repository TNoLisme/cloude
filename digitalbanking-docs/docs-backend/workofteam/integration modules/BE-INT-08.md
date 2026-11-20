# BE-INT-08 — OTP, Customer contact và Notification T3

> **Trạng thái · Verified** trên PostgreSQL với gateway HTTP mô phỏng. [Báo cáo](BE-INT-08-verification.md).
>
> Phạm vi · FR-041–043, AC-003/025/033, DEC-TRF-011, DEC-INT-017.

Identity gửi OTP qua Notification sau khi challenge đã commit. Customer cung cấp số đang hiệu lực, còn Notification gọi gateway ngoài transaction. OTP resend giữ số lần đã thử, xoay mã dưới khóa dòng và ghi Audit C2 trước khi gửi. Handler trả 202 hoặc 428 sau khi ACK của lần gửi hiện tại được ghi bền.

Transfer enqueue đúng một SMS kết quả cho người tạo lệnh trong T2. Worker đọc lại địa chỉ và consent ngay trước lần gửi, cập nhật kết quả theo token của lease. Lỗi gateway ở T3 dẫn tới retry hoặc FAILED cho thông báo. Kết quả chuyển tiền, ledger và audit giữ theo T2.

## Cấu hình chạy

- Dùng PostgreSQL canonical V1–V22 và hồ sơ ACTIVE có `phone_enc` tạo qua Customer PII protection. Gán `transfer_sms_allowed=true` cho dữ liệu tổng hợp đã đồng ý nhận SMS kết quả.
- Đặt `APP_NOTIFICATION_SMS_ENABLED=true`, `APP_NOTIFICATION_SMS_ENDPOINT=<endpoint riêng của simulator>`, và `APP_NOTIFICATION_WORKER_ENABLED=true` nếu muốn scheduler tự gửi outbox. Compose truyền ba biến này vào backend.
- Gateway nhận POST JSON với header `Idempotency-Key`. Bản tin OTP gồm `type=OTP`, `destination`, `otp`, `expiresAt` và `correlationId`. Kết quả chuyển tiền gồm `type=TRANSFER_RESULT`, `destination`, `template`, `payload` đã mask và `correlationId`. HTTP 2xx là ACK. Gateway mô phỏng chỉ dùng với dữ liệu tổng hợp và phải chuyển mã qua kênh nhận của môi trường đó. Đặt deadline và dedupe theo header nếu cần mô phỏng retry.
- Để demo staff đăng nhập, nạp `app.notification.sms.demo-staff-contacts[userId]` từ cấu hình server cho các staff demo đã provision, chỉ trong profile local/demo/test. Customer đang có hồ sơ vẫn luôn đi qua contact của Customer.
- V22 fail nếu DB lịch sử có trùng bộ khóa outbox. Đối chiếu các bản ghi trước khi nâng cấp, giữ nguyên dữ liệu nghiệp vụ cho đến khi người điều hành chốt cách xử lý. PostgreSQL test dùng dữ liệu tổng hợp.

BE-INT-08 kiểm ứng dụng với adapter HTTP và PostgreSQL; simulator ở đầu kia là dịch vụ thử nghiệm. Bật cấu hình Transfer mặc định và worker recovery/Tfail thuộc BE-INT-10. Nghiệm thu Admin maker/checker, Frontend và phát hành ở BE-INT-09/11/12.
