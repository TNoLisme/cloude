# BE-FND-01 — Backend Base Project

> **Dự án:** Digital Banking MVP
> **Task ID:** `BE-FND-01`
> **Owner:** Thịnh / Phương (Audit & Chuẩn hóa)
> **Trạng thái:** Completed and fully verified — ready for team handoff
> **Nguồn chuẩn:** `docs-common/05. Technical stack.md`, `06. System architecture.md`, `07. API contracts.md`, `08. Deployment infrastructure.md`, `09. Test plan.md`
> **Backend conventions:** `docs-backend/00. architecture & conventions.md`
> **Configuration reference:** `docs-backend/deployment/configuration.md`

## 1. Mục tiêu

Tạo project foundation dùng chung để module owners bắt đầu triển khai riêng trên cùng build, package, configuration, migration, Testcontainers và local Docker baseline.

## 2. Phạm vi

### In scope

- Gradle Wrapper `8.14.3` (kèm `gradle-wrapper.jar` và Version Catalog `gradle/libs.versions.toml`) và Spring Boot `3.5.7` application.
- Đầy đủ P0 dependencies: `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-web`, `spring-boot-starter-actuator`, `flyway-core`, `flyway-database-postgresql`, `micrometer-registry-prometheus`, `springdoc-openapi-starter-webmvc-ui`, `bouncycastle`, `mapstruct`, `lombok`, `lombok-mapstruct-binding`, `archunit-junit5`, `testcontainers`, `awaitility`, `wiremock-standalone`, `jacoco`.
- Java 21 toolchain và package root `com.simulatedbank.banking` với đủ 11 module (10 module nghiệp vụ theo cấu trúc 5 lớp `api`, `adapter/in`, `adapter/out`, `application`, `domain` và module `common` với 4 gói `config`, `context`, `crypto`, `error`).
- Cấu hình nền tối thiểu trong `common/config`: `AppProperties` (`@ConfigurationProperties(prefix = "app")`), `TimeConfig` (`Clock` UTC & `ZoneId` `Asia/Ho_Chi_Minh`), `SecurityFilterChainConfig`.
- Local/test Spring configuration khớp `deployment/configuration.md` (`jpa.open-in-view: false`, `ddl-auto: validate`, `hikari.auto-commit: false`, Jackson `fail-on-unknown-properties: true`, tách quyền `banking_app`/`banking_migrator`).
- PostgreSQL 16 local Compose service và init script tạo role `banking_app` / `banking_migrator`.
- Flyway V1 bootstrap schema (`CREATE SCHEMA IF NOT EXISTS banking;`).
- Actuator health infrastructure endpoint (`/actuator/health`).
- Foundation unit, context, ArchUnit (`ModuleBoundaryArchTest`) và Testcontainers PostgreSQL + JPA/Flyway integration test.
- Task report và verification report.

### Out of scope

- Chi tiết xử lý nghiệp vụ của `common` (`CorrelationAndRequestIdFilter`, `PiiCryptoService`, `HmacService`, `JcsCanonicalizer`, `GlobalExceptionHandler`) và 10 module nghiệp vụ.
- 11 module business schema (V2+).
- Cloud provider, Cloud deployment, DNS, production TLS, IaC, managed DB, production backup.
- Frontend integration.

## 3. Tài liệu đã dùng

| Tài liệu | Dùng cho |
|---|---|
| `docs-backend/00. architecture & conventions.md` | Package, Java/Boot/Gradle baseline, module boundaries, test conventions |
| `docs-backend/deployment/configuration.md` | Variable names, profiles, PostgreSQL roles, Flyway, JPA/Hikari/Jackson, `app.*` properties, health exposure |
| `docs-backend/deployment/README.md` | Local usage, deploy/release process, smoke-test and evidence conventions |
| `docs-common/05. Technical stack.md` | Java 21, Boot 4.x/fallback 3.5.x, Gradle 8.14+/9.x, PostgreSQL 16, Spring Data JPA, Security, MapStruct, Lombok |
| `docs-common/06. System architecture.md` | Modular monolith, package-by-feature, Docker Compose |
| `docs-common/07. API contracts.md` and OpenAPI | API/error/health contract |
| `docs-common/08. Deployment infrastructure.md` | Environment, secret, migration and health requirements |
| `docs-common/09. Test plan.md` | Test and evidence states |

## 4. Decisions

- Spring Boot `3.5.7`: selected as fallback permitted by `00. architecture & conventions.md` (`BE-CON-07`) and `05. Technical stack.md`.
- Gradle Kotlin DSL `8.14.3` + Version Catalog `gradle/libs.versions.toml`: meets documented 8.14+ baseline and pins dependency versions (`BE-CON-06`).
- Java toolchain `21` with `-parameters` and MapStruct compiler arguments (`componentModel=spring`, `unmappedTargetPolicy=ERROR`).
- Spring Data JPA (`spring-boot-starter-data-jpa`) with `ddl-auto: validate` and `open-in-view: false` (`BE-CON-03`, `BE-CON-67`).
- PostgreSQL `16-alpine` for local development and Testcontainers.
- `GET /actuator/health` is public infrastructure probe; all other endpoints are protected by default via `SecurityFilterChainConfig`.
- Flyway V1 initializes `SCHEMA banking` cleanly (`CREATE SCHEMA IF NOT EXISTS banking;`) without dummy/probe tables.
- Local Compose uses distinct `banking_app` and `banking_migrator` credentials.

## 5. Files changed

- `digitalbanking-backend/gradle/libs.versions.toml`
- `digitalbanking-backend/build.gradle.kts`, `settings.gradle.kts`
- `digitalbanking-backend/gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`
- `digitalbanking-backend/gradlew`, `gradlew.bat`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/BackendApplication.java`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/AppProperties.java`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/TimeConfig.java`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/SecurityFilterChainConfig.java`
- `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/<layer>/.gitkeep` (10 business modules × 5 layers + `common` × 4 sub-packages)
- `digitalbanking-backend/src/main/resources/application*.yml`
- `digitalbanking-backend/src/main/resources/db/migration/V1__init_schema.sql`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/BackendApplicationContextTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/architecture/ModuleBoundaryArchTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/health/HealthEndpointTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/FlywayMigrationTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/migration/PostgreSqlFlywayMigrationIntegrationTest.java`
- `digitalbanking-backend/Dockerfile`, `compose.yaml`, `.dockerignore`, `.gitignore`, `.env.example`, `README.md`
- `digitalbanking-backend/docker/postgres/init/01-create-roles.sh`
- `digitalbanking-docs/docs-backend/README.md`
- `digitalbanking-docs/docs-backend/workofteam/README.md`
- `digitalbanking-docs/docs-backend/workofteam/BE-FND-01-verification-report.md`

## 6. Verification summary

Detailed command/result matrix is in [`BE-FND-01-verification-report.md`](BE-FND-01-verification-report.md).

## 7. Change log

| Date | Change |
|---|---|
| 10/11/2026 | Recorded BE-FND-01 scope, foundation decisions, initial implementation |
| 11/11/2026 | Completed Testcontainers Docker discovery and initial verification report |
| 12/11/2026 | Audited and standardized base project: switched to `spring-boot-starter-data-jpa`, added Version Catalog (`libs.versions.toml`) and full P0 stack (Security, Validation, MapStruct, Lombok, springdoc, Prometheus, ArchUnit, JaCoCo), added missing `common` module and 5-layer package structure, aligned `application*.yml`/`.env.example` with `configuration.md`, removed `foundation_probe` from `V1__init_schema.sql`, fixed `gradle-wrapper.jar` in `.gitignore` |
