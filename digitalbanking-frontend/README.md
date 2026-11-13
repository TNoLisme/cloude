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

Scripts: pnpm typecheck, pnpm test, pnpm api:check, pnpm format:check, pnpm build. API types mới tại src/shared/api/generated/openapi.d.ts sinh từ ../digitalbanking-docs/openapi/digital-banking-v1.yaml qua pnpm api:generate. Types src/api/generated/openapi.d.ts vẫn là legacy.

Thanh preview sử dụng RHF/Zod. Các form AntD legacy chưa được chuyển toàn bộ; feature/shared/session/permissions và tích hợp thật còn theo FE-FND-02/03. Chưa hiện thực ESLint/test:e2e script trong task cha. Build còn cảnh báo bundle lớn và annotation Zod; không đánh dấu release gate hoàn thành.

Evidence: ../digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md. Người phụ trách FE duyệt UI/mock ngày 14/11/2026 và yêu cầu push frontend. API thật/task cha vẫn mở; chưa có quyết định merge main.
