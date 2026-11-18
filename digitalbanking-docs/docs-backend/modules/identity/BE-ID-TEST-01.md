# BE-ID-TEST-01 — Unit, contract và concurrency test suite

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-TEST-01`
> **Ngày:** 16/11/2026
> **Owner thực hiện:** Đạt (`identity`)
> **Branch:** `datht-backend-rework`
> **Baseline đã fetch:** `origin/dev2@cf60428`
> **Trạng thái:** **BLOCKED** — phần Identity độc lập đã có automated evidence; acceptance end-to-end còn thiếu Transfer/Audit/Notification runtime theo §7.

## 1. Phạm vi và nguồn kiểm thử

Nguồn ưu tiên đã đối chiếu: `03. SRS.md` AC-001/003/004/033; `07. API contracts.md` API-AUTH-01/04/05/06/07; `09. Test plan.md`; `design/02. consistency-concurrency.md` T-STEP-01/02; `design/05. Modules contract.md` §5; BE-ID-IMP-01..07 và các quyết định DEC-ID-001..008.

Tại thời điểm thực hiện `BE-ID-TEST-01`, module Identity được kiểm chứng độc lập trên PostgreSQL và MockMvc, trong khi các consumer/provider liên module (`Transfer`, `Audit`, `Notification`) được nối qua test doubles theo hợp đồng giao tiếp nội bộ.

## 2. Automated tests bổ sung

| Test method | Loại | Bất biến được kiểm chứng |
|---|---|---|
| `IdentityAuthPostgreSqlIntegrationTest.excessConcurrentFailuresSaturateLockoutWithoutChallengeOrSession` | PostgreSQL concurrency | Hai password failure đồng thời tại `failed_attempt_count=4`: cả hai bị 423, counter chốt đúng 5, account LOCKED, không challenge/session. |
| `IdentityAuthPostgreSqlIntegrationTest.concurrentDuplicateLogoutCommitsExactlyOneRevocation` | PostgreSQL concurrency | Tám revoke đồng thời cùng opaque session: đúng một mutation thành công, bảy operation idempotent, session không thể dùng lại. |
| `OtpDeliveryPostgreSqlIntegrationTest.throwingVerificationC2AuditRollsBackStateAndAllowsSafeRetry` | PostgreSQL rollback/failure injection | C2 audit failure rollback `SENT→VERIFIED` và attempt; không session; retry sau khi dependency hồi phục commit đúng một verification. Fake audit chỉ tồn tại trong test. |

Bộ test hiện hữu được tái sử dụng, không sao chép: unit cho Argon2/password/OTP/state machine/logout; HTTP/OpenAPI contract; PostgreSQL session/lockout/MFA/step-up; replay/expiry/rollback; RBAC/security; migration và module boundary.

## 3. Coverage theo functional area

| Area | Evidence chính |
|---|---|
| Password, anti-enumeration, lockout | `Argon2PasswordHasherTest`, `PasswordLoginServiceTest`, `IdentityAuthPostgreSqlIntegrationTest` |
| MFA login, OTP retry/expiry/single-use | `DeviceMfaLoginHttpIntegrationTest`, `OtpChallengeServiceTest`, `OtpChallengePostgreSqlIntegrationTest`, `OtpDeliveryPostgreSqlIntegrationTest` |
| Session/token lifecycle, logout, CSRF | `IdentityAuthPostgreSqlIntegrationTest`, `IdentitySecurityIntegrationTest`, `LogoutServiceTest` |
| Step-up binding, replay, consume, rollback | `StepUpConsumptionPostgreSqlIntegrationTest`, `StepUpVerificationHttpIntegrationTest` |
| RBAC và permission snapshot | `RbacAuthorityPostgreSqlIntegrationTest`, `IdentitySecurityIntegrationTest` |
| REST/OpenAPI/error/sensitive fields | `IdentityOpenApiContractTest`, `IdentityRestErrorMappingTest`, `IdentityRestDtoValidationTest` |
| Failure isolation | `IdentityAuthDbFailureIntegrationTest`, `OtpDispatchServiceTest`, `OtpDeliveryPostgreSqlIntegrationTest` |

Không dùng H2 cho transaction/race. Các invariant lock/rollback dùng PostgreSQL 16 Testcontainers, transaction/connection riêng, `CyclicBarrier`/latch và timeout hữu hạn; không dùng `Thread.sleep` làm oracle.

## 4. Traceability acceptance criteria

| Tiêu chí | Nguồn | Expected behavior | Test evidence | Kết quả |
|---|---|---|---|---|
| AC-001 | `03. SRS.md` §10.1 dòng AC-001 | Password hợp lệ đi qua MFA rồi tạo session; 5 lần sai khóa account; audit. | `t01_httpLoginSuccessReturnsSessionAndNoSecrets`, `t09_t10_sequentialFailuresReachThresholdAndLock`, `t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate`, `excessConcurrentFailuresSaturateLockoutWithoutChallengeOrSession`. | **BLOCKED** end-to-end: auth/session/lockout PASS; Audit durable provider chưa có. |
| AC-003 | `03. SRS.md` §10.1 dòng AC-003 | New-device password tạo OTP; đúng OTP tạo session; sai/hết hạn/5 lần bị từ chối. | `passwordOnlyAlwaysRequiresMfaAndChallengeCannotAuthenticateProtectedApi`, `otpReturnsExactAuthResponseAndConsumesProofInSamePersistedSessionDeviceBoundary`, `exhaustingOtpAttemptsLocksProofAndCorrectOtpCannotIssueSessionAfterwards`, `acceptedCodeVerifiesOnlyOnceUnderConcurrentVerificationAndCannotReplay`. | **BLOCKED** end-to-end: Identity state/session PASS; Notification contact/provider và resend durable chưa có. |
| AC-004 | `03. SRS.md` §10.1 dòng AC-004 | Idle 15 phút/logout làm session vô hiệu; request sau trả session expired. | `id03_t05_idleExpiredSessionRejectedWithoutRevival`, `id03_t12_t13_logoutRevokesAndReuseRejected`, `id03_t15_concurrentLogoutAndAuthenticationNeverReactivatesSession`, `concurrentDuplicateLogoutCommitsExactlyOneRevocation`. | **PASS** |
| AC-033 | `03. SRS.md` §10.1 dòng AC-033 | 428 trước business write; verify rồi retry cùng key tối đa một execution; expired/invalid không debit; audit. | Identity evidence: `unverifiedSentProofReturnsPublic428ChallengeAndNoConsumption`, `callerBusinessAndAuditFailuresRollBackConsumptionAndAllowRetry`, `a_twoConcurrentConsumersHaveExactlyOneCommittedWinner`, HTTP fixture `testOnlyConsumerExecutesOnceAndConsumedProofCannotBeVerifiedOrConsumedAgain`. | **BLOCKED**: không có production Transfer T0/T1, idempotency/debit/ledger hoặc durable Audit để chứng minh toàn AC. |
| T-STEP-01 | `design/02` §14.2 | Hai transfer dùng cùng challenge: một transfer, không ledger thứ hai. | Port/PostgreSQL evidence: `a_twoConcurrentConsumersHaveExactlyOneCommittedWinner`, `b_manyConcurrentConsumersHaveExactlyOneCommittedWinner`, `d_simultaneousWrongHashWrongKeyAndValidProofCannotStealConsumption`. | **BLOCKED**: single consume PASS; hai Transfer/ledger production chưa tồn tại. |
| T-STEP-02 | `design/02` §14.2 | Verify OTP đồng thời: một verify, attempt count đúng. | `acceptedCodeVerifiesOnlyOnceUnderConcurrentVerificationAndCannotReplay`, `concurrentActualHttpVerificationCommitsExactlyOneSessionAndLoserIs409`, `t20_concurrentWrongVerifiesCannotBypassMaxAttempts`. | **PASS** |

Không nâng consumer/controller fake thành production evidence. Vì AC-001/003/033 và T-STEP-01 chưa thể PASS toàn bộ, checklist BE-ID-TEST-01 giữ `[ ]`.

## 5. Concurrency, replay, expiry và rollback

- Concurrent password failures: atomic `UPDATE … RETURNING`; không lost update, không vượt counter 5, không cấp challenge/session sau lock.
- Concurrent OTP verification: đúng một `VERIFIED`; attempt count 1; replay bị `INVALID_STATE_TRANSITION`. Năm OTP sai đồng thời chốt `LOCKED`, attempt count 5.
- Concurrent challenge consumption: 2 và 8 transaction độc lập chỉ có một committed winner; hash/key sai không chiếm consumption.
- Concurrent session revoke/use: logout không hồi sinh session; duplicate revoke chỉ mutation một lần.
- Expiry: verify/consume dùng DB wall-clock sau row lock; expired proof không tạo session/consume; expiry worker không đổi consumed/terminal rows.
- Rollback: caller/Audit failure hoàn `consumed_at`; C2 verify audit failure hoàn status/attempt; retry transaction mới được phép đúng một lần.
- Idempotency trong Identity: challenge duplicate cùng binding trả cùng active challenge và không dispatch lần hai; logout duplicate idempotent. Transfer idempotency end-to-end vẫn BLOCKED.

## 6. Defect record

Không phát hiện production defect trong phạm vi lần chạy này. Test mới ban đầu tạo 12 Argon2id verification đồng thời (mỗi verification dùng 64 MiB) và gây `OutOfMemoryError`; đây là test defect/environment overcommit, không phải business race. Test được sửa để cô lập đúng threshold race: seed counter 4, chạy hai request đồng thời, vẫn kiểm saturation/không side effect với mức bộ nhớ hữu hạn. Không giảm security parameter production.

## 7. Dependency và blocker

| Component | Owner theo docs | Thiếu | Impact | Required action |
|---|---|---|---|---|
| Transfer/Limit/Account/Ledger | Vũ (Transfer/Limit), Đạt (Account), Phương (Ledger) | Production Transfer controller/orchestrator, T0/T1, step-up policy/reservation, money/ledger path; MOD-OI-01/09 chưa chốt. `origin/dev2@cf60428` chỉ có candidate/domain/persistence. | AC-033, T-STEP-01 không thể PASS; không chứng minh 428 trước write hoặc không double transfer/debit/ledger. | Chốt protocol và ghép `StepUpChallengePort` vào transaction/idempotency thật; chạy PostgreSQL contract/concurrency tests xuyên module. |
| Audit | Thịnh | `AuditModuleApi`/audit persistence/hash chain runtime chưa có; Identity dùng security-log adapter và test failure fixture. | AC-001/033 audit durable và failure integration thật chưa chứng minh. | Cung cấp `recordInTransaction`/`recordStandalone` theo module contract và chạy rollback/integration thật. |
| Notification | Thịnh | Contact resolver/provider, durable secret handoff, resend generation/counter/fencing/recovery chưa có (MOD-OI-07/09). | AC-003 chỉ chứng minh gateway contract bằng fake; chưa chứng minh physical delivery/resend. | Chốt protocol/schema và provider; chạy delivery/resend/concurrency/crash tests. |

## 8. Kết quả thực thi

| Lệnh | Kết quả thực tế |
|---|---|
| `./gradlew test --tests '...IdentityAuthPostgreSqlIntegrationTest' --tests '...OtpDeliveryPostgreSqlIntegrationTest' --rerun-tasks --console=plain` | 47 tests chạy; 46 PASS, 1 FAIL do test mới tạo 12 Argon2id verification đồng thời và hết heap. Đã sửa test defect, không đổi production security parameter. |
| `./gradlew test --tests '...IdentityAuthPostgreSqlIntegrationTest' --rerun-tasks --console=plain` | `BUILD SUCCESSFUL` sau sửa lockout race test. |
| `./gradlew test --console=plain` tại shared worktree | Tests chạy nhưng Gradle task FAIL khi ghi 40 XML reports vì một process `gradlew build --rerun-tasks` khác đồng thời ghi cùng `build/test-results/test`; không có JUnit assertion failure đọc được từ run này. Không kill/ghi đè process của người dùng. Verification cuối chạy trong isolated workspace để loại shared-output race. |
| `./gradlew test --no-daemon --max-workers=1 --console=plain` trong isolated copy của working tree | `BUILD SUCCESSFUL` — **56 classes / 803 tests / 0 failure / 0 error / 0 skipped**. Identity: **29 classes / 575 tests / 0 failure / 0 error / 0 skipped**. Hai class sửa: 25 + 22 tests PASS. |
| `./gradlew build --no-daemon --max-workers=1 --console=plain` trong cùng isolated copy | `BUILD SUCCESSFUL`; assemble/check/JaCoCo hoàn tất, test task reuse kết quả 803 PASS ở lệnh ngay trước. |

## 9. File thay đổi

| Path | Mục đích |
|---|---|
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/IdentityAuthPostgreSqlIntegrationTest.java` | Thêm lockout saturation và duplicate logout concurrency tests. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpDeliveryPostgreSqlIntegrationTest.java` | Thêm C2 verification audit rollback/retry test và failure switch test-only. |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-TEST-01.md` | Evidence, traceability, blockers và trạng thái task. |
| `digitalbanking-docs/docs-backend/modules/identity/README.md` | Liên kết report TEST-01. |
| `digitalbanking-docs/docs-backend/modules/identity/tasks/README.md` | Cập nhật checklist bằng evidence thực tế, giữ task BLOCKED. |
