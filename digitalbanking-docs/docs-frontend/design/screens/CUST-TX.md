# CUST-TX — Chi tiết giao dịch

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-TX |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/transfers/:transferId |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]], [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/transfers/HistoryPages.tsx dòng 253–348](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/transfers/HistoryPages.tsx#L253-L348)

[Source frontend/src/features/transfers/TransferPage.tsx dòng 43–146](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/transfers/TransferPage.tsx#L43-L146)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

Heading Chi tiết giao dịch → ErrorPanel/Loading → Panel narrow Thông tin giao dịch → receipt-amount → Descriptions → alert/state OTP → action kiểm tra và về lịch sử.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| TX-AMOUNT | .receipt-amount/Money/Status | Amount và status server, căn giữa | Đọc |
| TX-DESCRIPTIONS | Descriptions column1 | Mã giao dịch, lời nhắn hoặc —, thời gian tạo, hoàn tất | Read-only |
| TX-FAILED | Alert error | Giao dịch thất bại, failureCode | Hiện FAILED |
| TX-EXPIRED | Alert warning | Giao dịch đã hết hạn. Tiền chưa được chuyển | Hiện EXPIRED |
| TX-OTP | TransferOtp | CodeField/Countdown/action theo CUST-TRANSFER | Chỉ AWAITING_OTP và ownsSource |
| TX-UNKNOWN | Alert info | Trạng thái mới từ hệ thống, cập nhật để kiểm tra | Enum ngoài danh sách source |
| TX-REFRESH | Button | Kiểm tra trạng thái | query.refetch |
| TX-BACK | Link | Về lịch sử | /customer/transfers |

## 4. Style và kích thước

Panel narrow theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Amount trong receipt-amount không có .receipt cha, vẫn nhận amount30px, dashed separator và margin. Descriptions một cột, action .actions.spaced gap12/margin-top24.

## 5. Trạng thái và tương tác

Pending dùng Loading, lỗi detail có retry. Query accounts xác định ownership nguồn trước hiện OTP, người nhận giữ read-only. OTP onUpdate refetch detail. FAILED/EXPIRED dừng OTP; enum ngoài whitelist hiển thị info alert. Route trong source dùng transferId.

## 6. Responsive

Panel body mobile24×20, receipt amount27px, content descriptions wrap anywhere, actions wrap. Layout customer và bottom nav theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]].

## 7. Accessibility và nội dung

Labeled descriptions, text trạng thái/error, link về lịch sử. Keyboard/focus khi query đổi cần kiểm chứng theo FET-07/08.

## 8. Cập nhật khi triển khai

FE-04-01/FE-03-02 chuyển reference/DTO và trạng thái theo contract. Route đích CUST-TX ở /customer/history/:reference, màn CUST-RESULT độc lập được tạo file chờ code.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
