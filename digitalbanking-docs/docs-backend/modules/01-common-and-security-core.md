# Module 01: Common & Security Core (`package: com.banking.common`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-01`
> **Phiên bản:** 1.0
> **Ngày:** 07/11/2026
> **Trạng thái:** Hoàn thành
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-039, FR-040, FR-052, NFR-013..020), [[digitalbanking-docs/docs-common/04. Database design & analysis.md#9. Data Security and Lifecycle]], [[digitalbanking-docs/docs-common/06. System architecture.md#9. Security architecture]], [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2, §4)

---

## 1. Trách nhiệm & Ranh giới (Scope & Boundaries)

- **Vai trò:** Cung cấp các thành phần dùng chung xuyên suốt (cross-cutting) cho toàn bộ 10 module nghiệp vụ.
- **Bảng sở hữu:** Không sở hữu bảng nghiệp vụ nào.
- **Quy tắc phụ thuộc:**
  - `com.banking.common` **không được import bất kỳ package nào** thuộc `identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`.
  - Tất cả module nghiệp vụ được phép phụ thuộc vào `com.banking.common`.

### Cấu trúc Package nội bộ:
```text
com.banking.common/
├── config/          # JacksonConfig, ClockConfig, SecurityHeadersConfig, OpenApiConfig
├── correlation/     # CorrelationIdFilter, CorrelationContext, MdcTaskDecorator
├── crypto/          # AesGcmCryptoService, HmacBlindIndexService, SecretHasher (Argon2id)
├── error/           # BusinessException, SystemException, GlobalExceptionHandler (RFC 9457)
├── json/            # JcsCanonicalizer (RFC 8785 cho request_hash và audit canonical_payload)
├── masking/         # PiiMasker, SensitiveDataScanner
├── ratelimit/       # Bucket4jRateLimitFilter, RateLimitProperties
├── security/        # AuthPrincipal, SecurityContextUtils, CsrfValidationFilter
└── web/             # HealthController (/api/v1/health), PageResponse, SortWhitelistValidator
```

---

## 2. Hợp đồng API thuộc Module (`API-HLT-01`)

| API ID | Method & Path | Quyền truy cập | Response `200 OK` |
|---|---|---|---|
| `API-HLT-01` | `GET /api/v1/health` | `ADMIN`, `AUDITOR` | `{ "status": "UP", "components": { "database": "UP", "reconciliation": "UP" }, "timestamp": "...", "correlationId": "..." }` |

> [!NOTE]
> Endpoint `/actuator/health` của Spring Boot Actuator vẫn mở nội bộ cho Docker container healthcheck, còn `/api/v1/health` là endpoint nghiệp vụ theo hợp đồng OpenAPI (yêu cầu xác thực `ADMIN`/`AUDITOR` và trả `correlationId`).

---

## 3. Thiết kế Chi tiết các Thành phần Cốt lõi

### 3.1. Mã hóa Dữ liệu Nhạy cảm (`AesGcmCryptoService` & `HmacBlindIndexService`)

Theo `NFR-014`, `BRL-026` và `AD-12`, các cột hậu tố `*_enc` (`customer.full_name_enc`, `phone_enc`, `email_enc`, `account.account_no_enc`) phải được mã hóa ở tầng ứng dụng trước khi lưu xuống `BYTEA`:

1. **Thuật toán AES-256-GCM (`AesGcmCryptoService`):**
   - Khóa chính (`masterKey`): 256-bit (32 bytes), nạp từ biến môi trường `APP_AES_KEY` (Base64), kèm `keyVersion` (`short`, mặc định `1`).
   - IV (Nonce): 12 bytes sinh ngẫu nhiên từ `SecureRandom` cho **mỗi lần mã hóa** (tuyệt đối không tái sử dụng IV).
   - Tag xác thực (Authentication Tag): 128 bits (16 bytes).
   - **Định dạng mảng byte lưu trong cột `BYTEA`:**
     ```text
     [ 1 byte: version (0x01) ] || [ 12 bytes: IV ] || [ N bytes: Ciphertext + 16 bytes GCM Tag ]
     ```
   - Hỗ trợ xoay khóa (Key Rotation cơ bản): giữ `Map<Byte, SecretKey>` theo `version` để giải mã dữ liệu cũ và luôn mã hóa mới bằng `activeKeyVersion`.

2. **Blind Index HMAC-SHA256 (`HmacBlindIndexService`):**
   - Dùng cho các cột cần tìm kiếm chính xác / ràng buộc `UNIQUE` mà không lộ plaintext:
     - `account.account_no_hash`: `HMAC-SHA256(pepper, normalizeAccountNo(raw))`
     - `device.device_fingerprint_hash`: `HMAC-SHA256(pepper, normalizeFingerprint(raw))`
   - Đầu ra: chuỗi 64 ký tự hex lowercase (`CHAR(64)`).
   - Khóa `pepper` nạp từ biến môi trường `APP_HMAC_PEPPER` (tách biệt với khóa AES).

### 3.2. Băm Mật khẩu, Passcode & OTP (`SecretHasher`)

- **Password & Passcode (`app_user.password_hash`, `device.passcode_hash`):**
  - Sử dụng `Argon2PasswordEncoder` của Spring Security Crypto (cấu hình khuyến nghị: `saltLength = 16`, `hashLength = 32`, `parallelism = 1`, `memory = 19456` (19 MB), `iterations = 2`).
- **OTP (`auth_challenge.otp_hash`):**
  - Sinh bằng `SecureRandom` (6 chữ số `000000`–`999999`).
  - Băm bằng `HMAC-SHA256(pepper, challengeRef + ":" + otp)` hoặc `Argon2id` trước khi lưu vào `otp_hash`.
- **Session Token (`session.session_token_hash`):**
  - Sinh 32 bytes ngẫu nhiên từ `SecureRandom`, encode Base64URL không padding làm opaque token gửi cho client.
  - Lưu vào DB giá trị `SHA-256` hex lowercase (64 ký tự) của token.

### 3.3. Quy tắc Che dấu Dữ liệu Nhạy cảm (`PiiMasker`)

Mọi DTO trả ra API, mọi snapshot `before_state`/`after_state` của `audit_log`, mọi `payload_masked` của `notification` và mọi dòng log đều phải đi qua `PiiMasker` (`FR-039`, `FR-043`, `NFR-020`):

| Loại dữ liệu | Hàm trong `PiiMasker` | Quy tắc biến đổi | Ví dụ đầu vào → đầu ra |
|---|---|---|---|
| **Số tài khoản** | `maskAccountNo(last4)` | Luôn định dạng cố định `'**** **** ' + last4` | `"100000011234"` → `"**** **** 1234"` |
| **Số điện thoại** | `maskPhone(rawPhone)` | Giữ đúng 3 chữ số cuối, phía trước thay bằng `********` | `"0901234567"` → `"********567"` |
| **Email** | `maskEmail(rawEmail)` | Giữ ký tự đầu tiên của local-part + `***@` + domain | `"nguyenvana@gmail.com"` → `"n***@gmail.com"` |
| **Họ và tên** | `maskFullName(name, isOwner)` | Nếu `isOwner == true`: giữ nguyên.<br/>Nếu khác chủ / trong audit / log: giữ từ đầu + từ cuối, che các từ đệm hoặc ký tự giữa | `"Nguyễn Văn An"` → `"Nguyễn V*** An"` |
| **Địa chỉ IP** | `maskIp(ip)` | IPv4: ẩn octet cuối (`.xxx`); IPv6: ẩn 80 bits cuối | `"192.168.1.45"` → `"192.168.1.xxx"` |
| **User-Agent** | `sanitizeUserAgent(ua)` | Cắt tối đa 120 ký tự, chỉ giữ tên trình duyệt/OS cơ bản | `"Mozilla/5.0 ..."` |
| **Secret / Token / OTP** | *(Không bao giờ trả)* | Tuyệt đối không có mặt trong DTO hay Log | `"[REDACTED]"` |

### 3.4. Chuẩn hóa JSON theo RFC 8785 (`JcsCanonicalizer`)

Dùng chung cho 2 nghiệp vụ quan trọng:
1. Tính `request_hash` (`CHAR(64)`) cho `idempotency_record` và `auth_challenge` (`TRANSFER_STEP_UP`).
2. Tính `record_hash` (`CHAR(64)`) cho `audit_log`.

**Quy tắc thực thi trong `JcsCanonicalizer`:**
- Sắp xếp toàn bộ keys của JSON Object đệ quy theo thứ tự từ điển Unicode code units.
- Loại bỏ toàn bộ khoảng trắng không cần thiết giữa các token JSON.
- Tất cả các trường số tiền (`amount`, `availableBalance`), ID (`accountId`, `customerId`, `actor_user_id`) truyền vào phải ở dạng **`String`**, chỉ các biến đếm nguyên (`attemptCount`, `hash_version`) mới dùng số nguyên.
- Đầu ra chuỗi UTF-8 -> băm `MessageDigest.getInstance("SHA-256")` -> chuyển thành 64 ký tự hex lowercase.

### 3.5. Xử lý Lỗi Tập trung (`GlobalExceptionHandler`)

`@RestControllerAdvice` bắt toàn bộ exception và chuyển thành đối tượng `ProblemDetail` chuẩn RFC 9457 (xem bảng mã lỗi tại [[digitalbanking-docs/docs-backend/api/02-error-catalog-and-mapping.md]]):

- `MethodArgumentNotValidException` / `ConstraintViolationException` / `MissingRequestHeaderException` → `400 VALIDATION_ERROR` (kèm mảng `errors[{field, code, message}]`).
- `HttpMessageNotReadableException` → `400 MALFORMED_REQUEST`.
- `HttpMediaTypeNotSupportedException` → `415 UNSUPPORTED_MEDIA_TYPE`.
- `MaxUploadSizeExceededException` → `413 REQUEST_TOO_LARGE`.
- `AccessDeniedException` → `403 AUTH_FORBIDDEN` (đồng thời bắn sự kiện ghi audit `SECURITY_ACCESS_DENIED`).
- `BusinessException` → ánh xạ theo `ErrorCode` (`status`, `code`, `type`, `detail`, `retryable`, cùng các trường mở rộng `challenge`, `reference`, `lockedUntil` nếu có).
- `AuditWriteException` → `500 AUDIT_WRITE_FAILED` (`retryable = true`).
- `TransientDataAccessException` / `CannotAcquireLockException` / `QueryTimeoutException` → `503 SERVICE_UNAVAILABLE` (`retryable = true`).
- `Exception` (fallback) → log `ERROR` kèm `correlationId`, trả `500 INTERNAL_ERROR` với thông điệp chung an toàn.

---

## 4. Checklist Kiểm thử Module `common`

- [ ] **Unit Test `AesGcmCryptoServiceTest`:** Mã hóa cùng một chuỗi 2 lần phải ra 2 mảng byte khác nhau (do IV ngẫu nhiên), giải mã cả 2 đều khớp bản gốc; sửa 1 bit bất kỳ trong ciphertext phải ném lỗi xác thực GCM tag.
- [ ] **Unit Test `HmacBlindIndexServiceTest`:** Cùng số tài khoản `" 100000011234 "` sau normalize cho ra đúng cùng 1 chuỗi 64 hex.
- [ ] **Unit Test `PiiMaskerTest`:** Kiểm tra đầy đủ edge cases cho STK, SĐT, Email, Họ tên, IP; đảm bảo không bao giờ lộ chuỗi gốc.
- [ ] **Unit Test `JcsCanonicalizerTest`:** Hai Map có thứ tự thêm key khác nhau phải sinh ra cùng một chuỗi JSON canonical và cùng mã băm SHA-256 (khớp test vector ở Appendix B của `design/01`).
- [ ] **WebMvc Test `CorrelationAndErrorAdviceTest`:**
  - Request không gửi `X-Correlation-ID` -> response có header `X-Correlation-ID` hợp lệ và khớp với `body.correlationId`.
  - Request gửi `X-Correlation-ID: "bad\nheader"` -> bị chặn ngay với `400 VALIDATION_ERROR`.
  - Mọi lỗi đều trả `Content-Type: application/problem+json`.
