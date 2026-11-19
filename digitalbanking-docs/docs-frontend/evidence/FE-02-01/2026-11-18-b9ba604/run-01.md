# Phiếu FE-02-01 / run-01

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok, Codex. Nền frontend@b9ba604 sau push; working tree sạch khi bắt đầu. Đã đọc FE README/tracker, roadmap, lifecycle/template, feature FE-02, design CUST-HOME/ACCOUNT/PROFILE, API-DATA/ERR/RETRY/CACHE, 07 §8.2, security/ownership và YAML v1/v2. BE không kết nối, v2 2.0.0 Draft FE/mock đã duyệt. FET-03/14 và FR-008–012/039, AC-005/023/024 là đầu ra đối chiếu.

## 2. Kiểm tra sẵn sàng

Chat18/11: user yêu cầu “trước khi thực hiện task hãy push giúp tôi lên nhánh frontend task vừa rồi sau đó mới thực hiện task tiếp theo”. Đã push frontend b9ba604 gồm các commit riêng 3fceda9 foundation, 60a77ee audit và b9ba604 auth. FE-02-01 phụ thuộc FE-01-01/FE-FND-03 còn mở; contract customer self profile/list/detail đã rõ ở 07 §8.2/v2. Không tự dùng quyền staff/GAP-07, PIN/GAP-01 hay backend runtime v2/GAP-05. Màn phiên chung mọi role đã chốt trước đó; không tự cấp route nghiệp vụ theo legacy.

## 3. Thay đổi và kịch bản

Đã thêm mock GET v2 customer/self profile, accounts và account detail trong `src/mocks/v2-auth.ts`; fixture 3002 mô phỏng account khác chủ403, ID không hợp lệ400, ID chưa tồn tại404; 401 khi chưa có fixture session. Component độc lập `src/features/accounts/v2/CustomerPanels.tsx` tái sử dụng Panel/Descriptions, đọc masked fields và formatMoney không qua Number. Các component chưa nối route hoặc data client. Không triển khai PIN/chuyển tiền/history chưa thuộc task.

Đã hỏi người phụ trách FE qua `request_user_input_async`: “FE-02-01 sẽ có màn hồ sơ và tài khoản dành cho CUSTOMER. Trước đó bạn chốt sau login chỉ hiện màn phiên chung, chưa nối nghiệp vụ. Ở task này, bạn muốn mở các màn customer trong preview như thế nào?” Các lựa chọn: preview customer riêng giữ màn phiên chung; hoặc thêm lối vào customer khi role CUSTOMER. Chờ phản hồi trước wiring/route phụ thuộc; không tự coi lựa chọn mặc định là quyết định.

Sau run-01, user đã trả lời “Preview customer riêng; giữ màn phiên chung sau login (đề xuất)” trong chat18/11; wiring và kết quả lần tiếp theo ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]].

## 4. Đối chiếu hoàn thành

7 tests mới đạt:3 test mock 401/200/403/404/400/masked/status filter và4 test component profile null, Money lớn chính xác, detail masked/owner, unknown status. Chạy tập trung `node node_modules/vitest/vitest.mjs run src/features/accounts/v2/CustomerPanels.test.tsx src/mocks/v2-customer.test.ts`: 2 files/7 tests đạt,55.42s. Full gates bằng pnpm10.34.9: lint/typecheck/api:check/format:check/test/build đạt;19 files/158 tests đạt, test87.24s, build28.77s (`test-report/full-checks.log`, hoàn tất13:23:21 Asia/Bangkok). Lint/typecheck/format chạy lại sau thay đổi enum và đạt (`test-report/final-static-checks.log`,13:23:46). Vì thao tác sửa component/test diễn ra khi full gate đang chạy, lần static sau xác nhận lại source cuối; full test log có đủ7 tests mới. Browser/UI mới chưa chạy vì chưa có route/preview được chốt; FET-03/14 và backend ownership không được coi đạt chỉ từ mock.

## 5. Tài liệu và review

Thiết kế màn, feature/guide/tracker cập nhật theo code thực tế; user chưa duyệt UI FE-02. Không tự tích hợp BE/push/merge task mới.

## 6. Kết quả lần chạy

Đang làm; phần độc lập mock/component đã kiểm tra, chờ người dùng trả lời lựa chọn cách mở UI trước wiring. Chưa có UI/mock customer để review, chưa tích hợp BE hay push/merge task mới. Mốc auth đã push frontend b9ba604. Chi tiết [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/result.md]].
