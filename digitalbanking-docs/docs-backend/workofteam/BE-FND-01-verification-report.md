# BE-FND-01 Verification Report

> **Task:** `BE-FND-01` — Backend Base Project
> **Date:** 12/11/2026
> **Overall status:** Completed — Gradle wrapper (`8.14.3`), Version Catalog (`libs.versions.toml`), Spring Data JPA, Spring Security, Bean Validation, MapStruct/Lombok, ArchUnit, Flyway V1 (`banking` schema), Actuator health, and Testcontainers PostgreSQL 16 integration test fully verified. Ready for team handoff.

## Verification matrix

| Check | Command / procedure | Result | Status |
|---|---|---|---|
| Gradle wrapper | `./gradlew --version` | Wrapper is Gradle 8.14.3 (`gradle-wrapper.jar` tracked); documented baseline is 8.14+ | Verified |
| Full check & JaCoCo | `./gradlew clean check --no-daemon` | `BUILD SUCCESSFUL`; 5/5 test classes pass (`BackendApplicationContextTest`, `ModuleBoundaryArchTest`, `HealthEndpointTest`, `FlywayMigrationTest`, `PostgreSqlFlywayMigrationIntegrationTest`) + JaCoCo report generated | Verified |
| ArchUnit boundaries | `./gradlew test --tests '*ModuleBoundaryArchTest'` | 4/4 architecture rules pass (`common` one-way, `domain` framework-free, `adapter.in` vs `adapter.out`, no module cycles) | Verified |
| Context & config | `./gradlew test --tests '*BackendApplicationContextTest'` | `Clock` (UTC), `ZoneId` (`Asia/Ho_Chi_Minh`), `AppProperties` (`app.*`) bound and verified | Verified |
| Security & health | `./gradlew test --tests '*HealthEndpointTest'` | `/actuator/health` returns HTTP 200 `{"status":"UP"}` with security headers; unauthenticated `/api/v1/health` rejected with HTTP 403 | Verified |
| Flyway V1 & JPA | `./gradlew test --tests '*PostgreSqlFlywayMigrationIntegrationTest'` | PostgreSQL 16 Testcontainers starts, Flyway applies V1 (`banking` schema without `foundation_probe`), JPA `EntityManager` (`ddl-auto: validate`) queries `current_schema() = 'banking'` | Verified |
| Compose config | `docker compose -f compose.yaml config --quiet` | Exit 0 | Verified |
| Live Docker Compose build & run | `docker compose -p be-fnd01-verify up --build -d` | Both `postgres` (`postgres:16-alpine`) and `backend` (`digitalbanking-backend:0.1.0-SNAPSHOT`) containers reach `healthy` status | Verified |
| Live Actuator health & security headers | `curl -i http://localhost:18080/actuator/health` & `curl -i http://localhost:18080/api/v1/health` | `/actuator/health` returns `HTTP/1.1 200 {"status":"UP","groups":["liveness","readiness"]}` with `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`; unauthenticated `/api/v1/health` returns `HTTP/1.1 403` | Verified |
| Live DB role separation | Query `banking.flyway_schema_history` as `banking_app` | `version=1`, `description=init schema`, `installed_by=banking_migrator`, `success=t`; queried by `current_user=banking_app` | Verified |

## Evidence and boundaries

- Gradle test report: `digitalbanking-backend/build/reports/tests/test/index.html` (5 test classes, 0 failures, 100% success rate).
- JaCoCo coverage report: `digitalbanking-backend/build/reports/jacoco/test/html/index.html`.
- Schema baseline: `V1__init_schema.sql` initializes `CREATE SCHEMA IF NOT EXISTS banking;` cleanly without dummy/probe tables.
- Module layout: All 11 modules (`identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`, `common`) have their standard sub-package hierarchy in place.

## Change log

| Date | Change |
|---|---|
| 11/11/2026 | Initial verification of Compose, PostgreSQL roles, health, and Testcontainers |
| 12/11/2026 | Re-verified after audit remediation: Spring Data JPA (`ddl-auto: validate`), SecurityFilterChain, `AppProperties`, `TimeConfig`, `ModuleBoundaryArchTest`, and clean `V1__init_schema.sql` |
