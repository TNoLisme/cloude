# Phase 06 — History, Audit Queries, and Risk Flags

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Phases 01–05 and canonical V3/V4 in [`00-roadmap.md`](./00-roadmap.md).  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).

## Scope and endpoints

- Complete transfer history/status from Phase 05: `GET /transfers`, `GET /transfers/{transferId}`.
- `GET /audit-events` for AUDITOR/ADMIN with exact OpenAPI filters `limit`, `cursor`, `eventType`, `actorId`, `from`, `to`.
- `GET /operator/risk-flags` for OPERATOR/AUDITOR/ADMIN with `limit`, `cursor`, `ruleId`, `transferId`, `from`, `to`.
- No mutating audit or risk endpoint; no review workflow; no ML; no Outbox/Kafka/broker.

## Java components and module API

```text
audit/api/AuditModuleApi.java
audit/application/AppendAuditFactService.java
audit/application/ListAuditEventsService.java
audit/domain/AuditFact.java
audit/domain/AuditEventView.java
audit/infrastructure/persistence/AuditEventEntity.java
audit/infrastructure/persistence/AuditEventRepository.java
audit/web/AuditController.java
risk/api/RiskModuleApi.java
risk/application/TransferCommittedRiskListener.java
risk/application/RiskEvaluationService.java
risk/domain/RiskRule.java
risk/domain/LargeTransferRule.java
risk/domain/FrequencyRule.java
risk/infrastructure/persistence/RiskFlagEntity.java
risk/infrastructure/persistence/RiskFlagRepository.java
risk/application/ListRiskFlagsService.java
risk/web/RiskFlagController.java
shared/pagination/CursorCodec.java
shared/pagination/CursorPosition.java
```

```java
public interface AuditModuleApi {
    void append(AuditFact fact); // joins caller transaction
    AuditPageView list(AuditQuery query, AuthenticatedActor actor);
}
public record AuditFact(UUID actorId, String actorRole, String eventType,
                        String targetType, UUID targetId, String outcome,
                        UUID correlationId, String summary, Map<String, Object> safeMetadata) {}
public interface RiskModuleApi {
    void onTransferCommitted(TransferRiskSnapshot snapshot); // AFTER_COMMIT only
    RiskFlagPageView list(RiskFlagQuery query);
}
```

Cross-module event contains only IDs, amount/currency, committedAt, customer/account identifiers needed by rules; no contact information, credentials or entire entity graph. Audit/risk modules own persistence and query services; no direct repository imports across modules.

## Audit write policy

Audit facts for account creation, counter creation, seed, transfer, PIN lifecycle, recovery, block/unblock and required auth outcomes are appended through `AuditModuleApi` in the business transaction where atomicity is required. `append` inserts only. Query API maps entities to OpenAPI `AuditEvent`: `eventId`, `eventType`, optional `actorId`, `targetType`, `targetId`, `outcome`, `occurredAt`, `correlationId`, `summary`. Do not return metadata because OpenAPI does not expose it.

No password, password/PIN hash, raw PIN, OTP, token, secret, full auth header, raw credential identifier, or unnecessary PII in `summary`/metadata. Allowlist metadata fields per event type rather than generic request serialization. Store only safe minimal metadata.

Append-only enforcement: application exposes no update/delete method; runtime DB principal gets SELECT/INSERT on `audit_events` where grants are managed. Do not add a trigger or migration that interferes with test cleanup/owner maintenance. Tests may use isolated disposable containers.

## Cursor pagination

All three page types use OpenAPI `PageBase` and shared `Limit`/`Cursor`: 1..100, default 20; nextCursor nullable string. Use descending timestamp/ID ordering. Serialize CursorPosition with version, timestamp (ISO instant) and id (UUID) to JSON UTF-8, then Base64 URL-safe without padding. Decode with maximum 256 characters, strict UTF-8 and JSON parsing; validate supported version, required field types, Instant and UUID. Do not split delimiters. Reject malformed input as `400 VALIDATION_ERROR` if no more specific code exists. Base64 is not encryption or authorization: always reapply role/ownership and filters; never trust cursor fields as access grants.

```sql
WHERE (:cursor_time IS NULL
       OR occurred_at < :cursor_time
       OR (occurred_at = :cursor_time AND id < :cursor_id))
ORDER BY occurred_at DESC, id DESC
LIMIT :limit_plus_one;
```

Use query-specific timestamp column for each table. Fetch `limit+1`, emit first `limit`; nextCursor encodes final emitted row only if extra row exists. Bind every filter. Date filter semantics use inclusive `from`, exclusive `to` as API guide defines; reject `from >= to` with documented `INVALID_DATE_RANGE`.

### Transfer visibility

- Source owner sees every status.
- Destination owner sees only COMPLETED. Non-completed detail returns 404 `TRANSFER_NOT_FOUND`.
- Outgoing/incoming list item computes direction and counterparty through module APIs; no phone/email/full account number.
- Admin/operator/auditor detail/list permissions only where OpenAPI explicitly allows. Current OpenAPI list endpoint has default bearer security but no `x-required-roles`; follow baseline customer participant rule and do not add staff listing access absent explicit decision.

### Audit visibility

Only AUDITOR/ADMIN. `actorId` filter narrows results; never grants broader access. Filters: event type, actor UUID, time range, cursor/limit. Return only `AuditEvent` fields in schema. No target metadata/raw PII.

### Risk visibility

OPERATOR/AUDITOR/ADMIN only. Filters exact OpenAPI. Return `RiskFlag` fields only: flagId, transferId, ruleId, ruleVersion, reason, detectedAt. Read-only, no review status/notes.

## Risk evaluation after commit

Publish the event inside the active successful transfer transaction. Register `TransferCommittedRiskListener` with:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onTransferCommitted(TransferCommittedEvent event) {
    try {
        riskEvaluationService.evaluate(event.snapshot());
    } catch (RuntimeException ex) {
        log.warn("Risk evaluation failed after transfer commit", kv("transferId", event.transferId()),
                 kv("correlationId", event.correlationId()), kv("errorType", ex.getClass().getSimpleName()));
    }
}
```

RiskEvaluationService.evaluate must run with `@Transactional(propagation = Propagation.REQUIRES_NEW)` on a separate Spring-managed bean, called through its proxy, not by self-invocation. Evaluation and flag persistence commit in this new transaction. The listener catches exceptions outside the service call, including transaction commit failures. Do not log exception messages containing PII/SQL/secrets. Failure never reaches transfer caller as failure and never alters balances/status. AFTER_COMMIT is not automatically asynchronous; include synchronous listener time in latency measurement. Best-effort means process crash after transfer commit may leave a missing flag; this is accepted MVP behavior. Do not add a retry scheduler or durable queue. Re-running evaluation is safe due unique `(transfer_id, rule_id, rule_version)`; duplicate insert uses `ON CONFLICT DO NOTHING` or equivalent.

### Rules

- `LARGE_TRANSFER` version `1`: flag when amount `> 5000000`, not equal.
- `HIGH_FREQUENCY` version `1`: flag when an account initiates more than five completed transfers in a rolling ten-minute window. Count only transfers where the account is `source_account_id`; incoming transfers do not count. Window uses transfer completion time, not AWAITING_OTP creation time.
- Query only committed COMPLETED records and use fixed Clock for tests.
- One transfer may produce two flags, one per rule; uniqueness is rule-version-transfer tuple.

## Test matrix and phase gate

- Audit append transaction joins seed/transfer/status transaction; injected append failure rolls back those mutations.
- Audit repository/API has insert/read only; no update/delete route; response redaction allowlist tests.
- Cursor order, timestamp ties, page boundaries, empty page, malformed/oversized cursor, every filter, from/to boundaries.
- JSON cursor round-trip preserves timestamps containing colons and fractional seconds; invalid JSON/version/types rejected; modified cursor cannot bypass ownership filters.
- Role tests: unauthorized 401, authenticated wrong role 403, Auditor/Admin audit access; Operator/Auditor/Admin risk access.
- Customer source/destination visibility; pending destination sees 404; no contact details in transfer list.
- Risk exact threshold (5M no flag, 5M+1 flag), frequency 5 vs 6, rolling boundary, failed/pending transfers excluded.
- AFTER_COMMIT listener not invoked on rollback; invoked after commit; evaluator exception logged with transfer/correlation and transfer remains committed; repeated evaluator creates no duplicate.
- Read persisted flags from a fresh transaction to prove the new risk transaction committed; inject both evaluator and risk-commit failures and verify transfer success remains unchanged.
- Testcontainers PostgreSQL for append-only, indexes/query semantics and unique idempotence.

## Implementation loop

Inspect operation security metadata and exact Page schemas. No new review workflow. If OpenAPI lacks required role metadata, keep role behavior from approved baseline; do not edit contract. Implement missing transfer queries, audit append/query, then risk after-commit listener/query. Verify best-effort failure semantics and inspect captured logs for sensitive data. Update evidence and stop before Phase 07 pending approval.

**Verification record:** pending implementation approval.
