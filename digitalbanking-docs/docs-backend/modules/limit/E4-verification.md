# E4 — Báo cáo triển khai và kiểm chứng LIM-07–LIM-10

> **Lượt provider:** 15/11/2026 — LIM-07/08
> **Lượt endpoint:** 16/11/2026 — LIM-09/10
> **Trạng thái hiện tại:** Code hai endpoint và approval LIMIT_CHANGE thật đã triển khai; 294 kiểm thử liên quan đạt; build thành công; tự converge PASS sau2 vòng, 0 gap cuối. Nghiệm thu với identity/customer/account/GET E3/transfer thật còn **Blocked**. Không dùng subagent ở lượt endpoint.

## 1. Nguồn, phạm vi và quyết định

Ưu tiên docs-common/01–11 → design/05 → backend. Căn cứ: SRS FR-032/033/035/051, UC-011, AC-017/019/028; Database §4.5; Architecture §6–8; API §6.5/8.5; DESIGN-MOD-05 §5/6/7/10/11/12/13; DELIVERY-PLAN E4.

Lượt 15/11 hoàn tất provider LIM-07/08; lúc đó hoãn endpoint theo clarify. Lượt 16/11 user yêu cầu làm tiếp theo contract, đồng ý triển khai approval_request/workflow LIMIT_CHANGE thật hẹp trong admin. Các quyết định hiện hành:

- Policy max từng lần/ngày phải có cấu hình rõ, không default nghiệp vụ. Thiếu/sai policy từ chối tạo/duyệt; reject vẫn dùng được.
- Từ chối effectiveFrom tương lai; config có hiệu lực ngay khi duyệt. Không scheduler/backdate.
- D4: account scope chưa có cấu hình đang hiệu lực không được kích hoạt nếu customer đã dùng amount/count trong ngày; giữ pending để có thể duyệt ngày mới, không reset/backfill.
- D5: maker tự duyệt trả **409 ROLE_CONFLICT**; đã đồng bộ SRS UC-011 và07. Thiếu quyền vẫn403.
- Maker challenge bind actor/action/hash payload chuẩn hóa, chưa có approvalId; checker bind thêm approvalId/decision/proposal hash. Không tạo approval trước step-up.
- Không subagent reviewer ở lượt16/11; tự chạy Spec Kit converge. Không tự commit/push lượt này.

Hồ sơ Spec Kit: [lượt provider](specs/003-controlled-limit-changes/spec.md), [lượt endpoint](specs/004-limit-admin-integration/spec.md), [plan](specs/004-limit-admin-integration/plan.md), [candidate contracts](specs/004-limit-admin-integration/contracts/integration.md), [tasks](specs/004-limit-admin-integration/tasks.md). Interface mới là candidate cần owner review; không tự ghi các thành viên khác đã phê duyệt.

## 2. Phần code đã thực hiện

| Phần | Implementation và hành vi |
|---|---|
| LIM-07/08 | LimitChanges prepare/approve/reject, version history, validation/policy, maker != checker, giữ usage; shared customer/account lock với E2 |
| LIM-09 | POST `/api/v1/admin/limits`: staff permission/CSRF/step-up; 201 ApprovalSummary + Location `/api/v1/admin/approvals/{id}`; approval PENDING + config PENDING_APPROVAL + C2 audit cùng transaction |
| LIM-10 | POST `/api/v1/admin/limits/{approvalId}/approve`: staff/CHECKER, lock/recheck pending, consume challenge, approve/reject, approval APPLIED/REJECTED + config + C2 audit cùng transaction; 200 ApprovalDetail |
| Admin persistence | V6 tạo approval_request thật đúng04; chỉ workflow LIMIT_CHANGE, không generic GET approvals/role/retention. Quyền cột tối thiểu, không DELETE/maker-target rewrite |
| Public provider API | Limit validateChange/current/approve(expectedVersion); identity StepUpChallengePort/StaffDirectory, customer CustomerQuery, account AccountQuery; admin chỉ import provider.api, không đọc bảng chéo |
| Audit | AuditModuleApi facade, AuditRecorder thật: C2 MANDATORY fail-closed; self-denial C3 REQUIRES_NEW, reasonCode-only allowlist. C3 lỗi fallback log/metric, vẫn deny |

Các mutation dùng READ COMMITTED. Maker requestChallenge ở ngoài transaction nghiệp vụ; verified consume tham gia transaction caller. Decision khóa approval trước, kiểm config current version dưới limit scope lock; proposal stale bị409, giữ pending. Lost race không consume challenge của checker thua. Body ID/Money là JSON string; money fixed2, ID trong BIGINT, enum quyết định dạng string, extra fields bị từ chối.

Reason/note giới hạn500, từ chối control characters và mẫu PII/secret rõ ràng; không đưa free text vào audit/log. Bộ lọc không phải cơ chế phát hiện mọi loại PII; contract vẫn cấm caller gửi PII/secret. Actor/displayName lấy từ principal và StaffDirectory, không bịa displayName production.

Policy: `app.limit.policy.max-per-transaction-vnd`, `app.limit.policy.max-daily-vnd`; env tương ứng `APP_LIMIT_POLICY_MAX_PER_TRANSACTION_VND`, `APP_LIMIT_POLICY_MAX_DAILY_VND`. Mặc định trống → SERVICE_UNAVAILABLE; vượt max → LIMIT_OUT_OF_POLICY.

## 3. Thành phần thật, fake/mock và giới hạn bằng chứng

| Thành phần | Cách test | Chứng minh / chưa chứng minh |
|---|---|---|
| PostgreSQL16, Flyway V1–V6, constraints, role/grants, locks | Testcontainers thật, app/migrator role, transaction/connection độc lập | Kiểm chứng persistence, khóa và rollback của code đang có; không dùng H2/mock cho invariant |
| Admin endpoint/service/store, limit providers, AuditRecorder/facade | Implementation thật, MockMvc đi qua Spring Security/common filters, SQL thật | Approval/config/audit cùng commit/rollback; concurrency, stale version, usage và HTTP mapping |
| SessionAuthenticator | Bean fake chỉ ở TestConfiguration; ánh xạ bearer/cookie test sang Session snapshot | Test wiring principal, permission, CSRF thật; **không** chứng minh login/session expiry/revocation production |
| StepUpChallengePort | TestPorts fake; requestChallenge tạo fixture PENDING, test đánh dấu VERIFIED, consume kiểm vector bằng SQL trên `e4_test.challenge` | Chứng minh consumer truyền binding đúng và consume fixture JOIN caller/rollback; **không** có OTP/FIDO/identity engine thật |
| StaffDirectory, CustomerQuery, AccountQuery | Fake projection/ID test; unit test missing-provider dùng mock | Test consumer xử lý existence/ownership/missing dependency; **không** chứng minh DB lookup/data scope thực của các provider |
| Ngày nghiệp vụ | Lượt provider có spy nguồn transactionStart/currentTime cho ca biên ngày, và ca SQL thời gian thật | Chỉ điều khiển seam thời gian; config/usage/transaction vẫn PostgreSQL thật |
| Policy/usage | MockEnvironment trong unit test; property test và một số fixture usage trực tiếp; ca khác gọi E2 thật | Không phải max nghiệp vụ production, hot reload hoặc bằng chứng transfer/ledger thật |

Không có fake provider bean production hoặc bảng customer/identity placeholder trong migration. Production chưa có session SPI thì401; đã auth mà thiếu provider bắt buộc thì503, không tạo/duyệt bằng đường giả.

## 4. Kiểm thử và kết quả

Lượt provider15/11: **159 tests pass**, package build thành công; reviewer độc lập lúc đó converge0 findings, 15/15 task. Đây là bằng chứng lịch sử của LIM-07/08, không tự nghiệm thu endpoint mới.

Lượt endpoint16/11:

- Test đỏ đầu tiên: authenticated POST maker kỳ vọng503 khi chưa có provider; trước controller nhận404. Sau implementation ca fail-closed đạt.
- Test HTTP/PostgreSQL gồm: maker pending/Location/audit; thiếu step-up không approval/config; role/privilege/CSRF; strict ID/Money/unknown fields/PII; binding đổi actor/payload/decision; expired/single-use challenge; approve/reject/self/repeat; stale config; D4; lowered limit giữ usage và E2 chặn chi thêm; hai checker cạnh tranh; audit SQL failure rollback approval/config/consume fixture; self-denial audit và fallback khi audit lỗi; DB grants/constraints.
- Scoped command từ `digitalbanking-backend/`: `./gradlew test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.audit.application.AuditWriterIT' --tests 'com.simulatedbank.banking.audit.domain.AuditEventValidatorTest' --tests 'com.simulatedbank.banking.audit.domain.AuditSnapshotContractTest' --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (54 giây), 294 tests / 0 failure / 0 error / 0 skip**.
- Package: `./gradlew build -x test --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (1 giây)**.
- Chỉ chạy scope liên quan, tối đa2 worker; không full regression của module khác.

XML: `digitalbanking-backend/build/test-results/test/TEST-*.xml`; HTML: `digitalbanking-backend/build/reports/tests/test/index.html`. Log tạm lượt16/11: `/tmp/limit-e4-api-red.log`, `/tmp/limit-e4-api-focused.log`, `/tmp/limit-e4-api-scoped.log`, `/tmp/limit-e4-api-package.log`; không commit log tạm. Log lượt15/11 có thể đã mất; các lần test sau ghi đè XML, không dùng đường dẫn tạm làm archive lâu dài.

## 5. Dependency còn Blocked — owner và điều kiện đóng

**Ngày theo dõi: 16/11/2026.** Không có ngày hoàn thành của owner được tự đặt trong báo cáo.

| Dependency | Trạng thái và phần chưa nghiệm thu | Owner/phối hợp theo tài liệu11 | Điều kiện đóng |
|---|---|---|---|
| Identity/session/step-up/StaffDirectory thật | Blocked: hiện chỉ có candidate API và fake trong test; chưa login/OTP/FIDO/expiry/revoke/consume thực | Đạt; phối hợp admin/limit | Owner review interface/vectors, cung cấp bean production; chạy lại contract suite và HTTP integration với identity thật |
| Customer/account thật | Blocked: chưa lookup reference/ownership/data scope từ DB thật | Đạt | Cung cấp query implementation, chạy not-found/ownership/IDOR và staff scope thật |
| Owner review candidate contracts | Blocked: user chốt hướng triển khai, chưa có biên bản các owner cùng review PR | Đạt (identity/customer/account/admin), Vũ (limit), Thịnh (audit) | Review signature/DTO/tx/error/vectors và ghi nhận compatibility; không tự sign-off thay người khác |
| FK liên module/lịch sử | Blocked: app_user/customer/account chưa có; limit.approval_id có dữ liệu lịch sử nên chưa validate FK tự động | Owner dữ liệu liên quan | Migration owner thật, đối chiếu/sửa reference lịch sử bằng quy trình được chốt, thêm/validate FK. Admin TX mới dùng approval thật nhưng không thay DB referential integrity toàn bộ |
| GET E3/remaining response | Blocked: chưa có API thật | Vũ; phối hợp FE Thắng | Cung cấp E3, kiểm response/remaining không âm sau duyệt; internal LimitRules test không thay API acceptance |
| Transfer/account/ledger thật | Blocked: chưa chứng minh debit/credit/ledger, terminal replay/recovery hoặc tác động qua transfer endpoint | Vũ, Đạt, Phương | Ghép consumer thật và chạy các ca limit/usage/retry/recovery/rollback theo E2/DELIVERY-PLAN |
| Policy max production | Chưa chốt giá trị max nghiệp vụ/seed | BA và owner limit | Cấp cấu hình max rõ ràng; không lấy số1000/2000 trong test làm baseline triển khai |

Admin approval_request/workflow LIMIT_CHANGE **đã có implementation thật** ở lượt này, không còn là dependency bị hoãn. Các workflow admin ngoài E4 vẫn ngoài phạm vi.

## 6. Lưu ý khi tích hợp và nghiệm thu

E2 giữ transaction-start instant/business date cố định. Nếu scope mới ACTIVE có hiệu lực sau instant của T2 đang chờ, toàn check trả NOT_CONFIGURED để không bỏ qua scope mới; không cấp phép tiền. Caller transfer không được hiểu NOT_CONFIGURED thành không giới hạn. Transaction mới sau commit dùng cấu hình mới.

GET `/admin/approvals/{id}` trong Location là hợp đồng điều hướng nguồn07; feature này chưa thêm endpoint GET chung. Không tuyên bố toàn E4 đã verified với mọi provider chỉ từ test doubles. Code LIM-09/10 và atomicity approval/config/audit hiện có được kiểm chứng riêng; acceptance dùng provider thật nêu ở §5 còn mở.

## 7. Tự converge lượt endpoint

Tự đối chiếu **12 FR, 4 SC, 9 acceptance scenario, 6 edge case, 12 quyết định thiết kế và 24 task** với code/test hiện tại. Không dùng subagent; constitution còn template nên bỏ qua placeholder, không extension hook.

- **Vòng1:** 2 gap partial: HIGH về thiếu vector purpose/cross-approval/wire type; MEDIUM về reject vẫn trả result như đã áp dụng. Đã append T023/T024 vào tasks, không sửa intent của spec/plan để che gap.
- Regression trước sửa chứng minh numeric effectiveFrom nhận428 thay vì400 và reject có result. Đã thêm StrictInstant, StrictDecision test, kiểm SENSITIVE_ACTION ở challenge response, vector sai purpose/approval và chỉ xuất result khi APPLIED.
- **Vòng2: CONVERGED — 0 gap.** Lượt đánh giá sạch không sửa tasks.md; sau đó phase implement/handoff đánh dấu T022 hoàn tất. **24/24 task xong** trong phạm vi code và consumer verification đã clarify.

Kết quả cuối: 294 tests, 0 failure/error/skip; package BUILD SUCCESSFUL1 giây; JAR có controller thật, không chứa TestPorts/FakeProviders/test schema; admin chỉ phụ thuộc provider.api và common SPI được nguồn cho phép. Không chạy full regression ngoài scope. Acceptance tích hợp các provider thật trong §5 vẫn Blocked, không được đóng bằng fake hoặc converge pass.
