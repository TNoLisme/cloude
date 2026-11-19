# FE-05-01-MOCK — kết quả ngày 19/11/2026

Đã làm màn CUSTOMER đọc hạn mức từ `GET /api/v2/limits` bằng MSW, gồm tổng hợp hạn mức hiệu lực, cấu hình CUSTOMER/ACCOUNT, chọn account self, giá trị nullable và kịch bản dữ liệu/rỗng/chậm/lỗi. Mở bằng `/customer/limits?customerPreview=v2` hoặc sau login mock tại `/customer/limits?authPreview=v2` khi dev server có thể truy cập.

Kiểm tra static, OpenAPI, format, 171 test và build đạt. Browser IAB không mở được dev server port 5175 (`ERR_CONNECTION_TIMED_OUT`); chưa có ảnh hoặc xác nhận UI chạy trong browser. Task giữ **Đang làm**, UI chưa duyệt. FE-05-01 cha và phần maker/checker còn mở theo GAP-07/08. Chi tiết lệnh, kết quả, phạm vi và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-05-01-MOCK/2026-11-19-b9ba604/run-01.md]]. FE HEAD `b9ba604`, working tree dirty, chưa commit/push/merge.
