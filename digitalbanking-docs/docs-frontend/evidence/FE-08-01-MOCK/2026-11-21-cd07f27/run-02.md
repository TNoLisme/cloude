# Phiếu chạy FE-08-01-MOCK / run-02

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 22/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-08-01 / FE-08-01-MOCK, [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| FE SHA / working tree | Nền `frontend@cd07f2774a2b863265051a2583c16f99e5aa04d4`; working tree dirty chỉ bởi FE-08-01-MOCK từ run-01 |
| BE SHA / môi trường | Không áp dụng — mock; `origin/dev2@740ab5f` chỉ là nguồn đối chiếu |
| Contract | 07 §8.8 và YAML v2 Draft FE/mock tại nền FE; không có runtime v2 được xác nhận |
| Mức chứng minh | Tiếp tục source/unit/mock; browser subset đã ghi tại run-01 |
| Nguồn đã đọc | Run-01, FE README/feature ADMIN-ROLES, tracker GAP-03/07/08, roadmap/lifecycle và YAML đã đọc ở lần chạy trước; hôm nay đọc lại diff/test và trạng thái repo trước kiểm tra |
| Phạm vi được phép | Người phụ trách FE: “tiếp tục thực hiện”, chat ngày 22/11/2026. Hoàn tất kiểm bộ chọn kịch bản, evidence và trạng thái preview; giữ POST/permission/step-up khóa. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-03/07/08 | Search user, capability/matrix và mutation/step-up chưa đủ cho FE tích hợp | Không có chốt mới; run-01 đã khoanh preview chỉ đọc | Có cho kiểm tra preview; chưa cho mở POST |
| Browser | Run-01 đã xem form/review/link Chrome; sau selector mới Computer Use dừng vì không xác định URL đủ tin cậy | Không suy ra browser check mới | Kiểm selector bằng MSW/component; browser selector để chờ nếu công cụ sẵn sàng |

## 3. Thay đổi và kịch bản

Run-01 đã có form bốn operation, review, GET approval và docs. Run-02 thêm test chuyển kịch bản danh sách sang empty từ UI. Không thêm endpoint/handler.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Selector fixture | Chuyển default→empty dùng GET, không POST | Component đổi kịch bản qua Select, hiển thị “Chưa có yêu cầu đổi quyền.”; toàn bộ request ghi trong test đều GET | Pass | `node_modules/.bin/vitest.cmd run src/mocks/RolePreview.test.tsx`, 3/3 pass, 22/11/2026 |
| Regression | Focused test, typecheck, lint, format, build và diff sạch | Focused 3/3; `tsc -b`, ESLint, Prettier check, `git diff --check` exit 0. Build của run-01 sau selector exit 0; full suite run-01 30 files/194 tests pass trước test mới (test mới chỉ tăng coverage) | Pass các gate đã chạy; không ghi full suite sau test mới | Binary local trong `node_modules/.bin`; lệnh và mức chứng minh tách rõ theo run |
| Browser | Không ghi nhận thêm nếu Computer Use không sẵn sàng | Không chạy browser trong run-02; run-01 đã xem default/review/link | Chưa chạy cho selector mới | Run-01 có browser subset; test UI/MSW xác nhận selector |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / design / guide | Đã cập nhật trong run-01; kiểm lại nếu hành vi đổi |
| Tracker / GAP | FE-08-01-MOCK chờ review; GAP-03/07/08 giữ mở |
| Review UI của người dùng | Chưa duyệt |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

FE-08-01-MOCK có form/review và danh sách ROLE_CHANGE mock để người phụ trách FE xem. Selector empty được kiểm qua UI component/MSW; browser subset ở run-01 không bao gồm selector mới. UI chưa được người dùng duyệt; FE-08-01 cha, mutation, role permission, target search, step-up và API thật giữ mở. Thay đổi chưa commit/push ở thời điểm cập nhật phiếu; commit SHA thực tế sẽ báo trong chat. Không coi browser subset hay unit/mock là FET-12/13/API thật.
