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

## Documentation

- Backend conventions: `../digitalbanking-docs/docs-backend/00. architecture & conventions.md`
- Deployment guide: `../digitalbanking-docs/docs-backend/deployment/README.md`
- Configuration reference: `../digitalbanking-docs/docs-backend/deployment/configuration.md`
- Team work reports: `../digitalbanking-docs/docs-backend/workofteam/README.md`
- Common requirements and architecture: `../digitalbanking-docs/docs-common/`
- API contract: `../digitalbanking-docs/docs-common/07. API contracts.md`
- OpenAPI: `../digitalbanking-docs/openapi/digital-banking-v1.yaml`
