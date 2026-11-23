# BE-ID-IMP-11 — RBAC, Admin role command và thu hồi session

> **Ngày:** 16/11/2026 · **Owner:** Đạt (Identity)
> **Branch:** `datht-backend-rework` · **Baseline:** HEAD = `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a` sau fetch.
> **Trạng thái:** PARTIAL — Identity có production implementation; Admin/Audit runtime chưa có, không đánh DONE toàn task.
> **Quyết định được người điều hành duyệt:** [DEC-ID-011](../../../../DECISIONS.md).

## A. Phạm vi và trạng thái ban đầu

- Root không có `README.md`; đã đọc `digitalbanking-docs/README.md`, SRS §4, architecture §6/§8, API §3/§4/§8.8, DB §4.2/§4.5.3, Modules contract §5.1/§5.3/§11.1/§12.1 và Audit action catalog §5.2–5.3.
- IMP-01 có V2/seed RBAC và migration tests; IMP-03 có opaque session, CSRF, logout và `SessionAuthenticator` thật. Các task trước có nhiều file chưa commit; giữ nguyên, không chuyển branch/commit/push.
- Admin/Audit hiện là package skeleton, tài liệu module chỉ có heading. Public role endpoints thuộc Admin, không thêm controller production vào Identity.
- Lỗi RBAC phát hiện khi đọc code: `JdbcAuthorityReader` chỉ kiểm `permission.status` trong nhánh permission, không kiểm `role.status`; role DISABLED vẫn cấp permission. Thay reader bằng JPA projection, không dựng security chain khác.

## B. Code thay đổi

Đường dẫn Java dưới `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/`:

| File | Mục đích và chi tiết |
|---|---|
| `api/IdentityRoleCommand.java` | Public internal boundary provider-owned: `ApprovedRoleChange`, `RoleChangeResult`, bốn operation chuẩn và approval states chuẩn; API đọc version cho maker; không là REST DTO. |
| `application/IdentityRoleCommandService.java` | JOIN/MANDATORY, principal checker thật, recheck quyền DB cho maker/checker, state/self/type/catalog/version guards; mutation + version + revoke cùng transaction. |
| `application/port/RoleChangeStore.java` | Port persistence Identity, scalar user/version read, lock, role/privilege mutation và revoke. |
| `adapter/out/JpaRoleChangeStore.java` | JPA projection/read, ordered user locks + refresh, assignment/grant/revoke, increment version; flush Identity writes trước khi caller lấy Audit chain lock, rồi bulk JPQL revoke ACTIVE sessions. Không JDBC/raw SQL CRUD mới. |
| `adapter/out/RoleChangeUserEntity.java` | Projection app_user; chỉ version assignment và updated_at được ghi. |
| `adapter/out/RoleChangeUserJpaRepository.java` | PESSIMISTIC_WRITE user query; target lock bảo vệ cả assignment chưa tồn tại. |
| `adapter/out/RoleChangeRoleEntity.java` | Projection catalog role, không thay định nghĩa. |
| `adapter/out/RoleChangeRoleJpaRepository.java` | Giữ khóa đọc role catalogue trong approval transaction. BE-INT-09 dùng hàm V23 `lock_role_for_assignment` để `banking_app` không cần UPDATE catalogue; xem DEC-INT-018. |
| `adapter/out/RoleChangeAssignmentEntity.java` | Composite key user_role, assigned_by/assigned_at. |
| `adapter/out/RoleChangePrivilegeEntity.java` | Composite key user_privilege, ACTIVE/REVOKED, grant metadata; không xóa lịch sử revoke. |
| `adapter/out/RoleChangeSessionEntity.java` | Projection phục vụ atomic JPQL revoke theo user/status; không chứa raw token. |
| `adapter/out/AuthorityUserEntity.java` | Read-only mappings user, role, permission, privilege; không mutate catalog qua reader. |
| `adapter/out/AuthorityJpaRepository.java` | Projection chỉ ACTIVE user/role/permission/privilege, role phù hợp CUSTOMER/STAFF. |
| `adapter/out/JpaAuthorityReader.java` | AuthorityReader implementation thay reader JDBC; fresh immutable grants, tách customer self-service và staff permissions, CHECKER chỉ STAFF. |
| `adapter/out/JdbcAuthorityReader.java` | Xóa implementation cũ; không giữ alias/shim hoặc hai bean reader. |
| `application/SessionViewService.java` | Javadoc dùng `AuthorityReader` thay tên implementation đã xóa; không đổi session response. |

Ngoài Java production:

| File | Thay đổi |
|---|---|
| `digitalbanking-backend/src/main/resources/db/migration/V4__add_role_assignment_version.sql` | Forward-only BIGINT nonnegative/default 0; không sửa V1/V2/V3. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/RoleChangePostgreSqlIntegrationTest.java` | PostgreSQL/JPA, command validation, concurrency, rollback, version migration, session/HTTP và Admin/Audit fixture. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/RbacAuthorityPostgreSqlIntegrationTest.java` | Regression disabled role/permission, inactive user, revoked privilege, type segregation và forged claims. |
| `DECISIONS.md` | DEC-ID-011: quyết định trực tiếp về version/revoke/self-change và dependency thật. |
| `digitalbanking-docs/docs-common/04. Database design & analysis.md` | Đồng bộ extension được duyệt tại app_user, không đổi nghĩa public API. |
| `digitalbanking-docs/docs-common/design/05. Modules contract.md` | §5.3.1 ghi boundary, transaction, errors và fixture thay thế khi dependency thiếu. |
| `digitalbanking-docs/docs-backend/modules/identity/README.md`, `tasks/README.md`, `BE-ID-IMP-11.md` | Index, checklist PARTIAL và báo cáo evidence tiếng Việt. |

## C. Contract và hành vi

### RBAC

Dùng duy nhất Spring Security/common filter chain có sẵn. Token chỉ trỏ server-side session; role/permission/privilege đọc lại từ authoritative DB, không tin body/header hay authority cũ của principal. `CUSTOMER` chỉ có role CUSTOMER và bốn self-service permissions; STAFF chỉ có OPERATIONS/ADMIN/AUDITOR và staff permissions theo `07` §3.4. Role/permission DISABLED và privilege REVOKED không hiệu lực; user LOCKED/DISABLED không có grants. Không có assignment → không có quyền nghiệp vụ. Ownership của module khác vẫn do provider tương ứng kiểm; không tuyên bố đã triển khai API Account/Transfer chưa tồn tại.

### Admin → Identity

1. Maker Admin + ROLE_MANAGE gọi `getRoleAssignmentVersion(targetUserId)`; Admin phải lưu expectedVersion cùng approved payload.
2. Admin lock approval, kiểm PENDING/type/target/payload/maker-checker và consume verified SENSITIVE_ACTION qua port hiện có; chuyển APPROVED trong transaction đó.
3. Truyền `ApprovedRoleChange` từ trusted server-side orchestrator. Identity yêu cầu authenticated Session principal trùng checker; maker/checker STAFF/ACTIVE, Admin + ROLE_MANAGE, checker thêm CHECKER. Maker khác checker; target khác cả hai.
4. Identity khóa ba user theo ID tăng dần, refresh managed entity để tránh stale first-level cache, kiểm target ACTIVE/version/type/catalog. ASSIGN/REVOKE chỉ role chuẩn ACTIVE; CUSTOMER không nhận staff role, STAFF không nhận CUSTOMER; CHECKER chỉ STAFF.
5. Mutate thực sự mới tăng version đúng 1 và revoke ACTIVE sessions target. Duplicate assign, revoke absent hoặc duplicate grant đều conflict, không bump/revoke.
6. Result giữ approval/target/operation/codes, previous/resulting version, revoked count, correlation/request IDs. Admin update APPLIED và Audit ghi ROLE_CHANGE_APPROVED C2 cuối cùng transaction. Commit/rollback dùng cùng datasource/transaction manager; không phải distributed atomicity.

### Concurrency và rollback

`expectedVersion` là optimistic precondition được so dưới user-row lock; không dùng timestamp giả version hoặc JPA @Version trên toàn credential entity. Hai lệnh cùng expectedVersion chỉ một lệnh commit; lệnh sau đọc version mới và nhận ROLE_CONFLICT. Role/version/session không commit riêng; thiếu ambient transaction bị từ chối ngay. Lỗi persistence/revoke hoặc lỗi C2 từ caller buộc rollback toàn approval transaction.

### Revocation

Theo DEC-ID-011, mọi thay đổi role/privilege revoke toàn bộ row ACTIVE của target thành LOGGED_OUT/logged_out_at, không thêm enum REVOKED/reason column. User khác giữ nguyên. Opaque Bearer/cookie được SPI kiểm DB mỗi request nên token cũ bị 401 AUTH_SESSION_EXPIRED; `/auth/session` không refresh/rotate lại phiên đã revoke. Không có JWT hay refresh-token credential trong authentication model hiện tại; không thêm blacklist hoặc refresh endpoint.

Login OTP/FIDO hiện khóa user trước khi tạo session; role change dùng cùng user lock trước khi revoke. Session mới sau khi transaction thay đổi quyền commit là login mới với grants hiện tại, không phải phục hồi session cũ. Request đã authenticated trước commit không bị hủy giữa chừng; request tiếp theo kiểm lại session.

### Audit ownership, errors và duplicate

- ROLE_CHANGE_REQUESTED/APPROVED/REJECTED thuộc Admin theo audit catalog; Identity không phát bản sao hoặc event tên mới cho revoke.
- Admin phải bổ sung maker/checker, safe before/after state, version/revoked count và correlation/request vào Audit provider. Không có audit_log/outbox mới trong Identity.
- Thiếu auth → AUTH_SESSION_EXPIRED; thiếu quyền/forged checker → AUTH_FORBIDDEN; target không tồn tại → USER_NOT_FOUND; inactive target/type/self/stale/no-op → ROLE_CONFLICT; invalid/disabled role → ROLE_NOT_FOUND; malformed command → VALIDATION_ERROR; terminal approval → APPROVAL_ALREADY_DECIDED.
- `APPROVED` input là trusted Admin fact, không chứng minh row approval tồn tại/đã lock khi Admin runtime chưa có. Không expose command trực tiếp qua HTTP production.
- Duplicate decided approval bị Admin từ chối; retry stale command bị version guard từ chối trước mutation, không duplicate audit/revoke. Không cam kết replay success/idempotency key khi OI-API-19 chưa chốt.
- Failure/403 C3 SECURITY_ACCESS_DENIED và durable C2 thật thuộc integration Admin/Security/Audit còn thiếu; không dùng SecurityLogAuditAdapter hiện hữu để tuyên bố durable role audit thành công.

### Chênh lệch được ghi nhận

- `04` cũ không có assignment version; extension V4 được người điều hành duyệt, không tự dùng updated_at.
- Draft §5.3 có expectedVersion nhưng chưa có signature/schema hoàn chỉnh; public request API-ROL không đổi. Boundary provider-owned được ghi §5.3.1, consumer thật vẫn cần owner review.
- Design security/overview có REVOKED/role names khác; dùng enum session và bốn role của nguồn `04`/`07` ưu tiên cao hơn.
- General user/device revoke API Deferred OI-API-12; chỉ revoke nội bộ theo role-change policy được duyệt, không thêm endpoint logout-all.

## D. Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| RBAC enforcement | PASS | JPA ACTIVE/type-filtered grants; 10 RBAC regression cases, command authorization và HTTP security chain thật. |
| Atomic approval + role update | BLOCKED (production) | Identity JOIN/MANDATORY và PostgreSQL fixture kiểm commit/rollback approval+challenge+role+version+session+audit; Admin/Audit runtime thật chưa có. |
| Stale version rejected | PASS | Version guard dưới refreshed user-row lock; stale retry giữ nguyên state, concurrent cùng version một commit/một ROLE_CONFLICT. |
| Revoked sessions denied | PASS | Target ACTIVE rows → LOGGED_OUT; opaque Bearer/cookie HTTP 401 và `/auth/session` không hồi sinh; user khác vẫn 200. |
| Admin integration | BLOCKED (production) | Provider command/version read hoàn tất; test consumer có lock/decision/step-up/retry/concurrent checker, không thay Admin public endpoint thật. |
| Audit integration | BLOCKED (production) | Safe result facts + JOIN rollback, fixture ROLE_CHANGE_APPROVED giữ correlation/version/count và không duplicate; chưa có Audit durable/hash chain/C3 provider. |

Các PASS ở bảng được chứng minh bằng runtime Identity; không chuyển fixture PASS thành production approval/Audit PASS. §E ghi execution hiện tại và full run cuối.

## E. Kiểm thử và build

### Lệnh đã chạy và kết quả ban đầu

Chạy tại `digitalbanking-backend`:

```bash
./gradlew test --tests 'com.simulatedbank.banking.identity.RoleChangePostgreSqlIntegrationTest' --tests 'com.simulatedbank.banking.identity.RbacAuthorityPostgreSqlIntegrationTest' --console=plain
```

- Lần đầu: 53 cases, 43 failure ở RoleChange context startup; 10 RBAC cases PASS. Root cause: test fixture controller được đăng ký vừa bằng nested TestConfiguration vừa bằng @Bean, gây `Ambiguous mapping` tại `/api/v1/admin/roles/{id}/approve`. Không phải lỗi provider hoặc assertion bị bỏ qua.
- Đã bỏ đăng ký @Bean trùng, import duy nhất test controller configuration; bổ sung hai case assignment-write rollback và concurrent cùng approval.
- Lần sửa: **BUILD SUCCESSFUL, 55/55 PASS** (RoleChange 45 + RBAC 10), 0 failure/error/skip, 25 giây. Đây là focused run trước bổ sung explicit flush bảo đảm Identity writes hoàn tất trước Audit phase; full run cuối là evidence cho source cuối.
- Không disable test, không nới assertion, không mock JPA/transaction/token-validation/signature để đạt xanh. Admin/Audit fixture và seeded session/VERIFIED challenge chỉ là input tại boundary; login OTP/FIDO vẫn được regression suite hiện hữu kiểm riêng.

### Regression Identity/security

```bash
./gradlew test --tests 'com.simulatedbank.banking.identity.*' --tests 'com.simulatedbank.banking.common.config.*' --tests 'com.simulatedbank.banking.architecture.*' --tests 'com.simulatedbank.banking.migration.*' --console=plain
```

**BUILD SUCCESSFUL (5m10s): 34 classes, 584/584 PASS, 0 failure/error/skip**; Identity 508, Common config/security + architecture + migration 76. Full build cuối chạy lại mọi task trên source cuối, gồm explicit flush và assertion write-failure trước Audit phase.

### Full verification trên source cuối

```bash
./gradlew build --rerun-tasks --console=plain
```

**BUILD SUCCESSFUL (5m40s), 9/9 Gradle tasks executed**: compileJava, resources/classes/bootJar/jar/assemble, compileTestJava, test, JaCoCo, check/build đều hoàn tất. JUnit XML: **48 classes / 721 tests / 0 failure / 0 error / 0 skipped**. Identity **508**, các nhóm khác **213**; trong cùng full run có **55 test IMP-11** (RoleChange 45 + RBAC 10). Đây là evidence sau explicit flush và assertion assignment-write failure trước Audit phase, không cộng dồn nhiều lần chạy.

- Failed test cuối: **không có**; không có remaining test/build failure để quy cho module khác. Lỗi test-fixture startup lần đầu đã sửa, các case từng fail đều chạy lại trong full suite.
- Runtime HTTP thật đã quan sát 200/401/403/409 qua Spring Security chain; revoked Bearer/cookie không truy cập được `/auth/session`, không có UI frontend mới cần visual proof.
- Report máy đọc: `digitalbanking-backend/build/test-results/test/TEST-*.xml`; HTML: `digitalbanking-backend/build/reports/tests/test/index.html`; coverage: `digitalbanking-backend/build/reports/jacoco/test/html/index.html`.
- Log lệnh: `/tmp/be-id-imp11-focused.log` (lỗi ban đầu), `/tmp/be-id-imp11-focused-fixed.log`, `/tmp/be-id-imp11-regression.log`, `/tmp/be-id-imp11-build.log`.
- Không có throwaway script production; fixture chỉ trong test. Không commit/push/merge/PR, không thay branch.

### Scenario chính trong test mới

| Nhóm | Evidence method/scenario |
|---|---|
| Authoritative RBAC | `disabledRoleHasNeitherRoleNorPermissionGrants`, disabled permission, revoked CHECKER, inactive user, no grants, CUSTOMER/STAFF segregation, forged context. |
| Command authorization | `unauthenticatedCommandDenied`, `forgedPrincipalAuthoritiesCannotReplaceDatabasePermissions`, maker/checker privilege/status, maker=checker, self-role/privilege change. |
| Validation | Invalid/disabled role, invalid privilege/field combination, absent/inactive target, invalid approval states, CUSTOMER↔STAFF mismatch. |
| Successful mutations | Assign/revoke role; grant/revoke/regrant privilege, assigned_by/granted_by, version increments, target-only revocation. |
| Concurrency/version | Stale command/retry, stale approval, managed-entity refresh, same-version concurrent commands và same-approval concurrent checker. |
| Atomicity/failure | MANDATORY without TX, caller rollback, assignment constraint failure trước Audit phase, revocation constraint failure, Audit failure; approval/challenge/role/version/session/audit consistent. |
| Integration boundary | Real SENSITIVE_ACTION consume, wrong actor binding rejected; fixture C2 correlation/request, previous/resulting version/revoked count; retry không duplicate audit. |
| Session/security HTTP | Unauthenticated 401, unauthorized 403, checker 200, decided approval 409, revoked Bearer/cookie 401, unrelated session 200, không leak token. |
| Migration | Upgrade V3 có user/role cũ → V4 version 0, giữ credential/assignment; negative version bị PostgreSQL constraint reject. |

## F. Dependency còn lại

| Dependency | Owner | Identity-side | Điều kiện gỡ BLOCKED |
|---|---|---|---|
| Public API-ROL-01/02, approval_request migration/entities, query/lock/decision, step-up orchestrator | Admin | Command/version read/JOIN/result đã có | Implement provider thật; capture expectedVersion khi proposal, không overwrite bằng current version lúc approve; validate binding và state, call cùng transaction. |
| ROLE_CHANGE_* C2 durable, C3 SECURITY_ACCESS_DENIED/failure, safe snapshots/hash chain | Admin + Audit/Security | Trả facts không secrets; rollback lan theo caller | Audit API/adapter thật; Admin append cuối TX, C2 fail rollback; kiểm metadata, retries và failure logs/metrics không lộ secrets. |
| Atomic approval+role+audit end-to-end | Admin + Audit | JOIN contract và test fixture, không cross-table writes | Chạy lại scenarios trên approval_request/audit_log thật, concurrent checker và rollback DB/Audit/revoke. |
| Administrative idempotency/replay success | API/BA/Admin, OI-API-19 | Stale/no-op rejected, không tạo duplicate side effects | Chốt policy nếu cần idempotency; không suy diễn transfer key/global record cho Admin. |

## G. Trạng thái cuối

**PARTIAL**. Identity độc lập đã có code; chỉ đánh DONE khi có evidence toàn bộ acceptance criteria, gồm Admin/Audit runtime thật. Checklist giữ `[ ]`.

## Change Log

| Ngày | Mô tả |
|---|---|
| 16/11/2026 | Triển khai BE-ID-IMP-11 theo DEC-ID-011, thay reader JDBC lỗi disabled-role, JPA role command/version/revoke và contract/security tests; phân biệt dependency production với fixture. |
