# Phiếu FE-04-01-MOCK / run-01

## 1. Định danh và đầu vào

Bắt đầu 18/11, tiếp tục 19/11/2026 Asia/Bangkok; FE HEAD b9ba604, working tree dirty từ FE-02-01/iconography, chưa commit. BE không áp dụng; contract Draft FE/mock v2. Đọc README, tracker/lộ trình/lifecycle/template, FE-04, CUST-HISTORY/CUST-TX, FE-UI/API standards, SRS FR-023/024/027–030/039–040, common 07 §1.4/2.5/3.6, security §3.6 và OpenAPI v1/v2 listTransactions/getTransaction. Mức mock/browser.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE yêu cầu thực hiện FE-04-01-MOCK trong chat 18/11; trước đó chốt hoàn thành các UI demo rồi mới thiết kế lại. CUSTOMER read-only có contract. Staff scope GAP-07, transfer recovery GAP-09, sort whitelist của endpoint chưa rõ. Người phụ trách FE trả lời trong chat 18/11: “Dùng thứ tự mặc định, chờ BE (đề xuất)”. Phần độc lập danh sách/chi tiết customer tiếp tục. Không thêm mutation, getTransfer, staff hoặc quyền tự suy.

## 3. Thay đổi và kịch bản

Đã bổ sung `history-api.ts`, `HistoryView.tsx`, fixture/handler MSW và route CUSTOMER `/customer/history[/:reference]`. Client shared thêm `URLSearchParams` tường minh, vẫn từ chối query nhúng trong path. Dashboard có vùng gần đây GET `/transactions` page0/size5/status=COMPLETED, loading/empty/error độc lập; browser ảnh `dashboard-recent.jpg`. List dùng page 0-based/size20, filter status/accountId/direction/from-inclusive/to-exclusive/min-max; direction bắt buộc accountId. Fixture tổng hợp có DEBIT COMPLETED và CREDIT PENDING; 403/404 và empty/error/slow chọn được ở toolbar. Không dùng legacy làm quyết định nghiệp vụ.

## 4. Đối chiếu hoàn thành

Typecheck/lint đã đạt. Focused test AuthPreview + CustomerPreview 11/11; browser IAB list/detail/dashboard đã thấy dữ liệu masked và không có transfer action, ảnh `history-list-filters.jpg`, `history-detail.jpg`, `dashboard-recent.jpg`; lọc `minAmount=200000.00` chỉ còn giao dịch 900.000,00 ₫, xóa lọc đưa hai dòng trở lại. Bộ full gate `node .tmp/fe-fnd-01-authui-20261117/history-check-01.cjs` chạy lại sau bổ sung dashboard và đạt: lint, typecheck, api:check, format:check, test 21 files/170 tests, build đều exit 0; log `run-01-full-checks.log`, kết thúc 19/11/2026 12:28 +07. Lượt đầu của full gate dừng ở format:check vì test mới chưa format, đã format và chạy lại toàn bộ đạt. `git diff --check` sau thay đổi cuối exit 0 (chỉ cảnh báo chuyển LF/CRLF). Chưa kiểm toàn bộ mobile/keyboard/BE thật.

## 5. Tài liệu và review

Cập nhật FE-04, CUST-HISTORY/CUST-TX, guide/tracker sau code. UI demo chưa duyệt, không tích hợp/merge.

## 6. Kết quả lần chạy

Demo UI/mock sẵn xem, thiết kế chưa chốt theo quyết định người phụ trách FE. FE-04-01 cha, staff scope, sort whitelist, runtime v2 và tích hợp thật vẫn mở.


## Giới hạn và bước tiếp

Không gọi `getTransfer`, không làm mutation/recovery/staff. Sort không có UI theo chốt FE; common 07 §2.5 ghi `createdAt`/`amount` là whitelist nhưng YAML v2 chỉ mô tả whitelist tổng quát, cần BE xác nhận bản giao FE trước khi làm bộ chọn và tích hợp. Mock ownership/403 không chứng minh server. Chưa kiểm full FET-08/13/14 bằng API thật/browser đa kích thước; parent FE-04-01 chưa hoàn thành. FE HEAD b9ba604, working tree dirty gồm FE-02-01/iconography và task này, không commit/push/merge.
