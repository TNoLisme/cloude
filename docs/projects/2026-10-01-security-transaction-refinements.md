# Six approved backend design refinements

Date: 2026-10-01. Status: documentation update complete; runtime phase implementation remains pending. Scope approved in the project conversation.

## Goal and scope

Make the six reviewed transaction/security decisions concrete before backend implementation. Update Phase 02, Phase 05, Phase 06 and the roadmap. No application implementation, new architecture, FK/staff-identity changes or additional risk rules are included.

References: [roadmap](backend-mvp/00-roadmap.md), [security](backend-mvp/02-shared-security.md), [transfers](backend-mvp/05-transfers.md), [history/audit/risk](backend-mvp/06-history-audit-risk.md), [API baseline](../baseline/api-and-team-contract.md), [quality baseline](../baseline/quality-security-and-cloud.md), [OpenAPI](../../contracts/openapi.yaml).

## Approved requirements and decisions

1. PIN verification locks/checks/updates PIN state in a short independent REQUIRES_NEW service call before the money transaction. Commit typed outcome before HTTP error mapping; no outer lock on the same row.
2. Each transfer OTP confirm uses one transaction. Wrong attempts and fifth-attempt FAILED/invalidation commit before error mapping. Valid consume, debit, credit, completion and audit are atomic; technical failure rolls all back. Completed replay precedes OTP validation. No nested wrong-OTP transaction.
3. CursorPosition version/timestamp/id becomes JSON UTF-8 then Base64 URL-safe without padding. Validate decoded content and preserve access filters; no delimiter splitting.
4. Independent IP and identifier buckets; both need quota. User explicitly retained prior numeric limits for each bucket: login 5/60s; registration OTP and recovery initiate 3/300s. Same behavior for registered/unregistered recovery identifiers; per-instance storage remains.
5. Commit challenge/transfer before dispatch. Failure/timeout triggers conditional AWAITING_OTP -> FAILED and challenge invalidation in a new transaction. Preserve terminal states. No Outbox; crash or compensation failure may leave pending state until expiry. Replay does not resend; a new intentional operation needs a new key.
6. Publish event in the transfer transaction; AFTER_COMMIT listener invokes a separate proxied RiskEvaluationService with REQUIRES_NEW. Catch evaluator and commit failures outside the service. Deduplicate flags. After-commit execution is not automatically asynchronous; latency and possible lost events remain visible limitations.

## User clarifications approved

Dispatch mapping approved: initial 503 SERVICE_UNAVAILABLE and conditional FAILED with OTP_DISPATCH_FAILED; same-key replay returns current Transfer without resend. Terminal states remain preserved in races.

Existing mismatch resolved by explicit user choice: fifth wrong transfer OTP returns 409 STATE_CONFLICT and commits FAILED/invalidation; attempts 1–4 return 400 OTP_INVALID. Later confirms on FAILED return 409 STATE_CONFLICT. Phase 05 now follows the API baseline.

## API and data impact

No schema/migration or public route/response-shape change in this documentation update. Cursor stays opaque. Rate-limit admission behavior changes as approved. OpenAPI createTransfer description and API baseline now record approved dispatch failure behavior using the existing 503 response and string failureCode.

## Acceptance and verification plan

Keep the three phase designs and roadmap consistent. Add future acceptance cases for persisted failure counters, OTP-money rollback, confirm/replay races, dispatch compensation/expiry races, JSON cursor parsing/access control, independent limiter quotas and committed risk flags despite isolated risk failures.

Implementation order for this update: inspect current files and approval scope; edit approved designs; check diff/format and stale contradictory wording; record results. Runtime tests belong to the future implementation phases; this repository currently has no Java implementation to run for these changes.

## Verification record

Initial working tree clean. Updated roadmap, Phases 02/05/06, API/quality baseline and OpenAPI descriptions using apply_patch. `git diff --check` passed. Reviewed scoped diff and removed old combined limiter keys, delimiter-based cursor instructions and nested OTP transaction wording from these phase designs. Python YAML parse could not run because the available python.exe is a WindowsApps alias that fails to start in this session; full YAML/schema validation remains unverified. No runtime tests run: this update is a design/contract-description change and no Java implementation is present. No commit/push performed.
