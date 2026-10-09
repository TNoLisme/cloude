# Sửa lỗi API được phát hiện khi tích hợp giao diện

Người dùng đã cho phép ngày 08/10/2026: sửa lỗi chuyển tiền ≤5 triệu, lịch sử với bộ lọc rỗng, kiểm tra cùng dạng truy vấn ở audit/risk; giữ nguyên contract và nghiệp vụ, thêm regression test PostgreSQL.

## Lỗi và thay đổi

- INSERT giao dịch `COMPLETED` phải có `completed_at` ngay trong cùng statement để thỏa constraint hiện có. Dùng thời điểm tạo hiện tại; debit, credit, audit và idempotency vẫn nằm trong transaction hiện có. Không nới constraint hoặc đổi ngưỡng OTP.
- PostgreSQL không suy ra kiểu của placeholder chỉ dùng trong `? IS NULL`. Ép kiểu guard theo cột: VARCHAR cho status/eventType/ruleId, UUID cho actorId/transferId, TIMESTAMPTZ cho ngày và cursor. Giữ nguyên điều kiện lọc, quyền sở hữu, thứ tự và cursor.
- Không đổi endpoint, payload, status code, migration cũ hoặc dữ liệu dùng chung.

## Acceptance

PostgreSQL thật phải chứng minh: giao dịch 5 triệu hoàn tất, hai số dư đổi đúng một lần khi replay cùng key; pending vẫn chưa có completed_at; lịch sử không lọc và có lọc/cursor chạy được, người nhận không thấy pending; audit/risk chạy với NULL và đầy đủ filter/cursor. Sau đó chạy API smoke và kiểm tra các màn tương ứng.

Trạng thái: implemented và regression PostgreSQL PASS. Ba test tái hiện completion/NULL lỗi trước sửa; bốn test PASS sau sửa (thêm FAILED/EXPIRED không debit). API smoke chuyển tiền hai ngưỡng, replay, lịch sử, audit/risk PASS; UI history/audit/risk có dữ liệu thật và giao dịch hết hạn không còn form OTP. Xem tiến độ frontend để biết giới hạn và lỗi lookup khác đang chờ quyết định.
