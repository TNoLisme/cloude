# Phiếu FE-05-02-MOCK / run-01

## 1. Định danh và đầu vào

19–20/11/2026 Asia/Bangkok, Codex. Task con FE-05-02-MOCK của FE-05-02. FE `main@65bd72f`, working tree dirty sau mở phiếu FE-05-01 run-02; BE không áp dụng. Contract OpenAPI v2 Draft FE/mock. Người dùng yêu cầu “preview lại rồi thực hiện task mới” trong chat ngày 19/11, sau đó “tiếp tục thực hiện” ngày 20/11.

Đã đọc README/tracker/roadmap/lifecycle/template, FE-05, STAFF-APPROVAL-LIST/DETAIL, FE-UI/API/AUTH standards, common 07 §8.8.3–4, security và v2 YAML `listApprovals`/`getApproval`.

## 2. Kiểm tra sẵn sàng

Độc lập: GET list/detail metadata và filter/page theo YAML. `sort` không có whitelist endpoint trong YAML, để mặc định. Quyền staff/scope GAP-07 và approval mutation/step-up GAP-08 giữ mở. `payload/result` của detail là object tự do, chưa có allowlist field để render an toàn. Người phụ trách FE trả lời trong chat 19/11: “Preview staff độc lập; chỉ hiển thị metadata có schema, ẩn payload/result và khóa duyệt/từ chối (đề xuất)”. Không dùng fixture để quyết định quyền.

## 3. Thay đổi và kịch bản

Đã thêm `src/features/approvals/v2/approval-api.ts` đọc `GET /admin/approvals` và `GET /admin/approvals/{approvalId}`, Zod kiểm tra metadata, page và ID; giữ `payload/result` là object chưa diễn giải. List hỗ trợ `page/size/requestType/status` có schema; không gửi sort hoặc mutation. Fixture tổng hợp và handler MSW dev/test có kịch bản default/empty/slow/error, validation và 404; request không có handler fail closed. `ApprovalPreview` tại `/staff/approvals?approvalPreview=v2` có danh sách/chi tiết chỉ đọc, không render payload/result, nút Phê duyệt/Từ chối disabled. Đã cập nhật main, guide, feature và hai screen design.

## 4. Đối chiếu hoàn thành

Focused client test lượt đầu 1/2 do test dùng `.rejects` cho lỗi sync; đã sửa test và chạy lại 2/2. Sau UI, focused test 4/4; đã bổ sung test và chạy full gate cuối: `vitest run` 23 files/176 tests pass; eslint toàn frontend, `tsc -b`, `node scripts/check-api.mjs` (v1/v2 match), Prettier check và `git diff --check` đều exit 0. `vite build --mode mock` exit 0, 3469 modules; cảnh báo bundle >500 kB. Không chạy API thật/BE tests.

Browser IAB ngày 20/11 qua Vite mock `127.0.0.1:5175`: danh sách có hai yêu cầu tổng hợp #8001 LIMIT_CHANGE/PENDING, #8002 ROLE_CHANGE/APPLIED; chi tiết #8001 chỉ hiện metadata có schema, thông báo payload/result chờ contract và hai nút quyết định disabled. Đã đánh dấu tab list/detail là deliverable. Ảnh chỉ xem trong công cụ browser, không lưu screenshot file vào repo. Chưa kiểm toàn bộ kịch bản trên browser hay responsive/keyboard.

## 5. Tài liệu và review

Tracker và tài liệu liên quan đã cập nhật. UI/mock chờ người phụ trách FE review; chưa tích hợp BE hoặc merge. GAP-07/08 và task cha còn mở.

## 6. Kết quả lần chạy

Preview staff độc lập chỉ đọc sẵn để xem. Working tree dirty theo các file task và phiếu FE-05-01 run-02; chưa commit, push hoặc merge trong lần chạy này.
