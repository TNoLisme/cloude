# Feature Specification: E1 — Limit Data and Effective Rules

**Feature Branch**: `phuong/limit`
**Created**: 2026-11-15
**Status**: Clarified — ready for planning
**Input**: Implement module `limit` epic E1 (LIM-01 and LIM-02) from DELIVERY-PLAN using specify → clarify → plan → tasks → implement, then independent converge review.

## Clarifications

### Session 2026-11-15

- Q: How should E1 handle foreign keys whose referenced production tables do not exist? → A: Implement only the two limit tables and their internal constraints. Defer cross-module foreign keys until the owning modules supply the referenced tables; explicitly record those references as not accepted yet. Do not create substitute production tables.
- Q: What should a per-transaction check return when neither scope has applicable configuration? → A: Return a distinct NOT_CONFIGURED outcome, never PASS and never an invented default. Transfer enforcement policy remains D1 for E2.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Preserve valid configuration and daily usage (Priority: P1)

As a module owner, I can store customer/account limit versions and daily usage separately, with invalid data rejected and configuration history preserved.

**Why this priority**: Safe financial limits need trustworthy data before enforcement can be integrated.
**Independent Test**: Persist valid and invalid records against PostgreSQL; verify state, value, uniqueness and reference constraints, version history, and transaction rollback.

**Acceptance Scenarios**:

1. **Given** a customer scope or an account scope, **When** valid configurations and daily usage are saved, **Then** all documented fields round-trip without loss, and pending/history versions remain distinct from usage.
2. **Given** an ACTIVE configuration, **When** another ACTIVE configuration is saved for the same scope, **Then** it is rejected; pending, rejected and superseded versions may coexist without overwriting history.
3. **Given** a scope/date usage counter, **When** another counter for the same scope/date is saved, **Then** it is rejected; other dates/scopes remain independent.
4. **Given** invalid amounts, currency, state, version, effective interval or approval attribution, **When** persistence is attempted, **Then** invalid data is rejected. Reference existence and account ownership integrity await the owning tables and are explicitly outside E1 acceptance.

### User Story 2 - Resolve applicable limits and check one transfer amount (Priority: P1)

As a caller preparing a transfer, I can resolve all applicable limits for a customer/source account at one supplied instant and check the per-transaction amount against every applicable scope.

**Why this priority**: An account-specific limit must never loosen its customer's limit.
**Independent Test**: Resolve customer-only, account-only, both and absent configurations at validity boundaries; test amounts below, equal to and above each applicable limit.

**Acceptance Scenarios**:

1. **Given** configuration versions in all four states, **When** limits are resolved at an instant, **Then** only ACTIVE versions with start ≤ instant and end absent or end > instant are selected.
2. **Given** customer and account limits, **When** an amount is checked, **Then** both apply; equality passes and exceeding either fails regardless of which scope is stricter.
3. **Given** only one scope configured, **When** limits are resolved, **Then** that scope alone is returned and checked.
4. **Given** no applicable configuration, **When** a caller requests a check, **Then** return NOT_CONFIGURED, never PASS or an invented default; transfer enforcement policy stays open for E2.

### Edge Cases

- Validity start is inclusive and end is exclusive, including equivalent instants in different time offsets.
- Future ACTIVE configurations are excluded until their start; expired ACTIVE configurations do not fall back to SUPERSEDED history. E1 does not change activation workflows (D3 remains outside scope).
- Customer and account limits combine by checking both, including an account limit higher than the customer limit.
- A source account's configuration must be selected for the supplied customer/account pair; configurations belonging to other accounts/customers are excluded.
- Invalid money (nonpositive, excess fractional precision or out of representable range) must not silently round into an accepted value.
- Usage is persisted independently; reading/checking effective per-transaction limits must not create, lock or increment usage.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Store configuration fields, four states, version history and approval attribution specified in source 04 §4.5.1, using only CUSTOMER and ACCOUNT scopes.
- **FR-002**: Reject nonpositive limits, per-transaction limit greater than daily limit (source 07 §8.5.2), non-VND currency, nonpositive version, invalid effective interval and invalid maker/checker attribution; enforce one ACTIVE configuration per scope.
- **FR-003**: Store daily usage fields from source 04 §4.5.2; reject negative amount/count and duplicate scope/date counters. Keep currency VND and counters independent of configuration version.
- **FR-004**: Keep configuration and usage operations within caller-controlled transactions. Do not expose hard-delete operations or silently overwrite configuration history.
- **FR-005**: Resolve only ACTIVE configurations in the half-open effective interval, for a supplied customer/source-account pair and instant; return both scopes when present.
- **FR-006**: Check a positive, valid transfer amount against every applicable per-transaction limit; equality succeeds and excess produces the established LIMIT_EXCEEDED business failure.
- **FR-007**: Return a distinct NOT_CONFIGURED outcome when neither scope has applicable configuration, never PASS or an invented default. D1 transfer enforcement remains open for E2.
- **FR-008**: Preserve reference columns to customer, account, maker/checker and approval, but defer their foreign keys until the owning modules supply their tables. Deliver only the two limit tables and internal constraints; explicitly record foreign-key/reference ownership integration as not accepted. Do not create fake production tables for other modules.
- **FR-009**: Verify persistence, uniqueness, rollback and migrations with real PostgreSQL, including clean installation and upgrade from the existing migration baseline. Verify effective selection and monetary boundaries with automated tests. The backend package build must succeed; tests are scoped to E1 and directly changed components per the user’s later instruction.
- **FR-010**: Deliver an internal provider only: no REST endpoints, approval workflow, activation scheduler, GLOBAL policy, policy seed/max/default, usage locking/increment enforcement or transfer orchestration.

### Key Entities *(include if feature involves data)*

- **Limit configuration**: Customer, optional account, per-transaction/daily amounts, VND currency, effective interval, state, version, maker/checker, approval reference and timestamps.
- **Daily usage**: Customer, optional account, business date, used amount, completed-transfer count, currency and update timestamp.
- **Applicable limits**: Configurations selected at a supplied instant, with explicit absence and the stricter per-transaction amount when available.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every documented valid persistence scenario is accepted and every covered invalid/duplicate scenario is rejected by the actual data store.
- **SC-002**: Every selection scenario (customer-only, account-only, both, none, all states, validity boundaries) produces exactly the expected configurations.
- **SC-003**: No amount exceeding either applicable per-transaction limit is accepted; amounts exactly at all applicable limits are accepted.
- **SC-004**: Read/check operations cause zero changes to configuration history or usage, and rolled-back writes leave no persisted change.
- **SC-005**: All E1 automated checks, directly affected regression checks and the backend package build succeed; independent convergence review reports no remaining work against the feature artifacts.

## Assumptions

- Source priority is docs-common/01–11 → docs-common/design → backend/frontend. Feature-local FR identifiers above are distinct from SRS FR identifiers.
- Canonical sources: docs-common/03 SRS §2.4/2.6; 04 Database §4.5.1–4.5.2; 06 Architecture §6.1–6.3; 07 API §7.2.13/8.5.1–8.5.2; 08 Deployment §8.1; 09 Test plan. DELIVERY-PLAN §4 bounds E1.
- E1 intentionally combines LIM-01 and LIM-02 for the user's requested epic; it does not claim completion of E2–E4 or integration with real transfer consumers.
- Existing branch `phuong/limit` is retained; no branch/worktree change is required.
- The repository constitution is an unfilled template; apply AGENTS.md and the source priority supplied by the user without inventing constitution principles.
- D2 business timezone, D3 activation transitions, D4 mid-day scope addition, and D5 checker API behavior are outside E1; explicit instant/date inputs avoid deciding these policies.

- Validation scope updated by user on 2026-11-15: do not run unrelated full regression. Run E1, directly changed common contexts and module boundaries; package with build -x test after scoped tests.
