# Module `admin` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-ADMIN-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đang phát triển (Skeleton — đã chốt hợp đồng Maker-Checker, `IdentityRoleCommand` & `BE-ADM-01`)
> **Owner chính:** Đạt (**Thịnh** đồng phát triển phần tích hợp `fraud` / `audit`)
> **Phối hợp / Reviewer:** Vũ (`limit`), Phương (`common`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-032, FR-033, FR-049..051, AC-028), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.5.3), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3.4, §6.5, §8.5, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Phụ lục A.14, B.7), [[digitalbanking-docs/docs-common/design/04. security.md]] (§5.3), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§5.3, §12.1), [[digitalbanking-docs/docs-backend/modules/_overview/10-admin.md]], [[DECISIONS.md]] (`DEC-ID-001`, `DEC-ID-011`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.admin` điều phối các nghiệp vụ quản trị và vận hành nội bộ dành cho nhân viên ngân hàng (`OPERATIONS`, `ADMIN`, `AUDITOR`), bao gồm:
- Quản lý vòng đời yêu cầu phê duyệt 4 mắt (**Maker-Checker / Dual Control**) trên bảng `banking.approval_request` cho thay đổi hạn mức (`LIMIT_CHANGE`), thay đổi phân quyền nhân viên (`ROLE_CHANGE`) và giữ/giải phóng pháp lý (`LEGAL_HOLD`).
- Điều phối gọi các Internal Ports của `identity` (`StepUpChallengePort`, `IdentityRoleCommand`), `limit` (`LimitConfigMutationPort`), `customer` (`CustomerQuery`), `account` (`AccountQuery`) và `audit` (`AuditModuleApi`).

---

## 2. Hiện trạng Triển khai trong `digitalbanking-backend/src/main`

- Package `com.simulatedbank.banking.admin` hiện ở trạng thái khung cấu trúc 5 lớp chuẩn (`api`, `adapter/in`, `adapter/out`, `application`, `domain`).
- Phía provider `identity` đã hoàn tất `IdentityRoleCommand` / `IdentityRoleCommandService` (`getRoleAssignmentVersion`, `applyApprovedRoleChange` kèm thu hồi toàn bộ phiên `ACTIVE`) và migration `V4__add_role_assignment_version.sql` với 45 bài kiểm thử tích hợp PostgreSQL (`BE-ID-IMP-11`, `DEC-ID-011`).
- Phía `audit` và `fraud` đã hoàn tất các thành phần cốt lõi (`AuditRecorder`, `AuditQueryService`, `FraudReviewService`) sẵn sàng để ghép nối theo đặc tả `BE-ADM-01`.

---

## 3. Bảng Sở hữu & Hợp đồng Giao tiếp Nội bộ

- **Bảng sở hữu độc quyền (`Write-Owner`):** `banking.approval_request` (`04` §4.5.3, migration `V9` theo `DEC-COM-001`), với trạng thái `status IN ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED')` và ràng buộc `CHECK (checker_user_id IS NULL OR maker_user_id <> checker_user_id)`.
- **Các Port phụ thuộc mà `admin` gọi:**
  1. `identity.api.StepUpChallengePort.consumeVerified`: Tiêu thụ challenge `SENSITIVE_ACTION` (`X-Step-Up-Challenge`) trong giao dịch phê duyệt.
  2. `identity.api.IdentityRoleCommand`: Đọc `getRoleAssignmentVersion(targetUserId)` khi Maker tạo yêu cầu và gọi `applyApprovedRoleChange(ApprovedRoleChange)` (`MANDATORY`) khi Checker phê duyệt (`DEC-ID-011`).
  3. `limit.api.LimitConfigMutationPort`: Áp dụng cấu hình hạn mức mới trong cùng giao dịch phê duyệt `LIMIT_CHANGE`.
  4. `customer.api.CustomerQuery` & `account.api.AccountQuery`: Tra cứu hồ sơ và tài khoản khách hàng (`API-ADM-01/02`).
  5. `audit.api.AuditModuleApi`: Ghi sự kiện kiểm toán `C2` (`LIMIT_CHANGE_*`, `ROLE_CHANGE_*`) và `C3` (`SECURITY_ACCESS_DENIED`).

---

## 4. Danh mục Tài liệu trong Thư mục `modules/admin/`

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [Tóm tắt Module 10: `admin`](../_overview/10-admin.md) | Tổng quan phạm vi, bảng `approval_request`, REST API `API-LIM-02/03`, `API-ROL-01/02`, `API-APR-01/02`, `API-ADM-01/02` và Internal Ports. | Hoàn thành |
| [BE-ADM-01 — Tích hợp Admin với Fraud và Audit](BE-ADM-01-fraud-audit-integration.md) | Đặc tả ranh giới sở hữu, phân quyền RBAC (`OPERATIONS`, `ADMIN`, `AUDITOR` + đặc quyền `CHECKER`), tính nguyên tử của kiểm toán `C2` và quy tắc không ghi chéo bảng. | Hoàn thành đặc tả |
| [BE-ADM-01 — Báo cáo Kiểm chứng](BE-ADM-01-verification-report.md) | Ma trận kiểm chứng tích hợp `admin` – `fraud` – `audit` – `identity`. | Đã lên kế hoạch (`Planned` — chờ ghép runtime `admin`) |
| [Quy trình phối hợp nhóm Audit/Fraud/Notification/Admin](../../workofteam/thinh-backend-workflow.md) | Thứ tự triển khai và quy tắc kiểm chứng liên module. | Hoàn thành |
