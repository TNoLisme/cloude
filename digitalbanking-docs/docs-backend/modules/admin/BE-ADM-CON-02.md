# BE-ADM-CON-02 — Step-up challenge binding cho Maker–Checker

> **Mã task:** `BE-ADM-CON-02` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái tổng:** **PARTIAL (Checker binding DONE)** — binding `SENSITIVE_ACTION` cho quyết định Checker đã chạy thật với PostgreSQL + Identity và 16/16 kịch bản bảo mật cục bộ PASS; REST decision (`API-LIM-03`/`API-ROL-02`), Maker step-up trước proposal (`MOD-OI-10`), Limit provider, idempotency proposal và durable Audit vẫn **BLOCKED** và không bị đánh dấu hoàn thành.  
> **Cập nhật 17/11/2026:** `BE-ADM-CON-03` đã xử lý Maker step-up, idempotency và REST decision; `MOD-OI-10` đóng bằng binding `Idempotency-Key` + proposal hash. Limit provider và durable Audit vẫn BLOCKED.  
> **Nguồn ưu tiên:** `07` §6.5/§6.6/§8.5.3/§8.8.2, `04` §4.2.5/§4.5.3, `design/02` §5.1–§5.2, `design/05` §5.2/§12.1, `design/01` §5, `BE-ADM-CON-01` §4.2/§6/§10.

## 1. Kết luận

Checker không thể chuyển approval sang `APPLIED`/`REJECTED` nếu thiếu một `auth_challenge` `VERIFIED`, purpose `SENSITIVE_ACTION`, thuộc đúng checker, bind đúng `approvalId` + hash quyết định + user, chưa hết hạn và chưa consume. Việc consume chạy bên trong chính transaction quyết định (`MANDATORY`/JOIN của Identity), nên rollback trả challenge về trạng thái dùng lại được, còn challenge đã consume không thể dùng cho approval/decision khác.

Phần Maker step-up **trước khi tạo proposal** (`MOD-OI-10` gốc) **không** được triển khai: `07` §8.5.2/§8.8.1 yêu cầu step-up trước khi business write, nhưng chưa có contract canonical proposal hash/idempotency (`BE-ADM-CON-03`) và chưa được owner API/Security chốt. Không tạo `approvalId` giả hoặc binding lỏng thay thế. Xem §10.

## 2. Mô hình binding

| Thành phần | Giá trị/nguồn | Kiểm ở đâu |
|---|---|---|
| `purpose` | `SENSITIVE_ACTION` (server chọn, không nhận từ client) | `ApprovalStepUpService`, `OtpChallengeService.consumeVerified` |
| `approvalId` | `approval_request.approval_id` đã persist; `<= 0` bị từ chối trước mọi lời gọi Identity | `ApprovalStepUpService`, `ApprovalStepUpBinding` |
| `bindingKey` | chuỗi thập phân của `approvalId` (= `auth_challenge.idempotency_key`, ≤255) | bind lúc issue; so khớp khi consume |
| `requestHash` | SHA-256 hex 64 ký tự của canonical decision (mục 3) | bind lúc issue; **tính lại** từ row đã lock khi consume |
| `checkerId` | `SessionAuthenticator.Session.userId` của principal tin cậy | `AdminAuthorization.requireChecker`; `auth_challenge.user_id` |
| `expiresAt` | TTL do Identity cấp (`app.security.otp`), so bằng `clock_timestamp()` trong SQL | conditional `UPDATE` của Identity |
| `consumedAt` | `auth_challenge.consumed_at` (không có state `CONSUMED`, OD-ID-06) | conditional `UPDATE ... WHERE consumed_at IS NULL` |
| `challengeId` | `auth_challenge.challenge_ref` (UUID) do Identity sinh | header `X-Step-Up-Challenge` ở controller tương lai |

Không thêm bảng/cột/nhãn binding mới: `auth_challenge.request_hash` + `idempotency_key` + `user_id` + `purpose` + `expires_at` + `consumed_at` là đủ và đã được duyệt (`04` §4.2.5).

## 3. Request hash

### 3.1. Canonical form

```
ADM-APPROVAL-STEPUP-V1
v=ADM-APPROVAL-STEPUP-V1
approvalId=<len>:<decimal>
requestType=<len>:<LIMIT_CHANGE|ROLE_CHANGE>
targetType=<len>:<CUSTOMER|ACCOUNT|APP_USER>
targetId=<len>:<id>
makerId=<len>:<decimal>
payload=<len>:<JCS JSON>
checkerId=<len>:<decimal>
decision=<len>:<APPROVE|REJECT>
decisionNote=<len>:<note> | -1:
```

- Mỗi field ghi `name=`, rồi `length:value` (UTF-8) và `\n`. Độ dài loại bỏ hoàn toàn khả năng "dịch" nội dung giữa các field; `null` là `-1:`.
- `payload` là JSON RFC 8785 (JCS) của map typed payload do `AdminPayloadJsonCodec` sinh — cùng nguồn với JSONB đã persist, field order không ảnh hưởng.
- Hash = `HmacService.sha256Hex(canonical)` (Common), lower-case hex 64 ký tự đúng ràng buộc `auth_challenge.request_hash CHAR(64)` mà `OtpChallengeService` validate.
- `ADM-APPROVAL-STEPUP-V1` là domain separator có version: đổi tập field phải bump version, nên challenge cũ không thể "khớp" sau khi đổi format.
- `decisionNote` được normalize đúng như domain ghi vào `decision_note` (`strip`, rỗng → `null`, ≤500) nên khác biệt khoảng trắng đầu/cuối không tạo mismatch giả; mọi khác biệt nội dung đều tạo hash khác.

### 3.2. Bảo vệ

- Hash **luôn tính lại** từ row approval đã `SELECT … FOR UPDATE` và decision hiện tại; client không gửi hash và hash client gửi (nếu có) sẽ bị bỏ qua hoàn toàn.
- Đổi `approvalId`, `requestType`, `targetType/targetId`, maker, payload, checker, `decision` hoặc `decisionNote` đều làm hash khác → `409 STEP_UP_CHALLENGE_MISMATCH`.
- Không dùng `toString()` của payload/entity, không serialize JSONB từ DB bằng thứ tự map không đảm bảo.

## 4. Vòng đời `approvalId`

1. **Maker tạo proposal** (chưa mở API; `BE-ADM-APP-01`): `ApprovalRequest.propose` → `ApprovalRequestStore.insert` (MANDATORY) → `approval_id` do DB sinh.
2. **Checker gọi decision không kèm challenge**: server load approval, kiểm `PENDING` + Maker–Checker, tạo/reuse challenge bind `approvalId` + hash, trả `428 STEP_UP_REQUIRED` kèm `challenge` công khai (không OTP/hash/binding).
3. **Checker verify** qua `POST /api/v1/auth/step-up/verify` (Identity) → challenge `VERIFIED`.
4. **Checker gọi lại** với `X-Step-Up-Challenge`; server lock row, tính lại hash, `consumeVerified` (JOIN), apply provider (role) hoặc reject, persist `APPLIED`/`REJECTED`, audit C2 — tất cả một transaction.
5. Challenge hết hạn/đã consume/khác approval/khác decision bị từ chối; approval giữ `PENDING`.

`approvalId <= 0`, rỗng hoặc chưa tồn tại → `400 VALIDATION_ERROR` / `404 APPROVAL_NOT_FOUND`, **không** tạo challenge. Không có nhánh nào tạo challenge với `null`/placeholder id.

## 5. Luồng 428 và header

- 428 chỉ xuất hiện khi request **chưa có** `X-Step-Up-Challenge`. Server không chuyển các rejection khác (mismatch/expired/consumed/OTP sai) thành 428.
- Extension `challenge` gồm `challengeId` (UUID), `purpose`, `methods`, `expiresAt`, `maxAttempts`, `remainingAttempts`, `channel` — đúng schema `ProblemResponse.checkChallenge`; không chứa OTP, `requestHash`, `bindingKey`.
- Challenge được Identity tạo trong transaction ngắn riêng **trước** transaction quyết định, nên 428 không rollback challenge và không ghi business state.
- Challenge issue lặp lại cùng approval+decision+note được Identity reuse theo `(idempotency_key, request_hash)` (policy hiện hữu), không sinh OTP thứ hai cho verifier cũ.

## 6. Kiểm tra authorization (thứ tự thực thi)

| # | Kiểm tra | Nguồn | Lỗi |
|---|---|---|---|
| 1 | Session hợp lệ | `AdminAuthorization.requireSession` | `401 AUTH_SESSION_EXPIRED` |
| 2 | Privilege `CHECKER` | `AdminAuthorization.requireChecker` (authority snapshot) | `403 AUTH_FORBIDDEN` |
| 3 | `approvalId` hợp lệ | service | `400 VALIDATION_ERROR` |
| 4 | Approval tồn tại + lock | `ApprovalRequestStore.lockById` | `404 APPROVAL_NOT_FOUND` |
| 5 | `PENDING` (chưa terminal) | `ApprovalRequest.assertCheckerEligible` | `409 APPROVAL_ALREADY_DECIDED` |
| 6 | Checker ≠ maker; role: checker/maker ≠ target | `ApprovalRequest.assertCheckerEligible` + DB check | `409 ROLE_CONFLICT` |
| 7 | Challenge thuộc checker | Identity `findByRefAndUser` | `404 AUTH_CHALLENGE_NOT_FOUND` |
| 8 | Purpose `SENSITIVE_ACTION` | Identity | `409 STEP_UP_CHALLENGE_MISMATCH` |
| 9 | `bindingKey` = `approvalId` | Identity | `409 STEP_UP_CHALLENGE_MISMATCH` |
| 10 | `requestHash` khớp canonical hiện tại | Identity | `409 STEP_UP_CHALLENGE_MISMATCH` |
| 11 | `VERIFIED`, chưa consume, chưa hết hạn | Identity conditional UPDATE + wall clock | `428`/`409 INVALID_STATE_TRANSITION`/`410 AUTH_CHALLENGE_EXPIRED` |
| 12 | Consume đúng một lần | `UPDATE … WHERE consumed_at IS NULL AND expires_at > clock_timestamp()` | 0 row → rejection, không transition |
| 13 | Provider apply (role) | `IdentityRoleCommand.applyApprovedRoleChange` (JOIN) | `409 ROLE_CONFLICT`/`403`/`404` |
| 14 | Transition + audit C2 | `ApprovalRequest.approve/reject`, `ApprovalRequestStore.update`, `AdminAuditPort` | rollback toàn bộ |

`checkerId` **luôn** lấy từ principal; body/header không có trường này. Không có controller trong task này, nhưng `ApprovalDecisionService` là entry point duy nhất được phép transition (xem §7).

## 7. Transaction, failure và concurrency

- `ApprovalDecisionService.decide` mở **một** transaction (`TransactionTemplate`, REQUIRED) gồm: lock approval → consume challenge (`MANDATORY`) → provider apply → persist trạng thái → audit C2.
- Provider hoặc audit lỗi → rollback: approval `PENDING`, `role`/`version` không đổi, `auth_challenge.consumed_at` được hoàn lại (`VERIFIED`), retry được với cùng proof.
- Không tuyên bố atomic xuyên datasource: Identity và Admin dùng chung transaction manager/datasource hiện tại (JOIN). Nếu tương lai tách transaction phải thiết kế outbox/recovery riêng.
- Hai checker đồng thời: `SELECT … FOR UPDATE` trên approval; người thua đọc `APPLIED`/`REJECTED` → `409 APPROVAL_ALREADY_DECIDED`, không consume/apply lần hai. Kể cả khi lọt qua status check, conditional consume chỉ cho **một** winner.
- Không distributed lock, không optimistic version trên approval (schema canonical không có `version`). Decision đã dùng idempotency record theo `BE-ADM-CON-03` (scope `checkerId + approvalId + Idempotency-Key`); duplicate cùng key replay body gốc, key khác trên approval terminal trả `APPROVAL_ALREADY_DECIDED`.
- `ApprovalStepUpService`/`ApprovalDecisionService` chỉ được tạo khi `app.admin.enabled` **và** `app.identity.enabled` bật (mặc định bật). Tắt Identity làm thiếu `StepUpChallengePort`/`IdentityRoleCommand`; Admin không có cơ chế thay thế nên bean không được tạo thay vì chạy non-functional. `RoleAdminController`/`LimitAdminController` cũng có cùng điều kiện nên fail-fast khi cấu hình thiếu.
- `APPROVE` của `LIMIT_CHANGE` fail closed (`503 SERVICE_UNAVAILABLE`) vì `limit.api.applyApprovedLimitChange` chưa có runtime (`ADM-OI-05`): không persist `APPLIED` khi chưa áp dụng hạn mức. `REJECT` limit/role không gọi provider và đã hoạt động.

## 8. Error mapping

| Tình huống | Kết quả |
|---|---|
| Thiếu/không hợp lệ `approvalId` | `400 VALIDATION_ERROR`, không tạo challenge |
| Approval không tồn tại | `404 APPROVAL_NOT_FOUND` |
| Thiếu challenge | `428 STEP_UP_REQUIRED` + `challenge` (đã bind) |
| Wrong purpose / wrong approval / wrong hash / wrong binding key | `409 STEP_UP_CHALLENGE_MISMATCH` |
| Challenge thuộc checker khác | `404 AUTH_CHALLENGE_NOT_FOUND` (anti-enumeration) |
| Maker tự duyệt / checker = target | `409 ROLE_CONFLICT` |
| Challenge hết hạn | `410 AUTH_CHALLENGE_EXPIRED` |
| Challenge đã consume | `409 INVALID_STATE_TRANSITION` |
| Approval đã terminal | `409 APPROVAL_ALREADY_DECIDED` |
| OTP sai/quá hạn khi verify | `401 AUTH_OTP_INVALID` / `410 AUTH_OTP_EXPIRED` (Identity) |
| Identity/JPA không khả dụng | `503 SERVICE_UNAVAILABLE` (fail closed, không transition) |
| Limit approve chưa có provider | `503 SERVICE_UNAVAILABLE` (fail closed) |

Không response/log nào chứa OTP, `otp_hash`, `request_hash`, `bindingKey`, token hay nội dung payload.

## 9. Audit

| Sự kiện | Action (design/01 §5.3) | Class | Ghi chú |
|---|---|---|---|
| Challenge tạo | `AUTH_CHALLENGE_CREATED` | C2 | Do Identity ghi trong TX tạo challenge (không nhân đôi ở Admin) |
| Verify step-up | `AUTH_STEP_UP_VERIFIED`/`AUTH_STEP_UP_FAILED` | C2/C3 | Identity |
| Approve role thành công | `ROLE_CHANGE_APPROVED` | C2 | Admin, cuối transaction; fail → rollback |
| Reject role/limit | `ROLE_CHANGE_REJECTED`/`LIMIT_CHANGE_REJECTED` | C2 | Admin, cuối transaction |
| Approve limit (khi provider có) | `LIMIT_CHANGE_APPROVED` | C2 | Mapping đã có; chưa đạt được vì provider BLOCKED |
| Binding mismatch / replay / hết hạn / sai checker / maker tự duyệt / terminal | `SECURITY_ACCESS_DENIED` | C3 | Standalone sau rollback, `reasonCode` = `ErrorCode` name |

`AdminAuditPort` chỉ phát action trong catalog, không tự chế event. Vì module Audit chưa có `AuditModuleApi`/`audit_log` (`ADM-OI-07`), adapter production hiện là structured security log + metric bounded `admin.audit.events{action,class,outcome}` — cùng semantics đã được chấp thuận cho Identity (`DEC-ID-013`). Durable persistence vẫn **BLOCKED**.

## 10. Dependency

| Dependency | Trạng thái | Bằng chứng/blocker |
|---|---|---|
| `BE-ADM-CON-01` | **PARTIAL** | Domain/persistence/state machine/V6 ổn định; REST/idempotency/Limit/Audit còn mở |
| `StepUpChallengePort` | **READY** | `requestChallenge`/`verifyChallenge`/`consumeVerified` chạy thật; consume `MANDATORY`, conditional UPDATE, rollback caller hoàn consume |
| Identity (challenge + role apply) | **READY** | `OtpChallengeService`, `IdentityRoleCommandService`; PostgreSQL tests |
| Security (session/RBAC) | **PARTIAL** | Session + `CHECKER` privilege re-check ở service; HTTP chain gate/CSRF test thuộc `BE-ADM-TST-04` |
| API (REST decision/428 header) | **PARTIAL** | `RoleAdminController`/`LimitAdminController` đã map `X-Step-Up-Challenge`, `Idempotency-Key`, `428`/`403`/`409`/`410`/`503`, no-store; contract/security-chain test đầy đủ còn ở `BE-ADM-TST-04` |
| Audit | **PARTIAL** | Contract + catalog đúng; durable provider BLOCKED (`ADM-OI-07`) |
| Limit provider | **BLOCKED** | `applyApprovedLimitChange` chưa có runtime (`ADM-OI-05`); approve limit fail closed |
| Idempotency proposal | **BLOCKED** | `BE-ADM-CON-03` chưa chốt |
| Maker step-up trước proposal | **BLOCKED** | `MOD-OI-10` gốc: chưa có canonical proposal hash/action ref được duyệt |

## 11. Test và bằng chứng

### 11.1. Lớp test mới

| Lớp | Số test | Phạm vi |
|---|---:|---|
| `ApprovalStepUpBindingTest` | 7 | Determinism, SHA-256 hex, binding key, rejection `approvalId<=0`, độ nhạy từng field, payload đổi, note quá dài |
| `ApprovalDecisionServiceTest` | 7 | 428 không lock/không transition, thiếu `approvalId`, Identity outage fail closed, mismatch + C3 audit, terminal trước consume, maker tự duyệt, thiếu `CHECKER` |
| `ApprovalStepUpPostgreSqlIntegrationTest` | 17 | PostgreSQL 16 + Flyway + JPA + Identity thật: happy path approve role, reject, missing/unknown id, 428 bind, wrong purpose, wrong checker, maker self, cross-approval, wrong decision, forged binding key, expired, consumed, replay, payload change, concurrency, provider rollback |

Tổng: **31 test mới**.

### 11.2. Lệnh chạy

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.application.*' \
  --tests 'com.simulatedbank.banking.admin.domain.*' \
  --tests 'com.simulatedbank.banking.admin.ApprovalStepUpPostgreSqlIntegrationTest' \
  --console=plain

./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.architecture.*' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --tests 'com.simulatedbank.banking.BackendApplicationContextTest' \
  --console=plain
```

### 11.3. Kết quả

| Lần chạy | Kết quả |
|---|---|
| Admin application/domain unit | **BUILD SUCCESSFUL**, 31 tests, 0 fail |
| `ApprovalStepUpPostgreSqlIntegrationTest` | **BUILD SUCCESSFUL**, 17 tests, 0 fail |
| Admin + Architecture + Migration + Context | **BUILD SUCCESSFUL**, 0 fail |
| `ObservabilityIntegrationTest` + `PostgreSqlErrorPipelineIntegrationTest` | **BUILD SUCCESSFUL** sau khi thêm điều kiện `app.identity.enabled` cho bean decision |
| Full `./gradlew cleanTest test` | **BUILD SUCCESSFUL** (7m58s) — **959 tests / 75 classes / 0 failure / 0 error / 0 skip** (baseline trước task: 928/72) |

Lần chạy full đầu tiên phát hiện 14 lỗi context ở `ObservabilityIntegrationTest`/`PostgreSqlErrorPipelineIntegrationTest` (hai harness chạy `app.identity.enabled=false`): `ApprovalStepUpService` inject `StepUpChallengePort` không tồn tại. Sửa bằng `@ConditionalOnProperty({"app.admin.enabled","app.identity.enabled"})` cho `ApprovalStepUpService`/`ApprovalDecisionService` — Admin decision không có nghĩa khi Identity tắt, và controller tương lai sẽ fail-fast thay vì chạy non-functional. Đây là lỗi wiring do task gây ra, đã sửa và chạy lại toàn bộ.

Không có test bị skip và không có lỗi do môi trường (Docker/Testcontainers PostgreSQL 16 chạy bình thường).

### 11.4. Ma trận 16 kịch bản bảo mật

| # | Kịch bản | Test |
|---:|---|---|
| 1 | Challenge + approval hợp lệ | `validStepUpApprovesOnceAndAppliesTheRole`, `rejectConsumesStepUpWithoutTouchingTheProvider` |
| 2 | Thiếu `approvalId` | `missingApprovalIdIsRejectedAndNeverCreatesAChallenge` |
| 3 | `approvalId` không tồn tại | `unknownApprovalIsRejectedWithoutBindingAnything` |
| 4 | Thiếu challenge | `missingChallengeIssuesBound428AndDoesNotTransition` |
| 5 | Sai purpose | `wrongChallengePurposeIsRejected` |
| 6 | Sai checker | `anotherCheckerCannotUseTheChallenge` |
| 7 | Maker tự duyệt | `makerCanNeitherIssueNorUseAStepUpForOwnRequest`, `makerCannotDecideOwnRequestEvenWithACheckedChallenge` |
| 8 | Bind sai approval | `challengeBoundToAnotherApprovalIsRejected` |
| 9 | Hash mismatch | `aDifferentDecisionOnTheSameApprovalIsRejected` |
| 10 | Binding key mismatch | `forgedBindingKeyIsRejectedEvenWithTheCorrectHash` |
| 11 | Hết hạn | `expiredChallengeIsRejected` |
| 12 | Đã consume | `previouslyConsumedChallengeIsRejected` |
| 13 | Replay | `successfulChallengeCannotBeReplayedForASecondDecision` |
| 14 | Payload đổi sau khi tạo challenge | `changedApprovalPayloadRejectsTheChallenge` |
| 15 | Concurrent consume | `concurrentConsumersHaveExactlyOneWinner` |
| 16 | Identity/step-up failure | `stepUpOutageFailsClosedAndKeepsTheApprovalPending`, `providerFailureRollsBackConsumptionAndKeepsTheApprovalPending` |

### 11.5. 428 flow vectors

Repository **không** có bộ "428 flow test vectors" độc lập. Không tuyên bố đã chạy 428 vector; thay vào đó 16 kịch bản §11.4 được implement trực tiếp với PostgreSQL/Identity thật. Nếu owner API/QA cung cấp vector, chạy bổ sung như test contract ở tầng HTTP (`BE-ADM-TST-04`).

## 12. Thay đổi source

| File | Mục đích |
|---|---|
| `admin/application/stepup/ApprovalStepUpBinding.java` | Canonical hash + binding key `approvalId` |
| `admin/application/ApprovalStepUpService.java` | Issue challenge 428, consume trong TX quyết định, public challenge view |
| `admin/application/ApprovalDecisionService.java` | Entry point duy nhất transition: lock, Maker–Checker, consume, provider, audit |
| `admin/application/AdminAuthorization.java` | Actor từ session + privilege `CHECKER` |
| `admin/application/AdminAuditPort.java` | C2/C3 audit contract (catalog action) |
| `admin/adapter/out/SecurityLogAdminAuditAdapter.java` | Security log + metric (durable Audit BLOCKED) |
| `admin/application/port/ApprovalPayloadCanonicalizer.java` | Port canonical payload cho hash |
| `admin/adapter/out/AdminPayloadJsonCodec.java` | Implement canonical JCS cho payload |
| `admin/domain/ApprovalRequest.java` | `assertCheckerEligible` dùng chung cho issue + transition |
| `admin/domain/AdminText.java` | Public normalization dùng chung cho hash/domain |

Không thêm migration, bảng, endpoint, thư viện hay phụ thuộc module mới. Không sửa `07`/OpenAPI vì contract field/route không đổi.

## 13. Còn lại

1. **`BE-ADM-APP-02/API-02` — DONE (18/11/2026):** controller `POST /api/v1/admin/roles/{id}/approve|.../limits/{id}/approve` đã map `X-Step-Up-Challenge`, `428`/`403`/`409`/`410` ra Problem, no-store/idempotency; contract/security-chain test đầy đủ vẫn thuộc `BE-ADM-TST-04`.
2. **Limit provider** — `applyApprovedLimitChange` + expectedVersion (owner Limit); mở `APPROVE LIMIT_CHANGE`.
3. **Durable Audit** — thay `SecurityLogAdminAuditAdapter` bằng `AuditModuleApi`; C2 rollback + C3 failure tests.
4. **Maker step-up (`MOD-OI-10`)** — cần canonical proposal hash + action ref được API/Security duyệt và idempotency (`BE-ADM-CON-03`).
5. **HTTP contract test** — `BE-ADM-TST-04` cho 401/403/428/409/410/503, IDOR/scope, log-leak.
