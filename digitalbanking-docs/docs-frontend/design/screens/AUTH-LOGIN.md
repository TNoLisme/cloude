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

Đã có trong preview v2 ở mục bổ sung FE-01-01 bên dưới; chưa chuyển chế độ API thật.

### Passcode/FIDO và device method picker

Chưa triển khai; FE-01-02/GAP-04 vẫn mở.

## Bổ sung FE-01-01 — 17/11/2026, chưa duyệt

Source working tree trên frontend@6bceb5d: `digitalbanking-frontend/src/features/auth/v2/AuthScreens.tsx` (V2LoginForm/AuthError), `AuthFlow.tsx`, `src/mocks/AuthPreview.tsx`, entry `src/main.tsx`. Mở `/login?authPreview=v2` trong dev mock. Phần mô tả phone/password phía trên thuộc viewer legacy; đường preview mới dùng username/password theo 07a và YAML v2, không chuyển user v2 thành DTO/Bearer legacy.

Card dùng lại `.auth/.authCard` trong AppLayout.module.css: rộng 460px, max-width100%, padding32; <=767px padding24×22, wrapper căn trên. Thứ tự: h1 → subtitle → lỗi an toàn/mã hỗ trợ có thể mở → form dọc → Đăng nhập → thông báo đăng ký/recovery chờ contract → Kiểm tra lại phiên. RHF/Zod required username tối đa64, password tối đa128; username lowercase ở controller, giữ nguyên password rồi xóa sau request. Submit khóa chống gửi đôi; 429 chỉ đếm chờ theo Retry-After, không tự retry.

Login200 đi màn phiên chung `/workspace?authPreview=v2`; 202 đi AUTH-CHALLENGE; 401 credential/423 account hiện lỗi, không coi OTP sai là session expired. Field errors chỉ map username/password có allowlist; không render raw Problem.detail. Label/htmlFor, autocomplete, aria-invalid/describedby, role alert và focus h1 có trong code. Register/recovery/passcode/FIDO chưa có action mới. Component/mock tests đạt theo report; browser/responsive/keyboard chưa kiểm chứng do công cụ truy cập localhost bị chặn. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

## Bố cục theo phản hồi ảnh — 18/11/2026, chờ duyệt

Phần này thay mô tả khung card của preview v2 ngày17/11; viewer legacy giữ nguyên. User yêu cầu bố cục ảnh trái/form phải như ảnh đính kèm, dùng logo/tên dự án, bỏ CAPTCHA và hotline. Brand hiện tại là DB / Digital Banking, màu primary theo theme dự án, không dùng MB. “Mã kiểm tra” trong ảnh là CAPTCHA tại login; không thay OTP của bước202.

Source mới: `src/features/auth/v2/AuthSplitLayout.tsx` và `.module.css`, host `src/mocks/AuthPreview.tsx`. Desktop grid50/50, toàn trang nền trắng; nửa trái nền xanh nhạt với tên DIGITAL BANKING và artwork từ ảnh user, giữ tỷ lệ ảnh bằng pseudo-element và giới hạn vùng hiển thị nửa trái. Không chỉnh bitmap; asset nguyên gốc lưu `public/images/login-reference.png`. Mặt nạ CSS làm mềm biên trên/dưới. Hình là trang trí aria-hidden.

Nửa phải form max-width500px, căn giữa theo chiều dọc. BrandDB56px; lời chào → h1 Digital Banking → lỗi → label trên input56px/username/password → nút52px → ghi chú contract → kiểm tra phiên → Simulation ở footer. Labels bỏ dấu colon và dấu sao trang trí; required vẫn do schema/validation. Icon hiện/ẩn mật khẩu AntD, RHF/Zod/error binding/submit lock giữ nguyên. Không thêm CAPTCHA/hotline/đổi ngôn ngữ hoặc link legal chưa có đích.

<=1023px giảm padding/brand; <=767px ẩn artwork, form một cột padding24px, heading32px, giữ form và nội dung phản hồi. Toolbar fixture ở details thu gọn góc dưới trái “Điều khiển bản xem thử”, không chiếm vùng form. Browser IAB đã xem1440×1000,768×1024,360×800; có ảnh thật và số đo scrollWidth không vượt clientWidth trong các lần quan sát. Login200 → reload → logout → reload về login đã kiểm bằng fixture; OTP/đa tab/security/API thật chưa được kiểm lại toàn bộ. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]].
