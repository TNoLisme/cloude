# BE-ADM-APP-02 — Checker decision service

> **Mã task:** `BE-ADM-APP-02` (P0)  
> **Ngày:** 18/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái:** **PARTIAL** — quyết định `ROLE_CHANGE` (approve/reject) đã chạy end-to-end với PostgreSQL 16, row lock,
> Step-Up consume, Identity provider và C2 audit; `LIMIT_CHANGE` **reject** chạy đầy đủ, **approve** fail-closed
> `503 SERVICE_UNAVAILABLE` vì Limit provider chưa có runtime (`ADM-OI-05`). Durable Audit vẫn **BLOCKED**
> (`ADM-OI-07`).  
> **Nguồn:** `07` §8.5.3/§8.8.2, `04` §4.5.3, `design/02` §7.9/§9.6, `design/05` §5.2/§12.1,
> `BE-ADM-CON-01/02/03/04`, `BE-ADM-DB-01/02/03`, `BE-ADM-DOM-01-APP-01`, source hiện hữu.

## 1. Kết luận

`ApprovalDecisionService` là **entry point duy nhất** được phép chuyển một `approval_request` khỏi `PENDING`.
Service thực hiện đúng thứ tự:

1. Resolve checker từ trusted session và yêu cầu privilege `CHECKER`.
2. Idempotency precheck (replay/conflict/in-progress) trước khi cấp challenge hoặc mở transaction.
3. Khóa approval bằng `SELECT … FOR UPDATE`, revalidate type/status/maker-checker.
4. Consume `SENSITIVE_ACTION` challenge trong cùng transaction.
5. Gọi provider-owned command (Identity role; Limit chưa có → fail closed).
6. Persist `APPLIED`/`REJECTED`, hoàn tất idempotency record, ghi C2 audit cuối cùng.

Hai checker quyết định đồng thời chỉ một người commit; người thua nhận `409 APPROVAL_ALREADY_DECIDED` và không
consume challenge/không gọi provider lần hai. Mọi lỗi provider/step-up/audit rollback toàn bộ transaction, đưa
challenge về `VERIFIED` và giữ approval `PENDING`.

## 2. Entry point và controller

| Thành phần | Source | Trách nhiệm |
|---|---|---|
| `ApprovalDecisionService.decide(DecisionCommand)` | `admin/application/ApprovalDecisionService.java` | Toàn bộ business rule quyết định |
| `RoleAdminController.decide` | `admin/adapter/in/RoleAdminController.java` | `POST /api/v1/admin/roles/{approvalId}/approve`, map body/header, không chứa business rule |
| `LimitAdminController.decide` | `admin/adapter/in/LimitAdminController.java` | `POST /api/v1/admin/limits/{approvalId}/approve`; reject chạy, approve fail-closed |
| `ApprovalDecisionHttpRequest` | `admin/adapter/in/ApprovalDecisionHttpRequest.java` | Chỉ `decision` + `decisionNote`; `checkerId`/`makerId`/`expectedVersion` không phải input |

`DecisionCommand` không có `checkerId`; actor luôn lấy từ `AdminAuthorization.requireChecker()`. Jackson
`fail-on-unknown-properties: true` nên field lạ (ví dụ `expectedVersion` trên decision body) bị từ chối `400`
theo OpenAPI `additionalProperties: false`.

## 3. Thứ tự kiểm tra và nhánh quyết định

| # | Kiểm tra | Nguồn | Lỗi |
|---:|---|---|---|
| 1 | Session hợp lệ | `AdminAuthorization.requireSession` | `401 AUTH_SESSION_EXPIRED` |
| 2 | Privilege `CHECKER` | `AdminAuthorization.requireChecker` | `403 AUTH_FORBIDDEN` |
| 3 | `approvalId > 0` | `ApprovalDecisionService` | `400 VALIDATION_ERROR` |
| 4 | `Idempotency-Key` hợp lệ | `AdminIdempotencyService.requireKey` | `400 VALIDATION_ERROR` |
| 5 | Idempotency terminal replay/conflict/in-progress | `AdminIdempotencyService.precheck` | replay hoặc `409 IDEMPOTENCY_CONFLICT` / `409 IDEMPOTENCY_IN_PROGRESS` |
| 6 | Approval tồn tại + đúng `requestType` | `ApprovalRequestStore.lockById` + `verifyType` | `404 APPROVAL_NOT_FOUND` (anti-enumeration) |
| 7 | Còn `PENDING` | `ApprovalRequest.assertCheckerEligible` | `409 APPROVAL_ALREADY_DECIDED` |
| 8 | Maker ≠ checker; role target ≠ maker/checker | `ApprovalRequest.assertCheckerEligible` | `409 ROLE_CONFLICT` |
| 9 | Challenge thuộc checker, đúng purpose/binding/hash, còn hạn, chưa consume | `StepUpChallengePort.consumeVerified` | `404`/`409`/`410`/`428` theo catalog |
| 10 | Provider command | `IdentityRoleCommand.applyApprovedRoleChange` (JOIN) | lỗi provider theo catalog |
| 11 | Persist transition + idempotency completion + C2 audit | `ApprovalRequestStore.update`, `AdminIdempotencyService.complete`, `AdminAuditPort.recordInTransaction` | rollback toàn bộ |

Nhánh `APPROVE`:

- Bắt buộc challenge `SENSITIVE_ACTION` đã verify.
- Gọi provider trước khi persist `APPLIED`; provider nhận fact `ApprovedRoleChange` với `approvalState=APPROVED`.
- Nếu provider fail, approval không đổi, challenge được hoàn, idempotency claim rollback.

Nhánh `REJECT`:

- Bắt buộc challenge `SENSITIVE_ACTION`; **không** gọi provider.
- Persist `REJECTED` + `decidedAt`; `appliedAt = null`.

Không có transition `APPROVED` trong MVP; `APPROVED` là schema-reserved. Không thêm status mới.

## 4. Locking và concurrency

- `ApprovalRequestStore.lockById` gọi `ApprovalRequestJpaRepository.findByIdForUpdate` với
  `@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE` chờ (không `NOWAIT`/`SKIP LOCKED`), đúng `design/02`
  §7.9/§9.6.
- Transaction quyết định dùng `TransactionTemplate` (REQUIRED, PostgreSQL default READ COMMITTED). Sau khi
  winner commit, loser đọc row mới dưới lock và thấy `APPLIED`/`REJECTED` → `APPROVAL_ALREADY_DECIDED`.
- Challenge consume của Identity là conditional `UPDATE … WHERE consumed_at IS NULL AND expires_at >
  clock_timestamp()`; kể cả khi status check bị vượt, chỉ một consume thắng.
- Idempotency dùng unique `(actor_id, operation, scope_key, idempotency_key)` + `ON CONFLICT DO NOTHING`:
  cùng key chỉ một business effect; khác key thì row lock approval là arbiter.
- Không dùng distributed lock, không optimistic version trên approval. Schema canonical `04` §4.5.3 **không có**
  cột `version`, nên không tự thêm.

## 5. Maker/Checker separation

- Maker/checker không bao giờ lấy từ body/header. `AdminAuthorization` chỉ chấp nhận principal
  `SessionAuthenticator.Session` do security chain cài đặt.
- `ApprovalRequest.assertCheckerEligible` chặn:
  - checker = maker → `ROLE_CONFLICT`;
  - với `ROLE_CHANGE`, maker hoặc checker = target user → `ROLE_CONFLICT`.
- Identity re-check độc lập khi apply: `IdentityRoleCommandService` đọc quyền hiện tại từ DB, kiểm maker/checker/
  target và trả `AUTH_FORBIDDEN`/`ROLE_CONFLICT` nếu vi phạm. Admin không thể bypass bằng service entry point khác
  vì `ApprovalDecisionService` là nơi duy nhất gọi transition.
- DB check `checker_id IS NULL OR checker_id <> maker_id` là lớp phòng thủ cuối.

## 6. Expected version

Task yêu cầu validate `expectedVersion`; repository contract xác định **không có version cột trên approval** và
`ROLE_CHANGE.expectedVersion` là **server-captured** rồi do Identity sở hữu:

- Lúc proposal, Admin capture `IdentityRoleCommand.getRoleAssignmentVersion(targetUserId)` và persist trong
  `RoleChangePayload`; client không gửi field này và response public mask bỏ nó (`AdminPayloadJsonCodec`,
  `ApprovalPayloadView`).
- Lúc approve, `ApprovalDecisionService` chuyển nguyên `payload.expectedVersion()` vào
  `ApprovedRoleChange`. `IdentityRoleCommandService.applyApprovedRoleChange` khóa user, so
  `role_assignment_version` với `expectedVersion`; stale → `409 ROLE_CONFLICT`, không mutate role/session.
- Step-Up hash của decision bao gồm payload canonical, nên sửa `expectedVersion` giữa issue và consume →
  `409 STEP_UP_CHALLENGE_MISMATCH`.
- Không có version approval row để so; không thêm cột/migration hoặc field API trái `04`/`07`.

`LIMIT_CHANGE.expectedVersion` sẽ do Limit provider sở hữu khi `applyApprovedLimitChange` có contract/runtime
(`ADM-OI-05`). Hiện tại limit approve fail closed trước khi persist `APPLIED`.

## 7. Idempotency và chống gọi provider lặp

- `Idempotency-Key` bắt buộc; scope `(checkerId, DECIDE_APPROVAL, approvalId)`.
- Cùng key + cùng hash terminal → replay body/status gốc, `Idempotent-Replayed: true`; không consume challenge,
  không gọi provider.
- Cùng key + khác hash → `409 IDEMPOTENCY_CONFLICT`.
- Record `RECEIVED` → `409 IDEMPOTENCY_IN_PROGRESS`.
- Key khác cho approval đã terminal → lock + status check → `409 APPROVAL_ALREADY_DECIDED`; provider không chạy.
- Provider/audit failure rollback claim, nên retry cùng key là một attempt sạch; committed terminal record luôn
  replay, không tái apply.
- Timeout sau commit an toàn: retry cùng key trả lại response đã lưu.

## 8. Transaction boundary và rollback

Một transaction duy nhất chứa:

```text
claim idempotency (RECEIVED)
→ SELECT approval FOR UPDATE
→ revalidate status/type/maker-checker
→ consumeVerified(challenge)              [Identity MANDATORY/JOIN]
→ provider.applyApprovedRoleChange(...)   [Identity MANDATORY/JOIN]
→ approval.approve/reject + update
→ idempotency.complete(...)
→ audit.recordInTransaction(C2)
→ COMMIT
```

- Identity role command, step-up challenge, approval row, idempotency record và audit adapter dùng chung
  `DataSource`/`PlatformTransactionManager` (modular monolith), nên rollback là thật, không tuyên bố distributed
  atomicity.
- Provider/step-up/C2 audit lỗi → rollback: approval `PENDING`, role/version không đổi,
  `auth_challenge.consumed_at` trở lại `NULL` (`VERIFIED`), idempotency record biến mất.
- C2 audit là catalog write cuối cùng; lỗi audit không được nuốt trong transaction.
- Durable Audit provider (`AuditModuleApi`) chưa có; adapter hiện tại là structured security log + metric
  (`SecurityLogAdminAuditAdapter`). Đây là **blocker** `ADM-OI-07`, không phải atomicity của provider.

## 9. Error mapping

| Tình huống | Kết quả |
|---|---|
| Thiếu session | `401 AUTH_SESSION_EXPIRED` |
| Thiếu privilege `CHECKER` | `403 AUTH_FORBIDDEN` |
| `approvalId`/decision body sai | `400 VALIDATION_ERROR` |
| Approval không tồn tại hoặc sai endpoint type | `404 APPROVAL_NOT_FOUND` |
| Thiếu challenge | `428 STEP_UP_REQUIRED` + public challenge |
| Sai purpose/user/approval/hash/binding/version payload | `404 AUTH_CHALLENGE_NOT_FOUND` hoặc `409 STEP_UP_CHALLENGE_MISMATCH` |
| Challenge hết hạn | `410 AUTH_CHALLENGE_EXPIRED` |
| Challenge đã consume | `409 INVALID_STATE_TRANSITION` |
| Approval terminal | `409 APPROVAL_ALREADY_DECIDED` |
| Maker tự quyết / target trùng actor | `409 ROLE_CONFLICT` |
| Stale role `expectedVersion` (provider) | `409 ROLE_CONFLICT`, không mutate |
| Limit approve khi chưa có provider | `503 SERVICE_UNAVAILABLE`, không persist `APPLIED` |
| C2 audit/provider technical failure | rollback; lỗi kỹ thuật theo `ErrorCode` |

Không response/log nào chứa OTP, challenge hash, token, payload đầy đủ hoặc PII.

## 10. Audit attribution

- `checkerId` trong C2/C3 là user ID thật từ `SessionAuthenticator.Session`, không phải field client.
- C2 action: `ROLE_CHANGE_APPROVED`/`ROLE_CHANGE_REJECTED`/`LIMIT_CHANGE_REJECTED` (và
  `LIMIT_CHANGE_APPROVED` khi provider mở).
- C3 `SECURITY_ACCESS_DENIED` với `reasonCode` là `ErrorCode` name cho forbidden/terminal/mismatch/expired/
  replay/maker self-decision.
- `428 STEP_UP_REQUIRED` là luồng cấp challenge bình thường, **không** ghi C3; maker/target yêu cầu challenge
  vẫn bị audit denial.
- Không log note, OTP, hash, binding key hoặc raw challenge.

## 11. Test coverage

| Lớp | Nội dung |
|---|---|
| `ApprovalDecisionServiceTest` | 428 không lock/không transition; thiếu `approvalId`; thiếu `CHECKER`; terminal trước consume; maker self-approve; Identity outage; binding mismatch + C3; sai endpoint type |
| `ApprovalStepUpPostgreSqlIntegrationTest` | approve/reject role; missing/unknown approval; 428; wrong purpose/checker/approval/decision/binding key; expired/consumed/replay; payload change; provider failure rollback; concurrent consume; unauthorized checker; maker self-reject; **2 checker race approve**; **race approve/reject**; 428 không bị audit denial |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | approve/replay/conflict; reject replay; actor/resource isolation; concurrent same/different key; provider rollback + retry; limit approve fail closed + limit reject; in-progress; expired record; **C2 audit failure rollback toàn bộ** |
| `RoleAdminControllerTest`/`LimitAdminControllerTest` | status/header/idempotency/replay/400/503 mapping; client gửi `expectedVersion` trên decision body bị `400 MALFORMED_REQUEST` trước service |

Các test concurrency dùng PostgreSQL 16 thật + Testcontainers + barrier, không mock lock/transaction.

## 12. Kết quả thực thi

Targeted suite (PostgreSQL 16 Testcontainers + Flyway thật, chạy trong bản copy sạch để tránh Gradle build
đồng thời trên worktree chia sẻ):

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.ApprovalStepUpPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.ApprovalDecisionIdempotencyPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.admin.application.ApprovalDecisionServiceTest' \
  --console=plain --no-daemon
```

**BUILD SUCCESSFUL** — **41 tests / 0 failure / 0 error / 0 skip**:

| Class | Tests | Kết quả |
|---|---:|---|
| `ApprovalStepUpPostgreSqlIntegrationTest` | 22 | PASS |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | 11 | PASS |
| `ApprovalDecisionServiceTest` | 8 | PASS |

Các test mới bổ sung trong task: `missingChallengeIsNotAuditedAsAnAccessDenial`,
`makerSelfRejectionWithoutChallengeIsAudited`, `checkerWithoutPrivilegeIsDeniedBeforeAnyLockOrChallenge`,
`twoCheckersRacingApproveApplyTheRoleOnce`, `concurrentApproveAndRejectLeaveOneTerminalDecision`,
`c2AuditFailureRollsBackApprovalProviderAndChallengeConsumption`, cùng
`clientSuppliedExpectedVersionIsRejectedBeforeTheService`.

Full backend suite (cùng bản copy có sibling `digitalbanking-docs` để test contract đọc được source of truth):

```bash
./gradlew cleanTest build --console=plain --no-daemon
```

**BUILD SUCCESSFUL** (7m02s) — **1197 tests / 0 failure / 0 error / 0 skip**. Lần chạy đầu trong bản copy
không có `digitalbanking-docs` có 12 failure ở `ErrorContractTest`, `PostgreSqlErrorPipelineIntegrationTest`,
`IdentityOpenApiContractTest`, `IdentityTransportSecurityIntegrationTest` do `NoSuchFileException`/context; khi
bổ sung sibling docs, 12 test đó PASS và full build xanh. Không có failure thuộc Admin.

## 13. Blockers còn lại

| Blocker | Owner | Điều kiện mở |
|---|---|---|
| Limit provider `applyApprovedLimitChange` + `limit_config PENDING_APPROVAL` | Limit owner | Contract/runtime + version semantics (`ADM-OI-05`); khi đó bỏ fail-closed 503 và thêm test approve/reject limit |
| Durable Audit `AuditModuleApi`/`audit_log` | Audit owner | `ADM-OI-07`; thay adapter security-log, chứng minh C2 fail-closed và C3 persistence |
| Cross-tenant/staff data scope | Security/API owner | `ADM-OI-06`; schema hiện tại single-tenant |
| HTTP security-chain contract đầy đủ (CSRF/401/403/428/409) | API/Security/QA | `BE-ADM-TST-04`; controller đã có nhưng contract suite chưa |
