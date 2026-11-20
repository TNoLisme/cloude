# STAFF-APPROVAL-LIST — Danh sách yêu cầu phê duyệt

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Khung tài liệu đã chốt — chờ bổ sung khi có UI |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | STAFF-APPROVAL-LIST |
| Trạng thái UI | Preview mock chỉ đọc — chưa chốt thiết kế cuối |
| Route đích | /staff/approvals (đề xuất) |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]], [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| Task thực hiện | FE-05-02 |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

FE-05-02-MOCK dùng `listApprovals` v2 Draft FE/mock trong preview staff độc lập theo chốt người phụ trách FE 19/11/2026. Không suy ra staff role/scope từ fixture; GAP-07 còn mở.

## 2. Bố cục và thứ tự vùng

Danh sách metadata approval, lọc `requestType` và `status`, phân trang 0-based/size20. `sort` để mặc định vì YAML chưa có whitelist endpoint. Preview có kịch bản tổng hợp/rỗng/chậm/lỗi.

## 3. Component và cấu hình

## 4. Style và kích thước

## 5. Trạng thái và tương tác

Mỗi item dẫn đến chi tiết qua `approvalId`; list loading/empty/error có thử lại. Chỉ có GET; không tạo hoặc phê duyệt yêu cầu.

## 6. Responsive

## 7. Accessibility và nội dung

## 8. Cập nhật khi triển khai

## 9. Nhật ký

19/11/2026: bản demo đang kiểm chứng tại [[digitalbanking-docs/docs-frontend/evidence/FE-05-02-MOCK/2026-11-19-65bd72f/run-01.md]].
