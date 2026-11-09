# CUST-TRANSFER — Chuyển tiền theo từng bước

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-TRANSFER |
| Trạng thái UI | Có source UI, có phần chờ cập nhật |
| Route trong code | /customer/transfers/new |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/transfers/TransferPage.tsx dòng 43–178](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/transfers/TransferPage.tsx#L43-L178)

[Source frontend/src/features/transfers/TransferPage.tsx dòng 179–572](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/transfers/TransferPage.tsx#L179-L572)

[Source frontend/src/styles.css dòng 271–314](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L271-L314)

[Source frontend/src/styles.css dòng 563–585](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L563-L585)

Mô tả UI đang có trong TransferPage, gồm recipient, amount, review/PIN, OTP, unknown và receipt. Task FE-03-01/02 chuyển hành vi sang contract step-up/PENDING/reference và cập nhật file này cùng code.

## 2. Bố cục và thứ tự vùng

BankingLayout customer → Heading Chuyển tiền → Steps nhỏ 4 bước Người nhận/Số tiền/Kiểm tra/Kết quả → Panel narrow theo state → ErrorPanel → nội dung state → footnote hướng dẫn reload/history.

## 3. Component và cấu hình

| ID | Component | Cấu hình / nội dung | Hành động |
|---|---|---|---|
| TRANSFER-STEPS | Steps | size small, responsive=false, current theo step/flow.intent | Hiển thị 4 bước |
| TRANSFER-PANEL | Panel narrow | Title Xác minh người nhận / Số tiền và lời nhắn / Kiểm tra giao dịch / Xác minh giao dịch / Kết quả giao dịch | Theo state |
| TRANSFER-RECIPIENT | Input numeric | accountNumber, label Số tài khoản người nhận, required 8–34 chữ số, autocomplete off | On change hủy recipient đã chọn |
| TRANSFER-RESOLVE | Note + ActionButton block | Tên và tài khoản che do hệ thống xác nhận, Xác minh người nhận | Resolver thành công đến bước số tiền |
| TRANSFER-RECIPIENT-CARD | .recipient | strong recipientDisplayName, account masked, Status Đã xác minh | Tĩnh trong bước số tiền |
| TRANSFER-SOURCE | Select | sourceAccountId, placeholder Chọn tài khoản nguồn, chỉ options ACTIVE, nhãn VND · masked | Chọn nguồn |
| TRANSFER-AMOUNT | Input suffix VND | Required số nguyên 2.000–10.000.000, maxLength8, numeric | Extra ghi khoảng hợp lệ |
| TRANSFER-MEMO | Input.TextArea | name memo, 3 rows, max140 | Lời nhắn |
| TRANSFER-FORM-ACTIONS | Button + ActionButton | Quay lại / Tiếp tục | Clear recipient khi quay bước0, tạo intent khi tiếp tục |
| TRANSFER-RECEIPT | Receipt | Money amount giữa, Descriptions một cột Người nhận/Tài khoản nhận/Nguồn/Lời nhắn | Dùng ở review/OTP/unknown/result |
| TRANSFER-PIN | CodeField secret | PIN giao dịch hoặc Nhập lại PIN giao dịch | Xác nhận chuyển hoặc đối soát cùng yêu cầu |
| TRANSFER-OTP | TransferOtp | Warning tiền chưa chuyển, ErrorPanel, CodeField OTP, Countdown, CTA Xác nhận OTP | Confirm-otp theo source |
| TRANSFER-CHECK | Button | Kiểm tra trạng thái | Query result |
| TRANSFER-UNKNOWN | Alert warning + Receipt | Chưa xác định kết quả, link Tra cứu lịch sử | Query nếu có result, nếu thiếu result nhập PIN gửi lại cùng intent |
| TRANSFER-RESULT | Result + Status + failure Alert | COMPLETED success, FAILED error, EXPIRED warning | Chi tiết giao dịch, Giao dịch mới khi terminal |

## 4. Style và kích thước

Steps max-width740, margin0 auto 28. Panel narrow max644/body28×32. Recipient flex column gap6/padding16, nền #F6F9FD/border #E5EDF7/radius9/margin-bottom24. Receipt và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Form-actions border-top và justify-between. Result theo Ant theme.

## 5. Trạng thái và tương tác

Editing step0 resolver được version guard để bỏ response cũ. Step1 chọn source/amount/memo, submit tạo key rồi review. Review yêu cầu PIN. Unknown giữ intent, hướng dẫn tra soát. OTP render TransferOtp với deadline từ server. OTP confirm unknown/409 cố query trạng thái rồi giữ UI unknown nếu query lỗi. Result hiển thị status/failureCode và receipt. Giao dịch mới clear flow/form/recipient. BeforeUnload cảnh báo khi unknown hoặc đang busy. Request lock/cooldown theo useAction.

## 6. Responsive

Steps vẫn ngang vì responsive=false. Tối đa767 step title10px, icon24×24/line-height22, padding start6, icon margin-end5. Panel body24×20, receipt20×16/amount27px, form-actions wrap. Footnote11px và customer bottom nav theo layout.

## 7. Accessibility và nội dung

Form có label, scrollToFirstError ở bước nhập/OTP, CodeField secret/OTP. Các trạng thái có text thay vì chỉ màu. Wizard Steps hiện tại không có onChange, người dùng dùng CTA. Cần QA keyboard qua state và focus khi step/result đổi.

## 8. Cập nhật khi triển khai

FE-03-01 sửa flow và DTO, FE-03-02 cập nhật result/recovery/reload, GAP-02/08/09. Các giá trị 8–34, 2.000–10.000.000, memo140 và OTP sau create trong mục component mô tả source; giá trị triển khai đích được cập nhật theo FE-03 và OpenAPI.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Step-up 428 trước create theo contract mới

### Trạng thái PENDING và receipt theo reference của contract mới
