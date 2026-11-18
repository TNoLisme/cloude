# Validation guide

Prerequisites: Java 21, cached Gradle dependencies, Docker/Testcontainers postgres:16-alpine. Run from digitalbanking-backend. No full regression.

1. `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2`
2. `./gradlew build -x test --offline --console=plain --max-workers=2`
3. `git diff --check` from repository root.

Expected: PostgreSQL constraints + provider tests green, no skips, package build successful. See contract for ordering. Test scenarios: both/single/no scopes, threshold and invalid amount, stale early reads, first-day and pre-existing row contention, aggregate multiple accounts, atomic rollback/business rejection, real audit failure, transaction-bound/repeated handle rejection, database timestamp and configurable timezone midnight.

No transfer end-to-end/retry/recovery claim: LIM-05 waits for real dependencies. Evidence recorded in E2-verification.md. Independent converge reviewer reads spec/plan/tasks and appends gaps only.
