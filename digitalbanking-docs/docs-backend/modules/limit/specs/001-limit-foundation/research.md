# E1 Research

## Persistence

Decision: V3 after V1 schema/V2 audit, JdbcTemplate and immutable snapshots following local modules. Adopt source 04 §4.5.1–2 fields/checks/partial indexes plus per-transaction ≤ daily from 07 §8.5.2. Validate decimal precision/range before PostgreSQL rounds NUMERIC(19,2); reject nonfinite database numerics.
Rationale: Existing dependencies suffice; real PostgreSQL proves nullable-scope constraints.
Alternatives: JPA and GLOBAL/backend-derived schema rejected as unnecessary or inconsistent.

## Policy and dependencies

Decision: User chose limit-only tables, deferred FK/ownership integration and distinct NOT_CONFIGURED. No fake owner tables or invented max/default. D1 transfer enforcement remains for E2.
Rationale: Independently useful provider with explicit acceptance boundary; source 08 permits later cross-domain constraints.
Alternatives: Four full prerequisite owner schemas or immediate fail-closed transfer policy were not selected.

## Selection and transactions

Decision: One customer/pair-constrained ACTIVE half-open interval query, customer first then account. Check all ceilings. Inserts MANDATORY; reads SUPPORTS. Explicit dates/instants.
Rationale: Account overrides cannot loosen customer limits; E1 cannot certify later T2 enforcement.
Alternatives: Historical fallback, hidden timezone, usage enforcement and activation scheduler exceed E1.

## Full-build baseline

Before E1 code, full build compiled but ran 448 tests with 17 failures, all caused by JdbcTemplate missing when six datasource-disabled common tests scan AuditRecorder (and later fraud/notification beans). XML captured at /tmp/limit-e1-baseline-results.
Decision: Test-only CommonTestApplication with common-only component scan and Boot TypeExcludeFilter; full DB application tests retain BackendApplication.
Rationale: Restore intended test scope; do not disable mandatory production audit or skip tests.
Read-only limit_research subagent confirmed these local patterns. No unresolved technical question or extension hooks.
