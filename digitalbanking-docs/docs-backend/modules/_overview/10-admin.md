# Module 10: `admin` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-10`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.admin`
> **Owner chính:** **Đạt** (**Thịnh** đồng phát triển phần tích hợp fraud/audit & review; Phối hợp: Vũ — `limit`, Thắng — FE)
> **Mốc tập trung:** **Tuần 5 (W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-032, FR-033, FR-049..051, AC-028), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.5.3), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.5), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3.4, §6.5, §8.5, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (Phụ lục A.14, B.7), [[digitalbanking-docs/docs-common/design/04. security.md]] (§5.3), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§5.3, §12.1), [[DECISIONS.md]] (`DEC-ID-001`, `DEC-ID-011`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `admin` cung cấp các chức năng vận hành dành cho nhân viên ngân hàng thuộc 3 role nội bộ chuẩn (**`OPERATIONS`**, **`ADMIN`**, **`AUDITOR`**) và điều phối quy trình phê duyệt 4 mắt (**Maker-Checker / Dual Control** với đặc quyền **`CHECKER`**) cho các thay đổi cấu hình nhạy cảm: thay đổi hạn mức giao dịch (`LIMIT_CHANGE`), thay đổi phân quyền nhân viên (`ROLE_CHANGE`) và phê duyệt giữ/giải phóng pháp lý (`LEGAL_HOLD`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `admin` sở hữu độc quyền bảng **`banking.approval_request`** (`04. Database design & analysis.md` §4.5.3, migration `V9` theo `DEC-COM-001`):

| Cột chính | Kiểu & Ràng buộc | Quy tắc & Bất biến Maker-Checker |
|---|---|---|
| `approval_request_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PK` | Định danh yêu cầu phê duyệt. |
| `request_type` | `VARCHAR(32) NOT NULL CHECK IN ('LIMIT_CHANGE','ROLE_CHANGE','LEGAL_HOLD_RELEASE', ...)` | Loại thay đổi nhạy cảm cần kiểm soát kép. |
| `target_type`, `target_id` | `VARCHAR(32) NOT NULL`, `VARCHAR(64) NOT NULL` | Đối tượng chịu tác động (`LIMIT_CONFIG`, `APP_USER`, ...) và định danh mục tiêu. |
| `payload` | `JSONB NOT NULL` | Chi tiết đề xuất thay đổi (chứa cả snapshot trước, phiên bản `expectedVersion` và giá trị đề xuất mới). |
| `status` | `VARCHAR(16) NOT NULL CHECK IN ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED')` | Trạng thái vòng đời yêu cầu phê duyệt (`04` §4.5.3). Khi Checker duyệt và áp dụng thành công trong cùng giao dịch, trạng thái chuyển sang `APPLIED` (`DEC-ID-011`). |
| `maker_user_id`, `maker_reason` | `BIGINT NOT NULL FK -> app_user`, `VARCHAR(500)` | Nhân viên khởi tạo đề xuất (`Maker`) và lý do đề xuất. |
| `checker_user_id`, `checker_reason` | `BIGINT NULL FK -> app_user`, `VARCHAR(500)` | Nhân viên phê duyệt/từ chối (`Checker`, bắt buộc có đặc quyền `CHECKER`) và lý do quyết định. |
| `step_up_challenge_id` | `BIGINT NULL FK -> auth_challenge` | Challenge Step-up (`purpose = 'SENSITIVE_ACTION'`) bắt buộc khi thực hiện thao tác nhạy cảm. |
| `correlation_id`, `created_at`, `decided_at`, `applied_at` | `VARCHAR(64)`, `TIMESTAMPTZ` | Mã truy vết và các mốc thời gian tạo, quyết định và áp dụng. |

- **Database Constraint bảo vệ nguyên tắc 4 mắt (`INV-MC-01`):** `CHECK (checker_user_id IS NULL OR maker_user_id <> checker_user_id)`.
- **Partial Unique Index chống đề xuất trùng lặp:** Chỉ cho phép tối đa 1 yêu cầu `status = 'PENDING'` trên cùng một `(request_type, target_type, target_id)`.

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §3.4, §8.5, §8.8)

| Mã API | Method & Path | Quyền truy cập (`STAFF` — `DEC-ID-001`) | Hành vi cốt lõi |
|---|---|---|---|
| `API-LIM-02` | `POST /api/v1/admin/limits` | Maker: `OPERATIONS` (`LIMIT_MAKER`) hoặc `ADMIN` (`LIMIT_MANAGE`) | Tạo yêu cầu `approval_request (PENDING)` cho `LIMIT_CHANGE`, ghi audit `LIMIT_CHANGE_REQUESTED` (`C2`). |
| `API-LIM-03` | `POST /api/v1/admin/limits/{id}/approve` | Checker: `OPERATIONS` / `ADMIN` có quyền hạn mức + đặc quyền **`CHECKER`** | Kiểm tra `maker_user_id <> checker_user_id` (`409 ROLE_CONFLICT` nếu trùng) + tiêu thụ Step-up challenge (`SENSITIVE_ACTION` qua header `X-Step-Up-Challenge`), gọi `Limit.applyApprovedLimitChange` áp dụng hạn mức mới và cập nhật trạng thái `APPLIED` / `REJECTED`. |
| `API-ROL-01` | `POST /api/v1/admin/roles` | Maker: `ADMIN` (`ROLE_MANAGE`) | Đọc `IdentityRoleCommand.getRoleAssignmentVersion(targetUserId)`, tạo `approval_request (PENDING)` cho `ROLE_CHANGE`, ghi audit `ROLE_CHANGE_REQUESTED` (`C2`). |
| `API-ROL-02` | `POST /api/v1/admin/roles/{id}/approve` | Checker: `ADMIN` (`ROLE_MANAGE` + đặc quyền **`CHECKER`**) | Kiểm tra `maker_user_id <> checker_user_id` và `targetUserId` khác cả hai + tiêu thụ Step-up challenge, gọi `IdentityRoleCommand.applyApprovedRoleChange` cập nhật quyền + thu hồi toàn bộ phiên `ACTIVE` của nhân viên đích (`DEC-ID-011`). |
| `API-APR-01` | `GET /api/v1/admin/approvals` | `OPERATIONS`, `ADMIN` (`LIMIT_MAKER`, `LIMIT_MANAGE`, `ROLE_MANAGE`) | Tra cứu danh sách yêu cầu phê duyệt (lọc theo `status`, `requestType`, `makerId`, phân trang). |
| `API-APR-02` | `GET /api/v1/admin/approvals/{approvalId}` | `OPERATIONS`, `ADMIN` | Xem chi tiết một yêu cầu phê duyệt. |
| `API-ADM-01`<br>`API-ADM-02` | `GET /api/v1/admin/customers/{customerId}`<br>`GET /api/v1/admin/customers/{customerId}/accounts` | `OPERATIONS`, `ADMIN`, `AUDITOR` (`CUSTOMER_READ` / `ACCOUNT_READ`) | Tra cứu hồ sơ và danh sách tài khoản của khách hàng phục vụ vận hành/tra soát/kiểm toán (gọi qua `CustomerQuery` và `AccountQuery`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §5.3, §12.1)

Module `admin` đóng vai trò điều phối ứng dụng quản trị và gọi tới các Port của các module khác:
1. **`StepUpChallengePort`** (của module `identity`): Phát hành và tiêu thụ challenge `SENSITIVE_ACTION` (`X-Step-Up-Challenge`) khi Maker/Checker thực hiện thao tác nhạy cảm (trả về `428 STEP_UP_REQUIRED` nếu chưa có challenge hợp lệ).
2. **`IdentityRoleCommand`** (của module `identity` — đã triển khai tại `BE-ID-IMP-11` / `DEC-ID-011`): Đọc `getRoleAssignmentVersion` và gọi `applyApprovedRoleChange` (`Propagation.MANDATORY`) để cập nhật role/privilege, tăng `role_assignment_version` và thu hồi phiên đăng nhập trong cùng giao dịch phê duyệt.
3. **`LimitConfigMutationPort`** (của module `limit`): Kích hoạt cấu hình hạn mức mới trong cùng giao dịch khi duyệt `LIMIT_CHANGE`.
4. **`CustomerQuery` & `AccountQuery`** (của module `customer` và `account`): Phục vụ tra cứu hồ sơ và tài khoản khách hàng (`API-ADM-01/02`).
5. **`AuditModuleApi`** (của module `audit`): Ghi nhận `LIMIT_CHANGE_REQUESTED/APPROVED/REJECTED` (`C2`), `ROLE_CHANGE_REQUESTED/APPROVED/REJECTED` (`C2`), và `SECURITY_ACCESS_DENIED` (`C3`).

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Chốt đặc tả phối hợp tích hợp giữa `admin`, `fraud`, `audit` và `identity` (`BE-ADM-01`, `DEC-ID-011`). Phía `identity` đã hoàn tất `IdentityRoleCommandService` và kiểm thử 45 kịch bản phân quyền/thu hồi phiên (`BE-ID-IMP-11`).
2. [ ] Hiện thực migration `V9` (phần bảng `banking.approval_request`), `ApprovalRequestEntity` và `ApprovalJdbcRepository` với khóa `SELECT ... FOR UPDATE NOWAIT` (`design/02` Pattern A.14).
3. [ ] Hiện thực `MakerCheckerApprovalService` cưỡng chế kiểm tra `maker_user_id != checker_user_id`, kiểm tra trạng thái `PENDING`, tích hợp `StepUpChallengePort` (`SENSITIVE_ACTION`), `IdentityRoleCommand` và `LimitConfigMutationPort` (`design/02` Pattern B.7).
4. [ ] Hiện thực các REST Controller quản trị (`API-LIM-02/03`, `API-ROL-01/02`, `API-APR-01/02`, `API-ADM-01/02`) và bộ kiểm thử tích hợp `AC-028` & `CT-API-016` (Maker tự duyệt nhận `409 ROLE_CONFLICT`; Checker thiếu Step-up nhận `428`; hai Checker bấm duyệt cùng lúc chỉ 1 người thành công nhờ `FOR UPDATE NOWAIT`).

**Tài liệu chi tiết module `admin`:**
- [Chỉ mục Module Admin](../admin/README.md)
- [BE-ADM-01 — Đề xuất & Hợp đồng Tích hợp Admin – Fraud – Audit](../admin/BE-ADM-01-fraud-audit-integration.md) ([Báo cáo kiểm chứng](../admin/BE-ADM-01-verification-report.md))
