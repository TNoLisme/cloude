# Docker/Testcontainers Investigation

## Environment

- Date: 2026-10-03
- Backend: `Cloud/cloude/be`
- Docker CLI: `29.4.1`
- Docker context: `desktop-linux`
- Docker Desktop server: `29.4.1`
- Docker engine: Linux on WSL2, kernel `6.6.87.2-microsoft-standard-WSL2`

## Docker CLI checks

`docker info` succeeds when Docker Desktop is ready. Server reports:

- `Server Version: 29.4.1`
- `Name: docker-desktop`
- `OSType: linux`
- `Operating System: Docker Desktop`

`docker ps` reports four running containers, including PostgreSQL containers:

- `xcreative-postgres`, host port `5433`
- `datahub-db`, host port `5435`

Docker context endpoint:

```text
npipe:////./pipe/dockerDesktopLinuxEngine
```

Both named pipes exist and respond to Docker CLI:

```text
\\.\pipe\docker_engine                  True
\\.\pipe\dockerDesktopLinuxEngine       True
```

Direct probes succeed:

```powershell
docker -H npipe:////./pipe/docker_engine info --format '{{.ServerVersion}} {{.Name}}'
docker -H npipe:////./pipe/dockerDesktopLinuxEngine info --format '{{.ServerVersion}} {{.Name}}'
```

Both return:

```text
29.4.1 docker-desktop
```

Default shell state:

- `DOCKER_HOST` unset.
- `%USERPROFILE%\.testcontainers.properties` absent.
- `localhost:2375` unavailable.

## Testcontainers checks

Original dependencies resolved:

```text
org.testcontainers 1.19.8
docker-java 3.3.6
```

Targeted command:

```powershell
mvn clean test "-Dtest=FoundationPostgresTest,OtpChallengePostgresTest,RefreshSessionPostgresTest"
```

Result:

```text
Tests run: 3, Failures: 0, Errors: 3, Skipped: 0
Could not find a valid Docker environment
NpipeSocketClientProviderStrategy: failed with BadRequestException
Status 400
Docker server metadata empty
```

Compatibility attempt:

- Added Testcontainers BOM `1.20.6`.
- Resolved Testcontainers `1.20.6` and docker-java `3.4.1`.
- Repeated `FoundationPostgresTest` and all three targeted tests.

Result remained unchanged:

```text
NpipeSocketClientProviderStrategy: failed with exception BadRequestException
Status 400
Docker server metadata empty
```

Explicit `DOCKER_HOST` attempts using both working named pipes produced same Testcontainers HTTP 400 response. TCP endpoint probe returned `TcpTestSucceeded: False` for `localhost:2375`.

## Root cause boundary

Docker daemon and CLI are operational. Java Testcontainers fails during Docker API negotiation through Docker Desktop named-pipe handling. Failure occurs before PostgreSQL container startup, Flyway migration, or application test execution.

No production application workaround added. PostgreSQL integration gates remain blocked by local Testcontainers client/endpoint negotiation.

## Current verification update — 2026-10-05

Previous Docker API negotiation blocker is resolved for current local environment. With:

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE='//var/run/docker.sock'
Remove-Item Env:TESTCONTAINERS_HOST_OVERRIDE -ErrorAction SilentlyContinue
$env:JWT_SECRET='<local secret with at least 32 UTF-8 bytes>'
```

`mvn test` starts PostgreSQL 16 Testcontainers and Ryuk successfully. Full backend suite: 114 tests, 0 failures/errors/skips. Targeted PostgreSQL suite: FoundationPostgresTest, OtpChallengePostgresTest, RefreshSessionPostgresTest all pass. Do not disable Ryuk or replace Testcontainers with shared application PostgreSQL.
