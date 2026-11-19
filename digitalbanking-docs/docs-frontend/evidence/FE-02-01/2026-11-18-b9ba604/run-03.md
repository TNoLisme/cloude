# Phiếu FE-02-01 / run-03 — vào dashboard sau login

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok; frontend@b9ba604, working tree dirty gồm FE-02-01 run-01/02 chưa commit. Đọc AGENTS, lifecycle, tracker, FE-01/02, design CUST-HOME và auth, 07 §3.5/8.2, v2 FE/mock; không API thật.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE yêu cầu trong chat18/11: “tôi muốn sau khi đăng nhập thành công thì vào phần dashboard của customer, và trên màn hình chính của customer thì bỏ phần hiển thị trạng thái hoạt động của tài khoản ( đang hoạt động )”. Đây là thay đổi quyết định trước về màn phiên chung đối với CUSTOMER; không chốt mapping staff/GAP-07 hay runtime v2/GAP-05.

## 3. Thay đổi và kịch bản

Đã nối auth mock customer vào dashboard v2 dùng cùng controller/session; không tạo fixture session khi đến từ login. Ẩn trạng thái ở card dashboard, giữ trạng thái trong detail. Staff giữ màn phiên chung cho tới khi mapping được chốt. Preview riêng vẫn phục vụ fixture dev/test.

## 4. Đối chiếu hoàn thành

Đã chạy regression login200/202 MFA, logout/reload và staff summary; customer screens có bộ test riêng. Browser IAB đăng nhập fixture-customer/fixture-only tới `/customer?authPreview=v2`, thấy Tổng quan, số dư và nút đăng xuất, không thấy “Đang hoạt động”; ảnh dashboard-login-run-03.jpg. Chưa kiểm lại browser reload/logout/mobile trong run này; không suy rộng từ test tự động. Mức mock không chứng minh session/ownership BE.

## 5. Tài liệu và review

Đồng bộ feature/design/guide/tracker và result; chưa coi yêu cầu sửa là duyệt UI. Không commit/push/merge.

## 6. Kết quả lần chạy

Đã hoàn tất thay đổi mock, chờ người dùng duyệt UI. `node .../vitest.mjs run src/mocks/AuthPreview.test.tsx src/mocks/CustomerPreview.test.tsx src/features/auth/v2/AuthFlow.test.tsx src/features/accounts/v2/CustomerPanels.test.tsx --maxWorkers=2`: 26/26 pass trước khi thêm test staff. Lần chạy tập trung đầu tiên lỗi do test host thiếu QueryClientProvider và test status còn kiểm card; đã sửa test host theo provider thực tế và chuyển kiểm fallback sang detail. Bộ đầy đủ qua `node .tmp/fe-fnd-01-authui-20261117/customer-check-03.cjs`: lint, typecheck, api:check, format:check, test (21 files/167 tests), build đều exit 0; chi tiết test-report/run-03-full-checks.log. `git diff --check` exit 0 (cảnh báo chuyển LF/CRLF). Working tree dirty, HEAD b9ba604; không commit/push/merge. FE-02-01 cha, review và tích hợp API thật vẫn mở.
