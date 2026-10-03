# Frontend MVP — Roadmap và quyết định giao diện

Ngày: 2026-10-02. Trạng thái: đặc tả frontend theo phạm vi giao diện đã được người dùng duyệt; chưa triển khai code và chưa có bằng chứng tích hợp BE thật.

## Nguồn yêu cầu

- [OpenAPI](../../../contracts/openapi.yaml): HTTP path, schema, header, response.
- [API baseline](../../baseline/api-and-team-contract.md) và [Quality baseline](../../baseline/quality-security-and-cloud.md): nghiệp vụ và tiêu chí chất lượng.
- [Backend roadmap](../backend-mvp/00-roadmap.md), [BE–FE integration](../backend-mvp/07-fe-contract-integration.md), [6 quyết định transaction/security](../2026-10-01-security-transaction-refinements.md).

Đây là kế hoạch triển khai UI theo contract hiện có. Không sửa backend để hợp thức hóa màn hình. Nếu schema và hướng dẫn nghiệp vụ mâu thuẫn, ghi issue và chặn nhánh liên quan; phần độc lập vẫn triển khai được bằng mock. Phê duyệt bộ docs không đồng nghĩa tất cả phase đã được code/test.

## Phạm vi và lựa chọn đã chốt

React + TypeScript + Vite, Ant Design + Ant Design Icons, Ant Design Form. Theme chung và CSS Modules cho bố cục đặc thù. TanStack Query quản lý server state; Zustand quản lý session/UI trong memory. Một ứng dụng tại `frontend/`, ba layout Auth/Customer/Staff. Không thêm form engine hoặc component library thứ hai.

Tiếng Việt, theme sáng, xanh đậm chủ đạo, nền xám nhạt, nội dung trắng. Luôn có nhãn “Mô phỏng — không sử dụng tiền thật”. MVP không gồm dark mode, đa ngôn ngữ, quản lý role/user mới, xuất báo cáo, dashboard tổng hợp không có API, real-money/payment integration.

Tiền hiển thị `5.000.000 ₫`, gửi API chuỗi nguyên `"5000000"`. Giữ dữ liệu tiền dạng string/BigInt khi cần format để không mất chính xác; không dùng số dư client làm quyết định nghiệp vụ. Timestamp API UTC; UI hiển thị `Asia/Ho_Chi_Minh`, nhãn giờ Việt Nam, chuyển bộ lọc sang UTC.

## Layout, route và quyền

Route dưới đây là route FE, không phải API mới. Chọn thư viện router và pin phiên bản khi triển khai foundation sau khi kiểm tra toolchain; ưu tiên React Router làm đề xuất kỹ thuật.

| Route | Layout/quyền | Nội dung |
|---|---|---|
| `/login`, `/register`, `/recover` | Auth/public | Đăng nhập, đăng ký OTP, recovery hai kênh |
| `/customer` | Customer/CUSTOMER | Profile tóm tắt, tài khoản, số dư |
| `/customer/accounts/:accountId` | Customer/CUSTOMER | Chi tiết tài khoản thuộc mình |
| `/customer/transfers/new` | Customer/CUSTOMER + PIN configured | Chuyển tiền theo bước |
| `/customer/transfers`, `/customer/transfers/:transferId` | Customer/CUSTOMER | Lịch sử và trạng thái |
| `/customer/profile` | Customer/CUSTOMER | Hồ sơ chỉ đọc, vào quản lý PIN |
| `/customer/pin/setup`, `/customer/pin/change`, `/customer/pin/reset` | Customer/CUSTOMER | Quản lý PIN |
| `/staff/customers`, `/staff/customers/new` | Staff/OPERATOR hoặc ADMIN | Tra cứu chính xác, tạo tại quầy, thao tác tài khoản |
| `/staff/audit` | Staff/AUDITOR hoặc ADMIN | Audit chỉ đọc |
| `/staff/risk` | Staff/OPERATOR, AUDITOR hoặc ADMIN | Risk flags chỉ đọc |
| `/workspace` | Authenticated | Chọn khu vực khi có nhiều role |
| `/forbidden`, route không khớp | Theo phiên hiện tại | Không có quyền/không tìm thấy |

Sau login: một khu vực hợp lệ thì vào thẳng; nhiều khu vực thì chọn tại `/workspace`. Customer chưa có PIN vào setup trước các chức năng chuyển tiền. Không ép staff setup PIN dựa trên `isPinSet=false`; staff có `customerId=userId` chỉ là alias, không dùng làm Customer thật. Menu không quyết định quyền backend.

Customer desktop sidebar; mobile bottom navigation: Tổng quan / Chuyển tiền / Lịch sử / Hồ sơ. Staff sidebar theo quyền; seed và block/unblock nằm trong kết quả chi tiết tra cứu khách, không có trang liệt kê toàn bộ khách. Dùng chung PageHeader, MoneyText, AccountStatusTag, TransferStatusTag, ApiErrorPanel, EmptyState, LoadingState, ConfirmActionDialog và bộ hiển thị tài khoản masked.

## Responsive và accessibility

| Mốc kiểm tra | Customer/Auth | Staff |
|---|---|---|
| 360px | Một cột, không cuộn ngang toàn trang, OTP là bước đầy đủ | Menu thu gọn, bảng cuộn trong vùng bảng; không mất nút |
| 768px | Form giới hạn chiều rộng, lịch sử dạng card hoặc bảng phù hợp | Các bộ lọc xuống hàng |
| 1024px | Sidebar và nội dung chính | Mốc acceptance chức năng chính |
| 1440px | Giới hạn bề rộng nội dung để đọc dễ | Bảng/filter/detail rộng |

Mọi field có label; bàn phím tới được nút, lỗi và dialog; focus vào lỗi đầu tiên và trả focus sau đóng dialog. Trạng thái có chữ, không chỉ màu. Cho phép paste OTP, giữ số 0 đầu, không tự submit khi gõ đủ. Lỗi quan trọng hiển thị trong trang/form, không chỉ toast. Dialog có tên, focus management; loading không xóa dữ liệu đang đọc mà không có thông báo.

## Thứ tự và tích hợp

| Phase | Spec | Phụ thuộc BE | Kết quả cần đạt |
|---|---|---|---|
| 01 | [Foundation/API client](01-foundation-api-client.md) | BE 01–02, BE 07 | Layout, session/client, mock/typegen |
| 02 | [Auth/onboarding](02-auth-onboarding.md) | BE 03 | Register/login/PIN/recovery |
| 03 | [Dashboard/accounts](03-customer-dashboard-accounts.md) | BE 04 | Đọc profile/tài khoản |
| 04 | [Transfers](04-transfers.md) | BE 05 | PIN/OTP, idempotency và unknown outcome |
| 05 | [History/detail](05-history-transfer-detail.md) | BE 05–06 | Cursor và trạng thái |
| 06 | [Operator](06-operator-workspace.md) | BE 03–04 | Tạo/tra cứu/seed/block |
| 07 | [Auditor/risk](07-auditor-risk-workspace.md) | BE 06 | Bảng chỉ đọc theo quyền |
| 08 | [Quality/demo](08-integration-quality-demo.md) | BE 07–08 | E2E, responsive, integration evidence |

Phase 06 có thể song song với 03–05 sau foundation/auth. Luồng demo chính: đăng ký → login → PIN → Operator seed → transfer → history. Sau đó kiểm thử recovery, step-up, audit/risk và lỗi.

Mỗi nhóm API ghi tiến độ `mock-only → BE-ready → integrated → verified`; hiện tất cả là **planned**, chưa có mock/code. BE-ready cần endpoint chạy và contract test; verified cần browser test BE thật. Tài liệu BE không phải bằng chứng endpoint hoạt động.

## Điểm cần làm rõ và giới hạn contract

- Session sau reload: refresh chỉ trả accessToken/tokenType/expiresIn, không trả UserSummary; chưa có staff `/me`. Đang hỏi người dùng chốt đăng nhập lại sau reload hay thay đổi contract để bootstrap đầy đủ. Chặn phần bootstrap phụ thuộc quyết định này.
- Lần sai OTP transfer thứ 5: quyết định mới nhất chốt `409 STATE_CONFLICT`; OpenAPI có 409 nhưng description còn ghi chung wrong OTP 400. FE dùng quyết định đã chốt, ghi mismatch để BE sửa description; không tự đổi response.
- Replay transfer/seed thành công là `200` + `Idempotency-Replayed`; đoạn BE integration nói replay 409 là mâu thuẫn với endpoint. `409 IDEMPOTENCY_KEY_REUSED` là conflict, không xem là thành công.
- Account response chỉ có số masked; không tạo nút copy số đầy đủ, QR nhận tiền hoặc tự suy ra số. Demo lấy số người nhận từ fixture BE được cấp.
- Reload giữa mutation có thể làm mất key in-memory. Không lưu PIN/OTP/token để khôi phục; không hứa exactly-once qua reload khi chưa có thiết kế lưu intent được chốt. Yêu cầu tra lịch sử trước khi tạo giao dịch mới nếu kết quả chưa biết.

## Quy trình và bằng chứng

Mỗi phase: đối chiếu contract → chốt điểm còn mở → triển khai phạm vi được giao → test mock → tích hợp thật → ghi bằng chứng. Package manager/phiên bản chính xác được xác nhận khi có frontend repo; không tự đưa phiên bản mới vào baseline. Các test runner đề xuất: Vitest/Testing Library, MSW và Playwright; chưa cài dependency trong công việc docs.

Handover mỗi phase ghi file, API coverage, lệnh/kết quả thực, screenshot ở viewport mục tiêu, mismatch/blocker và trạng thái tích hợp. Không đánh dấu runtime pass từ kiểm tra Markdown. Bộ docs này không tự tạo commit/push.

## Kiểm chứng bộ tài liệu (2026-10-02)

Đã đối chiếu endpoint/schema với OpenAPI hiện tại, BE phase 07 và các quyết định mới nhất về OTP/transaction. Kiểm tra 9 tệp frontend: toàn bộ link tương đối resolve được, code fences cân bằng; `git diff --check` không báo lỗi. Chưa scaffold/frontend dependency/build hay test runtime. Điểm bootstrap sau reload đang chờ người dùng chốt; các phần thiết kế độc lập đã được viết đầy đủ. Chưa commit/push.
