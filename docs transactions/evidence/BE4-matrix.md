# BE-B P0 Evidence Matrix

## Status

IMPLEMENTED — evidence rows below reflect checked-in outputs and fresh local verification. Overall acceptance remains `NOT_ACCEPTED` while blocker rows remain.

| Requirement | Owner | Command / evidence | Status | Gap | Next |
|---|---|---|---|---|---|
| BE-B evidence template and PR merge gate | BE-B | `_TEMPLATE.md` | IMPLEMENTED | Gate not yet applied to other owners' PRs | Share template and enforce on review |
| Disposable PostgreSQL support safety | BE-B | `DisposablePostgresTestSupportTest`: 3 passed | VERIFIED | Existing PG tests not migrated from per-class containers | BE-A/BE-3 consume helper on new PG tests; BE-B coordinate legacy split |
| Audit read roles, filter, cursor | BE-B | `ListAuditEventsServiceTest`, `AuditControllerMvcTest` | VERIFIED | Full PostgreSQL pagination/filter evidence pending | Add disposable PG query coverage if current owner gate requires it |
| Audit insert and redaction contract | BE-B + module owners | `JdbcAuditWriterTest`, `OnboardingAuditTest` | VERIFIED for these tests | Complete event inventory from BE-A/BE-3 pending | Collect owner evidence and confirm every required event |
| Risk exact threshold and frequency | BE-B | `RiskEvaluationServiceTest`: 3 passed | VERIFIED | Production rule depends on completed-count query; current query inspected, no DB boundary matrix yet | Add PostgreSQL query tests for outgoing, incoming, pending, failed and time boundaries |
| Risk duplicate rule flag | BE-B | `RiskFlagRepositoryPostgresTest`: 1 passed, V4 DDL applied | VERIFIED | None for unique insert proof | Keep evidence linked |
| Targeted audit/risk suite | BE-B | `*Audit*Test,*Risk*Test,DisposablePostgresTestSupportTest,RiskFlagRepositoryPostgresTest`: 16 passed | VERIFIED | This is not full backend suite | Keep exact report with `BE4-audit-risk.md` |
| DB outage and restored health | BE-B | `DatabaseFailureRecoveryTest`: 3 passed using Toxiproxy 2.5.0; `HealthControllerTest` separately proves HTTP 503 safe shape | VERIFIED for targeted tests | Direct `DatabaseHealthIndicator` tested with Toxiproxy; combined HTTP-over-live-outage not in single test | Add only if final review requires combined live-outage HTTP assertion |
| Same-key transfer persisted replay | BE-3 behavior / BE-B harness | `DatabaseFailureRecoveryTest`: 3 passed | VERIFIED for in-JVM context restart | OS process kill/restart and multi-node container failover not exercised; verified as Spring Boot context restart on persisted PostgreSQL | Clearly scoped as application context restart on persisted state |
| 100-account k6 seed + financial invariant | BE-B | `scripts/seed-k6-accounts.sql`, `queries/verify-financial-invariant.sql`, `K6SeedAndFinancialInvariantPostgresTest`: 2 passed | VERIFIED on disposable DB (pre-load) | Verified on seed and baseline state; post-workload invariant query execution remains pending FE/BE k6 run | FE/BE execute k6 load test and capture post-load invariant query output |
| Container vulnerability scan | BE-B | `BE4-container-scan.md` + raw Trivy JSON | NOT_ACCEPTED (zero-critical policy) | Hardened: OS 100% clean (Alpine); Tomcat, Postgres, Jackson clean. Total dropped 75.4% (126 to 31). Remaining 3 CRITICAL, 4 HIGH await upstream Spring 7.x GA | Upstream framework tracking |
| OpenAPI contract validator | BE-B | `python be/scripts/validate-openapi.py` | VERIFIED | None; validator reported 31 operations | Rerun after approved contract changes |
| FE contract/typecheck/tests/build | FE/BE | `npm run api:check`, `npm run typecheck`, `npm test`, `npm run build` | VERIFIED | Bundle warning: main JS chunk 1,212.01 kB; not acceptance failure by itself | FE/BE assess code-splitting separately |
| Backend full suite and final owner evidence | All owners / BE-B consolidates | `mvn -q test`: 165 passed, 0 failures, 0 errors across 62 reports | VERIFIED for current backend code | Cross-owner final evidence (BE-A, BE-3, FE-BE) not consolidated | Ready for cross-owner integration review; not ready for final release acceptance |

## Overall disposition

`NOT_ACCEPTED` — critical/high container findings remain (3 CRITICAL, 4 HIGH in upstream Spring), post-workload k6 invariant run is pending, and cross-owner P0 evidence integration (BE-A/BE-3/FE-BE) is pending. All BE-B owned test harnesses, disposable DB safety, application context restart replay, and seed/baseline invariant verifications are `VERIFIED` within their defined scopes. Do not convert design plans into verified evidence.
