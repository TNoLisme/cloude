# Phiếu chạy FE-07-01-MOCK / run-02

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 22/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-07-01 / FE-07-01-MOCK, [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| FE SHA / working tree | Nền `frontend@07ccdafdb3b3c363bfb5615ecd78e8b83ca2ab65`; lúc mở phiếu này chỉ có phiếu FE-03-01-MOCK cùng lần chạy chưa commit. FE-03 đã commit riêng `ae3365a` trước khi sửa FE-07. |
| BE SHA / môi trường | Không dùng API BE thật; `origin/dev2@740ab5f` chỉ tham chiếu |
| Contract | 07 §8.6, YAML v2 Draft FE/mock `searchAudit`/`getAuditRecord`; không coi là runtime v2 |
| Mức chứng minh | Dự kiến browser mock, component/MSW regression; FET-11/13/API thật chưa áp dụng |
| Nguồn đã đọc | FE README, tracker GAP-07/12, roadmap/lifecycle/template, FE-07, STAFF-AUDIT, API-PAGE, FE-AUTH/SEC/TEST, SRS/07/security/YAML và run-01 |
| Phạm vi được phép | Người phụ trách FE yêu cầu làm FE-07-01-MOCK sau FE-03-01-MOCK, chat ngày 22/11/2026. Chỉ đọc list/detail, export vẫn khóa. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-07 | Staff capability/session v2 chưa chốt | Không có chốt mới | Preview độc lập, không nối quyền thật |
| GAP-12 | Export sync/size/permission/sample còn mở | Không có chốt mới | Không export/handler/file giả; nút khóa |

## 3. Thay đổi và kịch bản

Đã có preview `/staff/audit?auditPreview=v2` từ run-01, source/component đạt nhưng browser chưa xem. Trên Chrome Windows, xem list fixture hai bản ghi và chi tiết #8001. Tại viewport 812 px, filter label/input chồng nhau. Thay wrapper flex bằng CSS grid tự xuống hàng và control rộng theo cột. Không sửa query, MSW, route, auth hay export. Kịch bản empty/forbidden được kiểm lại bằng component/MSW; không khẳng định đã xem hai kịch bản này trên browser.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Browser list/detail | List/detail metadata allowlist, export disabled, filter không chồng | Chrome `127.0.0.1:5174/staff/audit?auditPreview=v2` hiển thị #8001/#8002; mở `/staff/audit/8001?auditPreview=v2`, chỉ thấy Audit ID, thời gian, actor, action, entity, outcome, correlation/request ID; export disabled; viewport 812 px sau sửa không chồng | Đạt cho mock/browser đã xem | Chrome Windows, 22/11/2026; không lưu screenshot file |
| Mock scenarios/network | Empty/403, không xuất file | Focused component/MSW 2/2 pass, gồm empty/forbidden; `check-api.mjs` v1/v2 match. Không chạy network trace riêng hoặc xem error/slow trên browser | Đạt trong phạm vi test; phần chưa xem ghi rõ | `vitest.cmd run src/mocks/AuditPreview.test.tsx` exit 0; `node scripts/check-api.mjs` exit 0 |
| Gates | Typecheck, lint, format, build, diff | `tsc -b`, `eslint . --max-warnings=0`, `prettier --check` hai file sửa, `vite build`, `git diff --check` đều exit 0; build có warning chunk >500 kB | Đạt | Chạy tại `digitalbanking-frontend`, riêng `git diff --check` tại repo root, 22/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / design / guide | Cập nhật FE-07 feature và STAFF-AUDIT design cho browser + lưới filter; local guide route từ run-01 vẫn đúng |
| Tracker / GAP | FE-07-01-MOCK chờ review UI/mock; FE-07-01 cha đang làm; GAP-07/12 giữ mở |
| Review UI của người dùng | Chưa duyệt |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

Run-02 đạt trong phạm vi browser list/detail và regression mock. Có CSS fix cho filter viewport hẹp. Chờ người phụ trách FE review UI; không đóng FE-07-01 hoặc FET-11/13 từ mock. Không có API BE thật, xác nhận quyền staff, export hay file mẫu. Working tree trước commit chứa các file FE-07 của run này; commit SHA báo trong phản hồi sau khi commit.
