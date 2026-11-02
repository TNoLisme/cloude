# Digital Banking Simulator MVP — Documentation

## Documentation map

- [Backend Feature Delivery Workflow](./backend-development-workflow.md): mandatory per-update lifecycle: spec, clarification, user approval, implementation, testing, review, handover.
- [Observability and Debugging Policy](./observability-and-debugging.md): correlation, safe structured logs, error handling, metrics and sensitive-data rules.
- [MVP baseline documentation](./baseline/README.md): approved MVP-wide requirements and decisions.
- [Backend MVP phase roadmap](./projects/backend-mvp/00-roadmap.md): ordered phase specs. Review and approve each phase before its implementation.
- [Frontend MVP phase roadmap](./projects/frontend-mvp/00-roadmap.md): approved UI direction, route/role mapping, eight delivery phases, API integration and acceptance plans.
- [Backend acceptance gaps and team plan](./projects/backend-mvp/09-acceptance-gap-and-team-plan.md): current P0 gaps, ownership split, local-first cloud decision and acceptance checklist.
- [Detailed team task handover](./projects/backend-mvp/10-detailed-team-task-handover.md): per-person tasks, tests, acceptance, FE handoff and transfer protocol.
- [BE-1 work order](./projects/backend-mvp/11-be-1-identity-security-onboarding.md): Identity, security, onboarding, security scan and FE handoff.
- [BE-2 work order](./projects/backend-mvp/12-be-2-account-operator-db-support.md): Account, Operator, seed, locks, DB support and FE handoff.
- [BE-3 work order](./projects/backend-mvp/13-be-3-transfer-financial-consistency.md): Transfer rollback, concurrency, idempotency and financial acceptance.
- [BE-4 work order](./projects/backend-mvp/14-be-4-audit-risk-integration.md): Audit, risk, evidence, merge gate and final acceptance.
- [FE/BE work order](./projects/backend-mvp/15-fe-be-contract-e2e-load-ci.md): Contract, browser E2E, k6, CI and routing acceptance.
- [Local Docker Setup](./local-docker-setup.md): Compose startup for backend and PostgreSQL, FE URLs, verification, safe stop/restart and common failures.

Start from the workflow before implementing backend changes. Use baseline docs as shared context; each phase spec under `projects/backend-mvp/` defines one full development loop and requires its own approval before coding.
