# Digital Banking Simulator — bản giao diện mới

Mở `digital-banking-redesign.html` bằng trình duyệt để xem prototype. Không cần cài đặt hay kết nối mạng.

Thanh điều khiển phía trên thuộc bản xem thiết kế: chọn Customer, Operator, Auditor, Admin, Xác thực, Trạng thái hệ thống hoặc Design system; chọn màn hình và kích thước 1440, 1024, 768, 390 hoặc 360px.

## Luồng demo

1. Bấm **Bắt đầu luồng demo**; nhập thông tin giả lập, OTP gồm 6 chữ số và hoàn tất đăng ký. Tạo tài khoản dẫn về đăng nhập.
2. Đăng nhập, chọn **Khách hàng**, đặt PIN gồm 6 chữ số.
3. Chọn **Operator** trên thanh xem thiết kế. Tra cứu bằng `minhanh@example.test`, chọn nạp số dư và xác nhận dialog.
4. Chọn **Customer**, vào Chuyển tiền; xác minh người nhận, nhập số tiền, kiểm tra và xác nhận bằng PIN.
5. Với số tiền trên 5.000.000 ₫, prototype mở bước OTP; số dư giữ nguyên đến khi xác nhận. Vào Lịch sử để xem các trạng thái.

Các mã đủ 6 chữ số trong bản xem thử được coi là hợp lệ để minh họa chuyển màn. Dùng danh sách màn hình để xem OTP sai, hết hạn, FAILED, chưa xác định kết quả và lỗi hệ thống. Bản xem thử không kết nối máy chủ, không xác minh thông tin thật và không thực hiện giao dịch thật.

## Cập nhật quên mật khẩu · 08/10/2026

Giữ UI mobile hiện tại. Luồng mới: yêu cầu OTP → màn xác minh OTP → màn nhập và xác nhận mật khẩu mới → thành công → đăng nhập. Trong thanh xem thiết kế, chọn Xác thực để xem các màn OTP sai/hết hạn và phiên khôi phục hết hiệu lực. Demo chỉ chuyển bước sau khi bấm Xác minh OTP; mật khẩu xác nhận không khớp hiển thị lỗi tại trường. Backend tách bước đã được người dùng triển khai; bản HTML không kết nối backend và không xác minh mã thật. Xem IMPLEMENTATION-BRIEF.md để tích hợp API thực tế.

## Thiết kế

- Thẻ tài khoản có đường nhấn xanh, số dư 32–36px, số tài khoản được che, tag có chữ và icon.
- Menu có trạng thái đang chọn; header có khu vực, tên màn và nhãn mô phỏng.
- Form dùng vùng nội dung khoảng 480–560px, label rõ, lỗi gần trường nhập; vùng hành động riêng.
- Chuyển tiền có 4 bước; màn kiểm tra dùng bố cục phiếu giao dịch.
- Bảng có số tiền căn phải, dấu vào/ra, tag trạng thái, bộ lọc và Tải thêm.
- Mobile có thẻ tài khoản, danh sách giao dịch và điều hướng dưới. Staff 768px có sidebar thu gọn; cuộn ngang được giới hạn trong bảng.

### Thông số bàn giao

| Thành phần | Quy tắc |
| --- | --- |
| Thương hiệu / hành động | Navy `#173B5E`, Blue `#2868B2` |
| Nền / thẻ | `#F5F7FA` / `#FFFFFF` |
| Chữ / phụ / viền | `#223449` / `#65768A` / `#E1E7EF` |
| Thành công / cảnh báo / lỗi | `#237A52` / `#9C5B0A` / `#B53A3A` |
| Font | Inter, dự phòng Segoe UI và Arial hỗ trợ tiếng Việt |
| Chữ | Tiêu đề 28, tiêu đề vùng 20, tên mục 16, nội dung 14, phụ 12px |
| Khoảng cách / bo góc | Hệ 8px; khoảng cách 8/16/24/32/40; bo góc 8/12px |
| Ant Design tương ứng | Layout, Menu, Card, Form, Input, Input.Password, Select, Steps, Table, Tag, Alert, Modal, Skeleton, Empty, Result |

## Nhập vào Figma

Giải nén `digital-banking-figma-import.zip`, kéo các SVG vào canvas của tệp Figma. Có 37 màn chính và design system, đặt tên theo vai trò/luồng/kích thước. SVG chứa hình vector và chữ, không chứa ảnh chụp giao diện.

SVG không mang theo Auto Layout, component/variant hoặc liên kết prototype của Figma. Các tương tác nằm trong bản xem HTML. Tệp Figma gốc chưa được cập nhật vì công cụ Figma báo hết hạn mức Starter; chưa thể xác nhận các SVG sau khi nhập thực tế vào Figma.

## Kiểm tra

Luồng đăng ký → đăng nhập → PIN → Operator nạp số dư → Customer chuyển tiền → lịch sử đã được chạy trong trình duyệt. Đã kiểm tra số 0 đầu của OTP/PIN, không tự gửi khi đủ mã, bước OTP cho số tiền lớn, dialog nạp số dư, FAILED không có nút nhập lại OTP và thông báo khôi phục chung. Các màn được kiểm tra tràn ngang ở 1440/1024/768/390/360px; vùng bảng được phép cuộn trong khung.
