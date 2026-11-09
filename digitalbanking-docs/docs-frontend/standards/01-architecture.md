# Kiến trúc và quy ước FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-ARCH |
| Phiên bản / ngày | 1.0 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Nguồn và ranh giới

Nguồn công nghệ/kiến trúc là [[digitalbanking-docs/docs-common/05. Technical stack.md]] và [[digitalbanking-docs/docs-common/06. System architecture.md]]. Kiến trúc đích là React SPA + TypeScript strict + Vite + React Router, feature modules và shared foundation. UI/toolchain migration theo ADR-001, trạng thái tại tracker.

## FE-ARCH-01 — Cấu trúc source

~~~text
digitalbanking-frontend/
  src/
    app/                 # Bootstrap, providers, router, route policy
    features/
      auth/
      accounts/
      transfers/
      history/
      limits/
      operations/
      audit/
      administration/
    shared/
      api/               # Transport, generated types, errors
      auth/              # Session metadata, policy, challenge primitives
      ui/                # Tokens, form/table/dialog/state components
      lib/               # Money, time, safe formatters
    test/                # Setup, factories, MSW handlers
  e2e/
  scripts/
~~~

Feature chứa pages/components/api/model/tests theo nhu cầu thực tế. App nhập public exports của feature. Feature nhập shared và public interface được mô tả của feature khác. Shared giữ primitive và không phụ thuộc feature. Các quy trình chung như approval đặt module dùng chung có owner tại FE-05, FE-08 nhập public interface thay vì sao chép flow.

## FE-ARCH-02 — Quyền sở hữu state

| Dữ liệu | Nơi giữ | Vòng đời |
|---|---|---|
| Accounts/history/limits/audit/flags/approvals | TanStack Query | Theo user/scope/filters, xóa khi đổi phiên |
| Bộ lọc/page/sort | URL search params cho giá trị đã cho phép | Reload/back khôi phục, PII theo FE-SEC-03 |
| Form và validation | React Hook Form + Zod theo stack đích | Reset hoặc giữ dữ liệu an toàn theo flow |
| UI state như dialog/tab | Component state | Theo component |
| Session metadata/CSRF | Auth provider hoặc store memory | Bootstrap/logout/expiry |
| Transfer intent/key, challenge proof | Feature/controller memory | Theo intent và session, reload theo GAP-09 |

Zustand chỉ dùng khi có nhiều consumer cần chia sẻ UI state. Dữ liệu suy ra được tính từ nguồn sở hữu. Query cache giữ DTO đọc đã cho phép, mutation variables có secret/PII phải được giảm vòng đời và xóa theo FE-SEC-03.

## FE-ARCH-03 — Route và điều hướng

Route sau là đề xuất đích, giữ nguyên mã screen khi thay đường dẫn. App có một route policy tập trung. Feature specs tham chiếu các mã screen bên dưới.

| Screen | Route đề xuất | Đối tượng và đặc tả |
|---|---|---|
| AUTH-LOGIN | /login | Public, FE-01 |
| AUTH-CHALLENGE | /auth/challenge | Challenge context hợp lệ, FE-01 |
| CUST-HOME | /customer | CUSTOMER, FE-02 |
| CUST-PROFILE | /customer/profile | CUSTOMER, FE-02 |
| CUST-ACCOUNT | /customer/accounts/:accountId | CUSTOMER và ownership, FE-02 |
| CUST-TRANSFER | /customer/transfers/new | CUSTOMER + TRANSFER_CREATE, FE-03 |
| CUST-RESULT | /customer/transfers/:reference | CUSTOMER và ownership, FE-03 |
| CUST-HISTORY | /customer/history | CUSTOMER, FE-04 |
| CUST-TX | /customer/history/:reference | CUSTOMER và ownership, FE-04 |
| CUST-LIMITS | /customer/limits | CUSTOMER, FE-05 |
| OPS-CUSTOMER | /staff/customers/:customerId | Theo CUSTOMER_READ, FE-06 |
| OPS-FRAUD | /staff/fraud | Theo FRAUD_READ/FRAUD_REVIEW được chốt, FE-06 |
| STAFF-TX | /staff/transactions | Theo TRANSACTION_READ, FE-04 |
| STAFF-AUDIT | /staff/audit | Theo AUDIT_READ, FE-07 |
| STAFF-LIMIT | /staff/limits | Theo LIMIT_MAKER/LIMIT_MANAGE, FE-05 |
| STAFF-APPROVAL | /staff/approvals và /staff/approvals/:approvalId | Theo quyền loại yêu cầu, FE-05/FE-08 |
| ADMIN-ROLES | /admin/roles | ADMIN + ROLE_MANAGE, FE-08 |
| ERROR | /forbidden và wildcard | 403/404 và đường quay lại hợp lệ |

Role/permission chính thức theo 07 §3 và GAP-07. Quyền kiểm tra cả menu, direct URL và action. CHECKER là privilege bổ sung. Router chờ bootstrap trước khi quyết định redirect. Return URL chỉ nhận đường dẫn nội bộ trong allowlist.

## FE-ARCH-04 — App lifecycle

Bootstrap gọi getSession → lưu metadata/CSRF → tải capability theo matrix đã chốt → vào layout phù hợp. Anonymous chuyển login. Network error cho retry bootstrap. Session expired theo FE-AUTH-01. Logout hủy request đang chạy, xóa query cache/intent/secret, phát tín hiệu invalidation đa tab và về login.

Public auth, customer và staff có loading/error boundary. Lazy load staff/feature lớn theo route. Deploy phải hỗ trợ deep link và fallback index.html theo local guide. Build graph/circular dependency được review khi chuyển source.

## 2. Nguồn dùng chung

- [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]].
- [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md]].
- [[digitalbanking-docs/docs-frontend/decisions/ADR-001-frontend-migration-baseline.md]].
