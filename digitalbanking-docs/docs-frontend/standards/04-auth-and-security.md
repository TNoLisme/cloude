# Auth, quyền và bảo mật FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-AUTH |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, Security, QA |

## 1. Nguồn

Đọc 07 §3/6 và [[digitalbanking-docs/docs-common/design/04. security.md]], [[digitalbanking-docs/docs-common/03. SRS.md]]. GAP-05 ghi khác biệt cookie/path và GAP-07 ghi permission matrix. Đích tích hợp cần nguồn chung thống nhất, có contract test với backend đích.

## FE-AUTH-01 — Session lifecycle

SPA sử dụng opaque HttpOnly session cookie theo 07/OpenAPI, giữ user/session metadata và csrfToken trong memory. Cookie do server cấp và thu hồi. GET /auth/session khôi phục metadata khi reload. FE-FND-02 triển khai cơ chế này theo công việc tiếp theo trong tracker.

Bootstrap phân biệt anonymous/session expired với network error. Lỗi AUTH_SESSION_EXPIRED hoặc session invalid xóa dữ liệu của phiên và dẫn login. Lỗi AUTH_OTP_INVALID 401 hiển thị trong challenge. Tránh interceptor chung biến mọi 401 thành logout.

POST /auth/logout mang CSRF. Khi server xác nhận, clear cache/intent và về login. Nếu mất mạng khi logout, clear local, thông báo server logout chưa được xác nhận và cho thử lại. Lần bootstrap kế tiếp đối chiếu server. Session expiry dùng metadata idle/absolute, server quyết định hiệu lực.

Đồng bộ logout/session invalidation đa tab qua event/BroadcastChannel chỉ mang tín hiệu, mỗi tab xóa dữ liệu và kiểm tra session phù hợp. Secret không truyền qua kênh này.

## FE-AUTH-02 — CSRF và quyền

Request thay đổi dữ liệu dùng cookie phải có X-CSRF-Token lấy từ login/getSession. Transport tập trung gắn token, hủy request nếu context phiên thay đổi. Return URL chỉ nhận route nội bộ đã cho phép.

CUSTOMER, OPERATIONS, ADMIN, AUDITOR là roles theo 07. CHECKER là privilege. Navigation và action theo route policy/capability tập trung, request vẫn chịu kiểm tra server. UserSummary có roles/privileges trong YAML hiện tại, session permissions hoặc mapping tĩnh được chốt ở GAP-07.

Approval checker phải khác maker và có quyền với loại yêu cầu. UI vô hiệu action không hợp lệ kèm lý do. Quyết định cuối dựa server để xử lý thay đổi quyền giữa lúc mở màn hình và submit.

## FE-AUTH-03 — Challenge và step-up dùng chung

Challenge controller dùng cho LOGIN, TRANSFER_STEP_UP và SENSITIVE_ACTION nhưng giữ context/binding riêng. Nhận challengeId, purpose, methods, expiresAt, remainingAttempts và maskedDestination từ server. 07 dùng LOGIN_NEW_DEVICE trong luồng login, security dùng LOGIN_MFA. GAP-05 yêu cầu đồng bộ tên purpose trước tích hợp.

1. Giữ request/purpose và key nếu là transfer.
2. Hiển thị phương thức thực sự được contract cho phép với challenge cụ thể, MVP OTP theo OI-API-17.
3. Verify OTP qua verifyOtp cho login, verifyStepUp cho transfer/sensitive action.
4. Verify thành công tạo session đối với login, còn step-up quay lại request gốc theo đúng binding.
5. Transfer retry cùng key/body. Sensitive action gửi X-Step-Up-Challenge khi contract yêu cầu.
6. OTP sai giữ flow, cập nhật attempts từ server. Hết hạn/locked dừng submit, cho resend/restart khi server cho phép.
7. ResendChallenge thay metadata theo response và xóa OTP nhập cũ. Chỉnh payload/decision phải xin challenge mới phù hợp.

Đồng hồ UI tính từ expiresAt, xử lý tab sleep bằng thời gian tuyệt đối. FIDO/options/enrollment theo GAP-04. Phương thức fallback thực thi theo contract đã duyệt.

## FE-SEC-03 — Dữ liệu nhạy cảm và browser storage

| Dữ liệu | Giao ước |
|---|---|
| Password/passcode/OTP/proof/CSRF | Giữ memory trong lifetime tối thiểu, xóa khi verify/logout/expiry/đóng flow |
| Session cookie | HttpOnly do server quản lý |
| Query cache | DTO đọc được phép, masked theo server, xóa khi đổi phiên |
| Mutation variables | Không persist, clear/reset sau xử lý, review devtools/telemetry để tránh giữ secret |
| URL | Route ID/reference và filter an toàn theo allowlist, tránh password/OTP/token/full PII trong query/hash |
| Logs/telemetry | Chỉ code, correlation và metadata đã allowlist, không log request body nhạy cảm |
| Browser local/session storage | Chỉ preference vô hại được phân loại, key/payload recovery chờ GAP-09 |
| Ảnh/video/test fixture | Dữ liệu tổng hợp, mask trước lưu evidence |

Số tài khoản và PII lấy masked fields từ server. UI render text qua React, HTML bên ngoài cần sanitize theo policy được review. Clipboard/export chỉ cho field và quyền đã chốt. Build env VITE_* là public, secret thuộc backend/secret store.

## FE-SEC-04 — Kiểm chứng

FE-REL-01 kiểm tra direct URL, action theo quyền, cross-user cache, session expiry, CSRF, reload, đa tab và maker/checker. FE-REL-02 review bundle/source map/console/network/evidence theo môi trường. Security scan và invariant backend phối hợp theo [[digitalbanking-docs/docs-common/09. Test plan.md]].

## 2. Quy tắc liên quan

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] và [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]]. Mọi khác biệt protocol ghi trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].
