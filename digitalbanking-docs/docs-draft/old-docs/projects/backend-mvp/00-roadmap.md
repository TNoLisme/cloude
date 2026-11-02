# Backend MVP Delivery Roadmap

**Status:** Phases 01–06 implementation complete with targeted verification; Phases 07–08 remain planned.  
**Scope:** Cross-cutting design, package layout, dependency baseline, database migration map, phase order and global acceptance gates.  
**Workflow:** [`../../backend-development-workflow.md`](../../backend-development-workflow.md).  
**MVP baseline:** [`../../baseline/README.md`](../../baseline/README.md), [`../../baseline/mvp-requirements-and-architecture.md`](../../baseline/mvp-requirements-and-architecture.md), [`../../baseline/api-and-team-contract.md`](../../baseline/api-and-team-contract.md), [`../../baseline/mvp-decision-record.md`](../../baseline/mvp-decision-record.md), [`../../baseline/quality-security-and-cloud.md`](../../baseline/quality-security-and-cloud.md), [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## 1. How to use these phase designs

Each numbered phase is one complete development loop: inspect current repository, reconcile design with OpenAPI/baseline, ask only about genuine conflict or newly discovered consequential unknown, get approval for that phase, implement, run targeted checks, review diff, update evidence, and hand over. Approval of one phase does not approve later phases or contract changes.

These documents are detailed designs, not permission to implement every phase. No application code until user approves that phase's final spec. The OpenAPI file is immutable throughout this technical refinement task.

## 2. Approved architecture and package layout

- Java 21; Spring Boot 3.3.x; Maven; base package `com.bank.simulator`.
- One deployable Spring Boot modular monolith and one PostgreSQL database.
- Seven modules: `identity`, `customer`, `account`, `transfer`, `audit`, `risk`, `shared`.
- No cross-module repository access, business-path joins, cyclic module dependency, or cross-module database foreign key. Cross-module references are scalar UUIDs and cross ownership through module APIs.
- OpenAPI at repository root is HTTP authority. Do not silently alter path, schema, status, security, header, error media type or response behavior.
- Kafka, Outbox, Saga, read replica, microservices, real payment rails, real money and real SMS/email delivery remain outside MVP.

```text
be/
├── pom.xml
├── README.md
├── Dockerfile                             # only when required by approved local/CI deployment design
└── src/
    ├── main/java/com/bank/simulator/
    │   ├── BankingSimulatorApplication.java
    │   ├── shared/
    │   │   ├── api/                       # shared HTTP-only types: Problem, FieldError
    │   │   ├── config/                    # Clock, Jackson and typed application config
    │   │   ├── correlation/               # CorrelationIdFilter, request correlation context
    │   │   ├── error/                     # ApiException, error codes, GlobalExceptionHandler
    │   │   ├── health/                    # HealthController and datasource health probe
    │   │   ├── pagination/                # reusable technical cursor codec only
    │   │   ├── ratelimit/                 # local rate-limit primitive/configuration
    │   │   └── idempotency/               # technical storage API, no business rules
    │   ├── identity/
    │   │   ├── api/                       # IdentityModuleApi + immutable command/result records
    │   │   ├── application/               # login/session/OTP/PIN use cases
    │   │   ├── domain/                    # role, token, OTP, credential policies
    │   │   ├── infrastructure/            # identity-owned JPA, hash/JWT/mailbox adapters
    │   │   └── web/                       # auth/customer-PIN controllers and DTO mapping
    │   ├── customer/{api,application,domain,infrastructure,web}/
    │   ├── account/{api,application,domain,infrastructure,web}/
    │   ├── transfer/{api,application,domain,infrastructure,web}/
    │   ├── audit/{api,application,domain,infrastructure,web}/
    │   └── risk/{api,application,domain,infrastructure,web}/
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-local.yml
    │   ├── application-demo.yml
    │   └── db/migration/V1__*.sql ... V7__*.sql
    └── test/java/com/bank/simulator/<module>/...
```

`api` means Java module boundary, not HTTP controller. `web` contains controllers, request/response DTOs and HTTP mapping. Add only packages used by module. Do not create empty boilerplate packages just to fill a tree.

### Module ownership

| Module | Owns | Public API examples |
|---|---|---|
| `identity` | Users, roles, password hashes, PIN, OTP challenges, refresh sessions | `IdentityModuleApi.login`, `issueOtp`, `consumeOtp`, `verifyPin`, `revokeAllSessions` |
| `customer` | Customer profile, registration/counter creation, exact customer lookup | `CustomerModuleApi.createCustomer`, `getProfile`, `findByPhone`, `findByEmail` |
| `account` | Accounts, balance/status, seed ledger and account mutation | `AccountModuleApi.createDefaultAccount`, `getAccount`, `lockAccountsAscending`, `creditSeed` |
| `transfer` | Transfer lifecycle, transfer idempotency orchestration, history/status | `TransferModuleApi.createTransfer`, `confirmOtp`, `get/listTransfers` |
| `audit` | Append/query audit records | `AuditModuleApi.append`, `list` |
| `risk` | Rule evaluation and read-only flags | `RiskModuleApi.onTransferCommitted`, `listFlags` |
| `shared` | Technical correlation, error, cursor codec, limiter/idempotency adapters | No business entity or rules |

No module injects another module's JPA repository/entity. Module API calls may join the caller's Spring transaction using REQUIRED propagation. Account+transfer+audit mutation is one PostgreSQL transaction where specified.

### Maven and dependencies

Pin Spring Boot parent/BOM `3.3.13`, compiler release 21, UTF-8. Verify actual artifact resolution and vulnerability report in Phase 01/08; do not silently upgrade outside required 3.3.x line. Core dependencies:

| Artifact ID | Scope/version |
|---|---|
| `spring-boot-starter-web` | main, Boot-managed |
| `spring-boot-starter-data-jpa` | main, Boot-managed |
| `spring-boot-starter-security` | main, Boot-managed |
| `spring-boot-starter-validation` | main, Boot-managed |
| `spring-boot-starter-actuator` | optional main only for internal indicators/metrics; no public actuator substitute for OpenAPI health |
| `flyway-core` | main, Boot-managed |
| `flyway-database-postgresql` | main, Boot-managed |
| `postgresql` | runtime, Boot-managed |
| `jjwt-api`, `jjwt-impl`, `jjwt-jackson` | 0.12.6; API compile, impl/Jackson runtime |
| `spring-boot-starter-test` | test, Boot-managed; JUnit, Mockito, AssertJ, MockMvc |
| `spring-security-test` | test, Boot-managed |
| `testcontainers-junit-jupiter`, `testcontainers-postgresql` | test, version managed by Boot BOM |

Use MockMvc. Do not add RestAssured absent concrete gap. No Lombok, MapStruct, Redis or Spring Modulith by default. Pin Maven wrapper after checking installed toolchain. Include only dependency actually used.

## 3. Configuration baseline

```yaml
app:
  security:
    access-token-ttl: 900s
    refresh-token-ttl: 7d
    refresh-cookie-name: refresh_token
    refresh-cookie-same-site: Lax
  otp:
    length: 6
    ttl: 120s
    max-attempts: 5
    mailbox-enabled: false
  money:
    currency: VND
    transfer-min: 2000
    transfer-max: 10000000
    seed-max: 100000000
    transfer-otp-threshold: 5000000
  idempotency:
    retention: 24h
  risk:
    large-transfer-threshold: 5000000
    transfer-count-window: 10m
    transfer-count-threshold: 5
  rate-limit:
    storage: in-memory-instance-local
    policies:
      login: { limit: 5, window: 60s, independent-keys: [ip, phone] }
      registration-otp: { limit: 3, window: 300s, independent-keys: [ip, phone] }
      recovery-initiate: { limit: 3, window: 300s, independent-keys: [ip, identifier] }
      recovery-verify: { limit: 5, window: 300s, independent-keys: [ip, identifier] }
      operator-customer-otp: { limit: 10, window: 300s, key: operator-id }
      recipient-resolve: { limit: 30, window: 60s, key: customer-id }
      operator-customer-lookup: { limit: 30, window: 60s, key: operator-id }
```

Use environment overrides for datasource/secrets. Never commit secrets. Rate-limit counters use a synchronized in-memory per-key fixed window and monotonic clock; counters reset on restart, are not shared across replicas, and do not claim distributed protection. Bound map cardinality and evict expired entries. Trust forwarded IP only from explicitly configured trusted proxies; never trust arbitrary `X-Forwarded-For`. 429 uses OpenAPI Problem `RATE_LIMITED` and `Retry-After` integer seconds, minimum 1. Never log or metric-label raw phone/email.

For independent-keys, limit/window applies to each separate operation-scoped bucket. Allow only if both have quota; either exhausted rejects. These are not concatenated IP-identifier keys. User approved retaining the previous numeric limit for each independent bucket on 2026-10-01; shared IPs therefore share that quota. See the [six-decision update](../2026-10-01-security-transaction-refinements.md) and Phase 02.

## 4. Migration plan and complete PostgreSQL DDL

### Migration map

- V1 `V1__identity_customer_account_audit.sql`: `users`, `user_roles`, `customers`, `customer_pins`, `accounts`, `audit_events`.
- V2 `V2__identity_security_and_idempotency.sql`: `otp_challenges`, `refresh_sessions`, `idempotency_records`.
- V3 `V3__seed_ledger_and_transfers.sql`: `account_seed_records`, `transfers`.
- V4 `V4__risk_flags.sql`: `risk_flags`.
- V5 `V5__mvp_schema_constraints_and_indexes.sql`: forward-compatible MVP constraints and indexes for transfer state, OTP linkage, query support and idempotency behavior.
- V6 `V6__recovery_reset_tokens.sql`: password-recovery reset-token persistence.
- V7 `V7__registration_verification_tokens.sql`: phone-registration verification-token persistence.

No fake health/metadata table. Flyway `flyway_schema_history` tracks migrations; health runs datasource probe and returns exactly OpenAPI `{status,timestamp}`. Audit table is physically installed in V1 so mutations in earlier feature phases can write audit facts atomically; logical ownership remains audit module.

#### V1 SQL — identity, customer, accounts, audit

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    phone VARCHAR(10) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_phone UNIQUE (phone),
    CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_users_phone_vn CHECK (phone ~ '^0[3-9][0-9]{8}$'),
    CONSTRAINT ck_users_email_normalized CHECK (email_normalized = lower(btrim(email_normalized))),
    CONSTRAINT ck_users_email_not_empty CHECK (length(email_normalized) > 0)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN'))
);
CREATE INDEX ix_user_roles_role_user ON user_roles (role, user_id);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    address VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_customers_full_name CHECK (length(btrim(full_name)) BETWEEN 1 AND 120)
);
CREATE INDEX ix_customers_created_at_id ON customers (created_at DESC, id DESC);

CREATE TABLE customer_pins (
    customer_id UUID PRIMARY KEY,
    pin_hash VARCHAR(255),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    configured_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_customer_pins_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_customer_pins_configured CHECK (
        (pin_hash IS NULL AND configured_at IS NULL) OR
        (pin_hash IS NOT NULL AND configured_at IS NOT NULL)
    )
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(12) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    account_type VARCHAR(20) NOT NULL DEFAULT 'CHECKING',
    balance NUMERIC(19,0) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT TRUE,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_accounts_number CHECK (account_number ~ '^[0-9]{12}$'),
    CONSTRAINT ck_accounts_balance CHECK (balance >= 0),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_type CHECK (account_type = 'CHECKING')
);
CREATE UNIQUE INDEX uq_accounts_one_default_per_customer ON accounts (customer_id) WHERE is_default;
CREATE INDEX ix_accounts_customer_opened ON accounts (customer_id, opened_at DESC, id DESC);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID,
    actor_role VARCHAR(20),
    event_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID NOT NULL,
    outcome VARCHAR(10) NOT NULL,
    correlation_id UUID NOT NULL,
    summary VARCHAR(500) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_actor_role CHECK (actor_role IS NULL OR actor_role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN')),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_summary CHECK (length(btrim(summary)) BETWEEN 1 AND 500),
    CONSTRAINT ck_audit_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX ix_audit_occurred_id ON audit_events (occurred_at DESC, id DESC);
CREATE INDEX ix_audit_event_type_occurred ON audit_events (event_type, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_actor_occurred ON audit_events (actor_id, occurred_at DESC, id DESC);
```

#### V2 SQL — OTP, refresh sessions, idempotency

```sql
CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY,
    identifier_normalized VARCHAR(254) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_otp_channel CHECK (channel IN ('SMS', 'EMAIL')),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('REGISTRATION', 'OPERATOR_CREATE_CUSTOMER', 'RECOVERY', 'PIN_RESET', 'TRANSFER_STEP_UP')),
    CONSTRAINT ck_otp_attempts CHECK (attempts BETWEEN 0 AND max_attempts AND max_attempts = 5),
    CONSTRAINT ck_otp_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_otp_terminal_times CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);
CREATE INDEX ix_otp_lookup ON otp_challenges (identifier_normalized, purpose, channel, created_at DESC);
CREATE INDEX ix_otp_expiry ON otp_challenges (expires_at) WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_session_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_refresh_hash CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_expiry CHECK (expires_at > created_at),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refresh_replacement FOREIGN KEY (replaced_by_session_id) REFERENCES refresh_sessions(id) ON DELETE RESTRICT
);
CREATE INDEX ix_refresh_sessions_user_active ON refresh_sessions (user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status SMALLINT,
    response_body JSONB,
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_scope_key UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_key_length CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_status CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 599),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);
```

`users` has multiple roles through `user_roles`; there is no single role column. `customers.user_id` is unique but deliberately has no identity cross-module FK. `customer_pins.customer_id`, `accounts.customer_id`, `refresh_sessions.user_id`, idempotency actor/resource IDs, and later transfer/audit/risk cross-module IDs are scalar UUID references. Same-module `user_roles.user_id` FK and refresh self-FK are allowed.

OpenAPI requires `LoginResponse.user.customerId` UUID although privileged users have no customer row. Approved compatibility rule: for non-CUSTOMER roles, set `customerId=userId` alias; FE must branch on roles and never use alias for customer ownership. No staff Customer/Account row.

#### V3 SQL — seed ledger and transfers

```sql
CREATE TABLE account_seed_records (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    amount NUMERIC(19,0) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    actor_id UUID NOT NULL,
    reference VARCHAR(100) NOT NULL,
    idempotency_record_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_seed_amount CHECK (amount BETWEEN 1 AND 100000000),
    CONSTRAINT ck_seed_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_seed_reference CHECK (length(btrim(reference)) BETWEEN 1 AND 100)
);
CREATE INDEX ix_seed_account_created ON account_seed_records (account_id, created_at DESC, id DESC);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount NUMERIC(19,0) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL,
    failure_code VARCHAR(80),
    memo VARCHAR(140),
    otp_challenge_id UUID,
    idempotency_record_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transfers_amount CHECK (amount BETWEEN 2000 AND 10000000),
    CONSTRAINT ck_transfers_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_transfers_status CHECK (status IN ('AWAITING_OTP', 'COMPLETED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_transfers_completion CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL) OR (status <> 'COMPLETED' AND completed_at IS NULL)),
    CONSTRAINT ck_transfers_pending_expiry CHECK (status <> 'AWAITING_OTP' OR expires_at IS NOT NULL)
);
CREATE INDEX ix_transfers_source_created_id ON transfers (source_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_destination_created_id ON transfers (destination_account_id, created_at DESC, id DESC);
CREATE INDEX ix_transfers_status_expiry ON transfers (status, expires_at) WHERE status = 'AWAITING_OTP';
CREATE INDEX ix_transfers_created_id ON transfers (created_at DESC, id DESC);
```

Seed/transfer idempotency references are scalar UUIDs; use `shared.idempotency` API within same DB transaction. Persist original status/body/resource and 24h-or-more expiry. Cleanup may remove only expired idempotency rows and must never remove financial records. The OTP `channel` column records API delivery channel (`SMS`/`EMAIL`) only; the approved local mailbox is an `OtpSender` adapter, not a new channel value.

#### V4 SQL — risk flags

```sql
CREATE TABLE risk_flags (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL,
    rule_id VARCHAR(80) NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_risk_flag_rule_transfer_version UNIQUE (transfer_id, rule_id, rule_version),
    CONSTRAINT ck_risk_rule_id CHECK (length(btrim(rule_id)) BETWEEN 1 AND 80),
    CONSTRAINT ck_risk_rule_version CHECK (length(btrim(rule_version)) BETWEEN 1 AND 40),
    CONSTRAINT ck_risk_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);
CREATE INDEX ix_risk_flags_detected_id ON risk_flags (detected_at DESC, id DESC);
CREATE INDEX ix_risk_flags_rule_detected ON risk_flags (rule_id, detected_at DESC, id DESC);
CREATE INDEX ix_risk_flags_transfer ON risk_flags (transfer_id);
```

Audit writes are append-only through application API. Runtime DB grants should give audit SELECT/INSERT and deny UPDATE/DELETE where deployment DB privileges support it; no trigger that blocks migrations/maintenance. PostgreSQL version/migration SQL must be verified in isolated Testcontainers; never use shared/production DB.

## 5. Request/error/health baseline

- Effective API prefix `/api/v1`; `/health` resolves to `/api/v1/health`.
- Error media `application/problem+json`; fields `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`, optional `fieldErrors`. Never rename `detail` to `message`.
- Correlation ID is UUID; set/propagate `X-Correlation-Id`, store in MDC only for request lifetime and clear in `finally`. Invalid incoming ID gets generated UUID rather than echoed.
- Health body exactly `{status,timestamp}`. DB availability determines `UP`/200 or `DOWN`/503; no version/database properties.
- Unknown errors return sanitized 500 with correlation ID, no SQL/stack/credentials/tokens/OTP.

## 6. Phase order

| Phase | Design file | Depends on | Exit gate |
|---|---|---|---|
| 01 | [`01-backend-foundation.md`](./01-backend-foundation.md) | Contract/baseline | Java 21 build; PostgreSQL/Flyway V1; Problem, correlation, health and contract checks verified. |
| 02 | [`02-shared-security.md`](./02-shared-security.md) | 01 | Access/refresh/CSRF, roles/hashes, OTP local mailbox and approved rate limits pass. |
| 03 | [`03-identity-onboarding.md`](./03-identity-onboarding.md) | 01–02 | Customer/staff identity, onboarding, login, PIN, recovery, demo seed pass. |
| 04 | [`04-account-operator.md`](./04-account-operator.md) | 01–03 | Reads, exact lookup, seed, block/unblock and atomic audit pass. |
| 05 | [`05-transfers.md`](./05-transfers.md) | 01–04 | State machine, idempotency, atomic money and concurrency invariants pass. |
| 06 | [`06-history-audit-risk.md`](./06-history-audit-risk.md) | 01–05 | History/audit queries and best-effort risk flags pass. |
| 07 | [`07-fe-contract-integration.md`](./07-fe-contract-integration.md) | OpenAPI baseline; may proceed incrementally in parallel | Generated types/mocks and staged integration pass. |
| 08 | [`08-quality-demo-deployment-acceptance.md`](./08-quality-demo-deployment-acceptance.md) | 01–07 | E2E, security, failure, load and deployment evidence complete. |

## 7. Global acceptance gates

- OpenAPI syntax/schema and operation IDs validate; FE generated output has no drift.
- BE/FE independent builds pass; contract tests cover implemented success/error status, schema, security/header/media type.
- All protected endpoints enforce role/ownership in backend.
- VND scale 0 exact, no float/double. Transfer debit, credit, record, idempotency and audit facts commit atomically; no overdraft/one-sided mutation under failures/concurrency.
- `ORDER BY id ASC FOR UPDATE` is used for two-account transfer locks.
- OTP/PIN/password/token secrets never appear in API responses, logs, traces, audit, fixtures or source control. Local OTP mailbox unavailable outside local/demo.
- No test touches shared/production DB or runs destructive bulk operations.
- Load, E2E, restart/failure and security evidence records actual command/output; skipped checks and limitations remain visible.
- Each phase gets separate approval; no commit/push without explicit user instruction.

## 8. User-approved exceptional semantics

Staff login response must satisfy existing required UUID `customerId` despite no Customer row. For non-CUSTOMER users return `customerId=userId` as compatibility alias; FE must branch by roles. This compromise is documented and does not change OpenAPI.

Risk frequency rule counts only outgoing completed transfers where account is source, strictly more than five within rolling ten minutes. Incoming transfers do not count. Risk evaluation is best-effort AFTER_COMMIT; process crash can omit a flag, accepted for MVP. Flags are unique by `(transfer_id, rule_id, rule_version)`; no Outbox/broker.
