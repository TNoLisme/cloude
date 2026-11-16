# Data model

Canonical limit_config (DB §4.5.1) and limit_usage (§4.5.2) remain unchanged. No new production table or admin data write.

- Proposal: customerId > 0; optional accountId > 0; makerId/approvalId > 0; currency VND (null defaults VND); amounts > 0, at most 17 integer/2 fractional digits, perTransactionLimit <= dailyLimit; both within explicit policy maxima. effectiveFrom null defaults current DB time; future rejected.
- Version: one plus maximum version across all statuses for the canonical scope. Pending references one distinct approval_id; V5 unique index ignores null seed links. Maker and creation timestamp retained.
- PENDING_APPROVAL → ACTIVE: independent checker, valid current policy/time and D4; previous ACTIVE → SUPERSEDED with end no later than activation (never extend an already expired interval); new start/approval timestamp set to decision time, end null.
- PENDING_APPROVAL → REJECTED: no active update, approved_by/approved_at remain null. Checker belongs to external approval record.
- Terminal versions cannot be decided again. Never hard-delete, change amounts or scope, reset/increase usage, or invent approval/FK references.
- Usage is read for D4 by customer/date under shared enforcement lock, never created/updated by change workflow. New account activation requires no customer spending in that day if account has no effective config.
