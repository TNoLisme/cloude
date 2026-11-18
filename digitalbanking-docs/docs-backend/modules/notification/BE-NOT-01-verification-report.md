# BE-NOT-01 — Báo cáo Kiểm chứng Transactional Outbox, Delivery & Simulator

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-NOTIFICATION-01-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã kiểm chứng thành phần (`Component Verified` — 72 tests Notification + ArchUnit PASS trên PostgreSQL 16 Testcontainers)
> **Đặc tả công việc:** [BE-NOT-01-transactional-outbox.md](BE-NOT-01-transactional-outbox.md)

---

## 1. Ma trận Kiểm chứng (`Verification Matrix`)

| Hạng mục kiểm tra | Bài kiểm thử / Thủ tục | Kết quả mong đợi | Kết quả thực tế | Trạng thái |
|---|---|---|---|---|
| Kiểm tra PII allowlist & tính bất biến của Payload | `NotificationPayloadValidatorTest` (44 tests) | Chặn tuyệt đối số tài khoản chưa che dấu (kể cả có dấu cách/gạch ngang), email, SĐT, mã OTP, cấu trúc lồng và template code chứa PII; bản sao payload bất biến sâu | **44/44 tests PASS** | **Verified** |
| Chính sách thử lại theo hàm mũ (`Exponential Backoff`) | `NotificationRetryPolicyTest` (3 tests) | Tính chính xác $2^{k-1} \times \text{baseBackoff}$ (trần hệ số `64x`, không tràn số) và ánh xạ mã lỗi an toàn (`GATEWAY_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `DELIVERY_EXHAUSTED`) | **3/3 tests PASS** | **Verified** |
| Cấu hình kích hoạt Simulator an toàn (`Explicit Opt-In`) | `NotificationConfigurationTest` (7 tests) | Simulator không tự bật ngầm ở profile mặc định; chỉ đăng ký khi opt-in tường minh; worker chỉ khởi tạo khi có bean `NotificationDeliveryPort` | **7/7 tests PASS** | **Verified** |
| Lưu trữ JPA & MapStruct Mapper | `NotificationJpaRepositoryIT` (3 tests), `NotificationMapperTest` (1 test) | Ánh xạ hai chiều `NotificationEntity` ↔ `NotificationItem`, lưu cột `payload_masked JSONB` qua `@JdbcTypeCode(SqlTypes.JSON)` và kiểm tra cô lập giao dịch | **4/4 tests PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Rollback giao dịch `T2` không để lại thông báo mồ côi | `NotificationTransactionalOutboxIT.testT2RollbackLeavesNoOutboxRows` | Khi giao dịch `T2` bị rollback, bảng `banking.notification` có `0` dòng được lưu | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Đua chiếm lô đồng thời (`FOR UPDATE SKIP LOCKED`) | `NotificationTransactionalOutboxIT.testConcurrentBatchClaimSkipLocked` | Hai kết nối DB độc lập gọi `claimBatch` cùng lúc phân chia các dòng không trùng lặp; không xảy ra tranh chấp cùng một thông báo | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Cô lập lỗi Gateway ở Pha `T3` (`AC-025`) | `NotificationTransactionalOutboxIT.testT3WorkerGatewayFailureDoesNotAffectT2` | Khi cổng gửi ở `T3` báo lỗi, trạng thái giao dịch `T2` đã commit giữ nguyên; bản ghi outbox chuyển sang `RETRYING` | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Thu hồi Lease hết hạn & Chặn Stale Worker bằng `claim_token` | `testLeaseExpiryAllowsReclaimByAnotherWorker` & `testStaleWorkerCannotOverwriteAfterLeaseExpired` | Sau khi lease `locked_until` hết hạn, worker mới thu hồi và gửi thành công; worker cũ hoàn tất muộn bị từ chối bởi điều kiện `WHERE claim_token = ?` (`0` dòng cập nhật) | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Chuyển sang `FAILED` khi chạm ngưỡng thử lại tối đa & chống vòng lặp Crash | `retryReachesFailedAtMaxAttempts` & `repeatedCrashReclaimExceedingMaxAttemptsReachesTerminalFailed` | Sau khi vượt quá `maxAttempts`, bản ghi chuyển dứt điểm sang `FAILED` (`DELIVERY_EXHAUSTED`) và không gọi lại gateway | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Chặn gọi Worker bên trong giao dịch đang giữ khóa | `workerRejectsActiveCallerTransaction` & `deliverItemRejectsActiveCallerTransactionHoldingLock` | `processBatch` và `deliverItem` ném `IllegalStateException` ngay lập tức nếu luồng gọi đang mở giao dịch DB, bảo đảm không bao giờ giữ khóa DB qua cuộc gọi mạng | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Kiểm tra ranh giới kiến trúc Hexagonal (`ArchUnit`) | `ModuleBoundaryArchTest` (`notificationApplicationMustNotDependOnOutboundAdapters`) | `NotificationOutboxService` và `NotificationOutboxWorker` chỉ phụ thuộc vào `NotificationPersistencePort` / `NotificationDeliveryPort`, không import `adapter.out` | **PASS** | **Verified** |

---

## 2. Lệnh Tái lập Kiểm thử

Từ thư mục `digitalbanking-backend/` (yêu cầu Java 21 và Docker cho Testcontainers):

```bash
./gradlew test \
  --tests '*NotificationConfigurationTest' \
  --tests '*NotificationPayloadValidatorTest' \
  --tests '*NotificationRetryPolicyTest' \
  --tests '*NotificationTransactionalOutboxIT' \
  --tests '*NotificationJpaRepositoryIT' \
  --tests '*NotificationMapperTest' \
  --tests '*ModuleBoundaryArchTest'
```

- **Kết quả:** `BUILD SUCCESSFUL` — **72 tests Notification + 10 rules ArchUnit PASS** (55 unit/wiring tests + 17 PostgreSQL 16 Testcontainers integration tests), `0` thất bại, `0` lỗi, `0` bỏ qua.

---

## 3. Phân tích Thiết kế Persistence & Chuẩn hóa Kiến trúc (`BE-ARCH-00` §3, §4, §6)

| Thành phần / Phương thức | Bản chất thao tác | Công nghệ áp dụng | Lý do kỹ thuật | Bài kiểm thử |
|---|---|---|---|---|
| `insert(NotificationItem)`, `findById(long)` (`NotificationJpaRepository`) | CRUD thông thường (chèn thông báo trong `T2` và tra cứu theo PK) | **Spring Data JPA + MapStruct** | Chuyển đổi qua `NotificationMapper` và lưu `NotificationEntity` với `@JdbcTypeCode(SqlTypes.JSON)` cho cột `payload_masked`. | `NotificationTransactionalOutboxIT`, `NotificationJpaRepositoryIT`, `NotificationMapperTest` |
| `claimBatch`, `markSent`, `markRetryOrFailed` (`NotificationJdbcRepository`) | Kiểm soát đồng thời và hàng đợi PostgreSQL | **Native SQL / `JdbcTemplate`** | Cần truy vấn con nguyên tử `FOR UPDATE SKIP LOCKED` kết hợp `UPDATE ... RETURNING` để cấp lease (`claim_token`, `locked_until`) và cập nhật có điều kiện `WHERE notification_id = ? AND claim_token = ?`. | `NotificationTransactionalOutboxIT` |
| `NotificationPersistencePort` & `NotificationDeliveryPort` (`application.port`) | Đảo ngược phụ thuộc (`Dependency Inversion`) | **Hexagonal Outbound Ports** | Tách biệt hoàn toàn tầng `application` khỏi `adapter.out`; lớp cấu hình `NotificationConfiguration` đặt tại gói `notification.configuration`. | `NotificationConfigurationTest`, `ModuleBoundaryArchTest` |

---

## 4. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.10` | 11–15/11/2026 | Triển khai `NotificationPayloadValidator`, `NotificationRetryPolicy`, `NotificationOutboxService`, `NotificationOutboxWorker` và kiểm thử PostgreSQL Testcontainers. |
| `v0.11`–`v0.14` | 15/11/2026 | Hoàn thiện `NotificationEntity`, `NotificationJpaRepository`, `NotificationMapper`, `NotificationPersistencePort`, `NotificationDeliveryPort` và rule ArchUnit (72 tests Notification PASS). |
| `v1.0` | 18/11/2026 | Chuẩn hóa báo cáo tiếng Việt, loại bỏ đường dẫn máy cá nhân và đồng bộ quyết định `DEC-COM-001`, `DEC-ID-006`, `DEC-TRF-011`. |
