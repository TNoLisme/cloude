# Internal provider contract

LimitChanges.prepare(Proposal) → persisted PENDING_APPROVAL LimitConfiguration.
LimitChanges.approve(approvalId, checkerId) → persisted ACTIVE LimitConfiguration.
LimitChanges.reject(approvalId, checkerId) → persisted REJECTED LimitConfiguration.

Proposal carries customerId, optional accountId, perTransactionLimit, dailyLimit, currency, optional effectiveFrom, makerId and approvalId. All IDs come from a trusted admin caller after ownership/reference/auth checks. No HTTP API is created.

MANDATORY physical writable READ COMMITTED caller transaction. Caller locks/owns approval and performs actual decision and audit in same transaction; provider does not authenticate checker or verify generic approval status. Only configuration pending state is enforced internally. Checker must differ from configuration maker for either decision.

Errors: malformed input/future effectiveFrom VALIDATION_ERROR; missing/invalid max policy SERVICE_UNAVAILABLE; exceed policy LIMIT_OUT_OF_POLICY; missing linked configuration APPROVAL_NOT_FOUND; already decided or duplicate proposal link APPROVAL_ALREADY_DECIDED; self-decision ROLE_CONFLICT; D4 or inconsistent stored scope INVALID_STATE_TRANSITION. These are internal codes; D5 public HTTP mapping remains deferred.

Prepare/decision take shared customer advisory lock, then account advisory if any. E2 acquires same customer key before counters. Admin must not invert this order by holding limit config/usage locks first. Real approval row can be locked by admin before provider; provider never locks it.

Absent effective config remains NOT_CONFIGURED for E2; no default is introduced. Post-commit calls fail closed. No activation schedule; no usage backfill/reset; no endpoint/real-transfer acceptance claims.
