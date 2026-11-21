# FE-03-01-MOCK — kết quả kiểm browser

Ngày 22/11/2026, Chrome Windows đã mở `/customer/transfers/new?transferPreview=v2` trên Vite mock `127.0.0.1:5174`. Form lấy tài khoản nguồn tổng hợp `**** **** 3001`, nhận số tài khoản `1234567890` và Money `150000.00`. Bước xem lại hiển thị nguồn masked, số nhận ghi rõ chưa xác minh, `150.000,00 ₫` và nút Gửi lệnh disabled. Kịch bản “Danh sách rỗng” không còn account nguồn và hiện thông báo tương ứng. Không lưu screenshot file.

Focused component/MSW 2/2 pass; `api:check` v1/v2 và `git diff --check` đạt. Lint/typecheck/build đã đạt ở [run-01](../2026-11-21-fb0db95/run-01.md), không chạy lại vì không đổi code. Lệnh/kết quả và phạm vi browser tại [run-02](run-02.md).

UI mock chờ người phụ trách FE review và thiết kế cuối. Không có POST transfer/OTP/retry/receipt; GAP-02/08/09, FE-03-01 cha, FET-04–07 và tích hợp BE vẫn mở.
