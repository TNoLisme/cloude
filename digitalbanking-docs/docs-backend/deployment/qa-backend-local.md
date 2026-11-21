# QA backend local

Dùng DB riêng và dữ liệu tổng hợp. Từ `digitalbanking-backend`, nạp `APP_QA_PASSWORD` và `QA_SMS_TOKEN` qua môi trường. Mỗi giá trị cần ít nhất 16 ký tự.

```sh
docker compose -p banking-qa --env-file /dev/null -f compose.yaml -f compose.qa.yaml up --build --wait
```

API mặc định tại `http://localhost:18080/api/v1`. Có thể đổi `BACKEND_PORT`, `POSTGRES_PORT` và `QA_SMS_PORT` để chạy song song. Giữ profile `local`. Dùng cùng hostname cho trình duyệt và API, hoặc proxy cùng origin. Cookie là `DB_SESSION`, còn `X-CSRF-Token` lấy từ `session.csrfToken` sau OTP hoặc đọc phiên.

| Username | Vai trò | Điện thoại mailbox | Số tài khoản QA |
|---|---|---|---|
| qa-sender | CUSTOMER | 0900000011 | 000000000011 |
| qa-receiver | CUSTOMER | 0900000012 | 000000000012 |
| qa-maker | OPERATIONS | 0900000021 | |
| qa-admin | ADMIN | 0900000022 | |
| qa-checker | ADMIN + CHECKER | 0900000023 | |
| qa-auditor | AUDITOR | 0900000024 | |

Gửi `POST /auth/login` với `username`, mật khẩu QA và `deviceFingerprint`. Lấy mã mới nhất cho số tương ứng từ `GET http://127.0.0.1:18081/messages?destination=<phone>` với header `X-QA-SMS-Token`. Gửi `challengeId` và `otp` tới `POST /auth/otp/verify`. Khi ghi bằng cookie, gửi CSRF token mới nhất và dùng `Idempotency-Key` ở Transfer/Approval.

Mailbox giữ tối đa 100 tin trong bộ nhớ tiến trình. Mật khẩu fixture được đặt lần đầu khi seed. Lần khởi động lại dùng lại fixture và dữ liệu giao dịch đã có. QA bật recovery và job gửi kết quả chuyển tiền. OpenAPI v1.1.0 là nguồn sinh types cho backend này.

Chạy smoke HTTP từ thư mục backend sau khi stack đã healthy. Script ghi giao dịch tổng hợp và đổi trạng thái cờ, nên dùng DB QA riêng.

```sh
python3 scripts/qa-api-smoke.py
```

## Kiểm chứng bàn giao ngày 20/11/2026

- Docker Desktop, PostgreSQL 16 và Java 21. Stack QA khởi động với volume mới, Flyway áp dụng V1–V25, kết nối runtime bằng `banking_app`. Hai view V25 có SELECT và chặn quyền ghi của vai trò ứng dụng.
- Đối chiếu method/path từ `/v3/api-docs` với OpenAPI v1.1.0 cho kết quả 30/30 operation được đăng ký.
- Smoke HTTP trên image đóng gói đạt OTP, session/CSRF, scope Customer/Auditor, tạo transfer, đọc phía nhận, replay, history, Fraud review và C2, Audit CSV/JSONL và lỗi 413, logout.
- Regression chọn lọc theo DEC-INT-007 đạt 269/269 ca thuộc 19 class, gồm migration, kiến trúc, hợp đồng, RBAC, Transfer, Admin maker/checker, Ledger và OTP.
- Phạm vi bàn giao là QA local. Kiểm chứng browser và release gate D1–D5 theo kế hoạch BE-INT-11/12.
