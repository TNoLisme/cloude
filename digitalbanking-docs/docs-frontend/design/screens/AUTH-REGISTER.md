# AUTH-REGISTER — Đăng ký

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | AUTH-REGISTER |
| Trạng thái UI | Có source UI |
| Route trong code | /register |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/auth/AuthPages.tsx dòng 335–550](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/auth/AuthPages.tsx#L335-L550)

RegisterPage đã có UI ba bước trong source. Phạm vi/API sau refactor được theo dõi tại GAP-01, thiết kế hiện có được mô tả để người làm tiếp cập nhật đúng màn.

## 2. Bố cục và thứ tự vùng

AuthLayout → h1 theo stage → Bước n/3 → ErrorPanel/warning khi unknown → Form theo stage → CTA block → footer yêu cầu OTP mới/Đăng nhập. Sau thành công thay nội dung card bằng Result.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| REGISTER-STEP | h1/auth-sub | Tạo tài khoản mô phỏng → Xác minh số điện thoại → Thông tin tài khoản | Stage 0/1/2 |
| REGISTER-PHONE | Input tel | phone, label Số điện thoại, autocomplete tel, phoneRules | Gửi mã OTP |
| REGISTER-OTP | Alert info, CodeField, Countdown | Message OTP gửi đến số đăng ký, expiresAt từ response | Xác minh OTP |
| REGISTER-PROOF | Alert success | Số điện thoại đã được xác minh | Stage 2 |
| REGISTER-NAME | Input | fullName, required/whitespace, max 120, autocomplete name | Thu thập hồ sơ |
| REGISTER-EMAIL | Input email | emailRules, autocomplete email | Thu thập email |
| REGISTER-PASSWORD | Input.Password | 12–128 ký tự, autocomplete new-password | Reset sau submit |
| REGISTER-CTA | ActionButton block | Gửi mã OTP / Xác minh OTP / Tạo tài khoản | Submit theo stage |
| REGISTER-FOOTER | Button link + Link | Yêu cầu OTP mới khi stage >0, Đăng nhập | Reset stage hoặc /login |
| REGISTER-RESULT | Result success | Tạo tài khoản thành công, account masked, CTA Đến đăng nhập | /login |
| REGISTER-EXPIRED | h1/subtitle/Button | Quyền đăng ký đã hết hạn, Xác minh lại | Reset proof và stage |

## 4. Style và kích thước

Dùng AuthLayout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]]. h1/auth-sub/auth-row/auth-bottom, Form/Input/ActionButton và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. auth-sub 13 px, margin 9px 0 25px, auth-bottom border-top, margin-top 24/padding-top 22, font 12. Stage 1/2 dùng .flow-alert margin-bottom 24. Các field một cột, form preserve=false.

## 5. Trạng thái và tương tác

Stage 0 gọi send OTP và giữ phone. Stage 1 verify lấy proof, reset OTP. Stage 2 submit hồ sơ, password reset, REGISTRATION_TOKEN_INVALID quay stage0. Unknown ở stage2 hiện cảnh báo thử đăng nhập trước đăng ký lại. Proof hết hạn hiện màn Xác minh lại. Request/CTA bị lock khi busy/cooldown.

## 6. Responsive

AuthLayout chuyển căn trên và card padding 24×22 khi tối đa 767 px. Form dọc và nút block giữ đầy chiều rộng card. CodeField max-width 100% trên mobile. Breakpoint theo shared layouts.

## 7. Accessibility và nội dung

Form.Item có label, CodeField cho paste/giữ số 0 đầu. Title/thông báo từng bước nằm trong auth card. Cần QA thứ tự focus khi stage đổi và các alert.

## 8. Cập nhật khi triển khai

GAP-01 xác định contract/việc tiếp tục register, ghi task thực hiện trong tracker. Khi có thay đổi stage/field/validation, cập nhật từng component REGISTER-* và đặc tả FE-01.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
