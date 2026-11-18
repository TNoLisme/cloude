# BE-CUS-IMP-09 — Customer provisioning, creation, update và status workflows

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** **BLOCKED** — không có write contract đủ điều kiện để mở production workflow; các primitive Customer đã được duyệt được giữ nguyên và bổ sung regression test.
> **Quyết định:** [DEC-CUS-007](../../../../DECISIONS.md) · Modules contract §6.5.

## A. Repository và contract findings

Working tree ban đầu có 26 file tracked thay đổi và 35 path untracked từ các task Customer/Identity/Common đang thực hiện; task này không overwrite hoặc xóa thay đổi đó. Customer hiện có Flyway V5, JPA persistence, AES-GCM PII service, read-only `CustomerQuery`, self/staff GET controllers, security chain và test PostgreSQL. Account, Admin và Audit production modules vẫn chỉ có skeleton; Identity không có command/API provisioning `app_user`.

Nguồn authoritative đã đối chiếu:

- `docs-common/03. SRS.md` FR-008–011, FR-039, FR-049: Customer profile/account read; không có FR create/update/status.
- `docs-common/04. Database design & analysis.md` §4.2.2: schema `banking.customer`, `ACTIVE/INACTIVE`, FK/unique/check; không mô tả state transition.
- `docs-common/07. API contracts.md` §3.4–3.5, §7.1, §7.2.9, §8.2: chỉ công bố hai Customer GET; không có write route/request/error/idempotency.
- OpenAPI: `/customers/me` và `/admin/customers/{customerId}` chỉ có `get`.
- `docs-common/design/01. audit-traceability.md`: audit catalog không có Customer create/update/status action; mutation trạng thái phải là C2 atomic.
- `docs-common/design/05. Modules contract.md` §6: chỉ có Customer query/view; §11.1 yêu cầu provider Audit thật cho C1/C2.
- Identity `BE-ID-DOC-01` §3.5: lifecycle tạo user/provisioning chưa có FR/API và còn OPEN.

## B. Contract gate theo operation

| Operation | Phân loại | Phần đã duyệt/đã có | Contract bắt buộc còn thiếu |
|---|---|---|---|
| Customer provisioning | **BLOCKED** | V5 FK `customer.user_id → app_user.user_id`; encrypted persistence primitive | Approved caller/owner, Identity command tạo CUSTOMER user, orchestration/transaction, request/response/error, authorization, C2 audit, idempotency/retry |
| Customer creation | **PARTIAL** | `CustomerPiiService.create`, AES-GCM, mandatory name, nullable contacts, DB FK/unique/check, transaction/rollback | Public/internal workflow contract, server-side actor/permission, user-type verification provider, normalization/size rules, duplicate API error, audit action/provider, idempotency semantics |
| Customer profile update | **PARTIAL** | Owner-specific `replaceFullName/Phone/EmailForOwner`; ownership trước decrypt; field-specific API ngăn mass assignment nội bộ; encrypted replacement + rollback | REST/internal command, mutable-field contract, actor permission, audit action/provider, concurrency/version policy, response/error/idempotency |
| Customer status update | **BLOCKED** | DB representation chỉ có `ACTIVE`, `INACTIVE` | Source→target transitions, actor, preconditions, audit action C2, concurrency, error response, downstream effects |
| Customer retrieval | **IMPLEMENTED** | `CustomerQuery.findByUserId`, `GET /customers/me`, `GET /admin/customers/{customerId}`; missing/INACTIVE tách biệt | Staff data-scope/durable Audit vẫn là blocker riêng của IMP-06/07, không tạo write contract |

Không có production mutation endpoint hoặc public command được thêm. Thêm chúng khi các cột bắt buộc ở bảng trên còn thiếu sẽ bypass approval gate và tạo write không thể audit/idempotent an toàn.

## C. Open issues CUS-OI-01/03/04/06

| Issue | Trạng thái | Affected operation | Owner/artifact cần có | Lý do không thể triển khai an toàn |
|---|---|---|---|---|
| `CUS-OI-01` | **OPEN / BLOCKED** | Create, update, status | BA/DB/API/Security sửa nguồn chuẩn và chốt schema/status | Overview đề xuất CIF/national ID/KYC/FROZEN/CLOSED; `03/04/07` chỉ duyệt name/phone/email và ACTIVE/INACTIVE. Task giữ nguồn ưu tiên, không thêm field/status. |
| `CUS-OI-03` | **OPEN / BLOCKED** | Staff create/update | Admin/BA/API phê duyệt API, request/response/error, permission/scope | FE `OPS-CREATE-CUSTOMER` tự ghi GAP-01; API chuẩn không có create route. |
| `CUS-OI-04` | **PARTIAL / BLOCKED workflow** | Provisioning/create | Identity/DB/Customer chốt command và transaction/order tạo `app_user↔customer` | V2/V5 FK và session/query binding đã có, nhưng không có production provisioning caller/interface hoặc failure/rollback contract. |
| `CUS-OI-06` | **OPEN / BLOCKED** | Status update và downstream eligibility | BA/Account/Transfer phê duyệt policy | Không rõ INACTIVE chặn Account/Transfer lúc nào; Customer chỉ trả snapshot status, không tự cascade. |

## D. Phần triển khai an toàn và thay đổi code test

Production primitives đã có từ DEC-CUS-002 được tái sử dụng, không mở rộng contract:

- Create: validate boundary đã duyệt, encrypt trước persistence, `saveAndFlush`, FK/unique/check là consistency boundary.
- Update: chỉ ba method field-specific; actor phải khớp `customer.user_id` trước decrypt; field không đổi giữ nguyên ciphertext; lỗi crypto fail-closed.
- Retrieval: read-only transaction, principal server-side, masked output, missing/INACTIVE đúng contract.

Regression mới:

1. `CustomerPiiServiceTest.unauthorizedProfileUpdateIsRejectedBeforeDecryptAndLeavesEveryFieldUnchanged`: actor khác owner nhận `AUTH_FORBIDDEN`, decrypt count bằng 0, toàn bộ ciphertext và `updatedAt` không đổi.
2. `CustomerPiiPostgreSqlIntegrationTest.duplicateUserCreationIsRejectedWithoutReplacingExistingEncryptedProfile`: create thứ hai cùng `user_id` bị PostgreSQL unique constraint từ chối; chỉ một row còn lại, ciphertext/status row đầu không đổi.

Unique constraint chỉ chứng minh **at-most-one Customer row per user**, không phải idempotent replay. Không thêm `Idempotency-Key`, request fingerprint, retention hoặc idempotency table vì chưa có approval.

## E. Security, audit và transaction

- **Authorization write:** BLOCKED — không có permission/caller write được duyệt. Internal owner update kiểm `actorUserId == stored.userId`; public GET tiếp tục lấy principal từ Identity session, không tin client ID/role/permission.
- **Mass assignment:** không có request DTO/write endpoint; primitive update là field-specific và không cho sửa `userId`, `customerId`, `status`, timestamps.
- **PII:** AES-256-GCM qua Common; phone/email mask; không log plaintext/ciphertext/key.
- **Transaction:** create/update dùng `@Transactional`; `saveAndFlush` làm constraint failure xảy ra trong transaction; PostgreSQL tests chứng minh duplicate/FK/crypto failure không để lại partial row.
- **Audit mutation:** BLOCKED — catalog không có Customer mutation action và production `AuditModuleApi.recordInTransaction`/`audit_log` chưa tồn tại. Không dùng C3 deny log thay C2 mutation audit.
- **Concurrency:** DB unique chặn concurrent duplicate create ở persistence layer từ IMP-02; optimistic locking/update conflict policy không được duyệt nên không thêm `@Version`/migration.

## F. API và database changes

- **Public API:** không đổi. Không thêm POST/PUT/PATCH/DELETE Customer route.
- **Database:** không đổi. Không sửa V5, không thêm migration/idempotency/version/status.
- **Module boundaries:** không sửa Identity, Account, Admin, Audit hoặc Common production source.
- **Contract documentation:** bổ sung Modules contract §6.5 với dependency thiếu và test-only alternative theo `AGENTS.md`; ghi DEC-CUS-007.

## G. Test execution

Chạy từ `digitalbanking-backend`:

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.application.CustomerPiiServiceTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerPiiPostgreSqlIntegrationTest' \
  --rerun-tasks --max-workers=1 --console=plain
```

**PASS — 2 classes / 15 tests / 0 failure / 0 error / 0 skipped; BUILD SUCCESSFUL in 16s.**

```bash
./gradlew testClasses --console=plain
./gradlew test \
  --tests 'com.simulatedbank.banking.customer.*' \
  --tests 'com.simulatedbank.banking.migration.CustomerV5MigrationPostgreSqlIntegrationTest' \
  --tests 'com.simulatedbank.banking.common.config.SecurityAccessDeniedAuditorTest' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --rerun-tasks --max-workers=1 --console=plain
```

- Compile/test classes: **PASS — BUILD SUCCESSFUL in 1s**.
- Customer/security/database/architecture regression: **PASS — 13 classes / 96 tests / 0 failure / 0 error / 0 skipped; BUILD SUCCESSFUL in 29s**.

Full backend:

```bash
./gradlew build --rerun-tasks --max-workers=1 --console=plain
```

**FAIL — BUILD FAILED in 5m51s; 70 classes / 902 tests / 901 pass / 1 failure / 0 error / 0 skipped.** Failure duy nhất: `IdentityAuthPostgreSqlIntegrationTest.t11_fiveConcurrentFailedAttemptsLockWithoutLostUpdate`; assertion bắt `OutOfMemoryError: Java heap space` từ BouncyCastle `Argon2BytesGenerator` khi chạy 5 Argon2id đồng thời. Đây là resource defect Identity đã được ghi ở BE-CUS-IMP-06/07/08 và DEC-ID-TEST-02; stack không đi qua Customer. Hai test mới và toàn bộ focused Customer regression đã PASS. Không sửa/rerun Identity để tạo kết quả xanh giả.

## H. Acceptance criteria

| Acceptance criterion | Status | Evidence |
|---|---|---|
| Explicit API contract cho write workflows | **BLOCKED** | `07`/OpenAPI không có Customer write operation; CUS-OI-03/04 |
| Explicit database contract | **PASS** cho schema hiện tại | V5 + `04` §4.2.2; không có transition/version/idempotency schema được duyệt |
| Authorization controls | **PASS** cho read/internal owner primitive; **BLOCKED** cho write workflow | Identity session/RBAC + owner check; write permission/caller chưa có |
| Audit logging | **BLOCKED** cho mutation | Thiếu Customer C2 action và durable Audit provider; C3 access-denied không thay thế |
| Approved idempotency behavior | **BLOCKED** | Không có key/scope/fingerprint/replay/conflict/retention contract; unique chỉ at-most-one |
| Automated tests | **PASS** cho phần executable | 15/15 focused mới; 96/96 Customer-focused regression |
| Provisioning end-to-end | **BLOCKED** | Identity provisioning command + orchestration + audit/idempotency thiếu |
| Creation end-to-end | **BLOCKED**; primitive **PARTIAL/PASS** | Encrypted create/constraints/rollback PASS; không có authorized API/workflow |
| Profile update end-to-end | **BLOCKED**; primitive **PARTIAL/PASS** | Owner field update/no-mutation-on-forbidden PASS; API/audit/concurrency contract thiếu |
| Status lifecycle | **BLOCKED / NOT TESTED** | Chỉ enum/check ACTIVE/INACTIVE; không có transition để implement/test |
| Customer retrieval | **PASS** | IMP-04/05/06 + focused regression |
| Full backend build | **FAIL** | 902 tests: 901 pass / 1 Identity Argon2 OOM; không liên quan Customer, không rerun |
| Task documentation | **PASS** | Report này, task index, module index, Modules contract §6.5, DEC-CUS-007 |

## I. Files changed by BE-CUS-IMP-09

- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/customer/application/CustomerPiiServiceTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/customer/adapter/out/CustomerPiiPostgreSqlIntegrationTest.java`
- `digitalbanking-docs/docs-backend/modules/customer/BE-CUS-IMP-09.md`
- `digitalbanking-docs/docs-backend/modules/customer/README.md`
- `digitalbanking-docs/docs-backend/modules/customer/tasks/README.md`
- `digitalbanking-docs/docs-common/design/05. Modules contract.md`
- `DECISIONS.md`

## J. Final status

**BLOCKED.** Mọi phần an toàn theo contract hiện hữu đã được giữ và kiểm chứng; không có write workflow nào đạt đủ API + authorization + audit + idempotency gate để triển khai production. Chuyển task sang PARTIAL/DONE chỉ sau khi các owner đóng CUS-OI-01/03/04/06 và cung cấp artifacts liệt kê ở §C/§H.
