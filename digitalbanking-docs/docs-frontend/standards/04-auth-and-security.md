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

### GAP-07 — lát đọc staff single-bank đã có căn cứ ngày 21/11/2026

Theo quyết định của người phụ trách FE trong chat ngày 21/11/2026, ưu tiên `docs-common` và kết quả `dev2` cho phần đã rõ. `07. API contracts.md` §3.4–3.6 và DEC-INT-008/BE-INT-03 cho phép `OPERATIONS`/`ADMIN` đọc hồ sơ customer đã mask và tài khoản theo ID; `AUDITOR` chỉ đọc tài khoản, không đọc hồ sơ customer vì seed thiếu `CUSTOMER_READ`. FE gom điều kiện hiển thị cho lát này trong `src/shared/auth/feature-policy.ts`, kiểm `userType=STAFF` và từ chối snapshot có `CUSTOMER` trộn với role staff. Customer dashboard chỉ cho `userType=CUSTOMER` và role `CUSTOMER`. Đây là policy hiển thị phía FE; API trả `403` vẫn là quyết định cuối cùng. Preview staff độc lập chưa nối phiên đăng nhập và không chứng minh phân quyền BE v2.

GAP-07 chưa đóng: SRS §4.2 và Security §5.1 còn mô tả Auditor đọc dữ liệu khách hàng, trái với permission seed/API-ADM-01; BE/BA/Security cần đồng bộ nguồn. `UserSummary` v1/v2 chỉ có roles/privileges, không có permission/capability; phương án cấp capability runtime hoặc ánh xạ đầy đủ cho mọi route/action vẫn chờ owner chốt. Không dùng lát đọc trên để tự mở search, mutation, approval hay export.

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] và [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]]. Mọi khác biệt protocol ghi trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].

## 3. Baseline Security phía FE/mock ngày 14/11/2026

> Cập nhật tích hợp v1 ngày 23/11/2026: người phụ trách FE đã chốt `/api/v1`, cookie `DB_SESSION; Path=/`, challenge `LOGIN_NEW_DEVICE` theo BE OpenAPI v1.1.0. `createV1Session` và entry `dev:api` dùng cookie do browser/BE quản lý, CSRF trong memory, OTP 202 và session/logout v1; không đọc cookie HttpOnly hoặc dùng Bearer/refresh. Phần v2 bên dưới giữ lịch sử mock. API/browser QA, review BE/Security về Security design còn mở trong GAP-05; [[digitalbanking-docs/docs-frontend/evidence/FE-01-01-V1-API/2026-11-23-c5469eb/run-01.md]].

Người phụ trách FE chọn SESSION_TOKEN, Path=/api và LOGIN_MFA theo Security. Đây là mục tiêu đồng bộ API semantics/YAML trước implementation phụ thuộc; các mô tả baseline cũ ở trên chưa được coi đã sửa chỉ từ quyết định. [[digitalbanking-docs/docs-frontend/decisions/ADR-002-session-security-contract-alignment.md]] ghi quyết định giữ v1, tạo v2 riêng theo quy tắc breaking của 07. Ngữ nghĩa v2 ở [[digitalbanking-docs/docs-common/07a. API contracts v2.md]], YAML ở [[digitalbanking-docs/openapi/digital-banking-v2.yaml]]; preview/client v1 hiện tại không tự chuyển version. GAP-05 vẫn mở cho đồng bộ nguồn, DB mapping/migration và xác nhận BE/Security/runtime. Không tự chuyển UI hoặc client theo contract chưa đồng bộ.

## 4. Nền tảng session mock v2 ngày 17/11/2026 — chờ review

`src/shared/auth/session.ts` cung cấp `createV2Session`: base phải là /api/v2 tường minh, metadata/CSRF/challenge chỉ giữ memory; trạng thái idle/loading/anonymous/authenticated/challenge/error. Login/getSession nhận đúng runtime schema v2, không dùng accessToken/refreshToken legacy. Parse chỉ giữ field đã biết; auth payload thiếu field hoặc role/purpose không hỗ trợ không được lưu như phiên hợp lệ. Username lowercase theo 07, password không trim. 202 LOGIN_MFA chỉ giữ challenge; chưa có verify/resend/FIDO trong controller này.

Mỗi lần đổi context hủy request cũ, loại response stale, clear metadata/challenge và gọi callback `onSessionCleared` bắt buộc để consumer hủy/xóa cache và intent. Consumer phải nối callback/query/router tại FE-01-01; callback đã được kiểm bằng QueryClient độc lập trong tests, chưa nối vào app legacy. Logout giữ CSRF cũ riêng cho request, clear local ngay, trả `serverConfirmed` chỉ khi nhận 204; mất mạng/403/401 hoặc thiếu CSRF trả kết quả chưa xác nhận. Nếu request thuộc context đã bị thay thế thì reject AbortError, không cập nhật phiên mới. Sau logout chưa xác nhận, bootstrap là nguồn đối chiếu server và lấy metadata/CSRF trước lần logout tiếp theo.

`session-sync.ts` có adapter BroadcastChannel cho browser, chỉ truyền chuỗi `invalidate`; peer clear local và chờ bootstrap phù hợp tại lớp UI, không echo, không truyền secret/user/payload. Consumer truyền adapter vào controller và dispose khi kết thúc lifetime. Chưa đổi giao diện hoặc wiring preview, chưa thực thi capability mapping/quyền; chưa có bằng chứng cookie jar, DB session, CSRF/TLS thực. Báo cáo tại [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CLIENT-SESSION/2026-11-17-6bceb5d/result.md]].

## 5. Bổ sung FE-01-01 ngày 17/11/2026 — chưa review

Sau giai đoạn mục4, controller có verifyOtp/resend cho challenge LOGIN_MFA đã được contract FE/mock duyệt. Verify phải khớp challengeId đang ở memory; response200 parse AuthResponse. Resend không body, response202 parse metadata; không tự reset attempts hay áp giới hạn gửi lại đang mở. Public auth request có thể giữ CSRF đã biết của ambient cookie trong memory khi login chuyển sang challenge; đổi context/accept/error xóa giá trị đó. Không bổ sung FIDO/passcode/device policy.

Host preview v2 đã nối onSessionCleared để hủy/xóa query cache, transfer/operator intent và legacy metadata; render thông báo khi peer invalidate, không tự fetch hoặc tự retry. User chốt màn metadata/logout chung mọi role, chưa nối nghiệp vụ; GAP-07 không đóng. User cũng cho phép một cờ fixture dev/test không secret trong sessionStorage, xóa khi logout; key/value và giới hạn tại guide local. Đây là ngoại lệ fixture được chốt cho FE-01-01, không cho phép persist session metadata thật hoặc key/body recovery GAP-09. Kiểm chứng hiện tại source/unit/component/MSW; browser và API thật chưa đạt. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].
