# CUST-HOME — Tổng quan khách hàng

> Cập nhật FE-02-01 18/11: preview v2 riêng tại `/customer?customerPreview=v2` đã nối CustomerAccountCard với mock listAccounts. Thẻ tái dùng Panel/balance-card, đọc accountNumberMasked, availableBalance string, status và asOf; nút chi tiết nằm trong card để không bị breakpoint legacy ẩn trên mobile/tablet. Loading, rỗng, lỗi và retry có trong preview. Transfer/history chưa nối; auth v2 vẫn vào màn phiên chung. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]].

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

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].


### Cập nhật ngày 18/11/2026 — CUSTOMER sau đăng nhập

Người phụ trách FE chốt trong chat: “tôi muốn sau khi đăng nhập thành công thì vào phần dashboard của customer, và trên màn hình chính của customer thì bỏ phần hiển thị trạng thái hoạt động của tài khoản ( đang hoạt động )”. Quyết định này thay thế phần giữ màn phiên chung sau login **đối với CUSTOMER**: trong preview auth v2, người dùng có userType và role CUSTOMER đến tổng quan `/customer?authPreview=v2`, dùng cùng controller phiên; không seed phiên customer riêng. Màn tổng quan không hiển thị status tài khoản; chi tiết vẫn đọc status theo contract. Vai trò khác giữ màn phiên chung; chưa chốt mapping GAP-07. Preview riêng vẫn tồn tại. Bản mock đang chờ review, không chứng minh runtime v2 hay quyền/ownership BE. Chi tiết: [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-03.md]].


### Run-04 ngày 18/11/2026 — trang chủ customer theo ảnh tham khảo

Theo yêu cầu người phụ trách FE trong chat 18/11, trang chủ mock dùng topbar thương hiệu/lời chào, sidebar trắng với icon và vùng active kiểu ảnh tham khảo. Menu chỉ có Trang chủ, Thông tin tài khoản và Hồ sơ của tôi, là các màn v2 đã nối trong FE-02-01. Nội dung chính hiển thị masked account, số dư khả dụng/asOf và thông tin tài khoản đọc từ GET /accounts; không dựng lãi suất, thẻ, vay, hóa đơn, khuyến mãi hoặc số liệu tổng hợp không có nguồn. Transfer/history có contract nhưng UI v2 thuộc task khác nên chưa mở menu ở đây. Dashboard tiếp tục không có nhãn trạng thái; màn chi tiết vẫn hiển thị status. Browser mock đã xem tại `/customer?authPreview=v2`; ảnh và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-04.md]]. Chờ review UI, không khẳng định API thật.


### Iconography mock ngày 18/11/2026

Người phụ trách FE chốt áp dụng trên customer mock hiện tại trong chat 18/11. Menu Trang chủ/Thông tin tài khoản/Hồ sơ dùng `Icon` outline trong catalog, màu theo trạng thái của nút; thẻ account dùng `FeatureIcon` hai màu; danh sách account rỗng dùng `Illustration` đi cùng text. Không đổi nội dung, route hay API. SVG và quy tắc tại [[digitalbanking-docs/docs-frontend/design/shared/03-iconography.md]], ảnh trước/sau ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-03-ICONS/2026-11-18-b9ba604/run-01.md]].


### FE-04-01-MOCK dashboard gần đây ngày 19/11/2026

Vùng Giao dịch gần đây trên CUST-HOME đọc GET `/transactions` page0/size5/status=COMPLETED, hiển thị counterparty masked theo response, Money và thời điểm; có link chi tiết và Xem lịch sử. Loading/empty/error/retry độc lập với tài khoản. Đây là demo mock theo FE-04, không thay quyết định về UI cuối hoặc chứng minh server. [[digitalbanking-docs/docs-frontend/evidence/FE-04-01-MOCK/2026-11-18-b9ba604/run-01.md]].

### FE-02-01-V1-API ngày 24/11/2026

Màn thật `/customer` sau auth v1 dùng cùng bố cục topbar/sidebar ba mục customer và `CustomerAccountCard`; source `src/features/accounts/v1/V1CustomerDashboard.tsx`. Dữ liệu GET `/api/v1/accounts`, query theo userId/customerId, số tài khoản đã mask, số dư khả dụng và asOf từ server; có loading/rỗng/lỗi/refresh thủ công. Account status không hiện trên trang chủ. History/limits và công cụ fixture không xuất hiện trong runtime v1. HTTP QA thật đạt nhưng browser sau OTP bị chặn bởi INT-10 privileges; chưa có screenshot dashboard v1 hay duyệt thiết kế cuối. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01-V1-API/2026-11-24-eb4e2e7/run-01.md]].


### FE-BE-INT-10 & chốt UI M2 ngày 25/11/2026

Sau khi sửa projection `AuthResponse.user.privileges` (`DEC-INT-021`), Playwright browser E2E (`http://localhost:5173`) đã vào được `/customer`, xác nhận hiển thị đúng số dư/masked account, không hiện account status trên trang chủ, và lưu ảnh chụp thực tế tại `screenshots/m2-customer-home.png`; người phụ trách FE đã chốt UI M2 ngày 25/11/2026. [[digitalbanking-docs/docs-frontend/evidence/FE-BE-INT-10/2026-11-24-ce0d36e/run-01.md]].
