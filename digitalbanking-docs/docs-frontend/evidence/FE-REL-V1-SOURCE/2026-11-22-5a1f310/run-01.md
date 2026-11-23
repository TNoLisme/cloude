# Phiếu chạy FE-REL-V1-SOURCE / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 22/11/2026 Asia/Bangkok / Codex FE |
| Task | M0 FE-REL-V1-SOURCE theo [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]] |
| FE SHA / working tree | `frontend@5a1f31023e76b576c714e382736fa6f746096978`, clean trước tạo nhánh `codex/fe-be-v1-integration` |
| BE SHA / môi trường | `origin/dev2@740ab5fcaf1e5ec0e1f931c409881adb5c6b60ce`; source merge, chưa API thật |
| Contract | OpenAPI v1.1.0 dev2; v2 FE/mock chỉ preview |
| Mức chứng minh | Dự kiến merge source và build/check, không nghiệm thu integration |
| Nguồn | FE README/tracker/roadmap/lifecycle/template, M0 plan, AGENTS, common stack/security/07/OpenAPI và BE dev2 |
| Quyết định | Chat 22/11/2026: “tôi đồng ý với đề xuất xử lí hãy xử lí vào tạo nhánh merge để thực hiện việc merge fe và be”. Người dùng đồng ý gộp .gitignore và giữ AGENTS/DECISIONS tracked; giữ quy tắc FE và BE trong AGENTS; giữ Ant Design FE, ghi Tailwind/shadcn khuyến nghị dev2 và GAP-06 mở. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc | Căn cứ / ảnh hưởng | Quyết định | Tiếp tục? |
|---|---|---|---|
| Ba conflict | `git merge-tree` báo .gitignore, AGENTS.md, common 05 | Đã chốt hướng xử lý trong chat 22/11/2026 | Có, merge source nhánh riêng |
| GAP-05/07 và UI | V1 session/security, capability và UI cần review | Chưa chốt owner tương ứng | Chỉ merge source, không nối API |

## 3. Thay đổi và kịch bản

Đã chạy `git merge --no-commit --no-ff 740ab5f` trên nhánh tích hợp. Ba conflict đã được sửa và stage theo chốt. Merge thực tế phát hiện thêm conflict modify/delete `digitalbanking-docs/docs-backend/modules/02-identity-and-auth.md`: dev2 xóa, frontend thêm ghi chú v2 FE/mock. Ngày 23/11/2026 trong chat người phụ trách FE trả lời “tôi đồng ý với đề xuất của bạn hãy thực hiện”: theo `dev2` xóa file cũ, cập nhật link FE-01 sang `docs-backend/modules/identity/README.md`; giữ lịch sử quyết định v2 ở ADR-002/07a. Quyết định này không xác nhận runtime v2.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected | Actual | Kết quả | Evidence |
|---|---|---|---|---|
| Source merge và conflict | Hai SHA là parent; conflict giải theo chốt | 22/11 merge dừng conflict; 23/11 người dùng chốt file Identity, đã giải bốn conflict; merge commit sẽ có hai parent | Đã giải conflict; chờ commit | `git diff --name-only --diff-filter=U` không có output |
| Gate | Diff, FE/BE checks phù hợp | FE `tsc -b` exit 0, `vite build` exit 0 (cảnh báo bundle lớn); BE `gradlew.bat compileJava --console=plain` exit 0 trên Java 21; `node scripts/check-api.mjs` exit 1 vì generated v1 lệch YAML dev2; `git diff --cached --check` exit 1 vì whitespace trong nhiều artifact/log/Markdown dev2, ba file conflict được kiểm riêng không báo lỗi. | Một phần đạt; contract gate chưa đạt | Các lệnh thực tế 23/11/2026; chưa chạy API/browser hoặc full suite |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Feature/design/guide | Không đổi UI/behavior; cập nhật nếu phát hiện cần |
| Tracker/GAP | FE-REL-V1-SOURCE đang kiểm chứng; GAP-05/06/07 và đồng bộ generated v1 ở M1 giữ mở |
| Review UI / tích hợp / PR | Chưa có |
| Readiness / merge đích | Chưa có; source merge cách ly được user cho phép |

## 6. Kết quả lần chạy

Ngày 23/11 người dùng duyệt xử lý file Identity theo dev2 và link mới. Source merge có thể commit trên nhánh tích hợp riêng với giới hạn đã ghi; chưa đạt contract gate vì FE types v1 chưa đồng bộ, chưa có API/browser, review UI hay push. Không coi source merge là nghiệm thu chức năng; M1 tiếp tục theo kế hoạch và GAP-05.
