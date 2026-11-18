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

## Bổ sung FE-01-01 — 17/11/2026, chưa duyệt UI

Quyết định người phụ trách FE trong chat 17/11: “Chốt màn phiên chung cho mọi role; chưa nối nghiệp vụ (đề xuất)”. Trong chế độ `authPreview=v2`, `/workspace` dùng V2SessionSummary tại `src/features/auth/v2/AuthScreens.tsx`, working tree nền frontend@6bceb5d. Viewer legacy vẫn có màn chọn khu vực ở trên; v2 chưa áp mapping đó.

Card dùng cùng AuthLayout CSS, h1 “Phiên hiện tại”, lời chào displayName, AntD Descriptions một cột: userId/customerId giữ string, userType, roles/privileges nguyên phản hồi, deviceTrusted, idleExpiresAt/absoluteExpiresAt định dạng vi-VN Asia/Ho_Chi_Minh; không render CSRF/token. Mọi role dùng cùng màn, không link tài khoản/staff, không suy ra capability từ fixture. Nút Đăng xuất khóa khi đang thực hiện; local state/cache/intent được xóa, nếu chưa xác nhận 204 thì thông báo chưa xác nhận thu hồi trên máy chủ và cho kiểm tra lại phiên.

Label/value dạng text, `<time dateTime>` cho expiry, tiêu đề focusable và nút semantic. Bốn role CUSTOMER/OPERATIONS/ADMIN/AUDITOR có component test; browser mobile/desktop/keyboard chưa kiểm chứng. GAP-07 và tích hợp nghiệp vụ vẫn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

### Host mới theo ảnh người dùng18/11/2026

Preview v2 dùng AuthSplitLayout hai nửa thay card cũ: artwork trái/BrandDB và nội dung phải max500px; <=767px ẩn artwork. Component/nội dung/route/lifecycle riêng của màn này giữ nguyên; không đổi contract hoặc quyền. Toolbar fixture thu gọn trong details ở góc dưới trái. Browser run03 có screenshot/mobile login200/reload/logout; chưa kiểm lại toàn bộ challenge/đa tab/API thật. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]].
