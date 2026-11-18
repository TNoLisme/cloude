# Research

- 07 §8.5.1 and DEC-LIM-001 settle selected scopes; no GLOBAL fallback.
- 04 §4.5 owns `limit_config` and `limit_usage`; one joined read statement gives a consistent PostgreSQL statement snapshot without locking or mutation.
- 06 §6.3/Modules contract §7 require provider API for account ownership; current AccountQuery has no implementation. Fail closed and use tests with fake.
- Security C3 audit needs a generalized provider API; current event requires approvalId, cannot represent account denial. Log and metric fallback as a documented gap until audit owner supplies implementation.
- Existing limit services use JdbcTemplate; feature read path is separate Spring Data JPA, avoiding unrelated changes to money path.
- Business timezone configuration and UTC Clock already exist in common; D2 remains unconfirmed for acceptance.
