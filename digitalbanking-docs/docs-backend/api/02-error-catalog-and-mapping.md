# API 02. Error Catalog & RFC 9457 Problem Details Mapping

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-API-02`
> **Phiên bản:** 1.0
> **Ngày:** 07/11/2026
> **Trạng thái:** Hoàn thành
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/07. API contracts.md#4. Mô hình lỗi]], [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md#Appendix C. Mã lỗi và HTTP mapping]]

---

## 1. Cấu trúc Chuẩn RFC 9457 Problem Details

Mọi phản hồi lỗi từ Backend (kể cả lỗi ở Filter `401`/`403`/`429` và lỗi ở `@RestControllerAdvice`) bắt buộc trả về `Content-Type: application/problem+json; charset=utf-8` và header `X-Correlation-ID`.

```json
{
  "type": "https://api.demo.local/problems/insufficient-balance",
  "title": "Insufficient balance",
  "status": 422,
  "detail": "Số dư khả dụng không đủ cho giao dịch.",
  "instance": "/api/v1/transfers",
  "code": "INSUFFICIENT_BALANCE",
  "correlationId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "timestamp": "2026-11-03T10:15:30Z",
  "retryable": false
}
```

### Các trường mở rộng theo ngữ cảnh lỗi:
| Trường | Kiểu | Khi nào xuất hiện |
|---|---|---|
| `errors` | `List<FieldErrorItem>` | Khi `code == "VALIDATION_ERROR"` (`400`); mỗi phần tử gồm `{ "field", "code", "message" }` |
| `challenge` | `ChallengeDto` | Khi `code == "STEP_UP_REQUIRED"` (`428`) |
| `reference` | `String` | Khi `transfer` đã được tạo trong `T1` nhưng kết thúc `FAILED` (`422`) hoặc `500`/`503` sau `T1` |
| `transferStatus` | `String` | `"PENDING"` hoặc `"FAILED"` đi kèm với `reference` |
| `failureCode` | `String` | Mã lý do thất bại của `transfer` (ví dụ `"INSUFFICIENT_BALANCE"`, `"FRAUD_BLOCKED"`) |
| `lockedUntil` | `Instant` | Khi `code == "AUTH_ACCOUNT_LOCKED"` (`423`) |

> [!CAUTION]
> **Quy tắc Bảo mật Lỗi:** Tuyệt đối không trả stack trace, tên class exception Java, câu lệnh SQL, tên bảng/cột DB, hay PII/số tài khoản đầy đủ trong `detail` hoặc `errors`.

---

## 2. Bảng Tra cứu Toàn diện Mã Lỗi (Master Error Code Catalog)

### 2.1. Nhóm Xác thực, Phiên & Phân quyền (`identity`, `common`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) | Ghi chú xử lý Backend |
|---|---|---|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | `401` | `false` | `auth-invalid-credentials` | Thông tin đăng nhập hoặc mã xác thực không chính xác. | Tăng `failed_attempt_count`, ghi `auth_attempt` + audit `AUTH_LOGIN_FAILED` (`REQUIRES_NEW`) |
| `AUTH_SESSION_EXPIRED` | `401` | `true` | `auth-session-expired` | Phiên đăng nhập đã hết hạn hoặc không hợp lệ. Vui lòng đăng nhập lại. | Trả kèm header `WWW-Authenticate: Bearer realm="digital-banking"` |
| `AUTH_OTP_INVALID` | `401` | `true` | `auth-otp-invalid` | Mã OTP không chính xác. | Tăng `attempt_count` của `auth_challenge` |
| `AUTH_FORBIDDEN` | `403` | `false` | `auth-forbidden` | Bạn không có quyền thực hiện thao tác hoặc truy cập dữ liệu này. | Dùng cho cả thiếu Role/Permission, sai Ownership, sai/thiếu `X-CSRF-Token`. Ghi audit `SECURITY_ACCESS_DENIED` |
| `AUTH_DEVICE_NOT_TRUSTED` | `403` | `false` | `auth-device-not-trusted` | Thiết bị chưa được xác nhận tin cậy cho phương thức đăng nhập này. | Khi gọi Passcode/FIDO từ thiết bị `trusted = false` |
| `AUTH_CHALLENGE_NOT_FOUND` | `404` | `false` | `auth-challenge-not-found` | Không tìm thấy yêu cầu xác thực hoặc yêu cầu không thuộc về bạn. | — |
| `STEP_UP_CHALLENGE_MISMATCH` | `409` | `false` | `step-up-challenge-mismatch` | Mã xác thực bổ sung không khớp với thông tin giao dịch hiện tại. | Khi `request_hash`, `idempotency_key`, `purpose` hoặc `method` không khớp |
| `AUTH_CHALLENGE_EXPIRED` | `410` | `true` | `auth-challenge-expired` | Yêu cầu xác thực đã hết hiệu lực. | Cập nhật `auth_challenge.status = 'EXPIRED'` |
| `AUTH_OTP_EXPIRED` | `410` | `true` | `auth-otp-expired` | Mã OTP đã hết hạn sử dụng. | — |
| `AUTH_ACCOUNT_LOCKED` | `423` | `false` | `auth-account-locked` | Tài khoản đang bị khóa tạm thời do nhập sai quá số lần quy định. | Trả kèm trường `lockedUntil` trong body |
| `AUTH_CHALLENGE_LOCKED` | `423` | `false` | `auth-challenge-locked` | Yêu cầu xác thực đã bị khóa do nhập sai quá 5 lần. | Cập nhật `auth_challenge.status = 'LOCKED'` |
| `STEP_UP_REQUIRED` | `428` | `true` | `step-up-required` | Giao dịch cần xác thực bổ sung trước khi xử lý. | Tạo `auth_challenge` trong `T0`, trả kèm object `challenge` |
| `OTP_DISPATCH_FAILED` | `503` | `true` | `otp-dispatch-failed` | Không thể gửi mã OTP lúc này. Vui lòng thử lại sau. | Không tạo session hoặc không tiến hành chuyển tiền |

### 2.2. Nhóm Request, Validation & Rate Limit (`common`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) |
|---|---|---|---|---|
| `VALIDATION_ERROR` | `400` | `false` | `validation-error` | Dữ liệu yêu cầu không hợp lệ. |
| `MALFORMED_REQUEST` | `400` | `false` | `malformed-request` | Định dạng JSON hoặc dữ liệu đầu vào không thể đọc được. |
| `NOT_FOUND` | `404` | `false` | `not-found` | Đường dẫn hoặc tài nguyên yêu cầu không tồn tại. |
| `METHOD_NOT_ALLOWED` | `405` | `false` | `method-not-allowed` | Phương thức HTTP không được hỗ trợ cho đường dẫn này. |
| `REQUEST_TOO_LARGE` | `413` | `false` | `request-too-large` | Kích thước dữ liệu gửi lên vượt quá giới hạn cho phép (64 KB). |
| `UNSUPPORTED_MEDIA_TYPE` | `415` | `false` | `unsupported-media-type` | Định dạng Content-Type không được hỗ trợ (yêu cầu application/json). |
| `RATE_LIMITED` | `429` | `true` | `rate-limited` | Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau. *(Kèm header `Retry-After`)* |

### 2.3. Nhóm Nghiệp vụ Chuyển tiền, Tài khoản, Hạn mức & Gian lận (`account`, `transfer`, `limit`, `fraud`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) | Hành vi Transaction `T1`/`T2` |
|---|---|---|---|---|---|
| `ACCOUNT_NOT_FOUND` | `404` | `false` | `account-not-found` | Không tìm thấy tài khoản nguồn hoặc tài khoản thụ hưởng. | Chặn trước `T1` -> không tạo `transfer` |
| `CUSTOMER_NOT_FOUND` | `404` | `false` | `customer-not-found` | Không tìm thấy thông tin khách hàng. | — |
| `TRANSFER_NOT_FOUND` | `404` | `false` | `transfer-not-found` | Không tìm thấy giao dịch với mã tham chiếu này. | — |
| `FRAUD_FLAG_NOT_FOUND` | `404` | `false` | `fraud-flag-not-found` | Không tìm thấy cảnh báo gian lận. | — |
| `ACCOUNT_INACTIVE` | `409` | `false` | `account-inactive` | Tài khoản nguồn hoặc tài khoản đích hiện không hoạt động. | Nếu phát hiện trước `T1` -> `409`; nếu phát hiện sau khi khóa ở `T2` -> commit `FAILED(ACCOUNT_INACTIVE)` |
| `FRAUD_FLAG_ALREADY_CLOSED` | `409` | `false` | `fraud-flag-already-closed` | Cảnh báo gian lận này đã được xử lý và đóng trước đó. | — |
| `INVALID_STATE_TRANSITION` | `409` | `false` | `invalid-state-transition` | Không thể chuyển trạng thái do đối tượng đã ở trạng thái kết thúc. | — |
| `INSUFFICIENT_BALANCE` | `422` | `false` | `insufficient-balance` | Số dư khả dụng không đủ để thực hiện giao dịch. | Trong `T2`: commit `transfer = FAILED`, `idempotency = FAILED(422)`, ghi audit `TRANSFER_FAILED`, không ghi ledger |
| `LIMIT_EXCEEDED` | `422` | `false` | `limit-exceeded` | Giao dịch vượt quá hạn mức mỗi lần hoặc hạn mức trong ngày. | Trong `T2`: commit `transfer = FAILED`, `idempotency = FAILED(422)`, ghi audit `TRANSFER_FAILED` |
| `LIMIT_OUT_OF_POLICY` | `422` | `false` | `limit-out-of-policy` | Giá trị hạn mức yêu cầu nằm ngoài phạm vi chính sách cho phép. | Khi Maker tạo yêu cầu đổi hạn mức không hợp lệ |
| `FRAUD_BLOCKED` | `422` | `false` | `fraud-blocked` | Giao dịch bị từ chối tự động theo quy tắc kiểm soát rủi ro. | Trong `T2`: ghi `fraud_flag (BLOCKED)`, commit `transfer = FAILED`, không ghi ledger |
| `TRANSFER_FAILED` | `422` | `false` | `transfer-failed` | Giao dịch không thể hoàn tất do lỗi nghiệp vụ. | Kèm `reference`, `transferStatus = "FAILED"`, `failureCode` |

### 2.4. Nhóm Idempotency (`transfer`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) | Ghi chú xử lý Backend |
|---|---|---|---|---|---|
| `IDEMPOTENCY_CONFLICT` | `409` | `false` | `idempotency-conflict` | Khóa Idempotency-Key đã được sử dụng cho một yêu cầu có nội dung khác. | Ghi audit `SECURITY_IDEMPOTENCY_CONFLICT` (`REQUIRES_NEW`) |
| `IDEMPOTENCY_IN_PROGRESS` | `409` | `true` | `idempotency-in-progress` | Yêu cầu với cùng Idempotency-Key đang được xử lý. Vui lòng thử lại sau. | Trả kèm header `Retry-After: 2` |

### 2.5. Nhóm Quản trị, Maker-Checker & Audit (`admin`, `audit`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) |
|---|---|---|---|---|
| `AUDIT_NOT_FOUND` | `404` | `false` | `audit-not-found` | Không tìm thấy bản ghi kiểm toán. |
| `APPROVAL_NOT_FOUND` | `404` | `false` | `approval-not-found` | Không tìm thấy yêu cầu phê duyệt. |
| `ROLE_NOT_FOUND` | `404` | `false` | `role-not-found` | Vai trò hoặc quyền không tồn tại hoặc đã ngừng hoạt động. |
| `USER_NOT_FOUND` | `404` | `false` | `user-not-found` | Không tìm thấy người dùng. |
| `APPROVAL_ALREADY_DECIDED` | `409` | `false` | `approval-already-decided` | Yêu cầu phê duyệt này đã được xử lý trước đó. |
| `ROLE_CONFLICT` | `409` | `false` | `role-conflict` | Người phê duyệt không được trùng với người tạo yêu cầu hoặc xung đột quyền. |
| `LEGAL_HOLD_ACTIVE` | `409` | `false` | `legal-hold-active` | Dữ liệu đang thuộc diện giữ pháp lý (Legal Hold), không thể xóa hoặc lưu trữ. |
| `EXPORT_TOO_LARGE` | `413` | `false` | `export-too-large` | Kết quả xuất dữ liệu vượt quá giới hạn dòng cho phép. Vui lòng thu hẹp bộ lọc. |

### 2.6. Nhóm Lỗi Hệ thống & Hạ tầng (`common`, `audit`, `transfer`)

| Error Code (`code`) | HTTP | `retryable` | `type` slug (`/problems/...`) | Thông điệp tiếng Việt chuẩn (`detail`) | Hành vi Transaction |
|---|---|---|---|---|---|
| `AUDIT_WRITE_FAILED` | `500` | `true` | `audit-write-failed` | Không thể ghi nhận bằng chứng kiểm toán. Giao dịch đang ở trạng thái chờ đối soát. | Rollback toàn bộ `T2`, giữ `transfer = PENDING` (nếu `T1` đã commit), trả kèm `reference` |
| `INTERNAL_ERROR` | `500` | `true` | `internal-error` | Đã xảy ra lỗi hệ thống nội bộ. Vui lòng thử lại hoặc tra cứu theo mã tham chiếu. | Rollback `T2`, giữ `transfer = PENDING` |
| `SERVICE_UNAVAILABLE` | `503` | `true` | `service-unavailable` | Hệ thống tạm thời quá tải hoặc gián đoạn kết nối. Vui lòng thử lại sau. | Khi hết retry deadlock / lock timeout / DB pool timeout |
| `GATEWAY_TIMEOUT` | `504` | `true` | `gateway-timeout` | Hết thời gian chờ phản hồi từ dịch vụ tích hợp. | — |
