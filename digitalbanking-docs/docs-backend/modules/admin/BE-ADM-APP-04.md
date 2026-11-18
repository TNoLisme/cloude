# BE-ADM-APP-04 — Tích hợp C2 Audit cho approve/reject và C3 denied-access

> **Mã task:** `BE-ADM-APP-04` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **PARTIAL** — C2/C3 trên `AdminAuditPort` đã hoàn chỉnh và có test (transaction boundary, outcome,
> actor tin cậy, catalog action, không duplicate, structured log/metric + allowlist), C3 HTTP denial có 403 +
> `SECURITY_ACCESS_DENIED` đúng catalog. **BLOCKED:** durable `${AuditModuleApi}`/`audit_log`/hash-chain
> (`ADM-OI-07`) vì module `audit` chưa có runtime; adapter production hiện là structured security log + metric.
> **Nguồn:** `design/01` §5.2/§5.3/§5.4/§5.6/§6.3, `design/05` §11.1, `07` §4.3, `BE-ADM-CON-01/02/03`,
> `BE-ADM-DOM-01-APP-01`, `BE-ADM-APP-02/03`, source `admin/*` + `common/config`, `common/logging`.

## 1. Kết luận

1. Không có `audit.api.AuditModuleApi` trong repository (`src/main/java/com/simulatedbank/banking/audit/` chỉ có
   `.gitkeep`; grep toàn repo không thấy interface). Contract trong `design/05` §11.1 là **thiết kế**, chưa có
   runtime/DTO do Audit owner cung cấp.
2. Vì vậy task này **không thể** gọi `AuditModuleApi` trực tiếp. Phần độc lập-kiểm-chứng được là boundary
   `AdminAuditPort` + semantics C2/C3 + structured security log/metric; đã hoàn thiện theo `design/01` §5.2 và
   giữ nguyên seam để thay adapter khi Audit có provider (không tạo fake production bean).
3. Durable persistence, hash chain, retention, `audit_log` vẫn **BLOCKED** (`ADM-OI-07`, owner Audit/Thịnh).

## 2. Boundary và catalog (không tạo action mới)

| Thành phần | Source | Trạng thái |
|---|---|---|
| `AdminAuditPort` (`recordInTransaction` C2, `recordStandalone` C3) | `admin/application/AdminAuditPort.java` | Có sẵn từ CON-02; giữ nguyên contract |
| `AdminAction` | cùng file | 7 action trong catalog `design/01` §5.3: `ROLE_CHANGE_REQUESTED/APPROVED/REJECTED`, `LIMIT_CHANGE_REQUESTED/APPROVED/REJECTED`, `SECURITY_ACCESS_DENIED` |
| Adapter production | `admin/adapter/out/SecurityLogAdminAuditAdapter.java` | Structured security log (KV) + `admin.audit.events`, `admin.audit.failures` |
| C3 HTTP chain | `common/config/SecurityAccessDeniedAuditor.java` + `SecurityFilterChainConfig` | 403 authenticated denial → `SECURITY_ACCESS_DENIED` (`APPROVAL_REQUEST`) + `security.audit.events` |
| Stdout allowlist | `common/config/SafeJsonLogEncoder.java` | Thêm logger `admin.AUDIT` + action/`audit_class`/`request_type`/`outcome`/`reason_code` bounded |

Không thêm action, không đổi tên action, không tạo catalog identifier trùng. `SECURITY_ACCESS_DENIED` là action duy
nhất dùng cho C3; không có `MAKER_CHECKER_VIOLATION` tự chế.

## 3. Hành vi C2 (approve/reject)

- Action: `ROLE_CHANGE_APPROVED`/`ROLE_CHANGE_REJECTED` (role) và `LIMIT_CHANGE_REJECTED` (limit reject;
  `LIMIT_CHANGE_APPROVED` chưa mở vì Limit provider `ADM-OI-05`).
- Actor: `checkerId` từ `SessionAuthenticator.Session`; entity: `APPROVAL_REQUEST`, `entityId = approvalId`;
  `requestType`, `correlationId`, `requestId`, `outcome = SUCCESS` (thao tác đã hoàn tất và state bền vững),
  `reasonCode = null`.
- Vị trí: audit là **write cuối** trong transaction quyết định — sau `approvals.update`, `idempotency.complete`,
  trước commit (`ApprovalDecisionService.decideInTransaction`). Provider (Identity role) áp dụng trong cùng
  transaction (`MANDATORY`/JOIN).
- Failure semantics: `recordInTransaction` ném exception ⇒ toàn bộ transaction rollback (approval giữ `PENDING`,
  provider rollback, challenge trở lại `VERIFIED`, idempotency claim biến mất). Không bắt exception trong
  transaction rollback-only. Test: `c2AuditFailureRollsBackApprovalProviderAndChallengeConsumption`,
  `c2FailurePropagatesToTheCallerTransaction`.
- Không có audit success cho transaction rollback: audit nằm sau mọi business write và trước commit, nên rollback
  không để lại fact; replay cùng key trả response đã lưu và **không** ghi C2 lần hai
  (`replayedDecisionDoesNotWriteAnotherAuditFact`, `approveThenReplayConflictAndOneBusinessEffect`).

## 4. Hành vi C3 (denied access)

| Kịch bản | HTTP | Audit |
|---|---|---|
| Thiếu/invalid/expired session | `401 AUTH_SESSION_EXPIRED` | **Không** audit (design/01 §5.7); chứng minh ở `anonymousRequestIs401AndNotAudited` |
| Authenticated nhưng thiếu authority (`ROLE_MANAGE`, `CHECKER`) | `403 AUTH_FORBIDDEN` | `SECURITY_ACCESS_DENIED`, C3, outcome `REJECTED`, reason `AUTH_FORBIDDEN`, đúng **một** fact |
| Cookie session thiếu `X-CSRF-Token` | `403 AUTH_FORBIDDEN` | Không phát `SECURITY_ACCESS_DENIED` (không phải authorization denial; không conflate) |
| Maker tự quyết / target trùng actor | `409 ROLE_CONFLICT` | Một C3 từ service (`auditDenial`), không bị nhân đôi bởi outer handler |
| Binding/purpose/expiry/mismatch challenge, terminal, idempotency conflict | `404/409/410` theo `07` §4 | Một C3 với `reasonCode` là `ErrorCode` name |
| Thiếu challenge (428 flow) | `428 STEP_UP_REQUIRED` | **Không** C3 (luồng cấp challenge bình thường) |
| Approval không tồn tại | `404 APPROVAL_NOT_FOUND` | Không C3 (anti-enumeration) |

- Entity mapping (đã sửa): mọi path `/api/v1/admin/{limits|roles|approvals}[/{approvalId}][/approve]` →
  `entity_type=APPROVAL_REQUEST`, `entity_id=approvalId` (design/01 §6.3). Trước đây `roles` → `ROLE` (không có
  trong catalog) và path `/approve` → `UNKNOWN`; cả hai đã bị loại.
- Field phát ra chỉ gồm allowlist: `action`, `audit_class`, `outcome`, `actor_user_id`, `actor_roles`,
  `entity_type`, `entity_id`, `reason_code`, `correlation_id`, `request_id`. Không log payload, decision note, OTP,
  challenge ref/hash, token hay PII. Test: `SecurityAccessDeniedAuditorTest` (5), `SecurityLogAdminAuditAdapterTest` (4).
- Failure C3: adapter nuốt exception để 403/409 không thành 500 nhưng **không im lặng** — tăng
  `admin.audit.failures{action,class}`, phát `SECURITY_AUDIT_WRITE_FAILED` và metric alert
  (`c3FailureIsContainedButCountedAndLogged`). Chain-level auditor cũng tăng
  `security.audit.failures{action,outcome=WRITE_FAILED}` + `SECURITY_AUDIT_WRITE_FAILED` khi ghi fact thất bại, và
  không đổi response 403 (`writeFailureIsCountedWithoutDroppingTheDenial`).

## 5. Structured log

`SafeJsonLogEncoder` chỉ serialize field nằm trong allowlist, không bao giờ ghi raw message/argument. Task này bổ
sung logger `com.simulatedbank.banking.admin.AUDIT` và các key bounded cho catalog Admin; request/response audit
fact hiện xuất hiện trên stdout dưới dạng
`{"event":"SECURITY_AUDIT","action":"ROLE_CHANGE_APPROVED","audit_class":"C2",...}` thay vì bị drop thành
`APPLICATION_LOG`. `AUDIT_ENTITY_TYPES` đã bỏ `ROLE` (không thuộc catalog).

## 6. Test (evidence)

| Lớp | Nội dung | Kết quả |
|---|---|---|
| `SecurityLogAdminAuditAdapterTest` (mới) | C2 structured fact + metric; C2 failure propagate; C3 failure contained + failure metric + structured fallback; không field nhạy cảm | 4 PASS |
| `SecurityAccessDeniedAuditorTest` (+5) | mapping `APPROVAL_REQUEST` cho roles & `/approve`, giữ `LIMIT_CONFIG`/`ACCOUNT` cho non-admin, 401 không audit, không rò token, failure metric khi ghi fact lỗi | 6 PASS |
| `SafeJsonLogEncoderTest` (mới) | chỉ field allowlist được serialize; action/entity ngoài catalog và payload/note/OTP/token bị drop; non-audit logger không lộ KV/message | 4 PASS |
| `ApprovalDecisionServiceTest` (+3) | C2 approve/reject đúng action/actor/entity/outcome, không provider khi reject, replay không ghi audit | 11 PASS |
| `AdminHttpAuditPostgreSqlIntegrationTest` (mới) | 401 không audit; 403 + đúng 1 C3 fact + correlation + no-store; 403 approve giữ approvalId; 404 không audit; 428 không tạo approval/idempotency/C2; CSRF cookie; flow 428 → verify OTP → 201 + `Location` + 1 C2 + replay không duplicate; unknown role target 404 không state | 8 PASS |
| Regression Admin/migration/security | `ApprovalStepUpPostgreSqlIntegrationTest`, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest`, `ApprovalProposalPostgreSqlIntegrationTest`, `AdminIdempotencyPostgreSqlIntegrationTest`, controller/unit tests | PASS |

Lệnh:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.common.config.*' \
  --tests 'com.simulatedbank.banking.common.context.*' \
  --tests 'com.simulatedbank.banking.common.logging.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' \
  --console=plain
```

Kết quả: **BUILD SUCCESSFUL** — 35 classes / 297 tests / 0 failure / 0 error / 0 skip (PostgreSQL 16 Testcontainers
+ Flyway thật, security chain thật). Chạy thêm `DeviceMfaLoginHttpIntegrationTest` (22 test) cùng suite: **319
tests PASS** — dùng để loại trừ flake hạ tầng của full build (báo cáo API-01 §7).

## 7. Acceptance checklist

| # | Yêu cầu | Trạng thái |
|---|---|---|
| 1 | Approve ghi C2 đúng action/actor/target/outcome | PASS |
| 2 | Reject ghi C2, không gọi provider | PASS |
| 3 | Rollback không tạo success audit | PASS |
| 4 | Audit failure theo transaction contract | PASS (C2 fail-closed, C3 best-effort + metric/alert) |
| 5 | Provider state đúng khi audit fail | PASS (PostgreSQL + Identity thật) |
| 6 | Không duplicate audit khi retry/replay | PASS |
| 7 | C3 forbidden trả 403 | PASS (HTTP chain thật) |
| 8 | C3 có structured security event | PASS (stdout allowlist + metric) |
| 9 | Log không lộ secret/PII | PASS (allowlist + assertion) |
| 10 | Tái dùng action catalog, không duplicate | PASS |

## 8. Blockers

| Blocker | Owner | Điều kiện đóng |
|---|---|---|
| `ADM-OI-07` durable `AuditModuleApi`/`audit_log`/hash chain | Audit (Thịnh) + Security | Provider thật; thay `SecurityLogAdminAuditAdapter`, thêm test C2 rollback ở tầng DB, C3 persistence |
| `ADM-OI-05` Limit provider | Limit (Vũ) | `applyApprovedLimitChange` + `limit_config PENDING_APPROVAL`; khi đó bật `LIMIT_CHANGE_APPROVED` |
| `ADM-OI-06` data scope | Admin/Security/BA | Scope contract + test cross-scope |

> Không ghi `DONE` cho durable audit khi chưa có provider; không dùng security-log fixture như bằng chứng
> `audit_log`.
