# BE-ADM Task breakdown & implementation checklist

> **Trạng thái (17/11/2026):** `BE-ADM-DOC-01` hoàn thành tài liệu; `BE-ADM-DB-01` **DONE** — V6 `approval_request` canonical, fresh PostgreSQL metadata + positive/negative tests PASS; `BE-ADM-DB-02` **DONE (persistence)** — entity canonical, list/detail projection, filter/page/sort whitelist và read-only/lock tests PASS; REST list/detail + data scope vẫn BLOCKED ở `BE-ADM-API-03`/`ADM-OI-06`; `BE-ADM-CON-01` **PARTIAL** — domain/persistence/state machine/payload + Flyway V6 đã triển khai và test; REST proposal/decision, Maker step-up, idempotency, Limit provider và durable Audit còn BLOCKED. `BE-ADM-CON-02` **PARTIAL** — Checker step-up binding + decision gate đã triển khai và test. `BE-ADM-CON-03` **DONE (idempotency)** — V7, service/store/hasher, ROLE proposal/decision + reject limit end-to-end, Maker step-up binding, TTL/purge, 39 test mới; LIMIT proposal/approve vẫn BLOCKED. `BE-ADM-DB-03` **DONE** — V7 metadata/constraint/index test 8 mới + 3 test concurrency/rollback (winner rollback, response-persistence rollback, `RECEIVED` retention); Admin+migration+ArchUnit 23 classes / 188 tests PASS; cross-tenant vẫn BLOCKED do schema single-tenant. Các task còn lại giữ `NOT_STARTED`/`BLOCKED` theo dependency.  
> **Quy tắc:** chỉ đánh dấu `DONE` khi có source, test và evidence truy lại được. Dependency thiếu phải đánh dấu `BLOCKED`, không thay bằng fake production.

## 0. Evidence BE-ADM-CON-01

- Báo cáo: [BE-ADM-CON-01.md](../BE-ADM-CON-01.md).
- Source: `admin/domain`, `admin/application/port/ApprovalRequestStore`, `admin/adapter/out` (entity/repository/store/codec), `V6__create_approval_request_table.sql`.
- Test: 29 tests PASS (`ApprovalRequestTest` 17, `ApprovalRequestPostgreSqlIntegrationTest` 8, `PostgreSqlFreshMigrationIntegrationTest` 1, `FlywayMigrationTest` 3); lệnh trong báo cáo §12. Full build PASS: 928 tests / 72 classes / 0 fail/error/skip.
- Chưa có: application service, REST controller, step-up Maker binding, idempotency, Limit provider apply, durable Audit C2/C3, approval read/scope.

## 0.1. Evidence BE-ADM-CON-03

- Báo cáo: [BE-ADM-CON-03.md](../BE-ADM-CON-03.md).
- Source: `V7__create_admin_idempotency_record_table.sql`; `AdminIdempotencyService/Record/Scope/Store`; `JdbcAdminIdempotencyStore`; `AdminRequestHasher`; `ApprovalProposalService`; `ApprovalDecisionService` idempotency; `AdminProposalStepUpBinding`; `RoleAdminController`/`LimitAdminController`; `IdentityUserQuery`.
- Test: 40 test mới + regression Admin 97 test PASS (`AdminRequestHasherTest` 5, `AdminIdempotencyKeyTest` 2, `AdminIdempotencyPostgreSqlIntegrationTest` 6, `ApprovalProposalPostgreSqlIntegrationTest` 9, `ApprovalDecisionIdempotencyPostgreSqlIntegrationTest` 10, `RoleAdminControllerTest` 6, `LimitAdminControllerTest` 2; regression 17+8+7+17+8).
- Evidence PostgreSQL 16/Flyway V1–V7/Identity thật: replay/conflict/in-progress, actor+resource isolation, concurrent same/different key, provider rollback + same-key retry, expired replay/purge, terminal-only purge, limit reject idempotent, limit approve/proposal 503 no state.
- Full build `./gradlew cleanTest build`: **BUILD SUCCESSFUL** (7m20s), **1001 tests / 82 classes / 0 failure / 0 error / 0 skip** (baseline CON-02 959/75).
- Còn BLOCKED: LIMIT proposal/approve (Limit provider `ADM-OI-05`), durable Audit (`ADM-OI-07`), HTTP security-chain contract (TST-04), multi-tenant scope.

## 0.2. Evidence BE-ADM-DB-01

- Báo cáo: [BE-ADM-DB-01.md](../BE-ADM-DB-01.md).
- Migration: `V6__create_approval_request_table.sql` — giữ nguyên, không sửa `V1`–`V5`, không tạo version mới vì không có delta canonical.
- Test mới: `ApprovalRequestV6MigrationPostgreSqlIntegrationTest` 10 test — PostgreSQL 16 fresh database, Flyway `V1`–`V7`; column/type/length/nullability/identity/default, PK, 2 FK RESTRICT, 8 CHECK, 5 index + PK không unique/partial, positive/negative DML và timestamp timezone.
- Regression: `migration.*` + `admin.*` 18 classes / 129 tests PASS; full `./gradlew cleanTest build` PASS (0 failure/error/skip).
- Không có partial index trong canonical. `ADM-OI-03` (partial unique PENDING) vẫn OPEN nhưng là extension ngoài `04` §4.5.3/`design/05` §12.1.1; nếu duyệt phải thêm migration mới.

## 0.3. Evidence BE-ADM-DB-02

- Báo cáo: [BE-ADM-DB-02.md](../BE-ADM-DB-02.md).
- Source: `admin/application/query` (`ApprovalListQuery`, `ApprovalCriteria`, `ApprovalSort`, `ApprovalSortField`, `ApprovalSummaryProjection`, `ApprovalDetailProjection`, `ApprovalPage`, `ApprovalQueryService`); `admin/application/port/ApprovalRequestReader`; `admin/adapter/out/ApprovalRequestJpaRepository`, `ApprovalDetailRow`, `JpaApprovalRequestReader`.
- Test: `ApprovalQueryServiceTest` 9; `ApprovalRequestQueryPostgreSqlIntegrationTest` 8; `ApprovalRequestPostgreSqlIntegrationTest` +2 (10 tổng); `ModuleBoundaryArchTest` +4 rule (18 tổng).
- Evidence PostgreSQL 16: filter đủ trường + `from` inclusive/`to` exclusive, pagination/tie-breaker/page vượt cuối, empty page, list không decode JSONB, detail mask bỏ `expectedVersion`, read-only transaction, lock timeout `55P03` và row vẫn `PENDING`.
- Regression: Admin 14 classes / 116 tests PASS; migration 7 classes / 43 tests PASS; full `./gradlew cleanTest build` PASS.
- BLOCKED: REST list/detail + data scope (`ADM-OI-06`/`BE-ADM-API-03`), `ApprovalDetail.result` persistence. `NOWAIT` không áp dụng cho approval row theo `design/02` §7.9/§9.6.

## 0.4. Evidence BE-ADM-DB-03

- Báo cáo: [BE-ADM-DB-03.md](../BE-ADM-DB-03.md).
- Persistence: `V7__create_admin_idempotency_record_table.sql` (13 cột, PK identity, 2 FK `RESTRICT`, 10 CHECK, unique `uq_admin_idempotency_scope (actor_id, operation, scope_key, idempotency_key)`, 3 index phụ) — không tạo bảng mới, không sửa `V1`–`V6` (theo `BE-ADM-CON-03`/`DEC-ADM-003`).
- Source concurrency: `AdminIdempotencyService` insert-first `ON CONFLICT DO NOTHING` + `claim/complete` `MANDATORY`; `JdbcAdminIdempotencyStore`; `AdminIdempotencyPurgeJob` terminal-only `FOR UPDATE SKIP LOCKED`.
- Test mới: `AdminIdempotencyV7MigrationPostgreSqlIntegrationTest` 8 (metadata/PK/FK/CHECK/index/DML/unique scope/FK restrict, fresh Flyway `V1`–`V8`, rerun không execute lại); `AdminIdempotencyPostgreSqlIntegrationTest` +3 (`winnerRollbackReleasesTheKeyToTheBlockedConcurrentRequest`, `responsePersistenceFailureRollsBackApprovalAndClaimThenSameKeyRetries`, `expiredInProgressRecordIsRetainedAndStillBlocksExecution`).
- Ma trận A–G: concurrent same key một owner (proposal/decision/store), winner rollback → loser claim, conflict giữ record gốc, terminal replay không re-execute, `RECEIVED` retention/purge, rollback ở acquisition/challenge/provider/response boundary, actor+resource isolation, V7 constraints. Tất cả PASS trên PostgreSQL 16 Testcontainers + Flyway thật.
- Lệnh: `./gradlew test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.migration.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest'` → **BUILD SUCCESSFUL**, 23 classes / 188 tests / 0 failure / 0 error / 0 skip; `./gradlew cleanTest build` → **BUILD SUCCESSFUL** (6m57s), 88 classes / 1066 tests / 0 failure / 0 error / 0 skip.
- BLOCKED: cross-tenant isolation (schema v1 không có tenant column), recovery transition cho `RECEIVED` (contract chưa cho phép), LIMIT provider (`ADM-OI-05`), durable Audit (`ADM-OI-07`), HTTP client timeout harness.

## 1. Checklist theo dependency

| Checklist | Task | Priority | Mô tả / deliverable | Dependencies | Acceptance criteria |
|---|---|---:|---|---|---|
| [x] | `BE-ADM-DOC-01` | P0 | Khảo sát source/docs; viết `README.md`, `design.md`, checklist; ghi rõ phần đã có, phần thiếu, conflict và open issue | `docs-common/03,04,06,07,09`, design/01/02/04/05, backend source | Tài liệu tiếng Việt; trỏ đúng source of truth; không sửa application source; không tuyên bố admin runtime đã có |
| [~] | `BE-ADM-CON-01` | P0 | **PARTIAL** — chốt scope/actor matrix/payload/state machine; domain + V6 đã implement. Maker step-up/idempotency đã được CON-03 xử lý; Limit provider/durable Audit còn BLOCKED | `BE-ADM-DOC-01`; API/BA/Security/Identity/Limit/Audit review | Scope/matrix/schema/state machine rõ; reviewer sign-off chưa có; `07`/OpenAPI không đổi hành vi |
| [~] | `BE-ADM-CON-02` | P0 | **PARTIAL (17/11/2026, `datht-backend-rework`):** chốt + implement binding Checker `SENSITIVE_ACTION`: `bindingKey=approvalId`, `requestHash` SHA-256 canonical v1 (payload JCS), challenge thuộc checker, Maker–Checker gate, 428 cấp challenge đã bind, consume JOIN trong TX quyết định, rollback hoàn consume, audit C2/C3. **31 test mới PASS**: `ApprovalStepUpBindingTest` 7, `ApprovalDecisionServiceTest` 7, `ApprovalStepUpPostgreSqlIntegrationTest` 17 (PostgreSQL 16 + Identity thật; phủ 16/16 kịch bản §11.4, gồm expired/consumed/replay/payload-change/concurrency/provider rollback). Không có bộ 428 vector độc lập trong repo → không claim đã chạy. Còn BLOCKED: Limit approve provider (`ADM-OI-05`), durable Audit (`ADM-OI-07`); Maker step-up/idempotency/REST decision đã được `CON-03` xử lý. Báo cáo: [BE-ADM-CON-02.md](../BE-ADM-CON-02.md) | `CON-01`; `StepUpChallengePort`; Identity/Security/API | Purpose `SENSITIVE_ACTION`, request hash/binding key, expiry, single-use, replay/mismatch đã có test; 428 flow service-level có test, HTTP contract và 428 vectors còn mở; không tạo binding lỏng |
| [x] | `BE-ADM-CON-03` | P0 | **DONE (17/11/2026):** V7 `admin_idempotency_record` unique `(actor_id, operation, scope_key, idempotency_key)`; `ADM-IDEMPOTENCY-V1` JCS SHA-256; insert-first `ON CONFLICT DO NOTHING`; replay/conflict/in-progress; TTL 24h + purge terminal-only; Maker step-up bind idempotency key + proposal hash; ROLE proposal/decision end-to-end; LIMIT proposal/approve fail-closed 503. 40 test mới PASS; `07 §5.6` + OpenAPI cập nhật | `CON-01/02`; DB/API/BA | Key scope, canonical hash, unique constraint, replay/conflict/in-progress, TTL/purge, cross-actor isolation, timeout semantics được ghi vào API/OpenAPI |
| [x] | `BE-ADM-CON-04` | P0 | **DONE** — BIGINT/canonical status/expiration/challenge conflicts đã rà soát và chốt theo `04` §4.5.3/§4.5.4; `_overview/10-admin.md` và `design/04. security.md` đã cập nhật; `BE-ADM-CON-04.md` và `DEC-ADM-004` được tạo; source/migration hiện tại đã khớp canonical nên không cần migration mới. Partial unique PENDING (`ADM-OI-03`) vẫn BLOCKED chờ DB owner | `CON-01`; DB/Architect/Security | V6/V7 canonical; transition matrix rõ; docs đồng bộ; tests pass; partial unique PENDING chưa chốt |
| [x] | `BE-ADM-DB-01` | P0 | **DONE (17/11/2026):** `V6__create_approval_request_table.sql` canonical; fresh PostgreSQL 16 chạy `V1`–`V7`; 13 cột đúng type/length/nullability/identity/default, PK, 2 FK RESTRICT, 8 CHECK, 5 index canonical không unique/partial; 10 test migration + regression PASS. Không tạo `V8` vì canonical không có delta; `ADM-OI-03` partial unique PENDING vẫn OPEN nhưng là extension ngoài canonical | `CON-04`; DB owner; migration versions hiện có | PK/FK/check/index/forward-only PASS; fresh rerun không đổi schema; không sửa migration đã áp dụng; DB owner sign-off là gate quy trình |
| [x] | `BE-ADM-DB-02` | P0 | **DONE (persistence, 17/11/2026):** entity 13 cột canonical không `version`; `ApprovalRequestJpaRepository` có list constructor projection (không đọc `payload`) + detail projection; `ApprovalQueryService` read-only với filter `requestType/status/makerId/checkerId/from/to`, page 0/20/100, sort whitelist `createdAt` default `desc`; list/detail projection tách entity/REST DTO; 9 unit + 8 PostgreSQL query + 2 persistence test mới, ArchUnit Admin PASS. REST/scope vẫn BLOCKED ở `BE-ADM-API-03`/`ADM-OI-06`; `result` chưa persist | `DB-01`; backend conventions | Entity không lộ ngoài Admin; filter/page/sort/empty PASS; detail payload mask bỏ `expectedVersion`; `FOR UPDATE` chờ + read-only TX PASS; scope API còn BLOCKED |
| [x] | `BE-ADM-DB-03` | P0/P1 | **DONE (17/11/2026):** V7 `admin_idempotency_record` (unique `(actor_id, operation, scope_key, idempotency_key)`, FK actor/approval `RESTRICT`, terminal/RECEIVED/decision checks, purge `FOR UPDATE SKIP LOCKED`) + `JdbcAdminIdempotencyStore`; **8 test V7 metadata/constraint/index/DML** + **3 test concurrency/rollback mới** (winner rollback → loser claim, response-persistence rollback + same-key retry, expired `RECEIVED` retention). Admin+migration+ArchUnit 23 classes / 188 tests PASS; full build PASS. Cross-tenant BLOCKED (schema single-tenant), `RECEIVED` recovery chưa có contract | `CON-03`; `DB-01` | Unique race chỉ tạo một approval; conflict không lộ record actor khác; terminal response replay; retention không xóa PENDING; PostgreSQL concurrency test pass |
| [~] | `BE-ADM-DOM-01` | P0 | **PARTIAL** — enums, typed payload, money/role conditional validation, state machine + unit/PostgreSQL tests DONE; limit policy/current snapshot chưa có contract | `CON-01`, `CON-04`; `DB-02` | Terminal transition/maker-checker/role tests PASS; limit policy shape còn BLOCKED (`ADM-OI-05`) |
| [~] | `BE-ADM-APP-01` | P0 | **PARTIAL (CON-03):** `ApprovalProposalService.proposeRole` DONE (authorize, step-up 428, idempotency, PENDING approval, C2 audit); `proposeLimit` validate rồi fail-closed 503 vì Limit provider | `CON-02/03`; `DB-02/03`; Common/Identity | Không business write trước step-up; canonical hash ổn định; retry/replay/conflict đúng contract; request audit C2 cùng TX; provider không bị gọi khi validation fail |
| [~] | `BE-ADM-APP-02` | P0 | **PARTIAL (CON-02):** `ApprovalDecisionService` đã lock `FOR UPDATE`, re-check `PENDING`, maker/checker + role target, branch approve/reject, terminal semantics. Còn thiếu REST + Limit provider + durable Audit | `APP-01`; `CON-04`; Identity/Limit contracts | Hai checker song song chỉ một winner (PostgreSQL test PASS); loser không apply/consume; reject không mutate provider; `APPLIED` role commit cùng TX; limit approve fail closed |
| [~] | `BE-ADM-APP-03` | P0 | **PARTIAL (CON-02):** consume `SENSITIVE_ACTION` trong TX `MANDATORY` + dựng `IdentityRoleCommand.ApprovedRoleChange` (`APPROVED` fact, `expectedVersion` từ payload) đã chạy thật; rollback hoàn consume có test. Limit command chưa có contract | `CON-02`; `APP-02`; Identity/Limit provider review | Call xảy ra trong TX `MANDATORY`; binding/user/purpose/version đúng; rollback caller hoàn lại challenge consume; không truy cập `auth_challenge`/Identity/Limit internals; limit BLOCKED |
| [ ] | `BE-ADM-APP-04` | P0 | Tích hợp C2 `AuditModuleApi` cho request/approve/reject; C3 denied access | `APP-01/02`; Audit contract | C2 audit ở cuối TX; audit fail rollback approval + provider state; C3 failure giữ 403 và có structured security log/metric/alert; action catalog không duplicate |
| [~] | `BE-ADM-API-01` | P0 | **PARTIAL (CON-03):** `RoleAdminController` DONE (201/Location/no-store/replay/428/409); `LimitAdminController.create` validate rồi 503 | `APP-01`; OpenAPI/API review; Common security | Request/response đúng `07`; 201 + Location; 400/401/403/404/422/428/429/5xx map đúng; correlation/CSRF/no-store; không trả entity/secret/PII |
| [~] | `BE-ADM-API-02` | P0 | **PARTIAL (CON-03):** controllers approve/reject role + limit; ROLE approve/reject và LIMIT reject DONE với idempotency; LIMIT approve 503 | `APP-02/03/04`; OpenAPI/API review | 200 APPLIED/REJECTED; `X-Step-Up-Challenge` binding; duplicate/stale/maker-self denied; `APPROVAL_DETAIL` masked; no partial success |
| [ ] | `BE-ADM-API-03` | P1 | REST list/detail approvals với filter/scope/pagination | `DB-02`; scope contract; `APPROVAL_READ` | Filter `requestType/status/makerId/checkerId/from/to`; page/size/sort whitelist; maker/checker scope; 404 anti-enumeration; read-only/no audit thường |
| [ ] | `BE-ADM-API-04` | P1 | Tích hợp customer/account operational lookup, không duplicate provider implementation | `ADM-OI-06`; Customer/Account provider API; scope contract | Provider thật kiểm masked view/ownership/status; customer cross-scope 403 + C3 audit; không query entity chéo; integration chỉ pass khi provider runtime thật |
| [ ] | `BE-ADM-SEC-01` | P0 | Hoàn thiện service authorization và actor/data-scope matrix | `CON-01`; Identity; Security/BA | Endpoint gate + service re-check; client forged role/user IDs không bypass; CUSTOMER/disabled/expired session fail closed; maker/checker/self-escalation cases đúng |
| [ ] | `BE-ADM-SEC-02` | P0 | PII/secret/log/audit redaction và security header regression | `API-01/02`; Common/Audit | Không xuất OTP/token/password/hash/full account/full PII; payload current/proposed mask; no-store; correlation/request IDs; bounded metric labels |
| [ ] | `BE-ADM-TST-01` | P0 | Unit/component tests cho domain, DTO, service outcomes và Problem mapping | `DOM-01`, `APP-01/02`, `API-01/02` | Happy path + validation + forbidden + not found + stale + terminal + step-up mismatch + provider failure; assertions kiểm state/outcome, không test source text |
| [ ] | `BE-ADM-TST-02` | P0 | PostgreSQL migration/constraint/transaction integration tests bằng Testcontainers | `DB-01/02`; provider fixtures chỉ test | Fresh migration, FK/check/index, duplicate PENDING, maker≠checker, C2 rollback, no partial apply, no H2; kiểm DB state và audit facts |
| [x] | `BE-ADM-TST-03` | P0 | **DONE (CON-03):** PostgreSQL barrier tests cho same-key proposal/decision, different-key decision, unique claim, replay/conflict; role applied một lần | `DB-03`; `APP-01/02`; `CON-03` | Same proposal key at-most-one; different hash conflict; two checker one apply; two PENDING target race; consumed challenge one winner; no lost update/double revoke |
| [ ] | `BE-ADM-TST-04` | P0 | Security/API contract tests với session/security chain thật | `API-01/02/03`; Identity; Common | OpenAPI/status/header/error exact; 401/403/428/409/422; CSRF; forged metadata; IDOR/scope; response/log/audit leakage scan; no mock production bean |
| [~] | `BE-ADM-TST-05` | P0 | **PARTIAL (CON-03):** provider failure rollback claim/challenge + same-key retry, expired replay, purge; HTTP client timeout/durable audit failure chưa chạy | `APP-04`; `TST-02/03`; Audit/Limit/Identity | Technical failure rollback đúng; C2 fail closed; retry cùng key replay/no duplicate; no retry statement trong aborted TX; evidence ghi Blocked khi dependency thiếu |
| [ ] | `BE-ADM-INT-01` | P0 | Integration thật Identity + Limit + Audit cho role/limit approval | `APP-03/04`; provider owners; `TST-02..05` | Provider implementation thật; role expectedVersion/revoke session; limit version/policy; durable audit/hash chain; approval/provider/audit cùng commit hoặc cùng rollback |
| [ ] | `BE-ADM-OPS-01` | P1 | Observability/runbook: metrics, structured log, alert, retry/recovery và stuck approval handling | `INT-01`; deployment/common conventions | Metric labels bounded; correlation/request trace; C2/C3 failure alert; không log PII/secret; runbook nêu manual review, không auto-apply an toàn giả |
| [ ] | `BE-ADM-REL-01` | P0 | Release gate, OpenAPI validation, architecture/security review và evidence package | Tất cả P0; owner sign-off | `AC-028` và contract suite verified; full relevant build/test evidence; fresh migration; no unresolved P0; documented P1/P2 blockers; source/docs/OpenAPI đồng bộ |

## 2. Dependency gates

| Gate | Phải hoàn tất | Không được làm trước khi gate đóng |
|---|---|---|
| **A — Contract** | `BE-ADM-CON-01..04`, `ADM-OI-01..05`, provider DTO/error/TX/lock/PII review | Không tạo migration, không hard-code idempotency/step-up/state semantics |
| **B — Data** | `BE-ADM-DB-01..03`, migration review, ArchUnit boundary | Không viết service dựa entity/schema tạm |
| **C — Core** | `BE-ADM-DOM-01`, `APP-01..04`, unit/PostgreSQL transaction tests | Không mở REST success path khi C2 audit/provider chưa atomic |
| **D — REST/Security** | `API-01..04`, `SEC-01..02`, contract/security tests | Không coi common route gate là business completion |
| **E — Integration** | Identity/Limit/Audit thật, concurrency/failure evidence | Không đánh dấu Admin production-ready khi còn fixture provider |
| **F — Release** | `REL-01`, owner sign-off, OpenAPI/docs/source consistency | Không merge/release khi còn P0 conflict hoặc audit rollback chưa chứng minh |

> **Ngoại lệ có kiểm soát (CON-01, 17/11/2026):** DDL canonical/status conflict đã được chốt trong CON-01/CON-04 nên `DB-01` được phép tạo `V6` và test fresh migration; Gate A vẫn mở cho step-up/idempotency/Limit. Không mở REST success path, không thêm idempotency/partial unique/scope/Audit schema.

## 3. Dependency report cần theo dõi

1. **Identity:** `StepUpChallengePort` và `IdentityRoleCommand` đã tồn tại; Admin phải dùng đúng `consumeVerified` `MANDATORY`, `expectedVersion` và `ApprovedRoleChange`. Không tạo role/session/challenge repository trong Admin.
2. **Limit:** cần provider-owned `applyApprovedLimitChange`, version/policy semantics và rollback test. Runtime chưa đủ trên baseline.
3. **Audit:** cần durable `AuditModuleApi`/`audit_log`, C2 fail-closed và C3 fallback/hash-chain evidence. Security log fixture không thay được provider.
4. **Customer:** exact-ID masked view đã có nhưng `CUS-OI-05` data scope còn BLOCKED; không mở unrestricted staff access.
5. **Account:** cần `AccountQuery`/masked account projection và ownership/scope contract; chưa có runtime.
6. **API/BA/Security:** cần phân xử `OI-API-19`, `OI-API-07`, `OI-API-08`, `OI-API-09`, `OI-API-10`, `OI-API-18` trước các capability tương ứng.
7. **DB/Architect:** cần canonical approval schema và Flyway version coordination; không sửa migration đã chạy.

## 4. Status và evidence protocol

- `NOT_STARTED`: chưa có source/test.
- `IN_PROGRESS`: đang triển khai, chưa đủ acceptance.
- `PARTIAL`: một phần pass nhưng còn dependency/blocker.
- `BLOCKED`: không thể chứng minh do contract/provider thiếu; ghi rõ owner và điều kiện mở.
- `DONE`: chỉ khi source, focused tests, integration evidence và review acceptance đầy đủ.

Mỗi task khi hoàn tất phải ghi: commit/revision, command, môi trường PostgreSQL, test ID, pass/fail count, schema/API evidence, security/PII scan và blockers còn lại. Không ghi test `Pass` nếu chỉ có mock cho lock, unique, transaction propagation, audit durability hoặc provider side effect.
