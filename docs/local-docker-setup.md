# Local Docker Setup

This guide starts the backend and PostgreSQL for local frontend development. It uses simulated VND only. Do not use this setup for production.

## Requirements

- Docker Desktop with Linux containers enabled.
- Repository cloned locally.
- Ports `8080` and `5437` available.

## First start

Run from repository root (`Cloud/cloude`):

```powershell
Copy-Item .env.example .env
notepad .env
```

Set unique local values for `POSTGRES_PASSWORD`, `DB_PASSWORD`, and `JWT_SECRET`. `JWT_SECRET` must contain at least 32 UTF-8 bytes. `POSTGRES_PASSWORD` and `DB_PASSWORD` must match. Never send `.env` to Git or share it.

Start services:

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

Compose starts PostgreSQL 16, waits until its healthcheck passes, then starts backend. Flyway applies V1–V5 automatically. PostgreSQL host port is `5437`, chosen to avoid collision with standalone local PostgreSQL on `5436`.

## Verify services

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
(Invoke-WebRequest http://localhost:8080/api/v1/v3/api-docs -UseBasicParsing).StatusCode
docker compose logs --no-color --tail 100 backend
docker compose logs --no-color --tail 100 postgres
```

Expected health status: `UP`. OpenAPI endpoint returns HTTP 200.

## Frontend connection

Use these URLs from the host machine:

```text
API base:     http://localhost:8080/api/v1
Swagger UI:   http://localhost:8080/api/v1/swagger-ui/index.html
OpenAPI JSON: http://localhost:8080/api/v1/v3/api-docs
Health:       http://localhost:8080/api/v1/health
```

If FE runs in another container on the same Compose network, use `http://backend:8080/api/v1` as API base. Do not use `localhost` between containers; it points to the current container.

## Stop and restart

Stop containers and network while retaining PostgreSQL data:

```powershell
docker compose down
```

Restart without rebuilding:

```powershell
docker compose up -d
```

Rebuild backend after source changes:

```powershell
docker compose up --build -d backend
```

`docker compose down` preserves named volume `cloude_cloude-postgres-data`. **Never add `-v`** unless data deletion is explicitly intended and approved. Do not run `DROP`, `TRUNCATE`, or remove the volume as routine troubleshooting.

## Common failures

- `Bind for 0.0.0.0:5437 failed`: another process uses host port `5437`. Stop only the identified conflicting process or change the Compose host mapping and this guide consistently.
- `Bind for 0.0.0.0:8080 failed`: another process uses backend port `8080`. Check `Get-NetTCPConnection -LocalPort 8080 -State Listen` before acting.
- Backend restarts: inspect `docker compose logs --no-color backend`. Check `JWT_SECRET`, DB credentials, active Spring profile, and PostgreSQL health.
- OpenAPI/Swagger returns non-200: verify backend is healthy and use full `/api/v1` context path.
- DB password changed after first start: existing volume retains old credentials. Do not delete volume to reset it. Restore matching existing credentials or perform an explicitly approved, backed-up password rotation.

## Testcontainers distinction

Compose database is persistent local runtime. Testcontainers tests create disposable PostgreSQL containers independently and must not use Compose database as substitute. On Windows Docker Desktop configure Testcontainers as documented in [backend setup](../../be/README.md).
