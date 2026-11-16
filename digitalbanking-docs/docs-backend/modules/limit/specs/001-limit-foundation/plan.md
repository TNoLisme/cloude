# Implementation Plan: E1 — Limit Data and Effective Rules

**Branch**: `phuong/limit` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)
**Input**: Clarified LIM-01 and LIM-02 specification.

## Summary

Add canonical limit_config / limit_usage tables in forward-only V3, immutable internal snapshots, a JDBC repository and a resolve/check provider. Select both scopes at an explicit instant; check every per-transaction ceiling and preserve NOT_CONFIGURED. No HTTP routes or daily enforcement.

## Technical Context

**Language/Version**: Java 21, existing Spring Boot 3.5.7.
**Primary Dependencies**: Existing Spring JDBC/transactions, Flyway, common business errors. No new libraries.
**Storage**: PostgreSQL 16 / banking schema; source 04 §4.5.1–2. FK deferred per user clarification.
**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers PostgreSQL 16, existing Gradle build and ArchUnit.
**Target Platform**: Existing Linux/JVM backend.
**Project Type**: Internal provider in modular monolith.
**Performance Goals**: One indexed query for both scopes; no new E1 latency SLA or load acceptance claim.
**Constraints**: VND; BigDecimal, max 17 integer / 2 fractional digits, no rounding. Explicit Instant/LocalDate inputs; no host time. Writes MANDATORY join caller transaction; reads SUPPORTS without locks.
**Scale/Scope**: Two tables, LIM-01/02. No GLOBAL, policy seed/max/default, approval lifecycle, scheduler or money orchestration.

## Constitution Check

Pre-research: skipped constitution checks because constitution.md is an unfilled template. Apply AGENTS.md and user's source priority.
Post-design: PASS. Minimal provider, explicit deferred integrity/policy, real PostgreSQL evidence. Full-build baseline repair is test isolation only; never weaken mandatory production audit or skip tests.

## Phase 0 — Research

See research.md. Read-only research subagent inspected source/JDBC/Testcontainers patterns and baseline failures. Technical questions resolved locally; no new framework/library. No extension hooks configured.

## Phase 1 — Design

- api: LimitConfiguration, LimitUsage and ApplicableLimits immutable records; LimitRules port with PASS/NOT_CONFIGURED.
- domain/LimitAmounts: shared scale/range/sign checks before JDBC can silently round.
- adapter/out/LimitJdbcRepository: insert snapshots and retrieve config/usage, one ACTIVE/interval query. Inserts require caller transaction and return generated ID/timestamps. No update/delete, locks, increments or activation methods.
- application/LimitRuleService: resolve and check every ceiling; excess -> existing LIMIT_EXCEEDED; invalid amount -> VALIDATION_ERROR. Read-only SUPPORTS; no new money transaction.
- Normal production @Repository/@Service wiring, requiring database. Isolate DB-free tests instead of making mandatory business components conditional.
- Test-only support/CommonTestApplication scans common with Boot TypeExcludeFilter. Point six failing datasource-disabled SpringBootTest classes at it: BackendApplicationContextTest, HttpSecurityTest, HttpSecurityServletTest, ContextServletTest, CryptoOperationsTest and HealthEndpointTest. Full PostgreSQL tests retain BackendApplication.

## Project Structure

Feature docs: specs/001-limit-foundation/{spec,plan,research,data-model,quickstart,tasks}.md, checklists/requirements.md, contracts/internal-limit-rules.md.

Source: digitalbanking-backend/src/main/resources/db/migration/V3__create_limit_tables.sql; src/main/java/com/simulatedbank/banking/limit/api/{LimitConfiguration,LimitUsage,ApplicableLimits,LimitRules}.java; domain/LimitAmounts.java; adapter/out/LimitJdbcRepository.java; application/LimitRuleService.java.

Tests: digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/{LimitPersistenceIT,LimitRuleServiceTest,LimitRulesIT}.java and support/CommonTestApplication.java plus the six test isolation annotations above.

Delivery: digitalbanking-docs/docs-backend/modules/limit/{README,E1-verification}.md and modules/_overview/06-limit.md.

**Structure Decision**: Existing package-by-feature/JDBC style. Snapshot DTOs live in api for provider consumers. No extra generic repository abstraction.

## Validation and Delivery

Tests first for persistence and rules. Test clean migration and upgrade from V2, app-role access, both partial indexes, all state/approval checks, defaults, field round-trips and rollback. Use shipped migrations, no replica tables. Test half-open interval and amount boundaries, all four scope combinations, unrelated data isolation and unchanged usage/history.

Latest user instruction (2026-11-15): do not run unrelated full regression. Run E1, six directly changed common context classes and ModuleBoundaryArchTest, then ./gradlew build -x test --offline --console=plain to verify packaging without unrelated test execution. Record results, correct E1-derived overview, leave docs-common policy unchanged. Separate reviewer runs speckit-converge; implement appended remediation and repeat until clean. FK/ownership, D1 transfer behavior and E2–E4 remain unaccepted integrations.
