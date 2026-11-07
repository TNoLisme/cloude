# Operations 01: Configuration, Observability & Incident Runbook

| Trường | Giá trị |
|---|---|
| **Dự án** | Digital Banking Platform Simulator (Single-Bank, Core Simulated) |
| **Mã tài liệu** | `BE-OPS-01` |
| **Phiên bản** | v1.0 |
| **Ngày cập nhật** | 2026-11-07 |
| **Trạng thái** | Hoàn thành — Tài liệu tham chiếu chuẩn cho Backend Developer & Coding Agent |
| **Nguồn tham chiếu** | `[[digitalbanking-docs/docs-common/05. Technical stack]]` (§3, §5), `[[digitalbanking-docs/docs-common/06. System architecture]]` (§11, §12), `[[digitalbanking-docs/docs-common/08. Deployment infrastructure]]` (§3–§10), `[[digitalbanking-docs/docs-common/design/01. audit-traceability]]` (§12), `[[digitalbanking-docs/docs-common/design/02. consistency-concurrency]]` (§11, §13) |

---

## 1. Mục đích & Phạm vi

Tài liệu này tổng hợp toàn bộ cấu hình vận hành (Runtime Configuration), biến môi trường (`.env`), thiết lập HikariCP & PostgreSQL Timeouts, chuẩn hóa Structured JSON Logging, danh mục Micrometer Metrics, danh sách Background Scheduled Jobs và quy trình xử lý sự cố (Incident Runbooks `R-CON-01..08`, `R-AUD-01..05`) cho Backend Spring Boot.

---

## 2. Quản lý Cấu hình & Biến Môi trường (`.env` & `application.yml`)

### 2.1. Danh mục Biến Môi trường Bắt buộc (`.env.example`)

Tuyệt đối không commit khóa mật mã thật vào Git. Ứng dụng phải khởi động theo nguyên tắc **Fail-Fast** (dừng khởi động ngay nếu thiếu hoặc sai định dạng khóa mật mã ở profile `demo`/`prod`):

```dotenv
# --- Database Configuration ---
SPRING_PROFILES_ACTIVE=local
DB_HOST=localhost
DB_PORT=5432
DB_NAME=digital_banking
DB_APP_USER=banking_app
DB_APP_PASSWORD=change_me_local_app_pwd
DB_MIGRATION_USER=banking_migrator
DB_MIGRATION_PASSWORD=change_me_local_migrator_pwd

# --- Cryptographic Keys (Base64 encoded 32-byte / 256-bit keys) ---
# Sinh bằng: openssl rand -base64 32
APP_PII_AES_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
APP_PII_AES_KEY_ID=k-v1
APP_HMAC_KEY_BASE64=fedcba9876543210fedcba9876543210fedcba98765=

# --- Session & Cookie Security ---
APP_COOKIE_SECURE=false          # Đặt true khi chạy qua HTTPS (Nginx TLS)
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173,https://localhost:8443
```

### 2.2. Cấu hình Chuẩn `src/main/resources/application.yml`

Toàn bộ các ngưỡng nghiệp vụ có nhãn `(đề xuất)` / `OI-*` trong `docs-common` được ngoại hóa vào khối `app.*` (ánh xạ qua `@ConfigurationProperties(prefix = "app")`):

```yaml
spring:
  application:
    name: digital-banking-backend
  threads:
    virtual:
      enabled: false # Giữ platform thread pool tiêu chuẩn để kiểm soát chặt HikariCP connection pool

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:digital_banking}?currentSchema=banking
    username: ${DB_APP_USER:banking_app}
    password: ${DB_APP_PASSWORD:change_me_local_app_pwd}
    hikari:
      pool-name: BankingHikariPool
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 3000       # 3s chờ mượn connection từ pool
      validation-timeout: 1000
      max-lifetime: 1800000          # 30 phút
      auto-commit: false             # Bắt buộc tắt auto-commit để kiểm soát rõ T0, T1, T2
       leak-detection-threshold: 10000

  jpa:
    open-in-view: false              # BẮT BUỘC false: Không giữ DB connection ở tầng View/Controller
    hibernate:
      ddl-auto: validate             # BẮT BUỘC validate: Chỉ Flyway được tạo/sửa bảng
    properties:
      hibernate.jdbc.time_zone: UTC
      hibernate.Temp.use_jdbc_metadata_defaults: false

  flyway:
    enabled: true
    schemas: banking
    default-schema: banking
    user: ${DB_MIGRATION_USER:banking_migrator}
    password: ${DB_MIGRATION_PASSWORD:change_me_local_migrator_pwd}
    locations: classpath:db/migration
    validate-on-migrate: true
    out-of-order: false

server:
  port: 8080
  shutdown: graceful
  forward-headers-strategy: framework

management:
  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}

# --- Tham số Nghiệp vụ & Bảo mật Ngoại hóa (@ConfigurationProperties) ---
app:
  timezone: Asia/Ho_Chi_Minh
  crypto:
    aes-key-base64: ${APP_PII_AES_KEY_BASE64}
    aes-key-id: ${APP_PII_AES_KEY_ID:k-v1}
    hmac-key-base64: ${APP_PII_HMAC_KEY_BASE64:${APP_HMAC_KEY_BASE64}}
  security:
    cookie-secure: ${APP_COOKIE_SECURE:false}
    session:
      idle-timeout-minutes: 15
      absolute-timeout-hours: 8
      max-concurrent-sessions-customer: 3
      max-concurrent-sessions-staff: 1
    lockout:
      max-failed-attempts: 5
      lock-duration-minutes: 15
    otp:
      ttl-seconds: 300               # 5 phút
      max-verify-attempts: 5
      resend-cooldown-seconds: 60
      max-resend-count: 3
    step-up:
      single-transfer-threshold-vnd: 10000000     # >= 10,000,000 VND
      daily-cumulative-threshold-vnd: 20000000    # > 20,000,000 VND
  transfer:
    min-amount-vnd: 1000
    idempotency-ttl-hours: 24
    t2-lock-timeout-ms: 2000
    t2-statement-timeout-ms: 5000
    fraud-eval-timeout-ms: 1000
  jobs:
    reconciliation-interval-ms: 30000             # 30 giây
    pending-transfer-min-age-seconds: 15
    pending-transfer-max-age-seconds: 300         # 5 phút
    outbox-poll-interval-ms: 2000                 # 2 giây
    outbox-batch-size: 50
    outbox-lease-seconds: 30
    audit-verify-cron: "0 */15 * * * *"           # Mỗi 15 phút
    cleanup-expired-cron: "0 0 * * * *"           # Mỗi 1 giờ
```

---

## 3. Chuẩn hóa Structured JSON Logging & MDC

### 3.1. Các trường bắt buộc trong mỗi bản ghi Log (`Logback JSON Encoder`)

Mọi dòng log xuất ra `stdout` phải ở định dạng JSON 1 dòng (NDJSON) kèm đầy đủ ngữ cảnh MDC do `CorrelationAndRequestIdFilter` và `OpaqueSessionAuthenticationFilter` thiết lập:

```json
{
  "timestamp": "2026-11-07T10:20:00.128Z",
  "level": "INFO",
  "service": "digital-banking-backend",
  "environment": "demo",
  "correlationId": "corr-99887766",
  "requestId": "req-77889900",
  "actorUserId": "u1000000-0000-0000-0000-000000000001",
  "actorType": "CUSTOMER",
  "httpMethod": "POST",
  "httpPath": "/api/v1/transfers",
  "httpStatus": 201,
  "latencyMs": 42,
  "action": "TRANSFER_COMPLETED",
  "transferReference": "TRF2026110784920134",
  "errorCode": null,
  "message": "Transfer T2 completed successfully"
}
```

### 3.2. Danh sách Cấm Ghi vào Log (`Log Hygiene Rules` — `NFR-020`)

- **Cấm tuyệt đối:** `password`, `otpCode`, `SESSION_TOKEN` cookie value, `X-CSRF-Token`, `APP_PII_AES_KEY_BASE64`, `APP_HMAC_KEY_BASE64`, số tài khoản đầy đủ (`10–14` chữ số), số CCCD đầy đủ (`9–12` chữ số).
- Không dùng `@Data` / `@ToString` tự động của Lombok trên các DTO chứa thông tin nhạy cảm nếu không gắn `@ToString.Exclude`.

---

## 4. Danh mục Micrometer Metrics & Cảnh báo Vận hành

> [!WARNING]
> **Quy tắc High-Cardinality (`DEPLOY-08` §9.3):** Không bao giờ gắn các nhãn có tập giá trị vô hạn như `accountId`, `userId`, `transferReference`, `idempotencyKey`, hay `correlationId` vào Micrometer Metric Tags! Chỉ dùng các tag hữu hạn: `status`, `error_code`, `phase`, `rule_code`, `action`.

| Tên Metric (Prometheus format) | Loại | Tags được phép | Ý nghĩa & Ngưỡng Cảnh báo (`Alert Threshold`) |
|---|---|---|---|
| `banking_transfer_total` | `Counter` | `status` (`COMPLETED`, `FAILED`), `failure_code` | Tổng số giao dịch chuyển tiền theo kết quả. Cảnh báo `HIGH` nếu tỷ lệ lỗi hệ thống `> 1%` trong 5 phút. |
| `banking_transfer_phase_duration_seconds` | `Timer` | `phase` (`T0`, `T1`, `T2`) | Độ trễ từng pha chuyển tiền. Target: `p95 <= 2.0s`, `p99 <= 5.0s` ở tải `20 RPS`. |
| `banking_transfer_pending_oldest_age_seconds` | `Gauge` | — | Tuổi của bản ghi `transfer (PENDING)` lâu nhất chưa hoàn tất. Cảnh báo `HIGH` nếu `> 300s` (`R-CON-01`). |
| `banking_idempotency_events_total` | `Counter` | `outcome` (`NEW`, `REPLAY`, `CONFLICT`, `IN_PROGRESS`, `RESUMED`) | Theo dõi hành vi retry và xung đột khóa idempotency. |
| `banking_db_lock_error_total` | `Counter` | `type` (`LOCK_NOT_AVAILABLE`, `LOCK_TIMEOUT`, `DEADLOCK`, `QUERY_TIMEOUT`) | Đếm lỗi tranh chấp khóa PostgreSQL (`55P03`, `40P01`, `57014`). Cảnh báo `HIGH` nếu xuất hiện bất kỳ `DEADLOCK` nào (`R-CON-03`). |
| `banking_audit_write_total` | `Counter` | `class` (`C1`, `C2`, `C3`, `C4`), `outcome` (`SUCCESS`, `FAILURE`) | Cảnh báo `CRITICAL` ngay lập tức nếu có bất kỳ `outcome="FAILURE"` nào(`R-AUD-01`). |
| `banking_audit_chain_tamper_detected_total` | `Counter` | — | Cảnh báo `CRITICAL` nếu trình kiểm định phát hiện đứt gãy hoặc sai lệch `record_hash` (`R-AUD-02`). |
| `banking_fraud_evaluation_total` | `Counter` | `outcome` (`ALLOW`, `FLAGGED`, `AUTO_BLOCKED`, `FAIL_OPEN_ERROR`) | Theo dõi kết quả đánh giá gian lận trong `T2`. |
| `banking_notification_outbox_backlog` | `Gauge` | `status` (`PENDING`, `FAILED`) | Số lượng thông báo đang chờ gửi hoặc đã rơi vào Dead-letter (`R-CON-06`). |
| `hikaricp_connections_active` / `pending` | `Gauge` | `pool` (`BankingHikariPool`) | Cảnh báo `HIGH` nếu `active / max > 0.8` kéo dài hoặc `pending > 0` (`R-CON-07`). |

---

## 5. Tổng hợp các Tiến trình Chạy Nền (`Background Scheduled Jobs`)

| Tên Scheduler Class | Module sở hữu | Tần suất mặc định | Cơ chế Đồng bộ Đa Tiến trình | Nhiệm vụ chính |
|---|---|---|---|---|
| `TransferReconciliationScheduler` | `transfer` | Mỗi `30 giây` | `FOR UPDATE SKIP LOCKED` trên `banking.transfer` | Tìm các `transfer` ở trạng thái `PENDING` quá `15 giây`: tiếp tục chạy `executeT2()` để hoàn tất hoặc đánh dấu `FAILED (RECONCILIATION_EXPIRED)` nếu quá `5 phút`. |
| `NotificationOutboxWorker` | `notification` | Mỗi `2 giây` | `FOR UPDATE SKIP LOCKED` + cột lease `locked_until` (`30s`) | Lấy lô 50 thông báo `PENDING` (hoặc `PROCESSING` đã hết hạn lease), gửi qua adapter bên ngoài DB transaction và cập nhật `SENT` / `RETRY` / `FAILED`. |
| `AuditChainVerificationScheduler` | `audit` | Mỗi `15 phút` | `pg_try_advisory_lock` | Đọc tuần tự `banking.audit_log`, kiểm tra liên kết `prev_hash` và tính lại `SHA-256(JCS + "|" + prev_hash)` để phát hiện can thiệp trái phép. |
| `ExpiredSecurityArtifactCleanupJob` | `identity` / `transfer` | Mỗi `1 giờ` | `DELETE ... WHERE expires_at < now() LIMIT 1000` | Dọn dẹp các `sessions`, `auth_challenge` và `idempotency_record` đã quá hạn lưu trữ theo `retention_policy` (bỏ qua nếu đối tượng đang có `legal_hold`). |

---

## 6. Sổ tay Xử lý Sự cố Vận hành (`Operational Incident Runbooks`)

### 6.1. `R-CON-01` — Giao dịch Chuyển tiền bị Kẹt ở `PENDING` (`transfer_pending_age_seconds > 300`)
1. **Chẩn đoán:** Truy vấn trạng thái giao dịch, sổ cái và audit theo `reference`:
   ```sql
   SELECT t.transfer_id, t.reference, t.status, t.created_at,
          (SELECT COUNT(*) FROM banking.ledger_entry l WHERE l.transfer_id = t.transfer_id) AS ledger_count,
          (SELECT COUNT(*) FROM banking.audit_log a WHERE a.target_id = t.reference) AS audit_count
   FROM banking.transfer t
   WHERE t.status = 'PENDING' AND t.created_at < now() - INTERVAL '5 minutes';
   ```
2. **Xử lý:**
   - **Tuyệt đối không** chạy lệnh `UPDATE banking.transfer SET status = 'COMPLETED'` thủ công bằng SQL!
   - Nếu `ledger_count == 0`: Cho phép `TransferReconciliationScheduler` chạy lại `executeT2(transferId)` hoặc yêu cầu client retry với cùng `Idempotency-Key`.

### 6.2. `R-CON-03` — Phát hiện Deadlock (`40P01`) hoặc Lock Timeout (`55P03`) tăng cao
1. **Chẩn đoán:** Kiểm tra log lỗi để xác định bảng xảy ra tranh chấp (`banking.account` hay `banking.limit_usage`).
2. **Xử lý:**
   - Kiểm tra mọi code path khóa nhiều dòng trên `banking.account` đã tuân thủ đúng `ORDER BY account_id ASC FOR UPDATE` chưa.
   - Kiểm tra khóa trên `banking.limit_usage` đã tuân thủ đúng thứ tự `CUSTOMER` trước, `ACCOUNT` sau chưa.

### 6.3. `R-CON-04` — Lệch Số dư Tài khoản so với Sổ cái (`Ledger Mismatch`)
1. **Chẩn đoán:** Chạy câu truy vấn đối soát toàn hệ thống (Mục 7 của `[[digitalbanking-docs/docs-backend/modules/03-customer-and-account]]`).
2. **Xử lý:**
   - Đóng băng ngay tài khoản bị lệch (`status = 'FROZEN'`).
   - Không bao giờ sửa/xóa dòng trong `banking.ledger_entry` hay `banking.audit_log`. Sử dụng giao dịch điều chỉnh bù trừ có phê duyệt hoặc khôi phục từ bản sao lưu đã kiểm chứng.

### 6.4. `R-AUD-01` & `R-AUD-02` — Lỗi Ghi Audit (`AUDIT_WRITE_FAILED`) hoặc Đứt Chuỗi Băm (`Audit Chain Invalid`)
1. **Khi gặp `AUDIT_WRITE_FAILED` (`R-AUD-01`):**
   - Kiểm tra quyền `INSERT` của `banking_app` trên `banking.audit_log`, dung lượng đĩa PostgreSQL và xem có transaction nào giữ `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))` quá lâu không (`SELECT * FROM pg_locks WHERE locktype = 'advisory'`).
2. **Khi phát hiện đứt chuỗi băm (`R-AUD-02`):**
   - Dừng ngay lập tức mọi tiến trình `Retention / Purge`.
   - Xuất bản sao lưu bảng `banking.audit_log` hiện tại làm bằng chứng điều tra bảo mật, xác định `audit_id` đầu tiên có `record_hash` không khớp với giá trị tính toán lại.
