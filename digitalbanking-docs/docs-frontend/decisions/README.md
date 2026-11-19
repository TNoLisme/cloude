# Quản lý quyết định frontend

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-ADR |
| Phiên bản / ngày | 1.0 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, kiến trúc |

## 1. Sổ quyết định

| ID | Nội dung | Trạng thái quyết định | Quy tắc bị ảnh hưởng |
|---|---|---|---|
| ADR-001 | [[digitalbanking-docs/docs-frontend/decisions/ADR-001-frontend-migration-baseline.md]] | FE đã chọn A ngày 13/11/2026, preview được duyệt 14/11; xác nhận liên vai trò còn mở | FE-ARCH-01/02, FE-UI-01, API-CLIENT-01 |
| ADR-002 | [[digitalbanking-docs/docs-frontend/decisions/ADR-002-session-security-contract-alignment.md]] | FE chọn Security và v2 riêng ngày 14/11/2026; contract/mock được FE duyệt 14/11, runtime review còn mở | FE-AUTH-01/03, API-CLIENT-01, GAP-05 |

## 2. Quy trình

ADR ghi context, lựa chọn, đề xuất, tác động, nguồn và người quyết định. Trạng thái decision gồm Đề xuất, Được chấp thuận, Bị từ chối, Được thay thế. Đây là trạng thái quyết định, trạng thái nội dung tài liệu ở metadata theo quy ước README.

Khi chấp thuận ghi người/ngày/PR, cập nhật standards và phần nguồn chung bị ảnh hưởng. ADR lịch sử giữ nguyên lý do, thêm liên kết quyết định thay thế. Tracker giữ công việc thực thi và blocker. Feature dùng quy tắc hiện hành trong standards.

## 3. Template

[[digitalbanking-docs/docs-frontend/templates/decision-template.md]]. Phạm vi yêu cầu/API đổi cần owner BA/BE/Security của nguồn tương ứng review trước khi áp dụng.
