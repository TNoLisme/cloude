# Phiếu chạy FE-REL-V1-PLAN / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 22/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-REL-01/02/03, FE-REL-V1-PLAN; [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| FE SHA / working tree | `frontend@41209c4fc6dcc6b692f9f3b0163785555b7e6192`, clean trước sửa |
| BE SHA / môi trường | `origin/dev2@740ab5fcaf1e5ec0e1f931c409881adb5c6b60ce`; chỉ đọc repo, chưa chạy BE/API |
| Contract | BE OpenAPI v1.1.0 `/api/v1`; FE 07a/YAML v2 Draft mock giữ lịch sử, không dùng làm runtime v1 |
| Mức chứng minh | Đọc source/tài liệu và kiểm merge giả lập; không tích hợp/API/browser |
| Nguồn đã đọc | AGENTS, FE README/tracker/roadmap/lifecycle/template, ADR-002, GAP review, FE standards FE-API/AUTH/TEST và feature/design liên quan qua tracker; common 07/security/OpenAPI v1 và BE-INT-11 trên dev2 |
| Phạm vi được phép | Người phụ trách FE chốt trong chat 22/11/2026: “merge theo bàn giao v1 của be, hãy lên kế hoạch để merge frontend và backend theo như backend đã bàn giao”. Lập kế hoạch; chưa cho phép kết luận đã tích hợp hay merge main. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng | Có được tiếp tục? |
|---|---|---|---|
| GAP-05 version | FE/mock v2, BE runtime v1; Security common khác cookie/purpose v1 | Chọn v1 làm đích tích hợp, chat 22/11/2026 | Có: lập plan và chuẩn bị merge cách ly; Security/BE vẫn cần xác nhận runtime cookie/CSRF trước auth API |
| GAP-07/02/03/08/09/11/12 | Từng feature còn phụ thuộc contract/quyền/UX | Chưa có quyết định mới | Có: chia lát độc lập; phần phụ thuộc chờ owner |
| UI review | User muốn chốt thiết kế cuối sau các task demo, FE-08 sau | Chưa duyệt toàn bộ UI | Có: chuẩn bị code/contract/check; chỉ tích hợp phần đã được user duyệt theo cổng roadmap |

## 3. Thay đổi và kịch bản

Đã lập [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]]: M0 ghép source cách ly, M1 client/contract v1, M2 auth/customer, M3 history/limit read, M4 staff read, M5 transfer, M6 mutation/export, M7 regression/merge review. Giữ các task FE-01..08 và FE-REL, ghi điều kiện UI/contract/API/browser và GAP từng phần. Cập nhật tracker GAP-05/task plan, ADR-002 addendum, roadmap addendum, GAP review addendum và FE README. Không sửa feature UI/design/code, không chạy merge thật trong lần này.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence |
|---|---|---|---|---|
| Plan theo v1/BE handoff | Bước, phụ thuộc, FET/gate và owner | M0..M7 có điều kiện vào/ra, tách source merge và functional merge, GAP-05 cookie/CSRF còn mở | Đạt mức lập kế hoạch | `09-v1-fe-be-merge-plan.md`, 22/11/2026 |
| Kiểm docs | Link, diff, trạng thái thật | `git diff --check` exit 0; bốn wikilink trong plan tồn tại. Không chạy test/API/browser vì chỉ sửa docs. Working tree trước commit chỉ gồm các file tài liệu lần này. | Đạt mức tài liệu | PowerShell/git tại `C:\project\cloude`, 22/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Feature/standards/design/guide | Chưa đổi hành vi UI/client; cập nhật khi thực hiện từng lát |
| Tracker/GAP | Thêm FE-REL-V1-PLAN Chờ review, GAP-05 ghi lựa chọn v1 và điều kiện đóng runtime/security còn mở |
| Review UI | Chưa duyệt toàn bộ |
| Review tích hợp/PR | Chưa có |
| Readiness/xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

Kế hoạch sẵn để người phụ trách FE review. Chọn v1 là quyết định đích FE, không phải phê duyệt cookie/CSRF của Security hoặc nghiệm thu browser. Bước được phép tiếp theo là M0 ghép source cách ly; M1 auth thật chờ xác nhận GAP-05 và UI từng phần. Không có PR/merge/push hay kiểm API thật trong lần này.
