# BE-INT-07 — Kiểm chứng Fraud và Audit trong Transfer T2

| Mục | Kết quả |
|---|---|
| Ngày và revision | 19/11/2026, Asia/Ho_Chi_Minh. Working tree trên `dev2@ba3441cc035ed34c5153e28c10b7e86cb7011971`. [Manifest SHA-256](evidence/BE-INT-07/source-manifest.sha256). |
| Môi trường | JDK 21, Gradle 8.14.3, PostgreSQL 16.15 Testcontainers, Spring profile `test`. Role runtime `banking_app`, Flyway `banking_migrator`. Fixture SQL và kiểm tra dữ liệu commit dùng kết nối độc lập. |
| Kết quả | **549/549 test PASS qua 44 lớp**, gồm **46/46 ca T2**. Gradle exit 0, fail/error/skip đều 0. Prometheus kiểm cú pháp và đánh giá thành công cảnh báo khi counter C3 tăng. |
| Bằng chứng | [JUnit XML, tổng hợp, trace SQL/chain và kiểm tra Prometheus](evidence/BE-INT-07/results.tar.gz). [SHA-256](evidence/BE-INT-07/results.sha256). |

## Ma trận nghiệm thu

| Vector | Mong đợi và kết quả PostgreSQL |
|---|---|
| INT07-01 · Schema và rule | Flyway fresh V1–V21 validate thành công. Upgrade V20 giữ checksum và hai phiên bản LARGE_VALUE đã quản lý, seed HIGH_FREQUENCY còn thiếu bằng actor DISABLED có FK, apply lại 0 migration. Duplicate open-ended chặn migration trước khi chốt; constraint chặn khoảng rỗng và auto-block sai loại; runtime role chỉ SELECT trên projection. Ngưỡng 9.999.999,99 / 10.000.000,00 / 10.000.000,01 cho kết quả 0/1/1 cờ. Cửa sổ 4/5/6 cho 0/0/1 cờ, gồm intent hiện tại và cả ba trạng thái. Biên trái và khách hàng khác không tăng count; biên phải tính. Qua 00:00 Việt Nam, hai cửa sổ trả 6 và 3; chọn version mới đúng điểm cutover và bỏ intent đã quá cửa sổ. Overlap tại một thời điểm giữ PENDING. 428 và replay không sinh intent mới. |
| INT07-02 · Kết quả và replay | HTTP 201 được kiểm với 0, 1 và nhiều cờ. Balance, ledger, Limit effect, outbox và terminal snapshot khớp. Policy UNUSUAL bật tường minh trả 422 `FRAUD_BLOCKED`, RELEASED, 0 ledger/effect, có flag, outbox và C2→C1 FAILED. Trigger credit 0 hàng hoàn tác debit và giữ flag cùng `PROCESSING_ERROR`. Replay 201/422 trả đúng JSON đã lưu và bỏ qua đánh giá Fraud. |
| INT07-03 · Engine rollback | Trigger SQLSTATE `P0001` chạy ở insert thứ hai sau khi thấy cờ đầu trong T2. PostgreSQL hoàn tác cả hai cờ, tiếp tục settlement và commit 201 với ledger/C1 thật. Log C4 được encode qua allowlist ngay cả khi counter lỗi. Trigger chậm chứng minh vượt ngân sách Fraud làm rollback cờ khi T2 vẫn còn thời hạn. Fault rollback-only giữ intent PENDING và số dư gốc, trả 500. API MANDATORY bị từ chối khi gọi ngoài transaction. |
| INT07-04 · Audit bắt buộc | Probe không rollback cùng transaction xác nhận C2 đầu đã append trước lỗi C2 sau, và mọi C2 đã append trước lỗi C1 ở nhánh ALLOW/BLOCK. Cả ba ca trả 500 `AUDIT_WRITE_FAILED`; không còn flag/C2/C1/ledger/effect/outbox/terminal body. Số dư giữ nguyên và reservation PENDING. Audit verifier tính lại hash và so sánh cột với canonical payload. Trace gắn PID T2 chứng minh Fraud, Account, Ledger, Limit, terminal/replay và outbox hoàn tất trước khi lấy audit lock, pha cuối chỉ có SQL Audit rồi COMMIT. Catalog có 0 trigger/constraint deferred trong schema canonical. |
| INT07-05 · Race | Quan sát `pg_locks` thấy T1 chờ advisory lock T2 của cùng customer, T2 đầu thấy 5 intent, T2 sau thấy 6. Hai T1 đồng thời cùng customer tạo đúng intent; T2 của customer độc lập cùng đến pha audit trên các connection khác nhau. Các nhóm C2 theo ID tăng rồi C1 liền nhau trong chuỗi. Balance, Limit và ledger đúng sau cạnh tranh; replay giữ snapshot. |
| INT07-06 · C3 | HTTP ownership 403 và xung đột actor/hash 409 ghi C3 riêng. Conflict dùng entity ID `UNKNOWN` và chỉ có `reasonCode`. Tiêm lỗi begin, append, commit qua trigger deferred và exception từ provider vẫn trả cùng mã lỗi HTTP/API. 12 lần lỗi sinh đúng 12 tín hiệu fallback với actor đã che và trace; không có C3 dở dang hoặc tăng số dòng business. `promtool test rules` kiểm không có cảnh báo trước khi counter tăng và có cảnh báo sau khi tăng. |

## Tái lập

Chạy script `verify.sh` từ thư mục `digitalbanking-backend`. Script dùng JDK 21 và Docker Desktop trên macOS. Các lớp được lọc theo DEC-INT-007. Nhóm T0/T1/T2 và boundary kiểm wiring, challenge/replay, recovery entry point và quyền sở hữu module. Nhóm Fraud/Audit/Notification kiểm rule, ghi và đọc Audit, masking và mapping outbox. Nhóm migration và các fixture Account/Customer/Limit liên quan kiểm tương thích với seed và constraint V21. Account/Ledger/Limit kiểm luồng ghi tiền cùng T2.

Trong gói evidence, chạy `promtool test rules audit-alert-test.yml` để lặp kiểm tra tín hiệu cảnh báo. JUnit giữ metadata và kết quả test, trích log theo allowlist. Trace dùng SQL với bind parameter bị ẩn. `sql-and-chain.txt` tổng hợp bằng chứng từ phép đo T2 và các phép kiểm chain.

## Phạm vi

Context kiểm chứng dùng Fraud, Account, Ledger, Limit, Audit và Notification outbox thật. Policy/mapping IN_APP là fixture theo contract. Test biên 10 triệu dùng ngưỡng step-up riêng để gọi POST trực tiếp, còn nhóm T0/T1 kiểm 428 theo ngưỡng mặc định. Lịch sử cho các mốc thời gian kiểm soát dùng row fixture, riêng race dùng T1 tạo intent thật qua HTTP.

Delivery và policy Notification/OTP thuộc BE-INT-08. Recovery worker/Tfail thuộc BE-INT-10. Cấu hình Prometheus deploy và nghiệm thu tải D5 thuộc gate phát hành. [Kế hoạch tích hợp](README.md)
