# Quality, Security & Cloud Requirements

## 1. Purpose

Define measurable quality gates and required demonstrations for Digital Banking Simulator MVP. These are simulator controls, not a claim of regulatory/production banking compliance. Numeric targets marked **proposed baseline** must be load-tested and adjusted to actual team environment before final defense.

## 2. Non-functional requirements

| Area | Requirement | Proposed MVP acceptance target |
|---|---|---|
| **Performance** | Measure public API latency under documented workload. | MVP acceptance: transfer and balance query p95 <= 500 ms at 50 concurrent simulated users and 20 RPS steady load on documented environment. Stretch target: transfer p95 <= 350 ms after baseline measurement. Report p50/p95/p99 and machine/database sizing. |
| Throughput | Define reproducible peak test. | Sustain 20 RPS for 10 minutes without correctness failures; report achieved throughput and saturation point. |
| Availability | Demonstrate health and restart behavior, not five-nines availability. | Single-region MVP deployment; health/readiness checks recover after application restart. State deployment uptime limitations. |
| Consistency | Prevent partial transfer and overdraft. | Every committed transfer has matching debit/credit; any rejected/rolled-back transfer changes neither balance. |
| Idempotency | Retry-safe transfer. | Same key+payload produces one logical transfer under sequential and concurrent retries; key reuse with different payload is rejected. |
| Security | Authenticate, authorize and protect sensitive data. | Automated negative tests cover role failure, IDOR/ownership failure, invalid state and credential non-disclosure. HTTPS enabled at deployed ingress. |
| Auditability | Record critical actions. | Every customer/account creation, seed balance, transfer, PIN change and password recovery has actor/action/time/outcome/correlation reference; no password/token/secret stored in audit payload. |
| Recovery | Demonstrate failure and recovery. | Kill/restart application during request test; inspect persisted state and retry using idempotency key. Report whether request committed or rolled back; never infer outcome from client timeout alone. |
| Observability | Correlate requests and measure behavior. | Structured logs include correlation ID; dashboard/report shows request count, latency, error rate, DB connection/pool health and transfer outcome counts. |
| Cost | Estimate deployment cost. | Planning estimate: approximately $45-$50/month for listed AWS resources, excluding unpriced networking, backups, image registry, domain and overage costs. See assumptions below. |

### SLI / SLO and Error Budget Baseline

| Service Indicator (SLI) | Objective (SLO) | Measurement Method | Error Budget & Action |
|---|---|---|---|
| **API Availability** | Proposed target: >= 99.5% valid requests over the documented load test | Count successful responses and expected business 4xx separately from unexpected 5xx/availability failures. Publish numerator, denominator and exclusions. | Proposed error budget: <= 0.5% unexpected 5xx; trigger investigation if 5xx rate > 1% for 1 minute. |
| **Transfer Latency** | MVP acceptance: p95 <= 500 ms at 20 RPS; stretch target p95 <= 350 ms after baseline measurement; p99 is reported, not a hard gate | Measure from API ingress to response at documented workload | If p95 > 500 ms, inspect database pool contention and row-lock wait time. |
| **Financial Correctness** | $100\%$ invariant preservation | Zero dirty reads, zero lost updates, zero negative balances under concurrent load | Zero tolerance. Any invariant breach fails the acceptance test immediately. |
| **Idempotency Replay** | $100\%$ deduplication | Zero duplicate debit/credit on concurrent or sequential duplicate requests | Zero tolerance. Must return identical original logical response. |

Targets are educational MVP goals, not production SLO commitments. Record machine shape, database size, test tool, test duration, seed data, concurrency profile and run date in load-test report.

## 3. Financial integrity controls

### Amount representation

- Use decimal arithmetic (`BigDecimal` in Java and fixed-precision `NUMERIC`/`DECIMAL` in PostgreSQL).
- Never use `float`/`double` for money.
- MVP uses simulated VND with scale 0 (integer amounts); JSON APIs serialize amounts as integer strings (e.g. `"2000"`, `"50000"`, `"10000000"`). Transfer amount minimum is `2,000` VNĐ, transfer maximum is `10,000,000` VNĐ; Operator seed maximum is `100,000,000` VNĐ (minimum > 0).
- Reject amount < 2,000 VNĐ for transfers, amount <= 0 for seed, amounts exceeding respective maximums, excessive scale, unsupported currency and overflow before mutation.
- MVP supports simulated `VND` only. Adding currencies requires currency-specific minor-unit rules and an OpenAPI contract update.

### Atomicity and concurrency

- Transfer debit, credit, transfer record, idempotency outcome and audit facts commit atomically in one PostgreSQL transaction. Risk evaluation runs after commit; no outbox table or broker is part of MVP.
- Lock involved accounts in deterministic ascending ID order to avoid opposite-direction transfer deadlocks.
- Validate available balance after acquiring lock.
- Add database constraints for non-negative balances where compatible with account states, unique idempotency scope/key, unique default account per customer, and valid status/value constraints.
- Identity constraints on `customers` (and login identity table if separate): `phone VARCHAR(10) NOT NULL UNIQUE` with check `phone ~ '^0[3-9][0-9]{8}$'`, `email VARCHAR(254) NOT NULL UNIQUE` stored normalized (trim + lowercase; or unique index on `lower(email)`). Map unique-violation on `phone`/`email` to `409 PHONE_ALREADY_REGISTERED` / `409 EMAIL_ALREADY_REGISTERED`; never rely only on a pre-insert existence check (race condition).
- Do not make network calls to a broker, email provider or another service while holding account-row locks or inside the money DB transaction. MVP does not implement a message broker.
- Integration test concurrency against PostgreSQL-compatible behavior. H2-only tests do not prove PostgreSQL lock semantics.

### Idempotency

- Require client-generated `Idempotency-Key` for transfer create.
- Scope by actor + operation; store payload hash and original response/transfer reference.
- Enforce uniqueness in database, not only in application memory.
- Same key/same canonical payload replays original logical result.
- Same key/different payload returns conflict and changes no balance.
- Test concurrent duplicate requests, application crash after commit/before response, and retry after client timeout.
- Define retention period and cleanup policy before launch; do not delete keys while related transfers may reasonably be retried.

### Failure/recovery scenarios to demonstrate

1. **Client timeout after commit**: transfer may have committed. Retry same idempotency key; one debit/credit only, original result returned.
2. **Failure before commit**: inject exception after debit attempt but before credit/commit. Transaction rolls back; both balances unchanged.
3. **Concurrent debits**: send requests whose combined amount exceeds balance. At most balance-supported operations commit; balance never negative.
4. **Opposite-direction transfers**: concurrent A→B and B→A requests complete or fail within bounded timeout without persistent deadlock.
5. **Application restart**: restart service after request; verify persisted transaction and balances, then retry by key.
6. **DB unavailable**: API fails closed with sanitized retriable error; it must not report successful transfer or update browser state as success.

## 4. Security requirements

### Identity and access

- Hash passwords with a modern adaptive password hashing algorithm supported by selected Spring Security version; never store plaintext or reversible passwords.
- Use least-privilege roles: `CUSTOMER`, `OPERATOR`, `AUDITOR`, `ADMIN` as required by actual use cases.
- Enforce object-level ownership on every customer resource lookup and mutation.
- Operator/Auditor/Admin endpoints are protected by explicit role checks, not hidden UI routes.
- Keep admin privilege narrow; role changes and privileged actions are audited.
- Protect registration/login from brute force with configurable rate limits and generic authentication errors where needed.
- Login identifier is `phone`; wrong phone or wrong password returns the same `401 CREDENTIALS_INVALID`.

### OTP, PIN and account recovery

- OTP: 6 random digits from a CSPRNG, TTL `120` seconds, single use, stored only as hash, bound to `identifier + channel + purpose` (`REGISTRATION`, `OPERATOR_CREATE_CUSTOMER`, `RECOVERY`, `PIN_RESET`, `TRANSFER_STEP_UP`). Max 5 verify attempts, then invalidate. Resend/initiate rate-limited per identifier and per IP.
- OTP delivery goes through an `OtpSender` port (SMS / Email adapters). MVP uses a simulated adapter; OTP values never appear in application logs, traces, audit or API responses in shared/cloud environments.
- Recovery anti-enumeration: `/auth/recover/initiate` returns identical status, body and timing for registered and unregistered identifiers (OTP dispatched asynchronously only on match); `/auth/recover/confirm` with unregistered identifier returns `400 OTP_INVALID`, same as wrong OTP. No `404` on recovery endpoints.
- Accepted risk: registration returns `409 PHONE_ALREADY_REGISTERED` / `EMAIL_ALREADY_REGISTERED`, enabling slow existence checks. Mitigated by strict rate limit on `/auth/register/send-otp` (per phone + per IP) and OTP gate before `/auth/register`. Documented as accepted risk for MVP.
- Recovery channel: `SMS` only to registered phone, `EMAIL` only to registered email. Successful recovery hashes the new password and **revokes all refresh tokens of the user in the same DB transaction** (force logout on every device).
- Transaction PIN: 6 digits, stored with adaptive hash separate from password, never returned or logged. 5 consecutive failures lock PIN for 15 minutes (`403 PIN_LOCKED`). Operators cannot view or set a customer PIN.

### Transport, secrets and data

- Enforce HTTPS in cloud deployment; local development can use HTTP on isolated localhost only.
- Store secrets in environment injection/secret manager; commit only `.env.example` with placeholders.
- Rotate development/demo credentials before any shared deployment; never use reference repo secrets.
- Encrypt managed database and backups at rest using provider capability where available; document key ownership and cost/limitations.
- Exclude password, access/refresh token, secret, full auth header and unnecessary personal data from logs/traces/audit.
- Define data retention and deletion behavior for demo accounts before external/public deployment.
- Use parameterized persistence APIs; validate all external inputs at API boundary.

### STRIDE Threat Model & Security Controls

| Category | Threat Scenario | Architectural Mitigation | Verification / Test |
|---|---|---|---|
| **S**poofing | Attacker forges JWT or actor identity in request header/body | JWT validated per request via cryptographic signature; actor/role extracted strictly from verified claims, never from client body. | Negative auth test with expired, tampered, or forged signature returns `401`. |
| **S**poofing | Attacker takes over account via password recovery or brute-forces OTP/PIN | OTP 6 digits, TTL 120s, single use, max 5 attempts, rate limit; recovery revokes all refresh tokens; PIN lock after 5 failures. | Expired/reused OTP returns `400 OTP_INVALID`; old refresh token after recovery returns `401`; 6th wrong PIN returns `403 PIN_LOCKED`. |
| **T**ampering | Attacker tampers transfer parameters or reuses idempotency key with altered payload | Cryptographic SHA-256 payload hash stored with Idempotency Key; DB transactional checks; HTTPS in transit. | Replay test with altered payload returns `409 IDEMPOTENCY_KEY_REUSED`; balances untouched. |
| **R**epudiation | User denies initiating transfer or Operator denies creating a customer at counter | Append-only audit trail records `actorId`, `targetId`, `timestamp`, `outcome` and `correlationId`; transfer requires PIN (+ OTP above threshold). | Audit query confirms transfer and counter-creation records with matching correlation references. |
| **I**nformation Disclosure | IDOR to view other accounts, or sensitive secrets leaked into log streams | Object-level ownership validation (returns concealed `404`); account masking (`••••4821`); sensitive fields stripped from logs/traces. | IDOR test asserts Customer A cannot query Customer B account; log scanner verifies zero token/secret leaks. |
| **I**nformation Disclosure | Account enumeration via password recovery or operator lookup | Recovery always generic `200` / `400 OTP_INVALID`, async OTP dispatch; operator lookup requires exactly one exact filter, audited and rate limited; registration `409` documented as accepted risk. | Test compares recovery response status/body and p95 latency for registered vs unregistered identifier; unfiltered lookup returns `400`. |
| **D**enial of Service | Brute force login, account enumeration, OTP/SMS flooding, or DB connection pool exhaustion | Rate limiting on `/auth/login`, `/auth/register/send-otp`, `/auth/recover/initiate`, `/operator/customers/send-otp` and `/recipients/resolve`; bounded connection pool with HikariCP; deterministic row lock timeouts. | Burst traffic test triggers `429 RATE_LIMITED`; lock contention resolves within bounded timeout. |
| **E**levation of Privilege | Customer invokes Operator counter-creation/seed endpoints | RBAC for `CUSTOMER`, `OPERATOR`, `AUDITOR`, `ADMIN` enforced by backend. | Customer and Auditor receive `403 FORBIDDEN` on Operator endpoints. |

## 5. Audit and suspicious activity

### Audit event minimum fields

- `eventId`, `eventType`, `actorId` (or system actor), `targetType`, `targetId`, `occurredAt`, `outcome`, `correlationId`.
- Include minimal redacted metadata required to explain the action.
- Audit records are append-only through application APIs. Restrict DB permissions in deployed environment where practical.
- Capture request source metadata only when justified; avoid collecting excess personal/device data.

### Rule-based suspicious transaction detection

- MVP uses two deterministic rules: transfer above `RISK_LARGE_TRANSFER_THRESHOLD` (baseline `5000000` VNĐ) or account exceeds `RISK_TRANSFER_COUNT_THRESHOLD` (baseline 5) transfers within `RISK_TRANSFER_COUNT_WINDOW_MINUTES` (baseline 10 minutes).
- Evaluate after successful transfer commit. Flags never block or roll back transfer.
- Store rule ID/version, transfer ID, detection time and reason. Use a fixed clock in tests.
- Thresholds and windows are environment-configurable; document effective non-secret values.
- MVP flag query is read-only. Review notes and `REVIEWED` status workflow are post-MVP options.

## 6. Testing strategy

### Backend

- Unit tests: state transitions, amount validation, idempotency policy, risk rules, OTP TTL/attempt/single-use policy, PIN lockout, error mapping; mock external dependencies and use fixed clock.
- Integration tests: concurrent registrations with same phone or email produce exactly one Customer; recovery via SMS and via Email both revoke previous refresh tokens.
- Step-up transfer tests: concurrent double `confirm-otp` yields exactly one debit; confirm after expiry returns `409 TRANSFER_EXPIRED`; balance reduced by another transfer between PIN and OTP makes confirm `FAILED` with balances unchanged; replay `POST /transfers` while `AWAITING_OTP` sends no new OTP.
- Account status tests: block during in-flight transfer serializes on row lock; transfer from/to `BLOCKED` account returns `409 ACCOUNT_NOT_ELIGIBLE`; repeated block is idempotent.
- Repository/integration tests: PostgreSQL-specific migrations, constraints, transaction rollback and row locking using disposable isolated database/container.
- API tests: authentication, roles, ownership, OpenAPI response shape and error contract.
- Concurrency tests: parallel transfers, duplicate idempotency keys and opposite-direction locks.
- Never run destructive tests against a shared/production database. Test data must use isolated database/schema/container and be disposable by test harness.

### Frontend

- Typecheck and production build.
- Component/feature tests for registration (phone OTP step, duplicate phone/email errors), login by phone, mandatory PIN setup redirect, recovery channel selection (SMS/Email), transfer PIN + step-up OTP modal, loading/error/success, and role-gated navigation.
- API mock tests validate expected request headers, idempotency key behavior and cache invalidation/refetch after transfer.
- E2E smoke test covers register (phone OTP) → login by phone → PIN setup → Operator seed balance → transfer (PIN; > 5M with OTP) → history/status, plus forgot password → OTP → new password → login.

### Contract and CI

- Validate `../../contracts/openapi.yaml` and fail CI on invalid schema.
- Generate/check FE API types/client from committed contract.
- Run backend unit and targeted integration tests, frontend typecheck/build/tests, dependency/security scanning appropriate to course CI limits.
- Build container images in CI; deployment requires explicit environment configuration and health checks.
- Keep test commands and evidence in CI artifacts or team report; do not claim NFR without measured run.

## 7. Cloud and deployment requirements

### Capability-first, provider portable

Provider is not selected. Map these capabilities to a provider after cost/region/team-access review:

- Container runtime for Spring Boot backend and static frontend hosting.
- Managed PostgreSQL with automated backup and TLS.
- TLS ingress/load balancing and domain/DNS.
- Secret management or protected deployment secrets.
- Centralized structured logs, metrics and alerting.
- Optional message broker only if a concrete asynchronous use case is accepted in a later release; no broker is part of MVP.

### FinOps: Monthly Cloud Cost Estimation Model

The figures below are planning estimates only, not price quotes or a zero-cost guarantee. Verify current regional prices, account eligibility and usage in the provider calculator before deployment. Enable budget alerts. The AWS line items exclude or may vary with NAT Gateway, public IPv4, ECR, backup/snapshot storage, data transfer, domain, ALB capacity units and overages; total actual cost may exceed the table.

#### Baseline 1: Standard Managed Cloud (AWS Singapore `ap-southeast-1`)

| Component | AWS Resource | Sizing / Usage Profile | Estimated Cost/Month |
|---|---|---|---:|
| **Backend Compute** | ECS Fargate | 1 task × 0.5 vCPU + 1.0 GB RAM (24/7) | ~$15.20 |
| **Database** | RDS PostgreSQL | `db.t4g.micro` (Single-AZ, 20 GB gp3 storage) | ~$16.50 |
| **Frontend Hosting** | S3 + CloudFront | Static SPA hosting + 10 GB CDN egress | ~$1.80 |
| **Ingress & Networking**| Application Load Balancer / API Gateway | 1 ALB / HTTP API + TLS certificate (ACM free) | ~$10.00 |
| **Observability** | CloudWatch | 5 GB Log ingestion + 1 dashboard + 5 metrics alarms | ~$3.50 |
| **Total Listed Resources** | | | **~$47.00 / month before excluded costs** |

#### Baseline 2: Potential Free-Tier / Hobby Deployment

$0 is possible only while selected provider accounts qualify and usage stays within current quotas. Pricing, sleep/auto-suspend behavior, region availability and limits vary by provider. Auto-suspending databases are not a valid target for continuous 20 RPS load tests. Avoid combining providers unless operational trade-offs are understood.

| Component | Provider / Service | Sizing / Plan | Cost/Month |
|---|---|---|---:|
| **Backend Compute** | Render / Fly.io / AWS EC2 Free Tier | Provider-specific eligible plan and quota | Potentially $0 |
| **Database** | Neon / Supabase | Provider-specific free plan; auto-suspend may apply | Potentially $0 |
| **Frontend Hosting** | Cloudflare Pages / Vercel | Provider-specific free plan and quota | Potentially $0 |
| **Total** | | | **Potentially $0 within eligible free quotas; not guaranteed** |

*FinOps guidance:* Use eligible free/hobby tiers for low-volume development only. For official demo and load testing, select one provider and estimate actual cost before provisioning; do not assume the listed AWS sizing meets workload without measurement.

Avoid provider-specific dependencies in domain modules. Isolate provider-specific deployment/IaC under `infra/cloud/<provider>/` once chosen. Keep local development in `infra/compose.yaml` with non-production credentials/data.

### Deployment and rollback

- Build immutable versioned backend/frontend artifacts.
- Store migration scripts in version control and apply forward migrations during controlled deployment.
- Prefer backward-compatible expand/migrate/contract schema changes. Do not auto-drop data on deployment rollback.
- Document app rollback independently from database migration rollback; destructive down-migrations are not assumed safe.
- Health/readiness check must fail when required database dependency is unavailable.
- Run post-deploy smoke test for health, authentication and a safe demo flow.
- No real financial data or public real-money integrations in any environment.

### Infrastructure as code

- Describe network boundaries, runtime, database, secret references and observability in IaC when provider is selected.
- Separate dev/test and shared demo environments.
- Use least-privilege runtime identity and database user.
- Record state storage, access controls, estimated resource sizes and teardown procedure.
- Do not include provider credentials or Terraform state in Git.

## 8. Observability

- Propagate validated correlation ID from ingress to service logs and audit metadata.
- Structured logs: timestamp, severity, service, correlation ID, operation, outcome, duration; avoid sensitive payloads.
- Metrics: request count/latency/error by endpoint, DB pool usage, transfer success/failure, idempotency replay/conflict, lock/deadlock/timeout, and risk flag count.
- Distributed trace instrumentation is optional for single process; include it only if it materially helps cloud demo.
- Alerts for service unhealthy, elevated 5xx, DB unavailable/pool exhaustion, repeated deadlocks and growing outbox backlog if broker used.
- Dashboard must distinguish business outcome from transport status; for example, HTTP timeout does not prove transfer failed.

## 9. Load-test report template

Record:

- Date, commit/version, provider/region or local hardware, backend instance size, PostgreSQL tier/configuration.
- Test tool/version, test script, dataset size and data reset/isolation method.
- Workload: concurrent users, request mix, transfer amount/ratio, duration, ramp-up, target RPS and peak factor. Main load profile uses transfer amounts ≤ `5,000,000` VNĐ (PIN only, no OTP step); if step-up is included, report it as a separate scenario using the simulated OTP adapter.
- Results: achieved RPS, p50/p95/p99 latency, error rate by class, CPU/memory, DB connections, lock wait/deadlock count.
- Correctness: balance invariant, committed transfer count, duplicate protection and reconciliation result.
- Cost estimate and assumptions for cloud deployment.
- Bottlenecks, limitations and next optimization justified by measurements.

## 10. Required course demonstrations

To achieve a 9.5+ grade during project defense, the team must execute and explain the following live/recorded engineering demonstrations:

### 1. Concurrent Transfer & Overdraft Prevention
- **Scenario:** Two concurrent transfer requests of 600,000 VNĐ each are fired against an account with only 1,000,000 VNĐ available balance.
- **Demonstration:** Execute automated k6/JMeter script firing simultaneous requests.
- **Expected Outcome:** Exactly one transaction commits (600,000 VNĐ debited); the second request fails with `409 INSUFFICIENT_FUNDS`. Balance remains exactly 400,000 VNĐ. No negative balance, no lost update.

### 2. Duplicate Request Protection (Idempotency)
- **Scenario:** Customer submits a transfer of 100,000 VNĐ with `Idempotency-Key: K1`.
- **Demonstration:** Client replays the identical request with `Idempotency-Key: K1`.
- **Expected Outcome:** Server returns `200 OK` with header `Idempotency-Replayed: true` and identical transfer details. Source balance is debited only once (100,000 VNĐ, not 200,000 VNĐ). A subsequent request reusing `K1` with an altered amount (150,000 VNĐ) returns `409 IDEMPOTENCY_KEY_REUSED`.

### 3. Failure & Recovery Scenarios (Mandatory from Slide 11)

- **Failure 3A — Debit succeeds → Credit fails:** In isolated test/demo profile, inject deterministic failure after debit statement and before credit/commit. Transaction rollback restores source and leaves destination unchanged. Keep hook disabled in production.
- **Failure 3B — Client timeout after commit:** In isolated test/demo profile, delay response after commit by configured test-only duration. Client retries with same key and payload; server returns original result without second mutation.
- **Failure 3C — Service crashes during transaction:** Use deterministic test hook or isolated integration test to verify crash/restart recovery. Do not rely on timing-sensitive manual `docker kill` as sole evidence. Verify that PostgreSQL rolls back uncommitted work and same-key retry cannot duplicate a committed transfer.

### 4. Security & Audit Trail Review
- **Demonstration:**
  - Authenticate as Customer A, attempt to query Customer B's account ID -> Returns `404` (Concealed IDOR).
  - Authenticate as Customer, attempt to call `POST /operator/customers` -> Returns `403 FORBIDDEN`.
  - Register a second customer with an existing phone or email -> Returns `409 PHONE_ALREADY_REGISTERED` / `409 EMAIL_ALREADY_REGISTERED`.
  - Recovery initiate with registered vs unregistered phone -> identical `200` body (anti-enumeration).
  - Operator blocks Customer B account -> transfer A→B returns `409 ACCOUNT_NOT_ELIGIBLE`; audit shows block reason.
  - Transfer 6,000,000 VNĐ -> `AWAITING_OTP`; `GET /transfers/{id}` shows status; confirm twice -> one debit only.
  - Recover password via Email channel on device A while logged in on device B -> device B refresh returns `401 SESSION_EXPIRED`.
  - Log inspection verifies zero password/PIN/OTP/token leaks in logs.
  - Auditor logs in and queries `GET /api/v1/audit-events`, demonstrating full traceability of previous transfer, counter creation and recovery actions.

### 5. Load Test with Latency & Error Metrics
- **Demonstration:** Run 10-minute steady-state 20 RPS test using k6/Locust. Show measured p50/p95/p99 latency, error classes and DB pool utilization. Compare results with proposed MVP p95 target of 500 ms; do not claim zero 5xx before test.

### 6. Cloud Operations & CI/CD Pipeline
- **Demonstration:** Push a git commit; show GitHub Actions CI running contract checks, unit/integration tests, and container image build. Show health check endpoint `/api/v1/health` and explain deployment/rollback runbook.

## 11. Decisions still open before implementation

- Confirm auth/session expiry values and local demo seed credentials delivery mechanism; current access-token example uses 15 minutes.
- Apply MVP performance target after first baseline load test; proposed p95 gate is 500 ms at 20 RPS.
- Define audit/risk data retention policy before shared or public deployment. MVP flags are read-only; no actor can mark them reviewed.
