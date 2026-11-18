# BE-ADM-01 — Đặc tả Tích hợp Admin với Fraud, Audit và Identity

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-ADMIN-01` (`Task: BE-ADM-01`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành đặc tả — chờ ghép runtime `admin`
> **Owner `admin`:** Đạt · **Đồng phát triển phần `fraud`/`audit`:** Thịnh · **Reviewer:** Đạt, Vũ, Phương
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]], [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3.4, §8.5–§8.8), [[digitalbanking-docs/openapi/digital-banking-v1.yaml]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§5.3, §11, §12.1), [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[DECISIONS.md]] (`DEC-ID-001`, `DEC-ID-011`)

---

## 1. Phạm vi và Phân định Quyền Sở hữu (`Ownership`)

- **Đạt (`admin`, `identity`, `customer`, `account`):** Sở hữu bảng `banking.approval_request`, điều phối luồng Maker-Checker (`API-LIM-02/03`, `API-ROL-01/02`, `API-APR-01/02`), các API tra cứu vận hành (`API-ADM-01/02`) và port `IdentityRoleCommand` (`DEC-ID-011`).
- **Thịnh (`audit`, `fraud`):** Sở hữu bảng `banking.audit_log`, `banking.fraud_rule_config`, `banking.fraud_flag`, cung cấp writer/query kiểm toán (`BE-AUD-01/02`) và dịch vụ thẩm định cờ gian lận (`FraudReviewService` tại `BE-FRD-01`).
- **Quy tắc bất biến (`BE-CON-15`):** Tuyệt đối không ghi chéo giữa các bảng `approval_request`, `fraud_flag`, `audit_log` và các bảng của `identity` / `limit`. Mọi thao tác liên module bắt buộc gọi qua các Internal Ports trong gói `<module>.api`.

---

## 2. Tái sử dụng Nền tảng `common` và `identity`

- **`SecurityFilterChainConfig` & `IdentitySessionAuthenticator`:** Cung cấp kiểm soát quyền truy cập ở tầng HTTP, xác thực phiên `DB_SESSION` / Bearer token, kiểm tra `X-CSRF-Token` và nạp danh sách quyền (`authorities`) theo từng request (`DEC-ID-004`).
- **`RequestContextHolder`:** Cung cấp `correlationId` và `requestId` đã xác thực để truyền xuyên suốt vào `AuditEvent` và các bảng nghiệp vụ.
- **`ErrorCode` & `ProblemWriter` / `GlobalExceptionHandler`:** Dùng chung danh mục 46 mã lỗi chuẩn của `07` §4.3 (`ROLE_CONFLICT`, `APPROVAL_NOT_FOUND`, `APPROVAL_ALREADY_DECIDED`, `FRAUD_FLAG_NOT_FOUND`, `FRAUD_FLAG_ALREADY_CLOSED`, `AUDIT_NOT_FOUND`, `EXPORT_TOO_LARGE`, ...); không tạo mô hình lỗi riêng.
- **`PiiMaskingUtils`:** Hỗ trợ che dấu dữ liệu nhạy cảm; từng module vẫn chịu trách nhiệm kiểm soát allowlist các trường trong DTO và snapshot kiểm toán.

---

## 3. Hợp đồng Tích hợp Nội bộ (`Internal Integration Contracts`)

1. **Ghi nhận kiểm toán nguyên tử `C2` (`MANDATORY`):**
   - Mọi thay đổi trạng thái nhạy cảm trong `admin` (`LIMIT_CHANGE_REQUESTED`, `LIMIT_CHANGE_APPROVED`, `LIMIT_CHANGE_REJECTED`, `ROLE_CHANGE_REQUESTED`, `ROLE_CHANGE_APPROVED`, `ROLE_CHANGE_REJECTED`) và trong `fraud` (`FRAUD_FLAG_REVIEWED`) bắt buộc gọi `AuditRecorder` / `AuditModuleApi.recordInTransaction` ở bước cuối cùng của giao dịch sau khi đã `flush` thay đổi nghiệp vụ.
   - Nếu ghi audit `C2` thất bại, toàn bộ giao dịch phê duyệt hoặc thẩm định cờ gian lận phải bị rollback (`fail-closed`).
2. **Ghi nhận sự kiện từ chối truy cập `C3` (`REQUIRES_NEW` / Fallback):**
   - Khi phát hiện truy cập trái phép hoặc Maker cố tình tự phê duyệt đề xuất của chính mình, hệ thống từ chối yêu cầu (`403 AUTH_FORBIDDEN` hoặc `409 ROLE_CONFLICT`) và ghi nhận sự kiện bảo mật `C3` (kèm fallback qua `SecurityAuditFallbackLog` nếu DB lỗi) mà không làm rollback quyết định từ chối.
3. **Thẩm định cờ gian lận (`Fraud Review`):**
   - Endpoint `GET /api/v1/fraud-flags` (`API-FRD-01`) và `POST /api/v1/fraud-flags/{id}/review` (`API-FRD-02`) ủy quyền trực tiếp cho `FraudReviewService` của module `fraud` (khóa dòng `FOR UPDATE NOWAIT`, kiểm tra trạng thái `OPEN`/`UNDER_REVIEW` và ánh xạ quyết định `ALLOW -> ALLOWED`, `BLOCK -> BLOCKED`, `ESCALATE -> UNDER_REVIEW`, `CLOSE -> CLOSED`).
   - Việc thẩm định cờ gian lận sau giao dịch không tự động hoàn tất hay đảo ngược giao dịch chuyển tiền đã kết thúc.
4. **Phê duyệt thay đổi phân quyền (`ROLE_CHANGE` — `DEC-ID-011`):**
   - Khi Maker (`ADMIN` có `ROLE_MANAGE`) tạo yêu cầu `POST /api/v1/admin/roles`, `admin` đọc phiên bản hiện tại qua `IdentityRoleCommand.getRoleAssignmentVersion(targetUserId)` và lưu vào payload của `approval_request`.
   - Khi Checker (`ADMIN` có `ROLE_MANAGE` + đặc quyền `CHECKER`, khác Maker và khác `targetUserId`) gọi `POST /api/v1/admin/roles/{id}/approve` kèm `X-Step-Up-Challenge` (`SENSITIVE_ACTION`), `admin` khóa dòng `approval_request` (`FOR UPDATE NOWAIT`), tiêu thụ challenge qua `StepUpChallengePort.consumeVerified`, gọi `IdentityRoleCommand.applyApprovedRoleChange` (`MANDATORY`), cập nhật `approval_request.status = 'APPLIED'` và ghi audit `ROLE_CHANGE_APPROVED` (`C2`) trong cùng một giao dịch.

---

## 4. Ma trận Phân quyền RBAC Chuẩn (`07` §3.4, `DEC-ID-001`)

| Nhóm API | Endpoint | Quyền truy cập (`Role` / `Permission`) |
|---|---|---|
| Tra cứu & xuất Audit | `GET /api/v1/audit`, `GET /api/v1/audit/{auditId}`<br>`GET /api/v1/audit/export` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`AUDIT_READ`)<br>Chỉ **`AUDITOR`** (`AUDIT_EXPORT`) |
| Tra cứu & thẩm định Fraud | `GET /api/v1/fraud-flags`<br>`POST /api/v1/fraud-flags/{id}/review` | `OPERATIONS` (`FRAUD_REVIEW`), `ADMIN` / `AUDITOR` (`FRAUD_READ`)<br>Chỉ **`OPERATIONS`** (`FRAUD_REVIEW`) |
| Quản lý Hạn mức (Maker-Checker) | `POST /api/v1/admin/limits`<br>`POST /api/v1/admin/limits/{id}/approve` | Maker: `OPERATIONS` (`LIMIT_MAKER`) hoặc `ADMIN` (`LIMIT_MANAGE`)<br>Checker: quyền tương ứng + đặc quyền **`CHECKER`** |
| Quản lý Phân quyền (Maker-Checker) | `POST /api/v1/admin/roles`<br>`POST /api/v1/admin/roles/{id}/approve` | Maker: `ADMIN` (`ROLE_MANAGE`)<br>Checker: `ADMIN` (`ROLE_MANAGE` + đặc quyền **`CHECKER`**) |
| Tra cứu Yêu cầu Phê duyệt | `GET /api/v1/admin/approvals`, `GET /api/v1/admin/approvals/{approvalId}` | `OPERATIONS`, `ADMIN` |
| Tra cứu Khách hàng & Tài khoản | `GET /api/v1/admin/customers/{customerId}`<br>`GET /api/v1/admin/customers/{customerId}/accounts` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`CUSTOMER_READ` / `ACCOUNT_READ`) |

---

## 5. Yêu cầu Kiểm thử & Nghiệm thu

- Kiểm thử trên PostgreSQL 16 Testcontainers cho các kịch bản: hai Checker bấm duyệt đồng thời (`FOR UPDATE NOWAIT` đảm bảo đúng 1 người thắng, người còn lại nhận `409 APPROVAL_ALREADY_DECIDED`), hai nhân viên vận hành thẩm định cùng một `fraud_flag` đồng thời (`409 FRAUD_FLAG_ALREADY_CLOSED`), lỗi ghi audit `C2` làm rollback toàn bộ thay đổi quyền/hạn mức/fraud review, và Maker tự duyệt bị từ chối với `409 ROLE_CONFLICT` (`AC-028`, `CT-API-016`).

---

## 6. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1` | 11/11/2026 | Khởi tạo đề xuất phối hợp tích hợp giữa `admin`, `fraud` và `audit`. |
| `v0.2` | 12/11/2026 | Đồng bộ ranh giới bảo mật, ngữ cảnh và mã lỗi với module `common`. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt, đồng bộ ma trận phân quyền `DEC-ID-001` và giao thức `IdentityRoleCommand` (`DEC-ID-011`). |
