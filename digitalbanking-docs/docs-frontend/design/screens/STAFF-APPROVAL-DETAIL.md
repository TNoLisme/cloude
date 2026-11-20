# STAFF-APPROVAL-DETAIL — Chi tiết và quyết định phê duyệt

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Khung tài liệu đã chốt — chờ bổ sung khi có UI |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | STAFF-APPROVAL-DETAIL |
| Trạng thái UI | Preview mock metadata chỉ đọc — chưa chốt thiết kế cuối |
| Route đích | /staff/approvals/:approvalId (đề xuất) |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]], [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| Task thực hiện | FE-05-02, FE-08-01 |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

FE-05-02-MOCK dùng `getApproval` v2 Draft FE/mock. Theo chốt người phụ trách FE 19/11/2026, preview staff độc lập chỉ hiển thị metadata có schema; `payload/result` object tự do không render. GAP-07/08 vẫn mở.

## 2. Bố cục và thứ tự vùng

Chi tiết hiển thị ID, loại, đối tượng, trạng thái, maker/checker, thời điểm và correlation ID từ response. Nút phê duyệt/từ chối vô hiệu hóa; không gọi POST hoặc verify step-up.

## 3. Component và cấu hình

## 4. Style và kích thước

## 5. Trạng thái và tương tác

Loading/403/404/lỗi chung không lộ raw Problem hoặc payload; có quay về danh sách và thử lại.

## 6. Responsive

## 7. Accessibility và nội dung

## 8. Cập nhật khi triển khai

## 9. Nhật ký

19/11/2026: bản demo đang kiểm chứng tại [[digitalbanking-docs/docs-frontend/evidence/FE-05-02-MOCK/2026-11-19-65bd72f/run-01.md]].
