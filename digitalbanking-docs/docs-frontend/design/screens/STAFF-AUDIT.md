# STAFF-AUDIT — Nhật ký kiểm toán

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | STAFF-AUDIT |
| Trạng thái UI | Có source UI, có phần chờ cập nhật |
| Route trong code | /staff/audit |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-07-audit.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/staff/ReadOnlyPages.tsx dòng 24–288](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/staff/ReadOnlyPages.tsx#L24-L288)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

BankingLayout staff → Heading Nhật ký kiểm toán/subtitle read-only → Panel → filter form → ErrorPanel → vùng bảng → Tải thêm → Drawer chi tiết.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| AUDIT-KIND | Input | Loại sự kiện, name kind, max80 | Map eventType |
| AUDIT-ACTOR | Input | Actor ID, placeholder UUID tùy chọn, regex UUID | Map actorId |
| AUDIT-TIME | Input datetime-local | From/to, to > from | UTC filter |
| AUDIT-FILTER | Button primary + Button | Lọc / Cập nhật | Set filters / refetch |
| AUDIT-TABLE | Table | rowKey eventId, pagination=false, scroll.x1200, loading isPending | Xem mở drawer |
| AUDIT-COLUMNS | 7 cột | Thời gian VN, Sự kiện, Người thực hiện (— khi rỗng), Đối tượng, Kết quả Status, Tóm tắt, Chi tiết | Read-only |
| AUDIT-MORE | Button loading | Tải thêm ở panel-bottom | Cursor next page |
| AUDIT-DRAWER | Drawer width520 | Chi tiết sự kiện, open theo selected | onClose clear selected |
| AUDIT-DETAIL | Descriptions column1 | eventId/eventType/actorId/targetType/targetId/outcome/occurredAt/correlationId/summary trong allowlist | Datetime format, outcome Status, null thành — |

## 4. Style và kích thước

Table/filter/Alert theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Chi tiết dùng Button link size small. Drawer width520 và global max-width100vw. .table-region cuộn trong vùng, table cells12px/header theme.

## 5. Trạng thái và tương tác

Infinite query nối pages/dedupe eventId. Table pending spinner; locale empty Chưa có dữ liệu phù hợp. ErrorPanel retry fetchNextPage nếu lỗi trang sau, còn lại refetch. Drawer lấy selected row trực tiếp, không gọi detail endpoint riêng trong source.

## 6. Responsive

Staff sidebar76 ở640–1023, dưới640 mở drawer nav. Bảng giữ scroll.x1200 kể cả mobile, filter full-width tối đa767. Drawer rộng tối đa viewport. Không có mobile cards cho audit trong source.

## 7. Accessibility và nội dung

Labels/time validation, Table/Drawer dựa semantics của Ant, Button Xem có text chung nên cần kiểm chứng phân biệt row qua keyboard/screen reader. Detail allowlist chặn raw field ngoài danh mục source.

## 8. Cập nhật khi triển khai

FE-07-01 đổi /audit/page và detail API/DTO, GAP-07/12 chốt export. Cập nhật AUDIT-* khi đổi columns/filter/export. Thiết kế export chưa có code ở source, phần dưới chờ thực hiện.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Dialog hoặc panel xuất audit

### Trạng thái tải file và kết quả export

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
