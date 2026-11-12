# Digital Banking Backend

Spring Boot 3.5.7 (Java 21 LTS) modular monolith foundation for Digital Banking MVP.

## Technology Baseline

- **Runtime & Build:** Java 21 LTS toolchain, Gradle 8.14.3 Kotlin DSL with Version Catalog (`gradle/libs.versions.toml`)
- **Framework:** Spring Boot `3.5.7` (MVC, Data JPA + Hibernate, Security, Validation, Actuator)
- **Database & Migration:** PostgreSQL `16-alpine`, Flyway (`validate-on-migrate: true`, `ddl-auto: validate`)
- **Mapping & Boilerplate:** MapStruct `1.6.3`, Lombok `1.18.36`, `lombok-mapstruct-binding`
- **API & Observability:** `springdoc-openapi`, Micrometer Prometheus registry
- **Testing & Architecture:** JUnit 5, Testcontainers PostgreSQL 16, Spring Security Test, ArchUnit (`ModuleBoundaryArchTest`), Awaitility, WireMock, JaCoCo

## Local Development

Prerequisites:

- Java 21 LTS
- Docker and Docker Compose

Commands:

```bash
./gradlew test
./gradlew check
./gradlew build
docker compose config
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:18080/actuator/health
docker compose logs --no-color backend postgres
docker compose down
```

Copy `.env.example` to `.env` when customizing local ports or credentials. `.env` is ignored by Git. Do not use example credentials or sample cryptographic keys outside local/test environments.

### Testcontainers on Windows
For local test runs with Windows Docker Desktop (v4.34+ / Engine 29+), configure `~/.testcontainers.properties` in your user directory:
```properties
docker.client.strategy=org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy
docker.host=npipe:////./pipe/dockerDesktopLinuxEngine
api.version=1.44
```

## Configuration & Architecture Conventions

- **11 Modules (`com.simulatedbank.banking.<module>`):**
  - 10 business modules (`identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`) structured into 5 layers (`api`, `adapter/in`, `adapter/out`, `application`, `domain`).
  - 1 cross-cutting module (`common`) structured into 4 sub-packages (`config`, `context`, `crypto`, `error`).
- **Database Roles:** `DB_APP_USER` (`banking_app`) is the least-privilege runtime identity; `DB_MIGRATION_USER` (`banking_migrator`) runs Flyway DDL migrations.
- **Health Probes:** `/actuator/health` is the public infrastructure probe (`show-details: never`). `/api/v1/health` is implemented for Admin/Auditor and returns safe `HealthResponse` (503 for DOWN).
- **Migrations:** Forward-only under `src/main/resources/db/migration`. Never edit an applied migration.

### Common configuration and time

`AppProperties` binds `app.*`. Nested groups have defaults even without YAML. Spring Boot profile files override base YAML; environment variables and command-line arguments can override configuration. `local`/`test` include synthetic keys; demo/Cloud require external DB credentials and AES/HMAC keys. BE-COM-IMP-04 validates AES/HMAC keys at startup through `CryptoConfig`; multi-key rotation remains unimplemented (OQ-15/OI-COM-05). Never log key accessors; `Crypto.toString()` is redacted.

| Property | Environment variable | Default / validation |
|---|---|---|
| `app.timezone` | `APP_TIMEZONE` | `Asia/Ho_Chi_Minh`; a valid, nonempty Java `ZoneId` is required at startup |
| `app.security.lockout.max-failed-attempts` | `APP_LOCKOUT_MAX_FAILED_ATTEMPTS` | 5, positive integer; **Đề xuất** |
| `app.security.lockout.lock-duration-minutes` | `APP_LOCKOUT_DURATION_MINUTES` | 10, positive integer; **Đề xuất** |
| `app.security.rate-limit.transfer-per-customer-per-minute` | `APP_TRANSFER_RATE_LIMIT_PER_CUSTOMER_PER_MINUTE` | 30, positive integer; **Đề xuất**, scope is customer |

For example, set `APP_TIMEZONE=America/New_York` or pass `--app.timezone=America/New_York` to the application. Compose forwards the variables in this table from the shell or `.env`. Restart to apply changes. The removed `transfer-per-user-per-minute` and `max-concurrent-sessions-customer/staff` keys have no supported consumer; update deployments to the customer key and remove session caps. Other numeric business thresholds remain existing baselines pending their owners' decisions.

Inject `Clock` and `ZoneId businessZoneId` through the consumer constructor. Use `clock.instant()` for UTC timestamps/TTL, and `LocalDate.now(clock.withZone(businessZoneId))` for a business date. Tests can supply a primary `Clock.fixed(instant, ZoneOffset.UTC)` bean. Do not use the host default timezone or real time in business tests. These beans do not reset limits, expire sessions, execute rate limiting or schedule jobs; identity, transfer, limit, audit and notification own those behaviors. Multiple sessions are allowed per `07 §3.2`; device/user revocation is **Deferred**. Rate/lockout defaults still require policy approval (`07 §11.2–11.3`).

### Crypto, canonicalization and masking

Inject `PiiCryptoService`, `HmacService` and `JcsCanonicalizer`. AES encrypts a non-null UTF-8 string to `IV(12B) || ciphertext || tag(16B)` with a fresh SecureRandom IV. Decrypt authenticates the entire blob before returning a string; invalid data/wrong keys throw a safe `IllegalArgumentException` without provider details. Empty strings are supported. The services have no persistence or business-module dependencies.

Provide separate Base64-encoded 32-byte keys with `APP_PII_AES_KEY_BASE64` and `APP_HMAC_KEY_BASE64`. Missing/invalid keys and identical AES/HMAC keys fail startup in every profile. Only profiles exclusively `local`/`test` allow the published development keys; demo/prod/Cloud and mixed profiles reject those keys and uniform-byte placeholders. Generate each deployment key separately using `openssl rand -base64 32`; validation cannot establish randomness or discover every human-chosen placeholder. Keep actuator `env`/`configprops` unexposed and never serialize/log AppProperties key accessors. `previous-keys` must be empty: nonempty configuration fails startup rather than pretending rotation works. `aes-key-id` is metadata only and is not embedded in ciphertext.

`hmac.hmacSha256Hex(input)` hashes exact UTF-8 input and returns 64 lowercase hex characters. `HmacService.sha256Hex(String|byte[])` also preserves exact bytes. Decode hashes consistently and compare with `HmacService.constantTimeEquals(byte[], byte[])` (JCA `MessageDigest.isEqual`); do not use these functions for password hashing. Identity builds `challengeId + ":" + otp` itself. Account/customer normalize identifiers before HMAC and keep leading zeros.

`jcs.canonicalize(rawJson)` returns canonical UTF-8 bytes using RFC 8785 (`java-json-canonicalization:1.1`), with strict duplicate-key, syntax, Unicode and finite-number checks before information is lost. Pass original JSON; a previously parsed Map/JsonNode cannot prove there were no duplicate keys. Scalar roots, null, arrays and nested objects are supported. JSON numbers use IEEE-754/ECMAScript semantics; IDs and money must be strings. Transfer builds and normalizes the field set from `07 §5.2` before calling JCS, then calls `HmacService.sha256Hex(canonicalBytes)`. Audit builds its versioned payload and appends `"|" + prevHash` before SHA-256; its Appendix B vector is tested. Common does not infer fields, trim descriptions, normalize Unicode, format money/timestamps or add `hash_version`.

`PiiMaskingUtils` accepts account/national-ID **last4**, producing `**** **** 0012` / `********0012`; phone shows `********` plus last3; email shows first character plus `***@domain`; IPv4 masks the last octet. Missing input returns null; invalid input throws `IllegalArgumentException("Invalid masking input")` without echoing it. The display boundary accepts ASCII 4–15 digit phones with optional leading `+`, simple ASCII email addresses with a dotted domain, and decimal IPv4 without leading-zero octets. This is not business validation: callers must handle unsupported forms safely. IPv6 is omitted (null) until a display policy exists. Names require a caller-owned role/display policy; never fall back to full PII on failure. Secrets, fingerprints and transfer descriptions must be omitted/redacted by the owning response/log/audit/outbox mapper. Audit allowlists still forbid name/email/phone/description even after masking. Utility tests do not establish storage/backup encryption, role enforcement or cross-channel PII safety; these remain owner integration and IMP-07 work.

### Common error handling

`common.error` provides the 46 public `ErrorCode` values, `BankingBusinessException`, RFC 9457 `ProblemResponse`, MVC advice and a shared `ProblemWriter`. Success DTOs remain unchanged. Inject the writer in servlet filters or security handlers; call `create(code, safeDetail, request, errors, safeExtensions)` and `write(response, problem)`. The writer uses the application's UTC Clock and ISO-8601 ObjectMapper. Owner-supplied detail/extension values must already be public-safe; never pass entities, rejected values or raw exception messages. Extensions are allowlisted and cannot overwrite core fields; `STEP_UP_REQUIRED` requires a challenge Map matching the public schema.

MVC advice formats validation/JSON/media/method/route failures and 401/403. It preserves owner-classified business/audit failures before generic database mapping. SQLSTATE alone never identifies an idempotency race; the owner must classify it after rollback/retry. The error layer does not query, retry transactions, write audit or change transfer outcomes.

`ProblemWriter` returns `application/problem+json`, `X-Correlation-ID`, `WWW-Authenticate` for 401 and `Retry-After: 2` for in-progress/429/503, adopted as the current error-layer default under the owner’s 12 November 2026 delegation (common verification report DEC-02). IMP-05 HTTP token buckets supply the actual next-token wait via the writer overload; the 2-second fallback does not prove owner-integrated limiter enforcement. The outer correlation filter supplies validated request attribute `correlationId`; standalone writer fallback remains available without the filter. Production security entry points/body limits are implemented by IMP-05; real identity/cookie integration remains blocked; PostgreSQL locking/transaction verification remains IMP-07. See the common module verification report for evidence and limits.

### HTTP security and identity integration

BE-COM-IMP-05 implements the common HTTP layer; real identity integration is **Blocked** until identity supplies a `SessionAuthenticator` bean. That module currently has no authentication/session source. The adapter must validate active user/session, expiry/revocation and current permissions, then return `Session(userId, customerId, authorities, csrfTokenHash)`; inactive/unknown tokens return `Optional.empty()`. `userId`/`customerId` are canonical positive ID strings; authorities contain permission codes and roles prefixed `ROLE_`. CSRF hash is lowercase SHA-256 of the session's raw CSRF token. Identity owns persistence, issuance, activity/expiry, session/OTP/challenge state and cookies. The internal snapshot must never be serialized as an API DTO; its `toString()` is redacted. The shared DB design currently has no CSRF hash field: identity must settle its persistence mapping against the source before integration; common adds no schema.

The production chain resolves an opaque token from a single `DB_SESSION` cookie or a single `Authorization: Bearer` header. It rejects duplicate/ambiguous credentials and never falls back from a failed Bearer to a cookie. Missing/invalid credentials on protected routes return 401 `AUTH_SESSION_EXPIRED` with `WWW-Authenticate: Bearer realm="digital-banking"`; denied permissions and CSRF return 403 `AUTH_FORBIDDEN`, all through ProblemWriter with trace. No adapter means no credentials can authenticate. An inactive ambient cookie is ignored only on the explicitly public POST login/passcode/FIDO/OTP/resend routes, allowing reauthentication after idle expiry; an active cookie still requires CSRF there. Invalid explicit Bearer credentials remain 401. Bootstrap controllers/challenge ownership are identity work, not implemented by permitAll.

Cookie-authenticated POST/PUT/PATCH/DELETE requests use Spring's `CsrfFilter`: the single `X-CSRF-Token` header is hashed and compared to the adapter's session hash by Spring's constant-time primitive. Native token generation/rotation and Servlet HttpSession persistence are not used; no JSESSIONID or token cookie is created by common. Only successfully authenticated Bearer requests are exempt. OPTIONS preflight does not authenticate or require CSRF. The URL gates implement the API permission matrix and CHECKER privilege; method security stays enabled. Ownership, maker != checker, strong verification and immutable permission snapshots must still be enforced by the business owner.

CORS validates explicit HTTP(S) origins without wildcards, paths or credentials. Allowed methods are GET/POST/OPTIONS and headers follow `07 §3.3`, including Authorization; exposed headers include trace, replay, retry, Content-Disposition and the design's optional export headers. Denials are Problem responses. The CORS filter runs once inside security. An outer header filter also covers correlation/body/security errors with no-store, Pragma no-cache, nosniff, DENY, no-referrer and CSP frame-ancestors 'none'. HSTS is one year with includeSubDomains only for actual HTTPS requests. Forwarded headers remain disabled; ingress TLS, full SPA CSP, origin/header stripping and network isolation require task 08 verification. Common does not mint Set-Cookie; identity must verify HttpOnly/Secure/SameSite=Strict/Path=/ and 28800 seconds against `07 §3.2`.

`app.security.http.max-request-body-bytes` (`APP_MAX_REQUEST_BODY_BYTES`, default 65536, **Đề xuất**) limits all `/api/v1/**` bodies before authentication. It checks both declared length and at most limit+1 streamed bytes, including unknown/chunked lengths, then passes a bounded buffered body downstream. It supports the MVP synchronous JSON/body reader, not nonblocking Servlet ReadListener consumers. Oversize bodies return traced 413 without logging/replaying payloads. `max-rate-limit-buckets` (`APP_MAX_RATE_LIMIT_BUCKETS`, 10000) bounds process-local quota state; exhaustion fails closed with 503.

The `RequestRateLimiter` bean uses atomic token buckets with UTC Clock, full initial bursts and continuous refill. HTTP enforces global API 120/min/user or peer IP when anonymous, login 10/min/peer IP, authenticated OTP 20/min/user, step-up 10/5min/user, transfer 30/min/**customer**, audit export 5/hour/user. Multiple sessions/users of one customer share transfer quota; different customers do not. `read-per-user-per-minute` is the existing configuration key used for the global quota. Limits are **Đề xuất**, not approved release policy. Environment variables are forwarded in Compose and listed in `.env.example`; every enforced numeric limit must be positive. 429 Retry-After is ceil(seconds until a token is available), not the error writer's generic fallback of 2 seconds. A full 30/min transfer burst refills one token every 2 seconds; the tested retry after 1.1 seconds is 1 second.

Identity must enforce the normalized username bucket using `limiter.acquire("login-username", normalizedUsername, properties.security().rateLimit().loginPerUsernamePerMinute(), Duration.ofMinutes(1))` before credential verification (10/min per `07`, replacing the old design-derived 5). Return zero means allowed; a positive result is the actual Retry-After. Identity translates it through `writer.write(response, problem, retrySeconds)` with RATE_LIMITED; common does not parse/normalize login payloads. Identity also owns unauthenticated OTP user/challenge quotas, five-attempt locking and resend rules. These integrations are **Blocked**, not implied by properties or tests. Invalid-token lookup protection remains an identity/edge concern: a failed session resolver exits before the post-auth HTTP limiter. The limiter hashes subjects in memory, does not log tokens/PII, and is bounded but process-local; it resets on restart and does not enforce an aggregate quota across replicas. Behind a proxy it uses the socket peer until the ingress trust boundary is configured. Shared edge/distributed quotas and actual session flows need owner integration and task 07/08 evidence.

### Request context and propagation

`RequestContextConfig` registers `CorrelationAndRequestIdFilter` once as a servlet filter before Spring Security, including REQUEST/ASYNC/ERROR dispatches. Missing correlation headers receive UUIDv4; valid `[A-Za-z0-9._:-]{1,64}` values are preserved; empty, invalid or multiple values return 400 with a fresh safe ID. Every HTTP request receives a new server UUIDv4 requestId; client `X-Request-ID` is ignored. Dispatches reuse the request snapshot. Response correlation, request attribute, holder and MDC use the same IDs. The filter renders unexpected pre-MVC exceptions safely and cleans its binding even after failures; committed responses are preserved.

Read `RequestContextHolder.current()` inside a request. Capture its immutable value **before** scheduling async work, then bind it on the execution thread:

```java
RequestContext snapshot = RequestContextHolder.current().orElseThrow();
executor.execute(() -> {
    try (var scope = RequestContextHolder.bind(snapshot)) {
        consumer.process();
    }
});
```

Closing the scope restores the previous context and correlation/request MDC keys and preserves other MDC entries. Do not pass a scope between threads or use inheritable thread locals. MVC Callable/DeferredResult and custom executors need explicit binding around worker code; redispatch binding alone does not cover the worker. For a separate job run, create `new RequestContext(originalCorrelationId, UUID.randomUUID().toString(), null, null)`; an independent job uses its run UUID for both IDs. Copy correlation from the original transfer into ledger/audit/notification/challenge, including retries and step-up; do not generate a replacement mid-flow. Persistence propagation remains the owning modules' responsibility (IMP-07).

Forwarded headers are disabled (`server.forward-headers-strategy: none`) until an ingress trust boundary is configured and tested. Peer IP comes from the socket; only masked IPv4 is retained, while IPv6/unknown forms become null. User-Agent has control/format characters removed and is capped at 256 characters. It can still contain client-supplied PII: never log/serialize the context as an API DTO or metadata payload; use the audit allowlist. `toString()` contains IDs only. Do not put PII into correlation IDs. Behind Nginx this baseline identifies the proxy peer and does not trust client headers; validate header stripping/overwriting, direct-backend access restrictions and HTTPS redirect behavior before enabling forwarded headers (IMP-05/08).

### Logs, HTTP metrics and health

`logback-spring.xml` uses `SafeJsonLogEncoder` for one-line JSON stdout. Set `APP_ENVIRONMENT` to the deployment name (default: explicit active profiles, otherwise `local`; test profile uses `test`). Each record has UTC timestamp, level, service/environment, logger and correlation/request IDs when bound. `HTTP_REQUEST_COMPLETED` adds route template, method, status, `latency_ms`, action/outcome, approved error code and a SHA-256 masked internal user ID when authenticated. Unmatched or pre-MVC rejections use `UNMATCHED`; raw paths, query, headers, bodies and arbitrary MDC never enter the log. Completion is emitted before context cleanup; async requests share a completion guard across dispatches.

Redaction uses an allowlist: arbitrary formatted messages, arguments, structured fields, throwable messages/stack traces and unknown MDC are omitted globally. Generic framework/application records retain logger/level and exception class if supplied. This deliberately limits free-text diagnosis to avoid leaking JDBC URLs, keys, credentials or PII through third-party exceptions. Do not add a raw-message fallback. Audit/security/business owners must review a safe coded structured event integration before relying on richer technical events; technical logs never replace `audit_log` or its C3 fallback requirements. Correlation IDs are contract-approved opaque IDs; clients must keep PII/secrets out of them.

To investigate a request, take `X-Correlation-ID` from the response, find that `correlation_id` in `docker compose logs --no-log-prefix backend`, then use the server `request_id` to distinguish retries and the route/status/error code to identify the failed stage. A missing trace ID on framework startup/shutdown logs is expected. Do not enable servlet access/body/request-detail logging alongside this boundary without a separate leakage review.

Actuator/Micrometer owns the single `http.server.requests` timer. Prometheus exports `http_server_requests_seconds_count`, `_sum` and histogram `_bucket`; labels use method/route template/status/outcome and bounded framework exception/error categories. No user/account/request/correlation/idempotency IDs are metric labels. Use `rate(http_server_requests_seconds_count[5m])` for rate, filter `status=~"5.."` for server errors, and `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))` for aggregate p95 (replace 0.95 with 0.99 for p99). `/actuator/metrics/http.server.requests` gives a local snapshot; `/actuator/prometheus` is the scrape endpoint. Both require Admin/Auditor through the identity integration; keep them behind the internal ingress. Never put real tokens in shell history or reports.

`GET /actuator/health` is the anonymous infrastructure probe: status only, optionally names of liveness/readiness groups, never components/details. The aggregate checks database plus application contributors; `health/liveness` and `health/readiness` are application probes and must not be mistaken for database readiness. `GET /api/v1/health` requires Admin/Auditor and returns `status`, UTC `checkedAt`, `version`, and safe `database`/`application` statuses. Missing DB contributor fails closed to DOWN; DOWN returns 503 with `application/json` HealthResponse, UP/DEGRADED returns 200. No connection string, exception or fabricated notification backlog is exposed; no audit event is generated by health/metrics.

Common supplies HTTP signals only. Transfer owns transfer success/failure/latency, pending and idempotency metrics; identity owns auth/lockout/step-up; audit owns write/gap/tamper signals and durable records; fraud, notification and job owners emit their respective signals after the appropriate state/transaction boundary. Generic exception mapping does not increment their counters. Prometheus/Grafana deployment, alerts, retention/rotation, Cloud verification and full NFR-025 coverage remain owner/task 07/08 work. The observability integration test uses a test-only session resolver and controllers; it does not certify identity sessions or transfer execution.

## Documentation

- Backend conventions: `../digitalbanking-docs/docs-backend/00. architecture & conventions.md`
- Deployment guide: `../digitalbanking-docs/docs-backend/deployment/README.md`
- Configuration reference: `../digitalbanking-docs/docs-backend/deployment/configuration.md`
- Team work reports: `../digitalbanking-docs/docs-backend/workofteam/README.md`
- Common requirements and architecture: `../digitalbanking-docs/docs-common/`
- API contract: `../digitalbanking-docs/docs-common/07. API contracts.md`
- OpenAPI: `../digitalbanking-docs/openapi/digital-banking-v1.yaml`
