# BE-ADM-TST-01 — Unit/component tests cho Admin

> **Mã task:** `BE-ADM-TST-01` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **DONE (phần có runtime)** — domain/DTO/application/authorization/API-Problem/component tests chạy bằng JUnit 5 + Mockito + MockMvc + PostgreSQL 16 Testcontainers; không có test chỉ đọc source text.
> **Nguồn:** `docs-common/09. Test plan.md`, `07` §2/§4/§6/§8.8, `design/04`, `design/05` §12.1, `BE-ADM-DOM-01`, `BE-ADM-APP-01/02`, `BE-ADM-API-01/02`.

## 1. Phạm vi và nguyên tắc

- Test gọi đúng boundary production: controller → security chain/Problem pipeline; service → port/provider; persistence → PostgreSQL thật.
- Không mock business rule của chính service đang test; mock chỉ dùng cho provider chưa có runtime (Limit) hoặc seam ngoài (OTP transport trong HTTP integration).
- Mỗi test có precondition rõ, assertion outcome + state; negative test kiểm không mutate state/không gọi provider/không audit sai.
- Không dùng H2; migration/transaction/lock/unique chạy PostgreSQL 16 + Flyway thật.
- Không test nguồn bằng đọc text; `SafeJsonLogEncoderTest` kiểm byte output thực của encoder.

## 2. Domain tests

| Yêu cầu | Test |
|---|---|
| Happy transition `PENDING→APPLIED/REJECTED/CANCELLED` | `ApprovalDomainModelTest#pendingAllowsOnlyTheApprovedMvpTransitions`, `ApprovalRequestTest#approveAppliesTheChangeAndRecordsChecker`, `#rejectDoesNotApplyAndCannotBeRepeated` |
| Invalid transition | `ApprovalDomainModelTest#unsupportedNonTerminalTransitionUsesInvalidStateCode`, `#enumValuesAreExactAndUnknownValuesFail` |
| Stale state | `ApprovalDecisionServiceTest#terminalApprovalIsRejectedBeforeTheChallengeIsConsumed`, `ApprovalRequestPostgreSqlIntegrationTest#approveCommitsAppliedAndDuplicateDecisionIsRejected` |
| Terminal restriction | `ApprovalDomainModelTest#everyTerminalStatusRejectsEveryFurtherTransition`, `#terminalApprovalRejectsApproveRejectAndCancel` |
| State consistency sau reject | `ApprovalRequestTest#rejectDoesNotApplyAndCannotBeRepeated`, `ApprovalDomainModelTest#terminalApprovalRejectsApproveRejectAndCancel` |
| Version/concurrency | `ApprovalRequestPostgreSqlIntegrationTest#concurrentCheckersSerializeOnRowLockAndOnlyOneApplies`, `#lockContentionHonorsLockTimeoutWithoutSilentlyBypassingRowLock`; stale role version: `ApprovalDecisionServiceTest#staleProviderVersionIsAConflictAndNeverPersistsApplied`, `ApprovalStepUpPostgreSqlIntegrationTest#providerFailureRollsBackConsumption...` |
| Domain validation | `ApprovalRequestTest` (money/currency/id/target/correlation/reason), `ApprovalDomainModelTest` (role conditional fields, boundaries) |

Assertions kiểm cả status/checker/appliedAt và state sau rejection.

## 3. DTO validation tests

`AdminDtoValidationTest` (mới, 6 test) + controller tests:

| Yêu cầu | Test |
|---|---|
| Valid DTO | `RoleAdminControllerTest#createReturns201LocationAndNoStore`, `LimitAdminControllerTest#rejectDecisionUsesLimitTypeAndNoStore` |
| Missing required fields | `AdminDtoValidationTest#roleProposalRejectsMissingInvalidUnknownAndMalformedFieldsBeforeTheService`, `#limitProposalRejectsMissingAndMalformedFieldsBeforeTheService`, `#decisionRejectsMissingInvalidUnknownAndMalformedFieldsBeforeTheService` |
| Invalid format/id/money/instant | cùng test (ID 0/âm/overflow, money `abc`, `effectiveFrom=yesterday`) |
| Invalid enum | `AdminDtoValidationTest` (`ASSIGN`, `MAYBE`), `RoleAdminControllerTest#invalidDecisionIs400BeforeTheService` |
| Boundary values | `AdminDtoValidationTest#limitMoneyBoundaryReachesTheDomainWhichRejectsNonPositiveValues`, `RoleAdminControllerTest#targetUserIdOverBigintIs400WithoutCallingTheService`, `ApprovalDomainModelTest#limitMoneyAcceptsTheNumericBoundaryAndRejectsOverflow` |
| Unsupported/unknown fields | `AdminDtoValidationTest#unknownOptionalFieldsAreNotAccepted...`, `RoleAdminControllerTest#clientSuppliedExpectedVersionIsRejectedBeforeTheService` |
| Invalid state/version input | unknown `status`/`expectedVersion` fields → `MALFORMED_REQUEST`, domain version validation |
| Sensitive fields absent from response | `ApprovalAdminControllerTest#listReturnsPageResponseWithDocumentedSummaryFields`, `ApprovalReadHttpPostgreSqlIntegrationTest#detailReturnsMaskedPayload`, `AdminHttpAuditPostgreSqlIntegrationTest#sensitiveFreeText...` |

`jsonNullBody` và malformed JSON được kiểm là `MALFORMED_REQUEST`; validation field-level không echo giá trị.

## 4. Application service tests

| Kịch bản | Test |
|---|---|
| Happy proposal | `ApprovalProposalServiceTest#successfulProposalPersistsPendingApprovalWithTrustedMakerAndC2Audit`; `ApprovalProposalPostgreSqlIntegrationTest#retryWithVerifiedChallengeCreatesOneApprovalAndReplays` |
| Happy decision | `ApprovalDecisionServiceTest#approvedDecisionWritesOnlyTheCatalogC2FactWithTheTrustedChecker`, `#rejectedDecisionWritesTheC2RejectedFactWithoutCallingTheProvider` |
| Validation rejection | `ApprovalProposalServiceTest#invalidRolePayloadFailsBeforeIdempotencyOrChallenge`, `#missingIdempotencyKeyIsRejectedBeforeAnyLookup` |
| Forbidden | `ApprovalProposalServiceTest#unauthenticatedOrForbiddenActorIsRejectedBeforeAnyLookup`, `ApprovalDecisionServiceTest#missingChallengePermissionFailsClosed`, `#checkerWithoutTheTypePermissionIsDeniedBeforePrecheckOrLock` |
| Not found | `ApprovalDecisionServiceTest#wrongEndpointTypeIsNotFoundBeforeAChallengeIsIssued`, `ApprovalProposalPostgreSqlIntegrationTest#limitProposalFailsClosedWithoutAnyState` (target lookup) |
| Stale state | `ApprovalDecisionServiceTest#terminalApprovalIsRejectedBeforeTheChallengeIsConsumed`, `#staleProviderVersionIsAConflictAndNeverPersistsApplied` |
| Terminal state | `ApprovalDecisionServiceTest#terminalApproval...`, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#expiredRecordStillReplaysButPurgeDoesNotReopenATerminalApproval` |
| Step-up mismatch | `ApprovalDecisionServiceTest#bindingMismatchRejectsAndAuditsWithoutProviderOrTransition`, `ApprovalStepUpPostgreSqlIntegrationTest#challengeBoundToAnotherApprovalIsRejected`, `#changedApprovalPayloadRejectsTheChallenge`, `#forgedBindingKeyIsRejectedEvenWithTheCorrectHash` |
| External provider failure | `ApprovalDecisionServiceTest#providerTechnicalFailureKeepsTheApprovalPendingWithoutAuditOrProviderRetry`, `#stepUpOutageFailsClosedAndKeepsTheApprovalPending`, `ApprovalStepUpPostgreSqlIntegrationTest#providerFailureRollsBackConsumptionAndKeepsTheApprovalPending` |
| Repository/dependency failure | `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest#c2AuditFailureRollsBackApprovalProviderAndChallengeConsumption`, `#providerFailureRollsBackClaimAndTheSameKeyRetries` |
| Audit success/rejection | `ApprovalDecisionServiceTest` (C2 action/actor/outcome; C3 reason, replay không audit), `SecurityLogAdminAuditAdapterTest` 4 |
| Idempotency/retry | `AdminIdempotencyPostgreSqlIntegrationTest` 9, `ApprovalProposalPostgreSqlIntegrationTest` 9, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` 11 |
| No state mutation sau rejection | `ApprovalDecisionServiceTest#makerCannotDecideOwnRequest...`, `#providerTechnicalFailure...`; `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` kiểm DB row status/claim |

## 5. Authorization tests

| Yêu cầu | Test |
|---|---|
| Authorized Admin | `AdminSecurityMatrixPostgreSqlIntegrationTest#auditorKeepsOnlyApprovedPermissions`, `#checkerWithoutRoleManageIsDenied`, `ApprovalAdminControllerTest` (visibility) |
| Missing authentication | `AdminAuthorizationTest#missingAuthenticationIsAlwaysAnAuthenticationFailure`, `#unauthenticatedSessionPrincipalFailsClosed`, `AdminSecurityMatrixPostgreSqlIntegrationTest#missingOrUnverifiableCredentialsAre401` |
| Insufficient role/permission | `AdminAuthorizationTest#customerAuthoritiesNeverReachAnAdminOperation`, `#forgedRolesOnANonSessionPrincipalNeverAuthorize`, `AdminSecurityMatrixPostgreSqlIntegrationTest#customerActorIsDeniedEveryAdminRoute`, `#checkerWithoutRoleManageIsDenied` |
| Cross-scope | `ApprovalReadAuthorizationTest` 7, `AdminSecurityMatrixPostgreSqlIntegrationTest#forgedActorClaimsDoNotEscalate`, `ApprovalReadHttpPostgreSqlIntegrationTest#makerSeesOnlyOwnApprovals`, `#checkerScopeIsTypeBounded` |
| Forbidden không mutate | `AdminHttpAuditPostgreSqlIntegrationTest#forbiddenWriteIsDeniedAndAuditedOnce` (C3 một fact, không state) |
| Step-up authorization | `ApprovalStepUpPostgreSqlIntegrationTest` 22, `ApprovalDecisionHttpPostgreSqlIntegrationTest#makerSelfDecisionIsDenied`, `#outOfScopeCheckerIsDenied` |

Security chain thật (session filter, route gate, CSRF, Problem writer, denial auditor) được dùng trong các `*HttpPostgreSqlIntegrationTest`; không bypass chain.

## 6. API và Problem mapping

| Scenario | Expected | Test |
|---|---|---|
| Happy path | 201/200 + body/state | `AdminHttpAuditPostgreSqlIntegrationTest#verifiedStepUp...`, `ApprovalDecisionHttpPostgreSqlIntegrationTest#approveReturnsAppliedAndIsIdempotent`, `#rejectReturnsRejectedWithoutProviderMutation` |
| Validation | 400 + field detail | `AdminDtoValidationTest`, `RoleAdminControllerTest#missingKeyIs400WithHeaderField`, `#targetUserIdOverBigint...` |
| Forbidden | 403, no mutation | `AdminHttpAuditPostgreSqlIntegrationTest#forbiddenWrite...`, `#approveWithoutCheckerPrivilege...` |
| Not found | 404 Problem | `AdminHttpAuditPostgreSqlIntegrationTest#unknownApprovalIs404WithoutAudit`, `#unknownRoleTargetIs404WithoutState` |
| Stale/terminal | 409 `APPROVAL_ALREADY_DECIDED`/`ROLE_CONFLICT` | `ApprovalDecisionHttpPostgreSqlIntegrationTest#makerSelfDecisionIsDenied`, PG idempotency/step-up suites |
| Step-up mismatch | 428 or 409/404/410 theo catalog, no mutation | `RoleAdminControllerTest#stepUpRequiredIsMappedWithPublicChallenge`, `ApprovalStepUpPostgreSqlIntegrationTest` |
| Provider failure | 503/controlled error, no sensitive provider detail | `LimitAdminControllerTest#createIsFailClosed...`, `AdminHttpAuditPostgreSqlIntegrationTest#unknownRoleTarget...`; `ApprovalDecisionServiceTest#providerTechnicalFailure...` |
| Correlation/safe payload | `X-Correlation-ID` + body `correlationId`, no secret | `AdminHttpAuditPostgreSqlIntegrationTest` helper `assertProblem`, `#sensitiveFreeText...` |

Không tự tạo HTTP mapping ngoài `ErrorCode` catalog; `422 LIMIT_OUT_OF_POLICY` không có đường production vì Limit provider chưa có (`ADM-OI-05`).

## 7. Component tests

| Tương tác | Test |
|---|---|
| Controller ↔ validation | `AdminDtoValidationTest`, `RoleAdminControllerTest`, `LimitAdminControllerTest` |
| Controller ↔ application service | `RoleAdminControllerTest` (captor command), `LimitAdminControllerTest` (type/decision), `ApprovalAdminControllerTest` (query/visibility) |
| Service ↔ persistence | `ApprovalRequestPostgreSqlIntegrationTest` 10, `ApprovalRequestQueryPostgreSqlIntegrationTest` 9, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` 11 |
| Service ↔ audit | `ApprovalDecisionServiceTest`, `SecurityLogAdminAuditAdapterTest` 4, `AdminHttpAuditPostgreSqlIntegrationTest` 9 |
| Service ↔ provider | `ApprovalStepUpPostgreSqlIntegrationTest` 22 (Identity/StepUp thật), `ApprovalProposalPostgreSqlIntegrationTest` 9 |
| Exception ↔ Problem | `AdminDtoValidationTest`, controller tests, `AdminHttpAuditPostgreSqlIntegrationTest` |
| Security filter ↔ endpoint | `AdminSecurityMatrixPostgreSqlIntegrationTest` 8, `AdminRateLimitHttpPostgreSqlIntegrationTest` 1, `HttpSecurityTest` 10 |

## 8. Test mới trong task này

| Lớp/test | Nội dung |
|---|---|
| `AdminDtoValidationTest` (mới, 6) | missing/invalid/unknown/malformed cho role, limit, decision; boundary money; null body |
| `ApprovalDecisionServiceTest` (+2, tổng 14) | stale provider version → 409 + C3 + PENDING; provider technical failure → PENDING, không C2/C3 |
| `AdminPayloadJsonCodecTest` (+1, tổng 5) | free-text `password/otp/token` bị redact trong public payload |
| `ApprovalAdminControllerTest` (+1, tổng 9) | `decisionNote` + payload free-text redacted trong response |
| `AdminHttpAuditPostgreSqlIntegrationTest` (+1, tổng 9) | SEC-02 end-to-end: no-store/Expires/X-Request-ID, secret không lộ response/log/audit/metric |
| `RequestContextTest` / `HttpSecurityTest` | assert `X-Request-ID` server-generated + `Expires: 0` |

Không xoá/nới assertion cũ; các thay đổi test cũ chỉ là bổ sung assertion header/redaction.

## 9. Kết quả thực thi

```bash
cd digitalbanking-backend
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.common.config.*' \
  --tests 'com.simulatedbank.banking.common.context.*' \
  --tests 'com.simulatedbank.banking.common.logging.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --console=plain --no-daemon --max-workers=1
```

**BUILD SUCCESSFUL** — **41 classes / 348 tests / 0 failure / 0 error / 0 skip** (PostgreSQL 16 Testcontainers, Flyway `V1`–`V8`, security chain/Identity thật; OTP transport là test double duy nhất).

Các lớp trọng yếu:

| Lớp | Tests | Kết quả |
|---|---:|---|
| `ApprovalStepUpPostgreSqlIntegrationTest` | 22 | PASS |
| `AdminHttpAuditPostgreSqlIntegrationTest` | 9 | PASS |
| `ApprovalDecisionServiceTest` | 14 | PASS |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | 11 | PASS |
| `ApprovalProposalServiceTest` | 11 | PASS |
| `ApprovalRequestPostgreSqlIntegrationTest` | 10 | PASS |
| `ApprovalAdminControllerTest` | 9 | PASS |
| `AdminDtoValidationTest` | 6 | PASS |
| `RoleAdminControllerTest` / `LimitAdminControllerTest` | 8 + 2 | PASS |

## 10. Giới hạn / blocker

- `ADM-OI-05`: không có Limit provider runtime, nên không thể test `LIMIT_CHANGE` proposal/approve thành công, `422 LIMIT_OUT_OF_POLICY`, `result` limit; hiện fail-closed `503` có test.
- `ADM-OI-07`: durable Audit `audit_log` chưa có; C2/C3 test qua `AdminAuditPort` + structured log/metric, chưa chứng minh hash-chain/immutability.
- `ADM-OI-06`: cross-tenant/staff data scope chưa có model; test scope dừng ở role/approval-type/own-resource.
- `ADM-OI-11`: `ApprovalDetail.result` chưa persist; test chỉ kiểm `result` khi provider trả trong cùng transaction.
- Không chạy full `./gradlew cleanTest build` trong task này để tránh ghi đè/contention với build của agent khác trên worktree chia sẻ; phạm vi được yêu cầu (Admin/Common/Audit/ArchUnit) đã chạy đầy đủ và xanh.
