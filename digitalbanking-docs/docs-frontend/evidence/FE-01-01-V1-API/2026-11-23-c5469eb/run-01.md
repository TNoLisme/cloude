# Phiếu chạy task FE-01-01-V1-API / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 23/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-01-01 / FE-01-01-V1-API; tracker 03 |
| FE SHA / working tree | c5469eb, sạch trước thay đổi; SHA bản thay đổi là commit chứa phiếu này (tra `git log -- digitalbanking-docs/docs-frontend/evidence/FE-01-01-V1-API/2026-11-23-c5469eb/run-01.md`) |
| BE SHA / môi trường | 740ab5f, compose QA local profile; Docker Engine không chạy tại thời điểm kiểm tra |
| Contract | OpenAPI v1.1.0, SHA-256 CDB2C2A9DD3DB42F2ADB40D63B58C245483BBA15B40A5AE1D858E5E5A2A01946 |
| Mức chứng minh | Đọc source + unit/component/build; API/browser BE thật chưa chạy |
| Nguồn đã đọc | FE README, tracker FE-01-01/GAP-05, roadmap 04, lifecycle 03, template; FE-01, standards 04, AUTH-LOGIN; common 07/design security 04, OpenAPI v1; BE identity/AuthController/application.yml/compose.qa.yaml |
| Phạm vi được phép | User trong chat 23/11/2026: “tích hợp `/api/v1` có thống nhất `DB_SESSION; Path=/` và `LOGIN_NEW_DEVICE` theo OpenAPI v1.1.0 và thực hiện tiếp tục tích hợp”. Nối auth v1 trong FE; không thay contract/BE hoặc tuyên bố owner BE/Security đã review. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-05: v1 version/cookie/purpose | 07/YAML/BE dùng DB_SESSION, Path=/, LOGIN_NEW_DEVICE; security common còn chỗ gọi LOGIN_MFA | User chốt v1 cùng tên/path/purpose trong chat 23/11/2026 | Có cho FE v1; BE/Security đồng bộ tài liệu/chốt review còn mở |
| QA runtime/browser | compose.qa dùng local HTTP, Docker Engine hiện không kết nối | Chưa có bằng chứng API/browser tại lần mở phiếu | Có cho source/tests; không nghiệm thu tích hợp thật |
| UI cuối | Roadmap ghi UI demo chưa duyệt cuối; user yêu cầu tiếp tục tích hợp phần auth v1 | Chỉ nối luồng đã có, không redesign; review UI vẫn mở | Có cho luồng auth; không nhận là duyệt UI cuối |
| GAP-07 | Quyền staff/capability còn mở | Không nối staff business | Có cho session metadata và customer role đã rõ |

## 3. Thay đổi và kịch bản

- `src/shared/auth/v1-contracts.ts`: schema runtime từ generated OpenAPI v1.1.0 cho AuthResponse và challenge; chỉ nhận `LOGIN_NEW_DEVICE`, chỉ giữ field đã khai báo. `src/shared/auth/session.ts`: cùng state machine có hai entry tách base `/api/v1`/`/api/v2`, parser theo version; v2 preview vẫn là draft mock riêng. Cookie do browser và BE quản lý qua `credentials: include`; không gửi Bearer, không đọc cookie, không lưu token phiên.
- `src/features/auth/v1/AuthApiApp.tsx`, `src/main.tsx`: khi mock tắt, app vào auth v1 với bố cục/form login/OTP hiện có và màn metadata/logout; không chạy legacy Bearer hoặc MSW. Dev API mode gỡ đăng ký worker MSW còn sót khi đổi từ mock trên cùng origin và tải lại trang đang bị worker điều khiển trước khi gọi API. V1 đi `/workspace` sau OTP; customer dashboard v1 và ba GET đọc tài khoản thuộc FE-02-01-API sau, không dùng v2 API cho dữ liệu thật. Bố cục UI cuối chưa duyệt.
- `AuthFlow.tsx` nhận path builder để v1 dùng route không query mock, v2 giữ `authPreview=v2`. `AuthScreens.tsx` hiển thị challenge của cả hai version nhưng parser/session v1 và v2 kiểm purpose riêng.
- `v1-session.test.ts` dùng fetch double tổng hợp: login 202 không cấp auth, OTP 200, `GET /auth/session` đổi CSRF, logout 204 với CSRF mới, credentials include và không Authorization; v1 từ chối `LOGIN_MFA` và `/api/v2`. Fixture không mô phỏng cookie jar hay BE runtime.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| FET-01/13 source | Client v1 đúng base/challenge/session/CSRF | Unit v1 2/2 và focused auth 37/37 đạt; full 203/203 đạt; typecheck/lint/build/api:check/format đạt | Pass ở mức source/unit | Lệnh thực tế từ `digitalbanking-frontend`: `.\\node_modules\\.bin\\vitest.cmd run src/shared/auth/v1-session.test.ts src/shared/auth/session.test.ts src/features/auth/v2/AuthFlow.test.tsx`; `.\\node_modules\\.bin\\vitest.cmd run` (34 files, 203 tests); `.\\node_modules\\.bin\\tsc.cmd -b --pretty false`; `.\\node_modules\\.bin\\eslint.cmd . --max-warnings=0`; `.\\node_modules\\.bin\\vite.cmd build`; `node scripts/check-api.mjs`; `.\\node_modules\\.bin\\prettier.cmd --check src scripts vite.config.ts tsconfig.json package.json index.html eslint.config.js`; 23/11/2026 |
| v1 purpose isolation | LOGIN_MFA không hợp lệ trong v1; v2 mock không đổi | Unit v1 reject; v2 session + AuthFlow tests đạt | Pass ở mức unit/component | `v1-session.test.ts`, `session.test.ts`, `AuthFlow.test.tsx` |
| API/browser QA | Cookie cấp/xóa đúng DB_SESSION/Path, OTP, reload, CSRF, logout trên BE thật | Chưa chạy; Docker Engine không kết nối | Bị chặn | `docker info --format '{{.ServerVersion}}'` lỗi named pipe `dockerDesktopLinuxEngine`, 23/11/2026 |
| Source diff | Không whitespace mới | `git diff --check` exit 0; cảnh báo Git sẽ đổi LF/CRLF của hai file Markdown | Pass cho diff working tree | `git diff --check`, 23/11/2026 |
| Toolchain | Dùng Node 22/pnpm 10 theo project | Host hiện Node 24/pnpm 11; `pnpm exec tsc` tự thử install và lỗi EPERM symlink ngoài sandbox, nên checks chạy bằng binary đã cài trong `node_modules/.bin` | Giới hạn môi trường; checks code đạt | `pnpm exec tsc -b --pretty false` lỗi EPERM; `node_modules/.bin` như trên, 23/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | FE-01, standards/04, design/AUTH-LOGIN, guides/01 đã cập nhật; ADR-002 chỉ ghi quyết định FE v1 và giữ lịch sử v2 |
| Tracker / GAP | Tracker FE-01-01-V1-API/FE-01-01, log 10 và issue INT-01/GAP-05 đã cập nhật. BE/Security review Security design `LOGIN_MFA` và API/browser runtime còn mở |
| Review UI của người dùng | Chưa duyệt UI cuối; chat 23/11/2026 cho phép tiếp tục tích hợp v1 |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có; không merge main |

## 6. Kết quả lần chạy

Đang kiểm chứng tích hợp. FE source/code UI dùng v1, focused/full tests và local gates đạt. Chưa có API/browser QA vì Docker Engine không chạy; không chứng minh cookie/CSRF/OTP/SMS trên BE thật và chưa đóng FE-01-01/GAP-05. Người phụ trách FE chưa duyệt UI cuối; staff capability GAP-07 và customer dashboard v1 FE-02-01-API chưa nối. Tiếp theo: chạy QA stack với Node/pnpm đúng version, smoke browser login→OTP→reload→logout và đối chiếu Set-Cookie/CSRF, sau đó nối customer GET theo task riêng; BE/Security review tên purpose trong Security design. Không merge main.
