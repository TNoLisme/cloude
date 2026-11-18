# BE-ACC-11 — PostgreSQL concurrency, security và failure testing

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-11
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** **COMPLETED (Account scope)** — 13 suite / **128 test PASS** trên PostgreSQL 16 Testcontainers, gồm 6 concurrency test mới, 3 audit adapter test mới và 2 HTTP write-verb security test mới
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần Architect + DBA + Security/QA
> **Branch:** `datht-backend-rework`, không tạo/không switch branch, không commit/push
> **Nguồn tham chiếu:** `docs-common/design/02. consistency-concurrency.md` §5.3, §7–§8, §11, Appendix A/D (T-CON-01–04); `docs-common/design/05. Modules contract.md` §7.2; `docs-common/04. Database design & analysis.md` §4.3.1; `docs-common/07. API contracts.md` §2.3/§8.2; `docs-common/design/01. audit-traceability.md` §5.2–§5.7; [BE-ACC-05](BE-ACC-05.md)–[BE-ACC-10](BE-ACC-10.md); [DEC-ACC-010](../../../../DECISIONS.md)

---

## A. Phạm vi

| Hạng mục | Kết quả |
|---|---|
| PostgreSQL concurrency tests | **MỚI** `AccountConcurrencyPostgreSqlIntegrationTest` — 6 test: state transition vs debit, READ COMMITTED visibility, 10 T2 tranh chấp vượt số dư, tuần tự trong cùng transaction, duplicate request boundary, unique blind-index race |
| Security tests | **MỚI** 2 HTTP test write-verb (`AccountControllerPostgreSqlIntegrationTest` 17 → 19) + **MỚI** `AccountSecurityLogAuditAdapterTest` 3 test; các test 401/403/404/IDOR/masking hiện hữu giữ nguyên |
| Failure/recovery tests | Đã có từ BE-ACC-06/07 (rollback, constraint violation, retry) + **MỚI** unique constraint `23505` dưới cạnh tranh và concurrent status change |
| Production code | **Không sửa** — không phát hiện defect Account cần fix trong phạm vi task này |
| Schema/migration | Không sửa `V1`–`V8`; dùng đúng `V8` canonical |
| Business rule mới | Không — mọi assertion bám `design/02` §7–§8 và DEC-ACC-006/007 |
| T-CON-01/02 đầy đủ (ledger entry/transfer state) | **NOT_RUN** — phụ thuộc BE-ACC-08 (Transfer/Ledger chưa có runtime); Account chỉ chứng minh phần money invariant |

---

## B. Runtime blocker — điều tra và kết luận

Trạng thái cũ trong checklist là `BLOCKED: runtime` nhưng **không còn log gốc** của lần chặn đó. Kiểm tra hiện trường ngày 18/11/2026:

| Hạng mục | Kết quả kiểm tra |
|---|---|
| Docker | client/server **29.7.2**, daemon hoạt động |
| Java | OpenJDK **21.0.11**, toolchain Gradle 21 |
| PostgreSQL test engine | `postgres:16-alpine` qua Testcontainers, Flyway `V1`–`V8`, Hibernate `validate` |
| Test profile | `application-test.yml` có AES/HMAC key synthetic, Flyway `clean-disabled: false` |
| Account suite | `./gradlew test --tests 'com.simulatedbank.banking.account.*'` → **BUILD SUCCESSFUL** 39s, 13 class / **128 test PASS** |
| Port/DB cố định | Không — mỗi class dùng Testcontainer + database name riêng (`it_account_*`), không xung đột |

**Nguyên nhân quan sát được của lỗi runtime (đã tái hiện):** workspace bị **nhiều phiên Gradle chạy song song trên cùng project directory**. Lúc 01:07 UTC, `:test` fail với `Could not write XML test results for …` trong khi `build/test-results/test/` bị phiên khác `clean`/ghi đè; đồng thời full build ở phiên khác giữ test worker >40 phút, load average 128, RAM 11/12 GiB và swap cạn. Đây là **xung đột môi trường thực thi**, không phải lỗi cấu hình repo hay code Account.

**Cách xử lý:** chạy verification trong bản sao cô lập `/home/kali/acc11-verify` (cùng source working tree, `build/` riêng), Docker dùng chung. Không cần sửa `build.gradle.kts`/test profile cho Account. Các suite Account chạy ổn định trong cả workspace và bản sao.

**Phân loại:** Docker/PostgreSQL **khả dụng**; blocker runtime cũ **RESOLVED** cho phạm vi Account. Full-suite regression bị ảnh hưởng bởi phiên song song — xem [BE-ACC-12](BE-ACC-12.md) §D.

---

## C. Concurrency verification

### C.1. Test mới — `AccountConcurrencyPostgreSqlIntegrationTest`

PostgreSQL 16 thật, connection pool 16, transaction độc lập, `CountDownLatch` + timeout có bound (30s), không sleep điều khiển thứ tự.

| Test | Kịch bản | Oracle |
|---|---|---|
| `concurrentStatusChangeAndDebitSerializeAndNeverDebitANonActiveAccount` | Transaction A `UPDATE status='LOCKED'` giữ row lock; T2 debit cùng row | Debit không hoàn tất khi lock đang giữ; sau commit A → debit `INACTIVE`; `available/ledger` vẫn `1000.00`; status `LOCKED` |
| `debitIsInvisibleToOtherTransactionsUntilCommit` | T2 debit `100.00` chưa commit; transaction khác đọc cùng row | Reader thấy `1000.00` (không dirty read); sau commit thấy `900.00` |
| `concurrentTransfersThatExceedTheBalanceApplyOnlyTheAffordableOnes` | 10 T2 cùng `lock → debit → credit` với `300.00` trên source `1000.00` | Đúng **3 APPLIED**, 7 `INSUFFICIENT_BALANCE`; source `100.00`, destination `1900.00`, tổng `2000.00`; account không liên quan không đổi; không invariant nào vỡ |
| `sequentialDebitsInsideOneTransactionSeeEarlierUpdatesAndStayAtomic` | Hai debit `100.00` trong cùng T2 | Cả hai `APPLIED`, kết quả `800.00` — READ COMMITTED snapshot theo statement, không lost update |
| `duplicateConcurrentDebitRequestsAreBothAppliedBecauseAccountDoesNotOwnIdempotency` | Hai request trùng chạy song song | Cả hai `APPLIED` (`800.00`) — **ranh giới đã duyệt**: Account không dedup; idempotency thuộc T1 + transfer state guard (DEC-ACC-007 quyết định 7) |
| `duplicateBlindIndexInsertRacesHaveExactlyOneWinnerAndNoPartialRow` | Hai transaction chèn cùng `account_no_hash` | Đúng 1 row; loser nhận `DataAccessException` SQLSTATE `23505`; không row một phần cho customer còn lại |

### C.2. Bằng chứng hiện hữu (không regression)

| Yêu cầu | Test |
|---|---|
| Hai transaction cùng row bị serialize, không dirty read | `AccountLockPostgreSqlIntegrationTest.secondTransactionWaitsForTheFirstCommitAndSeesCommittedBalancesWithoutDirtyRead` |
| Lock thật ở PostgreSQL (`55P03`) | `AccountLock…postgresRejectsNoWaitLockOnTheHeldRowsButNotOnUnrelatedRows` |
| Không lost update | `AccountLock…concurrentReadModifyWriteOnTheSameRowsLosesNoUpdates` |
| Không deadlock A↔B | `AccountLock…oppositeTransferDirectionsDoNotDeadlockBecauseRowsAreLockedInAscendingIdOrder` |
| Lock wait có bound | `AccountLock…lockWaitIsBoundedByAnExplicitCallerTimeout…`, `defaultLockWaitIsBounded…` |
| Rollback nhả lock, bỏ mutation | `AccountLock…rollbackReleasesTheLockAndDiscardsTheProtectedMutation` |
| 12 debit đồng thời không overdraw | `AccountBalancePostgreSqlIntegrationTest.concurrentDebitsNeverOverdrawAndNeverLoseAnUpdate` |
| 8 transfer đồng thời giữ tổng tiền | `AccountBalance…concurrentCreditsAndDebitsKeepTheTotalBalanceConstant` |
| Rollback sau credit fail/DB fail | `AccountBalance…creditFailureAfterDebitRollsBackTheWholeTransaction`, `databaseFailureAfterDebitRollsBackTheWholeTransaction` |
| Retry sau rollback không nhân đôi | `AccountBalance…rolledBackAttemptCanBeRetriedWithoutDuplicatingTheDebit` |
| Constraint precision `NUMERIC(19,2)` | `AccountBalance…maximumNumericBalanceDebitsToZeroAndCreditOverflowFailsClosed` |

### C.3. Mapping T-CON

| Case (`design/02` Appendix D) | Phần Account | Trạng thái |
|---|---|---|
| T-CON-01 — 20 transfer đồng thời, đủ balance | Không overdraw, không lost update, lock serialize | **PARTIAL** — Account PASS; đếm ledger entry/2 entry mỗi transfer **NOT_RUN** (BE-ACC-08) |
| T-CON-02 — tổng vượt balance | Đúng số lượt đủ tiền được áp dụng, lượt bị từ chối không credit đích, tổng tiền bất biến | **PARTIAL** — Account PASS; ledger khớp **NOT_RUN** (BE-ACC-08) |
| T-CON-03 — A→B và B→A đồng thời | Không deadlock ở tầng row lock (2×25 transaction) | **PASS (Account lock level)** |
| T-CON-04 — account/customer khác không ảnh hưởng | `unrelated` giữ nguyên trong mọi test; hai customer riêng | **PASS (Account scope)** |

---

## D. Security verification

| Yêu cầu | Test / bằng chứng | Kết quả |
|---|---|---|
| Authentication bắt buộc | `missingAuthenticationFailsClosedOnBothEndpoints`, `invalidAndExpiredSessionsFailClosedWithoutDisclosure` | 401 `AUTH_SESSION_EXPIRED`, không lộ dữ liệu |
| Anonymous write-verb bị chặn trước handler | **MỚI** `anonymousWriteVerbsAreRejectedBeforeReachingAnyHandler` (POST/PUT/PATCH/DELETE) | 401; row không đổi |
| Authorization theo role/permission | `staffWithoutSelfReadAuthorityCannotListOwnAccounts`, `staffWithoutAccountReadAuthorityCannotReadAnyDetail`, `customerWithSelfReadButNoStaffPermissionStillCannotReadForeignAccounts` | 403 `AUTH_FORBIDDEN` |
| Ownership / anti-IDOR | `crossCustomerDetailIsForbiddenAuditedAndLeaksNoAccountData`; path `customerId` binding ở `API-ADM-02` | 403 + C3; không trả account ngoài owner |
| Không có write path / unauthorized modification | **MỚI** `writeVerbsCannotModifyAccountDataBecauseAccountExposesReadOnlyRoutes` (3 path × 4 method, owner + staff token) | 405; response không có balance/PII; row và số row không đổi |
| Tenant isolation | Schema `V1`–`V8` không có cột tenant/organization | **N/A / BLOCKED** — ACC-DR-13 + tenant model chưa duyệt |
| Sensitive information exposure | `ownerList…`, `ownerDetail…` (exact field set), `malformedCredential…` | Chỉ masked `**** **** <last4>`; không plaintext/ciphertext/blind index/PII/token; `Cache-Control: no-store` |
| Input validation | `invalidAccountId…`, `ownerListRejectsAnUnknownStatusFilter…`, `unknownStatusFilter…`, validation `Money` | 400 `VALIDATION_ERROR` trước persistence |
| Error response consistency | `assertProblem` trong HTTP suite | `application/problem+json`, code/status/type/title/detail/instance/correlationId; không stack trace/SQL/package name |
| Audit C3 của owner mismatch | HTTP metric `account.audit.events{action=SECURITY_ACCESS_DENIED}`; **MỚI** `AccountSecurityLogAuditAdapterTest` | Metric + security log có actor/entity/reason/correlation; không PII |
| Audit failure không đổi 403 | **MỚI** `auditProviderFailureDoesNotChangeTheDeniedOutcomeAndEmitsFallbackEvidence` | Best-effort; fallback `SECURITY_AUDIT_WRITE_FAILED` + `account.audit.failures`; không throw |
| Metrics hỏng không mất security log | **MỚI** `metricsInfrastructureFailureDoesNotRemoveTheSecurityLogEvidence` | `AUDIT_METRIC_WRITE_FAILED` + `SECURITY_AUDIT` vẫn phát |
| Durable `audit_log` C3 | Chưa có module Audit runtime | **BLOCKED** — ACC-DR-15 |
| Staff data scope (chi nhánh/segment) | Chỉ gate permission `ACCOUNT_READ` | **BLOCKED** — ACC-DR-13 |

---

## E. Failure & recovery verification

| Kịch bản | Test | Kết quả |
|---|---|---|
| PostgreSQL constraint violation (`CHECK balance >= 0`) | `databaseFailureAfterDebitRollsBackTheWholeTransaction` | Transaction abort; debit bị rollback; row giữ `1000.00` |
| Numeric overflow `NUMERIC(19,2)` | `maximumNumericBalanceDebitsToZeroAndCreditOverflowFailsClosed` | Credit vượt bound fail closed `DataAccessException`; balance giữ max |
| UNIQUE conflict dưới cạnh tranh | **MỚI** `duplicateBlindIndexInsertRacesHaveExactlyOneWinnerAndNoPartialRow` | `23505`; đúng 1 row; không partial write |
| Transaction rollback toàn T2 | `creditFailureAfterDebitRollsBackTheWholeTransaction` | Cả debit + credit rollback; lock nhả |
| Duplicate request / idempotency boundary | **MỚI** `duplicateConcurrentDebitRequestsAreBothApplied…`; `rolledBackAttemptCanBeRetriedWithoutDuplicatingTheDebit` | Account **không dedup** (đúng hợp đồng); retry sau rollback không nhân đôi |
| Invalid state transition | `nonActiveSourceIsInactiveForDebitAndSystemErrorForCredit`; **MỚI** concurrent status change test | `LOCKED`/`CLOSED` → debit `INACTIVE`, credit `INTERNAL_ERROR`; không di chuyển tiền |
| Missing/invalid account | `missingAccountIsNotFoundOnDebitAndSystemErrorOnCredit`, `unknownAccountIsNotFoundAndIsNotAuditedAsASecurityDenial` | 404 `ACCOUNT_NOT_FOUND` (đọc) / technical error (debit→credit) |
| Invalid input trước SQL | `invalidAmountsAreRejectedBeforeAnySqlAndWithoutTakingLocks`, `invalidAccountId…` | 400; không lock row nào |
| Concurrent modification conflict | `concurrentReadModifyWriteOnTheSameRowsLosesNoUpdates`, new status-vs-debit test | Serialize theo row lock; không lost update |
| Idempotent retry | `rolledBackAttemptCanBeRetriedWithoutDuplicatingTheDebit` | PASS |
| Unexpected exception giữa T2 | `rolledBackAttemptCanBeRetried…` (simulated failure), `databaseFailureAfterDebit…` | Rollback; không partial write |
| Downstream Customer failure trên read path | Không có failure-injection point trong Account (CustomerQuery in-process, không có adapter fault hook) | **NOT_RUN** — ghi limitation, không tạo mock che hành vi |
| Error response consistency | HTTP problem contract + DB error pipeline suite | PASS |

---

## F. Test quality

- Mỗi integration class sở hữu Testcontainer riêng (`it_account_concurrency`, …), không chia sẻ database; reset `account → customer → app_user` trong `@BeforeEach`.
- Fixture synthetic, deterministic; không phụ thuộc thứ tự test; `@AfterEach` shutdown executor.
- Đồng bộ bằng `CountDownLatch` + timeout 30s; chỉ dùng cửa sổ quan sát có bound (700ms) để chứng minh **đang bị chặn**, không dùng sleep để quyết định kết quả.
- Assertion kiểm **persisted row** (`SELECT available_balance/ledger_balance/status/count`) và invariant SQL, không chỉ HTTP status.
- Không H2, không mock repository, không nới assertion để pass.

---

## G. Lệnh và kết quả

```bash
cd /home/kali/acc11-verify   # bản sao cô lập của working tree (cùng source; build/ riêng)
./gradlew test --tests 'com.simulatedbank.banking.account.*' --console=plain
```

| Suite | Kết quả |
|---|---|
| Account module (13 class) | **128/128 PASS** — 0 failure / 0 error / 0 skip (fresh copy 1m08s; lặp lại 33s khi build cache có sẵn) |
| `AccountConcurrencyPostgreSqlIntegrationTest` (mới) | **6/6 PASS** (chạy lặp 2 lần) |
| `AccountSecurityLogAuditAdapterTest` (mới) | **3/3 PASS** |
| `AccountControllerPostgreSqlIntegrationTest` (17 → 19) | **19/19 PASS** |
| Các suite Account còn lại | 100/100 PASS (11 class, không regression) |
| Full `clean build --max-workers=1` | **BUILD SUCCESSFUL** — 106 class / **1244 test** / 0 failure / 0 error / 0 skip (8m20s) |

Môi trường: PostgreSQL **16-alpine** Testcontainers, Flyway `V1`–`V8`, Hibernate `validate`, security chain + session filter thật; Docker 29.7.2; OpenJDK 21.0.11. Bản sao cô lập cần sibling `digitalbanking-docs` (`/home/kali/digitalbanking-docs` → repo) vì `PostgreSqlErrorPipelineIntegrationTest` đọc `../digitalbanking-docs/openapi/digital-banking-v1.yaml`. Chi tiết JUnit: [`evidence/BE-ACC-11/junit-account-suite.txt`](evidence/BE-ACC-11/junit-account-suite.txt), [`regression-flyway.txt`](evidence/BE-ACC-11/regression-flyway.txt), [`full-build.txt`](evidence/BE-ACC-11/full-build.txt).

Full-suite chỉ PASS khi chạy **không song song**; xem [BE-ACC-12](BE-ACC-12.md) §D.3 cho lịch sử lần chạy và rủi ro vận hành.

---

## H. Defect phát hiện & fix

| # | Loại | Nội dung | Xử lý |
|---|---|---|---|
| 1 | Test defect (không phải production) | `AccountSecurityLogAuditAdapterTest` so sánh `formattedMessage` bằng `AUDIT_METRIC_WRITE_FAILED`, nhưng Logback nối thêm `meter=<name>` | Sửa assertion thành `startsWith(...).contains("account.audit.events")`; chạy lại PASS |
| 2 | Môi trường | Hai phiên Gradle cùng project ghi đè `build/test-results`, gây `Could not write XML test results` | Chạy verification trong bản sao cô lập; không sửa repo |
| — | Production Account | Không có defect nào được test mới phát hiện | Không sửa code production |

---

## I. Giới hạn & blocker còn lại

| ID | Nội dung | Trạng thái | Owner |
|---|---|---|---|
| BE-ACC-08 | T2 end-to-end với Transfer/Ledger; T-CON-01/02 đầy đủ (ledger entry) | **BLOCKED** | Transfer/Ledger/Account |
| ACC-DR-13 | Staff data scope (chi nhánh/segment) | **BLOCKED** | BA + Security + Admin |
| ACC-DR-10 | `404 CUSTOMER_NOT_FOUND` cho `API-ADM-02` | **BLOCKED** | Customer/BA |
| ACC-DR-15 | Durable `audit_log` C3 | **BLOCKED** | Audit |
| Tenant/NCC scope | Schema v1 không có tenant | **BLOCKED** | Architect |
| Downstream failure injection | Không có fault hook cho `CustomerQuery` | **NOT_RUN** | Account/Customer (khi có contract) |
| Full-suite khi chạy song song | Hai phiên Gradle cùng project gây lỗi ghi XML/timeout | **PASS khi cô lập** — 1244/1244; CI không chạy song song | DevOps/người điều hành |

---

## J. Change log

| Version | Ngày | Nội dung |
|---|---|---|
| v1.0 | 18/11/2026 | Thêm 6 PostgreSQL concurrency test, 3 audit adapter test, 2 HTTP write-verb security test; Account 128/128 PASS; xác định runtime blocker là xung đột phiên Gradle song song; [DEC-ACC-010](../../../../DECISIONS.md) |
