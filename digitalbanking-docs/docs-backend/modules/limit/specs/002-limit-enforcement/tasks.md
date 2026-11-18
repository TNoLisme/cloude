# Tasks: E2 — LIM-03/04 provider

**Input**: spec.md, plan.md, research.md, data-model.md, contracts/limit-enforcement.md.
**Tests**: PostgreSQL integration required by user; no full unrelated regression.

## Phase 1: Setup

- [x] T001 Confirm baseline 71 limit tests pass and source hierarchy in digitalbanking-docs/docs-backend/modules/limit/specs/002-limit-enforcement/research.md.
- [x] T002 Complete clarify, plan, contract and checklist in digitalbanking-docs/docs-backend/modules/limit/specs/002-limit-enforcement/.

## Phase 2: Foundation

- [x] T003 Define opaque transaction-bound Check contract (PASS/NOT_CONFIGURED, asOf/date and usage snapshots) in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/LimitEnforcement.java.
- [x] T004 Add forward-only column UPDATE grant (used_amount, transfer_count, updated_at only) in digitalbanking-backend/src/main/resources/db/migration/V4__grant_limit_usage_updates.sql; retain scope/date/config protection.

## Phase 3: User Story 1 — Locked daily enforcement

Independent test: two independent caller transactions compete for remaining capacity; zero duplicate counters or over-capacity successes.

- [x] T005 [US1] Write PostgreSQL check tests for both/single/no scopes, equality/excess, invalid amount, config refreshed after early read, and first-row concurrency in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java; observe missing implementation before code.
- [x] T006 [US1] Add DB transaction start query and insert-on-conflict/SELECT FOR UPDATE customer→account methods in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java.
- [x] T007 [US1] Implement MANDATORY writable READ COMMITTED lockAndCheck, money validation positive <=17 integer digits/2 decimals, current configs and noRollbackFor business rejection in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java.
- [x] T008 [US1] Prove business-date timezone and deterministic midnight/transaction-crossing behavior, plus real DB timestamp stability in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitBusinessDateIT.java.

## Phase 4: User Story 2 — Atomic completion accounting

Independent test: caller commit increments amount/count; caught business rejection does not poison transaction; technical/audit error rolls back usage and caller probe.

- [x] T009 [US2] Write PostgreSQL completion/rollback/real AuditRecorder failure, same-transaction handle, duplicate/foreign/expired/not-configured handle and multiple-check overspend tests in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java.
- [x] T010 [US2] Add atomic conditional increment SQL using captured row identity/date/amount and daily cap in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java.
- [x] T011 [US2] Implement recordCompleted with transaction marker and consumed guards, default rollback, customer/account increments in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java.
- [x] T012 [US2] Prove 20 operations with bounded 2-thread independent transactions, same/different accounts, existing/new counter races, lock held through check and increment, and privilege restriction in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java.

## Phase 5: User Story 3 — Deferred consumer handoff

- [x] T013 [US3] Record integration calling order, absent dependencies, future transfer retry/recovery acceptance and E4 D4 boundary in digitalbanking-docs/docs-backend/modules/limit/E2-verification.md and specs/002-limit-enforcement/contracts/limit-enforcement.md; do not implement deferred LIM-05.

## Phase 6: Validation and review

- [x] T014 Run focused limit + module-boundary suite and build -x test, record exact counts/commands in digitalbanking-docs/docs-backend/modules/limit/E2-verification.md; no full unrelated regression.
- [x] T015 Add E2 progress/evidence links preserving existing text in digitalbanking-docs/docs-backend/modules/limit/README.md; run git diff --check.
- [x] T016 Request independent subagent speckit-converge against digitalbanking-docs/docs-backend/modules/limit/specs/002-limit-enforcement/{spec,plan,tasks}.md; implement appended tasks and loop until converged.

## Dependencies and execution

Foundation → US1 → US2 → US3 handoff → validation/reviewer. Test cases precede matching implementation. US1 and US2 share service/test files and run sequentially. Independent documentation and date-test authoring could proceed in parallel after contract, but implementation stays with one agent; only research and final review use subagents. MVP is US1 provider; delivered scope includes US2. Every task uses checklist ID and concrete paths.

## Phase 7: Convergence

- [x] T017 Reject checked handles once caller transaction completion begins in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java, and add a PostgreSQL regression in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java that calls recordCompleted from TransactionSynchronization.afterCommit and proves it rejects without mutating usage. Spring keeps actualTransactionActive and synchronizations during afterCommit after the DB commit; synchronization membership alone currently accepts the expired handle, breaking atomic accounting. Trace: FR-E2-005, FR-E2-006, plan decision 5 / T011 (partial, HIGH).

## Phase 8: Convergence

- [x] T018 Require the bound physical caller transaction to still be active at both enforcement entry points in digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java using the existing JPA transaction stack, and add a PostgreSQL regression in digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java for a first lockAndCheck call from afterCommit (no prior provider call in the transaction body), asserting rejection before counter creation/increment. T017 expires existing handles but a fresh afterCommit check currently passes stale Spring flags and creates a new unexpired handle after the original DB commit. Preserve normal caller-owned T2 behavior and avoid a generic transaction infrastructure abstraction or new dependency. Trace: FR-E2-005, FR-E2-006, plan active caller T2 / decisions 4–5 (partial, HIGH).
