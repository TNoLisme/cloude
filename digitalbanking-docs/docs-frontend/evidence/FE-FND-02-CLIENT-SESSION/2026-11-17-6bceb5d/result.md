# Kết quả chi tiết FE-FND-02-CLIENT-SESSION

| Thuộc tính | Giá trị |
|---|---|
| Ngày / nhánh / commit nền | 17/11/2026, Asia/Bangkok; frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc |
| Phạm vi | Shared JSON client và memory session controller theo v2 FE/mock đã duyệt |
| Trạng thái | Chờ review — unit/mock; chưa nghiệm thu API/browser |
| Working tree | Đã có docs audit 16–17/11 chưa commit từ trước. Code và tài liệu task này chưa nằm trong SHA nền |
| Contract | V2 2.0.0 theo ADR-002/07a/YAML hiện hữu; không tiếp nhận semantics Transfer dev2 vào snapshot này |
| Mức chứng minh | Unit/fetch double + MSW ở Node; không API/browser/cookie security thật |
| Phiếu / input | [run-01](run-01.md), [hash input gốc](inputs.json) |

## 1. Phạm vi đã được phép và căn cứ

Người phụ trách FE yêu cầu thực hiện task nền tảng đã thống nhất, đọc kỹ nguồn đã chốt, hỏi trước phần chưa rõ và giao báo cáo chi tiết. ADR-002 và 07a cho phép triển khai FE/mock v2 SESSION_TOKEN/Path=/api/LOGIN_MFA, giữ v1. Handoff bước 1 cho phép generic client/login/session/logout mock làm độc lập với các quyết định delivery version, staff scope và recovery UX còn chờ BE/FE.

Nguồn và AC: FE-ARCH-01/02 (shared/api, shared/auth, memory), API-CLIENT-01 (credentials/header/cancellation/10s/204), API-ERR-01 (Problem/safe message), FE-AUTH-01/02 (session/CSRF/expired/logout), FE-SEC-03 (secret memory, đa tab signal-only), FE-01 và schemas/operationId login/getSession/logout trong v2. Các mô tả quyền/retry còn mở không được quyết định qua code này. FET-01/13 chỉ kiểm subset nền tảng; nghiệm thu các journey API/browser còn thuộc FE-01-01/REL.

## 2. Code được bổ sung

| File trong digitalbanking-frontend | Chức năng |
|---|---|
| src/shared/api/client.ts | Fetch JSON, credentials/CSRF/correlation, status + header metadata, Problem + safe message, 204, abort/timeout/invalidation/disposal |
| src/shared/auth/contracts.ts | Runtime guards cho LoginRequest, AuthResponse và ChallengeRequiredResponse; type đích từ generated v2; dùng ID utility đã duyệt |
| src/shared/auth/session.ts | Controller memory login/bootstrap/logout, state/subscription, stale generation, callback cleanup, lifecycle disposal |
| src/shared/auth/session-sync.ts | BroadcastChannel adapter chỉ gửi/nhận literal invalidate, không secret/PII/payload |
| src/shared/api/client.test.ts | Unit với fetch double, dữ liệu tổng hợp; không tạo handler nghiệp vụ mới |
| src/shared/auth/session.test.ts | Integration primitives với MSW auth v2 đã có, test doubles cho race và signal multi-tab; QueryClient độc lập để kiểm cleanup callback |

Các module không được tự khởi tạo trong app/preview. Không thêm dependency, thay package/lockfile, YAML/generated types hay handlers đã duyệt. Source UI/route cũ chưa được thay. Cơ chế cache/query app hiện hữu chưa được migrate: controller cung cấp callback bắt buộc, bước wiring thuộc FE-01-01.

## 3. Hành vi client và ranh giới dữ liệu

- Consumer truyền base URL rõ ràng; controller chỉ chấp nhận path base /api/v2, không query/hash. API path không được thành absolute URL hoặc dot traversal. Không tự fallback sang /api/v1 hoặc BE khi thiếu mock handler; worker/test MSW đã có vẫn fail closed.
- Request có credentials include/cache no-store và X-Correlation-ID UUID. POST mặc định cần CSRF trước network; public login chọn optional. Khi đã biết CSRF của phiên trước, login giữ riêng token đó cho request có thể mang cookie ambient dù local context đã được xóa. Password giữ nguyên; username lowercase theo 07.
- Không đọc Set-Cookie/cookie jar và không tự cấp/thu hồi cookie HttpOnly. Không gửi Authorization Bearer. Không giữ password, accessToken hoặc refreshToken trong state. Runtime schema chỉ project metadata đã biết; payload legacy/không hợp lệ không được nhận như phiên hợp lệ.
- Success trả data và metadata tách biệt. ApiError giữ code, metadata và Problem khi shape/status hợp lệ. Header correlation/request ID không ghi đè body correlation. Giữ Retry-After nguyên wire value, không áp retry/poll budget tự chọn. Metadata lỗi chỉ copy các field trace đã khai báo, không giữ nhầm data chứa auth secrets.
- 204 trả data undefined và không parse JSON. Mất mạng, timeout 10 giây và invalid JSON/Problem có code nội bộ an toàn; các lỗi đó không chứng minh mutation FAILED. Message hiển thị lấy từ catalog/fallback, không dùng raw server detail/HTML. Form errors chỉ đi vào allowlist field và dùng message an toàn.
- Callback hết phiên chỉ xảy ra cho Problem hợp lệ 401 AUTH_SESSION_EXPIRED trong context hiện hành. OTP/credentials 401 không tự logout. Response/request thuộc context đã đổi bị AbortError kể cả fetch double bỏ qua abort.
- Không auto retry/refresh/poll; caller gọi lại theo flow đã chốt. Giá trị metadata idle/absolute expiry chỉ là thông tin, không có timer client tự kết luận server đã revoke session.

## 4. Session state và lifecycle

| Thao tác | Diễn biến / kết quả |
|---|---|
| Khởi tạo | idle, auth/challenge null; không request tự động |
| bootstrap | Xóa context cũ, loading, GET /auth/session; 200 metadata hợp lệ → authenticated; AUTH_SESSION_EXPIRED → anonymous; network/invalid → error và reject để UI phân biệt |
| login | Validate payload, giữ CSRF đã biết nếu có, clear context/cache và loading; POST /auth/login không trim password; 200 → authenticated; 202 LOGIN_MFA → challenge, không có auth |
| logout | Giữ CSRF riêng cho request, clear local auth/challenge/cache/intent qua callback, invalidate request cũ và signal peer. POST /auth/logout nhận 204 mới serverConfirmed true; lỗi/no CSRF → serverConfirmed false |
| Logout không xác nhận | Local clear không chứng minh server revoke. Bootstrap kế tiếp vẫn có thể khôi phục phiên server; caller cần đối chiếu trước thao tác tiếp theo. UI thông báo/retry sẽ được nối ở FE-01-01 |
| Đổi user/context | Hủy pending, clear metadata/challenge và gọi onSessionCleared trước khi nhận dữ liệu phiên mới. Login/bootstrap/logout cũ không được ghi đè phiên mới |
| Peer invalidate | Clear local, hủy request/cleanup, không gửi echo; UI consumer sẽ quyết định thời điểm bootstrap theo feature. Signal chỉ literal invalidate |
| dispose | Hủy request, clear metadata/challenge, cleanup callback, unsubscribe và đóng adapter; chặn dùng lại controller/client đã dispose |

`onSessionCleared` phải hủy/xóa query cache và transfer intent/challenge của consumer khi nối app. Tests dùng QueryClient độc lập, không tự nhận toàn bộ cross-user cache của app legacy đã migrate. Adapter không gửi user ID, CSRF hoặc password qua BroadcastChannel, không dùng storage để đồng bộ secret.

## 5. Kịch bản và đối chiếu AC

| Nhóm expected | Case đã hiện thực để kiểm | Mức chứng minh |
|---|---|---|
| API-CLIENT-01 | Credentials, CSRF required/public optional, serialize Money/ID string không mất precision; JSON/204; timeout/caller abort/invalidate/dispose; base/path guard | Fetch double + auth MSW |
| API-ERR-01 | Problem + header/body trace riêng, validation field allowlist, safe message kể cả unknown/prototype code; invalid upstream JSON; no implicit retry dù retryable/Retry-After | Unit |
| FE-AUTH-01 login/session | Anonymous bootstrap, login200, login202 LOGIN_MFA, metadata reload từ MSW server, logout CSRF/204, no legacy secret JSON, legacy/unknown-role/malformed challenge rejection | Unit/MSW |
| FE-AUTH-01 error | OTP401 giữ auth; session401 clear auth; bootstrap network khác anonymous; offline/expired logout chưa xác nhận; explicit retry bootstrap | Unit/MSW |
| FE-AUTH-01 race | Login cũ về sau logout; bootstrap cũ về sau login mới; logout cũ về sau login mới; bỏ stale expiry response | Controlled fetch double |
| FE-SEC-03 | Project known fields, strip extra auth secret; local clear/cache cleanup trước đổi user; multi-tab no echo/no metadata; disposal | Unit/MSW, chưa browser |
| FE-TEST-03 | Sáu gate package; docs/evidence/manifest và phạm vi review | Kết quả thực tế ở mục 6 |

Các test headers/replay metadata là kiểm primitive bảo toàn dữ liệu HTTP bằng fetch double, không phải triển khai handler transfer mới hoặc chứng minh ledger/idempotency BE. Các override lỗi/race chỉ nằm trong tests; runtime mock tiếp tục chỉ có handlers v2 đã duyệt.

## 6. Lệnh, môi trường và kết quả thực tế

Môi trường Windows/PowerShell, Node 22.14.0, pnpm 10.34.9 gọi trực tiếp từ bản có sẵn; dependency có sẵn, không cài mới hoặc tự nhận clean-install trong task này. CWD gate: C:/project/cloude/digitalbanking-frontend. Contract/mock hashes được đối chiếu với inputs.json trước khi kết thúc.

Các lần kiểm tra phát triển đã chạy:

1. `node node_modules/prettier/bin/prettier.cjs --write src/shared/api/client.ts src/shared/api/client.test.ts src/shared/auth` để format các file mới.
2. `node node_modules/vitest/vitest.mjs run src/shared/api/client.test.ts src/shared/auth`: bản đầu 31 tests pass; sau bổ sung race/disposal/serialization checks có 42 tests pass. [targeted.log](test-report/targeted.log) là log bản 42 tests, chưa bao gồm các case bổ sung cuối.
3. Typecheck lần đầu `node node_modules/typescript/bin/tsc -b` fail: Omit trên generated Problem có index signature làm mất kiểu errors. Đã sửa bằng Pick các property định nghĩa, sau đó typecheck exit 0. Không sửa generated types để vượt gate.
4. Full gate lần đầu dừng ở ESLint no-this-alias trong mock BroadcastChannel; đã sửa test double, không tắt rule. [Log lỗi giữ lại](test-report/full-checks-first-lint-failure.log).
5. Full gate kế tiếp đạt 117 tests và build, log [trước bước siết path guard](test-report/pre-path-guard-full-checks.log). Rà soát cuối phát hiện newline hoặc slash mã hóa có thể đi qua bộ kiểm tra dot traversal; đã reject các ký tự/segment không hợp lệ và bổ sung hai case. Vì source thay đổi, chạy lại toàn bộ gate cho bản cuối; không lấy kết quả 117 làm kết quả của source mới.

Gate cuối chạy lần lượt lệnh `node C:/Users/ADMIN/AppData/Local/npm-cache/_npx/c80dab5832ec79ce/node_modules/pnpm/bin/pnpm.cjs run <gate>` với lint, typecheck, api:check, format:check, test, build. Lệnh test thêm `--reporter=verbose` để report giữ tên case. Đây là sáu script thành phần của `pnpm check`, không dùng pnpm khác trên PATH để giả pin version. [Full gate log](test-report/full-checks.log) ghi start/end, mỗi command/exit và toàn bộ output.

**Kết quả cuối: cả 6 gate exit 0; 12 test files / 119 tests pass (47 tests mới: 27 transport + 20 session), build đạt.** Kết thúc UTC 2026-11-17T08:52:45.494Z. Build còn warning annotation Zod và chunk lớn từ baseline; chưa tối ưu bundle hoặc nhận release gate đạt. Không cài lại dependency, không chạy GitHub CI/browser/API thật trong task này.

[Manifest cuối](test-report/source-manifest.json) ghi hash code kiểm chứng, 6 gate và UTC thực tế. Đã assert inputs.json không drift cho YAML v1/v2/mock v2; git diff --exit-code HEAD cho các contract/canonical semantics/package/lockfile và cây legacy api/app/mocks exit 0. Đây là evidence working tree chưa commit, không phải checks của commit nền.

## 7. Tài liệu đồng bộ và cách review

Đã cập nhật standards API/auth, feature FE-01, guide local và tracker với task con/ranh giới implementation. Các mô tả nguồn mới chờ review, không thay phê duyệt baseline cũ. Thiết kế APP-BOOTSTRAP và AUTH-LOGIN không đổi layout/component vì chưa wiring UI; feature ghi rõ sự phân biệt này.

Để review code/tests từ repo hiện tại:

```powershell
cd C:/project/cloude/digitalbanking-frontend
node node_modules/vitest/vitest.mjs run src/shared/api/client.test.ts src/shared/auth
```

Không có URL UI mới để review trong task foundation này. Preview 20 màn cũ tiếp tục được kiểm chứng theo mốc trước, không gọi đó là UI session v2 mới. Đầu ra tiếp theo là wiring module vào FE-01-01: subscriber lifecycle, query/intent cleanup callback, bootstrap/login/challenge/logout UI và browser checks theo contract đã được xác nhận.


Kiểm tài liệu cuối: git diff --check exit 0 (chỉ warning LF/CRLF); các wikilink file target, artifact tương đối, whitespace và fences của 7 file task đều đạt. [Log docs](test-report/docs-checks.log). Không dùng link check thay review ngữ nghĩa hoặc xác nhận mọi heading.

## 8. Phần còn mở và trạng thái giao

Task con Chờ review sau khi các checks đã đạt. Task cha FE-FND-02 còn query-key factory/download/stream và integrations; FE-01-01 còn UI wiring/verify flow. GAP-05 runtime cookie/session/DB/HTTPS và Q-01/02/03/04/05 vẫn chờ đúng owner/phần tương ứng. Không đổi quyền/navigation, ngân sách retry/poll, persistence intent/key, FIDO/enrollment hoặc tiếp nhận transfer semantics dev2 qua fixture.

Mock không kiểm HttpOnly/Secure/SameSite/Path thực, browser cookie jar, CSRF enforcement BE, DB session, cross-tab browser flow hoặc invariant money/audit. Không chạy API thật/browser E2E/CI remote trong lần này. Chưa commit/push/merge; không gán code mới hoặc kết quả unit/MSW này cho commit nền chưa chứa thay đổi.
