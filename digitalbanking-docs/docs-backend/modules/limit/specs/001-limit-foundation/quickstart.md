# Validate E1

Prerequisites: Java 21, Gradle cache/wrapper, Docker with postgres:16-alpine. Run from digitalbanking-backend. For Spec Kit without local state use SPECIFY_FEATURE_DIRECTORY=specs/001-limit-foundation.

1. Run ./gradlew test --tests 'com.simulatedbank.banking.limit.*' --offline --console=plain.
2. Expect shipped migrations from empty/V2 baseline, internal constraints, both nullable-scope uniques, rollback, app-role access and all snapshot fields to pass. No fake referenced owner tables.
3. Expect all states/validity boundaries, four scope combinations, both stricter-scope directions, numeric validation, NOT_CONFIGURED and unchanged usage/history to pass.
4. Per latest user instruction, do not run unrelated full regression. Run the directly changed BackendApplicationContextTest, HttpSecurityTest, HttpSecurityServletTest, ContextServletTest, CryptoOperationsTest and HealthEndpointTest plus ModuleBoundaryArchTest using --tests selectors together with E1. Then run ./gradlew build -x test --offline --console=plain for packaging only. All E1/affected tests must execute with zero failures/skips; -x test is used only for packaging after the scoped test run. Omit offline only for missing dependencies.
5. Record XML test counts/failures/skips from build/test-results/test and link build/reports/tests/test/index.html in module E1-verification.md.
6. Separate reviewer runs speckit-converge, appends gaps only to tasks.md; implement and repeat until clean.

Foreign keys/ownership, D1 transfer policy, daily enforcement and real approval/transfer integration remain unaccepted. Green provider checks do not certify those flows.
