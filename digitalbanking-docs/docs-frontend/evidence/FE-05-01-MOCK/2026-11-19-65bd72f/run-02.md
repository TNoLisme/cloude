# Phiếu FE-05-01-MOCK / run-02 — kiểm tra lại preview

## 1. Định danh và đầu vào

19/11/2026 Asia/Bangkok; `main@65bd72f`, working tree sạch lúc bắt đầu. User yêu cầu “preview lại rồi thực hiện task mới” trong chat ngày 19/11. Contract v2 Draft FE/mock; mức kiểm tra browser.

## 2. Kiểm tra sẵn sàng

Theo run-01, code/mock và 171 test đạt nhưng browser chưa mở được; không thay đổi nghiệp vụ hoặc GAP.

## 3. Thay đổi và kịch bản

Khởi động `vite --mode mock --host 0.0.0.0 --port 5175`, Vite báo ready tại localhost và hai địa chỉ mạng. IAB thử mở `/customer/limits?customerPreview=v2` qua `192.168.1.114:5175`, `Page.navigate` timeout. Sau đó chạy Vite ngoài sandbox mạng với `--host 127.0.0.1 --port 5175`; IAB mở được màn CUSTOMER tại `http://127.0.0.1:5175/customer/limits?customerPreview=v2`. AX tree và ảnh hiển thị tiêu đề, chọn tài khoản, hạn mức hiệu lực 5.000.000,00 ₫/17.500.000,00 ₫ và cấu hình khách hàng. Tab đã `markDeliverable`; không lưu file ảnh trong repo.

## 4. Đối chiếu hoàn thành

Browser subset: Đạt hiển thị màn default trên IAB ngày 20/11/2026 Asia/Bangkok. Chưa kiểm full kịch bản empty/slow/error, mobile/keyboard hoặc API thật trên browser.

## 5. Tài liệu và review

Chưa có review UI từ user; task con chuyển Chờ review UI/mock sau browser subset và các gate mock run-01, không coi task cha hoàn thành.

## 6. Kết quả lần chạy

Đã mở preview để người phụ trách FE xem và góp ý. UI chưa duyệt, FE-05-01 cha và tích hợp BE còn mở.
