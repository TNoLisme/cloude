# Layout và điều hướng dùng chung

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Mã | UI-SHARED-02 |
| Mức mô tả | Đọc code/CSS, chưa kiểm chứng render lại |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn

- [Source frontend/src/layouts/Layouts.tsx dòng 22–210](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/layouts/Layouts.tsx#L22-L210).
- [Source frontend/src/layouts/AppLayout.module.css dòng 1–251](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/layouts/AppLayout.module.css#L1-L251).
- [Source frontend/src/styles.css dòng 430–478](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L430-L478).
- Theme và primitive tại [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 2. AuthLayout

~~~text
main.auth
  Brand
  section.authCard
    Nội dung màn hình qua Outlet
  Simulation
~~~

Desktop min-height 100dvh, padding 40px 20px, flex column căn giữa hai trục. Brand margin-bottom 28. Card width 460/max-width 100%, nền trắng, border #E1E7EF, radius 14, padding 32, shadow 0 5px 22px #173B5E0A. Simulation bên dưới margin-top 24, nền trong suốt và border 0.

Tối đa 767 px, wrapper padding 32px 20px và justify-start, card padding 24px 22px, Brand margin-top 20, subtitle 10 px. Form của màn hình tự xác định chiều cao theo nội dung.

## 3. BankingLayout desktop

~~~text
shell flex, min-height 100dvh
  sidebar sticky 224px
    Brand compact
    navigation
    footer mô phỏng
  workspace flex 1, min-width 0
    topbar
      breadcrumb/menu
      simulation/avatar/workspace/logout
    main-content
      mobile simulation
      nội dung màn hình qua Outlet
  customer bottom navigation
  staff navigation Drawer
~~~

Sidebar rộng/flex-basis 224 px, navy, trắng, padding 28px 16px 20px, sticky top 0 và cao 100dvh. Main max-width 1200, margin auto, padding 32px 40px 40px. Topbar trắng, min-height 80, border-bottom, padding 16×40, gap 16.

Topbar chứa breadcrumb Digital Banking/Nhân viên, Simulation, Avatar dùng chữ đầu displayName nền #EAF2FC/chữ #2868B2, tên 12 px, vai trò 10 px, nút workspace Swap và logout có title/aria-label. Logout loading theo ending.

## 4. Navigation state

Nav dọc gap 7. Item padding 13×14, radius 8, min-height 46, gap 12, font 13, icon 18, text #CAD8E6. Hover nền #204868/trắng. Active nền #2868B2/trắng, inset shadow 3px #84B7F1.

Customer có Tổng quan, Chuyển tiền, Lịch sử, Hồ sơ. Staff trong source hiện tại có Tra cứu khách, Tạo khách tại quầy theo OPERATOR/ADMIN, Audit theo AUDITOR/ADMIN, Risk theo OPERATOR/AUDITOR/ADMIN. Chuẩn quyền đích được triển khai tại FE-FND-02/GAP-07.

Chọn pathname đóng drawer và focus h1 nếu có. Skip link tới main-content đặt top -100, khi focus top 16, left 16, border 2 px primary, padding 12, z-index 100. Logout thất bại phát thông báo chưa xác nhận thu hồi phía server và vẫn điều hướng login theo source.

## 5. Responsive

| Điều kiện CSS | Layout quan sát trong code |
|---|---|
| Tối đa 1023 px | Topbar padding 16×28, main padding 28, topActions gap 12 |
| Staff từ 640 đến 1023 px | Sidebar 76, padding 24×10, ẩn nhãn/footer/brand text, icon item giữa |
| Customer tối đa 767 px | Ẩn sidebar, topbar min-height 64/padding 14×20, main padding 24px 18px 100px |
| Tối đa 767 px | Ẩn Simulation ở topActions và text person, hiện nhãn mô phỏng trong vùng main theo layout |
| Customer tối đa 767 px | BottomNav fixed bottom/left/right 0, z-index 10, trắng/border-top, padding 10px 8px max(10px,safe-area) |
| Customer bottom item | Cột icon/chữ, gap 5, padding 6×8, font 10, icon 20, active nền #EAF2FC/primary |
| Staff tối đa 639 px | Ẩn sidebar, hiện menu button, main padding 24×18, topbar padding 12×18 |
| Staff Drawer | Title Khu vực nhân viên, placement left, width 280, đóng qua onClose hoặc đổi route |

Drawer/modal global max-width theo viewport trong styles.css. Trang con dùng min-width 0 và .table-region khi có bảng. Kiểm chứng ranh giới CSS cần gồm 639/640, 767/768, 1023/1024 ngoài viewport nghiệm thu chung.

## 6. Quy tắc chỉnh sửa

Screen docs dẫn file này cho layout và chỉ mô tả phần nội dung trong Outlet. Khi đổi navigation, cập nhật route policy, file này và các screen bị đổi entry/exit. Các breakpoint trong tài liệu là breakpoint hiện có trong source, FE-FND-03 phải cập nhật source và docs cùng PR nếu chọn baseline khác.

## 7. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Mô tả AuthLayout/BankingLayout và CSS responsive | Đọc source theo metadata |
