# FE-01-01 — Kết quả triển khai UI/mock auth v2

Thực hiện17/11, tiếp tục18/11/2026 (Asia/Bangkok). **Đang làm — implementation có, chờ hoàn tất browser rồi review UI/mock.** Task cha chưa Hoàn thành. Không kết nối hoặc nghiệm thu API thật.

## 1. Baseline, nguồn và quyết định

- Branch frontend, nền `6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc`; toàn bộ thay đổi mới chưa commit. Working tree đầu vào đã có client/session và docs audit của task trước, được giữ nguyên phạm vi riêng. Không commit/push/merge lần này.
- Contract v2 2.0.0 FE/mock: 07a, YAML v2, ADR-002; v1 không đổi. Hash source/contract và trạng thái Git tại `manifest.json`, `test-report/git-status.txt`. Kết quả119 tests của task nền tảng trước là snapshot lịch sử, không đại diện nội dung controller được mở rộng lần này.
- Nguồn hành vi: 07 §3/8.1.4/8.1.8, 07a, OpenAPI v2 login/getSession/logout/verifyOtp/resendChallenge; FE-01 §10, FE-AUTH-01/03, FE-SEC-03, API-ERR-01, UI-DOC-01 và FE-RUN. Legacy không được dùng để quyết định nghiệp vụ đích.
- Người phụ trách FE chốt qua chat17/11: **“Chốt màn phiên chung cho mọi role; chưa nối nghiệp vụ (đề xuất)”** và **“Cho phép cờ fixture dev/test, xóa khi logout (đề xuất)”**. Nguồn phản hồi hai câu hỏi call_K6KHCdCH0VF7tkhNjLvqzl8C. Đây là chốt phạm vi preview; chưa có phê duyệt UI mới hoặc xác nhận matrix/runtime của BE/Security.

## 2. Đầu ra code và hành vi

| Source trong digitalbanking-frontend | Kết quả |
|---|---|
| src/main.tsx; mocks/PreviewShell.tsx | Entry dev/mock `authPreview=v2`, link từ viewer cũ; production/dev:api chưa dùng UI mới |
| features/auth/v2/AuthFlow.tsx | Bootstrap loading/anonymous/error, explicit retry/login; route login/challenge/workspace, stale/unmount/StrictMode guard, logout/peer notices |
| features/auth/v2/AuthScreens.tsx | AntD + RHF/Zod username/password và OTP; Vietnamese validation, double-submit lock, safe errors, Retry-After cooldown, password/OTP reset |
| shared/auth/contracts.ts; session.ts | OTP schema theo generated DTO, verify200/resend202 không body; challenge phải khớp memory, CSRF đã biết cho public auth giữ memory; restart xóa context |
| mocks/AuthPreview.tsx | Host controller/QueryClient cleanup, clear transfer/operator intent và legacy metadata; cùng summary mọi role, không tạo accessToken/isPinSet/mapping OPERATOR giả |
| mocks/v2-auth.ts | Synthetic login200/202/401/423, verify/resend/expiry/locked/single-use; scenario bootstrap503/slow/logout503; fixture flag strict `1`, no secrets, xóa khi logout |
| mocks/browser.ts; wait-for-control.ts | Sau worker.start chờ page controller trước mount API consumers; timeout dev10s dừng khởi động, không fallback BE |

Summary chỉ displayName, string IDs, userType, roles/privileges nhận từ server, trusted-device và expiry; không render CSRF/token hay link nghiệp vụ. GAP-07 chưa đóng. Challenge chỉ `[OTP]` hoạt động, phương thức khác báo chưa hỗ trợ. Wrong OTP không logout hoặc tự trừ attempts ở UI; 404/409/410/423/expiry khóa verify/resend. Resend thay metadata, xóa OTP cũ, không tự đặt max-resend/cooldown khi contract chưa chốt. Chưa triển khai passcode/FIDO/device policy/step-up giao dịch.

Fixture persistence chỉ opt-in dev/test ở host. Key `digitalbanking.fixture.v2.signedIn`, value `1`; không user/password/OTP/CSRF/token, storage unavailable thì memory. Challenge không persist. Channel fixture chỉ signed-in/signed-out; channel session chỉ invalidate. Logout503 vẫn xóa flag và UI, báo chưa xác nhận thu hồi server; cùng trang GET session fixture có thể khôi phục lại. Đây không phải cookie/session thật hoặc quyết định v1/v2 coexistence.

## 3. Kiểm tra và truy vết AC/FET

Môi trường Windows, Node22.14.0, pnpm10.34.9, Vitest3.2.4/jsdom/MSW, Vite7.3.8. Log cuối: `test-report/full-checks.log` (timestamp và exit code thật); các lần fail/pass trước giữ riêng. Không có CI run hoặc backend SHA.

| Nhóm / nguồn | Thực tế | Mức / giới hạn |
|---|---|---|
| FET-01, AC-001/003/004 | Component flow login200/202, bootstrap loading/503/retry/anonymous, direct challenge thiếu context, logout204/503; host login/MFA, fixture remount/logout | Unit/component/MSW; browser end-to-end chưa đủ |
| FET-02, AC-002/033 | Verify/resend, OTP cũ bị vô hiệu, expiry/locked/single-use, schema6–8 digits, attempts theo metadata; UI wrong/resend/expired/restart | OTP subset đạt tự động; passcode/FIDO thuộc FE-01-02, chưa đạt toàn FET |
| FET-13, AC-024/030 | Foundation transport CSRF/safe errors/stale/cache/signal regressions; host clear QueryClient/legacy token; summary chung4 roles không navigation | Không chứng minh permission matrix, browser đa tab hoặc cookie/CSRF server |
| FE-SEC-03 | Flag một giá trị; dữ liệu tùy ý không decode user/session; xóa khi logout cả503; summary không render CSRF; password/OTP reset | Tests/source; chưa audit DevTools/network/storage thực tế trên browser |
| Startup defect | Browser17/11 first load INVALID_RESPONSE, reload rồi login200/summary thành công. Thêm waitForMockControl và3 tests immediate/wait/timeout | Chưa retest browser first install sau sửa; giữ defect verification mở |

Lệnh thực thi từ root: `node .tmp/fe-fnd-01-authui-20261117/check.cjs`, helper dùng đúng pnpm pin để chạy lần lượt `pnpm lint`, `pnpm typecheck`, `pnpm api:check`, `pnpm format:check`, `pnpm test --reporter=verbose`, `pnpm build` tại digitalbanking-frontend. Log ghi đầy đủ đường CLI/lệnh/exit. Gate cuối được đối chiếu trong phần cập nhật cuối báo cáo; không dùng số148 trước sửa để đại diện bản worker mới.

Các lỗi đã sửa: lint cleanup ref của effect → callback cleanup; format sau chỉnh test → prettier; tên helper fixture bắt đầu use bị rules-of-hooks bắt nhầm → seedAuthenticatedV2Fixture; test restoreAllMocks làm mất matchMedia mock → bỏ restore không cần thiết; query OTP lỗi trùng banner/field → assert cả hai. Logs lint/format fail, pre-host146 và pre-worker148 giữ để truy vết. Không có screenshot được tạo ở lần này.

Lượt18/11 chạy worker mặc định:150/151 tests đạt, legacy `AuthPages.test.tsx` recovery không tìm được text confirmation mismatch trong thời gian query; tổng194.06s, log `parallel-test-failure.log`. Chưa kết luận nguyên nhân chỉ từ failure này. Chạy lại cùng code với `pnpm test --reporter=verbose --maxWorkers=2` để kiểm tra ảnh hưởng contention, không sửa assertion/timeout hoặc code recovery. DOM dump được loại khỏi failure log để không giữ nội dung form fixture; thông báo lỗi, stack, số tests và exit giữ nguyên.

Lượt hai worker trong sandbox:148/151 đạt,377.76s; AuthPreview host và recovery timeout20s, double-login UI timeout chờ heading. Log `two-worker-failure.log` đã bỏ DOM dump. Dòng MSW unknown `/api/v1/not-in-contract` là test fail-closed chủ động, không phải request nghiệp vụ lọt BE. Chạy cùng helper/hai worker ngoài sandbox để đối chiếu môi trường; không sửa source/timeout/assertion giữa các lượt. Kết quả cuối ghi bên dưới, không xóa failure history.

Ngoài sandbox trước chỉnh harness:149/151 đạt119.57s; recovery và delayed double-login vẫn lỗi (`outside-sandbox-failure.log`), nên chưa thể quy toàn bộ lỗi cho sandbox. Cô lập double-login vẫn lỗi: thay delay120ms bằng Promise phản hồi chủ động, đợi request thứ nhất tới handler rồi thử click lần hai, kiểm chỉ1 request trước/sau giải phóng; không tăng timeout hoặc bỏ assertion. Sau sửa,15/15 AuthFlow tests đạt trong lượt ghép với legacy (lượt ghép vẫn fail recovery).

Recovery harness cũ dùng helper async nhưng chỉ trả fireEvent đồng bộ, không chờ React act bất đồng bộ của AntD validation. Bọc các thao tác helper trong `await act(async...)`; không sửa RecoveryPage/endpoint/payload/assertion/timeout. Chạy cô lập `node node_modules/vitest/vitest.mjs run src/features/auth/AuthPages.test.tsx --reporter=dot` ngoài sandbox:2/2 đạt39.08s,18/11 lúc00:53. Đây là sửa harness regression, không triển khai recovery contract mới. Sau hai sửa harness chạy lại6 gates; log cuối và kết quả ở phần cập nhật cuối.

Tài liệu: `test-report/doc-links.log` kiểm14 file/214 wikilink target, không thiếu đường dẫn; chưa kiểm heading tự động. `test-report/diff-check.log` chỉ cảnh báo chuyển LF/CRLF, không lỗi whitespace. `manifest.json` chụp SHA256 source/contract chưa commit.

## 4. Browser và HTTP

Ngày17/11 Vite sandbox không truy cập được; restart ngoài sandbox tại127.0.0.1:5174. `test-report/local-http.log` ghi6 đường HTML/module/worker trả200. HTTP chỉ chứng minh serving, không chứng minh worker interception.

IAB17/11 đã hiển thị bootstrap lỗi INVALID_RESPONSE; retry vẫn lỗi; Đến đăng nhập hiển thị form, reload vào login rồi fixture-customer login200 đến “Phiên hiện tại” với CUSTOMER/metadata và nút logout. Sau sửa gate đã reload thấy loading, chưa đủ quan sát kết quả. Native Chrome lần thử trước timeout approval. Ngày18/11 IAB không attach được webview ở cả visible/hidden; chưa có browser version/viewport/screenshot mới, không suy diễn pass.

Các case **chưa chạy đủ browser**: fresh worker install sau gate, MFA sai/resend/verify/locked/expired; login → reload giữ phiên → logout → reload anonymous; bootstrap503/retry; logout503/recheck; hai tab logout/invalidate/cache; keyboard/error focus/screen reader;360/768/1440 và overflow. Đây là checklist tiếp tục trước mốc Chờ review UI/mock; không yêu cầu owner chốt nghiệp vụ mới.

## 5. Mở UI để xem và bước tiếp

Tại digitalbanking-frontend: `pnpm dev:mock --port 5174`; mở `http://127.0.0.1:5174/login?authPreview=v2` (port theo output Vite). fixture-customer / fixture-only cho200; fixture-mfa / fixture-only cho202, code đầu123456 và toolbar lấy code sau resend. fixture-invalid/fixture-locked cho lỗi. Toolbar có bootstrap slow/error, logout error, expired OTP; chọn rồi Mở kịch bản. Chỉ dùng dữ liệu tổng hợp. Guide chi tiết: [[digitalbanking-docs/docs-frontend/guides/01-local-development.md]].

Design login/challenge/bootstrap/workspace, shared layout, feature, auth standard, roadmap/tracker đã đồng bộ. User chưa review/duyệt UI mới. Hoàn tất checklist browser, sửa lỗi nếu có rồi trình UI/mock để duyệt; chỉ tích hợp từng phần đã duyệt sau readiness BE và contract đúng version. GAP-01/04/05/07 và transfer handoff giữ mở, chưa tự làm business task kế tiếp. Không push/merge hoặc nghiệm thu toàn bộ từ mock.

## 6. Kết quả cuối lần tiếp tục18/11

**6/6 gates local đạt**, chạy ngoài sandbox với pnpm10.34.9: lint/typecheck/api:check/format:check/test/build đều EXIT0. Test17 files/151 tests đạt (32 tests thêm so với snapshot foundation119), duration35.15s; build11.01s. Hoàn tất00:55:23 ngày18/11 Asia/Bangkok (`2026-11-17T17:55:23.567Z` trong log UTC). `test-report/full-checks.log` là log cuối sau hai sửa harness, không phải các log fail trước. Build còn cảnh báo chunk lớn như baseline; chưa release gate/browser/API thật.

FE-01-01 và FE-01-01-MOCK **Đang làm**: không còn gate tự động lỗi trong snapshot này; còn verification startup defect và checklist browser mục4, review UI và integration theo readiness. Không có câu hỏi nghiệp vụ mới cần người dùng chốt trong phạm vi đã làm. Bước tiếp được phép là browser verification khi công cụ attach lại được. Mốc commit/push/merge chưa thực hiện.
