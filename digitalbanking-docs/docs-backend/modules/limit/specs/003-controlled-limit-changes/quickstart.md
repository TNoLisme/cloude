# Validation

From digitalbanking-backend/, Java 21, existing Gradle cache and Docker for Testcontainers PostgreSQL 16:

```sh
./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2
./gradlew build -x test --offline --console=plain --max-workers=2
```

Test properties supply explicit maxima (test data, not production defaults). Without app.limit.policy.max-per-transaction-vnd and max-daily-vnd, prepare/approve refuse changes while rejection/read/enforcement remain usable. Existing module tests reuse real bank app DB role.

Expected: pending leaves effective configuration/usage intact; approve/reject works exactly once, preserves history and usage, max policy rechecked; invalid/self/future/used-day-first-account rejected; concurrency and real audit failure rollbacks pass. Scope account may activate on a later unused day. No real admin endpoint, identity, ownership/FK or transfer integration is certified by these tests.
