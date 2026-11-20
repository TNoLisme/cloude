# Phiếu chạy FE-FND-02-GAP07 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 21/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-FND-02, lát policy đọc GAP-07; tracker mục 2 và 3 |
| FE SHA / working tree | Nền `b0d695e9b612ee725960e46d3068237aa5582c38`, nhánh `frontend`, sạch lúc bắt đầu; bản sửa chưa commit khi chạy checks bên dưới |
| BE SHA / môi trường | `origin/dev2@740ab5fcaf1e5ec0e1f931c409881adb5c6b60ce`; chỉ đọc tài liệu/source và evidence BE-INT-03, không gọi API thật |
| Contract | `docs-common/07` §3.4–3.6 trên dev2; YAML v1 dev2 1.1.0; YAML v2 FE/mock local SHA-256 `E9B09D8DCE1ACA0F63AB1EBFFCDA534274C2D3A05BCC90608EC905846FACD8E4` |
| Mức chứng minh | Đọc source, unit/component mock; chưa có API/browser tích hợp |
| Nguồn đã đọc | FE README §3–5, tracker FE-FND-02/GAP-07, roadmap đợt 2/4, FE-RUN, template, FE-AUTH-02, FE-06 §3–12, `docs-common/03` §4.2, `docs-common/04` Appendix A.2, `docs-common/07` §3.4–3.6, `design/04.security` §5.1, DEC-INT-008/020, BE-INT-03-verification, YAML v1/v2 UserSummary |
| Phạm vi được phép | Người dùng hỏi nguyên ý “ưu tiên docs common và dev2 thì nên làm như thế nò”, rồi yêu cầu “thực hiện nốt task theo gap07 đã chốt” trong chat ngày 21/11/2026. Áp lát đọc exact-ID single-bank đã được 07/dev2 kiểm chứng; không nhận là owner BE/BA/Security đã duyệt toàn ma trận |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| Scope đọc staff exact ID | 07 §3.4–3.6, DEC-INT-008, BE-INT-03: Operations/Admin đọc customer + account; Auditor chỉ account, không customer | Ưu tiên common/dev2, chat 21/11/2026 | Có cho policy hiển thị lát đọc và test |
| Ma trận toàn hệ thống | SRS §4.2 và Security §5.1 nói Auditor đọc dữ liệu customer; API 07/seed thiếu CUSTOMER_READ. Không thể tự sửa nguồn của BE/BA/Security | Chưa có quyết định của các owner trong chat | Chưa cho đóng GAP hoặc bật toàn bộ menu/action |
| Session permissions/capabilities | UserSummary v1/v2 chỉ có roles/privileges, không có permission list; RBAC server có thể đổi sau khi cấp phiên | Người dùng mới chốt ưu tiên nguồn cho lát đọc, chưa có contract capability runtime | Chưa cho suy quyền động hoặc nối preview staff với phiên thật |
| Backend version | dev2 giao v1; preview auth/staff đang dùng v2 Draft FE/mock | GAP-05 còn mở | Chưa tích hợp API thật |

## 3. Thay đổi và kịch bản

- Tạo `src/shared/auth/feature-policy.ts` cho customer dashboard và lát đọc customer/account của staff; từ chối userType sai, role thiếu và snapshot trộn CUSTOMER với staff. Quyết định 403 vẫn ở server.
- Thay hai kiểm tra customer dashboard rời rạc trong AuthFlow/AuthPreview bằng policy chung. Preview staff tiếp tục độc lập; cập nhật dòng mô tả để phân biệt scope v1 đã rõ với phiên v2 chưa tích hợp.
- Thêm test Operations, Admin, Auditor, customer, role trộn. Đồng bộ FE-AUTH, FE-06 và tracker. Không sửa YAML, BE, mock handler, route nghiệp vụ hay API contract.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Lát đọc GAP-07 | Policy tập trung, Auditor không đọc hồ sơ; snapshot bất thường fail closed | 5 test mới PASS; source dùng chung cho route customer; staff preview chưa nối phiên | Pass cho unit/mock slice | `./node_modules/.bin/vitest.cmd run src/shared/auth/feature-policy.test.ts src/features/auth/v2/AuthFlow.test.tsx src/mocks/AuthPreview.test.tsx`: 23/23 PASS, 21/11/2026 |
| Kiểu, lint, format | Không lỗi ở files ảnh hưởng | `tsc -b` exit 0; ESLint files ảnh hưởng exit 0; Prettier check files ảnh hưởng exit 0 sau format; `git diff --check` exit 0 | Pass trong môi trường hiện có | Lệnh trực tiếp `.cmd`, 21/11/2026 |
| API types/build | Generated DTO hiện hành không drift; build được | `node scripts/check-api.mjs` kiểm v1/v2 exit 0; `./node_modules/.bin/vite.cmd build` exit 0, có cảnh báo bundle >500 kB và chú thích Rollup từ dependency | Pass với cảnh báo | Lệnh thực tế, 21/11/2026 |
| Package gate chuẩn | pnpm 10/Node 22 và full gate | `pnpm` trên máy là 11.25.0/Node 24.22.3; `pnpm typecheck` và `pnpm exec prettier` dừng EPERM khi pnpm 11 tạo symlink dưới sandbox. Sau đó chạy binary `.cmd` trong node_modules. Full `pnpm check` chưa chạy. | Blocked cho gate chuẩn | Output EPERM thực tế của lệnh pnpm; không coi direct checks là CI sạch |
| FET-12/13, API/browser thật | Menu, direct URL, action/ownership dưới session backend đích | Chưa chạy; preview staff độc lập và v2 runtime chưa được BE xác nhận | Chưa chạy | GAP-05/07, chờ backend/owner và duyệt UI |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | FE-06, FE-AUTH và design OPS-CUSTOMER cập nhật; không đổi layout, chỉ sửa một dòng giải thích trong preview staff |
| Tracker / GAP | GAP-07 ghi Mở một phần; không đóng task cha |
| Review UI của người dùng | Chưa duyệt UI mới; quyết định ưu tiên nguồn không phải duyệt giao diện |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có; không tích hợp hay merge |
| Commit / push | Một commit task đã tạo trên `frontend`; `git push origin frontend` bị auto-review từ chối hai lần trước khi thực thi vì ownership/trust của GitHub remote chưa được xác minh bằng trusted evidence. `origin/frontend` chưa đổi. SHA cuối của commit được báo trong phản hồi; evidence này thuộc chính commit đó. |

## 6. Kết quả lần chạy

Lát policy FE cho đọc exact ID đã có source và kiểm chứng unit/mock. GAP-07 toàn cục còn mở vì common SRS/Security mâu thuẫn với API 07/seed và session không có permission/capability. Cần BE/BA/Security xác nhận ma trận chính thức và cách phát quyền cho FE, rồi mới nối staff preview vào auth, mở menu/action và làm FET-12/13 trên API thật. Không suy ra `dev2` v1 là runtime v2.

Push hiện bị chặn ở lớp xét duyệt tự động do chưa có chứng cứ tin cậy rằng remote `https://github.com/TNoLisme/cloude.git` thuộc user/tổ chức. Không dùng cách khác để đẩy. Commit local giữ sẵn cho bước push sau khi quyền được xác nhận.
