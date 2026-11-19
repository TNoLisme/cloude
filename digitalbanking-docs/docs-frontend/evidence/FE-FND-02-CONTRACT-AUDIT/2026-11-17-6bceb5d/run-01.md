# Phiếu chạy task FE-FND-02-CONTRACT-AUDIT / run-01

## 1. Định danh và đầu vào

17/11/2026, Asia/Bangkok; Codex. FE frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; working tree dirty từ các phiếu/tracker 16/11, giữ nguyên các thay đổi đó. BE refs tại source-snapshot.json, đã git fetch origin. Mức chứng minh đọc source/docs/OpenAPI, chưa API/browser.

## 2. Kiểm tra sẵn sàng

Yêu cầu chat: rà docs xem replay/TTL/ownership đã chốt chưa; thiếu thì chuẩn bị docs FE–BE để người dùng chốt, đã có thì lên lộ trình. Không suy ra owner runtime đã ký hoặc cho phép đổi version FE. Phê duyệt FE/mock v2 trước đó tiếp tục có hiệu lực.

## 3. Thay đổi và kịch bản

Đã tạo [[digitalbanking-docs/docs-frontend/management/05-fe-be-contract-handoff.md]]: nguồn theo SHA, C-01..06 đã có quyết định thiết kế, Q-01..05 còn thiếu xác nhận áp dụng/UX/readiness, lộ trình bước 0..5 với task/gate/test. Đồng bộ FE README, roadmap mục 9, feature FE-03 mục 13 và tracker. Không đổi code/mock/canonical contract.

## 4. Đối chiếu hoàn thành

Nguồn đã đọc: AGENTS.md; FE README/tracker/GAP-05/07/08/09, roadmap và FE-RUN/template; FE-03 và API-IDEM-01/API-RETRY-01/FE-AUTH-02; nguồn main 07 §§3.6/5.4/8.3, Modules contract §14; dev2/rework DECISIONS DEC-TRF-003/004/005/012/013/016, 03/04/07, OpenAPI v1, handoff/matrix/risk-register BE-TRF-01. Không dùng legacy làm quyết định đích.

Lệnh thực tế và kết quả:

- git fetch origin: exit 0, cập nhật dev@0371bfc và các nhánh mới; main giữ d1efbc6. Source refs đầy đủ tại [snapshot](source-snapshot.json).
- git for-each-ref --format=... refs/remotes/origin; git diff --name-only origin/main..origin/dev -- digitalbanking-docs; git ls-tree -r --name-only <ref> -- digitalbanking-docs: xác định tài liệu mới và các nhánh liên quan.
- git show <SHA>:DECISIONS.md và các file 03/04/07/Modules contract/OpenAPI/handoff/matrix/risk-register: nguồn main/dev DECISIONS rỗng; dev2/rework có bản ghi quyết định thiết kế 16/11 theo ủy quyền, provider/QA review và integration còn Blocked. Main 07 đã chốt replay nguyên status/body và 403/404; TTL ở main còn Đề xuất. Không diễn giải toàn bộ ownership/replay là chưa có quyết định.
- Node inline + js-yaml: đọc Git objects từ các SHA ở snapshot, parse YAML, trích DEC sections và assert 004 có 201/422/correlation, 005 có 24 giờ/completed_at/purge, 012 có source-or-destination/403/404; kiểm main có 200, dev2 bỏ 200. Exit 0. Output: PASS six pinned BE branches parsed; DEC assertions. [Kết quả và hash YAML](audit-findings.json); [nguyên văn DEC trích từ dev2](decision-extracts.txt). Đây là so sánh tĩnh, không phải test runtime.
- Không chạy suite FE/BE hoặc browser vì thay đổi chỉ là tài liệu và chưa triển khai contract mới. Không có evidence cookie/retention/ledger mới. Design không sửa vì chưa đổi UI.

Kiểm tra tài liệu sau thay đổi: git diff --check -- digitalbanking-docs/docs-frontend exit 0 (cảnh báo CRLF/LF); Node kiểm 6 Markdown files, 108 wikilink local, 9 Git source paths ghim SHA, 3 relative artifacts, code fences cân bằng: Pass. [Log](docs-checks.log). Không kiểm toàn bộ heading hoặc chứng minh ngữ nghĩa bằng link check.

## 5. Tài liệu và review

Đầu ra draft FE cần người phụ trách FE và BE/API/Security/QA xác nhận đúng phạm vi. Không ghi người khác đã duyệt.

## 6. Kết quả lần chạy

Đã hoàn tất audit và bản tài liệu bàn giao/lộ trình để người dùng review. Các lựa chọn thiết kế đã tìm thấy không phải câu hỏi chọn lại; Q-01..05 chờ xác nhận đúng nguồn/version, staff scope, UX recovery và readiness. Phần generic FE/mock đã rõ được tiếp tục; chưa triển khai dependent semantics hoặc đóng GAP. Các task cha giữ trạng thái trước đó. Tài liệu task con Chờ review. Không commit/push/merge hoặc gửi thông báo cho BE trong lần này. Working tree vẫn có các ghi chép 16/11 cộng tài liệu audit mới, chưa nằm trong SHA nền.
