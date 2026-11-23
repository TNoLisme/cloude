# Module 11: `common` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-11`
> **Phiên bản:** 1.3
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.common`
> **Owner chính:** **Phương** (Phối hợp: Vũ, Đạt, Thịnh — cung cấp hạ tầng dùng chung cho cả 10 module nghiệp vụ)
> **Mốc tập trung:** **Tuần 2 – Tuần 3 (W2–W3)** và duy trì xuyên suốt theo `11. Resource & Staffing Plan.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (NFR-013..020, NFR-025), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.1–6.3, §9, §12), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§2–§4), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§4.6, §7), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Phụ lục C), [[digitalbanking-docs/docs-common/design/04. security.md]] (§6–§8), [[DECISIONS.md]] (`DEC-DOC-001`, `DEC-ID-013`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `common` chứa toàn bộ các thành phần kỹ thuật xuyên suốt (**Cross-Cutting Concerns**) của hệ thống Backend. Module này **không sở hữu bảng dữ liệu nghiệp vụ nào** và **tuyệt đối không phụ thuộc ngược vào bất kỳ module nghiệp vụ nào** (`06. System architecture.md` §6.3), giúp cả 10 module nghiệp vụ (`identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`) dùng chung một chuẩn thống nhất về cấu hình/thời gian, truy vết ngữ cảnh, bảo mật HTTP, mật mã/che dấu PII, xử lý lỗi và quan sát vận hành (`observability`).

---

## 2. Cấu trúc Package Chuẩn của Module `common` (30 Java Files / 5 Gói con)

```text
com.simulatedbank.banking.common/
├── config/
│   ├── ApiSecurityHeadersFilter.java         // Servlet filter (-102): gắn Cache-Control: no-store, nosniff, DENY, CSP, HSTS
│   ├── AppProperties.java                    // @ConfigurationProperties(prefix = "app") với nested validation
│   ├── CryptoConfig.java                     // Khởi tạo PiiCryptoService & HmacService beans, kiểm tra khóa 32-byte fail-fast
│   ├── HealthController.java                 // GET /api/v1/health (ROLE_ADMIN / ROLE_AUDITOR) trả HealthResponse
│   ├── RequestBodyLimitFilter.java           // Giới hạn kích thước request body 64 KB (413 REQUEST_TOO_LARGE)
│   ├── RequestContextConfig.java             // Đăng ký CorrelationAndRequestIdFilter ở thứ tự -101 (trước Spring Security)
│   ├── RequestRateLimitFilter.java           // Áp dụng giới hạn tần suất HTTP theo route/subject
│   ├── RequestRateLimiter.java               // Bộ giới hạn tần suất Token Bucket trong tiến trình (tối đa 10.000 buckets)
│   ├── SafeJsonLogEncoder.java               // Logback JSON encoder một dòng theo allowlist, chặn lộ message/stack thô
│   ├── SecurityFilterChainConfig.java        // Cấu hình chuỗi bảo mật HTTP, CORS, CSRF, phân quyền URL
│   ├── SessionAuthenticationFilter.java      // Trích xuất cookie DB_SESSION / Bearer token và gọi SessionAuthenticator SPI
│   ├── SessionAuthenticator.java             // Public SPI để module identity xác thực opaque token & nạp quyền
│   ├── SessionCsrfTokens.java                // Đối chiếu SHA-256(X-CSRF-Token) với csrfTokenHash gắn theo phiên
│   └── TimeConfig.java                       // Bean Clock (UTC) & ZoneId (Asia/Ho_Chi_Minh)
├── context/
│   ├── CorrelationAndRequestIdFilter.java    // Kiểm tra X-Correlation-ID, sinh requestId mới, gắn MDC & RequestContextHolder
│   ├── HttpRequestLog.java                   // Ghi sự kiện HTTP_REQUEST_COMPLETED (route template, status, latency, trace)
│   ├── RequestContext.java                   // Record bất biến giữ correlationId, requestId, clientIp (masked), userAgent
│   ├── RequestContextHolder.java             // Quản lý ngữ cảnh request theo luồng kèm Scope khôi phục an toàn
│   └── SecurityAuditFallbackLog.java         // Ghi sự kiện dự phòng SECURITY_AUDIT_FALLBACK khi audit C3 gặp sự cố
├── crypto/
│   ├── HmacService.java                      // HMAC-SHA256 cho blind index, SHA-256 cho token/hash chain & so sánh hằng thời gian
│   ├── JcsCanonicalizer.java                 // Chuẩn hóa JSON nghiêm ngặt theo RFC 8785 (JCS) cho Idempotency & Audit
│   ├── PiiCryptoService.java                 // Mã hóa/giải mã AES-256-GCM (IV 12B || Ciphertext || AuthTag 16B)
│   └── PiiMaskingUtils.java                  // Che dấu số TK (**** **** 1234), CCCD (********1234), SĐT, Email, IPv4
├── error/
│   ├── BankingBusinessException.java         // Ngoại lệ nghiệp vụ chuẩn mang ErrorCode, safeDetail, extensions, retryAfterSeconds
│   ├── DatabaseErrorMapper.java              // Ánh xạ SQLSTATE PostgreSQL (55P03, 40P01, 57014, 23505, ...) sang ErrorCode
│   ├── ErrorCode.java                        // Danh mục 46 mã lỗi công khai chuẩn theo 07. API contracts.md §4.3
│   ├── GlobalExceptionHandler.java           // @RestControllerAdvice ánh xạ ngoại lệ MVC/nghiệp vụ/DB về ProblemResponse
│   ├── ProblemResponse.java                  // Khung phản hồi lỗi chuẩn RFC 9457 Problem Details
│   └── ProblemWriter.java                    // Ghi phản hồi application/problem+json cho cả MVC, Filter và Security handlers
└── logging/
    └── LogRedaction.java                     // Che dấu thông tin nhạy cảm (password, otp, token, cookie, csrf, username) trong map/text
```

---

## 3. Đặc tả Các Thành phần Cốt lõi Đã Cung cấp

### 3.1. Bộ lọc Truy vết `CorrelationAndRequestIdFilter` & `SecurityAuditFallbackLog` (`design/01` §4.6)
- Chạy ở thứ tự `SecurityProperties.DEFAULT_FILTER_ORDER - 1` (`-101`), ngay sau `ApiSecurityHeadersFilter` (`-102`) và trước chuỗi Spring Security (`-100`).
- Kiểm tra header `X-Correlation-ID` từ client:
  - Nếu hợp lệ theo mẫu `^[A-Za-z0-9._:-]{1,64}$` $\rightarrow$ giữ nguyên.
  - Nếu thiếu $\rightarrow$ tự sinh UUIDv4 mới.
  - Nếu sai định dạng $\rightarrow$ sinh UUIDv4 an toàn mới cho phản hồi và từ chối ngay với `400 VALIDATION_ERROR` (chống Log Injection).
- Luôn sinh mới `requestId` (UUIDv4) cho mỗi HTTP request, đặt `correlationId` và `requestId` vào SLF4J `MDC` và `RequestContextHolder`, đính kèm header `X-Correlation-ID` vào mọi HTTP response, ghi nhận `HTTP_REQUEST_COMPLETED` và dọn sạch `MDC` trong khối `finally`.
- Cung cấp `SecurityAuditFallbackLog` và `LogRedaction` để các module (`identity`, `transfer`, `audit`) ghi log bảo mật dự phòng an toàn khi ghi audit `C3` thất bại (`DEC-ID-013`).

### 3.2. Dịch vụ Mật mã & Chuẩn hóa JSON (`design/01` §7, `design/04` §6)
1. **`PiiCryptoService` (`AES-256-GCM`):**
   - Nạp khóa `256-bit` (`32 bytes`) từ biến môi trường `APP_PII_AES_KEY_BASE64` qua `CryptoConfig` (kiểm tra **Fail-fast** lúc khởi động).
   - Mỗi lần gọi `encrypt(plaintext)` sinh mới `12 bytes` IV từ `SecureRandom` và trả về mảng byte `IV (12B) || Ciphertext || AuthTag (16B)` để lưu vào cột `BYTEA` (`account_no_enc`, `national_id_enc`, `full_name_enc`, `phone_enc`, `email_enc`).
2. **`HmacService` (`HMAC-SHA256` & `SHA-256`):**
   - Tính `hmacSha256Hex(normalizedInput)` dùng khóa `APP_HMAC_KEY_BASE64` để tạo blind index (`account_no_hash`, `national_id_hash`, `phone_hash`, `email_hash`, `device_fingerprint_hash`).
   - Tính `sha256Hex(input)` và so sánh hằng thời gian `constantTimeEquals(...)` (`MessageDigest.isEqual`) cho `session_token_hash`, `csrf_token_hash` và chuỗi băm `audit_log.record_hash`.
3. **`JcsCanonicalizer` (`RFC 8785`):**
   - Chuẩn hóa JSON nghiêm ngặt theo RFC 8785 (sắp xếp khóa theo UTF-16 code units, định dạng số theo ECMAScript, từ chối khóa trùng lặp ngay từ bước parse) phục vụ tính `request_hash` của `transfer` và `canonical_payload` của `audit`.

### 3.3. Mô hình Lỗi RFC 9457 & `GlobalExceptionHandler` (`07` §4, `design/02` Phụ lục C)
- Phản hồi lỗi theo chuẩn RFC 9457 Problem Details (`application/problem+json`) gồm: `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`, `timestamp`, `retryable`, cùng `errors[]` cho lỗi validation và các trường mở rộng trong allowlist (`challenge`, `reference`, `transferStatus`, `failureCode`, `lockedUntil`).
- Phản hồi thành công trả trực tiếp DTO theo schema của từng endpoint trong `07` §7.2/§8 (không bọc thêm lớp `data/meta`).
- Ánh xạ ngoại lệ cơ sở dữ liệu PostgreSQL (`SQLSTATE`) sang mã lỗi hợp đồng API mà không bao giờ làm lộ câu lệnh SQL hay stack trace:
  - `55P03`: owner xác định đúng `NOWAIT` in-progress $\rightarrow$ `409 IDEMPOTENCY_IN_PROGRESS`; lock timeout tài nguyên khác $\rightarrow$ `503 SERVICE_UNAVAILABLE` (`retryable = true`).
  - `40P01` (`deadlock_detected`) hoặc `57014` (`query_canceled` do `statement_timeout`): ánh xạ sang `503 SERVICE_UNAVAILABLE` (`retryable = true`).
  - `23505` (`unique_violation`): owner phân loại replay/conflict/in-progress cho idempotency hoặc mã lỗi nghiệp vụ `409` thuộc catalog; vi phạm bất biến sổ cái/hệ thống ánh xạ sang `500 INTERNAL_ERROR`.

---

## 4. Trạng thái và Tài liệu Chi tiết Module `common`

- [Hướng dẫn sử dụng & tra cứu (`README.md`)](../common/README.md)
- [Thiết kế chi tiết (`TKCT.md`)](../common/TKCT.md): đầy đủ chữ ký 30 Java files, quy tắc HTTP/SQLSTATE, luồng xử lý và danh mục kiểm thử.
- [Trạng thái hiện tại (`CURRENT-STATE.md`)](../common/CURRENT-STATE.md): tổng hợp tiến độ các task `BE-COM-IMP-01..08`, báo cáo kiểm chứng và các cổng tích hợp liên module.
- [Danh mục công việc (`tasks/README.md`)](../common/tasks/README.md)

Toàn bộ **30 Java files trên 5 gói con** (`config`, `context`, `crypto`, `error`, `logging`) của module `common` đã hoàn thành triển khai và được kiểm chứng tự động.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v1.1` | 12/11/2026 | Đồng bộ mô hình lỗi, success DTO, masking và JCS theo `docs-common/01–11`. |
| `v1.2` | 12/11/2026 | Tách `README.md`, `TKCT.md`, `CURRENT-STATE.md`; cập nhật thứ tự đăng ký filter theo code thực tế. |
| `v1.3` | 18/11/2026 | Cập nhật cây package đầy đủ 30 Java files trên 5 gói con (`config`, `context`, `crypto`, `error`, `logging` gồm `SecurityAuditFallbackLog` và `LogRedaction`) và sửa liên kết tới `CURRENT-STATE.md`. |
