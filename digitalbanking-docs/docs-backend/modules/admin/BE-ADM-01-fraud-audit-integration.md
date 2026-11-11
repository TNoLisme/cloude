# BE-ADM-01 — Tích hợp vận hành fraud/audit vào admin

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-ADMIN-01; **Task:** BE-ADM-01  
> **Phiên bản:** 0.1; **Ngày:** 11/11/2026  
> **Trạng thái:** Draft — chờ review; **Implementation:** Planned; **Verification:** Not verified  
> **Người đề xuất và thực hiện phần task:** Thịnh; **Owner module admin:** Đạt  
> **Reviewer đề xuất:** Đạt, Vũ; chưa ghi nhận approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §2–§6; [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.6–8.8; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.2–5.6; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] Appendix A.14/B.7.

## 1. Phạm vi và cách chủ động làm trước

Đầu việc của Thịnh là đề xuất và kiểm thử phần vận hành fraud/audit, không nhận cả module admin của Đạt. Liên kết FR-032/033/048–051, AC-020/026/027/028. Thịnh viết contract, permission matrix, use-case và fake caller trước; có thể code adapter/test thuộc phần được đề xuất mà không chờ Đạt hoàn thành module. Đến lúc ghép code dùng chung mới review ownership và contract để tránh hai người tạo trùng controller/service.

**Đề xuất chia công việc:**
- Thịnh cung cấp audit writer/query/export; fraud list/review; các fixture và integration tests cho audit atomicity/RBAC; thiết kế port legal hold ở BE-AUD-03.
- Đạt giữ authentication/authorization thực, approval_request, role/limit orchestration và endpoint admin hiện hữu.
- Thắng dùng endpoint audit/fraud hiện có cho trang vận hành. Không thêm REST proxy admin chỉ để gọi lại REST cùng application.
- Bảng nào do module nào ghi vẫn giữ nguyên dù Thịnh viết code ở nhiều module. `audit` ghi audit_log, `fraud` ghi fraud_flag, `admin` ghi approval_request.

## 2. Interface, file map và dependency

Các file dự kiến của task này:
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminAuditAtomicityIT.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminOperationsContractTest.java`
- `digitalbanking-backend/src/test/java/com/simulatedbank/banking/admin/AdminMakerCheckerIT.java`
- Provider production code nằm trong `audit/api`, `fraud/application`, `audit/application`; caller admin chỉ bổ sung vào application service Đạt dùng khi ghép. Không tạo thêm một controller cùng URL.

Hợp đồng đề xuất:
- Admin gọi `AuditModuleApi.recordInTransaction(AuditEvent)` từ BE-AUD-01 sau các state writes. Audit failure làm rollback approval và mutation.
- C3 access denied dùng `recordStandalone` sau khi transaction đang giữ lock đã kết thúc; không rollback security control.
- Query audit dùng DTO của BE-AUD-02; review fraud dùng `FraudReviewService.review(long flagId, FraudReviewRequest, actorContext)` của BE-FRD-01. Đây là service nội bộ, không tái gửi HTTP qua localhost.
- Identity cung cấp principal/permission/step-up qua contract của Đạt; fake principal/port chỉ ở `src/test`, không trở thành production fallback cấp quyền.

## 3. Data ownership và SQL

Task không sở hữu schema mới. `approval_request` thuộc admin; audit/flags thuộc task provider. Không thêm `FRAUD_REVIEW` hoặc `LEGAL_HOLD` vào approval enum hiện tại một cách ngầm định: OpenAPI đang có `LIMIT_CHANGE`, `ROLE_CHANGE`; trạng thái `PENDING/APPROVED/REJECTED/APPLIED/CANCELLED`.

Để test admin concurrency dùng 2 transaction/connection PostgreSQL thật. Admin khóa approval (`FOR UPDATE NOWAIT`), validate PENDING/maker/checker, gọi mutation owner port, cuối cùng ghi audit C2. Exact thứ tự challenge/target locks theo spec admin/identity lúc tích hợp; không cho provider audit lấy lock rồi quay lại khóa approval.

## 4. REST, quyền và lỗi

| Hành động | Contract hiện có | Quyền | Trách nhiệm Thịnh |
|---|---|---|---|
| Audit search/detail | `/api/v1/audit`, `/audit/{auditId}` | Operations/Admin/Auditor với AUDIT_READ | Provider và tests |
| Audit export | `/api/v1/audit/export` | Auditor với AUDIT_EXPORT | Provider; C2 trước gửi file |
| Fraud list | `/api/v1/fraud-flags` | Operations FRAUD_REVIEW; Admin/Auditor FRAUD_READ | Provider và role matrix test |
| Fraud review | `/api/v1/fraud-flags/{id}/review` | Operations FRAUD_REVIEW | Provider; không cấp write cho Admin/Auditor |
| Approval read | `/api/v1/admin/approvals`, `/{approvalId}` | Theo `07`/OpenAPI và Đạt | Tests collaboration, không tạo endpoint trùng |
| Role/limit approval | `/api/v1/admin/roles`, `/admin/limits` và approve tương ứng | Maker/checker + sensitive-action step-up | Audit contract/atomicity tests |

Body review ví dụ hợp lệ: `{"decision":"BLOCK","notes":"Kết quả kiểm tra dữ liệu mô phỏng"}`. Notes optional tối đa 500 theo OpenAPI, không đổi thành required; không copy raw notes vào audit.

Fraud flag OPEN/UNDER_REVIEW mới được quyết định; terminal trả `409 FRAUD_FLAG_ALREADY_CLOSED`. Review BLOCK chỉ đổi flag; không đảo transfer/ledger đã hoàn tất. **Không áp maker-checker/step-up mới lên fraud review v1** vì endpoint không khai báo. Maker-checker áp vào approval role/limit theo contract, không áp mọi thao tác admin một cách chung chung.

Self-approval phải 403 theo API contract; error code lấy từ ErrorCode hiện hành, không lấy `MAKER_CHECKER_VIOLATION` ở overview nếu chưa có trong catalog. Cookie mutation phải kiểm tra CSRF; bearer-test không được dùng để chứng minh cookie-CSRF đã đúng.

## 5. Luồng và failure matrix

1. Principal thật hoặc fixture test được xác thực; endpoint kiểm tra permission, service kiểm tra data scope/state.
2. Với fraud review: lock flag; validate transition; update decision/status/reviewer/time; audit C2 cuối transaction; commit.
3. Với approval: kiểm tra step-up và maker khác checker theo contract Đạt; mutation qua owner port; audit cuối cùng; commit.
4. Nếu audit fail, rollback toàn bộ mutation; response không báo success. Nếu unauthorized, không chạm state và ghi C3 an toàn.
5. Khi 2 request review/approve cạnh tranh, một terminal transition thắng; request còn lại nhận lock conflict hoặc already-decided theo contract, không cập nhật lần hai.

## 6. Audit, metrics và PII

Audit actions: FRAUD_FLAG_REVIEWED, LIMIT_CHANGE_REQUESTED/APPROVED/REJECTED, ROLE_CHANGE_REQUESTED/APPROVED/REJECTED là C2; SECURITY_ACCESS_DENIED là C3. Snapshot role tại thời điểm xử lý, không tra role hiện tại để viết lại lịch sử. Chỉ lưu approvalId/target/status/decision/flagId/ruleVersion theo allowlist; không notes, OTP, session token. Metrics đếm outcome theo loại use case, không gắn user/approval/transfer ID.

## 7. Thứ tự thực hiện và acceptance

- [ ] Viết permission matrix và fixtures trước; báo Đạt danh sách provider/caller đề xuất, không đợi phân việc mới làm docs.
- [ ] Dùng provider của BE-AUD-01/02 và BE-FRD-01; test fake admin application transaction để chứng minh C2 propagation trước.
- [ ] `AdminOperationsContractTest`: Customer bị chặn; Operations review được; Admin/Auditor chỉ đọc fraud; Auditor export được; notes optional; unknown decision trả 400.
- [ ] `AdminAuditAtomicityIT`: forced audit failure rollback mutation và approval; không có audit success rời.
- [ ] `AdminMakerCheckerIT`: maker tự duyệt bị chặn; missing/used challenge không thay state; 2 checker dùng connections/latch chỉ một kết quả terminal; ghép identity/limit thật trước khi claim end-to-end.
- [ ] Review actual ports/controllers với Đạt, sửa proposal tương ứng; regression audit/fraud và endpoint liên quan, không run cả suite thiếu mục đích.

Lệnh dự kiến từ Backend: `./gradlew test --tests '*admin.AdminOperationsContractTest' --tests '*admin.AdminAuditAtomicityIT' --tests '*admin.AdminMakerCheckerIT'`. Chưa có class ở thời điểm viết. [Report](BE-ADM-01-verification-report.md) phân biệt harness/fake và integration thật.

## Proposal và handoff

ADM-P01: chia provider/caller như §1. ADM-P02: giữ nguyên fraud review contract v1, không thêm approval money. ADM-P03: test approval qua implementation Đạt thay vì viết bản thứ hai. Cả ba là Proposed do Thịnh soạn; chưa có xác nhận của Đạt/Vũ. Local code/test theo proposal được làm trước; chỉ đánh dấu Integrated sau khi ghép và test contracts thật.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Đề xuất ranh giới admin, permission matrix và kế hoạch integration tests |
