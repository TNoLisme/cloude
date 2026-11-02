Thiết kế UI/UX cho một ứng dụng web “Digital Banking Simulator” — hệ thống ngân hàng mô phỏng, chỉ dùng tiền VND giả lập, không giao dịch tiền thật. Giao diện bằng tiếng Việt. Đây là bản thiết kế để một lập trình viên frontend triển khai bằng React, TypeScript và Ant Design, vì vậy hãy ưu tiên bố cục rõ ràng, component thông dụng, ít hiệu ứng và dễ xây dựng. Không thiết kế như một ứng dụng fintech quá nhiều đồ họa.

Mục tiêu thị giác: hiện đại, đáng tin cậy, chuyên nghiệp nhưng không nhàm chán. Theme sáng; xanh đậm làm màu thương hiệu, nền xám rất nhạt, thẻ nội dung trắng, điểm nhấn xanh dương vừa phải. Có thể bắt đầu với navy #173B5E, blue #2868B2, background #F5F7FA; màu thành công, cảnh báo và lỗi phải dễ phân biệt và có nhãn chữ. Dùng font hỗ trợ tiếng Việt tốt, hệ khoảng cách 8px, bo góc vừa phải, viền nhẹ và bóng rất ít. Không dùng gradient lớn, hiệu ứng kính, biểu đồ trang trí, quá nhiều màu hoặc icon phức tạp. Luôn hiển thị nhãn “Mô phỏng — không sử dụng tiền thật” ở vị trí dễ thấy nhưng không lấn át nội dung.

Tạo một trang “Design system” gồm màu, typography, spacing, button, input, select, tabs, card, table, status tag, notification/error panel, loading skeleton, empty state và confirmation dialog. Sử dụng Auto Layout và component/variant để các màn hình nhất quán. Trạng thái không chỉ thể hiện bằng màu; luôn có chữ và icon phù hợp. Form phải có label rõ, lỗi ở gần trường nhập, nút chính nổi bật và thứ tự thao tác dễ hiểu.

Thiết kế ba bố cục dùng chung:
1. Auth: form gọn ở giữa, phần giới thiệu ngắn và nhãn mô phỏng.
2. Customer: desktop có sidebar; mobile có thanh điều hướng dưới gồm Tổng quan, Chuyển tiền, Lịch sử, Hồ sơ.
3. Staff: desktop có sidebar theo quyền, vùng nội dung đủ rộng cho bộ lọc và bảng; màn hình nhỏ thu gọn menu, bảng cuộn trong vùng bảng chứ không làm toàn trang cuộn ngang.

Tạo prototype có thể bấm qua các luồng chính sau:

A. Xác thực:
- Đăng nhập bằng số điện thoại và mật khẩu; có liên kết Đăng ký, Quên mật khẩu.
- Đăng ký: nhập số điện thoại, gửi và nhập OTP; tiếp tục nhập họ tên, email, mật khẩu; hiển thị kết quả tạo tài khoản rồi dẫn về đăng nhập, không tự đăng nhập.
- Khôi phục mật khẩu: chọn nhận OTP qua SMS hoặc email, nhập thông tin tương ứng, nhập OTP và mật khẩu mới. Sau khi yêu cầu mã, dùng thông báo chung “Nếu thông tin đã đăng ký, mã OTP sẽ được gửi tới kênh bạn chọn”; không tiết lộ tài khoản có tồn tại hay không.
- Sau khi tải lại trang, có trạng thái “Đang khôi phục phiên”. Khi khôi phục thành công, điều hướng theo vai trò Customer/Staff; khi phiên hết hạn, về đăng nhập. Người có nhiều khu vực hợp lệ được chọn tại màn “Chọn khu vực làm việc”.

B. Customer:
- Tổng quan: lời chào, tài khoản VND, số dư dễ đọc, trạng thái ACTIVE/BLOCKED, lối tắt Chuyển tiền và Lịch sử. Tiền hiển thị kiểu Việt Nam, ví dụ “5.000.000 ₫”.
- Chi tiết tài khoản và hồ sơ chỉ đọc; chỉ hiển thị số tài khoản đã che theo dữ liệu có sẵn. Không tạo nút sao chép số tài khoản đầy đủ hoặc mã QR.
- Thiết lập/đổi/quên PIN giao dịch: PIN 6 chữ số; quên PIN xác minh bằng OTP điện thoại.
- Chuyển tiền theo từng bước rõ ràng: nhập số tài khoản người nhận và xác minh → nhập số tiền, lời nhắn → màn kiểm tra lại thông tin và nhập PIN → kết quả. Số tiền hợp lệ từ 2.000 đến 10.000.000 ₫.
- Với số tiền không quá 5.000.000 ₫: kết quả có thể hoàn tất ngay. Với số tiền trên 5.000.000 ₫: chuyển sang màn OTP riêng, hiển thị trạng thái “Chờ xác thực — tiền chưa được chuyển”, thời hạn mã 2 phút; chỉ hiện thành công sau khi server xác nhận.
- Thiết kế các biến thể kết quả: hoàn tất, OTP sai có thể nhập lại, OTP hết hạn, giao dịch thất bại vì thiếu tiền hoặc tài khoản không còn đủ điều kiện, và “Chưa xác định kết quả — kiểm tra trạng thái giao dịch”. Giao dịch FAILED không có nút nhập lại OTP trên cùng giao dịch. Không tự hiển thị số dư đã trừ/cộng trước khi nhận kết quả từ server.
- Lịch sử giao dịch: danh sách hoặc bảng với chiều tiền vào/ra, người đối ứng, số tiền, thời gian, trạng thái; có lọc trạng thái và thời gian, nút “Tải thêm”. Chi tiết hiển thị AWAITING_OTP, COMPLETED, EXPIRED hoặc FAILED cùng thông tin phù hợp. Người nhận chỉ thấy giao dịch tiền vào sau khi COMPLETED.

C. Staff:
- Operator: màn tra cứu khách hàng bằng chính xác số điện thoại hoặc email; không thiết kế danh sách toàn bộ khách hàng. Kết quả gồm thông tin khách và các tài khoản.
- Tạo khách tại quầy: nhập số điện thoại, email, họ tên, mật khẩu ban đầu; gửi OTP đến điện thoại khách và xác nhận trước khi tạo.
- Trong kết quả tra cứu, Operator có thể nạp số dư mô phỏng, khóa hoặc mở khóa tài khoản. Các hành động quan trọng có dialog xác nhận; khóa/mở khóa bắt buộc nhập lý do. Trạng thái BLOCKED phải dễ nhận biết.
- Auditor/Admin: bảng audit chỉ đọc với bộ lọc và “Tải thêm”.
- Operator/Auditor/Admin: bảng cờ rủi ro chỉ đọc, thể hiện rule LARGE_TRANSFER hoặc HIGH_FREQUENCY. Cờ rủi ro là cảnh báo, không được diễn đạt thành kết luận gian lận. Không thiết kế nút duyệt, xóa hoặc xuất báo cáo.

Thiết kế các trạng thái loading, trống, lỗi mạng, 401, 403, 404 và 409 cho những màn quan trọng. Lỗi quan trọng phải hiển thị trong trang, không chỉ bằng toast. OTP và PIN cho phép dán mã, giữ số 0 đầu và không tự gửi ngay khi vừa nhập đủ 6 chữ số. Không đặt OTP, PIN, mật khẩu hay token vào URL hoặc ví dụ dữ liệu hiển thị. Dùng tên, số tài khoản và giao dịch giả lập.

Kích thước cần có: Customer/Auth desktop 1440px và mobile 360–390px; Staff desktop 1440px và 1024px, kèm phương án thu gọn ở 768px. Đảm bảo không mất nút hành động, chữ dễ đọc và có thể thao tác bằng bàn phím.

Kết quả mong muốn: một trang design system, các frame màn hình được đặt tên theo vai trò/luồng/trạng thái, và prototype cho luồng demo chính “Đăng ký → Đăng nhập → Đặt PIN → Operator nạp số dư → Customer chuyển tiền → Xem lịch sử”. Nếu không thể tạo tất cả trong một lượt, hãy hoàn thành design system và luồng demo chính trước, sau đó mở rộng các màn phụ với cùng quy tắc thiết kế. Không tự thêm tính năng ngoài danh sách trên, đặc biệt không thêm thông báo nhận tiền realtime, WebSocket, hủy/gửi lại OTP chuyển tiền, dark mode, biểu đồ tổng hợp, thanh toán ngoài hệ thống hoặc giao dịch tiền thật.