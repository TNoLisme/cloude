# BE-ADM-CON-03 — Idempotency cho Admin proposal và approve/reject

> **Mã task:** `BE-ADM-CON-03` (P0)  
> **Ngày:** 17/11/2026  
> **Branch:** `datht-backend-rework` (không tạo branch, không push)  
> **Trạng thái tổng:** **PARTIAL — idempotency DONE, ROLE_CHANGE end-to-end DONE; LIMIT_CHANGE proposal/approve vẫn BLOCKED do Limit provider.**  
> **Nguồn ưu tiên:** `07 §5/§5.6/§8.5/§8.8`, `04 §4.4.3/§4.5.3/§9.6`, `design/02 §6`, `design/05 §12.1.3`, `design/01 §5`, `BE-ADM-CON-01/02`, `DEC-ADM-003`.

## 1. Kết luận

Đã triển khai idempotency Admin-owned end-to-end cho:

- `POST /api/v1/admin/roles` (ROLE_CHANGE proposal).
- `POST /api/v1/admin/roles/{approvalId}/approve` (approve/reject ROLE_CHANGE).
- `POST /api/v1/admin/limits/{approvalId}/approve` — reject hoạt động; approve fail-closed 503.
- `POST /api/v1/admin/limits` — endpoint tồn tại nhưng fail-closed 503 sau validation vì Limit provider chưa có.

Cùng key + cùng canonical payload trả đúng response gốc với `Idempotent-Replayed: true`; cùng key + payload khác
trả `409 IDEMPOTENCY_CONFLICT`; record `RECEIVED` trả `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`.
Business row, idempotency terminal state, C2 audit và replay body commit trong **một transaction**.

**Không mở được:** tạo `LIMIT_CHANGE` proposal và approve limit vì `07 §8.5.2` yêu cầu `limit_config`
`PENDING_APPROVAL` trong cùng transaction, còn `limit.api.applyApprovedLimitChange` chưa có runtime
(`ADM-OI-05`). Không tạo approval nửa vời, không fake provider production.

## 2. Quyết định key scope

`banking.admin_idempotency_record` unique theo:

```text
(actor_id, operation, scope_key, idempotency_key)
```

| Thành phần | Proposal | Decision |
|---|---|---|
| `actor_id` | Maker từ `SessionAuthenticator.Session` | Checker từ session |
| `operation` | `CREATE_ROLE_PROPOSAL` / `CREATE_LIMIT_PROPOSAL` | `DECIDE_APPROVAL` |
| `scope_key` | `''` | `approvalId` dạng decimal string |
| `idempotency_key` | Header 1–255 ký tự, pattern `^[A-Za-z0-9._:~-]{1,255}$` | Như proposal |

Hệ quả đã kiểm chứng:

- Actor khác dùng cùng key **không** replay response của actor đầu (test `sameKeyAcrossActorsIsIsolated`,
  `anotherCheckerCannotReplayTheStoredResponse`).
- Cùng key trên hai approval khác nhau là hai scope (test `sameKeyIsScopedByApprovalResource`).
- Proposal và decision cùng key là hai operation độc lập; canonical hash có domain separator.
- Schema v1 không có cột tenant (`04 §4.5.3`). Vì vậy actor+resource là ranh giới hiện tại; khi bổ sung tenant,
  tenant phải vào scope + unique index trước khi mở endpoint đa tenant. Không thêm cột tenant giả trong task này.

## 3. Canonical request hash

- Domain separator: `ADM-IDEMPOTENCY-V1`.
- Serialize object cố định qua **RFC 8785 JCS**, hash `lowercase_hex(SHA-256(UTF-8(JCS)))` bằng
  `HmacService.sha256Hex`; lưu `CHAR(64)`.
- Role proposal gồm `operation`, `actorId`, `requestType`, `targetType`, `targetId`, `changeOperation`,
  `targetUserId`, `roleCode`, `privilegeCode`, `reason`.
- Limit proposal gồm request/target, `customerId`, `accountId`, `perTransactionLimit`, `dailyLimit`, `currency`,
  `effectiveFrom`, `reason`.
- Decision gồm `operation`, `actorId`, `approvalId`, `decision`, `decisionNote`.
- Chuẩn hóa: ID/money string; money scale 2; reason/note strip, null và rỗng cùng `""`; currency uppercase;
  Instant ISO-8601 UTC.
- Loại trừ: `Idempotency-Key`, header, correlation/request id, timestamp, challenge ref, và
  **`expectedVersion` server-capture**. `expectedVersion` chỉ nằm trong step-up hash
  `ADM-PROPOSAL-STEPUP-V1`; nhờ vậy retry cùng request vẫn replay dù role version provider đã đổi.
- Không dùng `toString()`/JSONB map không canonical; test `AdminRequestHasherTest` kiểm determinism, money scale,
  note normalization và độ nhạy từng field.

## 4. Database và transaction

Migration `V7__create_admin_idempotency_record_table.sql`:

| Column | Ghi chú |
|---|---|
| `record_id` | BIGINT identity PK |
| `idempotency_key` | VARCHAR(255), check 1–255 |
| `actor_id` | FK `app_user`, FK RESTRICT |
| `operation` | `CREATE_LIMIT_PROPOSAL`/`CREATE_ROLE_PROPOSAL`/`DECIDE_APPROVAL` |
| `scope_key` | VARCHAR(64), `''` cho proposal, `approvalId` cho decision |
| `request_hash` | CHAR(64), check lowercase hex |
| `status` | `RECEIVED`/`COMPLETED`/`FAILED` |
| `response_status` | SMALLINT 100–599, terminal bắt buộc |
| `response_body` | JSONB masked, terminal bắt buộc |
| `approval_id` | FK `approval_request`, bắt buộc với decision |
| `created_at` | TIMESTAMPTZ |
| `completed_at` | TIMESTAMPTZ, terminal bắt buộc |
| `expires_at` | TIMESTAMPTZ > `created_at` |

Unique index `uq_admin_idempotency_scope` trên `(actor_id, operation, scope_key, idempotency_key)`.
Check constraints chặn terminal thiếu response, RECEIVED mang response, và decision thiếu `approval_id`.
Index purge/status/approval hỗ trợ job và điều tra.

**Insert-first:** `INSERT … ON CONFLICT (actor_id, operation, scope_key, idempotency_key) DO NOTHING RETURNING`
trong transaction business hiện có. Không select-then-insert; không catch `DataIntegrityViolationException` làm
abort transaction. Claim, approval/decision, C2 audit và `complete` cùng commit hoặc cùng rollback.

## 5. Replay / conflict / in-progress

| Case | Hành vi đã triển khai |
|---|---|
| A — first request | `precheck` absent → 428 nếu thiếu step-up → transaction claim RECEIVED → business → complete terminal → 201/200 |
| B — same key/hash terminal | Replay `response_status`/`response_body` đúng byte-ngữ-nghĩa, `Idempotent-Replayed: true`; không chạy lại step-up/provider |
| C — same key khác hash | `409 IDEMPOTENCY_CONFLICT`; C3 `SECURITY_ACCESS_DENIED`; không side effect |
| D — record `RECEIVED` | `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After: 2`; không execute |
| E — original fails | Claim nằm trong transaction business nên rollback; không có record terminal; retry cùng key chạy lại sạch |

Concurrent same key: unique index là trọng tài. Loser block trên insert conflict; sau winner commit, loser đọc
record và replay (cùng payload) hoặc conflict (khác payload). Nếu winner rollback, loser claim được và chạy.
Vì RECEIVED và business cùng commit, crash không để lại RECEIVED bền vững; RECEIVED chỉ có thể đến từ flow
hai pha tương lai và được xử lý 409, không purge.

Response body được round-trip JSON một lần trước khi trả và lưu, nên first response và replay dùng cùng kiểu giá
trị (ví dụ `0` thay vì lúc `0L` lúc `0`). Đây là điều kiện để replay thật sự bằng nhau.

## 6. Tích hợp business

### 6.1. ROLE_CHANGE proposal — DONE

`ApprovalProposalService.proposeRole`:

1. `requireAuthority("ROLE_MANAGE")` từ session.
2. Validate key, role operation, role/privilege code, maker ≠ target.
3. Precheck idempotency bằng canonical **client** payload (không có `expectedVersion`).
4. Capture `IdentityRoleCommand.getRoleAssignmentVersion`.
5. Issue `SENSITIVE_ACTION` challenge bind `bindingKey=Idempotency-Key`,
   `requestHash=ADM-PROPOSAL-STEPUP-V1` (gồm `expectedVersion`), trả 428.
6. Retry: insert-first claim → consume challenge → `approval_request` PENDING → `ROLE_CHANGE_REQUESTED` C2 →
   complete idempotency → 201 `ApprovalSummary` + `Location`.

Test thật: 428 không tạo state; retry tạo đúng một approval; duplicate replay; payload khác conflict; actor khác
scope riêng; challenge fail rollback claim + retry thành công; concurrent same key một approval.

### 6.2. Approve/reject decision — DONE (ROLE), reject LIMIT DONE

`ApprovalDecisionService.decide`:

1. `requireChecker()` từ session; validate key.
2. Precheck canonical decision hash (actor, approvalId, decision, note) **trước** 428.
3. Replay terminal hoặc conflict/in-progress trước khi yêu cầu challenge.
4. Transaction: claim → lock approval `FOR UPDATE` → đúng `requestType` → `PENDING` → maker/checker →
   consume challenge → Identity apply/reject → persist APPLIED/REJECTED → complete idempotency → C2 audit cuối.
5. Body `ApprovalDetail` gồm masked payload (không `expectedVersion`) và `result` provider cho approve role.

Test thật: approve duplicate một lần apply; reject duplicate; conflict; actor isolation; resource scope;
concurrent same key một apply; concurrent khác key một winner; provider failure rollback claim + challenge và
retry cùng key thành công; limit reject idempotent; limit approve 503 không record.

### 6.3. LIMIT_CHANGE proposal/approve — BLOCKED

- `POST /admin/limits` validate body (customerId/money/currency/reason) rồi trả `503 SERVICE_UNAVAILABLE`.
- `APPROVE LIMIT_CHANGE` consume step-up, gọi provider branch và fail-closed 503; transaction rollback.
- `REJECT LIMIT_CHANGE` chạy thật, idempotent.
- Không tạo `approval_request LIMIT_CHANGE` hoặc `limit_config` giả.

## 7. Maker step-up (đóng MOD-OI-10)

`AdminProposalStepUpBinding`:

```text
ADM-PROPOSAL-STEPUP-V1
makerId=<len>:...
requestType=<len>:...
targetType=<len>:...
targetId=<len>:...
payload=<len>:<RFC 8785 JCS typed payload, gồm expectedVersion>
```

Issue/consume qua đúng `identity.api.StepUpChallengePort`; `purpose=SENSITIVE_ACTION`,
`bindingKey=Idempotency-Key`. Không tạo `approvalId` giả, không binding lỏng; đổi payload/version giữa 428 và
retry bị `409 STEP_UP_CHALLENGE_MISMATCH`. Checker decision giữ binding `approvalId` của CON-02.

## 8. TTL, purge và timeout

- TTL 24h từ `completed_at`, kế thừa `IDEMPOTENCY_RECORD` 24h (04 §9.6/BRL-013/OI-001), cấu hình
  `app.admin.idempotency-ttl-hours`; không tự đặt retention mới.
- `complete` đặt `expires_at = GREATEST(expires_at, completed_at + TTL)`.
- Job `AdminIdempotencyPurgeJob` dùng cron retention hiện hữu `app.jobs.retention-cleanup-cron` (mặc định 03:00),
  batch `app.admin.idempotency-purge-batch-size` (500), tối đa 20 batch/lần.
- Purge chỉ xóa `COMPLETED`/`FAILED` có `expires_at < now()`, `FOR UPDATE SKIP LOCKED`; không xóa `RECEIVED`.
- Record hết hạn nhưng chưa purge vẫn replay/conflict; chỉ sau purge key mới được coi là request mới. Đây là
  giới hạn cửa sổ chống trùng, không phải bảo đảm vĩnh viễn.
- Client timeout không đồng nghĩa fail: retry đúng key replay nếu đã commit, chạy lại nếu rollback. Retry key mới
  cho cùng thao tác logic có thể tạo approval/apply lần hai — documented risk.

## 9. Test và evidence

PostgreSQL 16 Testcontainers + Flyway V1–V7 + JPA/Identity thật; không H2, không mock lock/unique/transaction.
Focused suite Admin + migration: **BUILD SUCCESSFUL**.

| Lớp | Số test | Nội dung |
|---|---:|---|
| `ApprovalRequestTest` | 17 | Domain/payload/state machine regression |
| `ApprovalDecisionServiceTest` | 8 | Gate ordering + wrong endpoint type |
| `ApprovalStepUpBindingTest` | 7 | Checker binding regression |
| `ApprovalStepUpPostgreSqlIntegrationTest` | 17 | Checker step-up/decision regression |
| `ApprovalRequestPostgreSqlIntegrationTest` | 8 | Approval persistence regression |
| `AdminRequestHasherTest` | 5 | Canonical hash/normalization/field sensitivity |
| `AdminIdempotencyKeyTest` | 2 | Header key validation |
| `AdminIdempotencyPostgreSqlIntegrationTest` | 6 | Scope/unique/claim/replay/conflict/in-progress/purge/constraints |
| `ApprovalProposalPostgreSqlIntegrationTest` | 9 | 428, create, replay, conflict, actor isolation, retry, concurrency, purge, limit fail-closed |
| `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` | 10 | Approve/reject replay/conflict, actor/resource scope, concurrency, provider rollback, limit, in-progress, purge |
| `RoleAdminControllerTest` | 6 | 201/Location/no-store/replay/428/decision/field validation mapping |
| `LimitAdminControllerTest` | 2 | limit fail-closed + reject mapping |
| **Tổng Admin** | **97** | 40 test mới CON-03 + regression |

Lệnh đã chạy:

```bash
./gradlew test --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.migration.*' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.BackendApplicationContextTest' --console=plain
```

Kết quả: **BUILD SUCCESSFUL**, 0 failure/error/skip trong các lớp trên.

**Full build cuối:** `./gradlew cleanTest build --console=plain` → **BUILD SUCCESSFUL** (7m20s),
**1001 tests / 82 classes / 0 failure / 0 error / 0 skip**. Không có test Admin/lỗi biên dịch; lần chạy đầy đủ
trước đó phát hiện `RoleChangePostgreSqlIntegrationTest` dùng test-only `AdminFixture` trùng path với controller
thật — đã cô lập bằng `app.admin.enabled=false` như ghi ở §11, giữ nguyên toàn bộ assertion.

## 10. Ma trận yêu cầu

| Yêu cầu | Trạng thái | Bằng chứng |
|---|---|---|
| Persistence idempotency + unique scope | **VERIFIED** | V7, `AdminIdempotencyPostgreSqlIntegrationTest` 6, migration tests |
| Canonical request hash/normalization | **VERIFIED** | `AdminRequestHasherTest` 5 |
| Key validation | **VERIFIED** | `AdminIdempotencyKeyTest` 2, service 400 with `errors[0].field=Idempotency-Key`, controller test |
| Replay same key/hash | **VERIFIED** | Proposal 9, Decision 10, controller replay tests |
| Conflict same key/different payload | **VERIFIED** | Proposal/Decision integration; C3 audit |
| In-progress `RECEIVED` | **VERIFIED** | `AdminIdempotencyPostgreSqlIntegrationTest`, decision test 409 |
| Actor/resource scope isolation | **VERIFIED** | Proposal actor test; decision actor/resource tests |
| POST ROLE_CHANGE proposal | **VERIFIED** | `ApprovalProposalPostgreSqlIntegrationTest` |
| POST LIMIT_CHANGE proposal | **BLOCKED** | Limit provider/pending config absent; endpoint 503 |
| Approve ROLE_CHANGE | **VERIFIED** | `ApprovalStepUpPostgreSqlIntegrationTest`, decision idempotency test |
| Reject ROLE_CHANGE | **VERIFIED** | Decision idempotency test |
| Approve LIMIT_CHANGE | **BLOCKED** | `SERVICE_UNAVAILABLE`, no state |
| Reject LIMIT_CHANGE | **VERIFIED** | Decision idempotency test |
| Maker step-up binding | **VERIFIED** | Proposal integration 428/retry/mismatch |
| TTL + completion expiry | **VERIFIED** | Store test `completionExtendsExpiryFromCompletionTime` |
| Purge terminal-only, no RECEIVED | **VERIFIED** | Store test; proposal/decision expired tests |
| Timeout/retry semantics | **VERIFIED (service/DB)** | rollback + same-key retry tests; HTTP client timeout simulation not run |
| OpenAPI/API contract | **IMPLEMENTED** | `07 §5.6`, OpenAPI header/status; YAML parses |
| HTTP contract 401/403/CSRF | **PARTIAL** | controller standalone tests; full security-chain contract test not added |
| Durable Audit C2 | **BLOCKED** | security-log adapter only (`ADM-OI-07`) |
| Cross-tenant isolation | **BLOCKED (schema)** | no tenant column in 04; actor/resource scope tested, tenant not modeled |

## 11. Files

**Created**

- `V7__create_admin_idempotency_record_table.sql`
- `admin/domain/AdminIdempotencyStatus.java`, `AdminIdempotencyOperation.java`, `AdminIdempotencyScope.java`,
  `AdminIdempotencyRecord.java`
- `admin/application/port/AdminIdempotencyStore.java`, `ApprovalPayloadView.java`
- `admin/application/AdminRequestHasher.java`, `AdminIdempotencyService.java`, `AdminApprovalView.java`,
  `ApprovalProposalService.java`
- `admin/application/stepup/AdminProposalStepUpBinding.java`
- `admin/adapter/out/JdbcAdminIdempotencyStore.java`
- `admin/adapter/in/AdminIdempotencyPurgeJob.java`, `AdminHttpSupport.java`, `RoleAdminController.java`,
  `LimitAdminController.java`, `RoleChangeHttpRequest.java`, `ApprovalDecisionHttpRequest.java`,
  `LimitChangeHttpRequest.java`
- `identity/api/IdentityUserQuery.java`, `identity/application/IdentityUserQueryService.java`
- Tests: `AdminRequestHasherTest`, `AdminIdempotencyKeyTest`, `AdminIdempotencyPostgreSqlIntegrationTest`,
  `ApprovalProposalPostgreSqlIntegrationTest`, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest`,
  `RoleAdminControllerTest`, `LimitAdminControllerTest`

**Modified**

- `AdminAuditPort` (requested actions), `AdminAuthorization` (generic authority), `AdminPayloadJsonCodec`
  (public payload), `ApprovalDecisionService` (idempotency + response body + expected type)
- `AppProperties`/`application.yml` (Admin TTL/purge config)
- Migration tests (`PostgreSqlFreshMigrationIntegrationTest`, `FlywayMigrationTest`), Admin regression tests
- `RoleChangePostgreSqlIntegrationTest` được cô lập khỏi adapter Admin thật bằng `app.admin.enabled=false` để
  test-only `AdminFixture` giữ đúng vai trò contract consumer; không xóa/giảm assertion. Đây cùng pattern CON-01
  đã dùng cho sáu Spring context test.
- `07`, OpenAPI, `design/05 §12.1.3`, admin README/design/checklist, `DECISIONS.md` (`DEC-ADM-003`)

## 12. Còn lại / dependency cần owner xác nhận

1. **Limit provider** — `limit.api.applyApprovedLimitChange`, `expectedVersion`, `limit_config
   PENDING_APPROVAL`; mở `POST /admin/limits` + approve limit và chạy idempotency/version/rollback tests.
2. **Durable Audit** — thay `SecurityLogAdminAuditAdapter` bằng `AuditModuleApi`; C2 rollback và C3 fallback
   trên audit thật.
3. **HTTP security contract** — test qua security chain thật (401/403/CSRF/rate limit/IDOR) thuộc
   `BE-ADM-TST-04`; controller standalone đã kiểm header/status mapping.
4. **Tenant** — nếu schema chuyển multi-tenant, bổ sung tenant vào `admin_idempotency_record` scope/unique index
   trước khi mở endpoint đa tenant.
5. **Client timeout simulation** — service/DB retry đã chứng minh; HTTP client timeout/response-loss end-to-end
   cần harness riêng.
