# Xác minh OTP đăng ký trước bước hồ sơ

Ngày: 08/10/2026. Trạng thái: implemented; kiểm chứng bên dưới. Người dùng đã chọn bổ sung backend và chốt hợp đồng dưới đây trong chat.

## Mục tiêu/phạm vi

Prototype có màn xác minh OTP trước hồ sơ; backend cũ chỉ xác minh lúc tạo tài khoản. Bổ sung quyền đăng ký do server cấp để frontend chuyển bước đúng; giữ tương thích payload OTP cũ. Không sửa recovery, transfer hoặc phân quyền staff.

## Quyết định được duyệt

- POST `/api/v1/auth/register/verify-otp`, body phone/otp, public. OTP hợp lệ bị tiêu thụ; response registrationToken/expiresInSeconds=300, Cache-Control no-store.
- Token opaque, ngẫu nhiên 256 bit, gắn phone, hash SHA-256 lưu DB; một lần dùng. FE chỉ memory, không URL/storage/cache/log. Reload bắt đầu lại.
- POST register nhận đúng một trong otp cũ hoặc registrationToken mới. Token hết hạn/sai/đã dùng/khác phone trả 400 REGISTRATION_TOKEN_INVALID. Sai/hết hạn OTP trả 400 OTP_INVALID.
- Yêu cầu OTP mới vô hiệu quyền cũ; xác minh mới vô hiệu quyền cũ. Serialize send/verify/register-token trên cùng phone; tạo account và consume token trong cùng transaction. Conflict identity rollback token để người dùng sửa hồ sơ trong TTL còn lại.
- Rate limit verify dùng cùng giá trị limit/window với recovery verify (5/300s mặc định), nhưng bucket registration-verify riêng cho IP và phone; giới hạn attempts OTP hiện có vẫn được giữ.

## Nguồn/API/data

[Workflow](../backend-development-workflow.md), [OpenAPI](../../contracts/openapi.yaml), [brief](../design/digital-banking-codex-handoff/IMPLEMENTATION-BRIEF.md). Identity sở hữu controller/service/OTP/proof. Migration V7 tạo registration_verification_tokens; không xóa/sửa migration cũ hoặc dữ liệu giữ lại. Contract bổ sung endpoint và token alternative, không bỏ OTP legacy.

## Acceptance/test plan

- Verify sai không tạo proof/account; OTP attempts được commit kể cả sai. Verify đúng consume OTP và hash token; response no-store.
- Token không dùng được với phone khác, sau resend, sau TTL, sau consume; không tạo user/customer/account khi proof invalid.
- Consume proof + tạo user/customer/account atomic; lỗi duplicate/account creation rollback.
- Concurrency send/verify/register trên một phone được serialize; token chỉ tạo một account.
- MVC tests cho payload/shape/errors và legacy path; service tests cho branches; PostgreSQL disposable kiểm tra V7, one-use, expiry/invalidation/transaction rollback.
- OpenAPI validation, generated types drift, Maven build và tests. Không thao tác xóa database dùng chung.

## Triển khai/kiểm chứng

Đã triển khai service/repository/V7, controller/public route/rate limiter và OpenAPI; frontend đăng ký xác minh bằng endpoint thật trước hồ sơ. Chỉ lưu hash proof; giữ OTP legacy.

- Service tests: 6 PASS; MVC: 5 PASS, gồm no-store, validation, exactly-one proof và không fallback token lỗi sang OTP.
- PostgreSQL: 4 PASS, gồm phone-bound/one-use/hash-only, invalidation/expiry, rollback và hai request register đồng thời chỉ tạo một user/account.
- Full backend package:150 tests PASS; sau bổ sung concurrency, full suite cuối151 tests PASS (0 failure/error/skip).
- API smoke thực: OTP sai bị từ chối, verify đúng có TTL300, wrong-phone/used proof bị từ chối, resend vô hiệu proof; customer mới đăng nhập/PIN/seed/transfer/history thành công.
- UI component test xác nhận chỉ mở hồ sơ sau response verify và POST register gửi registrationToken thay OTP, không tự login. OpenAPI validation31 operations và generated-types drift PASS.

Dùng PostgreSQL16 disposable riêng, không sửa migration cũ hoặc dữ liệu dùng chung. Chi tiết môi trường/bằng chứng và giới hạn kiểm tra browser: [tiến độ frontend](frontend-mvp/continuation-status.md).