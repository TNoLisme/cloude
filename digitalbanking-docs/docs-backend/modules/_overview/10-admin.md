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

Module `admin` sở hữu độc quyền bảng **`banking.approval_request`**. **Nguồn chuẩn (canonical) là `docs-common/04. Database design & analysis.md` §4.5.3; tài liệu này đã được đối chiếu và xóa bỏ các cột/trạng thái chỉ có trong bản nháp overview.**

| Cột chính | Kiểu & Ràng buộc | Quy tắc & Bất biến Maker-Checker |
|---|---|---|
| `approval_id` | `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | Định danh yêu cầu phê duyệt. API serialize dạng chuỗi `ID`. |
| `request_type` | `VARCHAR(32) NOT NULL CHECK ('LIMIT_CHANGE','ROLE_CHANGE','RETENTION_HOLD','RETENTION_POLICY_CHANGE')` | Loại thay đổi nhạy cảm cần kiểm soát kép. |
| `target_type` | `VARCHAR(32) NOT NULL CHECK ('CUSTOMER','ACCOUNT','APP_USER','DATA_SCOPE','RETENTION_POLICY')` | Loại đối tượng chịu tác động. |
| `target_id` | `VARCHAR(64) NOT NULL CHECK (length 1–64)` | ID đối tượng chịu tác động. |
| `payload` | `JSONB NOT NULL` | Chi tiết đề xuất thay đổi theo `request_type`; không chứa secret/full PII. |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK ('PENDING','APPROVED','REJECTED','APPLIED','CANCELLED')` | Trạng thái vòng đợi yêu cầu phê duyệt. |
| `maker_id` | `BIGINT NOT NULL FK -> app_user` | Nhân viên khởi tạo đề xuất (`Maker`). |
| `checker_id` | `BIGINT NULL FK -> app_user` | Nhân viên phê duyệt/từ chối (`Checker`). |
| `decision_note` | `VARCHAR(500) NULL` | Lý do quyết định của Checker (hoặc lý do đề xuất do Maker ghi trong `payload`). |
| `correlation_id` | `VARCHAR(64) NOT NULL` | Trace ID xuyên request → approval → provider → audit. |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | Thờ điểm tạo. |
| `decided_at` | `TIMESTAMPTZ NULL` | Thờ điểm quyết định; bắt buộc với `APPROVED`/`REJECTED`/`APPLIED`. |
| `applied_at` | `TIMESTAMPTZ NULL` | Thờ điểm áp dụng; bắt buộc với `APPLIED`. |

**Những cột KHÔNG có trong schema chuẩn (đã loại bỏ khỏi overview):**
- `UUID` cho `approval_id`: canonical chọn `BIGINT` theo `04` §4.5.3.
- `EXPIRED` status: canonical 5 giá trị; hết hạn challenge do `auth_challenge.expires_at` kiểm soát, không phải trạng thái approval.
- `expires_at` trên `approval_request`: không có trong `04` §4.5.3.
- `step_up_challenge_id` FK trên `approval_request`: challenge binding được thực hiện qua `auth_challenge.idempotency_key`/`request_hash` (Checker bind `approvalId` + decision hash) theo `BE-ADM-CON-02/03`; không thêm cột FK vào bảng Admin.
- `maker_reason`/`checker_reason`: lý do đề xuất nằm trong `payload`; lý do quyết định là cột `decision_note`.

- **Database Constraint bảo vệ nguyên tắc 4 mắt (`INV-MC-01`):** `CHECK (checker_id IS NULL OR maker_id <> checker_id)`.
- **Constraint bất biến timestamp:** `status IN ('APPROVED','REJECTED','APPLIED')` ⇒ `checker_id IS NOT NULL AND decided_at IS NOT NULL`; `status='APPLIED'` ⇒ `applied_at IS NOT NULL`.
- **Partial Unique Index chống đề xuất trùng lặp:** Chưa được duyệt (`ADM-OI-03`); cần DB owner phê duyệt trước khi thêm vào migration. Hiện tại race được xử lý bằng `SELECT … FOR UPDATE` và idempotency key scope (`BE-ADM-CON-03`).

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

1. [x] Hiện thực `ApprovalRequestEntity`/`ApprovalRequestJpaRepository`/`JpaApprovalRequestStore` với `SELECT ... FOR UPDATE` **chờ** (`PESSIMISTIC_WRITE`, không `NOWAIT`/`SKIP LOCKED`) theo `DEC-ADM-005`; lock timeout `55P03` map `503`.
2. [x] Hiện thực `ApprovalDecisionService` cưỡng chế `maker_id != checker_id`, trạng thái `PENDING`, Maker–Checker/self-target, Step-up `SENSITIVE_ACTION` single-use và provider JOIN.
3. [x] Hiện thực controller `RoleAdminController`, `LimitAdminController`, `ApprovalAdminController` và các route provider-owned Customer/Account theo `07`/OpenAPI.
4. [x] Viết bộ test `AC-028`: `AdminAc028AcceptancePostgreSqlIntegrationTest` 4 test (approve → áp dụng role/limit; reject → không áp dụng; maker = checker bị chặn `409 ROLE_CONFLICT`; audit C2/C3), cùng `ApprovalStepUpPostgreSqlIntegrationTest` (428, race hai checker) và `AdminSecurityMatrixPostgreSqlIntegrationTest`. Ghi nhận: canonical dùng `409 ROLE_CONFLICT`, không phải `403 MAKER_CHECKER_VIOLATION`; lock chờ, không `NOWAIT` (`DEC-ADM-005`).
