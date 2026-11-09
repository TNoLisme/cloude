# FE-08 — Quản trị vai trò và đặc quyền

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-08 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Admin tạo yêu cầu assign/revoke role hoặc grant/revoke CHECKER privilege. Checker có quyền tương ứng và khác maker duyệt thay đổi ROLE_CHANGE qua shared approval FE-05. Quản trị định nghĩa role/permission, tạo user và tìm user cần contract riêng theo GAP-03/10.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-035/040/050–052, UC-013, AC-019/024/028–029. Contract 07 §8.8 và [[05-limit-and-admin-approval]].

[[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-02 — CSRF và quyền]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-03 — Challenge và step-up dùng chung]].

## 3. Màn hình và điều hướng

ADMIN-ROLES và STAFF-APPROVAL theo requestType ROLE_CHANGE. Route/action yêu cầu ROLE_MANAGE theo matrix đã chốt. Checker cần CHECKER và khác maker. Selection targetUserId từ nguồn được duyệt theo GAP-03.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/ADMIN-ROLES.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/STAFF-APPROVAL-LIST.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/STAFF-APPROVAL-DETAIL.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Chọn targetUserId, operation và roleCode/privilegeCode theo schema.
2. Nhập reason, review tác động và tạo createRoleChange.
3. Xử lý 428 SENSITIVE_ACTION qua controller FE-01, binding theo GAP-08.
4. Nhận approval PENDING, link shared detail FE-05.
5. Checker đọc payload/maker/target, chọn APPROVE/REJECT với note và step-up khi yêu cầu.
6. approveRoleChange trả approval, refetch đến trạng thái server xác nhận.
7. Khi APPLIED, cập nhật dữ liệu đích/capability theo contract. Những request bị đổi quyền vẫn chịu 403 server và UI refetch session.

## 5. Trạng thái giao diện

Form editing/validation/submitting/challenge, approval list/detail theo FE-05. Stale quyền hoặc self-approve có lý do từ chối. APPLIED, APPROVED và PENDING có nhãn khác nhau. Thay đổi quyền giữa phiên có thông báo và điều hướng về vùng được phép.

## 6. Thành phần và dữ liệu

RoleChangeForm có discriminated schema theo operation. Shared ApprovalList/Detail/DecisionDialog nhập public interface FE-05. Query key tách ROLE_CHANGE với LIMIT_CHANGE. Role/privilege enum từ generated types, matrix capability tập trung.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| createRoleChange | POST /admin/roles |
| approveRoleChange | POST /admin/roles/{id}/approve |
| listApprovals | GET /admin/approvals |
| getApproval | GET /admin/approvals/{approvalId} |
| verifyStepUp | POST /auth/step-up/verify |

Operation ASSIGN_ROLE/REVOKE_ROLE dùng roleCode, GRANT_PRIVILEGE/REVOKE_PRIVILEGE dùng privilegeCode, targetUserId string. Approve path id là approvalId ROLE_CHANGE. Reason/note theo giới hạn YAML.

## 8. Validation và thông báo

Target ID/schema, operation/enum hợp lệ, required field theo nhánh request. Review rõ target, quyền thêm/bớt và reason. Mọi POST dùng CSRF và challenge header theo contract. Lỗi 403/409/428 theo shared flow. Self-approve được xử lý cả UI và server.

## 9. Trường hợp đặc biệt

Cùng target có nhiều request cần đọc state server. Double submit khóa request, mất mạng khi tạo approval tra list trước retry theo GAP-08. Checker thay decision cần challenge binding phù hợp. Khi quyền phiên hiện tại bị thu hồi cần getSession/403 handling và clear scoped cache.

## 10. Tiêu chí nghiệm thu

FET-12 chứng minh 4 operation, maker/checker tách người, 428 verify/retry, APPROVE/REJECT, stale/concurrent decision và APPLIED. FET-13 cho direct URL/CSRF/capability đổi. BE audit evidence cho AC-019/028, dữ liệu allowlist cho AC-029.

## 11. Liên kết triển khai

FE-08-01 trong tracker, phụ thuộc FE-05-02. FE-08-01 bổ sung form role/privilege và áp dụng policy dùng chung với FE-FND-02. Kết quả triển khai mới theo FET-12.

## 12. Vấn đề cần chốt

GAP-03 tìm target user, GAP-07 matrix và session capability, GAP-08 binding/retry admin. Định nghĩa role/permission và quản trị user đầy đủ thuộc OI-API-10/18 cần BA/BE chốt trước mở rộng.
