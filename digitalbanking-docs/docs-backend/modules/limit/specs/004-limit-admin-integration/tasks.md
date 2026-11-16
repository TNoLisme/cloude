# Tasks: LIM-09/10

## Phase 1: Setup
- [x] T001 Hoàn tất spec/clarify/checklist/plan/research/data-model/contracts/quickstart trong thư mục feature; 3 câu trả lời, checklist 10/10; không subagent.
- [x] T002 Công bố candidate provider API trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/{identity,customer,account,audit}/api/; ghi rõ OWN/JOIN, binding, minimal projections và thiếu implementation fail-closed.
- [x] T003 Viết và chạy regression đỏ HTTP khi endpoint chưa có trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminMissingProviderIT.java.

## Phase 2: Foundation
- [x] T004 Tạo migration V6__create_approval_request.sql theo04 trong digitalbanking-backend/src/main/resources/db/migration/; checker != maker, terminal checker/time bắt buộc, APPLIED applied_at bắt buộc; quyền cột tối thiểu, FK staged.
- [x] T005 Tạo model/store admin riêng trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/domain/ApprovalRequest.java và adapter/out/ApprovalJdbcRepository.java; JSONB, INSERT pending, lock FOR UPDATE, conditional decisions, không ghi bảng provider.
- [x] T006 Bổ sung public validation/current/expectedVersion trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/api/{LimitChanges,LimitRules}.java và application services; giữ API cũ, stale check dưới lock.
- [x] T007 Thêm audit facade và C3 độc lập trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/audit/{api/AuditModuleApi.java,application/ApprovalAuditService.java,application/AuditRecorder.java,domain/AuditActionCatalog.java}; C2 bắt buộc rollback, C3 fail không đổi deny.

## Phase 3: US1 Maker
Independent test: POST tạo approval pending/config pending/audit thật cùng commit; thiếu quyền/CSRF/challenge/provider/invalid scope không tạo thay đổi.
- [x] T008 [US1] Viết contract/HTTP test fixtures chỉ trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminHttpIT.java: fake SessionAuthenticator, identity/challenge/staff/customer/account; SQL challenge fixture là test-only.
- [x] T009 [US1] Thực thi normalization và binding trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/LimitApprovalBinding.java; ID string dương, money fixed2/NUMERIC(19,2), VND, optional Instant, reason ≤500 an toàn; actor/action/payload hash không log raw payload.
- [x] T010 [US1] Triển khai maker trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/application/LimitApprovalService.java: staff permission, public scope lookups, requestChallenge ngoài TX; verified consume JOIN + approval/config/audit cùng TX, missing provider503.
- [x] T011 [US1] Triển khai controller maker/DTO trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/in/LimitAdminController.java; JSON string strict, extra fields reject, 201 ApprovalSummary và Location chuẩn.
- [x] T012 [US1] Kiểm chứng HTTP permission/CSRF/428/binding/invalid/policy/future/missing scope và PostgreSQL atomic create/audit failure trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminHttpIT.java.

## Phase 4: US2 Checker
Independent test: approve/reject/tự duyệt/đã duyệt/cạnh tranh/stale cùng approval-config-audit thật; usage giữ nguyên.
- [x] T013 [US2] Triển khai checker service/controller trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/{application/LimitApprovalService.java,adapter/in/LimitAdminController.java}; approvalId path, staff+CHECKER, self409+c3, binding approval/decision, lock/recheck/consume/apply/decide/C2 cuối.
- [x] T014 [US2] Kiểm chứng approve/reject/roles/self/repeat/changed actor/decision payload/expiry/single-use và stale config dưới khóa trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminHttpIT.java.
- [x] T015 [US2] Kiểm chứng hai checker/cùng challenge, lỗi audit SQL rollback approval/config/consume test-fixture, C3 denial tồn tại không có config mutation trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminHttpIT.java; 2 worker tối đa.
- [x] T016 [US2] Kiểm chứng qua endpoint hiệu lực mới với LimitRules/LimitEnforcement thật: hạ trần, usage giữ nguyên, D4/next unused day, từ chối future; ghi GET E3/transfer thật Blocked trong báo cáo.

## Phase 5: US3 Handoff/verification
Independent test: không có bean fake production; thiếu dependency fail-closed; contract vectors và handoff rõ.
- [x] T017 [US3] Hoàn thiện missing-provider tests và validation/binding unit tests tại digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/; không dùng fake để chứng minh identity implementation thật.
- [x] T018 [US3] Cập nhật schema/migration expectations trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/{LimitPersistenceIT,LimitRulesIT}.java và audit unit/writer tests liên quan; giữ kiểm tra FK staged.
- [x] T019 [US3] Đồng bộ D5 trong digitalbanking-docs/docs-common/03. SRS.md, maker binding trong 07. API contracts.md và candidate contract mapping trong docs-common/design/05. Modules contract.md; không ghi owner review giả.
- [x] T020 Chạy scoped admin/limit/audit-writer/security/architecture và package build digitalbanking-backend/; XML không failure/error/skip; không full regression không liên quan.
- [x] T021 Cập nhật README admin/limit và digitalbanking-docs/docs-backend/modules/limit/E4-verification.md tiếng Việt: phần thật/mock, Blocked dependencies/owners/điều kiện nghiệm thu, lệnh/kết quả rõ.
- [x] T022 Tự chạy speckit-converge theo spec/plan/tasks hiện tại, sửa task bổ sung đến sạch, ghi kết quả vào E4-verification.md; không gọi subagent/commit/push.

## Dependencies
T001→T002/T003→T004–T007→US1 T008–T012→US2 T013–T016→US3 T017–T019→T020–T022. Test trước implementation tương ứng. Các test fixtures và docs có thể chuẩn bị độc lập, nhưng không chạy nhiều Gradle/Docker suite song song; cùng file sửa tuần tự.

## Phạm vi chưa nghiệm thu toàn luồng
Production session/step-up/staff/customer/account, provider-owner review của candidate contracts, FK identity/historical approval, GET E3/transfer/ledger thật còn Blocked. Chỉ tích hợp những implementation hiện có và approval LIMIT_CHANGE mới; fake ở test không đóng các acceptance trên.


## Phase 6: Convergence

- [x] T023 Hoàn thiện vector purpose/cross-approval challenge và wire type (Instant/enum) trong digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/LimitAdminHttpIT.java; service chỉ trả challenge SENSITIVE_ACTION hợp lệ, payload effectiveFrom phải JSON string; sửa source tương ứng nếu regression fail theo FR-001/FR-003/FR-011 và plan:5/6/9 (partial, HIGH).
- [x] T024 Chỉ trả result khi approval APPLIED trong digitalbanking-backend/src/main/java/com/simulatedbank/banking/admin/adapter/in/LimitAdminController.java; thêm assertion reject không có result theo FR-001, US2/AC2 và 07 ApprovalDetail result nếu đã áp dụng (partial, MEDIUM).
