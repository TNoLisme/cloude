# Phạm vi và bản đồ chức năng FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-SCOPE |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / BA, Backend, QA |

## 1. Trách nhiệm

FE cung cấp UI tiếng Việt cho Customer, Operations, Admin và Auditor, điều hướng theo quyền, validation sớm, tích hợp API, hiển thị kết quả server và phục hồi UI khi mạng/phiên gặp lỗi. FE cùng QA kiểm chứng trình duyệt, responsive, masking và bàn giao build.

BE sở hữu quyết định số dư, ledger, hạn mức, auth, quyền và trạng thái giao dịch. FE hiển thị asOf khi được trả và refetch sau mutation. Notification được thể hiện trong kết quả giao dịch/challenge và nội dung masked từ server. Inbox notification theo GAP-10.

## 2. Bản đồ phạm vi đích

Nguồn là [[digitalbanking-docs/docs-common/03. SRS.md]]. Các ID FR/UC/AC dưới đây giữ nguyên từ SRS. Phạm vi legacy theo GAP-01, Must/Should và Fraud Option B theo GAP-11.

| Đặc tả | Đầu ra FE | FR | UC / AC |
|---|---|---|---|
| [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] | Login, trusted device, MFA/step-up, session/logout | 001–007, 026, 040, 042–043 | UC-001–003, 015 / AC-001–004, 019, 024, 033 |
| [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] | Hồ sơ, account list/detail, số dư | 008–012, 039–040, 052 | UC-004 / AC-005–006, 023–024, 029 |
| [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] | Own/intra-bank transfer, challenge, receipt, recovery | 013–026, 034, 038, 041, 043–046 | UC-005–008, 014–015 / AC-006–014, 018, 022, 025–026, 033–034 |
| [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] | List/filter/detail và tra soát theo quyền | 023–024, 027–030, 039–040 | UC-008–009 / AC-014–016, 023–024 |
| [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]] | Xem hạn mức, tạo/duyệt limit change | 031–033, 035, 051 | UC-011 / AC-017, 019, 028 |
| [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] | Đọc customer/account, xem/review flag | 044–049, 039–040 | UC-010 / AC-023–024, 026–027 |
| [[digitalbanking-docs/docs-frontend/features/FE-07-audit.md]] | Audit search/detail/export và correlation | 034–038, 039–040, 052–053 | UC-012 / AC-018–024, 029 |
| [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] | Assign/revoke role, grant/revoke privilege qua approval | 035, 040, 050–052 | UC-013 / AC-019, 024, 028–029 |

FR thuộc money movement, audit write, fraud engine, retention được truy vết tới UI quan sát và bằng chứng phối hợp BE. FE test xác minh phần UI, BE/QA cung cấp chứng minh invariant hệ thống.

## 3. Phạm vi xuyên suốt

| Đầu ra | NFR | Thiết kế |
|---|---|---|
| App shell, feature boundary, route/state | 026–027 | [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]] |
| Form, responsive, bàn phím, tiếng Việt/VND/timezone | 028–029 | [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]] |
| Generated types, lỗi, retry, idempotency/cache | 006, 010–012, 026, 028 | [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] |
| Session, RBAC/ownership, PII/secrets, CSRF | 013, 017–020, 030, 032 | [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md]] |
| Test/CI, evidence, bundle, build/handover | 025–029, 032 | [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]] |

## 4. Ranh giới và phối hợp

- FE kiểm chứng hiển thị khi BE rollback/reconcile. BE/QA giữ evidence số dư/ledger/audit cho D1–D5.
- Cloud sau M4 theo [[digitalbanking-docs/docs-common/10. Master timelines.md]]. FE chuẩn bị build, SPA fallback, API routing và smoke checklist.
- Register/recovery/PIN/seed/block cần quyết định phạm vi GAP-01 và đầu việc triển khai tương ứng.
- Search customer/user, notification inbox, retention/legal hold và định nghĩa role đầy đủ cần contract được chốt trong tracker.

## 5. Nguồn thực thi

[[digitalbanking-docs/docs-frontend/management/02-delivery-plan.md]] xác định thứ tự và cổng chuyển tiếp. [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] giữ trạng thái. Các điều chỉnh sau refactor và điều kiện hoàn thành được ghi trong mục 2.1 của tracker.
