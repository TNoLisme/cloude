# Phiếu chạy FE-03-01-MOCK / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 21/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-03-01 / FE-03-01-MOCK, tracker mục 2 |
| FE SHA / working tree | Nền `fb0db95455bda0a6d4b9c7a3c15947476d070397`, nhánh `frontend`, working tree sạch lúc bắt đầu; trước khi sửa code đã tạo phiếu này |
| BE SHA / môi trường | `origin/dev2@740ab5fcaf1e5ec0e1f931c409881adb5c6b60ce`; chưa gọi API thật |
| Contract | FE/mock v2 Draft: `TransferCreateRequest` sourceAccountId, destination oneOf, Money string, VND, description ≤255; v1 dev2 handoff chưa được nhận vào v2 runtime |
| Mức chứng minh | Dự kiến source/unit/component/mock; chưa có API/browser thật |
| Nguồn đã đọc | FE README, tracker FE-03/GAP-02/08/09, roadmap đợt 3, FE-RUN/template, FE-03 §§1–13, design CUST-TRANSFER, API-DATA-01/API-IDEM-01, FE-AUTH-03, 07 §8.3, YAML v2 TransferCreateRequest/Money/Destination |
| Phạm vi được phép | Người dùng yêu cầu tiếp tục task tiếp theo ngày 21/11/2026; chốt trước đây cho phép xem UI bằng fixture và khóa thao tác chưa có contract rõ. Chỉ làm form và review từ schema hiện hành; chưa tạo transfer. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| Form/review | YAML v2 có sourceAccountId, destination accountId/accountNumber, Money string, description ≤255; có helper ID/Money đã duyệt | Yêu cầu tiếp tục mock UI, chat 21/11/2026 | Có cho form/review với dữ liệu tổng hợp và không gửi lệnh |
| GAP-02 recipient confirmation | Không có endpoint masked lookup; tên người nhận/ownership không thể suy từ accountNumber | Chưa có contract BE/BA | Không hiển thị tên đã xác minh; không mock lookup; review chỉ hiển thị dữ liệu người dùng nhập và trạng thái chưa xác minh |
| GAP-08 step-up/binding | FE/mock v2 và dev2 v1 chưa đồng bộ runtime; 428/retry/key cần contract/test đúng version | Chưa có BE/Security/FE chốt bản giao | Khóa POST, OTP, retry; không tạo handler nghiệp vụ |
| GAP-09 recovery | Mất key/reference khi reload chưa có lookup by key hoặc policy browser storage | Chưa chốt | Không tạo intent/key hoặc lưu bền dữ liệu transfer |
| Session/ownership | Preview customer đang là fixture; FE-FND-02/GAP-05/07 còn mở | Chưa duyệt tích hợp | Preview độc lập trong dev/test; không chứng minh sở hữu/số dư/hạn mức BE |

## 3. Thay đổi và kịch bản

- Phiếu được tạo trước khi sửa code. Sau đó thêm `src/mocks/TransferPreview.tsx` và entry dev mock trong `src/main.tsx`, route `/customer/transfers/new?transferPreview=v2`.
- Tái sử dụng `GET /api/v2/accounts` và fixture customer tổng hợp đã có; không thêm handler `POST /transfers`. Chỉ chọn account ACTIVE/VND từ response, không quyết định ownership bằng fixture.
- Form nhập accountNumber theo schema 6–20 chữ số, amount Money đúng hai decimals và >0, description tối đa255. Review hiện dữ liệu do người dùng nhập và masked source; cảnh báo recipient chưa xác minh. Nút Gửi lệnh disabled. Không tạo key, challenge, transfer, receipt hay lưu storage.
- Đồng bộ FE-03, CUST-TRANSFER design, guide local và tracker. Không sửa API contract, BE hay phần auth/route nghiệp vụ.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Form/review FE-03-01-MOCK | Schema và validation từ YAML, không gửi lệnh | 2 component/MSW tests PASS: GET accounts; form thiếu source báo lỗi; form hợp lệ đến review; submit disabled; không POST | Pass ở mức mock | `./node_modules/.bin/vitest.cmd run src/mocks/TransferPreview.test.tsx`, 2/2 PASS, 21/11/2026 |
| Lượt test đầu | Test cần chọn combobox Ant đúng semantic | Lần đầu 1 FAIL do selector `getByLabelText` khớp cả input và wrapper của Ant Select; sửa test dùng role combobox, chạy lại PASS | Đã sửa | Vitest lần đầu exit 1, các lần sau exit 0; không phải lỗi API/logic |
| Checks code/contract | Typecheck, lint, format, generated types và build đạt | `tsc -b`, ESLint files ảnh hưởng, Prettier check, `node scripts/check-api.mjs`, `vite build`, `git diff --check` đều exit 0. Build cảnh báo chunk >500 kB và annotation Rollup của dependency | Pass với cảnh báo | Chạy trực tiếp binary `.cmd` trong node_modules, 21/11/2026 |
| Full pnpm gate/browser | Runtime package chuẩn và browser preview | Chưa chạy. Môi trường hiện có Node 24/pnpm 11 so package yêu cầu Node 22/pnpm 10; run GAP-07 từng có pnpm EPERM. Không có browser screenshot/evidence | Chưa chạy | Không coi component test/build là duyệt UI hoặc CI chuẩn |
| FET-04–07 / API/browser thật | Transfer, step-up, idempotency, receipt và invariant | Không trong phạm vi mock độc lập | Bị chặn | GAP-02/08/09 và BE version/runtime |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | FE-03, CUST-TRANSFER và local guide đã cập nhật |
| Tracker / GAP | FE-03-01-MOCK Chờ review UI/mock; FE-03-01/02 và GAP-02/08/09 giữ mở |
| Review UI của người dùng | Chưa duyệt |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

Form/review demo có source và component/MSW checks, chờ review UI; chưa xem browser. Các phần bị chặn nêu ở mục 2 không triển khai trong run này. Commit local riêng sẽ được ghi trong phản hồi; không push/merge khi remote còn bị auto-review chặn và UI chưa duyệt.
