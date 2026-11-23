# Phiếu chạy FE-FND-02-V1-READ / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 23/11/2026 Asia/Bangkok / Codex FE |
| Task | FE-FND-02-V1-READ, phần độc lập của M1 theo [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]] |
| FE SHA / working tree | `codex/fe-be-v1-integration@9832fa7`, clean khi bắt đầu |
| BE SHA / contract | `dev2@740ab5f`, OpenAPI v1.1.0 `/api/v1`; không dùng API thật |
| Mức chứng minh | Dự kiến unit/fixture; không nghiệm thu session/API/browser |
| Nguồn | AGENTS, FE README/tracker, FE-API/AUTH/TEST, roadmap/lifecycle/template, FE-02, 07 §8.2 và YAML v1 |
| Yêu cầu | User chat 23/11/2026: “hãy thực hiện merge phần có thể merge được rồi tôi sẽ chốt gap 05 rồi thực hiện tiế”. Phiếu chỉ thực hiện phần độc lập, GAP-05 chờ chốt. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc | Căn cứ | Quyết định | Tiếp tục? |
|---|---|---|---|
| GET customer/account | getMyProfile/listAccounts/getAccount có v1 schema và types | User chọn v1 và cho làm phần độc lập | Có: adapter read-only test bằng fixture, chưa nối UI/runtime |
| GAP-05 | Session/cookie/CSRF v1 so với Security còn cần xác nhận | User sẽ chốt sau | Không nối session hay gọi BE thật |
| GAP-07/08 | Quyền staff và mutation/retry còn mở | Chưa có chốt | Không mở staff/mutation |

## 3. Thay đổi và kịch bản

Đã thêm `src/features/accounts/v1/customer-read-api.ts`: ba GET `getMyProfile`, `listAccounts`, `getAccount`; chỉ chấp nhận base `/api/v1`, validate ID/Money/time và dữ liệu masked, bỏ field ngoài schema. `owner` gồm customerId/status; unknown status giữ để UI có fallback an toàn. Không thêm mutation, handler hay wiring UI/session. Test mapping operationId từ YAML và response/403 qua fetch fixture.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected | Actual | Kết quả | Evidence |
|---|---|---|---|---|
| GET v1 | Path/schema v1, dữ liệu không hợp lệ fail closed | 4 tests trong hai file adapter/contract pass: 3 route, owner không lưu tên thừa, từ chối base v2/ID sai/Money sai, giữ 403 | Đạt unit/fixture | `vitest run src/features/accounts/v1/customer-read-api.test.ts src/features/accounts/v1/customer-read-contract.test.mjs --maxWorkers=1` exit 0, 23/11 |
| Gates | Test/type/API/build | `tsc -b`, `eslint . --max-warnings=0`, `node scripts/check-api.mjs`, `vite build` exit 0; Prettier lần đầu báo test file, đã format và check lại exit 0. Build cảnh báo chunk >500 kB. Sau đó bổ sung test 403 và contract, chạy lại focused tests. | Đạt trong phạm vi local | 23/11 tại `digitalbanking-frontend`; không full suite/API/browser |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Feature/design/guide | Cập nhật FE-02 và FE-API; không thay UI/guide runtime |
| Tracker/GAP | FE-FND-02-V1-READ chờ review unit/fixture; nhật ký/sổ vấn đề cập nhật; GAP-05 giữ mở |
| UI / API / merge | Chưa duyệt UI; chưa gọi API thật; chưa merge đích |

## 6. Kết quả lần chạy

Đã chuẩn bị adapter ba GET v1 độc lập, kiểm schema/fixture; chưa nối UI, session hay API BE thật. Để tiếp tục auth theo yêu cầu user phải chốt GAP-05: cookie `DB_SESSION` Path/cờ, `LOGIN_NEW_DEVICE`, CSRF và cách xử lý mâu thuẫn Security common; cần BE/Security xác nhận. Chưa merge nhánh đích hay nghiệm thu FE-TEST-03. Working tree trước commit gồm file task này.
