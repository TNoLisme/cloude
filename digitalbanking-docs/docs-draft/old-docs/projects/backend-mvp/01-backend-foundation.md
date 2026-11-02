# Phase 01 — Backend Foundation

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Approved OpenAPI and shared design in [`00-roadmap.md`](./00-roadmap.md).  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## Goal and boundary

Create buildable Java 21 / Spring Boot 3.3.x application with module boundaries, PostgreSQL/Flyway, safe configuration, exact common Problem handling, correlation propagation, health contract and reproducible OpenAPI checks. No login, business endpoint, fake health table, or phase 02+ policy implementation.

## Maven project

`be/pom.xml` declares Spring Boot parent `3.3.13`, `<java.version>21</java.version>`, group `com.bank`, artifact `banking-simulator`, package `com.bank.simulator`. Dependency baseline and versions are centralized in [`00-roadmap.md`](./00-roadmap.md#22-dependency-baseline).

Dependencies for this phase:

- `spring-boot-starter-web`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator` only for internal indicators/metrics if useful; do not expose actuator endpoints as a replacement for OpenAPI health.
- `spring-boot-starter-test`
- `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`
- `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, PostgreSQL driver

Set compiler release 21, UTF-8, Maven Enforcer for Java floor if existing conventions permit. Exclude unused starters. Pin Maven wrapper version after checking local/toolchain convention. No Lombok or generated server stubs.

## Exact files and classes

```text
be/pom.xml
be/mvnw, be/mvnw.cmd, be/.mvn/wrapper/*
be/src/main/java/com/bank/simulator/BankingSimulatorApplication.java
be/src/main/java/com/bank/simulator/shared/api/Problem.java
be/src/main/java/com/bank/simulator/shared/api/FieldError.java
be/src/main/java/com/bank/simulator/shared/error/ApiException.java
be/src/main/java/com/bank/simulator/shared/error/ProblemCode.java
be/src/main/java/com/bank/simulator/shared/error/GlobalExceptionHandler.java
be/src/main/java/com/bank/simulator/shared/correlation/CorrelationIdFilter.java
be/src/main/java/com/bank/simulator/shared/config/ClockConfiguration.java
be/src/main/java/com/bank/simulator/shared/health/HealthController.java
be/src/main/java/com/bank/simulator/shared/health/DatabaseHealthIndicator.java
be/src/main/resources/application.yml
be/src/main/resources/application-local.yml
be/src/main/resources/application-test.yml
be/src/main/resources/db/migration/V1__identity_customer_account_audit.sql
be/src/test/java/com/bank/simulator/shared/error/GlobalExceptionHandlerTest.java
be/src/test/java/com/bank/simulator/shared/correlation/CorrelationIdFilterTest.java
be/src/test/java/com/bank/simulator/shared/health/HealthControllerTest.java
be/src/test/java/com/bank/simulator/FoundationPostgresTest.java
be/scripts/validate-openapi.py
be/README.md
```

Create only files required to build this phase. The migration filename above is V1 because no fake `app_metadata`/health table is allowed; Phase 01 creates the first real schema owned by identity/customer. Flyway's own `flyway_schema_history` tracks migration state. The current full backend baseline contains V1–V7; Phase 01 only defines and verifies V1. Do not duplicate later migration SQL here.

## Runtime and configuration

- `BankingSimulatorApplication` is the sole `@SpringBootApplication` entry point under `com.bank.simulator`.
- `application.yml`: `/api/v1` context path only if consistent with OpenAPI server URL; prefer controller mapping `/api/v1/health` with no accidental double prefix. Configure Jackson UTC/ISO dates, datasource placeholders, Flyway enabled, Hibernate `ddl-auto=validate`, SQL logging disabled by default.
- `application-local.yml`: local datasource placeholder defaults only if safe; no committed password. Local profile may later enable mailbox, not in Phase 01.
- `application-test.yml`: test-specific no credentials, Testcontainers dynamic datasource. Disable open-in-view.
- `ClockConfiguration` provides injectable `Clock.systemUTC()` bean for deterministic tests.
- No automatic schema creation/update (`ddl-auto=none|validate` only), no DB reset, no destructive migration.

## Error contract

Use `Problem` record with schema fields:

```java
public record Problem(
    URI type, String title, int status, String detail, String instance,
    String code, UUID correlationId, List<FieldError> fieldErrors
) {}
public record FieldError(String field, String code, String message) {}
```

`GlobalExceptionHandler` maps:

- request body parse/bean validation failures → HTTP 400, `code=VALIDATION_ERROR`, per-field `fieldErrors` when available;
- known `ApiException(status, code, safeDetail, fieldErrors)` → exact status/code;
- unknown `Exception` → HTTP 500 `INTERNAL_ERROR`, generic detail, server-side structured log with correlation ID only; never return exception message/stack/SQL.

Response media type is `application/problem+json`, matching OpenAPI `ProblemResponse` and RFC 9457-compatible shape. `type` and `instance` are URI references; for generic errors use stable problem type URI and request path as instance. Do not add `message` or change any OpenAPI field. Error code mapping for business cases is added with each endpoint phase; Phase 01 covers `VALIDATION_ERROR`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE` only where health operation uses it.

## Correlation filter

`CorrelationIdFilter extends OncePerRequestFilter`:

1. Parse incoming `X-Correlation-Id` as UUID only if contract permits accepted values; invalid/missing value gets generated `UUID.randomUUID()`.
2. Put UUID string into MDC key `correlationId` before downstream processing.
3. Set response `X-Correlation-Id` header.
4. Ensure `Problem.correlationId` uses same UUID from request context.
5. In `finally`, remove MDC key and clear request-scoped holder; do not leak across pooled threads.
6. Do not log request body, auth headers, credentials or OTP.

A small `CorrelationIdContext` holder is acceptable only if required for exception mapping; use request attribute/filter and pass value explicitly where possible.

## Health endpoint

`GET /api/v1/health` is public and maps OpenAPI `getHealth`. `HealthController` returns exactly `Health(String status, Instant timestamp)`. `DatabaseHealthIndicator` uses `JdbcTemplate` `SELECT 1`; status `UP`/HTTP 200 on success, `DOWN`/HTTP 503 on required datasource failure. Never include DB version, host, username or details in body. Timestamp uses injected Clock. Liveness/readiness split remains internal; do not add public route or fields.

## OpenAPI validator

`be/scripts/validate-openapi.py` loads `../contracts/openapi.yaml` relative to script path, parses with PyYAML only if available; otherwise use a pinned project-approved CLI validator. It must:

- fail nonzero on invalid YAML/OpenAPI schema;
- recursively collect `operationId`s and fail on missing/duplicate IDs;
- never rewrite the YAML;
- print concise pass/failure and file path.

Do not claim schema validation from YAML parsing alone. Pin validator/tool version in script docs/CI. Test validator against current contract and temporary invalid copies in a temp directory, not committed contract.

## Tests and acceptance

- Unit: Problem serialization field names/media type, validation mapping, generic exception sanitization, malformed correlation ID, response header, MDC cleanup after success/exception.
- MockMvc: health success and DB-down response exactly `{status,timestamp}` with no extra properties.
- Testcontainers PostgreSQL: application context connects and Flyway applies V1. Do not run against shared DB.
- OpenAPI validation and unique operation IDs.
- Maven `./mvnw -q test`, `./mvnw -q package`, contract validator, diff check. Run targeted tests first; do not run broad suite if project grows.

Acceptance: Java 21 build passes; startup requires configured PostgreSQL; Flyway applies real initial schema; errors/health/correlation match contract; no business endpoint/contract change; tests use isolated DB.

## Implementation loop and gate

Inspect existing `be/`, root build tooling, OpenAPI Problem/Health schemas and local PostgreSQL setup. Then present any actual conflict. After phase approval, implement in order: Maven shell → config/DB/Flyway → error/correlation → health → validator/README → tests/review. Record exact commands and output in this file. Do not proceed to Phase 02 without user approval.

**Verification record:** pending implementation approval.
