# Phiếu chạy task FE-FND-02-CLIENT-SESSION / run-01

## 1. Định danh và đầu vào

17/11/2026, Asia/Bangkok; Codex. FE frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; working tree dirty từ docs audit 16–17/11, chưa có code task này lúc bắt đầu. BE: không kết nối. Contract v1 1.0.0 giữ nguyên; v2 2.0.0 FE/mock đã được duyệt, hashes tại inputs.json. Mức chứng minh mục tiêu: unit/MSW, không API/browser/security thật.

Nguồn đã đọc: AGENTS.md; FE README/tracker/GAP-05/07/08/09, roadmap và handoff bước 1; FE-RUN/template; ADR-002, 07a/YAML v2 schemas LoginRequest/AuthResponse/SessionInfo/UserSummary/Challenge/Problem và ba auth operations; 07 §1.4/2/3/8.1 (timeout 10 giây, server lowercase username, password nguyên); Security và FE-AUTH-01/02/03, FE-SEC-03; FE-01, APP-BOOTSTRAP design, API-CLIENT-01/ERR-01/RETRY-01/CACHE-01 và FE-ARCH-01/02; FE-TEST-01/03/04.

## 2. Kiểm tra sẵn sàng

User yêu cầu chat: “thực hiện task và nhớ đọc kĩ các tài liệu đã chốt rồi thực hiện theo đúng đã chốt, chỗ nào không rõ hỏi tôi chốt. kết quả đầu ra kèm theo tài liệu kết quả chi tiết đi kèm”. Phạm vi ngay trước đó: shared client/session với mock v2 đã rõ. Login/session/logout và memory/CSRF/correlation/Problem/204 đủ nguồn để làm. Budget retry/poll, permission mapping, OTP verify/resend/FIDO và transfer semantics mới không được tự chốt. V2 runtime/DB/cookie topology vẫn chờ BE/Security; không đưa stub thành chứng minh security.

## 3. Thay đổi và kịch bản

Đã triển khai shared/api và shared/auth độc lập; không tự thay preview legacy hoặc nối UI trước task feature. Có adapter chỉ mang tín hiệu invalidation đa tab và callback để consumer hủy/xóa cache khi nối UI; không lưu secret browser storage.

## 4. Đối chiếu hoàn thành

Expected: credentials/CSRF, headers/status/Problem/204, request cancellation + loại stale response, login 200/202, session bootstrap/logout, phân biệt OTP 401/expired và network, local clear khi logout lỗi, đa tab không mang secret. Actual: 6 gate exit 0, 119 tests / 12 files pass, gồm 47 tests mới. Commands/expected-actual/development failures và giới hạn tại [result.md](result.md), [full log](test-report/full-checks.log), [manifest cuối](test-report/source-manifest.json). Typecheck đã sửa index-signature/Omit; lint đã sửa test-double no-this-alias, không hạ rule. Inputs/YAML/mock không drift; không đổi package hoặc cây legacy.


Kiểm tài liệu cuối: git diff --check exit 0 (chỉ warning LF/CRLF); các wikilink file target, artifact tương đối, whitespace và fences của 7 file task đều đạt. [Log docs](test-report/docs-checks.log). Không dùng link check thay review ngữ nghĩa hoặc xác nhận mọi heading.

## 5. Tài liệu và review

Đã cập nhật standards API/auth, feature FE-01/local guide và tracker cùng báo cáo chi tiết. Chưa có review task mới; design không đổi vì không sửa UI. Task cha/integration/GAP giữ mở.

## 6. Kết quả lần chạy

Chờ review — unit/mock; chưa commit/push/merge. Task cha FE-FND-02 và integration còn mở; báo cáo chi tiết ở result.md. Không tiếp nhận quyết định BE/BA/Security thay owner.
