# E3 / LIM-06 — Kiểm chứng tra cứu hạn mức

> **Ngày:** 17/11/2026  
> **Trạng thái:** Đã triển khai và kiểm thử phần limit với PostgreSQL và các provider test double; nghiệm thu tích hợp thực tế còn phụ thuộc các module liên quan.

## Phạm vi và nguồn

`GET /api/v1/limits` chỉ dành cho customer có `LIMIT_READ_SELF`. Không có `accountId` chỉ trả customer scope; có account được xác minh ownership trả thêm scope account đó (DEC-LIM-001). Truy vấn mới dùng Spring Data JPA, một native read query trên hai bảng do limit sở hữu, không tạo counter hoặc khóa enforcement. API trả usage theo ngày từ timezone cấu hình, từng remaining riêng không âm và effective là minimum của các scope được chọn. Cấu hình không hiệu lực và scope không liên quan không được trả.

Nguồn: `03` FR-031/AC-017, `04` §4.5, `06` §6, `07` §3.6/§7.2.13/§8.5.1; `design/05` §§7, 10 và `DELIVERY-PLAN.md` LIM-06. Spec/plan/tasks tại `specs/005-customer-limit-view/`.

## Kết quả

- Hồi quy toàn bộ trước các thay đổi cuối về xác thực scope và logging: `./gradlew test --offline --console=plain --max-workers=2` — **413 tests, 0 failures/errors/skips**.
- Sau đó chạy nhóm limit/common/architecture: **231 tests, 0 failures/errors/skips**. Chạy cuối trên mã hiện tại: `./gradlew test --offline --console=plain --max-workers=2 --tests '*LimitCustomer*' --tests '*SecurityAuditLogEncoderTest' --tests '*ModuleBoundaryArchTest'` — **21 tests, 0 failures/errors/skips**.
- Testcontainers PostgreSQL 16 dùng schema/migration và query JPA thật; HTTP dùng session/account test double. Ca kiểm thử gồm customer-only, account-only, hai scope, scope người khác, customerId khác, thiếu quyền, account không tồn tại, account provider thiếu (503), không config, usage thiếu/vượt, hiệu lực đầu/cuối, ngày đổi, dữ liệu đã commit và row đang có update chưa commit. Kiểm chứng không tạo row khi đọc.
- Audit C3: test contract với provider giả và lỗi provider; fallback ghi security log JSON an toàn cùng correlation context và metric `banking.audit.security.gap`. Không tự nhận đây là audit log bền vững hoặc alert đã được triển khai.
- OpenAPI YAML được parse, kiểm tra version 1.0.1 và phản hồi GET /limits; `git diff --check` đạt. Không coi kiểm tra YAML đơn giản là OpenAPI lint đầy đủ. XML của lượt cuối nằm tại `digitalbanking-backend/build/test-results/test/`; XML của các lượt 413 và 231 đã bị Gradle thay khi chạy lượt sau, nên số liệu đó là tóm tắt, không phải evidence archive.

## Chưa nghiệm thu

- Identity/session và AccountQuery production chưa có provider thật: cần owner tích hợp và chạy contract/IDOR test thật; thiếu AccountQuery thì truy vấn có accountId trả 503, không giả lập production.
- Audit provider tổng quát và cơ chế alert vận hành chưa có: cần owner Audit/Ops review và thực hiện `AccessDeniedAudit`, kiểm tra C3 write/fallback/alert thực tế.
- Transfer thật/LIM-05 chưa sẵn sàng: chưa chứng minh GET trước/sau transfer thực tế hoặc nghiệm thu end-to-end. Các thay đổi usage trong test là dữ liệu tổng hợp có commit.
- Giá trị timezone nghiệm thu D2 cần xác nhận; test dùng cấu hình `Asia/Ho_Chi_Minh`, không tự coi đề xuất là sign-off.
- Các repository E1/E2/E4 dùng JdbcTemplate đã tồn tại từ trước; đường đọc E3 mới hoàn toàn dùng Spring Data JPA. Không sửa money path nằm ngoài Epic 03.
