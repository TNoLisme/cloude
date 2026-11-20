# FE-05-02-MOCK — kết quả preview

Đã tạo bản xem staff độc lập tại `/staff/approvals?approvalPreview=v2` trên Vite mock. Danh sách đọc `GET /api/v2/admin/approvals`; chi tiết đọc `GET /api/v2/admin/approvals/{approvalId}` qua shared client và MSW. Có lọc loại yêu cầu/trạng thái, phân trang và các kịch bản dữ liệu mẫu, rỗng, chậm, lỗi. Dữ liệu hoàn toàn tổng hợp.

Theo quyết định của người phụ trách FE trong chat 19/11/2026, bản xem chỉ hiển thị metadata có schema. `payload/result` không hiện; Phê duyệt/Từ chối bị khóa. Không dùng `sort` khi whitelist chưa chốt, không cấp quyền staff theo fixture và không có handler mutation. GAP-07/08, phần API thật và task cha FE-05-02 vẫn mở.

Đã xem trực tiếp danh sách và chi tiết trong IAB ngày 20/11/2026. Static/typecheck/API check/format/build và 176 test đạt; build có cảnh báo bundle lớn. Không lưu screenshot vào repo, chưa có xác nhận duyệt UI. Chi tiết đầu vào, quyết định, lệnh và giới hạn trong [run-01](run-01.md). FE nền `main@65bd72f`; working tree dirty, chưa commit/push/merge.
