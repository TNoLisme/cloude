# Nhật Ký Tiến Độ Dự Án (Completed Work Log)

> **Quy tắc sử dụng:**
> - File này **CHỈ ĐƯỢC GHI KHI CÓ THÀNH VIÊN ĐÃ HOÀN THÀNH XONG MỘT ĐẦU VIỆC** (Done / Verified).
> - Không liệt kê các việc đang làm hay chưa làm vào đây (danh sách việc cần làm xem tại [`16-bang-phan-cong-nhiem-vu.md`](./projects/backend-mvp/16-bang-phan-cong-nhiem-vu.md)).
> - AI và các thành viên chỉ cần đọc file này là biết chính xác hệ thống đã code và test xong những gì.

---

## Danh Sách Công Việc Đã Hoàn Thành (Done)

### BE-B — Test Infrastructure, Resilience, Audit/Risk & Integration Lead
- **Hạ tầng Test PostgreSQL cô lập:** Tạo [`DisposablePostgresTestSupport.java`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/be/src/test/java/com/bank/simulator/DisposablePostgresTestSupport.java) quản lý lifecycle DB an toàn trên Testcontainers, không đè dữ liệu thật.
- **Kịch bản phục hồi sự cố (Resilience / DR):** Viết [`DatabaseFailureRecoveryTest.java`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/be/src/test/java/com/bank/simulator/DatabaseFailureRecoveryTest.java) dùng Toxiproxy kiểm chứng ngắt mạng DB báo lỗi 503 an toàn, context restart tự hồi phục và giữ nguyên tính idempotency.
- **Kiểm chứng Audit & Risk:** Hoàn tất test PII redaction, audit append-only và logic rủi ro (ngưỡng 5M, tần suất 5/6, AFTER_COMMIT, chống duplicate) ([`BE4-audit-risk.md`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/docs/projects/backend-mvp/evidence/BE4-audit-risk.md)).
- **Dữ liệu k6 & SQL Bất biến số dư:** Viết [`scripts/seed-k6-accounts.sql`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/scripts/seed-k6-accounts.sql) (100 account) và [`queries/verify-financial-invariant.sql`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/queries/verify-financial-invariant.sql) đo bảo toàn 1 tỷ VND.
- **Bảo mật Container & Nâng cấp Spring Boot:** Nâng cấp Spring Boot 3.4.13, Tomcat 10.1.60, Postgres driver 42.7.14, Springdoc 2.8.5; giảm 75.4% CVE ([`BE4-container-scan.md`](file:///d:/work/Xgame/XCreative/yuiyL/cloude/docs/projects/backend-mvp/evidence/BE4-container-scan.md)); Swagger UI và `/v3/api-docs` chạy ổn định.
- **Hồ sơ Evidence & Trạng thái nghiệm thu:** Chuẩn hóa 5 tài liệu nghiệm thu trong `docs/projects/backend-mvp/evidence/`; xác nhận 165/165 backend test pass, frontend typecheck/test/build pass 100%.
### BE-A — Authentication, Security & Account Lifecycle

- **Xác thực & Phân quyền (Authentication & RBAC):** Hoàn thiện xác thực JWT Access Token, phân quyền theo 3 vai trò CUSTOMER, OPERATOR, AUDITOR; kiểm soát truy cập API và ngăn chặn truy cập trái quyền.
- **Refresh Token Rotation & Revocation:** Hoàn thiện cơ chế xoay vòng Refresh Token, phát hiện token bị tái sử dụng (Reuse Detection), tự động thu hồi toàn bộ Token Family bằng PostgreSQL Recursive CTE; xử lý concurrent refresh và grace period 10 giây.
- **Bảo vệ PIN/OTP & Chống Brute-force:** Giới hạn 5 lần xác thực sai, khóa PIN trong 15 phút, tự động reset bộ đếm sau khi hết hạn; OTP có thời hạn, sử dụng một lần và được bảo vệ bằng PostgreSQL row-level locking.
- **Chống đăng ký trùng Email/SĐT:** Chuẩn hóa email, áp dụng PostgreSQL UNIQUE/CHECK constraints; xử lý an toàn các yêu cầu đăng ký đồng thời, không tạo dữ liệu customer/account trùng hoặc mồ côi.
- **Chống IDOR & Bảo vệ dữ liệu khách hàng:** Kiểm soát quyền sở hữu tài khoản theo `customerId`; ngăn Customer truy cập tài khoản người khác và chặn thao tác Operator trái quyền.
- **Chống nạp tiền trùng & Pessimistic Locking:** Áp dụng `Idempotency-Key`, kiểm tra request payload và `SELECT ... FOR UPDATE` để bảo vệ số dư; xác minh các giao dịch nạp đồng thời không gây cộng tiền trùng hoặc lost update.
- **Quản lý trạng thái tài khoản (Account Lifecycle):** Hoàn thiện khóa/mở tài khoản ACTIVE/BLOCKED, ngăn mở lại tài khoản CLOSED, chặn nạp tiền vào tài khoản bị khóa và ghi nhận audit events khi thay đổi trạng thái.
- **Khôi phục tài khoản & Session Security:** Hoàn thiện password recovery, chống user enumeration, tự động thu hồi toàn bộ refresh sessions sau khi đặt lại mật khẩu.
- **Kiểm thử & Nghiệm thu BE-A:** Hoàn thành kiểm thử bảo mật, concurrency và PostgreSQL integration tests bằng Testcontainers. Kết quả toàn bộ backend: **196/196 tests PASS, 0 Failures, 0 Errors, 0 Skipped**. OpenAPI validation PASS (31 operations).
- **CI & Evidence:** Tạo `.github/workflows/backend-ci.yml` và hồ sơ nghiệm thu [`BEA-auth-account.md`](./projects/backend-mvp/evidence/BEA-auth-account.md), lưu kết quả kiểm thử và đối chiếu các tiêu chí Definition of Done (DoD).