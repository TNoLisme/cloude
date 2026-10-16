# BE-B k6 Dataset and Financial Invariant Evidence

## Status

VERIFIED — SQL artifacts executed and validated on disposable PostgreSQL 16 Testcontainers via `K6SeedAndFinancialInvariantPostgresTest` (2 passed). Real k6 workload execution remains with FE/BE.

## Owner and scope

- Owner: BE-B
- In scope: create 100 synthetic customer/account rows, verify invalid-hash rollback, and prove financial invariant query passes against PostgreSQL 16 schema.
- Out of scope: execute k6 or modify `tests/load/banking-mvp.js`.

## Files

- Changed:
  - `scripts/seed-k6-accounts.sql`
  - `queries/verify-financial-invariant.sql`
  - `be/src/test/java/com/bank/simulator/K6SeedAndFinancialInvariantPostgresTest.java`
- Contract impact: none
- DB impact: disposable database only; no migration
- Security/role impact: synthetic identities, BCrypt hashes supplied through local psql variables; no plain text secrets stored.

## Dataset contract

- 100 users with role `CUSTOMER`.
- 100 customer records, configured PIN records, and one default ACTIVE CHECKING account per customer.
- Each account starts with `10,000,000 VND`.
- Login phone range: `0800000001` through `0800000100`.
- Account-number range: `700000000001` through `700000000100`.
- Email pattern: `k6-customer-NNN@example.test`.
- Seed requires psql variables `K6_PASSWORD_HASH` and `K6_PIN_HASH`, both 60-character BCrypt hashes. Do not store credential values in Git or evidence.
- Run only after Flyway migrations on an isolated disposable database. Seed transaction rolls back on any failed insert.

## Invariant

- Before and after load, use same 100 account-number range.
- Set `K6_EXPECTED_BALANCE_VND` to baseline sum captured before load.
- Pass only when account count is 100, current sum equals baseline, no negative account balance exists, and completed transfers have no duplicate idempotency resource.
- This follows approved MVP invariant `SUM(balance_before) = SUM(balance_after)`. No ledger-derived formula added.

## Verification

- Exact command:
  ```powershell
  mvn -q -Dtest="K6SeedAndFinancialInvariantPostgresTest" test
  ```
- Environment: Docker Desktop + Testcontainers PostgreSQL 16.14 (`postgres:16-alpine`), Flyway migrations V1–V7 applied.
- Expected:
  1. `seedRejectsInvalidHashAndRollsBack`: invalid BCrypt hash triggers RAISE EXCEPTION, rolls back transaction, leaves 0 rows in users/customers/accounts.
  2. `seedCreatesExact100AccountsAndInvariantQueryPasses`: valid synthetic BCrypt hash generates exactly 100 accounts with total 1,000,000,000 VND; invariant query returns `invariant_pass = true`, `delta_vnd = 0`, `negative_balance_count = 0`, `duplicate_idempotency_resources = 0`.
- Actual: 2/2 tests passed in 8.57s.
  - `seedRejectsInvalidHashAndRollsBack`: psql exit code != 0, stderr contained `K6_PASSWORD_HASH and K6_PIN_HASH must be valid BCrypt hashes`, row counts for users, customers, accounts all 0.
  - `seedCreatesExact100AccountsAndInvariantQueryPasses`: psql exit code 0, seeded 100 customers, sum balance 1,000,000,000 VND. `verify-financial-invariant.sql` executed with `K6_EXPECTED_BALANCE_VND=1000000000`, returned `invariant_pass = t`. Strongly-typed JDBC validation confirmed all totals match.
- Result: VERIFIED on disposable PostgreSQL.

## Evidence

- `scripts/seed-k6-accounts.sql`
- `queries/verify-financial-invariant.sql`
- `be/src/test/java/com/bank/simulator/K6SeedAndFinancialInvariantPostgresTest.java`
- Test report: `be/target/surefire-reports/com.bank.simulator.K6SeedAndFinancialInvariantPostgresTest.txt`

## Dependencies

- Provider + exact input: FE/BE supplies login/workload contract; BE-A confirms login and PIN expectations; BE-3 reviews financial invariant.
- Blocked item: FE/BE execution of k6 test suite against running backend instance.

## Handoff

- Consumer: FE/BE for k6 setup; BE-3 for invariant review.
- Status: SQL artifacts and disposable execution verified. Ready for FE/BE k6 runner.

## Limitations

- No load result or performance metric claimed.
- No real credentials or hash values included.
- Invariant verified only on seed/baseline state before workload. Verification of financial invariant following actual transfer load requires FE/BE k6 execution against a running instance.
