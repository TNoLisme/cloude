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
- **Health Probes:** `/actuator/health` is the public infrastructure probe (`show-details: never`). `/api/v1/health` business API remains protected.
- **Migrations:** Forward-only under `src/main/resources/db/migration`. Never edit an applied migration.

### Common configuration and time

`AppProperties` binds `app.*`. Nested groups have defaults even without YAML. Spring Boot profile files override base YAML; environment variables and command-line arguments can override configuration. `local`/`test` include synthetic keys; demo/Cloud require external DB credentials and AES/HMAC keys. Crypto key validation is handed to BE-COM-IMP-04; having properties does not make cryptography or rotation operational. Never log key accessors; `Crypto.toString()` is redacted.

| Property | Environment variable | Default / validation |
|---|---|---|
| `app.timezone` | `APP_TIMEZONE` | `Asia/Ho_Chi_Minh`; a valid, nonempty Java `ZoneId` is required at startup |
| `app.security.lockout.max-failed-attempts` | `APP_LOCKOUT_MAX_FAILED_ATTEMPTS` | 5, positive integer; **Đề xuất** |
| `app.security.lockout.lock-duration-minutes` | `APP_LOCKOUT_DURATION_MINUTES` | 10, positive integer; **Đề xuất** |
| `app.security.rate-limit.transfer-per-customer-per-minute` | `APP_TRANSFER_RATE_LIMIT_PER_CUSTOMER_PER_MINUTE` | 30, positive integer; **Đề xuất**, scope is customer |

For example, set `APP_TIMEZONE=America/New_York` or pass `--app.timezone=America/New_York` to the application. Compose forwards the variables in this table from the shell or `.env`. Restart to apply changes. The removed `transfer-per-user-per-minute` and `max-concurrent-sessions-customer/staff` keys have no supported consumer; update deployments to the customer key and remove session caps. Other numeric business thresholds remain existing baselines pending their owners' decisions.

Inject `Clock` and `ZoneId businessZoneId` through the consumer constructor. Use `clock.instant()` for UTC timestamps/TTL, and `LocalDate.now(clock.withZone(businessZoneId))` for a business date. Tests can supply a primary `Clock.fixed(instant, ZoneOffset.UTC)` bean. Do not use the host default timezone or real time in business tests. These beans do not reset limits, expire sessions, execute rate limiting or schedule jobs; identity, transfer, limit, audit and notification own those behaviors. Multiple sessions are allowed per `07 §3.2`; device/user revocation is **Deferred**. Rate/lockout defaults still require policy approval (`07 §11.2–11.3`).

### Common error handling

`common.error` provides the 46 public `ErrorCode` values, `BankingBusinessException`, RFC 9457 `ProblemResponse`, MVC advice and a shared `ProblemWriter`. Success DTOs remain unchanged. Inject the writer in servlet filters or security handlers; call `create(code, safeDetail, request, errors, safeExtensions)` and `write(response, problem)`. The writer uses the application's UTC Clock and ISO-8601 ObjectMapper. Owner-supplied detail/extension values must already be public-safe; never pass entities, rejected values or raw exception messages. Extensions are allowlisted and cannot overwrite core fields; `STEP_UP_REQUIRED` requires a challenge Map matching the public schema.

MVC advice formats validation/JSON/media/method/route failures and 401/403. It preserves owner-classified business/audit failures before generic database mapping. SQLSTATE alone never identifies an idempotency race; the owner must classify it after rollback/retry. The error layer does not query, retry transactions, write audit or change transfer outcomes.

`ProblemWriter` returns `application/problem+json`, `X-Correlation-ID`, `WWW-Authenticate` for 401 and `Retry-After: 2` for in-progress/429/503, adopted as the current error-layer default under the owner’s 12 November 2026 delegation (common verification report DEC-02). IMP-05 must use the remaining quota-window duration when integrating a real rate limiter; the default does not prove limiter enforcement. The outer correlation filter supplies validated request attribute `correlationId`; standalone writer fallback remains available without the filter. Production security entry points/body limits remain IMP-05 work; PostgreSQL locking/transaction verification remains IMP-07. See the common module verification report for evidence and limits.

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

## Documentation

- Backend conventions: `../digitalbanking-docs/docs-backend/00. architecture & conventions.md`
- Deployment guide: `../digitalbanking-docs/docs-backend/deployment/README.md`
- Configuration reference: `../digitalbanking-docs/docs-backend/deployment/configuration.md`
- Team work reports: `../digitalbanking-docs/docs-backend/workofteam/README.md`
- Common requirements and architecture: `../digitalbanking-docs/docs-common/`
- API contract: `../digitalbanking-docs/docs-common/07. API contracts.md`
- OpenAPI: `../digitalbanking-docs/openapi/digital-banking-v1.yaml`
