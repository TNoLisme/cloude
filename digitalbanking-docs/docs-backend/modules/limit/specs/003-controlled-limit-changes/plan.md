# Implementation Plan: Controlled limit changes (LIM-07/08)

**Branch**: `phuong/limit` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

## Summary

Provide internal prepare/approve/reject operations on limit_config only. Reuse E1 snapshots/validation and E2 enforcement; admin endpoints, approval persistence, identity/account ownership and actual transfer integration are deferred by user. Configuration lifecycle has no usage writes.

## Technical Context

**Language/Version**: Java 21, existing Spring Boot/Gradle baseline.
**Primary Dependencies**: Spring JDBC, transactions/JPA transaction guard, Spring Environment for explicit policy configuration. No new dependency.
**Storage**: PostgreSQL 16; V5 migration leaves V3/V4 checksums unchanged.
**Testing**: JUnit, SpringBootTest, Testcontainers PostgreSQL, application DB role, real AuditRecorder; 2 worker threads maximum.
**Target Platform**: Existing demo JVM app.
**Project Type**: Modular monolith internal provider.
**Performance Goals**: Existing 20 RPS demo; no new benchmark or global load suite.
**Constraints**: READ COMMITTED writable physical caller transaction (MANDATORY), no admin-owned data writes, no usage mutations, no production placeholders, no policy default.
**Scale/Scope**: LIM-07/08 only; relevant limit/module-boundary tests and package build.

## Constitution Check

Constitution is an unfilled template; skip template principles. Apply AGENTS.md surgical scope, simplicity and verifiable goals. Authority docs-common/01–11 > design > backend, with four explicit user clarifications recorded in spec. Gates before/after design: PASS; no new module/dependency or scope expansion. No extension hooks registered.

## Project Structure

Feature docs in this directory: spec, plan, research, data-model, contracts, quickstart, tasks, quality checklist.

Source touchpoints under digitalbanking-backend/:
- src/main/java/com/simulatedbank/banking/limit/api/LimitChanges.java (interface with proposal record).
- src/main/java/com/simulatedbank/banking/limit/application/LimitChangeService.java (validation/lifecycle) and LimitChangePolicy.java (explicit policy parsing/revalidation).
- src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java (locks, scope/version queries, conditional lifecycle updates).
- src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java (acquire shared customer advisory lock before usage mutex).
- src/main/resources/db/migration/V5__enable_limit_configuration_decisions.sql (non-null approval uniqueness and lifecycle-only column grants).
- src/test/java/com/simulatedbank/banking/limit/LimitChangesIT.java and LimitChangePolicyTest.java.
- Existing LimitPersistenceIT migration-upgrade expectation (one extra migration), and LimitRulesIT fixture uses distinct approval IDs per version after V5 uniqueness.
- Module README, overview, E4-verification and backend deployment configuration (explicit disabled-by-default policy).

## Design Decisions

1. Internal LimitChanges accepts trusted caller scope/maker/approval IDs, VND amounts and optional effectiveFrom; returns persisted LimitConfiguration. Approval owns external auth/approval/audit and verifies references before invocation.
2. Prepare validates values, policy and non-future effectiveFrom, allocates max scope version + 1 under transaction advisory lock, inserts PENDING_APPROVAL, preserves all usage/config history. Duplicate approval fails without creating a second version.
3. All mutation operations require active physical writable JPA transaction and READ COMMITTED, including rejection of calls from afterCommit. No REQUIRES_NEW/independent money transaction. Business rejection is noRollbackFor BankingBusinessException so caller can record a denial; technical SQL/lifecycle failures roll back.
4. Lock order is customer advisory lock, then account advisory lock when applicable, then proposal row for decision. E2 takes the same customer advisory lock before its existing customer/account usage locks, so activation and T2 cannot race. Keys use separate limit customer/account namespace. No usage row is inserted by changes.
5. Account scope query is by account ID (canonical uniqueness) and rejects inconsistent existing customer attribution; actual existence/ownership/FKs remain staged. Decisions re-read proposal under locks and accept only PENDING_APPROVAL; self decision ROLE_CONFLICT, unknown linked proposal APPROVAL_NOT_FOUND, terminal APPROVAL_ALREADY_DECIDED.
6. Policy properties app.limit.policy.max-per-transaction-vnd and max-daily-vnd have no built-in default. Read both for every prepare/approve. Absent/invalid/incomplete policy SERVICE_UNAVAILABLE; valid amounts above maxima LIMIT_OUT_OF_POLICY; malformed money/IDs/currency/relationships/future dates VALIDATION_ERROR. Reject does not require policy.
7. Decision uses DB clock_timestamp after locks for effectiveFrom/approvedAt and the business day via configured ZoneId. Recheck future-effective constraint; supersede old ACTIVE then activate pending in one transaction. Reject leaves config approved_by/approved_at NULL (canonical DB checks), with checker attribution delegated to approval/audit.
8. D4: no currently effective account configuration + nonzero customer used amount or count for decision day => INVALID_STATE_TRANSITION, leave pending. A later unused day may approve. Historical expired account versions do not bypass D4. No reset/backfill. Existing effective account tracking can change limit after usage without reset.
9. V5 adds unique non-null approval_id and grants only lifecycle status, effective interval, checker and timestamps; immutable scope/amount/version/approval/maker plus hard-delete stay protected. Provider serializes new scope versions; do not rewrite old migrations or legacy history to add version uniqueness.
10. Audit stays caller-owned. Integration harness invokes real AuditRecorder in same transaction; audit validation/DB failure (even caught by caller) must roll back prepare/approve/reject. Tests never create fake production approval/customer/identity/transfer tables.
11. E2 keeps its fixed transaction-start evaluation instant/business date. A transaction started before activation and waiting for the shared lock can return NOT_CONFIGURED if new ACTIVE starts after its instant; it fails closed, never permits spend, and is not claimed as uninterrupted in-flight availability. After the common lock, detect any currently effective ACTIVE scope starting after that fixed instant; return NOT_CONFIGURED for the whole check, even when another older scope still applies, so a new account scope cannot be silently skipped. A new transaction observes the approved configuration.
12. Verify all lifecycle transitions, first activation/competition, policy revalidation/missing policy, no usage changes, D4 race with E2 and midnight/date behavior, unchanged DB permissions. Build and only scoped regression; independent converge loop must finish clean.

## Implementation Strategy

Prepare contracts and PostgreSQL regressions first; demonstrate missing-provider red, implement prepare then decision transitions, integrate shared enforcement lock, run scoped tests/package, update traceable evidence and loop independent reviewer until zero findings. No full unrelated regression.
