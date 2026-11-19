# Phiếu FE-01-01 / run-04 — ghi nhận chốt kịch bản

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok, Codex; nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; working tree dirty, gồm code/docs chưa commit của các task trước. Không kết nối BE; mức chứng minh source/mock/browser subset theo run-03. Đối chiếu AGENTS, FE-RUN, roadmap, tracker FE-01-01/FE-02-01/FE-FND-03/GAP-01/05/07, feature FE-02 và handoff FE–BE.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE trả lời trong chat ngày 18/11/2026: “ok tôi chốt với các kịch bản hiện tại, đã qua task mới được chưa”. Câu này xác nhận các kịch bản auth vừa trình bày: login200/202, lỗi credentials, OTP sai/hết hạn/khóa/resend, bootstrap và logout chưa xác nhận. Ghi nhận theo đúng phạm vi câu trả lời; không tự suy ra đã duyệt bố cục FE-01-01-LOGIN-UI, duyệt toàn bộ browser/mock, API thật, BE/Security hay merge.

## 3. Thay đổi và kịch bản

Cập nhật tracker và ghi câu trả lời người dùng. Không thay code, mock, contract hay test; không commit/push/merge. Task tiếp theo theo roadmap là FE-02-01 profile/list/detail, phụ thuộc FE-01-01 và FE-FND-03. Có thể chuẩn bị và thực hiện phần độc lập customer/read mock theo contract đã rõ; kiểm tra readiness/điều kiện task cụ thể trước khi thay code. Mapping staff/GAP-07, route business, runtime session v2/GAP-05 và API thật vẫn giữ gate.

## 4. Đối chiếu hoàn thành

Kịch bản đã được user chốt về phạm vi/hành vi, không thay bằng chứng kiểm thử. Run-03 có 6 gates/151 tests và browser subset; full OTP/đa tab, fresh worker installation và integration còn mở. Không chạy lại test vì chỉ ghi quyết định vào tài liệu.

## 5. Tài liệu và review

Tracker liên kết phiếu này. Review bố cục login, nghiệm thu FE-01-01, xác nhận BE/Security và merge chưa được người dùng xác nhận trong câu trả lời này.

## 6. Kết quả lần chạy

Đã ghi nhận chốt kịch bản. Có thể chuyển sang chuẩn bị task kế tiếp, không đóng task cha hoặc các GAP. User hỏi khả năng chuyển task, chưa yêu cầu bắt đầu triển khai task mới trong lần này.
