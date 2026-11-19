# Phiếu FE-DOC-03 / run-03 — thứ tự demo UI

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok; FE HEAD b9ba604, working tree dirty từ FE-02-01/FE-FND-03-ICONS, chưa commit. Đọc README, tracker, roadmap, lifecycle/template, FE-04, OpenAPI v2 listTransactions/getTransaction/getTransfer và handoff Q-01..05. Mức chứng minh: đọc tài liệu, chưa code/API/browser.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE chốt trong chat 18/11: “chưa chốt hẳn UI vì tôi sẽ còn sửa nữa mà tôi muốn thực hiện xong các task có UI demo rồi mới thiết kế lại UI”. Đây là thứ tự review mới; không suy ra duyệt UI/mock, tích hợp hoặc merge. FE-03 còn GAP-02/08/09 và xác nhận version/contract cho phần phụ thuộc; FE-04 customer read-only có GET /transactions và GET /transactions/{reference} trong YAML v2 Draft FE/mock. Staff scope GAP-07 không làm trong bước customer.

## 3. Thay đổi và kịch bản

Đồng bộ roadmap/tracker: đánh dấu UI customer và iconography là demo đang chờ vòng thiết kế lại; cho phép tiến tới FE-04-01-MOCK phạm vi customer read-only nếu trước khi chạy task đọc đủ nguồn và lập phiếu riêng. Không đóng FE-02-01/FE-FND-03, không thêm nghiệp vụ.

## 4. Đối chiếu hoàn thành

Đối chiếu nguồn: tracker FE-04-01 Chưa bắt đầu; FE-04 §7 có listTransactions/getTransaction; YAML v2 có operationId tương ứng. FE-03-01/02 tiếp tục đợi GAP liên quan. Chưa chạy test vì chỉ chỉnh tài liệu điều phối.

## 5. Tài liệu và review

Roadmap/tracker cập nhật theo quyết định mới; chưa có xác nhận hoàn tất UI hoặc API thật. Không commit/push/merge.

## 6. Kết quả lần chạy

Đã cập nhật thứ tự thực hiện. Task tiếp theo được phép mở: FE-04-01-MOCK phần CUSTOMER read-only, với phiếu task riêng và chỉ các filter/state đã rõ. Phần chưa rõ phải hỏi trước khi triển khai.
