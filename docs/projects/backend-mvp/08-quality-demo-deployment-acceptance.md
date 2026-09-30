# Phase 08 — Quality, Demo, Deployment, and Acceptance

**Status:** Detailed design ready for phase review; provider provisioning requires separate explicit approval.  
**Depends on:** Phases 01–07.  
**References:** [`00-roadmap.md`](./00-roadmap.md), baseline quality/cloud docs, root OpenAPI.

## Goal

Produce evidence that integrated Digital Banking Simulator meets approved functional, security, financial integrity, load, recovery, observability and demo criteria. Deploy only after user confirms provider, region, account access, network exposure and budget. Do not imply production banking compliance.

## Acceptance matrix

| Area | Required verification | Pass criterion |
|---|---|---|
| Contract | OpenAPI 3.1 validator; operation IDs; generated FE drift; backend contract suite | No contract drift; every implemented endpoint matches path/schema/status/header/security/media type |
| Build | Java 21 Maven package; FE typecheck/build; container image build | Independent BE/FE commands pass |
| Identity | registration OTP; counter creation; login; mandatory PIN; recovery SMS/Email; refresh/CSRF/logout | Atomic identity/account creation; session revocation; generic anti-enumeration response; no secrets leaked |
| Authorization | role matrix, IDOR and ownership tests | Customer cannot access other customer resources; staff access follows role policy |
| Money | exact VND amount; boundary tests; rollback injection | zero partial debit/credit, overdraft or float/double arithmetic |
| Idempotency | same key replay, altered payload conflict, concurrent duplicate, restart retry | exactly one logical mutation; original result recoverable for >=24h |
| Transfer concurrency | competing debits, opposite-direction transfer, duplicate confirm, block race | no negative balance; deterministic `id ASC` lock order; no persistent deadlock |
| OTP/PIN | leading zero, purpose/channel binding, expiry, attempt exhaustion, replay | one-time 6-digit OTP, 120-second TTL, max five attempts; PIN lock 15 minutes |
| Audit/risk | audit coverage/redaction; AFTER_COMMIT listener | append-only audit; risk best-effort cannot alter transfer; duplicate flags suppressed by unique key |
| Load | 50 concurrent users, 20 RPS steady for 10 minutes | report transfer/balance p95 <= 500ms target; report p50/p95/p99, errors, throughput and saturation |
| Recovery | DB unavailable, app restart, timeout after commit, migration rollback review | fail closed, retry same key, distinguish transport timeout from committed outcome |
| Observability | health, correlation, structured logs, metrics | health schema exact; no secret/OTP/PII leakage; request and DB metrics available |
| Demo | local mailbox, seeded roles/customers, operator seed, transfer paths | repeatable demo using disposable test identities and no committed credentials |
| Deployment | immutable image, forward migration, smoke, app rollback | verified only in explicitly approved target environment |

NFR targets are measured, not inferred. Stretch p95 <=350ms is not a hard gate. Availability is a single-region educational demonstration, not five-nines.

## Demo profile and data

- Provide idempotent local/demo bootstrap for one Operator, one Auditor and two Customer identities with separate phone/email; Customer accounts are active and PINs set.
- Seed script does not create extra user roles/customer/account rows on repeat and never resets passwords/PINs or re-credits balances.
- Credentials and initial PINs come from protected local environment/secret mechanism; no defaults in source, fixture committed, build log or report.
- Local mailbox only enabled for local/demo. `GET /__local/otp-mailbox` absent outside local/demo, server binds localhost when enabled, requires local dev guard, no console logging.
- Seed balances are applied through Phase 04 API/use case with idempotency, never direct DB balance edits in demo path.

## Load/reliability execution design

Use an isolated disposable PostgreSQL-compatible database and generated synthetic users/accounts. Record test tool/version, run date, machine/container CPU/memory, DB version/size, pool config, concurrency, arrival rate, duration, warmup, data volume and result. Do not point load/failure tests at shared or production systems.

Load profile: 50 concurrent simulated users, 20 requests/second for 10 minutes. Mix read/transfer operations as approved workload; report actual mix. Count expected business 4xx separately from unexpected 5xx. Report p50/p95/p99, throughput, error rate, DB pool wait/lock metrics, CPU/memory and cost assumptions. Test correctness invariants during load; any financial invariant breach fails acceptance immediately.

Fault cases on disposable environment:

1. Fail after source debit statement but before destination credit/commit; verify rollback of both balances, transfer, idempotency result and audit.
2. Commit transfer then simulate response loss; retry same key/body; verify one debit/credit and original result.
3. Stop DB dependency only in isolated test environment; verify API fails closed and health 503 with exact schema.
4. Restart application after commit; verify state and idempotency replay persist.
5. Concurrent same-key requests, competing debit requests, opposite directions, duplicate OTP confirms and block-vs-confirm race.

## Deployment design

Cloud provider remains unselected. Before any paid/external resource action, obtain explicit user approval for provider, region, account/project, budget cap, public network/domain exposure and teardown plan. Until then, complete local/container acceptance only.

When approved:

- Build immutable versioned container image in CI; use non-root runtime, minimal base image, health check and externalized config.
- Inject secrets via provider secret service/environment; never commit `.env`, credentials, Terraform state, keys or real data.
- PostgreSQL managed service with TLS/backup as provider supports; least-privilege DB principal, runtime audit table SELECT/INSERT where feasible.
- Apply forward-only Flyway migrations as controlled deployment step. No automatic table drops or down-migrations.
- Configure same-origin ingress/proxy; HTTPS. Do not expose actuator or local mailbox.
- Verify `/api/v1/health`, auth, a safe local demo flow, metrics and logs. Roll back app artifact independently; document schema compatibility and data rollback limitations.
- Cost estimate is planning only. Validate current provider calculator/quotas, network, backups, registry, domain, IPv4, transfer and monitoring before provisioning. Configure budget alert.

## Security and privacy review

- Scan dependency tree and image for known vulnerabilities; state tool/database/version and findings.
- Verify no secrets, password, PIN, OTP, refresh/access token, full account number, unnecessary PII in logs, traces, audit or CI artifacts.
- Verify local mailbox endpoint and debug settings are absent in shared/cloud profile.
- Verify HTTPS and secure cookie flags outside localhost.
- Verify CORS absent/same-origin or exact allowlist; never wildcard credentials.
- Verify rate limits use approved policies; document in-memory per-instance/restart limitations.
- Verify staff `customerId=userId` compatibility alias is documented for FE and never used as real Customer ownership ID.

## Reports and artifacts

Create acceptance evidence under `docs/projects/backend-mvp/evidence/` only when tests/deployments actually run. Each report records:

- build revision/hash and environment label (never secrets or customer details);
- exact command/tool version and exit status;
- test case/count/pass/fail/skipped and failure summary;
- load profile and measurements;
- deployment approval/resource/cost/rollback details when applicable;
- acceptance matrix outcome and unresolved criteria.

Do not fabricate report results in advance. This spec's verification record remains pending until runs occur.

## Final exit gate

- Phases 01–07 complete with linked evidence.
- All critical contract, security and financial invariants pass.
- Load and E2E evidence recorded; any miss is disclosed and assessed, not silently waived.
- Provider-specific deployment considered complete only after explicit provisioning approval and successful smoke/rollback evidence.
- Final result explicitly `ACCEPTED` or `NOT ACCEPTED`, lists limitations, open risks, tests not run and exact artifacts.

## Implementation loop

Review all phase records; stop on any critical open gate. Run local/container acceptance first. Ask before paid/external provisioning. Execute the matrix on disposable infrastructure, review logs/secrets, write reports from fresh evidence, update roadmap and phase status. Do not continue to production hardening or post-MVP work within this phase without a new approved spec.

**Verification record:** pending phase approval and actual runs.
