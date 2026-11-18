# BE-ADM-API-02 — Approve/Reject REST API

> **Mã task:** `BE-ADM-API-02` (P0)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **IMPLEMENTED** cho `ROLE_CHANGE` approve/reject và `LIMIT_CHANGE` reject; **`LIMIT_CHANGE`
> approve vẫn BLOCKED** do Limit provider chưa có (`ADM-OI-05`, fail-closed 503 — không phải lỗi API).
> **Nguồn:** `07` §8.5.3/§8.8.2/§6.5, OpenAPI `/admin/limits/{id}/approve`, `/admin/roles/{id}/approve`;
> `design/02` §5.2/§7.9/§9.6; `design/04` §5; `design/05` §12.1; `BE-ADM-CON-02/03`, `APP-02/03/04`.

## 1. Endpoint & controller

| API | Method/path | Controller | Trạng thái |
|---|---|---|---|
| `API-ROL-02` | `POST /api/v1/admin/roles/{approvalId}/approve` | `RoleAdminController#decide` | DONE (APPROVE→`APPLIED`, REJECT→`REJECTED`) |
| `API-LIM-03` | `POST /api/v1/admin/limits/{approvalId}/approve` | `LimitAdminController#decide` | REJECT DONE; APPROVE fail-closed 503 (`ADM-OI-05`) |

Request DTO `ApprovalDecisionHttpRequest` chỉ có `decision` + `decisionNote`; `checkerId`/`makerId`/
`expectedVersion` không phải input (field lạ → `400 MALFORMED_REQUEST`). Response là `ApprovalDetail` map đã mask
(`payload` không có `expectedVersion`, `result` chỉ có khi provider trả). `Idempotency-Key` bắt buộc;
`X-Step-Up-Challenge` bind theo `approvalId` + canonical decision hash.

## 2. Luồng quyết định (service hiện hữu + hardening)

`ApprovalDecisionService.decide` giữ nguyên thứ tự đã chốt ở APP-02/03:

1. Session tin cậy + privilege `CHECKER`.
2. **Mới (API-02):** re-check quyền theo loại approval từ chính session — `ROLE_CHANGE` cần `ROLE_MANAGE`,
   `LIMIT_CHANGE` cần `LIMIT_MAKER`/`LIMIT_MANAGE` (`AdminAuthorization.assertCanReview`). Thiếu → `403
   AUTH_FORBIDDEN` trước idempotency/lock/provider; không tin URL/chain một mình.
3. Idempotency precheck → replay/conflict/in-progress theo `07` §5.6.
4. Lock `SELECT … FOR UPDATE`, re-check `PENDING`/type/Maker–Checker/target, stale provider version.
5. Consume `SENSITIVE_ACTION` trong transaction; provider Identity role (JOIN) cho role; Limit approve fail-closed.
6. Persist `APPLIED`/`REJECTED`, hoàn tất idempotency, C2 audit cuối transaction.

Không có workflow thứ hai; không đổi semantics đã được CON-02/03/APP-02/03 phê duyệt. Approval limit policy
(`422 LIMIT_OUT_OF_POLICY`) thuộc Limit provider chưa có (`ADM-OI-05`) nên chưa có đường production.

## 3. Test evidence

| Lớp | Nội dung | Kết quả |
|---|---|---|
| `ApprovalDecisionHttpPostgreSqlIntegrationTest` (mới) | 428→verify OTP→200 `APPLIED` (Identity role áp một lần, replay `Idempotent-Replayed`, key khác terminal → 409); reject 200 `REJECTED` không mutate provider; maker-self 409 `ROLE_CONFLICT` + C3; challenge bind sai approval bị từ chối; checker thiếu quyền loại bị 403; **2 checker approve đồng thời → một 200/một 409, role áp một lần** | 6 PASS |
| `ApprovalDecisionServiceTest` (+1) | `assertCanReview` chặn trước precheck/lock/provider; C2 approve/reject đúng action/actor/outcome; replay không audit | 12 PASS |
| `ApprovalStepUpPostgreSqlIntegrationTest` | 22 kịch bản APP-02/03 (challenge, provider rollback, race) — regression sau hardening | 22 PASS |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | replay/conflict/in-progress, C2 failure rollback, limit reject idempotent | 11 PASS |
| `RoleAdminControllerTest` / `LimitAdminControllerTest` | 400/428/200/no-store/replay; limit approve 503 | 8 + 2 PASS |

Chạy:

```bash
./gradlew test --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
```

Kết quả: **BUILD SUCCESSFUL — 25 classes / 221 tests / 0 failure / 0 error / 0 skip** (PostgreSQL 16
Testcontainers, Flyway, security chain + Identity thật; OTP transport là test double duy nhất). Broad regression
(Admin + Common config/context/logging + ArchUnit + Customer admin HTTP): **BUILD SUCCESSFUL — 39 classes / 330
tests / 0 failure**.

Full build `./gradlew cleanTest build`: **BUILD SUCCESSFUL** (9m15s) — **111 classes / 1281 tests / 0 failure /
0 error / 0 skip**.

## 4. Acceptance checklist

| # | Yêu cầu | Trạng thái | Bằng chứng |
|---|---|---|---|
| 1 | Approve → `200 APPLIED` | PASS | HTTP flow test; body `status=APPLIED`, `appliedAt` |
| 2 | Reject → `200 REJECTED` | PASS | HTTP flow test; `decidedAt`, `appliedAt=null`, không provider |
| 3 | Step-up bind đúng | PASS | 428 bound `approvalId`; verify OTP; mismatch approval → 404/409, state PENDING |
| 4 | Duplicate/stale | PASS | replay 200 + header; key khác terminal 409; service/PG stale version `ROLE_CONFLICT` |
| 5 | Maker-self denied | PASS | 409 `ROLE_CONFLICT` trước challenge, PENDING, C3 một fact |
| 6 | Role/scope/limits | PASS phần có runtime | loại approval re-check 403; limit policy **BLOCKED** `ADM-OI-05` |
| 7 | Masking | PASS | `payload` không `expectedVersion`, không secret/hash; `result` chỉ field provider |
| 8 | Không partial success | PASS | provider/C2 failure rollback (PG suite); limit approve 503 không state |
| 9 | OpenAPI | PASS | mô tả quyền theo loại, replay, masking; path/method/status khớp |

**Blocker:** `ADM-OI-05` Limit provider cho `LIMIT_CHANGE` approve (`422` policy, `result` limit); `ADM-OI-06`
phân công checker/tenant (hiện scope theo loại approval, DEC-ADM-009).
