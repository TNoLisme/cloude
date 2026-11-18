# BE-TRF-02 — Domain verification

> 16/11/2026. Partial evidence only; BE-TRF-02 remains blocked on the migration, provider integration and reviews described in the task.

- Revision: working tree based on `84a4d2e`, including the uncommitted Transfer domain and tests in this change. Reviewer: pending.
- Runner: Vũ (`transfer`); Java 21, local development environment. Test data is synthetic.
- Command from `digitalbanking-backend`: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew clean check --offline --console=plain`.
- Result: `BUILD SUCCESSFUL`; 208 tests, 0 failures, 0 errors, 0 skipped. `TransferDomainTest` ran 5 tests covering amount, identifiers, transfer state and idempotency status transitions. Existing PostgreSQL migration integration test ran once and covers V1 only.
- After clarifying two validation exception messages, reran `./gradlew test --tests 'com.simulatedbank.banking.transfer.domain.*' --offline --console=plain` with the same Java home: `BUILD SUCCESSFUL`.
- Scope limits: this is not evidence for a Transfer migration, real provider FKs, terminal database trigger, conditional update/race, cross-module transaction, TTL storage, recovery fencing or rollout. No Transfer persistence implementation or schema was included in this run.
