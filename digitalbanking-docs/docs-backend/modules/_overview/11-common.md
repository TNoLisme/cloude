# Module 11: `common` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-11`
> **Module:** `com.simulatedbank.banking.common`
> **Owner chính:** **Phương** (Phối hợp: Vũ, Đạt, Thịnh — cung cấp hạ tầng dùng chung cho cả 10 module nghiệp vụ)
> **Mốc tập trung:** **Tuần 2 – Tuần 3 (W2–W3)** và duy trì xuyên suốt theo `11. Resource & Staffing Plan.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (NFR-013..020, NFR-025), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.1–6.3, §9, §12), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2, §3), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§4.6, §7), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Phụ lục C), [[digitalbanking-docs/docs-common/design/04. security.md]] (§6–§8)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `common` chứa toàn bộ các thành phần kỹ thuật xuyên suốt (**Cross-Cutting Concerns**) của hệ thống Backend. Module này **không sở hữu bảng dữ liệu nghiệp vụ nào** và **tuyệt đối không phụ thuộc ngược vào bất kỳ module nghiệp vụ nào** (`06. System architecture.md` §6.3), giúp cả 10 module nghiệp vụ (`identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`) dùng chung một chuẩn thống nhất về truy vết, bảo mật mật mã, định dạng phản hồi và xử lý lỗi.

---

## 2. Cấu trúc Package Chuẩn của Module `common`

```text
com.simulatedbank.banking.common/
├── config/
│   ├── AppProperties.java                    // @ConfigurationProperties(prefix = "app")
│   ├── TimeConfig.java                       // Bean Clock (UTC) & ZoneId (Asia/Ho_Chi_Minh)
│   └── SecurityFilterChainConfig.java        // Cấu hình thứ tự Filter, CORS, Security Headers
├── context/
│   ├── CorrelationAndRequestIdFilter.java    // Sinh/kiểm tra X-Correlation-ID, X-Request-ID & gắn MDC
│   ├── RequestContext.java                   // Record giữ correlationId, requestId, clientIp, userAgent
│   └── RequestContextHolder.java             // ThreadLocal truy xuất ngữ cảnh request hiện tại
├── crypto/
│   ├── PiiCryptoService.java                 // Mã hóa/giải mã AES-256-GCM (IV 12B || Ciphertext || Tag 16B)
│   ├── HmacService.java                      // HMAC-SHA256 cho blind index & SHA-256 cho token/hash chain
│   ├── JcsCanonicalizer.java                 // Chuẩn hóa JSON theo RFC 8785 (JCS) cho Idempotency & Audit
│   └── PiiMaskingUtils.java                  // Che dấu số tài khoản (******1234), CCCD (********1234), IP
└── error/
    ├── ErrorCode.java                        // Enum danh mục mã lỗi chuẩn + HTTP Status + cờ retryable
    ├── BankingBusinessException.java         // Base exception cho các lỗi nghiệp vụ
    ├── ApiResponse.java                      // Envelope thành công chuẩn (data + meta)
    ├── ProblemResponse.java                  // Envelope lỗi chuẩn theo 07. API contracts.md
    └── GlobalExceptionHandler.java           // @RestControllerAdvice ánh xạ exception & SQLSTATE
```

---

## 3. Đặc tả Các Thành phần Cốt lõi Cần Cung cấp

### 3.1. Bộ lọc Truy vết `CorrelationAndRequestIdFilter` (`design/01` §4.6)
- Chạy ở mức ưu tiên cao nhất (`Ordered.HIGHEST_PRECEDENCE`) trước cả Spring Security чтобы mọi lỗi `401`/`403` đều có mã truy vết.
- Kiểm tra header `X-Correlation-ID` từ client:
  - Nếu có và khớp biểu thức chính quy `^[A-Za-z0-9._:-]{1,64}$` $\rightarrow$ giữ nguyên.
  - Nếu không gửi $\rightarrow$ tự sinh UUIDv4 mới.
  - Nếu gửi sai định dạng (chứa ký tự xuống dòng hoặc vượt 64 ký tự) $\rightarrow$ từ chối ngay với `400 VALIDATION_ERROR` (chống Log Injection).
- Luôn sinh mới `requestId` (UUIDv4) cho mỗi HTTP request, đặt `correlationId` và `requestId` vào SLF4J `MDC`, đính kèm header `X-Correlation-ID` vào mọi HTTP response, và dọn sạch `MDC` trong khối `finally`.

### 3.2. Dịch vụ Mật mã & Chuẩn hóa JSON (`design/01` §7, `design/04` §6)
1. **`PiiCryptoService` (`AES-256-GCM`):**
   - Nạp khóa `256-bit` (`32 bytes`) từ biến môi trường `APP_PII_AES_KEY_BASE64` (kiểm tra **Fail-fast** lúc khởi động).
   - Mỗi lần gọi `encrypt(plaintext)` sinh mới hoàn toàn `12 bytes` IV từ `SecureRandom` và trả về mảng byte `IV (12B) || Ciphertext || AuthTag (16B)` để lưu vào cột `BYTEA` (`account_no_enc`, `national_id_enc`).
2. **`HmacService` (`HMAC-SHA256` & `SHA-256`):**
   - Tính `hmacSha256Hex(normalizedInput)` dùng khóa `APP_HMAC_KEY_BASE64` để tạo blind index (`account_no_hash`, `national_id_hash`, `device_fingerprint_hash`, `otp_hash`).
   - Tính `sha256Hex(input)` và so sánh hằng thời gian `MessageDigest.isEqual(...)` cho `session.token_hash`, `csrf_token_hash` và chuỗi băm `audit_log.record_hash`.
3. **`JcsCanonicalizer` (`RFC 8785`):**
   - Sắp xếp toàn bộ khóa JSON theo thứ tự từ điển Unicode và loại bỏ khoảng trắng thừa để module `transfer` tính `request_hash` và module `audit` tính `record_hash` hoàn toàn tất định (deterministic).

### 3.3. Mô hình Lỗi & `GlobalExceptionHandler` (`07` §3, `design/02` Phụ lục C)
- Chuẩn hóa phản hồi lỗi trả về đầy đủ: `code`, `message`, `status`, `retryable`, `correlationId`, `requestId`, `timestamp` và `details`.
- Ánh xạ chính xác các ngoại lệ cơ sở dữ liệu PostgreSQL (`SQLSTATE`) sang mã lỗi hợp đồng API mà không bao giờ làm lộ câu lệnh SQL hay stack trace:
  - `55P03` (`lock_not_available` khi `FOR UPDATE NOWAIT` trên `transfer` / `approval_request` hoặc hết `lock_timeout = 2s`): ánh xạ sang `409 IDEMPOTENCY_IN_PROGRESS` hoặc `503 RESOURCE_LOCKED` (`retryable = true`, kèm header `Retry-After: 2`).
  - `40P01` (`deadlock_detected`) hoặc `57014` (`query_canceled` do `statement_timeout = 5s`): ánh xạ sang `503 SERVICE_UNAVAILABLE` (`retryable = true`).
  - `23505` (`unique_violation`): phân loại theo tên constraint để trả về `409 CONFLICT` tương ứng.

---

## 4. Danh sách Công việc Cần Triển khai (Ưu tiên Hoàn thành Sớm ở W2–W3)

1. [ ] Khởi tạo bộ khung cấu hình `AppProperties`, `TimeConfig` (`Clock` bean) và `SecurityFilterChainConfig` (HTTP Security Headers, CORS, `Cache-Control: no-store`).
2. [ ] Hiện thực và viết Unit Test cho `CorrelationAndRequestIdFilter` và `RequestContextHolder`.
3. [ ] Hiện thực và viết Unit Test cho bộ 4 tiện ích bảo mật dữ liệu: `PiiCryptoService`, `HmacService`, `JcsCanonicalizer` (kèm test vector RFC 8785) và `PiiMaskingUtils`.
4. [ ] Hiện thực `ErrorCode`, `BankingBusinessException`, `ApiResponse`, `ProblemResponse` và `GlobalExceptionHandler` để bàn giao cho Đạt, Vũ, Thịnh sử dụng ngay khi bắt đầu code các module nghiệp vụ ở W3.
