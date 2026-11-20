# GAP-03 — Phiếu chốt tìm khách hàng theo số điện thoại

## Quyết định FE ngày 20/11/2026

Người phụ trách FE yêu cầu “tìm khách hàng theo số điện thoại hãy giúp tôi thực hiện task” và chốt “Đúng, làm phần đọc bằng ID trước” cho FE-06-01-MOCK: preview staff độc lập đọc bằng `customerId` fixture; ô số điện thoại báo chờ contract, nút tra cứu khóa. Đây là lựa chọn UX và phạm vi preview, không xác nhận API hoặc quyền BE.

## Nguồn hiện có

- SRS FR-049/AC-027 yêu cầu staff xem customer/account/transaction trong phạm vi quyền, ngoài phạm vi 403.
- 07 §8.2.4–5 và OpenAPI v1/v2 đã có GET `/admin/customers/{customerId}` và GET `/admin/customers/{customerId}/accounts` với response đã mask.
- 07 OI-API-08 ghi tìm kiếm diện rộng còn mở và **đề xuất** `GET /admin/customers?q=`; YAML v1/v2 chưa có operation search. OI-API-18 về tìm user/staff cho FE-08 là vấn đề riêng.
- Security phân loại số điện thoại là dữ liệu confidential, không ghi bản rõ vào audit/log. GAP-07 còn thiếu matrix/capability staff thống nhất.

## BE/BA/Security cần chốt trước khi mở nút tìm

1. Tìm bằng số điện thoại chính xác hay một phần? Quy tắc chuẩn hóa số, định dạng đầu vào, nhiều customer trùng số, trạng thái customer nào được tìm và giới hạn kết quả là gì?
2. Endpoint/method/version chính thức, tên tham số, schema request/response, phân trang và error 400/403/404/429/5xx. Đề xuất OI-API-08 không tự động thành contract. Response trả ID và field masked nào đủ để chọn customer và gọi hai GET hiện có?
3. Actor/permission và scope được phép tìm; khác biệt giữa không tìm thấy và ngoài scope; kiểm soát truy vấn diện rộng và rate limit. Cập nhật GAP-07/matrix nếu cần.
4. Cách truyền số điện thoại để tránh xuất hiện bản rõ trong browser history, URL, access log, telemetry và audit. Nếu chọn GET query, cần chỉ rõ biện pháp che/redact; nếu đổi method phải phản ánh trong 07/YAML.
5. Cung cấp contract được owner review, version giao FE, ví dụ success/empty/forbidden/validation/rate-limit và môi trường/fixture tổng hợp cho kiểm chứng.

## Cổng FE

Sau khi BA/BE/Security chốt và đồng bộ 07/OpenAPI version giao FE, FE sẽ triển khai search client/MSW theo operation chính thức, mở nút tra cứu, kiểm tra input/error/masking và browser flow. FE-06-01-MOCK hiện chỉ chứng minh read bằng ID; không đóng GAP-03 hoặc nghiệm thu quyền BE. Nguồn `targetUserId` cho FE-08 vẫn phải chốt riêng theo OI-API-18.
