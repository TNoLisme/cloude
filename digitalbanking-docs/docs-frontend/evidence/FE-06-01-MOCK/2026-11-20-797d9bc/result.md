# FE-06-01-MOCK — kết quả ngày 20/11/2026

Preview staff độc lập tại `/staff/customers/2001?staffPreview=v2` đọc hồ sơ/tài khoản qua `getAdminCustomer` và `getAdminCustomerAccounts` v2 Draft FE/mock. Dữ liệu là fixture tổng hợp, phone/email/số tài khoản đã mask. Có kịch bản tài khoản rỗng, tải chậm, lỗi dịch vụ, 403 và ID không tồn tại.

Theo chốt người phụ trách FE trong chat ngày 20/11, dự án sẽ tìm khách hàng bằng số điện thoại nhưng màn demo này đọc bằng ID trước. Ô phone và nút Tra cứu bị khóa, không gửi search API. 07 OI-API-08 mới là đề xuất; YAML v1/v2 chưa có search operation. Phiếu [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]] liệt kê đầu vào BE/BA/Security cần chốt. GAP-03/07, FE-06-01 cha, liên kết history và tích hợp thật vẫn mở.

Browser IAB đã xem màn mặc định và 404; không lưu screenshot file. Lint/typecheck/OpenAPI/format/build đạt, 179 test pass; build cảnh báo chunk lớn. UI/mock chờ người phụ trách FE review, chưa nghiệm thu nghiệp vụ BE. Chi tiết lệnh, trạng thái, nguồn và giới hạn ở [run-01](run-01.md). Nền `frontend@797d9bc`; chưa có commit/push tại thời điểm viết phiếu.
