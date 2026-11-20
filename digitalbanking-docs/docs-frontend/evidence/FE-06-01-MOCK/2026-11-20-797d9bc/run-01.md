# Phiếu FE-06-01-MOCK / run-01

## 1. Định danh và đầu vào

20/11/2026 Asia/Bangkok, Codex. Task con FE-06-01-MOCK thuộc FE-06-01. FE `frontend@797d9bc`, working tree sạch lúc bắt đầu; BE SHA/API runtime không áp dụng cho mock. Contract tham chiếu: docs-common 07 và OpenAPI v1/v2 trong repo, v2 ở mức Draft FE/mock.

Người phụ trách FE yêu cầu trong chat ngày 20/11/2026: **“tìm khách hàng theo số điện thoại hãy giúp tôi thực hiện task”**. Sau câu hỏi phạm vi, người phụ trách FE chốt cùng ngày: **“Đúng, làm phần đọc bằng ID trước”** cho đề xuất preview staff đọc bằng customerId fixture, ô số điện thoại hiện “Chờ contract”, nút tra cứu khóa. Đây là quyết định FE cho preview, không phải xác nhận BE/BA đã có endpoint.

Đã đọc README, tracker FE-06-01/GAP-03, roadmap, lifecycle/template, FE-06, OPS-CUSTOMER, FE-UI/API/AUTH standards, SRS FR-049/AC-027, docs-common 07 §3.5/8.2.4–5/OI-API-08, security và YAML `getAdminCustomer`/`getAdminCustomerAccounts`.

## 2. Kiểm tra sẵn sàng

Hai GET theo `customerId` đã có schema. Search theo số điện thoại chưa có operation trong YAML v1/v2, còn là OI-API-08 đề xuất `GET /admin/customers?q=` trong 07; chưa có định nghĩa query/response, exact-match hay partial, masking, quyền/scope, lỗi và rate limit. Không dùng proposal này làm contract đã duyệt. GAP-07 về matrix/capability staff cũng còn mở. Theo chốt FE, có thể làm preview đọc ID fixture; phần phone search chờ BA/BE bổ sung contract.

## 3. Thay đổi và kịch bản

Đã thêm `src/features/staff/v2/staff-customer-api.ts` cho hai GET read-only với Zod validation và ID guard. `src/mocks/staff-customer-handlers.ts` dùng fixture tổng hợp 2001/3001 từ bộ customer hiện có, hỗ trợ default/empty/slow/error/forbidden/404; handler chỉ bật khi preview dev/test chuẩn bị. `StaffCustomerPreview` ở `/staff/customers/2001?staffPreview=v2` hiển thị hồ sơ masked và tài khoản, ô phone/nút tra cứu disabled. Không có search handler hoặc request `?q=`. Browser worker bổ sung catch-all `/api/*` fail closed để request thiếu handler không lọt tới BE. Đã cập nhật main, feature, design OPS-CUSTOMER, guide, tracker và phiếu handoff GAP-03.

## 4. Đối chiếu hoàn thành

Đã kiểm tra source contract bằng `rg`: không có operation search customer theo phone trong YAML v1/v2. Focused `vitest run src/mocks/StaffCustomerPreview.test.tsx`: 3/3 pass, gồm hai GET theo ID không gọi search, masked view, empty/403/404. Full gate trên Windows/Node local ngày 20/11: `eslint . --max-warnings=0` exit 0; `tsc -b` exit 0; `node scripts/check-api.mjs` exit 0, generated v1/v2 match; `prettier --check ...` exit 0; `vitest run` 24 files/179 tests pass; `vite build --mode mock` exit 0, 3472 modules, cảnh báo chunk >500 kB. `git diff --check` exit 0 sau code/tài liệu.

Browser IAB qua Vite mock `127.0.0.1:5175`: `/staff/customers/2001?staffPreview=v2` hiện tên, phone/email masked, account masked và số dư; input/button phone disabled. `/staff/customers/9999?staffPreview=v2` hiện “Không tìm thấy khách hàng.” Đã giữ tab default làm deliverable. Chỉ xem AX tree trong công cụ; không lưu screenshot vào repo. Chưa kiểm responsive/keyboard đầy đủ hoặc API thật.

## 5. Tài liệu và review

Feature, design OPS-CUSTOMER, local guide, tracker và handoff GAP-03 đã cập nhật. UI/mock chờ người phụ trách FE review; chưa tích hợp BE hoặc merge. GAP-03 và GAP-07 giữ mở.

## 6. Kết quả lần chạy

Phần preview đọc ID đạt mức mock/browser subset và chờ review UI. Tìm theo số điện thoại bị chặn bởi contract thiếu; BE/BA/Security cần trả lời phiếu handoff trước khi FE mở nút. FE-06-01 cha/FET-10/API thật còn mở. Nền `frontend@797d9bc`, working tree dirty do thay đổi task này; chưa commit/push/merge tại thời điểm ghi phiếu.
