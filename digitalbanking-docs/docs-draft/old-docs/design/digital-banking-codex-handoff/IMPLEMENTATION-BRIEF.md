# Bàn giao triển khai Digital Banking Simulator

## Đọc trước khi sửa mã

1. BUSINESS-SPEC.md: yêu cầu nghiệp vụ gốc và các giới hạn của sản phẩm.
2. digital-banking-redesign.html: bản giao diện mới, mở trong trình duyệt và thử các luồng. Đây là prototype HTML/CSS/JavaScript, chưa phải ứng dụng React kết nối backend.
3. HUONG-DAN.md: design tokens, ánh xạ Ant Design, các kích thước và luồng demo.
4. Các ảnh redesign-*.png: tham chiếu trực quan desktop, mobile, chuyển tiền và staff 768px.

Yêu cầu nghiệp vụ quyết định hành vi thực tế. Prototype quyết định hướng trình bày; dữ liệu giả và cách chấp nhận OTP/PIN trong prototype không phải yêu cầu triển khai. Tệp Figma gốc chưa được cập nhật theo bản redesign vì hết hạn mức công cụ.

## Phạm vi đã có

- Tiếng Việt, theme sáng, React + TypeScript + Ant Design là định hướng ban đầu. Nếu dự án đang dùng kiến trúc khác, kiểm tra và báo ảnh hưởng trước khi thay công nghệ.
- Customer, Operator, Auditor, Admin; đăng ký, đăng nhập, chọn khu vực, quên mật khẩu; hồ sơ, tài khoản, PIN, chuyển tiền và lịch sử; nghiệp vụ nhân viên theo BUSINESS-SPEC.md.
- Customer/auth có desktop và mobile 360–390px. Staff cần 1440/1024/768px. Mobile hiện dùng cùng hệ thiết kế với bố cục thích ứng: điều hướng dưới, danh sách giao dịch, nội dung một cột.
- Prototype có thanh chọn vai trò/màn hình/kích thước dành cho xem thiết kế. Không đưa thanh này vào sản phẩm. Bộ chọn khu vực thực tế phải dựa trên quyền mà server trả về.
- Dùng component chung và tokens phù hợp thư viện hiện tại. Không chép nguyên khối HTML prototype vào ứng dụng.

## Quyết định đã chốt ngày 08/10/2026

1. Giữ UI mobile responsive như bản thiết kế hiện tại. Thiết kế UI mobile riêng sẽ làm sau, ngoài phạm vi triển khai lần này.
2. Quên mật khẩu dùng ba bước: chọn kênh/yêu cầu OTP → xác minh OTP → nhập mật khẩu mới và xác nhận mật khẩu mới → thành công → đăng nhập. Chỉ cho cập nhật mật khẩu sau khi backend xác minh OTP thành công. Người dùng xác nhận backend đã xử lý luồng tách bước này; kiểm tra hợp đồng API trong repo để tích hợp đúng, không tự thêm endpoint hoặc thay backend khi chưa có nhu cầu cụ thể.
3. Đây là yêu cầu mới thay thế mô tả quên mật khẩu gộp bước trong BUSINESS-SPEC.md gốc. Prototype HTML đã được cập nhật. Phải xử lý OTP sai/hết hạn, mật khẩu xác nhận không khớp và quyền reset hết hạn/không hợp lệ theo phản hồi backend. Không suy đoán thời hạn OTP khôi phục từ thời hạn OTP chuyển tiền.
4. HTML vẫn là demo: mọi mã đủ 6 chữ số được chấp nhận để minh họa bước xác minh; cờ xác minh nằm trong bộ nhớ chỉ phục vụ điều hướng. Khi triển khai thực tế phải dùng API xác minh và quyền reset do backend cấp, không dùng cờ frontend để quyết định quyền đổi mật khẩu. Tên endpoint/payload/cơ chế quyền reset chưa được cung cấp trong bộ thiết kế.

## Kiểm tra dự án trước khi triển khai

- Đọc AGENTS.md và hướng dẫn dự án; xác định framework, routing, component library, form validation, auth/session, state/query và cách gọi API.
- Tìm màn hình/component/service hiện có và tái sử dụng nơi phù hợp. Giữ các thay đổi của người dùng, không reset hoặc ghi đè ngoài phạm vi.
- Đọc backend controllers, API schema/OpenAPI, DTO, tài liệu và test liên quan nếu có. Không suy đoán tên endpoint, payload, trạng thái hoặc quyền.
- Lập bảng: màn hình/luồng → mã hiện có → API thực tế → phần cần làm → thiếu hoặc xung đột. Phân biệt thay đổi giao diện với thay đổi nghiệp vụ/backend.
- API chưa có phải được ghi rõ; không dùng mock để báo tính năng production đã hoàn tất. Hỏi khi cần quyết định nghiệp vụ hoặc thông tin không thể tìm trong repo; tiếp tục các phần độc lập.

## Ràng buộc cần giữ

- Luôn có nhãn “Mô phỏng — không sử dụng tiền thật”. Dữ liệu demo dùng thông tin giả; không đưa OTP/PIN/password/token vào URL hoặc log.
- OTP/PIN giữ số 0 đầu, cho phép dán, không tự gửi khi đủ chữ số. Server xác minh mã và quyền; không chấp nhận mọi mã 6 chữ số như prototype.
- Đăng ký thành công dẫn về đăng nhập, không tự đăng nhập.
- Khôi phục mật khẩu dùng thông báo chung, không tiết lộ tài khoản có tồn tại.
- Chuyển tiền 2.000–10.000.000 VND: đến 5.000.000 VND hoàn tất sau xác nhận server; trên mức đó có AWAITING_OTP, thời hạn 2 phút, tiền chưa được chuyển. Backend là nguồn quyết định ngưỡng và trạng thái.
- Không cập nhật số dư theo kiểu optimistic. COMPLETED/AWAITING_OTP/EXPIRED/FAILED và chưa xác định kết quả phải được hiển thị theo phản hồi thực tế. FAILED không có thao tác nhập lại OTP cho giao dịch đó. Không tạo giao dịch lần nữa khi chưa rõ kết quả lần gửi trước.
- Staff chỉ tra cứu chính xác theo phone/email; tạo tài khoản tại quầy cần OTP khách hàng; khóa/mở khóa cần lý do và xác nhận. Audit và risk flags chỉ đọc theo quyền. Kiểm tra phân quyền server, không chỉ ẩn nút.
- Làm đủ loading/empty/network/401/403/404/409 và validation phù hợp luồng. Tuân thủ các giới hạn còn lại trong BUSINESS-SPEC.md.

## Thứ tự triển khai

1. Khảo sát repo, đối chiếu API và lập kế hoạch theo nhóm màn hình.
2. Tokens, layout, navigation, component dùng chung và responsive.
3. Auth/session, hồ sơ và PIN; dùng API có thật.
4. Luồng Customer chuyển tiền → kết quả → lịch sử, bao gồm OTP và các trạng thái lỗi.
5. Operator nạp số dư/khóa/mở khóa/tạo tài khoản; audit và risk flags theo vai trò.
6. Kiểm tra desktop/mobile, các trạng thái và luồng demo hoàn chỉnh.

Cập nhật tiến độ, quyết định và thiếu sót còn lại trong một tài liệu của repo để các lượt làm việc sau tiếp tục được.

## Tiêu chí hoàn tất

- Giao diện bám prototype mới và tokens; mobile không tràn ngang, bảng staff chỉ cuộn trong vùng bảng; điều hướng hoạt động theo quyền.
- Luồng đăng ký → đăng nhập → tạo PIN → Operator nạp số dư → Customer chuyển tiền → lịch sử hoạt động với backend của dự án và dữ liệu mô phỏng được phép.
- Kiểm tra cả chuyển tiền đến 5 triệu và trên 5 triệu, OTP sai/hết hạn, FAILED, lỗi mạng và chưa xác định kết quả.
- Dùng các lệnh build/lint/typecheck/test có trong repo; thêm kiểm thử cho rủi ro nghiệp vụ thực tế. Ghi rõ lệnh đã chạy, kết quả và phần chưa thể kiểm tra.
- Bàn giao danh sách file thay đổi, API sử dụng, phần backend đã thay đổi nếu được yêu cầu, cách chạy và vấn đề còn thiếu. Không gọi hoàn tất nếu luồng vẫn dựa trên mock hoặc chưa nối API.

## Prompt gửi vào Codex trong dự án

Hãy triển khai giao diện Digital Banking Simulator trong repo này theo bộ tài liệu ở docs/design/digital-banking/. Đọc IMPLEMENTATION-BRIEF.md, BUSINESS-SPEC.md, HUONG-DAN.md và kiểm tra digital-banking-redesign.html cùng các ảnh tham chiếu trước khi sửa mã.

Trước tiên đọc hướng dẫn repo, khảo sát frontend/backend và lập bảng đối chiếu màn hình, component, API, quyền và trạng thái hiện có với yêu cầu. Nêu các thiếu sót hoặc xung đột, rồi triển khai từng giai đoạn theo brief, tái sử dụng kiến trúc hiện tại và giữ các thay đổi của tôi. Không đưa mock OTP/PIN/số dư hoặc thanh điều khiển xem thiết kế vào sản phẩm.

Giữ UI mobile responsive hiện có. Luồng quên mật khẩu đã chốt tách xác minh OTP và đặt/xác nhận mật khẩu mới thành hai màn; backend đã được người dùng triển khai. Kiểm tra và tích hợp API thực tế trong repo. Không tự suy đoán API chưa có. Tiếp tục các phần độc lập khi một phần cần làm rõ.

Kiểm tra build và các luồng trọng yếu bằng công cụ của repo; báo file thay đổi, kết quả kiểm tra, cách chạy và phần còn thiếu. Cập nhật tài liệu tiến độ để có thể tiếp tục ở lượt sau.
