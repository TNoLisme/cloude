# Phiếu chạy task FE-01-01 / run-01

## 1. Định danh và đầu vào

17/11/2026, Asia/Bangkok; Codex. Nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc. Working tree đã có client/session chưa commit (119 tests local) và docs audit từ các task trước. BE: không kết nối; v2 2.0.0 FE/mock đã duyệt, v1 giữ nguyên. Đọc AGENTS/FE README/tracker/GAP-01/04/05/07, roadmap/FE-RUN/template, feature FE-01, design AUTH-LOGIN/APP-BOOTSTRAP/AUTH-CHALLENGE, FE UI/API/auth standards; 07 §3/8.1.4/8.1.8 và YAML v2 verifyOtp/resend (payload OTP 6–8 digits; resend không body, response202), 07a/ADR-002 và báo cáo client/session.

## 2. Kiểm tra sẵn sàng

User yêu cầu “thực hiện task fe 01 01”. Phạm vi mock UI login/bootstrap/logout/MFA theo contract đã rõ. Đã hỏi qua tool hai câu cần chốt: post-login khi GAP-07 chưa có matrix (màn phiên chung metadata/logout hay đợi mapping); cờ fixture không secret trong sessionStorage để tái lập bootstrap sau reload. Các phần phụ thuộc giữ chờ, có thể triển khai form/challenge và API primitives độc lập. Các giới hạn session BE/FIDO/legacy không được fixture chốt thay owner.

## 3. Thay đổi và kịch bản

Đang thực hiện, giữ preview legacy được duyệt; UI đích dùng AntD với RHF/Zod. Không chuyển user v2 thành accessToken/isPinSet/OPERATOR giả. Các request không có handler vẫn fail closed.

## 4. Đối chiếu hoàn thành

Mục tiêu: login200/202, OTP verify/resend/error/expired/locked theo schema, bootstrap loading/anonymous/error, logout/unconfirmed và multi-tab UI, không dữ liệu secret trong URL/storage/logs. Unit/component/browser sẽ ghi kết quả thật; FET-01/02/13 mức mock không thay API thật.

## 5. Tài liệu và review

Cập nhật design các màn thực đổi, feature/guide/tracker cùng result/evidence. Task/mock Chờ review sau checks, task cha/integration/GAP mở. Quyết định người dùng chờ phản hồi được ghi nguyên ý khi có.

## 6. Kết quả lần chạy

Đang làm. Không commit/push/merge hoặc tự gán review của task trước từ yêu cầu tiếp tục này.

### Kết quả cập nhật sau phản hồi và lần tiếp tục18/11

Người phụ trách FE trả lời nguyên ý trong chat17/11 cho call_K6KHCdCH0VF7tkhNjLvqzl8C: “Chốt màn phiên chung cho mọi role; chưa nối nghiệp vụ (đề xuất)” và “Cho phép cờ fixture dev/test, xóa khi logout (đề xuất)”. Hai phần phụ thuộc đã được mở trong phạm vi preview, không đóng GAP-07/05 hoặc xác nhận BE/Security.

Đã nối UI v2, summary chung, fixture flag, verify/resend và cleanup cache/intent; trước sửa worker có148 tests/6 gates đạt. Browser17/11 thấy login200 và summary sau reload; lần tải đầu gặp INVALID_RESPONSE, nên thêm gate chờ worker control và3 tests, chưa xác minh lại bằng browser. Lần tiếp tục18/11 không attach được IAB. Kết quả/checks cuối và phân loại FET tại [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]]; phiếu mới [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/run-01.md]].
