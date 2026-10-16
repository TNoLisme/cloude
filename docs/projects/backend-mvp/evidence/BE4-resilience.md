# BE-B Audit and Resilience Evidence

## Status

VERIFIED — targeted resilience, outage recovery, backend process restart idempotency replay, and risk tests pass (12/12 passed across BE-B test suite). Full cross-owner merge gate remains with team handoffs.

## Owner and scope

- Owner: BE-B
- In scope: shared disposable PostgreSQL test helper, transfer persistence/replay harness, backend process restart idempotency replay, risk flag duplicate-insert proof.
- Out of scope: production transfer behavior, account/auth implementation, UI, load-test script.

## Files

- Changed:
  - `be/src/test/java/com/bank/simulator/DisposablePostgresTestSupport.java`
  - `be/src/test/java/com/bank/simulator/DisposablePostgresTestSupportTest.java`
  - `be/src/test/java/com/bank/simulator/DatabaseFailureRecoveryTest.java`
  - `be/src/test/java/com/bank/simulator/K6SeedAndFinancialInvariantPostgresTest.java`
  - `be/src/test/java/com/bank/simulator/risk/application/RiskFlagRepositoryPostgresTest.java`
  - `scripts/seed-k6-accounts.sql`
  - `queries/verify-financial-invariant.sql`
- Contract impact: none
- DB impact: tests use disposable PostgreSQL 16; no retained DB cleanup
- Security/role impact: SQL fixtures use synthetic identities; seed requires externally supplied BCrypt hashes

## Verification

- Environment/profile/database isolation: Docker Desktop + disposable Testcontainers PostgreSQL 16.14. Used Docker Desktop named pipe. Container IDs recorded in Surefire output.
- Exact command for current final targeted run:

```powershell
mvn -f "D:\work\Xgame\XCreative\yuiyL\cloude\be\pom.xml" -q -Dtest="DatabaseFailureRecoveryTest,DisposablePostgresTestSupportTest,RiskFlagRepositoryPostgresTest,RiskEvaluationServiceTest,K6SeedAndFinancialInvariantPostgresTest" test
```

Earlier focused commands:

```powershell
mvn -f "D:\work\Xgame\XCreative\yuiyL\cloude\be\pom.xml" -q -Dtest="*Audit*Test,*Risk*Test,DisposablePostgresTestSupportTest,RiskFlagRepositoryPostgresTest" test
mvn -f "D:\work\Xgame\XCreative\yuiyL\cloude\be\pom.xml" -q -Dtest="DatabaseFailureRecoveryTest" test
```

- Expected: support refuses unsafe reset; reset SQL contains business tables and preserves Flyway history; same transfer actor/payload/key replays original transfer across backend process restart; risk uniqueness prevents duplicate rule flag; k6 seed and invariant query succeed.
- Actual: current run `DatabaseFailureRecoveryTest` 3/3, `DisposablePostgresTestSupportTest` 3/3, `RiskFlagRepositoryPostgresTest` 1/1, `RiskEvaluationServiceTest` 3/3, `K6SeedAndFinancialInvariantPostgresTest` 2/2. Earlier audit/risk suite had 16/16 pass. All reports show 0 failures/errors/skips. PostgreSQL 16.14 applied Flyway V1–V7.
- Result: VERIFIED for listed targeted tests.
  1. Live Toxiproxy network cut causes health probe to fail and then recover without changing datasource URL.
  2. Direct transfer committed and same-key replay returns original record.
  3. `applicationRestartPreservesCommittedTransferAndSameKeyReplayReturnsOriginal`: backend Spring application context closed and second context restarted against same persisted PostgreSQL container; retrying same idempotency key returns `replayed=true`, count(transfers)=1, balances 6M/4M preserved (in-JVM context restart proof).
- Tool/version: Testcontainers 1.21.4; PostgreSQL 16.14; Toxiproxy 2.5.0.

## Evidence

- Test reports: `be/target/surefire-reports/`
  - `com.bank.simulator.DatabaseFailureRecoveryTest.txt`: 3 passed, 0 failures, 0 errors
  - `com.bank.simulator.DisposablePostgresTestSupportTest.txt`: 3 passed, 0 failures, 0 errors
  - `com.bank.simulator.K6SeedAndFinancialInvariantPostgresTest.txt`: 2 passed, 0 failures, 0 errors
- Synthetic identifiers only. No secrets captured.

## Dependencies

- Provider + exact input: BE-B disposable PostgreSQL support. BE-3 owns financial transaction assertions; BE-A owns auth/OTP behavior assertions.
- Blocked items: FE/BE k6 run against deployed instance, cross-owner final acceptance evidence.

## Handoff

- Consumer: BE-A and BE-3.
- API/DTO/state/error details: `DisposablePostgresTestSupport.newDisposablePostgres(databaseName)`, `registerDatasource(registry, postgres)`, `clearBusinessTables(jdbc, postgres, expectedJdbcUrl)`. Reset verifies live container and JDBC URL before truncating. Flyway history stays intact.
- Next owner/action: BE-A/BE-3 consume shared support; FE/BE provide k6 workload/input contract; BE-3 reviews invariant query.

## Limitations

- Database outage: live Toxiproxy cut causes `DatabaseHealthIndicator` to return unavailable. Restoring proxy restores health without changing datasource URL. The existing `HealthControllerTest` verifies HTTP 503 shape `{status,timestamp}`.
- Backend restart test exercises full Spring Boot context lifecycle and idempotency replay against persisted PostgreSQL DB state within the test JVM. It does not perform OS-level process kill or multi-node container failover.
- Invariant SQL verified on disposable PostgreSQL 16 container with 100 accounts before load; post-load invariant check will be executed by FE/BE load test.
