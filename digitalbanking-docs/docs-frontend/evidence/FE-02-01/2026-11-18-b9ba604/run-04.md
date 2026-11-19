# Phiếu FE-02-01-MOCK / run-04 — thiết kế lại trang chủ

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok. FE HEAD b9ba604, working tree dirty từ FE-02-01 run01–03; BE không áp dụng. Đọc README, tracker FE-02-01/GAP-07, roadmap, lifecycle/template, FE-02, CUST-HOME, shared layout, API v2 operationId getMyProfile/listAccounts/getAccount; mức mock/browser.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE yêu cầu trong chat 18/11: “thiết kế giúp tôi UI của màn hình trang chủ cho dự án này có bố cục có thể tham khảo như trên hình nhưng hãy làm phù hợp với dự án ( cái gì dự án có thì mới dùng còn nếu các chức năng mà dự án không có thì không nên thiết kế ), các thanh chức năng bên trái hãy thiết kế giống vậy có thể nhất”. Ảnh là tham khảo bố cục, không phải nguồn nghiệp vụ. Phạm vi hiện có UI/mock: dashboard, account list/detail, profile. Transfer/history có contract nhưng UI v2 chưa ở task này; không gắn menu hoạt động hoặc phần lãi suất, thẻ, vay, khuyến mãi, hóa đơn. GAP-07 staff không bị ảnh hưởng.

## 3. Thay đổi và kịch bản

Đã sửa CustomerPreview.tsx và CustomerHome.module.css: header chào mừng theo displayName từ auth, sidebar icon/active, nội dung account/summary dạng thẻ. Nguồn dữ liệu giữ API mock v2, không tạo handler mới. Ảnh browser dashboard-layout-run-04.jpg.

## 4. Đối chiếu hoàn thành

`node node_modules/typescript/bin/tsc -b --pretty false`: exit 0. Browser IAB mở `/customer?authPreview=v2`: thấy 3 mục menu, masked account/số dư và không thấy status trên dashboard. `node node_modules/vitest/vitest.mjs run src/mocks/AuthPreview.test.tsx src/mocks/CustomerPreview.test.tsx --maxWorkers=2`: 1/8 fail ở AuthPreview login trong lượt chạy đồng thời; chạy riêng AuthPreview.test.tsx --maxWorkers=1: 3/3 pass. Chạy đủ `node .tmp/fe-fnd-01-authui-20261117/customer-check-04.cjs`: lint/typecheck/api:check/format:check/test (21 files, 167 tests)/build đều exit 0; log test-report/run-04-full-checks.log. Chưa kiểm mobile/keyboard bằng browser trong run này.

## 5. Tài liệu và review

Cập nhật CUST-HOME, shared layout, FE-02, tracker, hướng dẫn preview và phiếu này. Chờ review UI; không commit/push/merge.

## 6. Kết quả lần chạy

Chờ review UI/mock. FE-02-01 cha và API thật còn mở. FE HEAD b9ba604, working tree dirty; không commit/push/merge. Có lỗi không tái hiện ở lượt test đồng thời đầu, đã kiểm lại bằng bộ đầy đủ đạt.
