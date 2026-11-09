# ADR-001 — Baseline migration frontend

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / ADR-001 |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, kiến trúc, BA |

## 1. Trạng thái quyết định

Đề xuất, ngày 08/11/2026. Người chấp thuận Chờ phân công. Công việc liên quan FE-FND-01/02/03 và GAP-06. Nội dung sau là đề xuất để review, package mới chưa được tạo.

## 2. Bối cảnh

FE-FND-01/02/03 cần thống nhất toolchain, form và UI library cho digitalbanking-frontend. Nguồn 05 yêu cầu Vite 7, định hướng pnpm, RHF/Zod và lựa chọn UI Tailwind+shadcn hoặc MUI. Ant Design là phương án tái sử dụng UI cần được nhóm xem xét.

Nguồn [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] mục 2.1, GAP-06 và [[digitalbanking-docs/docs-common/05. Technical stack.md]].

## 3. Các lựa chọn

| Lựa chọn | Lợi ích | Chi phí và phụ thuộc |
|---|---|---|
| A — Tái dùng UI Ant Design, thay protocol và toolchain | Giữ component/UX và giá trị code đã có | Cần kiến trúc duyệt ngoại lệ UI và đồng bộ 05, kiểm tra nâng version |
| B — Chuyển UI sang Tailwind+shadcn | Phù hợp lựa chọn chính trong 05, kiểm soát token/component | Viết lại form/table/dialog/layout và kiểm chứng responsive/accessibility |
| C — Chuyển UI sang MUI | Phù hợp phương án UI trong 05, có component hệ thống | Migration component và theme, kiểm chứng lại tương tự B |

## 4. Đề xuất để review

Chọn A cho đợt migration protocol nhằm giữ phần UI đã có kiểm chứng. Nếu nhóm chấp thuận, cập nhật 05 ghi Ant Design là lựa chọn chính thức cho FE, giữ một UI library chủ đạo.

Đồng thời chuyển source sang digitalbanking-frontend, feature/shared architecture, client từ OpenAPI mới, RHF/Zod theo stack và React Router/Query hiện hành. Pin runtime/packageManager/version được duyệt với lockfile và scripts. Nâng Vite theo baseline chung trong PR riêng có build/test evidence.

## 5. Hậu quả và điều kiện áp dụng

- UI tokens/shared primitives vẫn theo FE-UI-01, form schema chuẩn hóa theo contract.
- Rà soát license/dependency/bundle và tương thích React trước chốt package versions.
- Phải regression auth, transfer, operator/audit và responsive sau migration.
- Khi chọn B/C, FE-FND-03 cần ước lượng lại và update delivery plan.
- Tiêu chí nghiệm thu là FE-FND-01/02/03 theo FE-TEST-03 với CI/build evidence mới.

## 6. Ghi nhận quyết định

| Thuộc tính | Giá trị |
|---|---|
| Quyết định cuối | Chờ review |
| Người/ngày duyệt | Chờ phân công |
| PR nguồn chung và standards | Bổ sung khi chấp thuận |
| Trigger xem xét lại | UI library cản requirement, dependency/security issue hoặc thay đổi phạm vi |
