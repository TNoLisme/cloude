# APP-BOOTSTRAP — Khôi phục phiên ban đầu

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | APP-BOOTSTRAP |
| Trạng thái UI | Có source UI |
| Route trong code | Trước BrowserRouter khi app bootstrap |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/app/App.tsx dòng 69–133](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/app/App.tsx#L69-L133)

[Source frontend/src/styles.css dòng 430–449](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L430-L449)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

main.bootstrap → Brand → section.bootstrap-card → h1 Đang khôi phục phiên → Loading hoặc ErrorPanel và hai action → Simulation.

## 3. Component và cấu hình

| ID | Component | Cấu hình | Hành động |
|---|---|---|---|
| BOOT-BRAND | Brand | Bản thường | Tĩnh |
| BOOT-CARD | section | h1 Đang khôi phục phiên | Chứa nội dung |
| BOOT-LOADING | Loading | Skeleton 4 dòng, role status | Trong boot |
| BOOT-ERROR | ErrorPanel | bootError | Khi refresh thất bại khác 401 |
| BOOT-RETRY | ActionButton | Thử khôi phục lại | Chạy bootstrap lại |
| BOOT-LOGIN | Button spaced | Đến đăng nhập | Clear session/error |
| BOOT-SIMULATION | Simulation | Nhãn mô phỏng | Tĩnh |

## 4. Style và kích thước

.bootstrap min-height 100dvh, flex column center, padding/gap 24. Card width 460/max-width100%, padding32, border #E1E7EF, radius14, trắng. Card h1 margin-bottom24. Shared primitive theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 5. Trạng thái và tương tác

Source bootstrap gọi refresh. 401 bỏ boot để router quyết định login, network/error khác giữ card lỗi. Retry dùng action lock. Đến đăng nhập xóa session local và bootError. Notice toàn app sau bootstrap là Alert warning closable bên trên Routes.

## 6. Responsive

Card max-width100% và wrapper padding24, không có override mobile riêng cho .bootstrap-card. Nội dung cao theo ErrorPanel và action.

## 7. Accessibility và nội dung

Loading role status, ErrorPanel role alert, nút có text. Đây là trạng thái app chưa có route, QA keyboard và error announcements khi retry.

## 8. Cập nhật khi triển khai

FE-FND-02/FE-01-01 chuyển bootstrap sang getSession theo FE-AUTH-01. Cập nhật nhãn/nút/state tại đây khi thay code.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
