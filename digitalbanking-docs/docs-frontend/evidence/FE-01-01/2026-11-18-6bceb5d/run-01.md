# Phiếu chạy task FE-01-01 / tiếp tục ngày 18/11/2026

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 18/11/2026 Asia/Bangkok / Codex |
| Task cha / con | FE-01-01 / FE-01-01-MOCK |
| FE SHA / working tree | frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; dirty, gồm foundation/docs của các task trước và UI/mock mới; chưa commit |
| BE / mức chứng minh | Không kết nối BE; source/unit/component/MSW; browser subset ngày17/11 |
| Contract | YAML v2 2.0.0 FE/mock đã duyệt; v1 giữ nguyên; hash trong manifest |
| Nguồn đọc lại | FE README, tracker FE-01-01/GAP-04/05/07, roadmap, FE-RUN/template, feature FE-01 §10/13–15, UI/auth/testing standards, design login/challenge/bootstrap/workspace; YAML v2/07a và ADR-002 theo run trước |
| Phạm vi | Tiếp tục kiểm tra và bàn giao chi tiết theo yêu cầu “tiếp tục thực hiện task” |

## 2. Kiểm tra sẵn sàng

Hai chốt qua chat đã nhận ở lần chạy17/11: “Chốt màn phiên chung cho mọi role; chưa nối nghiệp vụ (đề xuất)” và “Cho phép cờ fixture dev/test, xóa khi logout (đề xuất)”. Nguồn: câu trả lời người phụ trách FE cho hai câu hỏi call_K6KHCdCH0VF7tkhNjLvqzl8C. Không coi là duyệt matrix GAP-07 hoặc runtime v2 của BE/Security. Không có quyết định nghiệp vụ mới trong lần tiếp tục này.

## 3. Thay đổi và kịch bản

Tiếp tục bản sửa startup đã viết ở run17/11: `waitForMockControl` chờ ServiceWorker.controller/controllerchange trước mount consumer API, timeout dev10s thì dừng khởi động, không fallback BE. Ba tests immediate/wait/timeout đã đạt ở run trước; chạy lại đủ gate ở lần này. Source UI/controller/fixtures và cách thử tại result liên kết dưới.

Các lượt gate phát hiện lỗi test lặp lại cả ngoài sandbox; không kết luận chỉ do môi trường. Đã sửa harness: test chống gửi đôi dùng response Promise chủ động thay delay120ms (15/15 UI v2 đạt khi cô lập); helper tương tác recovery chờ React act bất đồng bộ (2/2 legacy đạt khi cô lập). Giữ nguyên assertion/timeout và code nghiệp vụ/contract recovery. Chi tiết failure history và lệnh tại result.

## 4. Đối chiếu hoàn thành

| Đầu ra | Actual / mức chứng minh | Trạng thái |
|---|---|---|
| Login200/202, OTP/resend, bootstrap/logout, fixture reload | Unit/component/MSW; full log tại thư mục17/11, có timestamp thực18/11 | Xem result và log cuối |
| Browser sau sửa startup, OTP/reload/logout/đa tab/responsive | Hai lần tạo IAB (visible/hidden) đều timeout chờ webview attach; chưa điều khiển được trang | Bị chặn ở môi trường |
| Browser trước sửa | Ngày17/11 đã thấy login200 và màn phiên chung; first-load INVALID_RESPONSE, reload vào login. Không dùng quan sát cũ để chứng minh bản sửa | Subset, chưa pass FET đầy đủ |
| API thật/cookie/CSRF/permission | Chưa chạy; runtime v2/readiness và matrix còn mở | Chưa đạt integration |

## 5. Tài liệu và review

Đồng bộ result, run17/11, feature/standards/guide/design/shared layout và tracker. Người dùng chưa duyệt UI mới; chưa có PR/merge readiness. Không commit/push/merge. Log/checksum là evidence working tree, không gán cho HEAD.

## 6. Kết quả lần chạy

FE-01-01-MOCK còn Đang làm: implementation và checks tự động có kết quả, kiểm tra browser chưa hoàn tất. Bước tiếp là mở lại browser khi môi trường attach được, kiểm tra các case còn thiếu ở result, sửa lỗi nếu có rồi trình review UI/mock. Task cha/integration/GAP vẫn mở.

Kết quả cuối00:55:23 ngày18/11:6/6 gates đạt,17 files/151 tests đạt35.15s, build11.01s; log cuối `../2026-11-17-6bceb5d/test-report/full-checks.log`. Giữ nguyên lỗi lịch sử và các lần chạy cô lập tại result. Chưa có screenshot/browser verification sau sửa, user chưa duyệt UI.

[[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].
