# CUST-HOME — Tổng quan khách hàng

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-HOME |
| Trạng thái UI | Có source UI |
| Route trong code | /customer |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]], [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/accounts/AccountPages.tsx dòng 58–155](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L58-L155)

[Source frontend/src/styles.css dòng 125–174](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L125-L174)

[Source frontend/src/styles.css dòng 479–524](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L479-L524)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

BankingLayout customer → Heading Tổng quan/subtitle tên user → ErrorPanel account → một overview-grid cho mỗi account → Panel Giao dịch gần đây → footnote.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| HOME-HEADING | Heading | Tổng quan, Xin chào displayName | Tĩnh |
| HOME-ACCOUNT-ERROR | ErrorPanel | accounts.error | Refetch accounts |
| HOME-BALANCE | Panel/.balance-card | CreditCardOutlined, Tài khoản VND, Status, masked number, Money balance | Đọc số dư server |
| HOME-TRANSFER | Button primary + Swap | Chuyển tiền | /customer/transfers/new |
| HOME-HISTORY | Button + History icon | Lịch sử giao dịch | /customer/transfers |
| HOME-INFO | Panel title Thông tin tài khoản | AccountInfo Descriptions một cột, owner/number/currency/status/openedAt | Xem chi tiết tài khoản theo accountId |
| HOME-RECENT | Panel + HistoryList recent | Giao dịch gần đây, subtitle Các giao dịch đã hoàn tất của bạn | Tải 5 COMPLETED, link Xem tất cả |
| HOME-FOOTNOTE | p.footnote | Số dư được hiển thị theo kết quả đã xác nhận của hệ thống | Tĩnh |

## 4. Style và kích thước

overview-grid dùng minmax(0,1.8fr) và minmax(260px,1fr), gap/margin-bottom24. Panel số dư có border-top4px primary. Balance amount36px/750, line-height1.25, letter-spacing-1.2, navy. Balance-top justify-between, margin-bottom25. Icon account nền #EAF2FC, padding10/radius9, font18. Shared [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 5. Trạng thái và tương tác

Account pending dùng Loading, rỗng thành công dùng EmptyState Bạn chưa có tài khoản, lỗi có retry. Có dữ liệu map từng account thành một grid. Giao dịch gần đây có loading/error riêng theo HistoryList. Action chuyển tiền vẫn hiện ở card trong source, guard/PIN xử lý khi vào route.

## 6. Responsive

Tối đa1100 overview-grid một cột và ẩn panel thông tin thứ hai. Tối đa767 amount32px, balance-top margin-bottom22, overview margin-bottom20, action nowrap gap8 với button12px/padding8×10. Recent dùng card mobile thay bảng theo CUST-HISTORY. Layout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]].

## 7. Accessibility và nội dung

Heading h1 có tabIndex -1, status có text/icon. Thông tin account là Descriptions. Kiểm chứng keyboard giữa card/link và vùng recent theo FET-03/14.

## 8. Cập nhật khi triển khai

FE-02-01 cập nhật DTO/balance/status theo server, FE-04-01 cập nhật recent list theo contract, route đích của history theo FE-ARCH-03.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
