# Phiếu chạy FE-08-01-MOCK / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 21/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-08-01 / FE-08-01-MOCK, [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| FE SHA / working tree | Nền `frontend@cd07f2774a2b863265051a2583c16f99e5aa04d4`, sạch lúc bắt đầu; phiếu được lập trước khi sửa code |
| BE SHA / môi trường | Không áp dụng — MSW preview; nguồn tham khảo `origin/dev2@740ab5f` |
| Contract | 07 §8.8, OpenAPI v1 và v2 Draft FE/mock tại `cd07f27`; v2 runtime BE chưa được bàn giao |
| Mức chứng minh | Source/unit/MSW và browser subset Chrome local; không phải API thật |
| Nguồn đã đọc | FE README, tracker FE-08/GAP-03/07/08, roadmap, lifecycle/template, FE-08 feature, ADMIN-ROLES design, FE-AUTH-02/03, 07 §8.8, YAML RoleChangeRequest/createRoleChange/approveRoleChange, shared approval preview |
| Phạm vi được phép | Người dùng yêu cầu “thực hiện task fe 08-01-mock dựa trên contract hiện có”, chat ngày 21/11/2026. Làm preview độc lập cho 4 operation từ schema, xem/review nội dung tổng hợp; không nối mutation/quyền phiên trước GAP. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-03 | Chưa có API tìm user/staff, targetUserId chưa có nguồn để chọn trên UI thật | Chat 21/11 yêu cầu demo theo contract hiện có; không chốt search | Chỉ dùng ID fixture tổng hợp để xem bố cục, không kết nối target lookup |
| GAP-07 | Role→permission/capability cho mutation và route chưa thống nhất | Chưa có chốt mới | Preview độc lập, không gắn auth/route bảo vệ |
| GAP-08 | 07 dev2 có idempotency/step-up Admin nhưng YAML v2 Draft FE/mock chưa đồng bộ/handoff | Chưa có chốt tích hợp mới | Khóa POST create/approve; không thêm handler cho POST |
| FE-05-02-MOCK | Approval GET list/detail đã có preview chỉ đọc | Người dùng đã cho phép preview staff độc lập trước đây; lần này chỉ tái dùng link/GET có sẵn | Có |

## 3. Thay đổi và kịch bản

Đã thêm `src/mocks/RolePreview.tsx` và chọn route preview trong `src/main.tsx` chỉ khi dev + `VITE_ENABLE_MOCKS=true`. Preview `/admin/roles?rolePreview=v2` cho xem 4 operation, role/privilege theo enum, reason tối đa 500, target ID tổng hợp `4002`, bước kiểm tra và nút tạo luôn disabled. Panel dưới dùng `approvalApi.list` với `requestType=ROLE_CHANGE` và handler GET FE-05-02; có kịch bản mẫu/rỗng/chậm/lỗi và link tới preview approval chỉ đọc. Không thêm POST handler hoặc endpoint. `RolePreview.test.tsx` kiểm discriminated union và network chỉ GET. Đồng bộ FE-08, ADMIN-ROLES, guide và tracker.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Schema 4 operation | Form không lẫn roleCode/privilegeCode; max reason 500 | Hàm tạo draft dùng union đúng 4 discriminant; TextArea maxLength 500 | Pass unit/source | `vitest run src/mocks/RolePreview.test.tsx`: 2/2 pass; `tsc -b` exit 0 |
| Mock/network | Không gửi POST createRoleChange/approveRoleChange; không gọi BE | Test chỉ thấy GET `/api/v2/admin/approvals?page=0&size=20&requestType=ROLE_CHANGE`; nút tạo disabled; không có POST handler | Pass MSW | Focused test 2/2; `rg` không tìm thấy role POST trong preview/handlers; worker unhandled API fail closed |
| UI preview | Có default/review và link approval chỉ đọc, xem browser nếu khả dụng | Chrome `127.0.0.1:5174`: form mặc định, review với nút Tạo yêu cầu disabled, link mở approval lọc “Đổi quyền” và item #8002. Bộ chọn kịch bản được thêm sau lượt browser, chưa xem lại bằng browser | Pass browser subset; scenario selector chưa browser kiểm | Chrome local ngày 21/11/2026; không lưu screenshot file; URL được kiểm trực tiếp |
| Gates | lint/typecheck/api:check/format/test/build theo package | Lint/typecheck/OpenAPI v1+v2/format exit 0; full test 30 files/194 tests pass; build exit 0 (3482 modules, cảnh báo chunk >500 kB). Sau thêm scenario selector: focused 2/2, lint/typecheck/build lại exit 0 | Pass code gates; full suite trước thay đổi selector nhỏ | Direct `node_modules/.bin` và `node scripts/check-api.mjs`, 21/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | Đã cập nhật FE-08, ADMIN-ROLES, local guide. Standards không đổi vì preview không đặt quy tắc chung mới |
| Tracker / GAP | Đã thêm FE-08-01-MOCK chờ review; FE-08-01 cha đang làm. GAP-03/07/08 giữ mở |
| Review UI của người dùng | Chưa duyệt |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

UI/mock đã có browser subset và code gates đạt, chờ người phụ trách FE xem/chỉnh UI. Phần mutation, quyền, chọn targetUserId thật, step-up và FET-12/13/API thật vẫn phụ thuộc GAP-03/07/08; không đóng task cha. `pnpm typecheck` trực tiếp thất bại do pnpm 11/Node 24 của host cố tạo symlink ngoài workspace và bị EPERM; đã chạy cùng binary `tsc -b` trong node_modules exit 0. Trình duyệt Chrome ban đầu không truy cập được Vite sandbox cổng 5173; Vite được chạy ngoài sandbox trên cổng 5174 và Chrome đã xem. Sau khi thêm scenario selector, Computer Use dừng do không xác định URL browser đủ tin cậy; không tiếp tục automation hoặc ghi nhận kiểm tra browser cho thay đổi nhỏ đó. Working tree còn thay đổi của task này trước commit; không push/merge chỉ từ mock.
