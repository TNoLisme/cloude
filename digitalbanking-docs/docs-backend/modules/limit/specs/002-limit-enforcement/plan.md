# Implementation Plan: E2 — LIM-03/04 provider

**Branch**: `skills/phuong/limit` (existing checkout; resolver feature label 002-limit-enforcement) | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

## Summary

Implement caller-owned T2 daily enforcement and completion accounting. Deferred LIM-05 only receives an integration handoff; no production transfer/account/ledger/identity is built. Reuse E1 snapshots/config query, canonical tables and business exceptions.

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.5.7.
**Primary Dependencies**: Existing Spring JDBC/transactions, Flyway; no new library.
**Storage**: PostgreSQL 16, banking.limit_config and banking.limit_usage.
**Testing**: JUnit5, AssertJ, Testcontainers PostgreSQL; existing architecture test.
**Target Platform**: Existing modular monolith.
**Project Type**: Internal backend provider, no REST endpoint.
**Performance Goals**: Correctness first; serialize only same customer/day, 20-operation concurrency scenario with bounded local worker count; no load benchmark claimed.
**Constraints**: Caller owns READ COMMITTED T2. No REQUIRES_NEW. Account/transfer locks precede limit; audit follows. No full unrelated regression.
**Scale/Scope**: LIM-03/04, V4 grant, provider tests/docs; LIM-05 deferred by user.

## Constitution Check

Unfilled .specify/memory/constitution.md skipped. Pre/post design gates PASS: docs-common/01–11 above design/backend; AGENTS surgical changes, no fake cross-module tables in production, forward-only migrations, PostgreSQL evidence, preserve user edits. Clarify checklist 16/16. No extension hooks file.

## Design decisions

1. New LimitEnforcement API: lockAndCheck(customerId, sourceAccountId, amount) returns opaque Check with outcome, DB asOf, usageDate, customer/account usage snapshots. NOT_CONFIGURED never authorizes increment.
2. Query transaction_timestamp once, derive business date using injected businessZoneId (app.timezone); no Clock/LocalDate.now. Date and config asOf remain fixed even after waiting/midnight. Query config after customer lock, once, reusing E1 [start,end) semantics; account lock only if that snapshot includes account config.
3. Repository upserts zero customer usage via partial unique ON CONFLICT DO NOTHING then SELECT FOR UPDATE; then account if applicable. Scope lookup validates stored customer/account identity; no ownership claim without account module.
4. lockAndCheck MANDATORY with noRollbackFor BankingBusinessException, manually validate money and compare both per-txn/daily caps. Do not call E1 transactional checkPerTransaction: its default rollback behavior would prevent committing FAILED. Caller catches business rejection and records FAILED; technical exceptions roll back normally.
5. recordCompleted(Check) MANDATORY default rollback. Opaque private implementation plus transaction synchronization identity binds handle to exact caller transaction; reject expired/cross-transaction/foreign/not-configured/repeated handles. Captured amount/scope/date cannot be replaced by caller.
6. Increment customer always and configured account with SQL arithmetic, transfer_count + 1 and updated_at now(). Conditional update checks captured daily cap again to protect multiple checks before increments in one transaction; unexpected failed update is a system error, rolls back caller even if caught. Locks remain until caller completion.
7. V4 grants banking_app UPDATE(used_amount, transfer_count, updated_at) only. No V3 rewrite, no config mutation grant, no identity/date/scope/currency update grant.
8. PostgreSQL integration tests use independent transactions/connections and latches, max 2 workers locally. Test-only controlled DB timestamp method override for deterministic midnight scenarios; separate real SQL tests prove transaction_timestamp is stable and injected timezone changes date. No production clock configuration for tests.
9. Audit fail-closed integration uses real AuditRecorder within caller T2 after usage and a test-only business probe; no fake money/ledger acceptance claims. Technical and caught audit failure must rollback usage/probe.
10. Focused suite limit.* + ModuleBoundaryArchTest, then build -x test and diff check. Independent reviewer speckit-converge appends only remediation tasks; implement and repeat until scope converges.

## Project Structure

### Documentation

`specs/002-limit-enforcement/{spec,plan,research,data-model,quickstart,tasks}.md`, `contracts/limit-enforcement.md`, `checklists/requirements.md` (local ignored SDD).
Tracked evidence: `digitalbanking-docs/docs-backend/modules/limit/E2-verification.md`, additive README update.

### Source Code

`digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/LimitEnforcement.java`
`digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/application/LimitEnforcementService.java`
`digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/adapter/out/LimitJdbcRepository.java`
`digitalbanking-backend/src/main/resources/db/migration/V4__grant_limit_usage_updates.sql`
`digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitEnforcementIT.java`
`digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/LimitBusinessDateIT.java`

**Structure Decision**: Existing module layout, one service/API with repository additions; no general reservation framework.

## Complexity Tracking

No unjustified violations. Transaction-bound handle is necessary to separate check from completion after caller money work while retaining exact locks/date and preventing replay. Consumer idempotency remains deferred.
