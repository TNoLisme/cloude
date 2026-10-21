# BE-B Audit and Risk Evidence

## Status

VERIFIED for targeted audit/risk tests. Security/image acceptance remains NOT_ACCEPTED while unresolved critical/high CVEs remain in the scanned image.

## Owner and scope

- Owner: BE-B
- In scope: Audit append/query behavior, risk rules/listing, duplicate flag prevention, related tests.
- Out of scope: auth/account/transfer business implementation, FE fraud UI/logic.

## Files

- Changed: `be/src/test/java/com/bank/simulator/risk/application/RiskEvaluationServiceTest.java`, `RiskFlagRepositoryPostgresTest.java`.
- Contract impact: none.
- DB impact: disposable Testcontainers PostgreSQL only; V4 `risk_flags` DDL applied in isolated test.
- Security/role impact: existing audit/risk authorization and redaction assertions retained.

## Verification

- Environment/profile/database isolation: Maven targeted suite; Testcontainers PostgreSQL 16.14 for uniqueness test.
- Exact command:

```powershell
mvn -f "D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be\pom.xml" -q -Dtest="*Audit*Test,*Risk*Test,DisposablePostgresTestSupportTest,RiskFlagRepositoryPostgresTest" test
```

- Expected: Audit roles/redaction/filter/cursor checks pass. Risk 5M exact does not flag; 5M+1 flags. Frequency 5 does not flag; 6 flags. Listener failure does not fail caller. Duplicate `(transfer_id,rule_id,rule_version)` insert produces one row.
- Actual: latest run: `RiskEvaluationServiceTest` 3/3; `RiskFlagRepositoryPostgresTest` 1/1. Prior audit/risk suite: 16 tests passed, 0 failures/errors/skips; PostgreSQL 16.14 with production V4 migration DDL applied.
- Result: VERIFIED for named targeted tests only. No claim for full backend suite.
- Tool/version: Maven; Testcontainers 1.21.4; PostgreSQL 16.14.

## Evidence

- `be/target/surefire-reports/`
- `evidence/BE4-container-scan.md` records current scan disposition separately.

## Dependencies

- Provider + exact input: BE-A/BE-3 evidence needed to close full audit event inventory.
- Blocked item: real end-to-end audit events emitted by all module owners not yet collected into final matrix.

## Handoff

- Consumer: BE-A, BE-3, FE/BE.
- API/DTO/state/error details: Audit rows use `audit_events`; Risk backend owns `LARGE_TRANSFER` and `HIGH_FREQUENCY` rules, FE/BE owns fraud UI/logic display.
- Next owner/action: module owners submit event evidence and FE/BE consumes final error/risk matrix.

## Limitations

- Test report count covers targeted suite, not final acceptance.
- Image scan found 8 CRITICAL and 29 HIGH vulnerabilities; do not mark image acceptance VERIFIED.
