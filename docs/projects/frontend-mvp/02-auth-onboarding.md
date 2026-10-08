# FE 02 — Auth, onboarding và PIN

Trạng thái: thiết kế. Phụ thuộc FE 01, BE 03. Nguồn: [OpenAPI](../../../contracts/openapi.yaml), [API baseline](../../baseline/api-and-team-contract.md), [roadmap](00-roadmap.md).

## Màn hình và API

Các path trong bảng có prefix `/api/v1`. DTO lấy từ generated types.

| Màn hình/hành động | API | Payload/kết quả |
|---|---|---|
| Đăng ký: gửi mã | POST /auth/register/send-otp | SendOtpRequest: bắt buộc phone và purpose REGISTRATION; nhận TTL |
| Đăng ký: xác nhận | POST /auth/register | phone, email, password, fullName, otp, address tùy chọn; 201 RegistrationResponse |
| Đăng nhập | POST /auth/login | phone/password; 200 LoginResponse + cookie |
| Recovery: gửi mã | POST /auth/recover/initiate | identifier/channel SMS hoặc EMAIL; 200 chung |
| Recovery: xác minh mã | POST /auth/recover/verify | identifier/channel/otp; 200 trả resetToken và expiresInSeconds=300 |
| Recovery: đặt mật khẩu | POST /auth/recover/confirm | resetToken/newPassword; 200 MessageResponse |
| Đặt PIN | POST /customers/me/pin/setup | pin/confirmPin; 200 |
| Đổi PIN | POST /customers/me/pin/change | currentPin/newPin/confirmNewPin; 200 |
| Quên PIN: gửi mã | POST /customers/me/pin/forgot/initiate | Không tự thêm identifier/body chưa có schema |
| Quên PIN: xác nhận | POST /customers/me/pin/forgot/confirm | otp/newPin/confirmNewPin; 200 |

## Validation chung

Phone string khớp `^0[3-9][0-9]{8}$`, không đổi sang number; email theo schema, tối đa 254; fullName 1–120, address tối đa 300. Mật khẩu đăng ký/reset 12–128; login chấp nhận 1–128 theo LoginRequest. Không tự thêm quy tắc ký tự đặc biệt hoặc trim mật khẩu. PIN/OTP đúng 6 số và giữ số 0 đầu.

Confirm password ở FE chỉ kiểm tra trùng và không gửi field thừa. Các confirmPin/confirmNewPin có trong schema phải gửi. Ant Form validation phục vụ UX; server error vẫn có quyền phủ quyết.

## Luồng và trạng thái

Đăng ký: nhập phone → gửi OTP → nhập thông tin còn lại và OTP → submit → thành công hiển thị account masked, điều hướng login. Không tự login. Phone thay đổi làm vô hiệu bước OTP hiện tại trên UI; submit gắn đúng phone đã gửi mã. Duplicate phone/email hiện lỗi 409 tương ứng; OTP_INVALID không xóa toàn bộ thông tin đã nhập. Resend đăng ký qua endpoint đã có, tuân 429; không áp dụng resend này cho OTP transfer.

Login: idle/submitting/invalid/rate-limited/success. Unknown phone và wrong password cùng thông báo chung; không tra cứu tồn tại. Tắt double submit. Nhận UserSummary rồi chọn workspace theo roadmap; CUSTOMER chưa có PIN vào setup. Role chỉ dùng điều hướng, backend vẫn kiểm tra request.

Recovery: chọn SMS/EMAIL → nhập identifier → generic confirmation → màn OTP riêng → verify thành công mới mở form mật khẩu mới hai lần. Chuyển channel/identifier hoặc yêu cầu OTP mới khởi tạo lại flow và làm resetToken cũ không còn dùng được. Nội dung luôn “Nếu thông tin đã đăng ký, mã OTP sẽ được gửi tới kênh bạn chọn”, không tuyên bố tìm thấy account. `400 OTP_INVALID` ở verify bao gồm identifier không tồn tại. FE giữ resetToken chỉ trong memory, không lưu URL/browser storage/query cache/log; reload sau verify phải bắt đầu lại. `400 RECOVERY_TOKEN_INVALID` ở confirm dẫn về bước yêu cầu OTP mới, không thử dùng lại token. Thành công xóa phiên UI và về login; các refresh session cũ bị thu hồi nhưng access token cũ có thể còn sống đến TTL, không mô tả force logout tức thời mọi request.

PIN: setup trước transfer; change cần PIN hiện tại, reset cần OTP phone. Thành công tải lại GET /customers/me và đồng bộ isPinSet presentation state. PIN_LOCKED không có trường remaining-seconds chuẩn thì không tạo countdown chính xác từ thời điểm nhận lỗi; thông báo khóa tạm và cho thử lại theo server. Không thể reset PIN bằng quyền Operator.

Mọi form có idle/submitting/server-error/success. Countdown OTP chỉ là hướng dẫn dựa TTL response; server quyết định hết hạn. Hỗ trợ paste và bàn phím số, không tự submit OTP. Xóa secrets khi hoàn tất, logout hoặc rời flow; tránh capture chúng trong ảnh/video test.

## Acceptance và mock

- Đăng ký thành công/duplicate/OTP sai-hết hạn; kiểm tra payload không có confirm password thừa.
- Login phone, role đơn/đa role, staff alias không gọi customer endpoints, double click một request.
- SMS và EMAIL recovery có cùng UI cho identifier có/không tồn tại; hết hạn/used OTP và 429.
- Setup/change/reset PIN, PIN_LOCKED, profile được refetch sau success; không bypass route để transfer chưa có PIN.
- Auth flow dùng được tại 360px và bằng bàn phím; lỗi giữ field không nhạy cảm phù hợp.

BE thật cần local mailbox được guard theo BE spec để test OTP; UI sản phẩm không gọi mailbox. Không có runtime evidence ở phase này.
