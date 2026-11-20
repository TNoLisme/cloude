# Phiếu FE-06-02-MOCK / run-01

## 1. Định danh và đầu vào

20/11/2026 Asia/Bangkok, Codex. Task con FE-06-02-MOCK thuộc FE-06-02. FE `frontend@ec8d548`, working tree sạch trước phiếu. BE runtime không áp dụng. Contract tham chiếu docs-common 07/OpenAPI v1, preview v2 Draft FE/mock. Người phụ trách FE yêu cầu “thực hiện task tiếp theo” trong chat ngày 20/11/2026.

Đã đọc README, roadmap, lifecycle/template, tracker FE-06-02/GAP-07/11, FE-06, OPS-FRAUD, FE-UI/API/AUTH standards, SRS FR-044–049/AC-026–027, docs-common 07 §8.7, security và fraud design, YAML `listFraudFlags`/`reviewFraudFlag`.

## 2. Kiểm tra sẵn sàng

GET `/fraud-flags` có filter/page/response schema. Không có GET detail theo flagId; detail chỉ được mở từ item list như feature ghi. Sort whitelist `createdAt`, `severity` ở 07; chưa cần sort cho demo. GAP-07 matrix/capability staff và GAP-11 phạm vi Fraud Option B còn mở. Hành vi review, nhất là BLOCK tác động transfer sau khi hoàn tất, chưa được BE/BA chốt. Người phụ trách FE trả lời trong chat ngày 20/11/2026: **“Đúng, làm demo chỉ đọc trước”** cho đề xuất preview staff độc lập chỉ đọc list/detail từ item GET, khóa ALLOW/BLOCK/ESCALATE/CLOSE và chưa nối transfer. Đây là quyết định phạm vi FE/mock, không thay xác nhận BE/BA/Security.

## 3. Thay đổi và kịch bản

Đã thêm `src/features/fraud/v2/fraud-api.ts` đọc `listFraudFlags` với schema Zod/page/filter; không có client mutation/detail. Fixture tổng hợp và MSW GET ở `fraud-fixtures.ts`/`fraud-handlers.ts` hỗ trợ default/empty/slow/error/403, lọc và phân trang. `FraudPreview.tsx` tại `/staff/risk?fraudPreview=v2` có list, filter status/severity, chi tiết từ item list và bốn nút review disabled. `matchedValue` object tự do không render. Chỉ bật preview trong dev/test mock tường minh; request không có handler fail closed. Đã đồng bộ main, browser handler, feature, screen design, guide và tracker. Không dùng fixture để quyết định policy fraud, quyền staff hoặc status transition.

## 4. Đối chiếu hoàn thành

Đã kiểm tra YAML có `listFraudFlags`, `reviewFraudFlag`; không có `getFraudFlag`. Focused `vitest run src/mocks/FraudPreview.test.tsx` 2/2 pass, `vitest run src/features/fraud/v2/fraud-api.test.ts` 2/2 pass. Gate local ngày 21/11/2026 Asia/Bangkok: `tsc -b`, `eslint . --max-warnings=0`, `node scripts/check-api.mjs` (v1/v2 match), Prettier check đều exit 0; full `vitest run` 26 files/183 tests pass; `vite build --mode mock` exit 0, 3476 modules, cảnh báo chunk >500 kB. `git diff --check` kiểm sau cập nhật phiếu.

Browser IAB ngày 20/11/2026 qua Vite mock `127.0.0.1:5175`: list có hai flag tổng hợp #9001/#9002; mở #9001 thấy metadata có schema và ALLOW/BLOCK/ESCALATE/CLOSE disabled. Tab preview đã giữ làm deliverable. Chưa lưu screenshot file vào repo; chưa kiểm responsive/keyboard đầy đủ hoặc API thật.

## 5. Tài liệu và review

Feature, OPS-FRAUD, local guide và tracker đã cập nhật. UI/mock chờ người phụ trách FE review; chưa tích hợp BE/merge. GAP-07/11 còn mở.

## 6. Kết quả lần chạy

Preview chỉ đọc đã có và browser subset đạt. FE-06-02 cha, review mutation, FET-10 và tích hợp API thật còn mở. Working tree dirty theo task này; chưa commit/push tại thời điểm ghi phiếu.
