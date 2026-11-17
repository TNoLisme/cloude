# OpenAPI Contract

> **Dự án:** Digital Banking MVP
> **Tệp:** `digital-banking-v1.yaml`
> **Phiên bản:** 1.0.1
> **Trạng thái:** Auth đồng bộ `07` + DEC-ID-008/010; không đồng nghĩa mọi operation đã có runtime
> **Cập nhật:** 17/11/2026

## 1. Mục đích

Thư mục này chứa hợp đồng API **máy đọc** của Digital Banking MVP, dùng cho validation, sinh client và kiểm thử hợp đồng.

- Tệp chính: [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]
- Nguồn ngữ nghĩa: [[digitalbanking-docs/docs-common/07. API contracts.md]]

## 2. Quan hệ với `07. API contracts`

| Nội dung | Nguồn |
|---|---|
| Quy tắc nghiệp vụ, error semantics, idempotency, step-up | `07. API contracts` |
| Schema, endpoint, status code, ví dụ máy đọc | `digital-banking-v1.yaml` |

Khi hai bên mâu thuẫn, **`07. API contracts` là nguồn ngữ nghĩa**; cập nhật `07` trước, sau đó đồng bộ YAML.

## 3. Nội dung chính

- OpenAPI 3.1.0.
- Server local và demo.
- Nhóm endpoint: Auth, Customer, Account, Transfer, Transaction, Limit, Audit, Fraud, Role, Approval, Health.
- Lỗi theo RFC 9457 Problem Details.
- Tiền và ID dạng chuỗi; thời gian ISO 8601 UTC.

### Auth — BE-ID-IMP-12

Một hợp đồng canonical duy nhất ở YAML này; không tạo spec Identity riêng. Base path là `/api/v1`.

| OperationId | Method/path | Security | Runtime/dependency |
|---|---|---|---|
| `login` | `POST /auth/login` | Public | Password đúng luôn 202 OTP, không session; metadata không bypass MFA (DEC-ID-008). Nhánh 200 được giữ nhưng chưa kích hoạt. |
| `loginPasscode` | `POST /auth/passcode` | Public | Giữ advertised 200; positive flow BLOCKED: thiếu enrollment/device-proof/expiry được duyệt. Request hợp lệ fail-closed 403 `AUTH_DEVICE_NOT_TRUSTED` (DEC-ID-009). |
| `loginFido` | `POST /auth/fido/verify` | Public | Begin/finish cùng route; UV required, RP/origin/signature/nonce/counter theo DEC-ID-010. Credential phải provision bên ngoài; enrollment/Audit thật còn dependency. |
| `verifyOtp` | `POST /auth/otp/verify` | Public | Challenge + OTP; chỉ SENT; session + consume atomic, không tự trust. Notification/Audit production còn dependency. |
| `verifyStepUp` | `POST /auth/step-up/verify` | Session cookie hoặc bearer | Chỉ challenge thuộc user và purpose step-up/sensitive. Schema giữ ba method; runtime chỉ OTP, method khác không tạo success. |
| `logout` | `POST /auth/logout` | Session cookie hoặc bearer | Không body; 204 không body, xóa cookie; token hết hiệu lực có thể 401. |
| `getSession` | `GET /auth/session` | Session cookie hoặc bearer | Trả authorities/session và rotate CSRF; không trả opaque session token. |
| `resendChallenge` | `POST /auth/challenges/{challengeId}/resend` | Public cho login; authenticated owner cho step-up/sensitive | **Không có runtime route**. Giữ advertised 202; BLOCKED DEC-ID-006/MOD-OI-07 đến khi duyệt counter/cooldown/generation/fencing schema + protocol. |

- Cookie-authenticated POST bắt buộc CSRF; bearer-only miễn CSRF. `security: []` cho bốn login/proof route; resend dùng optional auth và `x-security-by-purpose` để thể hiện điều kiện không biểu diễn đủ bằng OpenAPI security thuần.
- `x-error-codes` ánh xạ status → catalog `07` cho từng operation; lỗi chung dùng `Problem`/`ValidationErrorItem`, correlation header, `WWW-Authenticate` với 401, `Retry-After` với 429. `lockedUntil` chỉ áp dụng khóa tài khoản.
- Request schema giữ UUID chuẩn, giới hạn credential, nested Base64URL assertion và conditional proof. FIDO begin cho phép cặp challenge/assertion vắng mặt hoặc null; finish bắt buộc cả hai. Unknown fields bị từ chối; proof được chọn bởi `method` là bắt buộc, known proof không được chọn không thay đổi method.
- Các success schema `AuthResponse`, `ChallengeRequiredResponse`, `StepUpVerifyResponse` không chứa password/OTP/passcode/opaque session token. FIDO options luôn `userVerification: required`, không dùng ví dụ `preferred` đã bị DEC-ID-010 thay thế.
- Fallback FIDO bắt đầu lại password + OTP, không chuyển challenge hoặc assurance. Không biến advertised contract thành fake success để đóng blocker.
- `IdentityOpenApiContractTest` đọc YAML bằng SnakeYAML và đối chiếu request constraints với DTO/Jakarta validation, response serialization và conditional schemas; không snapshot source/wording. Chạy từ `digitalbanking-backend`: `./gradlew test --tests '*IdentityOpenApiContractTest'` (verification do integration owner thực hiện).

## 4. Quy trình thay đổi

1. Cập nhật mô tả ngữ nghĩa trong `07. API contracts`.
2. Cập nhật `digital-banking-v1.yaml` tương ứng.
3. Tăng `info.version` theo quy ước versioning của dự án.
4. Chạy validation/lint OpenAPI và kiểm thử hợp đồng.
5. Cập nhật traceability trong `07`, README này và README gốc nếu trạng thái thay đổi.

## 5. Quy ước

- Không đưa logic nội bộ hoặc thông tin nhạy cảm vào spec.
- Mọi thay đổi breaking phải được ghi rõ và có kế hoạch migration cho client.
- Ví dụ trong spec phải dùng dữ liệu tổng hợp, không dùng PII thật.
- Giữ `operationId`, tag và schema ổn định để sinh client không bị phá vỡ.

## 6. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Hợp đồng ngữ nghĩa | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Yêu cầu hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Kiến trúc | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Tài liệu backend | [[digitalbanking-docs/docs-backend/README.md]] |
| Tài liệu frontend | [[digitalbanking-docs/docs-frontend/README.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |
