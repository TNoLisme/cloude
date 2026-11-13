# ADR-001 — Baseline migration frontend

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / ADR-001 |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, kiến trúc, BA |

## 1. Trạng thái quyết định

Ngày 13/11/2026, người phụ trách FE chọn phương án A trong chat: “Giữ Ant Design; chuyển sang pnpm, Vite 7 và React Hook Form/Zod theo stack đích (đề xuất trong ADR-001).” Phạm vi đợt đầu: xem toàn bộ màn có source cũ trước, sau đó đồng bộ contract mới và bổ sung màn thiếu. Package đang được chuẩn bị; build/test và đồng bộ contract vẫn là đầu ra riêng. Không ghi nhận thay cho phê duyệt của kiến trúc/BE/Security.

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
| Quyết định cuối | Người phụ trách FE chọn A: Ant Design + pnpm 10 + Vite 7 + RHF/Zod |
| Người/ngày duyệt | Người phụ trách FE, chat ngày 13/11/2026; xác nhận liên vai trò theo GAP-06 còn mở |
| PR nguồn chung và standards | Bổ sung khi chấp thuận |
| Trigger xem xét lại | UI library cản requirement, dependency/security issue hoặc thay đổi phạm vi |

## 7. Package preview đang kiểm chứng

Node 22.14.0, pnpm 10.34.9, Vite 7.3.8, plugin-react 5.2.0, MSW 2.15.0, RHF 7.92.0, Zod 4.6.7, resolvers 7.0.6. Các version chính xác được tra npm registry trong lần triển khai và pin trong package/lockfile. Ant Design và React/Router/Query giữ version của source tham chiếu. Form legacy chưa được coi đã chuyển RHF/Zod chỉ vì dependency đã cài.
