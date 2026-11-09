# FE-05 — Hạn mức và quy trình phê duyệt

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-05 |
| Phiên bản / ngày | 1.0 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Customer xem hạn mức giao dịch/ngày và phần còn lại. Staff có quyền tạo limit change. Checker khác maker duyệt/từ chối qua cơ chế approval dùng lại cho FE-08. UI phân biệt yêu cầu được tạo, được duyệt và đã áp dụng.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-031–033/035/051, UC-011, AC-017/019/028. Contract 07 §8.5/8.8 và [[digitalbanking-docs/docs-backend/modules/05-limit-and-admin-approval.md]].

[[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-02 — CSRF và quyền]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-03 — Challenge và step-up dùng chung]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-RETRY-01 — Retry và kết quả chưa rõ]].

## 3. Màn hình và điều hướng

CUST-LIMITS, STAFF-LIMIT, STAFF-APPROVAL. Customer đọc self, limit maker theo permission và approval theo loại request. CHECKER là privilege bổ sung. Route detail dùng approvalId.

## 4. Luồng người dùng

1. Customer gọi getLimits, hiển thị items theo CUSTOMER/ACCOUNT, effective limits và usageDate/asOf.
2. Maker chọn customer/account scope, nhập perTransactionLimit/dailyLimit/effectiveFrom/reason và review.
3. createLimitChange nhận approval PENDING, step-up nếu 428 theo binding được chốt.
4. Staff mở listApprovals/getApproval, đọc payload đích và maker.
5. Checker đủ quyền và khác maker chọn APPROVE/REJECT, review tác động rồi submit approveLimitChange.
6. 428 dùng shared step-up, retry request/decision đã khóa với X-Step-Up-Challenge.
7. Refetch approval và limit, hiển thị APPLIED khi server xác nhận applied. Quyền giữ nguyên qua mỗi request server.

## 5. Trạng thái giao diện

Limit loading/empty/error, form editing/submitting/challenge. Approval hiển thị PENDING, APPROVED, REJECTED, APPLIED, CANCELLED từ server. Các trạng thái chưa đủ điều kiện hành động hiển thị read-only và lý do. 409 stale/conflict refetch detail.

## 6. Thành phần và dữ liệu

LimitSummary, LimitChangeForm, ApprovalList, ApprovalDetail, DecisionDialog. Approval public API nhận requestType và operation phù hợp. FE-08 dùng lại phần list/detail/challenge/decision, giữ payload nghiệp vụ role riêng. Query keys gồm scope/requestType/approvalId.

## 7. Tương tác API

| operationId | Method / path | Ghi chú |
|---|---|---|
| getLimits | GET /limits | Customer self, optional account scope theo YAML |
| createLimitChange | POST /admin/limits | LimitChangeRequest |
| approveLimitChange | POST /admin/limits/{id}/approve | id là approvalId LIMIT_CHANGE |
| listApprovals | GET /admin/approvals | Filter/page theo YAML |
| getApproval | GET /admin/approvals/{approvalId} | Payload/result |
| verifyStepUp | POST /auth/step-up/verify | SENSITIVE_ACTION |

LimitChangeRequest bắt buộc customerId/perTransactionLimit/dailyLimit, accountId tùy chọn. Currency và effectiveFrom theo schema. LimitApprovalRequest có decision APPROVE/REJECT và decisionNote tối đa 500.

## 8. Validation và thông báo

Money theo API-DATA-01, các giới hạn chính sách phải lấy từ contract/BE đã chốt. Form kiểm tra quan hệ giá trị theo rule nguồn, server quyết định hạn mức hợp lệ. Hiển thị usedToday/remainingDaily do server trả, tách nullable effective limits khỏi 0.

## 9. Trường hợp đặc biệt

Maker mở tab checker vẫn bị server từ chối tự duyệt. Hai checker đồng thời xử lý 409 bằng refetch. Đổi decision/payload cần challenge hợp lệ mới. Mất mạng khi tạo approval tra list trước gửi lại theo GAP-08. Sau approval APPLIED invalidate limits và session capability nếu có tác động.

## 10. Tiêu chí nghiệm thu

FET-09 chứng minh customer chỉ xem self, tạo PENDING, checker khác maker, APPROVE/REJECT, step-up, stale request và trạng thái applied. Gắn evidence backend một lần áp dụng và audit nhạy cảm cho AC-017/019/028. FET-13 kiểm tra direct URL và CSRF.

## 11. Liên kết triển khai

FE-05-01 và FE-05-02 trong tracker. Cơ chế approval là public interface dùng lại tại FE-08. Evidence mới gắn FET-09 và AC-028.

## 12. Vấn đề cần chốt

GAP-07 về quyền/checker, GAP-08 về binding khi create và retry approval, GAP-11 về phạm vi milestone. Policy max/default đang đề xuất ở 07 được chốt bởi BA/BE.
