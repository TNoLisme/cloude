# Data model

No new tables or schema fields. Existing limit_config remains read-only here. limit_usage retains customer/account scope partial uniqueness and NUMERIC(19,2) VND constraints.

- Customer counter: customer_id, account_id NULL, usage_date; always locked/created for synchronization, incremented for every permitted completion.
- Account counter: customer_id, account_id, usage_date; created/locked/incremented only when account config applies.
- Amount: positive, at most 17 integer digits and two decimals; reject before JDBC rounding. used_amount/count remain nonnegative.
- Check: opaque runtime capability carrying amount, applicable immutable config, DB asOf/date, usage snapshots, transaction marker, completed flag. State: NOT_CONFIGURED (cannot complete), CHECKED → RECORDED. Transaction completion invalidates handle. No persisted transfer ID/idempotency state is added.
- V4 privilege delta: UPDATE only used_amount, transfer_count, updated_at for banking_app when role exists. No DELETE/TRUNCATE or config UPDATE.
