# AUTH-LOGIN — Đăng nhập

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | AUTH-LOGIN |
| Trạng thái UI | Có source UI, có phần chờ cập nhật |
| Route trong code | /login |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/auth/AuthPages.tsx dòng 32–98](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/auth/AuthPages.tsx#L32-L98)

[Source frontend/src/styles.css dòng 217–237](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L217-L237)

UI source đăng nhập bằng phone/password. Login username, MFA và FIDO/passcode theo đặc tả FE-01 sẽ được cập nhật trong các mục chờ code phía dưới.

## 2. Bố cục và thứ tự vùng

AuthLayout → tiêu đề Chào mừng trở lại → subtitle → ErrorPanel → Form dọc → hàng trợ giúp/Quên mật khẩu → nút Đăng nhập → footer Đăng ký.

## 3. Component và cấu hình

| ID | Component | Cấu hình và dữ liệu | Hành động |
|---|---|---|---|
| LOGIN-TITLE | h1/p | Chào mừng trở lại, subtitle thực hành ngân hàng số | Tĩnh |
| LOGIN-ERROR | ErrorPanel | action.error, lỗi field phone/password được map | Hiện khi lỗi |
| LOGIN-PHONE | Form.Item/Input | Label Số điện thoại, name phone, type tel, autocomplete tel, placeholder Nhập số điện thoại | Required, regex phoneRules |
| LOGIN-PASSWORD | Input.Password | Name password, autocomplete current-password, placeholder Nhập mật khẩu | Required, max 128, reset sau request |
| LOGIN-HELP | .auth-row | Chú thích Đăng nhập bằng điện thoại và link Quên mật khẩu? | /recover |
| LOGIN-SUBMIT | ActionButton block | Đăng nhập + ArrowRightOutlined | Form submit, navigate landing replace |
| LOGIN-REGISTER | .auth-bottom | Bạn chưa có tài khoản? / Đăng ký | /register |

## 4. Style và kích thước

Dùng AuthLayout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]]. h1/auth-sub/auth-row/auth-bottom, Form/Input/ActionButton và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. auth-sub 13 px, margin 9px 0 25px, auth-bottom border-top, margin-top 24/padding-top 22, font 12.

## 5. Trạng thái và tương tác

Form dùng scrollToFirstError và useFormErrors. Submit đang chạy có spinner/disabled, cooldown đổi nhãn theo ActionButton. Thành công đi landing theo quyền/PIN trong source. Lỗi hiển thị ErrorPanel, password reset trong finally, phone được giữ.

## 6. Responsive

AuthLayout chuyển căn trên và card padding 24×22 khi tối đa 767 px. Form dọc và nút block giữ đầy chiều rộng card. CodeField max-width 100% trên mobile. Breakpoint theo shared layouts.

## 7. Accessibility và nội dung

Input có label và autocomplete theo source. ErrorPanel role alert. Input.Password dùng behavior mặc định Ant Design. Keyboard/focus validation cần kiểm chứng lại theo FET-01/14.

## 8. Cập nhật khi triển khai

FE-01-01/02 và FE-FND-02 thay session/client/field theo contract đích. GAP-01 chốt register/recovery, GAP-04 chốt FIDO. Giữ phần có code trong file cho đến khi component được sửa.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Login username và login MFA theo contract mới

### Passcode/FIDO và device method picker
