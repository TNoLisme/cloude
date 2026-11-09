# Module 10: `admin` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-10`
> **Module:** `com.simulatedbank.banking.admin`
> **Owner chính:** **Đạt** (**Thịnh** đồng phát triển phần tích hợp fraud/audit & review; Phối hợp: Vũ — `limit`, Thắng — FE)
> **Mốc tập trung:** **Tuần 5 (W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-032, FR-033, FR-049..051, AC-028), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.8), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.5), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.6, §11.9, §11.10, §11.11), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§A.14, §B.7), [[digitalbanking-docs/docs-common/design/04. security.md]] (§5.3)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `admin` cung cấp các chức năng vận hành dành cho nhân viên ngân hàng (`OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`) và điều phối quy trình phê duyệt 4 mắt (**Maker-Checker / Dual Control**) cho các thay đổi cấu hình nhạy cảm: thay đổi hạn mức giao dịch (`LIMIT_CHANGE`), thay đổi phân quyền nhân viên (`ROLE_CHANGE`) và phê duyệt giải phóng giữ pháp lý (`LEGAL_HOLD`).

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `admin` sở hữu độc quyền bảng **`banking.approval_request`**:

| Cột chính | Kiểu & Ràng buộc | Quy tắc & Bất biến Maker-Checker |
|---|---|---|
| `approval_id` | `UUID` / `BIGINT` (`PK`) | Định danh yêu cầu phê duyệt. |
| `request_type` | `CHECK IN ('LIMIT_CHANGE','ROLE_CHANGE', ...)` | Loại thay đổi nhạy cảm cần kiểm soát kép. |
| `target_type`, `target_id` | `VARCHAR NOT NULL` | Đối tượng chịu tác động (`LIMIT_CONFIG`, `USER_ROLE`, ...). |
| `payload` | `JSONB NOT NULL` | Chi tiết đề xuất thay đổi (chứa cả snapshot trước và giá trị đề xuất mới). |
| `status` | `CHECK IN ('PENDING','APPROVED','REJECTED','CANCELLED','EXPIRED')` | Trạng thái vòng đời yêu cầu phê duyệt. |
| `maker_id`, `maker_reason` | `NOT NULL FK -> app_user` | Nhân viên khởi tạo đề xuất (`Maker`) và lý do đề xuất. |
| `checker_id`, `checker_reason` | `NULL FK -> app_user` | Nhân viên phê duyệt/từ chối (`Checker`) và lý do quyết định. |
| `step_up_challenge_id` | `NULL FK -> auth_challenge` | Challenge Step-up (`purpose = 'SENSITIVE_ACTION'`) bắt buộc khi Checker ra quyết định. |
| `created_at`, `decided_at`, `expires_at` | `TIMESTAMPTZ` | Thời điểm tạo, quyết định và hết hạn. |

- **Database Constraint bảo vệ nguyên tắc 4 mắt (`INV-MC-01`):** `CHECK (checker_id IS NULL OR maker_id <> checker_id)`.
- **Partial Unique Index chống đề xuất trùng lặp:** Chỉ cho phép tối đa 1 yêu cầu `status = 'PENDING'` trên cùng một `(request_type, target_type, target_id)`.

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.6, §11.9–11.11)

| Mã API | Method & Path | Quyền truy cập (`STAFF`) | Hành vi cốt lõi |
|---|---|---|---|
| `API-LIM-02` | `POST /api/v1/admin/limits` | `LIMIT_PROPOSE` (`Maker`) | Tạo yêu cầu `approval_request (PENDING)` cho `LIMIT_CHANGE`, ghi audit `LIMIT_CHANGE_REQUESTED` (`C2`). |
| `API-LIM-03` | `POST /api/v1/admin/limits/{id}/approve` | `LIMIT_APPROVE` (`Checker`) | Kiểm tra `maker_id <> checker_id` + tiêu thụ Step-up challenge (`SENSITIVE_ACTION`), gọi `LimitConfigMutationPort` áp dụng hạn mức mới và cập nhật `APPROVED`/`REJECTED`. |
| `API-ROL-01` | `POST /api/v1/admin/roles` | `ROLE_PROPOSE` (`Maker`) | Tạo yêu cầu `approval_request (PENDING)` cho `ROLE_CHANGE`, ghi audit `ROLE_CHANGE_REQUESTED` (`C2`). |
| `API-ROL-02` | `POST /api/v1/admin/roles/{id}/approve` | `ROLE_APPROVE` (`Checker`) | Kiểm tra `maker_id <> checker_id` + tiêu thụ Step-up challenge, gọi `IdentityRoleCommandPort` cập nhật quyền + thu hồi session của user đích. |
| `API-APR-01` | `GET /api/v1/admin/approvals` | `APPROVAL_READ` | Tra cứu danh sách yêu cầu phê duyệt (lọc theo `status`, `requestType`, `makerId`). |
| `API-APR-02` | `GET /api/v1/admin/approvals/{approvalId}` | `APPROVAL_READ` | Xem chi tiết một yêu cầu phê duyệt. |
| `API-ADM-01`<br>`API-ADM-02` | `GET /api/v1/admin/customers/{customerId}`<br>`GET /api/v1/admin/customers/{customerId}/accounts` | `CUSTOMER_READ`<br>`ACCOUNT_READ` | Tra cứu hồ sơ và danh sách tài khoản của khách hàng phục vụ vận hành/tra soát (gọi qua `CustomerQueryPort` và `AccountQueryPort`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

Module `admin` đóng vai trò điều phối ứng dụng quản trị và gọi tới các Port của 4 module khác:
1. **`StepUpChallengePort`** (của module `identity`): Kiểm tra và tiêu thụ challenge `SENSITIVE_ACTION` khi Checker gọi `/approve` (trả về `428 STEP_UP_REQUIRED` nếu chưa có challenge hợp lệ).
2. **`LimitConfigMutationPort`** (của module `limit`): Kích hoạt cấu hình hạn mức mới trong cùng transaction khi duyệt `LIMIT_CHANGE`.
3. **`IdentityRoleCommandPort`** (của module `identity`): Cập nhật role nhân viên và thu hồi phiên đăng nhập trong cùng transaction khi duyệt `ROLE_CHANGE`.
4. **`AuditModuleApi`** (của module `audit`): Ghi nhận `LIMIT_CHANGE_REQUESTED/APPROVED/REJECTED` (`C2`), `ROLE_CHANGE_REQUESTED/APPROVED/REJECTED` (`C2`), và `SECURITY_ACCESS_DENIED` (`C3` khi Maker cố tình tự duyệt yêu cầu của chính mình).

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực `ApprovalRequestEntity`, `ApprovalJdbcRepository` với khóa `SELECT ... FOR UPDATE NOWAIT` (`design/02` Pattern A.14).
2. [ ] Hiện thực `MakerCheckerApprovalService` cưỡng chế kiểm tra `maker_id != checker_id`, kiểm tra trạng thái `PENDING` và tích hợp Step-up challenge (`design/02` Pattern B.7).
3. [ ] Hiện thực các Controller quản trị cho Limit, Role, Approvals và Customer/Account lookup.
4. [ ] Viết bộ test kiểm chứng `AC-028` & `CT-API-016` (Maker tự gọi `/approve` bị chặn với `403 MAKER_CHECKER_VIOLATION`; Checker thiếu Step-up nhận `428`; hai Checker bấm duyệt cùng lúc chỉ 1 người thành công nhờ `FOR UPDATE NOWAIT`).
