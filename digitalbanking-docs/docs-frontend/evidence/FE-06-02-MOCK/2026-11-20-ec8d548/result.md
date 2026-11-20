# FE-06-02-MOCK — kết quả preview

Đã dựng preview staff độc lập tại `/staff/risk?fraudPreview=v2`: danh sách GET `listFraudFlags` với filter trạng thái/mức độ, phân trang và chi tiết lấy từ item danh sách. Fixture tổng hợp hỗ trợ trạng thái dữ liệu/rỗng/chậm/lỗi/403. Chi tiết chỉ hiện metadata có schema; `matchedValue` object tự do không render. ALLOW/BLOCK/ESCALATE/CLOSE đều disabled; không gọi POST review, GET detail hoặc transfer.

Người phụ trách FE chốt “Đúng, làm demo chỉ đọc trước” trong chat 20/11/2026. Browser IAB đã xem list và chi tiết #9001, bốn nút quyết định bị khóa. UI chưa duyệt; GAP-07/11, ngữ nghĩa BLOCK, FE-06-02 cha và tích hợp BE còn mở. Test/gate thực tế và giới hạn ghi trong [run-01](run-01.md). Nền FE `frontend@ec8d548`; chưa commit/push tại thời điểm viết.
