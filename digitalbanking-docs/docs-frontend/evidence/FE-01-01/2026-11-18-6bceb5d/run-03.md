# Phiếu chạy FE-01-01 / run-03 — bố cục login theo ảnh tham chiếu

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok, Codex; frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc, dirty với foundation/docs/UI mock chưa commit của các run trước. Đọc README FE, tracker FE-01-01/GAP-05/07, roadmap, FE-RUN/template, FE-01, UI/auth standards, design AUTH-LOGIN/shared layout, v1 LoginRequest và baseline v2/07a đã chốt. Không API thật; chỉ thay trình bày preview v2.

## 2. Kiểm tra sẵn sàng

User yêu cầu trong chat18/11: “thiết kế UI có bố cục như thế này, thay thế logo mb thành logo của dự án hiện tại, không có phần nhập mã kiểm tra, không cần có hotline, hình ảnh bên nửa trái có thể tái sử dụng lại thay mb internet banking thành tên dự án hiện tại”. Ảnh đính kèm codex-clipboard-3d72d827-6ff5-4435-a4b3-b730681b74c2.png là tham chiếu bố cục/asset, không là nguồn nghiệp vụ. Dùng Brand DB / Digital Banking hiện có. Không thêm CAPTCHA/hotline hoặc endpoint/menu/link chưa có contract. OTP ở bước202 vẫn giữ theo contract; “mã kiểm tra” trong ảnh là CAPTCHA của form login.

## 3. Thay đổi và kịch bản

Đang thực hiện layout50/50, artwork trái tái sử dụng ảnh gốc với CSS giới hạn vùng hiển thị nửa trái (không chỉnh bitmap), form phải với project Brand; mobile ưu tiên form. Thanh fixture tách khỏi nội dung sản phẩm. Login/OTP/bootstrap/logout/CSRF/mock contracts không đổi.

## 4. Đối chiếu hoàn thành

Chạy gates hiện có và kiểm tra browser nếu công cụ hoạt động. Không lấy151 tests trước thay đổi làm kết quả mới. Ghi screenshot thật nếu chụp được; thiếu browser phải ghi đúng mức.

## 5. Tài liệu và review

Đồng bộ AUTH-LOGIN/shared layout và các màn dùng host, feature/guide/tracker/result. User đang yêu cầu sửa UI, chưa duyệt UI sau sửa. Parent/integration/GAP mở; không push/merge.

## 6. Kết quả lần chạy

Đã thực hiện bố cục và ghi kết quả tại layout-result. Hai lượt6 gates/151 tests đạt, lượt sau có kiểm tra CSS hòa biên artwork; sau bổ sung aria-required đã kiểm lại lint/typecheck/format đạt (accessibility-checks.log). Build cuối6.55s. Có browser IAB1440/768/360 và login200 → reload → logout → reload về login. Screenshot thật trong layout-report/screenshots, viewport overrides reset, tab giữ cho user review.

FE-01-01-LOGIN-UI Chờ review bố cục; parent FE-01-01 và FE-01-01-MOCK vẫn Đang làm với full challenge/đa tab/API thật/readiness còn mở. User chưa duyệt UI sau sửa. Không commit/push/merge. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]].
