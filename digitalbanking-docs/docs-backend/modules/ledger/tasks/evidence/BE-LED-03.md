# BE-LED-03 — Evidence provider posting

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-LEDGER-03-EVIDENCE`
> **Ngày chạy:** 17/11/2026
> **Trạng thái:** Component/candidate evidence — chưa nghiệm thu integration production
> **Owner:** Phương — Ledger; **Reviewer chờ:** Transfer, Account, DB, Architect, Audit, Security, QA

## 1. Phạm vi và môi trường

- **Revision / working tree:** `dev2@9d96c96` + thay đổi chưa commit của BE-LED-03 (provider `banking.ledger`, test mới) trên working tree đang có candidate BE-LED-01/02 và in-flight BE-TRF-06/07. Không commit/push/merge; không sửa module khác.
- **Java:** Temurin 21.0.1 (arm64). **Gradle:** 8.14.3. **Spring Boot:** 3.5.7. **Flyway:** 11.7.2. **PostgreSQL:** 16.15 (`postgres:16-alpine`, Testcontainers).
- **Role / datasource:** migration chạy bằng `banking_migrator`; posting runtime bằng `banking_app` (SELECT/INSERT trên `ledger_entry`). Harness bootstrap `docker/postgres/init/01-create-roles.sh`.
- **Flyway locations:** `classpath:db/ledger-fixture` (V1 synthetic PK Account/Transfer) + `classpath:db/ledger-candidate` (V2 `create_ledger_entry`). Đây là chain candidate, **không** phải chain production.
- **Transaction manager:** `JpaTransactionManager` primary trên datasource `banking_app`; service `LedgerPostingService` là Spring proxy `@Transactional(MANDATORY)`. Caller fixture mở T2 bằng `TransactionTemplate`. Negative test dùng `JdbcTransactionManager` (manager khác) và transaction read-only.
- **EntityManagerFactory:** `hibernate.hbm2ddl.auto=validate`, `default_schema=banking`, `jdbc.time_zone=UTC`, scan `banking.ledger.adapter.out.persistence`. Context khởi động thành công = Hibernate validate mapping thật đạt trên candidate (LED-POST-13).

## 2. Lệnh tái lập

Từ `digitalbanking-backend` (Java 21, Docker sẵn sàng):

```sh
./gradlew test --console=plain \
  --tests 'com.simulatedbank.banking.ledger.*' \
  --tests '*ModuleBoundaryArchTest' --tests '*AuditBoundaryArchTest'
```

Regression Transfer candidate (chạy riêng, không bị ảnh hưởng bởi entity Ledger):

```sh
./gradlew test --console=plain \
  --tests '*TransferSettlementCandidateTest' --tests '*TransferSettlementCandidatePostgresTest' \
  --tests '*TransferRecoveryCandidatePostgresTest' --tests '*TransferJpaCandidatePostgresTest'
```

## 3. Kết quả

| Suite | Tests | Pass | Fail | Skip |
|---|---:|---:|---:|---:|
| `ledger.domain.PostingValidationTest` (mới) | 49 | 49 | 0 | 0 |
| `ledger.domain.PairVerifierTest` (mới) | 15 | 15 | 0 | 0 |
| `ledger.application.LedgerPostingServiceTest` (mới) | 8 | 8 | 0 | 0 |
| `ledger.contract.LedgerPostingProviderContractTest` (mới, reuse oracle) | 2 | 2 | 0 | 0 |
| `ledger.persistence.LedgerPostingPostgresTest` (mới, LED-POST-01–14) | 29 | 29 | 0 | 0 |
| `ledger.contract.LedgerContractOracleTest` (regression BE-LED-01) | 32 | 32 | 0 | 0 |
| `ledger.persistence.LedgerCandidateMigrationPostgresTest` (regression BE-LED-02) | 14 | 14 | 0 | 0 |
| `architecture.ModuleBoundaryArchTest` | 10 | 10 | 0 | 0 |
| `architecture.AuditBoundaryArchTest` | 3 | 3 | 0 | 0 |
| **Tổng ledger + arch** | **162** | **162** | **0** | **0** |
| `TransferSettlementCandidateTest` (regression) | 26 | 26 | 0 | 0 |
| `TransferSettlementCandidatePostgresTest` (regression) | 7 | 7 | 0 | 0 |
| `TransferRecoveryCandidatePostgresTest` (regression) | 26 | 26 | 0 | 0 |
| `TransferJpaCandidatePostgresTest` (regression) | 8 | 8 | 0 | 0 |

**103 test mới** cho provider BE-LED-03; toàn bộ PASS, 0 failure/error/skip. `BUILD SUCCESSFUL`.

## 4. Mapping vector LED-POST → test

| ID | Test | Mức evidence |
|---|---|---|
| LED-POST-01 | `validCommandCommitsFullPairWithGeneratedIdsAndRoundTrip` | PostgreSQL: commit caller, đọc connection mới; đủ field §7.3, ID đúng phía, tiền round-trip, `created_at` có giá trị. |
| LED-POST-02 | `invalidIdsRejectBeforeInsertAndLeaveNoEntry` + `LedgerPostingServiceTest.rejectsInvalidCommandBeforeAnyAppend`/`rejectsNullCommandWithoutTouchingStore` | Unit spy không ghi + PostgreSQL count 0. |
| LED-POST-03 | `amountAndBalanceBoundariesRejectOrCommit` + `PostingValidationTest` | Unit + PostgreSQL: amount dương, balance 0 hợp lệ, 0.01/max accept, overflow reject trước ghi. |
| LED-POST-04 | `acceptsScaleUpToTwoAndStoresScaleTwo`, `rejectsExcessScaleOrOverflowBeforeWrite`, `rejectsBalanceScaleAboveTwo` + `PostingValidationTest` | Scale 0/1/2 accept (lưu 1.00), `1.000`/`1.235`/`1E+17`/balance scale>2 reject; so sánh `compareTo`. |
| LED-POST-05 | `currencyAndCorrelationBoundaries` + `PostingValidationTest` | Currency null/lower/khác VND; correlation rỗng/65 ký tự/ký tự sai reject, 64 accept; không INSERT khi sai. |
| LED-POST-06 | `failsWithoutTransactionAndLeavesNoEntry`, `readOnlyCallerTransactionDoesNotCommitEntries`, `wrongTransactionManagerIsNotAValidPostingContext` | Wiring thật: MANDATORY fail ngoài TX; read-only không commit; manager khác (`JdbcTransactionManager`) fail, không mở TX ngầm. |
| LED-POST-07 | `callerFailureAfterPostingRollsBackBothEntries`, `failureAfterFirstInsertRollsBackDebit`, `failureAfterSecondInsertRollsBackBoth` | PostgreSQL: 0 entry mới sau rollback ở cả ba điểm, đọc connection mới. |
| LED-POST-08 | `creditForeignKeyViolationPropagatesAndDebitIsNotCommitted`, `flushAndReadBackFailuresPropagateWithoutPostingRef` | FK thật (destination orphan → `23503`); flush/read fault injection đồng bộ; DEBIT không commit. |
| LED-POST-09 | `repostOverExistingPairFailsAndPreservesCommittedRows`, `repostOverPartialSeqOneOrSeqTwoFailsWithoutRepair` | Unique `23505` thật; không replay/sửa partial; dòng commit trước giữ nguyên. |
| LED-POST-10 | `readBackAmountMismatchIsDetectedAndRealInsertsRollBack`, `schemaBlockedCorruptionsAreRejectedViaMockedProjectionAndRollBackRealInserts` + `PairVerifierTest` | Domain từ chối từng mutation; rollback sau hai INSERT thật. Corruption bị schema chặn → mocked projection (disclosed). |
| LED-POST-11 | `correlationMismatchWithValidCountAndSumRollsBackEvenIfCallerSwallowsAndCommits` + `PairVerifierTest.rejectsWrongCorrelationEvenWhenCountAndSumAreValid` | Count 2 + sum 0 vẫn fail; caller catch rồi cố commit → `UnexpectedRollbackException`, count 0. |
| LED-POST-12 | `concurrentSameTransferSerializesOnUnique` (winner commits={true,false}) | Hai connection, barrier/latch + `pg_blocking_pids`; winner commit → loser `23505`; winner rollback → loser ghi được; luôn đúng 1 cặp. Không phải proof fencing T2 thật. |
| LED-POST-13 | `hibernateValidatedRealLedgerMappingOnCandidateSchema`, `runtimeCanInsertSelectButNotMutateLedger` + regression `LedgerCandidateMigrationPostgresTest` | Hibernate validate mapping thật trên candidate; runtime insert/select được, UPDATE/DELETE bị ACL từ chối; schema suite chạy lại. |
| LED-POST-14 | `consumerFixtureMapsIntentAndAccountFactsThenAuditFailureRollsBack` | Component fixture mapping intent/Account → command; audit failure (fixture throw) sau posting → rollback. Chưa thay Transfer/Account/Audit thật. |

## 5. Artifact synthetic và giới hạn fixture

- Dữ liệu tổng hợp: account 1001/1002, transfer sinh bởi fixture; correlation dạng `transfer:<id>`/`c`/`seed:<id>`. Không PII/secret.
- `TestLedgerEntryStore` là seam fault-injection **test-only** (không có trong production adapter). `InMemoryStore` trong contract test là fake test-only.
- **Append-only chặn corruption thật:** trigger `55000` + CHECK/unique khiến UPDATE/DELETE/TRUNCATE trên `ledger_entry` không thể thực hiện kể cả test-only. Vì vậy vector read-back lệch (LED-POST-10/11) dùng mocked projection; hai INSERT và rollback vẫn thật trên PostgreSQL. Đây là giới hạn đã disclosure, không phải proof corruption vật lý.
- Fixture FK Account/Transfer test-only; **chưa** chứng minh money path, fence, idempotency hay T2 liên module thật (BE-LED-04/08).
- Hibernate validate đạt trên **candidate**; chưa đóng gate validate/chain production vì `ledger_entry` chưa trong Flyway production.

## 6. Pre-existing failures (không thuộc BE-LED-03)

- Full-context PostgreSQL test (vd `IdentityAuthPostgreSqlIntegrationTest`, `RbacAuthorityPostgreSqlIntegrationTest`) fail tại Flyway do **duplicate V2** (`V2__create_audit_log.sql` + `V2__create_identity_and_rbac_tables.sql`) — gate LED-OI-05/BE-LED-02, owner DB.
- Full-context test loại trừ DataSource (vd `HealthEndpointTest`) fail do `audit.adapter.out.AuditJdbcWriter` (`@Component` đã commit) cần `JdbcTemplate`.
- **Đã xác minh bằng worktree HEAD tách biệt (`9d96c96`, không có thay đổi Ledger):** các test trên fail y hệt. BE-LED-03 không thêm Spring bean nào vào context loại trừ JPA (service/adapter là class thường không component-scan; `@Repository` interface chỉ do Spring Data quản lý khi JPA bật). Không sửa migration/audit/transfer của owner khác.

## 7. Gate còn Blocked / chờ review

- [ ] Chain migration production: xử lý duplicate V2, predecessor Account/Transfer/Ledger, version duy nhất (DB owner).
- [ ] Ghép T2 với Transfer/Account/Limit/Audit thật, fence, money atomicity (BE-LED-04/08).
- [ ] Public `LedgerQuery.findEvidence`/recovery/reconciliation (BE-LED-06).
- [ ] Review DTO/error/transaction/snapshots của Transfer/Ledger/DB/QA và Security/Audit theo phạm vi.
- [ ] Kích hoạt provider production (đăng ký bean) chỉ sau khi chain + wiring được review; hiện service/adapter đăng ký tường minh trong test.

Xem [đặc tả BE-LED-03](../BE-LED-03.md), [DEC-LED-005](../../../../../../DECISIONS.md) và [Modules contract §9](../../../../../docs-common/design/05.%20Modules%20contract.md).