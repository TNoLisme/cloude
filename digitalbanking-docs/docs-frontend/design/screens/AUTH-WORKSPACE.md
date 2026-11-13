# AUTH-WORKSPACE — Chọn khu vực làm việc

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | AUTH-WORKSPACE |
| Trạng thái UI | Có source UI |
| Route trong code | /workspace |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/auth/AuthPages.tsx dòng 552–582](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/auth/AuthPages.tsx#L552-L582)

[Source frontend/src/styles.css dòng 407–429](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L407-L429)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

[Source frontend/src/stores/session.ts](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/stores/session.ts).

## 2. Bố cục và thứ tự vùng

AuthLayout → h1 Chọn khu vực làm việc → subtitle quyền server → danh sách các area-choice theo thứ tự workspaces(user).

## 3. Component và cấu hình

| ID | Component | Cấu hình | Hành động |
|---|---|---|---|
| WORKSPACE-HEADING | h1/p | Chọn khu vực làm việc, Các khu vực bạn được server cấp quyền truy cập | Tĩnh |
| WORKSPACE-LIST | .area-choices | Flex column, gap 12 | Map workspaces(user) |
| WORKSPACE-ITEM | Link .area-choice | Safety icon, strong label, small detail, ArrowRight cuối | Đi area.path |
| WORKSPACE-CUSTOMER | Link | Khách hàng / Tài khoản và giao dịch cá nhân | /customer hoặc PIN setup khi chưa isPinSet |
| WORKSPACE-OPERATOR | Link | Operator / Tra cứu và hỗ trợ khách tại quầy | /staff/customers theo source role |
| WORKSPACE-AUDITOR | Link | Auditor / Đọc nhật ký kiểm toán | /staff/audit |

## 4. Style và kích thước

Dùng AuthLayout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]]. h1/auth-sub/auth-row/auth-bottom, Form/Input/ActionButton và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. auth-sub 13 px, margin 9px 0 25px, auth-bottom border-top, margin-top 24/padding-top 22, font 12. Area-choice border #E1E7EF, radius 10, padding 17, gap 13, align center, navy. Detail 12px secondary margin-top 2, mũi tên cuối margin-left auto.

## 5. Trạng thái và tương tác

Danh sách dẫn từ roles hiện tại. landing có một area sẽ điều hướng thẳng, nhiều area vào workspace, thiếu area vào forbidden. Customer chưa PIN chuyển setup. File mô tả danh sách chọn khu vực khi được render.

## 6. Responsive

AuthLayout chuyển căn trên và card padding 24×22 khi tối đa 767 px. Form dọc và nút block giữ đầy chiều rộng card. CodeField max-width 100% trên mobile. Breakpoint theo shared layouts.

## 7. Accessibility và nội dung

Mỗi area là link semantic gồm label và mô tả, focus ring global. Chưa có override hover riêng cho card, dùng anchor và global styles.

## 8. Cập nhật khi triển khai

FE-FND-03 và GAP-07 cập nhật area list/role/capability. Sau migration ghi lại điều kiện hiển thị và đường dẫn đã đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
