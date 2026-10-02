# FE 03 — Dashboard, profile và tài khoản

Trạng thái: thiết kế; phụ thuộc FE 01–02, BE 04. Nguồn [OpenAPI](../../../contracts/openapi.yaml), [roadmap](00-roadmap.md).

## Nội dung và mapping

| Vùng | API | Dữ liệu được dùng |
|---|---|---|
| Profile/header | GET /api/v1/customers/me | fullName, phone, email, isPinSet, createdAt |
| Thẻ tài khoản/dashboard | GET /api/v1/accounts | AccountPage.items, masked number, balance, currency, status |
| Chi tiết tài khoản | GET /api/v1/accounts/{accountId} | Account, openedAt |

Dashboard có thông tin chào khách, tài khoản mặc định và lối tắt chuyển tiền/lịch sử. MVP một default account nhưng render danh sách theo schema. Không thêm biểu đồ thu/chi, tổng giao dịch tháng hoặc thông báo chưa có endpoint. Profile chỉ đọc, không có nút lưu/cập nhật hay upload avatar khi contract chưa hỗ trợ.

Số dư định dạng Việt Nam từ chuỗi chính xác; không làm tròn hay quy đổi. AccountNumberMasked chỉ hiển thị; không có full-number copy/QR. Không tái sử dụng customerId alias của staff để fetch tài khoản.

## State và quyền

Loading skeleton; empty account có lời giải thích và nút tải lại, không tự tạo account. GET lỗi hiển thị retry; profile lỗi không khiến balance giả thành 0. Dữ liệu cũ khi refetch được giữ cùng thông báo đang cập nhật; lỗi refetch báo có thể chưa mới nhất.

ACTIVE: có thể mở flow transfer nếu PIN đã có. BLOCKED/CLOSED: hiển thị rõ trạng thái, không khuyến khích tạo transfer; lịch sử vẫn có lối vào. Backend luôn revalidate vì trạng thái có thể thay đổi sau khi UI tải. 404 account detail hiển thị không tìm thấy/không truy cập được, không xác nhận ownership người khác.

## Cache và bố cục

Query keys userId + profile/accounts/accountId. Refetch sau transfer success và khi người dùng chủ động cập nhật; không tự trừ/cộng balance. Staff seed/block ở phiên khác không đảm bảo cập nhật realtime; không hứa websocket. Khi quay lại trang dùng cơ chế query refetch đã cấu hình và phản ánh trạng thái mới.

Mobile một cột, nút chuyển tiền dễ chạm; desktop thẻ account và panel profile. Không dùng màu làm dấu hiệu trạng thái duy nhất; nhãn số dư/tài khoản có nghĩa khi đọc bằng screen reader.

## Acceptance

Mock profile/accounts success, loading độc lập, empty, 401, concealed 404, 503 và stale refetch. Test số dư lớn không mất chữ số; BLOCKED/CLOSED không mất lịch sử; deep link account của người khác không hiện dữ liệu cache. Sau transfer refetch số dư từ server. Chạy viewport 360/768/1024/1440 và BE read endpoints khi sẵn sàng. Chưa có kết quả thực thi.
