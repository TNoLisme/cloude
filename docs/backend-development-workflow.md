# Backend Feature Delivery Workflow

## 1. Purpose

Use this workflow for every backend feature, behavior change, API change, schema change, security fix, and meaningful refactor in `Cloud/cloude`.

The workflow prevents implementation from starting with unclear requirements. Each update gets a reviewed spec, implementation, relevant tests, diff review, and handover.

## 2. Documentation layout and authority

```text
docs/
├── README.md                         # Documentation index
├── baseline/                         # Approved MVP-wide reference documents
│   ├── README.md
│   ├── mvp-requirements-and-architecture.md
│   ├── api-and-team-contract.md
│   ├── mvp-decision-record.md
│   ├── quality-security-and-cloud.md
│   └── architecture-defense-and-finops.md
└── projects/                         # One feature/update spec per work item
    └── YYYY-MM-DD-<feature-name>.md
```

`contracts/openapi.yaml` remains at repository path `contracts/openapi.yaml`. It is the machine-readable source of truth for API paths, schemas, security schemes, status codes, and response shapes.

Use documents in this order:

1. `contracts/openapi.yaml` for exact HTTP contract.
2. `docs/baseline/` for approved MVP scope, domain behavior, architecture, security, quality, and decisions.
3. `docs/projects/` spec for the currently approved change. It may refine baseline behavior only when the spec records an explicit approved change.
4. Implemented code and tests as evidence of current runtime behavior. If code conflicts with approved contract or spec, report the mismatch; do not silently treat implementation as approval.

Do not copy the full baseline into every feature spec. Link to applicable baseline sections and record only feature-specific requirements, decisions, and changes.

## 3. One spec per update

Before editing implementation code, create or update one spec in `docs/projects/`:

```text
docs/projects/YYYY-MM-DD-<feature-name>.md
```

Use a new file for a distinct update. Update an existing spec only when continuing the same work item; preserve its decision history and status. Do not combine unrelated features into one spec.

Each spec must contain:

1. **Status and metadata** — proposed, awaiting clarification, awaiting approval, approved, implementing, testing, complete, or blocked; date; related issue/branch when provided.
2. **Goal and context** — user/business problem and desired outcome.
3. **Scope** — included behavior and explicit non-goals.
4. **Baseline references** — exact relevant files/sections and applicable OpenAPI operations/schemas.
5. **Requirements and behavior** — actors, preconditions, success path, state transitions, validation, failure cases, authorization, privacy, idempotency, and audit behavior as relevant.
6. **API and data impact** — request/response/error changes, database entities/constraints/indexes, migrations, compatibility and module ownership. State “none” when unchanged.
7. **Design decisions** — choices, alternatives considered, trade-offs, and whether each item is already decided or newly proposed.
8. **Acceptance criteria** — observable, testable statements covering success, failure, security and consistency as relevant.
9. **Test plan** — targeted unit, integration, contract, concurrency, security, and/or E2E checks; name test files or commands when known. Never target shared or production data for destructive tests.
10. **Implementation plan** — dependency-ordered vertical-slice steps and expected files/modules, refined after repository inspection.
11. **Implementation and verification record** — files changed, commands executed, actual results, failures/limitations, diff review, and handover notes. Fill during implementation; do not invent evidence in advance.

## 4. Per-update workflow and approval gates

### Stage A — Intake and baseline check

1. Restate the requested outcome and identify affected feature/module.
2. Read the applicable baseline docs and OpenAPI sections. Inspect existing code and tests in the affected area before proposing implementation.
3. Check current Git status. Preserve unrelated user changes; never overwrite or clean them.
4. Classify the request as a feature, bug fix, API/DB change, security change, or refactor. Identify risk and dependencies.

**Gate A:** scope is bounded enough to write a meaningful spec. If not, list the specific unresolved questions and ask the user before implementation.

### Stage B — Write detailed spec

1. Create the feature spec under `docs/projects/` using the required structure.
2. Compare proposed behavior against baseline and OpenAPI.
3. List every ambiguity that changes user-visible behavior, API compatibility, authorization, money movement, persistence, security, or test acceptance.
4. Separate confirmed requirements from assumptions and proposals. Do not turn an assumption into a requirement.
5. Include a focused implementation and test plan.

**Gate B:** spec is internally consistent and has explicit acceptance criteria. If a baseline or contract conflict exists, show it in the spec and stop the affected implementation.

### Stage C — Clarification and approval

1. Ask the user concise, numbered questions for decisions that cannot be resolved from approved docs or safe conventions.
2. For each question, state recommended option and consequence where useful.
3. Update the spec with the user's answers. Mark answered decisions as approved; leave no unresolved consequential assumption hidden.
4. Present the final spec summary and request explicit approval to implement that scope.

**Gate C:** do not modify application code, database schema, OpenAPI, or runtime configuration until the user approves the spec. Approval applies only to that spec and scope.

If no consequential questions remain, state that in the approval request; do not manufacture questions. Approval is still required before implementation.

### Stage D — Implement in small vertical slices

1. Re-check working tree and target files before edits.
2. Implement one behavior path at a time across API, application/domain logic, and persistence.
3. Add or update tests with the behavior. Prefer isolated mocks/fakes for unit tests; use an isolated PostgreSQL-compatible test database/container where database semantics matter.
4. Preserve module ownership and approved API behavior. No cross-module repository access. No Kafka, Outbox, Saga, read replica, microservices, or other post-MVP infrastructure unless a separately approved spec changes scope.
5. Update OpenAPI only when approved behavior requires a contract change. Keep FE contract generation and compatibility in view.
6. Record implementation progress in the spec. Stop and ask if new evidence forces a scope or design change.

### Stage E — Test and verify

Run only relevant checks, at minimum:

- Compile/build for the backend module.
- Focused unit tests for changed rules.
- Integration tests for persistence, constraints, transaction behavior, or row locks when affected.
- Contract validation/tests for changed endpoints or schemas.
- Security/authorization tests for changed access paths.
- Targeted E2E tests when a complete user flow or FE-visible contract changed.
- Formatting/static checks required by the project.

Report exact commands and actual results. Distinguish passed, failed, skipped, and blocked checks. Do not claim a test passed without fresh output. If a relevant check cannot run, state why and what remains unverified.

### Stage F — Review and handover

1. Inspect full scoped diff and Git status.
2. Check changes match approved spec; remove unrelated edits, temporary traces, secrets, and accidental generated files.
3. Confirm comments/docs are durable and contain no credentials or local-only instructions.
4. Update the feature spec with actual files, test evidence, known limitations, and next steps.
5. Report:
   - Goal and implementation summary.
   - Changed files.
   - Tests/checks with exact outcomes.
   - Contract/database impact.
   - Known gaps or risks.
   - Git status.
6. Do not commit, push, or stage files unless user explicitly asks. After staging for conflict resolution, stop and report status.

**Gate F:** mark spec `complete` only when approved acceptance criteria are implemented and verified. Otherwise mark `blocked` or `in progress` and list remaining criteria.

## 5. Handling changes during implementation

If implementation reveals a missing requirement or a different design is needed:

1. Stop work on the affected behavior.
2. Record new evidence and the proposed change in the feature spec.
3. Identify impact on API, DB, FE, security and tests.
4. Ask the user to approve the revised scope when it is consequential.
5. Resume only after approval; re-run tests affected by the change.

Do not expand scope silently. Do not treat previous approval as approval for a new feature or breaking contract change.

## 6. Completion checklist

- [ ] Feature spec exists under `docs/projects/` and links to applicable baseline/OpenAPI sources.
- [ ] User clarified consequential unknowns and approved implementation scope.
- [ ] Acceptance criteria are specific and testable.
- [ ] Code follows approved architecture and module ownership.
- [ ] Relevant tests/checks ran; actual results are recorded.
- [ ] Contract and database impacts are documented and compatible.
- [ ] Diff reviewed; unrelated edits, secrets and temporary traces absent.
- [ ] Spec status and handover reflect actual state.
- [ ] No commit or push occurred without explicit instruction.
