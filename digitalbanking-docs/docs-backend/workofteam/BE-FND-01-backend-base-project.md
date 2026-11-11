# BE-FND-01 — Backend Base Project

> **Dự án:** Digital Banking MVP
> **Task ID:** `BE-FND-01`
> **Owner:** Thịnh
> **Trạng thái:** Completed and fully verified — ready for team handoff
> **Nguồn chuẩn:** `docs-common/05. Technical stack.md`, `06. System architecture.md`, `07. API contracts.md`, `08. Deployment infrastructure.md`, `09. Test plan.md`
> **Backend conventions:** `docs-backend/00. architecture & conventions.md`
> **Configuration reference:** `docs-backend/deployment/configuration.md`

## 1. Mục tiêu

Tạo project foundation dùng chung để module owners bắt đầu triển khai riêng trên cùng build, package, configuration, migration, Testcontainers và local Docker baseline.

## 2. Phạm vi

### In scope

- Gradle Wrapper và Spring Boot application.
- Java 21 target và package root `com.simulatedbank.banking`.
- Local/test Spring configuration.
- PostgreSQL 16 local Compose service.
- Tách local database role `banking_app` và `banking_migrator`.
- Flyway bootstrap schema.
- Actuator health infrastructure endpoint.
- Foundation unit, context và Testcontainers PostgreSQL migration integration test.
- Task report và verification report.

### Out of scope

- `audit`, `fraud`, `notification` business implementation.
- 11 module business schema.
- Cloud provider, Cloud deployment, DNS, production TLS, IaC, managed DB, production backup.
- Frontend integration.

## 3. Tài liệu đã dùng

| Tài liệu | Dùng cho |
|---|---|
| `docs-backend/00. architecture & conventions.md` | Package, Java/Boot/Gradle baseline, module boundaries, test conventions |
| `docs-backend/deployment/configuration.md` | Variable names, profiles, PostgreSQL roles, Flyway, health exposure |
| `docs-backend/deployment/README.md` | Local usage, deploy/release process, smoke-test and evidence conventions |
| `docs-common/05. Technical stack.md` | Java 21, Boot 4.x/fallback 3.5.x, Gradle 8.14+/9.x, PostgreSQL 16 |
| `docs-common/06. System architecture.md` | Modular monolith, package-by-feature, Docker Compose |
| `docs-common/07. API contracts.md` and OpenAPI | API/error/health contract |
| `docs-common/08. Deployment infrastructure.md` | Environment, secret, migration and health requirements |
| `docs-common/09. Test plan.md` | Test and evidence states |

## 4. Decisions

- Spring Boot `3.5.7`: selected as fallback permitted by `00. architecture & conventions.md` and `05. Technical stack.md`.
- Gradle Kotlin DSL `8.14.3`: wrapper meets documented 8.14+ baseline.
- Java source/target `21`.
- PostgreSQL `16-alpine` for local development.
- `GET /actuator/health` is infrastructure probe.
- Flyway V1 creates `banking.foundation_probe` strictly to validate permissions, schema creation, and Flyway connectivity.
- Local Compose uses distinct `banking_app` and `banking_migrator` credentials.
- Testcontainers configuration relies on standard environment variables and local `~/.testcontainers.properties` without platform-specific hardcoding in build scripts.
- Cloud platform remains Open; no provider selected.

## 5. Files changed

- `digitalbanking-backend/build.gradle.kts`
- `digitalbanking-backend/settings.gradle.kts`
- `digitalbanking-backend/gradle/wrapper/gradle-wrapper.properties`
- `digitalbanking-backend/gradlew`, `gradlew.bat`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/BackendApplication.java`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/.gitkeep` (11 module directories)
- `digitalbanking-backend/src/main/resources/application*.yml`
- `digitalbanking-backend/src/main/resources/db/migration/V1__init_schema.sql`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/BackendApplicationContextTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/health/HealthEndpointTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/FlywayMigrationTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/PostgreSqlFlywayMigrationIntegrationTest.java`
- `digitalbanking-backend/Dockerfile`, `compose.yaml`, `.dockerignore`, `.gitignore`, `.env.example`
- `digitalbanking-backend/README.md`, `docs/development.md`
- `digitalbanking-backend/docker/postgres/init/01-create-roles.sh`
- `digitalbanking-docs/docs-backend/README.md`
- `digitalbanking-docs/docs-backend/deployment/README.md`
- `digitalbanking-docs/docs-backend/workofteam/README.md`
- `digitalbanking-docs/docs-backend/workofteam/BE-FND-01-verification-report.md`

## 6. Verification summary

Detailed command/result matrix is in [`BE-FND-01-verification-report.md`](BE-FND-01-verification-report.md).

- Gradle build: `Verified` targeting Java 21 bytecode; Docker image built with JDK 21 builder.
- Foundation context & health tests: `Verified` (`BackendApplicationContextTest` & `HealthEndpointTest`).
- Testcontainers PostgreSQL migration test: `Verified` (`PostgreSqlFlywayMigrationIntegrationTest` passing 100%).
- Docker Compose config: `Verified`.
- Backend image build: `Verified` using Java 21 builder.
- Isolated PostgreSQL 16: `Verified` healthy in project `be-fnd01`.
- Flyway V1, `banking_app` query and role grants: `Verified` against both Testcontainers and isolated Compose DB.
- Actuator health: `Verified`; HTTP 200 and `UP`; Backend container healthy.
- Git conflict check and `git diff --check`: verified clean; no unresolved index entries or whitespace errors.

## 7. Handoff

The base project is fully verified and ready for module owners to begin:

1. Base commit provides working build, wrapper, container setup, Flyway bootstrap, and 100% passing tests.
2. If Docker stack is stopped, start it using project `be-fnd01`: `docker compose -p be-fnd01 up -d`.
3. Keep module source under `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/`.
4. Keep module design and task reports in matching `digitalbanking-docs/docs-backend/modules/<module>/` folders.
5. Read `00. architecture & conventions.md` and `deployment/configuration.md` before changing shared conventions/config.

## 8. Change log

| Date | Change |
|---|---|
| 10/11/2026 | Recorded BE-FND-01 scope, foundation decisions, initial implementation |
| 11/11/2026 | Completed Testcontainers Docker discovery via standard env/properties, implemented `PostgreSqlFlywayMigrationIntegrationTest`, updated verification report to fully completed |
