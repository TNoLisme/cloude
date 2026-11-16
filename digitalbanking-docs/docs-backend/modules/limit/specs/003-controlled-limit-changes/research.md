# Research

Phase 0 research delegated to limit_change_research; repository-only, no files edited by researcher. No technology unknown remains.

- **Decision**: caller-owned physical writable READ COMMITTED transaction. **Rationale**: matches existing E2 and real AuditRecorder; afterCommit resources alone are insufficient. **Alternative**: REQUIRES_NEW rejected because breaks atomic approval/audit.
- **Decision**: shared customer advisory lock added before E2 usage locks, plus account key for changes. **Rationale**: prevents enforcement/activation race without any usage writes in configuration operations. **Alternative**: reuse lockUsage rejected because creates zero usage row; config row lock alone cannot cover first scope or transfers.
- **Decision**: distinct scope versions via max+1 under advisory lock; unique nonnull approval link. **Rationale**: concurrent preparation and repeats are explicit requirements. **Alternative**: global sequence for version or overwriting old rows rejected; legacy E1 direct history can have repeated version so no retroactive version unique constraint.
- **Decision**: explicit Environment policy with no defaults; re-evaluate at prepare and approve. **Rationale**: user clarified D1; rejects missing/invalid settings while keeping existing reads/enforcement operational. **Alternative**: copying app.transfer defaults would silently close open policy.
- **Decision**: reject future effectiveFrom, activate at decision time, reject first untracked account scope on a used customer day. **Rationale**: clarified D3/D4; no scheduler/backfill/transfer dependency expansion.
- **Decision**: retain E2 fixed instant. **Rationale**: prior clarified contract; old in-flight requests may fail closed and are disclosed. **Alternative**: silently changing asOf to wall clock would violate E2 date/time evidence.
- **Decision**: limited V5 grants, no placeholders/FK fabrication. **Rationale**: ownership and dependency separation in DELIVERY-PLAN; actual integration remains deferred.
