# Module `limit`

> **E3 / LIM-06:** API tra cứu đã được triển khai và kiểm thử với PostgreSQL và provider test double; tích hợp provider thật và transfer còn chờ. Xem [kiểm chứng E3](E3-verification.md).

> **Nền hiện tại:** codex/limit trên dev2 sạch. Audit production cũ không được mang sang; chỉ giữ AuditModuleApi contract, thiếu provider thì fail-closed503. Kết quả hiện tại213 tests; xem [chuyển nền dev2](DEV2-MIGRATION.md) và [báo cáo E4](E4-verification.md). Những kết quả audit thật ở ghi chú lịch sử bên dưới thuộc nền phuong/limit cũ.

Nguồn ưu tiên: **docs-common/01–11 → docs-common/design → docs-backend**. Nguồn mô hình là 04 §4.5.1–4.5.2; ngữ nghĩa chọn hạn mức là 07 §8.5.1 và validation là §8.5.2. Không dùng GLOBAL/scope_type/scope_id từ bản tóm tắt cũ.

- [Kế hoạch epic/feature](DELIVERY-PLAN.md): phạm vi E1–E4 và dependencies.
- [Bằng chứng E1](E1-verification.md): kết quả build/test, traceability và giới hạn nghiệm thu.
- [Tóm tắt module](../_overview/06-limit.md): canonical schema và các hướng tích hợp tiếp theo.
- [Spec E1](specs/001-limit-foundation/spec.md), [plan](specs/001-limit-foundation/plan.md), [tasks](specs/001-limit-foundation/tasks.md): hồ sơ SDD local, được ignore theo cấu hình repo.

## E1 — LIM-01/LIM-02

Migration V3 tạo hai bảng limit_config/limit_usage theo customer/account, lưu version và attribution riêng với usage. FK liên module chưa cài vì bảng customer/account/app_user/approval_request chưa có; đây là quyết định clarify của user ngày 15/11/2026, không phải xác nhận integrity/ownership.

Provider nội bộ `LimitRules` nhận customerId, sourceAccountId và Instant; chọn ACTIVE trong khoảng [effective_from,effective_to), trả cả hai scope, effective per-transaction là min. `checkPerTransaction` chấp nhận equality, vượt bất kỳ trần nào ném `BankingBusinessException(LIMIT_EXCEEDED)`; tiền không hợp lệ là VALIDATION_ERROR. Không cấu hình trả NOT_CONFIGURED riêng, không tự cho phép chuyển tiền hay thêm default.

`LimitJdbcRepository` insert configuration/usage trong transaction của caller (MANDATORY), trả identity/timestamps đã lưu; đọc configuration theo ID, usage theo scope/date. Không update/delete, không activation/approval workflow. E1 chỉ kiểm tra từng lần, chưa check/increment daily usage. Đọc provider không ghi dữ liệu hay lấy khóa enforcement. Caller phải xác thực customer/account pair; lọc pair không thay ownership kiểm chứng từ module account.

Money là BigDecimal (tối đa 17 chữ số nguyên, 2 chữ số thập phân), dương cho config/check, không âm cho usage; kiểm tra trước JDBC để tránh NUMERIC làm tròn. VND; không tự chốt timezone D2 vì ngày/instant được truyền tường minh.

## Chưa nghiệm thu

- FK/reference existence và account/customer ownership: cần migrations thật của owner, thêm/validate FK trong migration tiếp theo.
- D1 policy max/default và enforcement khi NOT_CONFIGURED: còn mở cho E2 và cấu hình sau này.
- LIM-05 transfer thật (retry/recovery/concurrency), E3 API self limits và LIM-09/10 endpoint maker/checker: chưa nghiệm thu. Daily enforcement provider E2 và lifecycle provider E4 được mô tả bên dưới.
- Future activation transition D3 và account scope giữa ngày D4: chưa được giải quyết bằng E1.

Build/test provider pass không thay nghiệm thu các tích hợp trên hoặc human review.

## E2 — LIM-03/LIM-04 provider

`LimitEnforcement.lockAndCheck` bắt buộc transaction caller có thể ghi (READ COMMITTED), dùng DB transaction start và `app.timezone` (nghiệm thu `Asia/Ho_Chi_Minh`). Tạo/khóa customer usage trước account usage, đọc cấu hình hiệu lực trong T2 và kiểm tra cả hai scope. Customer counter luôn là mutex, kể cả chỉ có account config. Thiếu cả hai cấu hình trả NOT_CONFIGURED và không cấp phép cộng usage; mapping lỗi transfer còn hoãn.

Caller xử lý fraud/tiền/ledger thành công rồi gọi `recordCompleted(check)` trước audit/commit. Handle giữ nguyên ngày/scope/amount, chỉ dùng một lần trong đúng transaction; provider kiểm tra transaction JPA vật lý đang active và vô hiệu handle trước completion; không thay thế idempotency của transfer. Business rejection ở check không đánh dấu rollback-only, để caller có thể ghi FAILED; lỗi kỹ thuật/completion/audit rollback cùng usage. Migration V4 cấp UPDATE riêng các cột counter, không sửa V3.

- [Spec E2](specs/002-limit-enforcement/spec.md), [plan](specs/002-limit-enforcement/plan.md), [tasks](specs/002-limit-enforcement/tasks.md).
- [Bằng chứng E2 và bàn giao LIM-05](E2-verification.md).
- LIM-05 hoãn theo clarify: checkout chưa có production transfer/account/ledger/identity. Provider test không nghiệm thu debit/credit, ledger, terminal replay hay recovery. D4 (bật account scope giữa ngày) thuộc quyết định E4, không tự reset/backfill.


## E4 — LIM-07/LIM-08 provider

Lượt 15/11/2026 hoàn tất provider tạo/duyệt/từ chối cấu hình. Lượt 16/11/2026 bổ sung endpoint LIM-09/10 và approval LIMIT_CHANGE thật trong admin; nghiệm thu với identity/customer/account/GET E3/transfer thật còn Blocked. Xem [bằng chứng E4](E4-verification.md), [spec](specs/003-controlled-limit-changes/spec.md), [plan](specs/003-controlled-limit-changes/plan.md), [tasks](specs/003-controlled-limit-changes/tasks.md).

`LimitChanges.prepare(Proposal)` nhận scope/maker/approval ID đã được caller xác thực, kiểm tra money/VND/policy, cấp version mới và lưu PENDING_APPROVAL. Pending không đổi active hoặc usage. `approve(approvalId, checkerId)` tái kiểm tra policy, chặn self/repeat, supersede active và activate pending nguyên tử; `reject` giữ active và để attribution checker cho approval/audit của admin (config rejected giữ approved_by/approved_at null theo nguồn 04).

Tất cả mutation yêu cầu physical writable caller transaction, READ COMMITTED. Admin chịu trách nhiệm quyền/step-up/reference/ownership, approval và audit cùng transaction; provider không tạo/sửa approval_request, không ghi audit tự động. Shared customer advisory lock được lấy trước usage locks của E2, và trước account advisory lock của E4; caller không đảo thứ tự bằng cách giữ usage/config lock trước provider. Version/approval link được giữ; V5 chỉ cấp UPDATE cột lifecycle và unique nonnull approval_id, không sửa V3/V4.

Quyết định user cho phạm vi này:

- Policy max từng lần/ngày là cấu hình bắt buộc, không có default nghiệp vụ. Thiếu/sai policy trả SERVICE_UNAVAILABLE khi prepare/approve; quá max LIMIT_OUT_OF_POLICY. Reject vẫn hoạt động khi thiếu policy. Xem [cấu hình](../../deployment/configuration.md#e4--limit-change-policy).
- Từ chối effectiveFrom tương lai; approve hiệu lực tại thời điểm quyết định, không scheduler/backdate. Đây là giới hạn E4 được user chốt; endpoint mới cũng từ chối future-effective và nguồn 07 đã được đồng bộ.
- Bật account scope chưa có cấu hình đang hiệu lực bị chặn nếu customer đã dùng amount hoặc count trong ngày theo app.timezone. Pending được giữ để có thể duyệt ngày mới chưa dùng; lịch sử expired không vượt qua guard. Không tạo/reset/backfill usage.
- D5 đã chốt ngày 16/11/2026: HTTP409 ROLE_CONFLICT khi self-decision; SRS/07 đã đồng bộ. Có C3 denial audit độc lập.

Thay đổi cấu hình không ghi usage, kể cả tạo counter 0. Hạ limit dưới usage giữ nguyên counter; E2 chặn chi thêm. E2 vẫn dùng transaction-start instant cố định: một T2 bắt đầu trước activation nhưng chờ khóa trả NOT_CONFIGURED nếu bất kỳ scope ACTIVE đang hiệu lực bắt đầu sau instant đó; chặn toàn check để không bỏ qua account scope mới trong khi customer scope cũ còn hiệu lực. Fail-closed, không tự cho phép chuyển tiền. Transaction mới sau commit đọc hạn mức mới. Không tuyên bố availability liên tục cho request đang chờ, hoặc nghiệm thu consumer transfer/GET thật.


## E4 — LIM-09/LIM-10 endpoint và approval thật (16/11/2026)

Đã triển khai `POST /api/v1/admin/limits` (201 ApprovalSummary + Location) và `POST /api/v1/admin/limits/{approvalId}/approve` (200 ApprovalDetail APPLIED/REJECTED). `admin` sở hữu approval_request thật theo04; chỉ workflow LIMIT_CHANGE. Chưa thêm GET approvals hoặc role/retention workflow.

Actor lấy từ principal của SessionAuthenticator; service kiểm staff role, LIMIT_MAKER/LIMIT_MANAGE và CHECKER. Cookie dùng CSRF common. Step-up SENSITIVE_ACTION theo candidate `identity.api.StepUpChallengePort`: maker bind actor/action/hash payload; checker thêm approvalId/decision/proposal hash. Consume tham gia cùng transaction approval/config/audit. Provider bắt buộc chưa có thì fail-closed, không có bean giả production.

Tạo/duyệt/từ chối dùng admin/limit thật + PostgreSQL; audit trên nền dev2 là SQL test double qua API, implementation production còn Blocked. Self-decision trả409 ROLE_CONFLICT khi các provider đã được ghép; contract yêu cầu C3 audit độc lập và fallback, hiện chỉ kiểm consumer với test double. Production thiếu audit thì503, không tiếp tục mutation. Approve kiểm current version dưới limit lock; stale proposal bị409 và giữ pending. Usage giữ nguyên; future-effective và D4 giữ quyết định cũ.

Candidate APIs `StaffDirectory`, `CustomerQuery`, `AccountQuery` chỉ công bố contract/projection; owner cần review và cung cấp implementation thật. Các fake tương ứng và bảng `e4_test.challenge` chỉ ở test. Không dùng kết quả test này để nhận OTP/session/ownership thật đã đạt. Xem [báo cáo E4](E4-verification.md) và [hồ sơ SDD endpoint](specs/004-limit-admin-integration/spec.md).
