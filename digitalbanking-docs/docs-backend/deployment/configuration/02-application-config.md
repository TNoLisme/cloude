# 02. Cấu hình Ứng dụng (`application.yml`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-02`
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Baseline đã đối chiếu `docs-common/` — chưa có bằng chứng runtime
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§5), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]], [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]], [[digitalbanking-docs/docs-common/design/04. security.md]]

> [!important]
> Đây là **baseline cấu hình** cho implementation, không phải bằng chứng rằng ứng dụng đã chạy. Giá trị `Open/Proposed` phải được chốt trước demo. Không đặt secret thật trong file này.

---

## 1. Runtime baseline

| Thành phần | Baseline | Trạng thái |
|---|---|---|
| Java | 21 LTS | Confirmed (`05`) |
| Spring Boot | 4.x; fallback 3.5.x | Confirmed (`05`) |
| Build | Gradle Kotlin DSL 8.14+/9.x | Confirmed (`05`) |
| Database | PostgreSQL 16.x | Confirmed (`05`) |
| Migration | Flyway 11.x | Confirmed (`05`) |
| ORM | Hibernate/JPA ở chế độ `validate` | Confirmed (`08` §8.1) |
| Fraud | In-process, synchronous trong T2, fail-open | Confirmed (`06`; `design/03`) |

> Không dùng Maven wrapper hoặc câu lệnh `./mvnw` cho dự án này.

---

## 2. `application.yml` baseline

```yaml
spring:
  application:
    name: digital-banking-backend

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:digital_banking}?currentSchema=banking
    username: ${DB_APP_USER}
    password: ${DB_APP_PASSWORD}
    hikari:
      pool-name: BankingHikariPool
      maximum-pool-size: ${DB_POOL_MAX_SIZE:20}
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      connection-timeout: 3000
      validation-timeout: 1000
      idle-timeout: 600000
      max-lifetime: 1800000
      auto-commit: false
      leak-detection-threshold: 10000

  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC

  flyway:
    enabled: true
    schemas: banking
    default-schema: banking
    user: ${DB_MIGRATION_USER}
    password: ${DB_MIGRATION_PASSWORD}
    locations: classpath:db/migration
    validate-on-migrate: true
    out-of-order: false

  jackson:
    deserialization:
      fail-on-unknown-properties: true
    serialization:
      write-dates-as-timestamps: false

server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
  forward-headers-strategy: framework
  servlet:
    session:
      cookie:
        http-only: true
        secure: ${APP_COOKIE_SECURE:true}
        same-site: strict
        path: /api

management:
  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
      probes:
        enabled: true

app:
  timezone: ${APP_TIMEZONE:Asia/Ho_Chi_Minh}

  crypto:
    aes-key-id: ${APP_PII_AES_KEY_ID}
    aes-key-base64: ${APP_PII_AES_KEY_BASE64}
    hmac-key-base64: ${APP_HMAC_KEY_BASE64}

  security:
    cors-allowed-origins: ${APP_CORS_ALLOWED_ORIGINS}
    session:
      # Session opaqueness, CSRF va CSRF token do app code dam nhan;
      # cac gia tri duoi day la policy, khong phai cookie config.
      idle-timeout-minutes: 15
      absolute-timeout-hours: 8
      max-concurrent-sessions-customer: 3
      max-concurrent-sessions-staff: 1
    otp:
      ttl-seconds: 300
      recovery-ttl-seconds: 120
      max-verify-attempts: 5
      single-use: true
    auth:
      lockout:
        max-failed-attempts: 5
        # CFG-OPEN-01: 10 phut (SRS/BRD/06/07) vs 15 phut (design/04)
        lock-duration-minutes: ${AUTH_LOCKOUT_MINUTES}
    step-up:
      # CFG-OPEN-02: 5.000.000 (design/03) vs 10.000.000 (design/04)
      single-threshold-vnd: ${STEP_UP_SINGLE_VND}
      daily-threshold-vnd: 20000000
      daily-cap-vnd: 50000000

  transfer:
    min-amount-vnd: 2000
    max-amount-vnd: 10000000
    idempotency-ttl-hours: 24
    t2-lock-timeout-ms: 2000
    t2-statement-timeout-ms: 5000
    fraud-eval-timeout-ms: 1000

  fraud:
    velocity-count: 3
    velocity-window-seconds: 120
    warning-ttl-minutes: 15
    review-timeout-minutes: 30

  jobs:
    # Toan bo interval duoi day la Proposed/Open (CFG-OPEN-03).
    reconciliation-interval-ms: 30000
    pending-transfer-min-age-seconds: 15
    pending-transfer-max-age-seconds: 300
    outbox-poll-interval-ms: 2000
    outbox-batch-size: 50
    outbox-lease-seconds: 30
    audit-verify-cron: "0 */15 * * * *"
    retention-cleanup-cron: "0 0 * * * *"
```

---

## 3. Nguồn và trạng thái từng nhóm giá trị

| Nhóm | Thuộc tính | Giá trị | Nguồn | Trạng thái |
|---|---|---|---|---|
| Datasource | `maximum-pool-size` / `minimum-idle` | 20 / 5 | `design/02` | Confirmed |
| Datasource | `connection-timeout` / `idle-timeout` / `max-lifetime` / `leak-detection-threshold` | 3000 / 600000 / 1800000 / 10000 ms | `design/02` | Confirmed |
| Datasource | `auto-commit` | `false` | `design/02` (T2 transaction control) | Confirmed |
| JPA | `open-in-view` / `ddl-auto` | `false` / `validate` | `08` §8.1 | Confirmed |
| Flyway | `validate-on-migrate` / `out-of-order` | `true` / `false` | `08` §8.1 | Confirmed |
| Session | idle / absolute | 15 phút / 8 giờ | `design/04` | Confirmed |
| Session | max concurrent CUSTOMER / STAFF | 3 / 1 | `design/04` | Confirmed |
| Cookie | HttpOnly / Secure / SameSite / Path | `true` / `true` (demo) / `Strict` / `/api` | `design/04` | Confirmed |
| CSRF | Token 32 byte | App code | `design/04` | Confirmed |
| OTP | challenge TTL | 300 giây | `05`; `design/04` | Confirmed |
| OTP | recovery TTL | 120 giây | `design/03` | Confirmed |
| OTP | max verify attempts / single-use | 5 / `true` | `design/04` | Confirmed |
| OTP | resend cooldown/count | Chưa chốt | — | **Open** (`CFG-OPEN-08`) |
| Lockout | max failed attempts | 5 | `03`; `06`; `07`; `design/04` | Confirmed |
| Lockout | lock duration | 10 phút vs 15 phút | `03`/`06`/`07` vs `design/04` | **Open** (`CFG-OPEN-01`) |
| PIN lockout | 5 lần / 15 phút | 5 / 15 phút | `design/03` | Confirmed (riêng PIN) |
| Step-up | single threshold | 5 triệu vs 10 triệu | `design/03` vs `design/04` | **Open** (`CFG-OPEN-02`) |
| Step-up | daily threshold / cap | 20 triệu / 50 triệu | `design/03` | Confirmed |
| Transfer | min / max | 2.000 / 10.000.000 VND | `design/03` | Confirmed |
| Idempotency | TTL | 24 giờ | `08` §8.3 | **Proposed** |
| T2 | lock timeout / statement timeout | 2.000 / 5.000 ms | `design/02` | Confirmed |
| Fraud | evaluation budget | 1.000 ms | `design/03`/`06` | **Proposed** |
| Fraud | velocity / warning / review | 3 / 120s / 15m / 30m | `design/03` | Confirmed |
| Jobs | reconciliation/outbox/audit/retention intervals | Giá trị khởi điểm | — | **Proposed/Open** (`CFG-OPEN-03`) |

---

## 4. Quy tắc validation khi khởi động (fail-fast)

Ứng dụng phải dừng khởi động nếu:

1. Thiếu biến bắt buộc (`DB_APP_USER`, `DB_APP_PASSWORD`, `APP_PII_AES_KEY_BASE64`, `APP_HMAC_KEY_BASE64`, `APP_CORS_ALLOWED_ORIGINS`, `AUTH_LOCKOUT_MINUTES`, `STEP_UP_SINGLE_VND`).
2. Khóa không decode được Base64 hoặc sau decode không đúng 32 byte.
3. `min-amount-vnd >= max-amount-vnd`.
4. `single-threshold-vnd > daily-cap-vnd` hoặc `daily-threshold-vnd > daily-cap-vnd`.
5. `t2-lock-timeout-ms >= t2-statement-timeout-ms` hoặc timeout không dương.
6. CORS chứa `*` trong khi credential được bật.
7. `APP_COOKIE_SECURE=false` ngoài profile `local`.
8. Secret hoặc placeholder mẫu còn nguyên trong môi trường `demo`.

Không dùng default ngầm cho secret. Nếu một giá trị chưa được chốt (ví dụ lockout duration), dùng biến môi trường bắt buộc và ghi rõ trạng thái **Open**.

---

## 5. Quy tắc sở hữu và thay đổi

- Cấu hình được externalize thành `@ConfigurationProperties(prefix = "app")`; không hard-code trong business logic.
- Server làm chủ transfer limit, OTP, fraud threshold và retention; client không được override.
- Cấu hình chỉ thay đổi hành vi môi trường, không được nới lỏng invariant nghiệp vụ.
- Thay đổi ảnh hưởng tiền, authorization, retention hoặc fraud phải qua review/audit.
- Pin version dependency/image; không dùng tag `latest` cho artifact release.
- Không đặt secret trong `application.yml` hoặc image.

---

## 6. Health và Actuator liên quan

- Chỉ expose công khai `health`; `info` chỉ công khai khi đã redacted.
- `metrics`/`prometheus` không được expose công khai; phải qua mạng nội bộ hoặc quyền vận hành.
- `show-details: never` cho public health để không lộ thông tin hạ tầng.
- Chi tiết contract xem [[digitalbanking-docs/docs-backend/deployment/configuration/04-observability-and-alerting.md]].

---

## 7. Kiểm chứng

| ID | Kịch bản | Kỳ vọng | Trạng thái |
|---|---|---|---|
| `DVT-001` | Rà soát biến/cấu hình | Không thiếu biến bắt buộc; không có secret thật | **Not verified** |
| `DVT-004` | Migration/validate schema | `ddl-auto: validate` không phát hiện lệch | **Not verified** |
| `DVT-008` | Cấu hình bảo mật | Cookie/CSRF/session/OTP đúng policy | **Not verified** |
