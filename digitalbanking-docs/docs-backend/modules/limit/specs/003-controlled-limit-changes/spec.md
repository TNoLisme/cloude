# Feature Specification: E4 — Controlled limit changes

**Feature Branch**: `phuong/limit`
**Created**: 2026-11-15
**Status**: Clarified — ready for planning
**Input**: Implement DELIVERY-PLAN E4 (LIM-07–10), using specify → clarify → plan → tasks → implement and independent convergence.

## Clarifications

### Session 2026-11-15

- Q: Bật scope account mới sau khi customer đã dùng trong ngày (D4)? → A: Chặn kích hoạt; giữ pending để có thể duyệt ngày mới chưa có giao dịch. Không reset/backfill counter.

- Q: Future-effective D3? → A: Chỉ hiệu lực ngay khi duyệt; từ chối effectiveFrom tương lai. Không thêm scheduler hoặc thay state machine.

- Q: Policy max/default chưa chốt xử lý thế nào? → A: Max từng lần/ngày đọc từ cấu hình; thiếu policy thì từ chối thay đổi, không tự thêm default nghiệp vụ.

- Q: Phạm vi E4 khi thiếu production admin/identity/account/transfer và E3? → A: Hoàn tất LIM-07/08 provider; hoãn tích hợp LIM-09/10, không nghiệm thu endpoint hay transfer thật.

## User Scenarios & Testing

### User Story 1 - Prepare a proposal without changing enforcement (Priority: P1)

An authorized maker proposes customer/account limits. A pending version is linked to the approval and preserves existing limits and usage.

**Why this priority**: No proposal may change the customer's spend allowance before approval.
**Independent Test**: Prepare a valid proposal in a caller transaction; inspect persisted pending version and unchanged effective limits/usage.

**Acceptance Scenarios**:
1. **Given** an existing configuration, **When** valid changes are proposed, **Then** a new pending version and approval link exist, while effective limits and usage are unchanged.
2. **Given** invalid amounts, scope or policy values, **When** proposed, **Then** the proposal is rejected without partial writes.
3. **Given** a proposal transaction fails, **When** it rolls back, **Then** no pending configuration remains.

### User Story 2 - Decide a proposal once and preserve history (Priority: P1)

A checker different from the maker approves or rejects a pending version. Approval applies the version atomically; rejection preserves the currently effective version.

**Why this priority**: Maker/checker separation and atomic changes are mandatory once configuration is supported.
**Independent Test**: Approve/reject using independent transactions, including concurrent first activation and an injected caller failure.

**Acceptance Scenarios**:
1. **Given** a pending proposal, **When** a different checker approves within current policy, **Then** the new version becomes effective at the agreed time and the previous version remains historical.
2. **Given** a pending proposal, **When** rejected, **Then** it becomes rejected without changing effective limits or usage.
3. **Given** a decided proposal or competing decisions, **When** another decision is attempted, **Then** at most one decision applies and no scope has two active versions.
4. **Given** an error later in the caller transaction (including audit), **When** the caller rolls back, **Then** all configuration changes roll back.

### Deferred Integration - LIM-09/10 (outside this implementation)

Operations performs protected proposal/decision actions with step-up and audit, and subsequent reads/transfers use the effective limit.

**Why this priority**: These actions require existing admin, identity, account and transfer capabilities.
**Independent Test**: Actual endpoint integration, real approval/identity/audit, read and transfer consumers; provider harness is not evidence of this story.

**Acceptance Scenarios**:
1. **Given** a permitted maker with verified step-up, **When** requesting changes, **Then** approval, pending configuration and requested audit are committed together; missing permissions/step-up prevent creation.
2. **Given** a permitted independent checker, **When** deciding, **Then** approval/configuration/audit are atomic and subsequent reads/enforcement see correct limits without resetting usage.

### Edge Cases

- Policy values change between proposal and approval.
- Concurrent proposals or decisions, including the first configuration in a scope.
- Future-effective proposal must not silently leave a gap in effective limits.
- First account-level configuration during a day with earlier transfers must follow a clarified usage policy. A transaction that started before activation must fail closed rather than skip the newly effective scope.
- Lowered limits below already-used amount must preserve usage; further spending is blocked and displayed remaining cannot be negative.
- Self approval, unknown/mismatched approval, previously decided proposal, and errors after partial changes.

## Requirements

### Functional Requirements

- **E4-FR-001**: Validate positive, representable VND values, per-transaction ≤ daily, scope and agreed policy using explicitly configured per-transaction/daily maxima; absent policy rejects proposal and approval before persisting a pending proposal linked to its approval (LIM-07; SRS FR-032/033; DB §4.5.1).
- **E4-FR-002**: Pending proposals must not alter effective configurations or usage; allocate and retain distinct scope versions and attribution.
- **E4-FR-003**: All mutations participate in the caller's transaction, including rollback of later caller/audit failures; limit must not write admin-owned approval data.
- **E4-FR-004**: Approval revalidates policy, prohibits maker=checker and repeat application, supersedes the old configuration and activates the proposed version atomically; rejection preserves the active version (LIM-08).
- **E4-FR-005**: Serialize competing decisions for one scope, including a scope without existing configuration; retain every historical version and do not reset/increment usage on configuration changes.
- **E4-FR-006**: Reject future-effective proposals and activate immediately on approval; refuse newly effective account scope when customer amount/count for the decision business day is nonzero (D3/D4). Preserve pending for a later unused day. Earlier-started enforcement must not bypass a scope activated after its fixed evaluation instant.
- **Deferred LIM-09 (not a current build obligation)**: Maker integration must enforce authorization, cookie CSRF and verified step-up before atomic approval/configuration/audit creation, using canonical responses (LIM-09).
- **Deferred LIM-10 (not a current build obligation)**: Checker integration must enforce independent checker and step-up, atomically decide approval/configuration/audit, and verify actual read/transfer consumers (LIM-10).
- **E4-FR-009**: Distinguish provider evidence from unavailable integration; do not build fake production dependencies to claim endpoint acceptance.

### Key Entities

- **Limit configuration**: customer, optional account, positive VND per-transaction/daily amounts, effective interval, version, status, maker/checker, timestamps and linked approval identity.
- **Approval request**: admin-owned proposal/decision record; limit only receives its identity and acts within the admin caller transaction.
- **Daily usage**: existing scope/date counters, preserved across configuration changes.

## Success Criteria

### Measurable Outcomes

- **E4-SC-001**: Every accepted proposal remains pending until a valid independent decision; no proposal changes current allowance or usage.
- **E4-SC-002**: In competing decisions, exactly one decision per proposal succeeds, each scope has at most one active version, and all versions remain traceable.
- **E4-SC-003**: Rejection, invalid input and injected transaction failure leave no partial configuration changes or usage resets.
- **E4-SC-004**: Every acceptance claim is supported by relevant automated evidence; package build succeeds and independent convergence reports zero gaps in the clarified scope.

## Assumptions

- Authority: docs-common 01–11 > docs-common/design > backend. Sources: DELIVERY-PLAN §7/9; SRS FR-032/033/035/051, UC-011, AC-017/019/028; DB §4.5; API §8.5.
- Existing E1/E2 provider implementations are reused. Scope/ownership and foreign-key integrity remain dependent on real customer/account/identity/admin owners as recorded in E1/E2.
- No UI, generic approval platform, unrelated regression suite, or admin POST idempotency expansion (OI-API-19).
- Confirmed scope: LIM-07/08 provider only. LIM-09/10 and actual E3/transfer impact are deferred because their production dependencies are absent. Provider tests never certify them.
- D1: Both policy maxima are explicit configuration; absent/incomplete policy refuses proposal and approval. No built-in numerical default, auto-seed or unlimited policy. Re-read policy at decision time.
- D3: Refuse future effectiveFrom when proposing and approving; approval starts the new version at the decision time, without backdating or a scheduled activation gap. D4: When no currently effective account configuration exists, refuse activation if the customer has used amount or completed transfers that business day. Keep the proposal pending for a later unused day; never reset/backfill counters. Apply this also when an account has historical but no effective tracking configuration.
- D5 HTTP source conflict is deferred with LIM-10. Provider rejects self-decisions with existing ROLE_CONFLICT code, without certifying any HTTP endpoint mapping.
- Configuration changes never create or write usage rows. Proposal and decision serialize with enforcement under a common customer lock; existing daily counters remain unchanged.
- Real FK/reference existence, ownership, permissions, verified step-up and atomic approval/audit orchestration remain caller responsibilities; do not accept untrusted IDs directly as a public API.
