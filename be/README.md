# Cloud Cloude Backend

Java 21 Spring Boot backend for Digital Banking Simulator MVP. Backend handles simulated VND only.

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker Desktop
- PostgreSQL 16 through Docker
- Python 3.11+ for contract validation

Verify:

```powershell
java -version
mvn -version
python --version
docker version
```

Compose PostgreSQL publishes host port `5437` to avoid collision with manual PostgreSQL on `5436`. Backend connects to Compose service name `postgres` internally.
Docker Desktop with Linux containers is required. Run from repository root:

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude
Copy-Item .env.example .env
notepad .env
```

Replace `POSTGRES_PASSWORD`, `DB_PASSWORD`, and `JWT_SECRET` with local values. `JWT_SECRET` must contain at least 32 UTF-8 bytes. Keep `.env` local; Git ignores it.

Start stack:

```powershell
docker compose config
docker compose up --build -d
docker compose ps
```

Compose waits for PostgreSQL health before starting backend. Flyway applies V1–V5 on backend startup.

Check service:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
Invoke-WebRequest http://localhost:8080/api/v1/v3/api-docs
docker compose logs --no-color backend
```

FE URLs:

```text
API base: http://localhost:8080/api/v1
Swagger UI: http://localhost:8080/api/v1/swagger-ui/index.html
OpenAPI JSON: http://localhost:8080/api/v1/v3/api-docs
```

Stop stack but keep database:

```powershell
docker compose down
```

`docker compose down` preserves named volume `cloude-postgres-data`. Never add `-v` unless explicitly approved; that removes persisted database data. Never run `DROP` or `TRUNCATE` against retained data.

Set `SPRING_PROFILES_ACTIVE` only to supported backend profiles. Compose defaults to the safe `default` profile and disables local OTP mailbox. Use `local` or `demo` only when mailbox guard token and loopback binding are configured correctly; do not expose local mailbox beyond localhost.

## Manual local development

For Maven development without containerizing backend, start PostgreSQL with Docker and run Spring Boot from `be/`. Existing manual commands follow.


Run from PowerShell. This creates isolated local database container. It does not use SQL Server.

```powershell
docker volume create cloude-postgres-data

docker run -d `
  --name cloude-postgres `
  --restart unless-stopped `
  -e POSTGRES_DB=banking_simulator `
  -e POSTGRES_USER=banking `
  -e POSTGRES_PASSWORD=<local-password> `
  -p 5436:5432 `
  -v cloude-postgres-data:/var/lib/postgresql/data `
  --health-cmd "pg_isready -U banking -d banking_simulator" `
  --health-interval 10s `
  --health-timeout 5s `
  --health-retries 10 `
  postgres:16
```

If container already exists:

```powershell
docker start cloude-postgres
docker inspect --format "{{.State.Health.Status}}" cloude-postgres
```

Expected status: `healthy`.

Do not run `docker rm`, `docker volume rm`, `docker compose down -v`, `DROP`, or `TRUNCATE` against this database without explicit approval.

## Configure and run backend

Open PowerShell in `be/`:

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude\be
$env:DB_URL = "jdbc:postgresql://localhost:5436/banking_simulator"
$env:DB_USERNAME = "banking"
$env:DB_PASSWORD = "<local-password>"
$env:JWT_SECRET = "<at-least-32-byte-local-secret>"
mvn spring-boot:run
```

`JWT_SECRET` is required. Keep PowerShell window open; environment variables apply only to that PowerShell process.

If `JWT_SECRET` is missing, startup fails with:

```text
app.security.jwt-secret must contain at least 32 UTF-8 bytes
```

Generate a local secret without putting it in source control:

```powershell
$bytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($bytes)
```

Flyway applies V1–V5 automatically. Backend context path is `/api/v1` and server port is `8080`.

Health:

```text
GET http://localhost:8080/api/v1/health
```

PowerShell smoke check:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Expected response:

```json
{"status":"UP","timestamp":"..."}
```

## Swagger and endpoint inspection

Swagger UI:

```text
http://localhost:8080/api/v1/swagger-ui/index.html
```

If Swagger returns `403`, restart backend after latest code changes and open the exact URL above. Swagger resources are public only under `/api/v1`:

```text
/api/v1/v3/api-docs/**
/api/v1/swagger-ui/**
/api/v1/swagger-ui.html
/api/v1/webjars/**
```

Do not open `http://localhost:8080/swagger-ui/index.html`; it misses context path `/api/v1`.

Generated OpenAPI JSON:

```text
http://localhost:8080/api/v1/v3/api-docs
```

Use Swagger UI to inspect controller endpoints, parameters, request bodies, response schemas, status codes and security requirements. Runtime Swagger describes registered Spring MVC routes. Contract source of truth remains:

```text
D:\work\Xgame\XCreative\Cloud\cloude\contracts\openapi.yaml
```

Swagger UI does not replace contract validation and does not create missing endpoints.

## Build and tests

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude\be
mvn -q -DskipTests package
```

Targeted Phase 1–6 suite:

```powershell
mvn "-Dtest=SessionControllerTest,CsrfTokenServiceTest,CsrfHeaderFilterTest,OtpChallengeServiceTest,OtpDispatchFailureTest,OnboardingServiceTest,OnboardingRegistrationTest,OnboardingRecoveryTest,PinCredentialServiceTest,OnboardingCookieTest,RateLimitInterceptorTest,RateLimitExceptionHandlerTest,OnboardingControllerMvcTest,RecipientControllerMvcTest,AccountControllerMvcTest,TransferControllerMvcTest,TransferQueryServiceTest,AccountOperatorServiceTest,AccountOperatorServiceTestAdditional,AccountQueryServiceTest,AccountStatusServiceTest,TransferServiceTest,TransferServiceLifecycleTest,TransferPolicyTest,RiskEvaluationServiceTest,RiskEvaluationServiceListTest,TransferCommittedRiskListenerTest,AuditControllerMvcTest,ListAuditEventsServiceTest,RiskFlagControllerMvcTest,CursorCodecTest,IdempotencyJdbcRepositoryTest" test
```

PostgreSQL/Testcontainers suite on Windows Docker Desktop:

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = "//var/run/docker.sock"
Remove-Item Env:TESTCONTAINERS_HOST_OVERRIDE -ErrorAction SilentlyContinue
$env:JWT_SECRET = "<at-least-32-byte-local-secret>"
mvn "-Dtest=FoundationPostgresTest,OtpChallengePostgresTest,RefreshSessionPostgresTest" test
```

Keep Ryuk enabled. Do not set `TESTCONTAINERS_RYUK_DISABLED=true`.

## Database checks

```powershell
docker exec -it cloude-postgres psql -U banking -d banking_simulator
```

Inside `psql`:

```sql
\dt
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Expected application tables:

```text
users
user_roles
customers
customer_pins
accounts
otp_challenges
refresh_sessions
idempotency_records
account_seed_records
transfers
audit_events
risk_flags
```

## OpenAPI contract validation

Run from repository root when validator script is available:

```powershell
cd D:\work\Xgame\XCreative\Cloud\cloude
python -m pip install -r be\requirements-openapi.txt
python scripts\validate-openapi.py
```

Contract file:

```text
contracts/openapi.yaml
```

## Runtime notes

- SQL Server is not used by this backend.
- PostgreSQL is required because migrations and queries use PostgreSQL-specific types and behavior.
- Do not commit passwords, JWT secrets, OTPs, tokens or `.env` files.
- Do not commit or push from this workflow.
