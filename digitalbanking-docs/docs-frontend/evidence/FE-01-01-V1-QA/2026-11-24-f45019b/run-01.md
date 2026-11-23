# Phiếu chạy task FE-01-01-V1-QA / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 24/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-01-01 / FE-01-01-V1-QA |
| FE SHA / working tree | `f45019b`, sạch sau commit FE-01-01-V1-API; lúc QA có `.qa-run.env`, helper tạm và working copy `gradlew` LF. Sau QA đã xóa hai file, hoàn nguyên CRLF checkout; chỉ phiếu/tài liệu lần này còn thay đổi trước commit |
| BE SHA / môi trường | `740ab5f`, Docker Compose project `banking-qa-fe`, profile local, HTTP `http://localhost:18080/api/v1` |
| Contract | OpenAPI v1.1.0, SHA-256 CDB2C2A9DD3DB42F2ADB40D63B58C245483BBA15B40A5AE1D858E5E5A2A01946 |
| Mức chứng minh | API HTTP thật; browser chưa chứng minh |
| Nguồn đã đọc | FE README/tracker FE-01-01/GAP-05, roadmap 04, lifecycle 03, template, FE-01/standards 04/AUTH-LOGIN; common 07/security design 04/OpenAPI v1; BE deployment/qa-backend-local.md, compose.yaml/compose.qa.yaml, AuthController và application.yml |
| Phạm vi được phép | User chốt FE `/api/v1` `DB_SESSION; Path=/` `LOGIN_NEW_DEVICE` trong chat 23/11 và yêu cầu tiếp tục ngày 24/11; kiểm QA local auth thật, không merge main hay ghi owner sign-off giả |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-05 | FE đã chọn v1; Security design còn từ `LOGIN_MFA` | Chat 23/11/2026; BE/Security review chưa có | Có cho smoke v1; không đóng GAP |
| QA local | Docker Engine khởi động; lần build đầu lỗi `gradlew` CRLF trên Windows; đổi working copy sang LF rồi build/compose healthy | Chỉ thay local checkout, không sửa canonical BE | Có cho HTTP/browser smoke |
| QA secret | File `.qa-run.env` chứa mật khẩu/token ngẫu nhiên dùng một lần, không commit hoặc ghi vào evidence | Theo QA backend local | Có, xóa sau kiểm |

## 3. Thay đổi và kịch bản

Không sửa source FE/BE hoặc contract trong lần QA. Script Node tạm đọc credential ngẫu nhiên từ `.qa-run.env` không commit, gọi backend thực bằng `qa-sender`, lấy OTP từ mailbox QA, kiểm login 202 → OTP 200/Set-Cookie → session/CSRF rotate → POST thiếu CSRF 403 → logout 204/xóa cookie → session 401. Script chỉ in các status/thuộc tính cookie, không in OTP, password, cookie hoặc CSRF raw. Dùng Vite `--mode development` mock tắt với `API_PROXY_TARGET=http://localhost:18080` và kiểm HTTP `/login`/proxy. IAB timeout localhost; Chrome native báo `ERR_CONNECTION_TIMED_OUT`, sau khi chạy Vite ngoài sandbox, computer use dừng vì không xác định được URL Chrome đủ chắc. Không có screenshot hay kết quả browser flow. Dừng Vite và `docker compose down --volumes` cho riêng project `banking-qa-fe`; xóa helper/env tạm, hoàn nguyên `gradlew` checkout Windows.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| QA compose | BE/Postgres/SMS healthy | Lần đầu build lỗi Gradle wrapper CRLF; bản working copy LF build bootJar đạt, cả ba container healthy | Pass sau sửa local | `docker compose -p banking-qa-fe --env-file .qa-run.env -f compose.yaml -f compose.qa.yaml up --build --wait`, 24/11/2026 |
| FET-01 API | Login/OTP/session/logout đúng v1 | `202 AUTH_CHALLENGE_REQUIRED` với `LOGIN_NEW_DEVICE` và không Set-Cookie; OTP `200` cấp phiên; session `200`; logout `204`; phiên bị thu hồi nhận `401 AUTH_SESSION_EXPIRED` | Pass ở mức HTTP QA | `node .qa-auth-smoke.cjs` exit 0 với backend/SMS thực; helper tạm đã xóa, 24/11/2026 |
| FET-13 cookie/CSRF HTTP | Cookie `DB_SESSION; Path=/`, CSRF mới, chặn thiếu CSRF | Cookie cấp có `HttpOnly; SameSite=Strict; Path=/; Max-Age=28800`, không `Secure` theo QA local HTTP; `GET /auth/session` trả CSRF mới; logout thiếu header nhận `403 AUTH_FORBIDDEN`, đủ header nhận `204` và Set-Cookie xóa `DB_SESSION; Path=/; Max-Age=0` | Pass ở mức HTTP QA local | Cùng lệnh smoke, assert từng thuộc tính/status; không kiểm browser cookie jar hay HTTPS |
| FE dev/proxy HTTP | UI entry và proxy tới BE | `GET http://localhost:5173/login` `200`; `GET /api/v1/auth/session` qua proxy `401` (anonymous) | Pass ở mức HTTP, chưa phải UI flow | Vite ngoài sandbox với `API_PROXY_TARGET=http://localhost:18080`, Node fetch assert statuses, 24/11/2026 |
| FET-01/13 browser | Login→OTP→reload→logout trong Chrome, cookie jar và UI state | IAB localhost timeout; Chrome native `ERR_CONNECTION_TIMED_OUT`; Computer Use dừng vì không xác định URL đủ chắc trước reload | Chưa chạy / Bị chặn bởi công cụ browser | Không có screenshot; không ghi browser pass |
| Dọn môi trường | Không giữ secret/QA dữ liệu/checkout thay đổi | `docker compose ... down --volumes` xóa đúng project `banking-qa-fe` và volume tổng hợp; Vite dừng; `.qa-run.env`/helper tạm xóa; `gradlew` quay về `w/crlf`, không hiện trong `git status` | Pass | `git status --short`, `git ls-files --eol`, `Test-Path` đều xác nhận, 24/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | Không đổi UI/feature/standard vì HTTP QA khớp BE v1 đã ghi trong FE-01/standards 04/guide 01 ở `f45019b`; cập nhật tracker, log và issue theo kết quả |
| Tracker / GAP | FE-01-01-V1-QA, nhật ký 10 và INT-01/GAP-05 ghi HTTP đạt, browser/Security review còn mở |
| Review UI của người dùng | Chưa duyệt UI cuối |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có; không merge main |

## 6. Kết quả lần chạy

Đang kiểm chứng tích hợp. HTTP QA thực đạt với BE `740ab5f` và FE `f45019b`; không có browser evidence nên chưa đóng FE-01-01/GAP-05 hoặc đề nghị merge main. Security common design/04 cần owner làm rõ tên OTP login `LOGIN_MFA` so với v1 `LOGIN_NEW_DEVICE`. Chạy lại browser critical flow khi công cụ truy cập localhost ổn định; test HTTPS/demo cookie `Secure` ở môi trường phù hợp. FE-02-01-API customer dashboard vẫn là task tiếp theo, không dùng màn v2 cho API v1.
