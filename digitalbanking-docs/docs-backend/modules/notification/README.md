# Module `notification` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-NOTIFICATION-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Outbox thật đã được kiểm chứng trong T2 theo BE-INT-06/07. Policy, delivery, `OtpGateway` và `CustomerQuery` tiếp tục ở BE-INT-08.
> **Owner:** Thịnh
> **Phối hợp / Reviewer:** Vũ (`transfer`), Đạt (`identity`, `customer`), Phương (`common`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-041..043, NFR-008, AC-025), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7.3), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.5, §10), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.4, §10.4, Phụ lục A.10, A.13, B.6), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.3), [[digitalbanking-docs/docs-backend/modules/_overview/09-notification.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-ID-006`, `DEC-TRF-011`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.notification` quản lý hàng đợi thông báo theo mẫu **Transactional Outbox (`T2` enqueue $\rightarrow$ `T3` async worker delivery)** trên bảng `banking.notification` và cung cấp cổng gửi mã OTP đồng bộ sau khi commit (`OtpGateway`) cho module `identity`. Module đảm bảo mọi thông báo lưu trong cơ sở dữ liệu đều đã được che dấu PII (`payload_masked`), và mọi sự cố của cổng gửi bên ngoài ở pha `T3` không bao giờ làm rollback giao dịch chuyển tiền đã `COMPLETED` (`AC-025`).

---

## 2. Hiện trạng Triển khai trong `digitalbanking-backend/src/main/java/com/simulatedbank/banking/notification`

Đã triển khai **19 classes Java** theo đúng kiến trúc Hexagonal (`00. architecture & conventions.md` §4.2) và đạt **72 tests PASS** (unit + PostgreSQL 16 Testcontainers):
- **`api/` (2 interfaces):** `NotificationOutboxPort` (chèn thông báo trong `T2`), `OtpGateway` (gửi mã OTP đồng bộ ngoài transaction sau khi `T0` commit theo `DEC-ID-006`).
- **`domain/` (7 classes):** `NotificationChannel` (`IN_APP`, `EMAIL`, `SMS`), `NotificationType` (`TRANSFER_RESULT`, `OTP_DELIVERY`, `SECURITY_ALERT`), `NotificationStatus` (`PENDING`, `SENT`, `FAILED`, `RETRYING`, `CANCELLED`), `TransferNotice`, `NotificationItem`, `NotificationPayloadValidator` (44 unit tests kiểm soát chặt chẽ PII allowlist), `NotificationRetryPolicy` (hàm mũ tất định $2^{k-1} \times \text{baseBackoff}$).
- **`application/` & `application/port/` & `configuration/` (6 classes):** `NotificationOutboxService` (`@Transactional(propagation = MANDATORY)`), `NotificationOutboxWorker` (quét `SKIP LOCKED`, lease recovery, fencing token, chặn chạy trong caller transaction đang mở), `NotificationOutboxMetrics`, `NotificationPersistencePort`, `NotificationDeliveryPort`, `NotificationConfiguration`.
- **`adapter/out/` & `adapter/out/persistence/` (5 classes):** `NotificationJdbcRepository` (`claimBatch` với `FOR UPDATE SKIP LOCKED`, `markSent` và `markRetryOrFailed` có điều kiện theo `claim_token`), `SimulatedNotificationGateway`, `NotificationEntity`, `NotificationJpaRepository`, `NotificationMapper` (MapStruct).

---

## 3. Bảng Sở hữu & Hợp đồng Giao tiếp Nội bộ

`NotificationEntity` lưu `payload_masked` khi insert và coi cột này là bất biến trong JPA. Trace BE-INT-07 đã xác minh commit T2 chỉ thực hiện SQL Audit sau khi lấy khóa chuỗi. [Kiểm chứng PostgreSQL](../../workofteam/integration%20modules/BE-INT-07-verification.md)

- **Bảng sở hữu độc quyền (`Write-Owner`):** `banking.notification` (`04` §4.7.3, migration `V10` theo `DEC-COM-001` & `DEC-TRF-011`) với `customer_id`, `transfer_id`, `auth_challenge_id`, `notification_type`, `channel`, `template_code`, `payload_masked JSONB NOT NULL`, `status IN ('PENDING','SENT','FAILED','RETRYING','CANCELLED')`, `UNIQUE (transfer_id, customer_id, notification_type, channel)`.
- **Tham số vận hành chuẩn (`DEC-TRF-011`):** Chu kỳ quét outbox **2 giây** (`2000 ms`), kích thước lô **50 bản ghi**, thời hạn thuê (`lease`) **30 giây**, thử lại tối đa **5 lần**; giải mã địa chỉ nhận tạm thời ngay trước khi gửi qua `CustomerQuery.resolveNotificationDestination`.

---

## 4. Danh mục Tài liệu & Báo cáo Kiểm chứng trong `modules/notification/`

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [Tóm tắt Module 09: `notification`](../_overview/09-notification.md) | Tổng quan phạm vi, bảng `notification`, hợp đồng `NotificationOutboxPort` / `OtpGateway` và tiến trình worker `T3`. | Hoàn thành |
| [BE-NOT-01 — Transactional Outbox, Delivery và Simulator](BE-NOT-01-transactional-outbox.md) | Đặc tả chi tiết kiến trúc Outbox `T2`/`T3`, kiểm soát lease/fencing token, chính sách retry backoff, bảo vệ PII và ranh giới gửi OTP. | Đã triển khai component |
| [BE-NOT-01 — Báo cáo Kiểm chứng](BE-NOT-01-verification-report.md) | Kết quả kiểm chứng 72 tests (unit, MapStruct, JPA CRUD và 14 kịch bản concurrency/lease/rollback trên PostgreSQL 16 Testcontainers). | Component Verified |
