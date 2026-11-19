# Frontend preview sau refactor

Bản xem UI từ source 9e09a71: Ant Design + Vite 7 + pnpm 10. Các màn legacy chưa đồng bộ contract dùng fixture/adapter chỉ hiển thị theo quyết định người phụ trách FE ngày 13/11/2026. Tất cả thao tác ghi legacy bị khóa.

## Xem UI

Bản đang chạy: http://127.0.0.1:5174/customer. Dùng thanh Preview UI phía trên: chọn màn, chọn trạng thái, bấm Mở màn hình. Không cần đăng nhập. Dữ liệu và vai trò tổng hợp chỉ để xem bố cục.

Khởi động lại trong thư mục digitalbanking-frontend với Node 22.14.0 và pnpm 10.34.9:

~~~powershell
pnpm install --frozen-lockfile
pnpm dev:mock --port 5174
~~~

Nếu pnpm hệ thống khác bản pin: npm exec --yes --registry=https://registry.npmjs.org --package=pnpm@10.34.9 -- pnpm dev:mock --port 5174.

## Phạm vi preview

Thanh công cụ mở 19 route, thêm APP-BOOTSTRAP bằng trạng thái Khôi phục phiên. Biên nhận cũ và OTP cũ mở từ trạng thái tương ứng tại màn transfer. Empty/error/loading áp dụng cho dữ liệu đọc; tải chậm có độ trễ 5 giây. Chúng là kịch bản UI, chưa tái hiện đầy đủ phân trang/filter/ownership/permission hoặc nghiệp vụ BE.

Auth/register/recovery/PIN/transfer/create customer chỉ xem, form bị khóa. Staff lookup hiển thị fixture sau khi nhập số điện thoại demo 0900000123 và bấm Tra cứu; nút nạp số dư/khóa tài khoản bị vô hiệu. Legacy mutation bị adapter chặn trước network. Không cần nhập password/PIN/OTP thật.

MSW auth/account dùng response contract mới. Adapter src/mocks/legacy-preview.ts chuyển dữ liệu để renderer cũ đọc được và cung cấp fixture UI cho history/audit/risk/staff; không tạo endpoint legacy tại BE. Dữ liệu thập phân không phải số nguyên VND sẽ bị adapter từ chối để tránh mất precision trong renderer cũ.

## Cấu hình và kiểm tra

pnpm dev và dev:mock đều chạy mock; dev:api là đường tích hợp legacy riêng, chỉ sử dụng sau khi readiness/contract được review. Production build không bật mock; worker chỉ được khởi động khi DEV và VITE_ENABLE_MOCKS=true. Chế độ mock tắt proxy và báo lỗi API thiếu handler.

Scripts: pnpm lint, pnpm typecheck, pnpm test, pnpm api:check, pnpm format:check, pnpm build. Chạy toàn bộ gate local bằng pnpm check. API types mới tại src/shared/api/generated/openapi.d.ts sinh từ ../digitalbanking-docs/openapi/digital-banking-v1.yaml qua pnpm api:generate. Types src/api/generated/openapi.d.ts vẫn là legacy.

Thanh preview sử dụng RHF/Zod. Các form AntD legacy chưa được chuyển toàn bộ; feature/shared/session/permissions và tích hợp thật còn theo FE-FND-02/03. ESLint 10 đã được cấu hình với recommended JS/TypeScript và hai rule React Hooks; generated files/worker/vendor output được loại khỏi lint. Chưa có test:e2e script trong task cha. Build còn cảnh báo bundle lớn và annotation Zod; không đánh dấu release gate hoàn thành.

Evidence: ../digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md. Người phụ trách FE duyệt UI/mock ngày 14/11/2026 và yêu cầu push frontend. API thật/task cha vẫn mở; chưa có quyết định merge main.

## CI nền tảng

.github/workflows/frontend.yml chạy frozen install và pnpm check cho thay đổi FE/OpenAPI/workflow, với Node từ .node-version, pnpm từ packageManager và Actions pin SHA. Lần bổ sung này mới kiểm chứng local; không tuyên bố CI remote đạt trước khi có run trên GitHub. Cài đặt sạch và pnpm check đã đạt trong task FE-FND-01-TOOLING; task con được người phụ trách FE duyệt ngày 14/11/2026. .gitattributes giữ LF cho source frontend, API drift check chuẩn hóa CRLF/LF khi so sánh.

## Data utilities theo contract mới

Task FE-FND-02-DATA bổ sung src/shared/lib/money.ts và id.ts, dùng type sinh từ OpenAPI mới. Parse/serialize tiền bằng BigInt minor units và giữ ID dạng string; formatter mới giữ đủ hai decimals. Module này chưa được nối vào renderer legacy/adapter preview, nên hạn chế chỉ hiển thị số nguyên VND của preview vẫn còn cho đến task migration riêng. Session/quyền/transport và tích hợp BE vẫn mở. Phần utility được người phụ trách FE duyệt ngày 14/11/2026.

## Contract và mock v2 riêng

V1 tiếp tục giữ nguyên. Contract/types/mock v2 đã được người phụ trách FE duyệt ngày 14/11/2026; runtime BE/Security chưa xác nhận. Contract v2 Draft FE/mock có tại ../digitalbanking-docs/openapi/digital-banking-v2.yaml, ngữ nghĩa 07a; types tại src/shared/api/generated/v2/openapi.d.ts. pnpm api:generate sinh cả hai; api:generate:v1/v2 sinh riêng, api:check kiểm tra cả hai (node scripts/check-api.mjs v1 hoặc v2 để kiểm tra riêng).

Worker có handler /api/v2/auth/login/session/logout và OTP verify/resend (FE-01-01 ngày17–18/11); FIDO chưa có handler, sẽ fail closed. Response header mô phỏng SESSION_TOKEN, HttpOnly/Secure/SameSite/Path=/api; logout kiểm tra CSRF fixture. State mock v1 và v2 chỉ phục vụ fixture, không phải chính sách coexistence BE. Các màn legacy vẫn dùng adapter chỉ xem. Không nhập credential thật.

## Preview auth v2 — FE-01-01 đang làm

Ngày18/11, theo ảnh người dùng: đổi sang artwork trái/form phải, BrandDB/Digital Banking, không CAPTCHA/hotline; mobile ưu tiên form. Toolbar nay mở qua “Điều khiển bản xem thử” góc dưới trái. Bố cục và browser smoke mới có tại ../digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md; chưa phải phê duyệt UI hoặc nghiệm thu auth/integration đầy đủ.

Chạy dev:mock như trên, mở `/login?authPreview=v2` hoặc link “Xem đăng nhập v2” trên toolbar. Dùng fixture-customer / fixture-only cho login200, fixture-mfa / fixture-only cho202. OTP đầu123456; sau resend lấy mã mới bằng nút fixture trên toolbar. Có kịch bản bootstrap slow/error, OTP expired và logout error. Form mới dùng AntD/RHF/Zod, controller memory v2; mọi role vào màn metadata/logout chung theo chốt FE17/11, chưa nối nghiệp vụ.

Người dùng cho phép sessionStorage chỉ một cờ `digitalbanking.fixture.v2.signedIn=1` để khôi phục fixture khi reload, xóa khi logout; không chứa metadata/secret. Challenge không persist. Startup chờ worker control trước mount consumer API; timeout10s dừng, không fallback BE. Mode này chỉ bật dev/mock; production/dev:api chưa nối UI v2.

Unit/component/MSW có kết quả, browser mới có subset trước sửa startup; lần18/11 công cụ browser không attach nên chờ retest và review UI. Chưa nghiệm thu cookie/security/runtime BE. Chi tiết và checklist còn thiếu: ../digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md.
