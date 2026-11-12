# Module 11: `common` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-11`
> **Phiên bản:** v1.2 — cập nhật 12/11/2026
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
│   ├── CorrelationAndRequestIdFilter.java    // Validate correlation ID; requestId luôn do server sinh; gắn MDC
│   ├── RequestContext.java                   // Record giữ correlationId, requestId, clientIp, userAgent
│   └── RequestContextHolder.java             // ThreadLocal truy xuất ngữ cảnh request hiện tại
├── crypto/
│   ├── PiiCryptoService.java                 // Mã hóa/giải mã AES-256-GCM (IV 12B || Ciphertext || Tag 16B)
│   ├── HmacService.java                      // HMAC-SHA256 cho blind index & SHA-256 cho token/hash chain
│   ├── JcsCanonicalizer.java                 // Chuẩn hóa JSON theo RFC 8785 (JCS) cho Idempotency & Audit
│   └── PiiMaskingUtils.java                  // Account: **** **** 1234 theo 03/07; CCCD: ********1234; IP
└── error/
    ├── ErrorCode.java                        // Enum danh mục mã lỗi chuẩn + HTTP Status + cờ retryable
    ├── BankingBusinessException.java         // Base exception cho các lỗi nghiệp vụ
    ├── ApiResponse.java                      // Danh mục cũ; chưa triển khai khi 07 chưa có contract cần nó
    ├── ProblemResponse.java                  // Envelope lỗi chuẩn theo 07. API contracts.md
    └── GlobalExceptionHandler.java           // @RestControllerAdvice ánh xạ exception & SQLSTATE
```

---

## 3. Đặc tả Các Thành phần Cốt lõi Cần Cung cấp

### 3.1. Bộ lọc Truy vết `CorrelationAndRequestIdFilter` (`design/01` §4.6)
- Một servlet filter ở SecurityProperties.DEFAULT_FILTER_ORDER - 1 (hiện -101), trước Spring Security; ApiSecurityHeadersFilter ở -102. Xem TKCT/source, không dùng HIGHEST_PRECEDENCE làm chữ ký bắt buộc.
- Kiểm tra header `X-Correlation-ID` từ client:
  - Nếu có và khớp biểu thức chính quy `^[A-Za-z0-9._:-]{1, 64}$` $\rightarrow$ giữ nguyên.
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
   - Canonicalize đầy đủ RFC 8785 mà `07` §5.2 yêu cầu: sort key theo UTF-16 code units, serialize number theo ECMAScript, escape đúng chuẩn, giữ null/array order; không chỉ sort map/bỏ khoảng trắng. `design/01` §7.2 bổ sung chi tiết không trái nguồn.

### 3.3. Mô hình Lỗi & `GlobalExceptionHandler` (`07` §4, `design/02` Phụ lục C)
- Problem theo `07` §4: `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`, `timestamp`, `retryable`; validation dùng `errors[]`. `requestId` giữ context/log, không là field body bắt buộc; không đổi thành `message/details`.
- Success trả đúng DTO/schema endpoint trong `07` §7.2/§8, không auto wrap `data/meta`. Mã security theo catalog `07`, không dùng STEP_UP_INVALID/MAKER_CHECKER_VIOLATION của thiết kế cấp dưới.
- Ánh xạ chính xác các ngoại lệ cơ sở dữ liệu PostgreSQL (`SQLSTATE`) sang mã lỗi hợp đồng API mà không bao giờ làm lộ câu lệnh SQL hay stack trace:
  - `55P03`: owner xác định đúng NOWAIT in-progress → `409 IDEMPOTENCY_IN_PROGRESS`; lock timeout tài nguyên khác → `503 SERVICE_UNAVAILABLE`. Không suy ngữ cảnh chỉ từ SQLSTATE; Retry-After theo policy nguồn.
  - `40P01` (`deadlock_detected`) hoặc `57014` (`query_canceled` do `statement_timeout = 5s`): ánh xạ sang `503 SERVICE_UNAVAILABLE` (`retryable = true`).
  - `23505`: owner phân loại replay/conflict/in-progress cho idempotency, business error đã có catalog cho unique nghiệp vụ, `500 INTERNAL_ERROR` cho ledger/invariant. Không thêm mã CONFLICT hoặc đổi mọi unique thành lỗi client.

---

## 4. Trạng thái và nơi tra cứu hiện tại

- [README common](../common/README.md): hướng dẫn sử dụng/tra cứu.
- [TKCT](../common/TKCT.md): class/API/crypto/error/health/logging và test mapping đã đối chiếu code.
- [Final report](../common/report/BE-COM-FINAL-REPORT.md): tasks, verification, state/dependency và điều kiện tiếp tục.

Nền tảng common đã triển khai;5 task Done trong phạm vi công bố, 05/07/08 Blocked thiếu identity/consumer/Cloud. Bảng class ở §2 là lược đồ thành phần cốt lõi; inventory đầy đủ 28 Java files ở TKCT §2. Không dùng checklist Planned trước code để suy hiện trạng hoặc nghiệm thu toàn module.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.1 | 12/11/2026 | Đồng bộ mô hình lỗi, success DTO, masking và JCS theo docs-common/01–11; tham chiếu quyết định trước implement, không sửa runtime |
| v1.2 | 12/11/2026 | Tách README/TKCT/final report; sửa registration order theo code, dẫn trạng thái hiện tại thay checklist Planned cũ |
