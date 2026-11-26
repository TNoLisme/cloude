# Phiếu chạy FE-REL-V1-M3 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 26/11/2026 Asia/Bangkok / Codex |
| Task cha / con | M3: FE-04-01-API và FE-05-01-READ-API (customer read) |
| FE SHA / working tree | Nền `edd588971b2fffe004fce49fadde5e742925f228`, clean trước task; kết quả kiểm trên working tree chưa commit, SHA commit/push đối chiếu Git sau lần chạy |
| BE SHA / môi trường | Source BE ghép từ `dev2@740ab5f` và INT-10 `edd5889`; QA image local `sha256:e2d688a212fde6c54c45f9f30f249474fc552c90f9bc6321f4357920142a51c0`, Compose `banking-qa-m3`, BE `http://127.0.0.1:18082/api/v1`, SMS `:18083`, Vite `http://localhost:5174`, Chrome 157.0.8103.79 |
| Contract | OpenAPI v1.1.0 SHA-256 `CDB2C2A9DD3DB42F2ADB40D63B58C245483BBA15B40A5AE1D858E5E5A2A01946` |
| Mức chứng minh | FE unit/component/full gates + BE QA HTTP + Chrome Playwright browser, dữ liệu synthetic; review PR/mốc merge còn riêng |
| Nguồn đã đọc | FE README, tracker M3/GAP, roadmap 04, merge plan 09, lifecycle 03 và template; FE-04/05, design CUST-HISTORY/CUST-TX/CUST-LIMITS, standards API/auth/test; common 07 §8.4/8.5, OpenAPI v1.1.0, BE TransactionHistoryController/LimitCustomerController |
| Phạm vi được phép | Người dùng chat 26/11/2026: “thực hiện task m3 giúp tôi”; sau đó chốt “Duyệt UI mock hiện tại cho M3”. Cho phép nối hai màn lịch sử/hạn mức customer vào GET v1, thiết kế cuối vẫn sửa sau. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| UI M3 | Plan 09 yêu cầu duyệt UI từng màn; tracker FE-04/05 trước đó chỉ là demo mock | Người dùng chốt “Duyệt UI mock hiện tại cho M3” trong chat 26/11/2026 | Có, riêng hai màn M3; thiết kế cuối còn có thể sửa |
| Sort lịch sử | FE-04-MOCK đã chốt dùng thứ tự mặc định, sort whitelist BE chưa rõ | Chat 18/11/2026 | Không thêm bộ chọn sort; adapter chỉ dùng default |

## 3. Thay đổi và kịch bản

Thêm adapter GET customer self v1 cho `listTransactions`, `getTransaction`, `getLimits`, validate response theo OpenAPI v1.1.0. Không gửi `customerId`/`sort`; kiểm `direction` yêu cầu `accountId`, range thời gian/số tiền và reference an toàn. Dùng lại bố cục `HistoryView`/`LimitsView` đã có, nối vào shell v1 sau login qua `/customer/history`, `/customer/history/:reference`, `/customer/limits`. Tách query key v1/v2 và invalidate adapter khi logout. Giữ nguyên mock v2, auth/routing staff và mutation.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| Contract v1 history/limits | `listTransactions`, `getTransaction`, `getLimits`; customer self, default sort | Common 07 §8.4/8.5, YAML v1.1.0 và BE controller khớp path/query/scope cho lát customer | Pass | SHA YAML ở mục 1, 26/11/2026 |
| FE gates | lint, typecheck, generated API check, format, Vitest, build | ESLint 0 warnings; `tsc -b` pass; `node scripts/check-api.mjs` match v1/v2; Prettier lần đầu báo file test mới rồi `--write` và `--check` pass; Vitest 37 files, 211/211 pass; Vite build pass (cảnh báo chunk >500 kB) | Pass sau sửa format | Chạy trực tiếp `.\node_modules\.bin\{eslint,tsc,prettier,vitest,vite}.cmd` trong `digitalbanking-frontend`, 26/11/2026 |
| BE HTTP history/limits | List/filter/page/empty UTC range; account scope 403, missing detail 404, limits 200 | `qa-api-smoke.py` PASS; `qa-m3-http.py` PASS: page 0/1 size1, range 2099 rỗng, direction thiếu account 400, foreign account history/limits 403, reference thiếu 404; `getLimits` 200 | Pass QA local | `python scripts/qa-api-smoke.py` và `python ..\digitalbanking-docs\docs-frontend\evidence\FE-REL-V1-M3\2026-11-26-edd5889\qa-m3-http.py` với env synthetic riêng; 26/11/2026 |
| Browser M3 | OTP → history list/detail → limits → reload, không JS error | Chrome Playwright PASS; ảnh thật `screenshots/m3-history.png`, `m3-detail.png`, `m3-limits.png`; user `qa-sender`, viewport 1280×800, Vite proxy sang BE QA; 0 pageerror | Pass critical flow | `qa-m3-browser.py` (lượt đạt chạy từ scratch path trước khi chuyển cùng nội dung sang phiếu); 26/11/2026 |

## 5. Tài liệu và review

Đã cập nhật feature/design/tracker/log theo lát M3. Chỉ ghi UI mock M3 được duyệt từ câu trả lời riêng ngày 26/11, không suy rộng sang M4–M6. Script QA trong thư mục phiếu không chứa secret; mật khẩu/token synthetic được cấp trong file env tạm và sẽ xóa khi dọn stack. Browser lượt đầu thất bại do script lấy OTP cũ từ mailbox (mailbox đã trả newest-first), lượt sau sửa script và đạt; lượt đầu Chrome thiếu Playwright browser bundle, chuyển sang Chrome cài sẵn; lượt tiếp theo Vite sandbox không truy cập từ Chrome, mở Vite QA trên host rồi kiểm lại. Không quy các lỗi harness này thành lỗi sản phẩm.

## 6. Kết quả lần chạy

Đã đạt triển khai và kiểm chứng kỹ thuật lát M3 customer read trên BE QA local. UI mock hai màn được người dùng duyệt cho lát này. Chờ review PR/code và xác nhận mốc merge cụ thể theo FE-TEST-03/plan 09; chưa merge `main`. Phần maker/checker, staff history, sort selector, thiết kế UI cuối và các GAP liên quan giữ ngoài phạm vi M3.

Sau QA, đã tắt Vite `:5174`, chạy `docker compose --env-file .qa-m3.env -p banking-qa-m3 -f compose.yaml -f compose.qa.yaml down --volumes` thành công và xóa `.qa-m3.env` cùng `scripts/__pycache__` tạm. Preview mock đang dùng `:5173` là tiến trình có trước task, không dừng hoặc thay đổi. Script QA và ba screenshot thật được giữ trong phiếu; không lưu password, OTP, SMS token hay cookie.
