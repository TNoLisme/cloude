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
