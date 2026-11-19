# FE-04-01-MOCK — kết quả demo ngày 19/11/2026

FE HEAD b9ba604; working tree dirty, thay đổi FE-02-01/iconography trước đó cùng FE-04-01-MOCK chưa commit. Contract dùng OpenAPI v2 Draft FE/mock. API thật, session/cookie, quyền và ledger backend chưa được kiểm chứng.

## Đầu ra

- Dashboard CUSTOMER có “Giao dịch gần đây” từ GET `/transactions` page0/size5/status=COMPLETED, có trạng thái loading/empty/error độc lập và link tới chi tiết/lịch sử. Ảnh `dashboard-recent.jpg`.
- Màn CUSTOMER `/customer/history?authPreview=v2` (hoặc `customerPreview=v2`), vào từ sidebar của dashboard sau login fixture. Chi tiết `/customer/history/:reference?authPreview=v2`.
- GET `/api/v2/transactions` với page 0-based/size20, status, accountId, direction (sau accountId), from inclusive/to exclusive theo UTC+7, minAmount/maxAmount Money string. GET `/api/v2/transactions/{reference}` đọc chi tiết. Route/query trong browser giữ filter khi mở và quay lại chi tiết; page vượt phạm vi được trả về trang cuối hợp lệ ở UI.
- Fixture tổng hợp: một giao dịch DEBIT/COMPLETED và một CREDIT/PENDING, masked account, số tiền string. Toolbar dev/test có default/empty/slow/error; mock không có handler khác vẫn fail closed. 403/404 detail hiển thị thông báo an toàn.
- Sắp xếp mặc định server; user chốt trong chat 18/11 “Dùng thứ tự mặc định, chờ BE (đề xuất)”. Common 07 §2.5 nêu createdAt/amount nhưng YAML v2 chỉ nói whitelist chung; chưa đưa bộ chọn sort vào UI.

## Kiểm tra

`node .tmp/fe-fnd-01-authui-20261117/history-check-01.cjs`: lint, typecheck, api:check, format:check, test (21 files/170 tests), build đều exit 0; log `run-01-full-checks.log`. Lượt đầu format check fail do file test vừa thêm chưa format, đã format và chạy lại toàn bộ thành công. Lint/typecheck/diff-check riêng sau sửa cuối đều exit 0.

Browser IAB port 5174: danh sách và chi tiết hiện đúng fixture; nhập minAmount `200000.00` chỉ còn giao dịch `900.000,00 ₫`, xóa lọc đưa hai dòng trở lại. Ảnh [danh sách](history-list-filters.jpg) và [chi tiết](history-detail.jpg) nằm cùng thư mục. Chưa chạy full browser FET-08/13/14, mobile/keyboard matrix hay API thật.

## Trạng thái

Demo mock sẵn cho người phụ trách FE xem và tiếp tục chỉnh UI. FE-04-01 task cha chưa hoàn thành; staff scope GAP-07, sort version/whitelist, runtime v2 và tích hợp thật giữ mở. Không commit/push/merge. Xem [[digitalbanking-docs/docs-frontend/evidence/FE-04-01-MOCK/2026-11-18-b9ba604/run-01.md]] để đối chiếu vòng đời task.
