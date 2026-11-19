# CUST-LIMITS — Hạn mức khách hàng

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Khung tài liệu đã chốt — chờ bổ sung khi có UI |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-LIMITS |
| Trạng thái UI | Demo mock chỉ đọc — chưa chốt thiết kế cuối |
| Route đích | /customer/limits (đề xuất) |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]] |
| Task thực hiện | FE-05-01 |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

FE-05-01-MOCK dùng `GET /api/v2/limits` theo YAML Draft FE/mock; chỉ CUSTOMER đọc self. UI preview dùng `/customer/limits?customerPreview=v2` hoặc `authPreview=v2`. Không có form đổi hạn mức, maker/checker hay step-up.

## 2. Bố cục và thứ tự vùng

Giữ shell CUSTOMER; chọn tài khoản từ `listAccounts`, vùng hạn mức hiệu lực và danh sách cấu hình CUSTOMER/ACCOUNT. Giá trị hiệu lực `null` hiển thị “Không có dữ liệu”, không biến thành 0.

## 3. Component và cấu hình

## 4. Style và kích thước

## 5. Trạng thái và tương tác

Loading/empty/error và thử lại; toolbar dev/test chọn default/empty/slow/error. Dữ liệu fixture tổng hợp không đặt quy tắc policy hoặc timezone reset.

## 6. Responsive

## 7. Accessibility và nội dung

## 8. Cập nhật khi triển khai

## 9. Nhật ký

19/11/2026: demo mock tại [[digitalbanking-docs/docs-frontend/evidence/FE-05-01-MOCK/2026-11-19-b9ba604/run-01.md]]. Thiết kế cuối chờ vòng review UI sau các task demo.
