# Phiếu chạy FE-07-01-MOCK / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 21/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-07-01 / FE-07-01-MOCK, tracker mục 2 |
| FE SHA / working tree | Nền `70eb0ea3d704aea141fc0e58aadab7ef4057fe63`, nhánh `frontend`, sạch lúc bắt đầu; sửa đổi chưa commit khi chạy checks |
| BE SHA / môi trường | `origin/dev2@740ab5fcaf1e5ec0e1f931c409881adb5c6b60ce`, chưa dùng API thật |
| Contract | `digital-banking-v2.yaml` FE/mock Draft, `searchAudit` GET /audit, `getAuditRecord` GET /audit/{auditId}; v1 dev2 là handoff khác version |
| Mức chứng minh | Source, MSW, component test; browser/API thật chưa chạy |
| Nguồn đã đọc | FE README, roadmap đợt 5, tracker FE-07-01/GAP-07/12, FE-07 §§1–12, FE-AUTH/FE-SEC, API-PAGE-01, STAFF-AUDIT design, YAML v2 AuditPage/AuditSummary/AuditDetail/parameters, mẫu preview fraud và shared client |
| Phạm vi được phép | Người dùng yêu cầu ngày 21/11/2026: “trước mắt thì cứ thực hiện cho tôi lần lượt task và ghi lại đúng, chi tiết kết quả để tôi thực hiện. giờ đã chuyển qua task mới được chưa”. FE-07 là task mock độc lập tiếp theo; export/quyền chưa rõ được giữ khóa theo GAP. |

Phiếu này được hoàn thiện sau khi bắt đầu code trong cùng lần chạy, thay vì trước khi sửa như FE-RUN yêu cầu. Đây là sai lệch quy trình của run-01; các lần chạy tiếp theo phải tạo phiếu trước khi sửa.

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| List/detail read-only | YAML v2 có `searchAudit`, `getAuditRecord`, PageMeta, AuditSummary/Detail; feature FE-07 có AC/FET tương ứng | Yêu cầu tiếp tục task mock lần lượt, chat 21/11/2026 | Có cho demo đọc bằng fixture tổng hợp |
| GAP-07 quyền runtime | Ma trận còn mâu thuẫn, UserSummary không có permission/capability; preview staff trước đây độc lập | Chưa có owner xác nhận v2 | Giữ preview độc lập, không nối auth/menu và không coi mock là quyền thật |
| GAP-12 export | YAML có file schema, nhưng quyền/giới hạn/sync/size và sample runtime còn cần đối chiếu với BE/Audit | Chưa có chốt FE/BE/Audit cho runtime | Khóa nút export, không tạo handler hoặc file giả |
| Sort/timezone | `sort` chưa có whitelist endpoint; chuẩn FE yêu cầu timezone cấu hình | Chưa có whitelist/config chốt | Không gửi sort; time filter nhận ISO 8601 có múi giờ tường minh, không ngầm chọn múi giờ browser |

## 3. Thay đổi và kịch bản

- Thêm `audit-api.ts` parse allowlist summary/detail/page, validate ID/page/filter và dùng shared client `/api/v2`.
- Thêm MSW handler chỉ GET `/api/v2/audit` và `/api/v2/audit/{auditId}` với fixture tổng hợp, kịch bản default/empty/slow/error/forbidden, filter và pagination. `beforeState` giả trong response detail chỉ để kiểm tra UI không render object tự do; không chứng minh masking BE.
- Thêm `AuditPreview.tsx`, route độc lập `/staff/audit?auditPreview=v2` và `/staff/audit/{auditId}?auditPreview=v2`; bộ lọc actor/action/outcome/from/to, list/page/detail, lỗi/rỗng/loading. Export disabled.
- Cập nhật FE-07, STAFF-AUDIT design, local guide, tracker. Không đổi contract, auth, routing app ngoài cơ chế preview dev-only hay BE.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| GET list/detail và allowlist | Đúng path, response; không hiển thị open object hoặc gọi export | 2 component/MSW tests PASS, kiểm 2 GET, không hiển thị `beforeState.internal`, export disabled | Pass ở mức mock | `./node_modules/.bin/vitest.cmd run src/mocks/AuditPreview.test.tsx`, 2/2 PASS, 21/11/2026 |
| Lỗi/rỗng | Có empty và 403 an toàn | Component test empty/forbidden PASS; slow/error có handler và source nhưng chưa có browser evidence | Pass một phần | Cùng test; browser chưa chạy |
| Source gates | Type/lint/format/API/build | `tsc -b` exit 0; ESLint files ảnh hưởng exit 0; Prettier check exit 0 sau format; `node scripts/check-api.mjs` exit 0; `vite build` exit 0 với cảnh báo bundle >500 kB và Rollup comment dependency; `git diff --check` exit 0 | Pass trong môi trường hiện tại | Lệnh `.cmd` trực tiếp từ `node_modules/.bin`, 21/11/2026 |
| Full gate/package environment | pnpm 10/Node 22 theo package | Không chạy `pnpm check`; môi trường từ run GAP-07 có pnpm 11/Node 24 và lỗi tạo symlink EPERM | Chưa chạy | Không coi direct checks là CI chuẩn |
| FET-11/13 và BE invariant | Browser critical flow, quyền/export/masking/chain BE | Chưa chạy. API thật và export không tích hợp, UI cuối chưa duyệt | Chưa chạy | GAP-07/12, FE-TEST-03 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | FE-07, STAFF-AUDIT design, local guide đã cập nhật; standards chung không đổi |
| Tracker / GAP | Thêm FE-07-01-MOCK Chờ review UI/mock; FE-07-01 cha và GAP-07/12 tiếp tục mở |
| Review UI của người dùng | Chưa duyệt; mới có source/component preview |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Không đủ; không merge main |
| Push | Remote `TNoLisme/cloude` bị auto-review chặn ở run GAP-07; không thử push task này qua đường khác. Commit local riêng theo yêu cầu người dùng. |

## 6. Kết quả lần chạy

FE-07-01-MOCK có source demo và test component/MSW, đang chờ review UI; chưa có kiểm chứng browser. Cần BE/Audit/FE chốt GAP-12, BE/Security/BA chốt GAP-07, xác nhận runtime/version và duyệt UI rồi mới mở export/tích hợp. Phiếu đã ghi rõ sai lệch thời điểm lập trong run này để không tạo bằng chứng quy trình giả.
