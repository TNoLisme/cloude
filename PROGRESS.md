# Tiến độ Dự án (`PROGRESS.md`)

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Ngày cập nhật:** 18/11/2026
> **Giai đoạn hiện tại:** Hoàn tất chuẩn hoá bộ tài liệu kỹ thuật Baseline v1.0 & Phát triển độc lập các module; sẵn sàng cho pha ghép tích hợp liên module (`Integration Merge`).

---

## 1. Tổng quan Trạng thái Dự án

- **Tài liệu kỹ thuật (`digitalbanking-docs/`):**
  - `docs-common/01–11`: **Hoàn thành (v1.0)** — Đã chuẩn hoá toàn bộ yêu cầu nghiệp vụ (`BRD`), đặc tả hệ thống (`SRS`), thiết kế cơ sở dữ liệu (`Database Design`), ngăn xếp công nghệ (`Technical Stack`), kiến trúc hệ thống (`System Architecture`), hợp đồng API (`API Contracts`), hạ tầng triển khai (`Deployment Infrastructure`), kế hoạch kiểm thử (`Test Plan`), lộ trình (`Master Timelines`) và phân công nhân sự (`Resource Plan`).
  - `docs-common/design/01–05`: **Hoàn thành (v1.0)** — Đã đồng bộ hoàn toàn thiết kế chuyên sâu về Audit (`01`), Consistency & Concurrency (`02`), Fraud Detection Rules (`03`), Security (`04`) và Hợp đồng giao tiếp nội bộ giữa 11 module (`05. Modules contract`).
  - `openapi/digital-banking-v1.yaml`: **Hoàn thành (v1.0.1)** — Đã đồng bộ ngữ nghĩa HTTP status codes (`201`/`422` cho idempotency replay), headers và schemas với `07. API contracts.md`.
  - `docs-backend/`: **Hoàn thành (v1.0)** — Đã chuẩn hoá chuẩn kiến trúc (`00. architecture & conventions.md`), cấu hình vận hành (`deployment/`), tổng quan 11 module (`modules/_overview/`) và tài liệu chi tiết từng module (`modules/<module>/`).
  - `docs-frontend/`: **Hoàn thành (v1.2)** — Đã chốt baseline thiết kế màn hình và đặc tả tính năng Frontend.

---

## 2. Trạng thái Hiện thực 11 Module Backend (`digitalbanking-backend/`)

| # | Module | Owner | Trạng thái Hiện thực | Chi tiết Thành phần Đã Hoàn thành & Công việc Cần Ghép Tích hợp |
|---:|---|---|---|---|
| 01 | `identity` | Đạt | **Đã triển khai Core & REST (75 classes)** | Hoàn thành 5 lớp (`api`, `adapter/in`, `adapter/out`, `application`, `domain`), đăng nhập mật khẩu + MFA (`LOGIN_NEW_DEVICE`), FIDO2/WebAuthn (`Yubico`), quản lý phiên opaque (`DB_SESSION` + CSRF rotation), `StepUpChallengePort`, `IdentityRoleCommand`, rate limit theo username và kiểm thử PostgreSQL/bảo mật/hiệu năng. Chờ nối adapter thật của `AuditModuleApi`, `OtpGateway` và `CustomerQuery`. |
| 02 | `customer` | Đạt | **Đang phát triển (Skeleton)** | Đã chốt đặc tả bảng `banking.customer` (`04` §4.2.2) và hợp đồng `CustomerQuery` (`findByUserId`, `getCustomerView`, `resolveNotificationDestination` tại `design/05` §6). Các module phụ thuộc đang kiểm thử qua mock/port theo tài liệu giao tiếp. |
| 03 | `account` | Đạt | **Đang phát triển (Skeleton)** | Đã chốt đặc tả bảng `banking.account` (`04` §4.3.1) và hợp đồng `AccountQuery`, `AccountBalance` (`lockTransferAccounts`, `debitConditional`, `credit`), `AccountReconciliationQuery` (`design/05` §7 & §9). |
| 04 | `transfer` | Vũ | **Đã triển khai Candidate (51 classes)** | Đã hoàn thành toàn bộ logic điều phối `TransferCreateOrchestrator`, `TransferCreateService`, `TransferQueryService`, `TransferPreflightCandidate`, `TransferIntentCandidate` (`T1`), `TransferSettlementCandidate` (`T2`), `TransferRecoveryCandidate` và bộ kiểm thử hợp đồng (`BE-TRF-01..07`). Chờ kích hoạt component-scan sau khi ghép provider `account`, `limit`, `ledger`, `audit`. |
| 05 | `ledger` | Phương | **Đã triển khai Candidate (16 classes)** | Đã hoàn thành `LedgerPosting` (`postDoubleEntry` `MANDATORY`), `PairVerifier` (native read-back kiểm chứng cặp `DEBIT`/`CREDIT`), `LedgerEntryEntity` và migration append-only (`BE-LED-01..03`). Chờ bổ sung `LedgerQuery.findEvidence` và ghép vào giao dịch `T2`. |
| 06 | `limit` | Vũ | **Đang phát triển (Skeleton)** | Đã chốt đặc tả `limit_config`, `limit_usage`, `limit_reservation` và hợp đồng `lockStepUpGuard`, `evaluateStepUpUnderGuard`, `reserveForPendingTransfer`, `lockAndCheck`, `settleOrReleaseReservation` (`design/05` §10). |
| 07 | `audit` | Thịnh | **Đã triển khai Component (17 classes)** | Đã hoàn thành `AuditRecorder` (`C1`/`C2` `MANDATORY`), `AuditJdbcWriter` (khóa tư vấn `hashtextextended('audit:chain', 0)` và tính chuỗi băm JCS SHA-256), `AuditQueryService`, `AuditChainVerifier` (`BE-AUD-01/02`). Chờ công bố port `audit.api.AuditModuleApi` (gồm `recordStandalone` `C3`), controller REST và job retention (`BE-AUD-03`). |
| 08 | `fraud` | Thịnh | **Đã triển khai Component (24 classes)** | Đã hoàn thành `FraudRuleEvaluator` (`LARGE_VALUE`, `HIGH_FREQUENCY`, `UNUSUAL_PATTERN`), `FraudReviewService`, `FraudJdbcRepository` và JPA entities (`BE-FRD-01`). Chờ công bố port `fraud.api.FraudEvaluation` (kèm cô lập savepoint `T2`) và controller REST thẩm định cờ. |
| 09 | `notification` | Thịnh | **Đã triển khai Component (19 classes)** | Đã hoàn thành `NotificationOutboxPort`, `OtpGateway`, `NotificationOutboxService`, `NotificationOutboxWorker` (`SKIP LOCKED`) và `SimulatedNotificationGateway` (`BE-NOT-01`). Cần chuyển `TransferNotice` sang `notification.api.dto` và nối `CustomerQuery.resolveNotificationDestination`. |
| 10 | `admin` | Đạt & Thịnh | **Đang phát triển (Skeleton)** | Đã chốt đặc tả bảng `banking.approval_request` (`04` §4.5.3) và luồng điều phối phê duyệt 4 mắt (`Maker-Checker`) gọi `IdentityRoleCommand` / `Limit` / `Audit` (`design/05` §12.1, `BE-ADM-01`). |
| 11 | `common` | Phương | **Hoàn thành (30 classes)** | Đã hoàn thành toàn bộ hạ tầng dùng chung trong 5 sub-packages (`config`, `context`, `crypto`, `error`, `logging`): `SessionAuthenticator` SPI, CSRF filter, Rate Limiter, `PiiCryptoService` (`AES-256-GCM`), `HmacService`, `JcsCanonicalizer`, `PiiMaskingUtils`, `GlobalExceptionHandler` (RFC 9457), `SafeJsonLogEncoder` và `LogRedaction`. |

---

## 3. Danh mục Cần Xử lý Khi Ghép Tích hợp (`Pre-Merge Integration Checklist`)

1. **Đồng bộ đánh số Flyway Migration (`DEC-COM-001`):**
   - Hiện tại trong `src/main/resources/db/migration/` đang có 2 tệp trùng số hiệu `V2` (`V2__create_identity_and_rbac_tables.sql` và `V2__create_audit_log.sql`), đồng thời SQL của `transfer` và `ledger` đang đặt tại `db/transfer-candidate/` và `db/ledger-candidate/`.
   - Khi ghép nhánh code, đánh số lại theo chuẩn `DEC-COM-001` (`V1` schema → `V2..V4` identity → `V5` audit → `V6` customer/account → `V7` transfer/idempotency → `V8` ledger → `V9` limit/approval → `V10` fraud/notification) để `Flyway` và `spring.jpa.hibernate.ddl-auto: validate` khởi chạy thông suốt trên toàn bộ ứng dụng.
2. **Bổ sung quyền `CUSTOMER_READ` và `FRAUD_READ` cho `AUDITOR` trong seed `V2` (`DEC-ID-001`):**
   - Cập nhật danh sách `role_permission` của role `AUDITOR` trong `V2__create_identity_and_rbac_tables.sql` để khớp hoàn toàn với ma trận phân quyền tại `04` Appendix A.2 và `07` §3.4.
3. **Chuẩn hoá package DTO của `notification.api` (`DEC-NOT-001`):**
   - Di chuyển `TransferNotice` (hoặc tạo DTO tương ứng) vào `com.simulatedbank.banking.notification.api` để `NotificationOutboxPort` không import trực tiếp `notification.domain`.
4. **Hoàn thiện các module `customer`, `account`, `limit`, `admin` và nối dây (`wiring`) các port:**
   - Hiện thực các provider còn thiếu theo đúng chữ ký tại `docs-common/design/05. Modules contract.md`.
   - Thay thế các test double/adapter tạm (`SecurityLogAuditAdapter`, consumer seams của `transfer`) bằng bean provider thật và chạy bộ kiểm thử nghiệm thu `D1–D5` theo `09. Test plan.md`.
