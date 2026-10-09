# Lookup Operator — response không khớp contract

Trạng thái: complete. Ngày 09/10/2026, người dùng duyệt: “sửa giúp tôi theo đề xuất đó luôn”. Phạm vi được duyệt là sửa DTO/mapping response lookup, regression và kiểm thử lại UI; không đổi nghiệp vụ hoặc contract.

Nguồn hợp đồng: [OpenAPI](../../contracts/openapi.yaml), operation `operatorLookupCustomer`, schemas `OperatorCustomerView` và `Account`; [API/team contract](../baseline/api-and-team-contract.md); [quality/security](../baseline/quality-security-and-cloud.md). Module sở hữu: identity/web. Không có migration hoặc thay đổi persistence, quyền, audit, idempotency.

## Bằng chứng

Giao diện gọi GET `/api/v1/operator/customers?phone=...` với phiên OPERATOR trên database QA riêng. API trả200 nhưng frontend báo INVALID_RESPONSE. Controller `OnboardingController.operatorLookup` trả thẳng `OnboardingService.OperatorCustomerView`; accounts là `IdentityJdbcRepository.AccountRecord` có `accountNumber` đầy đủ. OpenAPI `OperatorCustomerView.accounts` yêu cầu DTO Account với `accountNumberMasked`. Giao diện không chuyển raw response này thành dữ liệu giả hoặc nới validation.

API smoke trước khi sửa chứng minh lookup/seed ở service/API hoạt động nhưng chỉ kiểm tra accountId. Lỗi response lúc đó chặn acceptance tra cứu/nạp/khóa trong UI; kiểm chứng sau sửa ghi ở cuối tài liệu.

## Quyết định đã duyệt và triển khai

Controller trả một DTO OperatorCustomerResponse gồm customerId/fullName/phone/email/isPinSet/createdAt/accounts. Map accounts bằng helper `account(AccountRecord)` đã dùng cho registration, lấy `maskedNumber()`; giữ accountId/type/status/balance/currency/openedAt. Không trả thuộc tính `accountNumber`.

Không đổi endpoint, query, quyền OPERATOR/ADMIN, logic lookup exact, status codes, OpenAPI hoặc migration. Metadata customer vẫn theo contract hiện có. Không sửa frontend để chấp nhận response sai contract.

## Tiêu chí acceptance và kế hoạch kiểm chứng

- MVC response có `accountNumberMasked`, không có `accountNumber` và không chứa chuỗi số đầy đủ.
- API smoke kiểm tra tất cả accounts theo schema và tính che số, giữ các kiểm tra quyền403.
- Build/test backend, restart đúng helper QA; browser Operator lookup → seed dialog → nạp có xác nhận → refresh số dư; khóa/mở khóa có reason/confirm; desktop1440 và tablet768.
- Cập nhật [tiến độ frontend](frontend-mvp/continuation-status.md). Không dùng/reset database giữ lại.

## Tệp thay đổi và kết quả thực tế

- `be/src/main/java/com/bank/simulator/identity/web/OnboardingController.java`: `operatorLookup` trả `OperatorCustomerResponse`; map mọi account qua helper `account(AccountRecord)` hiện có. Service lookup, role/audit, query, status/error và persistence giữ nguyên.
- `be/src/test/java/com/bank/simulator/identity/web/OnboardingControllerMvcTest.java`: thêm regression nhiều account ACTIVE/BLOCKED, metadata/tiền/thời gian giữ nguyên, không có trường hoặc chuỗi số đầy đủ; danh sách rỗng qua email và ADMIN; giữ lỗi403. Test cũ mock null/200 được sửa để kiểm chứng404 `CUSTOMER_NOT_FOUND` từ service. Standalone MVC serializer cấu hình ISO giống `spring.jackson.serialization.write-dates-as-timestamps: false` ở runtime.
- `frontend/scripts/api-smoke.mjs`: kiểm tra lookup phone/email, mọi account đủ bảy field DTO, UUID, số che, trạng thái, balance string và UTC timestamp; không có `accountNumber`; CUSTOMER/AUDITOR không được lookup. Không sửa frontend để nới validation.
- Spec này và tài liệu tiến độ; ảnh QA trong `frontend-mvp/evidence/`. Không sửa OpenAPI, schema/migration hoặc tài liệu thiết kế của người dùng. Không stage/commit/push.

Kết quả 09/10/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| `mvn -o -Dtest=OnboardingControllerMvcTest test` | PASS14 tests. Lần đầu trong sandbox kẹt Mockito attach, dừng đúng tiến trình test; lần chạy ngoài sandbox phát hiện serializer standalone xuất epoch. Chỉ sửa cấu hình fixture test để khớp ISO runtime, rồi PASS |
| `mvn -o package` với JWT_SECRET ngẫu nhiên đủ dài | PASS154 tests,0 failure/error/skip; đóng gói executable JAR thành công |
| `npm run api:smoke` | PASS12 scenarios trên PostgreSQL QA riêng, gồm assertions response mới và quyền403 |
| `npm run format:check`, `git diff --check` | PASS; mã frontend sản phẩm và contract không đổi trong scope này nên không lặp lại build frontend đã PASS |
| Browser desktop1440 và tablet768 | Lookup trả DTO hợp lệ và số che; nạp có hai bước/confirmation, số dư tải lại từ API; khóa/mở khóa với reason/confirmation, reason rỗng bị chặn; trạng thái ACTIVE/BLOCKED phản ánh server, BLOCKED tắt nút nạp |
| Browser mobile360 | Không tràn ngang: page scrollWidth345≤360, modal360/360; xác nhận nạp hiển thị số che/số tiền, hủy không tạo nạp; drawer điều hướng nhân viên hoạt động |
| Đối chiếu PostgreSQL chỉ đọc | Kết thúc ACTIVE, balance170019342; đúng hai seed UI (12345 desktop +1000 tablet), zero seed cho reference đã hủy trên mobile |

Backend chỉ được restart sau xác minh PID/executable của helper QA; database container `cloude-ui-qa-20261008` được kiểm tra label và vẫn chỉ loopback35437, không volume. Testcontainers dùng database test riêng. Không reset database giữ lại. Browser từng gặp429 sau API smoke dùng hết cửa sổ login; UI hiển thị Retry-After, chờ hết rồi đăng nhập thành công, không thay rate-limit. Viewport đã trả mặc định sau kiểm tra.

Bằng chứng: [kết quả desktop](frontend-mvp/evidence/operator-final-desktop.jpg), [lookup1440](frontend-mvp/evidence/operator-lookup-1440.jpg), [xác nhận nạp1440](frontend-mvp/evidence/operator-seed-confirm-1440.jpg), [BLOCKED1440](frontend-mvp/evidence/operator-blocked-1440.jpg), [lookup768](frontend-mvp/evidence/operator-lookup-768.jpg), [xác nhận nạp768](frontend-mvp/evidence/operator-seed-confirm-768.jpg), [lookup360](frontend-mvp/evidence/operator-lookup-360.jpg), [xác nhận mobile](frontend-mvp/evidence/operator-seed-confirm-360.jpg). Chỉ fixture giả; không có OTP/token/password trong ảnh.

Acceptance scope này đã đạt; không còn blocker lookup Operator. Các giới hạn chung (bundle lớn, ADMIN browser E2E, delivery SMS/email bên ngoài, audit performance/accessibility đầy đủ) được giữ trong tài liệu tiến độ.
