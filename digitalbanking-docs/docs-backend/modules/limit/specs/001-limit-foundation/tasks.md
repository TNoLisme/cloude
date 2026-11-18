# Tasks: E1 — Limit Data and Effective Rules

**Input**: spec.md, plan.md, research.md, data-model.md and contracts/internal-limit-rules.md.
**Tests**: Required by user; real PostgreSQL for constraints and transactions. Paths below are repository-relative.

## Phase 1: Setup

- [x] T001 Record source priority, E1 scope and two user decisions in specs/001-limit-foundation/spec.md and checklists/requirements.md.
- [x] T002 Research migration baseline and capture 448-test/17-failure preimplementation build in specs/001-limit-foundation/research.md.

## Phase 2: Foundation

- [x] T003 Restore intended datasource-free scope of six existing tests through digitalbanking-backend/src/test/java/com/simulatedbank/banking/support/CommonTestApplication.java and the six test annotations named in plan.md; retain full application DB coverage and all assertions.

## Phase 3: US1 — Persist trustworthy data (P1)

**Independent test**: PostgreSQL accepts valid snapshots and defaults, rejects invalid values/states/duplicate scopes, preserves versions and rolls back caller writes. Clean install and V2 upgrade succeed; no foreign module tables exist.

- [x] T004 [US1] Write PostgreSQL constraint/migration/rollback tests first in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitPersistenceIT.java using shipped Flyway migrations, covering both nullable-scope partial indexes, four states, approval attribution, defaults, all fields and staged FK boundary.
- [x] T005 [US1] Add digitalbanking-backend/src/main/resources/db/migration/V3__create_limit_tables.sql with source 04 §4.5.1–2 fields; amounts `> 0`, per-transaction `<= daily`, VND, version `> 0`, effective end `> start`; four states and maker/checker checks; usage finite `>= 0` / count `>= 0`; exact source partial unique/index names. No owner-table replicas or FK claims.
- [x] T006 [US1] Add immutable snapshots digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/LimitConfiguration.java and LimitUsage.java, including every source field; add domain/LimitAmounts.java checking positivity/nonnegativity, max `99999999999999999.99`, at most 2 fractional digits before JDBC rounding.
- [x] T007 [US1] Add digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java with MANDATORY append-only configuration/usage inserts, generated identities/default timestamps returned, ID validation, config-by-ID and usage-by-scope/date reads. No mutation/deletion or daily enforcement.
- [x] T008 [US1] Run LimitPersistenceIT; record command and result in digitalbanking-docs/docs-backend/modules/limit/E1-verification.md.

## Phase 4: US2 — Resolve and check limits (P1)

**Independent test**: Resolve exactly the ACTIVE half-open interval configs for each pair; equal amount passes, excess in either scope fails and absence is NOT_CONFIGURED. Reads change no data.

- [x] T009 [P] [US2] Write rule unit tests in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitRuleServiceTest.java for all combinations, both stricter directions, equality/excess, invalid money and explicit NOT_CONFIGURED.
- [x] T010 [P] [US2] Write real PostgreSQL selection/side-effect tests in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitRulesIT.java for all states/time boundaries, customer/account isolation, configuration/usage independence and Spring provider wiring.
- [x] T011 [US2] Add internal contract digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/LimitRules.java and ApplicableLimits.java with immutable items and Optional minimum, PASS/NOT_CONFIGURED outcomes and explicit instant.
- [x] T012 [US2] Add ACTIVE pair/time selection to digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java and implement application/LimitRuleService.java with read-only SUPPORTS, VALIDATION_ERROR/LIMIT_EXCEEDED and no usage mutation.
- [x] T013 [US2] Run all E1 tests; record results in digitalbanking-docs/docs-backend/modules/limit/E1-verification.md.

## Phase 5: Delivery and Verification

- [x] T014 Update digitalbanking-docs/docs-backend/modules/limit/README.md and E1-verification.md with contracts, traceability, test evidence and deferred FK/ownership/D1/E2–E4. Correct E1-derived schema/scope claims in modules/_overview/06-limit.md without inventing future implementation.
- [x] T015 Run scoped E1/directly affected tests, ./gradlew build -x test --offline --console=plain and whitespace checks per latest user instruction (superseding the initial full-regression gate); record exact counts/failures/skips and baseline test-isolation repair in digitalbanking-docs/docs-backend/modules/limit/E1-verification.md.
- [x] T016 Have a separate reviewer run speckit-converge against specs/001-limit-foundation/{spec,plan,tasks}.md; implement appended tasks and repeat until converged, recording review result in digitalbanking-docs/docs-backend/modules/limit/E1-verification.md.

## Dependencies and strategy

T001–T002 → T003 → US1 (T004–T008) → US2 (T009–T013) → delivery T014–T016. T009 and T010 may be authored independently after US1. All implementation is incremental within this epic; US1 is the persistence MVP, US2 completes E1. Verification covers provider only, never real transfer/approval integration.

## Phase 6: Convergence

- [x] T017 Fix digitalbanking-backend/src/test/java/com/simulatedbank/banking/support/CommonTestApplication.java so it is a standalone common-only SpringBootTest root while remaining excluded from full application component scanning; @TestConfiguration currently causes BackendApplication rediscovery and restores missing-JdbcTemplate failures. Verify the six directly changed context test classes plus E1, then package via assemble/build -x test without running unrelated full regression, per latest user instruction; update digitalbanking-docs/docs-backend/modules/limit/E1-verification.md with current results per FR-009, SC-005, plan: common-test isolation, T003/T015 (partial).
- [x] T018 Preserve half-open effective selection for nanosecond Instant inputs in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java: JDBC currently rounds start-minus-1ns to start and end-minus-1ns to end. Add real PostgreSQL regressions in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitRulesIT.java for just-before/start/just-after and just-before/end/just-after at submicrosecond offsets, retaining original asOf metadata and no side effects; run affected E1 tests and update digitalbanking-docs/docs-backend/modules/limit/E1-verification.md per FR-005, SC-002, US2/AC1, spec: half-open interval edge case, T010/T012 (partial).
