# Digital Banking Simulator MVP

Educational simulator for Cloud Application Development. System handles simulated VND only. No real money, bank rail, payment gateway or regulatory banking workload.

## Repository map

- `be/`: Java 21 / Spring Boot backend.
- `contracts/openapi.yaml`: HTTP contract source of truth.
- `docs/`: architecture, security, delivery workflow and phase designs.

Read first:

1. [Documentation index](docs/README.md)
2. [Backend workflow](docs/backend-development-workflow.md)
3. [MVP baseline](docs/baseline/README.md)
4. [Backend roadmap](docs/projects/backend-mvp/00-roadmap.md)
5. [API contract](contracts/openapi.yaml)

## Backend quick start

Detailed backend setup, Docker PostgreSQL, Testcontainers and Swagger instructions:

- [Backend setup and API explorer](be/README.md)

Runtime API explorer when backend is running:

```text
Swagger UI: http://localhost:8080/api/v1/swagger-ui/index.html
OpenAPI JSON: http://localhost:8080/api/v1/v3/api-docs
Health: http://localhost:8080/api/v1/health
```


- Java 21 or compatible JDK. Maven compiler target is Java 21.
- Maven 3.9+.
- PostgreSQL 16+ for local runtime.
- Python 3.11+ for OpenAPI validation.
- Docker Desktop for Testcontainers PostgreSQL tests.

Verify tools:

```powershell
java -version
mvn -version
python --version
```

Compose PostgreSQL host port is `5437` to avoid collision with existing manual PostgreSQL container on `5436`.
Requires Docker Desktop with Linux containers enabled.

Create local environment file. Do not commit `.env`:

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude
Copy-Item .env.example .env
notepad .env
```

Set a local PostgreSQL password and random `JWT_SECRET` with at least 32 UTF-8 bytes. Start PostgreSQL and backend:

```powershell
docker compose config
docker compose up --build -d
docker compose ps
```

Check backend, OpenAPI and logs:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
Invoke-WebRequest http://localhost:8080/api/v1/v3/api-docs
docker compose logs --no-color backend
```

FE integration URLs:

```text
API base: http://localhost:8080/api/v1
Swagger UI: http://localhost:8080/api/v1/swagger-ui/index.html
OpenAPI JSON: http://localhost:8080/api/v1/v3/api-docs
Health: http://localhost:8080/api/v1/health
```

Stop services and preserve database volume:

```powershell
docker compose down
```

Never run `docker compose down -v`, `DROP`, or `TRUNCATE` against retained local data without explicit approval. Testcontainers tests use disposable PostgreSQL and are separate from Compose runtime.


### Configure PostgreSQL

Set environment variables. Do not commit credentials.

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5436/banking_simulator"
$env:DB_USERNAME = "banking"
$env:DB_PASSWORD = "<local-password>"
```

Default values are defined in `be/src/main/resources/application.yml` for local development. Override them through environment variables when local PostgreSQL uses different values.

Flyway applies forward migrations automatically at application startup. Phase 01 contains V1 identity/customer/account/audit schema. No startup reset, `DROP`, `TRUNCATE` or destructive migration is allowed.

### Run backend

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude\be
mvn spring-boot:run
```

Public health endpoint:

```text
GET http://localhost:8080/api/v1/health
```

Expected healthy response shape:

```json
{
  "status": "UP",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

### Build and test

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude\be
mvn test
mvn package
```

`FoundationPostgresTest` starts disposable PostgreSQL through Testcontainers. Run it only with Docker available:

```powershell
mvn -Dtest=FoundationPostgresTest test
```

### Validate OpenAPI

Install pinned validator tools once:

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude\be
python -m pip install -r requirements-openapi.txt
```

Validate contract:

```powershell
python scripts/validate-openapi.py
```

Validator checks OpenAPI structure, references and schema validity. Script also checks missing or duplicate `operationId`. It does not run backend or business tests.

## Runtime profiles

- Default profile: PostgreSQL settings from environment/default placeholders.
- `local`: local development settings.
- `test`: test-specific settings. Integration tests override datasource through Testcontainers.

Activate profile:

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Current implementation status

- Phase 01: backend foundation.
- Phase 02–06: implemented and verified with full Maven suite, OpenAPI validation, Docker/Testcontainers, and PostgreSQL migration checks.
- Phase 07: FE contract integration design complete.
- Phase 08: quality/demo/deployment design complete.

Phase 02–06 acceptance scope excludes load testing, outage/restart recovery, dependency/image scanning, FE integration, and deployment/demo acceptance. Those belong to Phases 07–08 and remain separate gates.

## Debugging and logs

Read [observability and debugging policy](docs/observability-and-debugging.md) before adding logs. Every request receives a validated `X-Correlation-Id`. Logs may record operation, outcome, duration, state transitions and safe identifiers. Logs must never contain passwords, PINs, OTPs, tokens, hashes, full account numbers, raw auth headers or unnecessary phone/email values.

Use correlation ID to trace one request:

```http
X-Correlation-Id: 47ec3533-9b7f-408c-a143-ab3918e56446
```

Phase 01 logs unexpected failures with correlation ID and exception type only. Business-flow state logging is added with each later phase after its safe fields and event names are defined.

## Safety rules

- Use disposable PostgreSQL/Testcontainers for integration tests.
- Never run destructive database commands against shared or real data.
- Do not commit `.env`, credentials, OTP values, tokens or generated secrets.
- Do not commit or push without explicit approval.
