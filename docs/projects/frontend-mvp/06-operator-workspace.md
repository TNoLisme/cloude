# FE 06 — Workspace Operator

Trạng thái: thiết kế; FE 01–02, BE 03–04. Quyền OPERATOR hoặc ADMIN. Nguồn [OpenAPI](../../../contracts/openapi.yaml), [API baseline](../../baseline/api-and-team-contract.md).

## Tra cứu và tạo khách

`/staff/customers`: chọn SĐT hoặc email, nhập chính xác và bấm tìm. GET `/api/v1/operator/customers` gửi đúng một query param phone/email; không query khi ô rỗng, không wildcard, không tải tất cả. Kết quả OperatorCustomerView gồm profile, isPinSet và accounts. Raw phone/email không đưa URL trình duyệt/localStorage/log, tránh persist kết quả tra cứu. Đổi điều kiện clear kết quả cũ; response chậm không được ghi đè request mới. 404 CUSTOMER_NOT_FOUND là empty search, 403 là permission, 429 có Retry-After.

`/staff/customers/new`: phone/email/fullName/initialPassword/address và OTP. POST `/api/v1/operator/customers/send-otp` chỉ gửi phone; POST `/api/v1/operator/customers` gửi OperatorCreateCustomerRequest. OTP xác nhận SĐT khách, không phải SĐT nhân viên. Validate cùng schema như registration: password 12–128, tên 1–120, address tối đa 300, PIN không có field. Không thêm DOB/giấy tờ KYC.

201 hiển thị RegistrationResponse, account masked, lối tra cứu và seed theo accountId. 409 phone/email duplicate, 400 OTP_INVALID map field/form. Network failure không tự submit lại tạo khách; cho tra cứu trước để kiểm tra có tạo thành công không. Không hiển thị lại mật khẩu trên trang kết quả hoặc audit UI.

## Thao tác tài khoản trong kết quả tra cứu

| Hành động | API / request | Hành vi |
|---|---|---|
| Seed demo | POST /api/v1/operator/accounts/{accountId}/seed-balance; amount/currency/reference + key | Chỉ ACTIVE; review trước submit; success 201 hoặc replay 200 |
| Khóa | POST /api/v1/operator/accounts/{accountId}/block; reason | ACTIVE → BLOCKED |
| Mở khóa | POST /api/v1/operator/accounts/{accountId}/unblock; reason | BLOCKED → ACTIVE |

Seed là tiền mô phỏng; amount nguyên >0 đến 100.000.000, reference 1–100, currency VND. Một key cho một intent; timeout giữ payload/key, người dùng retry cùng key; không tự tăng balance. Replay đọc Idempotency-Replayed; 409 key reused là lỗi. Không có seed-history API để tự đối soát chỉ bằng balance; retry cùng key là đường chính khi chưa rõ kết quả.

Block/unblock dialog hiển thị khách/tài khoản/trạng thái hiện tại và lý do bắt buộc 1–500 ký tự, không chỉ whitespace. CLOSED không mở lại bằng unblock. Request lặp có thể trả 200 current state; refresh query sau success. Timeout cho đọc lại account và retry cùng hành động nếu cần; không tự đảo sang hành động ngược.

Sau seed/block/unblock: invalidate account detail và lookup của khách đang xem; lấy balance/status từ BE. Sau tạo khách clear secrets. Không cho client chọn actor/role hoặc sửa customerId làm quyền truy cập.

## Layout và acceptance

Staff sidebar, bộ lọc trên đầu, kết quả profile và danh sách account bên dưới. Chi tiết/drawer hành động đủ chỗ ở 1024/1440; ở 360/768 filter xuống hàng, bảng cuộn vùng riêng, dialog không mất footer/nút.

Test Customer/Auditor truy cập route bị chặn và BE 403 vẫn được xử lý; no-filter/both-filter không được gửi. Mock duplicate/OTP/429, repeated seed/timeout, block race/CLOSED. E2E Operator tạo khách → seed → khóa → Customer transfer bị BE từ chối → mở khóa. Không ghi test đã chạy trước khi có môi trường BE thật.
