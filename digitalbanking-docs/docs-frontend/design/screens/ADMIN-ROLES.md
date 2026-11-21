# ADMIN-ROLES — Quản trị role và privilege

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Khung tài liệu đã chốt — chờ bổ sung khi có UI |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | ADMIN-ROLES |
| Trạng thái UI | Có preview FE-08-01-MOCK độc lập; UI đích chưa duyệt |
| Route đích | /admin/roles (đề xuất) |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| Task thực hiện | FE-08-01 |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

Preview dev/test theo OpenAPI v2 Draft `RoleChangeRequest` và `listApprovals`. Bốn operation được chọn trong form; dữ liệu user mẫu không chứng minh nguồn target hay quyền.

## 2. Bố cục và thứ tự vùng

Sidebar nhỏ của preview staff, header, cảnh báo phụ thuộc contract, ba bước Nhập → Kiểm tra → Gửi chờ contract. Form và review ở panel đầu; panel dưới đọc danh sách ROLE_CHANGE và liên kết sang approval chỉ đọc FE-05-02. Chưa thiết kế lại trang quản trị cuối.

## 3. Component và cấu hình

`RolePreview` tái dùng Brand/Heading/Panel, Ant Design Input/Select/Descriptions/Alert/Steps; GET qua `approvalApi.list`, fixture/handler FE-05-02 có sẵn. Không có handler POST mới. `rolePreviewDraft` tạo union theo operation, không giữ `roleCode` và `privilegeCode` cùng lúc.

## 4. Style và kích thước

Dùng token và AppLayout hiện có. Form rộng tối đa 560px, bước tiến tối đa 760px; không thêm màu hoặc hình trang trí mới.

## 5. Trạng thái và tương tác

Chọn operation/role, nhập reason tối đa 500 ký tự, xem lại và sửa; nút Tạo yêu cầu luôn disabled. Target user ID chỉ đọc là fixture tổng hợp. Danh sách có loading/error/empty/default theo GET mock; link mở approval preview riêng. Không có maker/checker hoặc challenge runtime.

## 6. Responsive

Panel và form theo bề rộng workspace; kiểm browser desktop/mobile trong phiếu task, không coi là duyệt UI cuối.

## 7. Accessibility và nội dung

Input/Select có nhãn, loading có `role=status`; cảnh báo và nút bị khóa có lý do bằng chữ. Không dùng màu làm tín hiệu duy nhất. Kịch bản danh sách cho phép xem dữ liệu mẫu/rỗng/tải chậm/lỗi trong preview.

## 8. Cập nhật khi triển khai

FE-08-01-MOCK chỉ bổ sung preview. Phần chọn target từ user search, capability, step-up, POST và kết quả APPLIED đợi GAP-03/07/08, FET-12/13 và API thật.

## 9. Nhật ký

- 21/11/2026: tạo preview theo yêu cầu người phụ trách FE trong chat; [[digitalbanking-docs/docs-frontend/evidence/FE-08-01-MOCK/2026-11-21-cd07f27/run-01.md]]. Chờ review UI.
