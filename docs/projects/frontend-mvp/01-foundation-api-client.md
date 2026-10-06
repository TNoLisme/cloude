# FE 01 — Foundation, API client và session

Trạng thái: thiết kế; chưa triển khai. Tham chiếu [roadmap](00-roadmap.md), [OpenAPI](../../../contracts/openapi.yaml), [BE integration](../backend-mvp/07-fe-contract-integration.md). Quyết định bootstrap sau reload đã chốt: refresh trả cùng shape với login.

## Cấu trúc đề xuất

```text
frontend/src/
  app/                 # router, providers, theme, query client
  layouts/             # AuthLayout, CustomerLayout, StaffLayout
  api/generated/       # openapi.d.ts, không sửa bằng tay
  api/                 # client, errors, session, endpoint functions
  components/          # UI dùng chung có nhu cầu thực tế
  features/            # auth, accounts, transfers, operator, audit, risk
  stores/              # session và UI state trong memory
  mocks/               # handlers, fixtures, scenarios
  test/                # setup và helpers
```

Không tạo abstraction cho mọi Ant component; wrapper chỉ khi có hành vi/chính sách dùng lại. Ant Form giữ dữ liệu form, Query giữ response server; không nhân bản response vào Zustand.

## Contract và request pipeline

Typegen theo BE phase 07: `openapi-typescript` 7.6.1 sinh `src/api/generated/openapi.d.ts`; fetch wrapper handwritten có typed endpoint functions. Các script api:generate/api:check giữ cùng nguồn `contracts/openapi.yaml`. Fixture builders kiểm tra TypeScript và runtime schema trong pipeline contract; TypeScript đơn lẻ không chứng minh response runtime hợp lệ.

`VITE_API_BASE_URL` trỏ base `/api/v1`; endpoint functions dùng suffix `/auth/login`, v.v. Vite proxy `/api` sang backend, giữ nguyên path. Test URL cuối không lặp `/api/v1`. Deployed same-origin theo baseline.

Request: bearer cho protected API, JSON header khi có body, correlation UUID, idempotency key cho transfer/seed. Auth cookie dùng `credentials: include` khi cần nhận/gửi cookie, gồm login/refresh/logout/CSRF. CSRF lấy từ `GET /auth/csrf`, giữ memory, gửi `X-CSRF-Token` với refresh/logout. Không đọc HttpOnly cookie bằng JavaScript.

Response: 204 không parse JSON; success parse đúng schema, đọc Idempotency-Replayed. Problem giữ status/code/detail/fieldErrors/correlationId; fieldErrors chỉ map vào field đã biết. Non-JSON/network/abort có nhóm lỗi riêng, không giả lập business failure.

## Session và quyền

Zustand memory giữ access token, UserSummary và workspace đang chọn; không persist middleware. PIN/OTP/password giữ ở form/request closure ngắn hạn, không đưa vào query cache, mutation metadata, URL, analytics hoặc log.

Trong tab đã có UserSummary: refresh có single-flight để nhiều request không cùng rotate refresh cookie. Nếu đọc an toàn nhận 401, refresh tối đa một lần và retry đọc một lần; refresh thất bại thì xóa session/cache, vào login. Mutation không tự phát lại sau 401 hoặc lỗi mạng; màn hình nghiệp vụ đưa ra retry/reconciliation phù hợp. Login/recovery lỗi 401 không kích hoạt vòng lặp refresh.

Logout gọi API với bearer/cookie/CSRF; thành công xóa token/UserSummary/CSRF, query cache và sensitive forms. Nếu logout request thất bại, có thể kết thúc phiên UI nhưng phải báo chưa xác nhận thu hồi phiên server. Đổi user phải cancel query cũ, xóa cache và không nhận response muộn của phiên cũ; query key có userId.

Khi reload, giữ màn hình ở trạng thái khôi phục phiên; lấy CSRF token rồi gọi `POST /auth/refresh` với `credentials: include` và `X-CSRF-Token`. Response có access token cùng `user: UserSummary` để điền session store và chọn workspace theo roles. `401 SESSION_EXPIRED` xóa session/cache và chuyển login; không tự bịa `/auth/me`, không coi JWT decode là hồ sơ đã được backend xác minh. Nhiều request trong cùng tab dùng một refresh single-flight; không persist access token hoặc UserSummary để bootstrap.

## Lỗi chung

| Kết quả | UI/client |
|---|---|
| 400 | Field/form error theo code; không retry nguyên request |
| 401 | Chính sách session bên trên; mutation giữ trạng thái chưa đối soát |
| 403 | Trang hoặc panel không có quyền; PIN_LOCKED xử lý riêng |
| 404 | Nội dung theo endpoint, không tiết lộ protected resource |
| 409 | Business conflict, dispatch theo code; không coi mọi 409 là replay |
| 429 | Đọc Retry-After, khóa retry trong khoảng đó; không khẳng định IP hay tài khoản tồn tại |
| 500/503/network | Lỗi có correlation; mutation có thể đã commit, không tự retry |

Không hiển thị stack/SQL/raw response bất thường. Unknown code/enum có fallback an toàn. Abort request không chứng minh server đã hủy mutation.

## Query, mock và kiểm chứng

Query keys bao gồm user, resource, filter và cursor. Không optimistic update số dư/trạng thái transfer. Sau mutation invalidate các query liên quan trong phase nghiệp vụ. Không dùng retry mặc định của thư viện cho mutation hoặc lỗi auth/permission; chính sách GET phải có giới hạn và test.

MSW được bật rõ ràng trong chế độ phát triển/test, không tự fallback từ BE lỗi sang mock. Mỗi scenario có success/loading/empty/error, hạn OTP, 429 và unknown outcome. Không log secrets; fixture chỉ dùng identity giả và không commit credential thực.

Acceptance: typegen không drift; base path/header/204/Problem đúng; refresh đồng thời chỉ một call; không lặp 401; user A không thấy cache B; menu/direct route đều kiểm tra quyền; keyboard/focus trên ba layout đúng. Kiểm tra 360/768/1024/1440px. Chưa có kết quả runtime; ghi bằng chứng sau implementation.
