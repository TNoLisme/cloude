# Phiếu chạy task FE-BE-INT-10 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 24–25/11/2026 Asia/Bangkok / Codex |
| Task cha / con | INT-10 / BE AuthResponse v1 projection; liên quan FE-01-01-V1-API, FE-02-01-V1-API (M2) |
| FE SHA / working tree | `ce0d36efe2f86bf23c3c7494440da5fe35a616ec`; chưa commit bản sửa INT-10 và docs 25/11/2026 |
| BE SHA / môi trường | Source BE từ dev2 `740ab5f` đã ghép vào nhánh tích hợp + sửa `AuthController`/`AuthHttpSupport`; Compose `banking-qa-int10` + Vite `dev:api` (`http://localhost:5173`) |
| Contract | `07. API contracts.md` §7.2.1 và OpenAPI v1.1.0 `PrivilegeCode=[CHECKER]`, YAML SHA-256 `CDB2C2A9DD3DB42F2ADB40D63B58C245483BBA15B40A5AE1D858E5E5A2A01946` |
| Mức chứng minh | BE unit/integration + QA Docker HTTP + FE 6 gates + Playwright browser E2E (`http://localhost:5173`); review BE/Security và 2 test `409` unrelated còn mở |
| Nguồn đã đọc | AGENTS.md; FE README/tracker FE-01/02, INT-10; roadmap 04, lifecycle 03, merge plan 09, log 10, issues 11, template; FE-01/02, standard 04, design CUST-HOME/ACCOUNT/PROFILE; BE README, BE-ID-IMP-04, common 07, security design 04, Modules contract 05, OpenAPI v1.1.0; AuthController/AuthHttpSupport/LoginSessionService/OtpChallengeService/OtpDispatchService và test Identity |
| Phạm vi được phép | Chat 24/11/2026 user yêu cầu sửa response BE theo OpenAPI v1.1.0; chat 25/11/2026 người phụ trách FE chốt nguyên ý “tôi đã chốt UI cho M2, hãy thực hiện phần việc còn lại để xong task, nhớ là hãy có tài liệu kết quả đúng với những gì đã làm”. Đã giữ nguyên endpoint/DB/FE parser, kiểm chứng browser E2E và đồng bộ docs/evidence; không tự ghi duyệt BE/Security hoặc merge `main` khi chưa có xác nhận mốc merge. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| INT-10 | BE trả permission codes trong trường chỉ có `CHECKER` theo 07/YAML; FE chặn response sau OTP | User yêu cầu sửa response BE trong chat 24/11/2026 (`DEC-INT-021`) | Có, đã sửa projection và kiểm chứng; chờ owner BE/Security review |
| INT-08 UI M2 | M2 (`FE-01-01-V1-API`, `FE-02-01-V1-API`) cần chốt UI trước nghiệm thu lát | Người dùng chốt trong chat 25/11/2026: “tôi đã chốt UI cho M2” | Có cho M2; M3–M6 giữ mở |
| GAP-05/07 | Cookie/challenge owner review (Security common gọi `LOGIN_MFA`) và capability matrix staff toàn bộ vẫn mở | Không có chốt BE/Security trong chat | Không tự đóng GAP-05/07 từ QA local |

## 3. Thay đổi và kịch bản

Đã triển khai projection `AuthHttpSupport.publicV1Privileges(Set<String> authorities)` lọc chỉ `CHECKER` tại boundary HTTP dùng chung cho `AuthHttpSupport.authenticated` (`POST /api/v1/auth/otp/verify`, FIDO) và `AuthController.session` (`GET /api/v1/auth/session`). Giữ nguyên tập quyền nội bộ trong session authority để security filter kiểm tra endpoint. Bổ sung `AuthHttpSupportTest` và assertion `privileges` trong `DeviceMfaLoginHttpIntegrationTest` và `IdentitySecurityIntegrationTest`, đồng thời ghi `DEC-INT-021` trong `DECISIONS.md`.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| AuthResponse OTP/FIDO | Chỉ trả `PrivilegeCode` hợp lệ (`[]` hoặc `["CHECKER"]`) | `AuthHttpSupportTest` pass; `DeviceMfaLoginHttpIntegrationTest.otpReturnsExactAuthResponseAndConsumesProofInSamePersistedSessionDeviceBoundary` pass; `FidoLoginHttpIntegrationTest` pass; QA `qa-sender` & `qa-maker` `verify_privs=[]` | Pass (unit/integration/QA HTTP) | `.\gradlew.bat test --tests "...AuthHttpSupportTest" --tests "...DeviceMfaLoginHttpIntegrationTest" --tests "...IdentitySecurityIntegrationTest"` (25/27 `DeviceMfa` pass; 2 fail unrelated 409 vs 503/410 tại `gatewayFailureCannotIssueSessionOrAuthorizeCapturedUndeliveredProof` & `expiredProofIsDeniedWithoutSession`); `.\gradlew.bat test --tests "...FidoLoginHttpIntegrationTest"` pass; `qa-api-smoke.py` PASS, 25/11/2026 |
| GET session | Cùng projection sau reload/CSRF rotation | `IdentitySecurityIntegrationTest.id04_actor01_sessionViewReturnsOnlyTrustedActor` pass (`privileges=[]`); QA `qa-sender` & `qa-maker` `GET /api/v1/auth/session` `session_privs=[]` | Pass (integration/QA HTTP) | `IdentitySecurityIntegrationTest` pass; `verify_privileges.py` trên `banking-qa-int10` (sau đó đã `down --volumes`), 25/11/2026 |
| RBAC nội bộ | Permission codes vẫn có trong session authority và gate server | `IdentitySecurityIntegrationTest` pass; `qa-api-smoke.py` pass (scope/CSRF, 403 cross-customer/auditor/sender review, fraud review, audit export, logout) | Pass | `qa-api-smoke.py` trên stack `banking-qa-int10` build từ working tree hiện tại, 25/11/2026 |
| FE gates | lint, typecheck, api check, format, build, unit/component tests | `tsc -b`, `eslint . --max-warnings=0`, `node scripts/check-api.mjs`, `prettier --check`, `vite build`, `vitest run --no-file-parallelism` (35 files, 206/206 tests) pass | Pass | Chạy trực tiếp trong `digitalbanking-frontend`, 25/11/2026 |
| FE browser | OTP→dashboard/customer GET→reload/logout, đa user & staff | Playwright (`http://localhost:5173` → Vite proxy `http://127.0.0.1:18080`, QA Docker `banking-qa-int10`): `qa-sender` login 202 → OTP 200 (`privileges=[]`) → `Tổng quan` (không hiện status tài khoản) → `Chi tiết tài khoản` (`owner.customerId=1`) → `Hồ sơ` (masked) → `reload` (`GET /auth/session` 200, `privileges=[]`, CSRF xoay vòng) → `logout` 204 → `reload` 401; `qa-receiver` cô lập không lộ cache `qa-sender`; `qa-maker` mở `Phiên hiện tại` không gọi API customer. Lưu ý: truy cập `127.0.0.1:5173` bị CORS 403 do `APP_CORS_ALLOWED_ORIGINS` mặc định là `localhost:5173`. | Pass | `m2_browser_e2e.py` (scratch) + 3 ảnh chụp thực tế trong `screenshots/` (`m2-customer-home.png`, `m2-customer-detail.png`, `m2-customer-profile.png`); 0 pageerror/5xx; sau đó đã tắt Vite và `down --volumes`, 25/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide cần đồng bộ | Đã cập nhật `DECISIONS.md` (`DEC-INT-021`), `FE-02-customer-and-accounts.md`, `CUST-HOME.md`, `CUST-ACCOUNT.md`, `CUST-PROFILE.md`, `01-local-development.md` |
| Tracker / GAP | Đã cập nhật `03-work-tracker.md`, `10-fe-be-integration-log.md`, `11-fe-be-integration-issues.md`; `INT-10` chờ owner BE/Security review; `GAP-05/07` giữ mở |
| Review UI của người dùng | Người phụ trách FE chốt UI M2 trong chat ngày 25/11/2026 (“tôi đã chốt UI cho M2”); M3–M6 giữ mở |
| Review tích hợp / PR | Chưa có review riêng của owner BE/Security (cần xem cả 2 test `409` pre-existing ở `DeviceMfaLoginHttpIntegrationTest`) |
| Readiness / xác nhận merge | Chờ BE/Security review và xác nhận mốc merge cụ thể vào `main` |

## 6. Kết quả lần chạy

Đã hoàn tất phần triển khai kỹ thuật và kiểm chứng end-to-end cho **M2 (`FE-01-01-V1-API`, `FE-02-01-V1-API`, `FE-BE-INT-10`)**:
1. Code backend projection `AuthResponse.user.privileges` (`AuthHttpSupport`, `AuthController`) đạt unit/integration tests liên quan (`AuthHttpSupportTest`, `IdentitySecurityIntegrationTest`, `FidoLoginHttpIntegrationTest`, 25/27 `DeviceMfaLoginHttpIntegrationTest`) và QA HTTP (`qa-api-smoke.py`, `verify_privileges.py`).
2. Toàn bộ 6 gates FE (`tsc`, `eslint`, `check-api`, `prettier`, `vite build`, `vitest` 206/206) đạt.
3. Playwright browser E2E trên QA thật (`http://localhost:5173`) đạt cho `qa-sender` (OTP → dashboard → detail → profile → reload/CSRF rotation → logout), `qa-receiver` (cô lập cache) và `qa-maker` (`V2SessionSummary`, không gọi API customer), kèm 3 screenshot thực tế với dữ liệu synthetic tại `screenshots/`.
4. Người dùng đã chốt UI M2 ngày 25/11/2026.

Giới hạn còn mở (không tự đóng/merge):
- Trong `DeviceMfaLoginHttpIntegrationTest`, 2 test case (`gatewayFailureCannotIssueSessionOrAuthorizeCapturedUndeliveredProof` và `expiredProofIsDeniedWithoutSession`) trả `409 INVALID_STATE_TRANSITION` thay vì `503`/`410` (lỗi có sẵn trong luồng `OtpDispatchService`/`OtpChallengeService`, không liên quan `privileges`).
- Chưa có xác nhận của owner BE/Security cho `DEC-INT-021`/`GAP-05` và chưa có xác nhận mốc merge cụ thể để merge vào `main`.

### Bổ sung 26/11/2026 — chốt review và push nhánh tích hợp

Người dùng thông báo trong chat: “be và se đã chốt hãy giúp tôi push lên nhánh merge”. Đây là nguồn xác nhận được cung cấp cho lần push này; chưa có nội dung review/điều khoản GAP-05 của BE và Security trong chat. Vì vậy ghi nhận việc chốt do người dùng truyền đạt, không tự suy ra thay đổi contract hoặc miễn trừ hai test `409` còn lỗi. Phạm vi push là nhánh `codex/fe-be-v1-integration`, không merge `main`. SHA commit và kết quả push được báo trong phản hồi sau khi thực hiện và có thể đối chiếu bằng `git log`/remote.
