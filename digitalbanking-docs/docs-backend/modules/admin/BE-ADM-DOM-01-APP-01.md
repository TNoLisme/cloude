# BE-ADM-DOM-01 / BE-ADM-APP-01 — Domain model và proposal application service

> **Mã task:** `BE-ADM-DOM-01`, `BE-ADM-APP-01` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái:** `BE-ADM-DOM-01` **COMPLETE**; `BE-ADM-APP-01` **PARTIAL** — `ROLE_CHANGE` proposal chạy end-to-end; `LIMIT_CHANGE` proposal vẫn fail-closed 503 vì Limit provider/`limit_config PENDING_APPROVAL` chưa có runtime (`ADM-OI-05`).  
> **Nguồn:** `docs-common/03–09`, `design/01/02/04/05`, `docs-backend/admin/BE-ADM-CON-01/02/03/04`, `BE-ADM-DB-01/02/03`, OpenAPI và source hiện hữu.

## 1. Kết luận

Đã hoàn thiện phần domain còn thiếu của `DOM-01` và xác nhận application service của `APP-01` cho luồng role:

- Enum/typed payload/state machine đã có từ `CON-01`; bổ sung **transition matrix tập trung** trong `ApprovalStatus` và dùng nó trong aggregate, không để controller/adapter tự quyết định transition.
- Thêm test biên cho enum, terminal state, money/limit, role conditional fields, target mapping, reason/control character và payload serialization/masking.
- Xác nhận `ApprovalProposalService.proposeRole` thực hiện đúng thứ tự: session → permission → idempotency precheck → capture `expectedVersion` → Maker step-up → insert-first claim → consume challenge → `approval_request` PENDING → C2 audit → complete idempotency → 201.
- `proposeLimit` vẫn **không tạo state**: validate đầy đủ shape rồi trả `503 SERVICE_UNAVAILABLE`. Lý do là `07 §8.5.2` yêu cầu tạo `approval_request LIMIT_CHANGE` **và** `limit_config PENDING_APPROVAL` trong cùng transaction, trong khi `limit.api`/schema Limit chưa có runtime. Không tạo approval nửa vời, không fake provider production.

## 2. Domain enums

| Enum | Giá trị | Ghi chú |
|---|---|---|
| `ApprovalStatus` | `PENDING`, `APPROVED`, `REJECTED`, `APPLIED`, `CANCELLED` | Canonical `04 §4.5.3`; `APPROVED` schema-reserved, không có transition MVP |
| `ApprovalRequestType` | `LIMIT_CHANGE`, `ROLE_CHANGE`, `RETENTION_HOLD`, `RETENTION_POLICY_CHANGE` | Chỉ hai loại đầu `openInMvp()` |
| `ApprovalTargetType` | `CUSTOMER`, `ACCOUNT`, `APP_USER`, `DATA_SCOPE`, `RETENTION_POLICY` | Target do typed payload quyết định |
| `ApprovalDecision` | `APPROVE`, `REJECT` | Decision API |
| `RoleChangeOperation` | `ASSIGN_ROLE`, `REVOKE_ROLE`, `GRANT_PRIVILEGE`, `REVOKE_PRIVILEGE` | 07 §8.8.1 |
| `AdminIdempotencyOperation` | `CREATE_LIMIT_PROPOSAL`, `CREATE_ROLE_PROPOSAL`, `DECIDE_APPROVAL` | Scope idempotency CON-03 |
| `AdminIdempotencyStatus` | `RECEIVED`, `COMPLETED`, `FAILED` | `RECEIVED` không purge; `FAILED` schema-reserved |
| `AuditClass` / `AdminAction` | C2/C3; catalog action Admin | Audit port, không tự thêm action ngoài catalog |

Không thêm enum/business value ngoài contract.

## 3. State transition matrix

```text
PENDING --approve--> APPLIED     (provider apply + C2 audit cùng transaction)
PENDING --reject---> REJECTED    (không gọi provider)
PENDING --cancel---> CANCELLED   (chưa mở API MVP)
APPROVED                          schema-reserved; không reachable trong MVP
REJECTED / APPLIED / CANCELLED    terminal
```

`ApprovalStatus.canTransitionTo(target)` là nguồn duy nhất của matrix; `requireTransitionTo` ném
`APPROVAL_ALREADY_DECIDED` cho transition không hợp lệ/duplicate. `ApprovalRequest.approve/reject/cancel` gọi
matrix này trước khi mutate; `cancel` không yêu cầu actor vì API chưa mở. `assertCheckerEligible` vẫn được dùng
chung cho issue step-up và transition để chặn Maker–Checker/role-target trước khi consume proof.

## 4. Domain invariants

- `ApprovalRequest.propose` chỉ nhận payload `openInMvp()`, maker ID dương, correlation bắt buộc 1–64 ký tự.
- Target được suy ra từ payload: limit không `accountId` → `CUSTOMER`; có `accountId` → `ACCOUNT`; role → `APP_USER`.
- Maker không được là target của `ROLE_CHANGE`; checker không được là maker hoặc target role.
- `restore` kiểm tra invariant của row đã persist: decided state phải có checker + `decidedAt`; `APPLIED` phải có
  `appliedAt`; `PENDING` không được có decision fields; checker ≠ maker.
- Reason/decisionNote tối đa 500 ký tự, strip/null normalization, từ chối control character; không tự nhận diện PII
  (theo CON-01 §13.2 quyết định 5) và không log giá trị bị từ chối.
- `expectedVersion` của role là server-captured, không nhận từ client; không xuất hiện trong public payload.

## 5. Limit/money và role payload

### Limit

- `customerId` bắt buộc, dương; `accountId` optional, nếu có phải dương.
- `perTransactionLimit`, `dailyLimit` bắt buộc, `> 0`, tối đa 2 chữ số thập phân (chuẩn hóa scale 2, từ chối phần lẻ
  khác 0 vượt scale), tối đa 17 chữ số phần nguyên; `perTransactionLimit <= dailyLimit`.
- `currency` mặc định `VND`; giá trị khác bị từ chối.
- `effectiveFrom` optional; chưa áp policy quá khứ/tương lai vì provider/BA chưa chốt.
- `reason` optional, ≤ 500, không control character.

### Role

- `ASSIGN_ROLE`/`REVOKE_ROLE`: bắt buộc `roleCode` thuộc `CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR`;
  `privilegeCode` phải null.
- `GRANT_PRIVILEGE`/`REVOKE_PRIVILEGE`: bắt buộc `privilegeCode=CHECKER`; `roleCode` phải null.
- `targetUserId` dương; `expectedVersion >= 0` và khác `Long.MAX_VALUE`; maker ≠ target được enforce ở aggregate.
- Payload role persist `expectedVersion`; public response bỏ field này.

## 6. Proposal application service (`APP-01`)

Luồng `ROLE_CHANGE`:

1. `AdminAuthorization.requireAuthority("ROLE_MANAGE")` lấy actor từ `SessionAuthenticator.Session`.
2. Validate correlation/requestId/idempotency key và typed role payload; maker ≠ target.
3. `AdminIdempotencyService.precheck` bằng canonical hash **client payload** (không gồm `expectedVersion`).
   Terminal cùng hash → replay 201/body gốc; khác hash → `409 IDEMPOTENCY_CONFLICT`; `RECEIVED` → 409 in-progress.
4. Capture `IdentityRoleCommand.getRoleAssignmentVersion(targetUserId)`.
5. Thiếu `X-Step-Up-Challenge` → issue challenge `SENSITIVE_ACTION` bind `bindingKey=Idempotency-Key` +
   `ADM-PROPOSAL-STEPUP-V1` hash (gồm `expectedVersion`) và trả `428 STEP_UP_REQUIRED`; không tạo state.
6. Retry có challenge: mở transaction, insert-first claim, `consumeVerified`, insert `approval_request` PENDING,
   complete idempotency response, ghi C2 `ROLE_CHANGE_REQUESTED` cuối cùng.
7. Trả `201 ApprovalSummary` `status=PENDING` + `Location` + `Cache-Control: no-store`.

`LIMIT_CHANGE`:

- Service vẫn authorize `LIMIT_MAKER`/`LIMIT_MANAGE`, validate key và payload.
- Sau validation trả `503 SERVICE_UNAVAILABLE`; không tạo approval, idempotency record, audit C2 hoặc provider call.
- Điều kiện mở: Limit cung cấp `limit.api`/`limit_config PENDING_APPROVAL` và contract version (`ADM-OI-05`).

## 7. Authentication, authorization, step-up

- Actor luôn lấy từ trusted session; body/header không có `makerId`/`checkerId`.
- Thiếu session → `AUTH_SESSION_EXPIRED`; thiếu permission → `AUTH_FORBIDDEN`; service fail closed trước mọi write.
- Không tạo JWT/refresh-token/challenge repository song song; dùng `StepUpChallengePort` của Identity.
- Challenge Maker bind idempotency key + proposal hash; challenge Checker bind `approvalId` + decision hash.
- Provider step-up failure/timeout không được coi là thành công; không tạo approval/không complete idempotency.

## 8. Canonical hash và idempotency

- Domain separator `ADM-IDEMPOTENCY-V1`; RFC 8785 JCS; SHA-256 lowercase hex.
- Role proposal hash gồm actor, operation, request/target, operation role, target user, role/privilege code, reason;
  **loại trừ** `expectedVersion`, header, correlation/request id, timestamp, challenge ref.
- Limit proposal hash gồm customer/account, money scale-2 string, currency, effectiveFrom, reason.
- Decision hash gồm actor, approvalId, decision, normalized note.
- Scope `(actor_id, operation, scope_key, idempotency_key)`; proposal `scope_key=''`, decision `scope_key=approvalId`.
- Insert-first `ON CONFLICT DO NOTHING`; replay/conflict/in-progress/TTL/purge giữ nguyên CON-03.

## 9. Transaction, audit, failure

- Proposal write transaction chứa claim + consume challenge + approval insert + idempotency complete + C2 audit.
  Bất kỳ lỗi nào rollback toàn bộ; retry cùng key chạy lại sạch.
- C3 `SECURITY_ACCESS_DENIED` chạy standalone cho idempotency conflict/rejection đã được audit; lỗi C3 không đổi
  response 403/409 thành 500.
- Audit adapter hiện tại là security log + metric bounded; durable `AuditModuleApi` vẫn BLOCKED (`ADM-OI-07`).
- Không log payload, reason, decision note, token, OTP, challenge ref, hash hay raw idempotency key.
- Failure mapping dùng `ErrorCode`/Problem pipeline chung: 400/401/403/404/409/428/503; không lộ stack trace/SQL.
- `AdminHttpSupport.id` kiểm pattern và bắt `NumberFormatException` để ID 19 chữ số vượt `Long.MAX_VALUE` trả
  `400 VALIDATION_ERROR`, không để parser thành 500; controller test BIGINT overflow xác nhận không gọi service.

## 10. Test và kết quả

Đã chạy:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --console=plain --no-daemon
```

Kết quả thực tế: **BUILD SUCCESSFUL** — 26 classes / 216 tests / 0 failure / 0 error / 0 skip
(Admin 17 class/147 test, migration 8 class/51 test, ArchUnit 1 class/18 test). Các lớp mới/bổ sung:

| Lớp | Nội dung |
|---|---|
| `ApprovalDomainModelTest` | Enum parsing, transition matrix, terminal state, unsupported non-terminal transition, money boundary/overflow, currency, role conditional fields, target mapping, reason/control char |
| `ApprovalProposalServiceTest` | Unauthenticated/forbidden, missing key, invalid payload, maker self-target, provider version failure, step-up failure, 428 no state, replay, conflict C3 audit, successful PENDING + C2 audit, consume failure no complete |
| `AdminPayloadJsonCodecTest` | Public role payload bỏ `expectedVersion`, persisted payload giữ version, limit decimal/ISO, canonical JSON không chứa secret |
| `RoleAdminControllerTest` (+1) | `targetUserId` vượt BIGINT trả 400 và không gọi service |

Regression hiện hữu: `ApprovalRequestTest`, `AdminRequestHasherTest`, `ApprovalDecisionServiceTest`,
`ApprovalStepUpBindingTest`, controller tests, PostgreSQL/Identity idempotency + step-up + decision tests,
Flyway migration tests và ArchUnit boundary.

## 11. Evidence

- Admin + migration + ArchUnit: **BUILD SUCCESSFUL** — 26 classes / 216 tests / 0 failure / 0 error / 0 skip.
- Split: Admin 17 class/147 test; migration 8 class/51 test; ArchUnit 1 class/18 test.
- Full `cleanTest build`: **BUILD SUCCESSFUL** — 98 classes / 1.166 tests / 0 failure / 0 error / 0 skip (§11.1).
- Lưu ý môi trường: một lần chạy bị `TestOutputStore`/`EOFException` do nhiều Gradle daemon ghi cùng
  `build/test-results`; đã `./gradlew --stop`, xóa generated test-results và chạy lại `--no-daemon` thành công.
  Không phải lỗi source/test.

## 11.1. Full build và flaky test ngoài Admin

`./gradlew cleanTest build --console=plain --no-daemon` (final): **BUILD SUCCESSFUL** — 98 classes / 1.166 tests /
0 failure / 0 error / 0 skip.

Một lần chạy trước đó fail `ObservabilityIntegrationTest.realHttpMetricsCountLatencyErrorsAndUseBoundedLabels`
(`ObservabilityIntegrationTest.java:188`): `count("UNKNOWN", "401")` trả `0` thay vì `> 0`. Rerun riêng vẫn fail
cùng assertion, nhưng final clean build PASS toàn bộ; đây là flaky/environmental test ngoài Admin, không phải
regression của task này. `ObservabilityIntegrationTest` đã có thay đổi chưa commit từ công việc khác
(comment `BE-ACC-05`, thêm request `/api/v1/unmapped/{LEAK}`) và không được task này sửa. Không sửa mù test/
observability; 216 test Admin/migration/ArchUnit và full build final đều PASS.

## 12. Còn lại / dependency

| Hạng mục | Trạng thái | Điều kiện mở |
|---|---|---|
| `LIMIT_CHANGE` proposal + approve | **BLOCKED** (`ADM-OI-05`) | `limit.api`/`limit_config PENDING_APPROVAL`, expectedVersion/policy, rollback test |
| Durable Audit C2/C3 | **BLOCKED** (`ADM-OI-07`) | `AuditModuleApi`/`audit_log`/hash chain; thay security-log adapter |
| Staff data scope/tenant | **BLOCKED** (`ADM-OI-06`, schema v1 single-tenant) | Scope model + tenant column/unique index trước khi mở đa tenant |
| HTTP security-chain contract | **PARTIAL** (`BE-ADM-TST-04`) | Test 401/403/CSRF/rate-limit/IDOR trên chain thật |

`APP-01` không được đánh dấu hoàn tất toàn phần khi Limit provider còn thiếu; `ROLE_CHANGE` path đã được kiểm
chứng và không có thay đổi hành vi ngoài contract.
