# Phiếu chạy FE-01-01 / run-02 — trình UI mock để duyệt

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok, Codex. User yêu cầu “đưa tôi duyệt UI mock”. Nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; working tree dirty gồm foundation/docs và UI/mock chưa commit như result. Mức: mở preview để người phụ trách FE review; không API thật. Đọc lại FE README, tracker FE-01-01/GAP-05/07, FE-RUN, result và hướng dẫn mở UI; feature/standards/design/07a/YAML giữ theo run trước, không đổi code hay contract.

## 2. Kiểm tra sẵn sàng

Hai chốt FE17/11 (summary chung mọi role, cờ fixture dev/test xóa khi logout) giữ nguyên. 6 gates/151 tests đã đạt ở run trước; không chạy lại vì lần này chưa đổi code. Browser verification sau sửa startup và integration vẫn mở. Yêu cầu trình review không phải xác nhận đã duyệt.

## 3. Thay đổi và kịch bản

Khởi động preview dev mock port5174, mở authPreview=v2 cho người dùng. Fixture-customer/fixture-mfa, toolbar lỗi/bootstrap/OTP/logout theo guide hiện hành; chỉ dữ liệu tổng hợp. Ghi kết quả mở thực tế bên dưới, không tự duyệt hoặc push/merge.

## 4. Đối chiếu hoàn thành

Vite7.3.8 mode mock báo ready tại http://127.0.0.1:5174/ (session19426). Tool open_in_codex với URL `/login?authPreview=v2`, placement right trả status queued; chưa quan sát trang hiển thị. Trước khởi động HTTP local báo connection refused; sau khởi động lệnh HTTP read bị automatic approval review lỗi Bad Request, không được thực thi. Không retry/bypass check; gửi link trực tiếp cho người dùng. Không suy ra browser critical flows đạt từ Vite ready hoặc panel queued.

## 5. Tài liệu và review

Trình phạm vi UI login/OTP/bootstrap/session chung/logout và hướng dẫn review. Review UI: chờ phản hồi người phụ trách FE. Task cha và browser/integration giữ mở; không đổi design/source khi chỉ trình bản hiện tại.

## 6. Kết quả lần chạy

Đã khởi động bản xem và gửi lệnh mở panel (queued), trình link và checklist dưới đây. Chờ người dùng xem/duyệt; chưa có approval UI mới. FE-01-01 và task con vẫn Đang làm do browser verification còn thiếu. Không commit/push/merge.

Link: `http://127.0.0.1:5174/login?authPreview=v2`. Fixture-customer / fixture-only cho login200; fixture-mfa / fixture-only cho202, OTP đầu123456, sau resend lấy mã qua toolbar. Review bố cục/form/lỗi, OTP/resend/restart, summary chung, reload/logout; toolbar có bootstrap/error/expired OTP/logout lỗi. Chỉ dùng dữ liệu tổng hợp; không đánh dấu API/security BE đạt từ review này.
