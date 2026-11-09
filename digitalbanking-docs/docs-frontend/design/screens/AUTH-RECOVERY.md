# AUTH-RECOVERY — Khôi phục mật khẩu

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | AUTH-RECOVERY |
| Trạng thái UI | Có source UI |
| Route trong code | /recover |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/auth/AuthPages.tsx dòng 99–334](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/auth/AuthPages.tsx#L99-L334)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

AuthLayout → heading/subtitle Bước n/3 → ErrorPanel → request channel/identifier hoặc OTP hoặc new-password → CTA block → footer. Stage success/expired thay toàn bộ nội dung flow.

## 3. Component và cấu hình

| ID | Component | Cấu hình / nội dung | Hành động |
|---|---|---|---|
| RECOVER-CHANNEL | Segmented block | SMS, EMAIL, aria-label Kênh nhận OTP | Đổi channel reset form/proof |
| RECOVER-ID | Input | Label Số điện thoại hoặc Email theo channel, name identifier, type tel/email | Validation phoneRules/emailRules |
| RECOVER-OTP | Alert info + CodeField + Countdown | Thông báo chung Nếu thông tin đã đăng ký..., name otp | Verify để đến password |
| RECOVER-NOTE | Note | Sau khi xác minh OTP, bạn sẽ đến bước đặt mật khẩu mới | Tĩnh |
| RECOVER-NEW | Input.Password | newPassword, autocomplete new-password, 12–128 ký tự | Nhập mật khẩu mới |
| RECOVER-CONFIRM | Input.Password | confirmPassword, phụ thuộc newPassword, matchRule | Nhập lại mật khẩu |
| RECOVER-CTA | ActionButton block | Yêu cầu mã OTP / Xác minh OTP / Cập nhật mật khẩu, icon Safety/Check | Submit theo stage |
| RECOVER-RESTART | Button link | Đổi thông tin / yêu cầu mã mới khi OTP | Reset identity/proof/form |
| RECOVER-SUCCESS | Result success | Đã cập nhật mật khẩu, Đến đăng nhập | /login |
| RECOVER-EXPIRED | Heading + warning + block button | Xác minh đã hết hiệu lực, Xác minh lại | Restart |

## 4. Style và kích thước

Dùng AuthLayout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]]. h1/auth-sub/auth-row/auth-bottom, Form/Input/ActionButton và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. auth-sub 13 px, margin 9px 0 25px, auth-bottom border-top, margin-top 24/padding-top 22, font 12. Channel control đặt trước identifier có .spaced margin-top 24. Password stage có alert success trên fields.

## 5. Trạng thái và tương tác

Request → OTP → password → success. Server TTL chuyển expiresAt. Proof hết hạn khi password hoặc RECOVERY_TOKEN_INVALID chuyển expired. OTP/password/confirm được reset sau request liên quan. ErrorPanel giữ lỗi, request thông báo anti-enumeration. Footer OTP dùng restart, các stage khác link /login.

## 6. Responsive

AuthLayout chuyển căn trên và card padding 24×22 khi tối đa 767 px. Form dọc và nút block giữ đầy chiều rộng card. CodeField max-width 100% trên mobile. Breakpoint theo shared layouts.

## 7. Accessibility và nội dung

Label/input type theo channel, Segmented có aria-label. Form scrollToFirstError, confirm validation liên kết newPassword. Cần QA live announcements/focus sau đổi stage.

## 8. Cập nhật khi triển khai

GAP-01 xác định API recovery trong phạm vi mới. File này sở hữu cả request, OTP, password, expired và success trên cùng route. Cập nhật từng state tại đây khi code thay đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
