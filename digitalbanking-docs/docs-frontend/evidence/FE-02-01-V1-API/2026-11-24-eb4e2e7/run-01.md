# Phiếu chạy task FE-02-01-V1-API / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 24/11/2026 Asia/Bangkok / Codex |
| Task cha / con | FE-02-01 / FE-02-01-V1-API, tracker 03 |
| FE SHA / working tree | Nền `eb4e2e7590b575ec316613dde49c16f07d077cec`; sạch trước sửa; code/docs đang thay đổi, SHA commit mới chưa có |
| BE SHA / môi trường | `740ab5f` dev2; Docker Compose project `banking-qa-fe-read`, profile local, HTTP `http://127.0.0.1:18080/api/v1` qua Vite proxy cùng origin |
| Contract | `digital-banking-v1.yaml` 1.1.0, SHA-256 `CDB2C2A9DD3DB42F2ADB40D63B58C245483BBA15B40A5AE1D858E5E5A2A01946` |
| Mức chứng minh | Source/unit, HTTP API thật, browser tới OTP; dashboard browser chưa đạt |
| Nguồn đã đọc | FE README; tracker FE-02-01/GAP-05; roadmap 04; lifecycle 03; template; FE-02; standards 03/04/05; design CUST-HOME/ACCOUNT/PROFILE; SRS FR-008–011, AC-005/023/024; 07 §8.2; Security design 04; OpenAPI getMyProfile/listAccounts/getAccount; FE/BE merge plan 09 |
| Phạm vi được phép | User yêu cầu ngày 24/11/2026 trong chat: “nối phần đọc của customer với BE”. Áp dụng `/api/v1` đã chốt ngày 23/11: `DB_SESSION; Path=/`, `LOGIN_NEW_DEVICE`. Chỉ dashboard, profile, account list/detail self; không nối mutation, history/limits hay merge main. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-05 | BE v1 và FE auth HTTP đã đối chiếu; browser critical flow và Security review còn mở | Chốt v1 trong chat 23/11/2026 | Có cho lát đọc v1; không đóng GAP/merge main |
| GAP-07 | Customer self có căn cứ 07 §3.4/8.2; staff matrix tổng thể còn mở | Chốt dashboard CUSTOMER trong chat 18/11/2026 | Có cho customer self; không mở staff |
| Owner v1 | `AccountDetail.owner` chỉ có customerId/status; preview v2 dùng fullName | DEC-INT-020 trong 07/OpenAPI v1.1.0 | Hiển thị mã customer, không tự suy ra tên |
| INT-10 AuthResponse privileges | 07 §7.2.1/YAML `PrivilegeCode` chỉ `CHECKER`; BE QA 24/11 trả cả permission codes trong `user.privileges`, gây `INVALID_RESPONSE` sau OTP | Chưa có chốt của người phụ trách FE hay owner BE; câu hỏi gửi người dùng trong chat 24/11/2026 | Chỉ tiếp tục phần đọc độc lập/docs; dừng đổi parser/BE và browser dashboard |

## 3. Thay đổi và kịch bản

`AuthApiApp` bật `customerDashboard` cho phiên v1 có userType/role CUSTOMER; role khác giữ màn phiên chung. `V1CustomerDashboard.tsx` dùng bố cục customer hiện tại, chỉ ba menu Trang chủ/Thông tin tài khoản/Hồ sơ, gọi đúng ba GET qua `customer-read-api.ts` với AbortSignal và query key version/userId/customerId. `onSessionCleared` invalidate reader, cancel/xóa query cache và legacy context. GET 401 báo host hủy phiên. Loading/rỗng/lỗi/retry/403/404 và direct detail được render; dashboard không hiện account status. Detail dùng `owner.customerId` thay vì fullName v2. Không nối history/limits/PIN/transfer/staff hoặc mock vào `dev:api`.

QA dùng DB/fixture tổng hợp mới, password và SMS token ngẫu nhiên chỉ trong `.qa-run.env` tạm. `.qa-customer-read.py` tạm login hai customer qua OTP mailbox QA, gọi GET tự thân/foreign/missing và assert masking, decimal Money, asOf, no-store/correlation; không in PII/secret. Lần chạy đầu fail do assertion sai của helper yêu cầu emailMasked không null trong khi contract cho phép null; đã sửa assertion và lần chạy sau pass. Browser IAB `http://localhost:5173` login `qa-sender` → challenge `LOGIN_NEW_DEVICE` → OTP; UI báo `INVALID_RESPONSE` thay vì dashboard. Đối chiếu HTTP response cho thấy `user.privileges` có `ACCOUNT_READ_SELF`, `LIMIT_READ_SELF`, `TRANSACTION_READ_SELF`, `TRANSFER_CREATE`, trái enum `CHECKER` của YAML. `AuthHttpSupport.authenticated` và `AuthController.session` lấy toàn bộ authorities nội bộ; chưa sửa vì cần chốt owner. Thử `127.0.0.1` trước đó trả login 403 do ambient cookie/CSRF, sau chuyển hostname `localhost` challenge thành công; không coi 403 đó là lỗi của ba GET.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Pass/Fail/Blocked/Chưa chạy | Evidence / command / timestamp |
|---|---|---|---|---|
| FET-03 / AC-005/023 source/UI | Đúng ba GET v1; mask, Money string, không hiện status trên home | Component/adapter tests đạt; account detail hiện owner.customerId, GET 401 báo hủy phiên | Pass ở mức unit/component | `tsc -b` exit0; test riêng 5/5 trước test 401, full tuần tự 206/206 sau test 401, 24/11/2026 |
| FET-03 / AC-005/023 API | Profile/list/detail self đúng dữ liệu server | QA hai customer: ba GET 200, masking, decimal Money, asOf, no-store/correlation | Pass ở mức HTTP QA | `docker compose -p banking-qa-fe-read ... up --no-build --wait` healthy; `python .qa-customer-read.py` exit0 sau sửa helper, 24/11/2026 |
| AC-024 API | Foreign account 403, missing 404 | QA sender đọc account receiver → 403 AUTH_FORBIDDEN; unknown ID → 404 ACCOUNT_NOT_FOUND | Pass ở mức HTTP QA | Cùng helper QA, 24/11/2026; không dùng mock làm chứng cứ server |
| FET-01/03/13 browser | Login/OTP → dashboard/profile/detail → reload/logout, không lộ cache | Browser mở login/challenge; OTP HTTP 200 nhưng parser FE chặn AuthResponse vì `privileges` ngoài enum | Blocked / Fail integration | IAB `localhost:5173`; INT-10; không có screenshot/dashboard pass, 24/11/2026 |
| FE gates | lint/typecheck/API check/format/build/tests | lint, typecheck, API check, format, build pass; full test lần đầu 204/205, một AuthPreview timeout khi chạy song song; ca đó chạy riêng 3/3 pass và full `--no-file-parallelism` 206/206 pass | Pass sau chạy tuần tự | Direct `.\\node_modules\\.bin\\tsc.cmd -b`, `eslint.cmd . --max-warnings=0`, `prettier.cmd --check ...`, `node scripts/check-api.mjs`, `vite.cmd build`, `vitest.cmd run --no-file-parallelism`; 24/11/2026. `pnpm exec` hệ thống v11/Node24 tự install và lỗi EPERM symlink ngoài workspace; không gán lỗi này cho source FE |
| Dọn QA | Không giữ fixture DB/secret tạm và Vite | Vite dừng; `docker compose -p banking-qa-fe-read ... down --volumes` exit0; `.qa-run.env` và `.qa-customer-read.py` đã xóa, Test-Path đều False | Pass | 24/11/2026; chỉ code/docs task còn trong working tree trước commit |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide cần đồng bộ | FE-02, standards 03/04, CUST-HOME/ACCOUNT/PROFILE, guide local 01 đã cập nhật trạng thái source/API/browser và giới hạn |
| Tracker / GAP | FE-02-01-V1-API và parent ở tracker 03; log 10, issue INT-10 ở 11 |
| Review UI của người dùng | Chưa duyệt UI cuối; user muốn hoàn thành các task trước khi chốt thiết kế |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

Đang kiểm chứng và bị chặn ở browser end-to-end bởi INT-10. Phần source/adapter/component và HTTP QA ba GET đạt; không thể xác nhận dashboard/hồ sơ/detail trong browser sau login, reload/logout/cross-user cache hay FE-TEST-03. Không đổi response/parser hoặc đóng GAP khi chưa có chốt đúng owner. Cần chốt xử lý `user.privileges`, sau đó chạy lại QA/browser, kiểm review UI và mốc merge cụ thể trước khi merge main.
