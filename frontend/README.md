# Digital Banking Simulator frontend

React 19 + TypeScript + Vite + Ant Design. Giao diện tiếng Việt, responsive, chỉ tiền VND mô phỏng. Bộ thiết kế nằm ở `../docs/design/digital-banking-codex-handoff/`; brief mới được ưu tiên.

## Chạy local

Node 22/npm 10; backend của repo chạy trước theo `../be/README.md`.

```powershell
cd frontend
npm ci
npm run dev
```

Mở http://127.0.0.1:5173. Vite proxy `/api` tới http://localhost:8080; API base mặc định `/api/v1`. Nếu backend ở địa chỉ khác, đặt `API_PROXY_TARGET` trước `npm run dev`. Có thể cấu hình `VITE_API_BASE_URL` khi build, nhưng môi trường production nên cung cấp API cùng origin và cấu hình fallback về `index.html` cho các URL SPA. Không đặt secret vào biến `VITE_*`.

Frontend không tự cấp tài khoản hoặc tiền. Dùng đăng ký thật, hoặc profile `demo` backend với credentials riêng đã cấu hình. OTP qua transport backend; mailbox local cần guard, chỉ dùng trong kiểm thử. Không có nút xem OTP, chọn vai trò giả hay toolbar thiết kế trong giao diện.

## Kiến trúc và các luồng

- `src/api/client.ts`: fetch có cookie, bearer, CSRF; refresh single-flight; GET 401 thử lại một lần, mutation không tự replay. DTO sinh từ OpenAPI, kiểm tra response trước khi dùng.
- `src/stores/`: phiên và intent/idempotency trong memory. Không lưu password/PIN/OTP/proof/token vào storage, URL hoặc query/mutation cache. Đổi user/logout xóa query cache và intent.
- `src/layouts/`, `src/components/`: tokens, auth card, sidebar/bottom navigation, trạng thái, money string/BigInt, giờ Việt Nam và form components.
- `src/features/`: auth/recovery/registration, tài khoản/PIN, transfer/history, operator/audit/risk. Quyền lấy từ server, server vẫn kiểm tra mỗi request. Staff tải theo nhu cầu.

Quên mật khẩu: yêu cầu mã → API xác minh OTP → form mật khẩu mới + xác nhận → API confirm → đăng nhập. Đăng ký: gửi OTP → API verify trả proof 300s → hồ sơ → register → đăng nhập; vẫn giữ payload OTP legacy ở backend. Reload phải xác minh lại.

Chuyển tiền lấy trạng thái từ server, không cập nhật balance optimistic. Kết quả không rõ giữ cùng intent/key để đối soát; không tạo giao dịch khác. Sau reload, kiểm tra lịch sử trước khi gửi mới. Seed balance cũng giữ key khi chưa rõ kết quả. Audit/risk chỉ đọc.

## Kiểm tra

```powershell
npm run typecheck
npm run api:check
npm test
npm run format:check
npm run build
```

`npm run api:generate` cập nhật `src/api/generated/openapi.d.ts` từ contract; không sửa file sinh bằng tay. Tests có fixture/fetch stub chỉ trong tệp test, không dùng cho sản phẩm.

`npm run api:smoke` chỉ chạy với backend/database disposable riêng và các biến `UI_QA_DISPOSABLE=yes`, `UI_QA_API_BASE`, `UI_QA_PASSWORD`, `OTP_MAILBOX_GUARD_TOKEN`. Script dùng identities demo giả được cấu hình ở backend, tạo thêm dữ liệu QA, seed và chuyển tiền mô phỏng. Không chạy với database giữ lại hoặc tài khoản người dùng. Không in secret/OTP/token vào kết quả.

Tiến độ, đối chiếu API, bằng chứng và giới hạn: `../docs/projects/frontend-mvp/continuation-status.md`.
