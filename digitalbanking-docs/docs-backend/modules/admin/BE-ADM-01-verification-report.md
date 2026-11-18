# BE-ADM-01 — Báo cáo Kiểm chứng Tích hợp Admin – Fraud – Audit

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-ADMIN-01-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã lên kế hoạch (`Planned` — các component `identity`, `audit`, `fraud` đã kiểm chứng độc lập; chờ ghép orchestrator `admin`)
> **Đặc tả công việc:** [BE-ADM-01-fraud-audit-integration.md](BE-ADM-01-fraud-audit-integration.md)

---

## 1. Ma trận Kiểm chứng (`Verification Matrix`)

| Hạng mục kiểm tra | Thủ tục / Bài kiểm thử | Kết quả mong đợi | Kết quả hiện tại | Trạng thái |
|---|---|---|---|---|
| Đối chiếu ma trận phân quyền RBAC | Rà soát theo `07` §3.4, `OpenAPI` và `DEC-ID-001` | 4 role chuẩn (`CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR`) + đặc quyền `CHECKER` khớp hoàn toàn giữa DB seed `V2` và `SecurityFilterChainConfig` | Đã kiểm chứng trên PostgreSQL (`RbacAuthorityPostgreSqlIntegrationTest`, `IdentityTransportSecurityIntegrationTest`) | **Verified (Phía Identity/Common)** |
| Hợp đồng `IdentityRoleCommand` & thu hồi phiên | `RoleChangePostgreSqlIntegrationTest` (45 tests) | Kiểm tra `maker != checker`, đối chiếu `expectedVersion`, tăng `role_assignment_version` và thu hồi toàn bộ phiên `ACTIVE` trong cùng giao dịch | 45/45 tests PASS trên PostgreSQL 16 Testcontainers (`BE-ID-IMP-11`) | **Verified (Phía Identity)** |
| Đua thẩm định cờ gian lận (`Fraud Review Race`) | `FraudFlagIT.concurrentReviewOneTerminalWinner` | Hai nhân viên review đồng thời trên cùng 1 cờ; `FOR UPDATE NOWAIT` đảm bảo đúng 1 quyết định thành công và 1 phản hồi `409 FRAUD_FLAG_ALREADY_CLOSED` | PASS trên PostgreSQL 16 Testcontainers (`BE-FRD-01`) | **Verified (Phía Fraud)** |
| Tính nguyên tử của kiểm toán `C2` (`Audit Atomicity`) | Testcontainers PostgreSQL + tiêm lỗi ghi audit | Khi ghi audit `C2` thất bại, toàn bộ thay đổi trạng thái `approval_request` / `fraud_flag` / `user_role` bị rollback | Đã kiểm chứng ở mức component (`AuditWriterIT`); chờ ghép nối trực tiếp vào `FraudReviewService` và `AdminApprovalService` | **Partial / Chờ ghép nối** |
| Luồng Maker-Checker đầu-cuối trên REST API `admin` | Kiểm thử tích hợp HTTP `POST /api/v1/admin/roles` & `/limits` (`AC-028`, `CT-API-016`) | Maker tự duyệt bị chặn (`409 ROLE_CONFLICT`); Checker thiếu `X-Step-Up-Challenge` nhận `428`; áp dụng thành công chuyển `approval_request` sang `APPLIED` | Chờ hiện thực controller/orchestrator của module `admin` | **Planned** |

---

## 2. Điều kiện Hoàn tất (`Exit Criteria`)

Báo cáo này chuyển sang trạng thái **Verified** toàn phần sau khi module `admin` hoàn tất bảng `approval_request` (migration `V9`), ghép nối `MakerCheckerApprovalService` với `IdentityRoleCommand`, `LimitConfigMutationPort`, `StepUpChallengePort`, `AuditModuleApi` và vượt qua toàn bộ bộ kiểm thử tích hợp `CT-API-016` / `AC-028`.
