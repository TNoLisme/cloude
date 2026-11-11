# Digital Banking Backend

Spring Boot modular monolith foundation for Digital Banking MVP.

## Local development

Prerequisites:

- Java 21 (or newer JDK for local compilation; artifact target remains Java 21)
- Docker and Docker Compose

Commands from PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
docker compose config
docker compose up --build -d
docker compose ps
curl.exe http://localhost:8080/actuator/health
docker compose logs --no-color backend postgres
docker compose down
```

Set `BACKEND_PORT`, `POSTGRES_PORT`, `POSTGRES_SUPERUSER_PASSWORD`, `DB_APP_PASSWORD`, and `DB_MIGRATION_PASSWORD` in local `.env` when defaults conflict or need customization. `.env` is ignored by Git. Do not use example credentials outside local development.

### Testcontainers on Windows
For local test runs with Windows Docker Desktop (v4.34+ / Engine 29+), configure `~/.testcontainers.properties` in your user directory:
```properties
docker.client.strategy=org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy
docker.host=npipe:////./pipe/dockerDesktopLinuxEngine
api.version=1.44
```

## Configuration

- `DB_APP_USER` is runtime identity.
- `DB_MIGRATION_USER` runs Flyway migrations.
- Local PostgreSQL initialization creates both roles. Business grants are provisioned by reviewed Flyway migrations as module schemas are added.
- `/actuator/health` is infrastructure probe. `/api/v1/health` business API remains protected and is not implemented in foundation.
- Flyway migration is forward-only. Never edit an applied migration.

## Documentation

- Backend conventions: `../digitalbanking-docs/docs-backend/00. architecture & conventions.md`
- Deployment guide: `../digitalbanking-docs/docs-backend/deployment/README.md`
- Configuration reference: `../digitalbanking-docs/docs-backend/deployment/configuration.md`
- Team work reports: `../digitalbanking-docs/docs-backend/workofteam/README.md`
- Common requirements and architecture: `../digitalbanking-docs/docs-common/`
- API contract: `../digitalbanking-docs/docs-common/07. API contracts.md`
- OpenAPI: `../digitalbanking-docs/openapi/digital-banking-v1.yaml`

## Current scope

Base build, local PostgreSQL, Flyway bootstrap, and Actuator health only. Business modules remain separate owner tasks. Cloud provider, region, sizing, cost and provider-specific IaC remain Open.
