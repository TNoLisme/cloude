# Phiếu chạy FE-FND-02-V1-TRANSPORT / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 23/11/2026 Asia/Bangkok / Codex FE |
| Task | FE-FND-02-V1-TRANSPORT, phần độc lập M1; [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]] |
| FE SHA / working tree | `codex/fe-be-v1-integration@44c0a77`, clean khi bắt đầu |
| BE SHA / contract | `origin/dev2@740ab5f`, OpenAPI 1.1.0 `/api/v1`; không gọi API thật |
| Mức chứng minh | Unit transport và YAML, không session/cookie/browser thật |
| Nguồn | FE README/tracker, roadmap/lifecycle/template, FE-API/FE-AUTH/FE-TEST, 07 §5–6 và OpenAPI v1, client.ts và tests |
| Phạm vi được phép | Chat 23/11 user “tiếp tục thực hiện”, sau chốt làm phần độc lập rồi user chốt GAP-05. Không nối runtime/session hoặc thay policy đang mở. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc | Căn cứ | Quyết định | Tiếp tục? |
|---|---|---|---|
| Status/header | YAML v1 khai báo Problem, 409/422/428, correlation và replay | Chọn v1, user 22/11 | Có, kiểm transport bằng fetch doubles |
| GAP-05/08 | Auth/session, binding và runtime chưa được owner xác nhận | User sẽ chốt GAP-05 sau | Không triển khai auth/retry/mutation |

## 3. Thay đổi và kịch bản

Đổi import type `Problem` của shared client từ generated v2 sang generated v1; không thay logic transport. Thêm `v1-transport.test.mjs` đọc ví dụ trong YAML v1 và dùng fetch doubles kiểm 422 replay, 428 challenge, 409 in-progress/Retry-After, correlation gốc/request hiện tại và không retry. Không gửi mutation hoặc gọi BE. V2 session/mock regression được chạy lại.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected | Actual | Kết quả | Evidence |
|---|---|---|---|---|
| Status/header v1 | Preserve thông tin Problem/trace/replay theo contract | `vitest run src/shared/api/v1-transport.test.mjs src/shared/api/client.test.ts src/shared/auth/session.test.ts src/mocks/v2-auth.test.ts --maxWorkers=1`: 4 files/53 tests pass | Đạt unit; không chứng minh BE | 23/11, `digitalbanking-frontend` |
| Gate | Test/type/API/format theo thay đổi | `tsc -b`, `eslint . --max-warnings=0`, `node scripts/check-api.mjs` v1/v2, Prettier hai file đều exit 0. Typecheck đầu fail vì js-yaml chưa có declaration trong file TS test; chuyển test sang `.mjs` như contract tests hiện có, chạy lại đạt. Không chạy build/API/browser trong lần chỉ đổi type/test. | Đạt trong phạm vi local | 23/11; `git diff --check` exit 0 |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Feature/design/guide | Cập nhật FE-API standards; không đổi UI, behavior runtime hay guide |
| Tracker/GAP | FE-FND-02-V1-TRANSPORT chờ review unit; cập nhật nhật ký/sổ vấn đề, GAP giữ mở |
| UI / API / merge | Chưa duyệt UI, chưa gọi BE thật, chưa merge đích |

## 6. Kết quả lần chạy

Đạt phần type và unit độc lập theo contract v1. Chưa thực hiện auth, retry/binding, API thật hoặc browser; GAP-05/08/09 còn mở và user sẽ chốt GAP-05. Không merge đích. Working tree trước commit gồm các file task này.
