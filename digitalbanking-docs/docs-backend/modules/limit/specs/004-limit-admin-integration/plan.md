# Implementation Plan: LIM-09/10 — endpoint và approval LIMIT_CHANGE

**Branch**: `phuong/limit` | **Date**: 2026-11-16 | **Spec**: [spec.md](spec.md)

## Summary

Triển khai thật hai endpoint maker/checker và persistence LIMIT_CHANGE trong admin; nối public API của limit/audit. Identity/customer/account cung cấp candidate interface theo design/05, chưa có implementation production nên fail-closed khi thiếu; fake chỉ trong test. Giữ quyết định policy E4 cũ và ba clarify mới. Tự converge, không subagent theo yêu cầu user.

## Technical Context

Java 21, Spring Boot/MVC/Security/JDBC/JPA transaction manager hiện có; PostgreSQL 16/Flyway; Jackson/JCS và SHA-256 cho binding. Không dependency mới. Kiểm thử JUnit/MockMvc/Testcontainers, tối đa 2 worker; không full regression không liên quan.

## Constitution Check

Constitution là template chưa điền: bỏ qua placeholder. Áp dụng AGENTS.md, docs-common 01–11 > design/05 > backend. Gate trước/sau thiết kế PASS: user đồng ý scope approval thật tối thiểu, D5 409, maker binding; không generic approval platform/identity engine/E3/transfer mới. Không có extensions.yml. Nghiên cứu do agent chính thực hiện để tuân thủ lượt làm không subagent.

## Project Structure

Hồ sơ ở thư mục này: spec/plan/research/data-model/contracts/quickstart/tasks/checklists.

Trong digitalbanking-backend/:
- admin/adapter/in/LimitAdminController.java: hai POST; wire DTO tách nội bộ, ID/Money string strict, actor từ principal, trace từ RequestContext.
- admin/application/LimitApprovalService.java: permission/scope/step-up, binding, transaction, approval/config/audit, response facts.
- admin/adapter/out/ApprovalJdbcRepository.java và admin/domain/ApprovalRequest.java: dữ liệu riêng của admin.
- admin/application/LimitApprovalBinding.java: normalize/validate nội dung và JCS SHA-256 binding; không log secret/PII.
- identity/api/StepUpChallengePort.java và StaffDirectory.java; customer/api/CustomerQuery.java; account/api/AccountQuery.java: public provider contracts, không có bean fake production.
- audit/api/AuditModuleApi.java và audit/application/ApprovalAuditService.java: facade provider-owned cho C2 approval và C3 deny; tái sử dụng writer thật.
- AuditRecorder: thêm recordStandalone C3 REQUIRES_NEW và chia sẻ đường append có kiểm chứng; mở đúng catalog SECURITY_ACCESS_DENIED tối thiểu (không retention contract).
- limit/api/LimitChanges.java, LimitRules.java và application services: validate policy preflight, đọc current theo scope, approve có expected current version dưới cùng khóa; giữ phương thức cũ tương thích.
- db/migration/V6__create_approval_request.sql: schema chuẩn 04 §4.5.3, quyền cột tối thiểu; không tạo bảng customer/identity giả, FK liên module còn staged.
- src/test/.../admin/*: HTTP/PostgreSQL thật, fake ports test-only và shared contract vectors; unit test missing provider và normalization.
- Test LimitPersistenceIT/LimitRulesIT: cập nhật migration count và khẳng định schema có approval thật; không bỏ kiểm tra FK staged.
- Docs: SRS D5, API §6.5 maker binding, design/05 contract mapping/candidate state, README module, E4-verification tiếng Việt.

## Design decisions

1. Admin là write-owner approval, chỉ workflow LIMIT_CHANGE. Chuẩn ID/JSONB/status/maker/checker/timestamps theo 04; không thêm GET approvals/roles/retention API. Payload lưu current/proposed/reason đã kiểm tra, không chứa auth token/challenge secret. Public ActorSummary lấy displayName từ StaffDirectory, không bịa dữ liệu production.
2. Service entrypoint không có outer transaction. Validate/auth/scope và requestChallenge chạy trước transaction; requestChallenge OWN. Có header verified thì TransactionTemplate READ COMMITTED: consumeVerified JOIN → insert approval → LimitChanges.prepare → snapshot current/update payload → C2 audit cuối → commit. Không dùng fake production khi thiếu port; ObjectProvider.getIfAvailable vắng mặt trả SERVICE_UNAVAILABLE.
3. Decision đọc approval preflight, kiểm staff/CHECKER và maker != checker; self-denial ghi C3 riêng rồi trả 409 ROLE_CONFLICT. Challenge binding dựa actor/action/approvalId/decision+note+proposal hash. Trong TX lock approval FOR UPDATE, recheck pending/payload, consume challenge, gọi limit, cập nhật APPLIED/REJECTED và C2 audit cuối. Lost race không consume challenge. Rejection không apply và giữ usage.
4. Consumer không import implementation/entity/repository provider khác. Limit API thêm validateChange, current theo scope và approve(expectedCurrentVersion); kiểm stale version trong shared customer/account lock. Null expected version nghĩa chưa có current effective config, không phải bỏ qua kiểm tra. Hai-param approve cũ giữ hành vi provider E4 đã nghiệm thu.
5. Maker hash normalize ID string, money 2 decimals, VND, optional effectiveFrom=IMMEDIATE sentinel, reason trim/null→rỗng, actor và action; checker hash gồm approval ID, quyết định/note và hash payload approval. JCS + SHA-256 UTF-8; truyền binding cho identity, không lưu raw secret trong approval/audit/log. Không admin idempotency mới.
6. StepUpChallengePort công bố requestChallenge và consumeVerified; DTO identity sở hữu, purpose SENSITIVE_ACTION, single-use/actor/binding/expiry/rollback obligation. Fake test cung cấp challenge VERIFIED và lưu consume ở test schema để chứng minh caller TX wiring; không tuyên bố OTP hoặc identity implementation thật đã đạt. Provider API candidate cần owner review, không đánh dấu đã được các owner chốt.
7. CustomerQuery kiểm tồn tại qua projection tối thiểu; AccountQuery trả ID/customer/currency đủ admin kiểm ownership. StaffDirectory cung cấp safe displayName. Không tự thêm rule ACTIVE/KYC ngoài nguồn cho cấu hình hạn mức. GET E3/transfer thật vẫn Blocked; kiểm tác động qua LimitRules/LimitEnforcement thật.
8. Chính sách tiền, future-effective refusal, D4 và NOT_CONFIGURED của E4 giữ nguyên. Preflight validation dùng public limit API, approve tái kiểm tra bên trong. Giữ usage, không backfill/reset; lowered limit chặn E2 tiếp theo.
9. Wire DTO chỉ nhận JSON string cho ID/Money, exact 2 decimals theo 07; reject field ngoài hợp đồng, số/format sai, overflow ID, số tiền không hợp lệ, note >500. reason/note chỉ nhận text an toàn, từ chối control characters và mẫu secret/email/số định danh dài; không đưa free text vào audit hay logs. Không khẳng định bộ lọc phát hiện mọi PII; contract cấm caller gửi PII/secret.
10. Audit facade C2 dùng AuditRecorder MANDATORY thật. C3 security denial dùng writer REQUIRES_NEW, nếu writer lỗi fallback log an toàn + metric, vẫn deny. Không ghi business config khi tự duyệt. Không giả C1/C2 thành best-effort.
11. Migration chỉ cấp app SELECT/INSERT và UPDATE payload/status/checker/decision timestamps/note; không DELETE hay sửa maker/target/request type. Không rewrite V1–V5. FK approval→identity và limit→approval vẫn chưa validate vì reference lịch sử/identity thật chưa sẵn sàng; actual admin transaction chứng minh liên kết cho các request mới.
12. Verify HTTP happy/deny/CSRF/428/binding/replay/missing scope, PostgreSQL rollback/audit failure/concurrent checker/stale config, native limit providers and readonly usage, migration/permission contracts. Fake-only claims tách khỏi DB atomicity. Chạy scoped admin/limit/security filter/audit writer/architecture cần thiết rồi package build, tự converge đến sạch.

## Implementation strategy

Public candidate contracts + test vectors → migration/real admin store → maker endpoint + PostgreSQL tests → checker + concurrency/audit tests → missing provider fail-closed tests → targeted regression/build → Vietnamese evidence/dependency handoff → self-converge. Không tự commit/push.
