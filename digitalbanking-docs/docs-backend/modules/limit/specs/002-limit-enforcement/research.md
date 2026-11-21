# Research — LIM-03/04

- Decision: use transaction_timestamp from PostgreSQL, injected configured ZoneId. Rationale: design §9.2 fixes business date at T2 start and avoids instance clock skew. Alternatives: Clock/LocalDate.now rejected; statement_timestamp/clock_timestamp can advance during a transaction.
- Decision: opaque checked handle, separate recordCompleted in same caller transaction. Rationale: caller must execute fraud/money between check and increment; handle captures scope/date/amount. Callback considered but would invert orchestration and obscure business rejection outcomes. Transaction synchronization identity prevents reuse outside original transaction; completed flag prevents repeated increment.
- Decision: business rejection does not mark caller rollback-only at lockAndCheck proxy. Rationale: design §5.3/DD-CON-11 requires caller to commit FAILED; default E1 exception propagation would mark rollback. Technical and completion errors remain fail-closed.
- Decision: grant column-specific usage UPDATE via V4. Rationale: E1 V3 only grants reads/inserts; immutable identity/scope/date and config history remain protected. Broad table UPDATE rejected.
- Decision: retain customer mutex for account-only/absent config, lock customer before account, use insert-on-conflict then row lock. Rationale: design §5.3/7.5, unique first-day race and shared customer totals/fraud serialization.
- Decision: no production consumer simulation. User explicitly deferred LIM-05; real transfer replay/recovery/ledger remain unaccepted. Harness proves provider atomicity only.

Research agent independently inspected existing local source/contracts; no dependency/version change or external reference required.

- Review remediation: Spring synchronization membership/actualTransactionActive remain visible in afterCommit. Expire existing handles in beforeCompletion, and verify the bound EntityManagerHolder physical EntityTransaction.isActive at provider entry. This uses the backend's existing JpaTransactionManager, no new transaction abstraction or dependency. Separate PostgreSQL regressions cover old-handle and fresh first provider entry from afterCommit; both failed before their fixes.
