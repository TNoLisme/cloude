# BE-ADM-CON-04 — Resolve Admin Schema Conflicts

> **Mã task:** `BE-ADM-CON-04` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái tổng:** **DONE (schema conflicts resolved, documentation updated)** — BIGINT/canonical status/expiration/challenge conflicts đã rà soát và chốt theo `04` §4.5.3; source/migration hiện tại đã khớp canonical; không cần migration mới. Partial unique PENDING vẫn **BLOCKED** chờ DB owner duyệt.  
> **Nguồn ưu tiên:** `docs-common/04. Database design & analysis.md` §4.5.3/§4.5.4 > `07. API contracts.md` §7.2.14/§8.5/§8.8 > `design/05. Modules contract.md` §12.1 > `docs-backend/modules/_overview/10-admin.md`.

## 1. Kết luận

Các xung đột schema của Admin module đã được xác định, đối chiếu với nguồn chuẩn và giải quyết bằng cách **giữ nguyên canonical DDL đã có trong `V6/V7`**, cập nhật tài liệu nháp (`_overview/10-admin.md`) và `design/04. security.md` cho khớp canonical, ghi rõ quyết định vào `DECISIONS.md` (`DEC-ADM-004`).

Không tạo migration mới vì:
- `V6__create_approval_request_table.sql` đã đúng canonical `04` §4.5.3.
- `V7__create_admin_idempotency_record_table.sql` đã đúng canonical `04` §4.5.4.
- Entity, repository, DTO, enum và workflow hiện tại đã dùng `BIGINT`, status canonical, không `expires_at`/`step_up_challenge_id` trên `approval_request`.

## 2. Các nguồn mâu thuẫn đã rà soát

| Nguồn | Mô tả | Thẩm quyền |
|---|---|---|
| `docs-common/04. Database design & analysis.md` §4.5.3 | DDL canonical `approval_request`: `BIGINT`, 5 status, không expiry/challenge FK. | **Cao nhất** |
| `docs-common/04. Database design & analysis.md` §4.5.4 | DDL canonical `admin_idempotency_record`: `BIGINT`, `expires_at`, unique scope. | **Cao nhất** |
| `07. API contracts.md` §7.2.14/§8.5/§8.8 | `approvalId` là `ID` (BIGINT string), `ApprovalStatus` 5 giá trị canonical, `APPLIED` là kết quả thành công. | Cao |
| `digital-banking-v1.yaml` (OpenAPI) | `ApprovalStatus` enum: `PENDING, APPROVED, REJECTED, APPLIED, CANCELLED`. | Cao |
| `design/05. Modules contract.md` §12.1 | Ghi rõ `V6` canonical, không UUID/EXPIRED/expiry/challenge/result/version. | Trung bình |
| `docs-backend/modules/_overview/10-admin.md` | Bản nháp cũ: `UUID`/`BIGINT`, `EXPIRED`, `expires_at`, `step_up_challenge_id`, `maker_reason`/`checker_reason`. | Thấp nhất — đã cập nhật |
| `docs-common/design/04. security.md` | Bản nháp ghi "lưu vết `step_up_challenge_id` đã tiêu thụ trên cả `transfer` và `approval_request`". | Thấp — đã cập nhật |

## 3. Ma trận xung đột và quyết định

| Component | Current Implementation | Canonical Definition | Required Action |
|---|---|---|---|
| **Identifier types** | `BIGINT` PK/FK (`approval_id`, `maker_id`, `checker_id`, `record_id`, `actor_id`, `approval_id` FK); API string `ID` | `04` §4.5.3/§4.5.4: `BIGINT IDENTITY` | **No code change** — khớp canonical; cập nhật `_overview/10-admin.md` xóa `UUID` |
| **Approval statuses** | `ApprovalStatus` 5 giá trị: `PENDING, APPROVED, REJECTED, APPLIED, CANCELLED`; `APPROVED` schema-reserved, MVP `PENDING→APPLIED/REJECTED` | `04` §4.5.3 CHECK + `07` §7.2.14 | **No code change** — khớp canonical; cập nhật `_overview/10-admin.md` xóa `EXPIRED` |
| **Expiration fields** | `approval_request` không có `expires_at`; `admin_idempotency_record` có `expires_at` > `created_at`, TTL 24h từ `completed_at`; challenge expiry do `auth_challenge.expires_at` | `04` §4.5.3 (không expiry trên approval); `04` §4.5.4 (expiry trên idempotency); `04` §4.2.5 (challenge expiry) | **No code change** — khớp canonical; cập nhật `_overview/10-admin.md` xóa `expires_at` trên approval |
| **Challenge fields** | `approval_request` không có `step_up_challenge_id`; Checker binding qua `auth_challenge.idempotency_key` + `request_hash` (DEC-ADM-002) | `04` §4.5.3 (không challenge FK); `04` §4.2.5 (`auth_challenge` binding fields) | **No code change** — khớp canonical; cập nhật `_overview/10-admin.md` và `design/04. security.md` |
| **Foreign keys** | `maker_id/checker_id → app_user.user_id` (BIGINT→BIGINT); `admin_idempotency_record.approval_id → approval_request.approval_id` (BIGINT→BIGINT) | `04` §4.5.3/§4.5.4 | **No code change** — kiểu tương thích |
| **Migrations** | `V6`, `V7` forward-only, không sửa V1–V5 | Flyway convention + `04` canonical | **No new migration** — kiểm tra lại ordering và rerun idempotency |

## 4. Quyết định chi tiết

### 4.1. BIGINT vs UUID

- `approval_request.approval_id` = `BIGINT GENERATED ALWAYS AS IDENTITY` (canonical `04` §4.5.3).
- `admin_idempotency_record.record_id` = `BIGINT GENERATED ALWAYS AS IDENTITY`.
- Tất cả FK Admin (`maker_id`, `checker_id`, `actor_id`, `approval_id`) đều là `BIGINT`, tham chiếu `app_user.user_id` hoặc `approval_request.approval_id` cùng kiểu.
- API expose dạng string `ID` (`^[1-9][0-9]{0,18}$`) theo `07` §2.3; controller đã dùng `AdminHttpSupport.id()` parse về `long`.
- Không chuyển đổi `BIGINT ↔ UUID` vì canonical đã chọn `BIGINT`, source hiện tại đã khớp, và chuyển đổi sẽ phá vỡ FK/lock/idempotency scope đã test.

### 4.2. APPROVED vs APPLIED

- **Canonical status** (DB CHECK + enum): `PENDING`, `APPROVED`, `REJECTED`, `APPLIED`, `CANCELLED`.
- **Semantic:**
  - `APPROVED` = quyết định phê duyệt đã được ghi nhận (schema-reserved cho async apply trong tương lai).
  - `APPLIED` = thay đổi đã được provider áp dụng thành công trong cùng transaction với audit C2.
- **MVP synchronous:** `PENDING → APPLIED` (approve + Identity/Limit apply + audit) hoặc `PENDING → REJECTED`.
- **Provider mapping:** `IdentityRoleCommand.applyApprovedRoleChange` nhận fact `APPROVED`; Admin persist `APPLIED` — đây là mapping chủ đích (DEC-ADM-001/002).
- Không rename/remove `APPROVED` vì `04` CHECK và OpenAPI enum đều yêu cầu.

### 4.3. Expiration schema

- **`approval_request`:** KHÔNG có `expires_at`. Vòng đợi approval kết thúc bằng `APPLIED/REJECTED/CANCELLED` hoặc do challenge hết hạn bị từ chối trước khi transition.
- **`admin_idempotency_record`:** Có `expires_at TIMESTAMPTZ NOT NULL > created_at`. Terminal `complete` cập nhật `expires_at = max(expires_at, completed_at + TTL)` với TTL 24h theo `04` §9.6.
- **`auth_challenge`:** Có `expires_at` và `consumed_at`; kiểm soát hết hạn/reuse challenge cho Checker decision (DEC-ADM-002).

### 4.4. Challenge-related columns

- `approval_request` KHÔNG có `step_up_challenge_id`.
- Binding Checker challenge: `auth_challenge.idempotency_key` = decimal string `approvalId`, `auth_challenge.request_hash` = canonical decision hash, `purpose=SENSITIVE_ACTION`, `user_id=checkerId`.
- Binding Maker proposal challenge: `auth_challenge.idempotency_key` = `Idempotency-Key` header, `auth_challenge.request_hash` = `ADM-PROPOSAL-STEPUP-V1` (DEC-ADM-003).
- Consume là conditional `UPDATE` trong transaction quyết định; rollback caller hoàn lại `consumed_at`.

## 5. Transition và recovery

### 5.1. Transition rules

| Transition | Actor | Precondition | Side effect | Invalid handling |
|---|---|---|---|---|
| `propose` | Maker | Permission, payload hợp lệ, maker ≠ role target | Insert `PENDING` + audit `*_REQUESTED` | `400/403/409/422/428`; không ghi row khi fail |
| `approve` | Checker | Lock; `PENDING`; checker ≠ maker; step-up verified; provider version/policy hợp lệ | Provider mutation JOIN; `APPLIED`; `decided_at=applied_at`; audit C2 | `409 ROLE_CONFLICT`/`APPROVAL_ALREADY_DECIDED`; rollback |
| `reject` | Checker | Lock; `PENDING`; checker ≠ maker; step-up verified | `REJECTED`; `decided_at`; không provider; audit C2 | `409`; rollback |
| `cancel` | Maker/system (chưa mở API) | `PENDING` | `CANCELLED`; `decided_at` | `409 APPROVAL_ALREADY_DECIDED` |

### 5.2. Migration safety

- `V6` và `V7` đã forward-only; **không sửa** V1–V5.
- Không cần `V8` cho task này vì schema đã canonical.
- Nếu tương lai cần partial unique PENDING (`ADM-OI-03`), phải thêm `V8` mới với `CREATE UNIQUE INDEX ... WHERE status='PENDING'` sau khi DB owner duyệt.
- Recovery: vì không có migration mới, không có rollback migration. Nếu cần revert một quyết định Admin sai, dùng workflow `CANCEL`/`REJECT` và tạo approval mới — không xóa/update audit.

## 6. Thay đổi source và documentation

### 6.1. Files modified

| File | Thay đổi |
|---|---|
| `digitalbanking-docs/docs-backend/modules/_overview/10-admin.md` | Cập nhật bảng schema `approval_request` theo canonical `04` §4.5.3; xóa UUID/EXPIRED/expires_at/step_up_challenge_id/maker_reason/checker_reason; thêm cột canonical `decision_note`, `correlation_id`, `created_at`, `decided_at`, `applied_at`; ghi rõ cột KHÔNG có và lý do. |
| `digitalbanking-docs/docs-common/design/04. security.md` | Sửa dòng "lưu vết `step_up_challenge_id` trên cả `transfer` và `approval_request`" thành chỉ `transfer` có `step_up_challenge_id`; `approval_request` dùng `auth_challenge.idempotency_key`/`request_hash`. |
| `DECISIONS.md` | Thêm `DEC-ADM-004` ghi rõ conflict matrix, canonical DDL, identifier/status/expiration/challenge decisions, no migration, evidence, remaining blocker (`ADM-OI-03`). |
| `digitalbanking-docs/docs-backend/modules/admin/tasks/README.md` | Cập nhật trạng thái `BE-ADM-CON-04` từ `PARTIAL` thành `DONE` và thêm evidence. |

### 6.2. Files created

| File | Mục đích |
|---|---|
| `digitalbanking-docs/docs-backend/modules/admin/BE-ADM-CON-04.md` | Báo cáo này: conflict analysis, matrix, canonical DDL, transition/recovery, test evidence, risks. |

### 6.3. Không thay đổi source Java/migration

Không cần thay đổi:
- `ApprovalRequestEntity` — đã khớp canonical.
- `ApprovalRequestJpaRepository` — `Long` identifier, `FOR UPDATE` lock.
- `ApprovalStatus` — 5 giá trị canonical.
- `ApprovalRequest` domain — `PENDING→APPLIED/REJECTED`, `APPROVED` schema-reserved.
- `AdminIdempotencyRecord`/`Scope`/`Operation`/`Status` — `BIGINT`, `expires_at` đúng.
- `V6/V7` migrations — đã canonical.

## 7. Test evidence

### 7.1. Kiểm thử đã có

| Lớp test | Số test | Nội dung liên quan schema conflict |
|---|---|---|
| `FlywayMigrationTest` | 4 | `approvalMigrationDeclaresCanonicalAdminTable` đảm bảo `V6` không chứa `EXPIRED`/`expires_at`/`step_up_challenge_id`; `adminIdempotencyMigrationDeclaresScopedUniqueRecord` đảm bảo `V7` unique scope và terminal/RECEIVED checks. |
| `PostgreSqlFreshMigrationIntegrationTest` | 1 | Flyway V1–V7 từ DB rỗng; kiểm constraint/index `approval_request` và `admin_idempotency_record`; rerun không đổi schema. |
| `ApprovalRequestPostgreSqlIntegrationTest` | 8 | JSONB round-trip, APPLIED/REJECTED persist, duplicate decision, hai checker tranh lock, DB check/FK/length, target/payload mismatch, MANDATORY TX. |
| `AdminIdempotencyPostgreSqlIntegrationTest` | 6 | Scope/unique/claim/replay/conflict/in-progress/purge/constraints của `admin_idempotency_record`. |
| `ApprovalRequestTest` | 17 | Domain state machine, payload validation, maker-checker, terminal transition. |
| `ApprovalStepUpPostgreSqlIntegrationTest` | 17 | Challenge binding qua `approvalId` + hash, expired/consumed/replay/payload-change/concurrency. |
| `ApprovalProposalPostgreSqlIntegrationTest` | 9 | Maker step-up binding qua `Idempotency-Key` + proposal hash, retry, conflict. |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | 10 | Decision idempotency, actor/resource scope, provider rollback + same-key retry. |

### 7.2. Lệnh chạy

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --console=plain
```

### 7.3. Kết quả

- Focused Admin + migration: **BUILD SUCCESSFUL**, 0 failure/error/skip.
- Full build `./gradlew cleanTest build`: **BUILD SUCCESSFUL**, **1001 tests / 82 classes / 0 failure / 0 error / 0 skip** (baseline sau CON-03).

## 8. Risks và blockers còn lại

| Risk/Blocker | Mô tả | Owner/điều kiện mở |
|---|---|---|
| `ADM-OI-03` — Partial unique PENDING | Chưa có `UNIQUE INDEX ... WHERE status='PENDING'` trên `(request_type, target_type, target_id)`; race hiện xử lý bằng lock + idempotency | DB owner duyệt semantics; sau đó thêm `V8` |
| `ADM-OI-05` — Limit provider | `limit.api.applyApprovedLimitChange` chưa có runtime; LIMIT proposal/approve 503 | Limit module owner |
| `ADM-OI-07` — Durable Audit | `AuditModuleApi`/`audit_log` chưa có; Admin dùng security log + metric | Audit module owner |
| `ADM-OI-06` — Staff data scope | Approval list/detail chưa có scope contract | Admin/Security/Customer owners |
| Multi-tenant | Schema v1 single-tenant; nếu multi-tenant cần thêm tenant vào unique index trước khi mở | DB/Architect |

## 9. Acceptance criteria checklist

- [x] All Admin schema conflicts have been identified.
- [x] Canonical DDL follows the approved document hierarchy (`04` §4.5.3/§4.5.4).
- [x] BIGINT/UUID compatibility is resolved — canonical = `BIGINT`.
- [x] APPROVED/APPLIED semantics are consistent — `APPROVED` schema-reserved, `APPLIED` là outcome MVP.
- [x] Expiration fields and rules are consistent — `expires_at` chỉ trên `admin_idempotency_record` và `auth_challenge`, không trên `approval_request`.
- [x] Challenge-related fields are aligned — binding qua `auth_challenge.idempotency_key`/`request_hash`, không có `step_up_challenge_id` FK trên `approval_request`.
- [x] Entities, repositories, and API mappings are consistent — source hiện tại đã khớp.
- [x] Migration safety and order are documented — V6/V7 forward-only, không cần V8 cho task này.
- [x] Recovery and compatibility procedures are defined — transition matrix và rerun Flyway.
- [x] Required code changes are implemented — **không có code change** (schema đã canonical), documentation updated.
- [x] Relevant tests pass — Admin + migration tests PASS; full build PASS.
- [x] Admin documentation is updated — `_overview/10-admin.md`, `design/04. security.md`, `BE-ADM-CON-04.md`, `tasks/README.md`, `DECISIONS.md`.
- [x] Existing functionality and audit records are preserved — không migration mới, không đổi dữ liệu.

## 10. Trạng thái cuối

`BE-ADM-CON-04` — **DONE** (schema conflict resolution + documentation).  
Blocker còn lại duy nhất: `ADM-OI-03` partial unique PENDING chưa được DB owner duyệt; không chặn việc đánh dấu CON-04 DONE vì đây là quyết định schema extension tương lai, không phải conflict hiện tại.
