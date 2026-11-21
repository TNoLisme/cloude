# FE-08-01-MOCK — kết quả demo

Mở `/admin/roles?rolePreview=v2` khi chạy Vite ở chế độ mock. Preview độc lập có bốn thao tác `ASSIGN_ROLE`, `REVOKE_ROLE`, `GRANT_PRIVILEGE`, `REVOKE_PRIVILEGE` theo YAML v2 Draft FE/mock. Form dùng user ID tổng hợp `4002`, role enum hoặc privilege `CHECKER`, lý do tối đa 500 ký tự và bước xem lại. Nút Tạo yêu cầu bị khóa. Panel danh sách gọi GET `listApprovals` lọc `ROLE_CHANGE`, có kịch bản mẫu/rỗng/chậm/lỗi và link tới approval chỉ đọc; không thêm handler POST.

Chrome local đã xem form mặc định, review và link tới approval #8002 ngày 21/11/2026. Bộ chọn kịch bản thêm sau lượt browser được kiểm bằng test UI/MSW ngày 22/11/2026 (3/3 focused pass); chưa xem lại browser cho selector. Full suite run-01 đạt 30 files/194 tests; lint/typecheck/OpenAPI/format/build đạt, chi tiết lệnh và giới hạn tại [run-01](run-01.md) và [run-02](run-02.md). Không có screenshot file trong repo.

UI còn chờ người phụ trách FE review và thiết kế cuối. GAP-03/07/08, FE-08-01 cha, FET-12/13 và tích hợp BE còn mở. Không có xác nhận merge.
