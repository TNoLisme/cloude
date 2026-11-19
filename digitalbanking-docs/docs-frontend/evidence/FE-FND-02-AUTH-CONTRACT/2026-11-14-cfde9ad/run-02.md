# Phiếu chạy task FE-FND-02-AUTH-CONTRACT / run-02

## 1. Định danh và đầu vào

Ngày 14/11/2026, Asia/Bangkok; Codex. Task: FE-FND-02-AUTH-CONTRACT, phạm vi contract/types/mock v2. Baseline FE lúc nhận yêu cầu: cfde9ad205f4bc341d979f58b28bc2a065438fe2, nhánh frontend; working tree dirty chứa 4 task chờ review. BE: không chạy; dev snapshot 24606b529197d6dd3e6908a7cd271b69b20dd4d2 chỉ đọc trong DEV-REVIEW. Contract v1 1.0.0 giữ nguyên; v2 2.0.0 là Draft FE/mock. Nguồn: FE README, tracker, roadmap, FE-RUN/template, run-01 của task, standards/feature/design và nguồn gốc đã dẫn tại run-01; đọc lại FE-TEST-03 để giữ giới hạn nghiệm thu.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE chốt trong chat ngày 14/11/2026: “chốt các task đang cần review, push nhánh frontend với mỗi commit là mỗi task riêng chứ không commit riêng”. Ghi nhận phê duyệt 4 task con đang Chờ review và yêu cầu một commit cho mỗi task, kèm docs/evidence của task; không tạo commit duyệt hoặc commit tổng hợp riêng. Không coi đây là xác nhận BE/BA/Security, nghiệm thu task cha hoặc phê duyệt merge main. GAP-01/05/07/08 và các phụ thuộc runtime giữ mở.

Commit nền ngay trước task này: cdd8a6048542ac634a5198afb60de8a0796ee952. Working tree còn các task sau chưa commit; index được tách riêng cho task này.

## 3. Thay đổi và kịch bản

Ghi nhận phê duyệt, đồng bộ tracker/docs liên quan và tách index theo task. Giữ source working tree; bản package/lockfile/API checker ở commit tooling được tách trước delta v2. Không đổi hành vi UI hay thêm nghiệp vụ. Đầu ra và kịch bản thực hiện trước đó tại run-01.

## 4. Đối chiếu hoàn thành

Snapshot staged của task đã qua đủ 6 gate: lint, typecheck, api:check, format:check, test (72 tests), build; tất cả exit 0. Kết thúc 2026-11-14T10:42:06.188Z. Chạy trong bản export mới từ Git index, không dùng source working tree của các task sau. Dependency được dùng lại qua junction node_modules; đây không phải lần cài sạch mới. Node 22.14.0/pnpm 10.34.9 được gọi trực tiếp bằng executable đã kiểm version.

Lệnh thực tế: node C:/Users/ADMIN/AppData/Local/npm-cache/_npx/c80dab5832ec79ce/node_modules/pnpm/bin/pnpm.cjs run <gate>, lần lượt với lint, typecheck, api:check, format:check, test, build. CWD: .tmp/fe-fnd-01-review-20261114/verify-3-01/digitalbanking-frontend. [Log](test-report/review-checks.log) và [staged input manifest](test-report/review-source-manifest.json) ghi commands/exit/kết quả và blob IDs.

Build còn cảnh báo bundle lớn/annotation Zod đã có; chưa có browser E2E hoặc API thật, chưa tuyên bố CI remote đạt.

## 5. Tài liệu và review

Người phụ trách FE đã duyệt phạm vi contract/types/mock v2 trong chat ngày 14/11/2026. Tích hợp API/browser, review runtime BE/Security và CI remote chưa được ghi nhận đạt. Design không đổi vì không sửa UI. Tracker ghi Đã duyệt với mức chứng minh của task con; task cha vẫn Đang làm.

## 6. Kết quả lần chạy

Đã duyệt phạm vi task con FE-FND-02-AUTH-CONTRACT; checks phù hợp đã đạt trước khi tạo commit riêng. Phiếu/source/docs được đưa cùng commit task, không có commit duyệt riêng. Chưa push ở thời điểm ghi phiếu. Sau khi commit, tra SHA thực tế bằng git log -1 --format=%H -- đường-dẫn-phiếu-này. Kết quả push phải kiểm tra remote thật và thông báo trong chat, không ghi thành công trước khi chạy.

Kiểm tài liệu trước commit: PASS staged docs: 13 Markdown files, 141 wikilink file targets, 0 pinned Git paths; git diff --cached --check. Lệnh đọc source staged bằng git show :path và xác minh đích bằng git cat-file -e :path hoặc SHA:path; fences cân bằng.
