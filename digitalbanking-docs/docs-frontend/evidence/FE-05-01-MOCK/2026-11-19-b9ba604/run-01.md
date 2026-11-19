# Phiếu FE-05-01-MOCK / run-01

## 1. Định danh và đầu vào

19/11/2026 17:21 Asia/Bangkok. Người thực hiện: Codex. Task con FE-05-01-MOCK của FE-05-01. FE HEAD `b9ba604`, working tree đã dirty từ FE-02-01/iconography/FE-04-01-MOCK; không gán kết quả mới cho SHA này. BE: không áp dụng, mock dev/test. Contract: OpenAPI v2 Draft FE/mock. Mức chứng minh dự kiến: mock/browser.

Đã đọc README, tracker/roadmap, lifecycle/template, FE-05, CUST-LIMITS, standards UI/API, SRS FR-031/AC-017, common 07 §8.5.1, v2 YAML `getLimits`. Người dùng yêu cầu thực hiện task trong chat 19/11 sau khi chốt làm các UI demo trước vòng thiết kế cuối.

## 2. Kiểm tra sẵn sàng

Phạm vi độc lập: CUSTOMER chỉ đọc `GET /limits`, accountId tùy chọn, summary do server trả, items CUSTOMER/ACCOUNT, nullable effective fields, trạng thái loading/empty/error. GAP-07/08 chặn staff maker/checker, step-up và mutation; policy max/default, timezone reset không được suy từ fixture. Route `/customer/limits` đang là đề xuất ở CUST-LIMITS; chỉ dùng cho preview mock, không chốt route tích hợp. Không thêm handler mutation.

## 3. Thay đổi và kịch bản

`LimitsView.tsx` đọc/validate `getLimits`, chọn accountId từ account self và hiển thị nullable effective fields, cấu hình CUSTOMER/ACCOUNT. `limits-fixtures.ts` tổng hợp theo YAML; MSW chỉ GET `/api/v2/limits`, có default/empty/slow/error, 401 và 403 fixture. Đã nối `/customer/limits?customerPreview=v2` hoặc `authPreview=v2` vào shell CUSTOMER và thêm icon sidebar. Preview route chưa chốt route tích hợp. Không thêm mutation.

## 4. Đối chiếu hoàn thành

19/11/2026 17:25–17:28 Asia/Bangkok tại local dev/test:

| Kiểm tra | Kết quả | Mức chứng minh |
|---|---|---|
| `tsc -b`, `eslint . --max-warnings=0` | Exit 0 | Static |
| `node scripts/check-api.mjs` | Exit 0, generated v1/v2 khớp YAML | Contract type |
| `prettier --check ...` | Lượt đầu fail ở test mới; format rồi chạy lại exit 0 | Format |
| `vitest run` | 21 files, 171/171 tests, exit 0; test FE-05 default/empty/error đạt | Mock/component |
| `vite build` | Exit 0, có cảnh báo chunk lớn | Build |
| `git diff --check` | Exit 0, chỉ cảnh báo LF/CRLF | Diff |
| Browser IAB và Chrome Windows `http://127.0.0.1:5175/customer/limits?customerPreview=v2`; IAB thử thêm `localhost` | Đều `ERR_CONNECTION_TIMED_OUT`; Vite port 5175 báo ready | Chưa đạt browser |

Không có screenshot. Chưa kiểm keyboard/mobile/API thật, ownership runtime hoặc policy BE.

## 5. Tài liệu và review

Cập nhật FE-05, CUST-LIMITS và tracker. Chưa duyệt UI; chưa tích hợp, commit, push hoặc merge.

## 6. Kết quả lần chạy

Đang làm: code/mock/tests đạt; browser preview chưa xác minh do IAB timeout, chưa thể đặt mốc Chờ review UI/mock. Cần mở lại preview/browser để kiểm tra tương tác và ảnh rồi bàn giao UI demo. FE-05-01 cha, maker/checker và GAP-07/08 vẫn mở. Không suy từ mock ra quyền, timezone reset hay policy backend.
