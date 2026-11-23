# Phiếu chạy FE-FND-02-V1 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 23/11/2026 Asia/Bangkok / Codex FE |
| Task | FE-FND-02-V1: đồng bộ phần độc lập theo [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]] |
| FE SHA / working tree | `codex/fe-be-v1-integration@282de76`, clean khi bắt đầu |
| BE SHA / môi trường | `origin/dev2@740ab5f`; chưa API thật |
| Contract | `openapi/digital-banking-v1.yaml` 1.1.0; v2 FE/mock tách biệt |
| Mức chứng minh | Dự kiến generated schema, typecheck và mock/test; không chứng minh session/runtime |
| Nguồn | AGENTS, FE README/tracker, roadmap/lifecycle, FE-API/AUTH/TEST, 07/OpenAPI v1, security, dev2 DEC-INT-020 và M0 evidence |
| Phạm vi | Người phụ trách FE yêu cầu chat 23/11/2026: “hãy thay đổi để phù hợp với contract be v1.1.0 trên dev2 để bắt đầu tích hợp”. Version v1 đã chốt 22/11. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc | Căn cứ / ảnh hưởng | Quyết định | Tiếp tục? |
|---|---|---|---|
| Generated v1 | OpenAPI dev2 1.1.0 mới hơn types FE | Chọn v1, chat 22–23/11 | Có, sinh types và sửa phần phụ thuộc rõ |
| GAP-05 | `DB_SESSION`/`LOGIN_NEW_DEVICE` v1 khác Security; API/browser thật chưa có owner xác nhận | Chưa có xác nhận BE/Security | Không nối session thật; phần types độc lập được làm |
| GAP-02/03/07/08/09/11/12 | Phần recipient, quyền, mutations, recovery và export chưa chốt | Không có quyết định mới | Không mở phần phụ thuộc |

## 3. Thay đổi và kịch bản

Sinh lại `src/shared/api/generated/openapi.d.ts` từ YAML v1.1.0 bằng openapi-typescript 7.6.1. Fixture v1 `AccountOwner` chỉ gồm customerId/status; tách `v2-account-fixture.ts` giữ owner.fullName theo v2 Draft, sửa v2 mock/test dùng fixture này. Bỏ handler login v1 trả session 200 tức thì vì runtime dev2 ghi MFA 202; request v1 login chưa triển khai fail closed, fixture session chỉ phục vụ preview. Cập nhật test v1/v2 để không ép v2 Draft bằng v1 đã nâng version, test v1 owner không có tên và login chưa được mock. Không nối API thật hoặc đổi YAML/cookie/session runtime.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected | Actual | Kết quả | Evidence |
|---|---|---|---|---|
| Schema v1 và consumers | Đồng bộ v1.1.0 không phá mock | `node scripts/check-api.mjs` v1/v2 exit 0; typecheck exit 0; fixture v1/v2 được kiểm riêng, v1 login fail closed | Đạt mức schema/mock | 23/11/2026 tại `digitalbanking-frontend` |
| Gates | Typecheck/API/test/build phù hợp | `tsc -b`, `eslint . --max-warnings=0`, Prettier 6 file thay đổi, `vite build` exit 0; full `vitest run --maxWorkers=2` 30 files/195 tests pass. Lượt đầu chạy đồng thời 4 failure: 3 kỳ vọng cũ v1/v2/login đã cập nhật, 1 timeout transfer sau đó pass khi chạy riêng và full 2 workers. Build cảnh báo chunk >500 kB. | Đạt trong phạm vi local; chưa API/browser | Vitest, ESLint, Vite, Git 23/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Feature/standards/design | Cập nhật FE-01, FE-API và local guide; không đổi thiết kế UI. V2 fixture giữ bản mock cũ |
| Tracker/GAP | FE-FND-02-V1 đang kiểm chứng phần schema/mock; GAP-05/07/08 và integration giữ mở |
| UI / integration / merge | Chưa duyệt UI, chưa kiểm API thật, chưa merge |

## 6. Kết quả lần chạy

Phần schema/fixture độc lập đã đồng bộ v1.1.0 và local checks đạt; chưa triển khai client/auth v1 hoặc nối BE. BE/Security cần xác nhận cookie `DB_SESSION` Path/cờ, `LOGIN_NEW_DEVICE`, CSRF và cách xử lý mâu thuẫn Security common trước auth thật; các mutation còn chờ GAP liên quan. V2 Draft giữ nguyên, không khẳng định contract v2 đã theo v1 mới. Chưa có UI approval/API/browser/merge. Working tree trước commit gồm các file task này.
